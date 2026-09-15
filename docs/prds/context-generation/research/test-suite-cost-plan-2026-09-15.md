---
type: plan
status: proposed
tags: [test, performance, research]
---

# Test suite cost — 2026-09-15

**Recommendation: stop automatic confirmation on the gate path, then remove repeated fixture acquisition. Keep the real class regressions.** Research only; no tests, runtime mutations, source edits, or default lifecycle operations. Read the testing and data-oriented Clojure skills; inspected the fixture, runner tiers/confirmation/recording, launcher, cache, and the tests below. Source references describe the inspected tree at `bae3d261513337d6e413a72e9c7cba87cd70dd9d`, not necessarily each historical gate snapshot. Later startup changes already landed (`c395610db`, `db7e653ca`); do not count their savings again.

## Measurement boundary and breakdown

Logs under `tmp/orchestrator/gate-results/`; each number below is from an anchored `^bin/test:` PHASE or timestamped END line. END measures the task including fixtures (`src/seon/test/runner.clj:890`), **not a fixture/body split**. Summed task time is parallel worker time, not wall time. These selected gates do not establish the duration of the entire repository suite.

| Gate | Snapshot / slot / dependencies / checkouts / published base, seconds | Coordinator + tests, seconds | Total reported wall seconds | END tasks / summed task seconds |
|---|---:|---:|---:|---:|
| batch-1/named | 2 / 0 / 2 / 8 / 42 | 164 | 218 | 207 / 953.948 |
| batch-1/platform | 3 / 0 / 3 / 10 / 1 | 130 | 147 | 84 / 251.637 |
| batch-2b/named, partial | 2 / 0 / 9 / 5 / 119 | unavailable | ≥135 | 290 / 1087.837; four BEGINs lack END |
| batch-2c/named | 3 / 0 / 3 / 5 / 44 | 748 | 803 | 433 / 1449.061 |

Completed gates account for **1168 reported wall seconds**; all observed ENDs total **3742.483 worker seconds**. Rank distinct tests by their largest observed END, including confirmation; the 25 below sum to **1331.916 seconds**, **47.27%** of the 578-test maximum-per-name total (2817.927 seconds). Counting every occurrence instead, these same 25 names hold **1559.485 / 3742.483 = 41.67%**. Neither denominator is a single full-suite run.

Hypothesis → evidence probe → number → verdict:

* **Every gate republishes for 119 s** → compare PHASE/cache lines → preparation 41,508 / 3 / 118,399 / 44,011 ms, lock wait 0 ms in all four → **refuted**: existing reuse works; cold publication remains expensive. No additional cache is justified.
* **Confirmation is cheap failure classification** → pair CONFIRM launch with BEGIN/END → batch-2c: 41 fresh workers, 584.845 summed startup seconds (11.189–16.179 each), 455.582 task seconds, 357.803 wall seconds from first launch to last END → **refuted**. This is inside the 748 s coordinator phase; the remaining 390.197 s includes initial tests, initialization, exchanges, shutdown and recording, not separately timed here.
* **A fresh physical store is necessary for every slow MCP test** → inspect fixture uses → six top-25 MCP tests request it; `live-runtime-observation…` only checks projection/config carriage (`test/seon/cluster/mcp_test.clj:334`) → **refuted for that test**. The other five exercise store-global blobs and must retain physical isolation. Stop at these three refutations; the proposals below use the evidence already read, with no further runtime probes.

Every unassigned millisecond remains in its enclosing measured bucket. The logs cannot honestly resolve publication CPU versus IO, fixture versus assertion time, or result-recording time. Failed batch-2c also emits **78,581,555 bytes**, including a complete SCI context: `report-event!` forwards assertion failures to unbounded `clojure.test/report` (`src/seon/test/runner.clj:199`; `reference-code/clojure/src/clj/clojure/test.clj:377`). Its time is included above, not separately measured.

## Slowest 25: fixture, classification, evidence

Namespace/path key: **P** `seon.problems-test` → `test/seon/problems_test.clj`; **A** `seon.cluster.agent-test` → `test/seon/cluster/agent_test.clj`; **S** `seon.cluster.source-test` → `test/seon/cluster/source_test.clj`; **C** `seon.config-application-test` → `test/seon/config_application_test.clj`; **M** `seon.cluster.mcp-test` → `test/seon/cluster/mcp_test.clj`; **R** `seon.contracts-plan-test` → `test/seon/contracts_plan_test.clj`; **E** `seon.effect-test` → `test/seon/effect_test.clj`; **T** `seon.turn-test` → `test/seon/turn_test.clj`; **B** `seon.cluster.cohost-boot-test` → `test/seon/cluster/cohost_boot_test.clj`. `DB` means canonical `with-database`, already branching one per-JVM base; `fresh` rebuilds a complete physical memory store (`test/seon/test_support.clj:595`), not a cluster boot. `*` marks confirmation timing.

| ns:line / test | ms | Fixture | Classification — evidence |
|---|---:|---|---|
| P:412 `absent-facts-produce-no-entries` | 244517 | DB + config, 40 trials | GENUINE — asserts present families too; repeated sampling of four-family subsets is unnecessary. |
| P:321 `every-committed-error-fact-shape-is-projectable` | 139445 | DB + config, 60 trials | GENUINE — four attribution combinations retain refs and render; no proven removable class. |
| P:442 `projection-twins-preserve-the-generated-family-structure` | 111205 | DB + config, 24 trials | LOW QUALITY in part — lines 457–476 invoke identical render functions twice and compare them; retain row/count/occurrence assertions by merging. |
| A:1795 `wake-routing-conservation-property` | 82601 | DB + real graphs, 12 trials | GENUINE — creation-and-message transaction reaches a newly armed agent; recorded provider replies. |
| S:511 `latest-test-evidence-survives-rebuilding-from-an-older-base` | 73811 | file store + real publication | GENUINE — verifies commit ancestry, stale-head retry and bounded refusal; memory fixture without commit graph cannot substitute. |
| A:835 `disarm-waits-for-the-turn-proc-stop-transition` | 47339 | DB + withheld executor, 100 agents | HARNESS WASTE — repeats one deterministic withheld-runnable scenario 100 times, not 100 schedules. |
| S:296 `incremental-first-party-publication-retains-complete-scalar-rows` | 44887 | analysis + DB + file publication | GENUINE — sparse admission and published component identity are distinct boundaries. |
| C:148 `applied-values-shape-the-running-system` | 43000 | published root + cluster | GENUINE — real executors, store and port consume boot decisions. |
| C:234 `no-auth-is-consumed-as-the-credential-alternative` | 38198 | published root + cluster | HARNESS WASTE — assertions only inspect `config/effective → ai/targets`; no boot consumer observed. |
| A:720 `n-agent-parallel-turns-property` | 37345 | DB + real graphs, 12 trials | GENUINE — concurrent conservation and per-agent ordering; not duplicate of newborn routing. |
| M:433 `retrievable-artifacts-have-an-identified-no-history-root` | 33948 | fresh DB | GENUINE — executable requery, durable root, history retraction and retrieval refusal. |
| M:363 `oversized-values-share-one-digest-across-storeless-and-stored-modes` | 33632 | fresh DB | GENUINE — cross-mode identity and offset behavior; setup can merge. |
| R:40 `run7-reader-refusal-uses-the-shared-grammar` | 33148 | contracts `with-agent` | HARNESS WASTE — refusal case installs unrelated order schemas/data (`contracts_fixture.clj:60`). |
| M:401 `stored-strings-page-by-character-offset` | 32909 | fresh DB | GENUINE — character paging differs from collection paging; setup can merge. |
| E:318 `background-work-outlives-the-deadline-of-the-turn-that-started-it` | 32642* | DB + launcher + gated handler | GENUINE — real deadline separation; failure time is not evidence of removable work. |
| R:64 `unresolved-symbol-shows-the-correction-without-the-evidence-map` | 32049 | contracts `with-agent` | HARNESS WASTE — unresolved symbol needs no order schema/data. |
| T:425 `virtual-turns-use-the-proc-and-compaction-is-agent-scoped` | 31398 | two DB + real-proc fixtures | GENUINE — second fork proves stable identities; retain both. |
| M:496 `ordinary-value-artifacts-drill-from-the-result-root` | 31255 | fresh DB | GENUINE — nested path starts at result root; setup can merge. |
| E:480 `detached-work-is-bounded-by-its-own-limit-config-then-the-form` | 30355* | DBs + launchers + real deadlines | GENUINE — precedence/refusal cases differ; 5 ms polling (`:465`) is visible but cannot explain 30 s. |
| M:334 `live-runtime-observation-hands-its-projection-to-flow-health` | 30144 | fresh DB | HARNESS WASTE — no blob operations, so private physical store proves nothing extra. |
| M:195 `jvm-exceptions-retain-the-root-location-and-flat-error` | 30010 | fresh DB | GENUINE — root location plus oversized-message retrieval; setup can merge. |
| B:92 `a-second-cluster-boots-under-the-first-cluster-s-instrumentation` | 29977 | published root + two clusters | GENUINE — second boot under first cluster's wrappers is the subject. |
| R:8 `missing-note-id-names-the-key-and-the-docstring-example` | 29735 | contracts `with-agent` | HARNESS WASTE — no order-schema dependency. |
| R:52 `run11-about-refusal-names-the-attribute-and-reference-shape` | 29190 | contracts `with-agent` | HARNESS WASTE — no order-schema dependency. |
| R:20 `installed-contract-refusal-names-the-run5-failing-coordinate` | 29176 | contracts `with-agent` | GENUINE — installs a real function, refuses lazy input, then verifies corrected output. |

The R assertions intentionally check the user-visible refusal grammar; they are not redundant with instrumentation enforcing a contract. No paid-provider cost was established. Do not relabel all absence assertions, exact grammar checks, or slow failing tests as useless.

## Kills, ranked by estimated saving per affected gate

Seconds below are **targets, not measured post-change results**. Worker-time savings are not additive wall-time savings; selection and parallel scheduling determine the latter. Each row names the regression to retain/add and its exact assertion. No new cache.

| Priority / unnecessary work → structural kill | Expected after / saved seconds | Regression and ownership |
|---|---|---|
| 1. Automatically re-executing every red task does not change a red gate → make isolated confirmation an explicit diagnostic operation (`runner.clj:2230,2299`). | Confirmation 357.803 wall → 0 by default; save about 358 on batch-2c, 0 on green gates. | `seon.test.runner-test/default-red-does-not-launch-confirmation`: a real failing selected task remains failed, retains provenance/failure evidence, launches zero confirmation workers, and no task disappears. Own `src/seon/test/runner.clj`, runner tests, `bin/test`. Decision below. |
| 2. Reacquiring DB/config for overlapping family samples → merge P:442 into P:412; enumerate all 16 subsets once, carry each derived value to both renderers; one additional recurrence case checks 1→5 occurrences. Delete the two self-equality assertions outright. | 355.722 worker s / 64 randomized trials → target ≤100 s / 17 fixtures; save ≈256 s. This is a fixture-count estimate, not a throughput promise. | Retain `seon.problems-test/absent-facts-produce-no-entries`, incorporating exact present-family set, HTML/log row counts and recurrence assertions; assert 16 distinct subsets exercised and one call per renderer/value. Delete standalone `projection-twins-preserve-the-generated-family-structure` only after transfer. Own `test/seon/problems_test.clj`; leave P:321's 60 trials intact. |
| 3. Five artifact tests rebuild the same complete physical population → one table-driven artifact lifecycle test with one fresh store and isolated scenario identities; keep root retraction last. Blob isolation remains real. | M:433/363/401/496/195 total 161.754 worker s → target ≤40 s; save ≈122 s. | `seon.cluster.mcp-test/artifact-lifecycle-preserves-identity-paging-and-retraction`: retain every assertion from those five named tests and assert one fresh-store acquisition; all five cases must execute. Remove their standalone deftests only after transfer. Own `test/seon/cluster/mcp_test.clj`. |
| 4. Four grammar cases install schemas/orders they never use → merge R:8/40/52/64 into one canonical DB/SCI fixture without those data; keep R:20 as the sole order-schema installation scenario. SCI already provides private forks (`reference-code/sci/src/sci/core.cljc:345`). | Five tests 153.298 worker s → target ≤60 s total; save ≈93 s. Current fixture acquisition must be remeasured after already-landed initialization fixes. | `seon.contracts-plan-test/refusal-grammar-survives-real-evaluation`: assert four cases executed with their existing error kinds, coordinates, fixes and shown text; zero order-schema installations in this case. Keep `installed-contract-refusal-names-the-run5-failing-coordinate` and corrected Ada/115 output. Own `test/seon/contracts_plan_test.clj`, `test/seon/contracts_fixture.clj`. |
| 5. Identical withheld executor repeated 100 times → one controlled schedule, then release the actual runnable and observe disarm completion (A:783). | 47.339 worker s → target ≤5 s; save ≈42 s. | Retain `seon.cluster.agent-test/disarm-waits-for-the-turn-proc-stop-transition`: exactly one runnable admitted, stop pending before execution, completion after execution; assert one scenario acquisition. Own `test/seon/cluster/agent_test.clj`. |
| 6. Boot for a config transformation → canonical DB + apply/effective/targets (C:234); physical store for flow-health projection → ordinary branch (M:334). | 38.198→≤3 s and 30.144→≤3 s; save ≈35 + 27 worker s. | Keep both named regressions and their positive output assertions; count zero `cluster/start!` calls for no-auth and zero fresh-store acquisitions for observation. Own `test/seon/config_application_test.clj`, `test/seon/cluster/mcp_test.clj`. |
| 7. Eager publication and unused worker copies before knowing fixture needs → derive publication demand from selected tests' call reach; carry one snapshot/manifest into canonical memory fixture, materialize file base only for file/boot subjects. Create serial/confirmation checkout only when admitted (`bin/test:679–682`). | Remove 42–119 s eager publication on selections needing no file base; **net saving unknown** because population moves into workers. Keep 1 s reuse on mixed gates. Copy phase 5–10 s is an upper bound; lazy unused copies target 1–4 s saved. | `seon.test.runner-test/memory-only-selection-does-not-publish`: actual canonical read regression passes armed with zero publisher invocations; a file-backed regression positively requires publication and correct snapshot digest. `unused-workers-own-no-checkout`: no directory without admitted work. Own `bin/test`, `src/seon/test/cache.clj`, runner and fixture owners/tests. Do not replace immutable snapshot isolation or borrow live default facts. |
| 8. Serializing the SCI world into assertion output → pass expected/actual through the existing value renderer/profile at `runner.clj:199`, preserving failure counters and evidence identity; delete the raw report bypass. | 78.6 MB log → target <1 MB for this failure set; seconds saved **unmeasured**, hence last. | `seon.test.runner-test/assertion-report-uses-bounded-value-renderer`: failing comparison containing a large real SCI context increments fail once, reports source/test identity and explicit elision, and obeys the supplied renderer budget. No new clipping seam. Own runner and its tests. |

No deletion is proposed for S, B, T, E, or either routing property: their work currently lacks a measured removable component. Record results through the existing `source/record-results!` owner (`runner.clj:1465`); no evidence supports deleting durable result recording or adding a second writer.

**Owner decisions (before runner production edits):** (1) **recommended, smallest constraint:** default red verdict without confirmation, explicit diagnostic confirmation; ~358 s saved on the observed red gate, one runner option/regression slice, gives up automatic scheduling classification; (2) retain automatic confirmation only for worker-exchange/drift evidence, preserving those diagnostics, saves only the excluded portion of 358 s (unmeasured), adds policy and loses automatic classification for ordinary assertion reds; (3) retain all confirmations, 0 s structural saving, no behavior change, keep the measured cost. For publication: (1) **recommended:** defer row 7's publication change until an in-memory-only selection establishes net cost, 0 immediate savings and no weakened proof; (2) implement lazy file publication with declared fixture requirements, removes eager 42–119 s but adds worker population cost and cross-owner work; (3) retain published-base preparation for every selection, 0 savings, simplest existing equivalence guarantee. Lazy unused checkouts can proceed independently.

**Prevent regrowth:** extend the existing test metadata/selection owner, not a text scanner. `seon.test.runner-test/expensive-fixtures-require-a-declared-observation` queries `:seon.fn/calls` reach to published-root/fresh-store owners and refuses every reaching selected test lacking a nonblank explanation of what an ordinary branch cannot prove; additionally fail an unreasoned direct fixture acquisition. Synthetic unreasoned and reasoned test declarations must respectively refuse/pass under the real program graph. Today the fixture entry points accept an unreasoned call. Own `resources/seon/schemas/seon.test.edn`, `src/seon/test/runner.clj`, `test/seon/test_support.clj`, and their canonical tests. A reason alone does not prove necessity; reviewers must check the asserted observation. Branching is already the dependency mechanism (`reference-code/datahike/src/datahike/versioning.cljc:212`), and isolated stores are necessary for blob-global state (`test_support.clj:649`).

**Ownership / stop:** this lane owns only this note. Final observed protected edits: `src/seon/render.clj`, `src/seon/render/transcript.clj`, `src/seon/render/web.clj`, `src/seon/schedule.clj`, `test/seon/render/retained_test.clj`, `test/seon/render/web_context_test.clj`, `test/seon/render/web_debug_test.clj`, `docs/seon/issues/the-first-debug-page-after-an-adoption-takes-eighteen-seconds.md`; untracked research `debug_page_adoption_probe_2026_09_15.clj`, `debug_page_adoption_sections_2026_09_15.clj`, `debug_page_effects_probe_2026_09_15.clj` in this directory. Recheck ownership before implementation. Initial test/reaching/schema edits had landed by the final status observation; no foreign files changed here. The log-output defect is recorded here to honor the single-note deliverable. No gate requested or run.

Evidence fingerprints (bytes; SHA-256): batch-1/named `63954; 736a904343bd8d3ec0dbd4e5e49e42b82e4b629ceeeee22f084a0cce677bb6a3`; batch-1/platform `66889; f77aa95ad22e361169c7ba9dc27262db7ddd7202026a4327120aa4a9f6da212c`; batch-2b/named `90335; 57d7629e7bbd2de5d241e18a061846f685008a5283f9b495dd938de6874b92bf`; batch-2c/named `78581555; 753bc9ab55a668958a943afbe6cecbbc57a220e06c2bc28a17ceb88050821068`. Reproduce ranking by parsing anchored timestamped `END worker=… elapsed-ms=… task=…`, grouping on task, taking maximum elapsed-ms, sorting descending, and taking 25; sum all observations separately. Exclude embedded copies of log-like text in failure output.

## Landing

Lane `test-runner-waste`, 2026-09-15. Read this plan and the testing,
data-oriented Clojure, and REPL skills end to end; read AGENTS.md laws
2.3–2.5. Owner approved row 1 option 1, only lazy checkouts from row 7,
row 8, and fixture reason enforcement. Publication demand remains deferred.

Dependency ledger: Clojure assertion reporting/counters are owned by
`reference-code/clojure/src/clj/clojure/test.clj:377`; the runner's capture
and task execution are the existing first-party integration. AI projection
is owned by `src/seon/render/value.clj:411`, not the reporter. The existing
manifest selection traverses call refs at `src/seon/test/selection.clj:134`;
canonical fixture branch custody remains in `test/seon/test_support.clj`.

Row 1: normal stages return original task results without confirmation.
`bin/test --confirm ns/failing-test ...` selects only named tests and runs
their tasks in fresh isolated workers; unknown test names refuse. The
diagnostic does not rerun the platform tier or broaden to the namespace.
Regression: `seon.test.runner-test/default-red-does-not-launch-confirmation`.
Hot-reloaded JVM REPL probe: original red value unchanged, fail count 1,
confirmation launches 0. `bash -n bin/test` and `git diff --check` passed.
No test JVM launched: orchestrator-only assignment takes precedence over
the ordinary lane gate. Namespaces submitted in
`tmp/orchestrator/gate-requests/test-runner-waste.txt`.

Read today's `batch-1/fixtures-events.md` (207 tests, 6191 assertions,
0 failures/errors) and `batch-2c/phases.txt` (5 s checkouts, 44 s publication,
748 s coordinator/tests). These are baseline evidence, not post-change gates.
Preserved foreign edits, including projection carriage in test_support.clj.

Row 7 (checkout half): launcher no longer creates serial/confirmation
checkouts. Worker admission materializes from the immutable snapshot through
the existing bounded cache copy owner; serial acquisition is delayed until
unresolved or leftover tasks need it. Publication remains eager and unchanged.
Regression `unused-workers-own-no-checkout` observes absent directories before
admission and exact snapshot bytes after serial admission. Hot-reloaded cache
and runner JVM probe returned zero tasks and `:serial-acquired? false`.
The first probe correctly refused an unreloaded cache Var; reloading the
changed dependency before its caller resolved it. Shell syntax and diff checks
passed; the orchestrator gate remains pending.

Row 8: failing expected/actual values enter `seon.render.value/render-ai`
with the runner's acquired profile. Assertion failures no longer enter raw
`clojure.test/report`; counters still increment once. Captured messages use
the same projection. The existing normalized failure-identity calculation
is unchanged. Regression `assertion-report-uses-bounded-value-renderer`
uses a real SCI context with 2000 declared bindings, source/test identity,
counter and evidence assertions, and a supplied 64-token profile. Live JVM
probe rendered that context as 203 characters of explicit elision data;
reporter probe counted exactly one fail and printed `probe.clj:1`.
The first value probe identified the required root-selector key; the final
request supplies `:seon.render.value/root`. No new clipping owner was added.

## Landing (tests)

Lane `slow-tests-merge`, 2026-09-15. Read this plan and
`.agents/skills/clojure-testing/SKILL.md` end to end, plus AGENTS.md §5;
also read the data-oriented Clojure and REPL skills. No test JVM launched:
the orchestrator-only assignment supersedes the ordinary lane gate rule.
Requested namespaces are in `tmp/orchestrator/gate-requests/slow-tests-merge.txt`.

Dependency ledger: canonical `seon.test-support/with-database` branches the
shared memory base (`test/seon/test_support.clj:649`); its private
`with-fresh-database` owns physical isolation (`:595`). SCI's real fork
copies its env atom (`reference-code/sci/src/sci/core.cljc:345`);
`seon.contracts-fixture` uses `support/fork-cluster-ctx`, as does
`test/seon/cluster/agent_test.clj:90`. Assertions run through real
`clojure.test/test-var` (`reference-code/clojure/src/clj/clojure/test.clj:708`)
in default's JVM, with scratch fixture connections, never default's data.
These are in-process REPL probes of loaded test definitions, not isolated
gate results or development-adoption proofs.

### Row 4 — refusal grammar

`contracts_plan_test.clj` transfers all assertions from the four standalone
grammar tests into `refusal-grammar-survives-real-evaluation`. Its four named
cases share one canonical database and real SCI context. Acquisition and
order-installation observers assert one and zero respectively.
`contracts_fixture.clj` separates `with-grammar-agent` and `install-orders!`;
`with-agent` still installs orders for the retained run-5 installation test.
Both tests were loaded and invoked through MCP JVM on default: **52 passed,
0 failed, 0 errors, 2 tests**. The corrected Ada/115 output remains asserted.
Syntax lint: zero errors and warnings in these two files.

### Row 5 — controlled disarm schedule

The retained disarm regression acquires exactly one scenario instead of 100
identical schedules. It asserts one admitted runnable, no premature stop
acknowledgement, pending disarm before execution, and completed disarm after
running the actual queued runnable. The original bounded wait remains in
`finally`, so failure still releases the proc. MCP JVM scratch-fixture probe:
**5 passed, 0 failed, 0 errors, 1 test**. Syntax lint: zero errors/warnings.

### Row 2 — exhaustive family subsets

`absent-facts-produce-no-entries` now enumerates all 16 distinct subsets once,
then acquires one additional recurrence scenario and observes 1→5 occurrences.
All membership, schema, HTML/log row-count, Hiccup, signature-count and
occurrence assertions moved from the deleted standalone projection test;
only its two self-equality assertions were dropped. Empty log output counts
zero nonblank rows. The test observes **17 acquisitions**, **18 derived
values**, and exactly one call to each renderer for each value. The separate
60-trial committed-error property is byte-identical to the entering HEAD.

Live verification boundary: the unchanged `found` helper sees loaded Vars
absent from the fixture's sealed source population, including
`seon.db/carry-connection-projection-state!`, the new render evidence helpers,
`seon.test/prepare-tests!` and the runner confirmation helpers. The direct
REPL invocation reported **110 pass / 74 fail / 0 error**; a one-fixture
probe positively observed those seven `:seon.problems/stale-vars` entries
before committing any family fact. No production or foreign session was
changed to remove them. The committed
[probe script](slow_tests_merge_probe_2026_09_15.clj) removes only that
separately observed family at the private test-helper boundary for diagnosis:
**184 pass / 0 fail / 0 error, 46,738 ms**. This verifies the reshaped family
logic and counters, not a green isolated gate. The regression itself retains
its exact-family check. Syntax lint has zero errors; two pre-existing warnings
remain at `dead` and the unrelated `derive` binding.

MCP repeatedly reported `session-lost` although the PID and probe Vars
survived. Reconnecting retrieved completed results; no default lifecycle
operation was issued. This matches the already-recorded boundary in
`p1-ambient-state-2026-09-15.md` (commit `40bdd357c`).
