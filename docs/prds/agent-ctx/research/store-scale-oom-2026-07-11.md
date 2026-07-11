---
type: research
status: active
tags: [research, database]
---

# Store-scale OOM at fresh-agent mint — heap forensics + root cause (2026-07-11)

## TL;DR

The pod OOM at fresh-agent mint is a **datahike query-PLANNER execution bug on
CLJS**, firing inside `my.plan.internal/ready-leaves` (the plan block of the
first context render). The heap snapshot proves the planner **manufactured
15,411,789 tuples out of only 783 distinct pairs over 29 distinct values**
(~19,700× duplication; 783 = 29 × 27, an exact cross product of two tiny
columns) inside the evaluation of the inlined `ready`/`blocked`/`open-work`
rule tree. The plan graph in the store was NEVER bigger than 13 nodes / 5
parent edges / 0 `needs` (history-verified), so **no legitimate evaluation of
these rules can produce more than ~169 pairs** — every byte beyond that is
executor-created duplication. The pod is the only surface that runs the
planner (`datahike.query/*force-legacy*` is `false` on CLJS, and legacy on the
JVM unless `DATAHIKE_QUERY_PLANNER=true`), which is why the JVM was correct on
the same store.

This is investigation + evidence only (owner scope change mid-unit): **no fix
was landed**; the datahike lane owns the fix. Proposed patch direction + a
ready-made JVM repro harness are below.

## Symptom (established prior to this unit)

- Deterministic OOM on the acme cluster (pod 7980): once the store reached
  ~40k konserve keys, a fresh-agent mint / first context render grew the Node
  heap ~450MB → 4.4GB in ~16s → death at V8's default old-space cap.
- Not P6 code (pre-P6 stash and toolkit-cap-0 config OOM'd identically);
  cluster reset (fresh store) clears it.
- Forensic artifact: `Heap.20260711.121208.74047.0.001.heapsnapshot` (2.9GB,
  untracked at repo root; 40,643,259 nodes / 145,926,772 edges).

## Heap-snapshot evidence (all numbers measured, scripts in scratchpad)

Analysis tooling: streaming snapshot parser → typed-array binaries + BFS
retainer tracing + subtree/distinct-tuple counters
(`heap_load.js`, `heap_analyze.js`, `heap_retainers.js`, `heap_inspect.js`,
`heap_walk.js`, `heap_count_subtree.js`, `heap_distinct_tuples.js` in the
session scratchpad — trivially re-creatable; parse takes ~4s, whole analysis
minutes).

### Dominant heap content

- 18,248,359 `(object elements)` arrays (735MB) + 18,251,655 `Array` objects
  (584MB) + 1,414,182 `Object`s (87MB). 227,768 source-map `Mapping`s (16MB)
  are noise, not the killer.

### Retainer chain (95%+ of the arrays)

```text
(Stack roots)  [an ACTIVE frame — the query was mid-execution]
  → Object#3660        PersistentVector (a ctx :rels vector, 1 relation)
    → Relation#16429855  {attrs #16429856, tuples #16429857}
      attrs = {?d__auto__r83 → 0}      ← ONE attr; renamed rule var
      tuples = PersistentVector with:
        15,411,789 tuples, ALL width 2 (JS arrays of 2 smis)
        783 DISTINCT tuples, 29 DISTINCT values   ← measured via smi node dedup
        1.29GB self size in this ONE relation
```

- `?d` occurs in exactly one seon rule body:
  `[(blocked ?t) [?t :my.plan/needs ?d] (open-work ?d)]`
  (`src/my/plan/internal.cljs:43`). The `__auto__r<N>` suffix is minted by the
  planner's non-recursive-rule INLINING —
  `reference-code/datahike/src/datahike/query/lower.cljc:288` +
  `:321`/`:459` (`(gensym "r")`).
- A second context relation on the stack: attrs `{?t → 0}`, 27 one-wide
  tuples, all distinct (sane). **783 = 29 × 27** — the killer relation is the
  full cross product of a 29-value column with that 27-row `?t` column, then
  `limit-rel`'d to the single `?d__auto__r83` attr (which is why attrs has 1
  entry while tuples stay width-2 — `limit-rel` narrows attrs but keeps
  tuples).
- The crashed Context's `:rules` map holds my.plan rules + datahike's
  pre-seeded built-in bitemporal rules (`valid-at`, `interval-*` …) — normal
  (`datahike.query/built-in-rules`, query.cljc:629).

### Call stack at death (function names on Stack roots)

```text
seon.agent.turn/run-turn! → prefetch-and-render-prompt! → render-prompt
→ seon.agent.ctx/render-context(-ai) → rendered-block-texts
→ my.plan.internal/ready-leaves   (also plan-body, anchor on the stack)
→ datahike.query/q → raw-q* → execute-planned-relation
→ datahike.query.execute/execute-plan
→ execute-recursive-rule → execute-branch-plans → execute-or (×6) → execute-not
→ datahike.query.relation/hash-join → join-tuples   ← innermost, allocating
```

### The data was tiny — history-verified

Against the acme wire-server (7981), over `(d/history db)`:
`{:plan-nodes-ever 13, :parent-edges-ever 5, :needs-ever 0}`.
Max legitimate `descendant` closure: ≤169 pairs. The 15.4M-tuple relation is
therefore **100% executor-manufactured multiplicity**, not data.

## Root cause

**Where:** `reference-code/datahike/src/datahike/query/` — the planner's
rule execution on the CLJS path:

- `execute.cljc:3024` `execute-recursive-rule` — semi-naive fixpoint; on CLJS
  BOTH shortcuts are compiled out (`magic-info` → `:cljs nil`,
  `use-delta-driven?` → `:cljs false` — the 2026-07-11 depth-1-truncation fix),
  so every iteration takes `execute-branch-plans` with `aug-ctx` = the FULL
  outer context (its unrelated `:rels` included) + `:rule-accumulators`
  (execute.cljc:3121–3126, :3211).
- `execute.cljc:2882` `execute-branch-plans` — per branch:
  `(reduce rel/hash-join (:rels result-ctx))` then `limit-rel` to head-vars.
- `relation.cljc:69` `tuple-key-fn` + `:103` `hash-join` — with **zero common
  attrs the key-fn degenerates to a constant key, so `hash-join` silently
  becomes a full Cartesian product**; and hash-join/sum-rel/subtract-rel all
  PRESERVE multiplicity, so once duplicates exist, every subsequent join
  MULTIPLIES them (dup₁ × dup₂ per logical row — exponential through the
  deeply nested OR/NOT/rule-inlined plan that `ready` lowers to).

**Mechanism (matches every measured number):** during evaluation of the
inlined `(not (blocked ?t))` sub-plan, a branch context carries relations
that share no variables (the renamed `?d__auto__r83` column vs the outer
`?t` column — `[?t :my.plan/needs ?d]` has 0 rows in the store, so nothing
ever links `?d` to `?t`). `reduce hash-join` cross-products them
(783 = 29 × 27 distinct pairs) and repeated re-execution/joining inside the
fixpoint + OR/NOT nesting multiplies each pair ~19,700× → 15.4M materialized
tuples → the next `join-tuples` (mid-flight at snapshot time) pushes past the
4GB heap cap.

**Why store-scale:** the amplification factor compounds with relation sizes
and iteration counts that grow with the store (more agents/turn entities in
`?t`-adjacent scans, more fixpoint iterations, bigger intermediate products),
so small stores stay under the heap cap and a grown store (~40k keys)
deterministically crosses it. The 27-row `?t` column already exceeds the 13
live plan nodes (27 ≈ plan nodes + other eids picked up by an unconstrained
column), consistent with scans widening as the store grows.

**Why pod-only:**

1. `query.cljc:57` `*force-legacy*` — `:clj` legacy-by-default,
   `:cljs false` → **the pod ALWAYS uses the planner**; the JVM wire-server
   uses the legacy engine (which requires bound vars in `not`/rules and
   dedups at rule boundaries — no explosion, correct results).
2. On CLJS the magic-set + delta-driven shortcuts are (correctly) disabled,
   so the vulnerable `execute-branch-plans`-with-full-outer-ctx path runs on
   every fixpoint iteration.

## Independent correctness findings (same code, bonus evidence)

JVM + `DATAHIKE_QUERY_PLANNER=true`, tiny 13-node store, `ready-leaves` query
(harness below):

- With the JVM shortcuts ON (magic sets + delta-driven): **wrong result** —
  returns `root` as ready even though it has open work under it.
- With shortcuts forced off (CLJS-equivalent path): correct (`a1` only).
- (Already known: CLJS depth-1 recursive-rule truncation, fixed uncommitted in
  the submodule by the datahike lane, `cljs_recursive_rule_test.cljc`.)

So the planner's recursive-rule path currently has three defects in one zone:
depth-1 truncation (CLJS, fix in flight), wrong `ready` results (JVM
shortcuts), and the duplication/cross-product OOM (CLJS, this report).

## Reproduction

### JVM harness (fast iteration; reproduces the CORRECTNESS bug today)

`scratchpad/repro.clj` (recreate from this recipe): in-memory datahike,
`my.plan`-shaped schema, the 5-node tree root→(a,b), a→(a1,a2), statuses
open/done, agent ref; run `ready-leaves`'s exact query with
`my.plan.internal/rules`:

```bash
DATAHIKE_QUERY_PLANNER=true clj -Sdeps '{:deps {org.replikativ/datahike {:local/root "reference-code/datahike"} org.replikativ/konserve {:git/url "https://github.com/seantempesta/konserve" :git/sha "32e3c59847184c3b4a3acea87797ed9c864a0ff7"}}}' \
  -M -e '(load-file "repro.clj") (repro/-main "20000")'
# WRONG: #{[a1 …] [root …]}   (root is not ready — it has open work)
# with magic-info + use-delta-driven? forced off (CLJS path): #{[a1 …]} correct
```

The MEMORY explosion did NOT reproduce on the JVM with junk datoms (100k),
nor with keep-history churn — it needs the CLJS executor (and/or the pod's
grown-store relation shapes). The definitive repro is CLJS-side:

### CLJS repro recipe (for the fixing lane)

1. In a CLJS test (node), build an in-memory datahike conn with the my.plan
   schema + the 5-node tree above.
2. Run the `ready-leaves` query with `my.plan.internal/rules` through the
   planner (default on CLJS) and assert BOTH: result = `#{a1}` AND bounded
   intermediates. For the bound, instrument `execute-branch-plans` /
   `rel-dedup-into!` to record `(count (:tuples rel))` maxima — with a
   13-node graph nothing should exceed a few hundred tuples. On current code
   expect duplication (compare `count` vs `count-distinct`).
3. Live-scale confirmation (optional): on the acme cluster, drive an agent to
   churn `my.plan` steps + turn/eval entities until the store grows
   (tens of thousands of keys), then `POST /agents/new` on 7980 and watch
   `process.memoryUsage()` — pre-fix it climbs unboundedly during the first
   render; post-fix it must stay bounded (~working set).

## Proposed patch (NOT applied — datahike lane owns it)

Root-cause layer, in order of leverage:

1. **Dedup at rule/branch boundaries** (semantics: Datalog relations are
   sets). In `execute-branch-plans` (execute.cljc:2882) dedup the unioned
   branch relation (or make `rel/sum-rel` dedup when attrs align); in
   `execute-recursive-rule` the seen-set already dedups the delta — the leak
   is the NON-fixpoint uses (`execute-or` branches, inlined-rule bodies)
   where duplicates survive and multiply through later joins.
2. **Make the zero-common-attrs `hash-join` loud or lazy**: in
   `relation.cljc:103`, when `common-attrs` is empty either raise (planner
   should have ordered a product explicitly) or route to an explicit
   bounded `prod-rel` — never a silent constant-key hash-join. This turns
   any future recurrence into an error instead of an OOM.
3. **Don't feed the whole outer ctx into rule-branch execution**: in
   `execute-recursive-rule`, restrict `aug-ctx`'s `:rels` to relations
   sharing vars with the rule's plan (the legacy engine effectively does
   this by construction). This removes the cross-product source.
4. Re-enable the JVM shortcut paths only after fixing the `ready`-leak
   (wrong-result finding above) — the magic-set/delta-driven path returns
   wrong rows for these rules even on tiny data.

Verification for the fix: (a) CLJS test from the recipe — correct result +
bounded intermediate counts; (b) JVM planner repro returns `#{a1}` with
shortcuts on AND off; (c) live acme mint on a grown store with RSS observed
bounded; (d) `bin/test-cljs` full suite; (e) the datahike lane's
`cljs_recursive_rule_test.cljc` still passes (depth > 1 correct).

## State / handoff notes

- **No seon or datahike source edits from this unit** — the uncommitted
  `reference-code/datahike` diffs (`execute.cljc`, `lower.cljc`,
  `nodejs_test.cljs`, `cljs_recursive_rule_test.cljc`) are the datahike
  lane's in-flight depth-1 work, untouched.
- Acme cluster: healthy and running (pod 7980 HTTP 200, wire-server 7981),
  store `data/clusters/acme` currently ~14.8k keys, 21,311 datoms, 13 plan
  nodes — the pre-OOM 40k-key store state was reset at 12:23 (history in the
  current store starts fresh; the heap snapshot is the surviving record of
  the OOM store's behavior).
- The heap snapshot at the repo root should be kept until the fix lands
  (primary forensic artifact), then deleted (2.9GB).
- Smell (out of scope, noting per protocol): datahike's planner treats
  `hash-join` with disjoint attrs as silent cross product (relation.cljc:69
  constant key-fn) — a landmine for ANY query, independent of rules.
- Smell: `rel-dedup-into!` (execute.cljc:2843) on CLJS assumes tuples are JS
  arrays (`aget`) and indexes via `(nth indices j)` where `indices` may
  contain `nil` for head-vars missing from `:attrs` (see lower.cljc:364
  comment) — projects `undefined` silently. Worth an assert while in there.
