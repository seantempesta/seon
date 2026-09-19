---
type: research
status: active
tags: [error, schema, testing, namespace-agents]
---

# Kind schema references — 2026-09-20

## Scope and grounding

Bounded arming repair on `steward-platform`, starting at `dae516b51`.
Read AGENTS.md sections 0–5 end to end; namespace-agents plan §8 (D12/D13);
the retirement inventory's R1–R8 actions, all nine assigned resource sections,
and D12/D13 handoff's six required-member replacements; predicate-caller-sweep's
Verification section; error-family-1a's Step 6, exact outputs and final handoff.
Read the data-oriented-clojure, data-modeling, clojure-testing and repl skills,
and the active roadmap entry.

Authorities: [plan §8](../plan/namespace-agents-plan-2026-09-19.md),
[inventory](error-kind-retirement-inventory-2026-09-19.md),
[predicate sweep](predicate-caller-sweep-2026-09-20.md),
[1a](error-family-1a-2026-09-19.md).

Dependency ledger: Malli resolves every map member through its entry parser
(`reference-code/malli/src/malli/core.cljc:1210`); an absent schema fails at
`core.cljc:329–333`. Optional controls value presence, not schema resolution.
First-party base members are declared at
`resources/seon/schemas/seon.error.edn:266`; the prior settlement-contract
repair is the predicate sweep's `seon.turn/receipt-settle-call` change.
No new declaration, dependency, predicate, discriminator or runtime machinery.

MCP runtime_status observed default alive, PID 41822. One read-only JVM probe
compiled `[:map [:probe/missing {:optional true} :probe/missing]]` and caught
exactly `:malli.core/invalid-schema`, with both schema/form `:probe/missing`.
No live definition, adoption, publication or lifecycle operation was performed.
This is a dependency-resolution probe, not live adoption proof.

## Exact sites

The assignment's 18 matching source lines represent 13 map entries (some
references span three lines, and a one-line member repeats the token).
Locations below are original HEAD locations, so every assigned reference is
accounted for even after line movement.

| Resource under `resources/seon/schemas/` | Original lines | Replacement and producer evidence |
|---|---|---|
| `seon.cluster.eval.edn` | 77,79 | Remove optional classification mirror from `receipt`; keep error text, triage and interruption evidence. `src/seon/turn.clj:99–118` constructs the settlement projection. |
| `seon.cluster.eval.edn` | 139,141 | Same removal from `settle-request`; no error facet is asserted for successful evaluations. |
| `seon.problems.edn` | 13 | Required kind becomes required at/layer/operation, per inventory handoff; retain evaluation identifiers, source and error text. Producer: `src/seon/problems.clj:144–168`. |
| `seon.problems.edn` | 26 | Same base requirements on `form-problem`; retain attribution and source evidence. Producer: `src/seon/problems.clj:170–221`. |
| `seon.problems.edn` | 120 | Same base requirements on `error-signature`; retain occurrence count and complete fact member. Producer: `src/seon/problems.clj:108–125`. |
| `seon.maintenance.result.edn` | 97,99 | Remove optional classification mirror from claim error; preserve claim path and message. Producer: `src/seon/maintenance.clj:106–113`. |
| `seon.maintenance.result.edn` | 154 | Required kind becomes at/layer/operation on collection-error, preserving message and the separate positive success arm. Producer: `src/seon/maintenance.clj:200–211`. |
| `seon.context.contribution.edn` | 23,25 | Remove optional classification mirror; retain contribution error text and evaluation links. Producer: `src/seon/context.clj:468–488`. |
| `seon.context.capture.edn` | 27,29 | Remove optional classification mirror; retain capture basis, message and contributions. Producer: `src/seon/context.clj:490–534`. |
| `seon.turn.loop.edn` | 73 | Remove optional classification mirror in terminal-request; retain evaluation error/triage/interruption. Producer: `src/seon/turn.clj:99–118`. |
| `seon.test.accretion.edn` | 107 | Required kind becomes at/layer/operation; retain the existing installation refusal evidence (function, orientation, counts, failure groups, advisories, auto-check and arguments). Producer: `src/seon/test/accretion.clj:315–330`. |
| `seon.render.edn` | 159 | Remove optional `seon.render.unknown/refusal`, which only held the selected retired kind under another key. Retain reason, producer, output, throwable and call evidence. Producer: `src/seon/render.clj:885–899`. |
| `seon.eval.drive.edn` | 48 | Required kind becomes at/layer/operation as explicitly requested by the inventory handoff; preserve source, shown text, live value slot, error text and evaluation time. Producer: `src/seon/eval/drive.clj:135–167`. |

The six required replacements use the existing declared base members. They do
not invent a domain facet that these unconverted producers do not supply.
The producer reads above expose the already inventoried step-6 boundary:
problems, maintenance projection, install-refusal and evaluation-drive still
construct/project old kind-only maps. Their producer conversion remains owed;
this bounded schema repair does not claim their returned values now satisfy
D12. In particular evaluation-drive also projects successful evaluations with
an absent-kind sentinel: its eventual producer/contract conversion must
represent success separately rather than fabricate an error observation.
The resource repair follows the inventory's explicit six-site directive.

The optional mirrors are deleted under R4; this does not claim R5's broader
producer/reader evidence-carriage conversion is finished. The source remains
read-only under this assignment unless an inline contract blocks arming.
The existing [editing-surface issue](../../../seon/issues/schema-edit-admission-cannot-load-after-error-predicate-retirement.md)
and retirement inventory retain that wider integration obligation.

## Verification

The arming repair landed in `c0a99068f`. The required shared-tree load command exited 0 and printed
`:loads` before committing:

```sh
clojure -M -e "(require 'seon.fn 'seon.cluster 'seon.turn 'seon.plan 'seon.test) (println :loads)"
```

Exact fast invocation:

```sh
bin/test-fast --paths \
  resources/seon/schemas/seon.cluster.eval.edn \
  resources/seon/schemas/seon.problems.edn \
  resources/seon/schemas/seon.maintenance.result.edn \
  resources/seon/schemas/seon.context.contribution.edn \
  resources/seon/schemas/seon.context.capture.edn \
  resources/seon/schemas/seon.turn.loop.edn \
  resources/seon/schemas/seon.test.accretion.edn \
  resources/seon/schemas/seon.render.edn \
  resources/seon/schemas/seon.eval.drive.edn \
  -- seon.error-test seon.instrument-test seon.schema-test
```

Snapshot HEAD `dae516b51c4582484d6210da8d1cc675697ce634`, only the nine
resource changes overlaid. Published graph
`c7c66f815606ca3f11a53ab24f6067df22c9449f4e8eef83d6a3b20fcef150c3`
was announced as 24 commits behind HEAD. Packaged projection acquired at
`2026-09-19T21:02:31.262907Z`, test-fast PID 28917.

No source contract changed. `rg -n ':seon.error/kind' resources/seon/schemas`
returns no matches after the repair. The static source search finds one remaining inline kind member:
`src/seon/sci/reader.cljc:881`, `seon.sci.reader/read`'s `[:map
[:seon.error/kind :keyword] ...]` arm. It uses the built-in `:keyword` schema,
so does not resolve the deleted schema and does not block arming. It is outside
the assigned `.clj` arming blockers and remains for the producer conversion.
No `::error/kind` alias appears under src. Other vector hits are query/select
forms, not contracts.

Arming succeeded at `2026-09-19T21:03:21.594307Z`: 3 selected namespaces,
110 program namespaces, 1,335 registered / 1,335 instrumented / 1,309
program-armable. No inline source contract blocked arming.
Load log: 205 bytes, SHA-256
`579c3de9fb226ffa47e63f1160596a1fe89089af30718e0a8855f4a369c28a18`.

## Integration boundary and cleanup

The orchestrator owes the path-limited cold gate, platform proof, batched
schema reset/adoption and live verification. No cold gate, all/full suite or
bin/seon lifecycle command was run. Default PID 41822 was left untouched.

Initial foreign dirty paths: `bin/test`,
`docs/prds/steward-platform/research/test-selector-a1-2026-09-19.md`,
`src/seon/test.clj`. None is edited or included in this lane's commit.
The fast snapshot excludes A1's source changes. No shared-tree load failure
required an additional worktree.

## Final fast tally

The one requested fast run reached its summary and exited **1**:

```text
Ran 118 tests containing 3843 assertions.
46 failures, 23 errors.
```

All three namespaces completed; the last ended at
`2026-09-19T21:18:01.705103Z`. This proves arming and test execution, not a
green suite. No arming bypass, test change or further source edit was used.
The final log was 167566 bytes, SHA-256 `4d27286fc15effc576d8045efda1c4d363f91efbb752d11f13f8769bd0b508c8`.

Exact red distribution (counts are assertions/errors, not distinct tests):

| Namespace / test | Failures | Errors |
|---|---:|---:|
| `seon.error-test/complete-error-children-validate-through-the-writer` | 0 | 1 |
| `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer` | 0 | 1 |
| `seon.error-test/error-facets-persist-through-the-real-occurrence-owner` | 1 | 0 |
| `seon.instrument-test/applying-without-a-handed-projection-refuses-before-collection` | 0 | 1 |
| `seon.instrument-test/jvm-and-interpreted-functions-arm-the-declared-guard` | 0 | 2 |
| `seon.instrument-test/an-invalid-core-error-mode-is-an-evidence-complete-value` | 0 | 1 |
| `seon.instrument-test/dependency-free-linters-join-production-instrumentation` | 0 | 2 |
| `seon.instrument-test/sci-installed-contracts-enforce-facets-and-refusals-in-both-dials` | 13 | 0 |
| `seon.instrument-test/an-invalid-refusal-retains-its-evidence-at-the-kernel-boundary` | 0 | 1 |
| `seon.instrument-test/a-wrong-shaped-call-throws-a-flat-error-value` | 0 | 1 |
| `seon.instrument-test/prefix-related-sibling-vars-keep-their-own-contracts` | 0 | 2 |
| `seon.instrument-test/host-boundaries-enforce-per-arity-facets-in-both-modes` | 8 | 0 |
| `seon.instrument-test/authored-contract-changes-rearm-without-reloading` | 0 | 1 |
| `seon.instrument-test/interpreted-contracts-use-the-active-registry-and-core-error-dial` | 0 | 1 |
| `seon.instrument-test/a-reporter-failure-is-not-relabelled-as-a-contract-violation` | 0 | 1 |
| `seon.instrument-test/the-selection-is-declared-vars-with-schemas-and-nothing-else` | 0 | 3 |
| `seon.instrument-test/a-sovereign-sci-fork-acquires-its-own-recorder` | 0 | 1 |
| `seon.instrument-test/a-deadline-firing-inside-an-instrumented-call-is-reported-as-the-bound` | 0 | 1 |
| `seon.instrument-test/a-public-multi-arity-does-not-reenter-its-instrumented-var` | 0 | 1 |
| `seon.schema-test/a-projection-with-no-bound-predicates-compiles-every-declared-shape` | 0 | 1 |
| `seon.schema-test/canonical-reference-values-use-the-pull-collection-grammar` | 14 | 0 |
| `seon.schema-test/a-refused-projection-source-never-yields-a-projection-with-no-forms` | 3 | 0 |
| `seon.schema-test/render-declarations-require-a-contract-that-accepts-their-shape` | 7 | 0 |
| `seon.schema-test/pulled-forms-derive-from-the-entity-schema-and-selector` | 0 | 1 |

Measured boundaries, without asserting an unprobed common cause:

- `complete-error-children-validate-through-the-writer`: `schema_shape.clj:130`,
  “A compiled schema did not retain canonical EDN shape data.” Its preceding
  writer refusal calls `seon.error/diagnostic` without the base members from
  `seon.db (db.clj:174)`. The separate unowned-entity assertion receives
  `:seon.schema.shape/noncanonical-compiled-form` with a compiled generator/Var
  rather than the expected `:seon.db/unowned-entity` diagnostic.
- `error-identity-and-occurrences-are-owned-by-the-writer`:
  `seon.cluster.status/snapshot` returns a map at `:seon.cluster.status/store-bytes`
  where its contract requires an integer.
- Several instrument assertions call `seon.schema/valid-candidate-value?` with
  an inline `:or` vector; its argument contract requires a registry keyword.
- `applying-without-a-handed-projection-refuses-before-collection`:
  `seon.schema/call-with-projection-state` returns undeclared
  `:seon.instrument/registration-error`.
- The SCI/host recording assertions named by the prior landing note execute
  but remain red in the counts above; they are not claimed repaired by arming.

These are outside the nine-resource and compile-blocking-contract assignment.
The retirement inventory's step-6 producer work remains the owning follow-up;
this note records the execution evidence rather than changing held owners or
making broader claims from a passing load.

The second required raw-JVM load, after `c0a99068f` and before this evidence
commit, also exited 0 with `:loads`. Both load logs are 205 bytes with the same
SHA-256 recorded above. `git diff --check` passes. No additional source
kind-contract blocker was encountered; the nonblocking reader member listed
above remains.

Cleanup: the fast process (PID 28917) exited and its launcher removed
`tmp/test-runs/run.mCvErg`. Both owned raw load processes exited. The lane's
`tmp/kind-schema-references` logs were removed after recording their tallies,
exact failures, hashes and reproducible invocation here. No worktree was
created and no foreign scratch root was removed.
