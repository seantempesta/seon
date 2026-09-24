---
type: research
status: design options; no implementation
created: 2026-09-24
tags: [agent-platform, publication, adoption, concurrency]
---

# Publication under concurrent lanes

The installed path captures files, publishes program rows on `current-src`, then
copies rows, reloads Vars, arms contracts and records adoption on default.
The principal Clojure composition is immutable captured inputs plus Datahike's
writer/branches, with exclusive JVM convergence as a separate operation.

## Broken first; scope and basis

Per-save publication does not provide either eventual convergence after a missed
event or an atomic change to the usable program. Disjoint file ownership does
not isolate namespace dependencies, schema resources, analyzer cache or JVM Vars.

Read-only research; no tests, JVM launch, reload, publication or branch mutation
was requested by this lane. Only this note is edited. `CLAUDE.md` was read end
to end. [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md)
§2a–2e and [D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md)
§7–9 distinguish the target from installation. This note offers choices, not a
parallel implementation plan. The owner explicitly resumed hooks in `7fecf9eb0`;
the earlier B1 lint-only policy is not evidence that hooks are currently off.

Source basis: initial HEAD `399ab5a88de5c97d23788e1f68fc4063e104de59`;
HEAD advanced to `5d6f3bd9c` during reads. Publication owners' latest relevant
commit is `da9910fec`. Other lanes changed `fn.clj`, rendering and tests;
their files/sessions were untouched. Source citations below identify the observed
checkout. MCP at 18:55:55Z confirmed five named files matched default's stored
file digests, including both publication owners; metadata matched their cited
entry lines. This proves row/disk agreement and Var presence, not atomic behavior.

MCP `runtime_status`, root `/Users/sean/src/seon`, cluster `default`:
PID **99265**, start **2026-09-24T18:13:37Z**, alive, **faulted**, five open fault
signatures (DB read interruption, turn-stop acknowledgement, turn contract,
missing render observation, transaction interruption). All observed Flow pings
replied. No recovery was attempted; none of those signatures proves this defect.

## Saved file → adopted Vars, with observed costs

Below, `C` = `src/seon/cluster.clj`, `S` = `src/seon/cluster/source.clj`,
`H` = `bin/seon-hook`. Live counters are **inclusive**, cumulative across
definition/context cells; maxima are not phases of the same save. Do not add
them or attribute callback time to wrapper overhead. Counts/maxima were read
at **18:54:48Z**, through MCP JVM mode with `read_only: true`.

| Step and authority | Input → output; recomputation scope | Measured cost |
|---|---|---|
| Hook admission/discovery, H:1816–1866, 1996–2022 | Named paths plus session-to-session digest changes; lint/contract refusal suppresses publication | Historical walk: **112 ms**, 553 files / 9 MB; **212 ms** including bb process (H:1720). Individual lint cost unmeasured here |
| Hook request, H:1638–1705 | One synchronous prepl request to `refresh-source!`, **gate true** | Transport cost not separately measured; config timeout **600 s**, not a performance guarantee |
| Acquire schema world, C:2931–2954 | Bootstrap/store, then projection of checkout-wide `packaged-forms` | Separate projection/build cost unavailable; wrapper totals below include their bodies |
| Capture/compare, C:1993–2065; S:106, 131, 136 | Explicit paths only, or pathless inventory ∪ stored paths; captured bytes/digests, classification; selected no-change returns published head | `capture-paths`: **134 calls, max 20 ms, total 165 ms** |
| Analyze, C:2066–2088; `fn.clj:2484–2534` | Captured changed files plus selected dependent paths → canonical rows; analyzer config change selects all sources | `analyze-rows`: **89 calls, max 10,753 ms, total 33,209 ms** |
| Publish, C:2089–2107; S:539–657 | Expected head → scratch branch → population/reconciliation, optional issues, seal → guarded `current-src` head move | `publish!`: **33 calls, max 4,475 ms, total 93,271 ms**; complete `full-source-refresh!`: **26 calls, max 4,759 ms, total 60,016 ms** (different populations) |
| Gate choice, C:2840–2878 | Interpretable delta: candidate tests before adopt; host-bound functions/unknown classification and changed core tests: adopt before tests | `save-gate!`: **21 calls, max 128,066 ms, total 722,141 ms** |
| Select/verify closure, C:2281–2403, 2638–2648 | Changed identities since prior adoption; before/after dependency closure; every reloaded file must match published digest | `development-namespaces`: **16 calls, max 90 ms**; `verify-development-sources!`: **30 calls, max 27 ms** |
| Adopt rows, C:2568–2604, 2649 | One guarded transaction: schema declarations, program replacements, issue rows | **17 calls, max 1,883 ms, total 9,370 ms** |
| Exclude, unmap, load, reverify, C:2210, 2654–2670 | Test-member write exclusion; ordinary ordered `require :reload`; post-load digest check | `call-with-program-change`: **16 calls, max 3,641 ms** (includes body, not just waiting); load: **16 calls, max 1,125 ms** |
| Arm, C:2672–2700; `instrument.clj:894` | Published projection + reloaded/schema-affected identities → replaced wrappers | `instrument/apply!`: **41 calls, max 317 ms, total 1,725 ms** |
| Record/advance, C:2703–2724 | Guard prior adoption/current source head; record commit and evidence, advance projection, notify web | Separate record cost unavailable; entire `development-source-refresh!`: **20 calls, max 4,951 ms, total 29,168 ms** |

End-to-end `refresh-source!`: **26 calls, max 132,550 ms, total 788,064 ms**.
A separately recorded single gate spent **2,552 ms publish + 47,054 ms adopt +
9,032 ms tests**, **59,103 ms total**, during concurrent publications
([panic-sink landing](../../prds/agent-platform/landing/lane-fix-panic-print-sink-2026-09-24.md#evidence-default-pid-99265)).
Those older adoption timings must not be substituted for today's differently
scoped counters. The supplied earlier status (107 s / 103 s maxima;
34,393 projection calls / 362 s) is historical owner evidence.
The new read found **339,511** projection calls / **6,053,855 ms** inclusive,
max **138,103 ms**. `schema.clj:1272–1278` binds a projection and invokes
the callback: this does **not** establish 6,054 seconds of projection construction.

## Root causes, separating remaining defects from repaired ones

1. **An event is being used as an acknowledgement.** H:1834–1844 records
   digests before lint/publication finishes; subsequent discovery compares
   against that observation, not against accepted/published/loaded content.
   First observation has no previous baseline; iteration over current files
   misses deletions. A refused path unchanged on disk is not reoffered by
   this derived path (a later explicit named edit can offer it).
   [The sweep's publication gap](../../prds/agent-platform/landing/lane-sweep-hand-built-ns-rows-2026-09-24.md#still-broken-not-this-class-or-outside-this-lanes-files)
   records committed `turn.clj` and `turn_next_work_refusal_test.clj` at
   `eb14a2f22` missing publication; adding them manually recovered in **23 s**.
   Git commit is not publication. S:106–148 / C:2013–2045 already provide
   content discovery; the hook does not use that pathless reconciliation.

2. **File ownership is smaller than the consistency boundary.** Ordinary
   `require :reload` reads disk. C:2384 checks the whole reload closure,
   not just the lane's files; the precheck at C:2648 correctly prevents
   knowingly loading unpublished dependents. The postcheck cannot undo a
   racing file edit or already replaced Var. Historical
   [db-adoption refusals](../../seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md)
   name held `render.clj` and other lanes' files, with **24.8–65.6 s**
   attempts; later sightings took **69.7 / 180.6 s**, with **297.8 s**
   aggregate refresh time. This is not evidence that a single attempt took
   300 s. Current C:2262–2382 narrows ordinary Var-indirected changes and
   equal-digest declarations; macro/inline/type/unknown closures still exist.
   Removing the verification would conceal the mismatch, not fix it.

3. **Work and admission happen before consistency is secured.** Analysis
   precedes the expected-head check (C:2070 vs S:554–582); independent scratch
   writers can lose after paying analysis/population. Source publication and
   adoption are distinct head movements. Today's path is selected analysis,
   **not unconditional whole-program lint on every save**: C:2013–2045,
   `fn.clj:2484`, and S:239 reuse already repair part of the historical cause.
   Residual broad work includes reading/merging all stored path digests and
   hashing the aggregate on partial publication (C:2046–2064), checkout-wide
   schema acquisition (C:2953, 1681), and legitimate wide dependency closures.
   `schema/edn.clj:360–401` caches populations but scans a directory stamp;
   a resource change can invalidate that population irrespective of named paths.
   Repeated events multiply work and compete with tests. The current much larger
   gate maximum than source-build maximum demands phase attribution, not blaming
   `full-source-refresh!` by its name. Ordinary >1 s and historical >10 s
   failures remain defects; this research does not authorize those costs.

4. **Database atomicity is not JVM adoption atomicity.** Current adopt-rows
   is ONE transaction, an improvement over the old split writer. It precedes
   the write lock; load/arm runs inside it, but record/projection advancement
   runs after release (C:2649, 2654–2700, 2703–2718). `da9910fec` and
   `test.clj:151` exclude participating test members; they do not establish
   exclusion of every agent/render/host caller or keep affected execution
   closed after a failed reload. The lock's finally unlocks on failure.
   Host-bound `adopt-then-test!` explicitly changes default before its verdict
   (C:2818–2838); C:2901–2903's blanket “red … unchanged” docstring is false.
   [Leftovers-1](../../prds/agent-platform/landing/lane-fix-leftovers-1-2026-09-24.md#broken-or-unproven-first)
   records `internal.cljc` loaded, `seon.flow.edn` rows not adopted, and
   `:seon.flow/callback-result` refusals for ~five minutes.
   That is incident evidence; this read-only investigation did not reproduce
   its exact interleaving. The surviving ordering above explains why an old
   adoption record cannot certify either the old or new callable world.

## Dependency seams before more machinery

Pinned gitlinks: Datahike `620721d674451364be05bbc43cead99347d3671e`;
clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`.
Datahike `versioning.cljc:251–274` branches stored index roots; `:323–334`
requires exclusive writer custody for `force-branch!`—expected-head checking
is not a cross-writer CAS. `writer.cljc:139–206` retains the old DB on a
failed transaction; it cannot roll back `require`, Vars or external effects.
Use Seon's registry/writer owners and immutable transaction reports/history,
not another queue, generation registry, store copy or per-lane JVM.
[Upstream versioning documentation](https://github.com/replikativ/datahike/blob/main/doc/versioning.md)
describes the same branch/commit model; the pinned source supplies the exact guard.

clj-kondo's native cache supplies unchanged namespaces:
`impl/cache.clj:127–195`; native locks at `:117–125`. Its
`core.clj:242–268` writes cache before final lint verdicts; cache presence
proves analysis, not admission. Current adapter `fn/analyzer.clj:333–396`
checks used entries, rebuilds stale sources and reruns; concurrent source
changes can refuse again. Preserve branch-appropriate resolver inputs and
prove rejected-candidate/cache reuse before deleting checks. The
[upstream cache interface](https://github.com/clj-kondo/clj-kondo#project-setup)
already supports incremental file analysis, cache directories and disabling writes.
No function bodies in EDN: declarations remain Clojure source/program rows;
schema/config EDN contains only data and declared symbol references.

## Exactly three priced options

Estimates are engineering ranges, **not measurements or approved implementation**.
“Per save” separates default publication from lint/test bodies; no test duration
can be promised without its selected set. Changes exceeding ~100 additions must
be divided into reviewed, loadable slices. Existing B1/D1 owners retain ownership.

| Option | Guarantee and mechanism | Cost: lines / hours / per-save ms | Sacrifice |
|---|---|---|---|
| **1. Root-only coherent checkpoints** — simplest viable constraint | Disable automatic default publication, retain lint; root holds the complete affected file/schema set, then uses existing pathless content discovery and one integration caller. No lane save changes default. Root admits no dependent execution during integration; a failed checkpoint stays unavailable until repaired, rather than being treated as healthy from its old record. This is an operational constraint, not an installed automatic exclusion guarantee. | **0–15 production additions**, **10–30 documentation lines**, **2–4 h** setup/verification; **0 ms default publication/save**. Existing discovery baseline 112 ms; lint remains separately priced. Checkpoint adoption retains measured seconds-scale cost until narrowed. | Default runs the last accepted checkpoint; live feedback waits for root. Shared-file holds still block broad integrations. Automatic recovery and strict runtime fencing are not delivered by a config switch. |
| **2. Content-derived checkout reconciliation** | Replace session digest acknowledgement with existing pathless checkout-vs-published comparison, then compare publication-vs-adoption even on no-op publication; an adoption record alone cannot certify actual installed definitions after failure. Include additions/removals. A missed/refused save remains a content difference and is reconsidered on the next trigger. Capture one immutable input set; reject moved heads and dirty/held dependency sets before expensive work. Convergence is conditional on a subsequent trigger, released holds, coherent valid inputs and passing admission. Keep host-bound integration root-controlled. | **60–100 production additions**, retire **80–140** hook/session lines, **60–100 test additions**, **12–24 h**. Target **100–300 ms** no-change discovery, **500–1,000 ms** ordinary small delta excluding test bodies; not yet demonstrated. Published baselines remain 2.6–4.1 s for small sweep passes and 23 s for its gap recovery. | Every pathless event inspects all declared input bytes; no O(changed-only) discovery guarantee. It can discover another lane's unfinished work and must defer it. It repairs missed events, not lane isolation or rollback; continuous edits can prevent progress. Do not auto-adopt all dirty content merely because it parses. |
| **3. Named lane branches → root-accepted merge → fail-closed convergence — recommended** | Keep ordinary edits/program rows in each lane's Datahike branch and SCI context. Test the combined candidate, check tested head at the writer, obtain named root acceptance, then staged write-back under whole-file holds. Only root publishes accepted bytes into default. Close affected execution across rows → load → arm → record; reopen only after bytes, callable behavior and contracts agree. Failure retains unavailability and evidence, with REPL inspection reachable. “All-or-nothing” means availability/acceptance, **not undoing JVM effects**. | **250–450 production/schema additions**, retire **150–300** duplicated hook/entry lines, **200–350 test additions**, **40–72 h** across B1/B2/B4/D1. **0 ms default publication per lane save**; branch submission/install target **<1,000 ms** plus actual test bodies. Ordinary accepted integration target **<1,000 ms**, wide macro/type closures measured separately; neither target is installed proof. | More branch/review discipline; shared default updates only after acceptance. Host-bound/file-only changes need their required platform proof or remain deferred under the one-JVM rule. A failed load can make affected work unavailable until forward convergence; uninterrupted old-generation service is not promised. |

Option 3 is the existing CLAUDE/D1 model, not a new runtime architecture.
Its price includes real gaps: installed `S:736–784` prepares only replacements
and refuses additions/deletions; `S:786–834` checks tested evidence and writer
basis. MCP branch SCI defs are private, not settled program rows (REPL skill's
verified limitation); file export/recovery and complete evaluation exclusion
remain D1 §7–9 obligations. Merely giving today's file lanes branch names
would leave the shared checkout/Vars problem intact. Use option 1 as its interim
operating constraint; content comparison from option 2 belongs at integration.

Required future proof, not run here: missed save and deletion converge on next
trigger; held dependent defers without loading; competing head refuses before
costly repeated work; interrupted reload/arming leaves execution unavailable;
host-bound red never claims unchanged; accepted multi-file schema/code converges;
two named branches isolate indirect callers and merge only current green proof.

## Exact read evidence and limits

All evaluations used `eval_clj` with root `/Users/sean/src/seon`, cluster
`default`, mode `jvm`, `read_only true`, timeout 10,000 ms. No defs/reloads.
First form (profile table above is its returned aggregate):

```clojure
(let [started (System/nanoTime)
      connection (seon.cluster.boot/connection "default")
      database @connection
      wanted '#{seon.cluster/refresh-source! seon.cluster/full-source-refresh! seon.cluster.source/capture-paths seon.fn/analyze-rows seon.cluster.source/publish! seon.cluster/save-gate! seon.cluster/development-source-refresh! seon.cluster/adopt-rows! seon.cluster/verify-development-sources! seon.cluster/load-development-definitions! seon.cluster/development-namespaces seon.cluster/call-with-program-change seon.instrument/apply! seon.schema/call-with-projection}
      observations (filter #(wanted (:seon.profile/sym %)) (map seon.profile/observation (seon.profile/cells)))
      runtime (Runtime/getRuntime)]
  {:at (str (java.time.Instant/now))
   :cluster (seon.db/pull database [:seon.source/commit-id] [:seon.cluster/name "default"])
   :vars (into {} (map (fn [s] [s (when-let [v (find-var s)] (select-keys (meta v) [:file :line :arglists]))])) wanted)
   :profiles (mapv (fn [[s rows]] {:sym s :cells (count rows) :calls (reduce + (map :seon.profile/calls rows)) :total-ms (reduce + (map :seon.profile/total-ms rows)) :max-ms (apply max (map :seon.profile/max-ms rows))}) (group-by :seon.profile/sym observations))
   :heap-used-bytes (- (.totalMemory runtime) (.freeMemory runtime))
   :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)})
```

Result: adoption commit `6ab5716f-e314-5d23-a103-c4fb7938c490`;
**27.720042 ms** form / **30 ms** prepl; heap used **8,634,755,912 bytes**
(point observation, no GC and no publication allocation attribution).
Metadata resolved all 14 Vars. MCP returned a windowed envelope and itself
retained a 22,822-byte blob `86664349f9b7df4860eb433484a2c10de72599a5998ff07bbc670d3f835a6db4`
despite read-only selection; the submitted form requested no storage.

Second form:

```clojure
(let [start (System/nanoTime)
      database @(seon.cluster.boot/connection "default")
      paths ["src/seon/cluster.clj" "src/seon/cluster/source.clj" "src/seon/fn.clj" "src/seon/turn.clj" "test/seon/turn_next_work_refusal_test.clj"]
      stored (seon.cluster.source/stored-path-digests database paths)
      disk (seon.cluster.source/path-digests "/Users/sean/src/seon" paths)]
  (pr-str {:at (str (java.time.Instant/now))
           :matches-adopted-rows (mapv (fn [p] [p (= (get stored p) (get disk p))]) paths)
           :elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)}))
```

All five comparisons returned true at **18:55:55.498777Z**;
**1.469167 ms** form / **3 ms** prepl. Row equality is not proof of wrappers or
indirect-call isolation. An unsolicited hook feedback during the read sequence
reported unchanged/no selected tests, publish **33.522417 ms**, commit
`6ab5723a-00df-5a78-a5fb-d9740ee85b28`; it was not a requested lane publication.

Verification is source/history/dependency inspection plus those live reads.
No fresh fault injection, browser observation, test, parent/change timing or
atomicity proof was performed. Separate transport, row-record and lint timings
remain unavailable. Production/test delta: **0/0 lines**; only this research
note is committed and released. Existing issue classes are cited above; no
issue index or skill was changed under this assignment's single-file boundary.
The scoped Babashka Markdown validator returned valid with zero violations
(169 ms process); the automatic repository-wide pin check reported 66 stale
citations elsewhere. Those historical documents remain untouched.
