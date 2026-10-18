---
title: "live run 8: three DeepSeek chains on real request-cost and red-test tasks, observed in JVM mode"
type: evidence
status: "run complete 2026-10-18: 0 of 3 merged, all capped at 40 calls with the proof red; default never crossed a heap bound (old gen peak 1,178 MB of 4,096); help, two task proofs and five tasks committed"
---

# Live run 8: three chains, attempt 1, observed in JVM mode

Lane live-run-8 (Opus). Default JVM pid 33264, restarted by root onto c87f0fb77 at 02:55:34 UTC (ready 21.7 s) so the
chains could see this lane's new proofs. One root act: one SCI request at 02:56:28 UTC with three `seon.task/chain!`
calls, template `seon.step.implement/template`, plan and proofs from the filed task on main, chain config
`{:seon.config.ai/no-provider :seon.config/absent :seon.config.run/max-episode-runs 40}`, model `deepseek-flash`
(main's effective `:seon.config.ai/model`), `:seon.config.agent/max-running` 4. Everything else was JVM-mode reads:
`seon.competency/grade`, `seon.mine/classify`, `d/pull` and `d/q` on the kept branches, `seon.profile/pain`,
MemoryPoolMXBean. Hand acts on the agents: 0. All three ended by 02:59:41 UTC, about three minutes after the start.

The platform rule of 09a7eaa1f was in force: a branch interprets only its own definitions and the test namespaces
calling them, and compiled `src` callers are untouched. Chains that change `src` functions leave the tests that reach
those functions only through compiled code to the merge's platform run. This run shows the other side of that rule:
a proof that reaches its subject only through a compiled caller can never turn green on a branch (Broken 1).

## Task selection (why three, not four)

Screened at fd373b0c1 with `bin/seon check --at HEAD` (runs `dbe26b094d48` and `2eca35bafdaa`, about 10 minutes of
check JVM):

- 25 filed tasks name proofs, and only 9 of those proofs existed.
- 4 of those 9 were already green, so the tasks were stale. They were retired in c87f0fb77:
  - `seon.effect/the-effect-ai-render-returns-data-is-red-on-head`;
  - `seon.test/host-exclusions-test-calls-the-retired-arity`;
  - `seon.test.runner/reaching-tests-assert-what-the-runner-answers-not-cache-retention`;
  - `seon.error/an-unresolved-alias-name-offers-its-nearest-core-name`, whose test was merged in 48528db79.
- The red ones sit beside other reds:
  - `seon.cluster.db-flow-test` holds 22 reds at HEAD (84 tests, 8.4 minutes alone), so both `seon.cluster.agent` tasks are unfair under P5;
  - `seon.turn.batch-test` holds 2 reds that are not the task's proofs.
- The preferred defects (fork point walked per request, unresolved-symbol reply cost) had no proof written.

This lane wrote two proofs and committed them red (c87f0fb77, `--accept-red-before`): the fork-point proof red 3
against 42, and the unresolved-symbol proof red with 4,152 `name-tokens` calls and 15.3 MB. Root restarted onto them.

Chains:
- the fork point, task `seon.cluster.registry/the-fork-point-is-walked-for-on-every-request`;
- the unresolved-symbol reply, task `seon.error/an-unresolved-symbol-reply-searches-every-declaration`;
- the root seed, task `seon.cluster/root-seed-declarations-come-from-source-is-red-on-head`, whose test namespace is
  one test.

A third proof was tried and dropped: recording a large `def` as a blob. It came out green because the thread
allocation it measured misses the work on the MCP path, so it could not be trusted.

## Summary

Wall is first call opened to last call closed. Tokens are prompt / cached / completion.

| chain | task | end | calls | wall | tokens p / c / out | forms (in-ns) | first defn tried | wasted calls | agent errors | eval ms / MB | score |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `6451a0172cc8` | fork point walked per request | **capped**, proof red | 40 | 184.6 s | 886,143 / 266,752 / 8,068 | 72 (3) | form 20 | 8 | 8 | 4,168 / 2,293 | 41 |
| `ea90b1a9dc77` | unresolved-symbol reply cost | **capped**, proof red | 40 | 162.4 s | 920,855 / 197,248 / 8,038 | 100 (1) | form 27 | 7 | 3 | 3,832 / 2,751 | 41 |
| `8600111a2d59` | root seed from source | **capped**, proof red, nothing written | 40 | 152.3 s | 930,802 / 141,952 / 9,840 | 83 (2) | never | 10 | 4 | 6,472 / 1,933 | 41 |

Totals:
- 120 calls and 2,737,800 prompt tokens, of which 605,952 were cached (22%);
- 25,946 completion tokens;
- 255 forms, 6 of them `in-ns` (2.4%);
- 25 wasted calls and 15 agent errors;
- 499.3 s of summed wall;
- 14.5 s of evaluation and 6,977 MB allocated.

Classes:
- read 89 and re-read 23;
- search 31;
- probe 41;
- progress 43;
- unresolved 9;
- error 6;
- repeat 7;
- navigation 6.

## Broken things first

1. **A proof that measures through a compiled caller cannot turn green on a branch.** `seon.error-test/an-unresolved-symbol-reply-costs-under-twenty-milliseconds`
   (c87f0fb77) called `seon.error/reply-value`, which is compiled. The agent redefined `seon.error/nearest-names` and
   added `seon.error/tokens-of` on its branch, which is the right fix: forms 74 and 75, and a `seon.cache` keyed
   design at form 89. Every proof run still read 4,158 `name-tokens` calls and 15.5 MB, because the compiled
   `reply-value` calls the compiled `nearest-names`. About a dozen of its probe forms measured `reply-value`, saw no change and
   had no signal why. The cause was this lane's proof, not the agent. The proof now calls `nearest-names` itself
   (829263bac), and the write-up rule for proof authors is below.
2. **The fork-point proof refused a correct design.** It asserted equal `commit-step` counts after 1 and after 40
   commits. The plan offers two designs: hold the commit at the fork, or cache it per fork. The cached one reads cold
   4 and warm 1, so it failed the proof. The proof now asserts no more after 40 than after 1 (829263bac). The agent's
   cache was still wrong: keyed per branch name, it broke the re-fork tests
   `seon.cluster.registry-test/a-re-forked-branch-answers-its-own-fork-commit-as-its-merge-base` and
   `.../a-fork-from-another-branchs-commit-keeps-the-fork-point`, so under P5 that chain would not have merged.
3. **Prompt caching collapsed: 22% cached, against 87% in run 7.** On `ea90`, cached tokens stayed flat at 4,224 or
   6,144 per call for all 40 calls while the prompt grew from 8,258 to 41,596. Only the system prefix caches. Each
   call costs about 4 s of wall, against 2.3 to 2.8 s in run 7. Filed as `seon.ai/the-prompt-cache-holds-only-the-system-prefix`; reproduce first.
4. **`my.program/ns-unmap!` costs about 100 MB per call.** 19 unmaps allocated 2,188 MB, 30% of all agent
   allocation. The largest was 446 MB and 428 ms for `(my.program/ns-unmap! 'seon.cluster.registry/fork-point-of)`.
   Filed as `my.program/ns-unmap-costs-a-hundred-megabytes`.
5. **The miner refuses a whole kept branch.** On a kept branch, `seon.mine/situations` refused `:chain-ea90b1a9dc77`:
   `seon.mine/shown-entry`'s output contract rejected the producer `tokens-of`, an agent's unresolved guess. M2's
   attribution therefore cannot read this run, and the attribution below is by hand from `seon.mine/classify`. Filed
   as `seon.mine/a-cause-producer-from-an-agent-definition-refuses-the-miner`.
6. **DeepSeek tool markup reached the reader.** `[assistant/analysis]` was evaluated as a form ("Unable to resolve
   symbol"). Evaluation `eda4a2044f0f` carries `:seon.cluster.eval/delimiter-repairs` dropping `</｜｜DSML｜｜` three
   times. Filed as `seon.ai/deepseek-tool-markup-reaches-the-reader-as-code`.
7. **An alias seemed to vanish.** On `ea90`, `(require '[seon.cache :as cache])` in `seon.error` was answered with no
   error. 38 forms and several `ns-unmap!` calls later, a `defn` using `cache/lookup` was refused as unresolved. Not
   reproduced. Filed as `my.program/a-repl-alias-is-lost-after-ns-unmap`, to be reproduced first.
8. **Starting three chains cost 1,230 ms and 458 MB** (the request: 1,444 ms and 1,072 MB). That is over one second for
   three forks and three small transactions. Filed as `seon.task/starting-three-chains-costs-a-gigabyte`.
9. **The seed chain never wrote.** 42 reads and 22 text searches went into finding who writes the `my.agents.root`
   namespace row. At form 68 its own comment listed all five failures correctly and named the likely cause (the
   fixture branches from a database that already holds the program), but the cap came first. The task plan now carries
   that lead (`resources/seon/tasks/seon.cluster.edn`).
10. **A boot-time reply carried a render error.** My first JVM read during root's restart (02:55:43) answered "not
    ready" with a `:seon.render/request-error` in its diagnostics ("Rendering requires a carried profile or handed
    projection"). That is noise beside a clear refusal. Minor, not filed.

## Done and merge

None reached done. Every proof stayed red at every run.

Fork point:
- runs at 02:56:30 (1 member), 02:57:50, 02:58:34, 02:58:54 and 02:59:11 (6 members each);
- the reach was the proof, two registry fork tests, `seon.task-test/a-chain-is-linked-tasks-written-in-one-transaction-on-its-branch`,
  `seon.merge-test/the-merge-base-is-the-merge-requests` and `seon.cluster.db-flow-test/a-join-forks-main-after-both-needs-merge`;
- members took 0.14 to 1.6 s each, and `seon.merge-test/the-merge-base-is-the-merge-requests` allocated 461 MB once and 310 MB the second time.

Unresolved-symbol reply: three 1-member runs (02:56:30, 02:58:39, 02:58:59).

Root seed: one run at the start, and nothing after it, because nothing changed. No run was stopped
(`:seon.test.run/stopped` none). P6 held: one test request per step.

## Producer attribution (25 wasted calls, by `seon.competency/grade`'s rule: a call with no read, probe, progress or wait)

| producer | wasted calls | where | action |
|---|---|---|---|
| task plan missing its lead: who writes the `my.agents.root` namespace row | 9 | seed: 8 text searches and 1 `seon.db/q`, the `ns` row hunt | lead added to the task's plan; no provenance verb filed (one chain) |
| help: no verb named for a subject's callers | 4 | fork 1 (`search :seon.cluster.registry/fork-point`, `"fork-point"`), unresolved 3 (`"nearest-names"`, `"digest"`, `"name-tokens"`) | **fixed** in help step 1: `(my.program/callers 'seon.db/q)`, 4 ms and 2 MB against 100 to 400 ms per search |
| re-reading a source already shown (cause not isolated) | 6 | fork 3, unresolved 3 (`reply-value` 3 times) | unattributed; the unresolved chain's re-reads of `reply-value` follow Broken 1 |
| help: the nilable-return refusal not named | 2 | fork: `branch-of` (bare `[:maybe ...]`) and `fork-base-t` (invalid derive) | **fixed** in help Data; the refusal itself was clear and each was repaired in one call |
| help: no way named to reach a branch's store or head record at the REPL | 2 | fork: guesses `seon.context/db`, `seon.context/step`, `seon.store/head-record`, `(seon.db/connect!)` | not fixed (2 calls, one chain) |
| platform: alias lost (Broken 7) | 1 | unresolved | task filed |
| agent's own error, clear reply | 1 | seed: cyclic `require` of `seon.test-support` from `seon.cluster` (the refusal said to require it from `user`) | none |

Outside the wasted-call count:
- The futile work of Broken 1 (about a dozen probe forms on `ea90`) and the 19 scratch-def unmaps are probes and progress by the
  grader's classes, so they are not counted as waste, but they cost 2 GB. Help step 4 now teaches probing in a `let`.
- None of the waste points at the four producers lane context-fix owns: the block's proof source and must-read size,
  `dir`, the partial edit verb, and the agent id in the chain answer. The first block was not measured on its own here.
  The first prompt (system with help, the block's attempt sections, then the turn sections) was 8,655, 8,258 and 7,204
  tokens, and it grew by about 600 to 1,000 tokens a call.

## New evidence

- **Per eval request** (evaluations of agent forms):

  | chain | median ms | max ms | median MB | max MB | forms over 100 ms |
  |---|---|---|---|---|---|
  | fork | 1 | 705 | 0 | 446 | 11 |
  | unresolved | 9 | 376 | 12 | 205 | 11 |
  | seed | 4 | 950 | 0 | 193 | 18 |

  By verb:
  - `my.program/search`: 31 forms, 5.3 s, 1.6 GB;
  - `my.program/source`: 112 forms, 3.7 s, 1.3 GB;
  - `my.program/ns-unmap!`: 19 forms, 2.4 s, 2.2 GB.

  Every `source` read cold in a namespace cost 100 to 950 ms and about 94 to 115 MB (`'seon.cluster/program-restart-test`
  950 ms, `'seon.cluster.agent/ensure-agent-namespace!` 573 ms), and the same read warm cost 0 to 12 ms. No form
  passed one second.
- **`seon.profile/pain`, before (02:56, just after boot) and after (03:01).** The top after:

  | definition | self share | self overrun |
  |---|---|---|
  | `seon.error/nearest-names` | steps 0.474 | steps 53.7×, bytes 116× |
  | `seon.db/vector-query-call` | 51,513 calls | bytes 3.2× |
  | `seon.context/branch-cost` | steps 0.199 | steps 18.7× |
  | `seon.fn/analyzed-artifacts` | steps 0.161 | steps 8.9×, bytes 104× |
  | `seon.db/with` | bytes 0.14 | bytes 710× |

  `seon.fn.analyzer/parse-facts` overran bytes 507× and `seon.test.runner/reach-memberships` steps 82.8×. Before the
  run, the boot-time list was led by `seon.db/commit-database` (bytes 7×) and `seon.db/vector-query-call`.
  `seon.context/branch-cost`, a context producer, is new in the top three.
- **Heap watch.** No crossing during the run: the 3 `seon.heap/observation` errors on record are all from 2026-10-17
  11:56 to 11:58. Old gen was 750 MB at 02:58:17, 969 MB at 02:59:41 and 1,119 MB at 03:01 after the lane's reads,
  with a peak of 1,178 MB of 4,096.

## Comparison with run 7

| measure | run 7 (5 render chains, d76806d39) | run 8 (3 real chains, c87f0fb77) |
|---|---|---|
| merged | 1 of 5 | 0 of 3 |
| calls per chain | 39 | 40 |
| wall per call | 2.3 to 2.8 s | 3.8 to 4.6 s |
| cached share of prompt | 87% | 22% |
| `in-ns` share of forms | 9.8% (57% repeats) | 2.4% |
| agent errors per form | 12% (62 of 501) | 6% (15 of 255) |
| reads and re-reads | 47% | 44% |
| first write (defn tried) | form 49 to 90 | form 20, 27, never |
| evaluation per chain | 0.7 to 1.7 s, 1.1 to 3.0 GB | 3.8 to 6.5 s, 1.9 to 2.8 GB |
| largest form | 1,207 ms (whole-store `d/q`) | 950 ms (`my.program/source`, cold) |
| heap | peak 836 MB, no pressure | peak 1,178 MB, no crossing |

What improved:
- `in-ns` waste is gone; the help step-2 fix holds;
- agent errors halved, and the errors that remain are clear refusals;
- the first `defn` attempt moved earlier, by two to three times;
- the done rule and P6 behaved: one test request per step, and no stopped run.

What got worse:
- prompt caching (Broken 3);
- evaluation cost per chain, driven by `search` and `ns-unmap!`.

The tasks were harder than run 7's render tasks, and two of the three proofs were defective (Broken 1 and 2: this
lane's own proofs), so 0 of 3 says more about the proofs than about the context.

## Rule for proof authors (from Broken 1 and 2)

A task proof must reach its subject from source the branch evaluates: the test calls the function it judges, not a
compiled caller of it. Since 09a7eaa1f a branch's redefinition is invisible through compiled `src` code. A cost proof
must also accept every design the plan offers: assert "no more than", not "equal", when caching is one of the designs.

## Fixes made

- c87f0fb77: wrote two red proofs and retired four stale tasks.
- 829263bac:
  - `resources/seon/help.md` step 1 names `(my.program/callers 'seon.db/q)`;
  - step 4 teaches probing in a `let`;
  - Data names the nilable-return refusal;
  - proof: `seon.bootstrap-test/help-teaches-callers-let-probes-and-no-nilable-return`;
  - the two task proofs revised as above, still red at HEAD (42 against 3; 15,048 calls and 42 MB).

## Tasks filed

- `seon.ai/the-prompt-cache-holds-only-the-system-prefix`
- `my.program/ns-unmap-costs-a-hundred-megabytes`
- `seon.mine/a-cause-producer-from-an-agent-definition-refuses-the-miner`
- `seon.ai/deepseek-tool-markup-reaches-the-reader-as-code`
- `my.program/a-repl-alias-is-lost-after-ns-unmap` (reproduce first)
- `seon.task/starting-three-chains-costs-a-gigabyte`
- The plan of `seon.cluster/root-seed-declarations-come-from-source-is-red-on-head` now carries run 8's lead.

## Next run

Rerun the fork-point and unresolved-symbol tasks on a HEAD holding 829263bac. Both proofs are now reachable from
source, and the help now names callers and `let` probes. Measure the cached share first, because Broken 3 doubles
the wall of every call.

The chains' evidence stays on the kept branches `:chain-6451a0172cc8`, `:chain-ea90b1a9dc77` and `:chain-8600111a2d59`.
