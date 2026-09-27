---
type: review
date: 2026-09-26
status: plan review only; no implementation or tests
plan: cc3514a1e3c88188dde0a74c22b42bf63ca75b7c
reviewed-source: d148e86383015ab9f6a5bf31974de12e9448383f
scope: SCI acquisition, cache identity, mechanical deletions, definition authority, admission codec
follow-up-design: 987a90b62
---

# Review of the SCI eval / reader / admit shrink plan

Initial plan verdict (`cc3514a1e`): slice 2 is **REVISE**. Sharing a program base across branches is appropriate, but the recommended key does not cover all the inputs its constructor reads. It also preserves an existing divergence from the branch law: file-derived rows differing from the loaded program currently run the newer compiled code. Slices 1 and 3 are **ACCEPT-AFTER-FIXES**; slice 4's listed mechanical deletions are **ACCEPT**, retaining `evaluate-native!`.

The principal approach should remain a function of explicit immutable acquisition inputs, retained in `seon.cache`, followed by a receiving context's SCI fork. An agent's continuing context and live objects are not cache entries to reconstruct from declaration text.

## New verdict — slice-2 design at 987a90b62 — REVISE

The loaded-base / receiving-delta split is an appropriate simplification. **`[store identity, loaded commit]` is the correct shared-base key if construction is restricted to that loaded program and stable process inputs.** Branch differences belong on the receiving context, not in this key. The proposed algorithm does not yet establish that restriction or complete the receiving side: B1 omits graph changes, acquisition can select the branch's obsolete loaded record, test execution still selects host code by authorship, and rebuilding a delta can replay live data-def initializers. Resolve these points before implementation. The earlier slice-1/3/4 verdicts remain unchanged.

This section reviews `shrink-eval-slice2-design-2026-09-27.md` end to end at `987a90b62`. New source citations use `7e8043de10783a306b5298079fa2f54829eb1fd4`, the checkout/archive at review entry. Its acquisition, kernel, instrumentation, call-preparation, program, function and schema sources match the design's `b1112f457` baseline; the test owner has subsequently changed. The sections below this new verdict retain their original historical baseline.

### Shared-base construction and the correct receiving identity

The simpler shared key is sufficient only with these construction and attachment rules:

| Input / open item | Finding and required disposition |
|---|---|
| **C — loaded projection** | **It is read even with no interpreted functions.** `build-base-ctx` selects injected bindings from the projection and retains it (`src/seon/sci/eval.clj:377`, `:429`); `program/base-context-injected-symbols` reads schema properties and sometimes host namespace publics (`src/seon/program.cljc:136`). Derive the loaded projection from loaded declarations with fixed compile/predicate inputs. Then it is a function of L and needs no redundant key member. If those inputs may vary independently, the key must include their complete projection construction identity, as `schema/build-projection` does (`src/seon/schema.clj:2403`). A fingerprint is insufficient. |
| Loaded materialization | `loaded-program` still attaches the **caller's** projection and sometimes returns the supplied DB unchanged (`eval.clj:1657`). Calling it does not establish a branch-independent loaded projection. Separate detached/store-owned materialization from receiving metadata, as required in the earlier slice-1 review. A shared base must not retain the first branch's database or projection holder. |
| Config / caps | For a loaded base copying compiled roots there need be no branch config read. On the receiving side, `install-function-contract!` reads config (`eval.clj:1355`), and `wrap-interpreted` validates caps (`src/seon/instrument.clj:452`). Config-only change must refresh the affected arming decision or surface its refusal even when row deltas are unchanged. Current `compiled-wrapper` takes `_caps` and no longer uses caps to admit results (`instrument.clj:554`); do not claim that changing a positive cap presently changes its result behavior. Retain only actual arming dependencies, or remove an obsolete read at its owner. |
| Supplied-default snapshot | This is a real captured input, not just calling custody. Arming captures `call-preparation/snapshot` (`eval.clj:1357`), and `compiled-wrapper` builds a plan from it (`instrument.clj:651`). `preparing` receives the calling environment dynamically but retains the snapshot and plan (`src/seon/call_preparation.clj:1052`, `:1069`). Default-only changes need receiving rearming without source replay. An unchanged compiled host root also carries its JVM arming: the design must specify how branch schema/default differences reach such calls, or explicitly establish their existing loaded-program semantics. Calling-world custody alone does not refresh this snapshot. |
| Recorder | Remove recorder effects and receiver references from shared derivation; retain refusal data there and record/deliver it after receiver attachment. Current acquisition stores a recorder and calls refusal recording (`eval.clj:2945`, `:3116`); `copy-base-ctx` does not replace it (`:3454`). Correction to the earlier historical table: the current wrapper implementation reads the snapshot policy but does **not** consume `commit-fault!`; the present recorder dependency is in the acquisition/context boundary, not a demonstrated wrapper capture (`instrument.clj:643`). |
| **A — late host loads** | The proposed direction is useful but incomplete. `host-namespace!` loads/returns a host namespace; it does not install SCI bindings (`eval.clj:2148`). `install-first-party-namespaces!` selects admitted identities and copies available roots (`:2255`); namespace tables can already exist with missing bindings (`:3036`). Refresh missing admitted bindings on the **receiver**, including an empty delta or equal acquisition identity, under the proper test loader. `resolve-loaded` deliberately uses that loader, unlike `host-namespace!` (`src/seon/test.clj:1455`). Preserve overrides and tombstones; do not copy every newly visible host intern. No new epoch is needed. |
| Other process inputs | Guard hooks are process-owned (`src/seon/sci/kernel.clj:200`), so they do not require a branch key. Host roots and their arming must be coherent with the published loaded identity, retaining the existing start/end check (`eval.clj:1614`). The current constructor also captures `RT/baseLoader` (`:3427`), which can vary under a test request. Keep the test loader in the receiving execution or normalize it at the shared owner; a commit id does not identify an arbitrary caller's loader. |

Do not retain `copy-base-ctx` “unchanged” as proposed at design line 92. It only forks the SCI state and kernel atoms and attaches database/loaded values (`eval.clj:3448`); it does not establish the receiver's projection, environment, custody or recorder. Compose the existing receiving-world ownership from `fork-cluster-ctx` (`:3766`) before installation and rearming, including the no-delta path. Refresh schema-declared injected bindings if the receiving projection changes their population; merely assigning a new projection does not update bindings selected during construction.

Use separate identities for the reusable template and acquisition validity:

```text
shared-base-key = [store, L]

receiving-acquisition-content =
  [store, L,
   complete effective declaration delta and selected interpretation set,
   branch projection construction content,
   effective inputs actually read by receiving arming,
   supplied-default declaration/plan inputs]
```

The delta/selection member must reflect all inputs that change the resulting bindings, descriptors, refusals or affected callers, including removals. Equivalent normalized content can reuse acquisition; unrelated receipt commits cannot force installation. Custody, recorder and test loader remain explicitly attached receiver services; changing those services must update that attachment even when content equality succeeds. No connection id belongs in the shared-base key. Store-scoped successful refusal recording may use target identity, because that operation actually writes to a target.

**D is accepted only with the correct loaded authority.** Design line 87's `loaded-program(V)` loses `loaded-source(snapshot,V)`: an old branch's adoption record intentionally stays at its branch point, while `::loaded-connection` names the current adopting authority (`eval.clj:1620`, `:3624`). Resolve and hold L from that authority before both lookup and diff; preserve unknown/failed-adoption refusal and the start/end check. Convert the one-argument entrance without assuming its passed branch names the live JVM program.

The optional memo for deriving receiving identity is **not keyed by bare branch commit**. At minimum its key includes store, exact committed branch value and resolved L, plus any independently supplied projection/arming input. The same branch value has a different delta after adoption without a branch transaction. Use the database owner's committed-value eligibility, not just `commit-id`: Datahike excludes speculative/wrapped values (`reference-code/datahike/src/datahike/db.cljc:385`; `src/seon/db.clj:3001`). Candidate `d/with` worlds must derive from their actual content rather than alias the parent commit's memo. This memo is only an acceleration of immutable reads, not a second program stamp.

### B — delta completeness and execution consumers

**B1 is incorrect as written.** Selecting rows by `[digest admission host-bound?]` and only then including selected rows' calls in identity cannot detect a calls-only change on an unselected row (design lines 74–79). The declaration digest deliberately excludes call/analysis facts (`src/seon/program.cljc:377`). Such a change must either alter the selection/identity or produce an explicit incoherence refusal; silently treating it as equal is not valid.

B2's full canonical-row comparison is not a complete alternative by itself either: declared invocation edges can change on an arbitrary holder's attribute without changing any function/test/namespace row (`src/seon/fn.clj:1714`). Use narrow immutable reads covering the actual installation facts and graph dependencies; reuse their dependency-aware query results. Compute the interpretation closure against the branch and include its effective result and selected row content in acquisition equality. Recompute when a reached graph input changes, including removal of a formerly affected caller. Do not force full payload pulls merely to establish selection. `program/canonical-row` is an owned-attribute selector, not a deep identity-ref normalizer (`program.cljc:1181`); normalize namespace components/refs for cross-branch content comparison and pass explicit shapes.

The following are required parts of the delta algorithm, rather than later optimizations:

1. **Deletion seeds and snapshot removal.** Include removed symbols among closure seeds and retain only present members for installation. `reverse-closure` supports absent seeds while traversing surviving callers (`fn.clj:1938`). Unmapping a SCI Var alone does not remove its cached source, namespace descriptor or installed marker (`src/seon/sci/kernel.clj:207`, `:261`; `eval.clj:1385`). Purge those receiving entries and prevent late host binding from resurrecting deleted identities. A surviving compiled caller must not reach the loaded deleted implementation: either the canonical writer refuses the deletion or acquisition installs/refuses its affected callers according to the branch program.
2. **Tests and their executor.** Install differing tests regardless of authorship and handle unchanged tests that reach interpreted functions. `reverse-closure` returns both `:seon.fn/affected` and `:seon.fn/tests` (`fn.clj:1951`). The current test owner still chooses SCI only for `:agent` tests and resolves `:core` tests from the JVM (`src/seon/test.clj:1514`). Therefore installing a changed file-derived test on the fork is insufficient: convert `resolve-test` to use the acquisition decision, including affected test execution. This missing consumer is a branch-law correctness issue, not just a cache issue.
3. **Namespace population and ordering.** Changed/removed namespace rows must update the effective reader context, including removal of old aliases/refers/imports. A changed function in an unchanged namespace still needs that namespace's full branch bindings. Namespace-only changes must reach the declarations whose resolver context changes, or be refused when analysis is not current. Preserve class installation, all required namespace bindings, caller host bindings, generation-local Vars and declarations before caller analysis, then tests after functions (`eval.clj:3031`, `:3047`, `:3052`, `:3074`, `:3114`). A loop of `install-row!` in namespace order alone does not state these guarantees.
4. **Lazy installation and host-bound refusals.** The effective kernel snapshot must describe the receiver's whole program: loaded descriptors with delta replacements/removals, including namespace descriptors. The lazy owner reads it later (`eval.clj:1377`). Publish the new interpreted set before any install; use it consistently in `install-row!`, lazy acquisition and test resolution. Continue checking both the branch and loaded declaration for unsupported host-bound overrides (`:1788`), retaining named refusals instead of silently using the host body.
5. **Continuing live roots.** A new branch must create its own agent data-def root. A continuing world must retain an unchanged root and its captured references when an unrelated delta member, schema or default changes. Design lines 92–98 instead rebuild every delta row before carrying interns. `acquire!` excludes installed roots unless marked context-defined (`:3639`), and regeneration has its own generation/root filters (`:3214`). Even when a later transfer preserves the visible atom, executing its initializer first repeats effects and can leave newly analyzed callers referring to another Var/root. Carry unchanged owned roots before analyzing affected callers; rearm without reevaluating source when only arming inputs changed. Cover delta-to-base transitions after adoption too. Authorship does not guarantee a row remains a delta forever.

The data-def ruling remains settled: `(def m {:a 1})` is a program row and clj-kondo supplies classification. Per-world lifetime is not grounds to reintroduce runtime type classification or omit data definitions from the program comparison.

### Consumer conversion, deletion and cost

Retain the proposed central-cache migration and deletion of revision-basis machinery. Extend the conversion table to include `resolve-test` above; the lazy kernel snapshot/install path; `install-evaluated-rows!` and its `installation-covers-program-change?` fast path (`eval.clj:1969`, `:2030`, `:2100`); `regenerate-agent-context!`; and both public `base-ctx` arities. Settlement already transfers actual evaluated roots to avoid evaluating definitions twice. Updating only its database snapshot after deleting revision comparison is insufficient unless the new acquired identity is also advanced from the proved installation. Preserve the newly explicit acquired-fork acquisition in `fork-cluster-ctx` (`:3813`) used by the test owner (`src/seon/test.clj:1893`).

Design line 105's “every landing restarts” is not an acceptable listener-retirement mechanism. This review authorizes no restart. Deleting a Var or registration site leaves live callbacks and their captured roots registered. Retire `::program-identity` listeners through the connection lifecycle / public `datahike.api/unlisten` (`reference-code/datahike/src/datahike/api/specification.cljc:1157`, implemented by `datahike.core/unlisten!` at `core.cljc:213`) during authorized adoption, or assign that concrete retirement step to the adopter before calling the deletion complete.

Refusal dedup needs `[store, complete relevant acquisition/refusal content, target]`, not an ambiguous “loaded commit or delta identity.” Different deltas or arming failures at the same L can produce different refusals. Retain only successful recording outcomes, replay their delivery semantics, and retry failed recording (`eval.clj:3568`). The base cache needs an explicit retention decision for a **returned context containing refusals**: `seon.cache/lookup` removes throws, but defaults to retaining every non-nil result (`src/seon/cache.clj:121`, `:152`). Missing late host bindings must not become permanently cached unavailability.

The central cache's single byte budget and concurrent lookup are appropriate. `256 × installed Vars` is an unmeasured weight, and the installed set is not the count of all copied namespace bindings/descriptors retained by a base. Calibrate against that retained structure, including projection/loader references, and keep the branch-release retention proof. The net −110 remains an estimate until the newly named consumers and live-root handling are included.

The proposed cost separates useful O(k + closure) **installation** from the diff read, but the latter is still O(N) to compare full populations when needed; query caching does not make every map comparison constant-time. Measure full input construction, closure, fork, arming, hits/misses and retained weight separately. Count actual complete empty deltas, not only `interpreted-count`; that count measures the selected function set, while acquisition independently installs agent tests (`eval.clj:2913`, `:3101`, `:3116`). Four surviving LRU entries are not a derivation distribution. A bounded sample of actual misses and empty deltas can establish the opportunity without making an entire day's observation a correctness prerequisite.

### Required regressions for the revised slice (not run)

- One retained loaded-base derivation across eight sibling worlds and receipt-only commits; concurrent callers share it; controlled eviction permits rederivation. Equal content constructed in different transaction orders compares equal, with actual diff/key cost measured.
- Old file-derived function and test rows across adoption return branch results. Unchanged transitive callers and tests execute the override; host-bound differences refuse by name. The unchanged branch commit acquires against a newly loaded L; failed/unknown adoption does not reuse a successful world.
- Calls-only/analysis-only change outside the old selected delta, declared invocation target change on a data row, and disappearance of a previously affected caller update selection or refuse incoherence. Missing evidence is not an empty delta.
- Function/test/namespace additions and deletions, deletion with surviving callers, alias/import/refer replacement and removal, and lazy invocation agree with the branch. Deleted identities cannot return through lazy or late host binding. Namespace cycles remain named refusals.
- Config-only failure/recovery, supplied-default-only change and projection-only change refresh receiving behavior without replaying source. Include colliding fingerprints and a schema-declared injected-binding change, plus a no-function-delta call with branch defaults/projection different from JVM arming. Preserve the owner's required-input-only supply rule.
- A mutated program atom, a private atom and a closure over each survive an unrelated program change, projection/default rearming, settlement and the next turn with identical live roots and no repeated initializer effects. Siblings retain independent agent-owned roots; adoption that removes an unchanged row from the delta does not erase continuing private state.
- Late test loading after cache construction works with an empty delta, including namespaces already present but lacking a binding. Receiver custody, loader and recorder remain correct across forks and equal-content reacquisition. An acquisition refusal records/delivers to each receiver; recording failure can retry.
- Direct `base-ctx`, already-acquired test forks, candidate `d/with` worlds, same-run transferred definitions and lazy entry all acquire the exact intended world. Branch release leaves no cache/listener/recorder reference; cache eviction leaves active contexts intact.

### Evidence and scope of this round

At entry, `bin/seon status` and MCP reported PID 66374, start `2026-09-27T01:30:21Z`, archive `7e8043de1`, with the cluster database not yet acquired. A later read-only `runtime_status` found no missing readiness layers but health **faulted**: 13 open faults, 5 errored receipts, 100 failed tests and 59 unknown failed-test records. These are foreign runtime observations, not review results. Source review continued independently; no test, restart, reload, adoption or branch mutation was performed.

A sequential read-only JVM inspection of already-realized old cache entries returned four bases at loaded commit `6ab871c4-079e-5cc3-aa6b-25f71e8c19ff`, each with `interpreted-count=0`, zero roots carrying `:seon.instrument/interpreted-original`, and no snapshot recorder. Host evaluation took 9 ms. It confirms absence of those wrappers in this sample, not absence of all mutable roots, test interpretation or full branch deltas. Installed Var metadata located `derive-base-ctx`, `loaded-program`, and `acquire-program!` at lines 3399, 1653 and 2803. This is current-owner evidence, not an installed implementation of the design.

Exact successful probe, with `root=/Users/sean/src/seon`, `cluster=default`, `mode=jvm`, `read_only=true`, `session_id=review-eval-slice2-design`, `timeout_ms=10000`:

```clojure
(let [cache-var (ns-resolve 'seon.sci.eval 'base-context-cache)]
  {:vars (into {} (map (fn [s]
                        [s (select-keys (meta (ns-resolve 'seon.sci.eval s))
                                        [:file :line])])
                      '[derive-base-ctx loaded-program acquire-program!]))
   :bases
   (when cache-var
     (mapv (fn [[key cell]]
             (if (realized? cell)
               (let [ctx @cell
                     snapshot @(:seon.sci.kernel/program-snapshot ctx)
                     bindings (mapcat vals (vals (:namespaces @(:env ctx))))]
                 {:loaded (second key)
                  :interpreted-count
                  (get-in snapshot [:seon.sci.eval/acquisition
                                    :seon.sci.eval/interpreted-count])
                  :interpreted-wrapper-count
                  (count (filter (fn [v]
                                   (and (sci.impl.utils/var? v)
                                        (sci.impl.vars/hasRoot v)
                                        (contains? (meta @v)
                                                   :seon.instrument/interpreted-original)))
                                 bindings))
                  :recorder? (some? (:seon.flow/commit-fault! snapshot))})
               {:unrealized true}))
           @@cache-var))})
```

The first attempt used the same form with `sci.utils/var?` instead of `sci.impl.utils/var?`; it failed compilation with `ClassNotFoundException sci.utils` before inspection. Both full MCP envelopes were read, with no elided result in the successful probe. No cached delay was forced unless already realized.

Foreign boundary: the shared checkout advanced during review, with other lanes' changes in turn/schema/test files, submodules and CLM material; `src/seon/db.clj` and `src/seon/sci/eval.clj` also advanced after the cited source snapshot. None were edited, reverted, tested or included in this review's commit. Only this owning Markdown review is changed; production/test line delta is zero. The design and implementation remain with their owners.

## Evidence boundary

Read `CLAUDE.md`, the plan and companion audit end to end, and traced the acquisition constructor, its callees, cache owner, relevant dependency implementations and tests. The plan at `cc3514a1e` is the review target. Its subsequent edit at `d148e8638` records the owner's data-def ruling and order 1 → 4 → 2 → 3; both are accepted inputs to this review. The companion audit's request for that ruling is therefore stale, not an outstanding decision.

Source citations below use `d148e8638` unless stated otherwise. Other lanes began editing `eval.clj`, `fn.clj`, `schema.clj` and additional files during review. Their uncommitted changes are outside this review. No foreign changes were reverted, tested, adopted or committed. No worktree was created.

`bin/seon status` and MCP `runtime_status` found default alive at PID 89341, start `2026-09-26T21:35:37.344Z`, serving committed source archive `bb07fdf0890961c63ea5641ad1baf86a542faad8`. Health was **faulted**, with one open fault signature, four errored receipts, 31 failed tests and one unknown failed-test record. These are existing runtime observations, not failures introduced or diagnosed by this document. Installed Var metadata confirmed the reviewed acquisition seams and line locations; it does not prove every checkout byte is installed.

Two read-only JVM probes ran sequentially with explicit root `/Users/sean/src/seon`, cluster `default`, session `review-eval-shrink-plan`. They inspected existing cache keys and profile cells; neither invoked acquisition or evaluated SCI source. Exact forms and observations are below. No tests, restarts, reloads, branch mutations or performance trials were run. Heap retention and allocation improvements remain unmeasured.

Dependency pins inspected: Datahike `67b00071e020a7ccf310985d01b28128cdadec34`; SCI `743d1c4317d95af9d1af74928392b913c0d1b91d`; Malli gitlink `43c6dca8d2d8df3162b13725939954807be4a4ed`. The relevant guarantees are Datahike's scoped revision comparison and commit materialization, and SCI's generation fork, cited below.

## Slice 2 — REVISE

### The measured cause is real; the exact hit-rate claim is not established

`base-ctx-key` includes `program-basis(program-identity(database))`, loaded commit and recorder (`src/seon/sci/eval.clj:3399`). The basis includes connection identity, generation, conservative revision and selected attribute revisions (`:3307`, `:3324`). The program-identity memo records the first observed basis for a commit (`:3336`); `watch-program-identity!` registers a per-connection listener (`:3357`). Thus equal program content on independently advanced branches can have different keys. The live cache inspection confirmed four entries sharing loaded commit `6ab83b0e-0e4d-580e-b88b-1a3653770360`, all without recorders, with branch-specific bases.

Datahike explicitly requires connection and generation equality when comparing revision counters: two freshly opened connections have empty revision maps even when their data differ (`reference-code/datahike/src/datahike/query.cljc:3010`). Removing those two fields while keeping the counters is **incorrect**. The dependency's shared query-result key drops branch/generation only when retaining store identity and the exact commit (`query.cljc:2684`); that seam does not identify program equality across different commits.

The first-write explanation needs qualification. The listener can record a new branch's own basis before acquisition sees it. But `acquired-database?` also recognizes unchanged revisions within the same lineage and rewrites the commit memo to the previous acquired identity (`eval.clj:3509`). A data-only write therefore does not inevitably rebuild an already acquired context; the existing behavior regression explicitly checks reuse (`test/seon/sci/branch_execution_test.clj:149`). Fresh contexts, lineage transitions, memo eviction and ordering determine the miss. The four-entry LRU (`eval.clj:3277`) is another source of repeated derivation.

The reported 45 derivations / 50 base calls implies 90% derivations per lookup, and 10% inferred hits **only if** every derivation belongs to exactly one counted cache miss. That condition is not proved. The plan itself directly calls `derive-base-ctx` twice; a nil key bypasses the cache (`eval.clj:3459`); failures evict and retry (`:3453`); profiling counts completed calls including throws, by cell, under concurrent work (`src/seon/profile.clj:107`, `:138`). The plan does not establish whether its direct probes preceded the cumulative sample. Inclusive totals overlap and must not be added. The 398/629 ms examples identify expensive paths, not an uncontended latency distribution. The cited 11.2 GB is JVM-wide allocation, not retained context size or allocation attributable solely to this operation.

The review's later cumulative sample was base 81 calls / 18,077 ms, derive 69 / 18,401 ms, acquire-program 69 / 18,166 ms, function-digests 143 / 3,962 ms, loaded-program 89 / 956 ms; all five cells reported zero throws. Derivation mean was 266.681 ms, max 1,701 ms. Derive's total exceeding base's is consistent with direct calls and/or concurrent sampling; it is not a new cache hit measurement. The cost warrants the proposed priority, with the attribution limits retained.

### Actual acquisition inputs

The constructor depends on more than a function/test/namespace **definition-digest** diff:

| Input | Read and consequence |
|---|---|
| Loaded program and host bindings | `loaded-source` reads the adopting connection, not the branch's old adoption record (`eval.clj:1619`). `loaded-program` materializes that commit (`:1645`). Host roots, loaded namespace availability and imports are read by `build-base-ctx` (`:345`), `install-first-party-namespaces!` (`:2226`), `install-caller-host-bindings!` (`:2289`) and `install-declared-classes!` (`:2637`). |
| Branch function population | All symbols, source, admission source and privacy are read (`:2820`); all definition digests are compared (`:2832`). All function descriptors remain in the lazy program snapshot (`:2900`), not only the initially interpreted set. Symbol removal is therefore an input too. |
| Branch namespace population | Namespace presence, source, admission source, requires, aliases, imports and refers are read (`:2800`, `:2812`, `:2856`). Every namespace receives bindings (`:3005`), even when no function in it is interpreted. |
| Branch tests | Test source, admission source and resolved namespace identity determine installed agent tests (`:2882`, `:3083`); changing or deleting a test must affect content equality. |
| Call graph and declared invocation targets | `reverse-closure` determines which callers must interpret (`:2842`; `src/seon/fn.clj:1916`). It reads calls and declared invocation edges. Those edges can come from data rows through an attribute named by `:seon.fn/invokes`, not just function rows (`fn.clj:1714`, `:1871`). |
| Pulled declaration facts | `install-row!` pulls the committed row and sometimes the loaded row (`eval.clj:1717`, `:1769`). Source, spec, arglists, host-bound status, admission and resolver bindings affect installation/refusal and arming (`:1753`, `:1814`, `:3063`). |
| Projection content | `derive-base-ctx` derives the effective projection (`:3372`); construction, schema interpretation and wrapper compilation use it. A small fingerprint alone is not identity: `test/seon/sci/acquisition_attributes_test.clj:11` deliberately demonstrates unequal projections with colliding fingerprints. |
| Arming configuration | `install-function-contract!` calls `instrumentation-config` (`:1347`), which reads effective cluster config, result caps and error-evidence bound (`:1306`, `:1333`). Equal program rows and projection do not imply equal config values. |
| Supplied-default declarations | The same arming call captures `call-preparation/snapshot` (`:1356`). Its row query reads key, schema and supplier identity (`src/seon/call_preparation.clj:111`, `:285`). These values are not a function/test/namespace digest diff. |
| Fault recorder | Interpreted wrappers retain the supplied callable (`eval.clj:1363`, `:2914`). Until recording/arming is moved out of the shared base, callable identity is part of the key. |

`install-program-doc!` is not an additional database dependency: its database/projection arguments are unused, and installed macros resolve the calling world's data at invocation (`eval.clj:2612`, `:2622`). This is the useful separation to retain.

Definition digests deliberately omit admission source, calls, host-bound status and analysis evidence (`src/seon/program.cljc:373`). Consequently a definition-digest map alone cannot establish equality of the reads above. Concrete counterexamples are an equal source with changed admission, repaired call analysis/host-bound evidence, a changed effective cap, and a changed supplied-default supplier. Equal current proposed keys can yield different installations or wrappers. `program/digest-map` is an existing authority for declaration comparison (`program.cljc:481`), but its declared purpose does not make it a complete acquisition key.

### Correct key and simpler composition

For the current constructor, use this semantic key:

```text
K = [loaded-program-identity, acquisition-content, fault-recorder-identity]

acquisition-content = {
  complete branch declaration inputs used by acquisition and lazy installation,
  call/declared-invocation graph inputs used to select affected callers,
  effective projection construction inputs,
  arming config values,
  supplied-default declaration content
}
```

These are descriptions of existing input values, not proposed new registries or stored revision stamps. Represent content as immutable maps/sets keyed by declared identities, with component refs normalized to their meanings rather than allocation-specific entity ids. Preserve absence, additions and retractions. Use full structural equality, or the existing canonical digest owner over those complete values. Missing read/analysis evidence must refuse explicitly; it must never become an empty diff. Projection identity must cover forms, contracts and effective compile options/predicate bindings, as its own constructor does (`src/seon/schema.clj:2329`), not only the collision-prone fingerprint or a fresh holder atom.

**A full content key is simpler than a diff key.** For fixed loaded program L, a complete branch content value B and a lossless diff(L, B) partition branches identically. The diff adds baseline reads, tombstone rules and another completeness proof without improving reuse. A branch commit id alone is correct for exact committed data but recreates the unwanted miss after unrelated writes. Loaded commit alone conflates different branches. Agent-authored digests alone miss old file rows, deletions and the other inputs above. No connection id, generation or new revision token belongs in the semantic key.

Acquire the loaded identity and the input value once, then pass those exact values into key construction and derivation. Do not key one projection/loaded record and reread another inside the constructor. The one-argument `base-ctx` currently keys loaded identity as nil but resolves it inside acquisition/copy (`eval.clj:3410`, `:3428`, `:3445`); resolve that path before lookup too. Keep the existing start/end loaded-program change check (`:1581`). Unknown/failed adoption is not a cacheable successful world.

The loaded identity is valid only if it identifies the host roots and availability being copied. `load-core-namespaces!` deliberately defers test namespaces (`:2202`), while construction reads `find-ns`. A namespace becoming available under the same loaded commit must not leave a cached unavailable binding indefinitely. State and prove the acquisition/publication ordering for these late loads, or refresh the affected host binding at its existing installation owner. Do not add an ad hoc epoch to conceal that dependency.

Use Datahike's existing dependency-aware query reuse for acquisition-input reads (`query.cljc:2582`, `:3010`). A central-cache entry per exact committed value may avoid repeating content construction on that value; it must not become a writer-maintained program stamp. Re-reading all N function rows and hashing them on every receipt commit is still O(N). F2 removes a repeated loaded-side query, not half of the branch-key computation, and the measured 7–15 ms function query does not measure the complete key proposed here. Measure that complete operation before promising near-zero acquisition.

### Branch semantics need a producer fix

`CLAUDE.md:125` requires other branches to interpret differing rows and affected callers; authorship is not the criterion. Current `overridden-row?` explicitly chooses the loaded JVM Var for an older file-derived row (`eval.clj:1678`). `acquire-program!` repeats that restriction with `authored` and an `:agent` filter (`:2834`). A complete cache key can distinguish those branches while still deriving the wrong behavior. Option 1 is therefore not, by itself, the claimed branch-correctness fix.

Compare all relevant branch definition identities against the actual loaded program, independently of admission source; interpret the supported differing rows and their affected callers, and refuse unsupported host-bound overrides by name. Preserve namespace changes and deletions as well. Existing tests at `test/seon/sci/branch_execution_test.clj:62`, `:277`, `:345` and `:428` are useful starting points, but add an old **file-derived** branch across adoption and verify returned values, not merely the interpreted set. Option 2 cannot stand on the branch law; option 3 retains this existing defect despite its smaller cache change.

### Sharing a base does not share an agent's world

`copy-base-ctx` already gives the caller new kernel atoms and its own database (`eval.clj:3417`). `fork-cluster-ctx` rebuilds custody/environment and rearms interpreted wrappers for the receiving branch (`:3733`, `:3764`). `regenerate-agent-context!` preserves owned Vars and live objects (`:3163`). Retain those guarantees during this cut.

SCI `fork` copies the environment atom and assigns a generation; it does not deep-copy Var-root objects (`reference-code/sci/src/sci/core.cljc:334`). Under the owner's ruling, `(def m {:a 1})` is a program row. A program row such as `(def a (atom 0))`, or a callable closing over mutable initialization state, makes cache purity a separate question: equal declaration text does not authorize sharing independently owned live objects. Keep shared program construction separate from per-world initialization; preserve a continuing agent's evaluated root. Do not regain isolation by restoring connection identity to the cache key. This is a required proof obligation before broadening cross-branch reuse, not a proposal to reverse the data-def ruling.

### Deletion closure and central-cache ordering

Deleting the listed revision functions requires converting **all** consumers in one loadable slice:

- `loaded-basis` and `loaded-program-current?` still call them (`eval.clj:3461`), and `acquire!` stores the old loaded basis (`:3628`). Replace that comparison with the selected loaded identity.
- `record-refusals-once!` derives its key through `program-identity` (`:3538`). Decide its content key or route dedup through the fault owner in the same slice; otherwise the deletion leaves an unresolved call. Recording must still target the receiving branch and deliver failures.
- Remove both listener registrations (`:3698`, `:3773`) and retire already registered `::program-identity` callbacks on live connections through the existing lifecycle. Removing a definition does not unregister a callback. Datahike exposes the registration/removal owner at `reference-code/datahike/src/datahike/core.cljc:200`.
- Convert tests that inspect the private cache atoms (`test/seon/cluster/branch_release_retention_test.clj:42`) and replace the deleted-helper fingerprint test with the surviving key's collision behavior. Keep their behavioral guarantees.

Move the surviving base memo into `seon.cache` in slice 2 rather than implementing its new key in an obsolete cache and moving it again in slice 5. `cache/lookup` already supplies concurrent one-derivation behavior, failure removal and retention predicates (`src/seon/cache.clj:115`). Use its existing **one byte budget**, per-kind counters and insertion weight; do not add per-kind size dials (`cache.clj:7`, `:53`, `:194`; `CLAUDE.md:308`). Refusal values require an explicit retention choice, since `lookup`'s default accepts any non-nil result. No new cache atom or second single-flight implementation is needed.

The claimed −110 lines is not yet supported by a diff: it omits the consumers above and complete input derivation. Re-estimate after the revised composition, naming the obsolete paths removed.

**Required regressions for slice 2:**

1. Equal program/config/default/projection content on sibling branches, before and after independent receipt/note/test-result commits, shares one retained derivation. More than four branches must not fail merely because the old cache held four entries; controlled eviction may legitimately cause rederivation. Count cache misses directly, including concurrent callers.
2. Different overrides produce different results; an unchanged transitive caller calls the branch's override. Cover older file-derived rows across adoption, supported vs host-bound overrides, namespace changes, function/test additions and retractions, and lazy first invocation. Equal content rebuilt in a different transaction order can share.
3. Changing only config, a supplied-default row, graph/host-bound analysis, or an invoked data-row target changes the affected acquisition behavior. Unrelated data leaves it reusable. Missing/incoherent analysis or a read refusal is explicit.
4. Different projection populations with equal fingerprints do not share; fresh equivalent projections can share. Loaded adoption, failed adoption, the one-argument base entrance and late namespace availability cannot return stale host bindings.
5. Both branches' supplied database/connection, fault routing and wrappers remain their own. Private Vars, results, closures and atoms survive subsequent turns in the same context and do not leak to siblings, including the newly admitted data-def class.
6. Failed derivation is retryable; eviction releases only cached derivations, never a live agent context. Adapt the existing released-branch weak-reference test to `seon.cache/retained`; inspect hidden wrapper/recorder references as well as top-level snapshot fields.

## Slice 1 — ACCEPT-AFTER-FIXES

Memoizing the loaded digest map is appropriate: `function-digests` scans all function identities (`eval.clj:1662`), and acquisition calls it for both loaded and branch values (`:2832`). One immutable loaded commit does not need repeated full scans. Retain that map in the central cache under the loaded store/commit identity, with a measured insertion weight and explicit refusal handling.

Do not memoize the current `loaded-program(database)` return by bare commit id unchanged. It includes the caller's carried projection via metadata, and can return the caller's database itself (`:1650`). Datahike `commit-as-db` also attaches the caller's connection-generation cache context (`reference-code/datahike/src/datahike/versioning.cljc:473`). Two callers naming the same loaded commit can therefore receive differently annotated/resource-associated objects today. Sharing the first return can retain a retired branch and provide the wrong projection.

Cache the immutable materialization under **[store identity, loaded commit]** at the loaded-store owner, separate from caller projection/custody attachment; prefer a detached/store-scoped value where the dependency permits it. Materialization from a raw store remains detached (`versioning.cljc:477`). Pass the caller's effective projection explicitly or attach it after lookup without changing cached content. Reuse the existing store-lifetime owner; define retirement/retention rather than keeping an arbitrary branch database indefinitely. A cached DB pins resources and must be weighed accordingly. Bare commit ids do not identify an owning store in the process cache; Datahike's own shared-result key retains store identity (`query.cljc:2684`). Missing commits must remain loud and not be retained as successful values.

The proposed additional per-kind config dial is unnecessary. `seon.cache` already has one configured budget and accepts a weight function per lookup. The +10-line estimate and ~140 ms saving remain estimates; primary-only materialization, annotation separation and resource ownership must be represented in the actual diff.

**Required regressions:** repeated loaded reads/digest lookups hit; a moved adoption record selects the new commit while an old branch retains its own program; equal commit identifiers in distinct stores do not cross; different carried projections are not inherited from the first caller; failed/unavailable materialization can recover; releasing the originating branch does not pin its node cache. Start with `an-adoption-record-moves-the-loaded-program-every-acquisition-compares-against` (`test/seon/sci/branch_execution_test.clj:277`) and released-branch retention (`test/seon/cluster/branch_release_retention_test.clj:36`).

## Slice 3 — ACCEPT-AFTER-FIXES

The useful simplification is narrower selectors and batched reads. `install-first-party-namespaces!` does not use its `_namespace-rows` argument (`eval.clj:2242`), so remove that argument. It does need the complete admitted symbol population. `install-declared-classes!` consumes imports from **all** namespace rows (`:2637`), and acquisition installs aliases/imports for all program namespaces (`:3000`). Restricting those reads to the interpreted namespaces changes the reader context of a first definition in a clean namespace.

Source is also retained for **all** functions in `kernel/cache-program!` (`:2900`) and used by lazy installation (`:1382`). Stopping the initial source query is safe only if the existing lazy installation owner obtains source from the receiving branch's held database when needed. Do not leave empty descriptors or read source from the loaded branch by accident. Treat that caller conversion as part of F4, not a guaranteed mechanical −20 lines.

Build a narrow, complete namespace-binding projection in one owner, retain all required binding facts and fetch selected declaration payloads when used. Cost should be O(namespace binding facts + admitted symbol identities + interpreted payload), plus the reached graph work; the current code cannot become O(overrides) while constructing every namespace and host binding. Measure after slice 2, when misses are rarer, before expanding this optimization.

**Required regressions:** the two interpreted-caller tests named by the plan (`test/seon/sci/branch_execution_test.clj:345`, `:428`); a namespace containing no overrides still supports its first definition, aliases, renamed refers and imported classes; private/core host bindings remain available; an unloaded namespace produces the named refusal; a previously uninstalled function loads the correct branch source lazily; namespace binding cycles and failed reads remain explicit. Verify selector completeness and fewer pulls, not only lower elapsed time.

## Slice 4 and remaining slices

The table uses the shrink plan's numbered slices, not the companion audit's S1–S8 numbering. Later-slice verdicts are bounded design findings, not approval of an unseen implementation diff.

| Slice | Verdict | Required correction / regression |
|---|---|---|
| 4 — D0 mechanical | **ACCEPT** | Remove the unused requires/bindings, four ignored `bindings` arguments and their unused observer computation. `bindings` reads only context and before-values (`eval.clj:1073`). The `declared-row` spec arm is unreachable for `row`'s schema/ns/deletion products (`:469`, `:3839`); retain the separate schema `with-contract-facts` call at `:3828`. Privatizing the two listed local-only Vars is acceptable. No new behavioral test is needed for unused bindings; preserve existing evaluation/schema and program-retraction coverage. |
| 5 — remaining caches | **ACCEPT-AFTER-FIXES** | Base cache migration belongs with slice 2. Use one central byte budget, not separate kind dials. If refusal dedup moves, prove per-target durable recording, delivery and retry after recording failure; record-refusals currently distinguishes connection targets (`eval.clj:3538`). Do not silently drop it when deleting program identity. |
| 6 — evaluation constructor | **ACCEPT-AFTER-FIXES** | Consolidate common fields while preserving presence semantics: nil and false are returned values, not missing. Success and failure differ in ending namespace, record source, restoration, interruption and timing (`eval.clj:4075`, `:4119`, `:4181`). Regress success, evaluated failure, unrun refusal, interruption, renderer failure, bindings, output and cost fields. |
| 7 — one definition authority | **ACCEPT-AFTER-FIXES** | Apply the settled ruling: data defs are program rows; clj-kondo owns classification. Remove runtime type classification and reader head checks with their consumers. Update contract admission for arglist-less data declarations without inventing a second runtime classifier. Prove file/REPL parity for data `def`, `defonce`, `defmulti`, point-free callable defs, nested/qualified declarations, multiple children, redefinition and deletion. Preserve opaque/atom live roots and restoration; the sentinel also serves unbound/deletion state (`eval.clj:1893`, `:3141`), so classification removal alone does not prove every sentinel use dead. |
| 8 — candidate gate through test owner | **ACCEPT-AFTER-FIXES** | Preserve seeded auto-check, test namespace requires, exact candidate rows and custody. Prove a test/definition authored earlier in the same run is tested even before final settlement, and that refusal does not promote the candidate. The current evaluator passes a database to `run-candidate-test!` (`eval.clj:5009`); replacing the path must preserve the working program, not test an older committed branch. Verify one candidate evaluation, test evidence and resource cleanup. |
| 9 — arm once / pre-eval gate / no re-reads | **REVISE** | The audit's static batch must prove ordering for `require`, `in-ns`, aliases, schema registration and same-run definitions (`eval.clj:1093`, `:3776`). A gate for static declarations cannot promise that runtime-generated mutations or arbitrary side effects are undone. Retain a loud guard/restoration path for unindexed writes until its replacement is proved. Test preceding namespace changes, throwing partial definitions, root rebinding and early side effects; rejected declared forms must not execute. Transferred armed roots must use the receiving world (`:3764`). The MCP reply must use the settlement's value, including errors, without a later connection reread (audit D9). |
| 10 — CLJ rename / doc move | **ACCEPT-AFTER-FIXES** | Check namespace/resource/path consumers before renaming. Move the single doc owner with all dynamic references; moving lines is not deletion. Preserve `doc`/`dir`/`source` behavior in the viewing branch (`eval.clj:2612`). Reader regressions retain source spans, namespace-sensitive syntax, reader errors and trailing prose. |

**The deletion trap is confirmed.** `my.program/native!` invokes `(requiring-resolve 'seon.sci.eval/evaluate-native!)` at `src/my/program.clj:680`; the target is `eval.clj:1904`. It is live despite an unused-private-var lint finding. Keep it and retain ordinary program retraction coverage. Do not generalize unused-var findings to deletion without checking symbols in data and dynamic resolution.

## admit.clj — retain the question, revise the suggested replacement

There are two value walks with overlapping type classification. The floor's `classify` and `prepare` select and display the actual value under a presentation budget (`src/seon/render/value.clj:492`, `:1091`). Admission instead builds a complete finite print-node representation, an EDN encoding and a semantic projection under a storage bound (`src/seon/sci/admit.clj:299`, `:512`, `:760`). These are different current responsibilities; proving overlap does not prove either output unused.

Print nodes and EDN still have consumers:

- `kernel/invoke` calls `admit-value` inside the armed boundary (`src/seon/sci/kernel.clj:856`).
- Artifact construction/readback retains `:seon.sci.admit/print-node` and derives the stored semantic value (`src/seon/render/value.clj:1203`, `:1237`, `:1258`).
- Error normalization stores admission EDN; `fact-source` decodes that grammar (`src/seon/error.clj:552`, `:738`).
- Settlement still admits a changed disposition before rendering it (`src/seon/turn.clj:3594`).

One concrete avoidable cost is already visible: `admit-value` says it constructs no storage EDN, but invokes `admit*` and only dissociates the completed string afterward (`admit.clj:794`); `admit-walk` always creates the builder, print tree, semantic value and string (`:763`, `:779`). Correct the ownership/documentation and measure the unnecessary output before planning replacement lines. The semantic reconstruction is a second traversal of the finite node tree, although the source is visited once.

`print-method` on a bounded Writer plus `clojure.walk` is not an equivalent drop-in replacement. Clojure `walk` realizes sequences with `doall`, and `postwalk` is recursive (`reference-code/clojure/src/clj/clojure/walk.clj:35`, `:53`). The current codec deliberately avoids recursive depth failure, polls at each node, does not enter deref/array references, projects declared world identities, and rewinds a partially emitted failing node (`admit.clj:335`, `:369`, `:487`, `:512`). A Writer only observes emitted output; it cannot interrupt an arbitrary host printer that blocks before writing. Printing arbitrary values also permits custom print behavior and loses the codec's explicit record/reference/opaque grammar. A displayed floor tree can contain cuts and cannot substitute for complete stored evidence.

The smaller next step is a consumer-by-consumer decision about which boundary needs original live values, which needs saved shown text, and which genuinely needs bounded durable evidence. Remove obsolete admission/serialization calls at their producer while retaining one evidence codec for remaining storage consumers. Do not add a second serializer or merge display elisions into stored-value semantics. No admit rewrite is approved in this plan.

**Required regressions for any later codec change:** existing round-trip/identity tests, cyclic host values, infinite realization under the arm, projection failure, deep nesting, whole-or-missing storage semantics, diagnostic preservation, artifact and error readback (`test/seon/sci/admit_test.clj:140`, `:297`, `:344`, `:392`, `:468`, `:534`). Add exact UTF-8 boundary cases including surrogate pairs and isolated surrogates: the current `utf8-length` counts every surrogate as two bytes (`admit.clj:97`), which is not proof of actual encoded length for malformed pairs. Preserve failure locality and actual live result handles.

## Measurements and documentation to carry into implementation

For each slow slice, compare the same finite probe on parent and installed change, record complete key-construction cost separately from derivation/fork/arming, and use central-cache hits, misses, load time, evictions and weighted bytes. A retained base may be derived again after eviction; the bound is not “one forever per loaded commit.” Count distinct complete input keys, including projection/config/recorder differences. Attribute allocation to the operation where possible and report retained memory separately. The existing start/end loaded-identity check remains required even when cache lookup is fast.

The data-oriented Clojure and REPL skills describe the current base as memoized by program identity; that omits the connection-scoped reuse limitation found here. Their cited context line numbers are also stale (`.agents/skills/data-oriented-clojure/SKILL.md`, “Derive state”; `.agents/skills/repl/SKILL.md`, “Context”). The REPL skill still says a plain def receives a temporary-state note, contrary to this assignment's settled ruling. Update those claims when the implementation lands. They were not edited because this assignment owns only this review.

### Exact read-only probes

MCP arguments for both: `root=/Users/sean/src/seon`, `cluster=default`, `mode=jvm`, `read_only=true`, `session_id=review-eval-shrink-plan`, `timeout_ms=10000`.

```clojure
(let [n (find-ns 'seon.sci.eval)
      k (ns-resolve n 'base-context-cache)
      p (ns-resolve n 'program-identity-cache)]
  {:loaded-vars
   (into {} (map (fn [s]
                   [s (select-keys (meta (ns-resolve n s)) [:file :line :private])])
                 '[base-ctx derive-base-ctx loaded-program function-digests revision-basis]))
   :base-keys
   (when k
     (mapv (fn [[basis loaded recorder]]
             {:connection (:datahike.cache/connection-id basis)
              :generation (:datahike.cache/generation basis)
              :attribute-count (count (:datahike.cache/attribute-revisions basis))
              :loaded loaded :recorder? (some? recorder)})
           (keys @@k)))
   :identity-cache-count (when p (count @@p))
   :loaded-identity @(var-get (ns-resolve n 'loaded-identity))})

(mapv seon.profile/observation
      (filter #(contains? #{'seon.sci.eval/base-ctx
                           'seon.sci.eval/derive-base-ctx
                           'seon.sci.eval/acquire-program!
                           'seon.sci.eval/function-digests
                           'seon.sci.eval/loaded-program}
                         (:seon.profile/sym %))
              (seon.profile/cells)))
```

First probe: host evaluation 2 ms, identity-cache count 256; loaded Var lines 3431, 3368, 1645, 1662, 3307 respectively. Keys had branch/attribute counts `agent-9b9715260fda/86`, `host-test-3d04312b72c5/9`, `oc-fresh-ns/9`, `oc-fresh-ns/39`, under store `224560ae-b0ba-3d5c-b6db-6d7415ad66e7`; loaded identity was the commit cited above. Second probe: host evaluation 4 ms; profile results are recorded in the measurement section. Both complete MCP envelopes were inspected. Their nested display metadata marked the saved responses retrievable; no unseen response content is used as evidence. Saved response digests: `987b19676ceaac711a737dd3c4348b9eee2c12d6536323ee30a15285c987e4bd` and `615cfa47299ff58bd8ba713d23414368fb62cdbbf884105003ebf2f09710f466`.

Only this Markdown file is changed by this review. Production/test line delta: zero. All regressions above are requirements for subsequent implementation, not passes claimed by this review.
