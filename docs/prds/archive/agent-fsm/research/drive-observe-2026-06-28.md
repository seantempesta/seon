---
type: research
status: active
tags: [agent, flow, research]
---

# Live DeepSeek drive — observe NEXT failure modes (2026-06-28)

## TL;DR

Drove a fresh DeepSeek agent (`Bgl-2606281423`) on a real DATABASE-MEMORY +
PLANNING task: "learn 3 things about your runtime, design a schema to store
each as a fact WITH provenance, store them, then tell me which matters most."

**It SUCCEEDED at the task** — designed `:my.kb.discovery/*`, discovered and
reused the shared `:my.kb/source-path` + `:my.kb/confidence` provenance attrs,
transacted 3 real facts with provenance, queried them back, closed its
address-todo, and sent a clean final report. Live-proven by querying the store.

**But 42 of its 68 evals (62%) were noise/failures.** The mechanics work; the
agent is fighting the substrate. Three clusters, in priority order:

1. **CODE/EVAL — form segmentation produces orphan-delimiter + empty evals**
   (29/68 evals). The biggest, cheapest win.
2. **CONTEXT — `db/store-inventory` shape isn't self-describing** — the agent
   burned ~6 turns guessing the attr shape and getting all `nil`s.
3. **RENDERING — smart truncation hides structure the agent must navigate**
   (the owner-flagged bug). No mechanism exists to show a known-bounded value
   in full; `render-ai` hardcodes the truncated path.

## The drive (narrative)

- Boot evals (turns 0): `(message/user "Hi…")` then `(wait …)` — clean.
- Human task lands → wakes. Turn 1: correctly reads its id + home-ns from
  context, calls `(db/store-inventory)`, wraps the result in
  `{:agent-id … :home-ns … :inventory inv}`. OK.
- Turn 2–7: **the swamp.** It tries to enumerate the inventory's attrs, gets
  `[]` / all-`nil`, and thrashes — `(mapv :seon.db/kind kinds)`,
  `(mapv :seon.db/attr (:seon.db/attrs k))`, `(db/entity attr)` — none match
  the real shape. Interleaved with orphan `}` / `]` read-errors and a literal
  `(the full inventory)` prose-as-code eval. It eventually finds
  `(db/store-inventory {:seon.db/system? true})` and the "67 attrs / 13 kinds"
  fact.
- Turn 8+: **clean finish.** `(ns my.kb.discovery …)`, five `schema/register!`,
  one `db/transact!` of disc-1/2/3 with provenance, a read-back query, todo
  done, final `message/user` report. The PRODUCTIVE work was ~10 evals.

Stored facts (live-proven, `[:find (pull ?e [*]) :where [?e :my.kb.discovery/id]]`):
each carries `:my.kb.discovery/{fact,why-important,source,at}` **plus** the
shared `:my.kb/source-path` + `:my.kb/confidence :verified`. Real provenance,
not faked.

## NEXT failure modes (classified)

### CODE/EVAL — orphan-delimiter evals (11/68, all `ok? false`)

Stray closing delimiters become their own failing reads. In the transcript
this renders, between every good form, as:

```
; [unverified narration — not a real result]
}

;=> ✗ READ ERROR — this form did not parse, so it DEFINED NOTHING.
; Unmatched delimiter: } at line 1, col 1:
```

Source rows are literally `"}\n"`, `"]\n"`, `"}\n\n"`. The multi-form block the
model emits is being segmented such that trailing delimiters split off as
separate top-level "forms". This is the single largest source of red in the
agent's own memory.

### CODE/EVAL — empty-source evals (18/68, recorded as `ok? true`, `nil`)

`:seon.eval/source ""` rows, recorded as no-op evals (`record-eval!` coerces
nil→"" at eval.cljs ~2175 so they persist). They render as blank `;=> nil`
rows and as transcript-boundary echoes (`└─ end transcript ─`). Pure clutter —
they should never have become eval rows.

### CODE/EVAL — prose-as-code (part of the 13 "other-fail")

The model puts English INSIDE the code fence and it isn't stripped:
- `(the full inventory)` — a parenthesized English phrase eval'd as a call.
- `` `:db/ident approach returned nil, so I need to see the actual keys on the entity.`(let [inv …] …) `` — prose glued directly in front of a real form, so
  the whole segment fails to read.

The "⚠ Read as a note, not code" guard fires for bare maps but still records a
failed eval row.

### CONTEXT — `db/store-inventory` shape is not self-describing

The agent reached for `(db/store-inventory)` (natural), got `:seon.db/kinds`
where each kind is `{:seon.db/kind <kw> :seon.db/attrs {<attr> <count>}}` — i.e.
`:seon.db/attrs` is a **map attr→count**, not a list of `{:seon.db/attr …}`.
The agent guessed the list shape every which way and got all `nil`s for ~6
turns. Two compounding traps:
- Default `(db/store-inventory)` returns `:kinds` with all-`system` kinds
  filtered such that the agent-relevant rows look empty; only
  `{:seon.db/system? true}` surfaced the seon.* kinds. A fresh agent doesn't
  know to pass that.
- The shape is nowhere in context (the inventory block is 133 tokens of counts
  only), so the agent had to reverse-engineer it by trial.

### RENDERING — smart truncation hides navigable structure (OWNER-FLAGGED BUG)

Turn 1 stored its inventory as `{:agent-id … :inventory {:seon.db/kinds
[{…2 keys} {…2 keys} …]}}`. The result-edn the agent re-reads next turn is the
**truncated skeleton** — `{…2 keys}`, and elsewhere `"…"⟨24 tokens⟩`. Turn 2 the
agent treated its result handle as the inventory directly (it could not SEE the
`:inventory` nesting because it was collapsed) → `(:seon.db/kinds inv)` = nil →
more thrash.

The owner's diagnosis is right: for **known, bounded** function returns
(`db/store-inventory`, `db/transact!`, `schema/register!`) we should show the
value in full, not the compact skeleton.

**Research result — the "disable smart truncation" mechanism does NOT exist
for eval results today.** What I found:

- `seon.render.value/render-ai` (value.cljs:405) hardcodes `(sample value)`
  with NO opts — it ALWAYS uses `default-opts` (max-depth 3, max-keys/items 8,
  max-string 80). `seon.eval/render-result-edn` (eval.cljs:2149) delegates to
  it with no override. So every eval result is unconditionally bounded.
- `sample` ALREADY has a 2-arity that merges caller opts
  (value.cljs:261 `([x opts] (sample* x (merge default-opts opts) 0))`) — but
  `render-ai` never threads anything in. The opts pathway is built and unused.
- The only existing knobs are the GLOBAL env vars `SEON_VALUE_MAX_DEPTH /
  _MAX_KEYS / _MAX_ITEMS / _MAX_STRING / _WIDTH / _SHAPE_SAMPLE`
  (value.cljs:73-78) — coarse, not per-value.
- The "verbatim doctrine" (render.cljs:70-80) only governs context-BLOCK
  `:seon.render/ai` literal strings, not transcript eval results.

So there is no per-function / per-value "show in full" switch. It needs to be
built.

## Code smells

- **Whitespace/delimiter-only segments become eval rows at all.** The segmenter
  emits `""`, `}\n`, `]\n` as forms; `record-eval!` faithfully stores them.
  Root of the orphan-delim + empty-src clusters (29/68). Drop them before they
  become evals.
- **`render-ai` ignores its own opts pathway** (value.cljs:405 vs 261). The
  truncation is unconditional even for tiny, trusted returns.
- **Provenance duplication.** The agent stored BOTH `:my.kb.discovery/source` +
  `:my.kb.discovery/at` AND the shared `:my.kb/source-path` + `:my.kb/confidence`.
  Harmless, but signals the canonical provenance attr-set isn't presented as
  THE one to use, so the agent invents a parallel pair.
- **Double execution.** The ns + 5 registers + transact ran TWICE (tx ...298
  and ...311, each +27 datoms; identity upsert deduped so no data harm). The
  agent re-emitted the whole program — plausibly because the first success was
  buried in orphan-delimiter red and it didn't trust it.

## Proposed fixes (flagged by lane)

- **[Core] Drop whitespace/delimiter-only segments before recording an eval.**
  Hook: the form segmenter (seon.client ~l.1072 parse / seon.eval record path).
  A segment whose trimmed source is `""` or matches `^[)\]}]+$` is not a form —
  never record it. Kills 29/68 noise evals. **Biggest, cheapest win.**
- **[Core] Strip leading prose / refuse non-`(`-leading segments earlier.** The
  "⚠ Read as a note" guard exists but still records a failed eval; move the
  refusal ahead of `record-eval!` so prose-as-code doesn't land as red.
- **[UI/Render — mine] Add a "render in full" path for bounded values.**
  Simplest match to owner intent: a SIZE GATE in `render-ai` (value.cljs:405) —
  if `(count (pr-str value))` ≤ a cap (a few hundred chars), emit verbatim and
  skip `sample` entirely ("known functions won't be too long" = small enough to
  print). Avoids a per-fn registry. The opts pathway at value.cljs:261 is
  already there to thread through if we later want a per-call override from
  `render-result-edn`.
- **[Core] Make `db/store-inventory` self-describing in context**, or default it
  to the agent-relevant kinds (drop the implicit `system?` exclusion that makes
  a fresh agent's call look empty). The agent should not need
  `{:seon.db/system? true}` to see the seon.* kinds, and the `:seon.db/attrs`
  map-shape should be visible where the agent reaches for it.

## Live proofs

- Stored facts: `(seon.db/query '[:find (pull ?e [*]) :where [?e
  :my.kb.discovery/id]])` → 3 rows, each with `:my.kb/source-path` +
  `:my.kb/confidence :verified`.
- Eval tally: 68 total → `{:ok-real 26, :empty-src 18, :other-fail 13,
  :orphan-delim 11}`.
- Context: `(seon.agent.inspect/ctx-preview {:seon.agent/id "Bgl-2606281423"})`
  → 21597 token-estimate; sections `[:system 3114] [:namespaces 8098]
  [:live-tile 1009] [:warnings 1299] [:inventory 133] [:transcript 7839]`.
- Final report message stored + `ok? true` (`:seon.agent.message/id
  "CjF-2606281425"`).
- Agent now `:idle` after a clean finish.
