---
type: research
status: completed
tags: [research, database, schema, test]
---

# CLJS codec test instrument repair

## Exit

Make the full CLJS suite a reproducible instrument before intentional load
testing and end-to-end measurement. The falsifiers were:

- two unchanged full runs produce identical counts;
- `:seon.db.protocol/entity-id` rejects `{1.5 0}` and accepts a Datahike ID,
  lookup ref, and keyword ident; and
- every residual failure or error has one namespace and an R28-or-real
  classification.

## Dependency ledger

- Datahike is vendored at `caf526850084a9d5846ccd9ea34251fe411e0d6b`.
  `reference-code/datahike/src/datahike/schema.cljc:6-9` defines
  `:db.type/id`; `datahike/db/utils.cljc:106-139` resolves positive numeric
  entity IDs, two-element lookup refs, and keyword idents;
  `datahike/query.cljc:98-121` normalizes query input; and
  `datahike/store.cljc:50-61` constructs the two- or three-element connection
  ID.
- datalog-parser 0.2.37 is mirrored at
  `08a32d8f2facde9986e257e3df2807104402bf59`.
  `datalog/parser/pull.cljc:65-198` owns the pull grammar and
  `datalog/parser.cljc:9-24` owns query parsing. The root, writer, and
  Babashka dependency sets now name the same 0.2.37 version Datahike uses.
- Malli 0.20.0 remains the schema and generator owner. The existing
  `seon.schema` projection and `seon.db.protocol/wire-projection` are the
  first-party mechanisms strengthened in place.

## Baseline and O12 reconciliation

The retained full reports were:

- `tmp/test-cljs-20260725-220339-12545.report.edn`: 1,303 tests, 83 failures,
  one error; and
- `tmp/test-cljs-20260725-225154-55575.report.edn`: 1,303 tests, 85 failures,
  one error.

The only net count change was
`seon.db.codec-totality-test/every-registered-wire-shape-is-total-and-round-trips`,
which moved from 62 to 64 failures. Its two samples were not supersets:
12 assertions occurred only in the first run and 14 only in the second.
test.check selected a time-based seed because the property supplied none.
O12 commit `a3e4b971d` changed only the redesign ledger; it did not change the
codec, protocol, or test. There were therefore no two stable O12 product
regressions to wave through.

## Repair

Commit `1fbbc7b8e`:

- replaces arbitrary wire values with Datahike-grounded entity ID, pull
  selector, query form, entity ID vector, and connection ID schemas;
- keeps query arguments genuinely open only as eager ordinary wire values,
  matching the parser's scalar, tuple, collection, relation, source, and rules
  bindings;
- pins the gate seed to `424242` and prints it in every counterexample;
- rejects fractional numeric map keys as already-ordinary because Transit JSON
  encoded `0.5` with an integer key tag and decoded it as `0`; the existing
  total projector now degrades that key before encoding;
- fixes invalid test database values to use Datahike connection IDs;
- makes asynchronous tests restore global database stubs before signaling
  completion; and
- removes the stale `seon.eval` namespace assertion left by the pod self-host
  deletion.

The direct grammar property passes 15 assertions, including the entity ID
accept/reject examples. `seon.db.protocol` also loads on the JVM writer
classpath.

## Final counts and classification

With no source change between them:

- `tmp/test-cljs-20260725-234245-10131.report.edn`: 1,304 tests, 6,697
  assertions, zero failures, one error; and
- `tmp/test-cljs-20260725-234528-12850.report.edn`: 1,304 tests, 6,697
  assertions, zero failures, one error.

The assertion inventory is identical. The codec-totality property has zero
failures.

The only residual is
`seon.web.serve-test/model-transport-projection-is-ordered-bounded-and-fail-closed`.
It errors because `seon.web.serve/project-model-transport-rows` validates the
now-absent `:seon.ai.attempt/entity` schema. Commit `f6f6673b6` deleted that
schema with the pod turn phase stack. This is authorized R28 pod-path fallout,
not a surviving JVM defect. There are no residual real failures in the full
CLJS run.

The suite is now stable and explained. The final system gate remains open:
intentional load, measured performance, real end-to-end agents, and the
graduation drills have not been run by this checkpoint.
