---
type: report
status: implementation in progress
created: 2026-09-21
tags: [agent-platform, boot, operator]
---

# B1b implementation evidence

The rewrite is in progress. The eight canonical destructive drills and default
replacement/platform gate are **pending**. No test pass is claimed here.
The owner authorized temporary boot/tool breakage between commits. Default has
not been stopped or replaced by this lane; the orchestrator owns that checkpoint.

## Changes and scope

`26143d5f2` adds destructive store admission after acquiring the existing sibling
FileLock, before database probing or deletion. The lock inode is retained.
Filesystem measurements moved to `seon.fs`; collection and log rotation moved to
`seon.maintenance`; bounded tooling subprocess execution moved to
`seon.cluster.process`. Publication, registry, schema/projection, search and SCI
acquisition semantics remain the installed owners.

The tooling-only changed-test report lock now refuses busy requests immediately.
Its former lifecycle queue/holder files were removed; existing subprocess execution
and reaping bounds remain. It has a process-local reservation before opening the
kernel lock, preventing a same-process second descriptor close from dropping the
held fcntl lock. This is tooling resource exclusion, not boot/reset lifecycle state.
A narrow concurrent-request regression remains pending.

## Early evidence (not completed boot)

`bin/seon help` and BB client namespace loading return successfully without launching
a JVM. Pure argv validation refuses reset without force, invalid cluster paths,
a cluster argument to down, and unknown commands.

First owned scratch child pid 36477/start `2026-09-21T22:35:32.815Z` reported
prepl 53899 before program loading, but the initially chosen `user` accept Var
failed because Clojure's server requires its namespace. Replaced with core
`io-prepl`'s supported `:valf` argument. Exact-root `down --force` reaped that child.

The retained second scratch child is pid 36681/start
`2026-09-21T22:37:46.670Z`, prepl 53938, root `tmp/b1b-initial-root`.
The real transport evaluation `(+ 1 1)` returned `2` before full program loading
and after a later source-analysis refusal. The later refusal retained its listener,
partial instance and store holder. Full boot is not yet proven.

Canonical source analysis exposed retired claim/lifecycle references in tests.
After conversion it exposed duplicate lint identity `4217c301d9de`: two warnings
at `src/seon/cluster/process.clj:96:1` for redundant declarations of
`matching-process-handle` and `process-start-instant`. Removing the useless declare
unblocks the input; the underlying identity class is filed separately as
[lint-identities-collide-for-multiple-findings-at-one-location](../../../seon/issues/lint-identities-collide-for-multiple-findings-at-one-location.md).

Exact diagnostic duplicate rows (identical identity, differing message):

```clojure
{:seon.lint/id "4217c301d9de"
 :seon.lint/file [:seon.fn.file/relative-path "src/seon/cluster/process.clj"]
 :seon.lint/type :redundant-declare :seon.lint/level :warning
 :seon.lint/message "Redundant declare: matching-process-handle"
 :seon.lint/row 96 :seon.lint/col 1 :seon.schema.admission/source :core}
{:seon.lint/id "4217c301d9de"
 :seon.lint/file [:seon.fn.file/relative-path "src/seon/cluster/process.clj"]
 :seon.lint/type :redundant-declare :seon.lint/level :warning
 :seon.lint/message "Redundant declare: process-start-instant"
 :seon.lint/row 96 :seon.lint/col 1 :seon.schema.admission/source :core}
```

## Size snapshot (2026-09-21, intermediate)

Actual `wc -l`: `bin/seon` 26; `script/seon/operator.clj` 368;
`src/seon/cluster/boot.clj` 437; total 831 against the approximately 900 target.
Boot exceeds its individual 420 target by 17 lines because the installed projection,
coherence, search and acquisition sequence is preserved until its owning cuts.
Final counts, exact path inventory and additions/deletions will follow the drills.

## First completed scratch boot

Publication produced `6ab1b46f-7eba-5cd7-b6d5-8bc9b09eb4f4`. Reloading the
new cluster/boot owners and replacing the partial instance IN THE SAME scratch JVM
produced readiness with `:seon.boot/missing-layers []`, one root agent,
`:seon.boot/ready-ms 9154`, prepl 54258, wanted web port 7994, actual URL
`http://127.0.0.1:54263`. HTTP GET `/` returned 200. This is a moved boot-path proof,
not one of the eight recorded drills and not a default replacement.

The existing long-lived MCP process returned transport failure with `error:null`
for explicit scratch-root JVM and SCI requests. Tool restoration remains pending;
raw operator PREPL remains usable. No successful MCP reconnection is claimed.
