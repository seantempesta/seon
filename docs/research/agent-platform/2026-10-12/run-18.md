---
title: "Run 18: chain 4 (seon.render.block/surface-id) on 487388073: Prove wrote the table on call 4 (red as specified), and the attempt never made a 5th model call. Its turn proc spun at about one core for 23 minutes in the :prove situation (prove-turn → run-proofs, no write), because proof-run-recorded? never became true once the first product test existed. No law, no merge, nothing shipped"
type: research
status: complete (lane run18, 2026-10-13); chain STALLED in Prove (a busy loop); nothing merged
created: 2026-10-13
---

# Run 18: the chain `seon.render.block/a-surface-id-passes-only-ascii-letters-digits-dot-and-hyphen` on 487388073

**Broken first: the Prove attempt live-locked (F53).**

The Prove agent `attempt/0343dd641b00` made 4 model calls in 4.7 s:

1. sources;
2. aliases;
3. generators;
4. the table deftest `seon.render.block-test/surface-id-escapes-every-character-outside-ascii-letters-digits-dot-and-hyphen`.

The automatic run after call 4 (`2e465810a5d8`, 23:22:56.7, `:interpreted`) found it **5 pass / 3 fail**: exactly the
three non-ASCII rows, as the task says. The branch's last write was at 23:22:57.226 (`:seon.agent/new-red`). After
that nothing happened: no 5th model call, no law, no write on the branch or on main (basis t 536871291 from 23:27 to
23:42), and `:open-turns 0`.

The attempt was not idle. Its turn proc thread `seon-flow/default/attempt/0343dd641b00/seon.agent/turn` used **about
2,000 ms of CPU per 2,000 ms**. I sampled it at 23:44, and three stacks cycled through:

- `seon.turn/step` → `seon.turn/next-agent-work` → `seon.turn/attempt-running?` / `seon.task/obligations`
- `seon.turn/step` → `seon.turn/turn` → `seon.turn/prove-turn` → `seon.cluster.agent/acquire-context!`
- `seon.turn/step` → `seon.turn/end-task!` → `seon.turn/task-end` → `seon.context/step-verdict` → `step-observation` → `head-entries`

The JVM's profile at 23:43 had **`seon.schema/call-with-projection-state` 311,767 calls, 1,264 s self** (about
21 min of CPU), with `seon.db/commit-database` called 155,883 times. Health counted 8 → 15 untested-hot warnings
during the window. The spin stopped within 5 s of my cleanup write (`:seon.task/execution :held`).

**Cause (hypothesis from the code, not verified by a failing test).** `seon.turn/next-agent-work` answers `:prove`
while `(not (proof-run-recorded? db agent-id))`. `seon.turn/first-proof-run-t` finds that run only through
`[?run :seon.test.run/identities ?proof]` for the step's tests. For a Prove step, `seon.turn/step-tests` includes
each declared `:seon.task/product` test the branch already holds.

1. At the fork, neither product test existed, so step-tests was empty and the attempt row counted as "proved first".
2. When the table appeared, step-tests became `#{table}`. The post-eval run that recorded it (`2e465810a5d8`) selects
   by `:reaches-changed` and carries no `:seon.test.run/identities`.
3. From then on, every pass derived `:prove` and ran `prove-turn` → `run-proofs` (`:named`, identities `#{table}`).
   The table's result is unchanged, so it was answered from its record, and no run with identities was written
   (the branch's max-tx stayed 536871310).
4. Every next pass derived `:prove` again.

Run 16 and Run 17 never reached this state: before `ec19ecdfb`, Prove ended on its first deftest or was satisfied
at its fork.

**Nothing shipped.** `seon.render.block/surface-id` is unchanged on HEAD and default. In the JVM,
`(surface-id (keyword "é"))` → `"surface-é"`, `ª` → `"surface-ª"`, `٣` → `"surface-٣"`.

| Measure | Prove (`198592de3663`) | Implement (`ac1862579146`) |
|---|---|---|
| Assignment / agent / branch | `0343dd641b00` / `attempt/0343dd641b00` / `:attempt-0343dd641b00` (+ `:base/…`), **kept**, no end | never launched |
| Model calls | **4** (23:22:50.1 → 23:22:54.8), then none | 0 |
| Product written | table **yes** (8 rows exactly as specified, `\u` escapes via `clojure.core/keyword`); law **no** | n/a |
| Proof runs | `2e465810a5d8` (post-eval, `:reaches-changed`): table 5/3/0, interpreted | n/a |
| End | none; attempt row without `:seon.task.attempt/end` | n/a |
| `refine!` / `send-back!` | 0 / 0 | n/a |

## Before launching

- Default pid 52150, booted 23:22:05 UTC from `data/source/487388073aa6…` (HEAD), and reset. Health `:degraded`:
  only a cache discipline warning and one untested-hot warning. Default was not stopped or reset by this lane.
- Subject `2ecf010721dd` `:held`. Prove `198592de3663` (`:step :prove`) has `:seon.task/product` set to the two
  deftests. Implement `ac1862579146` `:needs` Prove. `seon.task/obligations`: Prove `:proofs #{}` and
  `:product #{table law}`; Implement `:proofs #{}`, because neither test exists yet. `seon.task/ready` `#{}`.
  Branches `[:db :current-src :cluster-default]`.
- Gap re-verified in the JVM: `é`, `ª` and `٣` pass through unescaped. `:transcript`, `:a_b` and `"a b"` answer the
  table's expected values.
- **Reaching tests, interpreted, on probe branch `run18-probe`** (`:agent-209ad86c26c7`).
  `(my.program/tests-reaching 'seon.render.block/surface-id)` gates one test,
  `seon.render.block-test/stable-surface-addresses-are-injective`. Run `fa3644c9c634`: `:interpreted`, **green 2/0/0**,
  2,270 ms and 4.0 GB (F27 again). Launched. The probe was retired with `(my.agent/retire-branch! (my.agent/branch))`,
  which now composes (F47 fixed by `1ced3d565`).
- Config: `seon.config/apply!` with `{:seon.config.ai/no-provider false :seon.config.run/max-episode-runs 60}`
  answered `{:converged? false :operations 4}` (C3). `seon.config/effective` confirmed both values, plus
  `relaunches 2` and model `deepseek-flash`.

## Timeline (UTC, 2026-10-12)

| At | Event |
|---|---|
| 23:22:49.9 | root transacts `[:db/add [:seon.task/id "2ecf010721dd"] :seon.task/execution :running]`. This was the only hand act. Nothing was written to default until 23:42:51 |
| 23:22:50.116 | Prove call 1: source of `surface-id` and the injectivity test |
| :52.150 | call 2: `my.program/source` of the table it has not written yet, plus `ns-aliases` |
| :53.643 | call 3: `clojure.repl/source` of `gen/string` and `gen/such-that` |
| :54.844 | call 4: the table deftest, tagged Implement. It shows the Var render, no error |
| :56.7 | post-eval run `2e465810a5d8`: table 5 pass / 3 fail (the three non-ASCII rows) |
| :57.226 | last branch write: `:seon.agent/new-red` |
| 23:22:57 → 23:45:49 | no model call and no write. The turn proc spins (F53); `:open-turns 0`; main basis constant |
| 23:42:51 | 20 minutes up. JVM-mode reads begin |
| 23:45:49 | cleanup: config restored, subject `:held`. By 23:46 every thread is idle |

## Seon git commits from this run

None. `git log 487388073..HEAD` was empty through 23:50.

## Correctness check

Nothing merged, so there is nothing to grade. The table Prove wrote, on its branch only, matches the instructions
row for row. Its red rows are exactly the gap. The law the task declares as product was never written.

## Grades

- Hand acts after launch: **0**. Wakes: **0**. Relaunches: **0**. Calls: Prove **4**, Implement **0**.
- Platform: launch → Prove fork → 4 calls → **live-lock**. The `ec19ecdfb` rule that Prove needs both product tests
  held (Prove did not end on its first deftest, which is the intended change). But the attempt then had no way to
  reach its next model call.
- What the coordinator asked to watch:
  - Prove did not write both product tests. It wrote one and was then never called again.
  - Implement never ran, so whether it owns the tagged tests as proofs is unobserved. Only `seon.task/obligations`
    on main, which shows no proofs while the tests are absent.
  - Contract quality: nothing merged.
  - Branch deletion: Prove's branch remains, correctly, since the attempt never ended.
  - `be25d0dfb` (satisfied branches deleted) was not exercised.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F53 (binding, platform)** | Once a Prove step's first declared product test exists, the turn proc loops `next-agent-work` (`:prove`) → `prove-turn` → `run-proofs` with no write and no model call, at about one core for 23 min, until the task is held. Hypothesis: `seon.turn/first-proof-run-t` needs a run carrying `:seon.test.run/identities ⊇` step-tests. The post-eval run (`:reaches-changed`) records none, and the `:named` run inside `prove-turn` answers the unchanged table from its record without writing a run, so `proof-run-recorded?` never turns true | `seon.turn/first-proof-run-t` / `proof-run-recorded?` (read "the proofs ran" from member results by symbol, whichever run recorded them), or `seon.turn/run-proofs` (a reused answer still records the run's identities) | in `seon.turn-test`: a Prove attempt that defines its first product deftest in an eval request (red) derives `:seon.turn.work/situation` other than `:prove` on the next `next-agent-work`. Red on 487388073: it answers `:prove` forever |
| **F54 (platform, bound)** | The live-lock was unbounded and silent: no error, no problem, no health line naming the agent. Health only showed a growing untested-hot count. The law says every execution surface declares its bound and names the missing terminal event, and that a repeated situation with no progress is a finding | the turn proc's loop bound (`seon.cluster.agent/turn-step` / the write-refusal bound in `prove-turn`'s docstring, which parks only refusals, not no-op passes) | a turn proc that derives the same `:prove` situation N times with no new transaction records one error on the agent (`:seon.error/type` naming the situation) and parks |
| F55 (cost) | Reading step state from a raw `datahike.versioning/branch-as-db` value is very slow. `(#'seon.context/step-verdict bprove agent)` gave no answer within 62 s, and `(#'seon.turn/proof-run-recorded? …)` did not finish within 20 s. The hot frame is `seon.schema/call-with-projection-state`, probably rebuilding a projection for a db value that carries none. Not isolated further | `seon.db` carried projection for `branch-as-db` / `commit-as-db` values | `seon.profile/measure` of `(seon.turn/proof-run-recorded? (branch-as-db conn b) agent)` under 1 s |
| F44, F27, C3 (again) | as Runs 16-17; only C3 and F27 observed this time (`apply!` 4/3 operations; 2,270 ms and 4.0 GB to check one test) | as before | as before |

**Lane note (my load on default).** My F55 probes (`step-verdict`, then `proof-run-recorded?` inside a `future`)
added up to two more spinning threads, on the MCP connection thread and the future, for a few minutes at
23:44-23:46. Both were idle by 23:46. They were reads only: no writes and nothing destructive.

## Residue on default (disposable)

- **Subject** `2ecf010721dd` is back to `:held`, which stopped the spin. Prove's attempt `0343dd641b00` has no end.
- **Branches**: `:attempt-0343dd641b00` and `:base/attempt-0343dd641b00` remain. They hold the unmerged table
  deftest, which is the evidence for F53. `run18-probe` was retired.
- **Config** restored to `no-provider true` and `max-episode-runs 100`, verified with `seon.config/effective`.
- **Shared tree**: unchanged by the chain. `surface-id` still has the gap.
