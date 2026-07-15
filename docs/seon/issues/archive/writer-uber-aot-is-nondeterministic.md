---
type: issue
status: resolved
severity: blocker
tags: [issue, component, flow]
---

# Make the writer uberjar content reproducible

## Problem

Two clean `writer-uber` builds from identical source, dependency, and toolchain
inputs produced different normalized content digests. The release manifest
could not treat the standalone writer as one immutable compatibility-set
member even after cross-target build serialization.

## Evidence

The artifact build AOT-compiled `seon.db.server` and the Proximum secondary
adapter. Clojure AOT recursively compiled loaded transitive dependency sources;
otherwise identical JVM runs emitted different captured-local slot order in
generated classes. The established normalized digest hashes sorted entry names
and entry bytes, so ZIP timestamps and ordering could not explain the mismatch.

Commit `be30f420` removes `:gen-class` and Clojure AOT from this artifact.
`java/seon/DatabaseServerMain.java` is the deterministic entry point;
it requires and invokes the one source-loaded `seon.db.server/-main` at process
start. Two clean builds now share normalized digest
`d7011dacb7192decc826b37b014502ee372f362bc26a4a0c7e44a56ebd4e2deb`. Raw ZIP
bytes still differ in metadata as expected. `java -jar` reaches the real writer
preflight and exits 11 only because the proof environment omits `SEON_EMBED`.
The active PRD retains the exact digest while default runtime admission runs.

## Owner

The one `build.clj/writer-uber` artifact entry mechanism and the canonical
source-loaded `seon.db.server` implementation.

## Acceptance

- Two clean builds from identical declared inputs have the same normalized
  sorted-entry content digest.
- The artifact contains dependency-owned secondary implementation classes
  without recursively AOT-compiling transitive Clojure source.
- `java -jar` reaches the canonical writer preflight and server implementation.
- No second Clojure server namespace or package-only runtime path exists.
- Raw ZIP metadata differences do not change application or compatibility-set
  identity.
