---
title: "live run 9: the run-8 tasks again on d24ad58e7, prompt cache back at 87%, one merged, observed in JVM mode"
type: evidence
status: "run complete 2026-10-18: 1 of 3 merged (the unresolved-symbol reply, 17 calls), 1 sent back correctly, 1 capped; cached share 87% (run 8: 22%); default never crossed a heap bound (old gen peak 1,026 MB of 4,096)"
---

# Live run 9: three chains, attempt 1, observed in JVM mode

Lane live-run-9 (Opus). Default JVM pid 53160, started 03:27:36 UTC at d24ad58e7. There was one root act, an SCI
request at 03:29:59 UTC with three `seon.task/chain!` calls. It used the same form as run 8:
- template `seon.step.implement/template`;
- plan and proofs from the filed task on main;
- chain config `{:seon.config.ai/no-provider :seon.config/absent :seon.config.run/max-episode-runs 40}`;
- model `deepseek-flash` and `:seon.config.agent/max-running` 4, both main's effective values.

Each task name carries the suffix `-run-9`. The reason: `seon.task/chain!` answers the chain main already holds for
the same first handle (`seon.task/task-handle` is `seon.id/id` over `[ns name]`), so run 8's names would have returned
run 8's chains and written nothing.

Everything else was JVM-mode reads and the lane's own checks:
- `seon.competency/grade` and `seon.mine/classify` on the kept branches;
- `seon.profile/pain` and `seon.profile/measure` on `seon.context/model-context`;
- MemoryPoolMXBean;
- three SCI requests as the lane's own agent `live-run-9-probe`, on its own branch, to verify the help fix.

Hand acts on the agents: 0. All three ended by 03:32:00 UTC, two minutes after the start. Scratch output is in
`tmp/live-run-9/`.

## Before the start

`bin/seon check --at HEAD` (run `7f1d31c9a7cc`) showed the three proofs red:
- fork point: 42 against 3;
- unresolved symbol: 15,056 `name-tokens` calls and 41.5 MB;
- root seed: 5 failures.

The five tests that reached them in run 8 were green:
- `seon.cluster.registry-test/a-re-forked-branch-answers-its-own-fork-commit-as-its-merge-base`;
- `seon.cluster.registry-test/a-fork-from-another-branchs-commit-keeps-the-fork-point`;
- `seon.task-test/a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch`;
- `seon.merge-test/the-merge-base-is-the-merge-requests`;
- `seon.cluster.db-flow-test/a-join-forks-main-after-both-needs-merge`.

Status said `:faulted`, but every `seon.heap/observation` fault on record is from 2026-10-17 11:56 to 11:58. Old gen was
617 MB at the start.

## Summary

Wall is first call opened to last call closed. Tokens are prompt / cached / completion.

| chain | task | end | calls | wall | tokens p / c / out | cached | forms (in-ns) | first defn tried | wasted calls | agent errors | eval ms / MB | score |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d6c6977d38fd` | fork point walked per request | **capped**, proof red | 40 | 104.2 s | 1,084,074 / 983,552 / 9,975 | 91% | 73 (1) | form 36 | 11 | 16 | 670 / 891 | 41 |
| `8c4f98deb037` | unresolved-symbol reply cost | **done at call 17, merged** 50e879526 | 17 | 40.3 s | 239,981 / 191,232 / 4,044 | 80% | 51 (15) | form 35 | 1 | 0 | 472 / 607 | 17 |
| `ae969b87b62b` | root seed from source | **sent back** at call 23, a correct diagnosis | 23 | 62.2 s | 357,527 / 288,896 / 6,129 | 81% | 39 (1) | never (no write needed) | 7 | 3 | 384 / 404 | 41 |

Totals:
- 80 calls and 1,681,582 prompt tokens, of which 1,463,680 were cached (**87%**);
- 20,148 completion tokens;
- 163 forms, 17 of them `in-ns` (10.4%; 15 in one chain);
- 19 wasted calls and 19 agent errors;
- 206.7 s of summed wall;
- 1.5 s of evaluation and 1,902 MB allocated.

Classes:
- read 72 and re-read 5;
- search 19;
- probe 10;
- progress 19;
- unresolved 9, error 5 and contract refusal 5;
- navigation 17;
- repeat 1 and wait 1.

## Prompt cache first

The cache has recovered. The cached share was 87% over the run (91%, 80% and 81% by chain), against 22% in run 8 and
87% in run 7. Per call, the cached count tracks the previous call's prompt less its last message:
- fork, call 11: 17,408 cached of the previous 19,835;
- seed, call 13: 13,568 of 15,250.

That gap is what `seon.context/exchange-values` leaves out by design (the newest message and the turn sections). The
growth of a whole cached share past 80% is bounded by the new tokens each call adds.

When a large newest reply is later sent by reference, the prompt shrinks (unres call 10 was 20,255 tokens, call 11
was 14,613). This is the designed reference cut, and the next call caches again. d24ad58e7 is confirmed live, so the
byte diff of two consecutive views was not needed.

Wall per call fell back to 2.4 to 2.7 s (run 8: 3.8 to 4.6 s), and the whole run took two minutes.

## Broken things first

1. **The merged fix dropped a behavior no test covered.** 50e879526 (the agent's `seon.error/nearest-names`, cached
   per candidate set) kept only the candidates that share a word with the guess. Its docstring and spelling pass still
   asked for names within two edits. Measured on default against the pre-merge function:
   - `qqq/transcat!` lost `seon.db/transact!`;
   - `user/pul` lost `seon.db/pull`.

   The proof went green, and every `seon.error-test` member the merge's run reached stayed as before (`bin/seon check
   --at 50e879526`: 90 tests, the 2 reds already red at d24ad58e7). Nothing asserted a spelling-only match. **Fixed** in
   f4f6155ef, with regression `seon.error-test/a-guess-sharing-no-word-is-still-offered-the-name-it-misspells`.
2. **The fork chain could not replay its proof's fixture, so it never saw why its cache missed.** Its design (forms
   44 and 47) holds the fork point in `seon.cache` under `[branch base-t target]`, read from `seon.cluster.registry/fork-of`
   on the database it is given. In the proof that database is the fixture's main. There the branch's fork entity is
   absent, so every call walks the history.

   Reproduced as the probe agent: `fork-of` on the fixture's main answers `nil` for a fresh `fork!`. The agent saw
   "42 against 3" unchanged after its defn. It then spent 25 forms (10 wasted calls) trying to rebuild the fixture:
   - a store from `(:store (seon.db/schema-database db))`, refused for its missing `:seon.store/dir`;
   - `seon.test-support/with-database`, unresolved because nobody required it;
   - `resolve`, which gave `nil` and then a NullPointerException.

   **Fixed** at help step 4 (f4f6155ef): load the fixtures from `user`, call `with-database`, and take the store from
   `execution-handle`. Each step was verified on an own-branch SCI agent (58 to 99 ms).
3. **The root-seed proof is a retired assumption, and the chain said so.** It confirmed the plan's lead in 23 calls
   and sent the task back with a precise reason. `seon.test-support/with-database` branches from a commit that already
   holds the loaded program's `my.agents.root` row, and no producer opens a root turn in `seon.cluster/seed-root-agent!`.
   This is the right outcome, recorded on the filed task (98b839d98). Restate the test before relaunching.
4. **`seon.cost-estimate-test/no-ordinary-operation-grows-past-its-accepted-overrun` is red at 50e879526 and HEAD.**
   `:unmap-one-def` allocated 162.5 MB against 146.25 MB accepted (task `seon.repl/a-new-def-costs-a-hundred-forty-megabytes`).
   It was not caused by this run's changes; it was accepted red before in both commits.
5. **One chain re-sent `(in-ns 'seon.error)` at the start of almost every call** (15 of 51 forms), although the block's
   turns section ended `:seon.context/namespace seon.error` and help step 2 says not to. These forms cost 0 ms and are
   not wasted calls. The other two chains sent one each. Not filed: there is no producer to name yet (run 7 saw 57%
   repeats, run 8 2.4%).
6. **A defn refused with "derive: :malli.core/invalid-schema" names no schema.** On the fork chain (form 41), a
   contract named `:seon.cluster.registry/fork-point-result`. `seon.schema/register!` had admitted that schema while it
   referenced the then-unregistered `:seon.cluster.registry/no-fork-point`. The refusal did not say which schema failed.
   The agent registered the missing key next, which cost one call. **Not filed**: no red proof was written in this
   lane's time.
7. **The merge wrote straight onto the checkout's branch.** Root's `db<->flow` committed 50e879526 into git on
   `refactor/agent-platform` at 03:30:46, beside the lanes' work. That is the installed merge path. Default still runs
   the pre-merge compiled code until root's next restart.

## Done and merge

Unresolved-symbol reply:
- runs at 03:30:01, 03:30:28 and 03:30:43, each with 1 member;
- the third was green, and the chain merged at 03:30:46 (merge commit `6ad43d65`, git 50e879526);
- the design matches the plan: a `seon.cache` kind keyed by the candidate set, with only the guess tokenized warm.

Fork point:
- runs at 03:30:01 (1 member) and 03:30:50 (6 members);
- in the second, the proof failed 42 against 3 and the other 5 passed (the registry fork tests that broke under run 8's
  per-branch-name cache now pass);
- nothing reran after that, because the agent defined nothing more.

Root seed: one run at the start, then the send-back.

No run was stopped. P6 held: one test request per step.

## Producer attribution (19 wasted calls, by `seon.competency/grade`'s rule: a call with no read, probe, progress or wait)

| producer | wasted calls | where | action |
|---|---|---|---|
| help: no way named to replay a proof's fixture at the REPL | 10 | fork, forms 50 to 65 | **fixed** f4f6155ef, help step 4; proof `seon.bootstrap-test/help-teaches-replaying-a-proofs-fixture` |
| task proof a retired assumption (searches for the `my.agents.root` row) | 5 | seed, `seon.db/q` and `search` hunting the row | the chain's send-back; the task plan records it (98b839d98) |
| agent's own send-back forms (an unquoted symbol in a set; a `nil` `:my.task/target`) | 2 | seed | none: both refusals were clear, and each was repaired in one call |
| agent defined a bare nilable return, which help already names | 1 | fork, form 36 | none: the refusal was clear |
| a repeated `in-ns` with a re-read | 1 | unres | none (Broken 5) |

The task's last line asks for the top one or two producers to be fixed when small. The help line was. The merged
regression (Broken 1) was fixed as well.

## New evidence

- **Per eval request.** No form passed 100 ms (run 8: 40 forms did):

  | chain | median ms | max ms | median MB | max MB | forms over 100 ms |
  |---|---|---|---|---|---|
  | fork | 0 | 97 | 0 | 107 | 0 |
  | unresolved | 0 | 87 | 0 | 108 | 0 |
  | seed | 0 | 84 | 0 | 112 | 0 |

  By verb (forms, ms, MB):
  - `my.program/source`: 77, 361 ms, 559 MB;
  - `my.program/search`: 5, 335 ms, 346 MB;
  - `my.program/callers`: 5, 19 ms, 25 MB;
  - `my.program/ns-unmap!`: 1, 15 ms, 19 MB (5099c7e16 holds).

  Text searches fell from 31 to 5, with `callers` named in help.
- **Every model request builds its context, and that build is the run's largest per-call platform cost.**
  `seon.context/model-context` ran once per model call: 80 calls, 12.3 s in total, a mean of 153 ms. Inside it:
  - `seon.context/current-state`: 6.2 s;
  - `seon.context/exchange-values`: 3.5 s;
  - `seon.bootstrap/help-value`: 0.9 s.

  A warm build on the kept branches cost 81 to 108 ms, 96 to 132 MB and 65k to 102k armed calls, to show 71k to 153k
  characters: 900 to 1,600 bytes per character, about 85 MB of it fixed per request. For the 40-call agent, 32,057
  calls were `seon.schema.datahike/query-variable?`, under 1,094 `clause-variables` and 97 `lower-where`, with
  404 reads.

  `seon.render.sent/view` repeats the build and adds a second `exchange-values`: 117 ms and 197 MB warm. The 640 ms
  per view in the brief is the cold figure; this lane's own first builds cost about 0.6 s each. That is a per-call cost
  every agent pays. It is filed on the existing task `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`
  with the red proof `seon.context-test/a-warm-model-request-allocates-in-proportion-to-what-it-shows`: 18.9 MB against
  2.3 MB (200 bytes per shown character) on the fixture agent.
- **`seon.profile/pain` after** (`tmp/live-run-9/pain-after.edn`):
  - `seon.test.runner/reach-memberships`: steps 212×;
  - `seon.db/vector-query-call`: 40,238 calls;
  - `seon.db/declaration-content-key`: bytes 290×;
  - `seon.cluster.source/snapshot-test-input-digest`: steps 70×;
  - `seon.context/branch-cost`: steps 16×.

  `seon.error/nearest-names` (run 8's top entry) is gone from the list, because default still runs it compiled and no
  agent made an unresolved request.
- **Heap watch.** No crossing. Old gen was 617 MB at the start, 811 MB at 03:32 and 1,015 MB after this lane's measurement
  forms, with a pool peak of 1,026 MB of 4,096.

## Runs 7, 8 and 9

| measure | run 7 (5 render chains, d76806d39) | run 8 (3 real chains, c87f0fb77) | run 9 (run 8's tasks, d24ad58e7) |
|---|---|---|---|
| merged | 1 of 5 | 0 of 3 | 1 of 3 (plus 1 correct send-back) |
| calls per chain | 39 | 40 | 27 (40, 17, 23) |
| wall per call | 2.3 to 2.8 s | 3.8 to 4.6 s | 2.4 to 2.7 s |
| cached share of prompt | 87% | 22% | **87%** |
| `in-ns` share of forms | 9.8% (57% repeats) | 2.4% | 10.4% (15 of 17 in one chain) |
| agent errors per form | 12% (62 of 501) | 6% (15 of 255) | 12% (19 of 163; 16 of them on the fork chain) |
| reads and re-reads | 47% | 44% | 47% |
| text searches | not counted | 31 | 5 |
| first write (defn tried) | form 49 to 90 | form 20, 27, never | form 36, 35, none needed |
| wasted calls | 47 | 25 | 19 |
| evaluation per chain | 0.7 to 1.7 s, 1.1 to 3.0 GB | 3.8 to 6.5 s, 1.9 to 2.8 GB | 0.4 to 0.7 s, 0.4 to 0.9 GB |
| largest form | 1,207 ms (whole-store `d/q`) | 950 ms (`my.program/source`, cold) | 97 ms |
| heap | peak 836 MB, no pressure | peak 1,178 MB, no crossing | peak 1,026 MB, no crossing |

What improved since run 8:
- the cache, and with it the wall per call;
- evaluation cost per chain, down by five to ten times (`ns-unmap!`, warm sources, and `callers` replacing text search);
- reachable proofs: the unresolved-symbol proof, now calling `nearest-names` directly (829263bac), turned green on the
  branch and merged in 17 calls;
- the seed chain used its plan's lead and ended in 23 calls with the right answer instead of 40 calls of searching.

What did not:
- the fork chain capped again, on a design error it could not observe (Broken 2), now addressed in help;
- one chain repeated `in-ns` (Broken 5).

## Fixes made

- f4f6155ef:
  - `seon.error/near-entries` (was `shared-word-entries`) keeps names within two characters of the guess's length
    beside those sharing a word;
  - the content key is the candidates' own hash and count, no sorted set per call, so the cost proof stays under 10 MB;
  - `resources/seon/help.md` step 4 teaches replaying a proof's fixture;
  - proofs: `seon.error-test/a-guess-sharing-no-word-is-still-offered-the-name-it-misspells` and
    `seon.bootstrap-test/help-teaches-replaying-a-proofs-fixture`, both red before and green after;
  - `seon.error-test/an-unresolved-symbol-reply-costs-under-twenty-milliseconds` and the rest of the named checks green.
- 98b839d98:
  - the model-request cost task carries run 9's numbers and its red proof;
  - the root-seed task records the send-back.

## Tasks filed or updated

- `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`: updated to cover the model-context build, with
  proof `seon.context-test/a-warm-model-request-allocates-in-proportion-to-what-it-shows`, red.
- `seon.cluster/root-seed-declarations-come-from-source-is-red-on-head`: plan records the send-back; restate the test
  before relaunching.
- Not filed, because no red proof was written: Broken 5 (repeated `in-ns`) and Broken 6 (the unnamed `invalid-schema`
  refusal).

## Next run

Rerun the fork-point task once default has restarted onto f4f6155ef. The help now shows how to replay its proof, and
the design needs `fork-of` read on the branch's own database. Relaunch the root-seed task only after its test is
restated. The kept branches are `:chain-d6c6977d38fd`, `:chain-8c4f98deb037` and `:chain-ae969b87b62b`.
