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
