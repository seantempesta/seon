---
type: issue
status: open
severity: blocker
tags: [issue, database, testing, performance]
---

# Each eval sample costs ~42 MB of store

## Problem

The first full Inspect run (`gpqa_diamond`, 198 samples, 2026-08-02)
measured **42.40 MB median store growth per sample** (58.12 MB mean,
11.96–516.43 MB range), totalling **2.042 GB logical / 2.104 GB
allocated** across its two operator roots. Each sample is one cluster
running one bootstrap plus one turn.

At this rate a 20x20 generation matrix is ~17 GB, a 1,000-sample
benchmark ~42 GB, and repeated calibration runs fill a laptop disk in a
day. This bounds every experiment the platform exists to run, so it is
a blocker on scale rather than a performance nicety.

## What is already known about the mechanism

`research/admission-caps-and-blob-fallback-2026-08-01.md:225-235`
measured the same class directly: **every transaction rewrites the
konserve index nodes across three indexes with no reclamation**, so
cost scales with transactions and with how many rows share a node.
That probe used DELIBERATELY HUGE synthetic payloads (1-2 MB per
receipt) to find the amplification knee — real receipts are nothing
like that: the 2026-08-02 refactor proof measured a settled receipt's
`result-edn` at **59 characters**. The companion figure is the one
that bites: **~1.5 MB per transaction REGARDLESS OF PAYLOAD**
(`plan/state-of-the-design-2026-08-01.md:102`) — the fixed cost of
rewriting index nodes across three indexes. We pay megabytes to store
tens of bytes; the payload is not the problem and shrinking it cannot
help.

An episode commits many transactions (agent creation, bootstrap plan
seeding, a receipt per bootstrap form, the message, run open, the
model attempt, each reply form's receipt, settlement, terminal). At
~1.5 MB each, ~28 transactions accounts for the observed 42 MB
almost exactly — so the hypothesis to falsify FIRST is simply
"transactions x fixed index-rewrite cost", not payload size.

Mitigations already landed that did NOT prevent this: the blob tier
(large results leave the datoms), index-root fusion and the 256-entry
diff buffer on fresh stores (`store.clj:177` — the eval roots had
them).

## MEASURED 2026-08-02 — the fixed-cost claim is FALSE; payload
## amplification and transaction count are the real costs

Twelve cells on isolated scratch stores (`tmp/store-amplification/`,
lane stopped before writing its source-anatomy report — the numbers
stand, the konserve/datahike WHY is still unread):

| cell | per-transaction |
|---|---:|
| 1 / 10 / 100 empty txs | 2.81 / 3.53 / 10.84 KB |
| 100 txs, `:keep-history? false` | 5.02 KB |
| 100 txs, fusion off | 11.12 KB |
| 100 txs, stock defaults | 11.09 KB |
| **100 datoms in ONE tx** | **25.38 KB TOTAL** |
| **one 4 KB payload** | **350.19 KB** |
| **one 64 KB payload** | **5,510.19 KB** |

Three conclusions, all directly actionable:

1. **A small transaction costs kilobytes, not 1.5 MB.** The
   "~1.5 MB regardless of payload" figure in
   `plan/state-of-the-design-2026-08-01.md:102` is WRONG and must be
   corrected wherever it is repeated.
2. **Payload amplification is ~86x and it is the dominant cost.** Our
   `:seon.config.eval.result/blob-threshold` is 65,536 CHARACTERS, so
   a payload just under it stays inline and costs **5.5 MB**. The July
   probe placed the knee "between 10 KB and 100 KB"; this data shows
   87x amplification already at 4 KB. The threshold is roughly an
   order of magnitude too high.
3. **Batching is worth 42x.** 100 datoms in one transaction cost
   25 KB; the same datoms in 100 transactions cost 1,084 KB. An
   episode commits ~28 transactions that could be far fewer
   (the bootstrap's 13 form receipts are the obvious batch).

History-off roughly halves the empty-transaction cost; fusion and the
diff buffer are noise at this scale (10.84 vs 11.12 KB) — they were
adopted for different, larger-scale reasons.

## Acceptance

1. DONE above for the mechanism; still owed: the per-episode
   decomposition on a real eval cluster (how much of the 42 MB is
   receipt payloads at 86x, how much is transaction count, how much is
   the cluster fork itself) and the source anatomy the stopped lane
   never wrote (konserve/datahike/psset file:line for WHY 86x).
2. Land the three measured levers, each with before/after: LOWER
   `blob-threshold` (re-derive the knee from this data — 4 KB already
   costs 87x), BATCH the episode's transactions (bootstrap receipts
   first), and history-off for eval roots. Also consider:
   fewer transactions per episode (batching bootstrap receipts and
   terminal writes), `:keep-history? false` for eval roots (see
   [[history-off-is-not-a-creation-seam-toggle]] — it is not a simple
   flag today), storage GC with a real cutoff
   ([[storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing]]),
   or the unadopted upstream index-root work.
3. A 20-sample run measured before and after the chosen fix, with the
   per-sample figure recorded in the eval plan so future matrices are
   budgeted from evidence.
