---
type: research
status: current
created: 2026-09-24
scope: sixteen scenarios pushed through one task mechanism; the smallest task data model; where the current design fails
---

# One task mechanism, sixteen scenarios

**Recommendation (UNVERIFIED design):** keep one `seon.task` family for *authored intent*
and put everything that happens while the work runs on a separate `seon.task.attempt`.
A task names its perspective (`ns`), its context function and two kinds of exit.
**Requirements** are evidence that this piece of work is done; they stay on the attempt's
branch or in the task and never merge. **Guarantees** are `deftest`s that merge as program
and keep passing. Deliverables are program symbols, schema keys, or *rows of a named data
schema* (finding, child tasks, review observation, decision), written through that
schema's owner and never merged as program.

Evidence: the schemas named in the assignment, `config/default.edn:511`, `src/seon/issue.clj`,
`src/seon/schedule.clj:46`, `src/seon/cluster/source.clj:736`, `src/seon/config.clj:610`, the
toy experiment and Astra review (this directory). Source was read; nothing was installed or run.
Notation: `id/id` is `seon.id/id`, as `seon.issue/subject-id` uses it (`src/seon/issue.clj:538`).
**R** = requirement (does not merge); **G** = guarantee (merges).

---

## 1. A user asks a question

- **Trigger:** a human `seon.message`. `:seon.message/to` is listened and opens a turn, and
  answeredness is already derived from the wake `:t` (`seon.wake.edn`). **No task by
  default: the wake is the unit of work.** When the user wants it verifiable, the agent
  creates one:

  ```clojure
  #:seon.task{:id (id/id {:seon.task/producer 'seon.message/question :seon.message/id "m-81"})
              :title "Answer: which exercises lost volume this month?"
              :subject [:seon.message/id "m-81"]
              :done-query [:find ?r . :in $ ?q :where [?r :seon.message/caused-by ?q] [?r :seon.message/from _]]}
  ```

- **Exit:** R only: a reply caused by the message exists. **Whether it answers cannot be
  decided by a query or test**; the honest exits are user acknowledgement on the chain or a
  root decision. The draft's "no reply = a red `done-when` test" (D §1.6) is not a `deftest`.
- **Deliver:** the reply, through the reply writer; nothing cherry-picked. **Context:** the
  chain (`caused-by`), the namespace, the queried data's renderers. **Verify:** settlement
  after the turn. **Recur:** never.

## 2. "Build me a workout tracker": decomposition

- **Trigger:** the user message; the agent calls `(seon.task/create! …)` for one *planning*
  task whose deliverable is data: child task rows.

  ```clojure
  #:seon.task{:id "workout-tracker" :why "Log lifts and rest days; weekly summary; a page."
              :ns my.workout :context-fn seon.task/decompose :output :seon.task/task
              :done-query [:find ?c . :in $ ?t :where [?c :seon.task/parent ?t]]}
  ;; accepted output (abbreviated):
  #:seon.task{:id "workout/schema" :parent "workout-tracker" :ns my.workout
              :delivers-keys #{:my.workout/entry :my.workout/kind :my.workout/sets}
              :requires #{my.workout-test/entry-kinds-validate}}
  #:seon.task{:id "workout/log" :parent "workout-tracker" :needs #{"workout/schema"}
              :delivers #{my.workout/log-entry} :guarantees #{my.workout-test/a-rest-day-is-logged}}
  #:seon.task{:id "workout/summary" :parent "workout-tracker" :needs #{"workout/schema"}
              :delivers #{my.workout/weekly-summary}
              :guarantees #{my.workout-test/summary-counts-kinds}}   ; property, independent oracle
  #:seon.task{:id "workout/page" :parent "workout-tracker" :needs #{"workout/schema"}
              :delivers #{my.workout/render-entry-ai my.workout/render-entry-html}}
  ```

- **Split:** schema-first is a **declared** need, because no call edge exists yet.
  `summary → log` is **derived**: its guarantee calls `log-entry`, which another task
  delivers (the toy's derived-order query). A derived need is computed per read and never
  stored. One attempt, agent and branch per child keeps each context small.
- **Exit:** planning R = children exist *and root accepted them*. Each child's G merges
  atomically with its code under the combined gate.
- **Deliver:** children merge code; the planner's rows go through the task writer on the
  shared head and its SCI scratch is discarded. **Context:** the message, existing `my.*`
  schemas for reuse, the task primer — no code. **Verify:** root (scenario 11), then tests.
- **Fails today:** `prepare-merge!` refuses additions ("Only replacements of existing
  functions and tests merge here", `src/seon/cluster/source.clj:768`); the draft's
  `delivers` cannot name schema keys (Astra); there is no path for data deliverables.

## 3. A contract refusal in `my.workout` → triage → fix

- **Trigger:** the error writer stores a `seon.error` keyed by signature
  (`seon.error.edn:272`). A producer joins its function to its namespace, the join
  `:seon.error/of-steward` derives today (`seon.error.edn:227`), and upserts one task per
  signature:

  ```clojure
  #:seon.task{:id (id/id {:seon.task/producer 'seon.task/from-error :seon.error/signature "9f3c…"})
              :title "Triage: my.workout/log-entry refused a rest day" :ns my.workout
              :context-fn seon.task/triage-error :subject [:seon.error/signature "9f3c…"]
              :output :seon.task/task}                         ; emits the fix task or a verdict
  #:seon.task{:id "…/fix" :parent "…" :needs #{"…"} :ns my.workout :context-fn seon.task/fix-error
              :delivers #{my.workout/log-entry}
              :requires #{my.workout-test/rest-day-regression}      ; red on base, green on branch
              :guarantees #{my.workout-test/rest-day-regression}}
  ```

- **Split:** triage first. It reproduces from the stored offending input and classifies:
  agent mistake, core fault, product question (ask root), duplicate. Only a core fault
  outputs the fix task, with a **declared** need on triage. A recurrence adds an occurrence
  component to the same error, never a second task.
- **Exit:** fix R = the regression fails on base *for the intended behavioural reason*,
  not a setup error (Astra), and passes on the branch. G = it and every current test reaching
  `log-entry` pass on the combined head.
- **Deliver:** the function, the regression and any schema change, named explicitly. A
  reached helper is *proposed*; root accepts it. **Context:** the error with its native Malli
  explanation, `:description` along the paths, `log-entry`'s source, contract and
  `must-read`s, the re-runnable call, reaching tests' states. **Verify:** settlement after
  each turn; root checks diff and oracle. **Recur:** reopened by the producer when the
  signature recurs after acceptance; attempt history shows the earlier fix.

## 4. Staleness: body changed after its docstring; a must-read changed

- **Trigger:** a detector in the existing `seon.issue/generate` shape. It compares current
  declaration and doc digests with those a **review observation** last examined (Astra
  G6-B), not transaction order.

  ```clojure
  #:seon.task{:id (id/id {:seon.task/producer 'seon.task.detect/doc-older-than-body :seon.fn/sym 'my.workout/log-entry})
              :ns my.workout :context-fn seon.task/review-doc :subject [:seon.fn/sym my.workout/log-entry]
              :output :seon.review/observation}          ; a new docstring OR "still accurate"
  ```

  A changed must-read has the same shape (producer `must-read-changed`, subject = the
  declarer; context adds the must-read's old/new source); it fans out to every declarer.
- **Exit:** R = the detector no longer names the subject, the rule `seon.issue/done?` already
  applies to detector issues (`src/seon/issue.clj:1014`). Two ways there: a docstring edit (G
  if an example test comes with it), or an observation `{subject, doc, must-read digests,
  author, reason}` — data, never program.
- **Context:** old/new body (history), docstring, must-read diff, callers. **Verify:** the
  detector. **Recur:** each program change; a new body reopens the same identity.
- **Fails today:** transaction order misses body-only edits (Astra); no observation schema
  exists; forcing a docstring edit just to clear a flag is reward hacking.

## 5. Recurring maintenance: collection, reindex, a nightly measurement

- **Trigger:** `seon.schedule.fire`, one entity per firing. Root's portfolio is a constant
  seeded as initialization data (`src/seon/schedule.clj:46`).

  ```clojure
  #:seon.task{:id "root/maintenance/compact" :function seon.maintenance/collect!  ; no model
              :schedule [:seon.schedule/id "root/maintenance/compact-schedule"] :for "root"
              :output :seon.maintenance.result/entity}
  #:seon.task{:id "root/measure/eval-latency" :function seon.profile/measure-probe!
              :schedule [:seon.schedule/id "nightly"] :output :seon.profile/measurement
              :done-query [:find ?m . :in $ ?t :where [?m :seon.profile/task ?t]
                           [?m :seon.profile/elapsed-ms ?ms] [(< ?ms 1000)]]}
  ```

- **Split:** each fire is one attempt, `(id/id [task-id fire-id])`. A failed bound is itself
  a producer: settlement upserts a child *agent* task, "eval latency over 1 s: name the armed
  functions" (scenario 13).
- **Exit:** mechanical = a receipt with a result and no error (today's
  `seon.maintenance.receipt` becomes the attempt); measurement = the bound query. Neither
  merges. **Context:** none for mechanical; the child gets the measurement, armed-call
  profile, parent/change timings. **Verify:** settlement; an errored receipt is a core fault
  (store and deliver). **Recur:** the schedule; identity stable, attempts accumulate.
- **Fails today:** `seon.schedule.task` has no exit condition and no route from a failed
  bound to work; request, receipt and fire are three runtime entities for one attempt;
  profiles are volatile (D §2), so a measurement has nothing durable to cite.

## 6. System setup from preloaded EDN in a fresh cluster

- **Trigger:** `:seon.config/initialization` rows, exact-reconciled by
  `seon.config/apply-compiled!` (`src/seon/config.clj:653`).

  ```clojure
  {:seon.task/id "setup/workout-agent" :seon.task/function seon.cluster.agent/ensure!
   :seon.task/ns my.workout
   :seon.task/done-query [:find ?a . :where [?a :seon.agent/namespace ?n] [?n :seon.ns/name my.workout]]}
  {:seon.task/id "setup/weekly-review" :seon.task/ns my.workout
   :seon.task/context-fn seon.task/improve-ns :seon.task/schedule [:seon.schedule/id "weekly"]}
  {:seon.task/id "setup/meal-schema" :seon.task/ns my.meals
   :seon.task/delivers-keys #{:my.meals/meal} :seon.task/requires #{my.meals-test/meal-validates}}
  ```

- **Split:** declared `needs` order the preload; mechanical rows run at once, agent rows
  await an attempt. **Exit:** each row's R; setup is done when every preloaded R holds —
  one query, and a status line. **Deliver/Context/Verify/Recur:** per row.
- **Fails today, twice:** (a) `require-functions!` refuses every manifest qualified symbol
  without a `:seon.fn/sym` row (`src/seon/config.clj:610`), so a task naming a not-yet-written
  function *or any test symbol* is refused at apply; obligation attributes must be exempt
  by schema (values, not refs). (b) Exact reconciliation would retract progress on the same
  entity; hence runtime lives on the attempt (Astra G2: never reconcile progress).

## 7. "Improve yourself" for a namespace

- **Trigger:** a recurring preload (scenario 6) or root; one task per namespace per period.

  ```clojure
  #:seon.task{:id (id/id {:seon.task/producer 'seon.task/improve-ns :seon.ns/name 'my.workout})
              :ns my.workout :context-fn seon.task/improve-ns :output :seon.task/task
              :why "Propose the smallest changes that serve how the system actually uses you."}
  ```

- **Split:** it delivers **proposals only**, each a child with a concrete exit: "p95 of
  `weekly-summary` 45 → <10 ms (bound)", "delete `set-volume`: 0 callers (empty query)",
  "render pair for `:my.workout/summary` (detector)", "split `log-entry`; guarantees
  unchanged". Never "make it better".
- **Exit:** R = children exist and root accepted them; "nothing to do" is a valid
  observation. **Context (the neighbourhood matters here):** reverse `:seon.fn/calls` from
  other namespaces, errors via `of-steward`, profile cells of its armed functions, which
  renderers users request, `seon.issue.detect/public-without-*` subjects
  (`src/seon/issue/detect.clj:185`), its open tasks.
- **Verify:** root, then each child's exit. **Recur:** the schedule; open proposals show
  rather than duplicate, because child ids derive from producer + subject.
- **Fails today:** profile and page-interest evidence is not stored.

## 8. Cross-namespace workspace: `my.meals` analysis inside `my.workout`'s page

- **Trigger:** the user: "show protein vs volume on my workout page."

  ```clojure
  #:seon.task{:id "meals-on-workout" :ns my.workout :output :seon.task/task}
  #:seon.task{:id "meals/protein-by-week" :parent "meals-on-workout" :ns my.meals
              :delivers #{my.meals/protein-by-week} :guarantees #{my.meals-test/protein-sums-meals}}
  #:seon.task{:id "workout/page-joins-meals" :parent "meals-on-workout" :ns my.workout
              :needs #{"meals/protein-by-week"}
              :delivers #{my.workout/render-page-html my.workout/volume-vs-protein}
              :guarantees #{my.workout-test/page-includes-protein-unit}}
  ```

- **Split:** one child per namespace perspective (D open decision 4). The need is declared
  until `my.workout`'s test calls `my.meals/protein-by-week`, then derived.
- **Exit:** G per child; the page guarantee asserts the rendered *value* contains the
  protein unit (behaviour, not a string). Browser paint is a separate observed R.
- **Context:** the meals agent sees the consuming test and the parent's `why`; the workout
  agent sees `protein-by-week`'s contract and schema, not the whole namespace.
- **UNVERIFIED:** whether a page may compose another namespace's declared query under the
  one-render-pair rule; probe `seon.render/selection` before pricing.

## 9. Research whose deliverable is a finding

- **Trigger:** root or a triage verdict, e.g. "why does a trivial SCI eval take 833 ms?" (D §8).

  ```clojure
  #:seon.task{:id "research/sci-eval-833ms" :for "root" :context-fn seon.task/investigate
              :output :my.finding/finding
              :done-query [:find ?f . :in $ ?t :where [?f :my.finding/task ?t] [?f :my.finding/evidence _]]}
  ```

- **Exit:** R = a finding whose evidence refs resolve (runs, profiles, errors, commits,
  `seon.issue.citation`-shaped `file:line` components); its correctness is a root decision.
  Nothing merges as program; probes stay queryable on the branch until unlink. The finding
  may output fix tasks.
- **Deliver:** the finding row via its writer on the shared head; it outlives the branch.
  **Context:** the question, cited measurements, the owning dependency's source. **Verify:**
  root. **Recur:** never.
- **Fails today:** no finding schema; `my.note` is agent-owned prose with one `about` ref and
  no evidence refs; the draft merges only program.

## 10. Dependency pin bump

- **Trigger:** a changed gitlink or deps coordinate (pins are checked, D §2, `186957285`).

  ```clojure
  #:seon.task{:id (id/id {:seon.task/producer 'seon.task/from-pin-change :dep 'io.replikativ/datahike :rev "c79cd03a"})
              :title "Datahike pin moved: re-verify wrappers" :for "root" :output :seon.task/task}
  ```

- **Split:** fan-out by query: one scenario-4 review child per first-party wrapper whose
  `:seon/must-read` points into the dependency, plus one child running every test reaching
  those wrappers at the new pin.
- **Exit:** R = those tests green at the new pin and an observation per wrapper over the new
  must-read digest. G is unchanged (the existing tests). **Context:** the upstream diff
  restricted to must-read Vars, each wrapper's docstring (its lesson). **Recur:** every pin change.
- **Fails today:** library rows and pinned must-reads are not installed (D §5): nothing to query.

## 11. Root review and the escape hatch

- **Trigger:** an attempt settling green, or an agent's `(seon.task/dispute! {:task … :argument …})`.

  ```clojure
  #:seon.task{:id (id/id {:seon.task/producer 'seon.task/review :seon.task.attempt/id "a-3f…"})
              :for "root" :context-fn seon.task/review-attempt :subject [:seon.task.attempt/id "a-3f…"]
              :output :seon.task.decision/decision}
  #:seon.task.decision{:task [:seon.task/id "<review id>"] :choice :revise :revises [:seon.task/id "workout/log"]
                       :reason "rest entries must have no sets; the test asserted empty"}
  ```

- **Exit:** R = a decision: accept (merge), revise, send back (new attempt), reject (unlink).
  *Revise* is the only way requirements change: an ordinary transaction on the task, reason
  in transaction provenance, so `history` answers what they were; no new id.
- **Context:** the task and frozen requirement digests, the attempt's program diff plus
  proposed helpers, the test oracle and helper changes, the argument message, budget use.
  **Verify:** named root/owner acceptance advances the cluster pointer. **Recur:** per attempt.

## 12. Three agents given the same task

- **Trigger:** `(seon.task/attempt! {:seon.task/id "workout/summary" :count 3 :models […]})`.
  The task is unchanged; only attempts differ:

  ```clojure
  #:seon.task.attempt{:id (id/id ["workout/summary" 2]) :task [:seon.task/id "workout/summary"]
                      :agent [:seon.agent/id "summary-2"] :branch :summary-2 :frozen #{"…test digests…"}}
  ```

- **Exit:** each settles on its own branch; comparison is a query over attempts (turns,
  forms, tokens/cost, elapsed, requirement state, diff size). One review task takes all
  three; one is accepted, the others unlinked and GC'd, queryable until retention reclaims.
- **Fails today:** `:seon.issue/agent` is cardinality-one, "asserted exactly once", with
  budget and `resolved-tx` on the issue (`seon.issue.edn:31-39`): one worker per issue.

## 13. Performance regression (clock row over bound)

- **Trigger:** a failed measurement R (5), or a hot-path parent/change probe beyond 20% or
  50 ms (laws). **Task:** producer `seon.task/from-bound`, subject = the probe, `:ns` = the
  namespace of the top armed profile cell. **Exit:** R = the bound on the same probe, parent
  vs branch; G = the merged bound test (fails beyond its declared bound) plus reaching
  tests. **Context:** profile cells naming the armed functions; both timings.

## 14. A schema without a render pair

- **Trigger:** `seon.issue.detect/entity-map-without-pair` (`src/seon/issue/detect.clj:61`)
  already yields one-identity subjects. **Task:** subject = the schema key,
  `:delivers-keys #{:my.workout/summary}`, `:delivers` the AI/HTML pair. **Exit:** R = the
  detector stops naming it; G = a render example on a real entity. **Lesson:** today's
  detector path already *is* a task producer; it lacks only a context function and attempts.

## 15. A design decision that needs the owner

- **Trigger:** a genuinely new cross-owner decision (laws: three priced options).
  **Task:** `{:seon.task/for "owner" :seon.task/output :seon.task.decision/decision}` with the
  options as data; dependent tasks declare `:needs` on it. **Exit:** R = the owner's decision
  row, written by the page form (nobody parses a reply). A child docs task folds the ruling
  into the owning spec.

## 16. Same-identity merge conflict

- **Trigger:** `prepare-merge!` answers a three-way conflict (`src/seon/cluster/source.clj:766`);
  the laws make it a root task. **Task:** subject = the intermediate branch and conflicting
  identities, `:context-fn seon.task/resolve-conflict`. **Exit:** the combined gate green there.

---

## The smallest task data model

**Authored intent: `seon.task`** (`:seon.program/partition :seon.data`), reconcilable from
EDN, holding nothing that changes while work runs.

| Attribute | Type | Meaning |
|---|---|---|
| `:seon.task/id` | `:string`, identity | **One identity:** authored = its slug; generated = `(id/id {:seon.task/producer p <subject-identity-attr> v})` as `seon.issue/subject-id` does, so reruns upsert. Never keyed by entity id, title or occurrence. |
| `:title`, `:why` | `:string` | Summary; case-specific instructions. A task *type's* standing instructions are its context-fn docstring. |
| `:ns` | `:symbol` (namespace-name schema, not `:qualified-symbol`, per Astra) | Perspective and responsibility, not merge scope. |
| `:context-fn` | `:qualified-symbol` | The task type, `(f db task) → forms`. |
| `:function` | `:qualified-symbol` | Mechanical executor, no model; replaces `seon.schedule.task/function`. |
| `:subject` | ref (sweep) | Error, function, schema key, message, attempt; identity already survives in `:id`. |
| `:producer` | `:qualified-symbol` | The generating detector/producer; "no longer names it" is an R. |
| `:requires` | set `:qualified-symbol` | **Requirement** tests: may be unresolved obligations, live on the attempt branch, never merge. |
| `:done-query` | `:seon.db/query` | Requirement query; `my.plan.item/done-query` semantics (`$ ?task`, errors never complete). |
| `:guarantees` | set `:qualified-symbol` | Tests that merge with the code and must keep passing; may overlap `:requires`. |
| `:delivers` | set `:qualified-symbol` | Functions/tests allowed to merge; reached helpers are proposed for root. |
| `:delivers-keys` | set `:qualified-keyword` | Schema keys allowed to merge; separate so both stay native (a mixed `:or` becomes an EDN string, `src/seon/schema/datahike.clj:116`). |
| `:output` | `:qualified-keyword` | A data deliverable's entity schema; its rows go through that owner's writer and cite this task. |
| `:needs` | set of `:seon.task/id` values | **Declared** order only (value edge, like `my.plan.item/needs`). **Derived** order is a query over tests calling what open tasks deliver; never stored. |
| `:parent` | ref | Decomposition; not a component, so children survive the planner. |
| `:schedule` | ref | Recurrence (`seon.schedule`). |
| `:for` | `:seon.agent/id` value | Who acts or decides when not a fresh worker (`"root"`, `"owner"`). |
| `:budget` | `:int` | Default ordinary-turn budget per attempt. |

**Runtime: `seon.task.attempt`**, never reconciled; one per start, fire or parallel run:
`:id` (`id/id [task-id ordinal-or-fire-id]`), `:task` (required ref), `:agent` (listened,
opens a turn — the assignment is the first wake, as `:seon.issue/agent` is today),
`:branch`, `:fire`, `:frozen` (requirement definition digests at start, or named unresolved
obligations), `:budget`, `:budget-exhausted-tx`, `:runs` (test runs, receipts),
`:settled-tx` (settlement: every R held), `:accepted-tx` (root's decision merged it). Done,
blocked, ready and stale are *derived presentation states*, like `:my.plan/state`.

**Where facts live.** Intent on the task (reconciled; `as-of` for its past). Progress on the
attempt. Evidence with its existing owners (test runs, error occurrences, maintenance
results, messages). Findings and decisions as `:output` rows. Revision reasons in transaction
provenance. Attempt facts stay on the branch or head until unlink; retention GC reclaims.

**Absorbs (UNVERIFIED sizing):** the `seon.issue` lifecycle (an indexed note is an authored
task with `:path`; citations stay), `my.plan.item` (a plan is a query by `:parent`/`:for`),
`seon.schedule.task`, and `seon.maintenance.request`/`receipt` (attempt plus request
component). Convert each family's callers in one slice; never run two side by side.

## Task producers

1. An agent's `seon.task/create!` on a user request (2, 8, 15); accepted `:output`
   children of planning, triage, improve and pin tasks (2, 3, 7, 10).
2. The error writer: one task per `seon.error/signature` (3).
3. Detectors in the `seon.issue/generate` upsert shape: doc/body staleness, must-read
   changed, missing render pair, public without doc/contract/reaching test (4, 14).
4. Schedule fires, one attempt each (5, 7).
5. Settlement: a failed bound (5, 13), a green attempt → review (11), budget exhaustion →
   root (loud, resumable).
6. EDN preload via `:seon.config/initialization` (6).
7. Dependency revision changes (10).
8. Merge conflicts (16) and agent disputes (11).

## Where the current design fails a scenario

1. **Additions cannot merge:** `prepare-merge!` refuses added definitions
   (`source.clj:768`), blocking every constructive task (2, 8, 13, 14). Extend that owner;
   no second merger.
2. **One worker per issue:** `:seon.issue/agent` asserted once, budget and `resolved-tx` on
   the issue (`seon.issue.edn:31-39`) — blocks recurrence (5), send-back (11), parallel
   attempts (12). Runtime must move to an attempt.
3. **Only program merges:** no data deliverable, blocking 2, 3 (verdict), 7, 9, 10, 11, 15.
   A named `:output` schema written through its owner is the missing piece.
4. **Deliverables cannot name schema keys** (Astra): 2, 8, 14.
5. **"Done = `deftest` green" is too narrow and too strong:** 1, 5, 6, 9, 15 finish on
   queries, receipts, bounds or decisions; red requirement tests on the shared head poison
   unrelated merges (Astra G4). Requirements vs guarantees fixes both.
6. **Preload refuses obligations:** `require-functions!` rejects symbols with no
   `:seon.fn/sym` row, test symbols included (`config.clj:610`); exact reconciliation would
   overwrite progress unless runtime is split (6).
7. **Staleness by transaction order** misses body-only edits, and nothing lets a reviewer
   clear a flag honestly (4, 10).
8. **Improve/performance evidence is volatile:** profiles and page interest are not stored (5, 7, 13).
9. **Answer quality is unverifiable** (1, 9): route to user or root acknowledgement; never
   pretend a test covers it.
10. **Declared and derived needs are conflated:** plan items store `needs`, the toy kept
    order in prose. Store only declared needs; derive the rest by query.
11. **Three done rules await collapse:** `issue/done?`, `my.plan.item/done-query`, and
    receipts. One settlement over `:requires`/`:done-query`/`:producer` replaces them
    (UNVERIFIED until callers are counted).

**Next probe (smallest slice, UNVERIFIED):** scenario 3 end to end on a scratch cluster:
one error-signature producer, one task with two attempts, settlement over `:requires`, and
`prepare-merge!` admitting one added regression test. Time every step.
