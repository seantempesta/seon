---
type: research
status: reviewed installed implementation; cuts proposed
created: 2026-09-27
tags: [schema, datahike, review]
---

# Installed vector storage: shrink review, 2026-09-27

**Recommendation: shrink in place, retaining cells.** The implementation contains duplicated parsing, tree walking and result decoding. Three of those duplicated paths already disagree with their owners. Native tuples or an EDN value are smaller storage, but neither preserves the required reference and membership behavior. Replacing cells would exchange this duplication for a second reference/query implementation.

**The ~468-code-line target is not yet demonstrated.** On the assignment's ≈771 added code lines, reaching 1.5 × 312 requires **303 net code lines removed**. The bounded cuts below plausibly remove **190–270 code lines**, leaving ≈501–581. These are review estimates, not a measured implementation diff. Do the shared-owner cuts first; demand an actual prototype and measured diff for the remaining 33–113 lines. Do not count deleted prose, contracts or relocated dependency code as code shrinkage, or silently narrow the vector contract to meet the estimate.

## Evidence boundary

Source citations are pinned to **441988b496706c1a3b9da9538290ba9559cab4cd**, the review HEAD and installed default archive. Read the eleven assigned commits, the revised vector design, its earlier review, the relevant skills and their named turn-context authorities.

Datahike gitlink: **591c825d6a47869486a56723532d6665f51ba636**. The parser dependency is `org.replikativ/datalog-parser 0.2.37` (`reference-code/datahike/deps.edn:14`); its callable seams were inspected through Datahike, not assumed from a target API.

Malli gitlink: **43c6dca8d2d8df3162b13725939954807be4a4ed**.

`bin/seon status` and MCP `runtime_status`, explicit root `/Users/sean/src/seon`, cluster `default`: pid **53665**, start **2026-09-27T21:18:45.129Z**, no missing readiness layers. **Health was faulted before this review:** 12 open signatures, 35 total signatures, 11 errored receipts, 113 failed tests, 46 unknown failures at the MCP observation. Reported historical maxima included boot request 23,020 ms and source refresh 22,868 ms. Cache weight was 61,516,698 / 512,000,000 bytes; this is cache accounting, not JVM RSS.

MCP JVM probes called the installed functions with an explicit connection. They did not redefine Vars, reload, adopt, stop/reset, start tests, or transact on default. Native dependency comparisons used immutable `datahike.api/with` values; these are **not** armed Seon writer/admission proofs. No connection, branch or retained probe namespace was created. The measured functions' instrumentation mode was not independently established. Two initially malformed review forms failed at the reader and were corrected; they are probe-author errors, not vector defects.

Installation was positively observed: all three bridge attributes are in the live Datahike schema; `vector-attribute-in?` finds 28 attributes. Entity **34508** pulls `{:seon.config.fs/roots ["."]}` through `seon.db/pull`, while native pull exposes presence and cells. Acquisition plus that comparison took **35.141 ms**, MCP evaluation **37 ms**. This is behavior evidence in addition to the archive identity.

## Ranked cuts

Numbers are **net executable-code removal estimates**, excluding docstrings, comments and contracts. Ranges are deliberately separated by responsibility; R2 excludes the cell grouping assigned to R5, and R3 excludes the result decoding assigned to R1. Integration may reveal less savings. No production implementation is authorized by this review.

| Rank | Cut and source | What replaces it | Net code lines | Risk and required proof |
|---|---|---|---:|---|
| **R1** | Delete the second query result-shape dispatcher, `src/seon/db.clj:2247–2267` (`decode-query-vectors`), and its separate result traversal at `:2300–2303`. | Add vector pull-field decoding to the existing `decode-query-result` / `decode-query-field` composition (`:2150–2245`), whose return-map handling already exists. Preserve the native-result fast path only when no field needs either codec. | **15–20** | Low/medium. Reproduce and fix the `:keys` failure below. Exercise relations, collections, tuples, scalars, `:keys/:strs/:syms`, mixed EDN and pull fields, and evidence-bearing results. One row traversal, no second envelope. |
| **R2** | Retire `selector-spec` (`src/seon/schema/datahike.clj:651–674`) and duplicate recursive selector/value walking at `:684–778`; stop lowering once to detect change and again to perform it (`:719–727`, `src/seon/db.clj:1027–1029`). | Consume Datahike's `PullSpec` from `compile-pull-plan` / `pull-plan-spec` (`reference-code/datahike/src/datahike/pull_api.cljc:80–101`). Extend existing `pull-output-options` and `decode-pull-entity` (`src/seon/db.clj:2305–2351`) with vector reconstruction/options. Lower once and carry logical/physical plans. Keep the physical plan for read evidence. | **45–60** | Medium. The native parser keeps both entries in a multi-entry map; the new parser loses one. Need all selector forms, aliases, defaults, per-vector limits, reverse component cardinality, recursive and wildcard pulls, query pulls, replay and temporal refusal. `PullSpec` does not itself implement vectors: budget the adapter, do not claim its mere existence deletes lowering. |
| **R3** | Remove raw-query rediscovery: `query-variable?`, `query-source?`, `clause-variables`, `query-map`, the `bound` and find-shape parsing in `lower-query`, and separate `vector-query?` tree scans (`schema/datahike.clj:798–812,901–909,925–946,963–983`). | Pass the parsed query already held by `encode-query-request` (`db.clj:2113`) and the existing `query-input-values`, `query-patterns`, `query-find-attributes` (`:1936–1966,2086`). Datahike's `memoized-parse-query` (`query.cljc:3132`) and the already-used parser `collect-vars`, `find-elements`, `get-source` are the seams. Retain a vector-specific clause transformation and hygienic, deterministic introduced variables. | **45–65** | Medium/high. Reuse **binding-aware** traversal, not unrestricted postwalk. Input-bound `missing?` is wrong today. Cover tuple/relation/collection bindings, rule-local variables, source symbols, NOT/OR join scope and cached-query identity. Measure no-vector queries before removing the detection fast path. |
| **R4** | Collapse successive encoding walks: `encode-vector-value` / `encode-vector-map` (`schema/datahike.clj:556–608`) followed by `encode-entity-in` / `encode-value-in` (`:462–488`), then `explicit-value-members` (`:985–1013`), composed at `:1041–1045`. | One entity fold returning `[encoded-entity operations]`, doing EDN slot encoding at its declared attribute, vector requests/cells at vector attributes, and explicit member operations where Datahike's two-keyword ambiguity requires them. `encode-attribute-value-in` must share the scalar codec without accidentally accepting entity replacement requests. | **35–50** | Medium/high. Never recurse into an opaque EDN-backed value as if it were an entity. Preserve metadata marking, nested transaction-function output, identity asserted by a separate operation, new tempids, and one batched request call. The keyword-pair workaround is live, not dead code. |
| **R5** | Share repeated cell grouping/sorting/value assembly in `cell-vectors` (`db.clj:3590–3639`), `cells-of` (`:4606–4621`), `declared-collections` (`:4852–4870`) and `decode-vectors` (`schema/datahike.clj:750–760`). | One small transformation over held cell rows with an explicit element decoder. Writer validation still checks physical completeness before decoding; pull still applies its logical selector; declaration values still map peers to identity lookup refs. Read rows once per parent and reuse grouped rows across that parent's requested vectors. | **25–40** | Medium. No universal database/selector framework. Exact definition-digest parity, component ownership and charged-node bounds are mandatory. Different views have different ref representations; share grouping/order, not those policies. Do not revalidate committed data on ordinary reads. |
| **R6** | Merge the two component-comparison traversals, `stored-component` and `desired-component` (`db.clj:4637–4672`); remove avoidable repeated presence/kind discovery across `vectors-tx` / `vector-diff` (`:4711–4713,4775–4787`). | One explicit comparison normalization supplied with the resolved element view, and one per-vector held plan. Existing reconciliation has almost the same current/desired normalization (`src/seon/reconcile.cljc:149–245`), but depends on `seon.db`; extract to the lower existing owner only if both callers can use it without a dependency cycle. Compute stored component **values only when a desired value-key needs them**. | **25–35** | High enough to prototype first. Do not directly substitute `declaration-value`: its peers are lookup refs, not eids. Require zero-datom republish of tempid-named components, reorder identity preservation, partial id-named updates, unresolved peer tempids, and distinct equal component maps. Eager stored value expansion is avoidable; matching by value itself is required. |
| **Total** | Six bounded cuts, with shared-owner conversion in the same slice. | No new registry, codec family or representation migration. | **190–270** | The ≈468 target remains unproven. |

The principal composition is **one parsed read plan, one value decoder, one entity encoder, one cell-order transformation**. Query transformations remain O(query size); encoding remains O(submitted value size); reconstruction remains O(cells + sum of per-vector sorting costs), with ordinary pull limited to selected data. No added cache is needed to justify the cuts. Any cache use must be the existing content-keyed owner.

### Why the 250-line writer block cannot simply disappear

The implementation already invokes the **existing** complete owning-value validator. `write-owned-values-error` discovers ancestors on both before/after values (`db.clj:3728–3909`), `write-entity-value` reconstructs the logical collection, and `write-entity-error` uses Malli's `m/validator` (`:3693–3710`; dependency `malli/core.cljc:2672`). Cells are charged through the same traversal and marked visited. There is no second entire ownership validator to delete.

Cell invariants are additional information: zero or multiple element datoms, non-contiguous positions, missing presence, and vector members outside cells (`db.clj:3612–3629,3665–3666`). A logical vector can satisfy its Malli schema after malformed cells have been sorted or discarded. `:seon.db.vector/cell` only declares a required position (`resources/seon/schemas/seon.db.vector.edn:15–16`); it cannot express the cross-cell position set or parent presence. **Deleting those checks as “schema duplication” is falsified.** Share reads and reconstruction; retain physical checks at the final writer.

Likewise, Datahike already owns ref resolution (`transaction.cljc:792–795`), incoming ref sweep (`:1008–1027`) and component cascade (`:834–838`). Its component flag does not prove exclusive ownership, missing-child validity or a cycle-free whole value. Keep Seon's ownership checks; do not reproduce cascade in vector-specific cleanup.

## Reproduced disagreements: repair by deleting the duplicate owner

All forms use MCP `eval_clj`, `mode jvm`, `read_only true`, explicit root/cluster. The successful combined diagnostic form took **4 ms**. With `db = @(seon.cluster.boot/connection "default")`, `p = (seon.schema/projection-from-database db)`:

1. **Multi-entry selector maps lose entries.** Native `pull-plan-spec` for `'[{:seon.fn/arities [:db/id] :seon.fn.arity/arguments [:db/id]}]` has both attributes. `(seon.schema.datahike/lower-selector p selector)` returns only `[:seon.db.vector/present {:seon.db.vector/cells [:seon.db.vector/position {:seon.fn/arities [:db/id]}]}]`. Cause: `(first element)` in `selector-spec`, `schema/datahike.clj:672–673`. This is a pure installed-lowering proof; no claim that those two attrs coexist on one function.
2. **Input-bound `missing?` observes physical absence.** `(seon.db/q '[:find ?e :in $ ?e ?a :where [(missing? $ ?e ?a)]] db 34508 :seon.config.fs/roots)` returns `#{[34508]}`, despite the positive pull `[:seon.config.fs/roots]` yielding `["."]`. `lower-query` leaves the predicate unchanged; `lower-clause` checks literal keywords only (`schema/datahike.clj:865–869`). Expected answer is `#{}`. The same literal-only restriction also appears in the get-else/get-some guard; its bound variants need regression proof, not an assumed pass.
3. **Return maps panic in the vector decoder.** `(seon.db/q '[:find (pull ?e [:seon.config.fs/roots]) :keys config :in $ ?e :where [?e :seon.config.fs/roots ?v]] db 34508)` throws `ClassCastException`, “Keyword cannot be cast to java.util.Map$Entry”, from `decode-query-vectors`, `db.clj:2256,2260`. Expected a relation of `{:config {:seon.config.fs/roots ["."]}}`. The MCP envelope reported `failure: evaluation`, not an ordinary query refusal. The first decoder supports return maps; the second does not.

**A suspected variable collision was falsified for the probed case.** A caller variable `?vector-cell-0` in find and a vector data pattern remained distinct from introduced `?vector-cell-1`; it returned `#{["."]}`, matching `?v`. Do not delete deterministic renaming as dead defensive code. That one case is not a hygiene proof for rule scope or quoted data.

Issue search covered the existing vector/query/pull notes, including `collection-attribute-query-bindings-leak-edn-storage-values.md`, `bound-pull-selector-evidence-retains-all-attributes.md` and the previous vector design review. They do not record these exact three reproductions. **Root follow-up tasks:** R1 return-map decoding, R2 selector parsing, R3 scalar query-function binding. Their evidence is retained here; this doc-only lane neither minted task ids nor edited the issue index or task resources.

## Smaller storage alternatives: measured and ruled out for this contract

Dependency mechanisms: a tuple is one value and one index key; homogeneous tuples cap at eight (`transaction.cljc:1029–1041`). Ref resolution and retraction dispatch on the attribute being `:db.type/ref`, not on a nested `:db.type/ref` tuple member. Native pulls automatically expand components (`pull_api.cljc:346–351`), but that supplies neither logical parent membership nor vector order.

Probe: `datahike.api/with` over a held live database, adding three temporary attribute declarations only to the derived value; insert `["b" "a" "b"]` as cells, homogeneous tuple, or EDN string. Twenty repeated speculative writes; 100 repeated reads. No Seon final-report validator or durable writer was invoked. Cells read through `seon.db/pull`; tuple reads are native pull, EDN reads native pull plus `edn/read-string`. Schema acquisition is outside warm timing. These are illustrative representation costs, not a fair armed end-to-end replacement benchmark.

| Representation | Data datoms | First write ms | Mean warm write ms | First pull ms | Mean warm pull ms |
|---|---:|---:|---:|---:|---:|
| Installed cells | 10 | 0.252 | 0.1184 | 29.169 | 0.0630 |
| One homogeneous tuple | 1 | 0.131 | 0.0402 | 0.0105 | 0.00335 |
| One EDN string | 1 | 0.0439 | 0.0381 | 0.0126 | 0.00348 |

All three restored the sequence in this scalar example. Full form: **48.077 ms**, MCP **51 ms**. In the same speculative value, a tuple declared `:db/tupleTypes [:db.type/long :db.type/ref]` retained `[0 82239]` **after retracting target 82239**. That directly falsifies using native tuple refs as a replacement for native ref lifecycle. A schema alone does not make a tuple slot an incoming ref edge.

| Alternative | What can disappear | What it would require or lose |
|---|---|---|
| One EDN value (smallest implementation) | Cells, positions, presence; writer multiset matching for plain values. | Element membership patterns no longer work; refs need a separate resolution/sweep/cascade owner; a containing EDN value cannot retain component entity identities. Reject under the current contract. |
| `[position value]` tuples or a homogeneous tuple | Cell entities and one link/position pair per member. | Positional tuples still need Datalog element extraction and pull decoding. Homogeneous tuple is bounded at eight. Neither gets native per-element refs. Reject as a universal encoding. |
| Parent members plus order metadata | Ordinary membership lowering could disappear. | Duplicates/order require metadata; tempids and deleted targets must update it under the writer. This is a second authoritative representation unless derived, and deriving loses order. Existing vector tx machinery makes a solution conceivable, so “impossible” is too strong; no smaller complete implementation or cost proof was found. |
| Position on existing components | One cell per component could disappear. | Peer vectors and scalar vectors still need another representation; repeated references and per-parent positions are not a property of the target. No uniform cut. |
| Native cells/components, as installed | Native cascade and resolution already replace custom lifecycle code. | Membership still needs parent→cell traversal; logical pull still needs ordered assembly. **Keep**, consolidate owners around it. |

Do not move 180 lines of lowering into the Datahike fork and report a Seon shrink. A native ordered-cardinality feature would be a separate dependency design touching transactions, indexes, temporal views, pull and queries, with its own total line/cost accounting.

## Dead branches, cost claims, prose and contracts

- **No large dead branch was proven.** Legacy/as-of behavior in `vector-attribute-in?` (`schema/datahike.clj:332–348`) and migration (`db.clj:4810–4849,5320–5348`) remains necessary for old database values, adoption and from-zero publication. Default being migrated does not make those paths unreachable. Commit `7b7deb4ad` is concrete history of the install-before-schema-row window; preserve that proof.
- The two-argument encoder with no `vectors-fn` is an actual API with a refusal, not evidence that batching is optional. The cell marker and already-encoded metadata prevent physical operations from being encoded again. Legacy pull `(limit … nil)` still has first-party callers (`src/seon/sci/eval.clj:2414–2415`). The keyword-pair explicit-member workaround predates vectors and remains needed for sets. Do not delete these by inspection alone.
- **The O(n) multiset-matching claim is false in the duplicate-heavy worst case.** `vector-diff` rebuilds `(remove used …)` and scans for a position for every desired item (`db.clj:4721–4723`). Equal values can cause Θ(n²) candidate visits. Installed speculative identical reassertions of 16/64/256 equal strings produced zero operations and took **0.107/0.261/1.217 ms** for diff alone (whole probe 10.331 ms; MCP 12 ms). These small timings do not themselves establish an asymptotic curve; the loop establishes the bound. A consumed queue plus same-position lookup can make matching linear, but measure mixed id/value component aliases and retention before replacing it. Do not add it to the removal budget until a smaller diff exists.
- `stored-keys` eagerly computes a whole component value even when the desired element is supplied only by eid (`db.clj:4697–4700`). Delay that computation by inspecting desired key kinds once. This is proportional-work shrink inside R6, not permission for another cache.
- **Prose cut:** shorten repeated encoding/temporal explanations at `schema/datahike.clj:318–343,503–513,690–695,911–920` and `db.clj:1015–1023,3591–3595,3642–3648,4598–4604,4748–4756,4811–4823,4853–4855`. Keep one-line responsibility, actual errors and corrected cost; link to the design for storage layout. Estimated **80–120 docstring/comment lines**, separately accounted. Delete repeated signatures from prose; do not drop the temporal or identity guarantee.
- **Contracts are not optional bloat.** Many now use the existing named projection, transaction and value schemas. `vector-request` (`schema/datahike.clj:541–547`) nevertheless repeats the operation enum from `resources/seon/schemas/seon.schema.datahike.edn:33–34` and returns an untyped tagged vector; batching removes that tag before passing the declared request. R4 can carry the existing named request value in its separate operations result and eliminate the tag constructor/predicate. Reuse the named request contract. Do not create a registry of one-off aliases merely to compress lines.
- Selector options and cell rows have inline structural contracts (`schema/datahike.clj:655–659`, `db.clj:4610`); the parser consolidation can delete the former function and its contract. Cell rows are **not** `:seon.db.vector/cell`: they contain storage observations, not an admitted entity. Replacing them by that schema would be wrong. Most of the ≈134 added contract lines disappear only with their functions; savings are outside the executable-code target.
- **Stale skill claims need their own correction:** `.agents/skills/data-modeling/SKILL.md:91–92` still instructs storing ordinals on ordered members, and `.agents/skills/datahike/SKILL.md:150–178` describes collection expansion without the installed vector cell conversion. Installed `logical-collection` and the 28-attribute probe refute teaching vectors as plain sets. These files are outside this lane's sole document ownership; root should assign the design's S12 update. Do not advertise the old skill text as current behavior.

## Root's focused proof list

No test request was started. For the doc-only commit, no behavior test is needed. For the proposed cuts, run these installed tests centrally, then add the three missing cases to the indicated existing behaviors:

`seon.schema.vector-storage-test/selectors-lower-to-cells-and-decode-to-vectors` (multi-entry map), `datalog-over-a-vector-attribute-means-membership` (return maps and bound scalar functions), `vectors-encode-as-cells-or-one-batched-request`, `vectors-round-trip-with-order-duplicates-and-presence`, `peer-vectors-keep-ref-semantics`, `raw-vector-operations-keep-a-meaning`, `component-vectors-keep-identity`, `the-writer-refuses-vector-storage-faults`, `pulls-read-logical-vectors-at-every-basis`, `datalog-over-history-is-an-exact-membership-change-view`, `reports-name-the-vector-owner`, `comparisons-see-order-only-where-storage-keeps-it`, and `the-migration-moves-members-into-cells-and-keeps-identities` (all names after the first share that namespace).

Affected owner integration: `seon.db-test`, `seon.schema-encoding-test`, `seon.reconcile-test`, `seon.source-reconciliation-test`, `seon.fn-test`, `seon.call-preparation-test`, `seon.program-test`, `seon.test-runner-test`. R5/R6 require definition-digest and zero-datom republication comparisons on the installed candidate as well as tests; R2/R3 require read-evidence invalidation and replay after reorder. Root owns the adoption/from-zero proof and the central test round.

## Landing checks and foreign boundary

Only this document is changed. Foreign working-tree changes observed: `.gitmodules`, staged `reference-code/CLM`, untracked contents of `reference-code/http-kit`, `resources/seon/tasks/seon.dev.mcp.edn`, a deleted scratch patch, and untracked `docs/prds/next-turn/` and `src-clm/`. None was used as candidate source or included in the commit; no foreign session was messaged or resumed.

Verification uses `tmp/review-vector-storage-shrink/archive`, a `git archive` of the pinned HEAD with this document overlaid, required dependency gitlinks extracted at their pinned revisions, and existing dependency class directories linked for reading. It is not a Git worktree. Source/test/resource delta: **0**. Installation/adoption claims apply only to the reviewed implementation, not these proposed cuts.

- `/usr/bin/time -l clj-kondo --cache false --lint src script test`: **0 errors, 948 warnings**, 6.49 s wall, maximum RSS **1,029,472,256 bytes**. Exit 2 reflects warnings; this meets the assignment's zero-error requirement, not a zero-finding claim. The unchanged source population is the foreign warning boundary.
- Static `seon.contracts-compile-test/check` against the archive's packaged projection, canonical `src test` scope: **0 findings**, exit 0, **41.84 s wall**, maximum RSS **1,387,298,816 bytes**. This is complete static-check JVM startup/load and whole-population work, not a live vector-operation latency. Called the checker directly; no `clojure.test`, `seon.test/run` or `bin/test` invocation. The initial classpath attempt found missing Datahike prepared classes (0.49 s); linking the existing dependency class directory resolved that archive setup boundary. No foreign source was repaired.
- The document embeds the measurement forms so the evidence survives the doc-only commit. Scratch logs are `tmp/review-vector-storage-shrink/{lint,contracts}.log`; no owned process remains running. The archive is retained as check evidence. No runtime reset/restart occurred.

Contract command, from the archive:

```sh
clojure -J-Xmx2g -Sdeps '{:paths ["src" "resources" "test"]}' -M:dev -e '
(require (quote seon.contracts-compile-test))
(let [f (seon.contracts-compile-test/check
         {:seon.contracts-compile-test/root "."
          :seon.contracts-compile-test/paths
          (seon.contracts-compile-test/source-files ["src" "test"])})]
  (prn {:findings (mapv #(select-keys % [:filename :row :message]) f)
        :count (count f)})
  (shutdown-agents)
  (System/exit (if (empty? f) 0 1)))'
```

## Reproducible measurement forms

These are bounded JVM dependency probes, not tests or writes to a connection. The exact forms used for the tables above follow; all derived database values are lexical and discarded.

```clojure
(let [base @(seon.cluster.boot/connection "default")
      started (System/nanoTime)
      schema [{:db/ident :review.vector/tuple :db/valueType :db.type/tuple :db/tupleType :db.type/string :db/cardinality :db.cardinality/one}
              {:db/ident :review.vector/edn :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
              {:db/ident :review.vector/ref-tuple :db/valueType :db.type/tuple :db/tupleTypes [:db.type/long :db.type/ref] :db/cardinality :db.cardinality/one}]
      b (:db-after (datahike.api/with base schema))
      values ["b" "a" "b"]
      cell-map {:db/id -1 :seon.db.vector/present #{:seon.config.fs/roots}
                :seon.db.vector/cells (mapv (fn [i v] {:seon.db.vector/position i :seon.config.fs/roots [v]}) (range) values)}
      cases [[:cells [cell-map]] [:tuple [{:db/id -1 :review.vector/tuple values}]] [:edn [{:db/id -1 :review.vector/edn (pr-str values)}]]]]
  {:review/cost
   (mapv (fn [[kind tx]]
           (let [t (System/nanoTime) r (datahike.api/with b tx) first-ms (/ (- (System/nanoTime) t) 1e6)
                 e (get (:tempids r) -1) d (:db-after r)
                 pull-fn (case kind
                           :cells #(seon.db/pull d [:seon.config.fs/roots] e)
                           :tuple #(datahike.api/pull d [:review.vector/tuple] e)
                           :edn #(clojure.edn/read-string (:review.vector/edn (datahike.api/pull d [:review.vector/edn] e))))
                 t (System/nanoTime) pulled (pull-fn) first-pull-ms (/ (- (System/nanoTime) t) 1e6)
                 t (System/nanoTime) _ (dotimes [_ 20] (datahike.api/with b tx)) write-ms (/ (- (System/nanoTime) t) 20e6)
                 t (System/nanoTime) _ (dotimes [_ 100] (pull-fn))]
             {:kind kind :data-datoms (count (filter #(< (:e %) datahike.constants/tx0) (:tx-data r)))
              :first-write-ms first-ms :warm-write-ms write-ms :first-pull-ms first-pull-ms
              :warm-pull-ms (/ (- (System/nanoTime) t) 100e6) :value pulled}))
         cases)
   :review/ref-sweep (let [r (datahike.api/with b [{:db/id -1 :review.vector/edn "target"}])
                          target (get (:tempids r) -1)
                          r2 (datahike.api/with (:db-after r) [{:db/id -2 :review.vector/ref-tuple [0 target]}])
                          owner (get (:tempids r2) -2)
                          r3 (datahike.api/with (:db-after r2) [[:db/retractEntity target]])]
                      {:target target :after-delete (datahike.api/pull (:db-after r3) [:review.vector/ref-tuple] owner)})
   :review/elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
```

```clojure
(let [base @(seon.cluster.boot/connection "default")
      p (seon.schema/projection-from-database base)
      started (System/nanoTime)]
  {:review/matching
   (mapv
    (fn [n]
      (let [values (vec (repeat n "x"))
            r (datahike.api/with base
                [{:db/id -1 :seon.db.vector/present #{:seon.config.fs/roots}
                  :seon.db.vector/cells (mapv (fn [i v] {:seon.db.vector/position i :seon.config.fs/roots [v]}) (range) values)}])
            db (:db-after r) e (get (:tempids r) -1)
            old (#'seon.db/cells-of db e :seon.config.fs/roots)
            t (System/nanoTime)
            ops (#'seon.db/vector-diff db p e :seon.config.fs/roots old values)]
        {:n n :operations (count ops) :ms (/ (- (System/nanoTime) t) 1e6)}))
    [16 64 256])
   :review/elapsed-ms (/ (- (System/nanoTime) started) 1e6)})
```
