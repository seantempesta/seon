---
type: research
title: Context ablation — why Attempt 5 replied without a form
date: 2026-08-14
status: complete
subject: agent reply medium, prompt recency, namespace documentation volume
---

# Context ablation — why Attempt 5 replied without a form

## Answer first

The teaching demonstration was **not broken in the agent-consumed prompt**.
The exact `/ai` capture contains the complete balanced fenced `greet` `defn`.
The mid-form cut visible in HTML is an HTML projection defect and cannot
explain this reply.

Weak recency is the clean causal flip. Appending one 123-character reminder at
the task tail produced one reader-accepted Clojure form in both calls (2/2).
The full prompt and the identical-byte complete-demo control produced no forms.

Namespace documentation volume also contributes. Reducing the namespace documentation span to 31.48% with no
tail reminder produced forms twice (2/2), but it was a worse intervention: the
first reply ran away into a very long repetitive survey, and the retained
repeat emitted 16 exploratory forms without completing the task. Volume
reduction improves the chance of entering the medium but does not state the
prose-only edge or discipline the next action.

Ranked implied fix:

1. **Repeat the medium contract at the final task boundary.** Use the tested
   line: `Your reply is read as forms and evaluated in your namespace. Prose
   alone runs nothing; include at least one Clojure form.` This is the smallest
   demonstrated fix and flipped 2/2.
2. **Enforce the existing namespace render budget and trim namespace documentation volume.**
   Keep task-relevant API/schema blocks rather than the whole directory wall.
   This cut billed prompt tokens from 11,476 to 4,188 in the retained V4 repeat,
   but it is secondary because its replies were noisy.
3. **Repair the HTML fence-splitting elision independently.** It is a real
   human-facing defect, but it did not alter the `/ai` bytes and is not an
   Attempt 5 form-emission fix.

`(help)` is descriptive enough to establish the medium when read: it states
that replies are forms and shows a complete form. Its teaching is incomplete
at the decisive edge—`Prose lines are kept as ;; comments` does not say that
prose *alone* carries no reader event—and it is roughly 33,200 characters away
from the task. The live failure is therefore recency first, volume second, not
a mangled `/ai` demonstration.

## Boundary and exact specimen

I read the complete Attempt 5 section of
`drive-1-observation-2026-08-14.md` and used only explicit-custody reads inside
the preserved specimen JVM (`tmp/drive-1-root`, cluster `default`, advertised
prepl 55155):

```clojure
(let [connection (seon.operator/connection "default")
      database @connection]
  (seon.db/pull database ...))
```

No elided database arity, `config/effective`, lifecycle operation, SCI
evaluation, or database transaction touched the specimen. Request assembly in
the live JVM required the explicit
`projection-from-database` → `sci.eval/projection-state` →
`schema/call-with-projection-state` pattern. The paid retained calls then ran
from `clojure -M:dev:test` through the one `seon.ai/complete` owner using the
captured primary target: `deepseek-v4-flash`, DeepSeek direct, thinking
disabled, no temperature field, 65,536 max output tokens, streaming usage, and
the shipped 180-second deadline.

Capture entity:

```text
a887d305-c8ae-4b6e-842f-43287f7f7496-context-536871133
```

Exact prompt:

| Fact | Value |
|---|---:|
| Characters | 34,955 |
| UTF-8 bytes | 34,962 |
| SHA-256 | `8422c3e18e05f59501eebe46f550f7c3f97f91d8a403a55b75df7b817e4b355b` |
| Teaching sentence begins | character 970 |
| Complete `(defn greet` begins | character 1,359 |
| Closing fence ends | character 1,469 |
| Task begins | character 34,197 |

The captured bytes around the suspected demonstration are:

```clojure
;; unqualified name — it lands in YOUR namespace
;; the :malli/schema attr-map is what makes it permanent; without it this is scratch
(defn greet
  "Say hello."
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str "Hello, " name))
```

The string `-- [name]` does not occur in the prompt. The prime suspect is
falsified: HTML elision did not cross into the AI capture.

The original sent body independently matched these facts: one user message,
`stream=true`, `thinking={"type":"disabled"}`, `max_tokens=65536`, no
temperature field, and its message content byte-equal to the capture.

## Variants and exact deltas

The committed runner is
`docs/prds/sci-execution-runtime/research/scripts/context_ablation_2026_08_14.clj`.
Every verdict below comes from `seon.cluster.reply/sources` in namespace
`my.agents.drive-one-agent-attempt-5`, never a regex or visual judgment.

| Variant | Exact delta | Chars / UTF-8 bytes | SHA-256 | Reader verdict |
|---|---|---:|---|---|
| V0 | Preserved exact task-turn capture; no new mutation | 34,955 / 34,962 | `8422c3e1…e4b355b` | no forms |
| V1 | Requested demo repair is a no-op because the capture already has the whole `defn`; identical-byte control | 34,955 / 34,962 | `8422c3e1…e4b355b` | no forms |
| V2 | Append exactly `\n\nYour reply is read as forms and evaluated in your namespace. Prose alone runs nothing; include at least one Clojure form.` | 35,078 / 35,085 | `29623272…52f9a4fa` | 1 form |
| V3 | Whole-demo repair plus reminder; because repair is a no-op, byte-identical to V2 | 35,078 / 35,085 | `29623272…52f9a4fa` | 1 form |
| V4 | Keep the complete prefix through the demo, `my.message`, `my.plan`, the agent namespace, `seon.db`, and the exact task suffix; delete the other namespace blocks | 12,921 / 12,926 | `1e16b130…1ea7e884` | 16 forms in retained repeat; forms in first noisy call too |

V4's original namespace documentation interval was 32,159 characters. It retained 10,125
characters (31.484%). The task-turn suffix is byte-identical.

## Results and raw replies

### V0 — exact preserved baseline: no forms

Provider usage: 11,476 prompt tokens, 97 completion tokens, cache hit 0,
`finish_reason=stop`.

```text
I'll start by understanding my plan and checking the current state. Let me look at what I need to do:

1. Author a plan with items using the NEW `:my.plan.item/about` plain-vector token shape
2. Define `sum-of-squares` function with Malli contract
3. Define and run tests through `seon.test/run`
4. Complete all plan items and report results

Let me first check my current state and any messages:
```

Production reader:

```clojure
{:seon.error/kind :seon.cluster.reply/no-forms}
```

I attempted one fresh V0 call. The MCP observer timed out at its own 120-second
cap after transmission and retained no completion or usage document. Because
the call may have been paid, I did not repeat it. The durable original attempt
is the V0 behavioral baseline above.

### V1 — complete-demo identical control: no forms

Provider usage: 11,476 prompt tokens (11,392 cache hit, 84 miss), 172
completion tokens, `finish_reason=stop`.

```text
I need to understand the task:
1. Create a plan with `:my.plan.item/about` using NEW plain-vector token shape
2. Define a durable `sum-of-squares` function with Malli contract
3. Create and run a test
4. Complete plan items and report results

Let me start by understanding the plan system and looking at how to use the NEW `:my.plan.item/about` shape.

Let me check the plan schema and message inbox first.

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="my.plan/plan">
<｜｜DSML｜｜parameter name="db" string="true">db</｜｜DSML｜｜parameter>
<｜｜DSML｜｜parameter name="agent-id" string="true">"drive-one-agent-attempt-5"</｜｜DSML｜｜parameter>
</｜｜DSML｜｜invoke>
</｜｜DSML｜｜tool_calls>
```

Production reader: `:seon.cluster.reply/no-forms`. The complete demonstration
does not prevent DeepSeek from narrating or emitting provider-style tool
markup instead of a Clojure form.

### V2 — tail reminder: one form

Provider usage: 11,503 prompt tokens (11,392 cache hit, 111 miss), 134
completion tokens, `finish_reason=stop`.

<pre>
```clojure
;; I am the agent drive-one-agent-attempt-5. Let me understand my task:
;; 1. Author and follow a my.plan for this task
;; 2. Define a durable contracted function sum-of-squares
;; 3. Define a discoverable clojure.test usage test
;; 4. Run it through seon.test/run
;; 5. Complete every plan item
;; 6. Close with my.run/complete reporting the exact test result

;; First, let me check my current plan and notes
(my.plan/plan db "drive-one-agent-attempt-5")
```
</pre>

Production reader: one source carrying the comments and
`(my.plan/plan db "drive-one-agent-attempt-5")`.

### V3 — no-op demo repair plus tail reminder: one form

Provider usage: 11,503 prompt tokens (11,392 cache hit, 111 miss), 128
completion tokens, `finish_reason=stop`.

<pre>
I'll start by examining the current state of my namespace and understanding the task. Let me plan this out.
```clojure
;; Plan: 
;; 1. Check current plan state
;; 2. Define the sum-of-squares function with Malli contract
;; 3. Define a clojure.test usage test
;; 4. Run the test through seon.test/run
;; 5. Complete plan items
;; 6. Close with my.run/complete

;; First, let me check my current plan
(my.plan/plan db "drive-one-agent-attempt-5")
```
</pre>

Production reader: one source. The leading prose was retained as a single-`;`
comment before the fenced form.

### V4 — namespace documentation trimmed to 31.48%: forms, but noisy

The first V4 call emitted reader-accepted forms but ran away into a repetitive
reply large enough to overflow the terminal evidence window. Its complete raw
text and usage were not retained, so it is used only as a boolean stability
observation and never for spend arithmetic. Under the assignment's one-repeat
allowance for a noisy decisive variant, the retained repeat returned 16 forms.

Retained-repeat usage: 4,188 prompt tokens (4,096 cache hit, 92 miss), 931
completion tokens, `finish_reason=stop`.

<pre>
I'll start by checking my current plan and understanding the task, then create a plan for completing it.

Let me first look at my plan namespace and understand the structure:

```clojure
(my.plan/plan db my.agents.drive-one-agent-attempt-5)
```

Wait, let me check what functions are available in my.plan more carefully, and also check if I have any existing messages or tasks.

Let me first check my current plan state:
```clojure
(my.plan/plan db "drive-one-agent-attempt-5")
```

Let me look at the task requirements:
1. Author and follow one my.plan for this task
2. Use NEW `:my.plan.item/about` plain-vector token shape
3. Define `sum-of-squares` function with Malli contract
4. Create clojure.test usage test
5. Run through seon.test/run
6. Complete every plan item
7. Close with my.run/complete

Let me first understand the `my.plan.item/about` shape by checking the schema:

```clojure
(seon.schema.edn/describe :my.plan.item/about)
```

Let me check what schema attributes are available:

```clojure
(dir :my.plan.item)
```

Let me look at how to use the plan functions properly. First, I need to understand the `my.plan.item/about` shape:

```clojure
(seon.schema.edn/describe :my.plan.item/add-request)
```

Let me check my current namespace and what I need to do. I'll create a plan, then execute it:

```clojure
;; First, let me define the sum-of-squares function
(defn sum-of-squares
  "Sum the squares of a collection of integers."
  {:malli/schema [:=> [:cat [:sequential :int]] :int]}
  [coll]
  (reduce + (map #(* % %) coll)))
```

Now, let me create a plan. First, I need to understand the plan structure:

```clojure
(my.plan/add! 
  {:my.plan.item/title "Define sum-of-squares function"
   :my.plan.item/about [:my.plan/sum-of-squares]
   :my.plan.item/after []}
  db
  "drive-one-agent-attempt-5")
```

Wait, I need to check what the actual schema for plan items looks like. Let me look at the my.plan namespace more carefully:

```clojure
(seon.schema.edn/describe :my.plan.item/item)
```

Let me look at what fields are needed for adding a plan item:

```clojure
(seon.schema.edn/describe :my.plan.item/add-request)
```

I should also check my inbox for any messages:

```clojure
(my.message/inbox db "drive-one-agent-attempt-5")
```

Let me proceed step by step. First, let me check what's in the my.plan schema to understand the exact shape needed.I'll start by understanding the plan schema and then create a proper plan for this task.

```clojure
(seon.schema.edn/describe :my.plan.item/item)
```

Let me check the schema for plan items and understand the about field structure:

```clojure
(seon.schema.edn/describe :my.plan.item/about)
```

Let me look at how to properly view the plan schema. Let me check if there's a way to see the full schema:

```clojure
(require '[seon.schema.edn :as schema.edn])
```

Actually, let me first look at what's in the my.plan namespace more completely by checking the schema definitions:

```clojure
(dir my.plan)
```

I can see the functions but need to understand the schemas. Let me pull the schema attributes:

```clojure
(db/pull db '[*] [:seon.schema/key :my.plan.item/about])
```

Let me look at an example of how plan items are structured by checking if there are any existing plan items:

```clojure
(db/q '[:find ?e ?title ?about
        :where [?e :my.plan.item/title ?title]
               [?e :my.plan.item/about ?about]]
      db)
```

Let me first check my inbox for any additional context:
</pre>

The production reader returned 16 sources. Several forms name unavailable or
incorrect calls; this experiment measures form emission, not task correctness.
The repeat nevertheless confirms the volume flip while showing why trimming is
not the first fix.

## Spend

No call returned 402. Six new provider requests were attempted: the
outer-timeout V0, V1, V2, V3, the noisy first V4, and the retained V4 repeat.

DeepSeek's official 2026-08-14 flash prices are $0.0028 per million cache-hit
input tokens, $0.14 per million cache-miss input tokens, and $0.28 per million
output tokens
([Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)).

| Retained usage document | Cost (USD) |
|---|---:|
| Preserved specimen V0 | $0.0016338000 |
| V1 | $0.0000918176 |
| V2 | $0.0000849576 |
| V3 | $0.0000832776 |
| V4 retained repeat | $0.0002850288 |
| **Known new-call floor** (V1–V4 retained) | **$0.0005450816** |
| **Known total including preserved baseline** | **$0.0021788816** |

An exact debit total is not recoverable because two transmitted calls lost
their usage envelopes at the observer boundary: the MCP-timed V0 and the first
noisy V4. Both are treated as potentially paid. With the observed input splits
and the configured 65,536-token output maximum applied to both missing calls,
the strict interval is:

```text
new experiment calls:  $0.0005450816 ≤ spend ≤ $0.0373132480
including specimen V0: $0.0021788816 ≤ spend ≤ $0.0389470480
```

The actual debit is plainly near the floor—the retained calls cost fractions
of a cent and the first V4 visibly ended below the configured maximum—but the
database/provider evidence does not support inventing a point estimate.

## Ruling impact and issue ownership

This experiment updated, without duplicating:

- `docs/seon/issues/no-forms-replies-close-without-correction-or-rewake.md` —
  adds the preventative 2/2 recency flip and task-tail acceptance.
- `docs/seon/issues/render-token-budgets-are-private-dials-no-producer-supplies.md`
  — adds the behavioral volume ablation and its noisy limitation.

The HTML fence-splitting defect remains real but is not convicted as an AI
prompt cause: the exact capture proves projection separation here.
