---
type: research
status: active
tags: [research, ai]
---

# DeepSeek-V4-Flash quality interrogation — is thinking mode worth it?

Measured 2026-08-01, 18:13–20:0x local (01:13–03:0x UTC 2026-08-02) against
the live `https://api.deepseek.com/chat/completions`. Every call serial.
Harness, raw responses, and graders: `tmp/flash-quality/`.

The owner's question: DeepSeek-V4-Flash ships with thinking mode ON by
default. **How helpful is thinking, and can Seon just take the much faster
non-thinking mode as the agent-loop default?**

Answer up front: **turn thinking OFF for the agent loop's code-writing path,
and keep a thinking configuration for the code-*reading* path.** The split
is not a hedge — it is where the measurements actually fall, and the
mechanism behind it is legible. See [[#Recommendation]].

This document supplies the evidence that **ruling #34 addendum** (plan
`README.md`) recorded as pending for adaptive mode. Verdict: adaptive does
NOT genuinely modulate, so it does **not** become the planner default;
always-high stays. See [[#Can we just leave it on adaptive No verdict b always-thinks]].

## Blocker found first: the configured model identifier does not exist

**Status: FIXED during this session** by commit 5c34b89a1, independently of
this lane; `config/default.edn` now reads `"deepseek-v4-flash"`. Recorded
here because the failure mode is worth not repeating.

`config/default.edn` currently sets
`:seon.config.ai/model "DeepSeek-V4-Flash-0731"`. **The API rejects that
string.** It is a release label, not a wire identifier:

```text
$ curl -s https://api.deepseek.com/models …
{"data":[{"id":"deepseek-v4-flash"},{"id":"deepseek-v4-pro"}]}

$ … -d '{"model":"DeepSeek-V4-Flash-0731", …}'
{"error":{"message":"The supported API model names are deepseek-v4-pro or
 deepseek-v4-flash, but you passed DeepSeek-V4-Flash-0731.", …}}
```

`DeepSeek-V4-Pro` is rejected identically. Every model call from a cluster
booted on the default manifest 400s before any completion happens. The legacy
`deepseek-chat` alias still resolves (server-side, onto flash with thinking
off), so the pre-existing config worked; the "correction" in 60b0476ee is
what broke it — a release label was substituted for a wire identifier
without a live call to check it.

Every measurement below therefore uses the accepted slugs
`deepseek-v4-flash` and `deepseek-v4-pro`.

## The thinking toggle, as measured

Probed against the live API before the vendor documentation arrived; the
two agree exactly. The vendor capture is
[[deepseek-thinking-mode-api-2026-08-01]].

```json
{"model": "deepseek-v4-flash",
 "thinking": {"type": "enabled"},      // also "disabled", "adaptive"
 "reasoning_effort": "low"}            // none|minimal|low|medium|high|xhigh|max
```

Measured facts the request builder must respect:

- **Thinking is ON by default at effort `high`.** Sending nothing gets you
  the most expensive configuration.
- `thinking.type` accepts exactly `adaptive`, `enabled`, `disabled` — the
  server enumerates them in its error:
  `unknown variant 'bogus', expected one of 'adaptive', 'enabled', 'disabled'`.
  Note `adaptive` is a real third value that the vendor capture's table does
  not mention.
- `reasoning_effort` accepts `none|minimal|low|medium|high|xhigh|max`, again
  from the server's own error text. `none` disables thinking entirely and is
  equivalent to `thinking:{type:"disabled"}` in every response field observed.
- **`enable_thinking: false` is silently ignored** — thinking still ran, 10
  reasoning tokens. Unknown top-level fields are dropped without error while
  known fields are type-checked (`thinking: false` errors with
  `expected struct ThinkingOptions`). A builder that guesses this field name
  gets thinking with no indication it failed to turn it off.
- Enabling thinking **adds ~79 tokens of prompt**: the same one-word request
  billed 11 prompt tokens with thinking off and 90 with it on.
- `reasoning_content` arrives beside `content` on the message;
  `usage.completion_tokens_details.reasoning_tokens` counts it, and
  `completion_tokens` **includes** it — which is what makes the failure mode
  in the next section possible.
- Per the vendor capture, `temperature`/`top_p`/penalties are silently
  ignored in thinking mode. **Methodology caveat:** the main matrix sent
  `temperature: 0` on every call, so the non-thinking cells ran at 0 and the
  thinking cells ran at the model default. The harness was corrected to omit
  temperature for thinking cells. This does not affect any correctness gate
  below (all gates are executed, not sampled), but it means the thinking
  cells are one sample from a default-temperature distribution.

## Method

Seven tasks, each with a **hard correctness gate written before any model
output was seen** (`tmp/flash-quality/tasks.py`, `grade.clj`). Code answers
are graded by **executing them** on a real JVM (`clojure -M:dev`) against
test inputs fixed in advance. Style is not graded.

Two of my own expected answers were falsified during setup and fixed before
spending a call: the scheduling puzzle had three solutions, not one, and the
"exactly n calls" task was ungradeable as first written. One grader bug (not
supplying `log` for t5) was found and fixed during grading.

Configurations: `flash-think` (enabled, default effort high),
`flash-nothink` (disabled), `pro` (deepseek-v4-pro, thinking on) as the
quality reference, plus `flash-adaptive` and `flash-think-low` as follow-ups.

## Can we "just leave it on adaptive"? No — verdict (b), always-thinks

`thinking: {"type": "adaptive"}` is **accepted by the OpenAI-format endpoint
for `deepseek-v4-flash`** — proven, not assumed: the server enumerates it
when rejecting a bad value (`expected one of 'adaptive', 'enabled',
'disabled'`) and adaptive requests return normally with `reasoning_content`.

**But it is UNDOCUMENTED.** The word `adaptive` does not appear anywhere in
the vendor documentation capture ([[deepseek-thinking-mode-api-2026-08-01]]),
whose toggle table lists only `enabled`/`disabled`. Depending on an accepted
-but-undocumented enum value means depending on behaviour the vendor has not
committed to and can change silently.

Measured against the full difficulty spread — three trivial tasks added
specifically so downward modulation would be visible:

| tier | non-thinking | **adaptive** | thinking-high |
|---|---|---|---|
| trivial (3 tasks) | 0 tok / 1.0 s | **27 tok / 1.2 s** | 24 tok / 1.3 s |
| hard | 0 tok / 1.6 s | **46,897 tok / 455 s** | 16,383 tok (truncated) |

Per trivial task, adaptive vs thinking-high reasoning tokens:

| task | non-thinking | adaptive | thinking-high |
|---|---|---|---|
| `2 + 2` | 0 | 6 | 9 |
| `(count [1 2 3])` | 0 | **30** | 21 |
| reverse a 5-vector | 0 | 44 | 43 |

**Adaptive never stands down.** It spends reasoning tokens on "what is
2 + 2", and its trivial-tier mean (27) is *indistinguishable from — slightly
above — thinking-high's* (24). On `(count [1 2 3])` it spent more than
thinking-high did. There is no near-zero floor on easy work, which is
precisely the property the free lunch would require.

This is **verdict (b): adaptive is thinking with extra marketing.** It is
not (a), so by the owner's own criterion it does not earn the planner
default — always-high remains the planner setting, and adaptive should not
be adopted. It is not quite (c) either: it does not *skimp* on hard work, it
simply thinks everywhere.

The latency point stands on its own: on the transducer task adaptive spent
**455.2 s and 46,897 reasoning tokens to reach the same PASS** that
non-thinking reached in **1.6 s and 121 tokens** — 285x the wall time for an
identical graded result.

## Result matrix

<!-- FILLED IN BELOW -->

## Recommendation

<!-- FILLED IN BELOW -->
