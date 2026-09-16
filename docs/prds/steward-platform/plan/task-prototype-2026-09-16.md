---
type: plan
status: prototype (iterating with the owner; nothing landed in src/)
created: 2026-09-16
tags: [plan, steward, task, schema]
---

# The task as data — prototype for iteration

Rulings this prototype obeys (owner, 2026-09-15/16): a task is data in the
database; success is a set of deftests, at least one, all passing; settlement
runs them; a session is an agent's turn chain, parallel sessions are parallel
agents; an instance is just an entity; context comes from the existing walk;
no `writes` family; no `:type`/`:kind`; every key namespaced.

Every attribute below that is not under `my.task` exists and is populated on
default today (verified 2026-09-15 23:4xZ: `seon.test/verified?` live, 381
`seon.test.run` rows with program digests, `done-query`/`subject` populated,
`:seon.render/units` driving the opening, `:seon.wake/listen` on four
attributes).

## 1. One family: `my.task`

A row is a task. There is no template/instance split in the schema: a row with
no `:my.task/agent` is unstarted; starting it asserts the agent. Copying a row
for a new subject derives a new id and records `:my.task/from`.

```clojure
;; resources/seon/schemas/my.task.edn (proposed)
#:my.task{:id
          [:string {:min 1 :seon.db/identity true
                    :description "The task's identity; derived through seon.id/id from its title and subject when copied."}]
          :title [:string {:min 1}]
          :instructions
          [:string {:min 1 :description "What the agent is to do and how the session ends, in the second person; rendered verbatim in the task block."}]
          :namespace
          [:and {:description "The namespace the work belongs to; the worker agent is assigned here."} :seon.db/ref]
          :subject
          [:and {:description "Optional entity the task is about: a message, a function row, a test row, a fault."} :seon.db/ref]
          :tests
          [:set {:min 1
                 :description "The deftests that define success; :seon.test rows, owned by the program, never a component. All must be verified on the current program digest."}
           :seon.db/ref]
          :budget [:int {:min 1 :description "Provider turns the worker may spend; copied to its settings overlay at start."}]
          :agent
          [:and {:description "The agent working this task; asserted by start! in the same transaction that creates the agent and opens its first turn."}
           :seon.db/ref]
          :from
          [:and {:description "The task row this one was copied from."} :seon.db/ref]
          :task
          [:map {:seon.db/attributes true
                 :seon.render/units [:my.task/subject :my.task/tests :seon.message/_about]
                 :seon.render/ai my.task/render-ai
                 :seon.render/html my.task/render-html}
           [:my.task/id :my.task/id]
           [:my.task/title :my.task/title]
           [:my.task/instructions :my.task/instructions]
           [:my.task/namespace :my.task/namespace]
           [:my.task/tests :my.task/tests]
           [:my.task/budget :my.task/budget]
           [:my.task/subject {:optional true} :my.task/subject]
           [:my.task/agent {:optional true} :my.task/agent]
           [:my.task/from {:optional true} :my.task/from]]}
```

Two accretions elsewhere, both optional keys:

- `resources/seon/schemas/seon.agent.edn`: add `:my.task/_agent` to the agent
  entity's `:seon.render/units`, after `:seon.agent/plan`. The opening grows a
  task block for any agent holding a task; agents without one are unchanged.
- `resources/seon/schemas/my.plan.item.edn`: nothing. The instance's plan step
  uses `subject` and `done-query` as they are.

Why `{:min 1}` is not enough: Datahike has no minimum-cardinality facet (the
bridge derives only one/many, `src/seon/schema/datahike.clj:194`), so the
writer refuses an empty set inside its transaction function as well.

Why `:my.task/agent` is NOT a wake (owner, 2026-09-16 00:50Z: "the wake
attribute I'm not wild about; focus on going from task definition to a
running agent entity rather than waking and scheduling"): starting a task
does not route a datom to an existing agent; it CREATES the agent entity with
its first turn already open. That is the bootstrap's own path today:
`seon.cluster/ensure-entity!` (`src/seon/cluster.clj:2259`) runs one
transaction function that emits `creation-tx` (agent, namespace, empty plan,
settings, runtime) plus `bootstrap/seed-tx` (namespace requires, the
assignment message, and `turn/generated-run-tx` opening a `:generate` turn);
the armer arms the new agent on that commit and `next-agent-work` finds an
open turn, which outranks any trigger. No wake, no schedule, no listener.

## 2. Two rows as exact transaction data

**A code task, test-first.** The test exists and is red before the function
does; `:seon.test/pending-subject` is the existing test-first shape
(`src/seon/fn.clj:810`, `src/seon/turn.clj:1183`).

```clojure
[{:seon.test/sym "my.agents.root/total-amount-sums-amounts"
  :seon.test/ns [:seon.ns/name my.agents.root]
  :seon.test/pending-subject "my.agents.root/total-amount"
  :seon.test/source
  "(deftest total-amount-sums-amounts
     (is (= 0 (total-amount [])))
     (is (= 155 (total-amount [{:example/amount 60} {:example/amount 55} {:example/amount 40}]))))"}
 {:my.task/id "root/total-amount"
  :my.task/title "Define total-amount"
  :my.task/instructions
  "Define a durable contracted function my.agents.root/total-amount that sums :example/amount over a vector of maps and returns 0 for empty input. Make the task's tests pass with (my.test/check {:seon.test/changed [\"my.agents.root/total-amount\"]}); the session ends when they are verified."
  :my.task/namespace [:seon.ns/name my.agents.root]
  :my.task/tests #{[:seon.test/sym "my.agents.root/total-amount-sums-amounts"]}
  :my.task/budget 12}]
```

**A communication task.** The subject is the unanswered message
`e10231f6` (root → juniper, audit B's false witness). The test asserts the
delivery fact; prose quality is not machine-verifiable and is not claimed.

```clojure
[{:seon.test/sym "my.agents.juniper/e10231f6-is-answered"
  :seon.test/ns [:seon.ns/name my.agents.juniper]
  :seon.test/source
  "(deftest e10231f6-is-answered
     (is (true? (seon.cluster.message/answered? (seon.db/db) [:seon.message/id \"e10231f6\"]))))"}
 {:my.task/id "juniper/answer-e10231f6"
  :my.task/title "Answer root's message"
  :my.task/instructions
  "Read the message and the thread, reply :about it with (my.message/send {...}), then (my.agent/done). The session ends when the task's test is verified."
  :my.task/namespace [:seon.ns/name my.agents.juniper]
  :my.task/subject [:seon.message/id "e10231f6"]
  :my.task/tests #{[:seon.test/sym "my.agents.juniper/e10231f6-is-answered"]}
  :my.task/budget 6}]
```

The second row needs `seon.cluster.message/answered?` (audit B §1, not
installed) and a deftest that reads the agent's own cluster database. The
elided `(seon.db/db)` arity already resolves to the calling agent's cluster
in SCI; whether the in-process runner binds the same custody when settlement
runs the test is open question 3 below.

## 3. Functions (`src/my/task.clj`, proposed contracts)

| function | contract | does |
|---|---|---|
| `my.task/start!` | `[:=> [:cat :my.task/start-request] [:or :my.task/started :seon.error/value]]` | The bootstrap's `ensure-entity!` generalised: ONE transaction function emits `seon.cluster.agent/creation-tx` for the worker (id derived from the task id, namespace = `:my.task/namespace`), asserts `:my.task/agent`, writes the settings overlay `max-episode-runs` = budget, authors the plan (objective = instructions; one step `{:my.plan.item/id <task id> :title <task title> :subject <task row> :done-query my.task/done-query}`), and opens the first turn with `turn/generated-run-tx` whose optional `:seon.turn/trigger` is the task row. The armer arms the agent on the commit; the agent's first system turn renders the opening in §4. The bootstrap's hard-coded `task-message` becomes an ordinary `my.task` row, which deletes a special case. |
| `my.task/status` | `[:=> [:cat :my.task/status-request] [:or :my.task/status :seon.error/value]]` | Reads the task, its subject identity, and each test with `verified?` on the current program digest (`seon.test.runner/program-digest`, `src/seon/test/runner.clj:1358`), red/green/unrun, the failing assertions, and the exact completing calls. The form the render pair emits. |
| `my.task/done-query` | a `:seon.db/query` value | Bound to `?subject` = the task row: true when every `:my.task/tests` member is verified on the current digest. Inputs are `$` and `?subject`, so the digest is derived inside the query from the source seal plus program facts, or the settlement supplies it as a third input (open question 2). |
| `my.task/copy!` | `[:=> [:cat :my.task/copy-request] [:or :my.task/task :seon.error/value]]` | New row from an existing one with a new subject; id via `seon.id/id`; `:my.task/from` set. Tests are copied by reference unless the caller supplies new ones. |
| `my.task/render-ai` / `render-html` | `[:=> [:cat :seon.render/unit] :seon.render/source]` / hiccup | AI emits `;; My task. …` plus `(my.task/status {:my.task/id "…"})`; HTML shows the same status as a block. |

Settlement change (one line in the owning seam, `src/seon/turn.clj:2192`,
`:3412`, `:3451`, `:4623`, currently under the n7 lane's edit): before
`plan/settle-call`, run each test of the agent's open task steps in-process
with `seon.test/run` under the existing bound; run rows carry provenance, so
`done-query` reads facts the same settlement just wrote.

## 4. The worker's opening, as the walk would draw it

Root's real opening today is: help, identity, plan, inbox, settings, notes,
`dir`, runtime, stewarded faults, cluster. With the two accretions, a worker
started on the code task above opens with one more block after the plan:

```
my.agents.root=> ;; My plan is my instructions. After seeing the result and verifying the criterion, complete this step with (my.plan/complete! {:my.plan.item/id "root/total-amount"}). my.plan/current! selects; completing clears the selection.
(seon.plan/plan {})
{:seon.agent/id "root-total-amount"
 :seon.plan/objective "Define a durable contracted function my.agents.root/total-amount …"
 :seon.plan/current-step "root/total-amount"
 :seon.plan/step-lines {}}

my.agents.root=> ;; My task. Its tests define done; (my.test/check {:seon.test/changed ["my.agents.root/total-amount"]}) runs them and records the result.
(my.task/status {:my.task/id "root/total-amount"})
#:my.task{:id "root/total-amount"
          :title "Define total-amount"
          :instructions "Define a durable contracted function …"
          :tests [#:seon.test{:sym "my.agents.root/total-amount-sums-amounts"
                              :state :unrun
                              :pending-subject "my.agents.root/total-amount"}]
          :budget 12
          :turns-left 12}
```

The block is comment, form, result, like every other block. The agent id `root-total-amount` is derived from the task id; the namespace is the task's. The subject and
tests then render through their own pairs via the task's units (a message
through `seon.cluster.message/render-ai`; a test through the test pair that
audit A found missing and this prototype has to add). After the agent defines
the function and runs `my.test/check`, the since-diff re-evaluates
`(my.task/status …)` because its read evidence changed; the step completes at
the settlement whose run rows made `done-query` true; the disposition ends the
session.

## 5. What is deliberately absent

- A `writes` declaration: the tests are the writes contract.
- A separate instance entity: the worker agent plus its plan step is the
  instance; `:my.task/agent` links them. Add an instance row only when one
  task must span agents.
- A template kind or stamp: a row with no agent is a template by absence.
- Triggers and scheduling, by the owner's ruling: `start!` is called by a
  human, by an agent (the steward chatting with a user spins up workers), or
  later by whatever detector or schedule we choose. Nothing in the task
  family listens or fires.
- A metrics registry or problem family (audits A and B).

## 6. Open questions to iterate

1. **Who writes the tests for a communication task?** The prototype pre-authors
   one deftest per task. A human question arriving as a message has no task
   row yet; the steward's session (or a listen on `:seon.message/inbox`)
   would call `copy!` from a `message/answer` template with the message as
   subject, and the template's test is parametrised by subject. Is a test
   whose source is templated on the subject id acceptable, or should the
   answered predicate take the subject from the task row at run time?
2. **Digest inside the done-query.** `done-query` receives `$` and `?subject`.
   The program digest is a derivation over the source seal plus changed program
   rows (`runner.clj:1358`), not a stored fact. Options: (a) settlement supplies
   the digest as a third query input (accretion to the plan settlement
   contract); (b) store the digest on each `seon.test.run` (already) and let
   the query accept "the newest run for this test whose digest equals the
   newest run of any test" — weaker; (c) `done-query` calls a rule function
   `my.task/verified-now?` instead of raw Datalog. Recommendation: (a).
3. **Test custody at settlement.** A code task's tests are self-contained. A
   communication task's test reads live cluster facts; `seon.test/run` takes a
   connection, and the deftest's elided `(seon.db/db)` must resolve to it. To
   verify on the fixture before landing.
4. **Budget as settings.** Copying `budget` into the worker's
   `max-episode-runs` overlay at start is one write and reuses the existing
   bound; the alternative, reading the budget from the task at
   `turns-left`, spreads the rule. Keep the copy?
5. **One worker per task, or reuse?** `start!` picks an idle agent in the
   namespace with no open task, else creates `<ns-suffix>-<task-id-suffix>`.
   Agents accumulate; the [TARGET] root maintenance portfolio would retire
   idle workers. Acceptable for the prototype?
6. **The test pair.** `:seon.test` has no AI/HTML pair (audit A). The task
   block renders test state through `status`, so the pair is not needed for
   the first proof; it is needed the moment a test row appears as a subject.

## 7. Live seams and boundaries

Verified in the development JVM (default, read-only): `seon.test/verified?`
returns `true` for `seon.sci.eval-test/one-unloadable-row-cannot-prevent-cold-acquisition`
at run `99ec86db1bab` and `false` for an absent symbol; `seon.test/check`
takes `{:seon.db/connection :seon.boot/cluster-name …}`; `seon.test/run`
takes `[test-var connection]`; agent creation is
`seon.cluster.agent/creation-tx` from `{:seon.agent/id :seon.ns/name}`; the
walk's units come from `declared-concerns`
(`src/seon/render/walk.clj:88`) and `declared-acquisition` (`:631`).

Protected while the n7 lane holds them: `src/seon/turn.clj`,
`src/seon/fn.clj`, `test/seon/fn_test.clj`, `test/seon/cluster/turn_test.clj`
(the settlement change waits or goes to that lane). Everything else the
prototype touches is free: the new schema resource, `src/my/task.clj`,
`resources/seon/schemas/seon.agent.edn` (one unit), a test namespace.

## 8. First live proof (when approved)

On the canonical fixture, then on default: transact the code-task rows;
`start!`; read the worker's opening through `seon.render/acquire-context!`
and record its bytes here; submit the function through a virtual turn;
`my.test/check`; observe the step's `completed-tx` and the run row's digest;
then the same with the provider on, the cheapest model first.
