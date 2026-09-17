---
type: research
status: open
created: 2026-09-18
tags: [errors, schema, manifest]
---

# Error declaration manifest — slice 1 dependency

**Not landed. RESET NEEDED for the eventual coordinated batch.** No production
resource or source was changed. The named dependency is coordinating same-key
schema replacements with the constructors that still return the old shape.
This is independent of another lane's uncommitted work.

## Evidence and exact boundary

The assignment requires the literal §2.5 manifest, unchanged constructors,
coexistence of old declarations until reset, and green armed schema/error/db
regressions. Those conditions conflict at existing canonical schema identities.
The [binding manifest](../plan/error-entities-prd-2026-09-17.md:148) changes
`:seon.error/value` from the old kind/message map to `:seon.error/base`.
The base requires `:seon.error/at`, `/layer`, and `/operation`.

`seon.error/diagnostic` promises `:seon.error/value` but constructs the old
shape ([src/seon/error.clj](../../../../src/seon/error.clj:338));
`seon.error/value` likewise returns only kind, message and an evidence pointer
([src/seon/error.clj](../../../../src/seon/error.clj:762)). The existing
`diagnostic-construction-is-evidence-complete` regression explicitly validates
that constructor result against the same key
([test/seon/error_test.clj](../../../../test/seon/error_test.clj:225)).
Changing the schema while retaining that constructor makes its promised output
invalid. Adding a duplicate key cannot preserve both definitions: the resource
merger refuses duplicate declarations
([src/seon/schema/edn.clj](../../../../src/seon/schema/edn.clj:306)).
A reset does not repair source-level constructor/contract disagreement.

The retained `:my.note/not-found-error` and
`:seon.error.occurrence/occurrence` contracts also acquire required base members
at their existing identities. This is not merely adding optional declarations.
Weakening the new base, accepting the old shape as an alternative under the
new base, or changing old regression expectations would conceal the mismatch.

## Measured declaration comparison

Run the committed [static comparison script](error-declaration-manifest-probe-2026-09-18.clj)
with `bb` from the repository root. It reads every literal resource fragment
with the native EDN reader and compares it with committed resource bytes,
excluding foreign working-tree edits.

Measured at source revision `49a26d725825fa020e03ed2ddd233d685b797188`:

| Observation | Count |
|---|---:|
| Literal resource fragments | 93 |
| Literal declarations | 643 |
| New keys | 316 |
| Changed existing keys | 280 |
| Unchanged existing keys | 47 |
| Namespace-placement errors | 0 |

These are syntax/key-comparison measurements, not compilation or storage proof.
The script prints exact before/manifest forms for representative collisions.
No resource → keys manifest has landed; the PRD remains the literal authority.
No retired declaration was removed or marked migrated.

The manifest still includes `:seon.error/result`. Owner
[§1q](../plan/program-facts-are-the-runtime-prd-2026-09-17.md:504)
explicitly rejects this generic contract spelling. That point is already
settled: implementation must omit it and consumers enumerate their facets.
It is not another question for the owner.

## Live observation

`bin/seon status` reported default alive, pid 80593. MCP runtime status answered
and reported 3 error signatures, 6 errored evaluations (the tool uses its older
field spelling), and 11 failed tests. These are inherited observations, not
attributed to this slice. Adoption freshness was not established.

One read-only JVM evaluation, with explicit root `/Users/sean/src/seon` and
cluster `default`, took **6 ms**. It changed no definitions, facts or lifecycle.
The probe checked a necessary condition of the proposed base, not the full
future manifest:

```clojure
(let [failure (try (seon.id/valid?)
                   (catch Throwable t (ex-data t)))
      base [:map
            [:seon.error/at inst?]
            [:seon.error/layer :qualified-keyword]
            [:seon.error/operation :qualified-symbol]]
      explanation (malli.core/explain base failure)]
  {:manifest-probe/keys (vec (sort (keys failure)))
   :manifest-probe/current-error? (seon.error/error? failure)
   :manifest-probe/base-valid? (malli.core/validate base failure)
   :manifest-probe/missing (mapv :path (:errors explanation))
   :manifest-probe/data (:seon.error/data failure)})
```

Observed: current error predicate **true**, proposed necessary base shape
**false**; missing paths were exactly `/at`, `/layer`, `/operation`.
The actual top-level keys were `:seon.error/data`, `/kind`, `/message`, and
`:seon.instrument/contract-violated`. Nested data recorded function
`seon.id/valid?`, actual arity 0, arm `:input`, cause
`:malli.core/invalid-arity`, and arglists `([length id])`.
The full tool result retained digest
`bcf248a809e6f7231df4cc77d10207851c9e69d476979275f991b1377b57d6de`,
reported size 5373 bytes. No future arity-facet acceptance is claimed.

## Checker, measurement and test status

No checker rules changed. Still owed: complete alias/conjunction expansion;
base-extension and non-base required-member admission; non-storable member
refusal; non-unique observations; optional coherent render properties; complete
owned-child and whole-parent ordering/count/omission invariants; overlapping
facet and base-only proof. Existing raw map inspection still selects one
literal map in an `:and` ([src/seon/schema/form.cljc](../../../../src/seon/schema/form.cljc:25)).

Fixture projection build time before/after: **not measured**. Fast tally:
**not run**, stopped at the constructor/contract sequencing dependency before
production edits. No green, storage, generated-value, armed-regression, cold,
platform, reset or browser proof is claimed. The static reader probe passed
with positive subject counts above.

## What slice 2 consumes

The intended interfaces remain the PRD's `:seon.error/base`,
`:seon.instrument/contract-error`, `/arity-error`, `/undeclared-error`, and
`/refusal-result`, with owned projection/location/explanation/arity-bound
children and complete declaration projection. **None is supplied by this
landing.** The wrapper owner must not treat this note as a released prerequisite.
An actual arity refusal needs the base observations and declared bounds from
the invocation seam; the current refusal cannot validate unchanged.

## Required sequencing decision

**Recommendation:** permit slice 1 to add genuinely new declarations and
expansion/checkers while explicitly deferring the 280 same-key replacements
that need consumers to their coordinated reset batch. This preserves current
contracts and permits focused green proofs, but changes the assignment's
"literal manifest exactly" delivery boundary. Alternatively, authorize the
constructor/recorder changes with the replacements, expanding this bounded
slice. The exact affected population is derived by the committed script.

The [issue](../../../seon/issues/error-manifest-replacements-require-constructor-coordination.md)
records this dependency. No other lane was contacted or operated. The hook
reported syntax errors in the stage-1 `test/seon/test/selection_test.clj`
during an attempted clarification; that file was preserved and is not the
reason for this stop. No worktree, scratch root, test JVM or background shell
was created. No default mutation/publication/reset was requested.

Grounding reviewed the supplied AGENTS instructions, schema skills, modeling
guide, owner §§1j–1q, review findings, and the binding declaration/sequencing
sections. The whole PRD's detailed per-resource implementation review remains
owed; this note does not claim an end-to-end implementation review completed.
