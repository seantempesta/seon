---
type: research
status: current
created: 2026-09-24
scope: the toy experiment for the self-documenting system — parse, load, query, context, dogfood agents, validation
---

# Toy experiment: the system documents itself, seen from the agent

Orchestrator experiment, 2026-09-23/24, read-only against `default` (a throwaway namespace
`scratch.wiki-toy` in its JVM and an in-memory Datahike). Artifacts are disposable under
`tmp/wiki-toy/` (git-ignored): the toy (`src/toy/{workout,schema,task}.clj`,
`test/toy/workout_test.clj`, `schemas/toy.workout.edn`), the extractor
(`experiment/scratch/wiki_toy.clj`) and every generated context (`context*.txt`).
The regex and runtime-Var reads in the extractor are probe shortcuts, not design.

## What was built

- A toy namespace with EDN schemas (every key described), contracted functions, a green
  example test, a green `defspec` property (50 generated cases), and two tasks declared as
  `def`s in the test namespace (docstring = why, value = namespace + deliverables), each with
  a red test tagged `:seon/task`.
- Extraction: clj-kondo analysis (0.09 s) + metadata links + runtime values → 24 rows,
  173 datoms, loaded in 40 ms.
- Queries that worked on the first try: the plan (tasks with red tests), **derived task order**
  (a task's test calls what another task delivers → `weekly-summary` needs `accept-rest-days`),
  backlinks by kind (calls, see-also, doc mentions, task delivers), which functions use a key,
  undocumented functions, keys without descriptions.
- Docs as data: hiccup-like bodies with real symbols inline, attached to a namespace, a
  function and a concept with no code; links by walking the data (13 ms), exact backlinks.
- Link validation declared per attribute (`:exists` / `:tdd`) plus task meaning checks: 0
  findings on the clean database; three badly written items (applied with `d/with`, nothing
  stored) each caught by name — a typo'd must-read, a test naming a missing task, a task
  delivering a typo'd function with no tests. A new function that a task test calls passes as
  a TDD obligation.

## Dogfood runs (each agent got only the generated context)

| Run | Agent | Task | Result | What it taught |
|---|---|---|---|---|
| 1 | Claude | fix `log-entry` to accept rest days | done, 5 forms | the schema form decided the fix; `must-read malli.core/validate` misled (Seon keys need our registry → must-read our wrapper); procedure steps need callable functions; delivery semantics unclear; MCP returns JSON (keywords → strings); a JVM `defn` rendered as `sci.lang.Var` |
| 2 | Claude | same (raced run 1) | done | context was stale against a REPL redefinition; contracts were not armed, so the new docstring's claim was false; no callable task-test runner; an unseen alias |
| 3 | Codex Sol low | write new `weekly-summary` | tests green in 2 forms, **but no docstring and no contract** | agents take the shortest path the checks allow → deliverable checks (docstring, declared contract) added to `toy.task/done-when` |
| 3b | same, re-prompted with the refusal as data | — | done in 5 forms | the refuse-explain-re-prompt loop works; revealed that **MCP JVM evaluation drops reader metadata on a `defn` name** (issue `docs/seon/issues/mcp-jvm-eval-drops-reader-metadata-on-a-defn-name.md`) |

Fixes made between runs were all to the toy's data or tools, never to the prompt: a schema
wrapper as the must-read, `toy.task/done-when` (arms contracts, runs only the task's tests,
names `:not-yet-defined` and `:deliverable-problems`), the new function's contract declared in
its task, `transduce` moved to the function that uses it, and the accepted fix written back.

## Independent review corrections (Astra, `astra-review-self-documenting-system-2026-09-23.md`)

- Namespaces and pages are simple symbols; link attributes must admit them.
- Transaction order is an advisory drift warning, not freshness: compare old/new declaration
  and doc values at indexing; record review observations over content identities when an
  acknowledgement must survive edits.
- Deliverables must name schema keys too; reachability from deliverables only proposes scope,
  root accepts it.
- Tasks as EDN data, or a literal quoted `def` parsed by the existing analyzer
  (`src/seon/fn/analyzer.clj:485`) — no evaluation to read them.
- Red acceptance tests stay on the task branch; merge code and its tests atomically after the
  combined gate. The current merge owner refuses added definitions
  (`src/seon/cluster/source.clj:760`) and must be extended, not duplicated.
- Start without PageRank, a separate reference database, prose-token extraction or a new task
  runner; select context around the task, not a whole-namespace tour.
- Toy flaws: the property's oracle reuses the helper under test; the entry schema does not
  enforce its own lift/rest description; context should surface such contradictions and let the
  agent ask root.

## Other research this round

- `long-form-docs-and-markdown-2026-09-23.md` (`d2ec67cfb`): docs as data is exact and cheap
  (1–2 ms); parse no docstring text; `nextjournal/markdown` (in bb) if a page body needs an AST.
- `indexing-static-vs-evaluated-2026-09-23.md` (`ba5aecf9d`): unchanged docstrings keep their
  transactions; clj-kondo gives no `def` values; native parsing is fast (toy 9 ms).
