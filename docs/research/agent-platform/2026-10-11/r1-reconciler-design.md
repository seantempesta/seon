---
title: "R1: the reconciler as a plan over facts and the roster"
type: research
status: design for review (root and owner); no code lands before it is accepted
basis: HEAD 4e8ce1164 read by symbol; working tree with held-default's frontier (only `:running` leaves); JVM probes on default (store at commit 6acb5eed…, which predates S1, so `:seon.task/execution` is not installed there)
---

# R1 reconciler design

What exists: `seon.cluster.agent/armer-step` reads every agent row, keeps the unarmed ones with work
(`agents-to-arm`) and calls `arm!` for each one. `seon.cluster.host/host!` acquires a branch execution, admits the
agent on the cluster and on the branch (`home-call`) and starts a branch armer graph. `seon.task/frontier` (S2) answers
the leaves that should run now. The principal approach is **one pure function from facts and the roster to actions**,
plus one small effect for each action. The reconciler is that function, called by the armer's transform on each wake
and at `::flow/resume`.

## 1. The pass: data in, actions out

```clojure
(seon.cluster.agent/reconcile-plan facts roster max-running) ; pure; → [action ...]

facts   {:frontier #{handle}                                  ; seon.task/frontier on main's value
         :open     [{:task h :attempt k :agent id :branch b :intent i}]  ; attempts with no end, joined to their task's intent
         :ended    {h n}                                      ; ended attempts per task (the next attempt's number)
         :created  {h t}                                      ; the tx of [?t :seon.task/id h], for order
         :basis    {h commit-id}                              ; the predecessor's :seon.task.attempt/commit, absent = main
         :cluster  :running | :paused}                        ; :seon.cluster/execution
roster  {:hosted #{branch} :armed #{branch}}                  ; from the cluster routing's :seon.agent/children and each host's :seon.agent/armed
actions [{:op :attempt :task h :attempt k :agent id :branch b :fork-point c|:main}
         {:op :host  :task h :branch b}  {:op :arm :task h :branch b}
         {:op :pause …} {:op :stop …} {:op :cancel …}]        ; the last three are R2
```

The rules are the whole design:

| Fact × roster | Action |
|---|---|
| a frontier leaf with no open attempt, while open `:running`/`:paused` attempts < `max-running`, oldest `created` first | `:attempt`: write the attempt, then host and arm it in the same pass |
| an open attempt, intent `:running`, branch not hosted (a restart, or a crash between the write and the host) | `:host` (fork answers `created? false`, `host!`, `arm!`) |
| hosted, not armed | `:arm` |
| hosted and armed | nothing |
| intent `:held` on a leaf | nothing: the frontier never holds it |
| cluster `:paused` | `:pause` on every hosted branch (R2) |
| intent `:paused` / `:stopped` / `:cancelled` on an open attempt | host without resume / `flow/pause`, join, stop, release (P3, no interrupt) / interrupt and end `:cancelled` (R2, D3) |
| an ended attempt still hosted | `:stop` (keep the branch for its merge request) (R2) |

**Identity, with no flag and no lock.** Attempt number `n` = 1 + the task's ended attempts. The attempt key is
`k = (seon.id/id [handle n])`, the agent is `"attempt/<k>"` and the branch is `:attempt-<k>`. The assignment row keeps
today's identity `seon.id/id [handle agent branch]`, which is then a function of `k`. A second pass re-derives the same
`k`. It sees the open attempt and writes nothing. `fork!` answers `created? false`, `host!` answers from the roster and
`arm!` leaves an armed agent alone.

**Write first, then make the runtime follow.** The attempt row is the desired state. It is written before anything is
forked or hosted. A crash at any later point leaves an open attempt with no host, and the next pass (the prime after a
restart) hosts it through the `:host` row. Launch and re-host are therefore one effect, and restart needs no code of its
own (R3 only adds `--paused`).

**max-running falls out of the facts.** The bound counts open attempts on main, not graphs in the roster, so it holds
across a crash and needs no counter. Lowering the dial never kills anything. It only stops new `:attempt` actions.

Prototype at the JVM REPL (scratch `user/plan`, 25 lines):

- A fresh `#{A}` gives one `:attempt`.
- Applying that write and planning again with an empty roster gives `:host`, the restart case.
- With A hosted and armed, the plan is `[]`.
- After A ends `:done`, `#{B}` gives an `:attempt` whose `:fork-point` is A's commit.
- Ten ready leaves with `max-running 4` give 4 actions.
- 50 frontier leaves and 200 open attempts plan in 0.5 ms and 0.35 MB.

## 2. Functions and contracts

| Function | Contract | Notes |
|---|---|---|
| `seon.cluster.agent/reconcile-facts` | `[:=> [:cat :seon.db/database-value] [:or ::facts :seon.db/invalid-read-error]]` | four reads on main: `frontier`, open attempts with intent, ended counts, bases |
| `seon.cluster.agent/reconcile-plan` | `[:=> [:cat ::facts ::roster :int] [:vector ::action]]` | pure; the R1 tests are a table over it |
| `seon.cluster.agent/roster-view` | `[:=> [:cat :seon.agent/routing] ::roster]` | reads `:seon.agent/children` and each `::host`'s armed set; no locks |
| `seon.cluster.agent/launch!` | `[:=> [:cat :seon.agent/launch-request] [:or :seon.agent/launched :seon.task/refused-error]]` | one action's effect: `:attempt` writes (`seon.task/attempt-tx` at main), then the host effect |
| `seon.cluster.host/assemble!` | card R1's request → `[:or ::assembled ::refused-error]` | `fork!` from the fork point (`commit-as-db`, released after), `host!`, then `arm!` when `graph?`; the world comes from W1's `seon.turn.world/world` once it lands, and the environment from R4's cached fork |
| `armer-step` 3-arity | unchanged signature | `facts → plan → run! launch!`, then today's `agents-to-arm` for cluster agents |
| dial `:seon.config.agent/max-running` | `[:int {:min 1}]`, default 4 | in `seon.config.agent.edn` and the defaults |

**Part (3) of the assembly, the task driving the context, is the attempt row itself.** `assemble!` renders nothing. The
agent's first turn pins a database value and renders `seon.context/current-state` from that row. Rendering it in the
reconciler would be thrown away, because the turn re-renders from its own value.

**Contract S3 must honor** (lane held-default, then S3):

- `seon.task/attempt-tx [db handle agent-id branch]` writes the agent's creation (as `home-call` decides) in the same
  transaction as the row, so the `:seon.task/agent` ref resolves. It also writes `:seon.task/attempt-of`.
- `:seon.task.attempt/end` and `:seon.task/needs` carry `:seon.wake/arms true`, so that A's end wakes the armer and B
  launches. Today only `:seon.task/execution` arms.
- `seon.turn/next-agent-work` treats a fresh attempt as work.

R1 does not depend on that last item, because the reconciler arms directly.

## 3. Cost, measured on default (JVM, the calling thread, `seon.profile/measure`)

| Leg | ms | MB | Proportional to | Owner |
|---|---|---|---|---|
| frontier (S2, check JVM, 734 tasks) | 5.9–8.3 warm | 1–4 (P1) | all task rows (about 0.008 ms per closed leaf) | `seon.task/frontier` |
| the plan | 0.5 | 0.35 | frontier + open attempts | new |
| `fork!` from main's head | 47 | 5.7 | one transaction | `seon.cluster.registry/fork!` |
| `fork!` repeat (`created? false`) | 0.2 | 0.2 | constant | same |
| `host!` (fresh branch, unchanged program) | 76 | 45 | two `home-call` transactions (44 ms) + acquisition | `seon.cluster.host/host!` |
| `arm!` | 14 | 15 | acquisition for the agent + `start-graph!` | `seon.cluster.agent/arm!` |
| `arm!` repeat | 0.06 | 0.01 | constant | same |
| the launch (attempt tx, est. 25 ms, + the above) | **about 160** | **about 66** | one task | |
| first-turn context, a task with 7 must-reads and 4 proofs | 86–177 | 148–354 | **proof reach and contract references, not must-reads** | `seon.context/current-state` |
| retire (stop + release + branch) | 77 | 2.7 | joins | `host/retire!`, `registry/retire-branch!` |

What the numbers say:

- **The launch is already under budget** without R4 when the branch's program equals the loaded one: about 160 ms and
  66 MB against 1 s and 300 MB.
- A branch whose program moved adds the overlay leg (interpreted definitions). R4 closes that.
- **The heavy leg is the first context, and it is outside the launch.** `current-state` spent 98 ms in 1,072 calls of
  `seon.schema/contract-references` and allocated up to 354 MB. Most of that came from `seon.test.runner/reach-*` for
  the proofs' status. It has no `seon.cache` kind, so every turn pays it.
- The lightness proof `starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` therefore needs a decision on its
  scope (Q2). If it measures launch to armed, it is green today without R4. If it includes the first context, it is
  red until `current-state`'s proof read is cached by content (a `seon.context` task for its owner, filed by root).

Each pass costs the four fact reads (about 10 ms) on every arm wake, and the armer's thread holds about 160 ms per
launch. `max-running` bounds a pass at about 0.7 s.

## 4. What it deletes, and what it adds

Kept and composed:

- `host!`, `arm!` and `fork!` are kept unchanged.
- `assemble!` is about 25 lines, and the plan with its facts is about 60.

Deleted or made deletable:

- **R1 deletes:** the second arming story for tasks. Today an assigned branch agent is re-hosted after a restart by
  nobody (§8). `agents-to-arm` then only serves cluster-branch agents.
- **R1 makes deletable, in other owners' cuts:**
  - `my.message/send`'s `host!` call, a second starter of graphs (`my.message`). Once every task agent is an attempt,
    the reconciler re-hosts the recipient.
  - `seon.test-support/own-branch-agent!` (D1).
  - The proof run in `seon.task/assign!` (S3).
  - The schedule clause of `agents-to-arm` (D2).
- **The branch armer graph** that `host!` starts is one graph and one platform thread per hosted agent. A hosted agent
  today runs four threads: one branch armer plus three agent procs, two once D2 deletes the schedule proc. The branch
  armer only owns the branch's `wake/route!` listener and arms agents that later appear on the branch. See Q1.

## 5. Test plan (red first; all through the pure plan except two)

- A table over `reconcile-plan`:
  - `two-reconciler-passes-launch-one-attempt`
  - `a-held-leaf-is-never-launched`
  - `max-running` (10 ready, 4 launched; 4 open, 0 launched)
  - restart: an open attempt with no host gives `:host`
  - `a-successor-forks-from-its-predecessors-end-commit`, at the plan level
- One integration test on a disposable branch of the live store: the two-leaf chain. Setting the subject `:running`
  launches A only. A's end with a commit launches B forked from that commit. Dropping the roster and priming re-hosts
  the open attempt.
- `starting-one-agent-…` through `seon.profile/measure`, recorded as profile evidence.

The integration test needs S1/S2 adopted on default. **Default runs a commit before S1** (`seon.db/q` refuses
`:seon.task/execution` as uninstalled), so a restart is needed before any live proof: **RESET NEEDED** after
held-default lands.

## 6. Open questions

1. **Keep the branch armer graph or fold it into `assemble!`?**
   - (a) Keep it. This is the smallest R1: `host!` stays as it is, and there is one extra graph per agent.
   - (b) **Recommended:** `assemble!` registers the branch `wake/route!` listener itself and arms the one agent. The
     branch armer goes, which removes a thread per agent and the second place that decides arming. It costs about 20
     lines in `host.clj`, and agents that appear later on a branch are no longer armed there. That is right, since
     only the reconciler starts graphs.
   - (c) Defer to R2.
2. **The lightness proof's scope.**
   - (a) **Recommended:** launch to armed. It is green today, so it guards a regression and is not a red-first proof.
   - (b) Launch plus the first `current-state`. It stays red until the context's proof-status read is cached, which
     belongs to the `seon.context` owner, not R1.
3. **The fork point of a join.** A leaf with several needs, or a need on a parent, has several end commits.
   - (a) **Recommended:** main's current value, with done predecessors expected to be merged first. That holds when
     root's merge request is how a green end reaches main.
   - (b) Fork from the newest predecessor and `merge!` the others into the branch.
   - (c) Refuse, with a problem task (Q1-class `stuck chain`).
4. **An open attempt whose task becomes `:held`.**
   - (a) **Recommended:** treat it as `:paused`, so the graph stays, nothing runs, and it can be inspected.
   - (b) Stop it.
5. **Where `:paused` is observed.** The roster does not say whether a graph is paused. R2 needs `flow/ping` or a
   roster entry field. R1 writes nothing for it.
6. **Agent ids.** `"attempt/<k>"` and `:attempt-<k>` derive from the attempt only.
   - **Recommended:** keep the task's symbol out of the id, so a rename never re-derives a different agent.
   - Alternative: `"<task-ns>/<task-name>#n"`, which is readable but breaks on a rename.
