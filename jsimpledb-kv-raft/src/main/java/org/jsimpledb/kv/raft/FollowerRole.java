
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.kv.raft;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.jcip.annotations.GuardedBy;

import org.dellroad.stuff.io.ByteBufferOutputStream;
import org.jsimpledb.kv.KVTransactionException;
import org.jsimpledb.kv.RetryTransactionException;
import org.jsimpledb.kv.mvcc.Reads;
import org.jsimpledb.kv.mvcc.Writes;
import org.jsimpledb.kv.raft.msg.AppendRequest;
import org.jsimpledb.kv.raft.msg.AppendResponse;
import org.jsimpledb.kv.raft.msg.CommitRequest;
import org.jsimpledb.kv.raft.msg.CommitResponse;
import org.jsimpledb.kv.raft.msg.GrantVote;
import org.jsimpledb.kv.raft.msg.InstallSnapshot;
import org.jsimpledb.kv.raft.msg.Message;
import org.jsimpledb.kv.raft.msg.PingRequest;
import org.jsimpledb.kv.raft.msg.PingResponse;
import org.jsimpledb.kv.raft.msg.RequestVote;

/**
 * Raft follower role.
 */
public class FollowerRole extends NonLeaderRole {

    @GuardedBy("raft")
    private String leader;                                                          // our leader, if known
    @GuardedBy("raft")
    private String leaderAddress;                                                   // our leader's network address
    @GuardedBy("raft")
    private String votedFor;                                                        // the candidate we voted for this term
    @GuardedBy("raft")
    private SnapshotReceive snapshotReceive;                                        // in-progress snapshot install, if any
    @GuardedBy("raft")
    private final HashSet<RaftKVTransaction> pendingRequests = new HashSet<>();     // waiting for CommitResponse from leader
    @GuardedBy("raft")
    private final HashMap<Long, PendingWrite> pendingWrites = new HashMap<>();      // wait for AppendRequest with null data
    @GuardedBy("raft")
    private final HashMap<Long, Timestamp> commitLeaderLeaseTimeoutMap              // tx's waiting for leaderLeaseTimeout's
      = new HashMap<>();
    @GuardedBy("raft")
    private Timestamp lastLeaderMessageTime;                                        // time of most recent rec'd AppendRequest
    @GuardedBy("raft")
    private Timestamp leaderLeaseTimeout;                                           // latest rec'd leader lease timeout
    @GuardedBy("raft")
    private HashMap<String, Timestamp> probeTimestamps;                             // used only when probing majority

// Constructors

    FollowerRole(RaftKVDatabase raft) {
        this(raft, null, null, null);
    }

    FollowerRole(RaftKVDatabase raft, String leader, String leaderAddress) {
        this(raft, leader, leaderAddress, leader);
    }

    FollowerRole(RaftKVDatabase raft, String leader, String leaderAddress, String votedFor) {
        super(raft, raft.isClusterMember());
        this.leader = leader;
        this.leaderAddress = leaderAddress;
        this.votedFor = votedFor;
        assert this.leaderAddress != null || this.leader == null;
    }

// Status

    /**
     * Get the identity of my leader, if known.
     *
     * @return leader identity, or null if not known
     */
    public String getLeaderIdentity() {
        synchronized (this.raft) {
            return this.leader;
        }
    }

    /**
     * Get the address of my leader, if known.
     *
     * @return leader address, or null if not known
     */
    public String getLeaderAddress() {
        synchronized (this.raft) {
            return this.leaderAddress;
        }
    }

    /**
     * Get the identity of the node that this node voted for this term, if any.
     *
     * @return node voted for, or null if none
     */
    public String getVotedFor() {
        synchronized (this.raft) {
            return this.votedFor;
        }
    }

    /**
     * Determine whether this node is currently in the process of receiving a whole database snapshot download.
     *
     * @return true if snapshot install is in progress
     */
    public boolean isInstallingSnapshot() {
        synchronized (this.raft) {
            return this.snapshotReceive != null;
        }
    }

    /**
     * Determine the number of nodes (including this node) that this node has successfully probed when probing
     * for a majority of nodes with {@link PingRequest}s prior to reverting to a candidate.
     *
     * @return the number of other nodes this node has successfully probed, or -1 if not probing
     */
    public int getNodesProbed() {
        synchronized (this.raft) {
            return this.probeTimestamps != null ? this.calculateProbedNodes() : -1;
        }
    }

// Probing mode

    private int calculateProbedNodes() {
        assert Thread.holdsLock(this.raft);
        assert this.probeTimestamps != null;
        int numProbed = this.raft.isClusterMember() ? 1 : 0;
        final Timestamp now = new Timestamp();
        for (Iterator<Timestamp> i = this.probeTimestamps.values().iterator(); i.hasNext(); ) {
            final Timestamp timestamp = i.next();
            if (now.offsetFrom(timestamp) >= this.raft.maxElectionTimeout) {                // timestamp is too old, discard
                i.remove();
                continue;
            }
            numProbed++;
        }
        return numProbed;
    }

// Lifecycle

    @Override
    void setup() {
        assert Thread.holdsLock(this.raft);
        super.setup();
        if (this.log.isDebugEnabled()) {
            this.debug("entering follower role in term " + this.raft.currentTerm
              + (this.leader != null ? "; with leader \"" + this.leader + "\" at " + this.leaderAddress : "")
              + (this.votedFor != null ? "; having voted for \"" + this.votedFor + "\"" : ""));
        }
    }

    @Override
    void shutdown() {
        assert Thread.holdsLock(this.raft);

        // Cancel any in-progress snapshot install
        if (this.snapshotReceive != null) {
            if (this.log.isDebugEnabled())
                this.debug("aborting snapshot install due to leaving follower role");
            this.raft.discardFlipFloppedStateMachine();
            this.snapshotReceive = null;
        }

        // Fail any (read-only) transactions waiting on a minimum lease timeout from deposed leader
        new ArrayList<>(this.raft.openTransactions.values()).stream()
          .filter(tx -> tx.getState().equals(TxState.COMMIT_WAITING) && this.commitLeaderLeaseTimeoutMap.containsKey(tx.txId))
          .forEach(tx -> this.raft.fail(tx,
           new RetryTransactionException(tx, "leader was deposed during leader lease timeout wait")));

        // Fail any transactions that are waiting on leader response to a commit request and not based on the last log entry
        new ArrayList<>(this.raft.openTransactions.values()).stream()
          .filter(tx -> super.shouldRebase(tx)
            && (tx.baseIndex != this.raft.getLastLogIndex() || tx.baseTerm != this.raft.getLastLogTerm()))
          .forEach(tx -> {
            assert tx.getState().equals(TxState.COMMIT_READY) && this.pendingRequests.contains(tx);
            this.raft.fail(tx, new RetryTransactionException(tx, "leader was deposed before commit response received"));
        });

        // Cleanup pending requests and commit writes
        this.pendingRequests.clear();
        this.pendingWrites.values().forEach(PendingWrite::cleanup);
        this.pendingWrites.clear();

        // Proceed
        super.shutdown();
    }

// Service

    @Override
    void outputQueueEmpty(String address) {
        assert Thread.holdsLock(this.raft);
        if (address.equals(this.leaderAddress))
            this.raft.requestService(this.checkReadyTransactionsService);       // TODO: track specific transactions
    }

    // Check whether the required minimum leader lease timeout has been seen, if any
    @Override
    boolean mayCommit(RaftKVTransaction tx) {
        assert Thread.holdsLock(this.raft);

        // Is there a required minimum leader lease timeout associated with the transaction?
        final Timestamp commitLeaderLeaseTimeout = this.commitLeaderLeaseTimeoutMap.get(tx.txId);
        if (commitLeaderLeaseTimeout == null)
            return true;

        // Do we know the leader's lease timeout yet?
        if (this.leaderLeaseTimeout == null)
            return false;

        // Verify leader's lease timeout has extended beyond that required by the transaction
        return this.leaderLeaseTimeout.compareTo(commitLeaderLeaseTimeout) >= 0;
    }

    @Override
    void handleElectionTimeout() {
        assert Thread.holdsLock(this.raft);

        // Invalidate current leader
        this.leader = null;
        this.leaderAddress = null;

        // Is probing enabled? If not convert immediately into a candidate
        if (!this.raft.followerProbingEnabled) {
            if (this.log.isDebugEnabled())
                this.debug("follower election timeout: probing is disabled, so converting immediately to candidate");
            this.raft.changeRole(new CandidateRole(this.raft));
            return;
        }

        // If not probing, start probing; if already probing, then we never heard from a majority, so keep on trying
        if (this.probeTimestamps == null) {
            if (this.log.isDebugEnabled())
                this.debug("follower election timeout: attempting to probe a majority before becoming candidate");
            this.probeTimestamps = new HashMap<>(this.raft.currentConfig.size() - 1);
        }

        // Send out a(nother) round of probes to all other nodes
        final Timestamp now = new Timestamp();
        for (String peer : this.raft.currentConfig.keySet()) {
            if (peer.equals(this.raft.identity))
                continue;
            this.raft.sendMessage(new PingRequest(this.raft.clusterId, this.raft.identity, peer, this.raft.currentTerm, now));
        }

        // Restart election timeout (now it's really a ping timer)
        this.restartElectionTimer();

        // Handle the case where I am the only node
        this.checkProbeResult();
    }

    /**
     * Check whether the election timer should be running, and make it so.
     *
     * <p>
     * This should be invoked:
     * <ul>
     *  <li>After a log entry that contains a configuration change has been added to the log</li>
     *  <li>When a snapshot install starts</li>
     *  <li>When a snapshot install completes</li>
     * </ul>
     */
    private void updateElectionTimer() {
        assert Thread.holdsLock(this.raft);
        final boolean isClusterMember = this.raft.isClusterMember();
        final boolean electionTimerRunning = this.electionTimer.isRunning();
        if (isClusterMember && !electionTimerRunning) {
            if (this.log.isTraceEnabled())
                this.trace("starting up election timer because I'm now part of the current config");
            this.restartElectionTimer();
        } else if (!isClusterMember && electionTimerRunning) {
            if (this.log.isTraceEnabled())
                this.trace("stopping election timer because I'm no longer part of the current config");
            this.electionTimer.cancel();
        }
    }

// Transactions

    @Override
    void checkReadyLinearizableTransaction(RaftKVTransaction tx, boolean readOnly) {

        // Sanity check
        assert Thread.holdsLock(this.raft);
        assert tx.getState().equals(TxState.COMMIT_READY);

        // Did we already send a CommitRequest for this transaction?
        if (this.pendingRequests.contains(tx)) {
            if (this.log.isTraceEnabled())
                this.trace("leaving alone ready tx " + tx + " because request already sent");
            return;
        }

        // If we are installing a snapshot, we must wait
        if (this.snapshotReceive != null) {
            if (this.log.isTraceEnabled())
                this.trace("leaving alone ready tx " + tx + " because a snapshot install is in progress");
            return;
        }

        // Handle situation where we are unconfigured and not part of any cluster yet
        if (!this.raft.isConfigured()) {

            // Get transaction mutations
            final String[] configChange = tx.getConfigChange();

            // Allow an empty read-only transaction when unconfigured
            if (readOnly) {
                this.raft.succeed(tx);
                return;
            }

            // Otherwise, we can only handle an initial config change that is adding the local node
            if (configChange == null || !configChange[0].equals(this.raft.identity) || configChange[1] == null) {
                throw new RetryTransactionException(tx, "unconfigured system: an initial configuration change adding"
                  + " the local node (\"" + this.raft.identity + "\") as the first member of a new cluster is required");
            }

            // Create a new cluster if needed
            if (this.raft.clusterId == 0) {

                // Pick a new, random cluster ID
                int newClusterId;
                do
                    newClusterId = this.raft.random.nextInt();
                while (newClusterId == 0);

                // Join new cluster
                this.info("creating new cluster with ID " + String.format("0x%08x", newClusterId));
                if (!this.raft.joinCluster(newClusterId))
                    throw new KVTransactionException(tx, "error persisting new cluster ID");
            }

            // Advance term
            assert this.raft.currentTerm == 0;
            if (!this.raft.advanceTerm(this.raft.currentTerm + 1))
                throw new KVTransactionException(tx, "error advancing term");

            // Append the first entry to the Raft log
            final LogEntry logEntry;
            try {
                logEntry = this.raft.appendLogEntry(this.raft.currentTerm, new NewLogEntry(tx));
            } catch (Exception e) {
                throw new KVTransactionException(tx, "error attempting to persist transaction", e);
            }
            if (this.log.isDebugEnabled())
                this.debug("added log entry " + logEntry + " for local transaction " + tx);
            assert logEntry.getTerm() == 1;
            assert logEntry.getIndex() == 1;

            // Update transaction
            this.advanceReadyTransaction(tx, logEntry.getTerm(), logEntry.getIndex());

            // Rebase transactions
            this.rebaseTransactions();

            // Set commit term and index from new log entry
            this.raft.commitIndex = logEntry.getIndex();
            this.raft.requestService(this.triggerKeyWatchesService);

            // Immediately become the leader of our new single-node cluster
            assert this.raft.isConfigured();
            if (this.log.isDebugEnabled())
                this.debug("appointing myself leader in newly created cluster");
            this.raft.changeRole(new LeaderRole(this.raft));
            return;
        }

        // If we don't have a leader yet, or leader's queue is full, we must wait
        if (this.leader == null || this.raft.isTransmitting(this.leaderAddress)) {
            if (this.log.isTraceEnabled()) {
                this.trace("leaving alone ready tx " + tx + " because leader "
                  + (this.leader == null ? "is not known yet" : "\"" + this.leader + "\" is not writable yet"));
            }
            return;
        }

        // Serialize reads into buffer
        assert !readOnly || tx.getConsistency().isGuaranteesUpToDateReads();
        final Reads reads = tx.view.getReads();
        final long readsDataSize = reads.serializedLength();
        if (readsDataSize != (int)readsDataSize)
            throw new KVTransactionException(tx, "transaction read information exceeds maximum length");
        final ByteBuffer readsData = Util.allocateByteBuffer((int)readsDataSize);
        try (ByteBufferOutputStream output = new ByteBufferOutputStream(readsData)) {
            reads.serialize(output);
        } catch (IOException e) {
            throw new RuntimeException("unexpected exception", e);
        }
        assert !readsData.hasRemaining();
        readsData.flip();

        // Gather writes data (i.e., mutations), but only if transaction contains mutations
        ByteBuffer mutationData = null;
        if (!readOnly) {

            // Serialize mutations into a temporary file (but do not close or durably persist yet)
            final Writes writes = tx.view.getWrites();          // synchronization not req'd here because tx is COMMIT_READY
            final File file = new File(this.raft.logDir,
              String.format("%s%019d%s", RaftKVDatabase.TX_FILE_PREFIX, tx.txId, RaftKVDatabase.TEMP_FILE_SUFFIX));
            final FileWriter fileWriter;
            try {
                fileWriter = new FileWriter(file, this.raft.disableSync);
            } catch (IOException e) {
                throw new KVTransactionException(tx, "error saving transaction mutations to temporary file", e);
            }
            try {
                LogEntry.writeData(fileWriter, new LogEntry.Data(writes, tx.getConfigChange()));
                fileWriter.flush();
            } catch (IOException e) {
                Util.closeIfPossible(fileWriter);
                this.raft.deleteFile(fileWriter.getFile(), "pending write temp file");
                throw new KVTransactionException(tx, "error saving transaction mutations to temporary file", e);
            }

            // Load serialized writes from file
            final long writeLength = fileWriter.getLength();
            try {
                mutationData = Util.readFile(fileWriter.getFile(), writeLength);
            } catch (IOException e) {
                Util.closeIfPossible(fileWriter);
                this.raft.deleteFile(fileWriter.getFile(), "pending write temp file");
                throw new KVTransactionException(tx, "error reading transaction mutations from temporary file", e);
            }

            // Record pending commit write with temporary file
            final PendingWrite pendingWrite = new PendingWrite(tx, fileWriter);
            this.pendingWrites.put(tx.txId, pendingWrite);
        }

        // Record pending request
        assert !this.pendingRequests.contains(tx);
        this.pendingRequests.add(tx);

        // Send commit request to leader
        final CommitRequest msg = new CommitRequest(this.raft.clusterId, this.raft.identity, this.leader,
          this.raft.currentTerm, tx.txId, tx.baseTerm, tx.baseIndex, readsData, mutationData);
        if (this.log.isTraceEnabled())
            this.trace("sending " + msg + " to \"" + this.leader + "\" for " + tx);
        if (!this.raft.sendMessage(msg))
            throw new RetryTransactionException(tx, "error sending commit request to leader");
    }

    @Override
    void cleanupForTransaction(RaftKVTransaction tx) {
        assert Thread.holdsLock(this.raft);
        this.pendingRequests.remove(tx);
        final PendingWrite pendingWrite = this.pendingWrites.remove(tx.txId);
        if (pendingWrite != null)
            pendingWrite.cleanup();
        this.commitLeaderLeaseTimeoutMap.remove(tx.txId);
    }

// Messages

    @Override
    boolean mayAdvanceCurrentTerm(Message msg) {
        assert Thread.holdsLock(this.raft);

        // Deny vote if we have heard from our leader within the minimum election timeout (dissertation, section 4.2.3)
        if (msg instanceof RequestVote
          && this.lastLeaderMessageTime != null
          && this.lastLeaderMessageTime.offsetFromNow() > -this.raft.minElectionTimeout)
            return false;

        // OK
        return true;
    }

    @Override
    void caseAppendRequest(AppendRequest msg, NewLogEntry newLogEntry) {
        assert Thread.holdsLock(this.raft);

        // Cancel probing
        if (this.probeTimestamps != null) {
            if (this.log.isDebugEnabled())
                this.debug("heard from leader before we probed a majority, reverting back to normal follower");
            this.probeTimestamps = null;
        }

        // Record new cluster ID if we haven't done so already
        if (this.raft.clusterId == 0)
            this.raft.joinCluster(msg.getClusterId());

        // Record leader
        if (!msg.getSenderId().equals(this.leader)) {
            if (this.leader != null && !this.leader.equals(msg.getSenderId())) {
                this.error("detected a conflicting leader in " + msg + " (previous leader was \"" + this.leader
                  + "\") - should never happen; possible inconsistent cluster configuration (mine: " + this.raft.currentConfig
                  + ")");
            }
            this.leader = msg.getSenderId();
            this.leaderAddress = this.raft.returnAddress;
            this.leaderLeaseTimeout = null;
            if (this.log.isDebugEnabled())
                this.debug("updated leader to \"" + this.leader + "\" at " + this.leaderAddress);
            this.raft.requestService(this.checkReadyTransactionsService);     // allows COMMIT_READY transactions to be sent
        }

        // Get message info
        final long leaderCommitIndex = msg.getLeaderCommit();
        final long leaderPrevTerm = msg.getPrevLogTerm();
        final long leaderPrevIndex = msg.getPrevLogIndex();
        final long logTerm = msg.getLogEntryTerm();
        final long logIndex = leaderPrevIndex + 1;

        // Update timestamp last heard from leader
        this.lastLeaderMessageTime = new Timestamp();

        // Update leader's lease timeout
        if (msg.getLeaderLeaseTimeout() != null
          && (this.leaderLeaseTimeout == null || msg.getLeaderLeaseTimeout().compareTo(this.leaderLeaseTimeout) > 0)) {
            if (this.log.isTraceEnabled())
                this.trace("advancing leader lease timeout " + this.leaderLeaseTimeout + " -> " + msg.getLeaderLeaseTimeout());
            this.leaderLeaseTimeout = msg.getLeaderLeaseTimeout();
            this.raft.requestService(this.checkWaitingTransactionsService);
        }

        // If a snapshot install is in progress, cancel it
        if (this.snapshotReceive != null) {
            if (this.log.isDebugEnabled())
                this.debug("rec'd " + msg + " during in-progress " + this.snapshotReceive + "; aborting snapshot install");
            this.raft.discardFlipFloppedStateMachine();
            this.snapshotReceive = null;
            this.updateElectionTimer();
        }

        // Restart election timeout (if running)
        if (this.electionTimer.isRunning())
            this.restartElectionTimer();

        // Get my last log entry's index
        long lastLogIndex = this.raft.getLastLogIndex();

        // Check whether our previous log entry term matches that of leader; if not, or it doesn't exist, request fails
        // Note: if log entry index is prior to my last applied log entry index, Raft guarantees that term must match
        if (leaderPrevIndex >= this.raft.lastAppliedIndex
          && (leaderPrevIndex > lastLogIndex || leaderPrevTerm != this.raft.getLogTermAtIndex(leaderPrevIndex))) {
            if (this.log.isDebugEnabled())
                this.debug("rejecting " + msg + " because previous log entry doesn't match");
            this.raft.sendMessage(new AppendResponse(this.raft.clusterId, this.raft.identity, msg.getSenderId(),
              this.raft.currentTerm, msg.getLeaderTimestamp(), false, this.raft.lastAppliedIndex, this.raft.getLastLogIndex()));
            return;
        }

        // Check whether the message actually contains a log entry we can append; if so, append it
        boolean success = true;
        if (leaderPrevIndex >= this.raft.lastAppliedIndex && !msg.isProbe()) {

            // Check for a conflicting (i.e., never committed, then overwritten) log entry that we need to clear away first
            if (logIndex <= lastLogIndex && logTerm != this.raft.getLogTermAtIndex(logIndex)) {

                // Fail all rebasable transactions whose base log entry is being overwritten
                for (RaftKVTransaction tx : new ArrayList<>(this.raft.openTransactions.values())) {
                    if (this.shouldRebase(tx)) {
                        assert tx.baseIndex == lastLogIndex && tx.baseTerm == this.raft.getLogTermAtIndex(lastLogIndex);
                        this.raft.fail(tx, new RetryTransactionException(tx, "transaction's base log entry overwritten"));
                    }
                }

                // Delete conflicting log entry, and all entries that follow it, from the log
                final int startListIndex = (int)(logIndex - this.raft.lastAppliedIndex - 1);
                final List<LogEntry> conflictList = this.raft.raftLog.subList(startListIndex, this.raft.raftLog.size());
                for (LogEntry logEntry : conflictList) {
                    if (this.log.isDebugEnabled())
                        this.debug("deleting log entry " + logEntry + " overrwritten by " + msg);
                    this.raft.deleteFile(logEntry.getFile(), "overwritten log file");
                }
                try {
                    this.raft.logDirChannel.force(true);
                } catch (IOException e) {
                    this.warn("errory fsync()'ing log directory " + this.raft.logDir, e);
                }
                conflictList.clear();

                // Rebuild current config
                this.raft.currentConfig = this.raft.buildCurrentConfig();

                // Update last log entry index
                lastLogIndex = this.raft.getLastLogIndex();
            }

            // Append the new log entry - if we don't already have it
            if (logIndex > lastLogIndex) {
                assert logIndex == lastLogIndex + 1;
                LogEntry logEntry = null;
                do {

                    // If message contains no data, we expect to get the data from the corresponding transaction
                    if (newLogEntry == null) {

                        // Find the matching pending commit write, if any
                        final PendingWrite pendingWrite = this.pendingWrites.values().stream().filter(pw -> {
                            final RaftKVTransaction tx = pw.getTx();
                            return tx.getState().equals(TxState.COMMIT_WAITING)
                              && tx.getCommitTerm() == logTerm && tx.getCommitIndex() == logIndex;
                          }).findAny().orElse(null);
                        if (pendingWrite == null) {
                            if (this.log.isDebugEnabled()) {
                                this.debug("rec'd " + msg + " but no read-write transaction matching commit "
                                  + logIndex + "t" + logTerm + " found; rejecting");
                            }
                            break;
                        }

                        // Commit's writes are no longer pending
                        final RaftKVTransaction tx = pendingWrite.getTx();
                        this.pendingWrites.remove(tx.txId);

                        // Close and durably persist the associated temporary file
                        try {
                            pendingWrite.getFileWriter().close();
                        } catch (IOException e) {
                            this.error("error closing temporary transaction file for " + tx, e);
                            pendingWrite.cleanup();
                            break;
                        }

                        // Append a new log entry using temporary file
                        try {
                            logEntry = this.raft.appendLogEntry(logTerm,
                              new NewLogEntry(tx, pendingWrite.getFileWriter().getFile()));
                        } catch (Exception e) {
                            this.error("error appending new log entry for " + tx, e);
                            pendingWrite.cleanup();
                            break;
                        }

                        // Debug
                        if (this.log.isDebugEnabled()) {
                            this.debug("now waiting for commit of " + tx.getCommitIndex() + "t" + tx.getCommitTerm()
                              + " to commit " + tx);
                        }
                    } else {

                        // Append new log entry normally using the data from the request
                        try {
                            logEntry = this.raft.appendLogEntry(logTerm, newLogEntry);
                        } catch (Exception e) {
                            this.error("error appending new log entry", e);
                            break;
                        }
                    }
                } while (false);

                // Start/stop election timer as needed
                if (logEntry != null && logEntry.getConfigChange() != null)
                    this.updateElectionTimer();

                // Success?
                success = logEntry != null;

                // Rebase transactions
                if (success)
                    this.rebaseTransactions();

                // Update last log entry index
                lastLogIndex = this.raft.getLastLogIndex();
            }
        }

        // Update my commit index
        final long newCommitIndex = Math.min(Math.max(leaderCommitIndex, this.raft.commitIndex), lastLogIndex);
        if (newCommitIndex > this.raft.commitIndex) {
            if (this.log.isDebugEnabled())
                this.debug("updating leader commit index from " + this.raft.commitIndex + " -> " + newCommitIndex);
            this.raft.commitIndex = newCommitIndex;
            this.raft.requestService(this.checkWaitingTransactionsService);
            this.raft.requestService(this.triggerKeyWatchesService);
            this.raft.requestService(this.applyCommittedLogEntriesService);
        }

        // Debug
        if (this.log.isTraceEnabled()) {
            this.trace("my updated follower state: "
              + "term=" + this.raft.currentTerm
              + " commitIndex=" + this.raft.commitIndex
              + " leaderLeaseTimeout=" + this.leaderLeaseTimeout
              + " lastApplied=" + this.raft.lastAppliedIndex + "t" + this.raft.lastAppliedTerm
              + " log=" + this.raft.raftLog);
        }

        // Send reply
        if (success) {
            this.raft.sendMessage(new AppendResponse(this.raft.clusterId, this.raft.identity, msg.getSenderId(),
              this.raft.currentTerm, msg.getLeaderTimestamp(), true, msg.isProbe() ? logIndex - 1 : logIndex,
              this.raft.getLastLogIndex()));
        } else {
            this.raft.sendMessage(new AppendResponse(this.raft.clusterId, this.raft.identity, msg.getSenderId(),
              this.raft.currentTerm, msg.getLeaderTimestamp(), false, this.raft.lastAppliedIndex, this.raft.getLastLogIndex()));
        }
    }

    @Override
    void caseCommitResponse(CommitResponse msg) {
        assert Thread.holdsLock(this.raft);

        // Find transaction
        final RaftKVTransaction tx = this.raft.openTransactions.get(msg.getTxId());
        if (tx == null)                                                                 // must have been rolled back locally
            return;

        // Sanity check transaction state
        if (!tx.getState().equals(TxState.COMMIT_READY)) {
            if (this.log.isDebugEnabled())
                this.debug("rec'd " + msg + " for " + tx + " in state " + tx.getState() + "; ignoring");
            return;
        }
        if (!this.pendingRequests.remove(tx)) {
            if (this.log.isDebugEnabled())
                this.debug("rec'd " + msg + " for " + tx + " not expecting a response; ignoring");
            return;
        }

        // Check result
        if (this.log.isTraceEnabled())
            this.trace("rec'd " + msg + " for " + tx);
        if (msg.isSuccess()) {

            // Update transaction
            this.advanceReadyTransaction(tx, msg.getCommitTerm(), msg.getCommitIndex());

            // Track leader lease timeout we must wait for, if any
            if (msg.getCommitLeaderLeaseTimeout() != null)
                this.commitLeaderLeaseTimeoutMap.put(tx.txId, msg.getCommitLeaderLeaseTimeout());
        } else
            this.raft.fail(tx, new RetryTransactionException(tx, msg.getErrorMessage()));
    }

    @Override
    void caseInstallSnapshot(InstallSnapshot msg) {
        assert Thread.holdsLock(this.raft);

        // Restart election timer (if running)
        if (this.electionTimer.isRunning())
            this.restartElectionTimer();

        // Sanity check that our log is not going backwards
        if (msg.getSnapshotIndex() < this.raft.commitIndex) {
            this.warn("rec'd " + msg + " with retrograde index " + msg.getSnapshotIndex()
              + " < my commit index " + this.raft.commitIndex + ", ignoring");
            return;
        }

        // Do we have an existing install?
        boolean startNewInstall = false;
        if (this.snapshotReceive != null) {

            // Does the message not match?
            if (!this.snapshotReceive.matches(msg)) {

                // If the message is NOT the first one in a new install, ignore it
                if (msg.getPairIndex() != 0) {
                    if (this.log.isDebugEnabled())
                        this.debug("rec'd " + msg + " which doesn't match in-progress " + this.snapshotReceive + "; ignoring");
                    return;
                }

                // The message is the first one in a new install, so discard the existing install
                if (this.log.isDebugEnabled()) {
                    this.debug("rec'd initial " + msg + " with in-progress " + this.snapshotReceive
                      + "; aborting previous install");
                }
                startNewInstall = true;
            }
        } else {

            // If the message is NOT the first one in a new install, ignore it
            if (msg.getPairIndex() != 0) {
                if (this.log.isDebugEnabled())
                    this.debug("rec'd non-initial " + msg + " with no in-progress snapshot install; ignoring");
                return;
            }
        }

        // Get snapshot term and index
        final long term = msg.getSnapshotTerm();
        final long index = msg.getSnapshotIndex();

        // Set up new install if necessary
        if (this.snapshotReceive == null || startNewInstall) {
            assert msg.getPairIndex() == 0;
            if (this.raft.discardFlipFloppedStateMachine())
                this.warn("detected left-over content in flip-flopped state machine; discarding");
            this.updateElectionTimer();
            this.snapshotReceive = new SnapshotReceive(this.raft.kv,
              this.raft.getFlipFloppedStateMachinePrefix(), term, index, msg.getSnapshotConfig());
            if (this.log.isDebugEnabled()) {
                this.debug("starting new snapshot install from \"" + msg.getSenderId()
                  + "\" of " + index + "t" + term + " with config " + msg.getSnapshotConfig());
            }
        }
        assert this.snapshotReceive.matches(msg);

        // Apply next chunk of key/value pairs
        if (this.log.isDebugEnabled())
            this.debug("applying " + msg + " to " + this.snapshotReceive);
        try {
            this.snapshotReceive.applyNextChunk(msg.getData());
        } catch (Exception e) {
            this.error("error applying snapshot to key/value store; aborting snapshot install", e);
            this.snapshotReceive = null;
            this.raft.discardFlipFloppedStateMachine();
            this.updateElectionTimer();
            return;
        }

        // If that was the last chunk, finalize persistent state
        if (msg.isLastChunk()) {

            // Flip-flop state machine
            final Map<String, String> snapshotConfig = this.snapshotReceive.getSnapshotConfig();
            if (this.log.isDebugEnabled()) {
                this.debug("snapshot install from \"" + msg.getSenderId() + "\" of "
                  + index + "t" + term + " with config " + snapshotConfig + " complete");
            }
            this.snapshotReceive = null;
            this.raft.flipFlopStateMachine(term, index, snapshotConfig);
            this.updateElectionTimer();

            // Fail any rebasable transactions
            new ArrayList<>(this.raft.openTransactions.values()).stream()
              .filter(this::shouldRebase)
              .forEach(tx -> this.raft.fail(tx, new RetryTransactionException(tx, "rec'd snapshot install from leader")));
        }
    }

    @Override
    void caseRequestVote(RequestVote msg) {
        assert Thread.holdsLock(this.raft);

        // Record new cluster ID if we haven't done so already
        if (this.raft.clusterId == 0)
            this.raft.joinCluster(msg.getClusterId());

        // Did we already vote for somebody else?
        final String peer = msg.getSenderId();
        if (this.votedFor != null && !this.votedFor.equals(peer)) {
            if (this.log.isDebugEnabled())
                this.debug("rec'd " + msg + "; rejected because we already voted for \"" + this.votedFor + "\"");
            return;
        }

        // Verify that we are allowed to vote for this peer
        if (msg.getLastLogTerm() < this.raft.getLastLogTerm()
          || (msg.getLastLogTerm() == this.raft.getLastLogTerm() && msg.getLastLogIndex() < this.raft.getLastLogIndex())) {
            if (this.log.isDebugEnabled()) {
                this.debug("rec'd " + msg + "; rejected because their log " + msg.getLastLogIndex() + "t"
                  + msg.getLastLogTerm() + " loses to ours " + this.raft.getLastLogIndex() + "t" + this.raft.getLastLogTerm());
            }
            return;
        }

        // Persist our vote for this peer (if not already persisted)
        if (this.votedFor == null) {
            if (this.log.isDebugEnabled())
                this.debug("granting vote to \"" + peer + "\" in term " + this.raft.currentTerm);
            if (!this.updateVotedFor(peer))
                return;
        } else {
            if (this.log.isDebugEnabled())
                this.debug("confirming existing vote for \"" + peer + "\" in term " + this.raft.currentTerm);
        }

        // Send reply
        this.raft.sendMessage(new GrantVote(this.raft.clusterId, this.raft.identity, peer, this.raft.currentTerm));
    }

    @Override
    void caseGrantVote(GrantVote msg) {
        assert Thread.holdsLock(this.raft);

        // Ignore - we already lost the election to the real leader
        if (this.log.isDebugEnabled())
            this.debug("ignoring " + msg + " rec'd while in " + this);
    }

    @Override
    void casePingResponse(PingResponse msg) {
        assert Thread.holdsLock(this.raft);

        // Are we probing?
        if (this.probeTimestamps == null) {
            if (this.log.isTraceEnabled())
                this.trace("ignoring " + msg + " rec'd while not probing in " + this);
            return;
        }

        // Update peer's ping timestamp
        this.probeTimestamps.put(msg.getSenderId(), msg.getTimestamp());

        // Check new status
        this.checkProbeResult();
    }

    private void checkProbeResult() {
        assert Thread.holdsLock(this.raft);
        assert this.probeTimestamps != null;

        // Get the number of nodes successfully probed so far (including ourselves), and the minimum number required (a majority)
        final int numProbed = this.calculateProbedNodes();
        final int numRequired = this.raft.currentConfig.size() / 2 + 1;
        if (this.log.isTraceEnabled())
            this.trace("now we have probed " + numProbed + "/" + numRequired + " required nodes");

        // If we have successfully probed a majority, then we can finally become a candidate
        if (numProbed >= numRequired) {
            if (this.log.isDebugEnabled())
                this.debug("successfully probed " + numProbed + " nodes, now converting to candidate");
            this.raft.changeRole(new CandidateRole(this.raft));
        }
    }

// Helper methods

    /**
     * Record the peer voted for in the current term.
     */
    private boolean updateVotedFor(String recipient) {

        // Sanity check
        assert Thread.holdsLock(this.raft);
        assert recipient != null;

        // Update persistent store
        final Writes writes = new Writes();
        writes.getPuts().put(RaftKVDatabase.VOTED_FOR_KEY, this.raft.encodeString(recipient));
        try {
            this.raft.kv.mutate(writes, true);
        } catch (Exception e) {
            this.error("error persisting vote for \"" + recipient + "\"", e);
            return false;
        }

        // Done
        this.votedFor = recipient;
        return true;
    }

// Role

    @Override
    boolean shouldRebase(RaftKVTransaction tx) {
        return super.shouldRebase(tx) && !this.pendingRequests.contains(tx);
    }

// Object

    @Override
    public String toString() {
        synchronized (this.raft) {
            final List<Long> pendingRequestIds = pendingRequests.stream()
              .map(tx -> tx.txId)
              .collect(Collectors.toList());
            return this.toStringPrefix()
              + (this.leader != null ? ",leader=\"" + this.leader + "\"" : "")
              + (this.votedFor != null ? ",votedFor=\"" + this.votedFor + "\"" : "")
              + (!pendingRequestIds.isEmpty() ? ",pendingRequests=" + pendingRequestIds : "")
              + (!this.pendingWrites.isEmpty() ? ",pendingWrites=" + this.pendingWrites.keySet() : "")
              + (!this.commitLeaderLeaseTimeoutMap.isEmpty() ? ",leaseTimeouts=" + this.commitLeaderLeaseTimeoutMap.keySet() : "")
              + "]";
        }
    }

// Debug

    @Override
    boolean checkState() {
        assert Thread.holdsLock(this.raft);
        assert this.leaderAddress != null || this.leader == null;
        assert this.electionTimer.isRunning() == this.raft.isClusterMember();
        for (RaftKVTransaction tx : this.pendingRequests) {
            assert tx.getState().equals(TxState.COMMIT_READY);
            assert tx.getCommitTerm() == 0;
            assert tx.getCommitIndex() == 0;
        }
        for (Map.Entry<Long, PendingWrite> entry : this.pendingWrites.entrySet()) {
            final long txId = entry.getKey();
            final PendingWrite pendingWrite = entry.getValue();
            final RaftKVTransaction tx = pendingWrite.getTx();
            assert txId == tx.txId;
            assert tx.getState().equals(TxState.COMMIT_READY) || tx.getState().equals(TxState.COMMIT_WAITING);
            assert pendingWrite.getFileWriter().getFile().exists();
        }
        return true;
    }

    @Override
    void checkTransaction(RaftKVTransaction tx) {
        super.checkTransaction(tx);
        switch (tx.getState()) {
        case COMMIT_READY:
            assert !this.commitLeaderLeaseTimeoutMap.containsKey(tx.txId);
            break;
        case COMMIT_WAITING:
            assert !this.pendingRequests.contains(tx);
            break;
        default:
            assert !this.pendingWrites.containsKey(tx.txId);
            assert !this.pendingRequests.contains(tx);
            assert !this.commitLeaderLeaseTimeoutMap.containsKey(tx.txId);
            break;
        }
    }

// PendingWrite

    // Represents a read-write transaction in COMMIT_READY or COMMIT_WAITING for which the server's AppendRequest
    // will have null mutationData, because we will already have the data on hand waiting in a temporary file. This
    // is a simple optimization to avoid sending the same data from leader -> follower just sent from follower -> leader.
    private static class PendingWrite {

        private final RaftKVTransaction tx;
        private final FileWriter fileWriter;

        PendingWrite(RaftKVTransaction tx, FileWriter fileWriter) {
            this.tx = tx;
            this.fileWriter = fileWriter;
        }

        public RaftKVTransaction getTx() {
            return this.tx;
        }

        public FileWriter getFileWriter() {
            return this.fileWriter;
        }

        public void cleanup() {
            Util.closeIfPossible(this.fileWriter);
            this.tx.raft.deleteFile(this.fileWriter.getFile(), "pending write temp file");
        }
    }
}

