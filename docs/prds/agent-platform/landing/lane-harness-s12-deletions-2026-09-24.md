---
type: landing
lane: harness-s12-deletions
status: deletions committed; not adopted into default (red baseline blocks the save gate)
created: 2026-09-24
---

# Lane harness-s12-deletions (2026-09-24)

One-test-harness S12 deletions (design-2026-09-24.md §9a/§9c) and the turn-suite verdicts
(turn-suite-subsumption-2026-09-24.md, c8621765a). Only deletions, plus one committed
measurement script.

## Broken first

- **The deletions have not reached default.** The save gate refuses every adoption. With
  `bin/test-check default --gate --changed-path …` over the 17 paths, the first attempt
  refused with "Source changed during development adoption", offense
  `test/seon/config_test.clj`. That file belongs to another lane and is published only when
  it is included. The second attempt included it and ran 120 reaching members in 120 s:
  pass 821, fail 32, error 25, `passed? false`, so default is unchanged. Every red came from
  a foreign namespace (my.plan-test ×21, my.note-test ×4, my.agent-test ×2, my.message-test,
  my.plan-api-test, my.program-mutation-test, seon.agent-call-edges-test,
  seon.agent-situation-test, seon.ai-test). This is the known red baseline that blocks every
  adoption (open-items, 78b01b98d). After the attempt, default's rows still hold the deleted
  test identities, and its loaded Vars still resolve them. An after run of `--ns` would
  therefore re-run the old program and prove nothing, so none was taken. **Owed:** once the
  baseline is green, run one `bin/test-check default --ns` per touched namespace and compare
  it with `before/` below.
- Every touched namespace was already red before the edit (see before run). No lane-local
  proof can distinguish "no new red" until adoption succeeds.

## What changed

S12, 13 independent deletions. 4 retirement guards: `authored-plan-machinery-is-deleted`,
`facade-is-retired-and-reader-refusal-is-a-literal`,
`instrumentation-observations-do-not-carry-legacy-class-stamps` and
`evaluator-no-longer-owns-a-second-reader-surface`. 2 subsumed tests:
`deleted-list-sessions-name-is-an-unknown-tool` and
`profile-fit-supersedes-legacy-print-cuts-with-values`. 7 measurements. The 6 that retire
with their subjects stay (fixture timing, two drift tests, three restore tests).

The measurements that keep an observable cost moved to `script/seon/dev/harness_costs.clj`
(97 lines, contracted, asserts nothing): warm validator and explainer acquisition, full
projection build, and per-call wrapper overhead for Malli, host and SCI across three contract
shapes. This replaces `registry-generation-operation-counts` without its `with-redefs` over
Malli, and it replaces the two hot-schema-check measurements and
`declaration-manifest-projection-build-cost`. Three measurements were dropped without a
script:
- `full-projection-fingerprint-dispatch-timing`: a one-off comparison against a legacy
  implementation.
- `one-error-write-…` (its `measured-write` helper redefined 30 roots): `seon.profile`
  already times the armed error path.
- `cold-root-pull-…`: informational only.

Measured in default, 511 ms total. Validator acquisition 1.72 µs, explainer 2.76 µs, over
3,383 schemas. Projection build 386 ms. Wrapper per call, in µs as Malli/host/SCI-Malli/SCI:
scalar 0.22/0.23/0.35/1.56, map 0.21/0.23/0.36/0.48, declared-error 0.20/0.21/0.32/0.46.

Turn suites: 43 of the 54 reviewed deletions were made. After re-reading each survivor,
**11 were kept** because the named survivor does not assert the same behaviour:

| kept | why |
|---|---|
| W681 two-wakes-in-one-transaction-are-one-turn | W585 has one wake; a regression that selects one item per turn passes it |
| W713 a-wake-arriving-mid-turn-opens-the-next-turn | W585's later wake arrives after close; the open-vs-close basis is untested there |
| L1015 the-committed-set-is-computed-… | L1172 is a subset check, vacuous over an empty committed set |
| L1357 install-gate-failure-settles-… | C3231 counts a pre-started receipt; it never asserts the receipt settled as an error |
| L1556 a-refused-terminal-commit-still-closes-the-run | only test of `settle!`'s commit-throw arm; L1612/C846 exercise `settle-batch!` |
| T1507 receipt-transitions-… | settle-without-terminal-fact refusal and triage-edn retention appear nowhere else (review's own note) |
| T902 batch-settlement-preserves-declaration-order | only case of a test before its subject in one `receipt-settle-batch-tx` |
| T1020 / T1045 read refusals | distinct read sites (budget, history); T1000 covers only `require-open-run` |
| C3176 generated-membership-failure-… | routes through `turn/turn`; C3231 calls `settle!` directly |
| C818 import-addition-… | the only SCI-path proof that an import addition is stored as symbols |

Survivor confirmations beyond the review: N212 and N215 are also covered by
`seon.cluster.reply-test/no-form-replies-tell-the-agent-what-to-send`. C379's doc persistence
is also covered by fn_test and documentation_test. Helpers left unused were deleted:
`drive-passes!`, `measured-write`, `staged-reply`, `planner-attempt`, `form-namespaces`,
`assignments`, `second-reader-sites`, `surface-exemptions`, `accepted-source-reader-pattern`
and the loop-test `request`. So were unused requires, orphaned section banners, and the
three scenario branches of `prove-session` that no remaining caller reaches.

## Tests and lines removed per file (git numstat, +added/−removed)

| file | tests | +/− |
|---|---:|---:|
| cluster/turn_test.clj | 60→46 | +0/−604 |
| turn_loop_test.clj | 26→16 | +0/−486 |
| turn_test.clj | 37→30 | +0/−235 |
| gen/loop_test.clj | 3→1 | +1/−193 |
| error_write_timing_test.clj | 4→3 | +2/−108 |
| schema_test.clj | 46→43 | +0/−95 |
| instrument_test.clj | 55→52 | +0/−74 |
| turn_continue_test.clj | 9→2 | +1/−50 |
| sci/reader_test.clj | 29→28 | +0/−44 |
| turn_work_test.clj | 13→11 | +0/−42 |
| loop_proof_test.clj | 4→3 | +0/−37 |
| render/root_pull_test.clj | 14→13 | +0/−17 |
| dev/mcp_bridge_test.clj | 24→23 | +0/−12 |
| error/refusal_test.clj | 9→8 | +0/−10 |
| bootstrap_test.clj | 9→8 | +1/−8 |
| render/value_test.clj | 38→37 | +0/−8 |
| **total** | **56 tests** | **+5/−2,023 test; +97 script** |

## Evidence

- The removal script `tmp/harness-s12-deletions/delete.clj` uses a rewrite-clj zipper under
  bb and has no regex. The plans are `s12.edn`, `turn.edn` and `helpers*.edn` beside it.
  Every named form was found.
- clj-kondo reports no errors and no new unused or unresolved findings against HEAD. The
  pre-existing findings are value_test `seon.error`, reader_test `read`, and turn_work_test
  `other-process`. All 17 files parse.
- The before run (`tmp/harness-s12-deletions/before/SUMMARY`) was one `bin/test-check
  default --ns` per namespace, sequential, 2026-09-24 ~20:05–20:33Z, with every namespace
  red except refusal and value. The largest were turn-continue at 112 s with 9 fail and
  11 error, and schema at 155 s with 12 fail. These are foreign reds and over-bound runs,
  which is not this lane's cost.
- Hook publication during the edits reported refusals of 1.1–6.8 s. This is foreign
  (`seon.schema/call-with-projection`), already filed under design §10.

RESET NEEDED: no. Commit: see git log (`test: S12 + turn-suite deletions`).
