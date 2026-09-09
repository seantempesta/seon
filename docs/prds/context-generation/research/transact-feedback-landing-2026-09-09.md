---
type: research
status: active
tags: [research, database, schema, test]
---

# Transaction schema feedback — 2026-09-09

## Scope and authorities

Read AGENTS.md end to end, including its verbatim turn-PRD §10 lane rules;
read turn PRD §10 and §§13–15 end to end, and the data-chart PRD §§0 and 9
end to end. AGENTS.md has no numbered §10 heading: its opening lane-rule
copy is the requested authority. Also read the plan README and working-edge
entry. No delegated lane or default lifecycle operation was used.

The initial shared tree contained context-blocks edits, including db_test.clj,
sci/eval.clj, and render/value.clj. The scratch worktree began at 05510a6d4.
The context-blocks checkpoint e915d2de0 subsequently released those tracked
edits. Only then were the database test fixtures corrected in the shared tree.

## Dependency ledger and implementation

- Datahike transaction syntax, map expansion, reference resolution and
  transaction-function execution:
  reference-code/datahike/src/datahike/db/transaction.cljc:1053–1180.
  Datahike's existing writer still owns serialization and native refusals.
- Explicit projection registries and compiled caches:
  src/seon/schema.clj:264, :1017, :2713, :3045, :3131.
  The write seam receives the evaluation's handed projection or the database's
  projection; no Malli process-global registry is consulted.
- Authored schema inspection and reference/cardinality storage facets:
  src/seon/schema/form.cljc:17–96 and src/seon/schema/datahike.clj:28–267.
- Existing candidates and transaction rejection classification:
  src/seon/db.clj:898–932, :2228–2253.
- Flat diagnostic constructor: src/seon/error.clj:280–332. Its existing
  extra-field handling suffices; no error-constructor change was required.

The transaction seam checks map attributes, identity-map entity contracts,
and each :db/add value before Datahike. Components and lookup-ref values
are checked recursively. Datahike many-value collections and reference
representations are normalized only for validation; the original transaction
continues to the existing codec and writer. Strings, negative tempids, and
"datomic.tx" remain admitted reference syntax. Installed native Datahike
attributes without an authored form retain Datahike validation and
classification; the preflight adds the supplied Seon forms where they exist.
Transaction-function bodies remain Datahike-owned; this preflight does not
execute them or inspect their subsequently generated transaction data.

Datahike's maybe-wrap-multival (transaction.cljc:717–738) also admits a
singleton scalar or identity lookup ref for cardinality-many map syntax.
The final change preserves that behavior; the admission regression includes
both forms alongside collections, negative tempids and "datomic.tx".

Open entity maps ignore additional registered attributes for entity-shape
validation while validating those attributes individually. Unknown attributes
return installed candidates. The returned error carries the authored attribute
form, original offending value, input path, applicable entity form and the
diagnostic constructor's complete evidence.

## Declaration defects exposed by the check

Canonical boot rows from seon.schema/canonical-schema-rows do not have
:seon.schema/generatable?: seon.test.accretion supplies it on admitted
agent-authored schemas. The entity member is therefore optional. Without that
correction every canonical database fixture refused during schema population.

:seon.test/failure-identity declared :seon.search/index :exact, while both
the enum and src/seon/search.clj:169–175 support only :text and :symbol.
The unsupported search declaration was removed. These two unprotected
declaration corrections are required to admit the canonical population.
The edit hook also reported existing polymorphic-schema admission findings
on :seon.schema/value, /arguments and /kvs; this note does not claim the
shared default adopted the edits.

## Live proof and exact bytes

Scratch root: tmp/transact-feedback-root; scratch cluster: transact-feedback.
Initial boot was observed at PID 51110, PREPL 64587, with all reported Flow
procs replying. Final proof used a freshly published and reforked scratch
branch at PID 65754, PREPL 49821 (current-src commit
6aa1d0b8-4c4a-5c81-b592-6673b3d860ec), with the final database namespace
loaded by boot. The canonical Juniper installer
docs/prds/context-generation/research/juniper_fixture_2026_09_06.clj
installed the fixture. Juniper's stored no-provider setting was true.

The initial proof hot-loaded db and re-armed contracts against the database
projection; the final proof used boot's instrumentation. The reproducible script is
test/seon/transact_feedback_probe.clj; call its probe! with the explicit
scratch cluster name. It enters seon.sci.eval/evaluate using the same request
shape as the MCP SCI evaluation tool, then reads the actual seon.repl/text
output. It does not fabricate a successful turn.

Observed 754 UTF-8 bytes, including the measured :ms field:

```clojure
my.agents.juniper=> (seon.db/transact! [[:db/add [:example/order "a1"] :example/amount "wrong"]])
#:seon.repl{:value {:seon.db/attribute :example/amount, :seon.db/offending "wrong", :seon.db/path
  [0 3], :seon.db/transaction-refused true, :seon.error/data #:seon.error{:diagnostic-cause
    :seon.db/invalid-value, :diagnostic-evidence #:seon.db{:path [0 3]}, :diagnostic-evidence-availability
    :seon.error/known, :diagnostic-expected :int, :diagnostic-layer :database-write,
    :diagnostic-member :example/amount, :diagnostic-offending "wrong", :diagnostic-operation
    seon.db/transact!}, :seon.error/kind :seon.db/invalid-write, :seon.error/message
  "Attribute :example/amount expected :int, got \"wrong\".", :seon.schema/form
  :int}, :ms 100}
```

The source amount remained 60 after refusal. The existing rejection AI function
produced these exact bytes:

```text
Expected: :int
Got: "wrong"
Attribute: :example/amount at [0 3]
```

**Unfinished presentation boundary:** the current SCI result path sends a
returned flat error through structural value rendering and labels it :value.
It does not call the schema-declared rejection AI function. Thus the requested
schema-first #:seon.repl{:error …} presentation is NOT proven or delivered
by this database-only slice. The source paths are
src/seon/sci/eval.clj (shown-result and success-evaluation),
src/seon/render/value.clj (value-node*), and src/seon/repl.clj (response-entries).
Those were the context-blocks lane's owned implementation files; no substitute
renderer or exception throw was added at the transaction seam.

A separate ordinary-turn submission through the existing Juniper fixture
timed out at the MCP's 60,000 ms bound while the writer repeatedly classified
:seon.turn/refused / run-exists. Disarming answered but did not settle that
submission. The scratch JVM was downed through its owning worktree operator.
The SCI evaluation observation above remains the live proof boundary;
ordinary-turn completion is not claimed.
The 183,246 observed run-exists log lines and fixture retry boundary are
recorded in docs/seon/issues/juniper-submission-repeats-existing-turn-refusals.md.

## Cost

100 warmups, 1,000 measured validation-only calls over 50 authored :db/add
datoms, with the supplied projection and armed cluster contracts:
**0.17995425 ms per transaction** on the final fresh scratch JVM
(initial hot-loaded observation: 0.202805875 ms).
The actual write committed 51 datoms
(50 authored plus Datahike's transaction instant). This measures validation
overhead, not total SCI acquisition/rendering or disk latency.

## Verification checkpoint

The first isolated gate (05510a6d4 plus owned paths) ran 46 tests / 332
assertions: 31 failures, zero errors. All six new test groups passed, including
native uniqueness classification. Nine older db-test cases failed; a test
also leaked instrumentation registry state. An original-db.clj fast baseline
ran 40 tests / 263 assertions: 14 failures, zero errors. The baseline failures
covered a deleted result attribute, unhanded read performance and synthetic
codec projections. Strict validation additionally exposed incomplete identity
maps in older read/custody/uniqueness fixtures.

The released db-test fixtures now supply their codec projection inside the
canonical fixture, use valid entity maps or explicit datom writes for partial
fact scenarios, and preserve instrumentation through the canonical helper.
The final scoped gate on eec1ca7c3 plus the owned paths ran **46 tests /
332 assertions, one failure, zero errors**. The only failure was the existing
ten-unhanded-queries-stay-within-twice-raw-query-cost; all six new test groups
passed. No worker instrumentation drift remained. The corresponding fast run
was 46 tests / 328 assertions, one failure, zero errors.

An intermediate platform check ran 83 tests / 477 assertions, 33 failures,
three errors while a trial refusal for installed native attributes lacking
authored forms was present. That trial was removed to preserve Datahike's
existing native-schema contract. The next platform run was 83 tests / 490
assertions, two failures, zero errors: both blob-GC fixture schema rows omitted
required identity-map members and ignored the refusal. Their partial schema
facts now use explicit datom writes, and setup asserts the transaction report
before exercising collection. The final platform rerun is **83 tests / 490
assertions, zero failures, zero errors** (exit 0), including those two collection
regressions and the cohost boot proof.

The final focused fast check is six tests / 67 assertions, zero failures and
zero errors. After the singleton many-value correction, the complete scoped
gate again ran **46 tests / 332 assertions, one failure, zero errors**.
The same unhanded-query performance assertion failed: ten wrapped reads took
5,209.148582 ms against a permitted twice-raw total of 70.828502 ms.
The original-db baseline already reproduced this class; the transaction slice
does not change the q projection-acquisition path.

Checkpoint commit: 26ec13420d6469da45ea4d41b66efb7234ef24d3.

## Paths and cleanup

Every path touched across the two slices:

- src/seon/db.clj
- resources/seon/schemas/seon.schema.edn
- resources/seon/schemas/seon.test.edn
- test/seon/db_test.clj
- test/seon/transact_feedback_test.clj
- test/seon/transact_feedback_probe.clj
- test/seon/cluster/registry_test.clj
- docs/prds/context-generation/research/transact-feedback-landing-2026-09-09.md
- docs/seon/issues/returned-errors-bypass-their-ai-renderer.md
- docs/seon/issues/default-component-probe-times-out-after-adoption.md
- docs/seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md
- docs/seon/issues/juniper-submission-repeats-existing-turn-refusals.md

The owning scratch operator stopped the final JVM; the process table confirmed
PIDs 51110, 62577 and 65754 absent. The scratch root and worktree were removed
without following the reference-code symlink. Holderless retained test roots
were removed after the failures were recorded and repeated. The final platform
gate exited and removed its successful root. All owned command sessions exited.
Foreign edits and
processes were preserved; default was never stopped, reforked or restarted.

All commands set SEON_TEST_WORKERS=3. Gates use bin/test --paths followed by
the explicit owned paths and -- seon.db-test seon.transact-feedback-test,
and a separate --platform invocation. No full-suite option was used.
