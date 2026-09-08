---
type: defect
status: open
severity: friction
tags: [issue, runtime, database]
---

# A throwable-shaped fault keeps no inline evidence at any plausible bound

Measured by the `verify-storage-repair` lane (2026-09-07,
[its note](../../prds/context-generation/research/verify-storage-repair-2026-09-07.md)
§1.3) and left open by `storage-bound-repair-2`.

A fault's evidence is admitted under
`:seon.config.error/max-evidence-bytes` and the fact divides its inline
budget among up to four payload fields, so the share is about 7,750 bytes at
the shipped 16,384. A `Throwable` source admits its whole
`Throwable->map`, stack trace included — **14,311 bytes for a three-key
`ex-info`** — so every exception-shaped fault's `:seon.error/data-edn` is the
missing marker and nothing else. The typed cause survives only as
`:seon.error/kind` on the fact and in full inside the content blob.

This is not a regression (the same fault was 915,655 unbounded bytes before)
and it is not dishonest (the marker says `:over-bound` with the bytes it
reached). It is the old "one member ablates the whole evidence" in byte form:
the fact keeps all of the evidence or none of it, and which one depends on
whether the source was a map or a throwable.

## To do

Decide whether a throwable's evidence should be admitted in PARTS — the
`ex-data` and the typed cause under their own share, the stack trace as blob
content only — so a fault fact keeps the part a steward reads. Raising the
bound is not the fix: the stack trace grows with the stack, not with the
bound.
