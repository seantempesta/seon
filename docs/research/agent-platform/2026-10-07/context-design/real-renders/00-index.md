---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# Real renders for the first-run task `seon.db/the-query-wrapper-costs-36x-datahike`

For every reference in the task, its entity's declared `:seon.render/ai` function was called **explicitly** from
agent `context-mockups`'s SCI context on default (installed 42e569d9d). Each file shows the reply exactly as the agent
saw it, because the reply passes the result through the value printer.

Sizes are estimated tokens (characters / 4) of the shown reply. "Whole" is the value printer's own `value-tokens`
when it cut the value.

| # | Reference | Kind | Render function | Shown | Whole | Time, armed calls | Verdict |
|---|---|---|---|---:|---:|---|---|
| [01](01-task.md) | `seon.db/the-query-wrapper-costs-36x-datahike` | task | `seon.render.ns/task-ai` (plus the reply's related walk) | 331 | 331 | 3 ms, 128 | Usable shape; the proof state is wrong from the agent's branch; repeats `seon.db/q`; no proofs key |
| [02](02-namespace-seon.db.md) | `seon.db` | namespace | `seon.render.ns/render-ai` | 802 | 4,132 | 166 ms, 18,883 | Not useful for this task: alphabetical public `dir`, `q` and the private subjects never shown; doc cut mid-sentence |
| [03](03-function-seon.db.q.md) | `seon.db/q` | function | `seon.render.ns/function-ai` | 745 | 1,517 | 0 ms, 102 | The right content, unreadable form: code as escaped strings, packed several per line, cut before the line that matters |
| [04](04-private-functions.md) | `seon.db/query-input-values`, `query-patterns`, `parsed-nodes` | private functions | `seon.render.ns/function-ai` | 260 | 260 | 0 ms, 70-81 each | Small enough to read; missing callers, which is what the task is about |
| [05](05-test-proof.md) | `seon.db-overhead-test/query-preparation-has-bounded-armed-work` | proof test | `seon.render.test/render-ai` | 73 | 73 | 72 ms, 5,220 | Says "pass" and nothing of what it asserts |
| [06](06-schemas.md) | `:seon.db/query`, `:seon.db/query-args`, `:seon.db/invalid-read-error` | schema keys | `seon.render.schema/render-ai` | 517 | 517 | 31-65 ms, ~365 each | The best render: definition as data plus usage |
| [07](07-unrendered.md) | `d/q`, `cefd8a2bf`, 2 evidence paths, bare private names | no declared render | none | n/a | n/a | n/a | Name once as data or drop |

Total, every reference rendered once as shown: about **2,730 tokens**; with the namespace and `seon.db/q` whole,
about 6,800.

## Where the value printer's missing fixes hurt (lane source-display, in flight)

- Source as `source-lines` strings is packed several per output line with every quote escaped (03, 04). A multi-line
  string printed as a code block would make `function-ai` return the source whole as one string.
- Short strings are cut: doc lines at 32 or 64 characters in 02, and in other probes a 33-character keyword string
  was cut by one character (task `seon.render.value/a-short-identifier-string-is-cut-at-its-first-chunk`).
- Small vectors are hidden behind dig forms: `[[3 0 0]]` became `…1 (drop 1 …)` in the candidate scan. Small values
  should print whole.

None of these were worked around here: every file shows the installed printer's output.

## Repeated across renders

- `seon.db/q`'s doc line appears 3 times: in the task's related `_tasks`, its related `must-read`, and 03.
- `seon.db/q`'s docstring appears twice inside 03 (as `:seon.fn/doc` and in the source).
- Contracts appear in the source string (03) and again as the schema definitions (06). This is acceptable, because 06
  is the resolved form.

## The brief this implies (one render per reference, deduped)

task (intent, proofs with state, must-read names) → each named function's source as code with callers → each named
schema's definition → proof sources. The namespace appears only as a name. This is what the shape mockups use.
