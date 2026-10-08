---
type: research
status: proposed
date: 2026-10-08
lane: redo-finish
---

# How a task finishes: proofs, proposal, answers, bail, wait

Redo of the completion and proof-status surface that both reviews flagged (Opus §1 row 1 and contradictions 1, 2, 4;
Astra contradictions 7, 8, 9, 17 and §6 items 1, 2, 5, 6). Authority: `review/owner-rulings.md`, CLAUDE.md. Code at
HEAD `aeb692d87`; live probes on default as MCP agent `redo-finish-probe` (SCI, own branch).

**Design in one breath.** The agent works and never declares success. After any pass that wrote something (a
definition, an answer, its own data), its proof proc runs the task's proofs, and only the proofs, by identity. Green
results whose reach still holds are reused. The proc then derives the outcome from stored results. A code task with a
program change is proposed, and it completes only when root accepts it on the combined program. A question task
completes on its branch, with no proposal. The agent has two verbs of its own: `my.task/complete!` with a reason (bail)
and `my.turn/wait`. Status lives only in the current-state block.

## 0. Broken things found first

1. **A caller keeps the definition from an earlier pass. This is a REPL-law violation, and proofs go green on stale
   code.** Pass 1: `(defn g2 [] 1) (defn h2 [] (g2)) (defn g2 [] 2)` → `[(h2) (g2)]` = `[2 2]`. Pass 2:
   `(defn g2 [] 3)` → `[2 3]`. Clojure answers `[3 3]`. With contracts the result is the same. A deftest written
   before the redefinition sees the old callee in the agent's context and on the test member's branch.
   `(deftest other-squares (is (= 4 (f/other 2))))` and `(deftest other-is-five (is (= 5 (f/other 2))))` were BOTH
   recorded green by one `my.test/check`, while `(f/other 2)` answered 6. Every completion rule below is unsound until
   this is fixed. Filed as `seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass`. Suspected owner: the
   pass-boundary advance or the overlay of PB-01 (`src/seon/sci/eval.clj:2686-2787`); not diagnosed further (pb-01
   lane's file).
2. **A same-pass check answers from the old program.** `(defn area …)` redefined, then `(my.test/check {:seon.test/changed
   ['…/area-squares]})` in the same pass gave `:executed-count 0 :reused-count 1`. A check in the next pass executed the
   test. This class is the existing same-pass check task (messages audit). Auto-run is not affected, because it runs
   after settlement.
3. Runtime health was `:faulted` (12 over-bound warnings, `seon.cluster.registry/branch!` max 151 s). These are
   existing tasks. `my.agent/retire-branch!` answered the known bare `admission-closed` (`my.agent.edn:7`), so the
   probe branch stays linked.

## 1. What exists (HEAD, verified)

- Three session-end teachings: help says `(my.agent/done)` must be the last form (`src/seon/bootstrap.clj:63`), and
  `my.agent/done` is a `wait` alias (`src/my/agent.clj:23-34`). The opening says "Every run ends with my.turn/complete
  or my.turn/wait" (`src/seon/cluster/agent.clj:410-411`). `my.turn/complete` is a success reply (`src/my/turn.clj:5`).
  `my.task/done!` completes a STEP (`src/my/task.clj:163-175`), and help teaches it (`bootstrap.clj:58`).
- Steps: `plan!`, `step!`, `done!` and `steps` (`src/my/task.clj:128-190`), the writer `seon.task/steps!` and its
  view (`src/seon/task.clj:520-640`), `:seon.task/steps` on the `:work` kind, render rows (`src/seon/render/ns.clj:96-115`)
  and `test/seon/task_steps_test.clj`. `plan!` with no task makes a self-assigned `:work` task with no proofs.
- `my.program/propose-merge!` (`src/my/program.clj:416-460`) already runs ONLY the proofs, by identity:
  `{:seon.test/policy :named :seon.test/identities proofs}`. It then checks `seon.test/verified?` per proof and writes
  `task/propose!` plus one message to root. With greens current, the run inside it reuses them. Measured: a reused
  one-test request takes 7-9 ms / 1,556 calls.
- `my.test/check` passes `:seon.test/changed`. Under `:named`, `select` adds every test reaching the changed set
  (`src/seon/test.clj:871-880, 942-945`). That is the reaching selection Astra flagged. `select` also adds `(keys
  pending)` for every policy (`:880`), so a request that names only proofs still re-runs earlier red non-proof tests
  (code read, not probed).
- `seon.test/verified?` (`test.clj:1562-1593`) is green, input-digest equal and reach-digest equal. Measured 1-2 ms per
  proof. This is the block's status read.
- `:seon.task/kind` is `#{:function :namespace :schema :merge :work}`, a subject kind (`seon.task.edn:9`). Nothing
  marks a question task.
- No proof proc exists, and nothing schedules proofs (agent-loop §4.5 "Not installed").

## 2. What changes

**Trigger (replaces the changed-set union).** At SCHEDULE, a pass whose settlement carried any write besides its own
recording rows (program facts, `answer!`, the agent's `transact!`) sends one signal, `:branch-advanced`, to the agent's
proof proc (`:compute`, in the agent's graph). The proc state is `{:running? bool :dirty? bool}`. When idle, it starts
one request. A signal during a run sets `dirty?`, and after actual exit a dirty proc runs once more. There is no
changed set: every task proof is always requested by identity, and reuse by reach digest makes unchanged proofs cost
milliseconds. This also covers a proof whose callee did not exist before (Astra §6.6).

**Request (proofs only).** `seon.test/run {:seon.test/policy :named :seon.test/identities proofs}`, with no
`:seon.test/changed`. This is the request `propose-merge!` already makes. `select` stops adding `pending` members that
the request did not name. Reaching tests never run during work. They run once, at merge, on the combined program.

**Outcome (derived after each request, by the proc, from stored results).**

| Task | All proofs `verified?` at the branch head | Outcome |
|---|---|---|
| `:question` kind | yes, and each member read the agent's current answer digest | the task is **done**, written by the task-home completion writer (Q16), with no proposal; the assigner gets one message |
| code (any other kind), the branch's declarations differ from the cluster head (`seon.test/changes`, `test.clj:1802`) | yes | **proposed**: the proc calls `propose-merge!`'s body. Its run reuses the greens (no re-run, about 9 ms per proof); one open proposal per assignment is replaced by a newer commit |
| code, no declaration differs from the cluster head | yes | done: the branch program is the head program, so it already is "the combined program" |
| code, proposed | root's `accept-merge!` with proofs green on S | **done**, written by the same writer inside acceptance (`merge.md` M-rows) |
| any | some red | nothing; the block shows the red data; a red recorded after the answered basis wakes the agent (AR-03) |

The agent never proposes by hand: help does not teach `propose-merge!`. The function stays callable for root's tests
and outside agents.

**Question-task custody (Astra §6.2).** The answer is `:seon.agent/answer` on the worker's own agent entity, on its
branch (competency A1). The member forks from that branch, so the fact is present there, but the member's own world
agent is the member. A proof therefore reads `(my.task/answer {})`, which declares `:seon.test.run/tested-branch` as
its input. The member world supplies it, and the agent that owns that branch gives the answer. The read records the
answer's blob digest on the member as `:seon.test.member/answer`. Outside a member world the input is missing and the
call refuses by name. The agent itself reads `(my.agent/answer {})`.

**Restart.** Nothing to persist. At proc start, and on each new assignment, the proc sends itself one
`:branch-advanced`. The run's reuse makes this a no-op when everything holds. A crash mid-request leaves an
unterminated run, which select already treats as not green.

**Status: the block only.** AR-04 is deleted. The Var render drops its reaching-test status and its `my.test/check`
offer (`src/seon/render/var.clj:85-139`), as P10 (`#'ns/x`) wants. CLAUDE.md "the Var render only reads their status"
is corrected by root.

**Steps: delete (recommended).** The proofs are the definition of done and the history already holds the agent's own
plan as comments. Steps add a third "done" verb and uncached block tokens on every call, and `plan!` mints proofless
`:work` tasks, which the assignment law refuses. Deleted: `plan!`, `step!`, `done!` and `steps`, `seon.task/steps!` and
its view, `:seon.step/*` and `:seon.task/steps`, the render rows, and `task_steps_test.clj` along with its tests. Kept
alternative (option b): steps as agent data rendered in the block's Task section, with `done!` renamed `step-done!`.

## 3. Exact lines

**Help (static prefix; one source: `help-value`; replaces the `my.agent/done` and `turns-left` lines and the steps
line):**

```clojure
{:seon.help/says "Your task is done when its proof tests pass. They run by themselves after each reply that changes your program or answer; the status block shows them."}
{:seon.help/says "A code task whose proofs pass is proposed to root for you. Keep working only while a proof is red."}
{:seon.help/says "A question task: answer with data and name your sources. It is done when its proofs pass."
 :seon.help/forms [(my.agent/answer! {:seon.agent/answer {:customer "c-17"} :seon.agent/sources '[result/e1a2b3c4d5e6]})]}
{:seon.help/says "If the task cannot be done, give it back with a reason naming what blocks it."
 :seon.help/forms [(my.task/complete! {:seon.task/reason "proof t1 needs Feb 29 2023, which does not exist"})]}
{:seon.help/says "To pause until a message or a red proof arrives, wait. A reply with no form also waits."
 :seon.help/forms [(my.turn/wait {:my.turn/note "proposed; waiting for root"})]}
```

**Block (`:seon.context/current-state`, data; Turns section):**
`{:task/status :working :proofs {:green 1 :red 1 :running 0 :pending 0} :calls-left 6}`, with the red as data.
Once all are green: `{:task/status :proposed :proofs {:green 3} :calls-left 6 :seon.render/operations [(my.turn/wait
{:my.turn/note "proposed"})]}`. For a question task, `:task/status :done`. After a bail, `:bailed` (the block is shown
only while the session runs). Statuses come from the `:seon.task/status` enum
`#{:working :proposed :done :bailed :capped}`, derived and never stored.

## 4. Deleted

`my.turn/complete`, `my.agent/done`, `seon.run/complete`, `my.task/done!` and the step API (option a), the opening's
"Every run ends with…" line (S4-02b deletes the opening), help's `(my.agent/done)`, `turns-left` and steps lines,
AR-04, the Var render's status and check offer, the changed-set union in agent-loop §4.5, and `:seon.test/changed`
in the auto-run request. Competency §3's "Run them, read your diff, then propose" is replaced by §3 above.

## 5. Cost per operation

| Operation | Measured / target |
|---|---|
| signal at SCHEDULE | 0 tx, < 1 ms (a `>!!` on the proc's input) |
| block status: `verified?` per proof | 1-2 ms each, measured; 3 proofs < 10 ms |
| proof request, all reused | 7-9 ms / 1,556 calls, measured |
| proof request, one test executes | **678-815 ms / 74-76k calls, measured** (first in a session); 267-446 ms later. Budget 150 ms / 20 MB |
| propose after green | the reused run (about 9 ms) + `propose!` (1 tx) + 1 message tx |
| question completion | 1 tx at the task home + 1 message |

**Where the 815 ms goes** (`seon.profile/explain` on the check pass): `seon.test/select` 518 ms, of which
`changed-definition-symbols` (a history `since` attribute scan, `test.clj:597`) is 443 ms; 4 recording transactions
243 ms (`validate-write-report` 118); `clean-request-handle` 182-222 ms (`program-overlay-tx` 43, `checkpoint-head`
34, `digest-differences` 32); the member itself, acquire 20-39 + run 14-23 + release < 1 ms. **Measured floor:** member
plus one recording transaction, about 60-100 ms. 150 ms is reachable only after F1-F3.

## 6. Slices (about 15 minutes each; proofs assert data through `seon.mcp/call`)

| Id | Change | Files | Contract | Proof |
|---|---|---|---|---|
| F0 | fix the stale caller (§0.1); owner pb-01 or Opus | `src/seon/sci/eval.clj` | unchanged | new `seon.repl-differential-test/a-later-pass-redefinition-reaches-earlier-callers`: `[(h2) (g2)]` equals plain SCI's `[3 3]`; a pre-written deftest records red |
| F1 | a request naming `:seon.test/identities` without `:changed` skips `changed-definition-symbols`; reuse by member reach digest only; `pending` restricted to named identities | `src/seon/test.clj` | `select` unchanged | new `seon.test-test/a-proof-request-runs-only-its-proofs`: a red non-proof reaching test from an earlier check is not a member; `:executed-count 1`; budget row < 400 ms |
| F2 | `clean-request-handle` writes the member overlay as one branch transaction | `src/seon/test.clj` | unchanged | existing `seon.sci.documentation-test/run-test-var-answers-one-branch-test-as-data` + budget row < 250 ms |
| F3 | member recording in one transaction (4 → 1) | `src/seon/test/runner.clj` | `commit-results!` unchanged | budget row: one executed proof < 150 ms / 20 MB of Seon work in `seon.pass-probe` |
| F4 | proof proc: `:branch-advanced` signal, `{:running? :dirty?}`, proof-only request, start signal | `src/seon/turn.clj` (settlement flag), agent graph proc table, `test/seon/cluster/turn_test.clj` | `proof-step [state signal] → [state outputs]` | AR-01's two named tests + new `…/a-restarted-proc-runs-pending-proofs` (member row after restart with no pass) |
| F5 | outcome derivation: question → done; code + change → propose (reused run); code, no change → done | `src/seon/task.clj` (completion writer, Q16), `src/my/program.clj` | `outcome [db assignment] → :seon.task/status` | new `seon.task-ingestion-test/green-proofs-propose-once` (second green, same digests: one proposal); new `…/a-green-question-completes-without-a-proposal` (no merge task row) |
| F6 | `:question` added to `:seon.task/kind`; `my.task/answer` reads the tested branch's agent answer and records `:seon.test.member/answer` | `resources/seon/schemas/seon.task.edn`, `seon.test.member.edn`, `src/my/task.clj` | `[:=> [:cat [:map [:seon.test.run/tested-branch …]]] [:or :any-edn :seon.error/value]]` (after A1) | new `seon.competency-test/an-answer-without-code-runs-its-proof`; new `…/a-changed-answer-invalidates-green` |
| F7 | delete `my.turn/complete`, `my.agent/done`, `seon.run/complete`; `my.task/complete!` bail (= S6-06b) | `src/my/turn.clj`, `src/my/agent.clj`, `src/seon/run.clj`, `src/my/task.clj`, schemas, tests | `complete! [{:seon.task/reason string}]` | `seon.task-ingestion-test/completion-with-a-reason-gives-the-task-back` |
| F8 | delete steps (option a) | `src/my/task.clj`, `src/seon/task.clj:520-640`, `src/seon/render/ns.clj`, schema, `test/seon/task_steps_test.clj` | — | `seon.contracts-compile-test` + `seon.render.ns-test` green; grep `:seon.step/` empty |
| F9 | help lines of §3 (one row with S1-10/H1/MS-S6); Var render status and offer deleted | `src/seon/bootstrap.clj`, `src/seon/render/var.clj` | `help-value [] → [help-entry]` | `seon.help-test/help-names-only-installed-forms` (every form head resolves) |

Order: F0 first (soundness). F1 → F2 → F3 in parallel with F7/F8. Then F4 → F5 → F6 → F9. F5 needs merge M-rows for
code completion.

## 7. Owner questions

1. **Question vs code: by task kind or by branch contents?** Root proposed branch contents. **Recommend the kind**
   (`:question`): a question agent's scratch helpers would otherwise be auto-proposed and merged.
2. **Steps: delete or keep as block data?** **Recommend delete** (§2).
3. **Proposal after more work:** replace the open proposal with the newer green commit, or freeze it after the first?
   **Recommend replace** (one open proposal per assignment; root always reviews the latest).
4. **The 150 ms one-test budget:** accept 400 ms after F1 as an interim, with F2-F3 to reach 150? **Recommend yes**;
   the floor is 60-100 ms.
5. **CLAUDE.md "the Var render only reads their status"** contradicts status-only-in-the-block. **Recommend root
   rewrites it**: "the task's proofs run off the pass; their status shows only in the current-state block".
