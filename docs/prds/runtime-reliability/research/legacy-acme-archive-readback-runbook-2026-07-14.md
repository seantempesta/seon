---
type: research
status: active
tags: [research, archive, database]
---

# Legacy ACME archive and read-back runbook — 2026-07-14

## Scope and result

This audit closes the read-only identity-discovery half of the stable and
display-v3 preservation gate. It queried each already-open Datahike connection
through its owning writer's loopback socket REPL, inspected process and Git
metadata, and measured available storage. It did not start or stop a process,
open a second connection, transact data, copy a cluster, hash store contents,
or alter either legacy checkout.

The protected current-tree file
`docs/prds/repl-autosuggest/research/shared-schema-section-2026-07-13.md` was
not read, hashed, or changed.

The live identity endpoint is suitable, but the archive still requires an
owner-approved maintenance window and archive root. The safe order is **stop
pod, capture final writer identity, stop writer, copy closed cluster**. The
earlier manifest order captured identity before stopping the pod and therefore
left a last-write race.

## Read-only endpoint and capture shape

Each legacy writer exposes `clojure.core.server/repl` on loopback only. The
checkout-local `bin/seon-server-call` sends one form, half-closes the socket,
parses the final EDN map, and enforces a deadline. The already-open connection
is resolved through `seon.server.registry/resolve-conn`; this neither creates a
database nor opens another store handle.

| Lane | Writer | Port file | Endpoint |
|---|---:|---|---|
| stable | PID 30873 | `seon-stable/tmp/seon-writer-repl-port-acme` | `127.0.0.1:7981` |
| display-v3 | PID 45003 | `seon-display-v3/tmp/seon-writer-repl-port-acme` | `127.0.0.1:7983` |

The capture expression must return only bounded projections: logical database
name, `datahike.db.interface/-max-tx`, the pulled latest transaction entity, a
SHA-256 over the attribute-sorted installed schema, schema attribute count,
selected entity counts, and blob-reference counts. Run it with:

```bash
WORKTREE=/Users/sean/src/seon-stable
PORT=$(cat "$WORKTREE/tmp/seon-writer-repl-port-acme")
"$WORKTREE/bin/seon-server-call" --port "$PORT" --timeout 30 "$CAPTURE_EXPR"

```

`CAPTURE_EXPR` is a single `(do ...)` form that:

1. requires `datahike.api`, `datahike.db.interface`, and
   `seon.server.registry`;
2. resolves `:acme` with
   `(registry/resolve-conn {:seon.server.registry/db-name :acme})`;
3. dereferences that connection once;
4. computes the basis with `(dbi/-max-tx db)` and pulls `[ * ]` at that eid;
5. canonicalizes `(d/schema db)` as attribute-sorted `[attribute
   (sorted-map properties)]` rows before hashing; and
6. runs count-only Datalog queries for `:my.blob/hash`, `:seon.agent/id`,
   `:seon.eval/id`, `:my.plan/id`, and the prompt/reply blob refs.

This is the exact audited expression. It performs no transaction, connection,
release, file operation, or registry mutation:

```clojure
(do
  (require '[datahike.api :as d]
           '[datahike.db.interface :as dbi]
           '[seon.server.registry :as registry])
  (let [conn (:seon.server.registry/conn
              (registry/resolve-conn
               {:seon.server.registry/db-name :acme}))
        db @conn
        basis (dbi/-max-tx db)
        schema-rows
        (mapv (fn [[attribute properties]]
                [attribute (into (sorted-map) properties)])
              (sort-by (comp str key) (d/schema db)))
        schema-bytes (.getBytes (pr-str schema-rows) "UTF-8")
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        schema-bytes)
        schema-sha (format "%064x" (java.math.BigInteger. 1 digest))
        qcount (fn [attribute]
                 (or (d/q '[:find (count ?entity) .
                            :in $ ?attribute
                            :where [?entity ?attribute]]
                          db attribute)
                     0))
        prompt-refs (set (d/q '[:find [?blob ...]
                                :where
                                [_ :seon.agent.turn/prompt-blob ?blob]] db))
        reply-refs (set (d/q '[:find [?blob ...]
                               :where
                               [_ :seon.agent.turn/reply-blob ?blob]] db))
        turn-refs (into prompt-refs reply-refs)]
    {:archive/db-name :acme
     :archive/basis-t basis
     :archive/latest-tx (d/pull db '[*] basis)
     :archive/schema-attr-count (count schema-rows)
     :archive/schema-sha256 schema-sha
     :archive/blob-projection-count (qcount :my.blob/hash)
     :archive/turn-prompt-blob-ref-count (count prompt-refs)
     :archive/turn-reply-blob-ref-count (count reply-refs)
     :archive/turn-distinct-blob-ref-count (count turn-refs)
     :archive/turn-blob-hashes-resolved
     (count (d/q '[:find [?hash ...]
                   :in $ [?entity ...]
                   :where [?entity :my.blob/hash ?hash]] db turn-refs))
     :archive/agent-count (qcount :seon.agent/id)
     :archive/eval-count (qcount :seon.eval/id)
     :archive/plan-count (qcount :my.plan/id)
     :archive/autocomplete-attrs
     (->> schema-rows
          (map first)
          (filter #(re-find #"autocomplete|typeahead|needle" (str %)))
          vec)}))
```

Do not use the request UDS for this capture: its retained query operation does
not expose schema identity or the transaction entity in one bounded response.
Do not connect a current writer or a standalone Datahike process to either live
path.

## Captured live identities

These values were captured on 2026-07-14. They are checkpoints, not final
archive identities; rerun the same expression after the owning pod has exited.

| Field | stable | display-v3 |
|---|---|---|
| Logical database | `:acme` | `:acme` |
| Basis/latest transaction | `536870984` | `536877667` |
| Latest transaction time | `2026-07-13T18:52:55.544Z` | `2026-07-13T02:30:48.938Z` |
| Schema attributes | 202 | 269 |
| Canonical schema SHA-256 | `0d98d8b1f1246b36703b3edd5b450a1470a153573a16352ee42882c7521b3199` | `aceac1c468682aa1c1428e2eb28a8b5ed7f1d8547c47d18d8eeeeb06cec18ec6` |
| Blob projection entities | 6 | 533 |
| Prompt/reply/distinct turn blob refs | 3 / 3 / 6 | 269 / 260 / 529 |
| Distinct turn blob refs resolving to a hash | 6 | 529 |
| Blob files on disk | 302 | 1,966 |
| Agents / evals / plans | 3 / 11 / 2 | 18 / 533 / 78 |

The extra files beyond current `:my.blob/hash` projections are not orphans by
definition: they can be historical, superseded, or referenced by another
projection. Archive the whole `blobs/` directory. Classification can happen
only against the restored database history.

Display-v3's installed schema includes 29 autocomplete/typeahead attributes,
including `:seon.repl.autocomplete/rating`,
`:seon.typeahead/transition`, `:seon.typeahead/offers-edn`, and
`:seon.typeahead/worker-sha`. Stable has no installed attribute whose name
matches `autocomplete`, `typeahead`, or `needle`.

## Historical runtime locks

The running JVM command line confirms the dependency actually loaded, not only
the worktree declaration.

| Lane | Checkout HEAD | `deps.edn` blob | `deps.edn` SHA-256 | Datahike | Konserve | basis-file SHA-256 |
|---|---|---|---|---|---|---|
| stable | `609c40065efd7ae058cd62fe7a927d96f34b7a51` | `abbc5782ad8e3187c8c2d85611f98e14435b1a9a` | `921c9f4b99cb66b4901841de4ba7d731b9c1d38a033b4ca22ebed858817c2110` | Git `67934f650fae30924ac115c899cd3412d90dcacb` | Git `df6818d43ea3363a808cd051c0d68917f1b987a9` | `7e9d5b78cc96d4940fd32b1752723aac322d48df756c18592ed07bb621d77b86` |
| display-v3 | `b7be18be5758c91a970de6bae50388e6f5232908` | `b937c5591623558c15da12d66c319ebc05247ffb` | `dceef71dde9cf91d34341f357288f59018c4ceb77084ca50d7fde54f3665b045` | Git `6e2d9beeb5002ba025e2f3aa69cd9111afd7abf8` | Maven `0.9.353` | `1c479b6075805cfb9d45fc04c1b49829774263a9b5a177f286e8a549beccdcae` |

Both running writers use GraalVM Java 25, the `:simd:fork-deps:writer` basis,
`reference-code/datahike/src-secondary`, and
`io.replikativ/konserve-jdbc` `0.2.91`. Preserve each checkout's
`.cpcache/3960494174.basis`, committed `deps.edn`, source HEAD, Datahike tree
commit, and the stable Konserve tree commit. A Git SHA alone is insufficient if
the fork remote later disappears; the final archive should include Git bundles
for the checkout and each Git dependency or a verified immutable remote URI.

## Archive-root capacity and durability

Only the internal APFS data volume is presently mounted as a writable archive
candidate. It has 1,108,428,396 KiB available (about 1.0 TiB), easily exceeding
the approximately 4.5 GiB closed cluster payload. `/Volumes/.timemachine` is an
implementation mount, not a user-selected archive destination. No existing
`~/Archives`, `~/archive`, `~/.local/share/seon-archives`, or `~/src-archives`
root exists.

Capacity is not durability. A same-volume archive protects against worktree
cleanup but not loss of the internal disk. Recommended choices are:

- a user-approved external or backed-up filesystem as the durable
  `ARCHIVE_ROOT`; or
- an internal APFS staging root, such as
  `~/Library/Application Support/Seon/archives`, followed by verified promotion
  to durable storage before cleanup authorization.

Reserve at least four times the closed logical payload: staged copy, compressed
package, extracted read-back copy, and headroom. For the current two clusters,
20 GiB free is a conservative minimum. The available internal volume exceeds
that, but no root was created by this audit.

## Exact quiesce, package, and verification order

The following is a runbook, not authorization to execute it.

1. Reconfirm each PID, OS start time, cwd, command, port, and open files. Abort
   if any identity differs from this report.
2. Run the bounded identity expression once as a pre-quiesce checkpoint.
3. Send `TERM` to the lane's **pod only** (stable 31038, display-v3 52189 at
   this snapshot), wait for exit, and prove its HTTP port is closed. Recheck
   that no other process can submit writes through the writer request socket.
4. With the writer still live but no producer, rerun the identity expression.
   Save this EDN as `database-identity.edn`; this is the final basis/schema/latest
   transaction record.
5. Send `TERM` to that lane's writer only, wait for exit, and prove the REPL,
   request, and publish endpoints are closed. Confirm `lsof +D` reports no open
   file below the cluster directory.
6. Copy the **whole** `data/clusters/acme/` directory into a fresh staging
   directory with `rsync 3.4.4` archive semantics, preserving relative paths,
   permissions, times, symlinks, hard links, ACLs, and xattrs:

   ```bash
   rsync -aHAX --numeric-ids --protect-args --itemize-changes \
     "$WORKTREE/data/clusters/acme/" "$STAGE/cluster/"

   ```

7. Add `database-identity.edn`, HEAD/dependency records, `.cpcache` basis,
   `git status --porcelain=v2 --branch --untracked-files=all`, ignored-file
   inventory, process command, and the relevant evidence files. Do not follow
   links outside the worktree; record them as links.
8. From `$STAGE`, write a NUL-safe, path-sorted SHA-256 manifest and record
   logical bytes, allocated bytes, and file count. Create a deterministic tar
   stream and compress it with `zstd`; hash the package. Promote only the closed
   package under `${ARCHIVE_ROOT}/sha256/<package-sha256>/`.
9. Extract into a second empty directory. Verify the per-file manifest, package
   digest, file count, logical bytes, and allocation. Mark the promoted package
   read-only.
10. Make a **disposable copy** of the verified extraction for database
    read-back. Never connect Datahike to the promoted archive itself. Use the
    archived historical checkout/dependency basis, deny network access, and
    perform only `d/db`, `d/schema`, `d/q`, and `d/pull` reads. `d/connect` may
    maintain process-local or store metadata, so compare the disposable copy's
    file manifest before and after and discard it afterward; archive integrity
    is established against the untouched promoted extraction.
11. Require exact equality for final basis, schema digest, latest transaction,
    blob/agent/eval/plan counts, and representative pulled facts. For
    display-v3 also require the 29 named autocomplete/typeahead attributes and
    representative `:seon.typeahead/transition`, `offers-edn`, and `worker-sha`
    facts. Verify every DB-resolved blob hash has a matching content-addressed
    file; retain extra historical files.
12. Record the archive URI, package digest, restore command, read-back EDN,
    before/after disposable-copy checksums, and owner acceptance in the
    preservation manifest and issue. Only then can cleanup review begin.

Because the writers are orphaned under PID 1, the old supervisor is not a
trustworthy stop owner. The maintenance executor must signal the four
revalidated process identities explicitly, one lane at a time; it must not use
`pkill`, port-based broad kills, or a current `bin/seon down` against these old
checkouts.

## Remaining blockers

- No owner-approved durable archive root exists.
- Both legacy clusters remain live and their captured identities are not yet
  final quiesced identities.
- No closed package, package digest, extracted checksum proof, or historical-
  dependency read-back exists.
- No Git dependency bundle or verified immutable remote retention has been
  archived.
- The extra blob files have not been classified against restored history.
- Owner acceptance for process shutdown and worktree retirement is still
  absent.

This runbook narrows the remaining work to a coordinated maintenance operation;
it does not authorize cleanup by itself. See
[[worktree-evidence-preservation-manifest-2026-07-14]] and
[[docs/seon/issues/autocomplete-worktree-evidence-preservation]].
