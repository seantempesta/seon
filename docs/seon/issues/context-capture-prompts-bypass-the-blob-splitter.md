---
type: issue
status: open
severity: friction
tags: [issue, blob, render, performance, class/n11, wave/eval-scale-economics]
---

# Route exact context captures through the blob owner

## Problem

`seon.context/capture-tx` writes the complete rendered prompt directly to
`:seon.context.capture/prompt` (`src/seon/context.clj:119-144`). The result and
reasoning splitters live in `seon.cluster.loop` and are never consulted by this
path (`src/seon/cluster/loop.clj:469-522,915-963`). Changing the configured blob
threshold therefore cannot move prompt captures out of Datahike's primary and
temporal indexes.

The schema comment still says the blob archive does not exist and calls the
cutover future work (`resources/seon/schema.edn:888-894`), although
`seon.blob/put!` is already the result/reasoning owner.

## Evidence

The 198-sample census in
`docs/prds/sci-execution-runtime/research/store-census-2026-08-02.md` measured:

- 198 prompt datoms totaling 3,443,098 raw UTF-8 bytes;
- every prompt over 4,096 characters, none over 65,536;
- 53,797,232 B of attributed physical storage, including 30,795,521 B in
  temporal indexes; and
- 43,963,414 B retained by immutable snapshots versus 9,833,818 B in ending
  heads.

The archived run used threshold 65,536, but that is not the root cause: prompt
capture bypasses the split at every threshold.

## Owner

`seon.context/capture-tx` owns the capture transaction data. `seon.blob` owns
content-addressed payload persistence.

## Acceptance

- Store the exact prompt once through `seon.blob`, with digest and size facts
  on the capture; do not introduce a second blob/split mechanism.
- Make debug and forensic readers resolve the exact bytes without changing
  what the agent received.
- Remove `:seon.context.capture/prompt` or reduce it to a deliberately bounded
  projection, and delete the stale schema comment.
- Report store bytes, blob bytes, and exact-capture equality from a fresh
  eval-root before/after census.
