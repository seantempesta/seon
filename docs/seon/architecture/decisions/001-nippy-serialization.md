---
type: decision
status: implemented
date: 2026-03-05
---

# ADR-001: Nippy for Inter-JVM Serialization

## Context

Seon's inter-JVM channel (orchestrator to agent flow messages over TCP) used length-prefixed EDN (`pr-str`/`read-string`). Three confirmed data corruption paths: `byte[]` not serializable in EDN, `Float` silently coerced to `Double`, and metadata lost on roundtrip.

## Decision

Replace EDN with Nippy (`fast-freeze`/`fast-thaw`) for all inter-JVM TCP communication in `seon.flow.harness.channel`.

## Rationale

- **Same format Datalevin uses.** Datalevin's client-server wire protocol (port 8898) defaults to Nippy. Using the same format eliminates a serialization boundary.
- **Complete type fidelity.** Nippy natively handles every JVM type in the pipeline: `Float` preserved (not coerced to `Double`), `byte[]` roundtrips, metadata preserved, nil is first-class (type-id 3).
- **3.7x faster.** REPL benchmarks: ~16us/op vs ~58us/op for typical flow message envelopes (10K iterations, warmed).
- **Already a dependency.** Nippy is on the classpath via Datalevin -- no new dependency.
- **No tagged literal maintenance.** EDN required custom `print-method` and reader entries for each non-native type (e.g., `Instant`). Nippy handles all types natively.

## Rejected Alternatives

- **EDN** -- human-readable but lossy. Three corruption paths confirmed in REPL.
- **Transit+JSON** -- supported by Datalevin (format byte `0x01`) but not its default. Extensible but requires per-type handler registration, same maintenance burden as EDN tagged literals.
- **Fressian** -- Rich Hickey's format for Datomic. Pure Java, no Clojure-native experience, not used anywhere in Datalevin. No reason to introduce it.

## Scope

Nippy is used for the **harness channel bridge** (inter-JVM TCP). Context persistence (`ctx.clj`) and other file-based storage remain EDN for human readability. Datalevin's own LMDB storage uses custom binary encoders per type, not Nippy (Nippy is only the fallback for its `:data` KV type).

## Details

- `docs/prds/schema-unification/research/serialization-findings.md` -- full research with REPL verification
- `src/seon/flow/harness/channel.clj` -- implementation
- [[components/database]] for wire protocol details
