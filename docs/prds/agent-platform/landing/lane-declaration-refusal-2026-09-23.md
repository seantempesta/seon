---
type: evidence
status: committed (fix schedule #24n)
created: 2026-09-23
---

# Lane declaration-refusal (fix schedule #24n), 2026-09-23

Opus 5.5 lane. Owned: `src/seon/schema.clj`, `test/seon/schema_test.clj`, and
(granted by the orchestrator, one slice) `src/seon/schema/edn.clj` and
`test/seon/schema/edn_test.clj`; this note; the issue note
`docs/seon/issues/retiring-a-contract-referenced-schema-member-refuses-as-raw-malli.md`.

## Defect

An undeclared schema reference raised Malli's raw
`{:type :malli.core/invalid-schema :data {:schema K}}` from two owners in
`seon.schema`:

- `direct-reference-keys-in` (called by `projection-with-schema`), the path of a
  declaration inside a transaction function (`seon.turn/row-tx`). Because that
  ex-data has no `:seon.error/at/layer/operation`, `seon.db/transact!`
  classified it as an unknown core failure and rethrew under `:panic`
  (the `:else` branch of the `transact-call` catch, db.clj ~4105-4125).
- `projection-registry` (called by `build-projection`, `load-projection`,
  `projection-with-schema`, `projection-with-function-contract`), the path of a
  retired member a function contract still names (the issue note's
  `seon.cluster.source/publish!` case).

## Change (one owner, one refusal)

`seon.schema/refuse-unresolved-reference!` wraps both compile sites. It reads
the missing key through the cause chain with the gate's own reader
(`seon.schema.internal/missing-schema-reference`, internal.cljc:394), and
throws `ex-info` carrying the flat refusal built by
`unresolved-reference-refusal` with the Malli exception as the cause. The
refusal goes through `seon.error.refusal/diagnostic`, so the whole cause chain
(`:seon.error/chain`, root `:seon.error/frame`, `:seon.error/exception-class`)
rides it (F0). Members:

- `:seon.error/at/layer(:seon.schema/derivation)/operation/message`
- `:seon.error/member` and `:seon.schema/missing-reference` = the missing key
- `:seon.schema/invalid-schema` = the naming schema (declared
  `:seon.schema/invalid-schema-error`), `:seon.schema/undefined-contract` = the
  naming function (declared `:seon.schema/undefined-contract-error`)
- `:seon.schema/key` = the declaration being admitted
- `:seon.schema.blockers/schema-keys` / `function-symbols` = EVERY candidate
  declaration naming the missing key (the vocabulary `schema-removal-blockers`
  already returns), all named in the message
- `:seon.schema/refused-value` = the naming definition,
  `:seon.schema/expected-value :seon.schema/registry-key`

A nested compilation's refusal passes through unchanged; a failure that is not
an unresolved reference (or whose key IS declared) propagates unchanged.

Caller converted in the same slice: `seon.schema.edn/unresolved-candidate-reference`
(staged-alias admission, 163c3ce28) read Malli's raw `:type`/`[:data :schema]`;
it now reads `:seon.schema/missing-reference`.

## Proof

Fresh probe JVMs only (`clojure -M:test <script>`). The default JVM (pid 70720)
was NOT reloaded (hook publication paused; no redefs in default), so
`seon.test/run` on default cannot exercise this source: the custody-bound tests
were not run through the canonical runner. Probes: `tmp/declaration-refusal/`.

1. `tmp/declaration-refusal/probe.clj` over the packaged projection
   (`(schema/build-projection (schema.edn/packaged-forms))`, 3,384 forms):
   - CASE1 `(projection-with-schema packaged :lane.probe/invalid [:vector :lane.probe/absent] {...})`:
     HEAD throws `:malli.core/invalid-schema` with no structural members;
     working tree throws the refusal above, 8.8 ms; valid against
     `:seon.error/base` and `:seon.schema/invalid-schema-error`;
     `(seon.error.refusal/refusal e)` (the db.clj classifier) returns it with
     operation `seon.schema/projection-with-schema`; chain root data is Malli's
     `{:schema :lane.probe/absent :form :lane.probe/absent}`.
   - CASE2b `(projection-with-function-contract packaged 'lane.probe/f [:=> [:cat :lane.probe/absent] :int] {...})`:
     HEAD raw Malli; working tree refusal, operation
     `seon.schema/projection-registry`, `:seon.schema/undefined-contract lane.probe/f`,
     valid `:seon.schema/undefined-contract-error`, 18 ms.
   - CASE3 staged alias through `seon.schema.edn/admit-changed-identities`
     (`:lane.probe/alias -> :lane.probe/base`, base only in candidate forms):
     admitted on both HEAD and working tree (12.7 / 8.8 ms).
   - Every touched contract compiles against the packaged registry:
     `unresolved-reference-in`, `unresolved-reference-refusal`,
     `refuse-unresolved-reference!`, `direct-reference-keys-in` -> true.
2. Writer shape (default JVM, read-only, pure data, lane namespace
   `lane.declaration-refusal.probe`, no new code loaded): the refusal as
   `write-observation` extends it validates against
   `:seon.db.write/validation-refusal`, `:seon.db/error-result`,
   `:seon.db/transaction-refused-error`, `:seon.schema/invalid-schema-error`,
   `:seon.error/base` -> all true (10 ms). With db.clj's catch
   ("A Seon transition refusal returns its own value verbatim") this is the
   refused result, not a `:panic` rethrow.
3. New regressions, bodies run in a probe JVM with `with-database` replaced IN
   THAT JVM by the packaged projection (`tmp/declaration-refusal/tests-in-probe-jvm.clj`):
   - `seon.schema-test/an-undeclared-reference-refuses-as-the-declared-schema-refusal`:
     pure section 11/11 pass; its writer section needs a real connection
     (2 expected fails on the stub) and is unproved here.
   - `seon.schema-test/retiring-a-referenced-member-names-every-referrer`:
     9/9 pass; refusal names `::member-user`, `member-reader`, `member-writer`;
     build 163 ms over 3,386 forms / 2 contracts.
   - `seon.schema.edn-test/staged-alias-admission-resolves-its-candidate-reference-first`: 3/3 pass.
4. Same-JVM comparison, working tree vs HEAD schema.clj+edn.clj (load-file),
   72 non-long tests of `seon.schema-test`, `seon.schema.edn-test`,
   `seon.schema-retirement-test`, `seon.schema-reference-graph-test`,
   `seon.schema-in-place-test` (`tmp/declaration-refusal/compare.clj`,
   output `compare.out`): identical outcome per test, `{:pass 30 :error 40 :fail 2}`
   on both; the 40 errors are the no-custody refusal of `with-database`, the 2
   fails (`every-predicate-schema-declares-what-it-accepts`,
   `render-declarations-require-a-contract-that-accepts-their-shape`) fail on
   HEAD too. Includes the generative `one-gate-admits-populations-or-names-the-generated-mutation`
   (its `:unresolved` mutation expects `::schema.edn/unresolved-reference`) and
   `register!-flows-through-the-same-gate`: pass on both.

Named test for the holder of `test/seon/schema/projection_writer_test.clj`
(writer-cost): after this commit is published and adopted,
`bin/test-check --test seon.schema.projection-writer-test/ordered-declarations-use-their-database-and-abort-together`
and `bin/test-check --ns seon.schema-test` (the two new regressions). Not run by
this lane.

## TIMINGS

| operation | wall ms | justification |
|---|---|---|
| probe JVM load of seon.schema + deps (probe.clj) | 10,591 | DEFECT >10 s: JVM compile of the whole source tree from `clojure -M:test`, proportional to the code base; recorded class `docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md` (orchestrator folds) |
| probe JVM load, test namespaces (tests-in-probe-jvm / compare) | 38,464 / 30,254 | DEFECT >10 s: same class; host load average 16-18 at the time |
| packaged build-projection, cold | 5,605 (11,345 under load) | whole population compile, 3,384 forms, first realization in a fresh JVM; same class (no cache link across probe JVMs) |
| compare.clj total | 100,130 | 72 tests x 2 generations + load; diagnostic only |
| first compare.clj attempt | 629,110 | DEFECT, my probe: it included the declared-long `seon.schema-redeclare-test/seeded-schema-families-survive-development-adoption` (child JVM boot); child pid 11346 (parent 9141, mine) and the probe JVM were killed; its two roots `tmp/schema-redeclare-test-{bc82e9fb,f7f2743a}-*` (no holder, no symlinks) deleted |
| refusal CASE1 / CASE2b / retirement | 8.8 / 18 / 163 | sub-second |

Hot path (`projection-with-schema`, valid declaration, packaged projection,
20 warm runs, median ms): run A (working tree first) 2.11 vs HEAD 1.71; run B
(HEAD, working tree, HEAD, load 18) 6.38 / 5.10 / 5.76. The success path adds
only a `try` frame; the spread is JIT order and host load, not the change.
Cache: none added; the probe JVMs share no projection cache (every build cold).

## Verification limits

- The canonical runner (`seon.test/run` / `bin/test-check`) did not run the
  new code: default is unreloaded; no scratch cluster boot (>10 s without owner
  authorization).
- The writer path is proved by shape (validation of the classified refusal)
  and db.clj's catch reading, not by a transaction on a branch.
- RESET NEEDED: no.

## Out of scope, observed

- `seon.schema/projection-without-schema` throws `:seon.schema/schema-in-use`
  as a non-flat ex-info (no `:seon.error/at/layer/operation`, no
  `:seon.schema/schema-in-use` member its declared error requires); under
  `:panic` the writer would rethrow it the same way. Callers read
  `:seon.schema/error` (`test/seon/schema_usage_guard_test.clj:189,233`).
- `seon.schema.internal/assert-compilable-schema!` and
  `seon.schema/projection-validator`/`projection-explainer` refusals are also
  non-flat; the new owner now wraps the internal one inside
  `projection-registry` only.
- `seon.schema/canonical-reference-graph` keeps `(catch Exception _ #{})`: a
  documented hand-off to the compilation gate, which now refuses flat; listed,
  not changed.
