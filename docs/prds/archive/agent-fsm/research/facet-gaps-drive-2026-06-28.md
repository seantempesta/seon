---
type: research
status: active
tags: [agent, research, gym]
---

# Facet-gaps drive — plan-resume + err-recovery (2026-06-28)

Hermetic gym drive (paid DeepSeek, `bin/gym --paid=resume,err2`) of the two
untested-facet scenarios with unknown outcomes. No live-pod touch, no Core-file
edits. Run sha `90085b09`.

## TL;DR

- **`err-recovery-diagnose-and-continue` — PASS (10/10 + judge 100). Facet HANDLED.**
  The agent stayed in its home ns (query-only task), hit real error-as-value
  envelopes, READ them, fixed, and finished. No spiral (error-rate 0.23 ≤ 0.34).
  The "ERRORS ARE VALUES" standing teaching + the `:seon.db/ok? false` envelope
  carry this facet today.
- **`plan-resume-across-restart` — scorecard pass? FALSE, but the FACIT UNDER TEST
  PASSES.** The `:judge-resumed-not-replanned` leg scored **100**: turn 2 resumed
  the open plan, loaded the books, wrote the fn, did NOT re-plan or re-do the
  schema. The 3 mechanical reds are NOT resume failures — they are a **general
  fn-authoring gap** the scenario happened to expose:
  1. `:keeps-the-repl-clean` RED (0.29 > 0.2) — the home-ns shorthand verbs
     (`db/`, `message/`, `todo/`, `complete`) BREAK the moment the agent switches
     into a `my.*` ns to author its fn (which the context explicitly tells it to
     do). ~60 `"X is not defined. Did you mean X?"` errors before it recovered by
     fully-qualifying. **This will red the cleanliness gate on EVERY fn-authoring
     drive — highest-value finding.**
  2. `:wrote-a-test-for-the-fn` RED (0/69 `deftest`) — the agent proved the fn
     with inline `(assert …)` (the judge + human accepted it), never a named test.
  3. `:minted-a-plan-up-front` RED (2 todos, want ≥3) — under-decomposed (schema;
     then a combined load+build item). Borderline predicate-strictness.

---

## Scenario 1 — plan-resume-across-restart (`:resume`)

`scorecard/pass? false` · `judge-pass? true` · 19 turns · error-rate 0.29 ·
toolkit-calls `{:my.ui 26 :my.data 0 :my.canvas 0}`

| predicate | axis | result |
|-----------|------|--------|
| minted-a-plan-up-front (≥3) | stores-proactively | **FAIL** — 2 todos |
| no-from-scratch-replan (≤5) | models-work-directed | PASS — 2 |
| closed-at-least-one-item | stores-proactively | PASS |
| closed-the-loop-no-open-items (empty) | stores-proactively | PASS |
| designed-the-books-schema (≥2) | models-work-directed | PASS — `:my.reading.log/{title,author,finished-at}` |
| wrote-a-test-for-the-fn (`deftest` ≥1) | writes-tests | **FAIL** — 0 `deftest` |
| replied-to-the-user | replies-honestly | PASS |
| agent-ends-idle | terminates | PASS |
| terminates-under-cap (≤30) | terminates | PASS — 19 |
| keeps-the-repl-clean (≤0.2) | makes-few-errors | **FAIL** — 0.29 |
| **judge-resumed-not-replanned** | models-work-directed | **PASS — score 100** |

### Real behavior (observed in the transcripts)

- **Turn 1:** designed the schema in a fresh `my.reading.log` ns
  (`title`/`author`/`finished-at`), **minted 2 todos** (`dfj-…`, `JNV-…`), closed
  the schema item, replied asking for the list. Agent's own comment on turn 2:
  *"Three books, two authors. Let me load them all into the store, then build the
  author-lookup fn with a test. First: mark the address-todo done, then transact
  the books."* — i.e. it consciously RESUMED from its open item.
- **Turn 2:** loaded the 3 books, wrote `my.reading.log/books-by-author`, verified
  it with **inline `(assert …)` forms** ("the test body runs clean, all 3 asserts
  pass"), rendered a canvas, replied. **Resumed correctly — judge 100.**

### Why the 3 mechanical reds (diagnosed from real evals)

**(1) eval-error-rate 0.29 — home-ns alias breakage on ns-switch (the real gap).**
To author `books-by-author` the agent did `(ns my.reading.log)` / worked in
`my.agent.YhP-…`. The home-ns *shorthand* verbs then stopped resolving:

```
(db/transact! …)
;=> ✗ `db/transact!` is not defined. This form ran NOTHING.
    Did you mean `db/transact!`? — that home-ns verb; use that form,
    do NOT switch namespace.
(message/user "…")
;=> ✗ `message/user` is not defined. … Did you mean `message/user`? …
```

The hint suggests the **identical token** and says *"do NOT switch namespace"* —
but the context elsewhere explicitly tells the agent to **"CREATE a namespace …
(ns my.<domain>.<thing>)"** to hold its fn. These two teachings COLLIDE. The agent
eventually recovered by **fully-qualifying** (`seon.db/transact!`,
`seon.agent.message/user`) — which the hint never suggests — but burned ~60 evals
getting there, pushing the run over the 0.2 cleanliness gate.

**(2) no `deftest`.** The agent's notion of "a test proving it works" was inline
`(assert …)` (494 accumulated assert forms, zero `deftest`). Legitimate
verification, accepted by the human-facing judge, but there is no canonical
named-test verb taught, so "write a test" produced ad-hoc asserts.

**(3) 2 todos, not 3.** The agent decomposed the 3-step task into 2 durable items
(schema; then a combined load+build). The resume still worked; this is mostly
predicate strictness (2 is a defensible plan), with a faint "one-todo-per-step"
teaching gap.

---

## Scenario 2 — err-recovery-diagnose-and-continue (`:err2`)

`scorecard/pass? true` · `judge-pass? true` · 10 turns · error-rate 0.23 ·
toolkit-calls `{:my.data 21 :my.ui 52 :my.canvas 0}`

| predicate | result |
|-----------|--------|
| exactly-five-articles (=5) | PASS |
| one-article-marked-read (≥1) | PASS — Why Clojure → :read |
| backlog-still-has-unread (≥1) | PASS — 4 |
| computed-over-word-count | PASS — 15/30 evals |
| agent-replied-to-the-user | PASS |
| agent-ends-idle | PASS |
| terminates-under-cap (≤19) | PASS — 10 |
| **no-error-spiral (≤0.34)** | **PASS — 0.233** |
| **judge-diagnosed-and-finished** | **PASS — score 100** |

### Real behavior

Query-only task → the agent **stayed in its home ns** → **zero home-ns alias
breaks** (grep returns nothing). It hit real recoverable errors — `:seon.db/ok?
false` envelopes (36 accumulated), a few prose-written-as-a-form `"ran NOTHING"`
notes, and some malli `invalid` — **read them, corrected, and continued without
hammering the same failing call**. Turn 1: total + argmax (Designing
Data-Intensive Systems, 4200), marked the shortest (Why Clojure, 950) read.
Turn 2: re-queried its own write, reported 4 unread (~47 min @ 250 wpm),
recommended The Cost of Microservices. Clean finish. **Facet fully handled by the
"ERRORS ARE VALUES" teaching + the error-as-value envelope.**

The contrast with scenario 1 is the whole finding: **err2 passed the cleanliness
gate precisely because it never had to leave its home ns.** The resume agent's
only "sin" was authoring code — the exact thing the context tells agents to do.

---

## Facets HANDLED vs GAPS

### HANDLED (context already carries these)

- **Error-recovery — diagnose / read-the-envelope / fix / continue, no spiral.**
  The standing "ERRORS ARE VALUES … Read the error map; it names the defect and
  the fix" teaching + the `{:seon.db/ok? false :seon.db/error …}` envelope work as
  designed. err2 is a clean PASS.
- **Planning continuity — resume-not-replan.** The open-todos block rendering every
  turn + the "mint the steps up front, done! each as it lands" teaching let the
  agent read its open item on turn 2 and resume. Judge 100 on BOTH the structural
  guard (`no-from-scratch-replan`) and the semantic leg.

### GAPS found + proposed GENERAL fixes

**GAP A — home-ns shorthand verbs break when authoring in a `my.*` ns
(lane: Core eval/error-render + U guidance). HIGHEST VALUE.**
Any task that requires writing a fn (the context's headline instruction, "BUILD
YOUR ENVIRONMENT … CREATE a namespace") forces an ns-switch that silently kills
`db/`, `message/`, `todo/`, `complete`, reding the cleanliness gate. General fix,
two parts:
- **Core (the "not defined" error render):** when a *known home-ns shorthand* is
  called from a NON-home ns, the recovery hint must teach the **fully-qualified**
  form, not the identical token + "don't switch namespace." e.g.
  *"`db/` is your home-ns shorthand and only resolves in your home ns; you're in
  `my.reading.log` — call `seon.db/transact!` (fully qualified), which works from
  any ns."* This is general (keyed on "bare alias used outside home ns"), never
  scenario-specific.
- **U (standing teaching):** the "BUILD YOUR ENVIRONMENT / (ns my.<domain>)" block
  should state in one line that the `db/` `message/` `todo/` shorthands are
  home-only and to use `seon.db/…` / `seon.agent.message/…` when working inside a
  `my.*` namespace.

**GAP B — "write a test" yields inline `assert`, never a named test
(lane: U toolkit/skills).**
There is no canonical named-test pattern in the always-on corpus, so a fn-proving
request produces ad-hoc `(assert …)`. General fix: teach (in the toolkit/skill
corpus) that proving a fn = a **named, runnable test** (a `my.test`/`deftest`
pattern), with one worked example — the same "show, don't tell" treatment the
`register!→transact→query` example already gets. (NOT keyed to "books" or to the
`deftest` literal — the lesson is "prove fns with a named test," general.)

**GAP C — under-decomposition (2 todos for a 3-step task) (lane: U, low priority).**
Faint gap in the "one todo per step" teaching; the agent still resumed correctly,
so this is mostly predicate-strictness. Flag for predicate tuning rather than a
context change.

### Flagged for predicate tuning (NOT a context gap)

- `:wrote-a-test-for-the-fn` and `:keeps-the-repl-clean` both fired RED on an agent
  that the human-facing judge scored **100** for a correct, resumed, tested result.
  The `deftest` literal-match and the 0.2 cleanliness ceiling penalize legitimate
  inline-assert verification and the unavoidable alias-recovery cost of authoring
  code. Once GAP A's error-render fix lands, re-drive to see if cleanliness clears
  0.2 on its own; if not, the ceiling is too tight for fn-authoring scenarios.

## How to reproduce

```bash
bin/gym --paid=resume,err2          # DeepSeek; needs DEEPSEEK_API_KEY
# scorecards: tmp/gym-paid-card-{resume,err2}-<run-id>.edn
# real transcripts: logs/turns/<turn-id>/<n>-…/prompt.txt (last file = fullest)
```
