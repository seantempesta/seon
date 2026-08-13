---
type: issue
status: open
severity: blocker
tags: [issue, render, class/n1, class-kill, wave/class-kill-queue]
---

# Make outward values unable to bypass one total render contract

## Problem

Outward values can reach agents, operators, MCP, and the web UI through
unrelated partial faces. A missing producer can silently select an empty or
opaque fallback; a face can throw, disclose host representation, or expand
without a bound; and several renderers encode computed values as source
comments or narration. Each instance is locally fixable, but the class remains
writable because no construction owns totality, boundedness, representation,
and declared producer selection together.

## Evidence

Current open members carry `class/n1` and are derived with
`bin/issues-index --class class/n1`.

## Owner

The one `seon.render` selection/call construction, the one `seon.print/fit`
owner, and the external projection leaves as one class-kill wave.

## Acceptance

- Every outward projection is constructed through one total result shape that
  distinguishes rendered value, explicit omission, and flat error.
- Important reachable schemas cannot publish without their declared
  projections; generic fallback use is counted and fails the graduation gate.
- The selected producer's output is terminal data, bounded by the selected
  profile, and no outward constructor accepts comment-prefixed narration as a
  computed result.
- Properties cover ordinary keys, host-wrapper values, deep/nested values,
  missing producers, and failure faces without a throw or silent empty result.

## N1 class-kill wave — 2026-08-12

The class remains open. Two coherent source slices landed:

- `4bc8104d8` makes the existing render/print crossing structurally total: an
  arbitrary value never becomes render-unit keys, terminal AI and HTML
  producer output is fitted, every fit cut is a complete elision value,
  unknown terminal faces become flat errors, and retained call evidence
  records `:seon.render/would-fall-to-floor?`.
- `5e449b275` removes namespace comment framing, makes empty and omitted states
  ordinary values, uses honest “no indexed members” language, and reduces
  compact error schemas to their declared `:error/message`.

Dependency grounding for this slice: Malli `3517a3cd` keeps maps open by
default and validates the qualified render-unit declaration; SCI `fcbd886`
supplies the live-Var invocation/fork boundary; the selected first-party seams
are `src/seon/render.clj`, `src/seon/print.cljc`, and
`src/seon/render/value.clj`. Producer selection remains one mechanism in
`seon.render`; fitting/emission remains one mechanism in `seon.print`.

Focused evidence:

- `seon.render.ns-test`: 5 tests, 134 assertions, green.
- `seon.render-simplification-test`: 11 tests, 134 assertions, green after
  `e8e37eb50`; nested database values use their AI identity face and nested
  transaction reports use their declared AI and HTML faces.
- The combined `seon.print-test`, `seon.render.value-test`, and
  `seon.sci.eval-test` gate reached 87 tests and 457 assertions, then retained
  two independently reproducible failures: the in-flight `my.background`
  declaration has no compatible render input contract, and the public walk
  attempts a selected render invocation while a different SCI context is
  already armed. Neither failure is in the nested admission handoff. The
  database identity's missing HTML producer remains this member's open
  boundary.
- `seon.render.transcript-test`: the execution-error/comment regression is
  green; the namespace is red only in the independently existing generated
  bootstrap-prefix ordering regression.

No protected Phase-1, N5, wedge-lane, database, or public-contract path was
edited, and no issue note was moved to `archive/`.
