---
type: issue
status: open
severity: blocker
tags: [issue, flow, database]
---

# Bind clean-or-force evidence to one exact managed generation

## Problem

The first clean-or-force coordinator draft could report a missing process
record as absent while its readiness door was still accepting, combine a pod
quiescence response with a different generation re-read by `stop!`, accept a
successful response body under an error HTTP status, and return clean for an
empty or unknown target set. Its nominal operation deadline also stopped at
the HTTP boundary, allowing each later containment wait to renew its own
clock. After those cuts were repaired, a live overloaded pod exposed that the
HTTP quiesce phase still inherited the entire selected agent-turn timeout before
the operator could reach containment drain. Any one cut could let restart,
reset, or retained branch cleanup proceed from a false clean claim or make a
forced close wait many minutes before supervision begins reclaiming processes.

## Evidence

- `stop!` preserved a readiness breadcrumb for an unmanaged listener but
  returned nil, which the coordinator classified as absent.
- Pod quiescence checked one record, addressed a mutable port file, and then
  called a two-argument `stop!` that selected the record again.
- The portable lifecycle response did not carry the managed process
  generation.
- HTTP status membership and response-schema validity were checked separately;
  `409`, `500`, or `503` plus a valid success body could reach clean
  classification.
- The public request had no closed schema, and filtering an empty or unknown
  target collection produced zero results whose aggregate fell through to
  clean.
- `await-terminal!` created a new shutdown-grace deadline for every selected
  process instead of consuming the coordinator's one absolute monotonic
  deadline.
- A write-saturated disposable pod made `cluster close` wait in the loopback
  quiesce request for the full operation deadline. The containment owner,
  workload, and execution child remained live because bounded containment
  drain had not yet been reached.

## Owner

`seon.dev.process` owns one closed request, exact-record quiesce/drain cut,
bounded loopback client, component classification, and aggregate result. The
portable response in `seon.runtime.lifecycle` carries the immutable generation;
the pod supplies it from its containment-injected environment.

## Acceptance

- A missing record plus any accepting managed door is containment-uncertain;
  only proved door absence is absent.
- The pod response generation equals the exact record passed unchanged into
  `stop!`; a record swap or stale endpoint can never classify clean.
- HTTP `200` pairs only with `quiesced? true`; typed error statuses pair only
  with `quiesced? false`.
- Empty, missing, unknown, or malformed target/operation requests fail their
  closed schema before effects.
- Forced writer evidence retains bounded status, digest, and byte-count
  forensics after its private containment directory is removed.
- Pod quiescence, every containment control exchange, terminal wait, legacy
  inverse, writer, and watcher consume one nonrenewed absolute deadline.
- Pod application quiescence consumes at most the existing lifecycle reserve;
  failure then reaches containment drain within the larger operation deadline
  and classifies the stop as forced.
- Focused Babashka tests cover those cuts, dependency-safe order, completed
  uncertainty prefixes, and one real bounded loopback EDN exchange before any
  public caller consumes the coordinator.
