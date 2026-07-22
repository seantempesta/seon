---
type: spec
status: active
tags: [spec, agent, architecture]
---

# W5-0a — the computed agent-surface census gate

First unit of the W5-0 series (census gate → seon.db parity → message →
lifecycle → blob port ∥ → gate green → composition drive → stage-1.5
retirement proof). Grounding, binding on this unit:
`research/w50-surface-census-grounding-2026-07-22.md` — read its §2, §4,
§5 before writing code. STOPPING EARLY IS FREE.

## The contract

One conformance test computes:

- LEFT: the deliberate child agent surface — public first-party function
  rows whose colocated var metadata projects `:seon.fn/agent-facing?
  true` (the compiler macro's closure, indexing.clj:85 → the boot
  indexer projection, client.cljs:1491 → the menu selection,
  menu.cljs:305,423). Compute it the way the grounding's §5 recommends —
  from source/compiled metadata, NOT from a live database (the gate runs
  in the ordinary test build). Never the host loader diagnostic.
- RIGHT: a DISPOSITION TABLE (data in the owning test ns): every LEFT
  symbol maps to exactly one of `:host/resolved`,
  `:host/capability-pending`, `:host/platform-pending`, or
  `:host/excluded-with-reason` (reason string required). The four staple
  families start with the grounding's §4 dispositions (db partial →
  capability-pending per-name where absent; blob platform-pending except
  `stat` capability-pending; message + lifecycle capability-pending).
- ASSERTIONS: (1) totality — every LEFT symbol has exactly one
  disposition, so a NEWLY MARKED agent-facing fn fails the gate until
  dispositioned (the q34 acceptance's red-for-new-function rule);
  (2) honesty — every `:host/resolved` row actually resolves in the host
  wrapper registry's declared names (compare against the registry
  seed/declaration source, not a live host); (3) the CUTOVER assertion —
  zero `*-pending` rows — lives behind an explicit flag/var the W5
  cutover flips, so the gate is GREEN today with pending rows visible
  and the pending COUNT is reported in the test output.

## Placement and idioms

- One new test ns in the writer or cljs gate per where the LEFT
  computation is honest (the grounding recommends; justify your choice).
- Reuse the one scan idiom (seon.test.source-scan, q23 precedent) —
  do not write a fourth scanner.
- The disposition table is the durable work-list for W5-0b..e: each row
  names its family unit. Keep it sorted and diff-friendly.

## Owned paths

The new test ns; seon.test.source-scan ONLY if a genuinely shared
helper is missing (report it). PROTECTED: everything else — notably
src/seon/config/resolve.cljc, src/seon/host/{sample,preflight,eval}.clj,
src/seon/ai.cljs and their tests (a live lane owns them), config/*.edn.

## Gates

The owning gate suite focused + full once; honest counts; full logs to
files. Commit nothing; leave the diff for review.
