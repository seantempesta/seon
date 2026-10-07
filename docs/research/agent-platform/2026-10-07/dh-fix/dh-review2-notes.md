---
title: Second independent Datahike fork review
type: research
date: 2026-10-07
lane: dh-review2
status: blocked
---

# Decision: do not bump the gitlink

Reviewed `d9121bbb..2ff3bd4d`, including each diff through `6f3b61f6` and the second repair range
`ee5ac0bd..2ff3bd4d`, against the planner and first-review notes. Both requested matrices now pass, but neither
universal answer equivalence nor the Budgets requirement is established. Targeted counterexamples below falsify
equivalence. Some are older than the pin; they are not attributed to this range merely because this review found
them. No fork commit, push, or Seon gitlink change was made by this lane.

The repeated-variable repair is incomplete. A two-line extension fixes the new counterexample but increases its
base-engine allocation from 21,464 to 36,840 bytes. I removed that experiment from the fork and preserved its
[patch](dh-review2-experiment.patch). Lane rules say: **“A slice that worsens any number does not land.”** This is a
rejected experiment, not an installed fix or a proposal ready to apply. Root needs a cheaper producer repair and a
decision about partially bound negation before acceptance.

## Answer evidence

All dependency evaluation ran in my own JVMs from committed archives, never default's JVM. Query result caching
was disabled. These are bounded differential probes, not test-runner invocations. Default still uses the pin.

| probe at `2ff3bd4d` | coverage | result |
|---|---|---|
| [Opus matrix](review-dhfork-opus-probe.clj) | 86 shapes × sizes 40/1,500 × current/history/as-of × fresh/shared-growing/shared-shrinking plan caches | 1,548 comparisons, 0 mismatches |
| [Astra matrix](dh-audit-probe.clj) | 50 shapes × sizes 20/320 × current/history/as-of | 300 comparisons, 0 differences |
| [first-review edges](dh-audit-edges.clj) | shared value/tx/added, lookup-shaped ordinary data, sparse high-id growth | answers agree; stale-plan cost persists |
| [dh-fix2 repros](dh-fix2-repro.clj) | lookup-shaped tuples/data, temporal shared vars, false added flag, repeated pattern/rule args, bound function output | repaired shapes agree; six lookup-through-relation cases still disagree |
| [new probes](dh-review2-probe.clj) | eight three-clause shapes × six permutations × three database views | 144 comparisons; 6 differences, all partially bound negation |

The matrices cover identity and ref joins, recursive/non-recursive rules, predicates including variable
comparisons, or/not/or-join, aggregates, pull, collection/relation/lookup inputs, unique/non-unique and many-valued
attributes, and empty results. They are not a proof for every query in those classes. The Opus comparator collapses
all exceptions to one error marker and converts vector results to sets; it cannot prove error identity or tuple
order. Six left-recursive variants use equivalent right-recursive reference rules to avoid base-engine blowup.
Astra compares complete returned values/error maps but clears the plan cache per row. Agreement of two wrong engines
is also possible, as the source-prefixed repeated-variable example demonstrates.

Confirmed repairs: `3cd6d267` preserves ordinary lookup-shaped data in the covered rule/input positions;
`c757d3d7` returns 9 rather than 12 rows for the shared-added history join and joins variables shared by merges;
`470c911a` fixes ordinary repeated variables and bound function outputs; `2ff3bd4d` keeps the covered merge-bound
negations standalone. The current-db false-added examples return empty as required. The new NOT counterexample
below is a different scheduling/variable-scope issue.

### Repeated variables: lookup entity and source normalization bypass the fix

Minimal database: schema `{:p/id {:db/unique :db.unique/identity} :p/a {}}`, transaction
`[{:db/id 1 :p/id "a" :p/a 1}]`. The data value 1 cannot equal its transaction id 536870913.

| query | planner | base | correct |
|---|---|---|---|
| `[:find ?v :where [[:p/id "a"] :p/a ?v ?v]]` | `#{}` | `#{[536870913]}` | `#{}` |
| `[:find ?v :where [1 :p/a ?v ?v]]` | `#{}` | `#{}` | `#{}` |
| `[:find ?v :where [$ [:p/id "a"] :p/a ?v ?v]]` | `#{[536870913]}` | `#{[536870913]}` | `#{}` |

Producer: `src/datahike/query/analyze.cljc:50`, `separate-repeated-vars`, excludes a sequential first element and
requires the outer clause to be a vector. A lookup ref is a vector; source unwrapping also produces a sequence.
The base-engine call at `query.cljc:2289` consequently skips equality. The experiment admits sequential patterns
whose first element is not a list and coerces the pattern to a vector. All three rows then answer correctly.

Same finite in-memory probe, 200 warmups and median of 21; no storage-node reads and no Seon armed calls:

| engine | `2ff3bd4d` ms / bytes | experiment ms / bytes | answers |
|---|---|---|---|
| planner | 0.177125 / 55,608 | 0.129125 / 55,624 | empty / empty |
| base | 0.048500 / 21,464 | 0.073125 / 36,840 | wrong one row / empty |

This extends existing task `seon.db/a-repeated-query-variable-is-not-joined-to-itself` (`5f8f03a38dd9`), not a new
task. Existing regression `a-repeated-variable-joins-a-clause-with-itself` needs the three rows in the patch.
Also observed: `[:find ?x :where [(identity [1 2]) [?x ?x]]]` answers `#{[2]}` in both engines; repeated output
binding positions still overwrite instead of joining. The same defect class needs coverage at function binding.

### Source-prefixed rule is treated as a datom

On entity `{:db/id 1 :p/ref 1}` with `:p/ref` a ref attribute and rules
`[[(edge ?a ?b) [?a :p/ref ?b]]]`, query `[:find ?e :in $ % :where ($ edge ?e ?e)]`:
planner throws `Symbol cannot be cast to Number`; base returns `#{[1]}`. Reproduced on the pin too.
`query/logical.cljc:234` unwraps the source prefix, classifies the inner list as a pattern and makes a scan without
the rule-call check used elsewhere. A repair must carry the source through rule lowering, not add a whole-query
fallback. Created task `seon.db/a-source-prefixed-rule-is-planned-as-a-datom` (`3fe9b606f10b`), with this exact
reproducer and proposed proof `datahike.test.query-planner-temporal-test/source-prefixed-rules-preserve-bindings`.
That proof name is a target, not an installed test.
These answer-only edge scripts did not retain per-query timings; those timings are unavailable. The timed
repeated-variable and sparse-cache probes are reported separately rather than attributing their numbers here.

### Partially bound negation differs between engines

Database entity `{:db/id 1 :p/a 1 :p/b 2 :p/c 3}`:

```clojure
[:find ?e ?v
 :where [?e :p/a ?v]
        (not [?e :p/c ?w])
        [?e :p/b ?w]]
```

Planner returns `#{[1 1]}`; base returns `#{}`. Base `query.cljc:2260` runs NOT when *some* variables are bound,
making `?w` existential at that point. Planner waits for the later binder. Existing NOT tests deliberately permit
deferred binding, but do not settle this partially bound case. Six of the 144 permutation/view comparisons differ;
the remaining 138 agree. Created `seon.db/partially-bound-negation-disagrees-between-query-engines`
(`e9ed8b56df26`); proposed proof `datahike.test.query-not-test/partial-negation-binding-is-order-consistent` is
not installed. Do not choose one semantics by silently changing expected answers.

### Known lookup-through-relation failure remains

The dh-fix2 repro returns planner `#{}` versus base `#{[[:k1 1]]}` for function-bound entities, mixed entity/value
rule arguments (literal and input), mixed collection input and multi-source entity collection input; function-bound
ref values give planner `#{}` versus base `#{[2]}`. Existing task
`seon.db/planner-leaves-a-lookup-ref-unresolved-where-a-relation-binds-it` (`88c4178179c4`). Resolution belongs at
the executor probe, where the source and position are known. `3cd6d267` fixes the covered cases without finishing
that larger correction.

### Size-class key is not population-sensitive

`query.cljc:3614` keys the plan by the bit-length class of `max-eid`. The edge probe pins max-eid at 1,000,000,
then adds 8,000 `:g/a` datoms below it. For
`[:find ?e ?x :where [?e :g/a ?a] [?x :g/b ?e]]`, both plans return 10 rows, but median of five observations is
stale **3.928250 ms / 639,264 bytes**, fresh **0.490959 ms / 85,208 bytes**. No storage reads on this memory db.
This is not an answer failure. It falsifies a population-sensitive invalidation claim; `ee5ac0bd` correctly narrows
the documentation, but does not fix the cost. Existing task
`seon.db/query-plans-are-reused-across-database-sizes` (`3523ddcf8a1a`).

## All thirteen changed assertions in `6f3b61f6`

These are thirteen assertion instances, not thirteen distinct test Vars. I compared each with the cited producing
commit. No expectation change below was found to hide a source defect.

| instances | test and changed case | prior authority | judgment |
|---|---|---|---|
| 1 | `query-attribute-dependencies-are-public-and-conservative`, pull in find | `407e9328` | `:all` is intentional: forward attr may be a component when no db is supplied |
| 1 | same, nested pull selector | `407e9328` | contains a forward attr; conservative `:all` |
| 1 | same, reverse pull selector | `407e9328` | selector also contains a forward attr; conservative `:all` |
| 1 | `query-dependency-plans-are-execution-aware-and-source-scoped`, input selector | `407e9328` | same unknown-component dependency rule |
| 1 | `mismatched-opening-does-not-reserve-a-reference` | `cc2b2bc7` | use schema-flexibility for generic mismatch; keep-history now has its own store-fixed refusal |
| 1 | `config-mismatch-returns-its-acquired-reference` | `cc2b2bc7` | same distinction, reference-count assertion preserved |
| 1 | `config-mismatch-does-not-open-or-alter-cache-generation` | `cc2b2bc7` | same distinction, cache-generation assertion preserved |
| 5 | `writer-log-preserves-exception-and-identities-without-arguments`, five exception cases | `591c825d` | omit `:data` and `:trace`, preserve `:via` and `:at`; deliberate argument/stack-copy removal |
| 1 | attribute-refs `test-pull-limit`, default many pull | `41c79c1a` | nil default means all 2,000; explicit 500 limit remains tested |

Root must also run `storage-retention-test/history-setting-is-adopted-or-refused-by-the-store` to retain explicit
coverage of the special history mismatch that the three generic-mismatch fixtures no longer exercise.

## Costs on one APFS clone

Command: `cp -c -R /Users/sean/src/seon/data/store /tmp/codex-dh-review2/dh-review2/store`. Clone size 1,024,768 KiB;
it opened cleanly. This was a live filesystem clone, not a transactionally coordinated backup. All revisions used
this same immutable benchmark input, their own JVMs and the same fixed query arguments. No benchmark writes.

Used [dh-fix2-cost.clj](dh-fix2-cost.clj), based on [bench.clj](bench.clj), from archives of the pin, `6f3b61f6`,
and `2ff3bd4d`, in that order, then repeated the pin. Two rounds per JVM, medians of nine after warmup. Cold means
fresh connection/store cache of one with query planning primed, not cold JVM startup. Warm node reads were zero.
ThreadMXBean allocated bytes and thread CPU were measured; background-thread allocations were not measured.
The [raw cost artifact](dh-review2-cost.edn) preserves all 40 rows, fixed inputs, CPU times and answer hashes.
All five answer hashes and row counts agree across revisions; hashes are not full result-equality proof.

Ranges below cover the first two rounds; columns are **pin / 6f3b61f6 / 2ff3bd4d**. MB/KB are decimal.

| shape | cold node reads | cold ms | cold MB | warm KB |
|---|---|---|---|---|
| receipts | 67 / 10 / 10 | 12.7–16.5 / 2.6–2.7 / 3.0–3.1 | 24.570–24.576 / 3.806 / 3.811 | 796.1–801.6 / 279.1 / 283.4 |
| analysis | 2876 / 293 / 293 | 357.9–500.6 / 45.2–54.1 / 54.2–62.8 | 996.509 / 110.786 / 110.793 | 12380.7 / 8113.7 / 8118.1 |
| next-ordinal | 63 / 9 / 9 | 11.1–13.0 / 1.9 / 2.2–3.0 | 22.675 / 3.518 / 3.522 | 386.1–386.4 / 172.2–172.5 / 176.5–176.8 |
| latest-turn | 42 / 41 / 41 | 7.0–20.7 / 6.2–7.3 / 7.9–9.2 | 14.532 / 14.184 / 14.189 | 223.6 / 218.4 / 222.8 |
| affected | 615 / 615 / 615 | 82.6–325.5 / 92.0–95.7 / 93.2–106.8 | 207.405 / 207.422–207.513 / 207.430–207.436 | 7443.4–7444.0 / 7441.1 / 7444.3–7447.4 |

Warm wall ms: receipts 1.1–1.4 / 0.7 / 0.7–0.8; analysis 10.3–13.3 / 5.2–5.9 / 5.9–6.9;
next-ordinal 0.8 / 0.4 / 0.4–0.6; latest-turn 0.4–0.8 / 0.5 / 0.5–0.7; affected 7.1–11.4 / 7.0–7.5 / 7.4–7.9.

The pin repeat measured latest-turn warm 219,320 bytes and affected 7,442,704 bytes, so the small increases cannot
be dismissed by choosing the first pin run. Against `6f3b61f6`, the final revision consistently adds roughly 4 KB
on several warm paths. Against the pin, affected's cold allocation is slightly larger. Wall times are noisy and do
not establish a causal regression by themselves. There is substantial improvement on receipts/analysis/ordinal,
but **“nothing may be worse” is not met**, and analysis/latest-turn/affected still exceed the 10 MB bound.

This clone's receipts result is 67 → 10 reads, not the 53 → 54 seen on the earlier review's different clone.
Do not transfer node counts between stores. Existing producer tasks:
`seon.db/a-probe-below-the-seek-break-even-scans-the-attribute` (`6046ef5943f0`),
`seon.db/a-variable-attribute-rule-query-reads-942-nodes-for-no-rows` (`ab50a8acaedd`), and
`seon.db/planner-estimates-load-every-node-of-the-counted-range` (`e55e210a7997`).

Seon armed calls: zero in raw dependency query probes. Benchmark transaction calls: zero. Independent transaction
commit counters and before/after store-byte deltas were not instrumented, so this is not full pass-budget proof.
JVM peak heap/RSS was not measured. Default's live heap observation (below) is not a substitute.

## Lint and static verification

`9e229d31`'s macro-wide unresolved-symbol exclusions really hid arbitrary mistakes. In archive copies I changed
`source` to `souce` inside `with-destructured-vector`, and `first-attr` to `first-atrr` in the merge macro body.
The `6f3b61f6` config reported **0 errors / 205 warnings / 1,066 ms**; `2ff3bd4d` reported **2 errors / 206 warnings /
1,175 ms**, precisely those typos. Thus `ee5ac0bd` fixes the broad suppression. Its remaining symbol-specific
exclusions match the macro bindings. The quoted `Exception` symbol and nil-safe `(int (or field 0))` correction
match their macro/empty-field roles; no hidden real finding was demonstrated after narrowing.

Seon `src script test` from a `git archive` of HEAD, with the ignored lint configuration linked as `bin/lane` does:
**0 errors / 1,482 warnings / 17,624 ms**. No source diff survived this review. This is not a zero-warning claim.
The final research probe lint: **0 errors / 8 warnings / 16 ms**. An initial accidental checkout lint was not used
as archive evidence. An initial typo-injection copy had a mismatched namespace path; I corrected the copy and
reran the experiment before using the numbers above.

Installed `seon.contracts-compile-test/check`, supplied the explicit default connection's projection, the archived
Seon root and the six reviewed fork source paths (query, analyze, logical, plan, lower, execute), returned `[]`
(`result/e7c3cf71c4ab2`). It statically reads source; it did not load fork code into default. Those dependency files
do not declare Seon Malli contracts; the empty result does not prove candidate runtime installation. The JVM MCP
reply did not expose elapsed ms; timing evidence for that check is unavailable, not a pass under the timing budget.

## Root's central test list

No suites were launched by this lane. Run these exact existing Vars after a repair; new names below are explicitly
targets. Prefix `datahike.test.` applies throughout.

- `query-planner-temporal-test/rule-arguments-preserve-lookup-shaped-data`
- `query-planner-temporal-test/recursive-rules-answer-like-the-base-engine`
- `query-planner-temporal-test/history-merge-matches-belong-to-the-scan-version`
- `query-planner-temporal-test/shared-vars-in-an-entity-group-are-joined`
- `query-planner-temporal-test/a-ground-retraction-flag-matches-nothing-on-a-current-db`
- `query-planner-temporal-test/a-repeated-variable-joins-a-clause-with-itself` (extend with patch rows and function output tuple)
- `query-planner-temporal-test/a-function-binding-a-bound-variable-joins-it`
- `query-identity-join-test/a-join-after-an-or-reads-the-nodes-of-its-answer`
- `query-not-test/test-insufficient-bindings` and `query-not-test/test-deferred-clause-binding`
- `query-test/query-attribute-dependencies-are-public-and-conservative`
- `query-test/query-dependency-plans-are-execution-aware-and-source-scoped`
- `connector-release-test/mismatched-opening-does-not-reserve-a-reference`
- `connector-release-test/config-mismatch-returns-its-acquired-reference`
- `query-cache-test/config-mismatch-does-not-open-or-alter-cache-generation`
- `writer-error-test/writer-log-preserves-exception-and-identities-without-arguments`
- `attribute-refs.pull-api-test/test-pull-limit`
- `storage-retention-test/history-setting-is-adopted-or-refused-by-the-store`

New proofs required, not installed: `query-planner-temporal-test/source-prefixed-rules-preserve-bindings` and
`query-not-test/partial-negation-binding-is-order-consistent`. Rerun both matrices and the sparse-id probe after
any fix. CLJS, alternate index backends, full fork/Seon suites and live publication/adoption remain unverified here.

## Design check and boundaries

The small repeated-variable extension serves the ordinary query step and reuses the existing normalizer, adding
no registry, cache or evaluator. Its answer design held but its allocation design failed: rewriting to fresh vars
and an extra equality relation costs more than the original wrong operation. Prefer equality at the owning
binding/probe rather than further compensating clauses. This review does not pretend that budget failure is fixed.

Options, estimated engineering time rather than measured guarantees:

1. **Recommended now: hold the pin**, zero implementation hours. Preserve installed behavior and spend no adoption
   budget; sacrifice the fork's demonstrated speedups until the failed obligations have a producer repair.
2. Budgeted producer work, approximately 2–4 hours initially: compare repeated slots at binding and resolve lookup
   refs at probes; handle source-prefixed rules in the existing lowering owner. Requires separate NOT semantics
   ruling, regression proofs and fresh costs; this estimate does not guarantee all open performance tasks fit.
3. Apply the preserved normalizer experiment, approximately 15 minutes plus central checks: fixes those three
   repeated-variable rows but is **not landable under current Budgets** and leaves other findings open.

Health before work: default PID 99416, start 09:28:25 UTC, faulted, no missing readiness layers, 9 over-bound
warnings, 7 errored receipts, 1 open fault, 2 unowned namespaces; heap-live 2,359,423,960 bytes. Observed maxima:
custody 10,582 ms, MCP 11,283 ms, schema projection 1,401,316 ms, dependency analysis 15,148 ms, adoption 6,426 ms.
Existing health/task evidence in the first-round and dh-fix2 notes applies; these were observations of default,
not timings caused by the raw dependency benchmark. My SCI health probe `(+ 1 2)` returned 3 in 0 ms
(`result/e3fdf67ef0672`). Task creation returned `result/ec5b69a401a97` and `result/e87bcc529ccd8`.
A broad task query was elided; I narrowed the query
instead of treating missing printed subjects as absent tasks.

Cleanup through `(my.agent/retire-branch! {:seon.store/branch :agent-f976da3c2d3e})` as `dh-review2` returned
`{:seon.error/message "run transition refused: admission-closed"}`, `isError true`, with neither timing nor a
result handle. This is the existing task `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed`
in `resources/seon/tasks/my.agent.edn`. No new duplicate task was created. Branch retirement is unverified and
left for root; no second agent/session was started to work around it. This is a default-hosted SCI branch, not
an owned JVM left running.

Foreign boundary: other lanes own Seon's test/runner, schema and batch files; their live checkout changes were
excluded by archive and were not investigated, edited, resumed or messaged. No default restart/reset was performed
or needed for this review. Root would restart only after an independently accepted gitlink bump. No Monitor,
poller, worktree or persistent benchmark service was created. All launched JVMs exited; process inspection found
no owned JVM. The APFS clone, four dependency archives, Seon archive and lint-injection copies were removed.
Raw scratch evidence is retained under `/tmp/codex-dh-review2/dh-review2/`; durable
reproducers, cost rows, rejected patch and conclusions are in these four review artifacts. Net installed source/test
change: **0 / 0**.
