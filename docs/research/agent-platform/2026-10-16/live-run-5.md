---
title: "live run 5: attempt 4 of the five held render tasks, all five stalled before a model call"
type: evidence
status: "run complete 2026-10-16 08:49 UTC: 5 of 5 attempts ended :stalled with 0 calls; the step's run held the full member list on each, stopped :request-threw with 0 members completed; the throwable is recorded nowhere; nothing merged"
---

# Live run 5: five held chains, attempt 4, observed read-only

Lane live-run-5 (Sonnet), observer only. Default JVM pid 80515 (started 08:47:56 UTC) at HEAD 56038bbb1. Root ran
`my.root/run!` on the five held tasks (turn `8e2682dc82d7`); attempt 4 of each started on its existing chain branch
`:chain-<handle>` at 08:49:12 to 08:49:21 UTC. Hand acts by this lane: 0 (JVM-mode `d/q` and `d/pull` over
`seon.db/branch-database` and main, `seon.problems/open-faults`, `bin/seon status`, `bin/seon check --at HEAD`; no SCI
request, no `run!`, no message). All five had ended before the first poll (about 08:50). The 45-minute poll was not
needed: every chain shows attempts 1 to 4 and no attempt 5 (checked 08:54), so nothing reran by itself.

## Summary

Wall is the attempt agent's first to last transaction. "Members" is the size of the step's named run recorded on the
chain branch.

| chain | task | attempt 4 end | calls | wall | tokens p / c / out | forms | step's run: members / completed / stopped | done and merge agree | test-step errors | score |
|---|---|---|---|---|---|---|---|---|---|---|
| `fddd9dcbfe66` | `seon.background/render-ai` | **stalled** (`1ed6864d6246`) | 0 | 9 s | 0 / 0 / 0 | 0 | 64 / 0 / `:request-threw` (`78a1da88e9d5`) | not judged | none recorded (see Broken 2) | none |
| `ce9d664c1be0` | `seon.effect/render-ai` | **stalled** (`8d5146f68e13`) | 0 | 3 s | 0 / 0 / 0 | 0 | 9 / 0 / `:request-threw` (`88e571d368ed`) | not judged | none recorded | none |
| `42e0e4c46b71` | `seon.error/elision-prose` | **stalled** (`90badd13b0ed`) | 0 | 3 s | 0 / 0 / 0 | 0 | 64 / 0 / `:request-threw` (`3374f7722c60`) | not judged | none recorded | none |
| `91a8e6815ef2` | `seon.render.transcript/render-run-ai` | **stalled** (`f51f9ea55ff5`) | 0 | 3 s | 0 / 0 / 0 | 0 | 4 / 0 / `:request-threw` (`b8db7100132a`) | not judged | none recorded | none |
| `fd3ba8395135` | `seon.repl/render-ai` | **stalled** (`214ed222a902`) | 0 | 3 s | 0 / 0 / 0 | 0 | 12 / 0 / `:request-threw` (`07c99920aa05`) | not judged | none recorded | none |

Totals: 0 calls, 0 forms, 0 tokens (run 4: 172 calls, 3,872,776 prompt tokens). The `in-ns` share is not measurable
(0 forms; run 4: 15%). Each stalled attempt has `:seon.agent/end-pending 1`, `proof-counts {:pending 1}`, and a held
problem task `unfinished-task-<attempt>` (`39ae5b5b379a`, `8c45a34c81e6`, `d0f8851c5899`, `26f31f381a99`, `57e831620682`)
whose plan is fixed text and whose verdict is only `{:seon.task/proof-counts {:pending 1}}`.

## Broken things first

1. **All five attempts ended `:stalled` at their first test step, before any model call: attempt 4 produced no
   evidence about the agents.** Per chain the step's run was admitted (policy `:named`, evaluator `:interpreted`,
   every member reason `:named`), then stopped with `:seon.test.run/stopped :request-threw` and `completed 0` of its
   members (`seon.test.runner/record-interrupted!` stops the run this way). The chain's attempt wrote no evaluation.
   The 64, 9, 64, 4 and 12 members are the full member list: 42e0 now runs 64, the same size as its merge ran in run 4
   (`eea50b7aed31`), so the one-member-list change (56038bbb1) holds on this evidence. What it does not show is whether
   done and merge agree on results, since no member ran.
2. **The throwable that stopped each run is recorded nowhere.** `seon.test.runner/record-interrupted!` discards its
   `failure` argument (`^Throwable _failure`) and `seon.test` rethrows to the entrant, yet after the stall I found no
   error occurrence on main or on any chain branch (`:seon.error.occurrence/last-at` after 08:40: 0 rows on all six
   databases), no `:seon.error/at` row, no new open fault (`seon.problems/open-faults`: 7, none after 05:36 UTC on
   2026-10-16), no `:seon.turn.loop/test-error` in any value (there is no reply to hold one), and nothing in
   `data/clusters/default/logs/seon.log` after 08:48:44. The five problem tasks carry only the pending count. "Recording
   without delivery, a log line or a panic alone is a defect" (CLAUDE.md): this is one, and it is the reason this
   run cannot say what threw. I did not reproduce it (a reproduction would write a run on a chain branch).
3. **Known risk, unconfirmed by this evidence.** The `seon.program/declaration-digest-map` refusal at
   `:seon.program/identities` (called from `seon.program/digest-map`) is consistent with the pattern (a throw at the
   first member's world acquisition, after the run was admitted, in all five chains, on a JVM started minutes earlier)
   but nothing recorded names it. `bin/lane status` shows a lane `digest-map-contract (since 56038bbb1): 2 paths`
   working on it. A direct JVM call of `digest-map` with test symbols as identities reproduces that refusal's shape,
   but that is not the step's call; I make no claim that it is the cause. Cost to each attempt: all of it (0 of 40 calls
   used, attempt ended in 3 to 9 s).
4. **A stalled attempt's problem task says nothing about why.** `:seon.task/plan` is "The attempt ended :stalled",
   the verdict is the pending proof count, and the task offers `my.root/run!` again. A reader of the held task
   cannot tell a model stall from a platform throw (Broken 2). Run 3's stall reason named a fault id
   ("stalled: reply-stage :seon.turn.loop/phase-failed-error, fault ec89d9a4fbaf"); this one has none.
5. **Cost of the step before it threw: fddd 7 s, the others 1 to 3 s from admission to the stop.** fddd's attempt
   took 9 s (08:49:12 to :21) against 3 s for the other four (started 08:49:18 to :21; four attempts started in
   parallel, one 6 s after fddd). A 9 s attempt that does nothing is over the sub-second rule for ordinary work.
   Not profiled: the stall made no call whose cost I could read.

## Test-step errors, red reaching tests, `in-ns`, merges

- **Test-step errors:** none visible as `:seon.turn.loop/test-error` (no reply exists); the run's `:request-threw` is the
  only trace (Broken 2).
- **How the agent handled red reaching tests:** unobserved. No agent turn ran.
- **`in-ns` share:** n/a (0 forms; run 4 15%, run 3 10%, run 2 23%).
- **Merges:** none. `:seon.unmerged/entry` on main: 0. No trailers to read, no `:stopped` on a merge, no platform JVM
  seen (every run `:interpreted`, none `isolated`). Kept merge branches unchanged from run 4.
- **Post-run check:** `bin/seon check --at HEAD --test seon.babashka-load-test/declared-namespaces-load-in-babashka
  --include-long`: run `ada356305d7f`, executed 1, pass 3, fail 0, error 0, 1,697 ms, `passed? true`, total 73.6 s
  (seed 55.6 s). Merged proofs of this run: none, so nothing else to check.

## Cumulative tally of the nine render tasks, runs 2 to 5

Attempts per open chain: 1 (run 2), 2 (run 3), 3 (run 4), 4 (run 5). The four merged chains stand.

| chain | run 2 | run 3 | run 4 | run 5 | end state |
|---|---|---|---|---|---|
| `dcc8` model-ai | merged | | | | merged |
| `419a` config-render-ai | merged | | | | merged |
| `8d9e` mcp-prose | merged | | | | merged |
| `05db` provider-ai | done, merge refused | merged (0 model calls) | | | merged |
| `fddd` background-render-ai | done, write-back refused | done, merge refused | capped | **stalled, 0 calls** | open, held |
| `ce9d` effect-render-ai | capped, done refused | capped, done refused | capped, done refused | **stalled, 0 calls** | open, held |
| `42e0` elision-prose | capped, proof red | capped, done refused | done, merge refused | **stalled, 0 calls** | open, held |
| `91a8` render-run-ai | capped, proof red | capped, done refused | capped, done refused | **stalled, 0 calls** | open, held |
| `fd3b` repl-render-ai | capped, proof red | capped, done refused | capped, done refused | **stalled, 0 calls** | open, held |

Merged 4 of 9, open 5, none abandoned. Model calls: 275 + 173 + 172 + 0 = 620 (unchanged from run 4's total). Prompt
tokens 13,492,849 (unchanged). Attempt 4 added five problem tasks and no information about the agents; the held
state of the five chains is as before run 4's changes took effect.

## Defects found

- **Unrecorded request throw** (Broken 2): `seon.test.runner/record-interrupted!` drops its throwable and the entrant does
  not record it when the attempt ends `:stalled` at its first step. Reproduce: read `:seon.test.run/stopped` of any run
  at or after 08:49 UTC on a chain branch (`:request-threw`), then search for an error occurrence at that time: none.
- **Stalled problem task carries no cause** (Broken 4): plan and verdict name only the pending count.
- **Instrument-time contract on a read of the member list may be the throw** (Broken 3): unconfirmed; the digest-map lane
  owns it.
- **One-second rule:** fddd's 9 s stalled attempt (Broken 5).
- Agent-side defects, wasted-turn classes and the `in-ns` share: nothing to measure; the run 4 classes stand untested
  against the one-member-list and digest changes.

## Wasted-turn classes against run 4

Run 5 has 0 forms, so no class can be ranked. Run 4's classes (reading sources 53%, hunting a way to run a test, `in-ns`
15%, `step-verdict` polling, wrong API shape) are neither confirmed nor refuted by this run. What changed on the
platform side is visible: the step now holds the full member list (64, 9, 64, 4, 12 members, against run 4's one-member
named runs), which is the precondition for removing the "hunting a way to run a test" class. It is not yet exercised,
because the first member did not complete.

## Top three fixes the evidence supports

1. **Record the throwable that stops a request, and carry it into the stalled attempt's problem task.** Replace the
   discarded `^Throwable _failure` of `seon.test.runner/record-interrupted!` with its recorded error occurrence (one
   error map, the entrant boundary records it once), and put its signature in the `:stalled` reason as run 3's fault
   id was. Proof: a red deftest that stops a request with a throwing member and asserts an error occurrence on the
   branch and the fault id in `:seon.task/reason`.
2. **Land the digest-map contract fix, then re-run the five chains once.** The member list is right; no member ran.
   The digest-map lane's proof is the gate: its red must be green, then root's `my.root/run!` on the five gives run 5's
   intended measurement (the done and merge member lists agree on results, agent behavior with a full list).
3. **Make a stalled attempt cheap and named at the first step.** An attempt that ends in 3 to 9 s with no call should
   say which stage threw (admission, world acquisition, member) as data on its verdict, and the 6 s that fddd's
   world acquisition took beside the other four's 3 s needs its profile cells read when the cause is fixed.
