---
title: "Program facts: one source derivation, live bindings in the context"
type: research
date: 2026-10-16
status: independent proposal; experiments complete; implementation not started
lane: pf-plan-astra
source-basis: a9e4e9904820739c583b39aa9549cec453569da0
live-basis: 4f15bf3d77aa98c83380c88efa693f072215c6bd
---

# Decision

Today a declaration passes through source analysis, mutable SCI namespace inspection, stored file attributes and a second installation interface. Make `seon.fn` the sole producer of declaration facts from the evaluated source and its analysis; make SCI the sole owner of the evaluated binding; make the file artifact the sole owner of location. Keep the one REPL fold and its one recording transaction.

The branch's declaration is **the source that actually defined it, interpreted in the namespace context at that occurrence**. Its durable representation is the program entity. A physical file is the input to publication and the output of write-back; it cannot be the authority for a definition that has never been written to disk. A live Var is an execution object, not an alternative declaration document.

“One place” means one derivation and one owner per field, not pretending a compiler takes only an unqualified string. Two experiments below rule out the stronger simplifications: static analysis includes a definition that never executes, and a session alias can affect an executed definition without appearing in the previous namespace entity. Keep execution evidence and lexical resolution inputs; stop reconstructing authored declarations from the final runtime namespace.

The principal composition is the existing source-analysis builder, SCI's parsed-form evaluator and fork, Datahike's transaction, and the existing file artifact/write-back machinery. No additional program store, interpreter, observer, reconciliation service or retry path.

Phase status: P1 pending; P2 pending; P3 pending; P4a pending; P4b pending; P5 pending. This is an independent proposal for the hybrid, not authorization to implement it. No contemporary Opus plan was read.

## Evidence and limits

Read `CLAUDE.md` completely and the complete dig at `docs/research/agent-platform/2026-10-16/digs/program-facts.md` (filed in 4683a909d). Read the producer/caller seams named below and the relevant history of 7f2558dbe, 504ff11d0, 10f6a4d5a, 4f15bf3d7 and 2af8586ec. The earlier one-path specification supplies the conditional-definition, macro-origin, captured-local and partial-failure requirements; this plan preserves them.

Default was running from the committed archive above, PID 35580, start instant 2026-10-16T02:59:30.495Z. Both `bin/seon status` and MCP `runtime_status` answered. Initial health was `:faulted`: 29 open faults, 399 errored receipts, no missing readiness layers, no reported regressions. Heap live was about 1.20 GB of the 4 GB cap. This is degraded evidence, not a healthy-system certification. No default stop, reset, restart, JVM definition or source reload was performed.

SCI probes ran as `pf-plan-astra`; the measured nested requests ran as the lane's separate scratch agent `pf-plan-astra-cost`. JVM probes used agent root, cluster default, mode jvm and an explicit connection for database reads. Their bodies were read-only; the MCP entrance still records its ordinary evaluation evidence. Tool result handles below are historical evidence, not durable object restoration promises.

### Experiments

1. **Namespace source versus runtime state.** On the scratch branch:

   ```clojure
   (ns pf.plan.astra "Authored namespace."
     (:require [clojure.string :as s]))
   (def cell (atom 0))
   (defn shout {:malli/schema [:=> [:cat :string] :string]}
     [x] (s/upper-case x))
   (swap! cell inc)
   ```

   The next request pulled `:seon.ns/source`, `:seon.ns/doc`, `:seon.ns/requires` and aliases through `seon.db/pull`. Source was regenerated as `(ns pf.plan.astra\n  (:require [clojure.string :as s]))`; the doc key was absent. The value declaration held `(def cell (atom 0))`, arglists `"()"`, and digest `1663012c679a75d216a8f91a8534f554143bdef71792c5917daf965025c3a28f`; its live value was an atom containing 1. The request cost 1580.3 ms, 81,247 calls and 1889.4 MB allocated. The contracted definition alone reported 693 ms and 497.9 MB. Existing tasks: `seon.program/an-evaluated-namespace-keeps-its-read-source-and-call-edges`, `seon.turn.batch/a-repl-namespace-row-keeps-neither-doc-nor-source`, `seon.repl/the-first-requests-on-a-new-agent-branch-are-sub-second`.

2. **Unused versus used session aliases.** Subsequent requests evaluated:

   ```clojure
   (require '[clojure.set :as unused])
   (alias 'extra 'clojure.string)
   (defn plain {:malli/schema [:=> [:cat :int] :int]} [x] x)
   ;; separate request after the namespace observation
   (defn extra-shout {:malli/schema [:=> [:cat :string] :string]}
     [x] (extra/upper-case x))
   ```

   Before `extra-shout`, SCI's aliases were `(s unused extra)` but the stored namespace had only `s`. Afterwards it also had `extra` and its regenerated source contained two requires of `clojure.string`. `unused` stayed absent. Result e1ac4fb59f972 precedes the used-alias definition; eccbc0635736a follows it. Preserve the useful distinction, but produce it from the declaration's analysis, not a final namespace snapshot. An explicit ns form must also preserve unused authored requirements: usage is not a reason to delete authored source.

3. **Text alone does not identify executed declarations.** Read-only JVM probe:

   ```clojure
   (let [a (with-in-str
             "(ns pf.source) (if false (def not-run 7) :skipped) (defn f [x] x)"
             (clj-kondo.core/run!
               {:lint ["-"] :cache false
                :config {:analysis true :output {:format :none}}}))]
     (mapv #(select-keys % [:ns :name :row :col :end-row :end-col :defined-by])
           (get-in a [:analysis :var-definitions])))
   ```

   Analysis returned both `not-run` and `f` (5 ms). SCI `(if false (def not-run 7) :skipped)` returned `:skipped`; the next request's pull of `pf.plan.astra/not-run` was nil. A design deleting `seon.turn.batch/join-write` or recording every analyzed definition would regress this. Keep ordered execution occurrences, including successful writes before a later throw.

4. **Analysis already identifies used alias targets.** Read-only JVM analysis of `(ns pf.source (:require [clojure.string :as s])) (defn f [x] (s/upper-case x))`, with namespace/Var usage analysis, returned namespace usage `{:from pf.source :to clojure.string :alias s}` and Var usage `{:from pf.source :from-var f :to clojure.string :name upper-case :alias s}` (7 ms, result e377c2b54d5a8). `seon.fn.analyzer/with-namespace-clauses` already consumes namespace, refer, import and rename analysis. Its source has Java class usage support; the dig's quoted comment that analysis records no class usage is not a sound reason to carry every import forever. Keyword and renamed-refer cases still need their proof rows before deleting the old selection.

5. **A digest is not the identity of an executed initializer.** After experiment 1, a later request evaluated `(def cell (atom 0))` again, then:

   ```clojure
   {:current @cell
    :acquired (sci.core/eval-string*
                (seon.sci.eval/base-ctx (seon.db/db))
                "@pf.plan.astra/cell")}
   ```

   Result eb6727b701a02 was `{:current 0 :acquired 1}` (5 ms, 4.8 MB). The same source executed twice creates two bindings; `seon.sci.eval/keep-value!` preserves the first cache entry. Filed on the lane's branch as `seon.sci.eval/an-identical-value-redefinition-keeps-the-previous-live-value-in-acquisition`, task handle `01837b74c8bf`. The plan retains the reproduction because that branch-local task is not a claim of publication to main.

6. **Whole-request measurement.** The exact measurement shape, run from the planning agent's SCI context, was:

   ```clojure
   (let [m (seon.profile/measure
             (fn []
               (seon.mcp/call
                 "{\"name\":\"eval_clj\",\"arguments\":{\"agent\":\"pf-plan-astra-cost\",\"cluster\":\"default\",\"mode\":\"sci\",\"code\":\"(ns pf.plan.astra.cost)\\n(def v 42)\\n(+ v 1)\"}}")))]
     (dissoc m :seon.profile/value :seon.profile/called
               :seon.profile/ranked-by-calls))
   ```

   The first request measured 387.53 ms, 59,965 calls, 460 reads, 3 GC collections/9 ms, 96,862,704 allocating-thread bytes. Its JVM window allocated 438.72 MB and used 1470.27 CPU ms. The twelve-definition request substituted twelve forms `(defn f0 {:malli/schema [:=> [:cat :int] :int]} [x] x)` through `f11` in that same agent. It measured 2237.75 ms, 394,636 calls, 1101 reads, 4 GC collections/26 ms; 9,084,128 entrance-thread bytes, but **2257.89 MB JVM-window allocation**, 3727.17 CPU ms. Result handles ee45e45264d5e and eba1fe6f532fe hold the measurements.

   The second profile names `seon.db/vector-query-call` 198 calls/756 self ms, `seon.db/transact-call` 1/166, `seon.db/declaration-content-key` 26/137, `seon.schema/projection-with-declarations` 24/115, `seon.schema/admit-declarations!` 24/84, `seon.db/with` 23/85, and `seon.fn.analyzer/invoke-kondo` 12/69. The await entry's 2218 ms is waiting for the worker, not its CPU. JVM-wide counters include other lanes; caller-thread allocation omits the worker. These are baseline observations, not a quiet parent/change comparison or proof of causality. They reproduce the cost class, not the exact filed 1.2–1.4 s numbers. A subsequent request on the measured agent returned `{:v 42 :functions [9 9 9 9 9 9 9 9 9 9 9 9]}` when calling f0 through f11 with 9 (result e621f4430f4ee).

Probe mistakes were surfaced, not treated as evidence: an unquoted wildcard pull became a function in its selector and was refused; quoting the selector returned the namespace facts above. A call to `seon.fn/analyze-forms` with a map was refused by its vector contract; the corrected explicit-database/vector call took 13.42 ms and returned `[[{} nil]]`, consistent with that API enriching a supplied declaration rather than constructing one. Reuse the underlying builder, not a guessed public signature.

### Corrections to the dig

`seon.sci.eval/install-evaluated-rows!` already takes the `agent-ctx` path to `seon.sci.eval/record-settled-rows!`. The latter records the acquired basis and committed declarations without evaluating their source. The alternate path transfers roots then calls `seon.sci.eval/install-row!`; its fixture callers remain. Therefore the current defect is multiple ownership/install interfaces and value recovery, **not evidence that every ordinary settlement now evaluates every def twice**.

`seon.cluster.source/file-rows` already analyzes held file text. Candidate write-back already uses those declarations when it has `ours`; `seon.cluster.source/file-digests` remains the database-link alternative. Reuse and finish the file-derived path; do not build another indexer.

## The ownership contract

| Value or field | Sole producer/owner | Consumers and recomputation |
|---|---|---|
| Exact authored source, definition kind, metadata, contract syntax, argument lists | The source analysis builder in `seon.fn`, using the held form/source and clj-kondo analysis | Program writer, documentation, test selection, write-back; source or relevant resolution input changes |
| Calls, references, keywords, host-bound classification, analysis digest | That same analysis result | Reach, instrumentation, execution selection; changed source or analyzed dependency content |
| Which declaration executed, ordering, deletion, macro expansion origin | SCI's existing producer observations, handed to the fold as immutable evaluation evidence | Select the analyzed occurrence and fold the delta; never reconstruct declaration metadata from a Var |
| Namespace source/doc/clauses | The namespace source value, analyzed by the same builder as file publication | Namespace entity, reader/acquisition, write-back; explicit ns edit or an actually used new resolution incorporated into that source |
| Definition/behavior identity | `seon.program/definition-digest` and behavior digest over the builder's complete semantic input | Writer, diff, tests and acquisition; no query of current SCI or file position |
| Current root value and runtime metadata | The agent's SCI context | Calls and inspection; only an evaluation/reload in that execution changes it |
| File path, span and file digest | File artifact for exact bytes; write-back chooses a destination for a new declaration | Source display, edit, publication and write-back; file content/destination changes |
| Accepted entities and branch history | Datahike through `seon.db` | Every durable reader; one recording transaction per eval request |

Keep existing identity and entity schemas (`:seon.program/row` is the installed name, not new vocabulary), `:seon.batch/form`, `:seon.batch/entry`, `:seon.batch/write` and `:seon.batch/change`. Extend the existing transient analysis value only where a genuinely missing field is proven. Its contract separates syntax/analysis from a live binding: live objects cannot occur in the entity producer's output schema. Compile contracts with `malli.core/schema`; validate with `malli.core/validator` at the existing producer/writer boundaries. No new declaration admission tier.

### Source, reading and analysis

Use `sci.core/parse-next+string`/the existing read-stage to retain the form and exact source together, then `sci.core/eval-form` through `seon.batch/evaluate-batch`. Pass the already held form to semantic consumers. Run clj-kondo on those exact bytes; its static parser is not a second semantic read or evaluation. Do not run a tag reader, macro or initializer again to rediscover metadata. Remove Seon's additional source parse in `seon.program/source-resolver-names`; collect lexical usage once in analysis. `seon.fn.analyzer/parse-facts` also currently parses text separately from the kondo run: consolidate its needed syntax facts at the analyzer owner rather than describing it as already free.

The runtime occurrence join remains. An untaken definition, an executed definition capturing locals, a macro-generated definition, `declare`, and an arbitrary `intern` are different Clojure operations. Use the current origin/expansion observations and current private-definition behavior. Do not promote dynamic root/metadata mutation into a new authored declaration. Do not replace the exact-origin join with a name match or a guess.

Move the entity construction now split across `seon.turn.batch/analyzed-entities` and `seon.turn.batch/write-entity` into the builder already used by `seon.fn/analysis-entities-by-file`. The fold selects executed entities and computes transaction operations; it does not construct another declaration. Publication, REPL recording and write-back reindex call that builder with different source containers. Files may contain unevaluated forms for static publication; execution evidence and static declarations remain distinguishable inputs, rather than pretending indexing executed the file.

### Namespace semantics

An explicit `ns` form owns its exact authored text, doc, metadata, require order, aliases, refers, imports and fixtures. Its clauses come from that form's analysis, not all namespaces SCI touched. `in-ns` selects a namespace; it does not invent an authored ns form or replace one.

A session `require`, `alias`, `refer`, `import` or `ns-unalias` affects resolution in that context. It does not by itself edit a durable namespace declaration. When a declaration actually uses a new resolution, the source owner incorporates that requirement into the namespace source, retaining existing authored text. Use `rewrite-clj` for the source edit and the analyzer's occurrence facts for the requested clause. Derive structured namespace facts from this resulting source value once; do not separately merge component facts with a different source string. `seon.fn.analyzer/namespace-context` and `seon.fn.analyzer/with-namespace-clauses` own the grammar; clj-reload's require parser supplies dependency semantics, including `:as-alias` not loading a namespace.

The evaluator's resolution context is a **transient input to analysis at the occurrence**, not durable namespace state. Capturing the final resolver state after a multi-form request is wrong: `(defn f [] (x/a))` before an alias change and a definition after it have different inputs. Preserve the source occurrence's analysis context, and reuse its resolved uses everywhere. That is the necessary exception to “only the live value comes from SCI”: execution and lexical resolution are evidence too. No Var root, incidental host import, loaded namespace inventory, instrumentation metadata or environment map becomes a program fact.

Changing the durable namespace source can change other declarations' interpretation. Reanalyze precisely the declarations using the changed alias/refer/import and feed those changed entities through normal acquisition and reaching tests; do not merely stamp their old bodies with newly calculated digests. The stored source program then has one meaning, including after reload. A transient alias change unused by a durable declaration changes none of those facts. Preserve actual Clojure outcomes for self-require, quotes, auto-resolved keywords and renamed refers in the table proofs. A clash is resolved by the namespace source edit and its normal program analysis, not a separate conflict classifier beside the writer.

This deliberately chooses **explicit namespace source plus the dependencies declarations actually use**, rather than explicit-ns-only. Explicit-ns-only would be simpler but breaks the installed require-then-define workflow and the write-back proofs named in P2. Recording the entire live resolver is larger and has already produced the reported failures.

### Value definitions and acquisition

`(def cell (atom 0))` has a durable declaration describing an initializer and a live root produced by this execution. Equal source/digest does not mean equal roots, nor does a changed cell mean a changed declaration. Keep the current root only in the agent's context; settlement acknowledges that context's accepted source basis and never re-installs its own definitions.

Delete `::kept-value` and its compartment/digest key. Reacquiring program changes advances the held context; it does not recover live roots from a content cache. `sci.core/fork` is the explicit operation for a consumer needing that context's bindings. A fresh independent execution starts from program declarations and evaluates its own initializers once. It cannot recover another execution's private objects from a database value. Restart loses them; reading a lost result reports it gone. These are different operations and must be represented by their actual arguments: database for program, context for live execution.

SCI fork preserves Var isolation but does not deep-copy an arbitrary atom stored in a root. Do not claim it does. Tests needing independent mutable state start an independent execution; a fork intentionally retaining a live context retains its referenced objects. A chain handoff, test world and merge world must explicitly choose which operation their existing contract requires. Do not copy objects or introduce a per-branch value cache to blur the distinction.

Convert the non-agent-context callers of `seon.sci.eval/install-evaluated-rows!` to the context that did the evaluation. Its surviving role is acknowledging committed declarations/basis. `seon.sci.eval/install-row!` still has legitimate acquisition callers and is not in the deletion list. Content caching remains valid for immutable source analysis and execution plans; cache a value-bearing execution only as that execution, never as the meaning of a definition digest.

### Location and write-back

Use the existing file artifact, keyed by its exact byte digest, to derive declaration identity → path/span. A function/test entity has no authoritative file location. Moving or adding a line above it changes the artifact's locations, not the declaration. The namespace/file relation is likewise obtained from source artifacts, not reconstructed from the last REPL ns entity.

At write-back, hold the git basis/tree and the corresponding file artifacts. For an existing identity, locate it in that basis, splice its accepted source, and derive candidate declarations from the candidate bytes. For a new identity, use the declared write-back destination or the namespace's actual source artifact. Do not guess path spelling from the symbol; files may contain multiple namespace sections. Unchanged siblings come from the held file bytes, deletions from the accepted delta. Compare the resulting declaration map with the accepted source program. This is the file-owner model already partly implemented by `seon.cluster.source/file-rows`, `seon.cluster.source/committed-bytes` and `seon.cluster.source/conflicts`.

Publication derives file artifacts once for changed bytes. Existing content caching shares them across branches. Reads needing a location query that derivation; evaluations do no file lookup. Retire `:seon.fn/file` and `:seon.fn/form-span` from declaration persistence only after their consumers use this owner. File entities and their own path/digest fields remain. There is no new durable location registry.

## Library seams and cost

Pins inspected: SCI eb3290d5c5ed72133ae1947492ecd324413868ed; clj-reload 61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa; clj-kondo eb245d8260736ca463cda85219b6fc3ae73699c3; Malli dd13ff81139159778edb0a67b491eccc0baa991d. Seon's gitlinks, not current web releases, are the authority for these experiments.

| Seam | Guarantee and use | Cost/recomputation |
|---|---|---|
| `sci.core/parse-next+string`, `sci.core/eval-form` | Held parsed form plus source; evaluate the form without rereading text | Source length and actual evaluation work, once per submitted occurrence |
| `sci.core/fork`, `sci.core/freeze` | This maintained fork supplies generation-isolated Var bindings and a held environment value; not object deep-copy | Fork/context ownership, not a whole-program rescan; verify against this pin |
| `clj-kondo.core/run!` | Source analysis with locations, namespace usages and Var usages; does not prove execution | Changed analyzed source and dependency inputs; one result reused by all fact consumers |
| `clj-reload.parse/read-file`, `clj-reload.parse/parse-ns-form`, `clj-reload.parse/parse-require-form` | File owns ns/in-ns sections and explicit top-level requires; dependency graph excludes own require and `:as-alias` load edges | File bytes on a changed file; it is not a per-Var REPL persistence API |
| `seon.fn/build-artifact`, `seon.cluster.source/file-rows` | Existing first-party callers deriving declarations from held source | Changed file bytes at publication/write-back; no files per ordinary eval |
| `malli.core/schema`, `malli.core/validator` | Compile/validate the named producer value; no bespoke schema checker | Once per schema content, then the actual input/output |

Expected work for an eval request is proportional to submitted source, executed declarations, changed schema/namespace dependencies and the transaction delta. For zero program changes there is no declaration derivation. For twelve independent definitions there must not be twelve scans of the packaged schema population or whole namespace program. Reuse an analysis prelude and projection derived from the held basis; later forms still see earlier forms' changes. Do not batch across a semantic dependency merely to reduce analyzer-call counts.

P5 compares parent/change on the same quiet JVM with `seon.profile/measure` around the complete MCP entrance, not only a digest helper. Run the three-form first-request case and the twelve-definition case, fresh agent versus continuing agent separately. Include request-accounted allocation and JVM-window allocation, all validation/recording/printing, and check returned values/errors. Retain the exact submitted source and named hot functions. The target is the existing sub-second ordinary-request rule and work proportional to changed inputs. An improvement to the source builder alone does not justify claiming the 1.2–1.4 s issue solved.

## Phases and red proofs

Each phase is a loadable slice, with schemas and consumers in the same commit. Root coordinates occupied files and the batched restart; implementations use their own SCI branches or the host-bound candidate check as applicable. Existing tests below are proof targets, not claims that this planning lane ran them green. Where a current test already passes, the indicated added row must be red before implementation.

### P1 — One declaration builder

Convert `seon.turn.batch/analyzed-entities`, `seon.turn.batch/write-entity` and publication to the same `seon.fn/analysis-entities-by-file` construction. Hold source/form/occurrence analysis once; keep the exact execution join and removal fold. Contract the producer to return source facts without live objects or file custody. Move contract-fact derivation there, using authored contract data and the supplied registry rather than Var metadata.

Red proof: extend `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` with the documented namespace from experiment 1 and compare source, doc, metadata and semantic attributes after storage. Add conditional-definition and repeated-name occurrences to `seon.program-test/reader-events-have-one-canonical-declaration-entity`, exercised through the REPL for the execution assertion. Preserve `seon.fn-test/static-index-and-runtime-admission-agree-on-analysis-attributes` and `seon.fn-test/a-fold-derived-definition-has-static-index-edge-parity`. Existing filed namespace-parity red was reported by the dig on an older HEAD; experiment 1 independently reproduced the symptom on the live archive.

Boot boundary: no public builder disappears until every file/publication/REPL caller uses its replacement in this commit. No changes to the task lifecycle. Remove the duplicate construction body immediately; do not leave two producers for a later cleanup.

### P2 — Namespace source owns its facts

Replace the live-resolver merge in `seon.turn.batch/namespace-facts` with P1's namespace source result. Feed used resolutions from occurrence analysis into the source edit. Change `seon.program/definition-digest` to consume that analysis's relevant lexical inputs; delete the reparse and whole-namespace selection. A durable resolver change reanalyzes affected declarations once, so the entity, its digest and acquisition use the same source meaning.

Red rows: explicit ns doc and exact source; an unused runtime alias; an alias used by a later declaration; two definitions separated by alias retargeting; `::alias/key`; renamed refer; class import; self-require; and a quoted qualified symbol. Extend `seon.turn.batch-test/a-retargeted-alias-leaves-no-stale-digest-for-the-test-world`, asserting behavior as well as no stale digest, and `seon.program-test/a-require-a-definition-does-not-use-leaves-its-digest-unchanged`. The doc/source case is experimentally red; the retarget/keyword cases must be run red before assigning their fixes.

Keep `seon.fn-test/an-alias-an-earlier-form-requires-resolves-in-later-forms-of-the-batch`, `seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`, `seon.cluster.write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form` and `seon.cluster.write-back-test/a-declaration-naming-an-unrequired-namespace-merges-with-its-ns-form-requiring-it` green. Remove the five namespace snapshot/merge functions in the deletion table in the same commit; runtime namespace installation still consumes source-derived data.

### P3 — One live binding owner

Advance the evaluating context from its existing bindings and accepted basis. Convert all callers of `seon.sci.eval/install-evaluated-rows!`, including contracts/documentation/test fixtures, before removing root transfer and kept-value recovery. Independently created execution evaluates source once; continuing execution retains its current objects. The runtime source of a root is the latest actual evaluation, not its content digest.

Red proof: add experiment 5 to `seon.turn.batch-test/a-value-def-init-runs-once-for-every-later-context`. Convert its fresh-database-context assertion to an explicit fork of the live execution when it expects the same object. Add independent execution and lost-context cases to that table; this is an explicit contract correction, not deletion of initializer-once coverage. Keep `seon.turn.batch-test/an-identical-value-def-in-two-agents-holds-two-values`, `seon.sci.eval-test/a-live-context-preserves-definition-value-class-and-metadata` and `seon.sci.eval-test/a-context-definition-survives-its-symbols-first-install` green. No cache eviction may cause a continuing execution's initializer to run again.

Boot boundary: update fixture entrances and execution-context consumers in the same commit. The existing loaded-Var/acquisition machinery stays. `install-row!` is not deleted; no host-bound code is interpreted merely to simplify the plan.

### P4a — File-derived location at every reader

Expose the location query over existing file artifacts and held publication/write-back basis, then convert source displays, edit, test runner, branch evidence and write-back. `seon.cluster.source/file-digests` becomes a read of the held artifact's declarations, not a query through declaration backlinks. Keep existing persisted fields readable during this conversion, but add no new writer or fallback that copies them into REPL entities.

Red row: extend `seon.turn.batch-test/an-identical-redefinition-leaves-every-owned-attribute-of-the-stored-row-unchanged` with a changed redefinition and a line inserted above it; assert that location comes from the current file artifact and definition meaning stays independent of position. Extend `seon.cluster.write-back-test/a-merge-writes-each-change-into-its-own-file` with a namespace containing multiple file sections so no guessed ns-to-path mapping passes. Preserve `seon.fn-test/an-edit-above-a-declaration-keeps-its-analyzed-source-digest` and `seon.fn-test/indexed-declarations-carry-exact-file-bytes`.

This is a mechanical caller conversion after the location interface/proof is settled. The census is 42 src/script/test files mentioning either location attribute, 17 in src/script, not a two-file fix. Split file-disjoint caller work into short tasks; readers use the new owner from their conversion commit. Neither code nor schema removes the old attributes before the last consumer moves.

### P4b — Remove declaration location persistence

Publish the declaration schema change and consumers together on a branch of the live store; remove declaration location datoms through the existing publication reconciliation, keeping file entities. Delete `seon.turn.batch/file-attributes` and its carry in `seon.turn.batch/entity-facts`. Keep exact replacement for semantic attributes; neither identical nor changed REPL definitions have file facts to lose.

Red proof: a new row in `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities` compares the complete declaration-owned facts without stripping file attributes; the same source from file and REPL agrees. The write-back location proofs from P4a run with declaration location fields absent. Preserve `seon.fn-test/a-declaration-answers-its-source-root-through-its-file` by converting its assertion to the location query, and `seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span` through the complete merge/write-back path. Schema publication/reload and a candidate archive boot are required before commit.

### P5 — Request cost follows changed inputs

Profile parent/change at the complete request entrance using experiment 6. Reuse the held projection and analysis inputs across independent forms; remove repeated full-population derivations at `seon.schema/projection-with-declarations`/`seon.db/declaration-content-key` only where the profile and their actual inputs prove redundancy. Keep validation at the existing writer. Do not substitute a cache keyed by branch or skip a check to achieve a timing.

Red proof: proposed new `seon.turn.batch-test/ordinary-declaration-requests-cost-their-changed-inputs`, attached to `seon.repl/the-first-requests-on-a-new-agent-branch-are-sub-second`. It submits the exact three-form and twelve-definition requests, checks their values/facts, and records the required `seon.profile/measure` parent/change evidence. Assertions for extra whole-population calls derive from the final measured cause, not an invented call budget. Existing `seon.program-test/definition-digests-stay-cheap-after-the-packaged-population-is-reread` remains supporting coverage. A no-gain or regressing candidate does not commit as a performance fix.

## Deletions and scale

Counted at source-basis above by top-level defn boundaries, including contracts/docstrings and excluding trailing blank/comment separators. These are removal candidates with their last-caller conversion phase, **not a net-diff promise**. The obsolete bodies total 14 functions / 225 physical lines. Do not delete a symbol merely because it appears here; the hybrid's narrow check must reproduce its caller conversion.

| Phase | Functions removed | Functions / lines |
|---|---|---:|
| P2 | `seon.turn.batch/namespace-entity` (7), `seon.turn.batch/live-bindings` (8), `seon.turn.batch/stored-namespace-row` (22), `seon.turn.batch/declared-bindings` (20), `seon.turn.batch/namespace-facts` (29) | 5 / 86 |
| P2 | `seon.program/source-resolver-names` (29), `seon.program/used-resolver-context` (27), after digest callers use the held analysis inputs | 2 / 56 |
| P3 | `seon.sci.eval/agent-value-definition?` (8), `seon.sci.eval/value-key` (5), `seon.sci.eval/keep-value!` (6), `seon.sci.eval/kept-value` (8), `seon.sci.eval/keep-evaluated-values!` (16), `seon.sci.eval/transfer-evaluated-roots!` (27), `seon.sci.eval/agent-value-definitions` (13) | 7 / 83 |

Additional reductions, not counted: P1's duplicate entity-construction bodies; the alternate installation branch of `seon.sci.eval/install-evaluated-rows!`; the `evaluated-value-bytes` cache-weight constant; the kept-value-dependent compartment contribution to program-context keys; P4b's file-attribute carry; obsolete schema members and fixture plumbing. `seon.sci.eval/binding-rows` still has namespace installation callers and is **not** counted as an unconditional deletion. `seon.turn.batch/join-write`, schema registration, removal validation, file reindex proof and one recording transaction remain.

The file-location conversion is the broadest work. A small initial optimization retaining a file carry is cheaper but cannot remove the ownership failure. A full virtual-filesystem/editor model would cost more and duplicate the program entity store. The recommendation is the middle option: one source builder and a file-derived location query, using the existing artifact model. Estimated scope is six loadable phases (P4 split into two), each decomposed into short file-owned tasks after the hybrid. No estimated implementation line count is presented as measurement.

## Acceptance and planning-lane verification

Before an implementation phase is assigned: name its red proof, reproduce its premise on the then-current runtime, resolve every quoted symbol/schema, and enumerate every caller of a removed symbol. P2's retargeting semantics and P3's context-versus-independent-execution distinction belong in the owning spec sections before their bodies change. A contradiction in either proof sends that phase back to design; adding resolver exceptions or another value-recovery layer is not a completion path.

This lane changes only this document. No production/test source or schema changed; source/test delta is 0/0. The listed tests are future proof obligations, not a reported green suite. The plan's probes demonstrate present behavior and costs; they do not demonstrate the proposed implementation. The new stale-value task remains on the scratch branch and is also preserved here for root to adopt.

Foreign boundary at intake: uncommitted changes in `src/seon/sci/eval.clj`, render source/schema/tests and task resources, plus dirty dependency checkouts. Source claims/counts use committed HEAD; live probes use the separately named archive. These unrelated edits were neither changed nor loaded. Documentation has no reaching test Var and must not widen program test selection; commit through the requested docs-only `bin/lane commit` path.

Planning-lane lint: `clj-kondo --lint src script test --cache false` completed in 8003 ms with **0 errors, 1805 warnings** (warning exit status 2). This is the shared-tree census, not zero findings. A global whitespace check also reported another lane's trailing blank line in `test/seon/competency.clj`; this document's whitespace check is separate. No unrelated lint or whitespace edit was made.
