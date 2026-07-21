---
type: research
status: complete
tags: [research, agent, flow, pod]
---

# First post-unit-1 child-lifecycle exit — 2026-07-15

## Decision

Unit 6 does not have an implementation-ready source change until unit 1
publishes one stable child/backend descriptor and proves that the old pod
subtree is absent after its group leader dies. That dependency is a coordinated
live checkpoint, not Unit-6 source work.

After that checkpoint, the first source change is deliberately smaller than
the older combined capability/receipt plan: add one private non-production
disposable-child lifecycle adapter under `seon.eval`, one repository-owned
hostile fixture, and focused lifecycle/frame tests. Do not move application
eval or provider work, mutate receipts, add actor capabilities, add a warm
pool, or run Inspect model trials in this slice.

Production eval receipts changed after the earlier audits. Commit `d4bd67ea`
now persists the existing running receipt before execution and terminally CASes
the same row; recovery can win without a late completion overwriting it. The
still-open durable eval-position contract is a separate database-evidence gate
in [[../../../seon/issues/multi-form-eval-order-is-not-durable]]. Neither
receipt wiring nor positioning helps prove child disposal, so neither belongs
in the first lifecycle experiment.

## Reconciled current state

- [[parent-capability-child-lifecycle-contract-2026-07-15]] correctly defines
  the eventual immutable parent capability, receipt, and effect-authority
  boundary, but its exact implementation boundary predates the receipt commit
  and combines several consumers of containment with containment itself.
- [[synthetic-disposable-child-hostile-gate-2026-07-15]] has the current narrow
  sequencing: stable unit-1 descriptor and dead-subtree proof first, then only
  a private adapter, hostile artifact, and direct/hard measurement.
- The current roadmap repeats both versions. For scheduling, the newer narrow
  boundary governs. The roadmap should be reconciled after the current shared
  source freeze; this read-only unit does not edit it.
- `seon.eval/eval-batch!` remains the sole application evaluator. The new
  adapter is a private lifecycle experiment beneath that owner, not a second
  evaluator or a production dispatch path.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source-grounded constraint |
|---|---|---|
| Unit-1 process owner | final descriptor not yet published to Unit 6 | Unit 6 consumes the exact selected executable, artifact, backend, environment, and parent-death contract. It must not choose Node, Docker, process groups, or packaging independently. |
| Host Node.js | `v26.4.0`, V8 `14.6.202.34-node.21`, release `2022edf3e32ce28ee08b17f8566243a090dacd95` | `tmp/reference-node-v26.4.0/doc/api/child_process.md` and `lib/internal/child_process.js`: retain the exact `ChildProcess`; `kill()` only sends; `killed` is not death; `close` follows exit plus stdio closure; delayed naked-PID signaling is unsafe. |
| Packaged Node.js | historical `seon:slice1` uses `v22.23.1` | The old image is not selectable evidence: its Node differs from the audited host, its executable is omitted from `PATH`, and its measured permission mode allowed a listener. The unit-1 descriptor must settle this identity first. |
| ClojureScript self-host | `1.12.145`, tag `bd23d9a2475d822ea8dfd65deaa6732428b9ed25` | Exact source is available in `reference-code/clojurescript`; compiler/analyzer/result state remains parent process-local. The fixture does not compile or evaluate application forms. |
| Datahike | `9ada755087228e10cfb179fa5779ce227a6ed220` | Exact maintained source is in `reference-code/datahike`. No connection, writer/feed socket, transaction, or receipt enters this experiment. |
| Malli | selected `0.20.0`, tag `4c054bd7d042e70d60b83b9f07fb765bc103037f` | Closed parent/child frame schemas reject unknown or authority-bearing fields before dispatch. |
| SCI | `0.13.53`, tag `b4917436550c857a18b8f6a4a8b5b26356acc2c4` | Existing sandbox semantics remain unchanged and are not claimed as hard containment. |
| Piscina | reference `23a6c2e94735216c6978679fe7b8ea0b5666683b`, not selected | Listener cleanup and rejection patterns are reference material only. Worker limits exclude external memory and cannot supply this boundary. |
| Inspect AI | reference `05322696`, tag `0.3.246` | Inspect consumes deterministic proof only after the lifecycle gate; it does not own child launch or disposal. |

## Prerequisite checkpoint, not a source change

Unit 1 must hand off one source-frozen descriptor and prove all of these against
that exact identity:

1. selected child executable, argv/environment construction, artifact digest,
   backend digest, and parent/death ownership are explicit;
2. ordinary stop, SIGINT, dead pod-group leader, and retained native-branch
   interruption all leave the prior subtree/backend absent before replacement
   readiness; and
3. a clean restart reproduces the same descriptor and absence result.

The shortest dependency falsifier is to start a synthetic descendant, kill the
pod group leader rather than asking the child to cooperate, and attempt
replacement readiness. If readiness can return while the old descendant or
backend resource still exists, Unit 6 remains blocked. A successful ordinary
`down`, signal-send return, or group-leader exit does not satisfy the gate.

## First implementation-ready source boundary

The first Unit-6 implementation owns exactly these new paths:

- `src/seon/eval/child.cljs` — private parent adapter with closed frame
  schemas, exact retained descriptor/handle, bounded decoder and output,
  absolute deadline, one close path, TERM/KILL escalation, and `close`-joined
  disposal;
- `test/seon/eval/child_test.cljs` — deterministic direct-backend schema,
  lifecycle, race, output-bound, and no-residual-resource regressions; and
- `test/fixtures/seon/eval/disposable_child.mjs` — repository-owned
  non-production modes: `ordinary`, `sync-loop`, `term-refusal`, `heap`,
  `array-buffer`, `external-buffer`, `output-flood`, and `ambient-probes`.

The hard-backend live arm must invoke the same fixture through the public
unit-1 descriptor and existing operator. It does not justify another runner or
a duplicate backend configuration. The exact generated artifact and backend
digests are evidence fields even when the checked-in fixture is the source.

Protected consumers for this slice are `src/seon/eval.cljs`,
`src/seon/eval/internal.cljs`, `src/seon/client.cljs`,
`src/seon/agent/runtime.cljs`, `src/seon/runtime/recovery.cljs`,
`src/seon/agent/turn.cljs`, `docker/Dockerfile`, and `shadow-cljs.edn`. The
unit-1 descriptor owner is also protected and consumed only through its public
handoff.

## Shortest source falsifier

Launch `term-refusal` through the adapter, invoke the one owned close path, and
observe this exact sequence:

1. TERM is sent through the retained `ChildProcess` and is insufficient;
2. KILL is sent through that same retained handle after the bounded grace;
3. the adapter resolves only on `close`, after stdout/stderr closure; and
4. post-close inspection finds no descendant, pipe, listener, timer, decoder
   buffer, backend resource, or readiness artifact while the pod event loop and
   writer ping remain responsive.

The slice fails immediately if it treats `child.killed`, signal-send success,
`exit`, a naked PID, or a timeout without `close` as disposal. It also fails if
any residual resource survives or parent health regresses. Run this falsifier
before expanding the fixture matrix.

## Acceptance evidence

- Closed startup/result/diagnostic schemas reject unknown fields and every
  child-supplied actor, run, turn, eval, coordinate, grant, deadline, artifact,
  or backend field.
- Parent evidence retains exact executable, argv, non-secret environment-key
  names, artifact/backend digests, deadline, output/frame/PID/CPU/memory
  bounds, terminal handle event, and backend resource identity.
- Decoder, stdout, stderr, frame count, and individual frame size are bounded;
  overflow selects the same disposal path and cannot grow parent memory
  without bound.
- Direct and hard arms cover every fixture mode. Only the hard backend may
  claim total-memory, network, or parent-death containment; V8 old-space flags,
  permissions, and RSS polling remain diagnostics or defense in depth.
- Ordinary, synchronous-loop, TERM-refusal, heap, `ArrayBuffer`, `Buffer`,
  output-flood, and ambient-probe failures kill only the disposable child;
  pod, writer, web readiness, and subsequent normal REPL work remain healthy.
- Every arm ends with zero processes/backend resources, descendants, pipes,
  listeners, timers, decoder buffers, capability sockets, queue entries,
  ports, or readiness artifacts owned by the experiment.
- Retain raw evidence for at least 30 sequential ordinary/TERM/KILL samples and
  at least 10 allocator/protocol samples. Report median, p95, p99 when sample
  size supports it, maximum, and the source/artifact/backend identities.
- No database connection, provider credential, `my.*` function, application
  eval, receipt mutation, capability effect, warm pool, or Inspect model trial
  participates in this slice.

## Ordered handoff

1. Unit 1 publishes the stable descriptor and frozen dead-subtree proof.
2. Unit 6 runs the dependency falsifier without source edits.
3. If green, implement only the three owned paths and pass the focused direct
   falsifier.
4. Run the same artifact through the hard backend, retain the measurement
   matrix, and prove parent health plus complete disposal.
5. Only then schedule parent capability/refused-effect probes, durable eval
   position, application eval/provider cutover, warm slots, and Inspect model
   trials as separate dependent boundaries.

The final Unit-6 graduation gate remains production application eval through
the proven disposable boundary with durable ordered receipts, exact
parent-stamped authority, hard memory/death containment, recovery, and normal
agent work after every hostile case. This report closes only the first
post-unit-1 scheduling boundary.
