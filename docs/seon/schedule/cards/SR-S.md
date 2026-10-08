---
title: "SR-S: collection deletes stale blob staging files"
type: reference
status: ready
created: 2026-10-08
---

# SR-S: Collection deletes stale blob staging files

Ledger row SR-S (group E). Source: `storage-audit/storage-audit-opus.md` finding 15 and slice S. Model: Sonnet.

`seon.blob/stage-binary!` writes an oversized blob to `<process root>/blob-staging/blob-*.stage`
(`seon.blob/stage-file!`, HEAD `blob.clj:61-80`; process root = the parent of the store directory) and leaves the file
when the write is interrupted or its settlement is refused (`blob.clj:296-301`). Nothing else names the directory, so
those files are never removed (0 files on default today; unbounded on a laptop over time). A staged write lives for one
settlement, so a file older than a day belongs to no live publication.

## Files

- `resources/seon/schemas/seon.config.maintenance.edn` (one dial)
- `resources/seon/schemas/seon.maintenance.request.edn` (carry the dial on the request value)
- `resources/seon/schemas/seon.operator.collect.edn` (one optional result member)
- `src/seon/maintenance.clj`
- `test/seon/maintenance_test.clj`

## Schema

`seon.config.maintenance.edn`, inside the map:

```clojure
:staging-max-age-ms
[:int {:min 1 :seon.config/dial true :seon.config/default 86400000
       :description "A blob staging file older than this many ms belongs to no live publication; whole-store collection deletes it."}]
```

The schema default means no `config/default.edn` line is needed (`seon.config/registration-defaults`,
`src/seon/config.clj:165-183`).

`seon.maintenance.request.edn`, in `:value` (after `:seon.config.maintenance/log-retained-files`, line ~87):
`[:seon.config.maintenance/staging-max-age-ms {:optional true} :seon.config.maintenance/staging-max-age-ms]`. The
schedule copies every key this map declares from the cluster's effective config into the request
(`declared-maintenance-request-values`, `src/seon/schedule.clj:507-513`), as it does for `collect?`.

`seon.operator.collect.edn`: add `:staging-deleted [:int {:min 0}]` to the namespace map and
`[:seon.operator.collect/staging-deleted {:optional true} :seon.operator.collect/staging-deleted]` to `:result`.

## Functions

Add to `seon.maintenance` (after `collection-evidence`):

```clojure
(defn- sweep-staging!
  "Delete blob staging files older than `max-age-ms` before `now-ms` under `managed-root`; the count deleted."
  {:malli/schema [:=> [:cat :string [:int {:min 1}] :int] [:int {:min 0}]]}
  [managed-root max-age-ms now-ms] ...)
```

Body: the directory is `(io/file (.getParentFile (io/file (store-dir managed-root))) "blob-staging")` (`store-dir`,
`maintenance.clj:276-280`, is `<managed-root>/data/store`, whose parent is the process root `stage-file!` uses). If the
directory does not exist, 0. Else for each `File` from `.listFiles` whose name starts with `blob-` and ends with
`.stage` and whose `.lastModified` is below `(- now-ms max-age-ms)`, `.delete` it; count the deletions that returned
true. No recursion, no symlink following (`.listFiles` of one directory).

Change `collect-store!` (`maintenance.clj:522`) to two arities, `[managed-root operation-store]` (as today, no
sweep) and `[managed-root operation-store staging-max-age-ms]`, contract
`[:function [:=> [:cat :string :seon.store/store] :seon.operator.collect/result] [:=> [:cat :string :seon.store/store [:int {:min 1}]] :seon.operator.collect/result]]`
(no `[:maybe]`). In the 3-arity's success branch, when `staging-max-age-ms` is present, `(assoc result :seon.operator.collect/staging-deleted (sweep-staging!
managed-root staging-max-age-ms (System/currentTimeMillis)))` after `complete?` is known, before returning. The sweep
runs inside the root store hold, after the Datahike collection, so the reachability permit sequence is unchanged.

Change `collect!` (`maintenance.clj:658`): pass `(:seon.config.maintenance/staging-max-age-ms request)` when present
(3-arity), else call the 2-arity. `dry-run-store!` does not sweep.

## Proof

New `seon.maintenance-test/stale-staging-files-are-collected`, modelled on
`collection-reports-and-verifies-the-exact-store` (`maintenance_test.clj:433`):

```clojure
(deftest stale-staging-files-are-collected
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath (io/file repository-root "managed"))]
    (try
      (declare-cluster-config! managed-root)
      (let [dir (io/file managed-root "data" "blob-staging")
            _ (.mkdirs dir)
            old (doto (io/file dir "blob-old.stage") (spit "x") (.setLastModified 0))
            fresh (doto (io/file dir "blob-new.stage") (spit "y"))
            other (doto (io/file dir "keep.txt") (spit "z") (.setLastModified 0))
            result (maintenance/collect! {:seon.operator/repository-root repository-root
                                          :seon.operator/managed-root managed-root
                                          :seon.config.maintenance/collect? true
                                          :seon.config.maintenance/staging-max-age-ms 3600000})]
        (is (= 1 (:seon.operator.collect/staging-deleted result)))
        (is (not (.exists old)))
        (is (.exists fresh))
        (is (.exists other)))
      (finally (test-support/delete-recursively! repository-root)))))
```

`--test seon.maintenance-test seon.maintenance-schema-test seon.config-defaults-test`

## Cost

Expected: one directory listing per daily collection, proportional to the staging files present (normally 0).

## Deleted

Nothing.

## Commit

`bin/lane commit <lane> -m "SR-S: collection deletes blob staging files older than the staging-max-age dial" --test seon.maintenance-test seon.maintenance-schema-test seon.config-defaults-test resources/seon/schemas/seon.config.maintenance.edn resources/seon/schemas/seon.maintenance.request.edn resources/seon/schemas/seon.operator.collect.edn src/seon/maintenance.clj test/seon/maintenance_test.clj`

## Dependencies

None; no lane holds these files at `3f69939e4`. SR-ENUMa edits `collect-store!` too: land SR-S first (smaller).
