---
title: "C7: acquisition retains its source's merge base"
type: research
status: evidence
created: 2026-10-12
---

# C7: acquisition retains its source's merge base

`seon.cluster.agent/acquire-context!` created work branches through `seon.cluster.registry/branch!` and wrote
`seon.cluster.registry/branch-fork-entity` itself. It now calls `seon.cluster.registry/fork!` with the captured
commit and its held database, leaving merge-base retention and the fork entity with their lifecycle owner.

## Broken things and cause

On HEAD e1c424db5, candidate run d0f754df530f reproduced all four requested regressions: 4 executed,
3 passing assertions, 2 failing assertions, 3 errors, 5410 ms. The retirement regression received
`:seon.cluster.registry/from nil` when a nested branch tried to retain its parent's absent base ref;
the MCP regression then had no hosted branch, and the inherited-test regression answered no value.
P1 dc5e1d348 added retained base refs inside `seon.cluster.registry/fork!`, but acquisition still bypassed that
owner. H5 a7195675a changed SCI acquisition, not this branch creation seam.

The tagged proof had a separate producer defect: `seon.registry-isolation-test/cluster-projection` called
`seon.config/apply!` on its left connection inside its right connection's custody. It now passes its connection
to `seon.db/call-with-custody`, as the fixture's other writes already do. No production write rule changes.

## Change and library seam

Acquisition still names its exact captured commit (`:seon.cluster.registry/from`) and its held immutable
database (`:seon.db/db`). The fork owner creates the retained base ref and writes the fork entity; acquisition
only assigns an agent when one was requested, under the acquired connection's explicit custody. The duplicate
fork-entity construction disappears. Work remains constant per branch plus existing SCI acquisition, with
one additional retained roster ref per acquired work branch; release already removes that ref.

Datahike pin 61bca1edd6bcef079f5c47e4f5f75df30549934d: `datahike.versioning/branch!` accepts a branch keyword
or commit UUID, selects that stored head and changes its branch configuration before publishing its roster
entry. Seon already composes this seam in `seon.cluster.registry/fork!`; no new branch mechanism is needed.
Contracts retain the existing named request and execution schemas, armed through `malli.core/-instrument`;
`malli.core/validator` caches validation on the compiled schema. Malli pin dd13ff81139159778edb0a67b491eccc0baa991d.

## Proof

The source-only intermediate candidate ran the four regressions in 8072 ms: 9 passing assertions, no failures,
and only the isolation helper's foreign-custody error remaining (run e26a7b7f8a01).

The complete candidate e4360c826e9e47285748421399b21cc7411dcfec passed run b1f07cf0b804:
7 tests, 43 assertions, no failures or errors, 9935 ms. Named tests:

- `my.agent-test/retiring-a-branch-this-process-still-connects-is-refused-as-a-value`
- `seon.registry-isolation-test/two-clusters-own-their-contracts-and-share-stable-host-wrappers` (tagged task proof)
- `seon.mcp-entrance-test/an-agent-runs-its-own-test-on-its-own-branch`
- `seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition`
- `seon.cluster.agent-test/releasing-an-execution-releases-the-executions-acquired-through-it`
- `seon.cluster.agent-test/concurrent-acquisitions-of-one-key-build-once`
- `seon.cluster.agent-test/a-fresh-agents-environment-costs-two-forks`

The last test proves warm acquisition stays below 25 ms and 15 MB allocated, with two binding inheritances,
no fresh-path `seon.sci.eval/acquire!`, and the acquired connection as SCI custody. Exact allocation and retained
heap were not sampled separately. Candidate publication took 3781 ms, adoption 454 ms; throwaway readiness
8767 ms, process boot 12714 ms, whole check 33859 ms. These are the authorized platform check, including source
publication and a process boot, rather than one ordinary acquisition. Source net -3 lines; test net +2 lines.

`clj-kondo --lint src script test --cache false`: 6720 ms, 0 errors, 1534 existing warnings.
No namespace-wide green claim: only the named Vars were run; central-round's other pre-existing reds remain
outside this slice. The gate proves publication, adoption, boot and tests on HEAD plus the claimed paths.

## Foreign boundary and live verification limit

Default was available but faulted: one open error signature, one unowned namespace, four hot untested warnings
and an overlay-cache warning. `src/seon/db.clj` is held by reg-projection and excluded from this candidate.
The exact live probe was MCP JVM mode, agent root, cluster default:

```clojure
(let [c (seon.cluster.boot/connection "default") d (seon.db/db c)]
  {:branch (get-in d [:config :branch]) :commit (seon.db/commit-id d)
   :acquire-contract (:malli/schema (meta #'seon.cluster.agent/acquire-context!))})
```

It failed before evaluation in `seon.sci.eval/interpret-source-namespaces!`: `seon.db/q` received a vector
where its database input was expected. The edit-hook entrance failed at the same boundary (807 ms for the
source edit, 266 ms for the test edit). Existing task `seon.sci.eval/a-changed-programs-overlay-reads-the-agents-database`
owns this C2 finding. No uncommitted code was loaded into default's JVM, and default was never stopped,
reset or restarted. Installed live proof remains unavailable; candidate proof is positive.
