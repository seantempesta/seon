---
type: review
status: ready-after-fixes
created: 2026-09-23
tags: [agent-platform, contracts, refusal, canonicalization]
---

# Independent Astra review: refusal canonicalization

The system already compiles named predicates and recovers their names for durable evidence. Preserve that information at the declaration; compose the existing Malli and Seon functions rather than adding another identity mechanism.

**Verdict: ready after fixes to the regression specification below.** The production repair is correct and qualifies as a **one-seam obvious fix**: quote the `malli.core/schema?` predicates in `src/seon/schema/internal.cljc` metadata. No redesign, second canonicalizer, Malli change, instrumentation change, or relaxed contract is warranted. The fixture repair is a separate necessary test correction, not proof of the production repair.

Reviewed [the design](refusal-canonicalization-defect-2026-09-23.md) at **9af8d4f22b4adc98502803e60b573eb6cf8e9fd9**, also HEAD when inspected, on `refactor/agent-platform`. Its working copy has no delta from that commit. This is independent source review, not an independent execution of its reported probes.

## Findings and required changes

1. **Make the regression's predicate-identity condition explicit.** A fresh wrapper symbol bypasses the retained contract (`src/seon/instrument.clj:641–649`), but does not alone ensure failure before the fix. `canonical-definition` can already name a raw callable when *any* supplied binding is identical to it (`src/seon/schema.clj:635–648`). Thus a fixture carrying the host root could make the proposed test pass on the broken declaration. Require positive evidence that the local wrapper symbol is absent from both the function-contract map and compiled registry, and that the supplied predicate bindings do not identify the raw host root. Use the existing fixture's SCI binding when it establishes that condition; do not inject a compensating host-root binding. Assert that the loaded metadata retains the qualified predicate symbol after the repair. That assertion must read the actual declaration, not a hand-authored replacement. This makes loss of the name observable even if fixture binding behavior later changes.

2. **Specify a contract-valid local body and compare actual evidence.** The design's diagnostic `(constantly true)` is valid for its synthetic boolean contract, but cannot be reused unchanged with `entity-entries` metadata: its output is a vector of entries (`src/seon/schema/internal.cljc:73–74`). For the local wrapper use a body returning `[]` and a valid compiled empty map schema for the success case. Keep the invalid input `nil`. Assert both checked-contract and problem fingerprints against the existing normalizer applied to independently named expected forms; merely checking that fingerprints exist would admit incorrect evidence. Assert the explanation count and item count, original operation, input check, nil observation, and validation against `:seon.instrument/contract-error`. A generic caught exception or valid `:seon.error/base` is insufficient: the concurrent fallback can produce that while canonicalization remains broken (`src/seon/instrument.clj:667–691`). Keep the proposed family round-trip and anonymous-callable refusal checks within this one behavior regression.

These are bounded test-specification changes, not requests for additional production machinery. The orchestrator can rule on these findings and pass them with the reviewed design to the implementing owner; this review does not edit that owner's design or test files.

## Mechanism and dependency evidence

Malli first: the committed gitlink is **8725a8cbd9d595f4a970ce53a2eefdbe7211b96d**. The inspected dependency checkout is **56394c54**; the parent reports a modified gitlink. No claim is made that either identifies the loaded runtime. The relevant `-fn-schema`, `schema`, `form`, `validator`, and `explainer` seams are unchanged by the inspected checkout-versus-pin core diff; its change concerns instrumentation of throwing validators.

`malli.core/-fn-schema` (`reference-code/malli/src/malli/core.cljc:1761–1800`) retains the supplied children for its form, evaluates the predicate for validation, and produces explanation data. `schema` and `form` (`:2555–2584`) compile or expose that form. `validator` and `explainer` (`:2631–2659`) use Malli's schema cache. Malli is not responsible for discovering a Clojure Var name from an arbitrary function root; it preserves the child it received. No fork-only API is needed for the proposed repair.

The first-party flow corroborates the design:

- The producer test (`test/seon/render/value_test.clj:380–396`) substitutes a matching row with an uninstalled schema key. Specificity looks up that compiled schema and calls `entity-entries` (`src/seon/render.clj:368–374`). The fixture therefore supplies nil to a boundary that correctly requires a compiled schema.
- `entity-entries` writes unquoted predicate expressions in evaluated metadata (`src/seon/schema/internal.cljc:73–74`), losing their symbolic identity. Its same-file siblings have the same declaration pattern (`:20,29,55–57,95,103,109`). Runtime calls such as `m/schema?` at `:34` are not conversion targets.
- `schema/compilable-form` preserves already-callable predicates and resolves named ones from explicit bindings or a loaded Var (`src/seon/schema.clj:315–331`). `bind-contract-predicates` leaves those resolved callables intact (`src/seon/instrument.clj:413–442`).
- `boundary-refusal` asks Malli for explanation data, then normalizes the checked contract and each problem schema (`src/seon/instrument.clj:609–635`). `authored-form` calls `canonical-definition` after `m/form` (`src/seon/fn/schema_shape.clj:75–81,171–196`). The latter refuses a raw root that neither carries a Var name nor matches the supplied bindings. This is a secondary exception during diagnostic construction, not evidence that Malli rejected the schema's compilation.

The design's recorded raw/named wrapper comparison and loaded-Var stack are consistent with this source path. They distinguish the host metadata path from the already-good durable contract. I did not retrieve its diagnostic blobs or rerun the forms, so the recorded identity, exact envelope and timings remain the design author's evidence. History `8704e5ed5` and `375f79b01`, and the existing [predicate durability issue](../../seon/issues/a-search-contract-predicate-cannot-be-made-durable.md), support reusing the existing inverse rather than rebuilding it.

## Refusal completeness and scope

Preserving the name lets the existing owner construct the full declared refusal. `resources/seon/schemas/seon.instrument.edn:99–113` requires the base error, function, arity, check, explanations, expected shape and location. The existing explanation construction carries schema/value locations and the projected offending value. The repair adds no envelope, undeclared keys, discriminator, identifier or signature path. Existing bounded observation rules still apply; “complete” means the declared evidence is present, not an unlimited serialization of the offending value.

The error policy remains intact: an unexpected reporter failure is a core fault, not an ordinary input refusal. The foreign fallback's use of `error.refusal/diagnostic` does not fix missing contract evidence; whole-cause construction stays at `src/seon/error/refusal.clj:75–114`, and signature derivation stays at `src/seon/error.clj:151`. Neither should change here. Canonical printing (`src/seon/sci/admit.clj:85–95`) cannot recover a discarded name. Rendering limits (`src/seon/render/value.clj:547–563,605–615`) likewise do not belong in this repair.

The producer fixture correction is appropriate: install a real declaration through `:seon.test-support/extra-schema`, whose write is checked at `test/seon/test_support.clj:582–590` and whose public fixture is documented at `:643–668`. Establish its compiled entry and actual match, use declared renderer functions, keep the error-shaped candidate and both AI/HTML selection assertions, and remove `with-redefs`. That repaired test can pass without the predicate repair, because it stops feeding nil to `entity-entries`. The metadata-path regression, with findings 1–2 applied, is the regression that must fail without the production fix. The existing generator test (`test/seon/instrument_test.clj:1372–1388`) does not establish this path and should remain.

Cost remains proportional to the changed contract during preparation and to the refused schema and reachable references during evidence construction. No successful-call work is added. Existing canonicalization sorts supplied bindings and can scan them for callable identity; quoting adds no scan, cache, retained structure, or whole-program pass. Expected source delta is zero lines for the mechanical conversion; small behavior-test growth is justified. Runtime speed and allocation were not measured by this review.

## Ownership and verification boundary

`git status` showed concurrent changes; clean does not mean unheld. At inspection:

| Path | Working state and ledger constraint |
|---|---|
| `src/seon/schema/internal.cljc` | Clean; no explicit exact-path hold found. Recheck before implementation. The ledger's `src/seon/schema/*.clj` glob does not literally include `.cljc`. |
| `test/seon/instrument_test.clj` | Dirty, +33 lines; `a1-arming` hold. Route work to holder or await release. |
| `test/seon/render/value_test.clj` | Clean; `agent-branch-sweep` hold. |
| `test/seon/test_support.clj` | Clean; also `agent-branch-sweep`; existing API suffices. |
| `src/seon/instrument.clj` | Dirty, +37/−13; overlapping `a1-arming` / `opus-leak-fix` ledger entries. No edit needed. |
| `src/seon/schema.clj` / `reference-code/malli` | Schema file clean but held; dependency gitlink modified and held. Ledger has multiple historical/current owner entries; neither needs an edit. |

The only review-owned path is this new document. Other tracked and untracked work was preserved; no other lane was contacted or resumed. The working instrumentation/dependency changes are a foreign verification boundary, not a reason to stop this docs-only review.

No tests, JVM, runtime probe, reload, adoption, gate, branch mutation, archive snapshot, worktree, or push was performed. The user's explicit docs-only/no-tests restriction governs over the generic test-retry instruction. This review asserts source consistency and conditional design readiness, not installed behavior or a green test. Implementation still owes the design's focused branch request, actual adoption/arming observation and source identity; the orchestrator owns integration proof.

Landing: one Markdown review; net src **0**, test **0**. No publication clock is applicable. File inspection commands completed sub-second as reported by the command tool; no runtime performance claim follows from that. Commit only this path with the requested co-author trailer; its resulting commit id is reported in the review handoff.
