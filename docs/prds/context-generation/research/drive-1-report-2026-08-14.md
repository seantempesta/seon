---
type: research
status: complete
date: 2026-08-14
---

# Drive 1 — live DeepSeek evolving session

## Attempt 5 verdict

**Web UI: [http://127.0.0.1:55156](http://127.0.0.1:55156)**

**Stopped at a durable `:seon.cluster.reply/no-forms` boundary on the real
task turn.** The fidelity wave is live: the first task prompt contains no
`largest` text, ends with the current message read, records 39 shape-cost
facts, and its 19 contribution costs sum exactly to its calibrated
whole-prompt estimate. DeepSeek nevertheless returned 97 tokens of prose and
no Clojure form. The runtime recorded the exact prose and typed refusal, then
closed the run. No corrective run or open work followed, so the mid-session
message and rebirth were not attempted.

### Attempt 5 live endpoint and retained root

- Fresh isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Git HEAD: `899e0b243`
- Published `:current-src` commit:
  `6a7efadd-34a1-5a21-bb26-9290201d2ecd`
- Publication inventory: 232 source inputs, 2,318 schemas, 823 functions,
  7,173 contract rows, 535 namespaces
- Agent: `drive-one-agent-attempt-5`
- Namespace: `my.agents.drive-one-agent-attempt-5`
- Generated opening run: `bootstrap:drive-one-agent-attempt-5`
- Task message: `inbound-536871131-0`
- Task run: `a887d305-c8ae-4b6e-842f-43287f7f7496`
- Opening capture:
  `bootstrap:drive-one-agent-attempt-5-context-536871009`
- Task capture:
  `a887d305-c8ae-4b6e-842f-43287f7f7496-context-536871133`
- Terminal error fact: `4b93a1bf-e56e-4a33-befe-1882cfda474f`
- Terminal error basis: `t=536871186`
- Retained-root footprint at final status: **10.90 GiB** (725.05 GiB
  filesystem usable). This is surprising growth from the fresh root and was
  preserved, not swept, under the drive evidence rule.
- Preservation: the root was neither stopped nor reset. The cluster, exact
  prompts, render costs, attempts, receipts, and error fact remain live.

The runtime-status tool remained degraded with the typed
`:seon.config/missing-projection` result; scoped JVM evaluation remained
functional. The report-only path restriction prevented a separate issue note.

### Attempt 5 headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Agent creation to opening close | **214.330 s** |
| Opening forms / receipts | **13 / 13** |
| Opening system / model-authored forms | **11 / 2** |
| Opening whole-prompt estimate | **10,635 tokens** |
| Opening provider prompt tokens | **11,182 tokens** |
| Opening shape-cost facts / sum | **36 / 10,582 tokens** |
| Beyond-closure intent contribution | **0 tokens** |
| Task whole-prompt estimate | **11,479 tokens** |
| Task provider prompt tokens | **11,476 tokens** |
| Task shape-cost facts / sum | **39 / 10,887 tokens** |
| Total provider prompt / completion tokens | **22,658 / 396** |
| Provider cache hits | **0** |
| Total spend | **$0.003283** |
| Authored plan items / `:about` refs | **0 / 0** |
| Durable functions / tests | **0 / 0** |
| Mid-session delta messages / rebirths | **0 / 0** |

Agent creation committed at `2026-08-14T11:25:09.534Z`; the generated
opening closed at `11:28:43.864Z`. The task was submitted at `11:28:56Z` and
its terminal error was committed in that same second.

### Family 1 — real opening composition and costs

There were no `my.plan` items before opening, so beyond-closure intent
membership contributed **zero**; all opening membership was ordinary closure.
The opening capture is 34,033 exact characters split into 17 ordered `walk`
contributions. Their recorded costs sum to **10,635**, exactly the calibrated
whole-prompt estimate. DeepSeek counted 11,182 prompt tokens on this first
fresh-cluster sample.

The 36 real `:seon.render.cost` facts committed between the capture's
single-snapshot basis and its capture transaction are:

| Shape | Calls | Per-call estimated tokens | Sum |
|---|---:|---|---:|
| `:seon.cluster/cluster` | 1 | 26 | 26 |
| `:seon.cluster.instruction/instruction` | 1 | 198 | 198 |
| `:seon.cluster.message/message` | 2 | 18, 95 | 113 |
| `:seon.cluster.run/run` | 1 | 41 | 41 |
| `:seon.config/entity` | 1 | 56 | 56 |
| `:seon.ns/ns` | 22 | 6, 6, 8, 6, 5, 7, 6, 6, 15, 8, 6, 1,018, 1,002, 576, 1,012, 1,005, 997, 1,002, 1,002, 69, 1,018, 1,008 | 9,788 |
| `:seon.schema/value` | 8 | 24, 17, 17, 22, 17, 26, 158, 79 | 360 |

The 17 exact ordered contribution costs were:

```clojure
[56 87 234 1038 1022 598 1032 1024 1017 1022 1022 110 97 1040 1028 81 127]
```

The 53-token difference between the shape-cost sum (10,582) and whole-prompt
sum (10,635) is prompt assembly/separator material assigned by the cumulative
contribution decomposition, not an unrecorded block.

### Family 2 — live pull

The opening took **214.330 seconds** under the fresh isolated root and settled
11 generated system forms before its model call produced two more forms. The
generated prefix therefore implies 12 `next-entry` decisions—11 concrete
entries plus the terminal call-boundary decision. This is a mechanical count
from the settled prefix because no separate function-call counter is stored.
The opening prompt then recorded 36 concrete render calls as cost facts.

### Current-task fidelity and exact accounting

The task capture contains **zero** lines containing `largest`. Its last
interaction is the current message read:

```clojure
my.agents.drive-one-agent-attempt-5=> (my.message/read "inbound-536871131-0")
```

That value is the exact current task:

```text
Author and follow one my.plan for this task. Every authored item must use the NEW :my.plan.item/about plain-vector token shape, mixing quoted qualified function symbols and namespaced keywords and targeting the actual functions and schema attributes that item will use. Define a durable contracted function sum-of-squares in your namespace that accepts a sequential collection of integers and returns the sum of their squares, with a complete Malli contract. Define a discoverable clojure.test usage test covering a non-empty input and the empty input, run it through seon.test/run, complete every plan item, and close with my.run/complete reporting the exact test result. Do not edit repository files.
```

The task capture is 34,955 characters and 19 ordered contributions whose
recorded costs sum to **11,479**. DeepSeek reported **11,476** prompt tokens:
a three-token difference after the opening sample calibrated the model. Its 39
shape-cost facts sum to 10,887:

| Shape | Calls | Sum |
|---|---:|---:|
| `:seon.cluster/cluster` | 1 | 26 |
| `:seon.cluster.instruction/instruction` | 1 | 198 |
| `:seon.cluster.message/message` | 4 | 361 |
| `:seon.cluster.run/run` | 2 | 73 |
| `:seon.config/entity` | 1 | 56 |
| `:seon.ns/ns` | 22 | 9,788 |
| `:seon.schema/value` | 9 | 385 |

### Verbatim terminal evidence

DeepSeek's whole 97-token completion was prose:

```text
I'll start by understanding my plan and checking the current state. Let me look at what I need to do:

1. Author a plan with items using the NEW `:my.plan.item/about` plain-vector token shape
2. Define `sum-of-squares` function with Malli contract
3. Define and run tests through `seon.test/run`
4. Complete all plan items and report results

Let me first check my current state and any messages:
```

The durable error is:

```clojure
{:seon.error/id "4b93a1bf-e56e-4a33-befe-1882cfda474f"
 :seon.error/kind :seon.cluster.reply/no-forms
 :seon.error/message
 "The reply carried no Clojure forms — its whole text read as prose. Prose runs nothing and settles nothing; write the Clojure you want evaluated."
 :seon.error/basis-t 536871186}
```

The task run has zero frozen forms and zero receipts, and closed with that
message. A complete agent/run query after settlement found no open work and no
correction run. There are zero plan items, `:about` refs, durable function
rows, or test rows for the agent.

### Eight measurement families

1. **Opening composition:** fully measured above: 10,635 whole-prompt tokens,
   36 real per-shape cost facts totaling 10,582, 17 ordered contribution
   costs, all closure and zero beyond-closure.
2. **Live pull:** 214.330 s, 11 generated entries, an inferred 12
   `next-entry` decisions, then 36 recorded opening-prompt render calls.
3. **Delta economics:** unavailable. The required unrelated message was not
   sent after the terminal no-forms boundary. There are two full captures
   (opening and task) but no mid-session delta package to compare honestly.
4. **Rebirth in vivo:** unavailable. No rebirth was triggered after the task
   failed before authoring any durable session state.
5. **Context behavior:** the opening model used `dir`; the task model correctly
   named all four newest obligations in prose, showing that stale-task removal
   worked, but emitted no executable form. It used neither `doc` nor `dir` on
   the task turn, did not operate `my.plan`, and installed nothing.
6. **Cost:** two direct `deepseek-v4-flash` attempts, no failover and no cache
   hits. Usage totals are 22,658 uncached prompt tokens and 396 completion
   tokens. At $0.14/M input and $0.28/M output, spend is **$0.003283**. Both
   attempts occurred in the declared 10:00–24:00 UTC off-peak window at its
   regular 1.0 factor.
7. **Basis-`t` comprehension:** the exact task prompt contains zero `t=` lines,
   so anchoring behavior remains unobservable rather than wrong.
8. **Planner omission case:** the denominator is zero authored items. No item
   text omitted `:about`, but no adoption frequency can be calculated.

### Stop ruling

The provider was healthy and the prompt fidelity repair worked; the new
boundary is model output shape. A prose-only reply creates no executable
continuation, and the settled database exposes no correction work. Sending a
second driver message would be a retry beyond the specified task turn and
would erase the clean falsifier. The cluster and every fact are retained.

## Attempt 4 verdict

**Web UI: [http://127.0.0.1:51063](http://127.0.0.1:51063)**

**Stopped after the first paid turn followed the generated opening's stale
task instead of the driver's task.** The opening itself completed cleanly for
the first time: 12 generated forms and 12 healthy receipts settled in
208.899 seconds. The subsequent prompt contained both the real task and the
opening's `largest` task, with the latter present twice. DeepSeek replayed
`largest`, made no plan, did not define `sum-of-squares`, and exhausted its
reply without `my.run/complete`. Per the drive stop rule, no unrelated message
or rebirth was attempted after that terminal boundary.

### Attempt 4 live endpoint and retained root

- Fresh isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Git HEAD: `a2da91591`
- Published `:current-src` commit:
  `6a7ee69e-0be7-5bf0-8b29-3d67ac768bbd`
- Publication inventory: 232 source inputs, 2,318 schemas, 823 functions,
  7,170 contract rows, 535 namespaces
- Agent: `drive-one-agent-attempt-4`
- Namespace: `my.agents.drive-one-agent-attempt-4`
- Generated run: `bootstrap:drive-one-agent-attempt-4`
- Paid run: `6c5dad44-e55a-4184-b4bb-0cf07a6b8764`
- Prompt capture: `6c5dad44-e55a-4184-b4bb-0cf07a6b8764-context-536871046`
- Final observed basis: `t=536871058`
- Preservation: the operator was neither stopped nor reset. Its cluster,
  opening receipts, exact prompt, paid attempt, reply forms, and errors remain
  live.

The isolated cluster health probe was degraded throughout: it returned the
typed `:seon.config/missing-projection` error instead of runtime health. JVM
evaluation remained functional. The report-only path restriction prevented a
separate issue note.

### Attempt 4 headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Agent creation to opening close | **208.899 s** |
| Generated forms / durable receipts | **12 / 12** |
| Opening source / result-face estimate | **339 / 4,437 tokens** |
| Opening episode estimate proxy | **4,776 tokens** |
| Beyond-closure intent membership | **0 tokens** |
| Stored `:seon.render.cost` rows | **0** |
| Paid prompt estimate / provider tokens | **22,620 / 22,604** |
| Prompt cache hit / miss tokens | **11,136 / 11,468** |
| Completion tokens | **310** |
| Paid-turn terminal latency | **8.063 s** |
| Provider attempts | **1** (`deepseek-v4-flash`) |
| Provider spend | **$0.0017235008** |
| Authored plan items | **0** |
| Requested durable functions / tests installed | **0 / 0** |
| Mid-session delta messages / rebirths | **0 / 0** |

Creation was committed at `2026-08-14T09:58:54.642Z`; the generated run
closed at `10:02:23.541Z`. Its receipt read bases advanced from
`t=536870953` at ordinal 0 to `t=536871026` at ordinal 11. The task message
was committed as `inbound-536871044-0` at `10:02:58Z`; its run closed at
`10:03:13Z`.

### Opening composition and pull economics

There were no ready `my.plan` subjects before the opening, so the
beyond-closure admission path contributed zero. All 12 entries came from the
ordinary generated-opening closure. The required `:seon.render.cost` family
was installed but had **no entities at all**, globally or for this agent, so
the promised authoritative per-entry cost measurement is unavailable. The
following is the explicitly weaker proxy derived from the stored source and
result strings with the shipped `seon.ai.tokens/estimate` function:

| Ordinal | Entry shape | Source | Result |
|---:|---|---:|---:|
| 0 | `(help)` | 21 | 527 |
| 1 | `(dir my.run)` | 3 | 117 |
| 2 | explained `(dir my.run)` | 25 | 117 |
| 3 | explained `(dir my.run)` | 30 | 117 |
| 4 | explained `(dir my.run)` | 22 | 117 |
| 5 | explained `(dir my.run)` | 42 | 117 |
| 6 | explained `(dir my.run)` | 23 | 117 |
| 7 | `(dir seon.db)` | 4 | 666 |
| 8 | `(dir my.message)` | 5 | 95 |
| 9 | agent-namespace `dir` | 12 | 1,100 |
| 10 | `(dir seon.bootstrap)` | 6 | 247 |
| 11 | stale `largest` task + schema `dir` | 146 | 1,100 |

The fact-derived 12-entry prefix implies 13 live pull decisions—one for each
settled entry and one terminal no-next-entry decision—from the documented
`next-entry` mechanism. There is no durable call-count fact, so 13 is a
mechanical inference rather than a recorded counter. Wall time is the exact
fact timestamp difference above.

### Verbatim stale opening context

Opening ordinal 11 was:

```clojure
; I can see there's a task waiting for me. Let me understand what's needed:

; 1. Define a durable contracted function named `largest` that returns the row with the greatest `:example/amount`, or `{}` for empty input
; 2. Call it once
; 3. Query its stored `:seon.fn/spec`
; 4. Complete with a short reply naming what I built and its contract

; Let me first check the schemas available for the examples and understand the data structures.

(dir (quote seon.schema.edn))
```

The paid prompt contained this line **twice**:

```text
From outside this cluster to drive-one-agent-attempt-4: Define a durable contracted function named largest that returns the row with the greatest :example/amount, or {} for empty input. Call it once, query its stored :seon.fn/spec, then complete with a short reply naming what you built and its contract.
```

It also contained the actual newest message once:

```text
From outside this cluster to drive-one-agent-attempt-4: Author and follow one my.plan for this task. Every authored item must use the NEW :my.plan.item/about shape: a plain vector mixing quoted qualified function symbols and namespaced keywords, targeting the actual functions and schema attributes you will use. Define a durable contracted function sum-of-squares in your namespace with a complete Malli contract, define a discoverable clojure.test usage test, run it through seon.test/run, complete every plan item, and close with my.run/complete reporting the exact test result. Do not edit repository files.
```

The exact captured prompt was 68,905 characters. Its one durable `walk`
contribution estimated 22,620 tokens; DeepSeek reported 22,604 prompt tokens.

### Verbatim paid reply and terminal evidence

DeepSeek's first form replayed the old objective:

```clojure
; Looking at this task, I need to:
; 1. Define a durable contracted function named `largest` that returns the row with the greatest `:example/amount`, or `{}` for empty input
; 2. Call it once
; 3. Query its stored `:seon.fn/spec`
; 4. Complete with a short reply

; Let me start by defining the function with the proper Malli schema:

(defn largest
  "Return the row with the greatest :example/amount, or {} for empty input."
  {:malli/schema [:=> [:cat [:sequential [:map [:example/amount :int]]]] [:or :map {}]]}
  [rows]
  (if (empty? rows)
    {}
    (apply max-key :example/amount rows)))
```

The remaining reply forms were:

```clojure
; Now let me call it once to verify it works:
(largest [{:example/amount 10} {:example/amount 20} {:example/amount 15}])

; Now let me query its stored `:seon.fn/spec`:
(db/pull db '[*] [:seon.fn/sym "largest"])

; Let me look up how to query the function spec. I should check what the directory shows for my namespace first:
(dir (quote my.agents.drive-one-agent-attempt-4))
; Let me check the seon.db functions to understand how to query the spec properly:
```

The first result was unreadable through the shipped fact reader and no
`:seon.fn` row was installed. The next three receipt values were typed errors:

```clojure
[:seon.sci.eval/evaluation-failed "Unable to resolve symbol: largest"]
[:seon.sci.eval/evaluation-failed "Unable to resolve symbol: db/pull"]
[:seon.sci.eval/evaluation-failed "No namespace: my.agents.drive-one-agent-attempt-4 found"]
```

The run itself has no run-level error; it simply closed after the reply was
exhausted. `seon.eval.drive/terminal-state` therefore derived `:stopped`, not
`:completed`. There are zero function rows, zero test rows, and zero
`my.plan.item` rows for this agent.

### Eight measurement families

1. **Opening composition:** 12/12 healthy generated entries, 208.899 s;
   4,776-token stored-string proxy, all closure and zero beyond-closure.
   Authoritative per-entry cost facts were absent, which is itself the
   measured result.
2. **Live pull:** 208.899 s and an inferred 13 pull decisions. No durable call
   counter exists, so a measured function-call attribution comparison cannot
   be made.
3. **Delta economics:** unavailable. The stop occurred on the first paid turn,
   before the required unrelated mid-session message. There is one full
   capture only, so no honest delta/full comparison exists.
4. **Rebirth in vivo:** unavailable. No rebirth was triggered after the
   terminal wrong-objective boundary; lived/reborn tokens and survival cannot
   be measured.
5. **Context behavior:** the agent replayed the demonstrated stale `largest`
   objective and its shown forms. It used `dir` in the opening and reply, never
   used `doc`, never operated `my.plan`, and did not follow the newest task.
6. **Cost:** one direct DeepSeek Flash attempt, no failover. Usage was 11,136
   cached input, 11,468 uncached input, and 310 output tokens. At the shipped
   $0.0028/$0.14/$0.28 per million cached-input/uncached-input/output prices,
   the total is **$0.0017235008**. The attempt began at 10:02:58Z, inside the
   declared 10:00–24:00 UTC off-peak window whose regular factor is 1.0.
7. **Basis-`t` comprehension:** the capture contained **zero `t=` display
   lines**. The agent therefore could not anchor on a shown basis value; this
   family is unobservable rather than a comprehension failure.
8. **Planner omission case:** zero authored items makes the denominator zero.
   There are no text-named subjects with omitted `:about`, but the omission
   frequency is **not measurable** from this attempt.

### Stop ruling

Continuing with the unrelated message or manufacturing a rebirth would have
measured a session already following the wrong durable objective. The defect
boundary is exact: the first real prompt preserved an obsolete bootstrap task
twice, and the live model selected it over the newest driver message. The
root and every receipt are retained for diagnosis.

## Attempt 3 verdict

**Stopped at generated-opening prefix drift after one healthy receipt.**
Attempt 2's codec failure is gone: the stored receipt and its 20 read-evidence
components read successfully. The next generated-opening derivation instead
refused because the already-settled `(help)` source was outside the new pull.

The same failure occurred for `bootstrap:root` and the actual Drive 1 agent.
Per the live-drive spec, no paid task message, mid-session message, or rebirth
was attempted after this terminal boundary.

### Attempt 3 live endpoint and retained root

**Web UI: [http://127.0.0.1:64767](http://127.0.0.1:64767)**

- Fresh isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Git HEAD: `eead437ed44c4754bd91deea9388953661058398`
- Published `:current-src` commit:
  `6a7eda81-fac7-5f08-a592-ec20ebe758db`
- Publication inventory: 232 source inputs, 2,318 schemas, 823 functions,
  7,170 contract rows, 535 namespaces
- Agent: `drive-one-agent-attempt-3`
- Namespace: `my.agents.drive-one-agent-attempt-3`
- Generated run: `bootstrap:drive-one-agent-attempt-3`
- Opening commit ID:
  `6a7eda81-fac7-5f08-a592-ec20ebe758db`
- Opening settlement basis: `t=536870972`
- Fault basis: `t=536870971`
- Preservation: the root was neither stopped nor reset after the attempt.
  Its cluster, run, receipt, read evidence, error fact, blobs, and logs remain
  live.

The previous Attempt 2 root was already stopped, then removed before this
fresh publication as directed. Its report remains in Git history.

### Attempt 3 headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Creation through generated-opening observation | **13.838 s** |
| Generated forms / durable receipts | **1 / 1** |
| Healthy generated receipts | **1** |
| Receipt read-evidence components | **20** |
| Provider attempts | **0** |
| Context captures | **0** |
| Recorded `:seon.render.cost` rows | **0** |
| Provider spend | **$0.00** |
| Driver task messages | **0** |
| Authored plan items | **0** |
| Rebirths | **0** |

Agent creation began at `2026-08-14T09:07:23Z`; the healthy receipt was
recorded during that second; the generated run closed at `09:07:26Z`; the
bounded observer returned at `09:07:36Z`. The full driver-observed interval
was **13.838 seconds**.

### Verbatim generated entry and value

The only stored generated source was:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

Its receipt has no `:seon.cluster.eval/error` and no `:seon.error/kind`.
The admitted value was:

```clojure
{:seon.cluster.agent/id "drive-one-agent-attempt-3"
 :seon.cluster.agent/namespace-ref
 [:seon.ns/name my.agents.drive-one-agent-attempt-3]
 :seon.cluster.agent/unread-message-count 0
 :seon.cluster.run/turns-remaining 99
 :seon.cluster.agent/protocol-namespaces
 [my.message my.run seon.bootstrap seon.db]
 :seon.cluster.agent/open-run-ref
 [:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-3"]
 :seon.cluster.run/trigger
 [:seon.cluster.message/id "bootstrap-task:drive-one-agent-attempt-3"]}
```

The receipt read at `t=536870970` and owns 20 readable read-evidence
component rows. It has no recorded `:seon.eval/fn-entries` or
`:seon.eval/allocated-bytes` fact.

### Attempt 3 typed stop evidence

The generated run closed with:

```clojure
{:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-3"
 :seon.cluster.run/opened-at #inst "2026-08-14T09:07:23.000-00:00"
 :seon.cluster.run/closed-at #inst "2026-08-14T09:07:26.000-00:00"
 :seon.cluster.run/error "A stored generated form is outside the pull."}
```

The total fault committer recorded error fact
`901d4b0d-c5cf-4573-b1c2-1e87d993df9b`:

```clojure
{:seon.error/kind :seon.bootstrap/prefix-drift
 :seon.error/message "A stored generated form is outside the pull."
 :seon.error/basis-t 536870971
 :seon.cluster.loop/phase-failed true
 :seon.bootstrap/prefix-drift true
 :seon.cluster.run/id "bootstrap:drive-one-agent-attempt-3"
 :seon.cluster.run.form/source
 "; A new run just opened. Why am I awake — do I have messages?\n(help)"}
```

`bootstrap:root` independently recorded one healthy form/receipt and then
closed with the same message. Its run opened at `09:06:25Z` and closed at
`09:06:32Z`. The Drive 1 agent reproduction makes the boundary independent
of root's special identity.

### Attempt 3 measurement families

#### 1. Opening composition

The full opening did not generate. One source/value entry settled, but the
fresh database contains zero context captures and zero
`:seon.render.cost` facts. Total opening tokens, explained-closure tokens,
beyond-closure tokens, and per-entry shape/cost are **unavailable**, not zero.
The codec now preserves the receipt; prefix validation refuses its successor
pull.

#### 2. Live pull

Named conditions: fresh isolated root at the publication above, shipped
`deepseek-v4-flash` with thinking disabled, one new agent, generated
`(help)`, 30,000 ms eval limit, receipt read basis `t=536870970`.
Creation-to-observer wall time was **13.838 s**. The pull terminated rather
than wedging. It read 20 evidence components successfully, proving the Attempt
2 codec boundary dead. No `:seon.eval/fn-entries` fact exists, so comparison
with the attribution baseline remains unavailable.

#### 3. Delta economics

**Not reached.** No task or unrelated mid-session message was sent, so no
delta or full-rerender comparison exists.

#### 4. Rebirth in vivo

**Not reached.** Rebirth after an incomplete generated opening would measure a
different session. Lived/reborn tokens, determinism, and behavior survival are
unavailable.

#### 5. Agent behavior against context

No model turn occurred. The system executed `(help)`; the agent had no
opportunity to replay forms, use `doc`/`dir`, follow a demonstration,
operate `my.plan`, or anchor a temporal form.

#### 6. Provider cost

There are zero `:seon.ai.attempt/run` facts and zero context captures.
Provider spend is **$0.00**. Neither DeepSeek nor failover was called.

#### 7. Basis-`t` comprehension

**Not reached.** The receipt records `t=536870970`, but no model prompt was
captured and no agent interpreted a rendered basis tag.

#### 8. Planner text without structured `:about`

**Not reached.** There are zero authored `my.plan.item` entities. Frequency
has denominator zero and is unavailable rather than 0%.

### Attempt 3 probe hygiene

One pre-agent root-health query used the wrong nested scalar binding and
returned a typed `:seon.db/invalid-read` without transacting. Three simple
scalar queries then measured the root run correctly. This diagnostic occurred
after root had already closed and cannot account for either generated-opening
failure.

### Attempt 3 stop boundary

```text
fresh init and start
→ root: healthy (help) receipt, then prefix drift
→ Drive 1 agent creation
→ generated opening form 0: healthy (help) receipt
→ next pull excludes the stored generated source
→ durable :seon.bootstrap/prefix-drift error fact
→ generated run closes with error
→ STOP before any paid message
```

Continuing would have bypassed the generated-opening prefix contract and
invalidated the requested evolve→rebirth→continue drive.

## Attempt 2 verdict

**Stopped while the generated opening advanced beyond its first healthy
receipt.** The fresh cluster and Drive 1 agent both booted, and the generated
`(help)` form evaluated successfully. Generation then closed the run with a
typed `:seon.db/invalid-read`: one captured call-preparation query's
`:seon.db/read-request` was not canonical EDN when the next opening pull read
the receipt.

Per the live-drive spec's terminal-defect rule, no paid task message,
mid-session message, or rebirth was attempted. This is Attempt 2's clean stop
boundary, distinct from Attempt 1's now-fixed advisory contract failure.

### Live endpoint and retained root

**Web UI: [http://127.0.0.1:62886](http://127.0.0.1:62886)**

- Fresh isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Git HEAD: `fe73d8922eb7065786a7a20e519aedaa6f289c53`
- Published `:current-src` commit:
  `6a7ed1ce-9551-514e-94ca-74d2586341e9`
- Publication inventory: 232 source inputs, 2,318 schemas, 823 functions,
  7,169 contract rows, 535 namespaces
- Agent: `drive-one-agent-attempt-2`
- Namespace: `my.agents.drive-one-agent-attempt-2`
- Generated run: `bootstrap:drive-one-agent-attempt-2`
- Opening commit ID:
  `6a7ed1ce-9551-514e-94ca-74d2586341e9`
- Opening settlement basis: `t=536870969`
- Fault observation basis: `t=536870968`
- Preservation: the root was neither stopped nor reset after the attempt.
  Its cluster, episode facts, receipt, read-evidence components, error fact,
  blobs, and logs remain live.

The previous Attempt 1 root was stopped through the operator and removed before
this fresh publication, exactly as directed. Attempt 1's evidence remains in
the prior report commit.

### Attempt 2 headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Creation through generated-opening observation | **9.740 s** |
| Generated forms / durable receipts | **1 / 1** |
| Healthy generated receipts | **1** |
| Receipt read-evidence components | **20** |
| Provider attempts | **0** |
| Context captures | **0** |
| Recorded `:seon.render.cost` rows | **0** |
| Provider spend | **$0.00** |
| Driver task messages | **0** |
| Authored plan items | **0** |
| Rebirths | **0** |

Agent creation began at `2026-08-14T08:30:38Z`; the healthy receipt was
recorded at `08:30:39Z`; the generated run closed at `08:30:42Z`; the
bounded observer returned at `08:30:48Z`. The complete driver-observed wall
interval was **9.740 seconds**.

### Verbatim generated entry and value

The sole stored generated source was:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

Its receipt had no `:seon.cluster.eval/error` and no `:seon.error/kind`.
The admitted result, rendered from the stored print face, was:

```clojure
{:seon.cluster.agent/id "drive-one-agent-attempt-2"
 :seon.cluster.agent/namespace-ref
 [:seon.ns/name my.agents.drive-one-agent-attempt-2]
 :seon.cluster.agent/unread-message-count 0
 :seon.cluster.run/turns-remaining 99
 :seon.cluster.agent/protocol-namespaces
 [my.message my.run seon.bootstrap seon.db]
 :seon.cluster.agent/open-run-ref
 [:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-2"]
 :seon.cluster.run/trigger
 [:seon.cluster.message/id "bootstrap-task:drive-one-agent-attempt-2"]}
```

The receipt read at `t=536870967` and owns 20 read-evidence component rows.
It has no recorded `:seon.eval/fn-entries` or
`:seon.eval/allocated-bytes` fact.

### Typed stop evidence

The generated run closed with:

```clojure
{:seon.cluster.run/id "bootstrap:drive-one-agent-attempt-2"
 :seon.cluster.run/opened-at #inst "2026-08-14T08:30:38.000-00:00"
 :seon.cluster.run/closed-at #inst "2026-08-14T08:30:42.000-00:00"
 :seon.cluster.run/error
 "The EDN-backed attribute :seon.db/read-request has an invalid logical value."}
```

The total fault committer recorded error fact
`9f54bedb-7424-4857-b862-7fc4a8ab36c2`:

```clojure
{:seon.error/kind :seon.db/invalid-read
 :seon.error/message
 "The EDN-backed attribute :seon.db/read-request has an invalid logical value."
 :seon.error/basis-t 536870968
 :seon.db/operation :datahike.pull/result
 :seon.schema.datahike/rule :seon.schema.datahike/noncanonical-edn
 :seon.schema.datahike/attr :seon.db/read-request}
```

The offending stored logical value, verbatim from
`:seon.error/data-edn`, was:

```clojure
#:seon.db{:read-operation :q,
          :query-request
          {:query
           {:find [?key ?schema-key ?fingerprint ?supplier],
            :in [$],
            :where
            [[?row :seon.call-preparation/key ?key]
             [?row :seon.call-preparation/schema ?schema]
             [?schema :seon.schema/key ?schema-key]
             [?schema :seon.schema/shape ?shape]
             [?shape :seon.schema.shape/fingerprint ?fingerprint]
             [?row :seon.call-preparation/supplier ?function]
             [?function :seon.fn/sym ?supplier]]},
           :args [:seon.db/database]}}
```

`bootstrap:root` independently exhibited the same shape before agent
creation: one healthy receipt followed by the same run error. The Drive 1
agent reproduction establishes that the defect is not root-specific.

### Attempt 2 measurement families

#### 1. Opening composition

The complete opening was not generated. One source/value entry settled, but
there are zero context captures and zero `:seon.render.cost` facts in the
fresh database. Total opening tokens, explained-closure tokens,
beyond-closure tokens, and per-entry shape/cost are therefore **unavailable**,
not zero. The one entry reached `help`; generation failed while deriving its
successor.

#### 2. Live pull

Named conditions: fresh isolated root at the published commit above, shipped
`deepseek-v4-flash` with thinking disabled, one new agent, opening
`(help)`, 30,000 ms eval limit, receipt read basis `t=536870967`.
Creation-to-observer wall time was **9.740 s**. The pull terminated rather than
wedging. Its receipt contains 20 read-evidence components, but no
`:seon.eval/fn-entries` fact, so a call-count comparison with the attribution
baseline is unavailable.

#### 3. Delta economics

**Not reached.** No task or mid-session message was sent, so no delta or
counterfactual full rerender exists.

#### 4. Rebirth in vivo

**Not reached.** A rebirth after a terminally incomplete generated opening
would be a different experiment. Lived/reborn tokens, determinism, and
post-rebirth behavior are unavailable.

#### 5. Agent behavior against context

No model turn occurred. The system-generated `(help)` form executed, but the
agent did not replay shown forms, use `doc`/`dir`, follow a first-use
demonstration, operate `my.plan`, or anchor a temporal form. These are
unobserved opportunities, not negative model decisions.

#### 6. Provider cost

There are zero `:seon.ai.attempt/run` facts and zero context captures for
the run. Total provider spend is therefore **$0.00**. Neither the primary
DeepSeek route nor the configured failover was called.

#### 7. Basis-`t` comprehension

**Not reached.** The receipt records `t=536870967`, but no model prompt was
captured and no agent could interpret or reuse the basis display.

#### 8. Planner text without structured `:about`

**Not reached.** There are zero authored `my.plan.item` entities. The
frequency has denominator zero and is unavailable rather than 0%.

### Attempt 2 stop boundary

```text
fresh init and start
→ system root opening: healthy (help), then invalid read
→ Drive 1 agent creation
→ generated opening form 0: healthy (help) receipt
→ next-entry pull decodes noncanonical :seon.db/read-request
→ durable :seon.db/invalid-read error fact
→ generated run closes with error
→ STOP before any paid message
```

Continuing would have bypassed the generated-opening contract and invalidated
the requested evolve→rebirth→continue measurement.

### Attempt 2 codec resolution — 2026-08-14

This is the **same database-codec class** as
[`wildcard-receipt-pull-refuses-a-stored-dependency-plan.md`](../../../seon/issues/archive/wildcard-receipt-pull-refuses-a-stored-dependency-plan.md),
not a separate `:seon.db/read-request` defect. Both that attribute and
`:datahike.read/dependency-plan` select the heterogeneous-union EDN fallback in
`seon.schema.datahike`. Its write seam used ambient `pr-str`, so
`*print-namespace-maps*` and map/set iteration order could change durable bytes;
the strict reader then correctly refused a stored string unequal to its
canonical re-encoding. That was a two-sided contract break created by the
writer, not a reason to weaken the reader.

Commit `8ec96cbf1` fixes the one canonicalization seam: recursively ordered map
and set data is printed under one complete readable binding policy. The strict
noncanonical refusal remains unchanged. The production-database regression
round-trips the exact captured call-preparation query plus a dependency plan
through `seon.db/transact!` and wildcard `seon.db/pull`, under opposite
namespace-map bindings and opposite construction/iteration orders. The test
failed six assertions before the fix; afterward the focused gate passed **9
tests / 101 assertions / 0 failures / 0 errors**. Commit `17787d4a6` archives
the single issue note with that evidence.

A fresh isolated root at `tmp/codec-live-root`, published as current-source
commit `6a7ed89d-557a-531a-b9a2-bfab87b3b2a2`, supplied the live boundary proof.
With `*print-namespace-maps*` forced false, wildcard-pulling the real ordinal-0
`bootstrap:root` receipt returned an ordinary value: receipt entity `29971`,
23 read-evidence components, 22 decoded `:seon.db/read-request` values, 23
decoded `:datahike.read/dependency-plan` values, and no `:seon.error/kind`.

The fixed opening then advanced past the codec boundary and exposed the
distinct existing prefix-drift failure, `"A stored generated form is outside
the pull."`, in `seon.bootstrap/next-entry`. That evidence belongs to the
already-open
[`generated-opening-live-pull-does-not-return-after-help.md`](../../../seon/issues/generated-opening-live-pull-does-not-return-after-help.md);
it is not part of the database-codec class and was not patched here.

## Attempt 1 verdict

**Stopped at the generated-opening gate.** The isolated cluster booted and
the Drive 1 agent was created, but its first generated form, `(help)`,
settled as a durable `:seon.instrument/contract-violated` receipt. The
opening run then closed with `:seon.cluster.run/error`. Per the live-drive
spec, no task message, unrelated mid-session message, provider turn, or
rebirth was attempted after this terminal opening failure.

This is a successful failed drive with clean evidence. The retained facts also
show that `bootstrap:root` failed with the same receipt error before the
Drive 1 agent was created. That makes the exact boundary the published
generated-opening evaluation path, not the contracted-function task.

## Retained live evidence

- Isolated operator root: `tmp/drive-1-root`
- Cluster: `default`
- Web UI: `http://127.0.0.1:54474`
- Operator start: successful; dependency cache current with 363 namespaces
- PID/generation at final status: `46196` /
  `66339cf1-b143-4a8b-9b6c-babc969f123a`
- Agent: `drive-one-agent`
- Namespace: `my.agents.drive-one-agent`
- Generated run: `bootstrap:drive-one-agent`
- Opening commit ID:
  `6a7ea9b9-e2bd-52e4-9ec9-4ec1e6de0770`
- Opening settlement basis: `t=536870968`
- Final evidence-query basis: `t=536870972`
- Preservation: the operator was not stopped or reset. The root, cluster,
  episode facts, receipts, blobs, and logs remain live after this report.

Final `bin/seon --root tmp/drive-1-root status`: 1/1 clusters alive,
0.13 GiB root footprint, no orphan Seon JVMs.

## Headline measurements

| Measurement | Fact-derived result |
|---|---:|
| Creation through generated-opening observation | **10.892 s** |
| Generated forms / durable receipts | **1 / 1** |
| Healthy generated forms | **0** |
| Opening provider attempts | **0** |
| Opening context captures | **0** |
| Recorded `:seon.render.cost` rows | **0** |
| Provider spend | **$0.00** |
| Inbound task messages sent by the driver | **0** |
| Rebirths | **0** |

Agent creation began at `2026-08-14T05:39:41Z`; the receipt was recorded at
`05:39:42Z`; the run closed at `05:39:45Z`; the bounded fact observer
returned at `05:39:52Z`. The wall measurement is deliberately the complete
driver-observed interval, not just receipt latency.

## Terminal facts

The generated run has these terminal facts:

```clojure
{:seon.cluster.run/id "bootstrap:drive-one-agent"
 :seon.cluster.work/situation :generate
 :seon.cluster.run/opened-at #inst "2026-08-14T05:39:41.000-00:00"
 :seon.cluster.run/closed-at #inst "2026-08-14T05:39:45.000-00:00"
 :seon.cluster.run/error
 "The EDN-backed attribute :seon.db/read-request has an invalid logical value."}
```

The only receipt has identity
`["bootstrap:drive-one-agent" 0]`, read basis `t=536870966`, result size
3,672 bytes, and:

```clojure
{:seon.error/kind :seon.instrument/contract-violated
 :seon.cluster.eval/error
 "seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type"}
```

The same fact pair already existed for `bootstrap:root`:

```text
receipt at 2026-08-14T05:38:04Z
run closed at 2026-08-14T05:38:08Z
seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type
The EDN-backed attribute :seon.db/read-request has an invalid logical value.
```

The publication at Git HEAD
`e569a89bedf8591d95a391cbc29404c07c23f166` instruments
`non-generatable-advisory` with input contract `[:cat :map]`.
The ordinary non-declaration evaluation path calls it with the evaluation's
program row. For the generated `(help)` form that row is absent. The durable
receipt proves the contract failure; this source observation explains the
boundary but is not substituted for the facts.

## Verbatim generated context sample

This is the exact sole generated form source stored on the run:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

Its exact stored receipt error is:

```text
seon.test.accretion/non-generatable-advisory violated its contract (invalid-input): invalid type
```

No later opening entry was generated, so there is no honest larger context
sample to quote.

## Required measurement families

### 1. Opening composition

The opening did not complete. There is one stored entry and one receipt, but
no context capture and no `:seon.render.cost` fact. Therefore total opening
tokens, explained-closure tokens, beyond-closure tokens, and per-entry
shape/cost are **unavailable**, not zero. The fact counts are: captures 0,
render-cost rows 0.

### 2. Live pull

Named conditions: isolated root, shipped publication, one newly created
agent, generated opening, `(help)` at read basis `t=536870966`.
Creation-to-observer wall time was **10.892 s**. The receipt contains no
`:seon.eval/fn-entries` fact, so call-count comparison against the
attribution baseline is unavailable. The live pull terminated with the
contract error above; it did not wedge.

### 3. Delta economics

**Not reached.** No driver task message or mid-session message was sent, so
there is no generated delta or counterfactual full rerender to compare.

### 4. Rebirth in vivo

**Not reached.** No rebirth was triggered after the terminal opening error.
Consequently lived/reborn tokens, determinism, and post-rebirth behavior are
unavailable.

### 5. Agent behavior against context

The agent never received a model turn. It did not replay shown forms, call
`doc` or `dir`, follow a first-use demonstration, operate `my.plan`, or
anchor a temporal form on `t=`. These are **not observed**, rather than
negative model choices.

### 6. Provider cost

There are **zero `:seon.ai.attempt/run` facts** for the generated run.
Total provider spend is therefore **$0.00**. No primary or failover provider
request occurred.

### 7. Basis-`t` comprehension

**Not reached.** Although the receipt durably records read basis
`t=536870966`, no model turn occurred and the agent had no opportunity to
interpret or reuse a rendered basis tag.

### 8. Planner text without structured `:about`

**Not reached.** No authored plan item exists. Frequency has denominator zero
and is reported as unavailable, not 0%.

## Driver probe hygiene

After settlement, one diagnostic pull mistakenly requested two undeclared run
attributes, `:seon.cluster.run/generated-at` and
`:seon.cluster.run/generation-complete-at`. It returned the expected typed
`:seon.db/invalid-read` value and did not transact. A later diagnostic also
called `seon.eval.drive/terminal-state` with the wrong arity; instrumentation
refused it before execution. Neither diagnostic altered the run or accounts
for the earlier receipt: both occurred after the run had closed, and
`bootstrap:root` already carried the same failure.

## Stop boundary

The exact boundary is:

```text
isolated operator start
→ agent creation transaction
→ generated opening form 0: (help)
→ durable contract-violation receipt
→ generated run closes with error
→ STOP
```

Continuing would have converted a terminally failed opening into a different
experiment and violated the drive spec's evidence-preserving stop rule.
