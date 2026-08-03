---
type: research
status: active
tags: [research, ai, config, data-model]
---

# Model registry as facts — tailored descriptor rows, query resolution

## Decision (owner-designed iteratively, 2026-08-03 night)

Models become first-class database entities: one row per hosted model,
child of its provider descriptor row by ref, carrying a FULL DESCRIPTOR
TAILORED TO THAT MODEL. Rows are open attribute sets — each model declares
only the facts true of IT, and attribute presence IS the capability claim
(no universal template, no stored nil, no `[:maybe]`). Shared attributes
are reused across rows wherever the meaning is genuinely shared; a
model-specific pricing structure gets its own declared attributes rather
than a force-fit into a common column.

Owner answers that fix the design:

1. **Scope**: full descriptor per model, tailored ("different configs
   likely have different attributes and we need to tailor each one as
   necessary; shared attributes are great of course").
2. **Consumers**: config resolution keeps picking the effective model
   (cluster + per-agent overlay, unchanged), AND the registry is queryable
   by agents and renderers — "what models exist, what do they cost" — with
   NO automatic routing in this slice.
3. **Config contract**: `:seon.config.ai/model` KEEPS its string meaning;
   the registry row is found by unique model-id attribute at resolution
   time. A configured id with no registry row is a derived
   `seon.problems` finding, never a breakage.
4. **Truth upkeep**: declared, best-effort — external facts (pricing,
   context windows, limits) are declared values fixed on discovery. No
   runtime fetching, no provenance ceremony required (though a source URL
   in the declaration comment costs nothing and helps the next reader).
5. **Last-observation facts are `:db/noHistory`** (owner, same night):
   values we want to SHOW somewhere but never need long-term — tokens per
   second on the last use, last latency, last error marker — live on the
   model row as `:db/noHistory` attributes updated at attempt settlement.
   Current value queryable and renderable; no history accumulation. Durable
   per-attempt truth already lives on attempt rows and is NOT duplicated
   here — these are display projections cheap enough to keep current.

## Row shape sketches

Schemas land in `resources/seon/schemas/seon.ai.model.edn` (flat split
layout). Open maps throughout; sketches show the tailored variance:

```clojure
;; shared, declared once
:seon.ai.model/id [:string {:min 1}]              ; unique identity attr
:seon.ai.model/provider :seon.db/ref              ; → provider descriptor row
:seon.ai.model/context-window-tokens [:int {:min 1}]
:seon.ai.model/max-output-tokens [:int {:min 1}]
:seon.ai.model/input-usd-per-mtok :double
:seon.ai.model/output-usd-per-mtok :double
:seon.ai.model/cached-input-usd-per-mtok :double
:seon.ai.model/input-modalities [:set :keyword]   ; presence claims support

;; tailored: DeepSeek rows only — off-peak discount windows
:seon.ai.model.deepseek/off-peak-windows …        ; the UTC windows + factor

;; tailored: thinking-capable rows only
:seon.ai.model/thinking-dial [:enum :disabled :low :medium :high]
:seon.ai.model/reasoning-output-usd-per-mtok :double

;; tailored: Muse row only — search grounding billing
:seon.ai.model.meta/search-usd-per-kquery :double

;; last-observation display facts — ALL :db/noHistory
:seon.ai.model/last-tokens-per-second :double
:seon.ai.model/last-latency-ms :int
:seon.ai.model/last-used-at :inst
```

Exact attribute names and the off-peak window shape are the implementing
lane's call within these rules; registry-query-first before declaring
(reuse existing `:seon.config.ai/*` declarations where the meaning is
identical, and do NOT redeclare what the provider row already owns —
endpoint, protocol shape, credential env var stay provider facts).

## Resolution

`seon.ai`'s per-turn settings resolution (ruling #34's one database value
per turn) gains one step: after the effective model string resolves, pull
the registry row by `:seon.ai.model/id`. The row informs request
construction (context budget, thinking dial admissibility) and display; the
string remains the config contract. Missing row → derived problem finding +
the call proceeds exactly as today (the registry ACCRETES capability; its
absence must not regress a working call).

## Seed rows

- **DeepSeek** `deepseek-v4-flash`, `deepseek-v4-pro` — from the shipped
  provider descriptor + current published pricing, including cache-hit
  pricing and off-peak windows.
- **Kimi K3** — lands with the enablement lane (wire-key as declared
  descriptor fact, owner-approved); calibration stays sequenced AFTER the
  prefix-cache work banks.
- **Muse** `muse-spark-1.1` — protocol facts established from
  [dev.meta.ai docs](https://dev.meta.ai/docs/overview/) (fetched
  2026-08-03): base `https://api.meta.ai/v1`, OpenAI-compatible
  `/v1/chat/completions` AND Anthropic-compatible `/v1/messages`, Bearer
  `MODEL_API_KEY` (our env: `META_MODEL_API_KEY` — the config names the
  variable, so no rename needed), context "1,048,576 tokens", input
  text/image/video/audio/PDF, $1.25 input / $4.25 output / $0.15 cached
  per 1M, search grounding $2.50 per 1,000 queries, per-team rate tiers
  (free 60 rpm / 2M tpm; paid 3,000 rpm / 4M tpm), OpenAI-style error
  envelope. **A cheap empirical probe** (one small streamed call through
  the chat-completions shape) verifies streaming event format and exact
  usage field names before the row is trusted for economics — the docs do
  not establish those.

## Falsifiers

- "What models exist and what do they cost" is one query an agent can run;
  the renderer shows it (the registry page/block declares `:seon.render/ai`
  and `:seon.render/html` producers per the standing rule).
- A configured model id with no row yields the derived problem finding and
  an unchanged successful call.
- A DeepSeek attempt settling updates `last-tokens-per-second` on the row;
  `history` on that attribute returns nothing (`:db/noHistory` proven).
- The Muse probe's observed usage fields match the row's declared shape or
  the row is corrected before merge.
- Adding a new tailored attribute to one model row breaks zero existing
  queries and zero other rows (open-map accretion proven).

## What not to build

- no automatic model routing/selection policy in this slice;
- no runtime pricing fetch or provider-API registry sync;
- no duplication of provider wire facts onto model rows;
- no duplication of durable attempt facts as model-row history;
- no second registry, enum, or hand list of models in code — the rows ARE
  the registry, and "which models support X" is attribute presence.
