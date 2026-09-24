---
type: requirements
status: living — rewritten in place as experiments teach us; not an append log
created: 2026-09-24
scope: what the decoration language (code metadata + EDN task files) must satisfy, from the owner's rulings and every dogfood run
---

# Requirements for the language that describes Seon to itself

Each requirement names its source (O = owner ruling, R = a dogfood/experiment run, A = review/audit).
"Met" means met by v0 in experiment (`decoration-language-v0-2026-09-24.md`,
`resources/seon/tasks/`), not installed.

## 1. It is data, in Clojure, that agents already know how to write

| # | Requirement | Source | v0 |
|---|---|---|---|
| 1.1 | Everything stored is a value in Datahike with a unique fully qualified name; symbols are symbols, keywords keywords, never strings | O | met |
| 1.2 | Code carries what is true of code (intent, contracts, links); EDN carries what is not code yet (tasks, target data model, proofs) | O | met |
| 1.3 | Plain Clojure only: `ns`/`defn`/`deftest` + metadata; no custom `ns` clauses (`clojure.core/ns` refuses them by spec — measured) | O, R | met |
| 1.4 | No mini-language inside strings; prose is never parsed | O | met |
| 1.5 | Links written the way a REPL resolves them: syntax quote in code, bare fully qualified symbols in EDN data, `'sym` inside requirement code | O, R | met |
| 1.6 | Task files are read by the Clojure reader without evaluation (edamame, quote on): `clojure.edn` silently misreads `'sym` as one symbol named `'sym` | R | met |
| 1.7 | Elegant and intuitive, not merely sufficient: the fewest authored keys; everything derivable is derived | O | v0.1: authored = id, intent, subjects, [schemas], [done-when]; 76 → 47 lines for the same eight tasks |
| 1.8 | Keys read as English: `:seon.task/done-when`, `:seon.task/intent` (a feature is not a problem) | O, R | met |

## 2. Tasks are the one unit of work

| # | Requirement | Source | v0 |
|---|---|---|---|
| 2.1 | One family: issue, backlog and plan are derived views, never separate stores | O | met |
| 2.2 | A task points at its subjects by value (symbols, schema keywords, stored-data identities); many tasks per entity; never written onto the entity | O, A | met |
| 2.3 | The subject is the producer that must change, not where the symptom showed; the symptom is evidence | O | met |
| 2.4 | Tasks come from many producers: errors, detectors (queries), users and agents (`create!`), other tasks (triage, decomposition), EDN preload incl. recurring and system setup | O | EDN + detector: the stale-doc detector generated 42 tasks from real data (115 drifted functions) |
| 2.10 | Detector granularity: findings are grouped by the subject's namespace — one focused task per namespace, not one per function (2,395 undocumented + 115 drifted functions would be 2,500 tasks) | R | met |
| 2.11 | A detector's empty result is the proof: generated tasks' done-when is "the detector returns nothing here", so they all derive a need on the task that delivers the detector — one platform task, then N farmable independent tasks, with no hand-written order | R | met |
| 2.5 | Identity: authored tasks by name (a qualified keyword, never confusable with a Var); generated tasks by producer + subject, so re-running a detector finds, not duplicates | O, A | met (ids stable across re-runs) |
| 2.6 | States are derived, never stored: raised (no proofs), ready (specified, every need settled), blocked (a need unsettled), settled (an attempt with every proof green), obsolete (subject gone), superseded | O, A | raised/ready/blocked/settled met |
| 2.7 | Order: derived — B needs A when B's proofs reference what A delivers; a missing symbol is delivered only by the task whose subjects include its namespace (otherwise every user of a new function claims it); "shares a cause" is distinct from "must precede" | O, A, R | derived order met |
| 2.8 | Runtime facts (attempts, agents, branches, results) are separate entities pointing at the task, so reconciling EDN never erases what happened | A | attempts as values met (not stored yet) |
| 2.9 | Large work is decomposed into small verifiable tasks by a task whose deliverable is tasks | O | not yet |

## 3. Done is decided by data

| # | Requirement | Source | v0 |
|---|---|---|---|
| 3.1 | Stages: data model first (target schemas), then proofs over existing functions and data (properties welcome), then functions | O | met (keys in task) |
| 3.2 | A requirement proves a task and does not merge; a guarantee is a `deftest` that merges by deliberate promotion; the test suite must not explode | O | met |
| 3.3 | A missing symbol is a failing requirement (TDD), allowed only when a requirement references it; a missing SUBJECT namespace is allowed when the task creates it | O, R | symbols met; namespaces open |
| 3.9 | A proof must be red on the base before work starts (a proof that already holds proves nothing) — checked by evaluating it on a throwaway branch | R | met (caught one task) |
| 3.10 | A proof must be red for its OWN reason: red only because a needed task is unsettled means blocked; check each task on a branch where its needs are satisfied | R | met: about-is-a-value on the settled branch is red for "Missing schema declaration :my.note/about-symbol" |
| 3.4 | Exits can be example tests, properties, bounds, empty detector queries, measurements or root decisions | A | examples only |
| 3.5 | Deliverables include functions, tests, schema keys and data (findings, child tasks) | A, R | fns + keys + data-only tasks (`changes #{}`) |
| 3.11 | "What a task is about" (perspective, context, reverse links) is distinct from "what it may change" (what root may cherry-pick): research, answers and decomposition are about code they must not change | R | met: `:seon.task/changes` |
| 3.12 | A small standard proof vocabulary lets every task kind state done the same way (children, specified?, settled?, accepted?, reply-to, finding, valid?, detectors) | R | specified as a task |
| 3.6 | Delivered functions carry a docstring and the declared contract; agents take the shortest path the checks allow, so the checks encode the requirement | R | met in toy |
| 3.7 | After every turn the task's own requirements run on its branch; red → re-prompt with the refusal as data; budget-bounded; escape hatch to root is itself a task | O, R | proven in toy |
| 3.8 | Requirement test digests are frozen once reviewed; only root revises them, with the reason recorded | O, A | not yet |

## 4. The writer refuses badly written tasks (constraints, not advice)

| # | Requirement | Source | v0 |
|---|---|---|---|
| 4.1 | Links resolve (program row, loaded Var, or delivered by this task or one it needs) | O, R | met |
| 4.2 | Requirements exercise what the task changes (a delivered symbol, subject, or changed key) | R | met |
| 4.3 | No unquoted symbol in value position (it evaluates to the Var's value); `*dynamic*` exempt | R | met |
| 4.4 | Requirement example values satisfy the called function's input schema (against target schemas + registry) | R | met (single-arity contracts; literal arguments) |
| 4.5 | A raised task (no requirements) can only be triaged | R | met |
| 4.6 | Evidence paths exist | R | met |
| 4.7 | Measured: 9 ms over eight tasks against the real program database; caught six real mistakes in the orchestrator's own writing | R | — |

## 5. Context is built from references, around the task

| # | Requirement | Source | v0 |
|---|---|---|---|
| 5.1 | Never a namespace or require dump: context is pulled by how things reference each other | O | met |
| 5.2 | Every form in a brief is callable by the agent in its own SCI context, and shows its current value | R | met |
| 5.3 | Must-reads are always included (task, fn and ns level); they point at our wrappers, which know the registry and the lessons | O, R | met |
| 5.4 | Source reads as source; values render through their schema's AI renderer; no escaped-string walls | R | met |
| 5.5 | The same brief value renders for the agent (AI) and for the owner's debug page (HTML): one path | O | not yet |
| 5.6 | Contradictions are surfaced, not hidden (a docstring that teaches a no-op function; a schema description its form does not enforce) | R, A | not yet |
| 5.7 | Intent is the only authored prose; everything else in the brief is derived | O | met |

## 6. Platform gaps the runs exposed (each is itself a task)

- An agent cannot validate a value against a key in SCI — `:seon.schema.task/agents-can-validate` DONE on branch `exp-validate-2` by a Codex agent from the brief alone (11 forms); not delivered: cherry-pick → rows → files does not exist.
- A session's MCP server that predates a script change silently runs the old evaluation path: branch definitions did not persist for this session's agents. Reconnect MCP after script changes; Codex lanes start fresh servers.
- `:nil` is refused in agent-authored contracts although core contracts use it; `[:= nil]` refused as not EDN-readable; `seon.schema.internal/contract-error!` crashes on a function symbol.
- A schema declaration cannot be published to a branch; `register!` at top level reports success and writes nothing.
- Contracts are not visible through `(meta #'f)` in SCI; the row's `:seon.fn/spec` is a `pr-str` string.
- `seon.fn/tests-reaching` takes 1.9 s / 6.5 GB and reports 1,720 tests for a core function (saturated reach).
- Refusals are ~15 KB of duplicated location trees; MCP drops `defn` name metadata, mislabels the namespace, turns keywords into strings.
- The merge owner refuses added definitions; every new function is blocked from landing.
