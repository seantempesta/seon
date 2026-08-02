---
type: issue
status: open
severity: blocker
tags: [issue, database, testing, performance]
---

# Eval storage is dominated by retained database snapshots

## Problem

The first full Inspect run (`gpqa_diamond`, 198 selected samples,
2026-08-02) grew its two operator roots by 2.042 GB. The previously reported
**42.444 MB median per sample** was not an attributable sample cost: it
subtracted shared-store sizes across overlapping intervals with up to five
concurrent peers. The reconstructed selected branches cost **1.939 GB / 9.793
MB per sample**. That is still a scale blocker, but it is a different mechanism
and budget.

## What is already known about the mechanism

The source anatomy and retained rigs now live in
`docs/prds/sci-execution-runtime/research/store-amplification-anatomy-2026-08-02.md`
and its three scripts. The earlier 40-transaction scratch cells were:

| cell | per-transaction |
|---|---:|
| 1 / 10 / 100 empty txs | 2.81 / 3.53 / 10.84 KB |
| 100 txs, `:keep-history? false` | 5.02 KB |
| 100 txs, fusion off | 11.12 KB |
| 100 txs, stock defaults | 11.09 KB |
| **100 datoms in ONE tx** | **25.38 KB TOTAL** |
| **40 transactions adding 4 KB each** | **350.19 KB average** |
| **40 transactions adding 64 KB each** | **5,510.19 KB average** |

The source/object explanation corrects the old interpretation:

1. The 86× coefficient is not one-payload amplification. In a shallow fused
   history-on store, each payload enters four roots, every immutable commit
   retains every earlier root, and the mutable head retains the final roots.
   For `N=40`, `4 * (N(N+1)/2 + N) / N = 86`. Growth is linear in payload size
   and quadratic in sequential commit count while roots remain shallow.
2. A held-out 16 KiB cell was predicted at 56,648,465 B and measured at
   56,618,147 B, a 0.05355% error. This is a predictive model, not a fitted
   label.
3. The 42× batching cell is the same retained-snapshot effect: one transaction
   creates one immutable snapshot and one set of transaction datoms; 100
   sequential transactions retain 100 growing snapshots.

## Eval-scale decomposition

The committed read-only decomposition script reconstructs the selected 198
branches as 1,938,931,814 B over 13,204 transactions (66.69/sample):

| category | bytes | fraction |
|---|---:|---:|
| record envelopes | 12,992,082 | 0.67% |
| current fused roots | 647,541,512 | 33.40% |
| temporal fused roots | 794,520,131 | 40.98% |
| current-only leaves | 362,301,629 | 18.69% |
| temporal-only leaves | 121,576,460 | 6.27% |

History is 916,096,591 B / 47.25%. The same current-index and transaction
workload without temporal roots/nodes is 1,022,835,223 B / 5.166 MB per
sample. Immutable commit records dominate; their non-root envelope averages
only 969 B per transaction. Content-addressed blob objects total **zero bytes**.
The archive's transcript strings total 2.16 MB, but inline string datoms recur
through retained snapshots and leaves. Lowering the blob threshold cannot fix
this particular run because no blob object was written.

## Per-attribute census

The follow-up read-only census is
`docs/prds/sci-execution-runtime/research/store-census-2026-08-02.md`. It
reconciles exactly to the same 1,938,931,814 B and answers the open attribution
question:

| Origin | Bytes | Fraction | Per sample |
|---|---:|---:|---:|
| Inherited fork-parent datoms | 1,128,741,258 | 58.215% | 5.701 MB |
| Sample-generated datoms | 797,198,474 | 41.115% | 4.026 MB |
| Record-envelope structure | 12,992,082 | 0.670% | 0.066 MB |

The fork does not make the program graph free: inherited datoms recur inside
the fused roots of retained database records. The largest attributes are
`:seon.schema/created-at` (187,360,394 B), `:seon.test/source`
(173,810,064 B), `:seon.fn/source` (150,492,183 B),
`:seon.cluster.eval/result-edn` (106,980,416 B), and
`:seon.context.capture/prompt` (53,797,232 B). Fully 1,793,869,909 B / 92.518%
is snapshot-retained rather than reachable only from ending heads.

The zero-byte blob result is now explained. All 198 archived attempts used
threshold 65,536 and disabled reasoning; no split-eligible result exceeded
65,536 characters. Prompts never consult the result/reasoning splitter:
`seon.context/capture-tx` writes the exact string inline. The current 4,096
threshold landed after this run and remains unjustified, not proven wrong.

Measured `:db/noHistory` candidates account for 111,931,217 temporal bytes:
result EDN, prompt capture until its blob cutover, frozen form source, captured
output, message content, attempt settings, and attempt usage. New owner notes
track the two largest structural removals:
[[schema-created-at-multiplies-nonsemantic-provenance]] and
[[context-capture-prompts-bypass-the-blob-splitter]].

The roots report five completions beyond the selected archive. Selected plus
those extras leaves a 41,494,099 B / 2.03% whole-growth reconciliation
residual for baseline/shared/schema/orphan boundary objects. Category sums for
the selected reachable set are exact; whole-directory attribution carries that
bounded uncertainty.

## Acceptance

1. **DONE:** predictive byte anatomy, selected-198 decomposition, history
   counterfactual, per-transaction envelope, blob-content census, and the
   per-attribute current/history/live/snapshot census are reproducible from
   committed scripts.
2. Deliver ruling #40's private history-off eval root after the boot-critical
   reconciliation reader has an honest non-temporal fallback; see
   [[history-off-is-not-a-creation-seam-toggle]].
3. Reduce episode transaction count at its owning run/receipt boundaries and
   measure the complete episode before/after. Do not infer a blob-threshold fix
   from the corrected 86× cell.
4. Specify a commit-ID/database-value retention window, then adopt cutoff GC
   through Seon's blob-aware GC owner; see
   [[storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing]].
5. A fresh 20-sample private-root run confirms the integrated history/batching/
   GC cost before this blocker closes.
