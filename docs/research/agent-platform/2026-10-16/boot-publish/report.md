# Boot publication investigation, 2026-10-16

No production fix accepted. The suspected continuing P1/P3 runtime slowdown was not reproduced. The two slow starts did different, substantially larger publication work: P1 changed the analyzer identity, then printer P1b changed schemas. No exemption from re-derivation, cache, or source change is proposed. The existing task `seon.cluster/schema-form-keys-re-derive-files-whose-entities-do-not-change` owns narrowing schema-dependent derivation; that is older than this suspect range and remains unresolved.

`seon.cluster/full-source-refresh!` analyzes changed inputs, derives the identity of the analyzer declarations through `seon.cluster/selected-analyzer-digest`, and compares each file's derivation inputs through `seon.cluster/derivation-keys`. Changed analyzer code invalidates the program; changed schema forms invalidate their dependent file keys. The continuation retains its first artifacts. The simplest alternative to a fix is to distinguish these different input sets before attributing their difference to slower execution of the same work.

## Historical checks

Each command was `bin/seon check --at REV --test seon.id-test`. `check.py` captures the owned JVM's readiness before the operator removes it. Initial 7d74 readiness was captured by the same PREPL expression after starting the check. All successful checks executed five tests, 56 assertions, zero failures/errors. Times are ms; publication is store-to-source/current, not operator archive time.

| Revision | Store seed | Publication | Ready | Adopt | Run |
|---|---|---:|---:|---:|---|
| 7d74a762f | fce9d823e template | 28,294 | 30,706 | 3,650 | 7213b9379fc2 |
| b08690b79^ = 3714d84a8 | fce9d823e template | unavailable | failed | 0 | no run |
| b08690b79 | fce9d823e template | 17,352 | 19,412 | 2,064 | e3c3ed82f6b1 |
| 8a3ef6573 | fce9d823e template | 16,134 | 18,549 | 1,567 | a4a1972e7ef2 |
| b08690b79^ | empty, owned operator root | 42,165 | 44,300 | 180 | 5f30f9a23441 |
| b08690b79 | empty, same owned root | 41,308 | 43,086 | 226 | 7dbfbf908af0 |
| fce9d823e | identical fce9d823e template | **1,018** | **5,813** | **200** | 1c7adf75675b |

The standard check seeds from the commit default currently runs (`seon.operator/seeded-store!`), not the requested revision's parent. Thus the first four rows are backward publications from fce9d823e. They are not a forward bisect of the historical starts. The pre-P1 backward publication refuses deletion of `seon.turn.batch/record-form`: two surviving test referrers still name it. The complete refusal is [retained](before-p1-refusal.edn). This did not stop the investigation: the pre-P1 revision and P1 were both booted from empty stores under an owned operator root, without changing default.

The empty-store pair uses the same command/test and full-population path. P1 publication was 857 ms (2.0%) lower, not 23 seconds higher. These are single observations under shared host load, not a statistically established improvement. They do not prove every workload is equivalent; they do refute assigning the historical +23 seconds to this pair without accounting for its different changed inputs.

[7d74 check](check-7d74a762f.txt), [readiness](ready-7d74a762f.edn); [pre-P1 check](check-before-p1.txt); [P1 check](check-p1.txt), [readiness](ready-p1.edn); [P3 check](check-p3.txt), [readiness](ready-p3.edn); [empty pre-P1 check](check-before-p1-empty.txt), [readiness](ready-before-p1-empty.edn); [empty P1 check](check-p1-empty.txt), [readiness](ready-p1-empty.edn).

The final unchanged-commit check ([result](check-unchanged.txt), [readiness](ready-unchanged.edn)) reached source/current in 1,018 ms after store, ready in 5,813 ms and adopted in 200 ms. It did not reproduce the 27–31 second readiness or 11.5 second adoption. This is one observation under shared host load, with no default restart. The remaining 1.018-second publication-layer cost includes dependency analysis and source inventory comparison; no sub-second pass is claimed.

The lifetime profiles in the first P1/P3 readiness files are **not** boot publication profiles. Publication precedes host instrumentation. Existing task `seon.cluster/boot-publication-phases-are-invisible` owns that gap. Replays below supply actual instrumented publication evidence.

## What changed at the first slow start

Read-only JVM probes on default compared the source commit ce241 published (`6ad1af85-f0dd-5dbd-ad03-00c1f9f6a478`) with its parent (`6ad1a1cb-8fe4-580b-a3fe-d2ec26eb2b0a`). Comparing the `:seon.program/definition-digest` values in `seon.cluster/analyzer-reach` finds exactly one changed analyzer dependency: **`seon.fn.analyzer/analyze`**, changed by **b08690b79**. No reached dependency was removed. Probe result e72cd28b582c1, 192 ms; whole request 234 ms.

P1 removed the optional runtime `::hooks` argument and its conditional merge. It did **not** remove hooks from a fixed publication configuration. The publication caller `seon.fn/analyzed-artifacts` never supplied that optional argument, so the Kondo configuration for publication is identical before and after P1. `seon.fn/runtime-analysis-batch` and `seon.turn.batch/record-form` are not in the stored analyzer reach.

A replay of the ce241 publication on an in-memory overlay selected 40 changed inputs and **560 additional stale inputs**, then published 600 inputs. This is why a small Git diff can trigger whole-program work. The resulting profile:

| ce241 replay | Cost |
|---|---:|
| End-to-end publication | 21,884.91 ms |
| Calling-thread cumulative allocation | 30,062,050,768 bytes |
| Kondo, two calls, self time | 5,075 ms |
| Transactions, four calls, self time | 4,154 ms |
| Logical pulls, 1,962 calls, self time | 1,413 ms |
| Analyzer processing beyond Kondo, two calls, self time | 1,173 ms |
| Parse-facts cache misses | 486 |
| Contract facts derived | 4,210 |

The first changed-file analysis/key comparison completed around 2.84 seconds; widened analysis around 14.0 seconds; population finished around 21.6 seconds. [Exact form](replay-ce241.clj), [phases and profile](replay-ce241.edn). Probe e1e1ad075c59e, evaluation 21,936 ms.

This runs the installed fce9 code, whose analyzer implementation matches ce241, on an archive of ce241 and that publication's actual historical database basis. It is an instrumented replay, not an exact cold ce241 JVM. The archive carries fce9's generated dependency pins and test-input-path inventory; the pinned dependencies are unchanged. The exact historical boot observations remain those in boot-time.md.

## Why the next boot was also slow

The source commits show 14 changed inputs from ce241 to fce9, including **two schema resources and config/default.edn**, not just printer implementation files. Commit **bb4dfd16c** added whole-width, max-name, max-number-digits and sort-bound dials and profile members. Twelve effective schema forms changed, including the aggregate config forms.

The analyzer reach has 385 declarations and none changed across these two source commits (probe eaa1fc57b13fc, 46 ms). Recomputing prior file keys with the new schema projection finds **107 mismatches** among 589 file keys (e092a1747a4e1, 44 ms). Four are already selected changed files. The replay therefore adds **103 unchanged inputs** and publishes **117 inputs**.

| Printer-schema replay | Cost |
|---|---:|
| End-to-end publication | 8,325.84 ms |
| Calling-thread cumulative allocation | 11,152,174,568 bytes |
| Kondo, two calls, self time | 2,291 ms |
| Transactions, five calls, self time | 1,677 ms |
| Logical pulls, 1,711 calls, self time | 598 ms |
| Analyzer processing beyond Kondo, two calls, self time | 575 ms |
| Contract facts derived | 1,268 |

[Exact form](replay.clj), [phases and profile](replay-printer.edn). Probe efa89447aca1f, evaluation 8,347 ms. The two earlier attempts lacked an explicit projection and returned input errors (e19f59f161955 and e14568e073d02); they are not timing evidence. The corrected form supplies the target source database's projection explicitly.

On the actual unchanged current source commit, all **589 stored derivation keys match** their recomputation: zero mismatches, probe ed8c5b30cb1c7, 41 ms. Therefore a publication-key drift on every boot was not reproduced.

## Proportionality and fix boundary

The first slow boot's main work is proportional to the entire program because one of the analyzer's own definitions changed. Its producer is `seon.cluster/full-source-refresh!`, with `seon.cluster/derivation-keys` selecting the invalidated inputs. Skipping them by a P1 exception would violate the content identity model.

The next boot's main work follows files whose schema derivation inputs changed, plus changed sources and writer validation. Its remaining optimization opportunity is separating static source analysis from schema-dependent entity derivation, so unchanged text/resolution is not sent through Kondo merely because a contract's schema form changed. That requires proving the actual inputs to contract facts, invoked targets, references and validation; dropping schema inputs from the existing key without that proof is incorrect. The existing schema-form-key task already specifies this work. It is not a newly introduced P1/P3 regression, and no speculative replacement is committed here.

No before/after **fix** numbers exist: production source and tests were not changed. P1's full requested proof suites were not rerun, and no green claim is made for their known P2/P3 reds. The checks here establish boot readiness and the same small test probe, not the entire publication correctness suite. The original request for a performance fix is therefore not fulfilled by this evidence-only slice.

## Verification and cleanup

- Read CLAUDE.md, the owning boot evidence, data-oriented Clojure, REPL, testing and Datahike skills. Default status and MCP runtime_status both answered; readiness complete, health unknown, existing cache and untested-hot warnings.
- Dependency seams: clj-kondo `run!` accepts the supplied lint/config inputs (pin eb245d8260736ca463cda85219b6fc3ae73699c3); Datahike `parent-commit-ids` and `commit-as-db` identify/materialize the held immutable source basis (ee3f8ec817e5310b3a0ea5adb8904214183568c0); Malli `-instrument` validates the probe's declared function inputs (dd13ff81139159778edb0a67b491eccc0baa991d).
- Replays use `seon.cluster.store/open-overlay!`; branch changes and publication writes stay in that memory overlay. The analyzer has a private copied resolver cache. Every database/overlay is released in finally. No uncommitted source was loaded, no SCI request as root was made, and default was never stopped, reset or restarted.
- Full `clj-kondo --lint src script test --cache false`: **0 errors**, 1,801 warnings, 7,841 ms. Source delta 0 lines; test delta 0 lines. Evidence/scripts only.
- Foreign boundary: render-select and merge-correct-2/gate-candidate edits are excluded; no held source file was edited or patched. The backward-publication refusal is retained rather than repaired outside this assignment.
- Check JVMs are terminated/removed by the operator. Owned temporary input copies and resolver data are removed after retaining these observations. No default restart is needed for this evidence-only commit.
