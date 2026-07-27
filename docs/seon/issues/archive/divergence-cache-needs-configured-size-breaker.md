---
type: issue
status: superseded
severity: blocker
tags: [issue, runtime, config]
---

# Divergence-cache maintenance needs its ruled config size breaker

## Evidence

R45 preprocessing design §8.1(a) rules a configured breaker for the one
complete no-history divergence-cache entity. S5 now writes that entity with
every durable program transaction, so its serialized complete delta can grow
with the divergence population even though the transition itself is
identity-delta-only.

There is no `:seon.config/*divergence*` schema/default/accessor. The nearby
`:seon.config/database-edn-cap` is a human-display bound and is not a cache
admission policy. `config/system.edn` and `src/seon/config/resolve.cljc` are
owned by the concurrent configlift lane, so S5 must not invent a local
constant or a second configuration path.

## Owner

The config manifest, resolve/acquisition path, and configuration accessor own
this fact. Runtime admission consumes the resolved value at cache-maintenance
admission.

## Acceptance

- One config fact and accessor cap the serialized divergence-cache delta.
- Both Bun and JVM maintenance reject an over-cap candidate before the program
  transaction, leaving program facts and cache unchanged.
- The rejection follows `:seon.config/on-core-error` policy and never creates
  a second cache or fallback serialization path.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
