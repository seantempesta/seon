---
type: plan
status: design for the first live trials (owner 2026-09-16 03:30Z: "come up with many candidates; let's not assume we know the best way")
created: 2026-09-16
tags: [plan, steward, issue, context, trial]
---

# Issue context trials — one real issue, many renderings of its world

Owner: start with one issue; get an end-to-end live session; try different
ways to express the issue and teach the agent about the system through the
AI-rendered forms around its data; see if it solves a real issue by writing
code; do not assume the best way.

## 1. The issue

[documentation-arglists-with-auto-keywords-are-not-edn](../../../seon/issues/documentation-arglists-with-auto-keywords-are-not-edn.md):
`doc` and `dir` read the stored `:seon.fn/arglists` string with
`clojure.edn/read-string` (`src/seon/sci/eval.clj:1211`, `:1236`), which
throws on auto-resolved keywords such as `::text` in destructuring. Verified
real at HEAD. One file, one function pair, a clear red test, fixable by an
agent with `my.edit/form!` followed by the hook's adoption and `my.test/check`.
Two other candidates were stale and archived while selecting.

The red test the issue starts with (a `seon.test` entity admitted through
the agent graph, linked by `:seon.issue/tests`):

```clojure
(deftest documentation-reads-arglists-with-auto-resolved-keywords
  (let [value (seon.sci.eval/documentation-value (seon.db/db) 'seon.sci.reader/read 'seon.sci.reader/read)]
    (is (not (:seon.error/kind value)))
    (is (vector? (:arglists value)))))
```

Issue entity (authored with `my.issue/add!`, not indexed): title from the
note, `:seon.issue/problem` = the note's Problem section verbatim,
`:seon.issue/functions` = `seon.sci.eval/function-doc-map`,
`seon.sci.eval/directory-value`, `seon.sci.eval/documentation-value`,
`:seon.issue/tests` = the test above, budget 20.

## 2. What is held constant across candidates

Same issue entity, same red test, same worker namespace (`my.agents.<worker>`),
same model (the cheapest DeepSeek), same budget, same day's program. The
plan is authored by `start!` the same way each time (objective = problem,
one step per test). Only the issue's AI rendering and the units around it
differ. Each candidate is one AI render function selected by an agent
settings dial (`:seon.config.render/issue-opening`, an enum of the candidate
names) so the worker's settings overlay picks it; the schema pair symbol
stays one function that dispatches on the dial.

## 3. Candidates

| # | Name | What the issue block emits (comment + form), and the units around it |
|---|---|---|
| A | **bare** | `;; My issue. Make its tests pass.` + `(my.issue/status {:seon.issue/id …})`. Units: functions, tests. Nothing else. The floor. |
| B | **plan-first** | The plan block carries the steps (one per test); the issue block only names the problem and the exact calls: `(my.test/check {:seon.test/changed [...]})`, `(my.edit/form! …)`. Units: tests only; functions reachable by `doc`. |
| C | **evidence-first** | The failing test renders FIRST through the test pair (source, last result, failing assertions), then the function pair for each linked function (contract, docstring, `doc` form), then the problem text last. Units ordered tests, functions. |
| D | **walkthrough** | The issue block teaches by one worked example in the REPL grammar: read the test → `(doc seon.sci.eval/documentation-value)` → find the reader → `(my.edit/form! …)` with a digest → `(my.test/check …)` → observe green. Modeled on the bootstrap opening's `help`. Units: functions, tests. |
| E | **questions** | The block is three questions the agent answers with forms: "Which function throws? `(doc …)`. What does the test expect? `(seon.db/pull … test)`. What reader handles auto-resolved keywords? `(dir seon.sci.reader)`." No instructions beyond the questions. |
| F | **namespace picture** | The worker opens as the steward of `seon.sci.eval`: the namespace picture first (functions, tests reaching them, lint findings, errors), the issue as one item in it. Units: `:seon.fn/_ns` …, then the issue. The most context, the least direction. |
| G | **minimal + retrieval** | Like A, but the block ends with `;; ask for more with (my.issue/context {:seon.issue/id … :seon.render/distance 2})`, and the pair renders one hop further only on request. Tests whether the model retrieves rather than reads. |

Every candidate obeys the rulings: links, never copies (function source is
read with `doc`/pull, test source through the test pair); no writes in
rendered forms; the completing calls are exact and executable.

## 4. Measures, all from facts already recorded

| Measure | Source |
|---|---|
| resolved? and turns to resolution | `:seon.issue/resolved-tx`, turns of the worker |
| provider turns, prompt and output tokens, cache hit ratio, cost | `seon.ai.attempt` usage facts (landed today) |
| evaluation errors per session, by kind | `seon.cluster.eval` error rows |
| first turn that reads the test source; first turn that edits the file | evaluation sources |
| wrong-target edits (edits outside `src/seon/sci/eval.clj`) | `my.edit` effect results |
| the model's own account | the explain probe (`docs/prds/context-generation/research/explain_probe_2026_09_14.clj`) on the captured prompt |
| opening bytes | `seon.context.capture/prompt` of turn 0, recorded per candidate |

One session per candidate first (seven sessions), then three per candidate
for the two best and the worst, to see variance before believing anything.

## 5. Procedure (lane `issue-trials`, after issue-family's `start!` lands)

1. Author the issue entity and admit the red test once; verify red with `seon.test/run`.
2. For each candidate: set the worker overlay dial; `start!`; let the session run to `resolved-tx`, `done`, or budget; record the measures; revert `src/seon/sci/eval.clj` to HEAD between candidates (the file is the subject; each session starts from the same red).
3. Write the comparison as a table plus the seven openings' first 40 lines, in `docs/prds/steward-platform/research/issue-context-trials-2026-09-16.md`.
4. Bring the owner the table; the owner picks what to keep, merge, or drop; the losing render functions are deleted.

## 6. What this does not test yet

Communication issues (no code), issues with several functions across
namespaces, and the steward starting workers itself. Those are the next
subjects once one code issue resolves end to end.
