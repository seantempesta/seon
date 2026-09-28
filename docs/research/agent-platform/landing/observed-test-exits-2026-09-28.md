# Preserve observed test exits during batch recording — 2026-09-28

Batch recording already writes outcomes and observed exits in one transaction
(`73f311e8d`). An interruption's global termination default incorrectly
outweighed a member's explicit false observation. `complete-members` now reads
the member value with the batch value only as its missing-key default. The
request owner hands exit-callback custody over after successful interruption
recording as it already did after normal recording; exits racing the captured
batch are recorded once by the existing callback path.

No new recording system or state is added. Both normal and interrupted paths
share the small handoff function. Cost stays O(batch members + observed exits),
with one completion transaction; a genuinely later exit needs its later
transaction. Source +18/-14; test +21/-9. This is correctness, not a claimed
latency optimization.

Replayed real recorded outcomes on disposable branches, asserting identical
stored test symbol/pass/fail/error tuples. Explicit false now means no stored
exit, while every outcome is still completed. External index-node bytes:

| Members | Old → new bytes | Old → new ms | Old → new exit facts | Completion commits |
|---|---:|---:|---:|---:|
| 1 | 2501839 → 2256571 | 91.920 → 63.181 | 1 → 0 | 1 → 1 |
| 10 | 2816457 → 2570037 | 73.187 → 73.619 | 10 → 0 | 1 → 1 |
| 100 | 2871897 → 2613957 | 163.112 → 166.553 | 100 → 0 | 1 → 1 |

These bytes exclude fused commit/head records, consistently with the original
store-write probes. The 1/10/100 regression covers both explicit true and false
against the opposite batch default, counts one writer call, and records one
subsequent exit to prove only that member acquires an exit fact.

Archive `88c65fc1922e3ef781ed285edf308f69ac05e710` plus this slice and the
attribute-refusal slice: lint src/script/test cache false, 0 errors, 1617
warnings, 7.227 s; contracts 499 files, 24.808 s, only the ten existing foreign
script findings. No test requests were started. Drill `749f18b1e236` passed: base fresh boot 153.652 s, move 29.781 s,
candidate fresh boot 164.747 s; total 431.126 s. Boot remains far above
the target; neither of these cuts claims to solve whole-boot cost.

Root's focused tests:

- `seon.test.runner-test/a-batch-records-every-outcome-and-observed-exit-in-one-commit`
- `seon.test.interrupted-request-test/a-thrown-request-records-its-unfinished-members-as-failed-obligations`
- `seon.test.one-request-test/a-body-outliving-its-bound-is-released-only-after-it-exits`
- `seon.test.one-request-test/a-member-that-never-exits-holds-only-its-own-test-and-the-next-request-runs`

Evidence: `tmp/astra-store-fixes-20260928/exit-observation-probe.clj` and
`exit-observation-final.json`. Foreign boundary: no turn, MCP, profile, health
or cache lookup edits are included. Historical member retention is still a
separate policy decision/prototype documented in the store-write note; this
slice does not prune old members or claim their removal.
