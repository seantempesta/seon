---
type: decision
status: implemented
date: 2026-03-05
tags: [decision, architecture, schema, database, flow]
---

# ADR-001: Nippy for Inter-JVM Serialization

## Context

Seon's inter-JVM channel (orchestrator to agent flow messages over TCP) used length-prefixed EDN (`pr-str`/`read-string`). Three confirmed data corruption paths: `byte[]` not serializable in EDN, `Float` silently coerced to `Double`, and metadata lost on roundtrip. (The DB layer is unaffected -- on the JVM track `[JVM track — paused]` Datahike runs in-process and never crosses this channel.)

## Decision

Replace EDN with Nippy (`fast-freeze`/`fast-thaw`) for all inter-JVM TCP communication in `seon.flow.harness.channel`.

## Rationale

- **Complete type fidelity.** Nippy natively handles every JVM type in the pipeline: `Float` preserved (not coerced to `Double`), `byte[]` roundtrips, metadata preserved, nil is first-class (type-id 3).
- **3.7x faster.** REPL benchmarks: ~16us/op vs ~58us/op for typical flow message envelopes (10K iterations, warmed).
- **Already a dependency.** Nippy is on the classpath via konserve (the Datahike store backend) -- no new dependency.
- **No tagged literal maintenance.** EDN required custom `print-method` and reader entries for each non-native type (e.g., `Instant`). Nippy handles all types natively.

## Rejected Alternatives

- **EDN** -- human-readable but lossy. Three corruption paths confirmed in REPL.
- **Transit+JSON** -- extensible but requires per-type handler registration, same maintenance burden as EDN tagged literals.
- **Fressian** -- Rich Hickey's format for Datomic. Pure Java, no Clojure-native experience, not in our dependency tree.

## Scope

Nippy is used for the **harness channel bridge** (inter-JVM TCP). Context persistence (`ctx.clj`) and other file-based storage remain EDN for human readability. Datahike's konserve LMDB backend uses its own value encoding (Nippy via konserve-nippy), independent of the harness channel.

## Details

- `docs/prds/schema-unification/research/serialization-findings.md` -- full research with REPL verification
- `src/seon/flow/harness/channel.clj` -- implementation
- [[components/database]] for wire protocol details
