---
type: plan
status: approved shape (owner 2026-09-16 02:30Z: "one family"); implementation lane `issue-family`
created: 2026-09-16
tags: [plan, steward, schema, issue, task]
---

# `seon.issue` — one family for issues and tasks

Owner rulings this spec obeys: a task is data; success is a set of deftests,
at least one, all verified on the current reach digest; an issue with tests,
functions and an assigned agent IS a task; issues must be linked to the
program graph and assignable, never prose that rots; Clojure and Datahike
terms only; refs, never strings for symbols; record for future tasks.
Grounding: [namespace-data-model](namespace-data-model-2026-09-16.md) §0,
§3.3, §8, §9; the [task prototype](task-prototype-2026-09-16.md) mechanics;
`docs/seon/issues/README.md` (lifecycle, severity); the 240 notes' measured
shape (Problem 159, Evidence 142, Acceptance 163, Owner 136; 156 commits,
59 tests, 137 files, 113 symbols cited).

## 1. Schema (`resources/seon/schemas/seon.issue.edn`)

```clojure
#:seon.issue{:id        [:string {:min 1 :seon.db/identity true :description "The note's file slug for indexed issues; seon.id/id of [title subject] for issues authored in the database."}]
             :title     [:string {:min 1}]
             :status    [:enum {:description "The issues README lifecycle; a closed set."} :open :resolved :superseded]
             :severity  [:enum :blocker :friction :cleanup]
             :opened    :inst
             :path      [:string {:min 1 :description "Repository path of the note whose sections are the prose; absent for database-authored issues."}]
             :problem   [:string {:min 1 :description "The Problem section, or the instructions to an agent."}]
             :functions [:set {:description "Program entities the issue is about: cited qualified symbols resolved to :seon.fn entities at index time."} :seon.db/ref]
             :tests     [:set {:description "The deftests whose verification resolves the issue (the Acceptance section as code). Workers add, never remove; the writer refuses an empty set on a started issue."} :seon.db/ref]
             :errors    [:set {:description "seon.error entities cited by signature."} :seon.db/ref]
             :commits   [:set :seon.source/commit-id]
             :members   [:set {:description "A class note's member issues."} :seon.db/ref]
             :agent     [:and {:description "The agent working it; asserted by start! in the transaction that creates the agent and opens its first turn."} :seon.db/ref]
             :budget    [:int {:min 1 :description "Provider turns for the worker; copied to its settings overlay at start."}]
             :resolved-tx [:and {:description "The transaction recording that every test verified; absence means open. Written by settlement, never asserted by a model."} :seon.db/ref]
             :issue [:map {:seon.db/attributes true
                           :seon.render/units [:seon.issue/functions :seon.issue/tests :seon.issue/errors :seon.issue/members]
                           :seon.render/ai seon.issue/render-ai :seon.render/html seon.issue/render-html}
                     [:seon.issue/id :seon.issue/id] [:seon.issue/title :seon.issue/title]
                     [:seon.issue/status :seon.issue/status] [:seon.issue/severity :seon.issue/severity]
                     [:seon.issue/problem :seon.issue/problem]
                     [:seon.issue/opened {:optional true} :seon.issue/opened]
                     [:seon.issue/path {:optional true} :seon.issue/path]
                     [:seon.issue/functions {:optional true} :seon.issue/functions]
                     [:seon.issue/tests {:optional true} :seon.issue/tests]
                     [:seon.issue/errors {:optional true} :seon.issue/errors]
                     [:seon.issue/commits {:optional true} :seon.issue/commits]
                     [:seon.issue/members {:optional true} :seon.issue/members]
                     [:seon.issue/agent {:optional true} :seon.issue/agent]
                     [:seon.issue/budget {:optional true} :seon.issue/budget]
                     [:seon.issue/resolved-tx {:optional true} :seon.issue/resolved-tx]]}
```

Derived, never stored: namespaces (through `functions → ns`), owner and
steward (through `ns → steward`), "unassigned" (no function ref, or a
namespace with no steward), "in flight" (agent present, no resolved-tx),
"red" (a test in `tests` not verified). Tags are not stored: `render`,
`schema`, `runtime` were prose classification; the function refs classify.
`class/…` tags become `members` on the class note's entity. `wave/…` tags are
dropped (dated coordination, not facts).

Accretion elsewhere: `resources/seon/schemas/seon.agent.edn` adds
`:seon.issue/_agent` to the agent's units after `:seon.agent/plan`.

## 2. Functions (`src/seon/issue.clj`, agent-facing surface `src/my/issue.clj`)

| function | does |
|---|---|
| `seon.issue/index-tx` (db, notes) → tx-data | Pure. For each note: slug, title (first `#`), frontmatter `status`/`severity`/`created`, the Problem section text, cited `ns/sym` → `[:seon.fn/sym …]` refs that resolve to existing entities (unresolved symbols are reported, never stored as strings), cited `-test/` symbols → `:seon.test` refs, cited 64-hex signatures → `[:seon.error/signature …]`, cited 9-hex commits, `class/<x>` tag → `members` on the class entity. Exact replacement per slug through the existing program-entity replacement discipline; a note removed from the folder retracts its entity's facts, the identity survives. |
| `seon.issue/index!` | Runs at publication beside source indexing (`bin/seon init`, the hook's adoption) so entities follow the folder; replaces `script/seon/dev/issues.clj` and `bin/issues-index` in place (the index page becomes a query over entities; the checker becomes the indexer's own refusals). |
| `seon.issue/start!` | One transaction function: `seon.cluster.agent/creation-tx` for a worker (id `seon.id/id` of `[issue-id]`, namespace = the issue's first function's namespace unless supplied), assert `:seon.issue/agent` and `budget` overlay, author the plan (objective = problem; one step with `subject` = the issue and `done-query` = every test verified), open the first turn with `seon.turn/generated-run-tx` (trigger = the issue). Refuses when `tests` is empty or an agent is already assigned. This is `seon.cluster/ensure-entity!` generalised; the bootstrap's hard-coded task becomes an issue entity in a later slice. |
| `seon.issue/status` | Read: the issue, each test's state (unrun/red/verified on the current reach digest via `seon.test/verified?` (db test) once lane reach-digest lands; interim: latest result green after the issue's start), each error's occurrence count, the completing calls. |
| `seon.issue/render-ai` / `render-html` | AI: `;; My issue. Its tests define done; (my.test/check …) runs them.` + `(my.issue/status {:seon.issue/id …})`. HTML: the same as a block. |
| `my.issue/add!`, `my.issue/tests!` | Agent surface: author an issue entity; add tests to an issue (never remove). Call preparation supplies connection and agent. |

Settlement (slice 2, lane reach-digest's `verified?` prerequisite): before
`plan/settle-call`, run the open issue's tests in-process with
`seon.test/run`; `done-query` reads the run entities; `resolved-tx` is written
when the step completes.

## 3. Regressions (canonical harness, armed)

1. Indexing three real notes (one class note with members, one with a cited
   test and commits, one with an unresolvable symbol) yields entities with the
   expected refs; the unresolvable symbol is reported, not stored.
2. Re-indexing after editing a note's status replaces exactly that fact;
   removing the note retracts its facts and keeps the identity.
3. `start!` on an issue with one red test creates the agent, plan and open
   turn in one transaction; `start!` again refuses; `start!` with no tests
   refuses.
4. The worker's opening (system turn on the fixture) contains the issue
   block after the plan block, rendered through the pair with the test
   state.
5. A worker retracting a test ref is refused; adding one succeeds.

## 4. Live proof on default

Index the folder; `(count)` of issue entities, unresolved-symbol count,
`[:seon.ns/name seon.render.web]` pull showing its issues through
`:seon.issue/_functions`; `start!` on one real issue with a real red test
(the batch-12b `seon.fn-test` red, or one the owner names); the worker's
opening bytes recorded in the landing note; then a live DeepSeek session,
cheapest model, with the ledger line and explain probe.

## 5. Ownership and boundaries

Owns: `resources/seon/schemas/seon.issue.edn`, `seon.agent.edn` (the one
unit), `src/seon/issue.clj`, `src/my/issue.clj`, `script/seon/dev/issues.clj`,
`bin/issues-index`, `docs/seon/issues/README.md` (the lifecycle stays; the
index becomes a query), tests. Does NOT edit `src/seon/cluster.clj`,
`src/seon/error.clj`, `seon.error*.edn` (lane error-graph), `src/seon/turn.clj`
(lane attempt-and-eval-facts), `src/seon/test.clj`, `test/runner.clj`,
`seon.test.edn` (lane reach-digest), `src/seon/fn.clj`, `program.cljc`,
`seon.fn*.edn` (lane program-provenance). Needed changes there go in the
landing note. Landing note
`docs/prds/steward-platform/research/issue-family-2026-09-16.md`.

## 6. Prerequisites for a worker to complete an issue, in order (owner, 03:05Z: "do those things first")

Vocabulary: entities, attributes, values, refs. The issue block LINKS; it
never copies function source, contract text, test source or error data —
the linked entities render themselves through their own pairs.

| # | Prerequisite | Status | Owner |
|---|---|---|---|
| P1 | The entities and links: issue entity with refs to functions, tests, errors; tests linked to namespaces through their direct calls; function and test entities carry file and span; lint findings as entities; the error entity keyed by its signature with occurrence components; renderer and usage as refs and facts | in flight | lanes issue-family, program-provenance, error-graph, attempt-and-eval-facts |
| P2 | Render pairs for the linked entities: `:seon.fn` has only a legacy `:seon.render/form` (`seon.fn.edn:25`), `:seon.test` has no entity pair (`seon.test.edn`). Without them the issue block cannot link a function or a test and have it render. Function pair: symbol, contract, docstring, callers and reaching tests as counts with the exact query; test pair: symbol, latest result, failing assertions, the functions it calls. Link-only, no source copies (the agent reads source with `doc`/`(seon.db/pull …)`) | queued behind program-provenance (it holds `seon.fn.edn`) | lane `entity-pairs` |
| P3 | `start!`: worker agent + budget overlay + plan (objective = problem, one step with subject = the issue and the done-query) + first turn, one transaction | in flight | issue-family slice 3 |
| P4 | The opening contains the issue block after the plan block, through `:seon.issue/_agent` on the agent's units; proven with a virtual turn on the fixture and on default | in flight | issue-family slice 4 |
| P5 | Tests run after every step: before each `plan/settle-call` (`src/seon/turn.clj:2208`, `:3427`, `:3466`, `:4646`) the turn runs the open issue's tests in-process with `seon.test/run` under the evaluation bound, results recorded with provenance; the done-query reads `verified?` on the reach digest; `resolved-tx` written when the step completes. Never inside a Datahike transaction function | queued behind attempt-and-eval-facts and error-graph (both hold `turn.clj` hunks) and reach-digest (the `verified?` arity) | lane `issue-settlement` |
| P6 | Writer invariant: a worker adds tests to its issue, never retracts them; refused at the writer (`:db.fn/call` guard on the issue writer plus a declared schema property the transact admission checks), not by a caller pre-read | design filed as an issue by issue-family | lane `issue-settlement` |
| P7 | Budget: `:seon.issue/budget` copied to the worker's `max-episode-runs` overlay at start; `turns-left` unchanged | in flight (start!) | issue-family |
| P8 | The first steward: an agent for `seon.render.web`, assigned through `steward-call`; `:seon.ns/steward` set | in flight (live proof) | issue-family |
| P9 | Regeneration: the issue block's read is re-evaluated by the since-diff when its linked entities change (new occurrence, test result); compaction regenerates it | exists (`seon.turn/system-turn`) | — |
| P10 | The live proof: one real issue with a real red test on default, a DeepSeek session on the cheapest model, opening bytes, ledger line, explain probe | in flight after P3–P4 | issue-family |
| P11 | Durability of the worker's results: admitted definitions land on default's branch immediately; per-issue forks and merge come later (roadmap E) | accepted for now | — |

Order of landing: P1 → P2 → P3/P4 (can proceed on the interim done-query) →
P5/P6 → P8/P10. P2 and P5 launch the moment their files are free.

## 7. Generated issues and their identity (owner, 2026-09-16 04:50Z)

Owner: "when we farm issues by detecting schemas without renders, missing
generator functions for generative testing, missing docstrings, or whatever
we come up with, we need good identity functions so we don't create
duplicate issues."

- **Identity = detector + stable subject identity**, hashed by the one
  identity function over a canonically ordered map:
  `(seon.id/id (into (sorted-map) {:seon.issue/detector 'seon.issue/missing-render-pair :seon.schema/key :seon.issue/issue}))`.
  `:seon.issue/id` is a Datahike identity, so every run upserts the same
  entity: no duplicates by construction.
- **Stable subject identities only**: schema key, function symbol, test
  symbol, error signature, file path. Never an entity id (reforks change
  them), never message text. The subject is ALSO stored as a ref
  (`functions`, `tests`, `errors`, `:seon.issue/schema`) for querying.
- **`:seon.issue/detector`** is a ref to the detector function's program
  entity. Detectors are contracted functions `db → subjects`, each subject
  carrying its identity attribute and value (the generator refuses bare
  entity ids). First detectors: entity schema without a render pair (3 on
  default today of 28 declared entity maps); public function without a
  docstring; contract without a generator where `seon.test.accretion/generatable?`
  says one is needed; public function without a reaching test (288);
  contract containing `:any`/`:some`; recurring error without a regression.
- **Ownership of attributes**: the generator writes status, the required
  test refs and the function refs on every run; `problem` only when absent
  (a human's edit survives). When the detector stops yielding a subject the
  run asserts `resolved-tx`; if it reappears the run retracts it; history
  keeps both.
- **Responsible namespace**: the subject's own namespace — for a schema key
  its keyword namespace (a `:seon.ns/name` entity exists for all three
  current hits), for a function its `:seon.fn/ns`; fallback for a family
  with no namespace entity: the functions whose `:seon.fn/keywords` use the
  family's keys. The steward derives from there.
- **The required test** each detector attaches is red until the finding is
  gone: e.g. `seon.render/selection` selects a declared pair for a fixture
  entity of that shape and both projections render totally.
- Function: `seon.issue/generate` (db, detector-fn) → tx-data, called by
  hand first, later by schedule or on adoption; identity as above; one
  regression per detector on the canonical harness proving idempotence
  (two runs, one entity) and resolution/re-open.

## 8. A set of issues seeds an agent; the steward triages (owner, 2026-09-16 05:05Z)

- **`start!` takes a set of issues.** Request `{:seon.issue/ids #{…} :seon.issue/budget n}`;
  one worker agent; objective = the issues' problems; one plan step per
  issue carrying that issue's tests; `:seon.issue/agent` asserted on each;
  refused if any issue is already assigned or has no tests. The session ends
  when every issue in the set has `resolved-tx`, or on budget. A single
  issue is the one-element case; the current slice's single-issue `start!`
  stays as that case.
- **The steward triages.** Its opening lists its namespace's open issues
  (through the namespace view: `:seon.issue/_functions` per function,
  `:seon.issue/_namespaces`), grouped by shared function and by
  `:seon.issue/detector`, and teaches the exact call above. Grouping is a
  judgment made at launch by an agent, never guessed at generation.
- **Checklists are detectors.** The standard for a function / schema / test
  / namespace (priority order: open errors, red or missing reaching tests,
  incomplete contract, docstring with passing example, generators for
  generative checking, lint, unresolved calls, file/span; schema: pair,
  attribute descriptions, identity, fixture example, total render; test:
  reaches a namespace under test, result on current digest, drift-free,
  structured failures, fixture observation; namespace: steward, docstring,
  no readerless duplicates, all public functions conform, no unlinked issue)
  is the ordered set of detector functions, each generating fine-grained
  issues with §7's identity. No separate standing-issue entity.
- **Ripples**: generators re-run (by hand first, on adoption later); the
  steward's opening is a generated read, so its next turn shows issues
  opened and resolved since its last turn.
