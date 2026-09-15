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

In-process verification after the owner's REPL-loop instruction:
all calls below use `(seon.test/run #'<qualified-test>
(seon.operator/connection "default"))`, with the existing
`seon.test/with-test-loader` loading test source. No test JVM was launched.

| Exact test | UTC / result entity | Pass / fail / error | Boundary |
|---|---|---|---|
| `seon.test.runner-test/default-red-does-not-launch-confirmation` | 22:17:24 / 64392 | 8 / 0 / 0 | Hot-reloaded runner |
| `seon.test.runner-test/unused-workers-own-no-checkout` | 22:17:42 / 64395 | 5 / 0 / 2 | Relative copy paths failed after changing child directory |
| `seon.test.runner-test/unused-workers-own-no-checkout` | 22:18:16 / 64399 | 7 / 0 / 0 | New canonical-path copy form evaluated before editing |
| `seon.test.runner-test/unused-workers-own-no-checkout` | 22:18:28 / 64402 | 7 / 0 / 0 | Persisted cache definition reloaded |
| `seon.test.runner-test/assertion-report-uses-bounded-value-renderer` | 22:17:44 / 64397 | 9 / 0 / 0 | Hot-reloaded runner |

Row 7 follow-up: canonicalize copy source/destination before setting the
child's directory. This fixes relative snapshot requests using the existing
copy owner. No scratch directory remains after the regression.

### Regrowth and final in-process verification

`resources/seon/schemas/seon.test.edn` declares
`:seon.test/fixture-observation`. Runner selection uses the existing manifest
reachability owner and Var/namespace marker owner. Published-root reach is
unconditional. Static fresh-store demand combines call reach with existing
`:seon.fn/keywords` request facts: blindly traversing `with-database`'s optional
fresh-store dispatch would incorrectly require reasons for ordinary branches.
Actual fresh-store and published-root acquisition always checks the reason,
including direct calls without a running test. Direct callers can supply the
same key in an options map. Existing fixture arities remain available.
This metadata is checked from loaded Var/ns declarations like platform/long
markers; no source text scanner or new metadata index was added.

Live manifest probe: 5895 rows, 54 demanding tests. Legacy demanding tests
without reasons now refuse; their owners must declare the actual observation,
not add blanket exemptions. Only fixture entry points changed in test_support;
the other lane's projection changes had landed before this slice's commit.

The first regrowth run (22:18:42, entity 64403) recorded 0/0/1 before its body:
canonical population at `seon.cluster/accrete-schema-population!` →
`seon.db/transact!` refused a missing carried projection. Protected owners were
not changed. This regression needs analyzed program facts, not a database
fixture: the revised test invokes production `seon.fn/build-artifact` on the
real fixture owner and synthetic reasoned/unreasoned declarations, then uses
the ordinary runner selection. It never acquires a physical store.

Exact invocation for each row is `(seon.test/run #'<test>
(seon.operator/connection "default"))`:

| Test | UTC / result entity | Pass / fail / error |
|---|---|---|
| `seon.test.runner-test/expensive-fixtures-require-a-declared-observation` | 22:19:54 / 64415, proposed form | 13 / 0 / 0 |
| `seon.test.runner-test/expensive-fixtures-require-a-declared-observation` | 22:20:10 / 64417, persisted/reloaded | 13 / 0 / 0 |
| `seon.test.runner-test/expensive-fixtures-require-a-declared-observation` | 22:31:40 / 64457 | 13 / 0 / 0 |
| `seon.test.runner-test/assertion-report-uses-bounded-value-renderer` | 22:21:10 / 64435, proposed error reporter | 9 / 0 / 0 |
| `seon.test-runner-test/repeated-identical-errors-have-one-whole-face` | 22:21:28 / 64437 | 7 / 0 / 0 |
| `seon.test.runner-test/assertion-report-uses-bounded-value-renderer` | 22:21:30 / 64438, persisted/reloaded | 9 / 0 / 0 |
| `seon.test.runner-test/default-red-does-not-launch-confirmation` | 22:31:06 / 64447, proposed diagnostic | 8 / 0 / 0 |
| `seon.test.runner-test/default-red-does-not-launch-confirmation` | 22:31:22 / 64451, persisted/reloaded | 8 / 0 / 0 |
| `seon.test-runner-test/unlaunchable-confirmation-worker-does-not-suppress-the-tally` | 22:31:23 / 64452 | 9 / 0 / 0 |

Row 8 follow-up also renders Throwable actual values through the same profile,
retaining signature deduplication. Row 1 follow-up uses the existing bounded
confirmation scheduler for explicit diagnostics and records launch failure as
one attributed error per named test. A live launch-refusal probe retained its
task, error count 1, failure message and 64-character evidence identity.
One proposed-form probe at 22:23:12 recorded 6/2/0 because MCP reader-time `::`
keywords resolved in the wrong namespace. Re-evaluation with explicit runner
keywords corrected that probe; the persisted forms use their normal ns reader.

Verification is hot-reloaded JVM evidence, not proof of converged development
adoption. The publication log observed source-change-during-adoption while
other lanes edited; the orchestrator's batched gate remains the final proof.
No test JVM, worktree, or background process was launched. Gate request:
`tmp/orchestrator/gate-requests/test-runner-waste.txt` contains
`seon.test.runner-test` and `seon.test-runner-test`.

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

### Row 3 — one physical artifact store

Five former standalone tests are private scenario functions in the table-driven
`artifact-lifecycle-preserves-identity-paging-and-retraction`. All **38 existing
assertions** remain; two additional assertions require all five scenarios in
order and exactly one fresh-store acquisition. Scenario cluster names and
vector contents are distinct. Retraction is last. Its history query binds the
retracted digest, preserving the no-history assertion while other scenarios'
roots remain in the shared store. A fixture-observation metadata declaration
explains why branch isolation cannot isolate store-global blobs, compatible
with the concurrently implemented fixture-admission rule.

The direct regression fails before its first assertion at
`cluster/accrete-schema-population!` → `db/transact!` with
`:seon.schema/missing-projection` during fresh physical-store population.
The committed diagnostic temporarily attaches the canonical fixture's
projection state to the new scratch connection before calling the unchanged
real population owner. The real fresh-store acquisition and all scenario
operations still run: **40 pass / 0 fail / 0 error**. Final distinct-value
probe: **45,447 ms** (earlier same-vector probe: 55,373 ms). These are diagnostic
worker times, not gate savings. No protected source/fixture file was changed.

The required construction-time carriage is illustrated by this unapplied
production diff; the diagnostic proves the missing input, not this complete
boot integration. The population owner should reuse a handed bootstrap
projection where available rather than build a second projection:

```diff
--- a/src/seon/cluster.clj
+++ b/src/seon/cluster.clj
@@ populate-source!
-  (let [forms (schema.edn/packaged-forms)]
-    (schema/call-with-forms
-     forms
+  (let [forms (schema.edn/packaged-forms)
+        projection (schema/build-projection forms)]
+    (db/carry-connection-projection-state!
+     connection (sci.eval/projection-state @connection projection))
+    (schema/call-with-projection
+     projection
```

The initial foreign MCP edits (database projection carriage and its existing
config regression) landed independently before this lane's commit. They were
preserved; this slice changes only the artifact cases and their fixture count.

### Row 6 — canonical branches for pure consumers

The no-auth regression now applies its manifest on the canonical database,
then calls `config/effective` and `ai/targets`, retaining both output assertions
and observing zero `cluster/start!` calls. Its obsolete long-test marker is
removed. The flow-health regression uses an ordinary canonical branch and
retains all three output assertions plus zero fresh-store acquisitions.
Direct MCP JVM probes: **7 pass / 0 fail / 0 error, 2 tests**. The C:148 real
boot consumer test remains unchanged. Syntax lint: no errors; the pre-existing
C:148 `name` shadow warning remains.

### Recorded in-process runs after the owner's additional REPL instruction

The owner added the `seon.test/run` requirement after the earlier direct probes
and the row 2/4/5 commits. Each new definition had been loaded into default's
JVM and exercised on real scratch data; those earlier direct deftest calls
were **not** recorded `seon.test/run` calls. After the additional instruction,
the following exact forms were run (each with explicit default custody):

```clojure
(seon.test/run #'seon.problems-test/absent-facts-produce-no-entries (seon.operator/connection "default"))
(seon.test/run #'seon.cluster.mcp-test/artifact-lifecycle-preserves-identity-paging-and-retraction (seon.operator/connection "default"))
(seon.test/run #'seon.contracts-plan-test/refusal-grammar-survives-real-evaluation (seon.operator/connection "default"))
(seon.test/run #'seon.contracts-plan-test/installed-contract-refusal-names-the-run5-failing-coordinate (seon.operator/connection "default"))
(seon.test/run #'seon.cluster.agent-test/disarm-waits-for-the-turn-proc-stop-transition (seon.operator/connection "default"))
(seon.test/run #'seon.config-application-test/no-auth-is-consumed-as-the-credential-alternative (seon.operator/connection "default"))
(seon.test/run #'seon.cluster.mcp-test/live-runtime-observation-hands-its-projection-to-flow-health (seon.operator/connection "default"))
```

| Slice / test | Recorded run entity | Pass / fail / error |
|---|---:|---|
| Row 2 | 64405 | 0 / 0 / 1 |
| Row 3 | 64401 | 0 / 0 / 1 |
| Row 4 grammar | 64393 | 0 / 0 / 1 |
| Row 4 installation | 64407 | 0 / 0 / 1 |
| Row 5 | 64406 | 0 / 0 / 1 |
| Row 6 config | 64408 | 0 / 0 / 1 |
| Row 6 observation | 64409 | 0 / 0 / 1 |

Every recorded result fails before assertions at the same protected boundary:
`seon.cluster/accrete-schema-population!` (`cluster.clj:1322`) →
`seon.db/transact!`, missing the carried projection during canonical population.
The complete returned result maps were saved and read, including their error
messages and evidence references; no green recorded result is claimed.
Result recording itself succeeds. The stronger recorded path therefore has
an unresolved fixture-population boundary despite the earlier direct probes.
No claim is made that the two acquisition paths have identical live state.
The row-3 diagnostic and unapplied construction-time diff above document the
missing input without modifying the protected owners.

Before the final row-6 file edit, the complete proposed observation deftest
was evaluated through MCP with `eval` in `seon.cluster.mcp-test`, then its
recorded regression was run. The edit was submitted through `apply_patch`,
queuing publication `2f05c93e-97b2-4321-bb54-8d00a5eaa9aa`. An explicit
`bin/seon init --dev default --changed` request includes all six owned test
paths so the earlier shell edits also enter the development publication.

Final publication observation after session restart: the hook reports
convergence at `6aa9c539-7bd7-5f86-9435-e1e8fd5d54dc`; a live query of default's
`:seon.source/commit-id` equals `seon.cluster.source/current` at that exact ID.
The publication's reaching check reports unavailable at canonical fixture
preparation. The explicit operator shell is no longer running.

Post-publication row-6 reruns used the exact `seon.test/run` forms above:
no-auth **3 pass / 0 fail / 0 error**, recorded run **64445**; flow-health
**0 pass / 0 fail / 1 error**, recorded run **64443**. The latter's complete
returned value now names `seon.program/base-context-injected-symbols`
(`program.cljc:21`) → `schema/declaration-population`, missing projection,
before assertions. This is the final observed foreign boundary; the earlier
population-transaction failure is historical evidence, not the current
attribution for that run. No production repair or foreign session operation
was attempted. The orchestrator's isolated gate remains outstanding.

All rows 2–6 are implemented. Path-limited slices: row 4 `e3af34340`, row 5
`59bf0ed7e`, row 2 `ef5adf54f`, row 3 `fc90bb972`, and the final row-6 commit
containing this note. Owned test paths are exactly the six assigned paths;
the only additional committed files are this landing note and its diagnostic
probe script. Gate request includes `seon.contracts-install-test` because it
also consumes the shared contracts fixture. No test JVM, paid provider call,
production edit, default restart, or scratch worktree was used.

### Batch 5 correction — render inputs on the contracts fixture

Read `tmp/orchestrator/gate-results/batch-5/slow-tests-merge.md` end to end.
Five of six namespaces passed; the grammar regression had 8 failures among
27 assertions because shown text was raw error-map EDN. The request omitted
both the agent render profile and the context's schema projection.
`evaluate-for-install` already delegates to `seon.sci.eval/evaluate`
(`src/seon/sci/eval.clj:2551`); preserving it retains candidate installation
semantics. The fixture request now carries the profile, projection and
`db/db` database value into that same evaluation-and-value-render path.
No assertion or acquisition-count check was weakened.

Before editing, evaluated the replacement `request` form in default's JVM,
then called `sci-eval/evaluate` on `my.web/no-such-fetch` in a real canonical
scratch fixture. Complete shown text was:

> seon.sci.eval/evaluate refused source at []: expected a resolvable symbol (:symbol), got an unresolved symbol my.web/no-such-fetch. Fix: Define or require this symbol. Example: No docstring example is available.

Exact in-process regression before editing:
`(seon.test/run #'seon.contracts-plan-test/refusal-grammar-survives-real-evaluation (seon.operator/connection "default"))`
→ **27 pass / 0 fail / 0 error**, run **64486**, 6,717 ms MCP envelope.
After `apply_patch` and loading the saved fixture definition, the same form
→ **27 pass / 0 fail / 0 error**, run **64487**, 8,601 ms including load.
These replace the earlier direct-probe confidence with recorded green results.
Hook publication queued as `4701da3b-e79d-49c1-9727-155a7f644a71`.
Kondo: zero errors/warnings. Hook docstring lint reports the five pre-existing
undocumented fixture functions; no production file changed.

Measured batch-5 worker totals (not claimed wall-time savings): problems-test
15 tests / **221,132 ms**, mcp-test 11 / **54,621 ms**, agent-test 22 /
**72,145 ms**, config-application-test 4 / **27,098 ms**. The report's complete
55-test sum is 392,561 ms. Gate request is narrowed to only
`seon.contracts-plan-test`; no test JVM was launched by this lane.


### Batch 6 correction — existing fixture observations

Read the named plan and AGENTS.md authority end to end; refreshed the
clojure-testing skill. Batch 6 stopped in SELECT before any test ran and
launched **zero confirmation JVMs**. The regrowth landing was incomplete:
enforcement arrived without declarations for existing tests.

The checker's own full-graph query found **54** expensive-fixture tests,
**52** without reasons. Added individual `:seon.test/fixture-observation`
declarations at those 52 deftests in 20 files. The row-6 HARNESS WASTE cases
already use ordinary branches and are absent from this set; neither needs a
pending exception. No checker exemption or assertion weakening was added.

Before editing, evaluated the 52 proposed metadata declarations in default's
JVM and called the checker on the 54 reaching Vars: zero refusals. Ran
`(seon.test/run #'seon.test.runner-test/expensive-fixtures-require-a-declared-observation (seon.operator/connection "default"))`:
**13/0/0**, entity **64561**. After editing and reloading saved namespaces,
the same regression returned **13/0/0**, entity **64564**. Hook publication
queued as `441bba28-b1ca-4689-bd47-5be476f23c6c`.

The full-selection checker passed for **1,651** tests, then **1,652** after a
concurrent test addition; both contained **54** expensive tests and **zero
refusals**. A stale loaded web-context namespace initially lacked a new Var;
reloading its current file resolved it without editing that lane.
Reproducible query:
[test-runner-waste-fixture-selection-2026-09-15.clj](test-runner-waste-fixture-selection-2026-09-15.clj).

Every row below used exactly `(seon.test/run #'<test> (seon.operator/connection "default"))`
through MCP JVM evaluation. Complete results were read and saved in
[test-runner-waste-batch-6-in-process-2026-09-15.edn](test-runner-waste-batch-6-in-process-2026-09-15.edn).
Across all four batch-6 namespaces: **50 passing tests, one bounded error**.
Cache reuse exceeded the live API's 20-second completion bound before any
assertion; [the issue](../../../seon/issues/cache-reuse-regression-exceeds-live-test-bound.md)
records the boundary without claiming a cache defect.

Three tests remain for the orchestrator because they launch child JVMs:
`seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`,
`seon.test-runner-test/fast-selected-paths-exclude-a-broken-foreign-file`, and
`seon.test-runner-test/liveness-dump-includes-coordinator-and-worker-virtual-threads`.
Other process regressions used existing Python/shell fixtures, including fake
Clojure launchers. No test JVM, default restart, or foreign session operation
occurred. Foreign `test/seon/sci/eval_test.clj` edits and untracked `build/`
are excluded. Markdown-hook validation reports pre-existing stale gitlink
citations in `agents-md-audit-2026-09-15.md`; that file was not edited.
Gate request now names all four batch-6 namespaces.

| In-process test | Run entity | Pass / fail / error |
|---|---:|---|
| `seon.test.runner-test/assertion-report-uses-bounded-value-renderer` | 64578 | 9 / 0 / 0 |
| `seon.test.runner-test/default-red-does-not-launch-confirmation` | 64579 | 8 / 0 / 0 |
| `seon.test.runner-test/expensive-fixtures-require-a-declared-observation` | 64580 | 13 / 0 / 0 |
| `seon.test.runner-test/initialization-acquires-one-projection` | 64581 | 8 / 0 / 0 |
| `seon.test.runner-test/unused-workers-own-no-checkout` | 64582 | 7 / 0 / 0 |
| `seon.test-runner-test/a-confirmation-loads-the-pool-workers-world` | 64583 | 2 / 0 / 0 |
| `seon.test-runner-test/a-dead-workers-task-is-never-classified-parallel-only` | 64584 | 14 / 0 / 0 |
| `seon.test-runner-test/a-fresh-run-root-is-claimed-before-population-and-sweep` | 64585 | 14 / 0 / 0 |
| `seon.test-runner-test/a-task-that-changes-worker-global-state-is-named-as-the-leaker` | 64586 | 9 / 0 / 0 |
| `seon.test-runner-test/a-worker-dying-during-re-arm-names-the-re-arm` | 64587 | 7 / 0 / 0 |
| `seon.test-runner-test/a-worker-rearms-only-when-a-task-stripped-its-contracts` | 64588 | 4 / 0 / 0 |
| `seon.test-runner-test/arming-refuses-when-a-program-contract-carries-no-wrapper` | 64589 | 6 / 0 / 0 |
| `seon.test-runner-test/assertionless-test-is-an-attributed-failure` | 64591 | 2 / 0 / 0 |
| `seon.test-runner-test/boot-tests-have-no-namespace-wide-execution-shape` | 64592 | 3 / 0 / 0 |
| `seon.test-runner-test/captures-counts-and-failure-identities-per-test` | 64593 | 4 / 0 / 0 |
| `seon.test-runner-test/checked-write-failure-is-one-attributed-task-result` | 64594 | 5 / 0 / 0 |
| `seon.test-runner-test/consecutive-cache-invocations-reuse-the-published-base` | 64675 | 0 / 0 / 1 |
| `seon.test-runner-test/coordinator-uses-the-prepared-worker-count` | 64676 | 4 / 0 / 0 |
| `seon.test-runner-test/dependency-configuration-excludes-first-party-source` | 64677 | 4 / 0 / 0 |
| `seon.test-runner-test/dependency-source-digest-does-not-name-the-checkout` | 64678 | 3 / 0 / 0 |
| `seon.test-runner-test/exit-before-readiness-is-one-attributed-terminal-value` | 64679 | 5 / 0 / 0 |
| `seon.test-runner-test/explicit-result-root-directs-bare-gate-evidence` | 64680 | 2 / 0 / 0 |
| `seon.test-runner-test/fast-and-worker-arm-the-complete-program-contract-set` | 64681 | 4 / 0 / 0 |
| `seon.test-runner-test/interrupted-launcher-awaits-its-runner-before-retaining-the-root` | 64682 | 10 / 0 / 0 |
| `seon.test-runner-test/isolated-confirmations-overlap-with-bounded-parallelism` | 64683 | 4 / 0 / 0 |
| `seon.test-runner-test/kill-after-command-acceptance-is-one-attributed-task-result` | 64684 | 7 / 0 / 0 |
| `seon.test-runner-test/live-worker-exceeding-its-bound-is-one-attributed-task-result` | 64686 | 6 / 0 / 0 |
| `seon.test-runner-test/ordinary-worker-reply-is-one-attributed-terminal-value` | 64687 | 3 / 0 / 0 |
| `seon.test-runner-test/persistent-recording-failure-refuses-a-successful-gate` | 64688 | 10 / 0 / 0 |
| `seon.test-runner-test/published-base-retention-preserves-live-users-and-symlink-targets` | 64689 | 11 / 0 / 0 |
| `seon.test-runner-test/repeated-identical-errors-have-one-whole-face` | 64690 | 7 / 0 / 0 |
| `seon.test-runner-test/result-facts-live-on-the-test-row-and-reruns-replace-them` | 64691 | 8 / 0 / 0 |
| `seon.test-runner-test/result-recording-is-total-under-concurrent-test-retraction` | 64692 | 3 / 0 / 0 |
| `seon.test-runner-test/root-owning-tasks-never-co-run-inside-one-worker-group` | 64693 | 7 / 0 / 0 |
| `seon.test-runner-test/selected-paths-overlay-head-for-preparation-and-every-worker` | 64694 | 8 / 0 / 0 |
| `seon.test-runner-test/snapshot-digest-follows-selected-bytes` | 64695 | 2 / 0 / 0 |
| `seon.test-runner-test/stale-dependency-cache-is-refused-or-selected-and-recorded` | 64696 | 9 / 0 / 0 |
| `seon.test-runner-test/the-agent-fork-callable-returns-the-committed-projection` | 64699 | 1 / 0 / 0 |
| `seon.test-runner-test/the-armed-program-derivation-refuses-absence-instead-of-answering-empty` | 64701 | 8 / 0 / 0 |
| `seon.test-runner-test/the-effectful-sink-refuses-the-default-cluster` | 64704 | 1 / 0 / 0 |
| `seon.test-runner-test/the-gate-runs-under-the-contracts-a-cluster-runs-under` | 64706 | 7 / 0 / 0 |
| `seon.test-runner-test/unlaunchable-confirmation-worker-does-not-suppress-the-tally` | 64707 | 9 / 0 / 0 |
| `seon.test-runner-test/worker-exit-backstop-names-and-fails-a-stuck-child` | 64708 | 6 / 0 / 0 |
| `seon.test-runner-test/worker-root-cleanup-awaits-recorded-child-completion` | 64713 | 10 / 0 / 0 |
| `seon.source-reconciliation-test/source-reconciliation-preserves-identities-and-unrelated-facts` | 64714 | 14 / 0 / 0 |
| `seon.test-reaching-test/a-test-completion-bound-is-recorded-as-a-named-error` | 64717 | 3 / 0 / 0 |
| `seon.test-reaching-test/check-records-provenance-and-verifies-green` | 64721 | 6 / 0 / 0 |
| `seon.test-reaching-test/empty-check-does-not-acquire-run-provenance` | 64724 | 7 / 0 / 0 |
| `seon.test-reaching-test/reaching-is-the-program-graph-relation` | 64726 | 4 / 0 / 0 |
| `seon.test-reaching-test/red-check-names-failure-and-stops-escalation` | 64727 | 5 / 0 / 0 |
| `seon.test-reaching-test/widened-hook-check-reports-and-runs-nothing` | 64728 | 6 / 0 / 0 |

### Batch 7 — fresh acquisition and renderer-selection evidence

Read `tmp/orchestrator/gate-results/batch-7/slow-tests-merge.md` end to end.
The repeated eight failures falsify the prior claim that merely adding the
profile/projection resolved the cold-worker difference. No assertion is removed.

Selection ownership: `render.value/value-node*` calls `render/project-node`;
`render/project-node*` reads the SCI context projection and
`schema-producer` matches schema-declared render pairs. The generic
`:seon.error/value` has no pair. The concrete reader/evaluation error schemas
select `seon.error/render-ai`; instrumentation errors select
`seon.error/instrumentation-prose`. A hypothesis that every refusal selects
the former was falsified by two assertion failures (recorded run 64782,
29 pass / 2 fail / 0 error); the regression now checks the schema-selection
stage, not a hand-maintained renderer list.

`with-grammar-agent` now calls `sci-eval/cluster-ctx` on its canonical branch,
with a database-derived projection and a complete explicitly constructed
cluster environment state. It no longer inherits the fixture base's SCI
Vars through `support/fork-cluster-ctx`. Request profile and projection remain
explicit. `submit` asserts that every error selects its schema renderer before
returning the evaluation to the existing shown-text assertions. Installation
still uses `evaluate-for-install`, which delegates to `evaluate` and preserves
candidate isolation.

Before file edits, both changed function forms were evaluated in MCP JVM.
The same recorded regression form used above returned **31 pass / 0 fail /
0 error**, run **64856** (15,021 ms envelope); fresh-context-only iteration
was **27 / 0 / 0**, run **64773**. The saved file passes kondo with zero
errors/warnings; the hook repeats the existing five missing-docstring warnings.

Independent probes built a new canonical in-memory base with
`#'support/create-base nil`, and a separate private copy of batch 7's exact
published base at `target/test-published-bases/368dc5a6094e5093f70f2183b471a5418dab5b37aa422ebf9adf102682f075da/base`.
Each received a fresh `cluster-ctx`, database projection, explicit environment
and agent profile. Both selected `seon.error/render-ai` at the schema stage
for `my.web/no-such-fetch`, and returned the complete correction grammar
quoted in the previous section. Both bases were released in `finally`.
Initial attempts without handing the construction projection failed at
`seon.program/base-context-injected-symbols`; the corrected probes wrap
construction with `schema/call-with-projection` over the canonical packaged
population. This is explicit input, not a default-cluster schema read.

**Verification limit:** neither independent probe reproduced the raw EDN in
the long-lived host JVM. Fresh SCI acquisition and explicit inputs remove
fixture inheritance, but do not prove hot-loaded host definitions irrelevant.
The next isolated gate must confirm this slice; no cold-worker green or
established host-staleness cause is claimed. The new selection checks will
separate missing schema selection from a later rendering failure in that gate.

After saving, the recorded regression also ran with a newly populated memory
base substituted only for the duration of the probe, then restored and closed:

```clojure
(schema/call-with-projection
 (schema/build-projection (schema.edn/packaged-forms))
 (fn []
   (let [base (#'support/create-base nil)]
     (try
       (with-redefs-fn {#'support/database-base (delay base)}
         #(seon.test/run
           #'seon.contracts-plan-test/refusal-grammar-survives-real-evaluation
           (seon.operator/connection "default")))
       (finally (#'support/close-base! base))))))
```

Result: **31 pass / 0 fail / 0 error**, recorded run **64890**. Default's
connection records the result only; evaluation uses the newly populated
canonical memory base and fresh SCI context. All probe futures completed.
Publication `d89a9911-55af-4fc8-af48-3e52681c82e7` was queued; no convergence
result was available at this checkpoint. The saved-file proof is an explicit
JVM reload, not an asserted adoption proof. Gate request contains only
`seon.contracts-plan-test`. No production/protected file or test JVM changed.
