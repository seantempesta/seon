---
type: research
status: active
tags: [research, agent]
---

# Value-bounding map — verb output → agent-visible text (2026-07-06)

TL;DR: output shrinks at four stations. Layers 1–2 (render sampler +
transcript decay) are display-only projections with honest elision and
recovery handles — correct, do not add to them. Layer 3 (verb caps) had
exactly ONE destructive site (shell out/err) — fixed by the A6 item-6
ruling (blob-backed previews). Design invariant going forward: **no
destructive clipping at verb boundaries; every clipped output names its
recovery handle.** Display economy lives in the render layer only.

## Layer 1 — render (the one guarded walker)

- Sampler `src/seon/render/value.cljs` — depth/breadth-bounded skeleton,
  real keys preserved (get-in paths stay valid), honest markers
  (`{…12 keys}`, `… +N more`, `⟨N tokens⟩`).
- Caps from `seon.config` (env `SEON_RENDER_*` / manifest
  `:seon.config/render`): value-max-depth 3, value-max-keys 8,
  value-max-items 8, value-max-string 80, value-verbatim-cap 1500
  (≤ cap → whole value verbatim), value-width 72.
- Opaque projections for DB/Datom/Entity/records/JS/fns.
- SCI bounding (`render/sci.cljs`) bounds agent-authored render fns by
  TIME (250ms default), not size.
- Global caps: store-edn-cap 16384 chars, eval-cap 1500,
  message-cap 4000, render-fn-token-cap 2000 (`config.cljs:541-653`).

## Layer 2 — transcript aging (`agent/ctx/transcript.cljs`)

- Result decay schedule: offset 0 → 16384 tokens, 2 → 1500, 5 → 200
  (stub keeps the `result/<id>` handle).
- Tier eviction: last `turns-retained` turns verbatim; older evals kept
  within per-band token budgets (newest-first); messages never evicted.
- Byte-stability law: aged clips render byte-identical forever (prompt
  cache); all times from stored `:at`, live `now` fails loud.
- Storage tiers: live value → `globalThis.result.<id>` (uncapped,
  process-scoped, session-capped count); datom `:seon.eval/result-edn` =
  rendered skeleton capped 16384 chars; big content → `my.blob`.

## Layer 3 — verb-boundary behavior (as found; shell fixed by A6.6)

| Verb | Cap | Recoverable? |
|---|---|---|
| shell out/err | 2048 tok/stream (hard 16384) | WAS destroyed → A6.6: full stream to blob, envelope `::out-blob`/`::err-blob` |
| grep | 12 file rows (docstring wrongly said 20 → fixed in A6) | yes (re-run; honest totals) |
| fs read-file/view/list | line windows / 100 / 5000 | yes (paged, on disk) |
| my.blob/text | 100 lines | yes (content-addressed) |
| web-fetch | 2000-tok preview, 2MB max | yes (full markdown → blob) — THE pattern shell now copies |

## Layer 4 — the seam (docs)

- `docs/seon/architecture/context.md:160-200` — the cache gradient;
  every dial is manifest data, no env side doors.
- `src/seon/render/CLAUDE.md` — one walker, renders never stored, sizes
  in tokens.
- `value.cljs:17-59` — why bounding is structural (char-clipped pr-str =
  invalid EDN + lost navigation).
- Escape-clipping (#43): `:seon.agent.ctx/escape-clipping?` frees small
  caps but never the age-decayed citable result body.

## Double-clip inventory (benign — display over recoverable backing)

Eval values pass up to four cuts (sampler → write backstop → store cap →
age decay); shell/web previews re-sampled at render. Only the old shell
verb cut destroyed data; everything else projects.
