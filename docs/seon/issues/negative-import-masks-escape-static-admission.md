---
type: issue
status: open
severity: friction
tags: [issue, sci, test, wave/sci-static-admission]
---

# Make static admission honor removed default imports

## Problem

The original eval-time schema/function/test lifecycle blocker is resolved and
has recurring build, runtime, reopen, and exact-replacement proof. One
downstream analysis mismatch remains: SCI persists a nil-mask when
`ns-unmap` removes a JVM default import, but the synthetic namespace prelude
given to clj-kondo cannot express that negative import. Static admission can
therefore accept a later bare `String` that SCI correctly refuses at
evaluation.

This is friction rather than a shipping blocker: runtime resolution remains
honest and the mistake becomes an agent-visible evaluation error. The lie is
that the earlier static pass claims the form is clean.

## Evidence

- `test/seon/cluster/turn_test.clj:604-681` and
  `test/seon/cluster/program_restart_test.clj:180-217` prove the persisted SCI
  nil-mask survives subsequent forms and a process restart.
- Under pinned clj-kondo `794a508d53df319bfb2f4db666315de6a3e56fff`, both
  `(ns my.agents.mask)\nString` and the same source after
  `(ns-unmap *ns* 'String)` report no finding. The current admission prelude
  drops an import component whose `:seon.ns.import/target-class` is absent
  because clj-kondo has no negative-import representation.
- The original blocker was closed in substance by the registration/indexing
  wave through `995ccec92`: current publication owns recurring canonical
  schema, function, namespace, and test rows. Keeping that completed history
  under an open blocker title made the backlog lie about what remains.

## Owner

The static source-admission boundary with an upstream-backed clj-kondo
representation. Destination: the **interop-expansion/static-admission wave**
that follows the current gate; do not add a second import registry or a hand
list of JVM defaults.

## Acceptance

- A persisted nil-mask for `String` makes the next bare `String` produce the
  same unresolved-class finding in static admission that SCI produces at
  evaluation.
- Positive imports and the existing alias/refer prelude remain exact.
- The proof uses the maintained clj-kondo representation; no Seon-only
  default-import roster or regex rewrite is introduced.

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76`.** Current code preserves the runtime mask but
still erases it from both static-analysis preludes:

- `src/seon/sci/eval.clj:501-544` stores an import row with only
  `:seon.ns.import/local` when SCI reports a nil target, and reconstructs the
  nil entry for runtime acquisition.
- `test/seon/cluster/turn_test.clj:645-696` proves that the `String` nil-mask is
  durable and takes effect in a fresh SCI context.
- `src/seon/fn/analyzer.clj:228-239` and `src/seon/fn.clj:470-481` both use
  `(keep :seon.ns.import/target-class)`, so the same row contributes nothing to
  clj-kondo's synthetic namespace form.

The maintained clj-kondo checkout is now `57252e079`; a source search there
still finds no negative-import representation to carry this fact.
