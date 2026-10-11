---
title: "The runtime follows the database: one path, one scheduling system, every state as primitives"
type: research
status: round 3 of an owner iteration (2026-10-11); not agreed
basis: HEAD e7dbabe9b, read by symbol; old plan model at cea904e92^; read-only JVM probes on default (roster 11 ms; resume legs below)
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
| `:seon.task/steps` (component ref set) | the parent owns its children; a parent never runs, it is done when its children are; its intent, must-read and evidence are the handoff medium its children read and `refine!` |
| `:seon.task/needs` (ref set, indexed) | ready only after every target is done; a target anywhere in the tree or another tree; inherited by descendants (a child cannot start before its parent's needs) |
| the assignment's end (`:done`, `:sent-back` + target, …) | written once per attempt by `end-task-call`; done(T) = ended `:done` with no later `:sent-back` targeting T |

```clojure
{:seon.task/name fix-slug :seon.task/intent "…" :seon.task/must-read […]        ; the subject: the handoff
 :seon.task/steps
 #{{:seon.task/name design}
   {:seon.task/name prove     :seon.task/needs #{design}}                     ; sequence = needs
   {:seon.task/name impl-a    :seon.task/needs #{prove}}                      ; fan-out: three siblings
   {:seon.task/name impl-b    :seon.task/needs #{prove}                       ;   with no needs between them
    :seon.task/steps #{{:seon.task/name impl-b-schema}                        ; nested: b has its own
                       {:seon.task/name impl-b-body :seon.task/needs #{impl-b-schema}}}}
   {:seon.task/name impl-c    :seon.task/needs #{prove}}
   {:seon.task/name integrate :seon.task/needs #{impl-a impl-b impl-c}}       ; join: b counts when its children do
   {:seon.task/name merge     :seon.task/needs #{integrate} :seon.task/execution :held}}}  ; root releases it
```

**The one query, "everything that should be running right now"**, over main with no scheduler state (`seon.db/q` takes
rules; `done` and `needs*` are recursive over the component tree):

```clojure
[:find [?t ...] :in $ %
 :where [?t :seon.task/id] (not [?t :seon.task/steps _])                    ; leaves run
        (not (done ?t))
        (not-join [?t] (needs* ?t ?n) (not (done ?n)))                       ; unblocked, ancestors' needs included
        (not-join [?t] [?t :seon.task/execution ?i] [(contains? #{:held :stopped :cancelled} ?i)])
        (not-join [?t] [?a :seon.task/assigned ?t] [?a :seon.task/end ?e] [(not= ?e :sent-back)] ;; or: an open attempt
                       )]
;; rules
[[(done ?t) [?a :seon.task/assigned ?t] [?a :seon.task/end :done ?tx]
            (not-join [?t ?tx] [?b :seon.task/end :sent-back ?tx2] [?b :seon.task/target ?t] [(> ?tx2 ?tx)])]
 [(done ?t) [?t :seon.task/steps _] (not-join [?t] [?t :seon.task/steps ?c] (not (done ?c)))]
 [(needs* ?t ?n) [?t :seon.task/needs ?n]]
 [(needs* ?t ?n) [?p :seon.task/steps ?t] (needs* ?p ?n)]]
```

The result splits by the roster: a task with an attempt whose admission is open and a graph → already running; with an
open attempt and no graph → re-host (after a restart); with no attempt → launch. Cost: proportional to the open trees'
leaves and their needs (reverse `:avet` on `:seon.task/needs`), never the 705 tasks (closed subjects are skipped by the
first `done` clause). The exact Datalog is a probe before building; the shape is `seon.plan/derived-frontier`'s.

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
| → the branch's own changes | **none today** | `seon.sci.eval/overlay!` re-interprets `overlay-plan` every acquisition whose commit differs from the loaded one | = cold: proportional to changed definitions and affected callers (hundreds of MB, plan item 12) | cache the acquired context by `[base-key commit]` as a `seon.cache` kind; `:stopped`→`:running` and t-resume become a lookup |
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

## 9. Round-4 owner questions (recommended first)

1. **No position: parallel by default, order by `needs`, parents never run.** Alternative: keep position as display order only.
2. **Problems are root-assigned tasks by identity; nominal ends wake nobody.** Alternative: root also gets a daily digest task (cron, no function).
3. **Which problem classes get a fresh agent rather than root**: error signatures and cost regressions (ordinary tasks); everything else root. Alternative: root for all until the classes are measured.
4. **One clock proc; the heartbeat is a cron task with a function.** Alternative: a dedicated heartbeat proc as in round 2.
5. **A firing to a live agent is a listened wake, otherwise an attempt; refused delivery is a problem task.** Alternative: always an attempt.
6. **The overlay cache (`[base-key commit]`) is the first performance lane of the path.** Alternative: current-state render cache first.

Not verified (probes before building): the §2 query's cost and Datahike rule support for `done`/`needs*` on the live
store; `host!` from the reconciler's proc thread; `fork!` from a historical commit; `flow/pause` + join as "finish the
current pass" against the turn-completion bound.
