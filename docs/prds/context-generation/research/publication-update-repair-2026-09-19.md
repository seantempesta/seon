---
type: research
status: complete
tags: [publication, instrumentation, performance, handoff]
---

# Publication update repair — bounded lane handoff

Recorded by the Codex publication-repair session on 2026-09-19. Under plan
§6, the other session owns orchestration, all integration gates, default's
lifecycle and the single README sequence. This lane stops after this landing.

## Exact ownership

Production and regression paths:

- `src/seon/fn.clj`
- `test/seon/fn_test.clj`
- `src/seon/instrument.clj`
- `test/seon/instrument_test.clj`
- `resources/seon/schemas/seon.instrument.edn`
- `src/seon/sci/eval.clj`
- `src/seon/cluster.clj`

Documentation paths:

- `docs/prds/context-generation/research/publication-update-repair-2026-09-19.md`
- `docs/prds/steward-platform/research/publication-projection-repair-2026-09-19.md`
- `docs/seon/issues/complete-publication-takes-seventy-seconds.md`
- `docs/seon/issues/test-host-classification-and-path-use-different-program-edges.md`

All other dirty/staged paths are preserved. No push, reset, restart or cold
gate belongs to this handoff.

## Result and evidence boundary

Fresh `fn/index!` now carries the construction projection through the existing
database acquisition owner, allowing the compiler to flatten shared shapes
using declarations not yet transacted. Instrumentation captures effective
caps and evidence limits once, and reuses wrappers only when their complete
policy and contract dependencies agree. No new global cache or alternate
validation path was added.

Canonical armed targeted iteration: **2 tests, 32 passing assertions,
0 failures, 0 errors**, both terminated. The tests are
`seon.fn-test/fresh-population-flattens-with-its-supplied-declarations` and
`seon.instrument-test/acquisition-carries-one-effective-policy-to-every-wrapper`.
The existing JVM's actual test arming and bounded runner were used. Public
`seon.test/host` encountered the linked classification/path mismatch;
the source-reviewed memory-only regressions were run through the bounded
runner directly. This does not prove public entry-point admission or cold
isolation. The regression sources are the reproducible evidence.

`bin/test-fast --paths` with exactly the seven owned source/test paths and
namespaces `seon.fn-test seon.instrument-test` executed **zero tests** and
exited **64** at admission. It required these foreign dirty caller paths:
`src/seon/db.clj`, `src/seon/test.clj`, `src/seon/test/runner.clj`,
`test/seon/db_test.clj`, `test/seon/schema_test.clj`, and
`test/seon/test/runner_test.clj`. They were not added to this lane's snapshot.
The orchestrator must gate the combined reviewed owners' snapshot.

Live publication CLI 64089 succeeded. The already-running follow-up CLI
64570 also succeeded, leaving published and adopted commit
`6aaec3db-f99e-5125-b8aa-db32567affc8`, digest
`dab7db5b3d08ee1a3cb3dbdc6624a85535cc5123b7b8dda7d5f5d2557a6a95ae`.
Read-only verification found matching owned definition source/spec facts in
the acquired SCI program and the new JVM `index!` docstring. The follow-up
reported added/removed identities while another session changed program
inputs; it is not an isolated incremental-update proof.

## Measured work and incremental-update requirement

These are dated observations with different scopes, not interchangeable
benchmarks. Durable operator logs are
`data/operator/operations/init-init-64089.log` and `init-init-64570.log`.
Local probe outputs are under `tmp/namespace-agent-audit/`.

| Operation | Observed elapsed time | Implication |
|---|---:|---|
| Complete publication/adoption | 159,359 / 150,095 ms | Completion fixed; latency remains open. |
| Population transaction | 26,019 / 25,327 ms | Avoid rebuilding unchanged population. |
| Published program rows read | 25,449 / 26,344 ms | Investigate reuse of one immutable database basis and changed-fact acquisition. |
| 100 repeated config-default acquisitions | 4,687.515 ms | Removed from per-wrapper path. |
| Full adoption JVM instrumentation, then follow-up | 11,434 / 445 ms | Retained wrapper reuse matters. |
| Separate direct arming of 1,241 Vars | 2,250.921 ms | Different scope from full adoption. |
| SCI acquisition in successful publications | 807 / 1,391 ms | Keep program-only base reuse and context isolation. |
| Targeted regression requests including fixture/arming | 36,614 / 72,263 ms | Fixture priming must be distinguished from test execution. |
| Two complete program-digest computations in one probe | exceeded 10,000 ms tool bound | Full rehash is not a cheap freshness check; exact completion time unmeasured. |
| 10,000 armed alias-resolution calls | 16.370 ms | Proposed alias cache was discarded; recomputation already cheap. |

Sean's requirement: prefer incremental updates for most operations; retain
full recomputation where measured cost and simplicity justify it. Incorporate
this into the orchestrator's existing plan, without creating another schedule:

- Key expensive derived results by their actual program/schema/config and
  external-input dependencies. An unchanged function alone cannot establish
  an unchanged test environment. Missing evidence remains unknown.
- Keep durable analysis and test outcomes queryable in the database; use the
  existing blob system for bulky durable payloads. Preserve basis, selection,
  execution outcome and dependency evidence so reuse can be explained.
- Keep validators, callable wrappers and SCI objects in memory with their
  owning projection/context. Do not serialize runtime objects or share
  interpreted closures across candidate contexts indiscriminately.
- Use existing Datahike change/read evidence to acquire updates, including
  removals and transitive declaration changes. Do not introduce a second
  change detector or a global mirror of database authority.
- Record operation duration, reuse/miss reason and retained memory cost;
  bound retained caches by ownership/lifetime. Measure CPU saved against
  memory retained before adding a cache. Preserve bounded execution and
  failure evidence through every expensive phase.

Copying a JVM callable into SCI captures its callable root; it does not
redirect that function's internal JVM Var calls to candidate SCI definitions.
Candidate isolation must therefore prove the changed dependency closure is
executed in the intended context before relying on parallel experimentation.

## Process handback

Default PID 41822 was left running, unchanged in process identity. All lane
publication clients, targeted-test calls and the fast-check shell are terminal;
no lane command is left awaiting collection. No further publication is queued
by this lane. The orchestrator owns the requested test-cache publication and
must verify its own expected source facts. Performance issue remains open;
this landing claims the two repairs and live adoption, not integration green.
