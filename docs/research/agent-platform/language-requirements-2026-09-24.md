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
| 3.13 | A proof is a function of ONE database value `(fn [db] …)`: the deletion run's proofs asked the agent's SCI (`resolve`) and went green while the program row survived; a db-value proof stays red until the data changes, and runs unchanged on base, branch and merge branch | O, R | proposed v0.3 |
| 3.8 | Requirement test digests are frozen once reviewed; only root revises them, with the reason recorded | O, A | not yet |

## 3b. The stored model under the authoring surface (Astra synthesis, `task-model-synthesis-2026-09-24.md`)

| # | Requirement | Source | Status |
|---|---|---|---|
| 3b.1 | Authored surface stays minimal; the writer PRODUCES producer, created-by, identity and provenance facts | O, A | open: Astra requires title/producer/created-by at authoring; recommend produced |
| 3b.2 | Each done-when form is compiled into a task-local test body run through the existing `seon.test/run` on the attempt branch; it never enters the mergeable program or counts as coverage | A | open |
| 3b.3 | Decisions are components: accept / revise / reject / obsolete / supersede, with by, reason, the inputs reviewed and the exact deliverables selected (root's cherry-pick) | A | open |
| 3b.4 | Attempts carry base commit, task digest, test-run identities, deliveries (commit + digest) and an actual exit; three agents on one task = three attempts; resume keeps the attempt | A | attempts as values only |
| 3b.5 | "Same cause" is a relation distinct from "must precede" (`needs`) | A | open |
| 3b.6 | Task identity type: qualified keyword (authored, readable) vs string from seon.id (Astra) — owner decision | O, A | open |

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
| 5.8 | What the agent must change is shown whole; only must-reads are clipped (a 14-line clip hid the exact call a deletion must remove) | R | met |
| 5.9 | A deletion or rename brief carries each affected definition's current callers | R | met |
| 5.10 | Briefs stay focused: measured 410–4,078 characters across 12 task kinds, generated in 3.4 ms | R | met |
| 5.11 | The plan is a rendered view of the task data (no hand-written plan); it must be readable on the owner's phone | O | met: plan artifact rendered from task data |

## 5a. Context is generated from the task, never a namespace (owner 2026-09-24; prototype `scratch.task-context`)

| # | Requirement | Source | Status |
|---|---|---|---|
| 5a.1 | Seeds = the symbols, schema keys and `[attr value]` entities the task's proofs (and must-reads) reference; the author controls relevance by writing proofs | O | prototyped |
| 5a.2 | Hop 1 is CONTENT: a seed function's doc + contract + source once; a seed key's schema form; a seed entity's AI render; a missing symbol is "you create this" | O, R | prototyped |
| 5a.3 | Hop 2 is ADDRESSES only: callers, first-party callees, contract keys, reaching tests — names the agent fetches with one REPL call (`(source f)`, `(callers 'f)`) | O, R | prototyped |
| 5a.4 | Taught vocabulary is never expanded: functions a help topic teaches (`:seon.help/about`) are tools, not subjects. Without this rule `seon.db/q` in a proof dragged in 410 callers and 12 schemas (27.8 KB); with it the faults task's context is 7.7 KB in 0.3 ms (live root prompt: 680 KB) | R | measured |
| 5a.5 | DATA GAP: a function's contract is stored only as a string (`:seon.fn/spec`); its schema keys are not an edge (`:seon.fn/keywords` of `faults-form` lacks `:seon.render/unit`). Store contract keys as `keyword*` at the analyzer (no string parsing at readers) | R | open |
| 5a.6 | DATA GAP: `:seon/must-read` / `:seon/see-also` metadata is not ingested (no attribute) | R | open |
| 5a.7 | ~~DATA GAP: tests have no call edges~~ CORRECTED (Astra review 82d87392b): 29,375 `:seon.fn/calls` edges on 2,106 test rows exist (`fn.clj:602,641`); the probe looked under the wrong identity. `:seon.test/reach` is execution evidence, not the call relation | A | corrected |

## 5b. Explicit authority (v0.2 prototype, pending the owner; `experiments/tasks_v02.clj.txt`)

| # | Requirement | Source | Status |
|---|---|---|---|
| 5b.1 | ~~What a task may change is explicit~~ SUPERSEDED 2026-09-24 (owner): a task authors only id, intent, done-when (+ optional must-read, needs, merge, attempts, schedule). `about` is derived from the proofs' symbols/keys; what changed is derived from the base→branch diff (merge already classifies it, `cluster/source.clj:698`); work discovered mid-task (another caller to change) needs no scope amendment; control = review of the diff + gate + the decision's subset; a target data model is a proof | O | proposed v0.3 |
| 5b.2 | Prerequisites are explicit (`needs`); the writer suggests missing needs (calls into another task's definitions; keys another task's schemas define) and refuses cycles, unknown ids and duplicate ids | A | prototyped |
| 5b.3 | Two tasks changing one definition is advice (declare needs or same-cause) unless an explicit needs path already orders them | R | prototyped |
| 5b.4 | Dependencies come from CALLS, not quoted names; a proof printing `'x/f` does not depend on x/f's author | A | prototyped |
| 5b.5 | A namespace a task creates is allowed only through its own explicit definitions in it | A, R | prototyped |
| 5b.6 | `#:seon.task{…}` literal is the natural file syntax; the task-level must-read key is `:seon.task/must-read` | A, R | prototyped |

## 5c. Merge and rendering (2026-09-24 research: `write-back-and-custody-`, `agent-context-render-pipeline-`)

| # | Requirement | Source | Status |
|---|---|---|---|
| 5c.1 | Merge scope is data (definitions/retracts/renames/schemas/output); the merge WORK is a task-named pure fn `(fn [{:keys [base source target task]}]) → tx-data`, default program-row copy; root applies it to an intermediate branch, gates, then transacts the same tx-data; tx-data outside scope refuses | O, A | proposed |
| 5c.2 | One attempt = one private branch; today agents default to the live cluster branch (`cluster/agent.clj:205`) | O, R | open |
| 5c.3 | The agent's context is a clojure.main transcript: `ns=> ` + comments/forms, plain printed value, raw `Execution error` text, one trailing `;; result/… · ms` comment, and the context ENDS with `ns=> ` | O, R | open: today ends `turns left: …`, results wrapped in `#:seon.repl{…}` |
| 5c.4 | One printer with reader conventions (`'x`, `#'v`, `#:ns{}`); nine agent-facing printers today disagree (`(quote x)` leaks) | O, R | open |
| 5c.5 | Briefs are REPL transcripts built from calls (`(task)`, `(proofs)`, `(source f)`, `(callers 's)`), never a separate grammar | O, R | open |
| 5c.6 | Help is data: topics with text, example form, needs; order derived by topo sort on needs; one source (today two, contradicting) | O, R | open |
| 5c.7 | Openers speak as the system, never in the agent's voice | R | open |
| 5c.8 | No prompt unit reprints a generated selector: 81% of root's 680 KB prompt is one error query printed five times | R | open |

## 7. Where task information lives (owner 2026-09-24)

"Bringing context closer to normal agents and seon agents is the goal"; "we don't want to make a function definition
explode and the metadata larger than the actual function… tasks and references to the tasks by id".

| # | Requirement | Source | Status |
|---|---|---|---|
| 7.1 | **A task is one entity joined from three small places:** its data (id, intent, optional must-read, needs, merge) in `resources/seon/tasks/<owning-ns>.edn` or from a runtime writer; its **subjects** = every function, namespace or schema whose attribute map lists its id; its **proofs** = every deftest that names its id | O | proposed |
| 7.2 | **In code, only references:** `:seon/tasks #{:<owning-ns>.task/<descriptive-name> …}` in the attribute map. The id is the in-source summary, so it must read as the problem (`merge-adds-and-retracts`) | O | proposed |
| 7.3 | **Proofs name their task:** `(deftest ^{:seon/task :x.task/y} …)`; done-when is derived, never listed | O | proposed |
| 7.4 | **One owner per edge:** code owns subject edges, tests own proof edges, EDN owns only the task's own data (no subject lists in EDN, except for a task whose subject does not exist yet) | O | proposed |
| 7.5 | **Refusals and triage:** an id in code or a test with no task entity refuses (dangling); a task with no subject and no proof is listed for triage; a duplicate id refuses | O | proposed |
| 7.6 | **Lifecycle:** raised (no naming test) → specified (a naming test, red on the base for its own reason) → green on the attempt → settled when root accepts AND the fix's diff removes the id from its subjects in the same merge. History keeps it | O | proposed |
| 7.7 | **Out of source and out of EDN:** attempts, decisions, evidence, measurements, discussion — database entities pointing at the task id | O | proposed |
| 7.8 | **Runtime tasks** (detectors, errors, users, agents) are database writes through `seon.task/create!`; they may name subjects explicitly because no code lists them yet | O, A | proposed |
| 7.9 | **Ingestion:** the analyzer stores `:seon/tasks` and `:seon/must-read`/`:seon/see-also` references as symbol/keyword edges (today it drops them); task EDN reconciles by id into the same task schema | O | open |
| 7.10 | **Context:** `(task :id)` shows the entity with its subjects and proofs; a function's context lists the open tasks that reference it, so work on one function surfaces its other known problems | O | proposed |

## 6. Platform gaps the runs exposed (each is itself a task)

- Two definition authorities: REPL forms are recognized by a hand matcher (`src/seon/sci/reader.cljc:245,323`) that sees only a top-level `clojure.core/defn`, so `(do (defn …))`, `(let [..] (defn …))`, `(def f (fn …))` and defn-expanding macros bypass the entry gate and arming. Owner-directed fix: observe what an evaluation defined (evaluate in a `sci/fork`, diff namespace maps, gate/arm the created Vars from their metadata, adopt or discard the fork); clj-kondo stays the one analyzer for rows and edges. Limit: a call inside the defining form runs before the check.

- An agent cannot validate a value against a key in SCI — `:seon.schema.task/agents-can-validate` DONE on branch `exp-validate-2` by a Codex agent from the brief alone (11 forms); not delivered: cherry-pick → rows → files does not exist.
- A session's MCP server that predates a script change silently runs the old evaluation path: branch definitions did not persist for this session's agents. Reconnect MCP after script changes; Codex lanes start fresh servers.
- `:nil` is refused in agent-authored contracts although core contracts use it; `[:= nil]` refused as not EDN-readable; `seon.schema.internal/contract-error!` crashes on a function symbol.
- A schema declaration cannot be published to a branch; `register!` at top level reports success and writes nothing.
- Contracts are not visible through `(meta #'f)` in SCI; the row's `:seon.fn/spec` is a `pr-str` string.
- `seon.fn/tests-reaching` takes 1.9 s / 6.5 GB and reports 1,720 tests for a core function (saturated reach).
- Refusals are ~15 KB of duplicated location trees; MCP drops `defn` name metadata, mislabels the namespace, turns keywords into strings.
- The merge owner refuses added definitions; every new function is blocked from landing.
- Deletion at the REPL (`ns-unmap`, deletion task run 2026-09-24 on `exp-drop-note-row-1`) warns about no callers and records nothing: the program row survives. The "no deletion while callers exist" law is bypassed on this path.
- The entry gate refuses an agent re-entering a CORE contract verbatim (`:nil` in `seon.note/add-note!`'s own contract); agents must rewrite system contracts to redefine a function.
- A redefinition without `:malli/schema` is admitted and silently drops the function's contract, although the strict `:gate` default should refuse it.
- A branch's SCI copy of a namespace lacks the file's `:require` aliases (`db/pull` unresolved in `seon.note` until the agent re-required `seon.db`).
