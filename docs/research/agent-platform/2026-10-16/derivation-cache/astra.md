---
title: Derivations and context units — independent Astra plan
date: 2026-10-16
lane: cache-plan-astra
type: plan
status: proposed; scratch evidence recorded; implementation proof outstanding
---

# Derivations and context units — independent Astra plan

Seon already has content caches, revision aliases, read evidence and separately rendered web units. Make acquisition produce the immutable inputs a derivation consumes; retain the result once by those inputs, and assemble context and pages from independently printed units. Remove commit-wide recomputation and duplicate retention at their producers.

Status: independent proposal, not an installed performance fix. No Opus derivation-cache plan was read. Phase status: C0–C6 proposed; scratch experiments below completed. The task-bearing prototype has a measured warm improvement; a controlled process-cold parent/change win remains unproven and is a required implementation proof.

## 1. One rule

**A retained derivation is a function of its definition and all the values it consumed. Its content key names those values; read evidence proves that acquiring them from another database would still answer the same values. Location and observation time are acquisition information, not content.**

Conceptually:

```clojure
[producer-definition-and-dependencies
 explicit-input-values
 semantic-options
 [[source-role read-request read-result-content] ...]]
```

The key may contain small immutable values directly; use an already available structural digest for large admitted values. `seon.id/id` is the public structural-identity entrance; its private `seon.id/structural-digest` supplies the implementation. Do not print, traverse a whole database, or hash a whole lazy result to make a cache key. That digest ignores metadata: any output-affecting metadata, such as an explicit printing budget, must become a declared semantic input. Producer identity includes its reached definitions and relevant contracts/schema forms, not just its symbol. A changed producer gets a different key. An existing digest may stand for several inputs only when its producer demonstrably includes them.

Separate three concerns:

1. **Acquire inputs.** Read named values, including empty answers and absence, from explicit sources. File content, analyzer configuration, dependency analysis, loaded host code, profile and render selection are inputs too. Bind each database source once for the operation. A closure over a mutable connection, checkout HEAD or clock is not an immutable input.
2. **Derive once by content.** Pass acquired values to a function of data and use `seon.cache/lookup`. Its existing concurrent-miss mechanism shares the derivation. Pure functions need no database currency proof. A consumer returning a view over a database must retain that database's semantics; it cannot substitute a lookalike from another branch.
3. **Prove later acquisition cheaply.** Retain the read requests and `seon.db/read-evidence` with a small alias to the content key. Check each against the matching current source through `seon.db/read-evidence-current?`. A successful proof reuses the canonical value. A changed acquisition yields another content key; the old value remains valid for its old inputs.

This is the installed projection pattern generalized, not a second cache: `seon.db/projection-cache-key` → `:seon.db/projection-alias` → declaration content → `:seon.db/projection`. An alias retains only its canonical key and proof, not another strong copy of the result. Missing canonical entries are ordinary cache misses. Eviction never makes evidence green or unknown evidence true.

### Currency and discovery

There are two lookup inputs: the semantic request, available before reading, and the complete content key, available after acquisition. Do not put a newly computed evidence vector in the initial key and then rerun the entire derivation to obtain it.

For a fixed read plan, use the dependency's revision identity to look up the content alias. For a data-dependent plan, retain the last acquisition description under the producer plus explicit input/options key, as the existing `seon.health/derived` does for its discovered reads. It is a candidate for validation, never an answer by itself. Refresh it only from the actual completed acquisition. Keep these descriptions in `seon.cache`, under its byte budget; do not add an agent cache, graph, listener, registry or writer stamp. Alternate branches may require reacquisition when the hint changes; they still converge on one canonical derived value.

The database owner composes the proof, while `seon.cache` remains independent of `seon.db`. Move/generalize the existing health acquisition machinery into `seon.db/derived` (proposed name) with health as its first converted caller. Its input explicitly names producer content, immutable non-database inputs, source roles and acquisition/derivation functions. Its retained answer names the value, content key and source-specific evidence. Register the complete runtime request/result shapes at that owner; do not make them stored entities. Use `malli.core/validator` and `malli.core/-instrument`, not another validation implementation.

The proposed interface is `[:=> [:cat :seon.db/derivation-request] :seon.db/derivation-result]`. The request declares the caller's cache kind, producer/dependency identity, semantic input vector, source-role-to-database map, acquisition function and pure derivation function. Acquisition returns a named value containing the finite input vector and each source's read evidence; derivation takes that vector and returns the producer's declared value. The result carries that value, its canonical key and the evidence. The generic value position reuses `:seon.schema/value`; individual producer contracts stay specific. C1 compiles these runtime schemas together with health's first concrete producer contract before implementing the body. Canonical entries keep their caller's kind; one common alias kind in `seon.cache` holds tagged request/revision aliases. No database object, acquisition closure or connection is retained in an alias. Existing polymorphic render invocation remains the printer owner's specialization of this rule, not an excuse to move selection into the database owner.

The cheap proof order remains the database owner's:

- Equal eligible dependency revisions prove unchanged reads. Include the conservative/schema revision and the dependency's required connection/generation identity; naked revision counters are not globally comparable.
- When revisions differ, bound index patterns can prove that changed datoms did not affect the read. `seon.db/index-evidence-current` already handles history and no-history evidence.
- When that proof cannot answer, replay supported reads and compare semantic content. On cross-branch reuse this is necessary unless the dependency itself supplies a shared identity. Reacquiring a small read is preferable to repeating its expensive derivation.
- Unknown/unreplayable evidence means acquisition is required. It does not become an empty dependency vector. A genuinely database-free producer explicitly has no reads.

Evidence has **source roles**, not only source argument positions local to individual calls. A context can read its branch, main, attempt-start, a merge base and a historical view. Each proof must be applied to that role's actual database and view parameters. An `as-of` source includes its origin and time point; a history/since source includes its operation and origin. A commit id alone is insufficient, and all speculative values must not collapse under nil. No blanket whole-database currency test is added.

Nested derivations must contribute their acquired dependencies to an enclosing acquisition even on a cache hit. Otherwise warming a child silently removes the parent's dependencies. Preserve the existing read sink at the database owner and fold cached child evidence into the parent; no new global atom. Check this before moving render callers. Laziness remains within the scope that captures its reads; a printer caches only the prefix/nodes it actually realizes under a request, never a digest of an infinite tail.

`datahike.db/datoms-diff` is the existing change primitive for owners already holding two compatible immutable index values. Use narrowed attribute/index ranges and pass the delta to an incremental derivation. Do not retain a full database per cache entry solely to obtain a future diff. The dependency exposes no verified root-content hash for this design.

## 2. Context and pages are vectors of units

The owner's `{:value :request}` is the conceptual pair. Reuse the printer's `:seon.render/value` and P1a's `:seon.render/request` vocabulary in a runtime unit schema; the printer call is the request with its value associated. The current `:seon.render/unit` means the open invocation input with a database, so do not silently change its promise. C0 checks the installed registry and records the new context-unit term in `docs/seon/architecture/vocabulary.md` before introducing a new schema name.

`seon.context/model-context` produces an ordered vector:

| Units | Acquired data | Request inputs that affect reuse |
|---|---|---|
| Help | The selected help value and relevant program/schema content | Selected renderer, output, profile, namespace/options |
| Task sections | Held task, chain, earlier attempts and send-backs | Attempt-start semantics, section order, profile |
| Must-reads | One resolved reference per unit, from the attempt-start value | Reference level, pinned file/library content, selected renderer |
| History exchanges | Saved emission and its handle, in agent order | Position/newest-or-older, reference budget; P1e's printed-value semantics |
| Turn sections | Proof results, step judgment, branch changes, unread messages, costs and turns | Current explicit source values, current profile and operations |

Do acquisition per independently changing section. Building the entire old `current-state` first and then caching its printed fields saves printing but leaves the expensive block construction in place. Share the acquired merge selection between `seon.context/branch-changes` and `seon.context/step-observation`; it is the same selection that `seon.cluster.source/publication` and the done rule consume.

Each unit goes through `seon.render.value/show`, owned by the printer plan. The unit's canonical printed key includes value content, selected render and its reached program/schema inputs, all output-affecting options, and read-content evidence. Include position, distance, direction, output face, budget/width, continuation root and any namespace/agent input the renderer actually consumes. Merely dropping the agent id is not correct when it changes an operation or selection. Equal agents' inputs share; differing inputs do not.

Acquisition custody such as a connection or SCI context does not belong in a shared output. A shared continuation must resolve the same retained value for both consumers; use P1c's content-held render-result handle, rather than caching the first viewer's private result symbol. Live opaque values keep their owner's identity; never claim structural equivalence for arbitrary host objects.

Assembly orders admitted whole units and concatenates their cached emissions into system/history/prompt or HTML slots. It performs no second render, truncation, schema dispatch or selection. Its cost is O(number of units + emitted output length), even when every unit hits; concatenating text is not O(1). Request budgets are chosen before printing, and history is selected as whole units. The AI and HTML emissions share input acquisition and selection, while each face remains a distinct requested output.

**Printer coordination:** P1c owns invocation identity, nested dispatch and derived handles; this plan supplies the general acquisition/content distinction it can use. P1d owns one reply and its parts; a history unit consumes that result. P1e owns saved history and position; C3 converts context after P1e releases its files. Keep its goldens and whole-history semantics. No new printer entry, allocator, emitter, render selector or render-by-name path is introduced here. Web conversion follows the existing block assembly and the printer's P2 web ownership.

## 3. Convert, retain, or delete

The dig's roughly 80 kinds is historical. The live probe observed 63 kinds; static search found 105 lookup/presence call sites, which is not a kind count. These families cover the live inventory; C0 derives a complete census from current analysis rather than treating this table as an admission list.

| Existing kinds / owner | Disposition and inputs |
|---|---|
| `:seon.db/projection`, `:seon.db/value-projection`, `:seon.db/projection-alias` | Retain one canonical content result and lightweight aliases. Preserve speculative/temporal semantics and existing projection sharing tests. Audit retained edges: an alias must not keep an evicted result alive. |
| `:seon.db/behavior-digest`, `:seon.db/namespace-resolver-context` | Retain. The current code keys behavior by **stored definition digest**, not an entity map as the dig says. Validate that the admitted digest covers resolver and shape inputs. Do not change it on the basis of a low cumulative hit percentage. |
| `:seon.schema/*`, `:seon.schema.edn/*`, `:seon.schema.datahike/decoded-edn`, `:seon.schema.datahike/vector-pull`, `:seon.program/*`, `:seon.fn.analyzer/parsed-facts`, `:seon.ref/document`, `:seon.error.refusal/authored-namespace` | Retain pure content derivations; include builder semantics where not already covered. Reuse compiled Malli objects. File documents key their bytes/pin, not their path alone. |
| `:seon.cluster/file-derivation-key`, `:seon.cluster/file-schema-keys`, `:seon.cluster/derivation-world`, `:seon.fn/population-targets` | Keep useful per-content derivations. Acquire actual analyzer inputs once. `seon.cluster/derivation-keys` already documents missing cross-file inputs; its existing task remains relevant. A path/config label is not the content it names. |
| `:seon.cluster.source/publication` | Delete the monolithic cached verdict in C2. Acquire current ownership, proof outcomes, changed declarations and checkout inputs; cache the pure file/analysis work and share merge selection. The verdict is a cheap composition of those values, not a cached commit answer. |
| `:seon.cluster.source/merge-base`, `:seon.cluster.source/commit-graph-record`, `:seon.cluster.registry/commit-step`, `:seon.cluster/branch-attribute-changes` | Keep exact immutable lineage/diff answers keyed by the commits they actually concern, including store identity when required to distinguish lookup semantics. A commit is the correct input to a question about that commit. These are not current-content aliases. |
| `:seon.db/builder-digest`, `:seon.sci.eval/loaded-digests`, `:seon.sci.eval/program-key` | Convert whole-commit acquisition keys to small revision/evidence aliases leading to builder/program content. Loaded host definition identity is a real semantic input. Do not create an agent-specific compiled-program cache. |
| `:seon.sci.eval/program-context`, `:seon.sci.eval/overlay-plan` | Retain canonical program/overlay derivations, sharing immutable bases. Mutable agent SCI contexts remain owned by their agent; content sharing is not permission to share private mutations. |
| `:seon.db/request-view`, `:seon.sci.eval/kept-value` | Classify by result semantics, not the word “cache.” Request views tied to a snapshot preserve that snapshot. Kept values are bounded live-result custody, possibly opaque and effect-produced; never recompute a lost value or merge it with another merely equal-looking object. They retain identity keys, under the same byte budget. |
| `:seon.config/effective`, `:seon.task/file-declarations`, `:seon.task/task-basis`, `:seon.task/tasks-tx` | Convert current-value acquisition to declared config/task content plus declaration inputs. An explicitly requested historical task basis retains its exact historical identity. |
| `:seon.cluster.agent/admission-end`, `:seon.cluster.agent/branch-end` | Convert repeated current reads to acquisition evidence over the relevant lifecycle facts. Do not cache effects or replace the writer's decision. No lifecycle changes. |
| `:seon.render/invocation`, `:seon.render/call-reach` | P1c converts the invocation; retain call reach by producer closure content behind acquisition aliases. Move reusable evidence proof/refresh to the database owner with its last caller. |
| `:seon.health/derived`, `:seon.health/reads`, `:seon.health/problems`, `:seon.health/untested`, `:seon.health/replaced-roots` | Generalize `derived`; remove its local read-discovery/key machinery with the converted callers. Delete commit-wide `problems`/`untested` retention once their content units replace it. Sample live counters explicitly; keep deadline/wake behavior, which is execution semantics, not validity. |
| `:seon.test.runner/reach-node`, `:seon.test.runner/reach-schema-node`, `:seon.test.runner/reach-index`, `:seon.test.runner/reaching` | Keep canonical reached-content derivations. Work follows requested roots and their reachable definitions; share component digests. |
| `:seon.test.runner/reach-attribute`, `:seon.test.runner/reach-attribute-head`, `:seon.test.runner/reach-head`, `:seon.test.runner/reach-index-by-revision` | Convert revision/head entries to acquisition aliases; delete whole accumulated index copies from them in C4 after `reach-entries`, `reached-graph` and reaching lookups consume canonical content. Do not drop the incremental algorithm without a cold comparison. |
| `:seon.test.runner/result`, `:seon.test/destructive-reach`, `:seon.test/edge-attributes`, `:seon.test/host` | Preserve the runner's tested-program/input evidence and execution semantics. Convert only acquisition aliases. Unknown/red never reuses green. A result is not valid solely because the test symbol is unchanged. |
| `:seon.contracts-compile-test/read-source`, `:seon.contracts-compile-test/schema-resources` | Test infrastructure: key source/resource bytes and analyzer/registry inputs. No production retention exception. |

Also retire per-context derived render storage, `seon.render/shared-cache` and `seon.render/branch-scope`, only when the final render/walk/web/kernel consumers and their schema/test references convert. C5 owns that closure, coordinated with P1c. Private session state remains where it belongs. Classify the remaining local `memoize` sites by lifetime: do not convert a finite per-call fold into a process cache; delete duplicate long-lived derived storage only with its last caller.

## 4. Weights and recomputation cost

Use one estimated-byte budget, presently 512,000,000. Charge retained keys, evidence, values and container overhead; output-only constant weights can hide a large key or render result. Weigh from counts already produced, with offline retained-heap calibration, not a traversal on each hit. Shared canonical values are charged once; aliases charge their own keys/evidence. A retained strong edge to another object must be included even if that object's original cache entry is evicted.

Do **not** divide byte weights by recomputation time. That would allow expensive values to exceed the declared memory budget. Nor does increasing a cheap entry's weight assign it lower priority: Caffeine 3.2.4 documents that weights determine capacity, not the next eviction choice. See [Caffeine maximumWeight and weigher](https://raw.githubusercontent.com/ben-manes/caffeine/v3.2.4/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java).

Recomputation cost informs **what is retained and at what granularity**: compare avoided measured work with key/proof cost and retained bytes. Eliminate cheap aggregate copies, retain expensive small reusable products, and let Caffeine's frequency/recency policy select among them. Report load time, completed acquisitions, actual reuse and retained bytes separately. `seon.cache/present` deliberately counts neither hit nor miss, so zero reported hits on the reach-index kind is not evidence of zero use: `reach-entries` reads it through `present`.

The current `seon.cache/create` does not call Caffeine `recordStats`; it owns per-kind `LongAdder` counters. C0 records that discrepancy with the central-cache law. C6 uses the dependency's statistics for global cache operations and existing function profiling for derivation cost, preserving whatever per-kind attribution is actually needed without inventing another eviction policy. Do not undertake a cost-aware cache replacement without a measured failure that remains after keys, duplication and accounting are corrected.

## 5. Experiments and limits

Default: PID 35580, archive `4f15bf3d77aa98c83380c88efa693f072215c6bd`, start 2026-10-16T02:59:30.495Z. Status and MCP were available; runtime health was `:faulted`, with 29 open faults and 400 errored receipts at entry. No stop, restart, reset, source reload, provider call or production edit occurred. Source HEAD moved from `6fa21abd7` to `a9e4e9904` during the investigation; the running archive, not the working tree, supplied the measured functions.

Dependency source inspected: Datahike `ee3f8ec817e5310b3a0ea5adb8904214183568c0`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`, SCI `eb3290d5c5ed72133ae1947492ecd324413868ed`, clj-kondo `eb245d8260736ca463cda85219b6fc3ae73699c3`; Caffeine 3.2.4. The Datahike revision/context and diff seams above are in our maintained fork; no claim that they all exist in upstream Datahike. Malli's named seams and Caffeine's capacity behavior were read at their source. The earlier null-slot diff report predates the `18af63bf4` fork fix; it is not an assumed current blocker.

### Snapshot corrections

Read-only JVM-mode MCP, explicit root/default:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      status (seon.cache/status)]
  {:commit (seon.db/commit-id database)
   :cache-kinds (count (:seon.cache/kinds status))
   :selected-kinds
   (select-keys (:seon.cache/kinds status)
                [:seon.db/behavior-digest
                 :seon.test.runner/reach-index-by-revision
                 :seon.cluster.source/publication])})
```

At commit `6ad19b7b-81fe-5c58-864a-b3d29b8317a5`: 63 kinds. Behavior digest: 7 hits, 3,074 misses, **0 evictions**, 1,573,888 estimated bytes, 1,757 ms accumulated load. Reach-index-by-revision: 0 counted hits, 18 misses, **0 evictions**, 68,740,040 estimated bytes. Publication: 100 hits, 11 misses, 0 evictions. The dig's cumulative counters do not establish eviction as the cause of behavior-digest misses. Cold population, input turnover and real repeated misses require separate measurement.

### Scratch branch and currency

First SCI form for agent `cache-plan-astra`, cluster default: `(seon.context/current-state)`. It acquired branch `:agent-6b74929f72db`; value was a turns section, 29 ms and 72.8 MB allocation (whole request 145.4 ms / 203.2 MB). A private `def` retained a pull of this agent's id/branch plus `read-evidence` with `:seon.db/retain-read-results? true`. A later request returned one evidence entry, currency true and equal value after evaluation recording. Checking that same evidence on main also returned true and equal value, exercising cross-branch semantic reuse.

On that SCI branch this finite speculative probe checked absence becoming present, through the checked writer without committing a change:

```clojure
(let [database (seon.db/db)
      sink (atom [])
      before (binding [seon.db/*read-evidence-sink* sink]
               (seon.db/pull database [:seon.agent/end-calls]
                             [:seon.agent/id "cache-plan-astra"]))
      evidence (seon.db/read-evidence @sink
                                     {:seon.db/retain-read-results? true})
      after (seon.db/with database
              {:tx-data [[:db/add [:seon.agent/id "cache-plan-astra"]
                          :seon.agent/end-calls 1]]})]
  {:before before
   :after (seon.db/pull after [:seon.agent/end-calls]
                       [:seon.agent/id "cache-plan-astra"])
   :before-current (seon.db/read-evidence-current? database evidence)
   :changed-current (seon.db/read-evidence-current? after evidence)})
```

Result: before nil, after `{:seon.agent/end-calls 1}`, before-current true, changed-current false. Evaluation 4 ms / 2.0 MB; whole request 80.0 ms / 45.6 MB. This proves one absence/insertion case, not the whole mutation matrix in C1.

### Context block: parent and held-input prototype

The original block-cost branch `:chain-b7ab51e090d5` no longer exists. The replacement task-bearing fixture reads the scratch branch's inherited assignment for `attempt/51efd81cbc42`, task `seon.ref/implement-a-pathless-reference-section`. It is a read on our branch, not a request to that agent. Its block contains task, step, changes, must-reads and turns. This differs from the original unpublished-code fixture and does not reproduce its publication/lint miss.

The experiment retains an immutable answer and source-specific evidence, then validates before returning it. It does not install a generic cache or modify the printer. Exact JVM-mode root/default form:

```clojure
(let [database (seon.db/branch-database
                 (seon.db/db (seon.cluster.boot/connection "default"))
                 :agent-6b74929f72db)
      parent (seon.profile/measure
               #(seon.context/current-state database "attempt/51efd81cbc42"))
      cold
      (seon.profile/measure
       #(let [sink (atom [])
              value (binding [seon.db/*read-evidence-sink* sink]
                      (seon.context/current-state database "attempt/51efd81cbc42"))
              groups (group-by
                      (fn [r]
                        [(str (class (:seon.db/db r)))
                         (seon.db/commit-id (:seon.db/db r))
                         (:time-point (:seon.db/db r))]) @sink)]
          {:value value
           :reads (mapv (fn [[_ xs]]
                          [(:seon.db/db (first xs))
                           (seon.db/read-evidence
                            (vec xs) {:seon.db/retain-read-results? true})])
                        groups)}))
      held (:seon.profile/value cold)
      warm (seon.profile/measure
             #(when (every? (fn [[db rs]]
                              (true? (seon.db/read-evidence-current? db rs)))
                            (:reads held))
                (:value held)))
      parent-warm (seon.profile/measure
                    #(seon.context/current-state database "attempt/51efd81cbc42"))
      summary #(select-keys % [:seon.profile/ms :seon.profile/alloc-bytes
                              :seon.profile/calls :seon.profile/reads])]
  {:parent (summary parent) :candidate-cold (summary cold)
   :candidate-warm (summary warm) :parent-warm (summary parent-warm)
   :equal (= (:seon.profile/value parent) (:value held)
             (:seon.profile/value warm) (:seon.profile/value parent-warm))
   :source-count (count (:reads held))
   :read-count (reduce + (map (comp count second) (:reads held)))})
```

| Operation in that request | ms | Calling-thread allocation, decimal MB | Instrumented calls | Read entrances |
|---|---:|---:|---:|---:|
| Parent first in request | 200.83 | 124.772 | 29,113 | 467 |
| Prototype empty entry: derive + capture + evidence | 63.72 | 95.858 | 65,511 | 487 |
| Prototype held entry: currency + answer | 1.08 | 0.593 | 44 | 0 |
| Parent warm rebuild | 65.08 | 79.898 | 28,328 | 467 |

Equal data: **true**. Five source/view groups, 139 evidence entries. The warm prototype avoided about 60× wall time and 135× calling-thread allocation versus the warm rebuild. Its empty-entry allocation is higher than a warm rebuild. Sequential warming and concurrent work confound the first/empty-entry time comparison: **63.72 versus 200.83 is not a demonstrated cold speedup**. All measured work used a warm process. The prototype also retains database objects locally; production entries must carry source descriptors/evidence instead. It does not cover reacquiring later sources, producer changes, files, printing, Caffeine contention or memory retention, and therefore is not the end-to-end implementation proof.

An earlier attempt grouped only by commit: 1,105.21 ms / 1,291.275 MB to build the task block, 9.93 ms / 11.572 MB to form evidence, and 1.92 ms to check it, but equality was **false**. The failed proof came from combining HistoricalDB and AsOfDB under nil. Correct grouping observed three raw DB sources, one HistoricalDB and one AsOfDB at t 536871687, all their reads individually current. The 1.1-second observation is recorded against existing finding `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`; its profile included vector queries (347 ms self), contract references (183), direct-reference keys (108), arity references (75), compiled contract products (74), and declaration-content-key (55). This is evidence for C0/C4 attribution, not proof that all that work belongs to one cache miss.

Two probe errors were ours: a query guessed nonexistent `:seon.agent/task`, which correctly returned an invalid-read error; and calling `keys` on a Datahike database caused a ClassCastException. The corrected query used `seon.task/agent-task`'s assignment relation; the corrected source report read only explicit database fields. Neither is presented as a production cache defect.

## 6. Phases and proofs

Each implementation slice gets a red proof before its body and commits with named reaching tests through `bin/lane commit`. Split each phase by the file ownership below into short commit windows. Publish schema changes with their consumers on a live-store branch before the isolated host check. No phase deletes a callable name before its final callers, tests, quoted references and schema references convert; HEAD must boot after every commit. Root owns the broad tiers and restarts. These are proposed proof names/rows until C0 establishes their red behavior, not claims that new tests already exist.

| Phase | Owner, change and last-caller deletions | Proof that becomes green |
|---|---|---|
| **C0: evidence and contracts** | Context/cache/db owners' test files, measurement script, vocabulary and runtime schema declarations. Canonical fixture: ordinary task with red proofs, pending publication delta, references and 40 saved exchanges. Save exact parent outputs and costs. Census kind → call sites → semantic inputs → alias/value/identity classification from analysis. No runtime deletion. | New `seon.context-test/unchanged-context-across-unrelated-recording-reuses-its-units`: two requests separated by unrelated recorded work, equal context data, no repeated file analysis or unit rendering. New cost proof through `seon.profile/measure`, parent/change in the same isolated run. These must fail on the parent for the claimed work, not merely compare two already warmed calls. |
| **C1: one acquisition proof** | `seon.db`, its schema, `seon.health`, `seon.bootstrap`, their tests and cache request schemas. Add the proposed `seon.db/derived` by generalizing health's machinery. Convert health and bootstrap callers (bootstrap caches help groups), including health/bootstrap tests that inspect the old kind, then delete `seon.health/read-attributes`, `seon.health/derivation-key` and old local `derived` implementation. Preserve public caller compatibility until its final use converts. `seon.cache/lookup` stays the sole retention owner. | Extend `seon.db-test/retained-read-evidence-invalidates-only-on-a-depended-attribute` with unrelated writes to the same attribute, absence/insertion, retraction, no-history, schema change, cross-branch equal content and changed content. New table for multi-source temporal roles, child-cache-hit evidence, alias eviction and concurrent equal-content acquisition. Preserve `seon.db-test/a-retained-read-whose-plan-names-no-source-is-answered-by-replay` and the help-group reuse proofs in `seon.bootstrap-test`. |
| **C2: publication inputs** | `seon.cluster.source`, context selection consumers, analyzer caller and their tests. Extract the file-input value from `proven-file`/schema/task variants; include file bytes, selected before/after declarations, resolver/schema inputs, analyzer pin/config/dependency facts and path semantics. Reuse `seon.cluster/derivation-keys` where its meaning fits, completing rather than copying its incomplete inputs. Share the merge selection. Remove the `::publication` lookup in `publication` with its final verdict caller conversion; keep the public verdict function. | Extend `seon.context-test/a-step-is-done-only-when-its-merge-would-accept`: unrelated main recording reuses file work; changed proof result, stewardship, declaration, file bytes or analyzer input changes the appropriate result. Existing merge/write-back equivalence and conflict tests remain green. C0 task-block cost must improve both time and allocation; hot calls to kondo/preview support, but do not replace, that proof. |
| **C3: context units** | After printer P1c/P1d/P1e: `seon.context`, its schema/tests and the provider/debug consumers. Acquire sections independently, produce the unit vector, call the one printer, assemble. Retire the old monolithic shown-head/shown-tail glue in `model-context` with both callers converted; do not duplicate printer P1e's exchange deletion. Keep `current-state` as the public data view over those acquired sections. | Extend `seon.render-cache-test/a-turn-re-renders-only-the-context-units-whose-reads-changed` through `model-context`, not just must-read rendering. Preserve `seon.context-test/the-blocks-attempt-sections-lead-and-its-turn-sections-follow-the-history` and `seon.context-test/the-system-text-holds-for-the-attempt-when-a-must-read-changes`. Add one-unit change, changed profile/renderer, distinct continuation roots and history append/position rows. |
| **C4: reach and program acquisition** | Runner, db and SCI owners, serialized where C1/C2 share files. Convert loaded/program/reach revision copies to aliases; retain canonical nodes/components. Remove accumulated content from `reach-index-by-revision` and store-head retention only when `reach-entries`, `reached-graph` and reaching queries no longer read it. Each other family conversion is its own proven slice. | New runner table: equal programs on two branches share canonical reach products; unrelated fact commits cause no graph rebuild; one definition changes only its reached closure; unknown and red never reuse green. Preserve `seon.test-runner-test/undigested-declarations-never-share-a-reuse-key` and `seon.db-test/equal-declarations-in-another-transaction-share-one-projection`. Measure requested-root cold acquisition and repeated context, including heap retained by aliases. |
| **C5: pages and remaining callers** | Render/web/walk/kernel owners after printer ownership clears. Convert web units and remaining current-value kinds in §3. Move `seon.render/refresh-read-evidence` to the common database owner with all callers. Remove per-context derived cache and branch-scope storage with the last `shared-cache` caller; convert tests of the retired storage mechanism to observable reuse/isolation behavior. | P1c's proposed `seon.render-cache-test/the-same-definition-over-the-same-data-renders-once-across-two-branches`; web table: change one unit, preserve others, and keep viewer-specific operations correct. Exercise HTML paint separately. No callback or page hangs while a `lookup-within` derivation remains pending. |
| **C6: retained bytes and cost** | `seon.cache`, schema dials/status and per-kind weighers in short owner slices. Calibrate key/evidence/value ownership, remove duplicate aggregate caches whose measured retained benefit is negative, use Caffeine statistics and function profiling appropriately. No cost-weighted eviction implementation. | Preserve `seon.cache-test/the-byte-budget-evicts-and-status-reports-it`, `seon.cache-test/a-zero-weight-value-still-counts-against-the-budget`, concurrent-miss and nested-kind tests. Add alias-lifetime retained-memory experiment. C0's end-to-end cost proof passes under the shipped byte budget with competing ordinary agent work; compare miss cost, allocation and retained heap, not hit percentage alone. |

### Acceptance measurement

Run parent and candidate on the same immutable fixture and inputs in the isolated check JVM, instrumented through the ordinary agent entrance. Measure `current-state`, `model-context`, and the debug page separately with `seon.profile/measure`. The first includes acquisition; the second includes printing/assembly. Capture wall time, calling-thread and all-thread allocation scopes, GC, CPU, relevant calls, cache loads and estimated bytes; measure retained heap separately. Do not add inclusive child times.

Use fresh owned cache inputs for empty-entry runs and held identical inputs for warm runs; record unavoidable shared library warming. Reverse parent/candidate order and repeat to distinguish order effects. A genuinely process-cold comparison needs isolated parent/candidate starts; never clear or restart default. Include (a) same sources, (b) unrelated recording, (c) one relevant change, (d) equal content on another branch, (e) changed checkout/analyzer input, and (f) concurrent requests for one canonical key. Equal outputs and correct changed outputs are mandatory.

Warm work should follow unit count and changed reads, not the complete program. Cold work should follow requested reach and the rendered data, not every known declaration. A cold regression, missing dependency or absent measured end-to-end gain keeps the relevant phase open. The warm local prototype is motivation and an achievable component cost, not permission to commit an unmeasured production optimization.

## 7. Documentation-slice verification

Only this document is claimed and committed; source/test delta is zero. Full shared-tree `clj-kondo --lint src script test --cache false`: 7,500 ms, **0 errors**, 1,805 warnings (exit 2 for warnings). No runtime test is selected for this docs-only, non-gate-input path; implementation proofs above remain outstanding. `bin/lane commit` uses its documentation path, with no production reload or check-JVM claim.

Foreign boundary: printer-p1a held render/schema/SCI files and the shared tree had other modifications; no foreign files or sessions were touched. They did not prevent the live probes or documentation work. The existing block-cost report, the present prototype and the printer hybrid remain distinct evidence. No installed speedup, original 13-second cold reproduction, or browser-paint verification is claimed.
