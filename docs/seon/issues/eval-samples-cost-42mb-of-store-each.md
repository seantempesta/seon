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
cost scales with transactions and with how many rows share a node —
40 receipts carrying 2 MB each produced a 6.3 GB store (~80x). The
2026-08-01 synthesis records the companion figure: **~1.5 MB per
transaction regardless of payload**
(`plan/state-of-the-design-2026-08-01.md:102`).

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

## Acceptance

1. Decompose the 42 MB: count transactions per episode and measure
   per-transaction growth on an eval-shaped cluster; confirm or
   falsify the "transactions x fixed cost" hypothesis with numbers.
2. State which lever actually moves it, measured, not assumed:
   fewer transactions per episode (batching bootstrap receipts and
   terminal writes), `:keep-history? false` for eval roots (see
   [[history-off-is-not-a-creation-seam-toggle]] — it is not a simple
   flag today), storage GC with a real cutoff
   ([[storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing]]),
   or the unadopted upstream index-root work.
3. A 20-sample run measured before and after the chosen fix, with the
   per-sample figure recorded in the eval plan so future matrices are
   budgeted from evidence.
