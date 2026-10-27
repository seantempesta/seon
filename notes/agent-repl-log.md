# agent-repl: an outside agent's interface, problem by problem (2026-10-27)

Driven on the standing root (port 7888) through the `seon` MCP tools as an attached agent would.
Each row: what was seen, the cause, the owner, the state.

| # | Seen | Cause | Owner | State |
|---|---|---|---|---|
| 1 | `seon_agents` answers 580 tasks (544 :cancelled, most of them `-merge` twins left from before with-merge left the task files), cut by the reply budget, with no running task in view | `seon.server/agents-answer` answered `task-summaries` whole | this branch (task seon.server/seon-agents-answers-what-is-live) | fixed: `seon.server/live-tasks`, the tasks running or with runs, newest first, the rest counted by execution, the form that lists them all (8 tasks + `{:held 423 :cancelled 544}` on the standing root); proof seon.server-test/seon-agents-answers-what-is-live |
| 2 | an attached run went `:lost` two minutes in (the merge session's bin/update restarted root), and the reconciler started run #2 with the task's own model, none configured: "no model provider", the task held | `seon.reconcile/actions` spawns: the next run's env is `desired-env`, the task's model, even when the run a restart lost was an outside agent's | this branch | fixed: a lost outside run's successor is outside; proof in seon.reconcile-test/an-outside-run-is-not-killed-for-the-tasks-own-model |
| 3 | the plan cut "+86 chars" on the page (the blind agent's task) | `seon.render.ui/task-data` printed the plan at a part's budget (256 tokens), the instructions at the value budget | this branch | fixed: the plan at the value budget; proof seon.render-test/the-models-page-holds-the-plan-whole |
| 4 | `"own-problems-…" [:div {…} nil]` on every page with no problem open | `seon.context/page` kept a component that drew nothing (own-problems answers nil) | web-ui (the render walk: with-id gives a nil render its element, and the model's page must stay the user's, seon.ctx-test/an-agent-adds-a-datum-and-its-render) | handed over; leaving it out of placed-hiccup alone broke that equality |
| 5 | `[#'seon.render/html {:budget 600 :handle …} value]` around every value on the page | by design: the owner's 2026-10-26 ruling, "the model reads the call as returned" (seon.render-test/a-part-returns-its-data-as-a-call-of-the-printer) | web-ui | owner ruled: the page prints a render's value, not the call; web-ui builds it |
| 6 | related's `:functions` shows one and elides 4 (`…4 (drop 1 …)`) | `my.task/related` returns each function as [doc keys], so its first entry spends the value budget | relevance-lab (my.agent/search, related by relevance) | open |
| 7 | the system prompt (~3 k chars) on every `seon_context` | it is the first message of every request a model is sent: byte for byte, as asked | none | as designed |
| 8 | a bare `(my.agent/prove)` answered 25 k chars, 15 k of them `:selected` names, plus every report and digest | `my.agent/prove` answered the test run whole | first-request (root's ruling; on the schema-summary mechanism of jolt/outside-repl-surface) | this branch's version reverted (e73026cb69), its shape sent to first-request |
| 9 | `(my.task/status)`'s `:proof-unknown` `:do` for a written proof was the template `(deftest … (is false "write the assertion…"))`, which overwrites the finished test | `seon.context/refused` offered the template whether or not the branch defines the test (its own unit test asserted the template for a defined `demo-test/f-test`) | this branch | fixed: a written proof's `:do` is `(my.agent/prove)`; seon.context-test/an-unknown-proof-says-what-to-write |
| 10 | an attached agent's page read `whole · held`, and `(my.task/current)` `:held`, while main had the task `:running` (task seon.task/an-agent-reads-its-task-as-main-holds-it, reproduced at a1's REPL: `[:held :running]`) | the branch is cut before main writes the release; `seon.agent/begin-run!` wrote the run on the branch but not the task's execution | this branch | fixed: begin-run! writes the task running (execution is not carried at merge); proof seon.server-test/an-agent-reads-its-task-running |
| 11 | a prove of named tests does not count for the judge (proof-unknown) | | first-request session | left to it |
| 12 | an unrelated process test's red (it dies in the agent) holds `seon_done` as stray-red | a test child's death counted as a red, and seon.prove replayed a remembered answer with a death (same key), so the proof never ran again | jolt/every-ask-is-answered (4de409e71c: an answer with a death is not remembered; 36908842e5: a death is no verdict in seon.proofs/results); the deaths are Jolt fork bugs on the jolt session's pin branch | left to them (not merged yet) |
| 13 | working a task whose proof is a process test from the agent's REPL: `(my.agent/prove)` defers it to the judge, so the agent never sees it red before seon_done | by design (process tests run at the gate) | root | noted; the in-agent run of one named process test is the missing tool |

Process notes: after `git merge main` the worktree's submodule checkout lagged the pin (reference-code/jolt 8e5f6304 → 329c39f2) and one build ran on the old Jolt; `bin/worktree --sync NAME` from the main tree fixed it (AGENTS.md §8 says so; easy to miss).

## Reset and fork (root's ruling, built)

| # | Seen | Cause | Owner | State |
|---|---|---|---|---|
| 14 | (design) a fork attached with its parent's session key: both live agents wrote one agent entity on main (branch, id, status), and the parent's run named the killed fork | an agent's entity on main is its key's (seon.task/agent-key), one per live agent | this branch | fork goes through `seon.root/clone!` with the clone's own key: branch, program and live values continue, the conversation starts empty (the parent's evals stay in the branch's history) |
| 15 | a fork's context read failed `seon.turn/observe invalid-input` (plan nil) | its branch, cut from the parent's, lacks the fork's task; `seon.turn/begin!` read the db before `begin-run!` wrote | this branch | begin-run! writes a task its branch lacks as main declares it (task/create-tx); begin! reads after |
| 16 | `seon_attach` on a task already worked answered `seon.server/defect` "branch … is held by live agent" | attach! always spawned | this branch | it answers the live run's agent |
| 17 | a clone kept its parent's `:task`, so `(my.task/current)` named the parent's task | the clone message carried no task | this branch | the clone takes the task it was cloned for |
| 18 | main's f634a4a41c ("server_test: an old word in a comment") moved the Jolt pin back from 329c39f2 to 8e5f6304 | looks accidental | root / merge | reported |

Proofs: seon.server-test/a-reset-starts-the-task-again-from-main, a-fork-goes-on-from-an-agents-head; bin/check smoke, orphan, mcp-proof, durable-write OK (clone's users).

## One small real task end to end (my.agent/a-deferred-test-names-the-process-it-reaches, on this worktree's root)

attach → seon_context → one reproducing eval (a deftest that calls in-agent, then `(my.agent/prove)`) → `(my.task/fail! ...)` → seon_done answered `:closed :failed`, the task held with the reason and its counterexample. Four requests, no workaround. What the context lacked:
- the page cited only `seon.program/reach-graph`, not the producer of the answer the plan says is wrong (`my.agent/prove` → `seon.agent/run-tests-forked`); one eval found it, since its answer already carries `:why`. A plan names what it changes; this one named a helper (the task file's text, fixed by its removal here).
- the code half of the premise was false on main: `:why` already names each deferred test's process var and the calls to it. The task was stale; a task whose proof was never written and whose plan names code that main already answers could be flagged before an agent is spent on it.
- its other half was a wiki edit (help's Done section), which an agent cannot write back from its REPL (THE PLAN 0.8). Made on this branch (docs/agent.md, "Run what judges your branch now"), and the task's entry removed from resources/seon/tasks/my.agent.edn.
