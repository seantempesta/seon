---
type: research
status: complete
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

## Runtime listens

`wake-matchers` derives one attribute map containing schema-addressed
recipients and runtime-owned entity/value constraints. The report handler
looks up that map once per datom, checks only the relevant constraints,
and unions recipients before the existing nonblocking delivery. Listen,
ownership and schema edits rederive from `:db-after` before dispatch.
The handler keeps its existing whole-callback catch and `offer!` delivery.
Logical value constraints pass through the existing schema storage codec.

The canonical regression proves initial registration followed by a new
listen, unrelated attributes, entity/value edits, retraction matching and
listen deletion. Existing saturation, closed-route and exception tests
exercise the same delivery seam. Owned fixture repairs supply required
schema/function provenance, expect the inbox edge, and derive the message
diff timestamp from its creating transaction instead of a retired date.

Live proof: fresh scratch cluster `evidence-listens`, root
`tmp/evidence-listens-root`, PID 17478, source publication
`6aa21d86-a5d1-53db-b854-d9d54444cc47`. Loaded the canonical Juniper
fixture installer, then ran
[the committed probe](evidence_listens_probe_2026_09_09.clj) through MCP JVM
mode. It observes the actual Flow mailbox proc count, not a substitute
channel. Amount changed at `536871016`; mailbox count **6 → 7 in
63.936291 ms**. An actual customer-attribute change left it at **7**.
[Exact measurements](evidence-listens-live-2026-09-09.edn).

Juniper was configured no-provider. Scratch boot had already made one
root provider attempt at `2026-09-10T03:02:52Z`, before fixture installation;
this was not a no-provider boot. The final probe explicitly set the cluster
and root no-provider too, and asserted total attempt count **1 → 1**.
No provider attempt was created by any of the amount-listen probes.

The live verification boundary is **wake delivery**, as assigned. The
ordinary work derivation still considers schema-declared wakes only:
Juniper's latest turn remained `536870997`. Recorded separately in
[runtime listen turn eligibility](../../../seon/issues/runtime-listens-do-not-yet-participate-in-turn-eligibility.md).
This commit does not claim custom listens open or refresh turns yet.

Second seam platform gate: **84 tests / 505 assertions, zero failures or
errors**, one worker. Final requested three-namespace gate: **60 tests /
379 assertions, one failure, zero errors**. The sole failure is the already
filed `ten-unhanded-queries-stay-within-twice-raw-query-cost`; its assertion
is unchanged. The stale timestamp and wake fixture failures are fixed.
All gates use HEAD plus owned paths; no all/full suite was run.

The final focused correctness gate (`seon.read-evidence-test` and
`seon.cluster.wake-test`, the same owned paths, one worker) passed
**20 tests / 118 assertions, zero failures/errors**. The complete
three-namespace failure is retained above, not replaced by this result.

## Files and cleanup

First commit `ed8fd02e3` owns `src/seon/db.clj`,
`test/seon/read_evidence_test.clj`, the chart PRD, this note, and
`docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`.

Second commit owns `src/seon/cluster/wake.clj`,
`test/seon/cluster/wake_test.clj`, `test/seon/db_test.clj`, the chart PRD,
this note, the adjacent `evidence_listens_probe_2026_09_09.clj` and
`evidence-listens-live-2026-09-09.edn`, plus these issue notes:

- `docs/seon/issues/runtime-listens-do-not-yet-participate-in-turn-eligibility.md`
- `docs/seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md`
- `docs/seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md`

The scratch operator down completed with PID 17478 exited and its flock
free. Owned disposable roots and probe logs were removed after confirming
no live runner held them. All owned command sessions ended. Foreign edits,
the other lane's processes and default were preserved; no reset is needed.
