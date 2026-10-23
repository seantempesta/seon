# Triage of `resources/seon/tasks/seon.fences.edn` (2026-10-22)

Read-only triage of the 19 tasks the FENCES audit (`../notes/audit-2026-10-21/FENCES.md`, 66 findings at 571ae49) filed. Checked against next's main at `6114183` (storage landed, AGENTS rewritten). Proof state is from `bin/test` runs made for this triage: niced, on an in-memory test store, with `SEON_RUNS`/`SEON_STORE` pointed at scratch and the entrance port set to 0. `next/runs/store` and the standing root on 7888 were not touched, and `seon.root-test` was not run because it opens `target/runs/test-store`.

## The answer to "fences are always bad. what are we doing?"

These tasks are meant to **remove** fences, and most of them do. The real problems are elsewhere:
- **Most of the file is already done.** 9 of the 19 have landed on main, and a 10th is finished on an unmerged branch (`jolt/unitflake`). Their entries are still in the file and still loaded as open work, so the file overstates what is left.
- **One task's fix is itself a fence.** `a-stall-is-past-the-requests-own-bound` needs `seon.root/one-clock` (a `:clock` proc that emits ticks) and `seon.turn/a-form-has-a-time-bound` (a per-form time bound). Both are clocks. The owner's ruling "No clocks or timers; workload decides" (DECISIONS, 2026-10-21) names STALL-MS, FLUSH-MS and per-form `:timeout-ms` as things it *replaces*. Rewrite this task; don't run it.
- **Two more fall short of the clock rule.** Part (1) of `health-is-answered-and-failover-recorded` keeps the 1000 ms `ping-proc :timeout-ms` that turns silence into `:unknown`. `checks-await-facts-and-assert-exact-numbers` is right in aim but too big to run as one task.
- **The file name breaks the convention** that tasks live in `resources/seon/tasks/<owning-ns>.edn`. "fences" is the name of an audit, not a namespace. Every open task here has a real owner (below). Moving them changes their ids (`seon.fences/x` becomes `seon.reconcile/x`). Ten plans in other task files, two source comments and five test files cite `seon.fences/...` by name, and attempts on the standing store are recorded under the old ids. So move them in one commit that rewrites those citations. Then delete `seon.fences.edn`.

## Summary table

| # | Task | Status | Owning ns | Value | Size |
|---|---|---|---|---|---|
| 1 | write-back-answers-what-happened | **done** (23d50ac) | seon.fx | high (done) | – |
| 2 | merge-trains-off-as-decided | **done** (23d50ac) | seon.reconcile | high (done) | – |
| 3 | a-refused-spawn-is-recorded | **ready** (proofs red as designed) | seon.reconcile | high | S |
| 4 | a-transient-model-failure-parks | **done** (573f12f) | seon.fx | high (done) | – |
| 5 | one-test-bound-derived-and-named | **done** (0d0e3fd + d619a70) | seon.agent | high (done) | – |
| 6 | budgets-are-data-and-spend-is-measured | **ready** (proofs red as designed) | seon.reconcile (+ seon.model) | high | S–M |
| 7 | records-keep-the-exact-value | **partly done**; rewrite to the 3 sites left, then ready | seon.proofs (+ seon.root) | medium | S |
| 8 | root-boots-without-a-synthetic-program | **done** (5be0d5e) | seon.root | medium (done) | – |
| 9 | the-compute-executor-is-declared | **moot/done** (486f3f3: no `:compute` proc remains) | seon.root | – | – |
| 10 | health-is-answered-and-failover-recorded | **half-baked**: (2) is ready, (1) keeps a clock | seon.root | medium | S + design |
| 11 | leases-and-store-size-are-declared-bounds | **done** (13038f6) | seon.db | medium (done) | – |
| 12 | a-run-ends-on-a-defect-too | **done** (23d50ac) | seon.e2e | medium (done) | – |
| 13 | checks-await-facts-and-assert-exact-numbers | **half-baked** (too big; needs unlanded work and a new OS mechanism) | seon.check | low–medium | L |
| 14 | cost-tolerances-are-derived | **done on unmerged `jolt/unitflake`** (c366a41, 348 behind main) | seon.cost | low–medium | S (land) |
| 15 | the-dragon-gate-sees-fences | **ready after a plan update** (its prerequisite has largely landed) | seon.check (+ seon.program) | medium | M |
| 16 | no-clip-in-renders | **done** (bc33b3a; duplicates seon.render/no-clipping-functions) | seon.render | – | – |
| 17 | a-stall-is-past-the-requests-own-bound | **half-baked; its fix is a fence** | seon.agent-proc / seon.root | medium | rewrite |
| 18 | event-loss-is-counted | **ready** | seon.root | low–medium | S–M |
| 19 | fork-refusal-names-the-threads | **half-baked** (one measurement first) | seon.agent (+ seon.lib) | low | S |

Counts: done 9 (1, 2, 4, 5, 8, 9, 11, 12, 16), done on a branch 1 (14), ready 4 (3, 6, 15, 18), partly done and ready once rewritten 1 (7), half-baked 4 (10, 13, 17, 19).

## Per task

### 1. write-back-answers-what-happened: done
- Evidence: `seon.fx/write-file` (`src/seon/fx.clj:33-50`) calls `lib/c-rename` and returns errno. On failure it answers `:seon.fx/io-error {:path :tmp :errno}` and deletes the tmp file. On success it answers the size on disk. Landed in 23d50ac.
- Proofs: `seon.fx-test/a-failed-rename-is-an-io-error-and-leaves-no-tmp` and `a-write-back-answers-the-bytes-on-disk` both exist and are **green** (run today).
- Fence? Removed one, a guard that hid errors. Its `catch` around `spit` is the named edge.
- Do: mark the task done (it belongs to seon.fx) and drop the entry.

### 2. merge-trains-off-as-decided: done
- Evidence: `resources/seon/config/default.edn:181` sets `:seon.config.merge/max-running 1`. `seon.reconcile/default-max-merging` reads it (`src/seon/reconcile.clj:22-26`). `seon.task/boot-tx` (`src/seon/task.clj:113-117`) writes the dial with the config in force. Landed in 23d50ac and 990cf51.
- Proofs: `seon.reconcile-test/boot-records-merge-max-running-one` and `two-ready-disjoint-merges-run-one-at-a-time-by-default` are **green**.
- Left over, not this task: `default-max-turns`, `default-max-running`, `default-max-merging` and `default-budget` are read once at load and chained by hand (`reconcile.clj:14-39`). That is census #5, a second resolver beside `seon.config/resolve-in`, and needs its own task. `default-budget` also goes under the ruling "A retry adds tasks", which deletes `:seon.task/budget`.
- Do: drop the entry.

### 3. a-refused-spawn-is-recorded: ready
- Cause, confirmed on main: the `:spawned` step in `seon.reconcile/advance` (`src/seon/reconcile.clj:602-606`) gets an answer without `:id` and returns `(done st)`, writing nothing. `seon.reconcile/stuck` (`reconcile.clj:395-408`) lists only tasks with a stuck fact or that are unreleased, so a task that cannot start is invisible at quiescence. Root produces the refusals at `src/seon/root.clj:263` (`too many live agents`) and in the held-branch case.
- Proofs: written and **red for the reasons designed**:
  - `a-refused-spawn-writes-a-refused-attempt`: status nil, not `:refused` (`test/seon/reconcile_test.clj:495-497`);
  - `quiescence-names-a-task-that-cannot-start`: the contract refuses `:refused` (`seon.facts.edn:171`).
- Fence? No. It replaces a silent drop with a recorded fact.
- Notes for the lane:
  - The enum lives in two places, `resources/seon/schemas/seon.facts.edn:171` and `seon.task.edn:45`. Use `my.schema/register!` as the plan says.
  - The owner's "One agent entity" ruling (2026-10-22) folds the attempt into `:seon.agent/*`. That has not started (no `:seon.agent/task` in the schemas). If it lands first, the refusal becomes `:seon.agent/status :refused`.
  - The `max-agents` refusal is a fixed slot that the workload-admission ruling (2026-10-21) will replace. Recording it is right either way.
- Value: high. A task that silently never starts is the failure E cannot tolerate, and the first DeepSeek agent worked this very task.
- Owner: seon.reconcile. Proof names follow (`seon.reconcile-test/*`).
- Write set: `reconcile.clj` (`advance`, `stuck`) and the two schema entries.

### 4. a-transient-model-failure-parks: done
- Evidence: 573f12f. In `seon.fx`, the owner's `:done` re-queues a `:seon.model/rate-limited` outcome instead of answering the asker (`src/seon/fx.clj:268`). `settle` halves the rate (`fx.clj:183-196`). `wake-later` sends the tick once the bucket refills, which is the delayed insert the clock ruling allows.
- Proof: `seon.fx-admit-test/a-rate-limited-model-call-waits-for-the-bucket-and-is-answered` exists and is green.
- Left over: the 2026-10-22 audit (§1, move 10) says "a 429 with `rate 0` retries immediately; transport errors are not retried despite the docstring" (`model.clj:36-41`). The task's plan says on purpose that transport errors are not retried. That is a docstring fix plus the `rate 0` case. File it under seon.fx if it is still true.
- Do: drop the entry.

### 5. one-test-bound-derived-and-named: done
- Evidence: the deadline is gone. `seon.agent/run-tests-here` is bounded by `:seon.config.agent/test-ticks` engine ticks, a work bound the owner's ruling allows ("bounded by the work it does ... never a wall-clock limit"). The child writes `{:seon.test/started var}` (`src/seon/agent.clj:315-345`), and `seon.agent/run-answer` (`agent.clj:358-370`) names the test a death interrupted.
- Proofs: `seon.agent-test/a-killed-test-run-names-the-test-that-was-running` and `seon.web-test/calls-become-requests` exist and are green.
- Do: drop the entry. Its plan text is a history of premise changes; nothing in it is still open.

### 6. budgets-are-data-and-spend-is-measured: ready
- Cause, confirmed: four places write a value nobody measured.
  - `seon.reconcile/judge-tx` writes `:turns (:turns end 0) :model-calls (:turns end 0) :tokens (:tokens end 0)` (`src/seon/reconcile.clj:557-558`). `:model-calls` is copied from `:turns`, a second fake value the plan does not mention.
  - `seon.model` parses a reply with no usage as `(:prompt_tokens u 0)` and `(:completion_tokens u 0)` (`src/seon/model.clj:152`).
  - `seon.fx/settle` charges `(or cost 1)` (`src/seon/fx.clj:192`).
  - `seon.task/end-members` requires all three counts as integers (`src/seon/task.clj:142-147`).
- Proofs: **red for the reasons designed**:
  - `seon.model-test/a-reply-without-usage-records-no-tokens`: both counts are present (`model_test.clj:55`);
  - `seon.reconcile-test/an-attempt-with-no-end-records-no-fake-spend`: the outcome is `:error`, not `:no-end`, and turns, model calls and tokens are present (`reconcile_test.clj:146-147`).
- Fence? No. It deletes fake defaults. The plan correctly leaves the token budget out pending an owner ruling.
- Fix the entry:
  - drop its `:needs` on `merge-trains-off-as-decided` (done);
  - add the `:model-calls` copy to the plan;
  - mark the stand-in's 4-chars-a-token usage (`model.clj:22,49-50`) as `:estimated`, which the plan asks for but does not locate.
- Value: high. E is measured as turns-to-green and cost, and those numbers are only honest if unknown stays unknown.
- Owner: seon.reconcile (the judge and the schema). The `parse-reply` half could be a seon.model task, but it is small enough to keep in one lane.
- Write set: `reconcile.clj` (`judge-tx`), `task.clj` (`end-members`), `model.clj` (the parse), `fx.clj` (`settle`), and the `:seon.attempt/outcome` enum in `seon.facts.edn`.
- Overlaps task 3 on `reconcile.clj` and `seon.facts.edn`, so run 3 and 6 in one lane, one after the other.

### 7. records-keep-the-exact-value: partly done; rewrite, then ready
- Already gone:
  - `message-chars`: `seon.agent/error-map` keeps the whole message (`src/seon/agent.clj:224-241`);
  - the model's 300-char body cut: `seon.model/body-text` keeps the whole body (`model.clj:52-59`);
  - `seon.learn`'s 80-char cut and `agent_proc`'s 200-char cuts: no `subs` is left in `learn.clj` or `agent_proc.clj`.
- Still standing:
  - `seon.proofs/max-assertions` is 8, and failures past it are dropped with no count (`src/seon/proofs.clj:45-58`);
  - `seon.root/tail-bytes` is 2048 and `stderr-tail` (`src/seon/root.clj:64-79`) stores only the tail of a dead agent's stderr in the death record (`root.clj:218`), though the file is kept;
  - `error-map` stores `ex-data` printed under the `:part` budget (`agent.clj:240`), a render cut made at storage time.
- Needs: `seon.repl/eval-record-keeps-a-failing-form-whole` (`resources/seon/tasks/seon.repl.edn:5`) is open and is a separate site. The rewritten task does not need it.
- Proofs: none of the three exists (`seon.agent-test/an-exception-message-is-recorded-whole`, `seon.proofs-test/every-failing-assertion-is-kept-or-counted`, `seon.problems-test/a-death-record-points-at-the-whole-stderr`). The first now tests something already done, so it can be written and should pass at once.
- Fence? It removes cuts. The fix (whole value or reference, and a render budget at display) is right under the one-printer law.
- Value: medium. The agent's context reads failures. The 9th assertion rarely matters, but the death record's stderr does when an agent crashes.
- Owner: seon.proofs (`failures`), with the stderr part in seon.root. Size S.

### 8. root-boots-without-a-synthetic-program: done
- Evidence: 5be0d5e, "the example program seeds only the test and check stores".
- Proofs: `seon.main-test/a-fresh-root-holds-no-program-it-was-not-given` and `warm-needs-no-seed` exist and are green.
- Left over: `seon.check` still calls `(c/start! {:datoms 2000})` at `check.clj:23,320,352,436,509,713`. That is the check store, which is allowed.
- Do: drop the entry.

### 9. the-compute-executor-is-declared: moot, fixed by another task
- Evidence: 486f3f3, "Root's threads are a declared number". The render proc moved from `:compute` to `:mixed`, and its comment cites the cached pool growing (`src/seon/render/proc.clj:481-485`). No `:workload :compute` remains in `src/`. `seon.root/declared-threads` (`root.clj:932-954`) lists every thread. The hypothesis was right; it was fixed by leaving `:compute` rather than by declaring an executor.
- Proofs: `seon.root-test/root-threads-are-what-root-declares` exists (not run here; see above). `seon.render-test/a-slow-render-step-is-recorded-not-abandoned` has nothing to test, since no compute timeout applies.
- Do: delete. If someone adds a `:compute` proc again, Flow's `compute-timeout-ms 5000` (`reference-code/jolt/stdlib/clojure/core/async/flow/impl.clj:419`) comes back. Note this in docs/flow.md (or the THREADS section) as a "Not this".

### 10. health-is-answered-and-failover-recorded: half-baked (split it)
- (2) Ready, size S:
  - The fork server sends `:standby-failed` (`src/seon/fork_server.clj:78`) and forwards it to the api (`fork_server.clj:246`).
  - The `:ev` case in `seon.root/back-op` (`root.clj:347-383`) has no branch for it and falls through to the default `st`. It is still dropped.
  - Fix: record it as a platform problem and show "no standby" in the view. This is a clear one-branch fix.
  - Proof `seon.procs-test/a-failed-standby-is-a-recorded-problem` does not exist yet.
- (1) Not ready:
  - `seon.root/agent-ping` (`root.clj:884-889`, `deref ... ms :unknown`) has no caller. Its deletion is already committed as 1bf4c78, but only on the unmerged `jolt/laws` branch.
  - `agent-health` and `health` (`root.clj:874-902`) call `flow/ping-proc ... :timeout-ms 1000` and answer `:unknown` on silence. That is a clock deciding. The plan's fix ("`:unknown` only for a proc observed dead") does not say how to observe death without the timeout.
  - AGENTS §2 says "Health is the absence of open problem facts". So the fix is to read problem facts plus Flow's own status events, not a timed ping. Callers: `check.clj:718,731,735,885`.
  - The needed task, `seon.root/a-proc-answers-every-request-it-took`, applies to (1) only.
- Proof `seon.procs-test/a-ping-of-a-gone-agent-answers-its-error` does not exist yet.
- Do: move (2) to `seon.root` as its own S task, `a-failed-standby-is-recorded`. Cherry-pick 1bf4c78, or redo its 7-line deletion. Fold the health redesign into `seon.root/a-proc-answers-every-request-it-took`, or open a seon.root task "health is the absence of problem facts".

### 11. leases-and-store-size-are-declared-bounds: done
- Evidence: 13038f6.
  - The ceiling lives in our Datahike fork (`dbt/set-eid-ceiling!`, called from `seon.db/after-fork!`, `src/seon/db.clj:285-291`).
  - Crossing it raises `:seon.db/lease-spent` (`db.clj:305-315`), which `seon.agent/transact!` reads (`agent.clj:119`).
  - `bin/check` has a lease step (`check.clj:1134-1152`).
  - The env reads moved to config (`:seon.config.store/eid-lease`, `lmdb-map-bytes`); no `SEON_LMDB_MB`, `SEON_TRIP_MB` or `SEON_AGENT_HEAP_MB` is left in `src/seon/`.
- Proofs: `seon.db-test/minting-past-the-lease-is-refused-not-shared` and `the-store-reports-its-map-use` exist and are green.
- Do: drop the entry.

### 12. a-run-ends-on-a-defect-too: done
- Evidence: 23d50ac. `seon.e2e/await-quiet` also completes on a `:platform` problem recorded on main, and every e2e verdict fails on one.
- Proof: `seon.e2e-test/await-quiet-ends-on-a-recorded-defect` exists and is green.
- Do: drop the entry.

### 13. checks-await-facts-and-assert-exact-numbers: half-baked (too big, and blocked)
- What still stands:
  - 21 `Thread/sleep` in `src/seon/check.clj` and 2 in `e2e.clj:99,125`;
  - 37 `await-status` calls (`check.clj:9`, a 2 ms poll with a deadline);
  - `until` (`check.clj:190`, a 100 ms poll for 120 s; one use passes 120000 at `check.clj:227`);
  - `bin/check:116-118` (`kill -0` with `sleep 1`);
  - the guessed 25000 chars (`check.clj:366`).
- Already done by another task: the lifecycle threads number is now exact (486f3f3).
- Why it is not ready:
  - it bundles about 60 sites across 10 check modes;
  - it needs `seon.check/branches-as-in-production` (open);
  - "orphans' deaths as kqueue EVFILT_PROC events" is a new FFI mechanism in Jolt, a dependency change that waits for root's approval;
  - its proof `seon.check-test/no-check-sleeps-or-polls` would be text matching unless it is built on the compiler's facts, which is task 15.
- Proofs: none exists.
- Value: low–medium. Flaky or slow checks cost landings time, but they are not on E's path.
- Do: split it into one task per check mode in `seon.check.edn` (smoke, robust, lifecycle, backpressure, scale, hotreload, orphan). Each replaces its sleeps with a fact or event wait (`seon.e2e/await-db` on main, a tap of `c/events` for `:ready`/`:dead`). Make the "no sleeps" assertion a rule of task 15's gate rather than its own grep. Land EVFILT_PROC only after research and root's approval.

### 14. cost-tolerances-are-derived: done on an unmerged branch
- Evidence: `jolt/unitflake` holds 5 commits.
  - c366a41 is the task: each unit row carries `:range`, `seon.cost/tolerances` derives slack and the exponent tolerance, the prompt's hardcoded 1.25x/3x/0.3 is deleted, and `profile_test` asserts `ceil(n/64)` exactly.
  - Its 3 proofs were green there.
  - The branch is 348 commits behind main (last commit 2026-10-21 13:13).
- On main the guesses still stand: `seon.cost/slack`, `steeper-by` and `ratchet` (`src/seon/cost.clj:40-59`), the prompt's copy (`context.clj:31`), and `profile_test.clj:30-41` (2x, gated on contracts).
- Proofs: none of the three exists on main.
- Conflict risk: the B0 lane "context v2" rewrites `seon.context`'s prompt. Land unitflake before that lane starts or immediately after it; don't let them run blind to each other.
- Value: low–medium. These are the tolerances of the cost judge, which agents are told is their performance goal.
- Do: a lane merges main into `jolt/unitflake` and runs `seon.cost-test`, `seon.context-test` and `seon.profile-test`; root lands it. Then mark this task done. Owner: seon.cost (its needed task, `seon.cost/a-unit-measures-the-work-not-the-cache`, is on the same branch, 2838e62/e7aad9d).

### 15. the-dragon-gate-sees-fences: ready after a plan update
- The cause stands: `bin/dragons` is an awk over `(catch` that leaves out `check.clj` and `e2e.clj` (`bin/dragons:8-11`).
- The plan says it waits for `seon.program/definitions-from-the-compiler`. That mechanism has **largely landed**: `seon.program`'s analyzer walk records, per form, `:refs`, `:banned`, `:inline-defs`, **`:timeouts`** (a 3-arg deref) and **`:catches`** (`src/seon/program.clj:146-200`). They are stored as `:form/timeouts` and `:form/catches` (`program.clj:385`, `merge.clj:190`), read back by `form-row` (`program.clj:433`), and tested (`test/seon/program_test.clj:116-117`).
- Plan update:
  1. Extend `:timeouts` in that one walk to `java.lang.Thread/sleep`, `clojure.core.async/timeout` and `flow/ping-proc`'s `:timeout-ms`, plus `.join` with a bound.
  2. The gate reads main's program facts (src imported by `seon.source`) and is red for each `:timeouts` or `:catches` site whose function has no row in `docs/DRAGONS.md`.
  3. Delete the awk.
  4. Include the check drivers, so task 13's sleeps show up here.
- Proofs: none exists yet (`seon.check-test/an-unnamed-timeout-fallback-is-red`, `the-dragon-gate-reads-resolved-references`).
- Fence? No. It is a judge of code, which AGENTS allows (proofs and checks judge; nothing gates the REPL). It is the one mechanism that stops fences from coming back.
- Value: medium. It does not move E directly, but every other row in this file returns without it.
- Owner: seon.check for the gate; the analyzer extension is in seon.program's walk. Size M. File-disjoint from 3/6, 7, 10(2), 18 and 14.

### 16. no-clip-in-renders: done
- Evidence: bc33b3a, "No clipping functions: the one printer's prose and every bounded list says what it left". `clip` and `line` are gone from `render/ui.clj`. The lists say how many more (`render/ai.clj:142-144`, `render/proc.clj:260-266`). This duplicates `seon.render/no-clipping-functions` (`resources/seon/tasks/seon.render.edn`), which says it answers this task, proofs included.
- Proofs: `seon.render-test/no-render-calls-clip` and `a-capped-list-says-how-many-more` exist and are green.
- Left over, an "only documented config" issue rather than a clip:
  - `EVENT-CAP`, `SERIES-CAP` and `HISTORY-CAP` are literals (`render/proc.clj:32-35`);
  - the default budget of 700 is a literal (`render/ai.clj:151`);
  - so is the `take-last 3` (`render/ai.clj:50,125`).
  Add them to `seon.config/one-documented-config`'s plan.
- Do: delete.

### 17. a-stall-is-past-the-requests-own-bound: half-baked, and its fix is a fence
- What stands:
  - `seon.agent-proc/stall-ms` is 30000 (`src/seon/agent_proc.clj:42-48`; its own docstring calls it a guessed tolerance and a clock);
  - the api's step starts a go block on `(a/timeout (quot stall-ms 2))` (`root.clj:482-487`) and asks each busy agent `:stall?` (`root.clj:431`);
  - the flusher sleeps out `flush-ms` (`src/seon/writer.clj:288`; `seon.db/flush-ms` is 10, `db.clj:55-60`);
  - `seon.agent/transact!` also commits on a 10 ms window (`agent.clj:123`).
- Why it is not ready:
  - Its `:needs` are `seon.root/one-clock` ("One declared `:clock` proc emits ticks", `seon.root.edn:17`) and `seon.turn/a-form-has-a-time-bound` ("Pass the bound (a dial) in act!", `seon.turn.edn:1`). Its own fix is "a request carries its declared bound ... the stand-in observes a stall when that bound passes".
  - All of that is a clock deciding. DECISIONS "No clocks or timers; workload decides" (2026-10-21) says: "a stall is read from the graph (a request owed when the work runs out), not from elapsed ms. Runaway work (an agent's eval or test that never ends) is bounded by the work it does (... Jolt's trip) ... Replaces FLUSH-MS, STALL-MS, STOP-BOUND-MS, the 30000 ms test bound, per-form :timeout-ms".
  - So both of its needs are fences too and should be rewritten the same way.
- The flusher half duplicates `seon.writer/durability-is-continuous-group-commit` (`resources/seon/tasks/seon.writer.edn`), which already states the ruling's fix.
- Do: delete this task.
  - Rewrite `seon.turn/a-form-has-a-time-bound` as "an eval is bounded by its work". This is the test-ticks mechanism already used for tests (`:seon.config.agent/test-ticks`, `seon.agent/run-tests-here`), applied to an agent's eval.
  - Delete `seon.root/one-clock`; the only timers left are the ruling's three exceptions.
  - The stall itself needs one owner decision (below).
- Owner: seon.agent-proc / seon.root once rewritten.

### 18. event-loss-is-counted: ready
- Evidence:
  - root's channels are literals: `events-in` sliding 8192, `:ext` 1024, `:back` 4096, `:server-ev` 4096, `:judge` 64 (`src/seon/root.clj:618-624`);
  - `seon.root/events` taps a sliding 1024 and never untaps (`root.clj:723-727`);
  - render's tap is sliding 4096 (`render/proc.clj:491`);
  - a sliding buffer drops the oldest with no count.
- The pattern for declared buffers already exists: `:chan-opts {:... {:buf-or-n :bind/<config-key>}}` in `resources/seon/system.edn:16,30`.
- Proofs: none exists yet (`seon.procs-test/a-dropped-event-is-counted`, `root-buffers-come-from-the-declaration`).
- Fence? No. It records loss, and it moves literals into documented config. The counting buffer is one small `core.async` buffer implementation (the library's own `Buffer` protocol). Name it in docs/core-async.md as the one counting buffer, so nobody writes a second.
- Value: low–medium. "Insight is a feature" (the dashboard can miss events silently), but nothing on E's path depends on it.
- Owner: seon.root. Size S–M. Write set: `root.clj` (`start!`, `events`), `system.edn`, `default.edn`, `render/proc.clj:491`.

### 19. fork-refusal-names-the-threads: half-baked (one measurement first)
- Evidence:
  - `proto-qfork-bounded` spins 50 µs × `max-tries` while `$active-threads` is not 1 (`src/seon/lib.clj:164-180`);
  - `safe-fork 1000` (`lib.clj:247-251`) is called from `agent.clj:380` (the test run) and `agent.clj:517` (clone);
  - `interrupt-self-test` loops up to 100,000 evals (`agent.clj:269-277`).
- Missing: the plan rests on "an agent is single-threaded by physics" (docs/DRAGONS.md "Physics: who may run threads"). Nobody has measured whether a second Chez thread is ever *briefly* active in an agent at fork time. Candidates: Jolt's timer or io-poller started by an agent's own `a/timeout` or socket wait, or a future the agent's user code started, which "get out of the way" allows.
  - If transients happen, refusing at once turns them into false refusals of test runs, the agent's main judge.
  - Measure first: `lib/active-threads` and the thread roster at every `safe-fork` over a `bin/check scale` run.
  - If the count is always 1 except for user-made threads, the plan is right and S.
- Proofs: none exists yet.
- Value: low. No failure has been observed; this tidies a wait.
- Owner: seon.agent (the callers and the self-test), with the qfork change in seon.lib.

## Proof runs (this triage)

The commands, run from next/ with `SEON_RUNS`/`SEON_STORE` in scratch and `SEON_ENTRANCE_PORT=0`, niced:
- `bin/test seon.fx-test seon.model-test seon.reconcile-test`: 46 tests, 6 failures and 1 error. All seven are the open proofs of tasks 3 and 6, failing for the reasons designed. The proofs of 1 and 2 are green.
- `bin/test seon.fx-admit-test seon.db-test seon.main-test seon.e2e-test seon.render-test seon.web-test seon.agent-test`: 60 tests, 1 failure and 5 errors. **None of them is a fence proof.** Every proof of the done tasks in this batch is **green**:
  - 4: `a-rate-limited-model-call-waits-for-the-bucket-and-is-answered`;
  - 5: `a-killed-test-run-names-the-test-that-was-running`, `calls-become-requests`;
  - 8: `a-fresh-root-holds-no-program-it-was-not-given`, `warm-needs-no-seed`;
  - 11: `minting-past-the-lease-is-refused-not-shared`, `the-store-reports-its-map-use`;
  - 12: `await-quiet-ends-on-a-recorded-defect`;
  - 16: `no-render-calls-clip`, `a-capped-list-says-how-many-more`.

  The reds are unrelated to this file:
  - `seon.db-test/a-moved-store-opens`: "Store identity mismatch". The store id is `(hasch/uuid path)`; this is task `seon.db/a-store-opens-where-it-lies`.
  - Five `seon.agent-test` errors (`an-agent-holds-mains-tests-and-runs-them`, `an-agents-test-run-is-its-proofs-and-what-its-change-reaches`, `an-agent-finds-no-repository`, `each-eval-call-is-answered-before-the-next`, `an-agent-that-throws-ends-with-its-cause`), all "EOF while reading" from a `./seon` child. These may come from this triage's environment overrides (entrance port 0, scratch runs dir) rather than main. Re-run them in a worktree before you file anything.

## Lanes that can run at once (ready work)

All file-disjoint from each other:

| Lane | Tasks | Write set | Size |
|---|---|---|---|
| **A: honest attempts** | 3, then 6 (one lane: both touch `reconcile.clj` and `seon.facts.edn`) | `seon.reconcile` (`advance`, `stuck`, `judge-tx`), `seon.task/end-members`, `seon.model`'s parse, `seon.fx/settle`, `seon.facts.edn`/`seon.task.edn` enums | S + S–M |
| **B: land unitflake** | 14 | merge main into `jolt/unitflake`; `seon.cost`, `seon.context` prompt line, `profile_test` | S |
| **C: dragon gate** | 15 | `seon.program`'s analyzer walk (`:timeouts`), `bin/dragons` → a `bin/check` step reading program facts, `docs/DRAGONS.md` | M |
| **D: root's records** | 10(2), 7's stderr half, 18 | `seon.root` (`back-op` `:ev`, `stderr-tail`, `start!` channels, `events`), `system.edn`, `default.edn`, `render/proc.clj:491` | S + S + S–M |
| **E: proofs keep every failure** | 7's `max-assertions` half | `seon.proofs/failures`, its render in the context | S |

Conflicts to watch:
- Lane A's `reconcile.clj` overlaps the B0 lever "the agent knows its task" if that lane changes the `:spawned` step (root writes the task on the branch before the fork). Start A after that lane's plan names its write set, or put 3 into it.
- Lane B overlaps B0's "context v2" on `context.clj` (one line). Land B first.
- Lane C overlaps nothing in B0.

Priority against the goal (E: Seon working a real task end to end): A first (honest attempts and visible stuck tasks are what E reads), then B (it is finished work going stale), then C, then D and E.

## Owner decisions and research (one question each)

1. **What is a stall, once an eval is bounded by its work?** (Task 17; it also unblocks rewriting `seon.turn/a-form-has-a-time-bound` and deleting `seon.root/one-clock`.)
   - (a) No stall at all. Evals and tests are bounded by ticks (Jolt's trip, as tests already are), so a CPU runaway ends itself. An agent blocked on I/O shows as busy with its request in flight, and its process state and last op are already in the view. Delete STALL-MS, the go-block timer and `:stall?`.
   - (b) The ruling's "a request owed when the work runs out": at graph quiescence (message count zero on root's side), any agent still owing an answer is recorded `:seon.agent/stalled` once. No time involved.
   - (c) Keep a time bound per request (the task's current plan). This contradicts the 2026-10-21 ruling.
   - **Recommend (a) plus (b):** ticks bound the work, and quiescence names who still owes an answer.
2. **Does health keep a ping?** (Task 10(1).)
   - (a) Health is the absence of open problem facts (AGENTS §2), plus Flow's own `:proc-status` events recorded on change; no `ping-proc :timeout-ms`.
   - (b) Keep `ping-proc` but block without a timeout. This hangs the caller on a dead proc.
   - **Recommend (a).** Fold it into `seon.root/a-proc-answers-every-request-it-took`.
3. **Kqueue EVFILT_PROC for orphan deaths?** (Task 13's orphan check: a new FFI mechanism in Jolt, which needs root's approval as a dependency change.)
   - (a) Yes, as the one process-death watcher for root (it would also serve the fork server's child reaping).
   - (b) No: orphan checking reads the fork server's `:exit` events, which already exist (`root.clj:356-361`).
   - **Recommend (b) first:** research whether `:exit` covers orphans after `:server-down`, which is the case the check provokes, before adding a watcher.
4. **Measurement before task 19:** research, not a decision. Count `lib/active-threads` at each `safe-fork` over a scale run, then decide.
5. **Where the moved tasks go:** a filing question for root.
   - (a) Each open task moves to its owning file (`seon.reconcile.edn`, `seon.root.edn`, `seon.proofs.edn` (new), `seon.check.edn`, `seon.cost.edn`), and every `seon.fences/...` citation is rewritten in the same commit.
   - (b) Keep the ids as they are.
   - **Recommend (a).** Task ids are references, and the parser resolves `seon.fences/x` only while the file exists. Attempts already on the standing store under old ids are history and stay as they are.

## Delete from the file

- **Done; record them as done and drop them:** 1, 2, 4, 5, 8, 11, 12, 16.
- **Moot:** 9 (no `:compute` proc remains; the thread roster is exact).
- **Replace with a rewrite elsewhere:** 17 (its flusher half is `seon.writer/durability-is-continuous-group-commit`; its stall half is decision 1). 13 is split into per-mode `seon.check` tasks.
- **After lane B lands:** 14.
- **Then:** the remaining ready and half-baked tasks move to their owning files (decision 5), and `seon.fences.edn` is deleted.

Nothing of this triage is still running.
