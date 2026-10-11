---
type: evidence
status: measured
date: 2026-10-11
lane: sol-loose-ends
---
# Instrumentation arm recording

The instrumentation reporter produces input, output and guard observations; error preparation preserves that arm into the fact and occurrence. The shared `:seon.instrument/arm` schema admitted only input/output. The smallest composition is to declare the existing guard arm at that schema, leaving preparation and recording unchanged. No new mechanism or guard is introduced; the work remains proportional to the observation and its evidence.

At Malli pin `dd13ff81139159778edb0a67b491eccc0baa991d`, `malli.core/-instrument` checks the guard against `[arguments result]` and reports `:malli.core/invalid-guard`. `seon.instrument/violation` maps that report to `:guard`; `seon.error/contract-violation-data` already accepts it. Default's installed projection answered `[:enum :input :output]`, 1 ms evaluation / 70.9 ms request / 0.2 MB attributed allocation. The enum, not the reporting producer, was stale.

Red proof: candidate `c78873c5f1ad2cd9ce17b4bade5d8ea336c2e951`, run `8c0f996fd23f`, `seon.error-test/preparation-and-recording-preserve-every-instrumentation-arm`: 4 pass / 0 fail / 1 error, 2,290 ms. Input and output prepared and persisted; guard preparation refused its result at `[:seon.error/fact :seon.instrument/arm]`. The proof now covers preparation and durable occurrence read-back for all three arms through the real writer.

Full lint before landing: 0 errors / 1,534 existing warnings, 6,897 ms. Candidate checks publish and adopt onto disposable branches of the live store and boot a throwaway JVM; default is untouched. Existing health findings include cache discipline, untested hot functions and recorded errors. The evidence-leak handoff resolved the fixture ownership boundary; unrelated working-tree edits, including `tmp/test-data-assertions/proofs.edn` and the http-kit checkout, remain outside this lane. No broad namespace tier is claimed; root owns that round.

Fixture landing `1a729eee9`: run `8fbfb7d864d7`, six selected fixture/regression tests, 52 assertions / zero failures / zero errors, 10,524 ms aggregate; publication 4,316 ms, adoption 484 ms, complete check 33,467 ms. Lint zero errors / 1,534 warnings. Source/test delta: +46 / -3 lines. These correctness checks do not establish sub-second performance; startup, publication and nested-fixture costs are retained in their check evidence for root's central performance round.
