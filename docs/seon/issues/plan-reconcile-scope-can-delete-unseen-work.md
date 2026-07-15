---
type: issue
status: open
severity: friction
tags: [issue, agent, database, flow]
---

# Plan reconcile scope can delete unseen work

## Problem

`my.plan/reconcile!` treats a target agent's whole current open forest as the
baseline for a submitted document. The document carries neither a database
coordinate nor an owned or delegated root, so work created after the read—or
work outside the caller's intended subtree—can be retracted as absent.

## Evidence

`my.plan/document` returns only a raw tree or forest. `reconcile!` accepts a
caller-supplied target agent id, compiles against that agent's whole current
open forest, and retracts baseline ids absent from the submitted shape. The
plan-preload pilot observed a planner subtree reconcile drop two unrelated
address roots, and a document captured before a mid-turn message later drop the
unseen address step.

## Owner

The one document/reconcile boundary in `my.plan` and the existing Datahike
complete-coordinate transaction precondition.

## Acceptance

- Every document carries the complete immutable database coordinate and one
  exact owned or actively delegated root.
- Reconcile rejects a stale coordinate with a structured error and no datom or
  coordinate advance.
- Deletion is limited to authored nodes inside that root; message-linked
  address rows and unrelated roots cannot enter its baseline.
- Behavioral tests cover message arrival between document and reconcile,
  delegated subtree edits, unrelated roots, retry after an uncertain response,
  and restart with a pre-restart document.
