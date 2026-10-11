---
title: "The runtime follows the database: one path, one scheduling system, every state as primitives"
type: research
status: approved for building (owner, 2026-10-11) after the probes of design-probes.md corrected §2, §10 and the attempt facts; build order in §10
basis: HEAD 05139fa1f (overlay plan reuse 0c3916000 read), read by symbol; old plan model at cea904e92^; read-only JVM probes on default (roster 11 ms; resume legs below)
---

# Runtime follows DB

**Thesis (owner): there is ONE path, an agent wakes and runs a task, and everything uses it.** Flow manages the literal
process and computation; channels carry only ephemeral data; everything of consequence is a database fact, so a crash
loses nothing and a restore to any commit is a derivation. **No bespoke scheduler**: every state and behavior is data on
the primitives already being built (tasks, refs between tasks, intents, listened datoms and wakes, Flow procs, the one
path, `seon.cache`, blobs), so the one path is the one thing to optimize (§7). Rounds 1–2 live in Git history; this
round revises in place.

## 1. The one path, and what is not on it

Wake (`seon.cluster.wake/route!`) → the turn proc pins one database value and derives work (`seon.turn/next-agent-work`)
→ task loaded, validated, context rendered (`seon.context/current-state`) → proofs run on the branch (`test-step`) →
green ends it (`end-task-call`, one transaction) or red opens one model call → recording transaction → next wake.

| Thing | On the path? | Disposition |
|---|---|---|
| step tasks, root's merge requests, root's problem tasks (§5), recurring attempts (§6), ad-hoc `eval_clj` agents | yes | the path |
| maintenance `:seon.schedule.task/*` with a per-agent `seon.schedule/schedule-step` (own timer, own `d/listen`, direct Var call) | no | folded into §6: one clock proc, task entities, firing facts |
| `seon.test-support/start-run!`, `own-branch-agent!`, "Work your task." | no | deleted: the bench writes tasks; a held unanswered assignment is work |
| `seon.task/assign!` running proofs before the row | no | the first pass runs them; `:paused` holds them; green ends with zero calls |
| the reconciler (§3) | no, by design | the one proc that hosts the path; a task cannot start itself |
| the clock and the heartbeat (§6) | no | procs that only write facts (a firing, an observation); their consequences are tasks |
| render proc, fault committer | no | views and the entrant boundary |

## 2. Structure: a tree of tasks, parallel by default, ordered by `needs`

The old `my.plan` (cea904e92^) had `steps` (component children), `position` (sibling order), `needs` (cross refs),
`completed-tx`, `current-step`, `done-query`. Take the first and third; drop the rest: completion is the end fact,
current-step is the state field to avoid, done-query is replaced by proofs, and **position is not needed**: siblings run
in parallel unless one `needs` another, which is what the owner asked for ("if they don't have deps on each other they
can run in parallel") and keeps the question one query.

| Attribute | Meaning |
|---|---|
| `:seon.task/children` (component ref set) | the parent owns its children; a parent never runs, it is done when its children are; its intent, must-read and evidence are the handoff medium its children read and `refine!` |
| `:seon.task/needs` (ref set, indexed) | ready only after every target is done; a target anywhere in the tree or another tree; inherited by descendants (a child cannot start before its parent's needs) |
| the assignment's end (`:done`, `:sent-back` + target, …) | written once per attempt by `end-task-call`; done(T) = ended `:done` with no later `:sent-back` targeting T |

```clojure
{:seon.task/name fix-slug :seon.task/intent "…" :seon.task/must-read […]        ; the subject: the handoff
 :seon.task/children
 #{{:seon.task/name design}
   {:seon.task/name prove     :seon.task/needs #{design}}                     ; sequence = needs
   {:seon.task/name impl-a    :seon.task/needs #{prove}}                      ; fan-out: three siblings
   {:seon.task/name impl-b    :seon.task/needs #{prove}                       ;   with no needs between them
    :seon.task/children #{{:seon.task/name impl-b-schema}                        ; nested: b has its own
                       {:seon.task/name impl-b-body :seon.task/needs #{impl-b-schema}}}}
   {:seon.task/name impl-c    :seon.task/needs #{prove}}
   {:seon.task/name integrate :seon.task/needs #{impl-a impl-b impl-c}}       ; join: b counts when its children do
   {:seon.task/name merge     :seon.task/needs #{integrate} :seon.task/execution :held}}}  ; root releases it
```

**The frontier, "everything that should be running right now"**, over main with no scheduler state, is **two reads**
([design-probes](design-probes.md) P1): the eligible leaves, minus the `blocked` set evaluated unbound. Written as one
query with `(not (blocked ?t))` Datahike evaluates the recursive rule once per leaf (75–93 ms, 118–151 MB at 50
leaves); a `done` rule recursing through negation overflows its stack in `datahike.query.lower/rename-branch-vars`.
Leaf completion is non-recursive, recursion is positive (`under`, `needs*`), negation applies only to finished
relations, a parent is done exactly when no leaf under it is open:

```clojure
(def frontier-rules
  '[[(leaf-done ?t) [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end :done ?tx]
                    (not-join [?t ?tx] [?b :seon.task.attempt/end :sent-back ?tx2] [?b :seon.task.attempt/target ?t] [(> ?tx2 ?tx)])]
    [(under ?n ?l) [?n :seon.task/children ?l]]
    [(under ?n ?l) [?n :seon.task/children ?c] (under ?c ?l)]
    [(self-or-under ?n ?l) [(identity ?n) ?l]]
    [(self-or-under ?n ?l) (under ?n ?l)]
    [(needs* ?t ?n) [?t :seon.task/needs ?n]]
    [(needs* ?t ?n) [?p :seon.task/children ?t] (needs* ?p ?n)]
    [(blocked ?t) (needs* ?t ?n) (self-or-under ?n ?l) [?l :seon.task/id] (not [?l :seon.task/children _]) (not (leaf-done ?l))]])
;; eligible leaves
[:find [?t ...] :in $ % :where [?t :seon.task/id] (not [?t :seon.task/children _]) (not (leaf-done ?t))
 (not-join [?t] [?t :seon.task/execution ?i] [(contains? #{:held :stopped :cancelled} ?i)])
 (not-join [?t] [?a :seon.task/attempt-of ?t] [?a :seon.task.attempt/end ?e] [(contains? #{:given-back :capped :stalled :cancelled} ?e)])]
;; minus
[:find [?t ...] :in $ % :where (blocked ?t)]
```

Only the terminal non-done ends exclude a leaf: a `:done` end is judged by `leaf-done`, so a leaf sent back after its
own `:done` re-enters the frontier (the literal "no end but `:sent-back`" clause deadlocked everything that needed it).
Measured (P1, twelve scenarios × three sizes, all exact): 0.8–4 ms and 1–4 MB at 50 leaves on an in-memory store and
under `d/with` on the live value (607k datoms); 4.5–76 ms at 500 leaves; about 0.008 ms per closed leaf, since the read
starts at `[?t :seon.task/id]` (715 tasks today add about 6 ms; a semi-join over leaves with open needs if big trees
become ordinary). The result splits by the roster: open attempt and a graph → running; open attempt, no graph → re-host;
no attempt → launch.

**Attempt facts** (S1/S3; today's row is kept and extended, so `seon.task/assignment-read` and `agent-task` still read
it): the row with identity `:seon.task/assignment` (`seon.id/id [handle agent branch]`) gains `:seon.task/attempt-of`
(indexed ref to the task, written beside the handle value `:seon.task/assigned` in the same transaction),
`:seon.task.attempt/end` (indexed enum `:done :given-back :capped :stalled :sent-back :cancelled`, asserted once by
`seon.turn/end-task-call`), `:seon.task.attempt/target` (ref, with `:sent-back`), `:seon.task.attempt/reason`, and
`:seon.task.attempt/commit` (the branch commit at the end; the successor's fork basis, keyed by commit id, never `t`,
which a fork repeats). Replaced: `:seon.task/end` on the end message becomes a copy root stops reading once Q1's merge
task reads the attempt; the proposal's `:seon.task/commit` as the successor's basis; `:seon.agent/admission-closed-tx`
stays as the agent-side closure the same transaction writes. Child tasks are `:seon.task/children` (component ref
set): the live `:seon.task/children` is the old step component that plan §0 item 5 deletes, and a changed meaning takes a
new name.

**The plan is shown, not stored.** `needs` carries the order: a render pair on the subject task (`:seon.render/ai` and
`:seon.render/html` on `:seon.task/subject-plan`, a schema-selected render as any other) layers the tree by needs,
layer n = the tasks whose needs all lie in earlier layers (Kahn's order over the pulled tree; a cycle is refused at the
writer, as `seon.plan/refuse-dependency-cycle!` did), so the same layer reads as parallel and successive layers as the
sequence; each task is annotated from the same derivations the reconciler uses: done, running (open attempt with a
graph), paused, held, waiting (a need open), problem (an open problem task names it). The HTML face is the layers as
rows with the tree nesting as indentation; the AI face is the same data pruned to the open layers. Read-only, cached
by what it read; a changed end or intent re-renders it.

## 3. Intents and the reconciler

Intent lives on the task, `:seon.task/execution`, absent = `:running`; the cluster carries `:seon.cluster/execution`
∈ `#{:running :paused}`; paused anywhere wins; both `:seon.wake/arms true`. Readiness (§2) is the precondition, intent
the permission and shape. The reconciler is `seon.cluster.agent/armer-step` generalized: woken by the router on arming
attributes, primed at its own `::flow/resume`, under one dial `:seon.config.agent/max-running`.

| Intent | Reconciler action, in Flow's verbs | Roster state it names |
|---|---|---|
| `:held` | nothing, even when ready | no graph, not launchable |
| `:running` | no graph: fork from the predecessor's recorded commit, `host!`, write the attempt, `flow/start` + `flow/resume`; paused: `flow/resume`; after a restart: re-host from the attempt row | running |
| `:paused` | launch without `flow/resume` (Flow starts paused: context acquired, pingable, nothing runs) or `flow/pause` (after the current transform: control is read with priority between transforms, `flow/impl.clj` `proc`) | paused, inspectable |
| `:stopped` | `flow/pause`, join the pass, `flow/stop`, join the executor, release the SCI context; agent, branch, attempt stay | gone, resumable |
| `:cancelled` | interrupt (`disarm!` as today), end `:cancelled` with reason, delete the branch | gone, terminal |

Complete against the roster's possible states; minimal (done is derived, not an intent). Idempotent by identity:
derived branch names, `fork!` answers `created? false`, the attempt upserts by `seon.id/id [handle agent branch]`,
`arm!` leaves an armed agent alone. **Only the reconciler starts graphs**: an agent writes a task; it never calls
`host!`, `arm!` or `assign!` (a graph started inside another agent's transform would die with that agent's executor;
two starters of one roster is the dual-writer problem). `eval_clj` hosting is root's act at the entrant boundary
through the same `host!`.

### 3b. One world, for agents and test members

Owner (2026-10-11, after lane live-root-reds, 4347f6cce): a test member running in its own world "sounds like the task
system". Today a world is built in several places: `seon.cluster.agent/acquire-context!` and `seon.cluster.host/host!`
build an agent's (branch connection, SCI fork, routing entry, environment); `seon.test/bounded-result` built a member's
by inheriting the requester's `seon.batch/*world*` through `bound-fn` (identity, faults, connection, store leaked from
root into members; now it keeps only the call sink); `seon.batch/*world*` with `seon.batch/world-members` is the value
`seon.call-preparation/supply` fills omitted inputs from; `seon.cluster.boot/request-world` and the MCP entrance derive
the world a failed request records in. Decision: **one world constructor** (`seon.batch/world`), shared by agents and
test members: identity (`:seon.agent/id`, or the member's test symbol), custody (branch connection, writer), the SCI
context fork, the supplied members and a call sink; an agent's world is that value plus a Flow graph and task facts; a
test member's world is that value alone, built from the run's branch, never inherited from a thread. **A test run**
("check these tests for this change") **is a task on the one path**: created with its test set and program basis as
data, launched by the reconciler, observed, restorable, its end carrying the run id; its members run inside that attempt
in worlds from the constructor, not as tasks of their own (about 2,800 members at milliseconds each is too heavy for
attempt rows). `seon.test`'s own world building goes.

## 4. Boot, resume, and the resume path's keys

| Mode | Pass |
|---|---|
| `start` | `recover-runs!` closes interrupted turns; the prime applies every intent (re-hosts open attempts, resumes `:running`, leaves `:paused` paused) |
| `start --paused` | writes `:seon.cluster/execution :paused` before the prime: every graph pingable, nothing runs, schedules too |
| resume to a commit | fork a branch from the commit id (`seon.cluster.registry/fork!`; a branch name moves and `t` repeats across forks), intent `:paused`, prime |

| Leg | Key | Owner | Warm / cold (default main, JVM mode) | Gap |
|---|---|---|---|---|
| commit → program identity | commit id | `seon.sci.eval/program-identity`, `program-identity-cache` | 0.008 / 0.03 ms | none |
| → cluster base context | `[store commit]` of the loaded program | `seon.sci.eval/base-ctx`, kind `::base-context` | 7.6 / 24 ms | none |
| → the branch's own changes: the plan | `[store, program basis, loaded commit]` | `seon.sci.eval/overlay-plan`, kind `::overlay-plan` (Astra, 0c3916000: plan data cached; `branch-database` opens a head once) | lookup / one derivation | none |
| → the branch's own changes: the interpreted context | **none today** | `seon.sci.eval/interpret-overlay!`, called by `overlay!` on every acquisition whose commit differs from the loaded one, re-interprets the plan's overridden and added definitions on a fresh fork of the base | = cold: proportional to changed definitions and affected callers (plan item 12) | **the remaining gap**: cache the interpreted fork by `[base-key, program basis]` as a `seon.cache` kind weighed like the base; an acquisition of a branch whose program did not move answers from it; `:stopped`→`:running` and t-resume become a lookup. A context holds atoms (`::kernel/program-snapshot`, installed functions), so the cached value is the fork to copy from (`copy-base-ctx`'s shape), never a shared live context |
| → rendered current-state | content digest of what it read | `seon.context/current-state` (no `seon.cache` kind found by grep: verify) | lookup / one render | add the kind once measured |
| large values | blob digest | `seon.blob/inline-or-stage`, `read-chunk` lazily | konserve read | none |
| what is due | `t` of listened datoms vs the latest answering turn | `seon.turn/unanswered-wakes` | 3.2 ms | none |

Never recoverable: live results evicted from `seon.cache` (a handle answers "gone"), SCI atom contents that are not
program entities, an in-flight model call or evaluation (due again; repeats unless idempotent), effects already outside
the store (provider spend, git, files, messages read by outsiders), and the compiled JVM program (HEAD's, not the
commit's).

## 5. Problems are tasks; root is woken only by a problem

No routine wakes to root: a nominal end wakes nobody (its successor is derived by the reconciler); a problem is a task
created idempotently by identity `seon.id/id [class subject]` (a repeat writes nothing), assigned to root, so the
attempt row is root's work and its first wake. Each class is detected as data where its facts already pass (the
reconciler's pass, the heartbeat's pass, or a listened fact), and has its own instructions template rendered by
`current-state` (a stage type, as TASK-CHAIN's). Root works it on the path; some need a fresh agent instead of root.

| Class | Detected by | Task type (instructions) | Worked by |
|---|---|---|---|
| merge request | the green end records it (today) | merge: diff since base, reached tests, proofs, decisions | root |
| attempt ended `:given-back`, `:capped`, `:stalled` | the end fact (listened: `:seon.message/to` root today → becomes the task) | repair: the end, its reason, the task; options: refine and relaunch, hold, cancel | root |
| send-back loop | `:sent-back` targeting the same task twice (query in the reconciler's pass) | arbitrate: both counterexamples, the subject | root |
| stuck chain | a leaf ready for longer than a dial with no attempt, or an open attempt with no turn since a dial | unstick: the leaf, the roster entry, the intents | root |
| observed ≠ intent | the heartbeat writes `:seon.agent/observed`; the reconciler compares it to the intent at its pass | runtime: agent, intent, observed, last ping | root |
| heartbeat stale | `:seon.cluster/heartbeat-t` older than two periods (health; the reconciler's pass) | runtime | root |
| error signature | `seon.fault`/recorded error once per signature at the task home (today) | defect: the error, its producer, the agents it hit | a fresh agent (ordinary task), root only if the producer is boot-critical |
| budget exhausted | turns-left zero (`:capped`) | repair (above) | root |
| delivery failed | a firing or message whose recipient has a graph but the offer was refused or the route is closed (`wake/delivery` refusal, recorded today) | scheduler-failed: the recipient, the firing, the roster | root |
| unresolved reference in a task | ingestion's problem on the task | task-quality: the task and the reference | root or the author |
| cost regression on the path | a pass over 1 s or 10× its proportion (`:seon.repl/ms`, profile evidence) | performance: the pass, the armed functions | a fresh agent |

Instructions are data on the problem type; the problem tasks that recur become the measurement of the platform.

## 6. One scheduling system: recurring tasks, firings, delivery

A thing to run over and over is a task with a schedule. Today's `:seon.schedule.task/*` (owner, function, cron) and its
per-agent proc fold into it; the words "schedule task" retire.

| Fact | Meaning |
|---|---|
| `:seon.task/cron`, `:seon.task/zone` on a task | when it fires (today's `:seon.schedule/expression`, parsed by the same cron library) |
| `:seon.task/function` (optional ref to a Var) | a firing that needs no agent: the clock proc calls the Var and records its result (today's maintenance and the heartbeat's own body) |
| `:seon.task.fire/at` + `:seon.task.fire/of` | one firing, written by the one clock proc in the cluster graph at each due instant; idempotent by `[task nominal-at]` (today's `nominal-fire-id`) |
| attempt child `seon.id/id [task fire-at]` | a firing of a task **without** a function creates an attempt on the path (intent `:running`), so §2's query covers it |

Firing rules, all data: the task's agent has a graph in the roster → the firing is a listened fact (`:seon.task.fire/to`,
`:seon.wake/listen true`) and becomes its wake, no new graph; no graph → the attempt is created and the reconciler hosts
it; delivery refused → the §5 `delivery failed` task. The clock proc is the only timer in the system (replacing one
timer and one `d/listen` per agent today); the heartbeat is a cron task with a function (`ping` every graph, write
`:seon.agent/observed` on change and `:seon.cluster/heartbeat-t`), so it is configured as data, runs in the clock proc,
and still never wakes an agent.

| Kind of work | Expressed as |
|---|---|
| chain steps | children with `needs` (§2) |
| merge requests, problems | tasks assigned to root (§5) |
| maintenance (collection, census) | cron task with a function |
| heartbeat | cron task with a function |
| a recurring agent task (nightly retrospective) | cron task without a function: each firing an attempt |
| an ad-hoc agent (`eval_clj`) | an agent with a `:running` work task, hosted as root's act |
| root's own standing work | its `:work` task, woken only by problem tasks |

## 7. Every state and behavior as primitives

| State or behavior | Primitive(s) | Query or proc |
|---|---|---|
| what exists to do | task entity, `steps`, `needs` | §2's tree |
| what should run now | §2 attributes + intents + attempts | §2's one query |
| whether it may run | `:seon.task/execution`, `:seon.cluster/execution` | read in the reconciler's pass |
| what is running | the roster, `flow/ping` | `seon.cluster.status` over the roster and its children |
| what was observed running (audit) | `:seon.agent/observed`, `:seon.cluster/heartbeat-t` | the heartbeat's function, on change |
| where an attempt is | attempt row, its end fact | `seon.turn/task-end` → `end-task-call` |
| what an agent should look at | listened datoms, `t` | `seon.turn/unanswered-wakes` |
| who gets a graph | arming attributes → the reconciler | `armer-step`, primed at resume |
| pause / resume / stop | Flow control commands derived from the intent | the reconciler |
| when something recurs | `:seon.task/cron`, firing facts | the clock proc |
| what went wrong | problem tasks by identity | detected in the reconciler's or heartbeat's pass, or a listened fact |
| what root does | its `:work` task's wakes = problem attempts | the path |
| fast resume | commit id → identity → base → overlay (gap) → render → wakes | `seon.cache` kinds, blobs by digest |
| what to measure | the path's seams | wake→work, work→task loaded, task→context (ms, MB), context→proofs, proofs→first call or end, end→successor hosted, recording tx (<100 ms); faults, interrupts, stale heartbeats, repeated effects |

## 8. Differences from the written designs and code (to revise in place)

TASK-CHAIN §2/§6 and plan §0 item 6: one evolving task with a mutable `:seon.task/stage` → children with `needs`,
stage type set once, basis on the predecessor's attempt. task-graph-design §3b: `after`/`of` → `needs`/`steps`.
`seon.task/assign!`: proofs before the row → the first pass. `seon.turn/next-agent-work`: a held unanswered attempt
is work. `armer-step`: hosts from attempt rows (today an assigned branch agent is not re-hosted after a restart).
`seon.flow/start-graph!`, `arm-agents!`: resume under the intent. `disarm!`: `:stopped` pauses and joins, only
`:cancelled` interrupts. `seon.schedule`: folded into §6. `seon.task/resolution`: adds the derived `done`. Vocabulary:
record `:held :running :paused :stopped :cancelled`, attempt, firing, problem task; retire "schedule task".

## 9. Decided (owner, rounds 1–4)

Progress derived, intent stored, on the task; cluster `running/paused`; `needs` only, no position, the plan rendered;
five intents each one reconciler action; only the reconciler starts graphs; root works every problem task except error
signatures and cost regressions (fresh agents); nominal ends wake nobody; one clock proc, the heartbeat a cron task with
a function; a firing to a live agent is a message wake, else an attempt, refused delivery a problem task; `start`
resumes, `--paused` for debugging; the interpreted-overlay cache is the first performance lane.

## 10. Build order: cards of about fifteen minutes, each with its red proof

The probes P1–P3 ran ([design-probes](design-probes.md)): P1 corrected §2 and the attempt facts; P2 and P3 found
`host!` from a proc thread, `fork!` from a commit id and `flow/stop` + `join-graph!` without interrupt all work today,
so their cards are retired (a proof green on arrival proves nothing) and what they established moved into R1, R4 and
D3. Schema and derivations (S), the reconciler (R), the scheduling system (C), problems and the render (Q), deletions
(D). Every card is one lane, one owner, `--branch-evidence` or `--test`; a slice that contradicts the design stops and
reports. S1–S3 and R1 are implementation-ready below; the rest are cards to make ready when their turn comes.

**S1 — the facts** (owns `resources/seon/schemas/seon.task.edn`, `seon.cluster.edn`, `test/seon/task_test.clj`;
lands after plan §0 item 5 deletes the old step component). Attributes: `:seon.task/children` `[:set {:seon.db/component
true :seon.db/component-schema :seon.task/task} :seon.db/ref]`; `:seon.task/needs` `[:set {:seon.db/index true}
:seon.db/ref]`; `:seon.task/instructions` `[:string {:min 1}]`; `:seon.task/execution` `[:enum {:seon.db/index true
:seon.wake/arms true} :held :running :paused :stopped :cancelled]`; `:seon.cluster/execution` `[:enum {:seon.wake/arms
true} :running :paused]`; `:seon.task/attempt-of` `[:and {:seon.db/index true} :seon.db/ref]`; `:seon.task.attempt/end`
(indexed enum above), `/target` (ref), `/reason` `[:string]`, `/commit` `:uuid`; `:seon.task/cron` `[:string]`,
`/zone` `[:string]`, `/function` `[:and {:seon.fn/reference-to :seon.fn/sym} :seon.db/ref]`; `:seon.task.fire/at`
`:inst`, `/of` (indexed ref), `/to` `[:and {:seon.db/index true :seon.wake/listen true} :seon.db/ref]`. Writer:
`seon.task/refuse-needs-cycle` `[:=> [:cat :seon.db/database-value :seon.store/transaction-data] [:maybe
:seon.task/refused-error]]`, called by `seon.task/create!` and `my.task/create!`. Red proofs:
`seon.task-test/a-needs-cycle-is-refused-at-the-writer`; `seon.task-test/the-task-facts-adopt-on-a-branch-of-the-live-store`
(publish + adopt on a disposable branch, `d/pull` of every new attribute). Vocabulary rows first (landed a18ef159f).

**S2 — the frontier** (owns `src/seon/task.clj` frontier section, `test/seon/task_test.clj`). `seon.task/frontier-rules`
(the `def` in §2); `seon.task/frontier` `[:=> [:cat :seon.db/database-value] [:or [:set :seon.task/handle]
:seon.db/read-refusal]]`: two `seon.db/q` reads, `clojure.set/difference`, handles by `[?t :seon.task/id ?h]`;
`seon.task/done?` `[:=> [:cat :seon.db/database-value :seon.task/handle] :boolean]` (leaf: `leaf-done`; parent: no open
leaf `under` it); `seon.task/resolution` gains the clause `:closed-by :seon.task/children` when `done?`. Red proof: an
`are` table `seon.task-test/the-frontier-is-the-leaves-whose-needs-are-done` with P1's twelve scenarios on the 50-leaf
tree (fresh → `#{design}`; design done → `#{prove}`; prove done → N leaves + b's schema; schema and one leaf done →
N−1 + M; all but integrate → `#{integrate}`; integrate done, merge held → `#{}`; merge released → `#{merge}`; a leaf
`:sent-back` to prove → `#{prove}`; prove with an open attempt → `#{prove}`; prove `:given-back` → `#{}`; one leaf
`:stopped` → N−1 + schema; all done → `#{}`), written under `d/with`, plus the cost row
`seon.task-test/the-frontier-answers-in-under-10-ms-for-fifty-leaves` through `seon.profile/measure` (P1 measured 4 ms
worst case; 50 ms was the probe's bound, 10 ms is the budget).

**S3 — the attempt is the launch's write; proofs run in the first pass** (owns `src/seon/task.clj` assign section,
`src/seon/turn.clj` work derivation and `end-task-call`, `test/seon/turn_test.clj`). `seon.task/attempt-tx` `[:=>
[:cat :seon.db/database-value :seon.task/handle :seon.agent/id :seon.store/branch] :seon.store/transaction-data]`
writes the row with `:seon.task/attempt-of` and the existing fields; `seon.task/assign!` calls it and runs no proofs
(its `:proofs-green`/`:proofs-unobserved` refusals go; `:no-proofs` stays); `seon.turn/next-agent-work` gains one
situation: a held attempt whose `t` is after `latest-answering-turn-t` is `:open` work with no wake; `test-step` is
already the first pass's proof run, and `task-end` → `:done` with zero calls when it is green;
`seon.turn/end-task-call` writes `:seon.task.attempt/end`, `/target`, `/reason`, `/commit` on the attempt in the one
transaction it already makes (S4 folds in here). Red proofs: `seon.turn-test/a-fresh-attempt-is-work-without-a-message`;
`seon.turn-test/an-already-green-attempt-ends-with-no-model-call`; `seon.turn-test/an-end-is-written-on-the-attempt`.

**R1 — the reconciler launches** (owns `src/seon/cluster/agent.clj` armer section, `test/seon/cluster/agent_test.clj`).
`seon.cluster.agent/launch!` `[:=> [:cat :seon.agent/launch-request] [:or :seon.agent/launched :seon.task/refused-error]]`
with request `{:seon.turn.loop/cluster handle :seon.task/handle h}`: the branch name derived from the attempt identity;
`seon.cluster.registry/fork!` from the predecessor attempt's `:seon.task.attempt/commit` (handed `commit-as-db`, released
after; main's current value when there is none; by commit id, never `t`), answering `created? false` on a repeat;
`seon.cluster.host/host!` on the armer's own proc thread (P2: 107 ms, each graph has its own executor, so the hosted
graph outlives the armer's transform); `seon.task/attempt-tx` at main. `armer-step`'s transform: `frontier` minus
tasks with an open attempt, sorted by creation `t`, launched while running attempts are under
`:seon.config.agent/max-running` (new dial, default 4; the armer's own pass is bounded by it, since each launch holds
the armer about 100 ms plus acquisition); then today's arming of agents with work. Red proofs:
`seon.cluster.agent-test/two-reconciler-passes-launch-one-attempt`; `…/a-held-leaf-is-never-launched`;
`…/a-successor-forks-from-its-predecessors-end-commit`; and the lightness proof the owner asked for,
`…/starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` through `seon.profile/measure` on the calling thread
(the parity rerun started in 4.25 s and allocated 4.2 GB for a two-call run; the budget is derived from the legs: fork
64 ms + host 107 ms + base context 24 ms + overlay, and four agents at 300 MB fit a 16 GB machine beside the user's
work; R4 brings the overlay leg under it), recorded as profile evidence so a slower run than main's is red.

**W1 — one world constructor** (owns `src/seon/batch.clj` world section, `src/seon/test.clj` `bounded-result`,
`src/seon/call_preparation.clj` supply, `test/seon/batch_test.clj`, `test/seon/test_test.clj`; beside R1). `seon.batch/world`
`[:=> [:cat :seon.batch/world-request] :seon.batch/world]` with request `{:seon.agent/id or :seon.test/sym, :seon.db/connection,
:seon.sci.eval/ctx (the fork), :seon.env/environment, :seon.render/profile?, :seon.batch/sink?}` returns the validated
world map whose keys are exactly `seon.batch/world-members`' values (the registered `:seon.batch/world` schema, closed);
`seon.cluster.agent/acquire-context!` and `seon.cluster.host/host!` call it for the agent's world; `seon.test/bounded-result`
calls it for the member (identity the test symbol, custody the run's branch, the fork from the run's program) and binds
that value, deleting its own `bound-fn` and `select-keys` world; `seon.call-preparation/supply` reads only this value;
`seon.cluster.boot/request-world` answers it. Red proofs: `seon.batch-test/every-world-is-built-by-one-constructor`
(a census over the loaded program: no other binding of `seon.batch/*world*` than the constructor's and the fold's);
`seon.test-test/a-member-runs-in-its-own-world-never-its-requesters` (exists, 4347f6cce) extended with a row asserting
the member's world is the constructor's value for the run's branch.

**W2 — a test run is a task** (owns `src/seon/test.clj` `run`/`run-request`, `src/my/test.clj`, `test/seon/test_test.clj`;
after R1 and W1). `my.test/check` and the gate's `seon.test/run-request` create a task (`:seon.task/instructions` the
test set and the program basis as data, intent `:running`, identity `seon.id/id [basis tests]` so a repeated request is
one task) instead of running inline; the attempt runs the members inside `seon.test/run` with W1 worlds; its end
`:done` carries the run id and the results are the facts `seon.test/run` already records; a request that only reads
answers from the recorded run (reuse by reach digest unchanged). Red proofs: `seon.test-test/a-test-run-is-an-attempt-on-the-path`
(the task exists, the reconciler launched it, the end names the run); `seon.test-test/members-are-not-tasks` (one
attempt row for a run of fifty members).

| # | Card | Red proof (deftest, tagged with the card's task) |
|---|---|---|
| S1 | the facts (above) | `seon.task-test/a-needs-cycle-is-refused-at-the-writer`; `…/the-task-facts-adopt-on-a-branch-of-the-live-store` |
| S2 | the frontier (above) | `seon.task-test/the-frontier-is-the-leaves-whose-needs-are-done` (12 rows); `…/the-frontier-answers-in-under-10-ms-for-fifty-leaves` |
| S3 | attempt as the launch's write; first-pass proofs; the end on the attempt (above; S4 folded in) | `seon.turn-test/a-fresh-attempt-is-work-without-a-message`; `…/an-already-green-attempt-ends-with-no-model-call`; `…/an-end-is-written-on-the-attempt` |
| R1 | the reconciler launches (above) | `seon.cluster.agent-test/two-reconciler-passes-launch-one-attempt`; `…/a-held-leaf-is-never-launched`; `…/a-successor-forks-from-its-predecessors-end-commit`; `…/starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` |
| W1 | one world constructor `seon.batch/world` for agents and test members (above; beside R1) | `seon.batch-test/every-world-is-built-by-one-constructor`; `seon.test-test/a-member-runs-in-its-own-world-never-its-requesters` (new row) |
| W2 | a test run is a task on the path; members are worlds inside its attempt (above) | `seon.test-test/a-test-run-is-an-attempt-on-the-path`; `…/members-are-not-tasks` |
| R2 | intents → Flow verbs: `:paused` launches without resume, or `flow/pause` then `flow/ping-proc` under the bound acknowledges the pass's end (P3); `:running` resumes or re-hosts; `:stopped` = `stop-graph!` without `interrupt-turn!` (P3: `flow/stop` + `join-graph!` exits after the current transform) and releases the SCI context; `:cancelled` interrupts and closes; cluster `:paused` wins; `start-graph!` resumes only under the intent | `seon.cluster.agent-test/each-intent-names-one-roster-state` (table over the five) |
| R3 | boot: `start --paused` writes the cluster intent before the prime; the prime re-hosts open attempts on branches | `seon.cluster.boot-test/a-paused-boot-arms-every-open-attempt-and-runs-nothing`; `…/a-restart-re-hosts-an-open-branch-attempt` |
| R4 | the interpreted-overlay cache kind keyed `[base-key, program basis]` (§4); the resume-to-a-commit path is `fork!` from the commit id handed `commit-as-db` (P2) and this cache | `seon.sci.acquisition-cost-test/a-second-acquisition-of-an-unmoved-branch-interprets-nothing`; R1's lightness proof under budget with a changed branch |
| C1 | the clock proc in the cluster graph: due instants from every task with `:seon.task/cron`, one firing fact per `[task nominal-at]`; `:seon.task/function` firings call the Var and record the result | `seon.schedule-test/a-firing-is-written-once-per-nominal-instant` |
| C2 | firings without a function: a live agent gets a listened wake (`:seon.task.fire/to`), else an attempt child by identity; a refused delivery writes the problem task | `…/a-firing-to-a-live-agent-is-a-wake-and-to-none-an-attempt`; `…/a-refused-delivery-is-a-problem-task` |
| C3 | the heartbeat as a cron task with a function: `ping` the roster and children, `:seon.agent/observed` on change, `:seon.cluster/heartbeat-t` per period; two periods read as unknown | `seon.health-test/a-stale-heartbeat-reads-unknown`; `…/an-unchanged-observation-writes-nothing` |
| Q1 | problem tasks by identity `seon.id/id [class subject]`, instructions as data on the task, assigned to root (fresh agents for error signatures and cost regressions); the merge request becomes one and reads the attempt | `seon.task-test/a-repeated-problem-writes-nothing`; `…/a-merge-request-is-a-problem-task-of-root` |
| Q2 | detection per class in the pass that already sees the facts (two classes per lane) | one row per class in `seon.task-test/each-problem-class-is-detected-from-its-facts` |
| Q3 | the plan render pair on the subject task: layers by needs, live annotations | `seon.render.task-test/the-plan-render-layers-a-tree-by-needs` |
| D1 | delete `seon.test-support/own-branch-agent!`'s setup eval and `outside-wake-on-branch!`; the bench writes trees | the bench test green through the reconciler |
| D2 | delete the per-agent `seon.schedule/schedule-step` proc, `:seon.schedule.task/*`, `seon.schedule/recover-tx`; `graph-definition` loses the schedule proc | `seon.cluster.agent-test/an-agent-graph-has-two-procs` |
| D3 | `disarm!` splits: `:stopped` = `stop-graph!` minus `interrupt-turn!`, `:cancelled` keeps it; the turn-completion bound covers a whole pass (the dial already adds the provider or evaluation limit) | R2's table row for `:stopped` |
| D4 | revise TASK-CHAIN, task-graph-design, plan §0 item 6 in place | documentation |
| B1 | bug: `seon.db/q` lets a `StackOverflowError` escape as a thrown `Error` instead of answering a refusal (P1) | `seon.db-test/a-stack-overflow-in-a-query-is-answered-as-a-refusal` |

Order: S1 after plan §0 item 5's deletion; S2, S3 after S1; W1 any time; R1 after S2 and S3; W2 after R1 and W1; R2–R3 after R1; R4 and B1 any time;
C1–C3 after S1 and R1; Q1–Q3 after S2; D1 after R1 and S3; D2 after C1–C3; D3 with R2; D4 last. First experiment
(plan §0 item 7) runs after R1 with a two-leaf tree.

## 11. Remaining owner questions

1. **Instruction types for problem tasks: one `resources/seon/tasks/problems.edn` template per class, rendered by
   `current-state` as a stage type is.** Alternative: one generic "problem" template with the class as data.
2. **`max-running` default: 4 attempts on a 16 GB machine** (hundreds of MB per hosted context until R4 lands).
   Alternative: unbounded until measured.
3. **Does the first experiment wait for R1 (automatic launch) or run with root creating the second leaf's attempt by
   hand through the same `host!`?** Recommended: wait for R1; it is one card.
