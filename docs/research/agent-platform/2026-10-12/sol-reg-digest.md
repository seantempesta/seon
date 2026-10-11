# Retained builder fixture and config-load attribution

The fixture temporarily changes a stored builder's documentation, then restores its original definition. Use the existing `seon.test-support/revision` producer for both writes; no digest or cache mechanism changes.

## Cause and change

C3 (`d02a78686`) added a docstring to `seon.schema/shape-entity-in`. The fixture assumed the original had no docstring: it retracted the temporary doc and copied the old digest. That deleted an original definition member, so the writer correctly refused. This is neither a stale snapshot nor an old-source digest. The fixture now holds the original doc and restores it through `seon.test-support/revision`, which calls `seon.db/definition-digest-of` on the final declaration. It replaces the handwritten retraction/digest transaction; work remains proportional to one declaration. The writer's contract and validation stay intact. The resolved task and proof tag retire together.

Malli's `malli.core/validator` and `malli.core/explain` own contract validation. Datahike's transaction upsert restores cardinality-one documentation; Seon's writer validates the final value. Pins inspected: Malli `dd13ff81139159778edb0a67b491eccc0baa991d`, Datahike `61bca1edd6bcef079f5c47e4f5f75df30549934d`.

## Config load

Fresh child probes on the same committed archive take 1.857 s with both class directories, 2.545 s without program classes, and 8.782–9.863 s without either. The dependency-first sample attributes 6.046 s to `datahike.api` require, 409 ms to Malli, 983 ms to `seon.error`, and 148 ms to `seon.config`. These sequential measurements include transitive loading. Round 3 used a nuked root: `seon.operator/launch-child!` deliberately supplies no compiled classes there; a child inherits its parent's classpath. This explains why the exact test passes in the ordinary candidate check (2.676 s) but reproduces its cost on a source-only classpath. No source-commit attribution follows from these samples. The existing config task is refined to the dependency/classpath owner; its 5 s bound is unchanged. Removing Datahike from schema loading or changing nuke/classpath semantics needs a separate designed slice beyond this fixture fix.

`script/seon/config_load_timing.clj` reproduces sequential require attribution in a fresh child on the archive's chosen classpath. It measures before rendering and shutdown. The measurements and standalone checks are in [evidence](sol-reg-digest-evidence.txt).

## Verification

Red before: run `60f593040b70`, fixture restore refused. Fixed test alone: `f8068e1a6e31`, 2 assertions, 0 failures/errors, 4.654 s; publication 3.247 s, adoption 348 ms, boot 10.759 s, total check 25.505 s. Config alone: `e0d2f097bb1b`, 3 assertions, 0 failures/errors, 2.676 s, total check 24.197 s. Complete archive publication and JVM startup are setup costs, not ordinary warm operations. No peak memory measurement was collected; default status was 929 MB used / 656 MB live before probing.

Full-tree lint: 0 errors / 1,541 existing warnings, 6.416 s. Default remains PID 94708, start instant 2026-10-11T21:22:23.023Z. Its existing degraded profile/cache/unowned-user observations are retained. No default lifecycle operation ran.

Foreign boundary: dirty http-kit checkout, `tmp/test-data-assertions/proofs.edn`, and unrelated untracked files were excluded. Existing reds in db/config and schema namespaces are not declared green; the gate names individual Vars. Central round 3 records roughly 340 historical reds; config's source-only timeout stays open, as does the separately owned flow cost test. This slice changes no production source, adds no tests, and removes two net test lines.
