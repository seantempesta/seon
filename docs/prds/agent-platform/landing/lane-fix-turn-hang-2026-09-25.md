---
type: landing
status: complete
lane: fix-turn-hang-opus
created: 2026-09-25
---

# The call pass nested a second opening, so every prompt took 36 s

The turn did not hang on a latch, a stub or S1's restore. Each call pass spent ~36 s
rendering its prompt. Every render invocation hit its 2 s limit, the prompt refused, the
pass closed `:error`, and the driver re-opened. `drive!` ran 12 such passes (~5 s each on
a quiet machine), which is more than the test's 114 s request remainder under load.

## Broken or unproven first

- **The fix is not adopted in default.** Hook publication is paused, and I did not reload
  `seon.turn` over default's Vars. The green side of the regression has not run. Root must
  adopt 68eb9d310 and then run the commands below.
- **This test is held until default's JVM is replaced.** Run `268374a45931`'s member (and
  five earlier runs' members) has `completed-tx` and no `terminated-tx`, although its
  thread exited. `admit-run` therefore reserves the test for nobody, and every request
  answers `executed 0 … pending (not started)` with no reason. Issue:
  `docs/seon/issues/an-exited-overdue-test-member-still-holds-its-test.md`. **RESET NEEDED**
  (or a JVM replacement) before root can run
  `a-batched-turn-commits-only-queryable-definition-facts`. The new regression has a fresh
  symbol, so it is not held.
- **The cold prompt still takes 3.0 s** (warm 13 ms) on a fixture branched off default.
  That is over one second, and I have not attributed it to its armed functions (the cold
  render cache is suspected). Unfixed.
- **Nested as-of is still possible elsewhere.** `seon.db/as-of` does not flatten, and
  transcript.clj calls `turn/opening-db` on rows (:1174, :2107, :2180). Issue:
  `docs/seon/issues/a-nested-as-of-view-rekeys-its-projection-on-every-read.md`, with the
  db.clj patch shape. db.clj and transcript.clj are not my files.
- **DRIFT is only partly done.** One `ai/complete` redef was converted (the batched test),
  and ~49 remain in cluster/turn_test.clj. `with-provider!` is now the third local provider
  server in the tests (agent_test, ai_test). It belongs in test_support, which is not my
  file.
- **I reloaded `seon.cluster.turn-test` in default's JVM** (`require :reload`) so I could
  run the new regression against the loaded program. It matches the committed file 7fb3f7131.

## Diagnosis (REPL, default pid 35446, disposable handles)

- `seon.test/run` refuses to start the member (held, above). I built a member handle the
  way `seon.test/run` does instead:
  `(#'seon.test/clean-request-handle handle recording "default" effective)`, then
  `(seon.cluster.agent/acquire-context! req nil {:seon.agent/isolate? true ...})`. I bound
  `seon.test/*member*` to it and drove the test's fixture (`#'seon.cluster.turn-test/with-cluster`,
  `drive!`) in a future, with agent_test's local provider in place of the redef. Both handles
  were released afterwards (branches `agent-e58399275dc3` and `agent-e7a8bb480cca` are gone
  from the roster), and the MCP branch `fix-turn-hang-opus` is retired.
- The drive with limit 12 ran 64.8 s. Every pass was `:open :released` → `:call :error`, and
  the provider ledger stayed at 0, so no provider call was made. The stub was never reached,
  so the redefs are not the cause.
- `seon.cluster.prompt/prompt` on the opening value returned a declared refusal. It named
  `seon.render.value/render-ai`, reason `:time-limit`, on the `(help)` evaluation, at the
  2000 ms `:seon.config.eval/time-limit-ms`.
- Thread sampling at 50 ms during a 60 s-limit prompt gave these counts:
  - 533 of 733 samples were in `seon.db/declaration-content-key`, reached through
    `carried-projection` db.clj:1529 (the as-of branch with no view key);
  - 425 of those came from `render/request-projection` ← `project-node*` ← `value-node`
    inside `render-ai`.
- Prompt db: `AsOfDB` over a committed `DB`, and `as-of-key` hits. `carried-projection`
  took 1.5-2 ms per call after the first. Nested view: `AsOfDB` over `AsOfDB`, `as-of-key`
  nil, 37-41 ms per call.
- The producer: `call-turn` passes `prompt-db = (opening-db observed-db run-id)` to `prompt`.
  `render/acquire-context!` (render.clj:1754) applies `turn/opening-db` to it again, which
  builds the nested view.
- The same fixture, same run and same limit, timed per prompt:

  | prompt input | first call | second call | result |
  |---|---|---|---|
  | observed value | 3042 ms | 13.3 ms | no refusal |
  | opening value (nested) | 35754 ms | | |

  The text (168,898 chars), the contributions and the budget report were all `=` between
  the two.

## Change

| commit | slice | lines |
|---|---|---|
| 68eb9d310 | `seon.turn/call-turn`: `prompt` receives `observed-db`. Acquisition derives the opening from the run id, and the turn keeps `prompt-db` for the refusal capture | +6 −1 src |
| 7fb3f7131 | turn_test: `with-provider!` (a local OpenAI-compatible server named through the endpoint and no-auth dials), the batched test converted from its `ai/complete` redef, and the class regression `a-turn-through-a-local-provider-settles-and-exits-within-its-bound` | +68 −3 test |

The class regression asserts `[:open :call]`, a `:closed` outcome, exactly one provider
request, `"42"` settled, and the drive under 10 s. The bound covers the measured open
(~1.4 s) plus a cold prompt (3.0 s) plus one evaluation. On the loaded pre-fix program it
fails the way the defect did: `[:open :call :open :call]`, `:error`, 0 requests, 33.5 s.

clj-kondo reports 0 errors and 28 warnings on turn_test (28 at HEAD). turn.clj has only
pre-existing warnings.

## Root runs (serially, after adopting 68eb9d310 and 7fb3f7131)

```
bin/test-check default --test seon.cluster.turn-test/a-turn-through-a-local-provider-settles-and-exits-within-its-bound
# after a JVM replacement (the held member, above):
bin/test-check default --test seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --ns seon.turn-loop-test
```

## Ownership

turn.clj: only `call-turn`'s prompt call site changed (root parked it once, and I
re-applied it from `tmp/orchestrator/parked/digestns-1217-src_seon_turn.clj.patch` before
committing). Released paths: src/seon/turn.clj, test/seon/cluster/turn_test.clj, the two
issues and this landing note.

## Follow-up: the regression hung on root's pid 16604

The regression passed later in a clean JVM. The hang was on a JVM running mixed code, not on the fix.

- **pid 16604 (booted 18:22:37Z).** Thread 385 was live, busy in
  `open-turn` → `system-turn` → `evaluate-sources` → `seon.sci.eval/shown-result` →
  `seon.error/prepare-result`. That frame renders a contract error's offending value as
  HTML with no bound, and the thread was recursing through `seon.render.hiccup/hiccup?`
  (JSON thread dump `tmp/fix-turn-hang-opus/threads2.json`). The same JVM logged
  "JVM RESTART NEEDED: loaded dependencies changed (reference-code/sci)" when 51dda24e9
  bumped SCI, so it was running mixed code.
- **pid 18152, after the restart.**
  - `seon.turn/call-turn` loads at the file's line 4265, and 68eb9d310 is an ancestor of HEAD.
  - I ran each of the seven system forms of the open separately through
    `seon.turn/evaluate-sources` on a member-handle fixture, with and without the provider
    config. Each form returned no contract error, in 4-1238 ms.
  - `clojure.test/test-vars` on the regression passed, with empty output, in 7003 ms, under
    concurrent root test load.
- **Still open:** `error/prepare-result` renders the complete HTML of any offending value
  with no bound, so one huge offending value can keep a thread busy indefinitely. That
  belongs to error.clj or its render owner, not to this lane.

## The overdue-member hold (harness, src/seon/test.clj, not yet granted)

Diagnosed from the code; not yet proved at the REPL.

- On a late exit, `member-result`'s watcher (test.clj:1933) calls `settle!` with a *new*
  failure, "exited after its request bound". `runner/commit-results!` (runner.clj:1405)
  then sees an already-completed member whose report ids differ from the recorded ones
  ("did not exit within …"). It refuses with `:seon.test.run/immutable`.
- That refusal is thrown on the watcher's bare virtual thread, where nothing reads it. So
  `terminated-tx` is never written, and `admit-run` holds the test until the JVM is replaced.
- There is also a race. If the body exits before the main path commits the overdue result,
  the watcher's commit lands first, and the main commit is refused as immutable instead.
- The fix is test.clj/runner.clj work that needs root's grant, plus a choice between
  options in the reply.

## The hold fix (option A, commit 1197a35ac; test.clj and runner.clj granted for this fix)

- **The cause, proved on real rows.** `(seon.test.runner/complete-members db completion)`
  for run `268374a45931`'s member threw `:seon.test.run/immutable` when given the watcher's
  "exited after its request bound" result. The late exit was never recorded because of
  that refusal.
- **The fix.**
  - `runner/exit-tx` adds only `terminated-tx`, and only when the member's result is
    recorded and its exit is not. I checked it on real rows with a scratch copy and
    `d/with`:
    - held member: one datom;
    - member already exited: nothing;
    - member with no result yet: nothing;
    - a second call: nothing.
  - `runner/record-exit!` commits `exit-tx` and returns a refusal instead of throwing.
  - The watcher now records the exit through `record-exit!`. If it sees the exit before
    the result exists, it writes nothing, and the main path records the exit after it
    commits the unfinished result.
  - A refused exit write, or a cleanup failure when the watcher releases the branch late,
    is recorded as a fault through `seon.fault/record!`, which stores and delivers it.
- **Size.** +72 −31 across test.clj, runner.clj and one_request_test.clj. clj-kondo reports
  0 errors, and the warnings are the ones HEAD already has.
- **Regression.** `an-unexited-member-is-never-started-again-until-its-exit-is-recorded`
  now records the exit through `record-exit!`, and the next request runs the test.
- **Not proved:** the new code has not run in default. It needs publication and one JVM
  restart, which also clears the held members of earlier runs.
- **File outside the grant:** `test/seon/test/one_request_test.clj`. The ledger listed it
  as free.

Root runs (after publishing 1197a35ac and restarting):
```
bin/test-check default --test seon.test.one-request-test/an-unexited-member-is-never-started-again-until-its-exit-is-recorded
bin/test-check default --test seon.test.one-request-test/a-body-outliving-its-bound-is-released-only-after-it-exits
bin/test-check default --test seon.cluster.turn-test/a-turn-through-a-local-provider-settles-and-exits-within-its-bound
bin/test-check default --test seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
```

## Round 4 (pid 23282, commit 6ab6c29c): the batched turn loops, it does not deadlock

- **One member-handle fixture drive of the batched source.** This was the local provider
  and a manual pass loop capped at 4 passes. Each tuple is (pass, outcome, forms run, time,
  provider requests so far):

  | pass | outcome | forms run | time | provider requests |
  |---|---|---|---|---|
  | `:open` | `:released` | 0 | 1669 ms | 0 |
  | `:call` | `:error` | 8 | 16195 ms | 1 |
  | `:open` | `:released` | 0 | 5789 ms | 1 |
  | `:call` | `:error` | 8 | 16024 ms | 2 |

  Every call pass evaluates all 8 forms, ends `:error`, and the agent re-opens and calls
  again. `drive!` allows 12 passes, and 6 × ~22 s is over the 115 s bound.
- **Root's dumps show one body, not two.** "ForkJoinPool-1-worker-3" is the carrier *of*
  virtual thread #554 ("Carrying virtual thread #554"). The two dumps caught the same body
  at different moments: once in `settle!` → `transact!`, once in the open's
  `declared-sources` render. No second body is running, and the writer is not stuck.
  Transaction waits took 43 of 366 samples.
- **`db/identity-attributes` is not the cost.** It took 0.12 ms per call, measured over
  20 calls (2486 attributes, 56 identity attributes).
- **Where the time goes.** Samples were taken every 100 ms over 36.6 s, 366 in all:

  | samples | ~time | path | what it does |
  |---|---|---|---|
  | 80 | 8 s | `turn/evaluate-sources` (turn.clj:4762) → `db/read-evidence` → `read-result-digest` → `stable-value` / `schema/canonical-data-string` | digests every captured read result, whatever its size |
  | 62 | 6 s | `test.accretion/auto-check` → `candidate-capabilities` → `effect/capabilities` (effect.clj:161) | recursive reach query over the whole call graph, once per `defn` |
  | 45 | 4.5 s | `turn/system-plan` (turn.clj:1976) → `db/read-evidence-changes` → `index-pattern-change` | scans since-views of the branch |
  | 32 | 3 s | `db/declares-program?` (db.clj:1398) inside `carried-projection`, from `render/project-node*` | per-node projection key |
  | 43 | 4 s | `turn/settle!` and `system-turn` `transact!` | writer latency |

- **Not yet known:** why the call pass ends `:error` after all 8 forms run. The next probe
  captures the call pass's whole report and error facts. It needs root's live-reproduction
  window.
