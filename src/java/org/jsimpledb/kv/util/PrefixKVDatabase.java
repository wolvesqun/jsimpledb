
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.kv.util;

import com.google.common.base.Preconditions;

import java.util.Map;

import org.jsimpledb.kv.KVDatabase;

/**
 * Prefix {@link KVDatabase} implementation.
 *
 * <p>
 * Instances use a fixed {@code byte[]} key prefix for all key access, thereby providing a nested {@link KVDatabase}
 * view of the corresponding sub-range of keys within the containing {@link KVDatabase}. This allows, for example, multiple
 * {@link KVDatabase}s to exist within a single containing {@link KVDatabase} under different key prefixes.
 *
 * <p>
 * Instances ignore invocations to {@link #start} and {@link #stop}; instead, invoke these methods on the underlying
 * {@link KVDatabase}.
 */
public class PrefixKVDatabase implements KVDatabase {

    private final KVDatabase db;
    private final byte[] keyPrefix;

    /**
     * Constructor.
     *
     * @param db the containing {@link KVDatabase}
     * @param keyPrefix prefix for all keys
     * @throws IllegalArgumentException if {@code db} or {@code keyPrefix} is null
     */
    public PrefixKVDatabase(KVDatabase db, byte[] keyPrefix) {
        Preconditions.checkArgument(db != null, "null db");
        Preconditions.checkArgument(keyPrefix != null, "null keyPrefix");
        this.db = db;
        this.keyPrefix = keyPrefix.clone();
    }

    /**
     * Get the containing {@link KVDatabase} associated with this instance.
     *
     * @return the containing {@link KVDatabase}
     */
    public KVDatabase getContainingKVDatabase() {
        return this.db;
    }

    /**
     * Get the key prefix associated with this instance.
     *
     * @return (a copy of) this instance's key prefix
     */
    public final byte[] getKeyPrefix() {
        return this.keyPrefix.clone();
    }

// KVDatabase

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public PrefixKVTransaction createTransaction(Map<String, ?> options) {
        return this.createTransaction();                                            // no options supported yet
    }

    @Override
    public PrefixKVTransaction createTransaction() {
        return new PrefixKVTransaction(this);
    }
}

