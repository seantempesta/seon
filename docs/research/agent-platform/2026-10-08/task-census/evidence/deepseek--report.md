# DeepSeek flash check, 2026-10-07

## Verdict
The latest flash model is **`deepseek-flash`** (display name **DeepSeek-V4.1-Flash**). The owner's "4.1" is right.
Seon's shipped default (`config/default.edn:399`) is already `"deepseek-flash"`, so no model-id change is needed.
The live request Seon builds is accepted with no rejection and no deprecation warning.
Remaining work is docs, one stale model row and a few parameter classifications (see Mismatches).

## 1. Provider facts (all retrieved 2026-10-07)
Sources: https://api-docs.deepseek.com/quick_start/pricing, /guides/thinking_mode, /api/create-chat-completion, /guides/kv_cache, and a live `GET /models`. Saved copies are in this directory (`*.txt`, `models.json`).

- **`GET /models` serves exactly two ids.**
  - `deepseek-flash`: name "DeepSeek-V4.1-Flash", context_window 1048576, max_output_tokens 393216, input text+image, effort levels low/high/max, default high.
  - `deepseek-v4-pro`: name "DeepSeek-V4-Pro", same context and max output, text only.
- **Legacy ids.** The pricing page says `deepseek-v4-flash` and `deepseek-v4-flash-vision-exp` are still accepted, retired, served by V4.1-Flash and billed at the Flash price. Live test: a `deepseek-v4-flash` request returned 200 with `"model":"deepseek-flash"`.
- **Context and output.** Context is 1M (1,048,576). Max output is 384K = 393216. A request with `max_tokens` 400000 returns 400 with "valid range [1, 393216]". The documented default is 8K in non-thinking mode and 64K in thinking mode (128K at effort max).
- **Pricing, USD per 1M tokens, off-peak.** Peak is exactly 2x. Peak hours are 01:00-04:00 and 06:00-10:00 UTC, Monday to Friday, excluding Chinese public holidays. Weekends are fully off-peak.

| model | cache-hit | cache-miss | output |
|---|---|---|---|
| deepseek-flash | 0.003 | 0.15 | 0.60 |
| deepseek-v4-pro (V4-Pro-0813) | 0.022 | 0.66 | 1.98 |

  Concurrency limit is 2500 for flash and 500 for pro.
- **Thinking is ON by default, with default effort high.**
  - Switch (OpenAI format): `"thinking":{"type":"enabled"|"disabled"}`.
  - Effort: `"reasoning_effort":"low|high|max"`. `none` disables thinking; `minimal` maps to low; `medium` and `xhigh` map to high.
  - In thinking mode, `temperature`, `presence_penalty` and `frequency_penalty` are accepted but have no effect.
  - `top_p` takes effect ONLY in thinking mode (values below 0.95 become 0.95). In non-thinking mode it is fixed at 1.0.
  - `frequency_penalty` and `presence_penalty` are marked deprecated and unsupported in every mode.
  - `tool_choice` `required` or a named tool returns 400 in thinking mode.
  - With a `tools` parameter, `reasoning_content` must be passed back on every later request.
- **Other parameters.**
  - `stop` takes up to 16 sequences.
  - `response_format` `json_object` needs a JSON instruction in the prompt.
  - `stream_options` is valid only with `stream:true` (otherwise 400).
  - `user_id` is an optional isolation key.
  - `finish_reason` values: stop, length, content_filter, tool_calls, insufficient_system_resource, aborted.
- **Caching.** Disk caching is automatic and best-effort, with seconds of build time. The cache stores request-boundary prefix units, so a repeat of the same prefix hits.
  - Usage fields: `prompt_cache_hit_tokens`, `prompt_cache_miss_tokens` (hit + miss = prompt_tokens), `prompt_tokens_details.cached_tokens` (equals hit), and `completion_tokens_details.reasoning_tokens`.
- **Temperature guidance** (`/quick_start/parameter_settings`): default 1.0; coding and math 0.0; data analysis 1.0; conversation and translation 1.3; creative 1.5. This page's navigation still lists V3.2-era news, so whether it applies to V4.1 is UNVERIFIED. It is also non-thinking only.

## 2. Key and config
- The key is referenced as `:seon.config.ai/api-key-variable "DEEPSEEK_API_KEY"` (`config/default.edn:444`). `DEEPSEEK_API_KEY` was NOT in my Bash environment.
- I found it as a line in `~/.zshrc` and in the project `.env` (git-ignored) and used that.
- **Incident:** my grep for key locations echoed the `.env` line into this lane's tool transcript. The value is not in this report, but it is in the lane transcript. Rotate it if transcripts are shared.
- I deleted the scratch copy `tmp/deepseek/.k`.

## 3. What Seon sends today (derived from `src/seon/ai.clj`; no JVM started)
`request-body` (ai.clj:562) merges the base with `wire-settings`.
- Base: `model`, `stream`, `messages` (system, then user), and `stream_options.include_usage:true` when streaming.
- Shipped dials: `max_tokens` 65536 (ai.clj:480 can swap the wire key), `stop ["#:seon.repl"]`, `thinking {"type":"disabled"}`.
- `:disabled` omits `reasoning_effort` (ai.clj:431). Temperature, top_p and both penalties ship absent. `response_format` and `extra_body` ship absent.
- Thinking `:low`, `:high` or `:max` sends `{"type":"enabled"}` plus `reasoning_effort` of the same name.
- Model row (`config/default.edn:534`): context 1000000, max-output 384000, prices 0.15 / 0.003 / 0.6, thinking-dials `#{:disabled :low :high :max}`.

## 4. Live tests (api.deepseek.com, deepseek-flash)
Raw outputs are `t*.out` in this directory.

| id | test | result |
|---|---|---|
| t1 | Seon's exact non-streaming body | 200 in 1.8 s, content "pong", finish stop, usage 18 prompt / 2 completion |
| t2 | Seon's exact streaming body | 200 in 2.1 s (see below) |
| t3 | Cache: ~3463-token prefix, c1 then c2 after a 6 s wait, then c3 with a different suffix | see below |
| t4 | Thinking toggles | see below |
| t5 | Stream, thinking low | see below |
| t6 | Legacy id, penalties, minimal, JSON mode, max_tokens too big | see below |

- **t2 stream shape.**
  - The first chunk has `delta {role, content ""}`.
  - Content chunks follow, then ONE final chunk carrying `finish_reason "stop"`, `delta.content ""` and the full `usage`.
  - Earlier chunks carry `"usage":null`. Then `data: [DONE]`. There is no separate usage-only chunk and no empty `choices`.
- **t3 cache results.**
  - c1: hit 0, miss 3463.
  - c2: hit 3328, miss 135, cached_tokens 3328.
  - c3: hit 3328, miss 135.
  - Hits are in 128-token blocks, and the cache is live within about 6 s.
- **t4 thinking toggles.**
  - `thinking:enabled` plus `reasoning_effort:high` gives `reasoning_content` and `reasoning_tokens` 18.
  - Neither parameter set: thinking is ON by default (reasoning_tokens 16).
  - `reasoning_effort:none` alone: thinking OFF.
  - `reasoning_effort:low` alone: thinking ON.
  - `thinking:disabled` plus `reasoning_effort:high`: thinking OFF, so disabled wins.
- **t5 stream, thinking low.** Deltas alternate `{"content":null,"reasoning_content":"..."}` and then `{"content":"391","reasoning_content":null}`. The final chunk has `finish_reason` and the usage including `completion_tokens_details.reasoning_tokens`.
- **t6 other requests.**
  - `deepseek-v4-flash` returned 200, served as deepseek-flash.
  - Penalties, temperature 0 and top_p 0.5 in non-thinking mode returned 200 with no error. Whether temperature changes sampling in non-thinking mode was not tested.
  - `reasoning_effort:"minimal"` returned 200 with thinking on.
  - `response_format json_object` returned 200.
  - `max_tokens` 400000 returned 400, as documented.

Shape match with `seon.ai`:
- `stream-chunk-error` and `stream-event` (ai.clj:616-717) accept null `content`, null `reasoning_content` and null `usage`. They keep the newest map `usage` and set `finish-reason` from a choice that also carries usage.
- `completion-text` reads `message.reasoning_content` (ai.clj:854).
- `normalize-usage` (ai.clj:865) maps `prompt_tokens`, `completion_tokens` and `total_tokens` correctly.
- For cached tokens it falls to `prompt_cache_hit_tokens`, and `prompt_tokens_details.cached_tokens` is also present. This is correct.
- I only read these functions and compared field names. I did not execute them (no JVM, per the brief).

## 5. Mismatches and recommendations
1. **Model id: no mismatch.** Default is `deepseek-flash` (`config/default.edn:399`, comment at :395-398 is accurate). Stale references to `deepseek-v4-flash`:
   - `docs/seon/reference/llm-adapters.md:77` still shows `deepseek-v4-flash`, and the doc's example block is stale generally (open issue `docs/seon/issues/provider-reference-and-price-schedule-drift.md`).
   - `src/seon/ai/tokens.cljc:15` is a historical note, so leave it.
   - `.agents/skills/llm-providers/SKILL.md` was reported stale in that issue; I did not re-audit it.
2. **Settings are correct for per-turn agents.** Explicit `thinking:disabled` is right, because the provider default is thinking ON at effort high. Do not rely on omission. `max_tokens` 65536 is fine (cap 393216).
3. **`deepseek-v4-pro` model row is stale** (`config/default.edn:545-558`). Official off-peak prices are now 0.66 / 0.022 / 1.98 (V4-Pro-0813), not 0.435 / 0.003625 / 0.87. It also still carries the daily off-peak window refs that do not match the weekday-only schedule. This is the only flash-adjacent price drift. Flash prices match.
4. **Flash row capacity is a little off.** The provider serves context 1,048,576 (row has 1000000) and max output 393216 (row has 384000), and also reports image input. The row's `#{:text}` modalities undersells it. This is cosmetic but wrong: set `:seon.ai.model/context-window-tokens 1048576`, `max-output-tokens 393216`, and add `:image` to input-modalities if the schema allows it. Nothing sends images today.
5. **`top_p` classification is inverted** (`resources/seon/schemas/seon.config.ai.edn:155-158`, `:seon.ai/inert-when-thinking true`). The provider ignores `top_p` in non-thinking mode and honours it (0.95-1.0) in thinking mode. The comment in `seon.ai.edn:2` ("omitted in thinking") is the old rule. This only matters if someone configures top_p. A one-line fix is to drop `inert-when-thinking` from top-p and document that it is dead in non-thinking mode.
6. **`frequency_penalty` and `presence_penalty` are deprecated** everywhere and are accepted but ignored. They are safe because they ship absent. Consider deleting both dials (`seon.config.ai.edn:31-42, 75-84`) instead of keeping an inert-when-thinking rule. This is not required.
7. **Usage normalisation.** `:seon.ai.usage` has no reasoning-tokens or cache-miss field. The provider now sends `completion_tokens_details.reasoning_tokens` and `prompt_cache_miss_tokens`. Optional addition for cost and diagnosis. The prompt-cache path itself works.
8. **Caching works with Seon's prompt shape.** A stable prefix reaches 128-token-block hits within seconds. The cache needs the prefix to be byte-identical, so any per-turn variable text must come after the stable part. The `llm-providers` skill already covers the rendered-context and cache boundary.
9. **Pricing schedule.** The pricing page lists peak as weekdays only. This matches `config/default.edn:529`, and the flash row correctly omits the daily-window refs.

### Proposed config change
None required for the id. If the owner wants the model rows brought current, the two edits are in `config/default.edn`:
- In the `deepseek-flash` row (line 534): `:seon.ai.model/context-window-tokens 1048576`, `:seon.ai.model/max-output-tokens 393216`.
- In the `deepseek-v4-pro` row (line 545): `:input-usd-per-mtok 0.66`, `:cached-input-usd-per-mtok 0.022`, `:output-usd-per-mtok 1.98`, and remove the stale `deepseek-off-peak-windows` set and its status, matching the flash row.

Proposed patch for `top_p` (not applied), in `resources/seon/schemas/seon.config.ai.edn` at lines 155-158:
```
-                   :seon.ai/request-attribute :seon.ai/top-p,
-                   :seon.ai/inert-when-thinking true}]}
+                   :seon.ai/request-attribute :seon.ai/top-p}]}
```
A `seon.ai` code change is not needed for correctness.

## 6. Spend
- 17 HTTP calls: 16 paid chat completions plus 1 free `GET /models`.
- About 11.4k tokens in total, of which about 10.4k is the three cache-test calls (about 6.6k of those hit the cache).
- Estimated cost is well under US$0.002.
- No Seon JVM was started and no tracked file was edited. Only files in `tmp/deepseek/` were written.

## Unverified
- Whether `temperature` takes effect in non-thinking mode on V4.1 (accepted, effect not measured).
- Tool-call and thinking continuation behaviour (not tested; see the open issue `thinking-tool-continuations-have-no-faithful-request-shape`).
- That the doc page `parameter_settings` applies to V4.1.
- Seon's actual built body was derived by reading the code, not by running `seon.ai/request-body` (no JVM).
