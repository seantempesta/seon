---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, host, mcp, branch, dogfood]
---

# Retiring a live hosted agent writes `:db/current-tx` as a ref, and `seon.db/transact!` refuses it

This blocks the dogfood loop's last step: a lane cannot release the branch it worked on.

## Observed

Default pid 25654 ran archive fbd0897df, where `:seon.agent/admission-closed-tx` is installed.
Branch `design-repl-entry-renders-2` was created with the MCP `branch` tool, and two MCP SCI runs
were made on it, so its hosted agent is live. Then `branch retire design-repl-entry-renders-2`
answered:

```
Closing the host's admission was refused.
seon.db/transact! refused transaction data at [0 3]: expected an integer or a string or a tuple
with 2 entries or a map, got a keyword :db/current-tx.
:seon.db.write.attempt/transaction [[:db/add 40431 :seon.agent/admission-closed-tx :db/current-tx]]
at seon.cluster.host$retire_BANG_ (host.clj:128)
```

In the same session, `branch retire design-repl-entry-renders` answered `:seon.dev.mcp/retired? true`.
That branch's host had not been re-hosted after default restarted, so `hosted` answered nil and
`retire!` never ran. Only the live-host path fails.

## Cause

`seon.cluster.host/retire!` (`src/seon/cluster/host.clj:126-127`, 07a03cdd4) asserts the ref as
`:db/current-tx`. Seon's writer validates a ref value against `:seon.db/ref`, which accepts the
string tempid `"datomic.tx"`. The rest of the code base uses that spelling for "this
transaction": `bootstrap.clj:310`, `:337` and `cluster.clj:3211` (`:seon.turn/opened-tx
"datomic.tx"`).

## Expected, and the regression

`retire!` closes admission in one committed transaction whose ref names that transaction.
The regression retires a host that has run at least once, then asserts that the branch has left
the roster and that the agent row's `:seon.agent/admission-closed-tx` names the closing
transaction.

Currently left behind: branch `design-repl-entry-renders-2` with hosted agent `mcp/default`
(the MCP default sessions agent), holding disposable probe rows. Retire it after the fix.
