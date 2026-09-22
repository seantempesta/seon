---
type: landing
status: repaired; healthy-reset-and-recorded-test-blocked
created: 2026-09-22
---

# Boot transaction provenance attribute

The declaration was never removed. At reported `a342298e5` and examined
`20e4ad941`, `resources/seon/schemas/seon.db.edn:245` contains
`:process [:and #:seon.db{:index true} :seon.db/ref]` inside `#:seon.db`.
Searching only the fully qualified spelling misses this declaration. No lost
docstring or narrower ref target exists in the implicated change to restore.

## Cause and repair

`git log -S':seon.db/process' --oneline -- resources src | head` is saved in
`tmp/boot-process-history.txt`. The actual regression is
`6d84f27fac7e98137b4bf9b718bd0909eb570f71`, **Rename error schema declarations
and their consumers together**, in `src/seon/schema/datahike.clj`:

```diff
@@ -202,7 +202,7 @@
-        facets #{:seon.db/identity :seon.db/unique :seon.db/index
+        properties #{:seon.db/identity :seon.db/unique :seon.db/index
@@ -219,7 +219,7 @@
-               (and (qualified-keyword? k) (some #(contains? properties %) facets))
+               (and (qualified-keyword? k) (some #(contains? properties %) properties))
```

The inner `properties` already names `(m/properties root)`. Iterating that map
passes map entries to `contains?`, instead of iterating the storage-property
keywords. Standalone stored attributes disappear from the derived installation.
The commit message and [its landing note](lane-error-schemas-2026-09-22.md)
describe a terminology rename, not retirement of provenance. The repair names
the set `storage-properties`; the existing declaration, writers and readers stay.
Exact original diff: `tmp/boot-process-cause.patch`.

## Dependency and proof boundary

Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`,
`reference-code/malli/src/malli/core.cljc:2581`, returns a compiled schema's
property map. The first-party consumer is
`src/seon/schema/datahike.clj:199` (`compiled-attribute-selection`). It receives
the carried projection and derives installation from its canonical roots; work
is proportional to declarations at population selection, not transaction reads.

Datahike `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`,
`reference-code/datahike/src/datahike/db/transaction.cljc:906`, expands supplied
transaction metadata into ordinary datoms on the transaction entity.
`src/seon/cluster.clj:1414` supplies the boot process lookup ref when committing
canonical schema rows. No dependency changes or second schema registry are needed.

The regression in `test/seon/boot_process_attribute_test.clj` uses the canonical
populated fixture. It checks current storage selection (so an old warm fixture
cannot hide the regression), the installed ref type, and a positive join from
the canonical schema row's population transaction to its boot process datom.
There is no hand-rostered production schema or synthetic process row.

Only the scratch root `tmp/boot-process-root` was operated. Before starting,
CLI status reported its directory absent; MCP runtime status returned no clusters.
These are unavailable readiness evidence, not health. Default was not operated.

Scratch JVM REPL probes:

```clojure
(let [p (seon.schema/declaration-projection (seon.schema.edn/packaged-forms))]
  {:declared (get (:seon.schema.projection/forms p) :seon.db/process)
   :selected (boolean (some #{:seon.db/process}
                      (seon.schema.datahike/database-attributes-in p)))})
; 67 ms: {:declared [:and #:seon.db{:index true} :seon.db/ref], :selected true}

(let [properties {:seon.db/index true}
      storage-properties #{:seon.db/identity :seon.db/unique :seon.db/index
                           :seon.db/component :seon.db/no-history? :db.secondary/only}]
  {:before (boolean (some #(contains? properties %) properties))
   :after (boolean (some #(contains? properties %) storage-properties))})
; 2 ms: {:before false, :after true}
```

These are host JVM evidence, not SCI or browser evidence.

## Cold reset and remaining foreign boundaries

Command: `bin/seon --root tmp/boot-process-root reset --force`.
The root was absent before this drill. PID 30076 started at
2026-09-22T17:08:10.448Z and exited after returning a boot failure at
17:10:18.344Z (127,896 ms from process start to failure). **Readiness ms is
unavailable: it did not become ready.** Schema population and program indexing
passed the originally reported provenance boundary. The next failure is
`config/default.edn:540–544`, which still names `seon.search/supplied-handle`,
deleted in `434c01f4c901efc960925bdff96770f83551a7f2`.
`tmp/boot-process-reset.log` preserves the complete refusal. CLI status during
boot was unknown, with missing layers (`tmp/boot-process-status-initial.edn`).
The JVM exited; subsequent MCP evaluation explicitly reported `repl-unavailable`.
Healthy default reset is not claimed. Permission to remove the foreign config
row was requested because the assignment prohibits editing another lane's files;
no response had arrived while the remaining scoped work was completed.

At 102 seconds, `jcmd 30076 Thread.print` showed publication waiting in
`seon.fn/commit-index-phase!` and the Datahike writer running
`seon.db/write-owned-values-error`; RSS observations were 3.7–4.7 GiB.
This is whole-program cold publication work, not the cost of the repaired
property membership check. Evidence: `tmp/boot-process-threads.txt`.

The direct from-zero proof follows the actual boot order: install canonical
storage declarations and process rows with `accrete-schema-population!`'s
`false` argument, then commit `instruction/seed-rows` with boot process
transaction metadata. All schema rows cannot precede their renderer functions:
the first probe's default `true` argument correctly refused missing renderers
(`tmp/boot-process-population-proof.log`). The corrected probe committed in
2,143.622875 ms, transaction 536870915, with installed `:db.type/ref`
(`tmp/boot-process-population-proof-2.log`). It uses the real store and writer,
but is an unarmed diagnostic, not a recorded canonical-fixture test pass.
The reproducible clock script is
`../research/measure-boot-process-population-2026-09-22.clj`; it requires an
absent scratch store path and releases the store in `finally`.

Recorded script command:

```sh
clojure -M:test docs/prds/agent-platform/research/measure-boot-process-population-2026-09-22.clj /Users/sean/src/seon/tmp/boot-process-population-store-3
```

Clock: **2,203.897375 ms**, committed true, provenance transaction 536870915,
installed ref type, exit 0 (`tmp/boot-process-population-clock.log`). This clock
includes canonical schema/process population and the instruction transaction,
excluding JVM load. The script and regression use the actual seed-row producer.

## Focused regression and load

Command:

```sh
bin/test-fast --paths src/seon/schema/datahike.clj test/seon/boot_process_attribute_test.clj -- seon.boot-process-attribute-test
```

Snapshot `tmp/test-runs/run.7zNTTQ`, HEAD `20e4ad941503ec0d19e9c0c90971ecd175848203`,
only the two named paths differed. The launcher acquired the packaged projection
and armed 1,684 contracts (1,678 program-armable). It then refused snapshot
admission: **Test recording requires a published current-src**. Run
`dd182e0c2e8b`, zero executions, zero assertions, no recorded green. The standard
launcher selected its repository recording authority; no manual default command
or evaluation was issued. Log: `tmp/boot-process-test-fast.log`.
The final regression also explicitly transacts the canonical instruction seeds
with provenance through `support/transacted!` and checks the returned datom.
It remains unexecuted because the recording authority is unavailable.

An isolated worktree at `e5021672f` plus the two owned paths loaded
`seon.cluster.boot` and `seon.boot-process-attribute-test`, exit 0:
`tmp/boot-process-head-load.log`. The earlier launcher snapshot had been cleaned
by its owner, so that absent directory was not reused. No foreign source or
configuration edits were carried into the isolated worktree. No cold test gate,
platform suite, adoption or browser proof was run. The broad publication/adoption
measurement script was not run: this slice repairs storage selection and records
its from-zero population clock; healthy reset remains blocked as described above.

The separate configuration defect is recorded in
[the issue authority](../../../seon/issues/boot-initialization-still-names-deleted-search-supplier.md).
Changed implementation: `src/seon/schema/datahike.clj`, 2 additions / 2 deletions.
Added regression: 38 lines. Added measurement script: 36 lines. Existing unrelated
documentation edits were preserved. Hook feedback reported pre-existing stale
dependency citations outside these paths and the existing `identity` shadow at
bridge line 58; neither is evidence of a failing new membership check.
