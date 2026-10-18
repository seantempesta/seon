---
title: "live run 10: four chains on ab89888ef after the nuke, 0 of 4 green, two proofs green at the cap"
type: evidence
status: "run complete 2026-10-18: 0 of 4 done, all capped at 40 calls; two proofs turned green at call 40 (one refused by the done rule for a real regression, one for a leftover helper); cached share 90%; no heap crossing (old gen peak 812 MB of 4,096); help fix 0b2b0082c; heap-reading defect filed red (2805d9deb)"
---

# Live run 10: four chains, attempt 1, the first post-nuke turns series

Lane live-run-10 (Opus). Default JVM pid 36207, restarted by root onto ab89888ef at 05:57:24 UTC (ready 7.4 s), so
main held this lane's two new proofs. There was one root act, an SCI request at about 05:58 UTC with four
`seon.task/chain!` calls, in run 9's form:
- template `seon.step.implement/template`;
- plan and proofs read from the filed task on main;
- chain config `{:seon.config.ai/no-provider :seon.config/absent :seon.config.run/max-episode-runs 40}`;
- model `deepseek-flash` and `:seon.config.agent/max-running` 4, main's effective values.

Task names carry the suffix `-run-10`, so `seon.task/chain!` writes new chains instead of answering run 8's or 9's.

Everything else was JVM-mode reads (`seon.mine/grade`, `seon.context/step-verdict`, `my.root/turns-report`, the
error facts, MemoryPoolMXBean and the collectors' last `GcInfo`). There were three SCI requests as this lane's own
agent `live-run-10-probe`, on its own branch, to verify the help fix. Hand acts on the agents: 0. All four chains
ended by 06:00:47 UTC, under three minutes after the start. Scratch output is in `tmp/live-run-10/`.

## The metric

`(my.root/turns-report)` after the run, the first window of the post-nuke series:

| window | attempts | green | median turns | p50 / p90 turns to green |
|---|---|---|---|---|
| ab89888ef (from 05:59:12 UTC) | 4 | 0 | 40 | none (no attempt green) |

The pre-nuke series (median 14, p90 35 over 13 attempts, `docs/research/agent-platform/2026-10-18/`) is not
comparable: its attempts were mostly render chains, and this run picked four cost and design defects.

Top waste in the report:
- rules: `:re-read` 12 forms, `:redundant-in-ns` 7, `:repeats-error` 2;
- producers: `my.program/render-source-ai` 10, `seon.context/current-state` 7, `seon.render.value/show` 2.

I read the forms behind each row. Most of these producers are wrong, and the worst producer of the run is
missing from the list (see Producer attribution).

## Task selection

The check was one JVM batch (`bin/seon check` of HEAD plus the two new proofs, run `d37370a44572`, 3 min 4 s).

The run used four tasks, not five. In that batch:
- **The fork point walked per request** (`seon.cluster.registry/the-fork-point-is-walked-for-on-every-request`):
  red 42 against 3. Every other `seon.cluster.registry-test` member was green.
- **A warm `similar` by name** (`my.program/a-warm-similar-by-name-costs-seventy-megabytes`) and **an
  unresolved-symbol request** (`seon.error/an-unresolved-symbol-request-costs-thirty-megabytes`): both tasks named
  the table test `seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates`. No single task can
  turn that table green, because every overrun row is red in it.

  This lane wrote direct proofs and committed them red (ab89888ef, `--accept-red-before`), checked again in the
  commit's own run:
  - `my.program-query-test/a-warm-similar-by-name-allocates-in-proportion-to-its-answer`: 19.5 MB against 5 MB;
  - `seon.error-test/a-warm-unresolved-name-ranks-without-re-reading-the-program`: 11.1 MB against 2 MB.

  Each measures a warm call after an unrelated write, so a cache keyed by the database's commit cannot pass.
  `a-guessed-name-is-answered-with-the-nearest-real-calls`, `an-unreachable-embed-server-still-answers-the-reply`
  and `seon.embed-test` were green.
- **Typed cross-namespace deletion** (`seon.program/typed-cross-namespace-deletion-leaves-function-and-test`): red
  at `:1209`. This was the weakest pick. Its plan only asks the agent to find out whether this is a producer
  defect or a retired assumption.

Rejected:
- `seon.context/one-agents-current-state-costs-hundreds-of-megabytes`: its proof is red, but the stray red
  `seon.context-test/a-referenced-function-shows-its-doc-view` reaches the same code, and the done rule refuses any
  red member.
- `seon.documentation/doc-in-shows-schema-internals-in-a-doc-tree`: the proof reaches its subject only through
  compiled MCP code.
- The writer's owned-value validation (`seon.db/the-checked-writer-is-slow-on-a-fresh-branch`): its named cause
  (`:seon.runtime/turns`) no longer exists in `src`, and no current measurement was taken.
- The instrument's database-walking explain (`seon.instrument` task): reproduced cold on default at 558 ms and
  638 MB (`(seon.task/chain-views db db)`), 55 ms warm. Its producer is the instrumented wrapper, which a branch
  cannot change.

## Summary

Wall is first call opened to last call closed. Tokens are prompt / cached / completion.

| chain | task | end | calls | wall | tokens p / c / out | cached | forms (in-ns) | first defn | wasted calls | agent errors | eval ms / MB |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `6cacbf210e95` | fork point walked | **capped**, proof green, done refused | 40 | 151.2 s | 1,034,745 / 942,208 / 13,908 | 91% | 70 (3) | form 16 | 5 | 5 | 882 / 517 |
| `dbd9bb49a299` | similar by name, warm | **capped**, proof red | 40 | 100.7 s | 894,480 / 814,464 / 10,726 | 91% | 73 (4) | form 53 | 2 | 5 | 584 / 741 |
| `806cdff35fd2` | unresolved name, warm | **capped**, proof green, done refused | 40 | 117.1 s | 1,046,559 / 955,904 / 14,318 | 91% | 99 (11) | form 45 | 3 | 3 | 2,080 / 1,188 |
| `457772aa9fd7` | typed deletion | **capped**, proof red, nothing written | 40 | 61.9 s | 793,591 / 697,344 / 3,347 | 88% | 60 (4) | never | 8 | 2 | 620 / 619 |

Totals:
- 160 calls and 3,769,375 prompt tokens, of which 3,409,920 were cached (**90%**);
- 42,299 completion tokens;
- 302 forms, 22 of them `in-ns` (7.3%);
- 18 wasted calls (by the grade's rule) and 15 agent errors;
- 430.9 s of summed wall;
- 4.2 s of evaluation and 3,065 MB allocated.

Classes:
- read 119 and re-read 12;
- search 30;
- probe 49;
- progress 54;
- navigation 22;
- error 5, contract refusal 5 and unresolved 5;
- repeat 1.

## Broken things first

1. **Two chains turned their proof green at call 40, and neither was done.** Both judgments are right.
   `seon.context/step-verdict` on each kept branch, read after the run:
   - **Fork point.** The design held the fork point in `seon.cache` keyed by `[branch target]` (form 69). The proof
     went green (`the-fork-point-is-not-walked-for-on-every-request` 3 of 3), but
     `seon.cluster.registry-test/a-re-forked-branch-answers-its-own-fork-commit-as-its-merge-base` went red: a branch
     re-forked under a retired branch's name read the retired fork's point. This is run 8's regression again. The
     cache was keyed by a place, the branch name, not by content, and the done rule caught it.
   - **Unresolved name.** The design held `nearest-declared` in `seon.cache` by
     `[(seon.db/carried-projection database) (str missing)]` (form 99), and the proof went green. The only refusal
     was "A replaced function reaches no green test of the named run": `seon.error/candidate-key`, a helper the final
     design no longer called. Both plans now record this (bfee2315b).
2. **The run's largest waste was refused `ns-unmap!` calls, and the grade counts none of them.**
   - 14 of 21 `ns-unmap!` forms were refused: unres 10, fork 4.
   - Each named a surviving referrer, often the agent's own caller. One referrer was a backticked mention in the
     caller's docstring.
   - Agents unmapped helpers before the caller that used them, and once the task's own subject.
   - Three more forms sent a schema keyword (`(my.program/ns-unmap! :seon.embed/names)`, `:seon.branch/fork-commit`
     twice). No help line named `seon.schema/unregister!`.

   `seon.mine/classify` counts an `ns-unmap!` as progress, so none of these 17 forms is in the grade's waste.
   **Fixed** in help (0b2b0082c), see Fixes.
3. **The heap watch reads non-heap memory as heap: the 8 recorded crossings were not pressure.** All 8 occurrences
   are on the previous process (12196):

   | first at (UTC) | count | last at | level | reading | warning bound | in flight | top self time |
   |---|---|---|---|---|---|---|---|
   | 04:54:34.608 | 1 | 04:54:34 | `:growth` | 1,181 MB | 2,577 MB | none | `seon.cluster.agent/settled`, `seon.schema/contract-references`, `seon.cluster/rehearse-source!` |
   | 04:57:02.705 | 1 | 04:57:02 | `:growth` | 1,405 MB | 2,577 MB | none | `seon.fn.analyzer/invoke-kondo`, `seon.db/transact-call`, `seon.db/pull-logical` |
   | 04:58:18.372 | 6 | 05:12:13 | `:growth` | 1,139 MB | 2,577 MB | none | the same |

   None reached the 60% bound, and none had an evaluation in flight. The readings are inflated: `seon.heap/reading`
   sums every pool in `GcInfo/getMemoryUsageAfterGc`. That includes Metaspace and CodeHeap (about 290 MB on default)
   and, for a `G1 Concurrent GC` notification, which `reading` counts as major, the eden that cycle never collects.

   On default the last concurrent cycle read old gen 597 MB, eden 117 MB and about 290 MB of non-heap, while status
   reported about 600 MB live. So the growth rule fires on metaspace and eden variation during warm-up, and the
   cluster stays `:faulted` with no pressure. This is a heap-watch defect, not pressure.

   **Filed red** (2805d9deb): task `seon.heap/a-heap-reading-counts-non-heap-pools-and-uncollected-eden`, proof
   `seon.heap-test/a-reading-counts-only-the-heap-its-collector-manages`. It is red in the check JVM: 682,720,784
   bytes read against 423,608,392 in the collector's own pools. The fix named there sums only the pools of the
   reporting collector (`GarbageCollectorMXBean/getMemoryPoolNames`).
4. **The deletion chain read for 40 calls and wrote nothing.** It made 60 forms: 33 reads, 16 searches and 11 of
   them `my.program/search` over the retired receipt machinery (`seon.turn/receipt-*`, `seon.turn/row-tx`). Each
   call carried one form (3,347 completion tokens in all). Its plan names no producer, so the task was a poor pick;
   restate it before relaunching.
5. **A test-runner cache holds 35% of `seon.cache` with no hits.** Status after the run carries a
   `:seon.cache/discipline-warning` on `:seon.test.runner/reach-index-by-revision`: 159 MB, a 0% hit rate, 0
   evictions. Not filed: there was no time for a red proof. `errored-receipts` rose from 4 to 6 over the run.
6. **The start request itself cost 1,048 ms and 575 MB** for four single-task chains (request: 1,175 ms, 1,210 MB,
   70,721 calls). I wrote a proof on a fixture, `seon.task-test/a-one-task-chain-costs-what-it-writes` (under 24
   MB), and it came out **green**. The fixture does not reproduce this cost: the thread measure misses the writer's
   thread. bfee2315b committed that test as a red proof by mistake, and this commit removes it. Unfiled.

## Done and merge

No merge. The done rule judged correctly in both green-proof cases (Broken 1). The cap ended each attempt at call 40,
and the end facts were written about 5 s after the last call closed:
- fork: last call 06:00:41.822, test run 06:00:41.971, end 06:00:46.665;
- unres: last call 06:00:08.841, end 06:00:09.740.

## Producer attribution (verified by reading the forms)

| producer | forms / calls | where | verdict | action |
|---|---|---|---|---|
| help named no order for `ns-unmap!` and no verb for a schema (`seon.schema/unregister!`) | 17 forms, about 9 calls | unres 10, fork 6, similar 1 | **real**, uncounted by the grade (`ns-unmap!` is classed progress) | **fixed** 0b2b0082c |
| task plan restated a defect the previous run had already shown (the fork-point fixture and the name-keyed cache) | fork forms 46 to 54 and 58 to 69 | fork | **real**, at the task's plan | plan updated with run 9's and 10's findings (bfee2315b) |
| `:re-read` charged to `my.program/render-source-ai` | 12 forms | all four | **mostly model behavior**: every first read was shown whole (no elision, 103 to 746 tokens) and was still in history; one re-read (`seon.sci.eval/install-row!`, 3,518 tokens) followed the designed reference cut over `:seon.config.context/reference-tokens` 3,000 | none |
| `:redundant-in-ns` charged to `seon.context/current-state` | 7 forms | unres 6, deletion 1 | **partly**: 4 of unres's 11 `in-ns` forms are help step 4's fixture recipe (to `user`, then back); the other 6 re-enter the namespace the agent is already in, as in run 9's `seon.error` chain | not filed: no producer shown (run 9 Broken 5) |
| fixture replay recipe (help step 4) done twice | 3 forms | unres call 11 | model behavior: help says "once" | none |
| task plan named no producer | 40 calls | deletion | **real**, at the task | restate before relaunch |

## New evidence

- **Per form.** Run 9 had none over 100 ms; this run had 8:

  | chain | median ms | max ms | median MB | max MB | forms over 100 ms |
  |---|---|---|---|---|---|
  | fork | 1 | 164 | 1 | 70 | 4 |
  | similar | 1 | 120 | 1 | 122 | 1 |
  | unresolved | 2 | 127 | 6 | 87 | 3 |
  | deletion | 0 | 63 | 1 | 65 | 0 |

  The forms over 100 ms are `seon.test-support/with-database` fixture replays (96 to 164 ms), the probe that help
  step 4 now teaches.

  By verb (forms):
  - `my.program/source`: 131;
  - `my.program/ns-unmap!`: 21;
  - `my.program/search`: 17;
  - `my.program/callers`: 10;
  - `with-database` probes: 29.
- **Cold done judgment.** The first `seon.context/step-verdict` of an attempt on its kept branch allocated about 650
  MB (JVM-mode request 665 MB, 268 ms). Warm it was 6.4 ms and 7 MB, with 180 `seon.cluster.registry/commit-step`
  calls inside it: the fork-point walk this run's first task was about.
- **Fresh-agent `seon.schema/register!`.** The first request of `live-run-10-probe` cost 393 ms and 389 MB (117,550
  calls). That is task `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork`'s territory.
- **Heap.** No crossing in process 36207. Old gen was 791 MB after the run, with a pool peak of 812 MB of 4,096.
  Status `heap-live` was 814 MB, and `seon.cache` weighed 451 MB of 512.

## Runs 7 to 10

| measure | run 7 (5 render chains, d76806d39) | run 8 (3 real chains, c87f0fb77) | run 9 (run 8's tasks, d24ad58e7) | run 10 (4 cost/design chains, ab89888ef) |
|---|---|---|---|---|
| merged | 1 of 5 | 0 of 3 | 1 of 3 (plus 1 correct send-back) | 0 of 4 (2 proofs green at the cap) |
| calls per chain | 39 | 40 | 27 (40, 17, 23) | 40 (all capped) |
| wall per call | 2.3 to 2.8 s | 3.8 to 4.6 s | 2.4 to 2.7 s | 1.5 to 3.8 s |
| cached share of prompt | 87% | 22% | 87% | **90%** |
| `in-ns` share of forms | 9.8% (57% repeats) | 2.4% | 10.4% (15 of 17 in one chain) | 7.3% (11 of 22 in one chain, again `seon.error`) |
| agent errors per form | 12% (62 of 501) | 6% (15 of 255) | 12% (19 of 163) | 5% (15 of 302) |
| reads and re-reads | 47% | 44% | 47% | 43% |
| text searches | not counted | 31 | 5 | 17 (11 in the deletion chain) |
| first write (defn tried) | form 49 to 90 | form 20, 27, never | form 36, 35, none needed | form 16, 53, 45, never |
| wasted calls (grade) | 47 | 25 | 19 | 18 (plus 17 refused `ns-unmap!` forms the grade counts as progress) |
| evaluation per chain | 0.7 to 1.7 s, 1.1 to 3.0 GB | 3.8 to 6.5 s, 1.9 to 2.8 GB | 0.4 to 0.7 s, 0.4 to 0.9 GB | 0.6 to 2.1 s, 0.5 to 1.2 GB |
| largest form | 1,207 ms (whole-store `d/q`) | 950 ms (`my.program/source`, cold) | 97 ms | 164 ms (fixture replay) |
| heap | peak 836 MB, no pressure | peak 1,178 MB, no crossing | peak 1,026 MB, no crossing | peak 812 MB, no crossing |

What improved:
- The cache reached its best share, 90%.
- Fewer errors per form.
- The first write came earlier on the two chains that reached a design.
- With the new direct proofs, two of three real cost tasks reached a green proof on their branch.

What did not:
- No attempt ended green. Both green proofs came at call 40, and one of them was the wrong design.
- The calls before the first useful write are still the cost: the fork chain spent forms 46 to 54 rediscovering
  run 9's fixture fact, and unres spent 10 forms on refused unmaps.
- The grade under-counts the run's biggest waste (Broken 2).

## Fixes made

- **0b2b0082c.** `resources/seon/help.md` step 4 names `(seon.schema/unregister! :the.owner/key)` for a schema the
  branch does not keep, and says `ns-unmap!` refuses a definition the branch still calls: redefine its callers first.
  - Verified as `live-run-10-probe`: `register!`, then `unregister!` in a later request; the next request reads no
    `:seon.schema/key` entity.
  - Proof: `seon.bootstrap-test/help-teaches-removing-what-a-branch-does-not-keep`, absent before and green after.
    `seon.bootstrap-test` is green.
- **bfee2315b.** The fork-point and unresolved-symbol plans record what run 10 showed: the name-keyed cache the done
  rule refused, where `fork-of` answers in the fixture, and the leftover helper.

## Tasks filed or updated

- `my.program/a-warm-similar-by-name-costs-seventy-megabytes` and
  `seon.error/an-unresolved-symbol-request-costs-thirty-megabytes`: direct red proofs and today's numbers
  (ab89888ef).
- `seon.heap/a-heap-reading-counts-non-heap-pools-and-uncollected-eden`: new, red proof (2805d9deb).
- `seon.cluster.registry/the-fork-point-is-walked-for-on-every-request`: plan updated (bfee2315b).
- Not filed, because no red proof was written:
  - Broken 2's classifier gap (`seon.mine/classify` counts a refused `ns-unmap!` as progress);
  - Broken 5 (`:seon.test.runner/reach-index-by-revision` holds 159 MB at 0% hits);
  - Broken 6 (the start request's 575 MB);
  - the repeated `in-ns` in `seon.error` chains.

## Next run

- Rerun the fork-point and unresolved-symbol tasks on HEAD (help and plans now carry run 10's lessons). Both were
  one design step from done.
- Restate the typed-deletion task before relaunching it.
- The kept branches are `:chain-6cacbf210e95`, `:chain-dbd9bb49a299`, `:chain-806cdff35fd2` and
  `:chain-457772aa9fd7`.
- Restart default onto 2805d9deb or later only after the heap-reading fix lands. Until then health stays `:faulted`
  on process 12196's growth occurrences.
