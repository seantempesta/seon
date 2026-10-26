# Reactive render over Datahike: how the field does it, and what we should do (research, 2026-10-26)

Inputs: notes/render-design-2026-10-26.md (re-pull 0.96 ms, patch 0.43 ms, `=` on 923 KB 0.195 ms, `identical?` 1 µs).

## How each system knows what changed

| System | Change detection | Cache / key | Keeps identity of unchanged subtrees? | Large collections |
|---|---|---|---|---|
| **posh / re-posh** (Datascript) | From each pull *result* it builds datom patterns: `[eid #{attrs} _]` per entity, `[_ attr eid]` per reverse ref, `[eid _ _]` under `*` (`posh.lib.pull-analyze/tx-pattern-for-pull`). Each tx datom is matched by **linear scan** over all patterns (`datom-matcher/datom-match?`); a hit **re-pulls the whole pull** (`posh.lib.update/update-pull`). | Keyed by `[:pull db pattern eid]` | No: a re-pull is a new object | `remove-limits` strips `(limit …)` before making patterns; nothing newest-first |
| **`listen!`** (Datascript/Datahike) | The tx-report (`:tx-data`, `:db-before`, `:db-after`); posh is built on it | n/a | n/a | n/a |
| **Reagent / re-frame** | Signal graph: a reaction re-runs when an input changes by `identical?` (`_handle-change`), and notifies its dependents only if the new value is not `=` to the old one (`Reaction._run`: `(= oldstate res)`). re-frame caches subscriptions by query vector, compared with `=` (`re-frame.subs/cache-key`). Components skip a render when their props are `=` | One subscription per query-v; one component instance per tree position | `=` short-circuits on `identical?`, so shared structure is free and fresh-but-equal data costs a full walk | The app's job |
| **Replicant** | Pure top-down: re-renders from the root each time. `unchanged?` compares old and new hiccup with `=` and returns the old vdom. Lists match by `:replicant/key`, else by position (`replicant.core/reconcile*`, `update-children`) | None beyond the previous vdom | Yes, by reusing the old vdom node | The app's job |
| **Fulcro 3** | Normalized db by ident; `db->tree` denormalizes the root query each render; default `keyframe-render2` renders from root and pure-component props compare skips subtrees; `ident-optimized-render` diffs the db at mounted idents | Props per component | Yes | App-level load params |
| **Electric v3 (Hyperfiddle)** | Typical apps `e/watch` the conn and **re-run** the query on every db change. `e/diff-by kf xs` "stabilizes successive states of collection xs" into an incremental sequence (`:grow/:shrink/:change`, permutations) keyed by `kf`, so `e/for` mounts and unmounts only the changed rows | Per keyed row | Rows yes; the query no | `:limit` in the query |
| **React** | `memo`: shallow `Object.is` on props; keyed O(n) reconciliation | Position + key | Yes | Virtualization |
| **DBSP / differential dataflow / Materialize** | True incremental view maintenance: Q^Δ = D∘Q∘I. Linear operators pass deltas straight through. A join keeps indexed state ("arrangements") of both sides and computes Δ(a⋈b) = Δa⋈b + a⋈Δb + Δa⋈Δb, at cost ∝ \|Δ\| × index lookups, independent of the view's size | Arrangements (indexes keyed by join key) | Output is a delta stream, not a tree | Top-k is a stateful operator |
| **Datomic `index-pull`** | n/a | n/a | n/a | **Newest-first window:** walks `:avet`/`:aevt` with `:reverse true`, `:offset`, `:limit`, pulling each entity |

## Where they disagree, and what decides it

1. **Re-run and compare** (Reagent, Replicant, Fulcro keyframe, Electric) **or target by datoms** (posh, Fulcro ident-optimized, DBSP)? Fulcro's maintainers report ident-optimized render was no faster, sometimes slower, than keyframe plus memo: finding what to redraw cost as much as redrawing (Slack reports, not benchmarks). Our numbers agree in direction (re-pull 0.96 ms, patch 0.43 ms). **For us identity decides, not speed:** a re-pull yields `=` but not `identical?` objects, so the memo, the ctx diff and the hiccup diff each pay an `=` walk of all of ctx (~0.2 ms/MB). A patch keeps untouched subtrees `identical?`, so those steps cost only the changed paths.
2. **Don't copy posh's matching:** linear in patterns × datoms, and a hit re-pulls everything, discarding the precision it found. DBSP gives the right shape: a pull is a join chain rooted at one eid, and its "arrangement" is an **index from eid to its paths in ctx**: one hash lookup per datom.
3. **Memo by position, not in a global table.** A global `[f args]` hash memo hashes every fresh argument tree (O(size) on new objects). Reagent, Replicant and React all compare against the previous render at the same position and key, with `=` (which is `identical?` on shared structure).

## Recommendation: five composed functions

1. **`(pull db eid pattern)` → `[ctx idx]`**: Datahike's pull plus `idx`, eid → set of paths, recorded during the walk. The pull is the definition; everything below must equal it.
2. **`(window db eid rev-attr n)`**, newest-first: `(->> (d/rseek-datoms db :avet rev-attr eid) (take-while #(= eid (:v %))) (take n))`. rseek fills missing components with `emax`, so it starts at the newest referrer (eids grow monotonically) and touches only the seek path plus n datoms (`datahike.db.search/temporal-rseek-datoms`). The count is `-count-slice`, O(log n) when `-has-subtree-counts?` (`datahike.index.interface`). The cause is in the dependency, so this belongs in `datahike.pull-api/pull-attr-datoms` on a `jolt/<topic>` branch as a pattern option (e.g. `{:limit n :order :desc}`, items plus count), as Datomic's `index-pull :reverse` offers; the pattern stays the one editable value.
3. **`(patch [ctx idx] db-after tx-data pattern)` → `[ctx' idx' changed-paths]`**: per datom `[e a v]`, look up `e` in `idx`, and `v` when `a` is a ref read in reverse (a referrer entered or left: re-read that window). Re-pull only those entities' sub-patterns and `assoc-in` them (path copying); the rest stays `identical?`. Cost: O(|tx| lookups + touched entities × sub-pull + path depth), independent of ctx size. A changed pattern or branch is a new `pull`, not a patch.
4. **`(emit prev-tree hiccup)`**: walks `[f opts & children]` through the render dispatch (explicit, else schema, else printer); at each position and key, same `f` and args `identical?` (else `=`) to the previous call returns the previous output object. One tree; HTML and LLM text are both printed from it.
5. **`(diff a b)`**: one walk skipping `identical?` subtrees. On ctx: the data diff; on the emitted tree: the page diff and, printed, the turn's text diff. O(changed paths).

**Proofs:** generative `(= (first (patch (pull db0 eid p) db1 tx p)) (first (pull db1 eid p)))` over random schema transactions; a cost proof that `patch` and `diff` grow with |tx|, not ctx.

## Pitfalls
- One entity at several paths: `idx` holds a *set* (why Fulcro normalizes).
- Cardinality-one replacement is retract + assert in one tx: apply both, then re-pull.
- `*` makes any attribute on a matched e a hit (posh's `[eid _ _]`); `...` recursion grows `idx` unboundedly, so give it a depth.
- Window edges: a new referrer evicts the oldest and changes the count; a retraction brings one back. Re-read the window, never splice.
- Lists need stable keys (the eid): a head insert into a newest-first list otherwise shifts every position and every row's memo misses.
- Anything that rebuilds ctx (a re-pull, a copying `walk`) silently turns every check into an O(ctx) `=` walk. Measure the `identical?` hit rate.
- On a history db rseek merges current and temporal indexes; read the current db unless history is wanted.

Sources: posh `pull_analyze`, `datom_matcher`, `update`; reagent `ratom.cljs`; re-frame `subs.cljc`; replicant `core.cljc`; Datahike (reference-code) `pull_api`, `core`, `db/search`, `index/interface`; Electric `electric3.cljc`; Fulcro rendering docs and #fulcro 2020-04-24, 2022-11-02; docs.datomic.com/query/index-pull.html; DBSP (Budiu et al., VLDB 2023).
