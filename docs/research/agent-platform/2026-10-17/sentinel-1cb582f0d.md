---
type: research
status: measured by hand through eval_clj (the fixed script not loaded into default); compared with sentinel-baseline.md and sentinel-after-restart-2.md
basis: default's JVM, 2026-10-17, sentinel probes (5 warm after 2 warm-ups, 3 fresh, 1 defn) as agents sentinel-stages-*
---

# Sentinel stages, 2026-10-17

## Cause of the nil `:reads`, `:transactions`, `:stages`

`seon.sentinel/eval-probe` read the numbers from the printed reply text of `seon.mcp/eval-clj`. Since cd5f02802 (C1 slice 2, 2026-10-15) that text is the reply's AI face (`seon.mcp/render-request-reply-ai`), which shows only the headline (`seon.batch/render-request-cost-ai`: ms, calls, alloc-mb) plus `:seon.repl/cost-result`, the handle that holds the whole cost. `:seon.cost/stages`, `:reads` and `:transactions` are therefore absent from the text by design; the cost map still carries them. The 10-14 runs predate that commit. `:seon.cost/*` keys in `seon.cost.edn` are unchanged. Producer is correct; the reader was stale.

Fix (script/seon/sentinel.clj, `eval-probe`): after the measured request, a second, unmeasured request evaluates the reply's `:seon.repl/cost-result` symbol as the same agent and the figures are read from that value. Raw check as a throwaway agent: `(+ 1 2)` returns a `:cost-result`, evaluating it returns the whole cost with all stages. The corrected script has not been loaded into default (uncommitted source stays out of default's JVM); the figures below come from the same two-request path by hand.

## Warm `(+ 1 2)` (6 samples after warm-up, ms)

Request ms 52.2, 72.6, 55.3, 79.1, 53.6, 55.6 (median 55.5; baseline 38.6, 10-14 runs 48.7 to 57.1).
Settle 36.2, 56.2, 37.8, 58.0, 35.7, 37.0 (median 37.4; baseline 27.4). Reads 41, transactions 1 in all.

| Stage, median ms | Baseline e9bcf36d2 | 10-14 (243683f1d) | Now (1cb582f0d) |
|---|---|---|---|
| custody | 0.4 | 0.6 | 0.7 |
| fork | 0.3 | 0.4 | 0.5 |
| evaluate | 1.1 | 1.2 | 1.5 |
| settle | 27.4 | 37.4 | 37.4 |
| test-step | 2.1 | n/a | 4.0 |
| print | 0.9 | 1.0 | 1.8 |
| unattributed | 6.3 | 9.6 | 8.7 |
| total | 38.6 | 51.6 | 55.5 |

Within settle, the recording transaction: commit 10.6 to 14.2 CPU ms, writer 6.7 to 9.2 CPU ms (not in the baseline doc).

Fresh first requests: 165.6, 169.5, 197.2 ms (custody 103.7, 112.8; settle 39.6, 35.4); baseline 213.9. Defn: 420.7 ms (custody 110.2, derive 18.3, evaluate 42.0, settle 98.3, test-step 116.0, unattributed 30.1, calls 18,543); baseline 378.6 ms, 10-14 about 326. Defn test-step 116.0 against 79.7 baseline.

## Step 2: where the extra ~17 ms of a warm request is

Against the baseline (+16.9 ms, 38.6 to 55.5): settle +10.0 (27.4 to 37.4), unattributed +2.4, test-step +1.9, print +0.9, evaluate +0.4, custody +0.3, fork +0.2. Settle carries about 60 percent of the regression, and it was already at 37 ms on 10-14 (+10 ms), so settle has not moved since 10-14; the rest of the 10-14 to now difference is test-step, print and unattributed (+0.1 to +1.8 each) and noise. Two of six samples had settle near 57 ms; the warm median is sensitive to that.

Settle is the recording transaction (`seon.batch/run` settle step -> writer, commit). Its commit plus writer CPU is about 20 ms of the 37. The commits since e9bcf36d2 touching `seon.batch`, `seon.db`, `seon.cluster.eval` include a713bc211, 3f4c760a3, 0f7db6347 (a later form reads a request view) and 5a8905a43; none is isolated as the cause. Next step, for root: bisect settle at e9bcf36d2 against HEAD with `seon.profile/measure` on `(+ 1 2)`, looking at the writer's `:datahike/processing` and `:datahike/commit` figures.

## Separate finding

The warm agent's `:calls` grew by exactly 16 per request (6638, 6700, 6716, 6732, 6748, 6764, 6780) while reads stayed 41, where the baseline and the 10-14 runs were constant. Something per request scales with the agent's earlier requests. Time did not trend within six samples. Not reproduced beyond them.
