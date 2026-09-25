---
type: requirements
status: living — rewritten in place as experiments teach us; not an append log
created: 2026-09-24
scope: what the decoration language (code metadata + EDN task files) and the one REPL path must satisfy, from the owner's rulings and every dogfood run; revised in place 2026-09-25 so docs/seon/issues and plans can move into the codebase as tasks
---

# Requirements for the language that describes Seon to itself

Each requirement names its source (O = owner ruling, R = a dogfood/experiment run, A = review/audit).
"Met" means met by v0 in experiment (`decoration-language-v0-2026-09-24.md`), not installed.
The v0 task files are not on this branch; `resources/seon/tasks/` is created by the ingestion slice (7.9).
Designs this spec is implemented by: `one-repl-language-2026-09-25.md` (the path, co-location, config),
`one-render-path-design-2026-09-25.md` (selection, floor), `function-face-and-canonical-authoring-2026-09-25.md`
(function face, canonical authoring, derived contracts) and `query-render-building-block-2026-09-25.md`.

## 1. It is data, in Clojure, that agents already know how to write

| # | Requirement | Source | v0 |
|---|---|---|---|
| 1.1 | Everything stored is a value in Datahike with native types (symbols are symbols, keywords keywords, never strings); every independently addressable entity has a declared identity; scalars and owned components need none | O, A | met |
| 1.2 | **Each fact lives with its owner, where grep finds it and the indexer parses it once:** what is true of a function (intent, contract, links, task ids) is in its `defn`; what is true of a data shape (keys, default render pair, a dial's default and reason) is on its Malli schema; a task's own data (id, intent, needs, must-read) is task EDN; what differs by deployment is a sparse overlay file; initialization data (providers, models) stays with its data owner. Nothing is written twice (owner 2026-09-25) | O | revised |
| 1.3 | Plain Clojure only: `ns`/`defn`/`deftest` + metadata; no custom `ns` clauses (`clojure.core/ns` refuses them by spec — measured) | O, R | met |
| 1.4 | No mini-language inside strings; prose is never parsed | O | met |
| 1.5 | Links written the way a REPL resolves them: quoted fully qualified symbols in code metadata (a bare symbol becomes a live function object), bare fully qualified symbols in EDN data; must-read and see-also are typed edges, not lumped into references | O, R, A | revised |
| 1.6 | Task files are read by the Clojure reader without evaluation (edamame, quote on): `clojure.edn` silently misreads `'sym` as one symbol named `'sym` | R | met |
| 1.7 | Elegant and intuitive, not merely sufficient: the fewest authored keys; everything derivable is derived | O | v0.1: authored = id, intent, subjects, [schemas], [done-when]; 76 → 47 lines for the same eight tasks |
| 1.8 | Keys read as English: `:seon.task/done-when`, `:seon.task/intent` (a feature is not a problem) | O, R | met |
| 1.9 | **Every reference is a handle followable both ways from limited context:** an id (task, function, schema, error, test) appears literally where it is defined, in a file its namespace names, so `rg <id>` finds the definition and every referrer; and the same id is one REPL call away (`(task :x.task/y)`, `(source 'f)`, `(callers 'f)`), which returns its explicitly linked graph neighbours. Rendered context shows ids plus the call that expands them, never the expansion by default. Task ids are written as the same full literal token in task EDN, in `:seon/tasks` metadata and in rendered context, so one grep finds all of them | O 2026-09-25, A | proposed |
| 1.10 | **The REPL is the authoring path:** agents change the program by evaluating forms: `defn` inside the target namespace or by its fully qualified name (the entrance admits `(defn other.ns/f …)`, which plain SCI/Clojure refuse today); `my.program/ns-unmap!` / `remove-ns!` delete (one deletion authority, nested native `ns-unmap` included); files are the gated write-back of accepted rows, never the working medium (owner 2026-09-25; AGENTS.md "The one REPL path") | O | open: one-repl R-slices |
| 1.11 | **One system for every function:** an anonymous `(fn …)` gets a generated unique name (as Clojure's `fn__N`), may carry the same metadata and contract, and is analysed and recorded exactly like a `defn`, as a function row owned by its evaluation (data, never program: it never merges or writes back) | O 2026-09-25 | implementing |
| 1.12 | **A contract may be derived.** Inputs come from destructured fully qualified keys (`(fn [{:keys [seon.db/db seon.render/value]}] …)` binds the locals and declares the input map from those keys' registered schemas; an `:or` key is optional; unqualified keys, positional, sequential and rest arguments derive nothing and need an explicit contract; positional `:cat` contracts remain). Outputs come from two sources only: an explicit `:malli/schema` (inputs and outputs in one place; it always wins), or the return keys the one parser (the clj-kondo fork's type inference, exported per arity) finds, where a tail call resolves through the call graph to the callee's effective output contract, and the writer re-derives dependent callers in dependency order in the same transaction from recorded `output-from` facts. Exactly two modes (complete explicit, complete derived), one effective contract recompiled only when a dependency changes. When no output is found the entry gate refuses by name citing what it saw ("return is :associative; declare :malli/schema"; "return depends on g, which is recursive; declare :malli/schema"). Design: `derived-contracts-2026-09-25.md` (d88ce949f) | O 2026-09-25, A | designing |
| 1.13 | **Contract-declared inputs are defaults, for every function:** an input a contract declares (map key or positional schema, e.g. `:seon.db/db`) that the caller omits is supplied by declared schema name through the one call-preparation seam; caller arguments always win | O 2026-09-25 | partly installed (`3785970b9`) |
| 1.15 | **Every stage of the language is specified data:** what authors write (task EDN entries, `:seon/tasks`/`:seon/task`/`:seon/must-read`/`:seon/see-also` values, `:seon.test/seed` forms), what the parser returns (the clj-kondo fork's analysis, including exported return tags) and what each transform produces (function, task, subject and proof rows; derived contracts) each has a named Malli schema. Each transform validates the value it builds and refuses by name (stage, member, offending item); the writer's final validation still applies. Nothing is silently dropped or indexed malformed (owner 2026-09-25) | O | proposed |
| 1.14 | **Canonical authoring:** what an agent writes is stored in one canonical head form (docstring, attribute map, contract; body bytes untouched), identical in files (for grep agents) and in rendered context (for REPL agents), and shown back as feedback ("stored as …"); semantics and digests are checked equal. An inline subschema that structurally equals a registered schema is shown as advice naming that schema, never substituted automatically (297 of 369 such matches are ambiguous; reviews 52e252f74, 49b1122d4) | O 2026-09-25, A | designing |

## 2. Tasks are the one unit of work

| # | Requirement | Source | v0 |
|---|---|---|---|
| 2.1 | One family: issue, backlog and plan are derived views, never separate stores | O | met |
| 2.2 | Subject edges are owned as §7 says: code declares the task ids it is subject to; tests name the task they prove; task EDN holds only the task's own data (and subjects that do not exist yet); many tasks per entity | O, A | revised to §7 |
| 2.3 | The subject is the producer that must change, not where the symptom showed; the symptom is evidence | O | met |
| 2.4 | Tasks come from many producers: errors, detectors (queries), users and agents (`create!`), other tasks (triage, decomposition), EDN preload incl. recurring and system setup | O | EDN + detector: the stale-doc detector generated 42 tasks from real data (115 drifted functions) |
| 2.10 | Detector granularity: findings are grouped by the subject's namespace — one focused task per namespace, not one per function (2,395 undocumented + 115 drifted functions would be 2,500 tasks) | R | met |
| 2.11 | A detector's empty result is the proof: generated tasks' done-when is "the detector returns nothing here", so they all derive a need on the task that delivers the detector — one platform task, then N farmable independent tasks, with no hand-written order | R | met |
| 2.5 | Identity is a task's identifying aspect, never its whole content (3b.6): the same aspect arriving again finds the existing task and counts a recurrence, as the error writer does for a repeated signature; re-running a detector finds, not duplicates | O, A | revised |
| 2.6 | States are derived, never stored: raised (no proofs), ready (specified, every need settled), blocked (a need unsettled), settled only as 7.6 defines (root's accepted outcome; green is evidence, never settlement), superseded. A settled task keeps its identity, proof edges and acceptance evidence after its subject edges are removed; reconciliation never resurrects it and emptiness never infers settlement | O, A | revised to §7 |
| 2.7 | Order: `needs` are authored (5b.2); suggestions are derived from calls, never quoted names —  B needs A when B's proofs reference what A delivers; a missing symbol is delivered only by the task whose subjects include its namespace (otherwise every user of a new function claims it); "shares a cause" is distinct from "must precede" | O, A, R | derived order met |
| 2.8 | Runtime facts (attempts, agents, branches, results) are separate entities pointing at the task, so reconciling EDN never erases what happened | A | attempts as values met (not stored yet) |
| 2.9 | Large work is decomposed into small verifiable tasks by a task whose deliverable is tasks | O | not yet |

## 3. Done is decided by data

| # | Requirement | Source | v0 |
|---|---|---|---|
| 3.1 | Stages: data model first (target schemas), then proofs over existing functions and data (properties welcome), then functions | O | met (keys in task) |
| 3.2 | A task's proof is an ordinary test naming it (7.3): behavioral regressions use the existing harness and injected worlds and are program tests; data proofs are predicates over one held database value (3.13). The test suite must not explode | O, A | revised |
| 3.3 | A missing symbol is a failing requirement (TDD), allowed only when a requirement references it; a missing SUBJECT namespace is allowed when the task creates it | O, R | symbols met; namespaces open |
| 3.9 | A proof must be red on the base before work starts (a proof that already holds proves nothing) — checked by evaluating it on a throwaway branch | R | met (caught one task) |
| 3.10 | A proof must be red for its OWN reason: red only because a needed task is unsettled means blocked; check each task on a branch where its needs are satisfied | R | met: about-is-a-value on the settled branch is red for "Missing schema declaration :my.note/about-symbol" |
| 3.4 | Exits can be example tests, properties, bounds, empty detector queries, measurements or root decisions | A | examples only |
| 3.5 | Deliverables include functions, tests, schema keys and data (findings, child tasks) | A, R | fns + keys + data-only tasks (`changes #{}`) |
| 3.11 | "What a task is about" (perspective, context, reverse links) is distinct from what root accepts: acceptance is root's reviewed subset through the existing gate (5b.1); no authored `changes` scope is enforced | R, A | revised |
| 3.12 | A small standard proof vocabulary lets every task kind state done the same way (children, specified?, settled?, accepted?, reply-to, finding, valid?, detectors) | R | specified as a task |
| 3.6 | Delivered functions carry a docstring and the declared contract; agents take the shortest path the checks allow, so the checks encode the requirement | R | met in toy |
| 3.7 | After every turn the task's own requirements run on its branch; red → re-prompt with the refusal as data; budget-bounded; escape hatch to root is itself a task | O, R | proven in toy |
| 3.13 | A proof is a function of ONE database value `(fn [db] …)`: the deletion run's proofs asked the agent's SCI (`resolve`) and went green while the program row survived; a db-value proof stays red until the data changes, and runs unchanged on base, branch and merge branch | O, R | proposed v0.3 |
| 3.8 | Requirement test digests are frozen once reviewed; only root revises them, with the reason recorded | O, A | not yet |

## 3b. The stored model under the authoring surface (Astra synthesis, `task-model-synthesis-2026-09-24.md`)

| # | Requirement | Source | Status |
|---|---|---|---|
| 3b.1 | Authored surface stays minimal; the writer PRODUCES producer, created-by, identity and provenance facts | O, A | open: Astra requires title/producer/created-by at authoring; recommend produced |
| 3b.2 | ~~done-when forms compiled into task-local tests~~ RETIRED 2026-09-25: proofs are tests that name the task (7.3); no done-when compiler in the migration | A | retired |
| 3b.3 | Decisions are components: accept / revise / reject / obsolete / supersede, with by, reason, the inputs reviewed and the exact deliverables selected (root's cherry-pick) | A | open |
| 3b.4 | Attempts carry base commit, task digest, test-run identities, deliveries (commit + digest) and an actual exit; three agents on one task = three attempts; resume keeps the attempt | A | attempts as values only |
| 3b.5 | "Same cause" is a relation distinct from "must precede" (`needs`) | A | open |
| 3b.6 | **Task identity = `seon.id` over the task's identifying aspect** (owner 2026-09-25): its name plus the component or subject kind it concerns (authored), or producer + subject (generated) — the unique aspect by which "the same thing coming up again" is recognized, exactly as errors dedupe by signature and count occurrences. Intent, needs, evidence and subjects are mutable content under a revision, never identity inputs. The id is the short token `seon.id/id` returns for that aspect (short enough for agents to write, one exact grep), stored under `:seon.task/id` (never a `:db/ident`) and written as the same literal token in task EDN, `:seon/tasks` metadata and rendered context, beside its readable name; the writer mints it (agents get it from one REPL call) and verifies a supplied one against its aspect. A migrated issue gets its id from its aspect and keeps its old issue path as a historical association | O 2026-09-25 | ruled |

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
| 5.4 | Source reads as source; values render by the per-face selection of 11.1 (value key, viewing agent's namespace function, schema default, floor); no escaped-string walls | R, O | revised |
| 5.5 | The same brief value renders for the agent (AI) and for the owner's debug page (HTML): one pipeline, two consumers (11.3) | O | law (AGENTS.md); not yet installed |
| 5.6 | Contradictions are surfaced, not hidden (a docstring that teaches a no-op function; a schema description its form does not enforce) | R, A | not yet |
| 5.7 | Intent is the only authored prose; everything else in the brief is derived | O | met |
| 5.8 | What the agent must change is shown whole: the subject's source renders through its source renderer, whose job is the whole source; everything else is clipped only by the floor, whose cuts name path, count and the call that retrieves the rest (a 14-line clip hid the exact call a deletion must remove) | R, O | revised |
| 5.9 | A deletion or rename brief carries each affected definition's current callers | R | met |
| 5.10 | Briefs stay focused: measured 410–4,078 characters across 12 task kinds, generated in 3.4 ms | R | met |
| 5.11 | The plan is a rendered view of the task data (no hand-written plan); it must be readable on the owner's phone | O | met: plan artifact rendered from task data |

## 5a. Context is generated from the task, never a namespace (owner 2026-09-24; prototype `scratch.task-context`)

| # | Requirement | Source | Status |
|---|---|---|---|
| 5a.1 | Seeds = the symbols, schema keys and `[attr value]` entities the task's proofs (and must-reads) reference; the author controls relevance by writing proofs | O | prototyped |
| 5a.2 | Hop 1 is CONTENT: a seed function's doc + contract + source once; a seed key's schema form; a seed entity's AI render; a missing symbol is "you create this" | O, R | prototyped |
| 5a.3 | Hop 2 is ADDRESSES only (1.9): callers, first-party callees, contract keys, reaching tests, render referrers (the schemas a function renders), open task ids, the config documents a test world reads, and for design tasks prior-art references — names the agent fetches with one REPL call (`(source f)`, `(callers 'f)`, `(task :id)`) or one grep | O, R | prototyped; new edges open |
| 5a.4 | Taught vocabulary is never expanded: functions a help topic teaches (`:seon.help/about`) are tools, not subjects. Without this rule `seon.db/q` in a proof dragged in 410 callers and 12 schemas (27.8 KB); with it the faults task's context is 7.7 KB in 0.3 ms (live root prompt: 680 KB) | R | measured |
| 5a.5 | ~~Contract keys are stored only as a string~~ CORRECTED (Astra 7117f211f): `program/contract-facts` compiles contracts and `arity-row` stores input/output/guard refs (`program.cljc:1007–1081`); `my.program/reads-key` queries them. Query that relation; add no keyword edge | A | corrected |
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
| 5c.1 | The merge WORK is a pure fn `(fn [{:keys [base source target task]}]) → tx-data`, default program-row copy; root applies it to an intermediate branch, gates, then transacts the same tx-data; acceptance is root's reviewed subset (no authored scope list) | O, A | proposed |
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
| 7.2 | **In code, only references, one key:** `:seon/tasks #{<task-id> …}` in the attribute map of any `defn`, `def`, `ns`, schema or `deftest`. The holder decides the role: on a `deftest` the ids are the tasks it proves, anywhere else the tasks that definition is subject to. Ids are written as full literal tokens (never `::alias/x`, never `^:seon/tasks`), and stored as keyword VALUES, not refs, so tasks never ride program merge (7.13); an id still listed in source means the task is open there (proof 76d5f06b3) | O, A | proposed |
| 7.3 | **Proofs name their task** through the same key: `(deftest ^{:seon/tasks #{<task-id>}} name …)`; done-when is derived, never listed. Metadata placed after the test name is refused by name (today it is silently dropped) | O, A | proposed |
| 7.4 | **One owner per edge:** code owns subject edges, tests own proof edges, EDN owns only the task's own data (no subject lists in EDN, except for a task whose subject does not exist yet) | O | proposed |
| 7.5 | **Refusals and triage:** an id in code or a test with no task entity refuses (dangling); a task with no subject and no proof is listed for triage; a duplicate id refuses | O | proposed |
| 7.6 | **Lifecycle:** raised (no naming test) → specified (a naming test, red on the base for its own reason) → green on the attempt → settled when root accepts AND the fix's diff removes the id from its subjects in the same merge. History keeps it | O | proposed |
| 7.7 | **Out of source and out of EDN:** attempts, decisions, evidence, measurements, discussion — database entities pointing at the task id | O | proposed |
| 7.8 | **Runtime tasks** (detectors, errors, users, agents) are database writes through `seon.task/create!`; they may name subjects explicitly because no code lists them yet | O, A | proposed |
| 7.9 | **Ingestion:** the analyzer stores `:seon/tasks` and `:seon/must-read`/`:seon/see-also` references as symbol/keyword edges (today it drops them); task EDN reconciles by id into the same task schema | O | open |
| 7.9b | **Test seeds are edges too:** a deftest's `:seon.test/seed` entries — literal tx data, a qualified seed-fn symbol, or an inline `(fn [input] ...)` (owner 2026-09-24: all three; literal data taught first; bodies stay out of EDN task files) — are ingested as edges (a symbol as its own edge; literal data and inline fns through the test's own analysis and definition digest), so a task's context walk follows task → test → seed fn (doc, contract, source) → the schema keys it writes (`:seon.fn/writes`, already stored) → their forms; the seed's tx-data is validated against those schemas at admission, so the context shows data of known shape (owner 2026-09-24) | O | open: part of the ingestion slice |
| 7.11 | **Issues and plans move into the codebase as tasks:** each `docs/seon/issues/*.md` becomes a task entry (id from `seon.id` over its data, 3b.6; intent from its summary, `must-read` to its evidence file) in its owning namespace's task EDN; its subjects gain the id in their `:seon/tasks`; its proof is the regression that names it; the markdown body stays as linked evidence, never parsed. The ranked issue index and plan READMEs become rendered views of task data (2.1, 5.11). Long designs, research and skills stay Markdown, linked by symbol and pinned `file:line` | O 2026-09-25 | proposed: first slice after 7.9 |
| 7.12 | Defects listed in "Defects the runs exposed" below are the first tasks migrated | O | proposed |
| 7.13 | **Tasks are data, never program:** task rows never ride program merge. Admitting work is two coordinated steps: the task/issue writer reconciles the task's data on the destination, then the program gate validates the code and test edges that reference it against that destination (a dangling id refuses there). A publication may admit both coherently, but each keeps its owner (A 4aedb01b2 P0-2) | O, A | proposed |
| 7.14 | **Bootstrap exception (owner 2026-09-25):** until write-back for data files and new definitions lands, the first task schema, task EDN, `:seon/tasks` annotations and ingestion code are hand-authored as ordinary committed files by an Opus lane. This is a named, time-limited exception to 1.10 that ends when that write-back lands | O | ruled |
| 7.16 | **One writer, every edit with a basis:** task EDN and runtime entrances both submit a desired declaration with the revision they read to the one task writer; unchanged re-ingestion is a no-op; a competing revision returns base, current and proposed values for repair (never last-writer-wins). Acceptance binds the reviewed task revision, proof identities and the tested destination. If the task is admitted and the program gate then refuses, the task stays valid (possibly raised) (A 6cfb6a789 P0-2) | O, A | proposed |
| 7.17 | **Write-back's honest guarantee:** the first write-back slice relies on one-file-one-owner to exclude simultaneous shell edits; digest checks detect observed drift and refuse; "accepted but not written" is loud. Supporting arbitrary concurrent external edits needs a separately reviewed installation mechanism (A 6cfb6a789 P0-3) | A | proposed |
| 7.15 | **Migration boundary:** a migrated record is converted through the existing issue owner, its old entry points resolve the same current task, and its Markdown stops being current input; unmigrated records keep the old owner. Never two writable representations of one task. Historical uncertainty is preserved as evidence (not imported as truth); README/index files are not issues | A | proposed |
| 7.10 | **Context:** `(task :id)` shows the entity with its subjects and proofs; a function's context lists the open tasks that reference it, so work on one function surfaces its other known problems | O | proposed |

## 11. Rendering is one pipeline (owner 2026-09-25; AGENTS.md "Render ordinary values totally", "Apply presentation limits once")

| # | Requirement | Source | Status |
|---|---|---|---|
| 11.1 | Per face, first match wins: the value's own `:seon.render/ai`/`:seon.render/html` key (master override: a renderer symbol, an inline function, or rendered data); a contract-fitting render function in the viewing agent's namespace (that agent's own defaults); the schema's declared pair (shipped default); the floor | O | source-present (`aa91ecb34`, `634d8aaa2`), installed-unverified; nested open |
| 11.2 | A render function is known by its face role; its output may be any spec'd shape, rendered in turn; its contract says what it needs (1.13) | O | partly |
| 11.3 | Printing is rendering: AI agents through MCP and humans through the browser share one selection, node tree and cut retrieval; only the final face differs (AI: pretty-printed, clipped, saved text; HTML: our Hiccup, explorable, each cut an expander fetching its path) | O | designing (floor redesign) |
| 11.4 | Only the floor clips, with one budget spread across the value's shape; nothing is realized, hashed or printed before it is affordable | O | designing |

## 12. Values stay values until an edge (owner 2026-09-25)

| # | Requirement | Source | Status |
|---|---|---|---|
| 12.1 | Everything before the print is a real value under its Malli schema: live in the runtime, a database fact, or a blob; printing and persisting are the only edges | O | law |
| 12.2 | An error is an ordinary value on that path: no error-specific summarizing, stringifying or clipping; identity exists to store at least one copy, link it and notify the agent to fix it; an error is a flat named error schema over `:seon.error/base` that can accompany any value (no error-kind stamp); an error member naming a function stays a symbol value (`:seon.error/operation`, `:seon.instrument/fn`): errors are data, never program, so they observe the name and never block its deletion; a relation to a live data entity (the occurrence's agent, `:seon.error.occurrence/agent`) is a ref (root ruling 2026-09-25, references-as-refs design §7 Q3) | O, root | law; six schemas to fix |

## 13. Configuration layers (owner 2026-09-25)

| # | Requirement | Source | Status |
|---|---|---|---|
| 13.1 | A dial's default and its reason live on its schema (`:seon.config/default`); `config/default.edn` is retired; initialization data moves to its own file | O | ruled; slice in one-repl design |
| 13.2 | Layers, least to most specific: schema default → ordered, composable deployment overlays (`config/development.edn`, …) → the typed environment layer → cluster facts → per-agent settings; `config apply` composes overlays in order (today it keeps one); every reader reads its own cluster's layer, never an arbitrary row | O | ruled; defects open |

## 8. Errors are recorded on the branch where they happen (owner 2026-09-24: "all errors then just get logged to the database in the right branches and we don't have to worry about routing them")

| # | Requirement | Status |
|---|---|---|
| 8.1 | An agent's own failing evaluation returns its error as that evaluation's result | met |
| 8.2 | Every error occurrence is recorded in the database of the branch where it happened (the custody at record time); an agent's view of errors is a query of its own branch — no routing, no cursor, no mailbox | lane launched |
| 8.3 | Each evaluation response carries the open errors on its branch that occurred after the previous evaluation's stored basis | lane launched |
| 8.4 | Core faults (not agent mistakes) are also recorded on the cluster branch with their origin branch, so status, root, stewards and `faults --wait` see platform bugs hit inside attempts, and unlinking a branch never drops an open core fault. Owner 2026-09-24: a core fault does NOT wake root's paid LLM turn until root's context can act on one (a config dial, default off); it stays recorded and visible in status, the MCP error window and the faults feed | lane launched |
| 8.5 | Errors are data, never program: they never merge; an agent's mistakes go with its branch | met by the partition |
| 8.6 | MCP JVM-mode probe errors return to their caller and are never recorded as cluster faults. **RULED (owner 2026-09-25):** when a cluster database is available, an MCP JVM-mode evaluation is recorded as a root evaluation on the one REPL path, floor-rendered, without custody; its errors are recorded like any evaluation's | ruled |
| 8.7 | The per-session `:seon.dev.mcp/new-faults` cursor (`e868e5ec5`) is deleted, replaced by 8.3 | lane launched |

## 10. Contracts and isolation stance (owner 2026-09-24)

| # | Requirement | Status |
|---|---|---|
| 10.1 | No `:any`/`:some` ban in function contracts, core or agent: at most one advisory ("deliberate?") silenced by a reason annotation | ruled; lane updating the gate |
| 10.2 | A stored attribute schema must map to a storable Datahike value type — a storage constraint, stated as such in its refusal | ruled |
| 10.3 | Root may execute anything outside SCI (JVM mode) against a cluster database; agents work in SCI. True isolation is not a goal | ruled |
| 10.4 | The goals: full environments cheap to spin up (checkpoint fork + branch), agents collaborating through merge, and agents unable to easily take down the cluster — reviews before merge are the main protection | ruled; drives the checkpoint and merge work |

## 9. nil means one thing (owner 2026-09-24: "be very clear about what we mean from the start rather than hacking the contracts and having ambiguous nils")

| # | Requirement | Status |
|---|---|---|
| 9.1 | A returned `nil` or empty collection means only "nothing / absent / no work"; a predicate's `false` is an ordinary answer, never an encoded failed read. Every failure or unknown returns its declared error value (or throws its declared refusal); never nil, a default or an empty result | ruled |
| 9.2 | The fix is at the producers, not in contract spellings: no invented "nothing" schema; core contracts keep `:nil`/`[:maybe]` where nil truly means nothing | ruled; census running (`ambiguous-nil-producers-2026-09-24.md`) |
| 9.3 | Catch sites either handle a declared case or resurface the whole cause (existing law); a catch that returns nil/default is a defect | law; census running |
| 9.4 | A lookup distinguishes "no such row" (nothing) from "could not read" (declared error) | census running |
| 9.5 | **RULED (owner 2026-09-25):** rendering never realizes a lazy value; a saved observation keeps its exact shown bytes; continuing a retained value needs valid origin evidence, and realizing is a new, explicit, bounded evaluation; an unrealized result shows a declared "unrealized lazy seq" marker with its class, and realizing a bounded page happens only on explicit request (the HTML expander or a REPL call), never as nil | ruled |

## 6. Defects the runs exposed (each becomes a task at migration, 7.12)

- Migrated to tasks (slice 2, 7.15); this list no longer holds them: `"132fee0467c8"` definitions-bypass-entry-gate
  (`resources/seon/tasks/seon.sci.eval.edn`) and `"cea47b015b23"` agents-can-validate, formerly
  `:seon.schema.task/agents-can-validate` (`resources/seon/tasks/seon.schema.edn`). Evidence:
  `docs/prds/agent-platform/landing/lane-impl-task-slice2-2026-09-25.md`.
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
