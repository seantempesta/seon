---
type: issue
status: open
severity: friction
tags: [issue, database, test, agent]
---

# Declaration settlement consumes an invalid-read value as a ref

On default at 2026-09-16T02:35:24Z,
`seon.turn-test/batch-settlement-preserves-declaration-order` recorded
1 pass, 2 failures, 0 errors through `seon.test/run` with explicit default
custody and armed contracts.

The real fixture at `test/seon/turn_test.clj:551` settles a test declaration
and its function declaration in one batch. The transaction refused with
`:lookup-ref/unique`, entity-id `[:seon.db/invalid-read true]`; the test's
subject ref consequently remained absent. The complete small failure value
was read. This proves an invalid-read value reached lookup-ref handling;
it does not identify the earlier failed query.

Probe the program declaration settlement owner and that query on the
current publication. Propagate a typed refusal rather than interpreting
its map entries as entity refs, and verify that a valid same-batch subject
resolves. The error-graph lane did not change the protected program or
declaration settlement owners. This is not evidence against the passing
error recording/refusal notification regression.

## The earlier failed query — 2026-09-16 (batch-19 triage)

The unnamed earlier query is `seon.db/q` over `:seon.fn/pending-calls`, the
attribute `76774d044` added. Probed on default in MCP JVM mode: inside
`seon.test-support/with-database` the cached canonical base has
`:seon.fn/file`, `:seon.fn/form-span` and `:seon.lint/id` installed but NOT
`:seon.fn/pending-calls`. `(seon.turn/row-tx (seon.db/db connection) {} row)`
then returned nine transaction entries, six of them built from the refusal
map's own entries, for example:

```clojure
[:db/retract [:seon.db/invalid-read true] :seon.fn/pending-calls "sample/idempotent"]
[:db/add [:seon.error/kind :seon.db/invalid-read] :seon.fn/calls "sym:sample/idempotent"]
```

`seon.db/q` returned the typed refusal
`"seon.db/q cannot read uninstalled attribute :seon.fn/pending-calls."`, and
the pending-call loop walked that map as if it were result tuples. This is
the absence-of-signal class: the caller must propagate the typed refusal
instead of iterating it. Observed while triaging
`seon.fn-test/settled-agent-form-has-static-index-edge-parity` and
`seon.program-test/identical-runtime-redeclaration-builds-no-datoms`; the
owning file `src/seon/turn.clj` was concurrently edited and left untouched.
A fresh gate base installs the attribute, so this hides until a base is stale.
