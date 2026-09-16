# The development store wipe — what deleted the keys (2026-09-16/17)

Read-only probe. All clock times UTC; the host clock is CST (UTC−6), so the
`…T1100Z` evidence names are the local session's labels for 2026-09-16 10:5xZ.

## 1. What the wiped store actually is

`tmp/orchestrator/refork/store-wiped-2026-09-17T1100Z/store/` holds **7 konserve
values, 28 KB**. Its roster key is intact and readable:

```
$ xxd .../1643fed1-9f2d-514e-b007-53bbb8682214.ksv   # konserve key :branches
… 6b 65 79 … 62 72 61 6e 63 68 65 73 … 6c 61 73 74 2d 77 72 69 74 65 … 64 62
  "key"        "branches"                 "last-write"                  "db"
```

Roster = `#{:db}` — one branch. The fresh store now at `data/store` has the same
konserve key carrying `db`, `current-src`, `cluster-default`. The wiped store's
`:db` branch record IS present (`0594e3b6-…ksv`, the same well-known key name in
both stores), together with a commit record, the schema-meta record and three
empty `pss/leaf` index nodes. The operator agreed at 10:54:
`tmp/orchestrator/refork/reset-2026-09-17T1100Z.log:3` — `flock free; roster
readable (1 branches)`.

**That shape is a genesis-fresh Datahike store, not a collected one.**

## 2. The Datahike-GC lead is falsified

`reference-code/datahike/src/datahike/gc.cljc:141` reads `:branches` and never
writes it; `sweep!` only deletes keys outside the marked set. A collect with a
nil/empty roster would have left `:branches` **naming `current-src` and
`cluster-default`** while their data vanished. The observed roster is `#{:db}`,
which only `d/create-database`'s genesis (or an explicit `delete-branch!` of
both) can write. No GC path rewrites the roster, so `seon.cluster.registry/collect!`
(`src/seon/cluster/registry.clj:519`) did not do this.

## 3. Timeline (falsifies the cold-gate-at-10:18 lead as the *start*)

| time (Z) | evidence | fact |
|---|---|---|
| 10:02:38 | advertisement in `tmp/orchestrator/fn-adopt2.log` | default JVM pid 17352 boots |
| 10:03:41 | `tmp/orchestrator/fn-adopt.log:1` | pid 17519 holds the root lifecycle lock running `init --dev default` |
| 10:07–10:16 | `logs/hook-debug.log:245-314` | edit-hook publications run and are *refused on content*, not on a missing store |
| **10:17:35** | `logs/current-source-failure.log` (91443/91443 population, then `:entity-id/missing`) | **a COMPLETE 91 k-row publication ran — the 3.6 GB store was healthy** |
| 10:18:11 | `tmp/orchestrator/gate-results/batch-61/platform.log` | batch 61 A platform tier begins |
| 10:19:58 | `logs/hook-debug.log:317` | last hook line; no further publication attempt |
| 10:54:49 | evidence copy mtimes; reset log | the 28 KB store is copied aside, `default` reforked |

So the wipe is bounded to **10:17:35 → ~10:50**, and the first three-gate window
(batch 61 A at 10:18:11) sits inside it. `bin/test-fast` history is not
available; `~/.local/share/fish/fish_history` last wrote 2026-09-08, so there is
no shell record of the window.

## 4. The two destructive paths that produce exactly this shape

Both are `(io/file <root> "data" "store")` with a root that can be **nil or
relative**, i.e. the class fixed at `86b4c8ff4` for `workers/`:

* `src/seon/operator.clj:277` — `(io/file managed-root "data")`, then
  `src/seon/operator.clj:294` `(fs/delete-recursively! managed-root path)` over
  `["clusters" "store" "store.lock" "blob-staging"]`. With `managed-root` nil or
  `"."` this deletes **the checkout's** `data/store`. This is the call whose
  output the 10:54 reset printed verbatim (`removed …/data/clusters,
  …/data/store, …/data/store.lock`).
* `src/seon/cluster/store.clj:281` — `create-store!` does
  `(fs/delete-recursively! store-dir store-dir)` then `d/create-database`, and
  `open-store!` (`src/seon/cluster/store.clj:361-366`) reaches it whenever
  `database-exists?` is false or genesis looks incomplete. Its product is
  precisely a 7-key, roster-`#{:db}` store.
* `src/seon/operator.clj:553` — `store-dir` is the same `(io/file managed-root
  "data" "store")` shape; `src/seon/cluster.clj:792` and
  `test/seon/test_support.clj:112` both fall back when
  `-Dseon.operator.root` is absent (`bin/test-fast:4` sets no operator root).

`test/seon/cluster/registry_test.clj:89` (`with-source-store`) is **not** the
culprit: it builds `tmp/registry-test/<uuid>/store`, and `open-store!` takes the
`flock` first, so it cannot open `data/store` while a live JVM holds it. But the
window 10:19–10:50 has no proof pid 17352 was still alive, and worker JVMs
demonstrably write into the **main checkout's** `tmp/` (`tmp/fn-test/…`,
`tmp/analyzer-test/…`, `tmp/armed-test/…` all carry window mtimes) — relative
paths in test code do resolve against this checkout.

## 5. Verdict and plan

**Verdict:** the store was **deleted and re-created from genesis**, not garbage
collected. The GC lead is falsified by the roster. The exact caller is not yet
named: no operator log, cluster log, or shell record covers 10:19–10:50, and the
evidence copy lost its mtimes (`cp -R` without `-p`), which would have separated
"recreated at one instant" from "swept over time".

**Plan, in order:**

1. **Make the deletion self-reporting.** `cleanup-root-under-lock!`
   (`src/seon/operator.clj:281`) and `create-store!`
   (`src/seon/cluster/store.clj:279`) must log root, resolved path, byte count
   and caller identity to `logs/` *before* deleting. Today a 3.6 GB deletion
   leaves no trace anywhere — the recurring "absence of signal reads as health"
   class.
2. **Unconstructable, not guarded.** A nil or relative root must refuse at the
   seam: `managed-data-paths` and `store-dir` take an **absolute, existing**
   root, validated by schema, so `(io/file nil "data")` cannot be spelled.
3. **The class regression** (what it must assert): a fixture's destructive
   operation refuses any path outside its own run root *by construction* —
   plant a **symlinked sentinel** pointing at a canary directory inside the run
   root and assert the sentinel's target survives (AGENTS.md: recursive deletion
   never follows symlinks), and assert that `cleanup!`/`create-store!` called
   with root `nil`, `""`, `"."` and a relative path each return a typed refusal
   naming the offending root — never a deletion.
4. **Preserve evidence with `cp -Rp`** in the refork drill so mtimes survive.

Bound: read-only, no gate, no operator command run.
