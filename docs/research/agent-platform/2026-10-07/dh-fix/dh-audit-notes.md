---
title: Datahike fork review — dh-audit
type: research
date: 2026-10-07
status: blocked
---

# Datahike fork review — dh-audit

**Do not bump Seon's gitlink to this candidate yet.** Review target: `d9121bbb..6f3b61f6`,
`seon/identity-join-first`. Two answer defects remain, and the strict cost/budget claim is not proven.
The landed changes from this review only narrow lint exclusions and correct the cache-size documentation.
The answer repair described below was measured, rejected, and removed. No test runner was started.

Fork commit `ee5ac0bdd3b44547c63bfce5dd81ceb69b134180`: 3 files, +7/-6; source net +1, tests net 0.
Rebased onto origin/seon/identity-join-first (already current), fast-forwarded main, and pushed both branches
atomically. Remote refs verified at that exact commit. Seon's gitlink was not staged or changed in a commit.

## Findings, ranked

1. **New rule-data regression in 84790323.** Rule arguments that happen to look like lookup refs are resolved
   before the rule's use of them is known. A rule returning ordinary vector data instead returns an entity id,
   or throws if the first keyword is not a unique attribute. Scalar inputs and literal rule arguments regress;
   collection input normalization already had this problem. Task:
   `seon.db/rule-arguments-resolve-lookup-shaped-data-as-entities` (`59567e8e05bc`).
2. **Shared history `?added` variables are not equality constraints in fused merges.** Twelve rows instead of
   nine in the exact example below. The value/tx fixes in 77c1228e work, but do not cover the fifth field.
   Task: `seon.db/history-joins-ignore-shared-added-flags` (`411bc7407db3`).
3. **5fcfc6a7 distinguishes id high-water marks, not populations.** Sparse ids, retractions, cardinality-many
   growth and temporal views can share a bucket despite very different sizes. A measured sparse-id example
   still allocates 7.6x more with the cached small-db plan. Existing task:
   `seon.db/query-plans-are-reused-across-database-sizes` (`3523ddcf8a1a`). Documentation corrected; no new
   invalidation/cache mechanism added.
4. **The hot-query improvements do not meet the budget.** Three cold queries still allocate over 10 MB;
   the affected-schema rule allocates about 304 MB for zero answers. There are small allocation increases and
   slower timing samples, so this review does not certify “nothing may be worse.” Details below.
5. **9e229d31 hides arbitrary unresolved symbols within two macro calls.** No actual bad current symbol was
   found, but deliberately inserted misspellings were hidden. Exclusions now name only the macro locals.

## Method and installed/foreign boundaries

Read lane-rules including Budgets, Datahike/Clojure/REPL/testing skills, every file in dh-planner, dh-fix-notes,
all nine commit diffs, and the four commits named by the test-update claim. Read the planner/lowerer/executor,
lookup normalization, macro definitions, clj-kondo exclusion implementation, and Seon's query callers.
No internet claim or uninspected upstream behavior is used as proof.

Initial `bin/seon status` and MCP `runtime_status`: default PID 41301, start 2026-10-07T07:48:14.692Z,
`:faulted`, 18 open faults, 179 errored receipts, 43 unknown tests, 11 over-bound warnings. Existing subjects:
`seon.batch/evaluate-batch` (mean 1.70 s, max 43.706 s; tasks 85796bb75795/53758c92fcb5),
`seon.sci.eval/fork-cluster-ctx` (mean 1.238 s; 00187adf567b), `acquire-program!` (mean 1.422 s;
4a738d4f7138), and `seon.turn/show-row` calling shown-failure with nil (7f5dfb6ad7a2).
Live heap 3,081,610,816 bytes; reach-declarations cache 119,086,336 bytes, zero hits. These are existing
findings, not candidate measurements. The lane SCI probe `(+ 1 2)` in scratch.dh-audit returned 3, 0 ms,
`result/e1d699c067a0e`. No fork code was loaded into default.

All fork probes ran in separately exited JVMs. Archives of d9121bbb and 6f3b61f6 used their pinned deps and
copied compiled Java classes. The initial candidate matrix used an isolated JVM with Seon's classpath and the
clean 6f3b61f6 checkout; the extended matrix was repeated against the archived fork. Experimental code ran
only in the owned archive. Scratch: `/tmp/codex-01a11581/dh-audit`.

The production-store measurement used exactly `cp -c -R /Users/sean/src/seon/data/store <scratch>/store`;
the clone measured 861,240 KiB with du. Default was never opened by a probe JVM. Same frozen clone and same
inputs on both revisions; branch `:cluster-default`, cache size 1, self writer, no transaction calls. No store
reset, default restart, worktree, provider call, Monitor task or poller was used.

Foreign boundary: persist-tasks owns dirty task resources; r-context/r-delete/r-read/r-turn own other notes.
Those files and their sessions were not edited or contacted. Seon's gitlink remains d9121bbb and is unstaged.
The new tasks are durable at the task home through `seon.task/create!`, not hand-authored task files. One update
initially supplied a revision digest instead of the declared task basis and was refused (8 ms); the corrected
call uses `seon.task/task-basis` on the task-home connection. This was a probe-author error.

Own branch retirement exposed the existing `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed`
defect. `(my.agent/retire-branch! {:seon.store/branch :agent-9084cfad176b})` returned only
`{:seon.error/message "run transition refused: admission-closed"}`, without timing or a result handle.
Branch unlink is unverified; no new lane session was opened to work around it. This leaves a possible roster
entry, not an owned JVM. The task already exists, so no duplicate was filed. Foreign working-tree changes
also include src/seon/sci/eval.clj, src/seon/test.clj and test/seon/test/overlay_world_test.clj; none were touched.

## Answer matrix

[Reproducible matrix](dh-audit-probe.clj), derived from the earlier review's fixture/table, adds independent
counterexamples. Both engines receive the identical immutable db value and arguments. Query-result caching
is disabled. Plan cache is cleared for each row. Sizes: 20 and 320 original entities, then a unique identity
moves to entity size+1, one ref retracts and one value changes. Worlds: current, history, as-of the first tx.

| Shapes | Coverage and result at 6f3b61f6 |
|---|---|
| identity/ref joins, unique identity/value, nonunique indexed and unindexed attrs | agree |
| cardinality-many refs, absent identity, empty input, disconnected joins | agree |
| collection/relation bindings, lookup refs in input collections | agree for reference uses |
| predicates, variable comparisons, aggregates, pull in find | agree |
| not, not-join, or, or-join | agree |
| plain rules, bounded recursion, two recursive calls | agree for tested entity uses |
| 24 clause orders of a four-pattern identity/ref join | agree |
| tx/added positions, moved identity, temporal versions | original table agrees; shared-added edge fails |
| scalar/literal/collection lookup-shaped rule data | fails; see below |

Original expanded matrix: 48 shapes × 3 worlds × 2 sizes = 288 observations.
At d9121bbb there are 21 mismatches: recursive 6, variable comparisons 6, predicates 2, history-versions 2,
two recursive calls 4, lookup 1. At 6f3b61f6 only six remain, all the newly added scalar rule-data case.
The earlier 42-shape table itself agrees in all 252 observations on the candidate.

Extended archived matrix: 50 × 3 × 2 = 300 observations, with 18 disagreements: scalar rule-data, nonexistent
lookup-shaped data, collection rule-data, six each. The rejected temporal prototype gives exactly the same
18 disagreements; it was not a rule fix. Successful equality compares complete values, not just counts/hashes.
No claim is made about every possible query, CLJS execution, attribute-ref mode, mutual recursion, every
Boolean/rule nesting, or >16-group greedy plans.

Largest planned matrix probe: about 204.95 MB / 194.13 ms on 6f3b61f6 (base maximum 203.21 MB / 356.43 ms).
These intentionally broad result-producing agent forms are not sub-10-MB overhead measurements. Startup/JIT
is outside individual q timings. One attempted recursive identity-only rule hung in the base-engine portion;
its JVM (70565, started 17:43:12) was interrupted and exited 130. It supplies no correctness evidence and was
removed from the bounded reproduction script.

### Rule counterexample and design boundary

```clojure
;; db contains entity 2 with unique :u/id 2
(d/q '[:find ?x :in $ % ?v :where (same ?v ?x)]
     database '[[(same ?v ?x) [(identity ?v) ?x]]] [:u/id 2])
;; base:    #{[[:u/id 2]]}
;; planner: #{[2]}
```

At size 20/current: planned 2.307 ms / 258,512 bytes; base 0.190 ms / 32,016 bytes. At size 320/current:
planned 0.967 ms / 243,720 bytes; base 0.155 ms / 32,016 bytes. These are single observations, not performance
benchmarks. d9121bbb agrees with the base on the scalar case. A literal `(same [:u/id 2] ?x)` also fails.
`[:not/an-attribute 2]` is ordinary data to the base rule; the candidate throws `:lookup-ref/unique`.

Producer: `query.cljc:3356`, unconditional `dbu/entid-strict` in rule-call substitution. A second producer,
`resolve-lookup-ref-bindings` around :3407, resolves collection/relation cells without considering their use;
that older behavior explains why simply reverting the new scalar line would not fix the class.
`lower/rename-branch-vars` and recursive post-filtering in execute.cljc further assume normalized values.
The rule-name privacy/invariant-head change itself passed the bounded recursion cases; this does not prove
all recursive semantics correct.

**Design check: the universal rule-argument conversion is unsound.** Per the lane stop rule, no ad hoc
head-variable type inference or fallback-engine dispatch was added. Options for root, with rough engineering
estimates (not performance promises):

1. **Hold the gitlink (recommended now)**: zero runtime deployment cost; preserves current behavior and buys
   time for the producer fixes. Sacrifice: delays the substantial identity-query improvements.
2. Repair pattern-local lookup resolution through rule/input binding paths, plus shared-added merge equality:
   roughly 2–4 hours plus central proofs; preserves data and ref semantics. Requires the exact regressions below,
   warm/cold cost checks, and separate action on the existing cold-query budget violations before acceptance.
3. Revert the lookup-ref portion of 84790323 while keeping its private head vars: roughly 30–60 minutes plus
   central proofs. Removes the new scalar/literal regression but restores wrong entity-lookup rule answers and
   leaves collection data broken; this is a bisect/control candidate, not a release recommendation.

### Temporal counterexample and rejected repair

[Edge probe](dh-audit-edges.clj): entity 1 initially has card-many a=[1 2], b=[1 4 5]; retract b=1, add a=4.

```clojure
[:find ?v ?w ?added
 :where [?e :i/a ?v ?tx ?added] [?e :i/b ?w ?tx2 ?added]]
```

History base returns the nine pairs v∈{1,2,4}, w∈{1,4,5}, all with `true`. Candidate adds
`[1 1 false]`, `[2 1 false]`, `[4 1 false]`. The merge producer compares shared v/tx, never shared added:
`execute.cljc:330` temporal-merge-datom-match?, setup :1813, fast replay :1508. The 77c1228e buffer repair is
correct for the value and tx cases tested; 49e681db's cursor ordering precondition also passes the matrix.

A four-line logical grouping prototype leaves fifth-variable patterns as ordinary relation joins. All nine
checks (value/tx/added × current/history/as-of) then agree, and the explicit nine-row answer is correct.
However, [five warm samples after 50 warmups](dh-audit-temporal-cost.clj) give:

| version | median ms | allocated bytes | rows |
|---|---:|---:|---:|
| 6f3b61f6 | 0.234083 | 83,432 | 12 (wrong) |
| prototype | 0.219875 | 129,936 | 9 (correct) |

No disk reads, commits or stored bytes are involved in this in-memory query. **Allocation worsens 55.7%;
prototype withdrawn.** The [experiment patch](dh-audit-temporal-experiment.patch) includes its regression but
is not installed or committed as fork code. The next fix belongs inside the existing temporal merge equality
producer, including multiple merges; broad disabling of fusion is rejected. Final fork changes have no runtime
algorithm change.

## The thirteen updated failure assertions

These are thirteen failing assertions, not thirteen distinct test vars. Every expectation change is supported
by the named earlier commit and its current producer; no hidden bug was found in these thirteen edits.

| # | assertion | authority and verdict |
|---|---|---|
| 1 | query-test, find pull dependencies | 407e9328 adds unknown-component widening for bare attrs without db; :all intended |
| 2 | query-test, nested pull dependencies | same; bare :person/id and nested :person/email may be components |
| 3 | query-test, reverse pull dependencies | same; reverse edge is concrete but other bare attributes still widen |
| 4 | query dependency-plan, input selector | same; no db schema was supplied to disprove component expansion |
| 5 | connector-release, mismatched opening | cc2b2bc7 makes keep-history store-fixed; schema-flexibility still exercises early generic mismatch |
| 6 | connector-release, acquired reference | same; rejection/ref-count assertion remains, input now hits intended generic path |
| 7 | query-cache, config mismatch | same; cache/connection state assertions remain, generic mismatch tested |
| 8 | writer-error, map evidence | 591c825d expressly removes :trace, retains :via :at; corrected assertion matches producer |
| 9 | writer-error, string evidence | same |
| 10 | writer-error, keyword evidence | same |
| 11 | writer-error, vector evidence | same |
| 12 | writer-error, nil evidence | same |
| 13 | attribute-refs pull-limit | 41c79c1a changes shared +default-limit+ from 1000 to nil; full 2000 intended |

Current owners: pull_api.cljc:107 (dependency derivation) and :16 (nil default), connector.cljc:190/:199
(store-fixed keys/adoption), writer.cljc:86 (error log). Dedicated storage-retention tests still cover the
keep-history refusal itself, so switching the generic-mismatch tests does not erase all coverage of that case.
The concept-id padding replacement is equivalent for the nonnegative bounded indexes its fixture uses;
CLJS has no cljs.core/format. No full-suite green or concurrency proof is claimed: root owns those runs.

## Lint audit and fix

Both macros actually bind the previously reported names. `tools.cljc:198` binds e/a/v/tx/added for its call in
query.cljc:1409; execute.cljc:1121 binds first-attr/cur-d/si/cur-a/target-a for its two invocations.
The bare Exception macro value becoming quoted Exception is valid on the JVM; the int/or adjustment has the
same result for the probe producer's nil-or-numeric-field values. CLJS execution remains unverified.

But `(macro-name)` in :unresolved-symbol/:exclude means *every symbol* in that call, as implemented at
`reference-code/clj-kondo/src/clj_kondo/impl/config.clj:362`. In archived source copies, replacing an entity
argument with `misspelled-entity` and a comparison operand with `misspelled-target` yielded **0 errors** under
9e229d31's config (564 ms). With the narrowed `(macro-name [actual-locals...])` entries, both exact misspellings
are reported (**2 errors**, 552 ms). Actual final source has zero errors. No hook was disabled and no general
finding category was suppressed.

## Cost: d9121bbb versus 6f3b61f6 on the same APFS clone

[Cost driver](dh-audit-cost.clj) reuses the existing [bench](bench.clj). Verified query text against
seon.eval.drive/run-receipts :117, seon.fn/runtime-analysis-batch :944, seon.turn/next-ordinal :2317,
continuing-reply? :2810, and seon.cluster's affected rule :2383. Inputs fixed once: last three run ids
`1b6a0ecdf49e`, `71ae2c1d8767`, `bf3db46efe09`; namespaces seon.db/seon.turn; root agent; affected keys
:datahike.read/dependency-plan, :datahike.read/revision, :gen/schema with all three arity-ref attrs.

Two sequential rounds per revision, median of five fresh-connection cold executions per round and their warm
repeats. Plan warmed separately, query-result cache disabled. Bytes from the calling thread's ThreadMXBean;
reads from konserve storage stats, not all logical node visits. No Seon armed calls in these dependency JVMs.
Ranges are round medians; decimal bytes are unrounded.

| query | cold reads pin / candidate | cold ms pin / candidate | cold bytes pin / candidate | warm ms pin / candidate | warm bytes pin / candidate |
|---|---:|---|---|---|---|
| receipts | 678 / 13 | 84.0–115.7 / 3.2–5.9 | 233427904–234755240 / 5083920–5145896 | 7.6–9.2 / 0.9–1.1 | 9707616–9707736 / 270712–270832 |
| analysis | 2896 / 295 | 323.9–362.3 / 50.5–65.2 | 1007651120–1007773048 / 111786264–112434392 | 8.2–11.0 / 5.6–8.3 | 12265416–12387064 / 8108024–8133896 |
| next ordinal | 607 / 10 | 71.0–72.5 / 2.0–2.9 | 204121352–204121360 / 4119808–4140040 | 3.6–4.2 / 0.4–1.0 | 3805224–3805232 / 172512–172584 |
| latest turn | 530 / 528 | 56.0–63.5 / 69.2–98.9 | 174276048–174315936 / 173548208–174553688 | 2.0–2.4 / 2.5–3.2 | 2197088–2197160 / 2113536 |
| affected | 942 / 942 | 98.7–107.2 / 106.8–130.9 | 303965656–303981000 / 303977568–304248648 | 5.0–6.1 / 5.7–7.9 | 7435568–7436240 / 7435976–7436832 |

All warm disk-read counts are zero. Latest-turn has slower samples; affected has slightly higher warm-allocation
samples. These are unresolved cost concerns, not waved away as noise. A final exact-answer pass on the archived
6f3b61f6 source plus the final lint/doc changes compared complete EDN answer values with d9121bbb: equality true
for all five queries. Query hashes also agreed in the original cost rounds. No timing-based acceptance is
claimed from five samples.

Existing budget tasks: `seon.db/a-probe-below-the-seek-break-even-scans-the-attribute`,
`seon.db/a-variable-attribute-rule-query-reads-942-nodes-for-no-rows`,
`seon.db/planner-estimates-load-every-node-of-the-counted-range`, and
`seon.cluster.store/index-roots-lose-their-subtree-counts`. The retained-data/cold-node costs require owner
work before release under the Budgets rule. No claim that a reset fixes them. The benchmark does not measure
full agent-pass allocation, storage-byte deltas, startup memory or default's installed candidate: unavailable
evidence is not a pass. No transactions were issued by store-query measurement; commits/store bytes were not
independently instrumented.

### Sparse-id cache counterexample

[Edge probe](dh-audit-edges.clj): small has one :g/a row, ten :g/b refs and one unrelated entity id 1,000,000;
large adds 8,000 :g/a rows below that id. Both max-eid values remain exactly 1,000,000. Query:
`[:find ?e ?x :where [?e :g/a ?a] [?x :g/b ?e]]`.

Cached-small versus fresh-large plan: ten correct answers in both. Five warm samples: stale median
3.337 ms / 638,160 bytes; fresh median 0.394 ms / 84,104 bytes. In-memory indexes, zero disk reads.
This refutes population-based claims, not the usefulness of the fresh-small/dense-growth bucket heuristic.
The final docstring/changelog now state precisely that limitation. No schema/commit stamps were introduced.

## Verification, design check, and root's central list

The one REPL loop depends on query answers and bounded query work. Existing owners remain the Datahike planner,
merge executor and clj-kondo macro configuration. No new evaluator, cache, scanner, store or registry is added.
Final changes are EQUIVALENT at runtime; the rejected temporal change was a BEHAVIOR CHANGE with a proposed
regression. The rule-data design failed its counterexample; the temporal shortcut failed its cost criterion.
Neither is called fixed.

Before commit: Seon git archive, src/script/test lint with --cache false: **0 errors, 1481 warnings, 8152 ms**.
Final fork archived candidate's two source files with Seon's configuration: **0 errors, 205 warnings, 613 ms**.
Research scripts: **0 errors, 23 warnings, 59 ms**. These standalone dependency probes use EDN output for
evidence and trigger the Seon value-printer warning; joint lint also sees their shared user namespace.
Existing bench.clj has an unresolved macro-local c when
linted alone; it is an unchanged foreign note/script, not a new source finding suppressed by this review.
Installed `seon.contracts-compile-test/check` against the archived packaged Seon projection returned `[]`,
`result/e2acc4de837d9`, for the final two changed fork files. Those files declare no Seon Malli contracts;
this proves the static check ran, not instrumentation. Fork source change is +6/-5 (net +1), tests +0/-0,
CHANGELOG +1/-1. No schema or adoption changes.

**No tests run by this lane. Root's exact focused list:**

- datahike.test.query-identity-join-test/an-identity-join-answers-like-the-base-engine
- datahike.test.query-identity-join-test/an-identity-join-has-answers
- datahike.test.query-identity-join-test/the-bound-unique-lookup-leads
- datahike.test.query-identity-join-test/an-identity-join-reads-the-nodes-of-its-answer
- datahike.test.query-identity-join-test/a-bound-unique-value-names-at-most-one-entity-per-value
- datahike.test.query-identity-join-test/a-plan-made-on-a-small-database-is-not-reused-on-a-large-one
- datahike.test.query-predicate-pushdown-test/bound-range-predicates-preserve-answers
- datahike.test.query-planner-temporal-test/test-history-merge-over-value-ordered-scan
- datahike.test.query-planner-temporal-test/recursive-rules-answer-like-the-base-engine
- datahike.test.query-planner-temporal-test/history-merge-matches-belong-to-the-scan-version
- datahike.test.query-test/query-attribute-dependencies-are-public-and-conservative
- datahike.test.query-test/query-dependency-plans-are-execution-aware-and-source-scoped
- datahike.test.connector-release-test/mismatched-opening-does-not-reserve-a-reference
- datahike.test.connector-release-test/config-mismatch-returns-its-acquired-reference
- datahike.test.query-cache-test/config-mismatch-does-not-open-or-alter-cache-generation
- datahike.test.writer-error-test/writer-log-preserves-exception-and-identities-without-arguments
- datahike.test.attribute-refs.pull-api-test/test-pull-limit
- datahike.test.storage-retention-test/history-setting-is-adopted-or-refused-by-the-store

Two required **new, not installed** proof names: rule-arguments-preserve-lookup-shaped-data and
shared-added-bindings-match-every-joined-datom in datahike.test.query-planner-temporal-test. The latter body
is in the rejected experiment patch. Root should add these with the producer repairs, then run them centrally.
This list is not evidence that root has run anything.

Reproduction: run the probe/edges scripts with `clojure -M` from an archive of each fork revision, compiled Java
classes available. The matrix's sole argument is an output EDN path. For cost, set JVM property dhfix.scratch to
the directory containing the APFS clone; add this dh-fix directory to an alias's :extra-paths so `[bench]` resolves,
and run dh-audit-cost.clj with an output EDN path. Use the same cost-inputs.edn and clone on both revisions.
Exit each JVM before starting another. Raw scratch outputs are retained for diagnosis; owned cloned stores are
removed after recording this note. Default needs **no restart or reset for this review**. A future approved
gitlink bump requires root's restart; this review does not approve that bump.
