---
type: issue
status: open
severity: blocker
tags: [issue, sci, runtime]
---

# Indexed core function can be falsely marked installed

## Problem

When a cluster program contains a newly added first-party function but the
long-lived JVM has already loaded that namespace without the new Var,
acquisition can record the function as installed even though SCI has no Var to
resolve. `kernel/ensure-function!` then trusts the installed set and invocation
fails only at the later resolution boundary. Absence of the host binding is
being recorded as successful installation.

Plain `require` is not a repair: it is a no-op for the already-loaded namespace.
Blind `require :reload` would evaluate the current filesystem globally and can
change other clusters independently of their program commits.

## Evidence

- `src/seon/sci/eval.clj:1053-1091` — `host-namespace!` returns an existing
  namespace before its plain `require` branch.
- `src/seon/sci/eval.clj:1131-1160` — first-party binding and marking correctly
  operate only on names found in `ns-interns`.
- `src/seon/sci/eval.clj:1657-1670` — every core function row is subsequently
  sent to `install-row!` with contract installation skipped.
- `src/seon/sci/eval.clj:850-875` — the function branch marks skipped/evaluated
  rows installed without checking for an installed SCI Var.
- `src/seon/sci/kernel.clj:162-178` — `ensure-function!` treats membership in
  the installed set as authoritative.
- Live falsifier, 2026-09-06: `seon.cluster.agent` was already loaded;
  `seon.cluster.agent/render-identity-ai` existed in the freshly forked
  cluster's indexed program but was absent from the host namespace and
  unresolved in SCI. Selectively evaluating the new host definitions and
  creating another fresh fork made SCI resolution succeed.
- The indexed program contained function source for `render-identity-ai` and
  private helper `identity-data`, but no function row for ordinary private
  `identity-selector`; per-function source evaluation is therefore not a
  complete fallback.

## Owner

`seon.sci.eval` and the first-party publication/process-generation boundary.

## Acceptance

- A core function is never marked installed unless its SCI Var resolves.
- A published core function missing from the compatible host generation causes
  a flat, evidence-complete acquisition refusal naming the function, program
  identity, and process generation.
- A recurring test starts from an already-loaded namespace lacking a newly
  indexed Var and proves that absence cannot be reported as installation.
- Any later on-demand loader consumes the exact cluster program version and
  states which indexed declarations are sufficient for its execution model;
  it does not reload process-global current filesystem source as a fallback.

The broader loading choices remain proposals in
`docs/prds/context-generation/research/first-party-function-loading-audit-2026-09-06.md`.
