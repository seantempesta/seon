---
type: research
status: completed
tags: [research]
---

# Prompt caching across Claude, Gemini, and DeepSeek

Authoritative mechanics for Seon's section-composer architecture. Written 2026-05-25.

## TL;DR — the one-sentence verdicts

- **Claude (Anthropic):** strict cumulative-prefix hashing with up to 4 user-placed `cache_control` breakpoints and a 20-block lookback; later breakpoints cache the **full prefix up to that point**, never a free-floating middle segment.
- **Gemini (Google):** both explicit `CachedContent` and implicit caching are **prefix-only** — `CachedContent` is documented as "a prefix to the prompt," and implicit caching matches by shared leading prefix.
- **DeepSeek:** fully automatic, strict **prefix unit** match on disk; users have no knobs other than "structure your prompt with stable stuff first."

**Content-hash hypothesis (the user's hypothesis: can we box stable context into separate messages so only dynamic ones re-evaluate?):**

- **Claude:** No. Cache is a hash of the cumulative prefix ending at each breakpoint, not a per-message content hash. Putting stable content into a later assistant/user message does not let you cache it independently of everything that came before it.
- **Gemini:** No. Both explicit and implicit caching are prefix-only. A "stable" message sitting after a volatile one is uncacheable.
- **DeepSeek:** No. Prefix-unit matching at request/token boundaries; nothing message-level.

**One practical finding for Seon:** every provider is strict-prefix. The only architecturally meaningful lever is **ordering**: put everything that is stable across turns (substrate intro, tool catalog, schema docs) first, put everything that changes (live DB-derived state, recent eval log, current user turn) last, and accept that the moment you mutate any byte in the "stable" zone you pay the full re-cache cost. There is no provider where reactive interleaving (stable / volatile / stable / volatile) is cache-friendly. Section composition must be **append-only at the volatile tail**, not a re-shuffle.

---

## Claude (Anthropic)

Primary source: [Anthropic prompt caching docs](https://platform.claude.com/docs/en/docs/build-with-claude/prompt-caching).

### Q1 — Cache matching mechanism

Cumulative-prefix hashing with a 20-block lookback. Direct quotes:

> "Cache writes happen only at your breakpoint. Marking a block with `cache_control` writes exactly one cache entry: a hash of the prefix ending at that block. The system does not write entries for any earlier position. Because the hash is cumulative, covering everything up to and including the breakpoint, changing any block at or before the breakpoint produces a different hash on the next request."

> "On each request the system computes the prefix hash at your breakpoint and checks for a matching cache entry. If none exists, it walks backward one block at a time, checking whether the prefix hash at each earlier position matches something already in the cache. ... The lookback window is 20 blocks."

Invalidation hierarchy:

> "The cache follows the hierarchy: `tools` → `system` → `messages`. Changes at each level invalidate that level and all subsequent levels."

### Q2 — Cache control granularity

- **Max breakpoints: 4.** Placeable on tool definitions (in `tools`), content blocks in `system`, and content blocks in `messages.content` (user OR assistant turns, including `tool_use` and `tool_result` blocks).
- Later breakpoints cache the **full cumulative prefix** up to that point, not the segment between the prior breakpoint and this one:

> "Prompt caching references the entire prompt - `tools`, `system`, and `messages` (in that order) up to and including the block designated with `cache_control`."

- TTLs: `ephemeral` 5-minute (default) and 1-hour (`"ttl": "1h"`, currently beta). Reads refresh the TTL.

### Q3 — Pricing

Multipliers vs base input price:

- 5-minute cache **writes**: 1.25x base input
- 1-hour cache **writes**: 2x base input
- Cache **reads**: 0.1x base input

Concrete (per 1M tokens):

| Model | Input | 5m write | 1h write | Read | Output |
|---|---|---|---|---|---|
| Opus 4.7 | $5 | $6.25 | $10 | $0.50 | $25 |
| Sonnet 4.6 | $3 | $3.75 | $6 | $0.30 | $15 |
| Haiku 4.5 | $1 | $1.25 | $2 | $0.10 | $5 |

> "Adding more `cache_control` breakpoints doesn't increase your costs - you still pay the same amount based on what content is actually cached and read."

### Q4 — Observability

```json
"usage": {
  "input_tokens": 2048,
  "cache_read_input_tokens": 1800,
  "cache_creation_input_tokens": 248,
  "output_tokens": 503,
  "cache_creation": {
    "ephemeral_5m_input_tokens": 148,
    "ephemeral_1h_input_tokens": 100
  }
}

```

Critical subtlety: `input_tokens` is **only the tokens after the last cache breakpoint**, not total uncached. `total = cache_read + cache_creation + input_tokens`.

If both `cache_read` and `cache_creation` are 0, you didn't meet the minimum (4096 tokens for Opus and Haiku 4.5; 1024 for Sonnet 4.6).

### Q5 — Non-prefix caching?

No. From the docs themselves:

> "The system only supports prefix caching. Cache breakpoints must appear in order and create cumulative prefixes—there is no support for caching arbitrary segments or non-contiguous blocks."

The 4 breakpoints let you have **multiple stable shelves of varying volatility** along the prefix (e.g. one breakpoint after `tools`, one after `system`, one after the first N stable messages, one right before the current user turn) — so if your messages tail invalidates, you still get reads up to the earlier breakpoint. They do **not** let you cache an island in the middle while everything before it changes.

### Q6 — Multi-turn / tool-use gotchas

- `tool_use` and `tool_result` blocks are cacheable like any other content block.
- Changing **`tool_choice`** invalidates everything from that point on.
- Adding or removing **images anywhere** invalidates the cache.
- Adding **one new MCP tool** = `tools` array changes = cache invalidated for tools/system/messages.
- Dynamic content in the system prompt (timestamps, session IDs, "Current date: ...") is the classic foot-gun — it changes every turn and silently destroys the cache. [per community write-up](https://www.mager.co/blog/2026-04-29-claude-prompt-caching/)
- Thinking blocks can't be marked with `cache_control` directly but are cached as part of subsequent requests' content when you pass them back with tool results.

See also the dedicated [tool use + prompt caching docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-use-with-prompt-caching).

---

## Gemini (Google)

Primary sources: [Gemini context caching docs](https://ai.google.dev/gemini-api/docs/caching), [implicit caching announcement](https://developers.googleblog.com/gemini-2-5-models-now-support-implicit-caching/).

### Q1 — Cache matching mechanism

Two systems, both prefix-based:

- **Explicit `CachedContent`:** you create a server-side cached object with content + TTL, then reference its ID in a request. The cached object is always treated as a prefix.
- **Implicit caching:** "if the request shares a common prefix as one of previous requests, then it's eligible for a cache hit." Google does not publish the internal algorithm.

Google's recommendation makes the prefix nature explicit:

> "you should keep the content at the beginning of the request the same and add things like a user's question or other additional context that might change from request to request at the end of the prompt."

### Q2 — Granularity / control

- **Explicit CachedContent:** you pick the TTL (default 1 hour, updateable) and can update or delete the cache. The cached content always sits at position 0:

  > "Cached content is a prefix to the prompt."

- **Implicit:** zero control. Enabled by default on all 2.5+ models.

Minimums (input tokens before caching is eligible at all):

| Model | Min tokens |
|---|---|
| Gemini 3.5 Flash | 1024 |
| Gemini 3 Pro Preview | 4096 |
| Gemini 2.5 Flash | 1024 |
| Gemini 2.5 Pro | 4096 (implicit was lowered to 2048 per the implicit-caching announcement) |

### Q3 — Pricing

- Cached-token discount is reported as **75% off** on Gemini 2.0 models and **up to 90% off** on Gemini 2.5+ models, applied automatically on cache hit.
- Explicit caching adds **storage cost per token-hour** that you keep the cache alive (varies by model — see [Gemini API pricing](https://ai.google.dev/pricing)).
- Implicit caching has **no storage cost** — Google just refunds the discount on hits.

### Q4 — Observability

Response `usage_metadata` includes `cached_content_token_count` (also surfaced as `cache_read_input_tokens` in newer SDK shapes). No `cache_creation_input_tokens` equivalent for implicit caching because there is no user-attributable write step.

### Q5 — Non-prefix caching?

No. `CachedContent` is explicitly a prefix; you cannot inject `CachedContent` mid-prompt with arbitrary content before it. Implicit caching matches on common leading prefix.

### Q6 — Multi-turn / tool-use

The docs do not deeply specify caching behavior across tool turns. Empirically, since the cache is prefix-keyed by serialized message bytes, the same rules apply: any change to tool declarations or earlier turn content invalidates everything after.

---

## DeepSeek

Primary source: [DeepSeek context caching guide](https://api-docs.deepseek.com/guides/kv_cache).

### Q1 — Cache matching mechanism

Strict prefix-unit match on disk, fully automatic:

> "The DeepSeek API Context Caching on Disk Technology is enabled by default for all users, allowing them to benefit without needing to modify their code."

A request "can only hit the cache if it **fully matches** a **cache prefix unit**." Cache units are created at request boundaries (end of user / model output), at detected common prefixes, and "at fixed token intervals" for long inputs to keep long-prefix cases cacheable.

### Q2 — Granularity / control

None. You cannot create, name, pin, TTL, or invalidate caches. The system makes the decisions.

### Q3 — Pricing

As of 2026 (per [DeepSeek pricing docs](https://api-docs.deepseek.com/quick_start/pricing) and TokenMix's [2026 V4 cache pricing breakdown](https://tokenmix.ai/blog/deepseek-cache-hit-pricing)):

- V4 Flash: cache hit ~$0.0028/M input, cache miss ~$0.14/M, output $0.28/M.
- V4 Pro (discounted through 2026-05-31): cache hit ~$0.003625/M, cache miss ~$0.435/M, output $0.87/M.
- Cache-hit price was cut to 1/10 of launch price on 2026-04-26.

Effective hit-vs-miss savings: ~98% on input cost. No separate "cache write" charge — misses are just normal input pricing.

### Q4 — Observability

`usage.prompt_cache_hit_tokens` and `usage.prompt_cache_miss_tokens`.

### Q5 — Non-prefix caching?

No. Prefix units only. No user control over where boundaries fall (other than that boundaries are placed at request/turn ends, which gives you implicit control: identical prior turns → cacheable).

### Q6 — Multi-turn

Because cache units form at end-of-turn boundaries, a stable multi-turn agent loop where each new turn only **appends** to history is the ideal case — every prior turn becomes a cache prefix unit. The moment you mutate or summarize earlier turns, you blow up the suffix.

TTL: "usually within a few hours to a few days" — best-effort. Don't depend on a specific lifetime.

---

## Cross-provider verdict: the content-hash hypothesis

> *"Is every message potentially matched to a previous one like a content hash? Could we just box up all generated context into separate 'messages' from the assistant and only the dynamic ones would be re-evaluated?"*

**No provider supports this. All three are strict-prefix.**

The implication for Seon's section composer: imagine the substrate prompt is `[A: substrate intro][B: live DB state][C: tool catalog][D: recent eval log][E: user turn]`. If `B` changes from turn to turn, **every byte after `B` is uncacheable** on every provider, even though `C` and `D` may be identical to last turn. Boxing them into separate `messages` does not help — the cache key is the cumulative prefix bytes, not a per-message hash.

The only thing Anthropic gives you with multiple breakpoints is the ability to **stop the bleeding earlier**: if you put a breakpoint after `A` and another after `A+B+C+D`, a `B` change still re-caches everything, but a `D`-only change keeps the earliest breakpoint intact (so future requests with the same `A` are read from cache for free). It does not let you ever cache `C` independently when `B` changed.

Practical consequence: **reorder, don't interleave**. The right shape is `[stable, stable, stable, …][volatile, volatile, volatile][user turn]`. Every reactive section that mutates between turns must live in the volatile tail.

---

## Community workarounds (Q7)

### "Don't put dynamic content early" — the universal advice

From [Mager's "How Claude prompt caching actually works"](https://www.mager.co/blog/2026-04-29-claude-prompt-caching/):

> "Dynamic content in system prompts (timestamps, message IDs, session metadata, 'Current Date & Time' section) changes every turn, invalidating Anthropic's cache which requires exact byte-for-byte matches."

The fix is well-known but worth quoting: move "Current date: 2026-05-25" out of the system prompt and into the last user message (or a final volatile turn). Same advice applies to Gemini and DeepSeek.

### "Cache the tools, freeze the order"

From [Anthropic's tool-use caching docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-use-with-prompt-caching):

> "Place `cache_control: {'type': 'ephemeral'}` on the last tool in your tools array."

And from the prompt-caching docs themselves:

> "Tool definitions are part of the cached prefix — they sit between the system prompt and the conversation messages. Adding one MCP tool changes the prefix, which invalidates the cache for the entire conversation history."

Implication for Seon's "agent-discoverable tools": the tool catalog must be **stable across an entire session**, or you give up the cache. Adding tools mid-session = cold cache.

### "Don't Break the Cache" (academic)

[The arXiv paper "Don't Break the Cache: An Evaluation of Prompt Caching for Long-Horizon Agentic Tasks" (2026)](https://arxiv.org/pdf/2601.06007) studies long agent loops and finds the dominant cost driver is naïve full-context re-caching every turn. Their recommendation aligns: deduplicate retrieved context across turns, hold tool definitions stable, and place dynamic retrieved context strictly at the message tail.

### "Cumulative tool-result appending is the canonical agent pattern"

The general agent-loop pattern that survives caching: never re-order or summarize past turns. Append each `tool_use` / `tool_result` pair. The cache hit grows over time because each prior turn becomes part of a stable prefix unit.

### Bug-class to know about

[This OpenClaw bug (#19534)](https://github.com/openclaw/openclaw/issues/19534) is a typical failure mode: a wrapper library was mutating message metadata between requests, making cache reads always 0. The pattern — "I set `cache_control` but `cache_read_input_tokens` is always 0" — almost always means something upstream is mutating bytes you didn't expect. The byte-exactness of the prefix is unforgiving.

---

## Recommendations for Seon's section-composer architecture

Given that all three providers are strict-prefix, here is the section model that survives reality.

### Architecture: two-zone prompt with append-only volatile tail

Structure every prompt as:

```
[STABLE ZONE — frozen for the session]
  1. Substrate intro (immutable)
  2. Schema docs (immutable)
  3. Tool catalog (immutable)
  4. Session-stable derived context (e.g. "you are agent <id>, working on <project>")

[VOLATILE ZONE — recomputed each turn, no caching expected]
  5. Reactive DB-derived sections (live state, warnings)
  6. Recent eval log (appended turn-by-turn)
  7. Current user turn

```

Place an Anthropic `cache_control` breakpoint at the end of the stable zone (= maximum cacheable prefix). Optionally one earlier breakpoint after tools to survive 20-block-lookback drift.

### Section classification is a static property, not a runtime decision

Sections register themselves at definition time as `:seon.section/zone :stable` or `:zone :volatile`. The composer concatenates `(stable-sections db) ++ (volatile-sections db) ++ user-turn`. If a section is mis-classified (says it's stable but actually mutates each turn), that's a bug the cache observability will surface — `cache_read_input_tokens` will be lower than expected.

### Stable zone must be a pure function of session identity, not DB state

If a stable section reads from the DB, the part it reads must itself be session-pinned (loaded once at session start, snapshotted). Don't query "current open issues" in the stable zone — that's volatile. Stable zone queries should resolve to things like "the agent's role" and "the registered tool catalog."

### Detect cache invalidation as a first-class signal

Every turn, compare expected vs actual `cache_read_input_tokens`. If the stable zone is N tokens and `cache_read < N - epsilon`, something in the stable zone moved. Log a structured event so we can identify the offending section. This is in the spirit of the "reactive context — derived by default" principle (CLAUDE.md): the system tells you when its invariants are broken.

### Provider-specific knobs Seon should support

- **Anthropic:** emit up to 4 `cache_control` breakpoints. The composer should know how to place them at zone boundaries.
- **Gemini:** use **explicit `CachedContent`** for the stable zone — create once per session, reference by ID. This is the strongest cache guarantee Gemini offers and avoids relying on implicit-caching heuristics.
- **DeepSeek:** no API surface needed; just don't mutate the prefix.

### What we explicitly should NOT build

- Per-message content-hash dedup ("if this section was sent before, skip it"). Doesn't help — providers don't cache that way.
- Reactive interleaving of stable and volatile sections in arbitrary order. Kills caching everywhere.
- "Latest evals" sections that delete or summarize older entries between turns. The cache wants append-only history.
- Dynamic ordering of tool definitions ("show only relevant tools for this turn"). Every reorder is a cold cache.

### Open question for follow-up

We should verify Gemini explicit `CachedContent`'s behavior when you reference a cached prefix AND include additional contents in the request — is the additional content always treated as appended (suffix), or can it precede? The docs say "Cached content is a prefix to the prompt," which strongly implies suffix-only, but a spike with `google-genai` to confirm before architecting around it is cheap and worth doing.

---

## Sources

- [Anthropic prompt caching docs](https://platform.claude.com/docs/en/docs/build-with-claude/prompt-caching)
- [Anthropic tool use + prompt caching](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-use-with-prompt-caching)
- [Gemini context caching docs](https://ai.google.dev/gemini-api/docs/caching)
- [Gemini 2.5 implicit caching announcement](https://developers.googleblog.com/gemini-2-5-models-now-support-implicit-caching/)
- [DeepSeek context caching guide](https://api-docs.deepseek.com/guides/kv_cache)
- [DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing)
- [Mager: How Claude prompt caching actually works](https://www.mager.co/blog/2026-04-29-claude-prompt-caching/)
- [TokenMix: DeepSeek cache pricing 2026](https://tokenmix.ai/blog/deepseek-cache-hit-pricing)
- [arXiv 2601.06007 — "Don't Break the Cache"](https://arxiv.org/pdf/2601.06007)
- [OpenClaw bug #19534 — cache read always 0](https://github.com/openclaw/openclaw/issues/19534)
