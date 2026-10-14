---
title: "S5 run 3: the filed-task chain's Design agent took its first model turns (D3, D4 and S3a-2b worked) and ended :capped at 52 DeepSeek calls (2.29M prompt tokens) with Prove and Implement never started. Three defects spent it: a truncated provider tool call parks a chain agent until an outside wake (D5), the instructed my.task/update! form collides with the connection arity (D6), and from-filed writes no ## Problem heading, so :problem-changed can never pass (D7). Root does not process a :capped chain end (D8). One hand act, then abandonment; config restored"
type: research
status: complete (lane s5-run-3 acting as root, 2026-10-14 11:35-11:48 UTC); abandoned after Design capped, residue cleaned, config restored and verified
created: 2026-10-14
---

# S5 run 3

Script: `docs/research/agent-platform/2026-10-14/s5-run-plan.md`, as amended for run 2: no stage proof run on main,
`seon.task/from-filed` builds the chain, and `seon.task/chain-handles` walks it. Default pid 55634 booted
11:34:08 UTC from `data/source/e1b5475285b5…` (HEAD `e1b547528`), `:seon.boot/missing-layers []`.

**Broken first.**

1. **D5: a truncated provider tool call parks a chain agent forever.** Nothing ends it and nothing tells root.
   - Model call 13 (turn `bae43b5c4b79`, 11:36:25) came back HTTP 200 with a cut-off `eval` tool call:
     `{"arguments" "{\"code\": \"(my.agent/id)\\n;; the current plan so I can target one exact passage", "name" "eval"}`.
     `seon.ai/tool-call-reply` recorded `:seon.ai/unparseable-body-error` ("The provider's tool call carried no name,
     or an eval call no code text."), error `70a3fb5f…`, notified, on the chain branch only. Main's open faults stayed
     at 2.
   - After that, `seon.turn/next-agent-work` answered `{:seon.turn.work/situation :prove}` and
     `seon.turn/opening-deferred?` answered `true`. The turn thread sat parked in `clojure.core.async.flow.impl` with
     CPU flat at 4,937 ms for 4 min 50 s. There was no attempt end, no problem task and no health signal.
   - Cause: `opening-deferred?` says "a provider refusal awaits a new outside wake". A chain agent never gets an outside
     wake (§2.3: it starts with no wake), so one malformed provider reply ends its work silently.
   - Owner: `seon.turn/opening-deferred?` / `seon.turn/next-agent-work` for chain agents. Either the attempt ends
     `:error` and goes to root (§2.7), or the turn answers the agent with the error as data, under the turn bound.
   - Proof shape: a chain agent whose provider attempt returns an unparseable tool call takes its next model turn,
     or ends `:error`, within one pass.
2. **D6: the template's own `my.task/update!` form cannot run.** Design's instructions say
   `(my.task/update! this-task :seon.task/plan my.edit/text old new)`. That is 5 arguments, and
   `my.task/update!`'s first arity is `[connection agent-id sym k f]`, also 5. "A call already matching a declared
   arity is never prefixed", so the connection and agent id are never filled, and argument 0 (the task symbol) is
   refused:
   "my.task/update! refused argument 0 `connection`: expected :seon.db/connection …, got a symbol
   seon.error/an-unresolved-alias-name-offers-its-nearest-core-name."
   - The agent tried the 5-arity, then `(seon.db/db)` in front (6 args, "got a map"), then `seon.db/connect`
     (unresolved). It searched 19 times for how to reach a connection, `(resolve 'this-task)` twice, read
     `seon.call-preparation`, `seon.env/of` and `my.program/supplied-context`, and finally wrote
     `(my.task/update! seon.db/*conn* "attempt/9107c5cedfe0" 'seon.error/… :seon.task/plan my.edit/text old new)` at
     about call 37. Roughly 24 of the 52 calls went on this.
   - Its own words at the point it gave up on the instructed form: "The five values I pass match `[sym k f a b]`,
     and supply should prepend. The refusal says arg 0 is `connection` = my symbol, so supply did not fire."
   - Owner: the arity-plan of `seon.call-preparation` against `my.task/update!`'s contract (an omitted
     schema-named prefix and an equal-count arity collide), or `my.task/update!`'s contract. Choose one: its arities
     must not be confusable by count. The template text in `seon.step.design/template` (and Prove's `next-task` form)
     must run as written.
   - Proof shape: `(my.task/update! 'ns/task :seon.task/plan my.edit/text "a" "b")` at an agent's REPL changes the
     plan.
3. **D7: `seon.task/from-filed` writes the filed plan with no `## Problem` heading, so Design's `:problem-changed`
   can never pass.** Run plan problem 2 named this gap; it is still open.
   - `(keys (seon.step/sections (:seon.task/plan filed)))` is `nil`, and `seon.step.design/created-problem` answers
     `""`.
   - The instructions say "Read `## Problem` first and leave it as it is". The agent did what they implied and put
     `## Problem` on top of the filed text. From then on the problem section differed from `""`.
   - At the cap, `seon.step.design/handoff` on the branch answered exactly `[:problem-changed]`. The What exists and
     Approach checks, references, producer citation and data modeling all passed.
   - Owner: `seon.task/from-filed` (S3f-1): put the entry's plan under `## Problem`.
4. **D8: root does not process a chain task's `:capped` end.** At 11:44:10 the attempt got
   `:seon.task.attempt/end :capped` and its end commit. Nothing followed: no outcome, the task stayed `:running`, no
   problem task appeared, and the agent's threads lived on. `seon.cluster.agent/chain-ends` reads only `:done` ends,
   so §2.7 (bail or fail) is not installed for chains. Owner: `seon.cluster.agent/run-pass!`'s lifecycle phase (S3c).
5. **Unsticking needed an outside message that root's tools cannot send** (part of D5's cost).
   - `my.message/send` from root on main answered `:seon.message/undeliverable` with cause `:no-graph`, even though
     the agent's graph was running. The real cause was `:seon.message/unknown-recipient`: the agent exists only on
     the chain branch. Passing `:seon.agent/routing` did not help.
   - On the branch connection it was accepted, but a message carrying `:seon.message/from` is an inside wake, and
     `opening-deferred?` stayed `true`.
   - What worked was an outside, human-shaped message: `seon.cluster.message/inbound-tx` transacted on the branch
     connection with `seon.db/*conn*` bound to it, the same write the web message form makes. Without that binding
     the writer refuses ("This write names branch :chain-f1f6dc8e6652, which is not the writing cluster's branch
     :cluster-default").
   - The outside wake also refilled the turn bound. That is why Design reached 52 calls, not 40.

## Readiness (symbols and committed state only; no test run on main)

| Row | Answer |
|---|---|
| boot | source-root `e1b5475285b5…` = HEAD, missing-layers `[]` |
| resolve | `chain!`, `from-filed`, `chain-handles`, `done-refusals`, `advance-tx`, `design/handoff`, `prove/handoff`, `seon.test.runner/current-results`, `merge-chains!`, `finish-chains!`, `my.root/close!` all true; `my.root/run!` false |
| chain facts on main | `#{}` |
| templates | `(#{seon.step.design-test/handoff} #{seon.step.prove-test/handoff} nil)` |
| subject still red | `:installed-nearest nil` |
| files free | `bin/lane status`: no live claims |
| filed entry | handle `f1f6dc8e6652`, entity 935077; `seon.step/problems` on its plan: one `:unresolved` `seon.agent/id` |
| config after apply | model `"deepseek-flash"`, no-provider absent, 40, relaunches 0, max-running 4 (`converged? false`, C3) |

**Setup act, filed plan quality.** In the first map, "The same error for `seon.agent/id` carried" became "The same
error for the misspelled program name seon.agent/idd carried". `seon.step/problems` then answered `[]`. The heading
gap (D7) was left alone, as the brief asked.

## Run

- 11:35:50: `(seon.task/chain! context (seon.task/from-filed database fixed))` answered, in 153 ms,
  `{:seon.chain/first "f1f6dc8e6652" :seon.chain/branch :chain-f1f6dc8e6652 :seon.task/handles ["f1f6dc8e6652"
  "34ca6ac297bf" "c12a57408f49"]}`.
  - The first task was `:running` with proofs `[seon.step.design-test/handoff]`; Prove and Implement were `:held`.
    D3's fix holds.
  - The attempt key `9107c5cedfe0` and agent `attempt/9107c5cedfe0` are the same as run 2's: attempt identities are
    derived, and run 2's branch was retired.
- 11:35:51: the attempt-start prove pass recorded run `035c31b9e4cb`. 11:35:52: first model call, with no wake.
  D4's and S3a-2's fixes hold: run 2 livelocked here.
- 11:35:52-11:36:25: 12 calls of reading (`my.program/source` of the named owners, `seon.step.design/handoff`,
  `seon.step/*`, `seon.error-test/…`, schema keys). Then two refused `update!` calls (D6).
- 11:36:25-11:41:17: call 13 truncated, then a 4 min 50 s stall (D5).
- 11:39:30: hand act attempts 1a/1b, root's `my.message/send` on main (`:no-graph`) and on the branch (accepted, but
  an inside wake: still deferred).
- 11:41:17: hand act 1, an outside inbound message on the chain branch ("Root: your last model call failed …;
  continue your task."). By 11:41:47 the agent had 21 calls.
- 11:42:47: first successful plan update (call ~37). After that the per-eval test steps ran: 12 recorded runs of the
  Design proof between 11:42:47 and 11:44:09, each red.
  - The agent registered `:seon.error/alias-target` and `:seon.error/candidate-names`, defined a
    `seon.error/alias-target` stub, and removed its own unresolved citations (`str/subs`, `clojure.string/subs`, a
    name that does not exist).
- 11:44:10: `:capped` at 52 calls. The last open refusal was `:problem-changed` (D7). Nothing followed the end (D8).
- 11:45:44: config restored. 11:46-11:48: attempt closed `:closed` with reason through `seon.task/close!` on the
  branch connection, chain fact retracted, `:chain-f1f6dc8e6652` retired (`my.agent/retire-branch!`). The agent's
  threads were gone by 11:48.

**Messages to root:** sent after Design ended. Prove never started.

## Grades

Model calls and tokens are read from `:seon.turn/attempts` usage on the branch before its retirement (the plan's
grading form works now that calls exist).

| Task | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End | Done-refusals seen |
|---|---|---|---|---|---|---|---|
| Design `f1f6dc8e6652` | 52 (1 failed: truncated tool call) | 2,286,883 (1,935,744) | 18,242 | 8 min 19 s (11:35:51 to 11:44:10), of which 4 min 50 s parked (D5) | 125 evaluations, 12 errors | `:capped`, then `:closed` by root | `:seon.source/test-evidence-error` and the proof red each turn after 11:42:47. Handoff checks seen: `[:sections :producer-not-cited :data-not-modeled]` (start), then `:references`, then `:problem-changed` (last) |
| Prove `34ca6ac297bf` | not started | | | | | `:held` | |
| Implement `c12a57408f49` | not started | | | | | `:held` | |
| **Total** | **52** | **2.29M (1.94M cached)** | **18k** | | | | |

About 44k prompt tokens per call, 85% cached. The turn thread allocated about 45 GB and used 27 s CPU over the
attempt, roughly 0.9 GB per model turn.

**Compared with:**

| Measure | Rough run 1 | Design prototype 1 | S5 run 1 | S5 run 2 | S5 run 3 |
|---|---|---|---|---|---|
| Model calls | 38 | 12 | 0 | 0 | 52 (Design only) |
| Prompt tokens | 570k (310k cached) | 217k | 0 | 0 | 2.29M (1.94M cached) |
| Hand acts after `chain!` | 6 | 0 | 0 that moved the chain | 2, then abandonment | 1 (plus 2 failed tries), then abandonment |
| Furthest point | Implement (write-back conflict) | Design done | Design open, idle (D1/D2) | Design prove pass, livelock (D4) | Design worked to one refusal (`:problem-changed`), capped |
| Merge | Prove auto-merged, Implement refused | n/a | none | none | none |

Progress over run 2: D3 and D4 are fixed on the live path. `chain!` of a filed task gives three handles and a
`:running` first task; the start prove pass is read back; the agent's first model call comes with no wake; the block
shows the task, its chain after it and its proof's red each turn. Design's real work (What exists, Approach, schemas
registered, references resolved) matched the prototype's quality. The failures moved to the provider edge (D5), a
verb's arity (D6) and the filed-plan shape (D7).

**Hand acts after `chain!`, each with the mechanism that should have removed it:**

1. An outside inbound message on the chain branch (`seon.cluster.message/inbound-tx` through the branch connection,
   `seon.db/*conn*` bound). Mechanism: D5. The chain agent's provider refusal must end or retry under the bound, never
   wait for an outside wake. Two failed tries came before it (root's `my.message/send` on main, `:no-graph`; on the
   branch, an inside wake).
2. Abandonment: close, retract and retire. Mechanisms: D8 (§2.7 bail of a `:capped` chain end; there is still no
   `my.root/run!`, S3c-1) and D7 (S3f-1 `## Problem`).

Setup acts, not counted: the filed-plan reference fix, config apply and restore.

**S5 pass criteria:** none met. 3 agents: 1. Merge: none. `clojure.core/subs` in the reply: no. Table row: never
written.

**Merge outcome:** none. No files written back; the branch and the chain fact are gone, removed by root's
abandonment, not by `finish-chains!`. The `(str/subs "abc" 1)` check in a fresh agent was not run: nothing merged, so
the reply cannot have changed. `(seon.error/reply-value main {… :seon.program/not-found 'str/subs})` still holds no
`:seon.error/nearest` (checked 11:48:01).

## Context defects (exact excerpts): the first real look at the S3a-2b block

What works, judged as the owner would:

- The block holds the task's full plan and instructions, the next two tasks' instructions under
  `:seon.context/chain :seon.context/after`, the proof with its source, `:seon.context/step` with the done refusals,
  `:seon.context/start` `[(in-ns 'seon.error)]`, the turn budget, and must-reads resolved from the plan
  (`clojure.core/subs`'s doc and arglists, `:seon.error/nearest`'s schema, `seon.error/nearest-names`, the run-1
  note's outline).
- No merge refusal before work, which fixes D7 of the prototype. The agent's first reply read its instructions back
  correctly.

Defects:

1. **The red proof shows only the assertion's prose, never which check failed.** The handoff deftest's task row
   asserts `(is (= [] (design/handoff branch task)) "the task is satisfied")`. The agent saw "red … 'the task is
   satisfied'" and had to call the predicate itself to learn the check ("The only remaining red is
   `seon.step.design-test/handoff` saying 'the task is satisfied' … Let me read the actual refusal the checker
   produced."). That call then failed (defect 3).
   - The step's refusal kinds (`:problem-changed`, `:references` with offending names) are data the block should
     carry beside the proof. Either the task row's failure value reaches the block through the value printer, or
     the step renders the predicate's refusals.
2. **`my.task/update!` as instructed fails** (D6). The block's must-read for `my.task/update!` shows the 8-element
   contract arities; it does not show how the connection and agent id get supplied.
3. **`(require '[seon.step :as step])` answers `nil` and adds no alias.** In the next form of the same request,
   `(step/problems …)` gives "Unable to resolve symbol: step/problems". This happened twice, then the same with
   `seon.step.design :as design` → `design/handoff`.
   - Nearest helped: `:seon.error/nearest [(seon.step/problems database text) …]`.
   - The agent never wrote the fully qualified call.
   - Not diagnosed further. Root must reproduce it on a scratch branch: `(in-ns 'seon.error)`
     `(require '[seon.step :as step])` `(step/problems (seon.db/db) "x")`.
4. **`my.program/source` of the task's own symbol refuses** (6 refusals in all, 1 of them the task):
   `(my.program/source 'seon.error/an-unresolved-alias-name-offers-its-nearest-core-name)` gives "No program
   declaration, namespace or schema names that subject." Filed already as `my.program/source-answers-a-task-symbol`.
5. **The instructions name placeholders (`this-task`, `next-task`) as if they were bound.** The agent evaluated
   `(resolve 'this-task)` twice (nil). Write the task's own symbol, or make the placeholders values in the block.
6. **Backticked alias forms read as unresolved references** (run plan problem 3, `seon.ref/candidate`):
   `(seon.ref/references db {:seon.ref/text "an alias-qualified name such as `str/subs` is scored"})` answers one
   `:unresolved` `str/subs`. The same name inside a code form, `(str/subs "#the-goal" 1)`, resolves cleanly. The
   agent spent about 8 calls hunting `str/subs` and decided by itself that Problem's citation "cannot change".
7. **The block's must-reads include a 5,943-token run note** (`live-chain-rough-1.md`'s lead and outline) because
   the filed plan cites it. That contributes to the 44k-token prompts. Weigh must-read size against the token budget.
8. **Nearest-less "Unable to resolve" (the defect under repair): 1 of 6.**
   `(seon.error/an-unresolved-alias-name-offers-its-nearest-core-name)`, the task symbol called as a function, had
   no `:seon.error/nearest`. The other 5 (`seon.db/connect`, `step/problems` ×2, `seon.task/by-sym`,
   `design/handoff`) carried nearest lists, because their namespaces are program namespaces.
9. **Each AI attempt stores its full settings as a ~5 KB `:seon.ai.attempt/settings-edn` string**, 52 copies here.
   "A model call stores its basis, not its prompt": the settings entity ref is already there.
10. **Root's own path:**
    - My one SCI eval as root on default (a schema read, `(my.program/source :my.message/send-request)`, a slip
      against the brief) still carried run 1's test-step noise:
      `:seon.test/selection-refusal :seon.test/identity-unresolved` on
      `seon.cluster.merge-test/a-refined-task-is-written-to-its-task-file-and-survives-re-ingestion`, 1.28 GB in the
      test step.
    - `seon.test.runner/current-results` refuses a set of test symbols ("expected a sequence, got a set").
    - A `seon.db/q` naming an uninstalled attribute repeats the full refusal map per call site.
    - A ClassCastException in a JVM-mode eval reply carries no frame or form position.
11. **Read after the `:capped` end, the proof shows `:pending`.** `seon.context/current-state` for the agent then
    gave `:seon.context/proof-status :pending` and `:seon.source/test-evidence-error`. `current-results` answered `[]`
    at the head: the keyed reach digest `0c0c0f40…` is not the last recorded `bd3bcee9…`.
    - During the turns, the agent's replies quote the proof as red, so the in-turn read matched.
    - Unknown whether the end transaction changes the task identity the digest keys on. Root must reproduce this
      before filing it as a D4 remainder.

## Errors by signature

| Signature / type | Where | Count |
|---|---|---|
| `:seon.ai/unparseable-body-error` (`seon.ai/tool-call-reply`, `choices.message.tool_calls`) | Design call 13, on the branch only | 1 |
| `:seon.instrument/contract-error` `my.task/update!` argument 0 `connection` | Design | 4 |
| `:seon.instrument/arity-error` `my.edit/text` (2 args, called from `my.task/member-updated`) | Design | 1 |
| Unable to resolve symbol (5 with nearest, 1 without) | Design | 6 |
| `my.program/source` "No program declaration …" | Design | 6 |
| `:seon.message/undeliverable` `:no-graph` (really unknown-recipient on main) | root | 2 |
| `:seon.db.write/validation-refusal` (branch write without `*conn*` bound) | root | 1 |
| open faults on main | before and after the run | 2 (both from before) |

The provider error was recorded and "notified" on the chain branch, and main never showed it. A chain agent's errors
do not reach root (CLAUDE.md "Deliver every error to whoever can act on it").

## Residue on default

- None from the run: chain fact retracted, `:chain-f1f6dc8e6652` retired, attempt `9107c5cedfe0` closed with its
  reason, agent threads gone.
- JVM `user` namespace: `s5r3-conn`, `s5r3-filed`, `s5r3-fixed`, `s5r3-chain`, `s5r3-watch`, `s5r3-usage`,
  `s5r3-evals`, `s5r3-threads`, `s5r3-final`, `s5r3-launch-at`, `s5r3-hand-*` (host helpers, not program).
- Root's SCI context: one read-only `my.program/source` eval as root on main (a slip), which recorded one test-step
  run on main.
- Config: no-provider `true`, `max-episode-runs` 100, `relaunches` 2, model `"deepseek-flash"`, verified with
  `seon.config/effective` at 11:48:01.

## To file (this lane commits docs only)

- D5: `seon.turn/a-chain-agents-provider-refusal-ends-or-retries-without-an-outside-wake` (owner
  `seon.turn/opening-deferred?`).
- D6: `my.task/the-instructed-update-form-changes-the-plan` (owner `seon.call-preparation` arity planning or
  `my.task/update!`'s contract; also the template texts).
- D7: `seon.task/from-filed-puts-the-entrys-plan-under-problem` (owner `seon.task/from-filed`, S3f-1).
- D8: `seon.cluster.agent/a-capped-chain-end-bails-to-root` (owner `run-pass!` lifecycle, §2.7, S3c).
- The block shows the stage predicate's refusals beside a red task row (owner `seon.context/task-sections` or the
  handoff deftest's row).
- `require :as` inside an agent's request adds no alias (reproduce first).
- Root cannot message a chain-branch agent; `:no-graph` names the wrong cause (owner `seon.cluster.message/send!`).
- A chain agent's recorded errors never reach main.
- `:seon.ai.attempt/settings-edn` copied per call.
