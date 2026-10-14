---
type: research
lane: render-cache
date: 2026-09-26
source-pin: HEAD 20b156860 (render.clj last changed 5d353149b)
status: research — no code changed; the cache design is the owner's decision
---

# One render cache: what exists, and the smallest version

**What exists.** Seon already has a render memo. `seon.render/render-call` (`render.clj:1423`) keys a producer's output
on its argument (the value minus db/ctx), the producer's program reach, the projection and the selection inputs, then
proves each hit with read evidence (`db/read-evidence-current?`, `db.clj:1167`). Its entries live in an unbounded map
inside a per-branch atom, `:seon.render/cache` (`render.clj:1637-1660`). Six callers share that atom under private
sub-keys. The memory lane measured it at **69.8 MB, with no eviction**
(`docs/research/agent-platform/landing/mem-idle-2026-09-26.md`).

**The approach.** The owner's idea — (input, renderer definition) → text, kept small, evicting unused entries — is
the right shape. It should become **one region of the pending central weighted cache**: task `edc8cb02dfc2`
(`resources/seon/tasks/seon.cache.edn`), mem-idle design option 2, which uses Caffeine and one byte dial. It should
not become a third mechanism. What goes away: the per-ctx atom, its branch-scope guard, the two-tier "calls vs
invocations" retention, and the web page's private sub-maps.

## 0. Broken things first

1. **The current key costs more than a render.** `call-cache-evidence` (`render.clj:804`) recomputes the producer's
   call reach through `db/stored-call-reach` (`db.clj:1457`) on every call, at about 4 ms per call (2,657 ms over
   670 calls). A non-turn render costs about 1.5 ms compiled, or 0.4 ms in SCI
   (`render-graph-research-2026-09-25.md` §0.3, §3). perf-profile #8 measured 2.2 GB allocated for this in one
   debug-page window. This alone erases the value of reuse: opening 2 took 2.01 s even with retention.
2. **Cache entries pin database values.** `::page-databases` holds whole database values (`web.clj:2189`).
   `::ai-calls` results carry `:seon.db/db` (`web.clj:2540`, and the stored result at `:2556`). Through them the entries hold old index roots.
3. **Cache entries hold read results.** `retain-read-results? true` is set at `render.clj:1551` and
   `walk.clj:454`. `read-evidence` then keeps both the result and its digest (`db.clj:966-973`), while
   `read-evidence-current?` can prove a read from the digest alone (`db.clj:1195-1200`). The mem-idle lane found
   that "most of the 70 MB" is these results.
4. **Nothing is ever evicted.** `::invocations` only grows (`web.clj:2193`, `update … merge`). `::graph-calls` keeps
   every debug view `[agent lookup n]` forever (`web.clj:3346`).
5. **The cache doubles as a subscription registry.** `retained-interest` (`web.clj:1915-1926`) derives the attributes
   that wake a page's SSE stream from `::calls` and `::ai-calls`. Naive eviction would therefore silently narrow what
   refreshes a live page. That breaks the "never interpret silence as health" law. Any eviction must first move page
   interest into the page registration.
6. **The premise "re-rendering is never expensive" is false for one class today.** The turn renderer
   (`transcript.clj:790`, `render-run-ai`) issues about 13 `db/q` per turn and costs 1.43–1.86 s per turn: 42 turns
   took 4.5 s and 18.9 GB (render-graph §0.2). Until those reads move into acquisition, evicting a turn render
   costs seconds.

## 1. Every cache in and near the render path

| # | Cache | Key | Stores | Eviction | Invalidation | Where |
|---|---|---|---|---|---|---|
| 1 | `:seon.render/cache` atom (per branch-scoped ctx env) | holds `{scope…, sub-keys}` below | see rows 2–8 | **none** | a branch-scope mismatch allocates a fresh atom | `render.clj:1607-1660` |
| 2 | ↳ `::entity-pull` | `[::entity-pull lookup]` | pulled entity + read evidence (with results) | none | read evidence checked on hit; the newer basis wins | `walk.clj:410-460` |
| 3 | ↳ `::ai-calls` | `[lookup turn-id selector]` → call-id | whole history render + `:seon.db/db` | none | program evidence + read evidence | `web.clj:2506-2560` |
| 4 | ↳ `::calls`, `::fragments`, `::page-results`, `::page-databases`, `::programs` | page registration key | page derivation, database value | `dissoc` on a program change only | `program-key` changed | `web.clj:2155-2194` |
| 5 | ↳ `::invocations` | `invocation-cache-key` (selected, face, hashes of program/projection/selection evidence) | producer output + read evidence | **none** (`merge`) | read evidence on hit | `render.clj:871-895`, `web.clj:2193` |
| 6 | ↳ `::graph-calls` | `[agent lookup n]` | captured calls of the debug graph page | **none** | via render-call evidence | `web.clj:3321-3346` |
| 7 | ↳ `::packages` | — | web packages | overwritten | — | `web.clj:2432` |
| 8 | ↳ `:seon.render.web/calls [::prefix agent]` | agent | prefix-check render calls | overwritten | render-call evidence | `transcript.clj:2118-2146` |
| 9 | `:seon.render/retained-calls` / `captured-calls` | call id | per-derivation fold of calls. Also carries render-cost facts (`render.clj:1595-1604`) and debug "retained renders" | per request | — | `turn.clj:1920`, `render.clj:1881`, `web.clj` |
| 10 | `entity-owners`, render pairs | projection | schema → row/pair index | lives with the projection | projection fingerprint | `render.clj:313` via `schema/projection-cache-value` (`schema.clj:366`) |
| 11 | `throwable-safe` | class | boolean | **`memoize`, unbounded** (small domain) | none | `value.clj:348` |
| 12 | `program-identity-cache`, `base-context-cache` | commit id / program identity | revision basis / base SCI ctx | core.cache LRU 4 (27.4 MB) | none (content keys) | `sci/eval.clj:3201-3207` |

No render cache exists in `cluster/prompt.clj`, `turn.clj` (atoms there are per-call folds), `repl.clj` or
`context.clj`.

**Dependencies.** Seon already uses `clojure.core.cache` for about 15 `defonce` LRUs (for example `db.clj:1289`,
`:3807`; `schema.clj:1313`; `sci/eval.clj:3201`; `test/runner.clj:865`). core.cache 1.1.234 and core.memoize 1.1.266
come transitively (render-graph §5). core.cache has **no hit/miss statistics**. Caffeine 2.9.3 and 3.2.4 are in
`~/.m2` but are not yet a declared dependency; the pending task adds one. CLAUDE.md's approved pattern ("use the
dependency cache, otherwise Clojure's cache tools… Count misses"; "memoize functions of immutable values") fits a
Caffeine region with `recordStats` better than core.cache.

## 2. What renderers receive, and whether an input digest can be computed without rendering

`render-invocation-argument` (`render.clj:464-493`) gives the producer one of three arguments:

- the attribute's value;
- the render unit (a map carrying `:seon.render/value` **and `:seon.db/db` custody**);
- for the floor, the render argument.

The key-side projection `invocation-argument-evidence` (`render.clj:743-754`) already strips `:seon.db/db`,
`:seon.sci.eval/ctx` and `:seon.db/connection`. **The input is therefore in hand before rendering, and the key needs
no pull beyond the walk's own.** Research measured 170 of 174 nodes keyable by their EAVT `[a v]` vector alone, at
6.8 ms for all 174 (render-graph §4).

**The renderer survey.** The scanner is `scratchpad/survey.py`, run over every `:seon.render/ai|html` symbol in
`resources/seon/**/*.edn`. It found 95 distinct renderers:

| Class | Count | Examples |
|---|---:|---|
| pure over the argument | 61 (64%) | `seon.error/render-ai` (279 schemas share it, `error.clj:1833`), `render.value/render-ai` (floor, `value.clj:1143`), `render.ns/schema-ai`, `ai/model-ai`, `effect/render-ai` |
| receive the database in their contract | 26 | `render.transcript/render-run-ai` (`transcript.clj:790`), `plan/render-plan-ai` (`plan.clj:1519`), `render.test/render-ai` |
| query in their own body (`db/q`, `db/pull`, `opening-db`) | 17 | `cluster.status/render-ai` (`status.clj:164`, `db/q`), `note/render-notes-ai` (`note.clj:58`), `cluster.message/render-inbox-ai` (`message.clj:430`), `render.transcript/render-runtime-ai` (`transcript.clj:1047`), `render-captured-prefix` (`transcript.clj:2098`, `opening-db`) |
| take or read the database, all told | **34 (36%)** | |

The body scan covers only each `defn` form. Helpers called from it read more: the turn renderer's 13 queries per
turn happen in helpers. So 36% is a floor on how many renderers read the database. The hot renderers — turns,
runtime, plan, notes, inbox, identity — are in the reading set.

**Conclusion.** An input key works for about two thirds of renderers. For the rest, a hit is sound only if the
entry also carries the renderer's **read evidence**, which the existing path already captures through
`db/*read-evidence-sink*` (`render.clj:1513-1551`). The research's hit-soundness probe (138 of 138 identical,
render-graph §4) held for turns only because closed turns are immutable. The durable fix is the law "renderers
make no database reads": move reads into acquisition. Until then, keep read evidence (digest only) on entries whose
render read anything.

## 3. The renderer's definition digest

- Every function row carries `:seon.program/definition-digest`, 64 hex characters (`seon.fn.edn:136`,
  `seon.program.edn:1`, producer `program.cljc:388`). One row costs two index seeks: AVET on `:seon.fn/sym`, then EAVT
  on the entity. That is microseconds.
- The **definition digest alone is not sufficient**. A render changes when a callee changes. The right identity is
  the digest of the reached definitions. `db/builder-digest` (`db.clj:1475-1486`) does exactly this for the
  projection builder: `stored-call-reach`, then `id/digest` over `{sym definition-digest}`, at about 2 ms.
- **Cheap at call time:** memoize `reach-digest` per `[producer program-identity]`.
  - `seon.sci.eval/program-identity` (`sci/eval.clj:3261`) is already memoized by commit id and recorded on commit
    by `d/listen`.
  - That costs about 2 ms once per producer per program change. Around 20 producers appear in an opening, so about
    40 ms per program change (estimate). Every other call is a map lookup. This replaces the 4 ms per call of §0.1.
- **Private SCI renderers** (viewing-namespace functions with no row) have no digest. The key uses their callable
  identity, as `::private-callable` does today (`render.clj:822-823`). That key is valid only in memory, which is
  fine, because the cache does not persist.

## 4. The smallest correct cache

**Where it lives.** It lives in one region, `:seon.render/call`, of the central `seon.cache` (task `edc8cb02dfc2`).
It is process-wide. It does not ride the SCI context, so `branch-scope` and `shared-cache` disappear.

- **Branches share correctly.** The key holds content and program identity, not a branch. Two branches with equal
  input and equal reached definitions produce equal text.
- **Read-evidence entries stay sound across branches.** Each is proven against the caller's own database value
  (`read-evidence-current?` compares revisions within one connection generation). An entry from another branch fails
  that proof and re-renders. That is a miss, never a stale hit.

**Key.** The key is a plain vector compared by value; Clojure caches collection hashes:

`[face producer reach-identity profile argument-evidence]`

- `face` is `:seon.render/ai` or `:seon.render/html`.
- `reach-identity` is the §3 digest, or the callable for a private function.
- `profile` is the render profile (its budget, which the floor printer reads).
- `argument-evidence` is `invocation-argument-evidence`.

The key needs no `seon.id/digest`. The owner's text digest (→ vector for CLM ranking) belongs to that consumer and
is a store keyed by text digest. It does not key the render cache and does not persist the cache.

**Value.** `{:seon.render.call/output text-or-hiccup, :seon.render.call/read-evidence [...]}`. The evidence carries
`:seon.db/read-result-digest` and **no** `:seon.db/read-result`: a one-line change to the `read-evidence` options, or
`retain-read-results? false` plus the digest. No database values are stored.

**Hit.** The key is present, and either the evidence is empty or `(db/read-evidence-current? db evidence)` holds. On
a revision mismatch, `refresh-read-evidence` (`render.clj:911`) re-stamps the entry.

**Eviction and size.**

- **Policy:** Caffeine W-TinyLFU under the one byte dial, with the region weighted at insert as characters of output
  plus a constant per evidence entry. Frequency-aware eviction keeps turn renders (reused every opening) and drops
  one-off debug views.
- **Size (estimate):** root's 176 nodes print to about 610 KB pulled (render-graph §2). The rendered text is
  smaller. A region cap of about 16–32 MB therefore holds dozens of agent openings plus pages, against 69.8 MB
  unbounded today.
- **Fallback:** if the owner picks mem-idle option 1 (core.cache dials), this becomes one `defonce` LRU with an
  entry-count dial. That version has no byte cap, and miss counting must be added by hand.

**Miss counting.** Caffeine `recordStats` gives hits, misses and evictions per region, published once through
runtime status. Report the hit rate beside the render-graph baseline (97% across consecutive openings, 169 of 174).

**One render path / floor clips once.** The cache wraps only the producer invocation inside `render-call`. It
stores exactly what the selected producer returned, whether that producer is a schema pair or the floor printer. The
cache never clips, and the budget stays inside the key's `profile`. Selection still runs before the lookup, because
the producer is part of the key. Selection itself is memoized per `[schema face viewer program-identity]`: 1.3 ms
uncached (render-graph §1a, §4 step 4).

**What the cache is never used for:**

- **Shown text** (`:seon.eval/shown`) remains the stored observation, and the cache is never its source.
- **Render-cost facts and debug inclusion data** stay a per-derivation fold that `render-call` returns. They are not
  a cache.
- **Page wake interest** moves to the page registration: its last derivation's read evidence, held by the SSE
  registration. This must land in the same cut as eviction (§0.5).

## 5. Deletions and additions (estimates, not measured diffs)

| Change | Lines |
|---|---:|
| `seon.cache` itself | counted in task `edc8cb02dfc2` (≈60, roughly net neutral there) |
| render region: key fn, memoized `reach-digest`, lookup-or-render in `render-call` | **+40** |
| page interest held by the registration instead of scanning the cache | +15 |
| `render.clj`: `branch-scope` and `shared-cache` (1607-1660) | −55 |
| `render.clj`: `program-evidence-current?`, `render-program-evidence`, `retained-program-current?`, `invocation-cache-key`, `reusable-invocation`, `retain-invocation!`, `same-invocation-evidence?` and `call-cache-evidence`'s previous-entry branch (787-910) | −100 |
| `render.clj`: `render-call`'s two-tier retained/invocation reuse (1423-1605, 184 lines → about 70) | −110 |
| `walk.clj`: the `acquire-entity` cache (410-460) becomes a direct pull. Pull is about 0.02 ms per node; the read-evidence check is not cheaper | −35 |
| `web.clj`: `::ai-calls`, `::graph-calls`, `::invocations`, `::calls`, `::page-databases`, and the retained/captured-invocation threading (167 matching lines) | −120 to −150 |
| `transcript.clj` prefix-check cache plumbing; `turn.clj` `invocations` atom | −15 |
| **Net (estimate)** | **about +55 / −435 to −465** |

## 6. Correctness risk: stale output when a renderer reads data outside its key

1. **Reads that bypass `db/*read-evidence-sink*`** leave no evidence, so a hit goes stale:
   - direct `datahike.api` calls (the survey found none in renderer bodies, but helpers are unchecked);
   - reads through another owner's non-`seon.db` path;
   - `as-of` or opening reads (`opening-db` in `render-captured-prefix`), whose evidence semantics must be verified.
2. **Non-database inputs:** `config/effective` (775 ms over 670 calls today, render-graph §0.3), wall-clock time,
   live results (`result/e…` in the SCI context), private atoms, and the viewing agent's identity when it is not in
   `argument-evidence`. Each one must be in the key or be refused.
3. **Private SCI renderers redefined in place:** covered by the callable identity.

**Verification to require (one class regression).** For every declared renderer:

1. Render against a captured basis.
2. Transact one related change and one unrelated change on a disposable branch.
3. Assert that a cache hit occurs exactly when a fresh render is byte-equal.

The 138-of-138 probe in render-graph §4 is the precedent. The regression kills the "renderer reads outside its
evidence" class. The durable fix remains moving renderer reads into acquisition, which starts with the turn renderer
(§0.6).

## Method and limits

Source reading only, at HEAD 20b156860. No JVM query, no test, no measurement. Every number above is cited from
`render-graph-research-2026-09-25.md`, `landing/mem-idle-2026-09-26.md` and `perf-profile-2026-09-26.md`. The
survey script ran only the regex scan over `resources/seon` and `src` described in §2; it lives in the lane
scratchpad and was not committed.
