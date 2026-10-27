# Render and context: an independent second opinion (2026-10-26)

Written without reading notes/render-design-2026-10-26.md or notes/one-derivation-*.md. It is based on the code at b20d48ab82, plus three read-only evals on root.

## 1. Inventory

| # | Mechanism | Does | Duplicates |
|---|---|---|---|
| A | `ctx/node`, `build` (ctx.clj:65, 98) | Follows the schemas' `:seon.render/units` **recursively** (agent→runs→task, turns, messages; every eval), `'[*]` each | B |
| B | `render/one`, `walk` (render.clj:287, 329) | Default page: one hop plus `:seon.render/parts`; sorts all n linking datoms | A |
| C | `ctx/patch`, `advance`, `diff`, `follow` | O(Δ) patch; paths left untouched stay `identical?` | keep |
| D | `render/render-ctx` (render.clj:151) | The **parts layer**, with a case per key (`:task :cites :related :status :run :eval :tile`) | ruled deleted |
| E | `context/task-key`, `cited-key`, `related-key`, `first-t` (context.clj:269–383) | Hand-built cache keys: they parse the plan and seek entities on every draw. `first-t` is called per sort comparison (render.clj:157, 210) | F, G |
| F | `render/drawn`, `part-faces` (render.clj:217–252); `repl/drawn-parts` (repl.clj:482) | Part memo | G, H |
| G | `render.proc/comp-entry`, `comps-touched`, `:read-index`, `touched` (proc.clj:432–597) | Root's `d/with-reads` cache | F |
| H | `step-units` (proc.clj:150) | `=` on each unit's input, then its HTML bytes | F, G |
| I | `agent-page`, `ui/parts-page`, `ui/chat`, `ui/chat-part`, `ui/component` | Two agent layouts: the parts one, and walk plus `d/pull` per component | D, B |
| J | `context/page`, `world` (context.clj:646, 667) | Per turn: `build` at c0 (O(N)), then **two full page renders with no memo**: `task-data`, `cited` and `related` (an embedding search) each run twice | D, F |
| K | `context/records`, `printed-run` (context.clj:602, 511) | The conversation read again by `d/q`, not from ctx; a fourth cache, keyed by tx | A |
| L | `binding [*reads* nil]` (ui.clj:665, proc.clj:292) | Hides reads from G's tracker | an escape hatch |

Bluntly: **A/B, D/I, F/G/H and J/K are second systems.** An agent's page has four producers: the agent's parts, root's fallback, root's walk and `world`. There are four memos. Only C and the Reagent-style call expansion in `seon.web.html/write!` match the rulings.

## 2. The design: five functions

```clojure
(pull   db spec)       ; -> tree. spec = ctx's own :pull, data the agent edits by swap!
(patch  c db datoms)   ; -> c' with :changed (C, driven by spec instead of units)
(render v opts)        ; override → schema → printer (render/html, unchanged)
(expand h memo)        ; -> [h' memo']; call [f & args] hits when f and each arg are identical?
(diff   h0 h1)         ; -> changed keyed subtrees; skips identical? subtrees
```

- **`spec`** is `{:root [:seon.agent/key k] :pattern [...]}`, stored in ctx and written through `write!`.
  - It is one hop by default. A deeper path is explicit, for example `{:seon.run/_agent [{:newest 1} {:seon.turn/_run {:newest 20}}]}`.
  - The schemas' `units` and `parts` properties are deleted. `node` becomes `d/pull`.
- **Layout** is the `:seon.ctx/ctx` schema's render. It returns calls (`[task-col task]`, `[chat evals turns messages]`, `[eval-row e]`), each with a ctx subtree as its argument, never a db.
- **`expand`** is Reagent's rule inside `write!`, which already expands calls. Its memo `{[f key] [args result]}` is per process and is the **only** cache.
- **Two outputs** come from the one expanded tree:
  - the page, as fragments for each key `diff` returns;
  - the model's read, the whole tree on the first turn, then `diff` plus `ctx/diff`.
- **Deleted:** A's walk, B, D, E, F, G with its four indexes, H's compares, I's second layout, J's rebuild, K and L.
- **`d/with-reads` stays as a law, not a cache.** A test renders a page with no db in scope and asserts it read nothing. That makes "everything shown comes through ctx" a check that can fail.

**Where a pull is not enough:**
- *Cited vars* are named in plan text, not by refs. A spec entry may take a root that is a pure function of a ctx path, `{:root [cites [:task :seon.task/plan]] ...}`. It re-runs only when the plan string is not `identical?`, and the vars it names are pulled one hop.
- *Related* is an embedding search. It becomes a render memoized on `[plan vectors-t]`, with `vectors-t` one datom the pull holds.

## 3. One transaction, only the changed renders

1. **Commit.** A transaction commits. `on-state` calls `patch` with its `:tx-data`.
2. **Patch.** Each datom is looked up in `ix`.
   - An attribute is set at its path.
   - A ref into a bounded reverse unit joins it, with one pull. The oldest member leaves when the unit is past n.
   - Every map not on a changed path keeps its object.
3. **Expand.** The root render re-runs, since its arg is a new ctx, at O(calls). Each child call compares its args with `identical?`:
   - the task column hits;
   - `chat` misses, but each `eval-row` hits except the new one;
   - a `cited` call misses only if its var subtree changed.
4. **Diff.** `diff` stops at `identical?` subtrees. Memo hits return the same object, so the walk is O(changed spine).
5. **Send.** The page gets the changed fragments. The model gets `ctx/diff` and the page diff.

**What stays `identical?`:** untouched members, the task and its cited vars, every hit's hiccup, and the spec. New each time: the root, `:t`, and the ancestors of changed paths.

## 4. Growing collections

Pull's `:limit` (pull_api.cljc:315) runs `take` over AVET `[a v e]`, which is ascending: the oldest come first. Measured on root, `d/rseek-datoms db :avet :seon.eval/agent r` answers newest first.

- **Fix it in Datahike** (`jolt/pull-newest`): an attribute option `:newest n`, which reads `rseek` with `take-while (= v)`. That is about 15 lines and O(n), independent of N.
- **The older ones are a cut**, `(my.ctx/older :evals before-eid)`, not a count. A count is O(N) or derived state.
- **Ordering:**
  - Units are sorted maps by eid.
  - The conversation is a k-way merge of three sorted units, so `first-t` and its sorts are deleted.
- **Ref direction stays many→one.** A forward agent→evals ref would make `'[*]` on the agent pull N refs.
- **One restructure pays: `:form/refs` as a ref to the var entity**, not a symbol. Then callers are `:form/_refs {:newest k}`, where `docs/callers` (docs.clj:373) queries and sorts every caller today.
  - Cost: a store erase (DECISIONS).
  - Cost: forward references upsert by `:var/name`.
  - Cost: `related`'s rarity counts move to the reverse index.

## 5. Accounting

N = the session, K = the bound per unit (about 50), P = calls on the page (about K + 10), Δ = datoms per transaction.

| Operation | Today | Design |
|---|---|---|
| First page | O(N) pulls; O(N log N) sorts with an EAVT scan per comparison; plan parsed 3×; root's walk also O(N) | O(K·units) pull; P misses; plan parsed once |
| New eval | patch O(Δ). `render-ctx` O(N log N). Keys re-parse the plan. Root `=`/bytes per part. Model: 2 builds O(N) plus 2 unmemoized pages (related 2×) | patch O(Δ) + 1 pull. About 4 misses, P−4 hits. diff O(depth + K) |
| Cited var redefined | that part ✓, but related's `:read` holds every cited key (render.clj:203): the embedding search re-runs | 3 misses; related hits |
| New caller of 100k | O(100k) query + sort | join 1, evict 1, 1 miss |
| Plan edit | every task part, plus related | task column, `cites`, the changed cited calls, related |
| Quiet turn | 2 builds + 2 renders | `identical?` root: 0 renders, O(1) |
| Request at turn n | prefix cached by tx; new part O(N) + 2 renders; cold resume O(n·N) | prefix held; new = 2 diffs, O(Δ_turn); cold resume O(N + Σ changes) |

The one cache key is `[f key]`, compared by `identical?`: a pointer test per arg. No digest is involved.

## 6. Risks and doubts

- **Byte-stable prefix after a render is redefined.** "Code as of each t" on a resume means running old render source. I would re-derive with the current code on resume and accept the cache miss. The owner should rule.
- **Merged entities keep main's eids,** so `:newest` by eid may misorder them after a catch-up. I measured 0 inversions on main, but not across a merge.
- **The function root (`cites`)** is the one step that is not a pull. The alternative, citation refs written with the plan, stores derived data in the db.
- **Agent-edited specs can ask for an unbounded pull.** A config default `:newest` for reverse attributes keeps them bounded without a guard.
- **The memo is per process.** A page of an agent that is not running is expanded cold by root, at O(K) misses, once.
- **None of this has been tried in an agent's REPL yet,** which the rulings require. The `:newest` pull, the hit rates and the merge order are what to measure first.
