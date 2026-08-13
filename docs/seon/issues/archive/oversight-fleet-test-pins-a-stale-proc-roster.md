---
type: issue
status: resolved
severity: friction
tags: [issue, flow, test, class/n2, wave/oversight-test]
---

# Derive the oversight fleet proof from the live proc roster

## Problem

The oversight proof pins the cluster plumbing roster to armer and renderer.
The live graph now also contains the search index proc, so the test fails when
the system accretes a declared proc.

## Evidence

At clean commit `48eb25ab7`,
`seon.oversight-test/a-booted-cluster-tells-its-live-fleet-story` expected
`#{:seon.cluster.agent/armer :seon.render.web/render}` and observed that set
plus `:seon.search/index`. The assertion at `oversight_test.clj:110-112` dates
to 2026-07-28, before the search proc. Evidence:
`tmp/full-gate-2026-08-10b.log:3311-3315`.

## Owner

Suspected owner: `seon.oversight-test`; the roster must be derived from the
same graph definition or query that oversight renders, not copied into a test.

## Acceptance

- The proof verifies every declared live plumbing proc is represented without
  enumerating a second roster.
- Adding or removing a proc changes one graph owner and the test follows it.
- Proc pass counts and output faces remain asserted.

## Resolution

Resolved by commit `5bc903010`.

The live-cluster proof now derives `declared-plumbing` directly from the
running Flow graph's `:procs` keys through `clojure.datafy/datafy`, then compares
that computed set with the proc identities rendered by oversight. The stale
armer/renderer literal roster is gone; pass-count and AI/HTML output assertions
remain in the same proof.

Source verification against the current test and fixing diff confirmed the
assertion follows the live graph definition and includes the search proc
without naming it. A fresh focused execution reached this test but was blocked
before its assertions by the unrelated in-flight R3 store-layout fixture
transition: the worker base attempted to reidentify `data/store` before that
new location had been populated. The source-level premise and its owning
construction are nevertheless complete in `5bc903010`; the foreign fixture
failure does not reopen this stale-roster defect.
