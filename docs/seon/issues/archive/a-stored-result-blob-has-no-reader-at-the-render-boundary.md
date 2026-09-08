---
type: issue
status: superseded
severity: friction
tags: [issue, render, repl, storage, blob]
---

# A result blob has no reader at the render boundary

## Problem

`:seon.cluster.eval/result-blob` is declared, and the PRD's storage rule is
"inline under the blob threshold, blob above it". Nothing in the render path
can read one back:

- `seon.render.transcript/history` holds a DATABASE VALUE, and
  `seon.blob/get` needs a CONNECTION (`src/seon/blob.clj:342`).
- `seon.repl/entity-emission` holds a pulled unit, which carries neither.
- `seon.sci.eval/bind-stored-results!` queries only `result-edn`.

Before the storage-bound wave this was hidden: settlement stored a WINDOW —
a page of the value — inline beside the blob, so something always rendered.
That window is deleted (a page whose root face was an ordinary vector, with
nothing in the node saying it was partial, is exactly the shape the wave
existed to end).

So the storage-bound lane stores every faithful value INLINE and stages no
result blob at all. That is honest — no silent absence — but it means a
result of up to `:seon.config.eval.result/max-bytes` (8 MiB) can be one
string datom, and the declared `result-blob` attribute is written by nothing.

## Why it is not just a config choice

A check that reports "fine" when its subject is absent is the recurring
failure class here. Storing blob-only WITHOUT a reader would make every
result over 4,096 bytes render as nothing — an evaluation that produced a
value would look like an evaluation that produced none.

## What to do

Give the render boundary a blob reader, then restore blob staging above the
threshold in `seon.cluster.run/settlement-projection`:

1. carry the cluster's connection (or a bounded read function over it) on the
   render unit — the turn lane owns the unit and the pull selectors;
2. have `seon.render.transcript/bounded-result` and
   `seon.repl/value-text` resolve `:seon.cluster.eval/result-blob` through it,
   and answer with a typed unknown naming the digest when the blob is gone
   (which is what `:seon.eval/missing :lost` is for);
3. re-add the staging branch, with one regression proving a blob-backed
   result renders the SAME bytes as the same value stored inline.

Grounding:
[the storage-bound landing note](../../../prds/context-generation/research/storage-bound-landing-2026-09-07.md)
§3.3.

## Superseded — owner ruling, 2026-09-08

§15 explicitly deletes result blobs, result-edn, and rehydration. A new result-blob reader would preserve a mechanism the owner removed. Evaluation history stores shown text; HTML may use the live object while it exists.

Authority: [agent record and turn loop PRD §15](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md#15-results-objects-in-memory-shown-text-on-disk-owner-2026-09-08).
This closes the old design proposal, not a claim that the protected turn and
render implementation has finished. No regression for the deleted design is
added. Implementation verification belongs to §12–§15’s live-object and
shown-text proofs.
