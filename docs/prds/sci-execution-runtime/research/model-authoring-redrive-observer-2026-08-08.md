---
type: research
status: complete
tags: [research, runtime, agent]
---

# Observer lane — model-authoring re-drive on `default`, 2026-08-08

Independent, trust-nothing observation of the model-authoring re-drive on
cluster `default`. This lane took no stimulus action: it submitted no message,
transacted nothing, stopped nothing, reforked nothing. Every fact below came
from read-only Datalog queries against the live connection, my own HTTP request
to the running server, the cluster log, and source reads.

I read end to end, before starting: the plan
[whole-system-arc-2026-08-08.md](../plan/whole-system-arc-2026-08-08.md) and my
predecessor's
[whole-system-arc-observer-2026-08-08.md](whole-system-arc-observer-2026-08-08.md),
including its four documented vacuous-probe traps. I did not repeat them, and I
recorded three new near-misses of my own in [Method](#method-and-my-own-near-misses).

## The claim, and the verdict

The claim I was set to falsify: **"a real model authored a contracted function
that is now callable."** On this drive it is **FALSE on every one of its four
halves**, and the cause is a single upstream platform defect that stops the arc
before authoring can even begin.

- **(a) The function's `:seon.fn` facts exist** — **NO.** No `word-count`
  function exists anywhere in the program graph. Queried directly:
  `[:find ?fqn :where [?f :seon.fn/name ?fqn] [(clojure.string/includes? ?fqn "word")]]`
  returns `[]` across the whole window; no `arc.*` function of any kind exists.
- **(b) A provider call authored it** — **NO, though the calls were real.** Four
  genuine DeepSeek attempts happened (real, varied, non-constant tokens —
  §Calibration). They authored nothing. Every reply is model prose *debugging a
  render error*, never a `defn`.
- **(c) A subsequent call settled a real result via a receipt** — **NO.** There
  is no `word-count` to call, and every run's forms are comment-only prose,
  which settle no receipt.
- **(d) Call preparation fired (the elided-argument case)** — **NOT REACHED.**
  The task never progressed to a function call of any kind.

**Root cause (blocker):** the context render walk
(`seon.render.walk/neighborhood`) fails its own output contract, so **every
agent's entire prompt is a 931-character render-walk contract-violation error**.
The human's instruction never reaches the model. The model, handed only that
error as its whole world, spends each turn explaining/debugging the render
violation, closes, is re-woken by its own fault, and repeats. Four provider
calls of ~14.5k completion tokens each were burned on a 336-token error prompt,
producing nothing.

This does not contradict my predecessor — it was a **different JVM**. The
predecessor observed pid 31475 (this morning) and reported the empty-context
defect fixed and real REPL sessions reaching agents. This re-drive is pid 14148,
booted `11:49:31Z`, and the context render is broken again. Given the churn in
`src/seon/render/walk.clj` since (`d4ac2ba40`, `88ecc7167`, `8872311d1`,
`9fa48fa20`), this reads as a **regression reintroduced after the morning fix**.

## Scope

- cluster `default`, pid `14148`, prepl `127.0.0.1:58540`, web
  `http://127.0.0.1:7994`;
- JVM start `2026-08-08T11:49:31Z`; my observation window `11:52Z` → `11:58Z`;
- basis at window end: **5 runs, 1 agent (root only), 4 provider attempts, 0
  `word-count`/`arc.*` functions, ~19 `:seon.error` facts**;
- read-only throughout; probes ran in MCP session `obsredrive`, jvm mode,
  qualified `"default"`.

## The re-drive's actual test

The human message to root (`:seon.cluster.message/ordinal` 0), verbatim:

> Please do this in one turn, then finish. In your namespace define a durable
> contracted function named word-count: a defn with a complete :malli/schema
> [:=> [:cat :string] :int] that returns the number of whitespace-separated
> words in a string (empty or blank string -> 0). Then call
> (word-count "the quick brown fox jumps") on one sample to show it works. Then
> end your run with (my.run/complete "...") summarizing what you defined. No need
> to explore first — just define, call, complete.

A single-agent authoring test, simpler than the three-agent arc in the plan doc.
Root itself is asked to author `word-count`.

## Timeline (facts, UTC)

| Time | Event |
|---|---|
| 11:49:31 | JVM start, pid 14148 |
| 11:49:39–45 | `bootstrap:root` — 13 forms |
| 11:49:46–11:52:00 | run `1de9beeb` (root) — 1 provider attempt at 11:49:47 |
| 11:52:00 | word-count message context built; run `8988f92b` opens; attempt at 11:52:00 |
| 11:52:00–11:54:11 | run `8988f92b` closes — 6 forms, **all prose about a render contract violation**, no `word-count` |
| 11:54:11–11:56:11 | run `599645bd` — 5 forms, prose *debugging* `seon.render/walk` (`(doc seon.render/walk)`), no `word-count` |
| 11:56:11–11:57:44 | run `fe68fac3` — same class |
| 11:58:09 | my last poll — 5 runs, 4 attempts, still no `word-count` |

Runs chain end-to-open with no gap (`…11:52:00 → 11:54:11 → 11:56:11 → …`): a
self-perpetuating wake loop, ~2 min per turn.

## The blocking defect, from the durable capture

Every run's prompt is stored at `:seon.context.capture/prompt`. All four
captured runs recorded **exactly 931 characters** (`:seon.ai.tokens/characters`
= 931 for all; `captures-all-931? => true`). The stored prompt, verbatim and
complete:

```text
;; (seon.render/walk) => error
Walk failed: seon.render.walk/neighborhood violated its contract (invalid-output): [#:seon.render{:output [{:value "Agent root is running now.\nmy.agents.root=> (help)\nYou are an agent in a Seon cluster — a real Clojure REPL, and it is\nyours. Your reply is read as forms and evaluated in order in your own\nnamespace; each form and its actual value come back as this session…", :message "should be either :seon.render/ai or :seon.render/html"}]} #:seon.render{:output [{:value "elided connections at the requested distance cap", :message "should be either :seon.render/ai or :seon.render/html"}]} #:seon.render{:output [{:value "elided connections at the requested distance cap", :message "should be either :seon.render/ai or :seon.render/html"}]} #:seon.render{:output [… 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}]
```

That is the whole prompt. The single context contribution is the block named
`walk` (232 tokens), and it is this failure. The word-count instruction is
absent from the prompt entirely — it is a database fact the render never reached.

Reproduced live independently through the web route: `GET
/ns/my.agents.root/debug` returns 200 / 3040 bytes and shows the identical
`seon.render.walk/neighborhood violated its contract (invalid-output)` text — so
the failure is current, not a stale capture.

**Mechanism.** `seon.render.walk/neighborhood`'s output contract requires each
`:seon.render/output` item be tagged `{:seon.render/ai …}` or
`{:seon.render/html …}`. The producers hand it **bare values**: e.g.
`seon.render.agent/agent-ai` (`src/seon/render/agent.clj:17-34`) returns a plain
`String` ("Agent root is running now."), and the session/transcript composition
that carries the real REPL preamble ("You are an agent in a Seon cluster — a
real Clojure REPL…") likewise reaches the walk as a bare string. The validator
wraps each as `{:value "…" :message "should be either :seon.render/ai or
:seon.render/html"}`, the node fails `invalid-output`, and the whole
neighborhood collapses to this error text — which then becomes the prompt.

Filed as a new blocker:
[Every agent prompt is a neighborhood render-walk contract violation](../../../seon/issues/every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md).

## The self-waking loop

The four post-bootstrap runs are one loop. Each turn:

1. gets the 931-char render-error prompt (no instruction);
2. burns a ~14.5k-token completion (mostly reasoning) producing prose about the
   render error;
3. closes with only comment-only forms (no receipt, charter (c));
4. its failure wakes root again, immediately (next run opens at the same instant
   the prior closes).

This is the class already owned by
[a-failed-turn-wakes-itself-through-its-own-fault-message.md](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md).
Evidence appended there: on a broken-context boot the loop is unbounded and
paid-for (4 attempts × ~14.5k completion tokens in 6 minutes on a 336-token
prompt).

## Token sentinel — a real pathology, exactly the one to watch

Every attempt's `:seon.ai.attempt/usage-edn`, verbatim shapes:

| At (UTC) | prompt | completion | reasoning | finish | c:p |
|---|---:|---:|---:|---:|---:|
| 11:49:47 | 336 | 14,531 | 14,175 | stop | 43.2 |
| 11:52:00 | 336 | 14,641 | 14,284 | stop | 43.6 |
| (2 more, same shape) | 336 | ~14,5xx | ~14,2xx | stop | ~43 |

The completion-to-prompt ratio is **~43**, against the 46.7 the directive named
as the pathology signal. `finish_reason` is `stop` every time, so it is not a
truncation runaway — it is the model reasoning ~14k tokens trying to make sense
of a 336-token error prompt with no task. **The sentinel should flag this**: a
c:p of 43 on a 336-token prompt is the loud symptom of the broken-context loop.
The prompt itself never approaches the budget (336 tokens), so a budget guard
alone never catches it; the ratio does.

## Calibration — what held, and one caveat

The estimator was rewritten since the predecessor's chars/4 finding
(`src/seon/ai/tokens.cljc`, commit `cc9357948`). It now carries an **observed**
basis fitted to facts, with a `shipped` chars/4 fallback, and the derivation
reads the database via `seon.cluster.prompt/model-calibration`
(`src/seon/cluster/prompt.clj:71-108`), which joins each attempt to the exact
`:seon.ai.tokens/characters` recorded with it. The budget seam consumes it
(`prompt.clj:224`).

**What held (verified live).** `model-calibration db "deepseek-v4-flash"`
returns, right now:

```clojure
{:seon.ai.tokens/chars-per-token 2.7708333333333335
 :seon.ai.tokens/basis :seon.ai.tokens/observed
 :seon.ai.tokens/sample-count 1
 :seon.ai.tokens/relative-error 0.0}
```

It is wired, reading facts, and on the `observed` basis. The direction is now
**conservative**: 2.77 ch/tok is lower than the docstring's calibrated 3.28, so
`estimate = chars/ratio` now *over*-estimates a real prompt rather than
under-estimating it. The predecessor's failure class — the estimate running
23-26% **low** so a 35,827-token prompt slipped past a 32,768 budget silently —
is structurally addressed: the number is now derived from provider facts, and it
errs high.

**Caveats, recorded so the calibration is not over-trusted:**

1. **n = 1, and `relative-error` is 0.0 — a false-confidence band.** With one
   observation the "worst observed miss" is trivially 0.0%, so `budget-report`'s
   `near-limit` verdict can never fire. The band is only meaningful once several
   representative prompts exist. This is inherent to the honest fit, not a
   defect, but a reader must check `sample-count` before trusting the band.
2. **The single calibrating sample is contaminated by the context bug.** The one
   observation is 931 chars → 336 tokens = 2.77 — i.e. the *broken* prompt. Until
   real prompts flow (which requires the render-walk fix), the live calibration
   is fitted to an error string, not to representative agent context. The number
   is honest about its provenance but not yet representative.

## Every error fact (corrected count)

`:seon.error/kind` frequencies at window end (grouped by kind — see near-miss #2):

| Kind | Count |
|---|---:|
| `seon.sci.eval/evaluation-failed` | 10 |
| `seon.instrument/contract-violated` | 4 |
| `user-input` | 2 |
| `seon.operator/collection-incomplete` | 1 |
| `seon.operator/process-census-incomplete` | 1 |
| `seon.operator/reap-incomplete` | 1 |

The `evaluation-failed`/`contract-violated` bulk is the render-walk failure plus
the model's own failed attempts to call `(seon.render/walk …)` and `(doc …)`
while debugging its broken context.

## Ugly output, verbatim

- The 931-char prompt above is itself the ugliest output on the cluster: a
  render-contract-violation EDN dump, shipped as an agent's entire context.
- Run `599645bd` form 2, the model quoting the validator's own wrapper back at
  itself: `; Those {:value ... :message ...} maps are the validator telling
  you: "this string is not a valid rendered output."` — the agent is now
  narrating the platform's internal contract error as if it were user-facing
  guidance.

## Method, and my own near-misses

Three traps I hit and caught, in the predecessor's spirit:

1. **Counting "receipts" by a non-existent attribute.** My first census counted
   `[:find ?r :where [?r :seon.cluster.eval/receipt-id]]` → 0. There is no
   `:seon.cluster.eval/receipt-id`; settlement is the presence of
   `:seon.cluster.eval/result-edn`/`/error` on an eval form. A vacuous zero
   sitting next to working counts.
2. **`frequencies` over a `d/q` result grouped by the entity var.** Grouping
   error kinds by `?e` (the eid) returned all `1`s because every eid is distinct
   in the set. Projecting `?k` gives the real distribution.
3. **`requiring-resolve` on the wrong namespace.**
   `seon.cluster.store/running-instances` resolves to `nil` (the var lives in
   `seon.operator.runtime`), and `@@nil` throws a nil-deref that *looks* like a
   dead cluster. The connection is
   `(:seon.boot/cluster-connection (get @@#'seon.operator.runtime/running-instances "default"))`.

The shared lesson, again: an empty/nil result from a read API is not evidence of
absence until a second, differently-shaped probe agrees.

## Disagreements a driver report should expect

1. **"A model authored a callable contracted function"** — refuted. No
   `word-count` fact exists; the provider calls authored only prose.
2. **"The arc ran"** — it did not begin. The blocking defect is upstream of any
   authoring: the agent never receives its instruction.
3. **"The context defect stayed fixed"** — not on this JVM. The neighborhood
   render collapses every prompt to a 931-char error; the morning fix regressed.
4. **"The token sentinel held"** — the ratio pathology (c:p ~43) is present and
   is the correct signal for the broken-context loop; a budget-only check misses
   it because the prompt is tiny.

## What is genuinely in good shape (calibration, not alarm)

- **The estimator rewrite is sound and fact-driven** — observed basis, honest
  band, conservative direction, wired into the budget seam. The one caveat is
  n=1 representativeness, not correctness.
- **The provider seam made real, well-formed calls** — four attempts, varied
  non-constant token counts, `finish_reason stop`, usage recorded durably.
- **Custody is clean** — every open run held the one process holder; the four
  closed runs shed it; no run ever held by two processes.
- **The durable context capture is what made this diagnosable** —
  `:seon.context.capture/prompt` records the exact bytes each run received, so
  "the instruction never reached the model" is a query, not a guess. Cheap
  correct diagnosis did the finding again.

## Issues

New:

- [Every agent prompt is a neighborhood render-walk contract violation](../../../seon/issues/every-agent-prompt-is-a-neighborhood-render-walk-contract-violation.md) — blocker

Evidence appended to existing:

- [a-failed-turn-wakes-itself-through-its-own-fault-message.md](../../../seon/issues/a-failed-turn-wakes-itself-through-its-own-fault-message.md) — the broken-context wake loop, four paid turns in six minutes.
</content>
</invoke>
