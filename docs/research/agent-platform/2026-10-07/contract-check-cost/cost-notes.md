# Contract check cost

The check statically reads source, qualifies contracts using publication's analyzer, and compiles them against
one packaged projection. This change removes runtime namespace acquisition from checkout predicate inspection
and moves shared static helpers from the database-backed indexer to its existing analyzer.

## Evidence and design

Baseline archive: `b72cb58b34d4e6167375c05c90435e0ed73711e7`, pinned Malli
`43c6dca8d2d8df3162b13725939954807be4a4ed`, clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`.
Read the assignment, CLAUDE, lane rules, file-reader notes, and both named reviewer notes end to end. Loaded
Clojure, REPL, testing skills. The REPL skill's old missing-contract refusal/context claims remain stale;
existing `seon.turn/repl-and-flow-skills-describe-retired-loop-contracts` owns that foreign boundary.

Loop step: compile admission for program changes. No new runner, evaluator, printer, registry, or subsystem.
Smallest alternative first: existing content-keyed projection reuse already exists; one projection is built per
check, not per contract. Loading predicate namespaces is unnecessary for this static compilation question.
Malli `function-schema` (`core.cljc:3130`) compiles schema shape, not predicate behavior; `schema` (`:2596`)
returns compiled inputs and resolves named inputs in the supplied registry. Publication and boot still use real
predicate Vars, and their namespace-load failures remain refusals. This checker already used a stand-in for
checkout declarations unavailable to its older checking JVM. Extend that existing static path to unloaded
checkout namespaces; never use the check's placeholder projection for validating runtime values.

EQUIVALENT: move `qualify-schema-symbols` and `namespace-context`, with their contracts and all callers, into
`seon.fn.analyzer`. Preserve `:seon.fn/analysis-entry` in rename refusals. No runtime indexer dependency is needed
to consume static clause facts. Existing namespace-context tests reach the shared producer.

BEHAVIOR CHANGE: static checkout predicates no longer load their namespace just to compile a contract.
Loaded/supplied bindings remain authoritative; absent declarations remain named findings. Cache read forms by source text through `seon.cache`, including the source key in its weight. A changed source
gives a new answer; the regression changes the same file from present to absent to present. Reader-generated
symbols may now be reused for identical text; these forms are never evaluated, and changed-definition comparison
already normalizes reader-generated symbols. Resolve the distinct predicates once per contract batch and pass
the carried bindings to individual compiles. `schema/with-predicate-functions` remains their one writer.

Costs: namespace loading is proportional to the transitive require closure; predicate inspection to distinct
predicate names and source of their unloaded namespaces; projection construction to schema nodes and references;
reading to selected source length; kondo to analyzed namespace prefixes; contract storage/compilation to contract
nodes and referenced compiled schemas. No cache is claimed to improve the first cold parse.

## Initial measurements

Platform main thread, `ThreadMXBean/getThreadAllocatedBytes`, 488 src/script/test files, 3,398 schema declarations,
3,002 contracts, no armed calls, no store acquisition/commits/bytes. Raw source classpath, fresh JVM:

| Phase | ms | allocated bytes |
|---|---:|---:|
| Require checker | 14,226.388 | 15,701,456,384 |
| Read schema resources | 27.511 | 30,098,408 |
| Resolve predicates | 3,207.481 | 2,875,443,696 |
| Build projection | 314.663 | 195,971,224 |
| Read sources | 350.952 | 390,231,136 |
| Kondo ns analysis | 450.197 | 113,257,600 |
| Stored contracts | 104.235 | 104,619,768 |
| Compile contracts | 84.346 | 62,632,816 |
| Whole warm check | 613.064 | 666,155,560 |

Independent per-predicate run: `seon.cluster/cluster-name?` loads 2,503,681,984 bytes / 2,635.775 ms;
`seon.edit/valid-form-operation?` 270,140,448 bytes / 364.746 ms; `seon.call-preparation/state?`
81,572,304 bytes / 78.676 ms. No inference from class names alone: these are separately measured resolutions.

Existing dependency AOT cache reduces baseline require to 3,437.183 ms / 2,410,377,104 bytes, but predicate
resolution remains 2,119.425 ms / 2,686,131,168 bytes. Cache reuse is reported separately, not credited as this fix.
A first prototype removed runtime lookup for unloaded checkout predicates: 45.281 ms / 70,734,760 bytes,
with the same ten findings. It reparsed predicates per contract and is not the final implementation.

## Health, foreign boundaries, and verification limits

`bin/seon status` and MCP `runtime_status` both ready but faulted: 3 faults, 13 errored receipts, 8 unowned
namespaces, 8 over-bound warnings. `call-with-projection-state` max 82,914 ms (existing `d27179bab996`,
`c4ece4f4eec9`); publication 4,856 ms (`9665c2ed25f0`); adoption 3,725 ms (`5dbb7de36bdb`);
analysis 3,887 ms (`d0022e8fb20b`); index 4,578 ms (`12037fb1e077`, `84fdc131a599`). No recovery attempted.
MCP JVM read-only connection/namespace observation: `[true true]`, 1 ms, `result/e7339b993dd34`.
No uncommitted source enters default. The entrance created a lane branch even in JVM mode; its cleanup is recorded below.

All full src/script/test checks reproduce ten script contract failures, already tracked by
`seon.contracts-compile-test/script-contract-vars-do-not-compile-against-packaged-projection`.
Do not report zero findings for that set. Existing cost tasks:
`seon.schema/contract-check-on-three-files-exceeds-one-second` and
`seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`;
this profile refutes the old reader attribution for today's cold excess.

The archive excludes s2a-code's in-flight web/transcript/schema/test changes. No other lane was messaged or edited.
An initial archive probe missed Datahike Java classes; linked only the existing compiled Java output, then reran.
That setup failure is not a product defect. All numerical evidence comes from completed successful processes.
No tests have run: the assignment reserves tests for root's central list. `bin/lane commit --test` itself runs
those tests unconditionally; clarification is pending before attempting that conflicting instruction.

## Final costs and remaining floor

Fresh JVMs with the SAME existing dependency AOT cache, same platform-thread allocation method and unchanged
baseline corpus (`b72cb58b3`, 488 paths); `probe.clj whole` invokes the public check before any phase probes:

| Operation | Before ms / bytes | After ms / bytes |
|---|---:|---:|
| Require checker | 3,154.736 / 2,409,965,184 | 1,713.495 / 849,398,072 |
| First whole check | 2,806.394 / 3,586,370,280 | 998.525 / 957,829,488 |
| Warm check 1 | 433.179 / 662,852,904 | 276.482 / 370,429,704 |
| Warm check 2 | 397.197 / 662,227,160 | 223.075 / 370,225,480 |
| Warm check 3 | 387.024 / 662,020,712 | 211.608 / 370,082,232 |

Armed calls: 0 throughout. These tools are unarmed in the disposable JVM, as in the reported baseline defect;
no armed-runtime speedup is claimed. Commits/store bytes: 0; no store is opened. Byte figures are allocations on
the calling platform thread, not RSS, live heap or allocations on Malli's parallel validation workers.
Each pair returns the same ten script findings (4 docstring, 6 markdown). Process wall including JVM startup and
loading the value printer AFTER measurement was 7.911 s before / 4.360 s after; it is not the check's wall time.

Final verification uses `git archive 8985125c9b66ceb1126cf1295743a5bc5c260eba` plus exactly the four owned source/test
files. Since the baseline, S2a landed externally: this corpus is 487 files / 3,397 schemas / 2,973 contracts
(2,498 distinct stored contracts). It is a separate final correctness/cost observation, not the same-corpus
before/after pair. Final phase split, dependency cache reused:

| Phase | ms | allocated bytes | Work follows |
|---|---:|---:|---|
| Require checker | 1,879.235 | 849,090,528 | dependency class loading and compilation of uncached Seon namespaces |
| Schema resources | 60.449 | 33,487,360 | resource text and schema nodes |
| Predicate bindings | 88.021 | 92,883,888 | distinct predicate names and unloaded predicate source |
| Projection | 271.668 | 196,201,584 | 3,397 declarations and their reference closure |
| Source reading | 242.181 | 327,810,960 | source text not already read for predicates |
| Kondo | 274.395 | 116,448,584 | namespace prefixes with symbolic contracts |
| Stored contracts | 59.690 | 100,174,592 | qualification, canonicalization, EDN storage round trip |
| Contract compile | 56.174 | 74,003,184 | contract schema nodes, using the one compiled registry |
| Warm whole check | 249.222 | 369,901,320 | source acquisition, analysis, contract storage/compilation; read forms reused |

A separate first-call final check measured 1,072.089 ms / 948,187,656 bytes (487 src/script/test files, 10 findings);
canonical src/test: 1,095.174 ms / 931,878,416 bytes (474 files, **0 findings**), warm 239–277 ms / 358 MB.
The target is **not fully met**: cold operation cost varies around one second, allocation is about 0.95 GB, and
namespace acquisition remains about 1.7–1.9 s / 0.85 GB even with dependency AOT. The measured current-owner floor
is substantial: required native source reads plus whole packaged projection alone account for ~0.52 GB after
predicate reads, and kondo plus canonical contract storage another ~0.22 GB. This is a measured floor of the
retained pipeline, not proof that further producer work is impossible. Do not label startup sub-second or
silently exclude it. No validation or source has been skipped to claim the target.

Read-cache weight calibration: baseline `probe.clj retained`, 10,346,907 source characters, forms held across GC,
heap-used increase **78,341,936 bytes** (7.57 bytes/character; text acquired before the baseline). At 24 bytes per
character, cache weight includes the text key and conservative room for forms, positions and cache overhead;
total corpus charge is ~248 MB under the existing 512 MB process budget. It is an estimate, not an object-layout
proof. Immutable source keys change with edits; failures are not retained by `seon.cache/lookup`.

## Design check and final verification

The static move held against the complete call graph: no source/test/resource reference to the old private
symbols remains. Two prototypes were rejected/refined: selecting fewer kondo prefixes saved only ~1 MB and was
deleted; resolving newly loaded printer predicates changed the second projection key. The checker now directly
requires the schema/Datahike bridge it necessarily uses to build the projection, establishing those bindings
before deriving the key. The observed changed-bindings set is empty. Repeated predicates share one batch binding
map; a redundant full-map merge per contract was removed. No new cache for projection or compiled schemas.

The read-cache edit initially supplied a zero-arity weight function; the disposable probe returned ArityException.
Fixed to the cache owner's one-argument weight function, then reran all checks. This was a prototype mistake,
never installed on default. Likewise, an attempted archive setup used its not-yet-created directory as cwd;
corrected before creating the final archive. Neither failed attempt is counted as measurement evidence.

Final `clj-kondo --lint src script test --cache false --parallel` on that archive: **0 errors, 1,461 warnings,
5,622 ms**, exit 2 for warnings. `git diff --check` on owned source/tests: clean. The full compile check and
canonical compile check above completed. The small public `check` probe answered present → absent → present
as `[]`, `[{:type :contract-compile ::unresolved #{contract.cost.fixture/predicate?}}]`, `[]`, and the fixture
namespace remained unloaded. This is a probe, not a test-runner result.

Root's exact central test list:

- `seon.contracts-compile-test` (whole namespace, including the new
  `checkout-predicates-are-checked-without-loading-and-follow-source-content`).
- `seon.fn-test/static-index-preserves-the-jvm-entity-contract`.
- `seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`.
- `seon.fn-test/a-top-level-require-rename-is-refused-by-name`.

The deliverable probe script was itself executed in the final archive (`whole src test`):
0 findings, cold 1,058.451 ms / 929,452,664 bytes, warm 225–243 ms / 358 MB; namespace loading
2,012.743 ms / 849,424,064 bytes. It reproduces the residual, including the startup overrun.
This script is an ordinary measurement driver, not a test runner. The allocation bean was renamed in the final
driver to avoid shadowing `clojure.core/bean` in scratch output.

After adding the task annotation, the final archive was updated with the exact final test file and both checks
were repeated: lint above; canonical compile **0 findings**, cold 1,038.616 ms / 928,461,280 bytes, warm
221–267 ms / 358 MB. This is the final file version.

The new regression names the existing corpus-cost task with quoted-symbol metadata. This is installed in
`seon.fn/task-links` (current `:seon.task/annotation`), despite the testing skill's older claim that it is still a
target; no hand-typed task identity or new namespace dependency was introduced.

No lane tests or publication/adoption run was started. The latest assignment forbids them, while
`bin/lane commit --test` calls `bin/seon check` and unconditionally runs named tests after adoption
(`bin/lane:249`, `script/seon/operator.clj:1651–1652`). Asked which instruction governs; no answer received.
Therefore **no commit is claimed**, and file claims stay with `contract-check-cost` for root's central proof and
commit. Root should publish/adopt the analyzer move and repeat the named tests before accepting it. Proposed
commit title: `contract-check-cost: keep static contract checks out of runtime namespace loading`.

Owned source/test diff: **+161 / -142 lines, net +19** (src net -4, tests net +23), four files; note and reproducible
probe are additional documentation. No other lane's source or session was changed. Default needs no reset;
after acceptance, root's normal restart installs the moved host functions. Cost tasks above remain open for the
explicit cold residual. No paid provider run, worktree, default stop, reset or prototype reload occurred.

## Cleanup

Read-only root JVM pull with explicit default connection found this lane's branch:
`["contract-check-cost" :agent-d30489efa7da]`, `result/e6659c2b7be31`, 2 ms. Called the existing
`my.agent/retire-branch!` from root JVM with the selected instance's store/routing and explicit default connection:
`{:seon.store/branch :agent-d30489efa7da :my.agent/retired? true}`, `result/ec6b78301076e`, 24 ms, no diagnostics.
No other agent was entered, resumed or retired. The Datahike skill was used at this cleanup boundary.
All owned measurement JVMs exited; owned baseline/candidate/final archives and the predicate fixture directory
were removed without following their cache/dependency symlinks. Raw probe outputs remain in the lane scratch
folder; the durable measurements and reproducible driver are in this notes directory. Git file claims remain
held solely for root's proof/commit handoff.
