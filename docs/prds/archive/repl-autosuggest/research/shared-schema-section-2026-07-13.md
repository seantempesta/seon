---
type: research
status: active
tags: [research, agent, schema, index]
---

# Shared schema section vs inline referenced-closure — measure + design

## TL;DR

The referenced-schema closure (the cross-namespace `(register! …)` defs now
appended to each ns card) is **inlined once per consuming namespace**. Across
the 13 namespaces a normal agent renders, that costs **2234 tokens**; the same
schemas rendered **once** cost **966 tokens** — a **net saving of 1268 tokens**
(~57% of the referenced-block cost, **~8% of the whole 15,808-token namespaces
block**). The saving is real and prompt-cache-friendly (schemas are stable
prefix material), but modest against the full prompt, and it trades away the
self-contained ns card.

**Recommendation: option B (threshold ≥2) IF we build a shared section at all.**
B captures the *entire* 1268-token saving while keeping the 20 single-consumer
refs inline (local). Option A (all referenced → shared) costs the same tokens as
B but loses more locality. Option C (fully-global, own schemas too) saves **zero
extra tokens** over A/B — own schemas are unique per namespace and already render
once — while destroying locality completely; it is strictly dominated. The open
question is whether ~1.3k tokens/turn justifies a new section mechanism +
cross-ns coordination against the one-mechanism / locality bar.

All numbers are measured live against the default cluster store
(`data/clusters/default/store`) on 2026-07-13, using the shipped
`seon.agent.ctx/schema-ref-closure` and `seon.ai.tokens/estimate`.

## 1. The agent-facing namespace set

A normal agent's `:namespaces` block renders its CURRENT (home) ns FULL plus its
DIRECT `:require`s as compact cards (`seon.agent.ctx.namespaces/namespaces-block`
= current ∪ requires ∪ `::full-source`). For the live `root` agent that set is
**13 namespaces**:

- Current (full): `my.agent.root`
- Required cards (12): `my.canvas`, `my.data`, `my.kb`, `my.plan`, `my.ui`,
  `seon.agent`, `seon.agent.fs`, `seon.agent.lifecycle`, `seon.agent.message`,
  `seon.agent.search`, `seon.db`, `seon.schema`

(Derived from `seon.eval/stored-require-targets` over the home ns, filtered by
`included-ns?`. This is the toolkit + core surface, NOT the whole DB.)

Per-namespace closure (own schemas / referenced schemas; 40-cap never hit):

| namespace | own schemas | referenced (closure) |
|---|---:|---:|
| my.agent.root | 4 | 6 |
| my.canvas | 28 | 7 |
| my.data | 9 | 4 |
| my.kb | 21 | 5 |
| my.plan | 51 | 7 |
| my.ui | 21 | 4 |
| seon.agent | 23 | 16 |
| seon.agent.fs | 60 | 7 |
| seon.agent.lifecycle | 2 | 9 |
| seon.agent.message | 9 | 5 |
| seon.agent.search | 38 | 0 |
| seon.db | 71 | 1 |
| seon.schema | 13 | 0 |

**37 distinct** referenced schemas across the 13 namespaces (76 total
`(ns, ref)` pairs).

## 2. Frequency ranking — how many DISTINCT namespaces reference each schema

`line-tok` = tokens of one `(register! <key> <source>)` line. `inlined` =
`ns-count × line-tok` (current cost). `saved` = `(ns-count − 1) × line-tok`
(what dedup removes).

| referenced schema | ns-count | line-tok | inlined | saved by dedup |
|---|---:|---:|---:|---:|
| `:seon.db/error` | 7 | 81 | 567 | **486** |
| `:seon.db/lookup-ref-value` | 4 | 17 | 68 | 51 |
| `:seon.db/ref` | 4 | 21 | 84 | 63 |
| `:seon.db/transact-response` | 4 | 90 | 360 | **270** |
| `:seon.db.id/agent-value` | 3 | 25 | 75 | 50 |
| `:seon.db.id/legacy-value` | 3 | 16 | 48 | 32 |
| `:seon.db.id/word-value` | 3 | 22 | 66 | 44 |
| `:seon.render.canvas/hiccup` | 3 | 21 | 63 | 42 |
| `:seon.render/error` | 3 | 11 | 33 | 22 |
| `:seon.render/html-response` | 3 | 51 | 153 | **102** |
| `:seon.agent/id` | 2 | 35 | 70 | 35 |
| `:seon.db.id/compact-value` | 2 | 28 | 56 | 28 |
| `:seon.db/db` | 2 | 7 | 14 | 7 |
| `:seon.db/db-val` | 2 | 8 | 16 | 8 |
| `:seon.items/count` | 2 | 8 | 16 | 8 |
| `:seon.items/items` | 2 | 11 | 22 | 11 |
| `:seon.result/ok?` | 2 | 9 | 18 | 9 |
| `:seon.agent.ctx/name` | 1 | 10 | 10 | 0 |
| `:seon.agent.fs.match/normalization` | 1 | 37 | 37 | 0 |
| `:seon.agent.fs.match/normalizations` | 1 | 23 | 23 | 0 |
| `:seon.agent.fs.match/range` | 1 | 14 | 14 | 0 |
| `:seon.agent.runtime/compile-state` | 1 | 12 | 12 | 0 |
| `:seon.agent.runtime/error` | 1 | 11 | 11 | 0 |
| `:seon.agent.runtime/llm-fn` | 1 | 16 | 16 | 0 |
| `:seon.agent.runtime/resume-request` | 1 | 61 | 61 | 0 |
| `:seon.agent.runtime/resume-response` | 1 | 73 | 73 | 0 |
| `:seon.agent.runtime/unhost-request` | 1 | 21 | 21 | 0 |
| `:seon.agent.runtime/unhost-response` | 1 | 32 | 32 | 0 |
| `:seon.code/block` | 1 | 25 | 25 | 0 |
| `:seon.code/lang` | 1 | 9 | 9 | 0 |
| `:seon.code/text` | 1 | 8 | 8 | 0 |
| `:seon.code/value` | 1 | 14 | 14 | 0 |
| `:seon.db.process/id` | 1 | 34 | 34 | 0 |
| `:seon.derive/state` | 1 | 18 | 18 | 0 |
| `:seon.items/envelope` | 1 | 37 | 37 | 0 |
| `:seon.render.canvas/content` | 1 | 27 | 27 | 0 |
| `:seon.render/system-input` | 1 | 23 | 23 | 0 |

Observations:
- **17 schemas** are shared (referenced by ≥2 namespaces); **20** are
  single-consumer (freq 1, zero dedup opportunity).
- The prediction holds: the top of the list is exactly the global vocabulary —
  `:seon.db/ref`, `:seon.db/error`, `:seon.agent/id`, the whole `:seon.db.id/*`
  identity chain, and `:seon.render/*`.
- The saving is **concentrated**: just two rows — `:seon.db/error` (486) and
  `:seon.db/transact-response` (270) — account for **756 of the 1268 tokens
  saved (60%)**. Both are large `:map` response envelopes that many toolkit fns
  return.

## 3. Token math for A / B / C

| quantity | tokens |
|---|---:|
| Whole `:namespaces` block (root agent) | 15,808 |
| CURRENT — referenced closure inlined (13 cards) | **2,234** |
| SHARED — each referenced schema once (A/B/C referenced part) | **966** |
| **Net saving (A, B, and C all realise this)** | **1,268** |
| — as % of the referenced-block cost | 57% |
| — as % of the whole namespaces block | 8% |
| All OWN-schema lines across the 13 nses (rendered once today) | 6,155 |

Key results:

- **A vs B are token-identical.** Both render each *distinct* referenced schema
  exactly once (966 tokens). B just *places* the 20 single-consumer defs inline
  (in their one card) instead of in the shared section. **B buys locality for
  the rare refs at zero token cost vs A.** Shared-section size: A = 37 lines
  (966 tok); B = 17 lines (~700 tok in the shared section) + 20 inline
  (~266 tok in their home cards).
- **C saves nothing beyond A/B.** Own schemas are unique to their namespace
  (`namespace(key) == ns`), so they already render once; moving them to a global
  section is a pure relocation (6,155 tokens either way, 0 saved). C's only
  effect is to strip every ns card down to bare `(defn …)` fn heads — maximal
  locality loss for no token gain.

Therefore the **entire** token win comes from deduping the *referenced* closure,
and it is **1,268 tokens** regardless of A/B/C.

## 4. The trade-off: dedup vs locality

- **Dedup (token savings):** 1,268 tokens/turn (~8% of the namespaces block).
  Because referenced schemas are stable core `seon.*` defs, a shared section
  placed at the TOP of the block is a rock-stable cache prefix — good for prompt
  caching and (this lane) for the training projection: the autosuggest exporter
  would see each schema def **once** per context instead of 2–7×, i.e. cleaner,
  less-redundant training signal.
- **Locality (self-contained card):** today a card is runnable-in-isolation —
  a fn's `:malli/schema` references `:seon.db/error` and the def sits three
  lines below. Moving defs to a distant top section forces a lookup; the whole
  point of the closure feature (a reader/model seeing what an arg IS *right
  there*) is diluted. This is the exact failure the inline feature fixed.
- **Complexity:** a shared section must be derived from the *rendered* ns set
  (the union of the 13 closures), and every card must know which keys to
  *exclude* from its inline block. That is new cross-section coordination
  against the one-mechanism principle — the closure helper stays one mechanism,
  but the render now has two placement rules instead of one.

B threads this needle: the 17 genuinely-global schemas (the vocabulary) go to a
shared prefix; the 20 one-off refs stay local. The heavy hitters
(`:seon.db/error`, `:seon.db/transact-response`, `:seon.db/ref`, the id chain)
are all freq≥2, so B still captures 100% of the saving.

## 5. Where each option plugs into the render assembly

The `:namespaces` section is ONE section fn,
`seon.agent.ctx.namespaces/namespaces-block` (`namespaces.cljs:381`),
symbol-wired into the config block layout as
`'seon.agent.ctx.namespaces/namespaces-block`. Its `selected`/`rows` locals
already hold the exact set of rendered nses. The shared closure helper already
exists: `seon.agent.ctx/schema-ref-closure` + `referenced-schema-block`.

- **A — shared referenced section.** In `namespaces-block`, after computing the
  rendered ns set, gather each ns's fn specs, run `schema-ref-closure` over the
  UNION (own-keys = the union of all rendered nses' own schema keys, so an own
  schema of ns X isn't re-emitted when ns Y references it), and PREPEND the
  result as a `;;; ┌─ shared schemas ─` block before the ns cards.
  `render-one-ns-compact` / `render-one-ns-ai` drop their inline referenced
  block (pass an `exclude-shared` set, or a `:shared? true` flag, so they skip
  keys already in the prefix). Placement: top of the block = stable cache prefix,
  ahead of the churning per-ns cards.
- **B — threshold ≥2.** Same as A, but partition the union closure by frequency
  within the rendered set: keys with count ≥2 go to the shared prefix; keys with
  count 1 are passed back to their single consuming card to render inline
  (each card excludes only the shared-set keys). Requires the block to compute
  the per-key ns-frequency (one `group-by` over the union of closures) — cheap,
  pure over the frozen db.
- **C — fully-global registry section.** A larger change: a section holding ALL
  own + referenced defs once (37 referenced + ~ every own schema of the 13
  nses). Each `render-one-*` drops BOTH its reg-lines and its referenced block,
  emitting only `(defn …)` heads. This is closest to a standalone `:schemas`
  ctx block (its own `:seon.agent.ctx/priority` slot in the config layout) since
  it no longer belongs to any one namespace — but see §3, it saves no tokens.

Either A or B can live INSIDE `namespaces-block` (recommended — the referenced
set is derived from the rendered ns set, so keeping them in one fn keeps them in
sync). C is better as its own ctx block but is not worth building.

## 6. Recommendation

1. **If minimizing tokens is the goal and a shared section is acceptable: build
   B.** It captures the full 1,268-token saving, keeps single-consumer refs
   local, and its shared prefix is exactly the stable core vocabulary (best for
   prompt cache + training projection). Implement it inside `namespaces-block`
   with a frequency partition; each card excludes only the shared keys.
2. **Do NOT build C.** Zero extra token saving over B, maximal locality loss.
3. **Reasonable to defer entirely.** 1,268 tokens is ~8% of the namespaces block
   and a smaller fraction of the full prompt; the inline design is simpler and
   self-contained (the property the closure feature was added for). If the
   decision is "not worth a second placement rule," a cheaper 80/20 is to
   **shrink the two dominant envelopes** (`:seon.db/error`,
   `:seon.db/transact-response` = 60% of the potential saving) at the source —
   but that touches their real schemas, so only if their verbosity isn't
   load-bearing.

My lean: **B is the right variant, but the token win is modest** — treat this as
a cache/training-cleanliness improvement, not a token emergency. Confirm the
appetite for the added render coordination before implementing.

## Reproduction

```clojure
;; live, against @seon.db/*conn* (default cluster):
;; 1. rendered set = (current-ns ∪ stored-require-targets), filtered by included-ns?
;; 2. per ns: seon.agent.ctx/schema-ref-closure over its fn :seon.fn/spec strings
;;            (own-keys = its own :seon.schema keys), cap 40
;; 3. frequency = distinct-ns count per referenced key
;; 4. tokens via seon.ai.tokens/estimate on each "(register! <key> <source>)" line
```
