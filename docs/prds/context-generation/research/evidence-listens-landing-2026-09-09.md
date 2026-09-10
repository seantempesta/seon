---
type: research
status: working
tags: [database, agent, runtime, test]
---

# Exact read evidence and runtime listens — 2026-09-09

Read the supplied AGENTS.md end to end, its opening verbatim lane rules,
the turn PRD §10 and §§13–15, and the complete agent data chart PRD,
including §0.6, §4, and roadmap rows 2 and 8. The checked-in AGENTS.md
ends at §8; the requested §10 is the turn PRD section copied at its top.

## Dependency ledger

- `reference-code/datalog-parser/src/datalog/parser/type.cljc`: typed
  Pattern, Not, Or, And, Pull and scoped `collect-vars`.
- `reference-code/datalog-parser/src/datalog/parser/impl.cljc`: preserved
  source via `get-source` / `with-source`, without text parsing.
- `reference-code/datahike/src/datahike/query.cljc`: `q`, normalized
  request bounds, parsed clause dependencies and inherited sources.
- `reference-code/datahike/src/datahike/pull_api.cljc:80`: compiled pull
  plans, reused by `src/seon/db.clj`'s finite pull dependency walker.
- `reference-code/datahike/src/datahike/db/utils.cljc:40`: indexed
  attributes; unindexed value constraints use an attribute seek and filter.
- `reference-code/datahike/src/datahike/writer.cljc`: serialized writes
  invoke listeners before delivering the transaction result.
- `reference-code/core.async/src/main/clojure/clojure/core/async/impl/channels.clj`:
  nonblocking offer, reused by the existing `wake/route!` delivery seam.
- `src/seon/schema/datahike.clj:519` and `:553`: encode target values and
  decode authored listen values through the ordinary schema codec.

## Read evidence

`query-index-patterns` walks typed conjunction, negation and disjunction.
Datahike binds nested scopes against enclosing positive patterns, including
entities excluded by negation. Pull-in-find resolves entity bindings through
Datahike and calls the existing `pull-index-patterns` on each selection.
Predicates, rules and unsupported pull selectors retain general evidence.
No extra queries run when the caller is not collecting read evidence.

Regression uses canonical databases and real SCI, removes replay data,
asserts nonempty exact evidence, and checks other-recipient changes,
own-message changes, and a read-marker retraction making an excluded
message visible. The initial new regression passed in the armed fast run;
the two older tests needed the current raw SCI query and valid message data.

## Verification boundaries

First scoped gate: `SEON_TEST_WORKERS=3 bin/test --paths src/seon/db.clj
test/seon/read_evidence_test.clj -- seon.db-test seon.read-evidence-test
seon.cluster.wake-test`, snapshot HEAD `8cab293a4`, 59 tests / 358 assertions,
8 failures / 3 errors. The requested evidence tests passed. Remaining
failures were the existing unhanded-projection performance regression,
a fixed historical timestamp expectation in the message diff test, stale
wake schema fixtures and inbox expectations, a refused fault fixture, and
one parallel published-base filestore acquisition failure (isolated
confirmation passed). No foreign session or edited file was operated.

The known performance and filestore classes already have issue notes:
[projection cost](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md)
and [parallel base acquisition](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md).

At session start default was alive, but MCP runtime status timed out after
30 seconds. The next ordinary JVM parser probe succeeded in 1 ms; the
existing MCP issue records this observation. Default was never stopped,
reforked or restarted. Scratch verification and final gates follow below.

First seam platform gate: `SEON_TEST_WORKERS=1 bin/test --paths
src/seon/db.clj test/seon/read_evidence_test.clj --platform`, 84 tests /
505 assertions, zero failures/errors. The final input-binding refinement
also passed the armed combined fast run: 20 tests / 118 assertions,
zero failures/errors (read-evidence plus wake namespaces). It excludes
collection/tuple input variables from added scalar bindings, preserving
Datahike's original input binding semantics.
