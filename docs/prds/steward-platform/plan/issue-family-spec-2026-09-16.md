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
             :functions [:set {:description "Program rows the issue is about: cited qualified symbols resolved to :seon.fn rows at index time."} :seon.db/ref]
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
`class/…` tags become `members` on the class note's row. `wave/…` tags are
dropped (dated coordination, not facts).

Accretion elsewhere: `resources/seon/schemas/seon.agent.edn` adds
`:seon.issue/_agent` to the agent's units after `:seon.agent/plan`.

## 2. Functions (`src/seon/issue.clj`, agent-facing surface `src/my/issue.clj`)

| function | does |
|---|---|
| `seon.issue/index-tx` (db, notes) → tx-data | Pure. For each note: slug, title (first `#`), frontmatter `status`/`severity`/`created`, the Problem section text, cited `ns/sym` → `[:seon.fn/sym …]` refs that resolve to existing rows (unresolved symbols are reported, never stored as strings), cited `-test/` symbols → `:seon.test` refs, cited 64-hex signatures → `[:seon.error/signature …]`, cited 9-hex commits, `class/<x>` tag → `members` on the class row. Exact replacement per slug through the existing program-row replacement discipline; a note removed from the folder retracts its row's facts, the identity survives. |
| `seon.issue/index!` | Runs at publication beside source indexing (`bin/seon init`, the hook's adoption) so rows follow the folder; replaces `script/seon/dev/issues.clj` and `bin/issues-index` in place (the index page becomes a query over rows; the checker becomes the indexer's own refusals). |
| `seon.issue/start!` | One transaction function: `seon.cluster.agent/creation-tx` for a worker (id `seon.id/id` of `[issue-id]`, namespace = the issue's first function's namespace unless supplied), assert `:seon.issue/agent` and `budget` overlay, author the plan (objective = problem; one step with `subject` = the issue and `done-query` = every test verified), open the first turn with `seon.turn/generated-run-tx` (trigger = the issue). Refuses when `tests` is empty or an agent is already assigned. This is `seon.cluster/ensure-entity!` generalised; the bootstrap's hard-coded task becomes an issue row in a later slice. |
| `seon.issue/status` | Read: the issue, each test's state (unrun/red/verified on the current reach digest via `seon.test/verified?` (db test) once lane reach-digest lands; interim: latest result green after the issue's start), each error's occurrence count, the completing calls. |
| `seon.issue/render-ai` / `render-html` | AI: `;; My issue. Its tests define done; (my.test/check …) runs them.` + `(my.issue/status {:seon.issue/id …})`. HTML: the same as a block. |
| `my.issue/add!`, `my.issue/tests!` | Agent surface: author an issue row; add tests to an issue (never remove). Call preparation supplies connection and agent. |

Settlement (slice 2, lane reach-digest's `verified?` prerequisite): before
`plan/settle-call`, run the open issue's tests in-process with
`seon.test/run`; `done-query` reads the run rows; `resolved-tx` is written
when the step completes.

## 3. Regressions (canonical harness, armed)

1. Indexing three real notes (one class note with members, one with a cited
   test and commits, one with an unresolvable symbol) yields rows with the
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

Index the folder; `(count)` of issue rows, unresolved-symbol count,
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
