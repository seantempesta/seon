---
type: research
status: active
tags: [research, schema, database, test]
---

# Program provenance — 2026-09-16

## Scope and authority

Read AGENTS.md, docs/seon/issues/README.md, the namespace data-model plan
including §8, and the current N7 class note and its three open member notes
end to end. Read the mining report's N7 row and structural kill: record the
missing fact at its owner, then query it. Applied data-oriented-clojure,
data-modeling, datahike, repl, and clojure-testing skills. This is data-model
slice 3.1 plus lint findings, not closure of the broader N7 class.

The owner's 02:25Z correction supersedes the initial subject assignment.
No subject derivation is persisted. `:seon.test/subject` remains metadata-only.
The earlier live prototype was withdrawn and the original metadata and call
functions restored before implementation. No production subject edit remains.

## Structural change

The static artifact owns each file row, every declaration's exact source span,
and its lint rows; all participate in the existing program identity and exact
replacement mechanism. Re-indexing removes obsolete lint facts and retains
only the identity tombstone, per AGENTS.md's stable-identity law. An active
finding has `:seon.lint/type`; counting identity tombstones is not an active
finding census.

- Functions and tests share `:seon.fn/file` and `:seon.fn/form-span`. They
  describe the same provenance, so no second attribute family or protected
  `seon.test.edn` edit is necessary. Agent-admitted definitions have neither.
- `:seon.fn.file/path` identifies a file. Its SHA-256 digest is a scalar,
  deliberately not an alias of identity-bearing `:seon.source/digest`.
- Spans are zero-based, half-open UTF-8 byte offsets, so they can select exact
  disk bytes. Kondo's one-based row/column coordinates are retained verbatim
  on lint findings. One conversion in `exact-form-span` maps those same
  analyzer positions to byte offsets for both declaration spans and finding
  ownership; per-line byte starts avoid rescanning whole prefixes per form.
- `:seon.lint/fn` refs the containing function or test identity; a finding
  outside a declaration has its file ref and no fabricated function ref.
  Its id is `seon.id/id` of `[function-symbol type row col]`; file-scoped
  findings use the canonical file path in the first position.
- Verified dependency correction: kondo emits **unqualified** types, including
  `:unused-binding`, `:redundant-do`, and `:namespace-name-mismatch`.
  `:seon.lint/type` is consequently `:keyword` and preserves the exact value,
  rather than inventing a namespace or renaming the attribute to `rule`.
- The schema bridge now maps fixed Malli tuples to Datahike's native tuple
  storage with `:db/tupleTypes`. This was necessary: the original bridge
  refused the requested `[:tuple :int :int]` as unstorable.
- The indexing writer closure carries the caller's projection into Datahike's
  writer thread. Without it, development reconciliation used the old boot
  enum for the newly admitted file/lint identity families.

## Dependency ledger

- `reference-code/clj-kondo/analysis/README.md`: var definitions and usages
  carry row/col/end positions; usage `:arity` identifies calls. The existing
  adapter is `src/seon/fn/analyzer.clj` and the existing static owner is
  `src/seon/fn.clj` (`analysis-rows-by-file`, `artifact`, `build-artifact`).
- `reference-code/datahike/src/datahike/db/transaction.cljc:1019`: tuple
  validation consumes `:db/tupleType` or `:db/tupleTypes`; fixed heterogeneous
  tuples enforce the supplied element count. The Seon bridge is
  `src/seon/schema/datahike.clj` (`malli->datahike-attr-in`).
- `src/seon/program.cljc`: `shapes`, `canonical-row`, and
  `exact-replacement-tx` already own exact definition replacement.
  `src/seon/fn.clj/reconcile-tx` uses that owner unchanged for file/lint rows.

## Tests of a namespace are a query

MCP JVM mode, explicit `(seon.db/db (seon.operator/connection "default"))`:

```clojure
(seon.db/q
 '[:find ?t :in $ ?name
   :where [?t :seon.test/sym _]
          [?t :seon.fn/calls ?f]
          [?f :seon.fn/ns ?ns]
          [?ns :seon.ns/name ?name]]
 db namespace-symbol)
```

Measured counts: `seon.plan` **22**, `seon.turn` **114**,
`seon.cluster.message` **35**. These agree with the owner's correction.
Initial subject holders: **0**. No chosen-subject mirror is added.

Juniper's historical `my.agents.juniper/largest-customer-test` still has only
`clojure.core/=`, `clojure.core/let`, and `clojure.test/is` call refs. Its
source calls `largest-customer`, but that edge remains absent. This is the
already recorded historical-analysis residual in
[the call-edge issue](../../../seon/issues/agent-form-calls-to-core-namespaces-are-not-indexed.md).
No backfill or synthetic edge was inserted.

## REPL sequence and measured results

Every changed function form was evaluated in default before editing its
production file. Representative calls exercised source bytes, row generation,
canonical declaration preservation, lint ownership, tuple derivation, and
index refusal. All tests below used `seon.test/run` with default's explicit
connection; no test JVM or `bin/test[-fast]` was launched.

Exact recurring forms:

```clojure
(seon.test/run #'seon.fn-test/indexed-declarations-carry-exact-file-bytes
               (seon.operator/connection "default"))
(seon.test/run #'seon.fn-test/static-findings-are-replaced-with-their-program-rows
               (seon.operator/connection "default"))
(seon.test/run #'seon.schema.datahike-test/fixed-tuples-derive-native-ordered-storage
               (seon.operator/connection "default"))
(seon.test/run #'seon.fn-test/indexing-refuses-an-already-populated-branch
               (seon.operator/connection "default"))
```

| Slice / point | Result |
|---|---|
| Initial byte-span prototype, before storage schema | 21 assertions, 0 failures/errors; runs 68195 and 68200. This was the earlier pure byte-check version, not the final storage regression. |
| Native fixed-tuple regression, before file edit | 3 assertions, 0 failures/errors; run 68220. |
| Final file/span regression, before edit | 0 assertions, 1 error; run 68215. Cached fixture rejected the new program identity before executing assertions. |
| Final lint replacement regression, before edit | 0 assertions, 1 error; old program-identity enum in the cached fixture. |
| Final regressions after edit | Repeated cached-fixture refusal; several attempts additionally failed recording results with `empty is not supported on Datom`. No green result claimed. |
| Writer-carriage slice before edit | Index-refusal assertion passed; run 68244 also recorded 1 instrumentation-drift error naming `seon.test.runner/commit-results!`. |

The final byte regression checks both function and test rows, with non-ASCII
text before the forms, follows the stored file ref, and compares the byte slice
to the stored source. The lint regression indexes one real unused-binding
finding, follows its function ref, corrects the file, and reconciles it through
`reconcile-tx`; only the lint identity must remain afterward.

## Publication and exact verification boundaries

Initial publication exposed and corrected two local defects: ordinary refs
cannot carry nested file maps under `:seon.db/ref` (file rows now stand alone),
and aliasing `:seon.source/digest` gave file rows two identity attributes.

Published source commit `6aa9efea-d937-536f-8bd5-70eb26d29e84` contains
**5,602** rows with file refs and **523** active lint rows. These are published
source measurements, **not** proof of default adoption. At that observation,
default still reported commit `6aa9e66f-89d6-5324-b9dd-24427d088b48`, no file
holders, and no lint holders. Its new storage schema was installed, but program
reconciliation had not completed. Subsequent adoption evidence belongs below.

The canonical base retained by `test/seon/test_support.clj` predates the new
program identity schema. See
[the existing fixture issue](../../../seon/issues/canonical-fixture-retains-old-function-contracts-after-adoption.md).
No cached fixture, foreign session, default process, or protected file was reset.

Test-result recording separately reached `seon.db/jdk-integers->long` via
`seon.test.runner/commit-results!`, then `clojure.walk/postwalk` called
`empty` on a Datahike Datom. The protected runner was being edited concurrently;
this is a measured verification boundary, not attribution of its cause.

The issue authority's initial `bin/issues-index --class class/n7` failed on
pre-existing lifecycle/schedule inconsistencies. No index edit was made. The
three current N7 members remain open; this slice does not close their residuals.

## Gate request

Requested namespaces: `seon.fn-test`, `seon.program-test`,
`seon.schema.datahike-test`, then the platform tier. Exact owned paths and
remaining boundaries are in `tmp/orchestrator/gate-requests/program-provenance.txt`.
No isolated gate result is claimed; the orchestrator owns the batched gate.

### Further verification before landing

The schema-evolution follow-up limits identity queries to attributes installed
in the queried database's actual `:schema`. Otherwise querying a new identity
against the old publication returned a typed error map, which the old loop
mistook for identity values. The index-refusal regression then passed **1
assertion, 0 failures/errors**, run **68268**, in 16,769 ms. A preceding full
program-row probe exceeded MCP's 30,000 ms bound; the smaller source proof
below completed in **1,110 ms**.

At published commit `6aa9f038-3813-5fbb-a598-6ae8120b2377`:

```clojure
{:seon.fn/sym "seon.plan/settle-call"
 :seon.fn/file {:seon.fn.file/path "/Users/sean/src/seon/src/seon/plan.clj"}
 :seon.fn/form-span [22108 23168]}
```

The 1,060-byte UTF-8 slice equals `:seon.fn/source`. A census of all
source-bearing function rows found **0 missing file refs** and **0 missing
spans**. **481** active findings have function/test refs. Three exact examples:

| Finding id | Type | Row:col | Referenced program identity |
|---|---|---|---|
| `008501985b6d` | `:shadowed-var` | 390:13 | `seon.cluster.turn-test/drive-agent!` |
| `019ac6991d98` | `:shadowed-var` | 3169:32 | `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent` |
| `0451c1a2fece` | `:shadowed-var` | 148:20 | `seon.cluster.wake-test/runtime-listens-route-and-refresh-with-entity-and-value-constraints` |

This proof read the published database through the existing default JVM and
carried that publication's projection. It does not claim converged adoption.
Publication request `ee4de556-638c-4cfb-b25b-656d56181f98` subsequently refused
at the concurrently edited `:seon.issue/issue` schema: its `:seon.render/ai`
names `seon.issue/render-ai` without a declared input contract. That foreign
file was preserved. A later ordinary adoption must still prove the requested
default pull and count.

All retired subject-prototype Vars were removed from the live namespaces.
There are no subject-derivation edits in the commit. No background shell,
scratch cluster, test JVM, or worktree was started by this lane.

Implementation commit: `3402913f3`. After the committed edit, the fixed-tuple
regression again passed **3 assertions, 0 failures/errors**, run **68282**, in
6,436 ms. The Datom recording observation is now filed as
[test-result recording](../../../seon/issues/test-result-recording-walks-datoms-on-failed-live-tests.md),
and the oversized publication explanation is recorded on the existing
[operator diagnostic issue](../../../seon/issues/init-failure-dumps-entire-prepl-event-history.md).
