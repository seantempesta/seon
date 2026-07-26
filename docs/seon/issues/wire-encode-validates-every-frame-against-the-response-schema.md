---
type: issue
status: open
severity: major
tags: [issue, database, runtime, flow]
---

# Encode one frame without validating it against the response schema

## Problem

`seon.db.transport.uds/encode` is the single encoder for BOTH message
vocabularies: `seon.db.protocol` request/response maps and
`seon.host.session` execution maps. Its projection step
(`seon.db.protocol/wire-envelope-projection`, `src/seon/db/protocol.cljc:1305`)
decides which schema-selected malli encoder to use by asking
`(schema/valid-candidate-value? ::response message)`. `::response`
(`src/seon/db/protocol.cljc:1255-1292`) is a ~30-arm alternative over every
database response shape.

An execution frame is neither a request (no `::protocol/operation`) nor a
response, so every single execution frame pays a complete failing validation
against all thirty response arms and then falls through to the plain walk it
should have used from the start.

## Evidence

Measured on this checkout (`clojure -M:writer`, Clojure 1.12.0, JDK 26,
20k-iteration timed loops after warmup):

```
uds/encode <database session-open request> =   4.2 us
uds/encode <execution invoke map>          = 465.3 us
  of which schema/valid-candidate-value? ::response = 457.0 us
  raw transit/write of the same map        =   3.9 us
  protocol/wire-projection (plain walk)    =   1.3 us

```

110x. The projection that the execution map actually uses costs 1.3 us; the
probe that decides to use it costs 457 us.

The cost is paid more than once per call:

- `seon.host.session.leaf/bounded-result` (`src/seon/host/session/leaf.clj:139`)
  encodes the value to measure its byte count;
- `seon.host.invoke/settle!` -> `leaf/prepare-frame` -> `encodable-frame?`
  (`src/seon/host/session/leaf.clj:96`) encodes the whole frame again;
- `leaf/write-prepared-frame!` -> `uds/write-frame!` -> `encode`
  (`src/seon/db/transport/uds.cljc:237`) encodes it a third time.

So one settled invocation burns roughly 1.4 ms of failing schema validation.
`seon.host.eval/wire-safe-value` (`src/seon/host/eval.clj:133`) pays it again
per evaluated form, as a try/catch probe.

The in-process cluster JVM driver pays it too: `seon.agent.driver.host:401`
calls `session/bounded-result` on a value that never crosses a process
boundary, purely to size it.

## Owner

`seon.db.transport.uds` owns bytes/framing/codec; `seon.db.protocol` owns the
database message vocabulary. The defect is that the codec asks the DATABASE
vocabulary to classify EVERY message. `wire-projection` (the total, 1.3 us
walk at `src/seon/db/protocol.cljc:298`) already produces a correct
Transit-safe projection with degradation paths for any value; the
schema-selected encoder adds no information the plain walk lacks.

Do not fork a second codec. Either make the caller name its schema (the
producer knows whether it is emitting a request, a response, or an execution
frame) or delete the schema-selected encoder and keep the one total walk.

## Acceptance

- Encoding an execution frame and encoding a database response cost the same
  order of magnitude; neither runs a full alternative-schema validation.
- One settled invocation encodes its payload once, not three times.
- R41 degradation still reports `::protocol/degraded-paths` for a value with
  no ordinary wire projection.
- A byte-size bound is measured without a throwaway encode, or the encode that
  measures it is the same one that gets written.
