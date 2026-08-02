---
type: issue
status: open
severity: friction
tags: [issue, sci, database, errors]
---

# Keep session-image refusal evidence as facts, not derived prose

## Problem

The durable SCI session image discards the observations that make a definition
unsafe to restore and stores only an English conclusion. Restart diagnostics
therefore depend on frozen prose rather than queryable evidence.

## Evidence

`src/seon/cluster/loop.cljc:340-410` computes replay safety from evaluation
outcome, host-interop count, unproven Vars, nondeterministic calls, and impure
calls. Its terminal branches dissoc those observations at lines 371-397 and
store a derived sentence under `:seon.code.def/unrestorable` at lines 399-410.

`resources/seon/schema/program.edn:188-210` declares that conclusion as a
string while exposing no structured refusal evidence on the definition row.
`test/seon/sci/session_image_test.clj:117-123`, `166-177`, `212-217`, and
`318-323` assert the exact English strings and, in the first case, explicitly
assert that the unproven-Var evidence was removed.

## Owner

The terminal evaluation transaction that writes `:seon.code.def` rows and the
existing durable evaluation/receipt facts it can reference.

## Acceptance

- A definition row retains or references the minimal structured observations
  that prove why source replay is unsafe; it does not store a rendered reason.
- One pure query or render function derives the human explanation from those
  facts.
- Tests assert observation identity and definition-to-evaluation provenance,
  not exact prose.
- Cold restore continues to pre-intern every unrestorable name without
  re-executing its source.
