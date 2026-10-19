---
title: "Context and turn loop: how an assigned agent's proofs run, and the Run 25 stall"
status: research (read-only), 2026-10-13, lane haiku-context-turn; nothing edited, no eval calls
---

# Context and turn loop: how proofs run for an assigned agent

Owner's model (quoted by root): "The tests run at the beginning of an agent starting their work and if they are all green we return back to the calling agent. At the end of their turn we execute them all and give the updates to the agent on the next turn."

Short answer: the code runs proofs at the START of an attempt (`seon.turn/prove-turn`, a `:prove` pass with no model call), and AFTER every pass that evaluated a form (`seon.turn/test-step`, inside the same pass, before the reply). It does NOT run proofs at `seon.task/assign!` (its docstring says "Nothing runs here"). The "next turn" half of the model is partly true: the results reach the agent through `seon.context/step-verdict` in its next context, and the reply carries them as `:seon.turn.loop/tests`.

## (A) READ THIS, in order

1. `seon.turn/next-agent-work`, turn.clj ~2245. The one derivation of the next situation: `:prove`, `:open`, `:call`, `:resume`, `:close`, or nil. Read first.
2. `seon.turn/attempt-running?` and `seon.turn/proved-unanswered?`, turn.clj ~2223-2243. Attempt state and the "red proving is work" rule.
3. `seon.turn/step-proved?`, turn.clj ~5265. The "proofs done" rule the `:prove` arm depends on. Reads `seon.turn/step-tests`, then `seon.context/current-results`.
4. `seon.turn/step-tests`, `seon.turn/task-obligations`, `seon.turn/task-proofs`, `seon.turn/held-task`, turn.clj ~5186-5226. Four readers of one task.
5. `seon.context/current-results`, context.clj 286. Digest-matched results. Calls `seon.test.runner/reach-digests` with NO task argument.
6. `seon.test.runner/reach-digests` (3-arity and 4-arity), `seon.test.runner/task-inputs`, `seon.test.runner/single-tags`, runner.clj ~1323-1407. Where a test's digest is keyed by its task input.
7. `seon.turn/run-proofs`, turn.clj ~5240. Runs proofs via `@test-run` with `:seon.test/task (held-task ...)`. Also the runner writer path at runner.clj ~2334-2339 (digest keyed with `task`).
8. `seon.turn/prove-turn`, turn.clj ~5281. The `:prove` pass body: `run-proofs`, then `(report :closed 0)` or `(report :error 0 ran)`.
9. `seon.turn/test-step`, turn.clj ~5452. After-eval test step: proofs, then reaching tests (`run-reaching`), then `step-reds`.
10. `seon.turn/step-due?`, turn.clj ~5185. Decides whether `test-step` runs after a pass.
11. `seon.turn/step`, turn.clj ~4870-5800. The turn proc transform: dispatch on situation (line ~4897 `:prove (prove-turn request)`), `test-step`, `end-task!`, the self-rewake offer (~5790).
12. `seon.turn/task-end`, turn.clj 2144; `seon.turn/end-task!`, turn.clj 2189; `seon.turn/end-task-call`, turn.clj 2164. When a task ends and how it is recorded.
13. `seon.context/step-verdict`, `seon.context/step-observation`, `seon.context/step-status`, `seon.context/task-done?`, context.clj ~308-373. The `:done` judgement the turn loop reads.
14. `seon.task/assign!`, task.clj 881. Writes the attempt row; runs no test. `seon.task/assignment-read`, task.clj 770: revision, agent id, proofs; refuses `:no-proofs`.
15. `seon.cluster.agent/run-pass!`, agent.clj 2304, and `seon.cluster.agent/agents-to-start`, agent.clj ~2288. db<->flow starts an agent when `seon.turn/more-agent-work?` says it has work.
16. `seon.step-test/define-handoff`, test/seon/step_test.clj 81, and the `judged` helper, step_test.clj 64. The Run 25 proof. Its tag is `seon.step/define`; it reads `(seon.test/task)`.
17. Commits: 0c684db75 (F53), b74344579 (step-proved?), cde025f68 (held opens no turn; attempt-running? reads main head), d8b470c57 (F60/F61 designed red and unmet clauses), 7ce008211 (assign! ran proofs, later removed).
18. Run reports: docs/research/agent-platform/2026-10-12/run-25.md (F69), run-18.md (F53), run-21.md (F60/F61), run-22.md, run-23.md (F66), run-24.md.

## (B) Call graph (indented; "→" = calls; each function's reads and decisions in brackets)

- `seon.cluster.agent/run-pass!` [db<->flow pass] → `seon.cluster.agent/agents-to-start` → `seon.turn/more-agent-work?` → `seon.turn/next-agent-work` (on the agent's branch db, a pure derivation). Starts the agent's graph when it has work.
- Turn proc transform `seon.turn/step` [per Flow wake; agent's own branch db, pinned]
  - `seon.turn/next-agent-work` [derives situation]:
    - open turn present (`agent-run`): `:call` / `:resume` via `fold-or-close` / nil.
    - no open turn; `task/agent-task` (held):
      - `admission-closed?` → nil (task ended; no wake reopens).
      - held is a map → it (the older path).
      - `attempt-running?` [reads `seon.task/execution` on main head] AND NOT `step-proved?` → `:prove`.
        - `step-proved?` → `step-tests` → `task-obligations` → `held-task` → `task/agent-task`, `task/assigned-obligations`; then `seon.context/current-results` → `seon.test.runner/reach-digests` (no task arg) → `task-inputs` → `single-tags`.
      - deferred error → it.
      - `attempt-running?` (else :held) → only if wakes or `proved-unanswered?` or `continuing-reply?` (and not `context-task-done?`) → `:open`.
  - situation `:prove` → `prove-turn` → `run-proofs` [reads `step-tests`; `@test-run` with `:seon.test/task (held-task)`; a run or nil] → `(report :closed 0)` (or `:error`). No model call, no form.
  - situation `:open`/`:call`/`:resume` → `open-turn`/`call-turn`/`resume-turn` → model calls, forms evaluated.
  - after a pass with forms: `step-due?` → `seon.turn/test-step` → proofs (`run-proofs`), then `run-reaching` (`seon.test/request-changes`), then `step-reds` → `seon.context/step-verdict`.
  - `:closed` outcome and not submission → `end-task!` → `task-end` → `seon.context/step-verdict` (`:done`) / `sent-back-to` / `turns-left` / `stalled?`; then `end-task-call` writes the attempt end.
  - idle pass (`next` nil): `continue-queue` for the queued tests left by a bounded step.
  - self-rewake: `(async/offer! (:seon.cluster.wake/channel cluster) :seon.agent/wake)` when NOT parked, for every non-nil situation; the next pass re-derives.
- `seon.task/assign!` [root's world] → `assignment-read` (revision, proofs; refuses `:no-proofs`) → `attempt-tx` → `next-attempt`; writes the attempt row on main and on the agent's branch (`task-placed!`, `attempt-rows-tx`). Runs no test.

## (C) Owner's model vs actual code

| Owner's model | Actual code | Divergence |
|---|---|---|
| Tests run at the beginning of an agent's work | `seon.turn/prove-turn` runs `run-proofs` on the agent's first `:prove` pass, before any model call | Matches, but not at assignment: `seon.task/assign!` docstring says "Nothing runs here." Commit 7ce008211 ran proofs at assign and was superseded. |
| If all green, return to the calling agent | `:prove` arm of `seon.turn/next-agent-work` is skipped when `step-proved?` is true; the pass then derives `:open` (model turn) if the step is not `:done` | Green proofs do not end the attempt. They only skip the prove pass. A Prove or Implement step still needs its product/merge verdict (`seon.context/step-status` `:done`). "Return to caller" is `task-done?` / `task-end`. |
| Green at beginning, no model call | `prove-turn` returns `(report :closed 0)`; the self-rewake fires; next derivation decides | Same. A red proof opens the model turn via `proved-unanswered?`. |
| At end of turn, execute all tests | `test-step` runs after the pass that evaluated a form (`step-due?`), in the same pass, before the reply | Divergence: it runs before the reply, not on the next turn. Only the RESULTS appear next turn (`:seon.turn.loop/tests` in the reply; `seon.context/step-verdict` in the next context). Bounded by `:seon.config.test/step-time-limit-ms`; the rest is queued (`continue-queue`). |
| Updates given to agent on next turn | `seon.context/step-section` / `seon.context/step-verdict` read from the branch at the next context | Matches. |
| Results reused when unchanged | Runner reuses by reach digest (`seon.test.runner/reach-digests`), which includes the task input for task-reading tests | The digest the writer uses (with `task`) and the digest `current-results` checks (no task) are DIFFERENT for a template test. See (D). |
| A step is proved when its proofs are green | `seon.turn/step-proved?`: a recorded result at current reach, red or green | Divergence: "proved" means "has a current result", not "green". Red-but-current counts as proved. |

## (D) Run 25 stall: hypothesis (not verified by a test or a probe)

Claim, from reading the code: the Define step's proof `seon.step-test/define-handoff` is a TEMPLATE test tagged `:seon/tasks #{seon.step/define}` (test/seon/step_test.clj 81). It is not cloned per task. Its `judged` helper reads `(seon.test/task)`, which is the request's `:seon.test/task` when given, else the one tag (`seon.step/define`).

Mismatch:
- Writer / reuse side: `seon.turn/run-proofs` passes `:seon.test/task (held-task ...)`, i.e. the clone task `seon.ref/a-pathless-reference-section-is-a-missing-file-error`. The runner records the digest with that task (`seon.test.runner/reach-digests` 4-arity, runner.clj ~2338, and the reuse path ~1996 with `task`). The digest is `id/id [closure-digest task-input]`, and `task-input` (runner.clj ~1331) includes the task's effective declaration and obligations, so it differs per task.
- Reader side: `seon.context/current-results` (context.clj 286) calls `seon.test.runner/reach-digests database names` with NO task. `task-inputs` then falls to `single-tags`, which returns `seon.step/define` (the template's one tag). Its digest is keyed by the template task's input, not the clone's.

Consequence (hypothesis): `seon.turn/step-proved?` compares `(= tests (set (map :seon.test/sym results)))`. `current-results` filters `latest` by `(= (get digests sym) (:seon.test.member/reach-digest result))`. That never matches for the clone, so `step-proved?` is false forever and `next-agent-work` returns `:prove`. `run-proofs` then runs `define-handoff` with the clone's task. The runner finds the digest reusable under the clone's task (reuse path, no executed member), writes no new run, and the turn proc's self-rewake (`seon.turn/step`, offer after `:closed`, not parked) re-derives `:prove` at once. No transaction on the branch, no model call, one core spinning. This matches Run 25's evidence: branch last transaction is the attempt row, no turns, no test run; `seon.problems/open-faults` empty (no error recorded).

Why it looks specific to Define: Define's obligation is one proof (`seon.step-test/define-handoff`), a task-reading template; `:prove` ends via `step-tests`' product branch, and `:open` steps are not affected because `proved-unanswered?` is never reached before the `:prove` arm.

Caveats:
- Not reproduced. The next check, if root wants it: on the branch of a clone task, call `seon.test.runner/reach-digests` with and without `:seon.test/task` for `seon.step-test/define-handoff` and compare. If they differ, the hypothesis holds. Expected: they differ, because `single-tags` gives the template's task.
- Run 18's F53 was a different cause (a product test identity missing from the run record). This is a second live-lock with the same spin signature.
- The owner's suspicion ("a template test tagged to seon.step/define not the clone") is the same shape: the fix belongs at the producer of the digest (one task arity used by both readers), not a fence.

## (E) History: loops and stalls and their fixes

- Run 18 (F53, Prove spin, 23 min): `seon.turn/first-proof-run-t` read only runs listing identities; a post-eval run had none. Fix 0c684db75: "a Prove attempt's proof run is any run after its attempt whose members include a step test." Then b74344579 replaced the run-based test entirely: `seon.turn/step-proved?` = a current result per test (reused or new), "owner rule: never rerun unchanged tests." That replacement introduced the digest-key split in (D).
- Run 21 (F60/F61, 180 calls): error-red product tests never counted as designed red, so `seon.context/step-status` could never be `:done` (3 capped attempts). Fix d8b470c57: designed red widened to errors; `seon.context/unmet` clauses (per commit message; not re-read in the current file).
- Run 22: merge carried scratch `seon.edit-test/rv` (F64). Fixed later by scope rules (run-23 notes "scratch helpers" help text).
- Run 23 (F66): relaunch after a refused merge forks its predecessor's end commit; `:satisfied` wrongly. Fix path: ea800d326 (F45/F41): fixable refusals relaunch with the reason shown.
- Run 24: Prove merged; Implement write-back refusal (F67, `68fd72410` auto-require).
- Run 25 (F69, Define spin, 11 min): see (D). Stopped by `:held` only. Fix not yet made.
- Other commits in this area: cde025f68 (held opens no model turn; `seon.turn/attempt-running?` reads main head; the ruling "execution is root's decision"); 57388f28b (a red first proof run on a :running attempt is its work: `proved-unanswered?`); a730dfa35 (fresh attempt proves first: the `:prove` arm); 346645d56 (judge at fork point, `:satisfied` opens no model turn); ea4e175e1 (the call cap is the one bound on model calls: removed consecutive-prose stall); 56d2efa61 (a loop that stops calling an agent ends its task `:stalled`).

Commit-message search for "live-lock", "prove-turn", "test-step", "proved": the closest matches are 0c684db75 (F53), b74344579 (step-proved?), 57388f28b, a730dfa35, 56d2efa61; no commit names "live-lock" in its subject.

## (F) Open questions

1. Is `current-results` meant to key the digest with the held task? If yes, it must take the task (the producer), and `single-tags` is wrong for a clone. Owner decision: one digest arity for both readers.
2. Should `step-proved?` mean "current result exists" or "current result is green"? Today red-but-current counts as proved, and the Prove/Implement judgement is elsewhere (`step-status`). Is that duplicate?
3. Should a no-write `:prove` pass self-rewake at all? The rewake (`seon.turn/step`, after `:closed`, not parked) lets a no-progress derivation spin. The fix is to make the derivation terminal, not to add a guard.
4. Does `assign!` run proofs or not? Its docstring says no; the owner's model says yes at beginning. Pick one; the first pass already does it.
5. The 2 test-step reads (`step-due?` calls `test-stale`; `test-step` calls `task-proofs` again) are duplicated. Is the second read needed?
6. The bounded test step and its queue (`merge-queue`, `summary-queue`, `queue-of`, `continue-queue`): is the time bound needed for the owner's model? Measure before keeping.

## (G) Overengineered paths

Items marked (inferred) are from reading signatures and call sites, not a full read of the body. Line estimates are rough.

1. `seon.context/current-results` vs `seon.turn/step-proved?`: two readers of the same digest, one passes the task and one does not. Simpler: one digest function taking the task, used by both (the runner already has the 4-arity). Removes the split behind (D). ~10 lines. Mismatch is the bug, not extra code.
2. `seon.turn/step-tests`, `seon.turn/task-obligations`, `seon.turn/task-proofs`, `seon.turn/held-task`: four readers of one task's obligations, each re-reading and each with its own error arm. Simpler: one reader returning the step's tests, with `step-tests` derived from `seon.task/assigned-obligations` directly (inferred). ~25 lines.
3. `seon.turn/next-agent-work` `:open` arm: `attempt-running?` is evaluated 3 times in the branch (`:prove` arm, the `:held` arm, `proved-unanswered?`). A single read of the held task's execution at the top would do. Also the `deferred` error-map branch and the held-map branch are older paths (inferred). ~20 lines.
4. `seon.turn/step`, self-rewake offer (after `:closed`, when not parked): unconditional rewake for every non-nil situation is the spin mechanism. Simpler: rewake only when the pass committed something, or let db<->flow's `more-agent-work?` decide. Root-cause side, not a fence. ~5 lines, plus an owner decision.
5. `seon.turn/test-step`: an inner try around `step-reds` and an outer try around the whole body, both building diagnostics. CLAUDE.md says only the entrant boundary catches. Simpler: no inner catch; the boundary records it. ~15 lines.
6. `seon.turn/test-step` / `seon.turn/step-due?`: the proof set is read twice (`task-proofs` in `step-due?` via `test-stale`, and again in `test-step`). Simpler: one read, passed down (inferred). ~8 lines.
7. Queue machinery: `seon.turn/queue-of`, `seon.turn/merge-queue`, `seon.turn/summary-queue`, `seon.turn/continue-queue`, and the `:seon.turn.loop/queue` state, plus the `pending-syms` helper. A second bounded-run path the owner's model does not mention. Simpler: run the step's tests to completion with the dial as a hard bound that reports `pending`, no carryover (inferred whether this loses needed behavior; measure). ~60 lines.
8. `seon.turn/platform-proof` and `seon.turn/platform` special case: host-bound changes are a special case inside the test step, with its own pending path. Simpler: the same reach selection with a host-bound filter (inferred). ~25 lines.
9. `seon.context/step-status`: a one-expression wrapper that calls `seon.cluster.source/results-verdict` and renames `:done`/`:working`. Simpler: call the verdict directly, or keep only the `:done` predicate (`task-done?`). ~6 lines.
10. `seon.context/task-done?` and `seon.turn/task-end`'s `:done` arm: both read `step-verdict`. Fine on its own; the duplication is that `task-end` also re-reads `context-step-verdict` directly (line ~2151 and `task-done?` elsewhere). Simpler: one read per pass (inferred). ~5 lines.
11. `seon.turn/task-end` vs `seon.turn/end-task-call` vs `seon.turn/end-task!`: three functions for one end; `end-task!` has a `fault!` closure and a `write!` closure that forward to `db/with-branch` + `db/transact!` (inferred that `write!` only forwards). Simpler: one transaction function. ~20 lines.
12. `seon.turn/turn-*` / `seon.turn/closed-on?` / `seon.turn/admission-closed?`: two predicates for the same admission state (`closed-on?` used in `end-task-call`, `admission-closed?` used in `next-agent-work` and `task-end`) (inferred duplicates; closed-on? body not read). Simpler: one predicate. ~8 lines.
13. Delay/`requiring-resolve` indirection block at `seon.turn` (~lines 60-75 and more, e.g. `context-step-verdict`, `context-task-done?`, `context-current-results`, `context-end-summary-tx`, `context-model-context`, `test-run`, `test-stale`, `test-request-changes`, `cluster-agent-acquire-context!`). About 12 defonce delays forwarding to other namespaces. Likely a cycle-avoidance device (inferred); if the cycle is gone, plain requires. ~40 lines.
14. `seon.task/assign!` + `seon.task/assignment-read` + `seon.task/attempt-tx` + `seon.task/attempt-rows-tx` + `seon.task/task-placed!`: root's world write and branch copy in one function, with a `:no-world` special case. Not read in full (inferred). Potentially reducible by writing once on main and letting the branch fork carry it (see commit cde025f68's note that a fork carries main's execution datoms anyway).
15. `seon.task/assignment` vs `seon.task/assigned-work` vs `seon.task/agent-task` vs `seon.turn/held-task`: four ways to answer "what task does this agent hold". Simpler: one (`seon.task/agent-task`) plus one pull. ~15 lines.

Estimated removable across G: roughly 250-300 lines in turn.clj and context.clj, most of it in items 2, 7, 13, 11. Item 7 is the only one whose removal changes behavior; measure first.

## Summary for root (15 lines)

1. Proofs run in three places: `seon.turn/prove-turn` (agent's first `:prove` pass, no model call), `seon.turn/test-step` (after any pass that evaluated a form, same pass, before the reply), and `seon.turn/continue-queue` (idle pass, bounded-step carryover). Not at `seon.task/assign!`, whose docstring says so.
2. `seon.turn/next-agent-work` decides: `:prove` when `attempt-running?` and not `step-proved?`; `:open` when held and wakes or red-proof or continuing reply, unless `context-task-done?`; nil when admission closed.
3. `seon.turn/step-proved?` = every step test has a result at its current reach (`seon.context/current-results`), red or green. Green is not required.
4. Owner's model diverges: green proofs do not return to the caller; "done" is `seon.context/step-verdict` `:done`. Tests run before the reply, not on the next turn; only results reach the next turn.
5. Run 25 hypothesis (not verified): `seon.context/current-results` calls `seon.test.runner/reach-digests` with no task, so `single-tags` gives the template tag `seon.step/define`; `seon.turn/run-proofs` records and reuses under the clone's task. Digests differ, `step-proved?` never true, no run is written, and the unconditional self-rewake in `seon.turn/step` spins.
6. Check to confirm: compare `seon.test.runner/reach-digests` for `seon.step-test/define-handoff` with and without `:seon.test/task` on the clone's branch. Expected: different.
7. Run 18 (F53) was a different spin: a run without identities; fixed by 0c684db75, then replaced by b74344579 (`step-proved?`), which introduced the split in 5.
8. Run 21 (F60/F61): error-red products never `:done`; fixed by d8b470c57. Run 23 (F66): relaunch forks predecessor's end commit; ea800d326 relaunch path.
9. Distinct "done/proved" notions: `step-proved?`, `proved-unanswered?`, `step-status` `:done`, `step-verdict`, `task-done?`, `task-end` `:done`, `proof-status`, `step-due?`/`test-stale`, `continuing-reply?`, admission-closed.
10. Overengineering (G): the digest split (1), four task readers (2), three `attempt-running?` reads in `next-agent-work` (3), inner catches in `test-step` (5), the queue machinery (7), `platform-proof` special case (8), delay indirections (13).
11. The fix for the spin is at the producer (one digest arity) plus making a no-progress `:prove` terminal; a guard or rewake cap would be a fence (CLAUDE.md).
12. Open questions: digest arity owner; whether `step-proved?` should mean green; whether the bounded queue is needed; whether assign! or the first pass runs proofs.
13. History anchors: 0c684db75, b74344579, cde025f68, ea800d326, d8b470c57, 7ce008211.
14. Limits: no tests run, no probes, no eval calls; some G items are inferred from signatures; closed-on? and unmet not re-read.
15. Run reports read: run-18, run-21 through run-25 (run-25 F69 is the live stall).
