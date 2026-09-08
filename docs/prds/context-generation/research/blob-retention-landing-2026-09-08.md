---
type: research
status: implemented; verification blocked
date: 2026-09-08
tags: [blob, storage, runtime]
---

# Blob retention landing — 2026-09-08

Implemented the root-wide 512 MiB budget, oldest-unreferenced-first reclamation,
and registration through the existing scheduled maintenance portfolio. The
private-function boundary is resolved. Verification remains incomplete because
the second focused gate rejected another lane's plan renderer arity change.

## Implementation

`src/seon/cluster/registry.clj` exposes its existing `referenced-blobs` with a
Malli contract and an explicit history option. Existing collection includes
history; retention queries current datoms across every roster branch. There
is no second schema traversal.

`src/seon/blob/retention.clj` holds Datahike's exclusive sweep permit from
roster/reference derivation through deletion. It uses Konserve binary metadata,
`last-write`, and `konserve.impl.defaults/key->store-key`; no payload is read.
`Files/readAttributes` with `NOFOLLOW_LINKS` measures physical bytes including
metadata. Non-regular files and absent timestamps refuse inventory. Candidates
sort by write time, then digest; deletion stops at the budget. Referenced bytes
survive even above budget, with an explicit excess in the durable result.

`resources/seon/schemas/seon.config.blob.edn` declares the dial once;
`config/default.edn` supplies 536870912. The retention schema declares the
request and durable result. Excess is absent when within budget.

`src/seon/schedule.clj` registers `root/maintenance/blob-retention` once per
minute. The ordinary handler request now carries the actual connection and
configured budget. No independent timer or execution service was added.

The new regression uses the canonical published file-store fixture, real blob
writes, explicit Konserve timestamps, a sibling Datahike branch, and actual
reference retraction. It checks oldest-first deletion, sibling-reference
survival, idempotence, excess reporting, and reclamation when only historical
references remain. Its corrected version has not completed the gate.
The schedule regression now checks connection identity and excludes that opaque
value from request-map equality.

The scheduled-error regression exposed a second defect: Datahike rejected an
Integer evidence byte count inside its transaction function. `seon.error/prepare`
now constructs that stored count as a Long. Live error evidence named
`:seon.error/data-size`, value 19776, and the required `java.lang.Long` class.
The corrected constructor still needs its post-change gate.

## Grounding and measurements

Read AGENTS.md and the agent-record-and-turn-loop PRD end to end, including
sections 2, 4a, 10, and 12; read the named scheduler-mining/root-maintenance
research as historical evidence and checked the current schedule owner.
Dependency seams read: Datahike `gc_guard.cljc`, `gc.cljc`, and
`api/specification.cljc`; Konserve `gc.cljc`, `core.cljc`, `filestore.clj`,
`protocols.cljc`, and `impl/defaults.cljc`, all under `reference-code/`.

Initial `bin/seon status`: root footprint **0.35 GiB**, usable filesystem
**586.08 GiB (31.5%)**. A no-follow file census of `data/store` found
**1,148 files / 160,582,484 apparent bytes**. Different scopes; neither was
blob-only. The owner selected the proposed **512 MiB** default on restart.
Restart status reported **2.30 GiB** for the root. The corrected live retention
call measured only **336,000 physical blob bytes**.

Reproduce the apparent-byte census:

```python
import os
sizes = [os.stat(os.path.join(root, name), follow_symlinks=False).st_size
         for root, directories, names in os.walk("data/store", followlinks=False)
         for name in names]
print({"files": len(sizes), "apparent_bytes": sum(sizes)})
```

## Proofs and exact gate boundary

MCP JVM mode used no root/cluster arguments. Queries on default confirmed the
536870912 config fact and new scheduled task. Its first invocation at
2026-09-08T18:09:00Z failed because the initial implementation passed options to
Konserve `get`'s not-found arity and received a channel. The four-argument call
corrected this. A later direct live invocation returned:

```clojure
#:seon.blob.retention{:bytes-before 336000, :bytes-after 336000,
                      :deleted-count 0, :reclaimed-bytes 0}
```

This exercised loaded code following partial in-place development adoption;
it does not prove source convergence or successful scheduled completion.
One explicit adoption retry followed the source-changed diagnostic.

First focused gate, before corrections:
`bin/test seon.blob.retention-test seon.blob-test seon.schedule-test seon.cluster.registry-test`
ran **28 tests / 142 assertions / 9 failures / 3 errors**. Retention, handler
request equality, and scheduled error writing failed. Each exposed cause was
corrected; no green result is asserted for those edits.

Second focused gate exited **1 before executing tests**, during published-base
preparation. Its exact errors were `test/my/plan_test.clj:73`, `:196`, `:230`,
and `:440`: **my.plan/render-plan-html is called with 2 args but expects 1**.
`src/my/plan.clj` was another lane's uncommitted edit in the snapshot. The same
boundary blocked development publication. It is independently recorded in
[the plan renderer issue](../../../seon/issues/plan-renderer-arity-change-blocks-development-publication.md).

Per the assignment's explicit stop-on-foreign-gate-breakage rule, no further
gate was started after that result. **Bare bin/test and bin/test --platform
remain unrun.** The next focused gate must also include `seon.error-test`, then
run bare and platform gates and prove successful scheduled completion on
default. No provider call or cluster refork was performed by this lane.

## Files and cleanup

Files touched: `config/default.edn`;
`resources/seon/schemas/seon.config.blob.edn`;
`resources/seon/schemas/seon.blob.retention.edn`;
`src/seon/blob/retention.clj`; `src/seon/cluster/registry.clj`;
`src/seon/schedule.clj`; `src/seon/error.clj`;
`test/seon/blob/retention_test.clj`; `test/seon/schedule_test.clj`; this note.

Every launched shell session ended. Failed test roots remain under the runner's
retention policy with their evidence. No foreign root was removed. The temporary
virtual-thread-inclusive JVM dump was removed. `git diff --check` passed.
Unfinished: post-correction gates, full development adoption, successful
scheduled completion, and any defects exposed by those proofs.
