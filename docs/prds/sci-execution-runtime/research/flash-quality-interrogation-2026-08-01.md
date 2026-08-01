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
always-high stays. Evidence in the adaptive section below.

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

## The ask-the-model bug-hunting modality

Separate from the synthetic tasks: paste a real Seon namespace with line
numbers, demand claims in a fixed `CLAIM/LINES/FN/KIND/MECHANISM/REPRO`
shape, and grade every claim by execution. This is the modality that matters
for Seon, because it is what an agent reading its own program graph would do.

**It found two real bugs on its first outing, both with thinking OFF.**

| claim | verdict | evidence |
|---|---|---|
| `admit/project-entries` cuts at `width - 1` | **CONFIRMED — filed** | `max-collection` 2→1 item, 3→2, 5→4 |
| `opened-window` size 0 shows nothing, `more? true` | **CONFIRMED** | residual degenerate case after the size-1 fix |
| `project-map` starves the cut marker at 2–3 nodes | CONFIRMED in substance | predicted symptom imprecise, mechanism right |
| `admit` with `max-nodes` 1 elides `42` | **FALSIFIED** | projects `42` correctly |
| `submit-evaluation!!` flow limit is 2x the eval limit | **FALSIFIED as a defect** | reading is correct; the 2x is the intentional outer backstop |
| `CountedDroppingBuffer.full?` always false is a contract mismatch | **FALSIFIED as a defect** | core.async's own `DroppingBuffer` returns `false` identically (`buffers.clj:41`) |

Confirm rate on the six claims checked: **3 confirmed / 3 falsified**, from
non-thinking calls costing 10–15 s each (four namespaces: `value.cljc`,
`admit.clj`, `loop.cljc`, `flow.clj`; `schema.cljc` at 2548 lines was cut
when it exceeded four minutes on one call).

[[admit-projects-one-fewer-item-than-max-collection]] is the significant
one: the identical off-by-one class as the `value.cljc` bug fixed earlier the
same day, surviving in the sibling that fix missed — and `admit` is the more
consequential owner, because it bounds what every agent sees of its own
evaluation results.

The failure mode is legible and worth planning around: **the model reads the
code accurately and then misjudges intent.** Not one falsified claim
hallucinated code that was not there. Two of the three described the source
correctly and drew the wrong conclusion because they lacked a surrounding
contract — the 2x flow limit is Seon's deliberate last-resort backstop, and
`full? = false` is core.async's own dropping-buffer contract, faithfully
implemented. The third simply mispredicted a boundary.

That is a good failure mode to design around, and it sets the supervision
requirement precisely: **claims are cheap and must be verified, never
applied.** A reviewer holding the architecture triages these in seconds; an
agent acting on them unsupervised would "fix" two deliberate designs. The
modality earns its place as a *lead generator* feeding execution-based
verification — which is exactly how the confirmed `admit` bug was caught.

## Result matrix

Correctness is the hard gate: code answers were executed, value answers
compared to an answer fixed in advance. `EMPTY` means the response finished
with `finish_reason: length` having spent the whole budget on reasoning and
emitted **no content at all** — graded as a failure, because a turn that
returns nothing is a failed turn.

| task | gate | flash-nothink | flash-think (high) | pro |
|---|---|---|---|---|
| t1 transducer | executed | **PASS** 1.6 s | EMPTY 148.7 s | **PASS** 22.2 s |
| t2 chunking (value) | `32` | **8 — WRONG** 1.3 s | EMPTY | **32** 34.8 s |
| t2 chunking (code) | executed | **PASS** | EMPTY | **FAIL** (NPE) |
| t3 CAS reduce | executed | **PASS** 1.6 s | **PASS** 36.0 s | **PASS** 7.4 s |
| t4 datalog + malli | executed | FAIL 2.0 s | EMPTY 122.2 s | FAIL 40.8 s |
| t5 debug | executed | **PASS** 2.1 s | EMPTY 123.8 s | EMPTY 240.4 s |
| t6 puzzle | `31254` | **PASS** 5.3 s | **PASS** 5.0 s | **PASS** 15.9 s |
| t7 trace real source | 6 values | **WRONG** 1.2 s | **PASS** 11.0 s | **PASS** 24.0 s |

**Tally (8 gates): flash-nothink 5 correct, flash-think 2, pro 5.**

Non-thinking flash matches pro on correctness while being 10–100x faster,
and *beats* thinking-high outright — because thinking-high failed four gates
by never producing an answer.

Notes on the two shared failures, which are honest and not thinking-related:

- **t4** — both non-thinking and pro wrote `:long` in the Malli schema.
  `:long` is not a Malli schema (`:int` is; Seon's own EDN uses `:int` 100
  times, `:long` zero). Both also got the Datalog query exactly right. This
  discriminates nothing between modes; it is a real shared knowledge gap.
- **t2 code** — pro used `(rest s)` where `(next s)` is required: `(rest s)`
  returns a truthy empty seq, so `(and s …)` stays true, `(first ())` is
  `nil`, and `f` is called on `nil` → NPE when `n` exceeds the collection.
  Non-thinking used `next` and passed. Pro reasoned its way to the right
  *prose* answer on t2 and then shipped the broken *code*.

## Two verbatim excerpts

**Where thinking earned its cost — t7, tracing real `value.cljc` source.**
Non-thinking answered in 35 tokens, entirely from intuition, and was wrong:

```text
WINDOW: [:a]      <- wrong
STEPS: [0]        <- wrong
SHOWN: 1          <- wrong
TOTAL: 3
MORE: true
TRUNCATED: true
```

Thinking substituted the actual arithmetic instead of pattern-matching
"page size 1 means one item", and got all six right:

```text
available = max 0 (dec size) = dec 1 = 0, max 0 => 0.
head = (into [] (comp (drop offset) (take (inc available))) entries).
(inc available) = 1 ... head = [[0 :a]].
more? (> (count head) available) = (> 1 0) => true.
page = subvec [[0 :a]] 0 (min 0 1) = subvec vector 0 0 => [] (empty vector).
```

This is the entire value of thinking mode in one paragraph: it is worth
paying for when the answer depends on evaluating code precisely rather than
recognising it. (The `(dec size)` it correctly traced *was* the live
off-by-one, fixed later the same day; t7 grades against the pre-fix source
pasted into the prompt.)

**Where thinking was pure latency — t1, the transducer.** Non-thinking
produced a fully correct stateful transducer, including the reusability trap
(fresh `volatile!` per `(fn [rf] …)` invocation, not captured once), in
**1.6 s / 121 tokens**, passing all seven checks:

```clojure
(defn dedupe-by
  ([f]
   (fn [rf]
     (let [prev (volatile! ::none)]      ; per-reduction state: correct
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input] ...))))))
```

Thinking-high, on the same task, had the correct approach in its first
paragraph — "each call to xform creates fresh state in its closure" — and
then spent **64,739 characters** of reasoning re-deriving it, exhausted the
budget, and returned an empty string. Its reasoning tail is still
second-guessing sentinel representation:

```text
This creates a new Object per reduction by calling xf; fine. It keeps
sentinel inside stateful closure. Since prev volatile initial value is an
Object. No need separate `none` variable? We need compare `(identical?
@prev sentinel)`. We can store initial sentinel separately: ...
```

## Cost

Outside the 2x peak window (UTC 01:00–04:00) — these runs were 22:13 UTC
onward, i.e. **standard price**; the same matrix inside the peak window costs
double. Since `completion_tokens` includes reasoning tokens and reasoning is
billed as output, the runaway is a direct cost multiplier: one transducer
answer cost 121 output tokens non-thinking and 47,025 under adaptive — **388x
the output spend for an identical graded result.**

## Recommendation

**Thinking OFF as the agent-loop default on flash.** This confirms the
shipped default recorded in ruling #34 addendum, on independent evidence.

The evidence, in order of weight:

1. **Thinking-high does not reliably terminate on code generation.** Four of
   seven tasks returned *nothing* at 16K max-tokens, spending 100% of the
   budget on reasoning. Pro did it too (t5, 240 s). An agent loop whose turns
   silently return empty is worse than one that is occasionally wrong.
   The `config/default.edn` provenance comment claiming 8192 "completed three
   consecutive ordinary Seon task shapes while preserving thinking" does not
   generalise: 16384 was insufficient for over half of this task set.
2. **Non-thinking is not the weaker model on code.** 5/8 gates vs pro's 5/8,
   at 1–2 s per turn against 20–40 s. On the transducer it produced the same
   correct answer pro did, and on t2 it shipped working code where pro shipped
   an NPE.
3. **The latency ratio where both succeed is 10–100x** (t3: 1.6 s vs 36.0 s;
   t1: 1.6 s vs adaptive's 455.2 s).

**Adaptive: do not adopt.** Verdict (b) — it never stands down (27 reasoning
tokens mean on trivial tasks vs thinking-high's 24), and it is undocumented.
Ruling #34's condition for promoting it to the planner default is not met.

**The one task class where the answer differs — keep a thinking
configuration for code *reading*.** t7 is the only gate where non-thinking
lost to thinking, and it is precisely the shape Seon cares about: tracing
exact behaviour through real source. Non-thinking failed it the intuitive
way, asserting what the code obviously *should* do. So:

| path | setting | why |
|---|---|---|
| per-turn namespace agents | thinking **off** | code generation; 1–2 s turns, no correctness loss |
| planner agents | thinking **on, effort high** | multi-step derivation; t6/t7 shape |
| code comprehension / audit | thinking **on** | the one measured class where off is wrong |
| bug-hunting over real source | thinking **off** | 3/3 confirmed-vs-falsified at 10–15 s per namespace |

**If thinking is on anywhere, `max_tokens` must be ≥ 32768** and the request
builder must treat `finish_reason: length` with empty content as a hard
failure to retry non-thinking, not as an empty reply. That single guard is
what stands between the runaway and a silently dead turn.

## Falsifiable gaps in this evidence

Honest limits, so nobody over-reads the table:

- One sample per cell. The correctness gates are executed, not sampled, but
  a borderline cell could flip on a re-run.
- `temperature: 0` was sent on all main-matrix calls and is silently ignored
  in thinking mode, so thinking cells are one draw from a default-temperature
  distribution while non-thinking cells are deterministic.
- `reasoning_effort: "low"` was not measured. Flash maps `low`→`low` (only
  pro collapses it), so it is a real dial and plausibly the setting that
  keeps t7's win without t1's runaway. **This is the highest-value follow-up**
  and the one number this document is missing.
- `schema.cljc` (2548 lines) was cut from the interrogation after exceeding
  four minutes on a single non-thinking call; the interrogation covers four
  namespaces, and the thinking arm of that comparison was not run.
