---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, architecture, rendering]
---

# Retain live eval values in the owning JVM host

## Problem

The universal data browser requires deep drill to run beside the live eval
value, but host-tier evals currently lose that addressable value when their
invocation returns. A Bun-only value-sampling transport would work for
`seon.eval/lookup-result` while falsely claiming universal support for agents
whose `eval-batch!` runs in the JVM host.

The ruled fix is one bounded process-local eval-id slot in the existing JVM
host session lifecycle. The JVM samples its own live value and returns only
the bounded ordinary projection. The parent must not receive the raw value as
a fallback, reparse persisted `:seon.eval/result-edn`, spawn a replacement
runtime, or introduce another value authority.

## Evidence

Implementation-readiness report
[[../../prds/source-cleanup/research/unit-1g-value-sampling-transport-implementation-readiness-2026-07-20]]
at `cfadf4e9` grounds the mismatch against both current execution transports.

`src/seon/execution/host.cljs` routes `eval-batch!` through the one lane-keyed
dispatcher: agents carrying `:seon.execution.host/eval-socket-path` execute in
the retained JVM `seon.host` session, while other agents execute in the Bun
child. Bun already has `seon.eval/lookup-result`, backed by the bounded
child-local `globalThis.result` slot.

`src/seon/host.clj` evaluates each host-tier form in `eval-batch-result` and
temporarily holds its raw `:seon.eval/value`. `wire-safe-value` preserves a
Transit-safe value in the returned envelope or replaces a host object with a
display string. The terminal recording path persists only the bounded
`:seon.eval/result-edn`; no JVM session map retains the raw value under the
managed eval id after the invocation. Consequently a later drill request has
no honest source value in the process that evaluated it.

This is not solved by sending the invocation envelope's raw value to the pod:
that materializes the very value the bounded sampler is meant to protect,
loses process-local identity, fails for SCI vars and other host objects, and
creates a second parent-owned value path. It is also not solved by selecting a
fresh current runtime after retirement or tier change: a replacement does not
own the prior process's value.

Unit 1F is still the dependency-critical producer. It must first freeze one
public bounded function from a decoded `seon.render.value/drill-request` plus
live value to the closed `drill-result`. This issue blocks Unit 1G transport,
not Unit 1F descent and paging.

## Owner

The existing execution-session lifecycle owns the fix:

- `seon.host` retains each successful host-tier raw value by its managed eval
  id in a bounded process-local slot beside the owning SCI context;
- the existing JVM session retirement, replacement, park, and eviction paths
  clear or honestly invalidate that slot;
- `seon.execution.host` addresses only the already-retained serving lane and
  uses its existing per-agent queue, generation/session identity, correlation,
  cancel, timeout, and settlement machinery; and
- the Bun child continues to use the existing `seon.eval/lookup-result` slot.

`seon.render.value` owns bounded descent and paging. `seon.web.serve` later
owns HTTP path decoding and eval-to-agent authorization. Neither becomes a
live-value store, and no new protocol, registry, compatibility namespace, or
raw cross-process fallback is permitted.

## Acceptance

- After Unit 1F freezes, one closed value-sample request/result/error contract
  invokes the identical bounded producer in both Bun and JVM serving
  runtimes.
- A host-tier eval retains its raw value under the managed eval id without
  sending that raw value to the parent merely for sampling. A later request in
  the same JVM session drills the original process-local value.
- Retention is explicitly bounded and uses the existing result-slot eviction
  policy. Eviction produces the same honest unavailable/recompute meaning as
  the Bun slot; it never reparses `:seon.eval/result-edn` as the live value.
- Parent and runtime independently reject malformed paths, excessive segments,
  narrower-limit inconsistency, unsafe offsets, and
  `offset + page-size > max-realized` before lookup, descent, or realization.
- Counter-backed infinite sequence proofs show each accepted page touches at
  most `offset + page-size + 1` source items in both runtimes; a poisoned next
  item remains untouched and every rejected request touches zero.
- Missing, parked, retired, restarted, replaced, or mid-request-lost owners
  settle exactly once as unavailable, never spawn or retry a fresh runtime,
  never accept a stale generation/session response, and leave no queued or
  active request behind.
- Changing an agent's tier after an eval cannot redirect the old eval id to a
  runtime that never owned it. The request reaches its recorded retained owner
  or returns unavailable.
- Focused protocol, Bun execution, host dispatcher, JVM host, eval-result, and
  renderer tests pass at one artifact digest. Live proof pages one large Bun
  value and one large JVM value, then retires each owner and observes an honest
  unavailable projection from both.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
