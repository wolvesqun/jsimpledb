
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.cli.cmd;

import java.util.Map;

import org.jsimpledb.cli.CliSession;
import org.jsimpledb.util.ParseContext;

public class SetAllowNewSchemaCommand extends AbstractCommand {

    public SetAllowNewSchemaCommand() {
        super("set-allow-new-schema allowed:boolean");
    }

    @Override
    public String getHelpSummary() {
        return "Sets whether recording a new schema version into the database is allowed";
    }

    @Override
    public CliSession.Action getAction(CliSession session0, ParseContext ctx, boolean complete, Map<String, Object> params) {
        final boolean allowed = (Boolean)params.get("allowed");
        return session -> {
            session.setAllowNewSchema(allowed);
            session.getWriter().println("Set allow new schema to " + allowed);
        };
    }
}

