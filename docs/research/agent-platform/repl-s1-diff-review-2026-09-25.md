---
type: research
status: independent diff review; revise
created: 2026-09-25
reviewed-commits: [91eb8e4a5, d37adee4a]
task: "132fee0467c8"
scope: one-REPL M1 S1; definition authority, restoration, reader fidelity, contracts and cost
---

# S1 diff review

**Verdict: revise.** Keep the existing evaluator, analyzer and instrumentation owners, and the move of contract validation/arming inside admission. The diff does not yet establish one definition authority: observable mutations can escape admission, a matched name need not identify the executed declaration, and settlement restoration can leave the retained execution running refused code. These are corrections to S1 before building S2 on it.

The principal Clojure approach remains a small transformation of held before/after values into one declaration and its restoration data. Complete that component at its existing owners. Do not add another evaluator, mutation registry or general rollback manager to cover the holes.

## Evidence and scope

Reviewed both actual commit diffs, their landing, the S1/P0-A design and both reviews in [one-repl-language-review-2026-09-25.md](one-repl-language-review-2026-09-25.md). Authorities: [design §2.2 and S1](one-repl-language-2026-09-25.md), [landing](../../prds/agent-platform/landing/lane-impl-repl-m1-2026-09-25.md), AGENTS.md and the data-oriented Clojure/REPL skills. Source citations below are at **d37adee4a**, unless specified; `eval.clj` means `src/seon/sci/eval.clj`, `turn.clj` means `src/seon/turn.clj`, and schema citations mean `resources/seon/schemas/seon.sci.eval.edn`.

Dependency source pins: SCI `fcbd8862800e638dc0f8f5521111f999279cbcd2`, Malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`, Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`. SCI and Malli checked-out revisions match the reviewed gitlinks. First recorded workspace HEAD was `1097ad460c0d6e6b0856d42c4a3a2026dc77af87`; review findings use Git objects, not concurrent working-tree edits.

**Source-confirmed** below means a control-flow defect, with proposed distinguishing inputs; those inputs were **not executed in this review**. The owner reports default entry-gate tests **6/0**, green program-change/definition-digest tests, and three eval-test reds demonstrated pre-existing. Accept that as supplied installed evidence, not proof of the omitted cases below. The landing's earlier “nothing published” statement is superseded by that supplied result. No tests, runtime probes, `bin/seon`, delegation, publication or other-lane contact were performed here.

## P0-A — The observed set is incomplete and host-bound rows exempt unrelated changes

**Source-confirmed.** `program-definitions` inspects only entries present after evaluation and requires the **new** value to be `fn?` or have `:test` metadata (`eval.clj:456–474`). Replacing a previously admitted function with data, for example `(def kept 42)` or `(def kept nil)`, is therefore not a definition; it is not a removal either because the intern still exists (`:488–501`). A stored function row can survive while its executing Var holds a different kind of value. Ordinary private data definitions should remain private, but replacing a program function cannot acquire that exemption from its new value.

There is a second independent bypass. If **any** matched row is host-bound, `unindexed` is nil for the entire form (`:711–716`). All host-bound rows are also collapsed into one declaration using `first`, without grouping by their originating declaration. Distinguishing inputs are:

```clojure
(do (defrecord Pair [a b]) (eval '(defn hidden [x] x)))
(do (defrecord A [x]) (defrecord B [y]))
```

Given the landing's demonstrated record rows, the first form disables the check on the unmatched `hidden` Var; the second counts both records as one. Constructor helpers from one record are a legitimate grouping concern, not evidence that every mutation in the enclosing form belongs to that record. `var-row (first var-rows)` still selects just one row at `:4117`.

**Smallest fix:** classify a changed binding using its prior program identity as well as its final value. A function/test-to-data replacement must either have a declared supported program transition or refuse and restore. Account for every unmatched Var independently. Group generated host-bound members only by positive declaration provenance supplied by the analyzer; until that exists, constrain the exception to one proven record/type declaration and refuse mixed/ambiguous forms. Do not infer ownership from helper-name patterns or a form-wide boolean. Required proof: function→data/nil, one record, two records, and record plus unmatched dynamic definition.

## P0-B — Generation filtering does not observe all metadata mutations

**Source-confirmed at the dependency seam.** The new metadata comparison only runs over `turn-interns`, which excludes Vars whose metadata lacks the current generation (`eval.clj:424–435`). SCI root writes do copy inherited Vars and stamp the generation (`reference-code/sci/src/sci/impl/evaluator.cljc:25–55`, `utils.cljc:362–379`). Metadata writes do not use that mechanism: `sci-alter-meta!` and `sci-reset-meta!` call core mutation directly (`namespaces.cljc:852–861`), and SCI Var's `alterMeta`/`resetMeta` mutate its metadata in place (`lang.cljc:174–180`). `with-writeable-var` checks built-in protection, not generation ownership (`impl/vars.cljc:283–293`).

Consequently an inherited, writable SCI Var can change metadata without changing the namespace map or entering the generation-owned snapshot. The held map also holds that same mutable Var; it is not a saved metadata value. Separately, even a generation-owned Var can disappear from detection after `(alter-meta! #'kept dissoc :sci/generation)`. The new regression changes only `:doc` on a Var already defined in that fork, so it covers neither case. This finding concerns writable SCI Vars; compiled JVM Vars have a separate explicit metadata-write refusal.

**Smallest fix:** make SCI's existing metadata-mutation entrance obey its root-write ownership rule, including context-local resolution of the changed Var and preservation of dependency-owned generation metadata. The evaluator can then retain and compare immutable metadata values through the existing snapshot. If that dependency change is deferred, explicitly refuse unsupported inherited program-Var metadata mutation before it occurs. A larger per-form scan would still fail to undo a shared mutation after other contexts observed it. Prove sibling isolation, inherited `:doc`/contract changes, and metadata replacement/removal of the generation key. Do not describe the current map-identity optimization as a complete mutation detector.

## P0-C — A name match is not exactly one analyzed declaration

**Source-confirmed.** `definition-rows` uses `some` to take the first row with the qualified name (`eval.clj:650–656`). It does not require a unique match. `source-rows` preserves a vector of analyzed declarations (`src/seon/fn.clj:1436–1454,1493–1505`); this evaluator does not reject two occurrences of the same identity. A before/after map also has only one slot for that identity.

A form with two contracted `defn`s for `f` can therefore have one observed changed Var, two analyzed rows, and a selected row describing the first body while the Var runs the last. An indexed `defn f` followed by an `eval` redefining the same `f` is the stronger source-provenance case: a matching source name does not establish which declaration produced the final root. Runtime contract metadata is merged into the selected row, but runtime metadata does not prove its source/body (`eval.clj:658–684`). A valid contract on both bodies does not resolve the mismatch.

**Smallest fix:** first require exactly one analyzed occurrence per changed identity, before selecting it, and report repeated occurrences even when their symbol set has size one. For S1's bounded constraint, admit only definitions whose executed declaration has established source provenance; refuse ambiguous extra definition events for the same identity. Prefer evidence from the existing SCI definition/analysis seam, not a second evaluator or a second macro expansion. Merely replacing `some` with a map keyed by symbol would hide the same duplication. Required proof: same-name double definition, indexed-plus-dynamic overwrite of that name, and row/source/behavior equality after reacquisition.

## P0-D — Settlement restores the candidate, not the context that accepted it

**Source-confirmed; acknowledged in the landing.** This is on the ordinary product path, not an unrelated future candidate feature. `evaluate-sources` calls `evaluate-for-install` and `gate-function-install` (`turn.clj:4719–4734`). The latter calls `accept-candidate!` before settlement (`:3287–3296`). Acceptance transfers roots, caches the function, marks it installed, arms it and advances the retained context's projection (`eval.clj:4207–4224,4253–4267`).

The evaluation's new `:restore` closes over the context originally evaluated (`eval.clj:3993–4009`), which is the candidate for reader-recognized function definitions (`:4247–4251`). `accept-candidate!` does not replace that restoration with one covering the recipient. The new reverse traversal (`turn.clj:4823–4833,4931,4977–4979`) therefore cannot undo the transferred root, installed-function state or projection. A refused batch can leave retained execution ahead of durable program rows.

**Smallest fix:** keep the batch's speculative definitions in the existing execution fork and make transfer into the retained recipient occur only after successful settlement, preserving sequential visibility of earlier forms inside that fork. If pre-settlement transfer must remain for this slice, its owner must carry restoration for the actual recipient, including every function/projection/install state it changes, under the same acquisition fence. Do not add a second independent undo system. The landing's claimed S1 exclusion does not satisfy design §2.2 rule 4(d) or the S1 row, both of which expressly require settlement restoration.

Required proof is one **ordinary turn** with a green candidate followed by a batch refusal, checking retained context, candidate, row, contract and an affected caller. Calling the candidate's restore member directly proves only its local effect; the current regression at `entry_gate_test.clj:164–172` is useful but insufficient.

## P1-A — Restoration loses mutations to Vars subsequently removed

**Source-confirmed.** Root/metadata restoration iterates only the after-intern map (`eval.clj:519–534`). Removed entries instead reinsert the prior Var object from the held namespace map (`:535–540`). If the current generation already owned that Var, redefining it mutates the object in place; removing it then excludes it from the first loop. Re-interning the held object restores the **new** root, not the separately captured original root/metadata.

Distinguishing sequence: begin with a generation-owned, row-backed `f`; evaluate a form that replaces `f`, unmaps it and then throws. The before-owned map has the old root, but the removal branch never reads it. This also affects a refusal that removes such an already-mutated Var.

**Smallest fix:** derive restoration over the union of changed and removed identities, using the before-owned root/metadata whenever the held Var was mutable in this generation. Reinsert the Var and restore its captured value, retaining identity for existing callers. Do not regard a held namespace map as a deep snapshot. Also install the failure observation for top-level unmap forms: `:4010–4016` currently skips it, although the reader explicitly leaves unmap arguments executable (`reader.cljc:394–404`). A throw from an argument after a program mutation otherwise has no restoration callback. Finally, failure restoration should use the held request database when no connection is supplied; the current `when-let connection` at `:3909` silently skips that supported evaluation shape.

## P1-B — Failed restoration is not a complete unavailable transition

**Source-confirmed.** The catch in `restore-or-unavailable!` covers applying already-produced steps (`eval.clj:585–587`). Building those steps, taking `turn-interns`, running `restore?` and validating the helper's inputs occur before that catch (`:4006–4009`). A failure in those operations need not mark execution unavailable.

Even when marking succeeds, it returns nil and stores the diagnostic only under `::unavailable-execution` in the SCI env (`:580`). Admission rethrows the original failure; settlement reports its original refusal. A repository search at the reviewed pin finds only the marker writer and the subsequent-entry reader (`:3975`). There is no new status/durable-fault delivery consumer. Moreover, automatic acquisition/regeneration precedes that entry check (`:3954–3975`), and regeneration resets the whole env from a fresh fork (`:2716–2727,2746`). The unavailable marker is not reliably preserved across that path.

**Smallest fix:** put snapshot/delta construction and application under one restoration failure boundary. Return or throw the named unavailable error immediately with both the original refusal and restoration cause retained as ordinary evidence; route a restoration core fault through the existing fault/status owner. Fence further work before acquisition can erase the state, and clear it only through the existing explicit release/reacquisition lifecycle. Include no-op restoration, planning failure, application failure, intervening acquisition, next-form refusal and visible fault evidence in the proof. This does not require a new status registry.

## P1-C — Keep accepted-source analysis; reject unmatched delimiters at the reader

**Source-confirmed and historically reproduced in the landing.** d37adee4a correctly gives the analyzer the reader event's source (`eval.clj:4103–4106`). Analysis must describe the actual accepted form, not malformed suffix bytes. Reverting that fix would revive the parser failure.

The reader nevertheless deliberately drops closer-only error spans (`src/seon/sci/reader.cljc:816–821,862–866`). `one-event` then sees one valid event and accepts it (`eval.clj:800–801`). The new test asserts successful execution of a definition with a trailing `)` (`entry_gate_test.clj:140–142`), establishing silent syntax repair as desired behavior. Clojure's unmatched-delimiter reader throws instead (`reference-code/clojure/src/jvm/clojure/lang/LispReader.java:1372–1377`). This is a pre-existing reader defect newly entrenched by the regression, not a reason to send raw invalid bytes back to clj-kondo.

**Smallest fix:** preserve the ordinary reader error event for the unmatched closer, with its source/cursor. In a single-form request, refuse the malformed whole input before execution. In a multi-form REPL stream, preserve the completed form and the subsequent reader error in order; do not report the submission as wholly successful. Change the regression to distinguish accepted-form analysis from complete-input validity. Reuse the current reader/error path, with no extra scanner. The fallback to raw source is acceptable only if absence of event source has a declared legitimate case; otherwise make that missing evidence a reader refusal.

## P1-D — Armed cost and retention remain acceptance evidence, not measured passes

The landing measures an **unarmed scratch copy**: contracted definition 22.4 → 23.5 ms (+1.1 ms, about 4.9%); arithmetic 2.41 → 2.54 ms (+0.13 ms, about 5.4%). Ten runs, no retained measurement script or allocation/retained-memory result in these commits. These figures do not measure installed armed helper calls.

The actual algorithm walks O(namespace count + entries in namespace maps differing from the acquired base + generation-owned Vars); sorting definitions adds O(changed definitions log changed definitions). Maps differing from the acquired base include earlier private changes, not just this form. The removal scan adds an O(namespace count) comparison plus changed-namespace entries and row reads per removal. `program-definitions`, `admission-request` and restoration contracts validate intern maps repeatedly; `:map-of` namespaces checks the complete supplied namespace map. `:restore` is attached even to an ordinary read and captures before-state until that evaluation is released. Neither aggregate armed validation nor retention follows from the +1 ms measurement.

**Smallest fix:** retain the contracts, measure their real installed cost before optimizing, and compute/reuse the one form's immutable change/restoration data rather than repeatedly deriving it. Attach restoration only when the form actually changes relevant state. Root should compare parent/change with the same armed inputs at small and accumulated-private-state sizes, recording per-owner timing, allocation and retained memory through batch release. Treat >20% or >50 ms regression as the existing gate requires. No performance claim is made by this review.

## P2-A — Point-free definitions and macros should be indexed, not permanently outlawed

The current refusal is safer than admitting an unindexed change, and it is consistent with a **temporary** one-analyzable-declaration constraint. It is too restrictive as the agent language: `(def f (comp ...))` is already a top-level form, and rewriting it as a `defn` is not a universal semantic equivalence for closures, captured state, arities or function identity. The current error's instruction to move it to a top-level form (`eval.clj:595–600`) is therefore misleading.

**Smallest correct next semantics:** extend the existing analyzer's declaration classification, not the gate's exception list. `seon.fn/function-definition?` currently relies on literal-def/arglist/defmulti analysis (`src/seon/fn.clj:259–263`); `var-row` is the existing row constructor (`:682,750`). Combine one analyzed `def` initializer with the observed function root and its explicit declared contract to produce the ordinary function row, including original initializer source and its dependencies. Evaluate the initializer once and arm that exact result. A function-valued `def` without a contract still gets the missing-contract refusal; ordinary data defs remain private. Do not invent a wrapper body or fabricate source from a root object.

For macro-generated definitions, use the existing analyzer's supported macro analysis where it provides a complete declaration; otherwise retain the actual once-evaluated expansion/provenance from SCI and feed it to that same row owner. Preserve originating source/resolver facts. Never expand the macro again merely for indexing: it may have effects. Until that seam is proven, refuse by named unsupported analysis with an accurate remedy. Dynamic `eval` remains refused when its executed declaration has no trustworthy analyzed source. This is a small sequence of owner cuts, not a demand to implement arbitrary dynamic publication in S1. Same-namespace qualified-name doubling remains the separate `seon.fn` defect already acknowledged by the landing.

## P2-B — Contracts and errors: present, but tighten the values they promise

All eleven new private evaluator helpers have authored `:malli/schema`: `program-definitions`, `row-backed?`, `removed-interns`, `program-restoration`, `apply-restoration!`, `execution-unavailable`, `restore-or-unavailable!`, `unindexed-change`, `multiple-declarations`, `program-identity-name`, `admit-program-change!`. The additional public `sci-var?` and turn helper `restore-evaluations!` are also contracted. This is source evidence, not proof each was compiled/armed. `collect-contracts!` does include private namespace Vars (`src/seon/instrument.clj:815–825`). The modified pre-existing `definition-rows` still has no inline contract in this diff; eleven newly authored contracts do not certify all touched helpers.

The right dependency is already used: contract conversion/projection validation now happens inside `admit-program-change!`'s try, followed by `install-function-contract!` (`eval.clj:662–682,707–742,973–992`). Seon's instrumentation calls Malli `m/-instrument` (`instrument.clj:706–714`); Malli compiles with `schema` and validates input before applying the function (`reference-code/malli/src/malli/core.cljc:3131–3152,2202–2231`). Keep that composition.

Small shape corrections:

- `restoration-step` makes every key optional and permits an arbitrary-length symbol path (schema `:325–339`), although the consumer needs either a Var/root/metadata triple or a one/two-element namespace path operation. Declare those real alternatives, including an unbound prior Var if supported; `{}` is not a valid restoration step.
- `unindexed-change` accepts an empty sequential input but promises a nonempty output set; `multiple-declarations` likewise permits no declared members while its error schema requires at least one (`eval.clj:591,606`; schema `:362–372`). Make the preconditions explicit. Preserve repeated source occurrences for P0-C rather than reducing the explanation to a set.
- Generic `:map` row slots and `[:vector :map]` admission output promise less than the row consumers require; reuse canonical row/namespace shapes. Selector `:any` returns should be a justified truthiness boundary or a predicate contract. The root-value `:any` exemptions are appropriate: values must remain arbitrary and must not be traversed.

The three error schemas are flat named extensions of `:seon.error/base`, with distinguishing `unindexed`, `declarations`/`removals`, and `unavailable` members (schema `:362–376`). That is the right shape; no discriminator stamp or copied global union is needed. `seon.error.refusal/diagnostic` consumes the throwable into class/whole cause chain (`src/seon/error/refusal.clj:91–120`). Keep it. Improve the diagnostic facts to identify the source occurrence/expected operation and correct the top-level remedy; P1-B is the material missing failure-delivery behavior. The evaluation envelope may contain arbitrary values, so adding a registry-wide error union to its result is not the fix.

`evaluate` carries exactly `:seon/tasks #{"132fee0467c8"}` (`eval.clj:3705–3708`), and the two relevant tests carry that task metadata. Task ingestion/adoption is not independently verified here. The loaded skills contain stale source offsets and, in the data-oriented skill, a claim that undefined contract slots refuse: at this pin `schema/internal.cljc:309–347` explicitly makes undefined slots advisory. That correction belongs to the skill owner; this assignment permits only this review file.

## Size, smallest landing and proof boundary

Measured from the actual commit patches, excluding landing prose:

| Material | 91eb8e4a5 | d37adee4a increment | Combined |
|---|---:|---:|---:|
| Clojure source | +316 / −82 | +4 / −1 | +320 / −83 |
| Schema | +67 / −0 | 0 | +67 / −0 |
| Tests | +65 / −10 | +6 / −1 | +71 / −11 |
| Total | **+448 / −92** | **+10 / −2** | **+458 / −94; net +364** |

The S1 estimate was +45. Contracts and useful docstrings explain some growth; they do not justify the missing correctness proof or demonstrate that this is the smallest composition. The cut removes the old gate and changed-intern helper, but keeps both candidate transfer and local restoration without a complete shared settlement boundary. Root should rule on a corrected, repriced cut before accepting growth, not book future S2/S3 deletions against it.

Recommended order: repair complete mutation/provenance accounting; put restoration over the actual mutated execution and all failure points; then fix reader error retention and measure the installed armed component. Preserve the existing definition/row/contract owners. Defer general macro support, deletion API unification, durable-result work and write-back to their named slices. The system REPL can remain the primary input while each component earns its guarantee.

Root's focused evidence should add the distinguishing cases above to real SCI/armed ordinary requests, including a turn-level settlement refusal and restoration-fault visibility. Existing green counts are valuable; they cover neither the whole change set nor this transfer boundary. The supplied three pre-existing eval-test reds are not attributed to these commits. No foreign lane breakage blocked this static review.

Only this document was authored. Production source/test delta for the review: **0 / 0**. No JVM health, load, publication, browser paint, timing or memory pass is claimed. Document checks cover whitespace, required findings and file scope only. Commit uses `git commit --only` for this path; its hash is reported with delivery. The path is released after that commit. Unrelated `reference-code/http-kit` state is preserved; no push or merge is authorized by this review.
