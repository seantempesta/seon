---
type: research
status: blocked
date: 2026-09-08
tags: [blob, storage, runtime]
---

# Blob retention: protected reachability integration required

No retention implementation landed. The required existing reachability owner
is inside the assignment's explicitly protected `src/seon/cluster/*` paths.
This note records the integration needed; it does not report retention green.

## Measurements and proposed default

`bin/seon status` reported default alive, PID 85105, generation
`9bdd08c1-8472-4b39-b020-50113b24f940`, web URL
`http://127.0.0.1:7994`, root footprint **0.35 GiB**, and filesystem usable
**586.08 GiB (31.5%)**. A separate no-follow file census of `data/store`
reported **1,148 files and 160,582,484 apparent bytes**. These measure
different scopes; neither is a blob-only inventory.

Chosen proposed default for `:seon.config.blob/max-bytes`:
**536,870,912 bytes (512 MiB)**, above today's entire reported root footprint.
This is a proposed storage decision, not an installed or enforced dial.
Referenced bytes may themselves exceed the budget; retention must report that
remaining excess and preserve those blobs.

Reproduce the exact apparent-byte census from the repository root:

```python
import os
sizes = [os.stat(os.path.join(root, name), follow_symlinks=False).st_size
         for root, directories, names in os.walk("data/store", followlinks=False)
         for name in names]
print({"files": len(sizes), "apparent_bytes": sum(sizes)})
```

## Existing mechanisms and the required protected hunk

Sources read: `src/seon/blob.clj`; Datahike's
`reference-code/datahike/src/datahike/gc_guard.cljc` and `gc.cljc`;
Konserve's `reference-code/konserve/src/konserve/gc.cljc`; the reachability
and collection seams in `src/seon/cluster/registry.clj`; and the task
registration/invocation seams in `src/seon/schedule.clj`.

- `src/seon/blob.clj` publishes bytes and their database references under
  the existing `:blob` reachability permit. Retention must acquire the same
  store's exclusive sweep permit before deriving references and hold it
  through deletion.
- `src/seon/cluster/registry.clj:316–369` owns the sole schema-derived
  blob-reference discovery. `referenced-blobs`, `branch-blobs`, and
  `blob-digest-attributes` are private. A read-only default JVM probe
  confirmed all three exist and all three carry `:private true`.
- `src/seon/cluster/registry.clj:345` queries a history view, so its present
  policy retains historical references as well as current datoms. This is
  stronger retention than the requested current-datom policy.
- `src/seon/cluster/registry.clj:521–531` unconditionally supplies its own
  `:datahike.gc/reachable-extension`. The public `collect!` therefore does
  not expose a blob-only budget operation; it also collects database objects.
- Konserve `sweep!` selects by whitelist and timestamp, then batches in key
  enumeration order. It supplies neither oldest-first byte selection nor a
  blob-only inventory. Calling whole-store GC is not the requested operation.

Required integration: expose one contracted blob-reference operation from
the existing registry owner, taking the store and a held exclusive sweep
permit, verifying that permit, and deriving the union across the current
branch roster. Accrete an explicit current-datom policy while preserving the
existing collector's history policy. Both paths must reuse the existing
schema traversal. The retention owner can then use that operation while
holding the permit and select only binary blobs, ordered by Konserve
`last-write` with digest as the deterministic tie-break, deleting the shortest
oldest-first prefix that meets the budget.

The implementation must not duplicate reference discovery, resolve private
Vars dynamically in production, or accept references computed before sweep
admission. This protected integration is a prerequisite to a working scheduled
handler, not a reason to install an unenforced config dial.

The existing root portfolio is `src/seon/schedule.clj:45–76`; handlers are
invoked by its existing per-agent proc. Task registration should use that
same portfolio after the handler exists. No independent timer is needed.

## Live evidence and unfinished work

The default JVM answered `(+ 1 1)` with 2. The complete second probe was:

```clojure
(mapv (fn [s]
        (let [v (ns-resolve 'seon.cluster.registry s)]
          {:symbol s :present (boolean v)
           :private (boolean (:private (meta v)))}))
      '[referenced-blobs branch-blobs blob-digest-attributes])
```

Every row returned `:present true`, `:private true`. No production Var was
changed, no cluster was reforked, and no provider call was made.

Read AGENTS.md and the agent-record-and-turn-loop PRD end to end, including
its binding sections 2, 4a, 10, and 12. Read the named scheduler-mining and
root-maintenance design as historical design evidence; the current schedule
source supersedes its statement that no scheduler exists.

Unfinished: protected reachability integration; config declaration and
default; blob inventory and byte reclamation; scheduled registration;
canonical scratch-store regression proving oldest-unreferenced-first and
cross-branch referenced survival; default live proof; bare, subject, and
platform gates. **Test tally: no tests run; no retention proof.**

Initial working tree was clean. Only this landing note is changed by this
lane. No background shell, scratch cluster, or worktree was created.
