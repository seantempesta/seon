---
title: Missing storage nodes in the 03:02Z broad check
type: research
date: 2026-10-14
status: trigger found, loss not reproduced
---

# Missing storage nodes in the 03:02Z broad check

**Default's data: no evidence it is at risk.** The missing node was in a throwaway check JVM's own store. That store
was an APFS clone at `data/check/slot-1/data/store`, not default's `data/store`. No process ever opens another
process's store, so default's collection cannot reach a check store, and a check store's collection cannot reach default's.
Default's own 03:00 collection completed at 03:01:05Z: `complete? true`, every root verified, 105,938 objects swept
(read in JVM mode with `seon.maintenance/last-collection`). This lane found no way for a collection on default to delete
a node that default's committed heads, or their in-window ancestry, still need. What **is** unprotected is a value held
in memory, and that holds on default as in any JVM (see 2). The deletion in the check JVM is not yet explained (see 4),
so this stays a high-priority open defect.

Evidence: [batch verification step 5](batch-verification.md#5-broad-writer-tier-no-new-red) (run `1df1e41711ad`,
36 reds), its operator log `data/clusters/default/logs/operator-check-20261014T100315.628.edn`, and the
[GC allocation note](gc-allocation.md).

## 1. Which collection ran, on which store

The collection was the throwaway JVM's **own** scheduled `root/maintenance/compact`, which runs
`seon.maintenance/collect!` as a real collection, not a dry run. `seon.schedule/fire-due!` fired it when that JVM booted.
It was not default's collection, and it was not the GC lane's work: that lane ran only dry runs, on
`tmp/gc-one-enumeration/store`.

- All 72 `:node-not-found` errors in run `1df1e41711ad` name one address, `6acef00b-63f1-4062-92b2-5d4fc12b00ee`, and one
  store: a `konserve.tiered.TieredStore`. That is a test world's overlay (`seon.cluster.store/open-overlay!`). Its
  backend is the `BackingFilestore` at `/Users/sean/src/seon/data/check/slot-1/data/store`.
- The address is a squuid. Its time prefix decodes to 02:59:23Z. It is an AEVT `Leaf` (109 datoms of
  `:seon.db.vector/position` and `:seon.db.vector/present`). Pid 10875 wrote it while it built the template
  `data/check/template/de1f48c23…_slot1` (02:58:44 to 02:59:31Z). That template still holds the leaf, and both
  template heads reach it (`:current-src` 6acef012 and `:cluster-default` 6acef013). The verification note said it
  was "a commit of the store default had just collected". That is wrong.
- The template's facts (read on a clone) show the cause. `root/maintenance/compact` was asserted at 02:59:31.368Z, it
  has never fired, and `:seon.config.maintenance/collect? true`. `seon.schedule/fire-due!` measures dueness from
  `task-created-at` when a task has no fire. So every JVM that boots from this template after 03:00:00Z fires the
  03:00 nominal immediately. The slot-0 template's tasks were asserted at 03:01:19Z, so they are not due today. The
  36-red run was the first slot-1 boot after 03:00 (about 03:02:15Z). The slot-0 runs at 03:02:57, 03:08 and 03:11 passed.
- Reproduced: I booted a slot-1 check (`--at HEAD`, the same four namespaces, `--include-long`) and took clones of its
  store every 1 to 3 s. The store recorded fire `cace10b13f26` (nominal 03:00:00Z, observed 03:22:56.733Z). Its
  receipt shows a real collection from 03:22:56.7 to 03:23:10.6Z, `swept-objects 9`, `verification-pass-swept 1`,
  `complete? true`. The test request's checkpoint branch was created at 03:23:04.7Z, inside that window. Without
  intervention, **every check JVM collects its store at boot whenever its template predates the latest 03:00 UTC.**
  Templates live for days, so from tomorrow that is every check.

## 2. How a check JVM gets its store, and what a collection can delete

`seon.operator/candidate-tests!` takes a slot lock and calls `seeded-store!`, which runs `cp -c -R` from the template
of the commit default runs (one template per slot) to the fixed slot path. The store id comes from the path
(`seon.cluster.store/datahike-configuration`), so each slot has its own template. No two processes share a store:

| Question | Answer, with the owner |
|---|---|
| Can default's collection delete a check JVM's nodes? | No. The two stores are different directories. |
| Can a check JVM delete default's nodes? | No. The rehearsal runs inside default (`seon.cluster/rehearse-source!`). The check JVM writes only its clone. |
| Inside one process, can an in-flight commit lose nodes? | No. The sweep cutoff is `datahike.gc-guard/safe-point`. `datahike.gc/gc-storage!` holds the sweep permit, and `branch!`, `force-branch!` and `delete-branch!` all wait on the same store-id gate. Overlays share the store id, so their in-flight writes move the safe point earlier. |
| Can an overlay delete disk nodes? | No. `:datahike/overlay` connects with `:write-policy :frontend-only`, so writes and deletes go to memory and `-keys` lists only the frontend. |
| What does the mark leave out? | Every root that exists only in memory: overlay heads, held database values, and any commit that is neither a disk head nor in a head's ancestry newer than `remove-before`. |

Two docstrings claim protection the mark does not give:
- `seon.cluster.registry/plain-cutoff` says no commit "a held value on it" names is pruned. A held value is never marked.
- `seon.test/clean-request-handle` says the request branch "roots its commit for the members". That branch lives in the
  overlay, so it roots nothing for a disk collection. The disk checkpoint branch is what keeps that commit.

`seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value` covers the held-value class (a
value read after its nodes were collected). That makes its single failure on HEAD (run `e335e49d815e`) the same class
of problem.

## 3. "Map literal must contain an even number of forms"

This is a consequence of the same incident, not a separate cause. No blob or source file was truncated.
`seon.test-support/reply` reads the value printer's text back with `clojure.core/read-string`. The printer marks a cut
inside a map as `"…"…+N` or `…N`, and that is not EDN. Reproduced:
`(read-string "{:message \"abc\"…+3808}")` throws exactly this message. Vectors happen to read (`[1 2 …15]`). The
cut happened because the reply carried the `:node-not-found` error. `datahike.index.persistent_set/CachedStorage`
`restore` raises that error with `:store store` in its data: the whole store record, serializers included, where a
reference would do. That gives two separate defects:
- the test reads printed text, where it should read the reply's data;
- the error copies the store, where it should hold a reference.

## 4. Reproduction on store copies: the loss did not recur

| Probe | Result |
|---|---|
| Five slot-1 checks (two at HEAD, two at HEAD run concurrently, one replaying candidate `950dd05e6`), each running the boot collection | 0 `node-not-found`. Each ended with the same 4 reds that were already there. |
| Datahike dry run on a template clone: both heads advanced past the leaf (the shape adoption and boot leave), then `gc-storage` with `remove-before` set to epoch, now − 1 h, or now | The leaf is never a candidate. The only candidate is the retired `:building-source-10875-…` branch key. |
| 15 real `gc-storage` passes on a clone after the same advance | The base commit's AEVT tree is complete. One address is absent by design: the fused root, which is stored inside the commit record, not as its own object. |

The template, the lineage and the mark all keep this leaf in every interleaving I built. The deletion in run
`1df1e41711ad` therefore needs a condition these probes did not produce. Two things are not established:
- **Whether the file was deleted at all.** Every failing write reads this leaf first, because AEVT
  `:seon.db.vector/position` is needed by every `:seon.fn/arities` write. One address across 72 errors fits a deleted
  file, and it equally fits a filestore read that returned nil for the whole run. The run's store was deleted with
  its slot, so the two cannot be told apart now.
- **Which part of the boot collection's mark or sweep misjudged it**, if it was deleted. This lane refuted these
  candidates: cross-process collection, overlay deletes, online GC (`[:online-gc :enabled?]` is unset), freelist
  address reuse (empty without online GC), and the compact-head and fused-root handling in `datahike.gc/reachable-in-branch`.

## 5. Fix at the owner, and red proofs

1. **A throwaway store is never collected.** A check store is deleted whole when the check exits, so collecting it
   only costs time and puts maintenance into the tests' timing. `seon.operator/launched-throwaway!` already marks the
   JVM `:seon.operator/throwaway? true`. Its launch configuration should declare
   `:seon.config.maintenance/collect? false`, the existing dial. This is a configuration value that removes the work,
   not a check. A template's history is not missed work for the clone's process. Red proof: a new
   `seon.operator`-owned test boots a throwaway from a template whose compact task predates the latest nominal, then
   asserts its store records no `:seon.maintenance.receipt/id`. That fails today: the receipt above exists.
2. **The mark covers every live root, or nothing holds one.** An overlay head is a root the disk collection cannot
   see. Either `seon.test/clean-request-handle` keeps the shared checkpoint branch for as long as any overlay forked
   from it is open (it already does, so the owner should state that this is the guarantee and correct the docstring),
   or the collection's `:datahike.gc/reachable-extension` in `seon.cluster.registry/collect-and-inventory!` adds the
   commits held by open overlays and contexts. Red proof: the existing
   `seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value`, run repeatedly; it is the
   flaky one.
3. **Find the deletion.** Until this is found, a collection on default is trusted only through its own root
   verification. The next step is the first slot-1 check after tomorrow's 03:00, if (1) has not landed: run it with
   a store clone every second, and diff the store's keys before and after the boot collection against its
   candidates. That turns "nil read" versus "deleted file" into a measurement.
4. **Fix the error and the reader.** `:node-not-found` should carry the store id, not the store. That is a Datahike
   fork change at `CachedStorage` `restore`. `seon.test-support/reply` should read the MCP reply's data, not its
   printed text.

## Measurements and limits

Every experiment ran in throwaway check JVMs or in my own JVMs on APFS clones under the scratchpad. No collection or
reset ran on default, and default ran only one JVM-mode read. Each check took about 100 to 105 s. One Datahike dry
run on a clone took about 2 s. The reference-code/datahike working tree was briefly checked out at `c9e57c6` to read
source, then restored to `seon/overlay-file-frontend` at `62209ee`, with a clean status.
