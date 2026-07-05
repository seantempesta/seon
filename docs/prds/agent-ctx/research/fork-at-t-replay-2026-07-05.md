---
type: research
status: active
tags: [research, database]
---

# Fork-at-t: writable copy of a cluster's world at a failure moment

> **SUPERSEDED BY `datahike.api/fork-database` (2026-07-05).** The
> copy + walk-to-t + re-identify + `force-branch!` choreography below is now a
> first-class API inside our datahike fork
> (`reference-code/datahike/src/datahike/versioning.cljc`, fork commit
> `5566ab13`): `(d/fork-database source-cfg target-cfg {:at t-or-inst})` copies
> the store at the konserve layer, resolves the commit (`tx-id` exact /
> inst ≤ / head), mints a FRESH store identity, and returns the target's
> effective config — independent, writable, byte-faithful, deletable. This doc
> stays as the grounding evidence; call the API instead of hand-rolling steps
> 0–4.

## TL;DR

1. Full tx-log enumeration = `(d/datoms (d/history db) :eavt)` grouped by `|tx|` — exactly what `wire/replay-tx-events` does (`src/seon/server/wire.clj:348`); the fork has NO `tx-range`, but it DOES have retractions (negative-tx datoms) + tx-meta datoms in that read. `replay-tx` replays since-t with no upper bound and a keep-NEWEST 50k cap — reusable as a reference, not as the fork producer.
2. Faithful re-transacting works: raw `Datom` objects in tx-data preserve original e, a, v AND tx (`transaction.cljc:1207-1211`); explicit eids advance `max-eid` (no collision); schema is ordinary datoms in the log (no separate bootstrap in `store.clj` `base-cfg`); `load-entities` REMAPS eids — do not use it.
3. **The fork HAS git-like versioning** (`datahike.versioning`): one commit per tx, stored under its commit-id, walkable via `:datahike/parents`, each carrying `:max-tx` — and `force-branch!` is a real "git reset --hard to t". Live-proven on the 7891 REPL.
4. **RECOMMENDED: store-dir copy + `force-branch!` rollback** — `cp -r` the cluster dir, walk head→parents to the commit with `:max-tx == t`, `commit-as-db`, rewrite its stored `:config` (`:store :id` + `:name`) to the fork's identity, `force-branch!` it onto `:db`, then `bin/seon cluster create fork-<src>-<t>` boots a pod on it. No replay, original eids/tx-ids/txInstants exact, fully independent store. Same-store `branch!` is rejected: `delete-database` on the fork cfg would nuke the SHARED source store.
5. The pod does NOT need seed suppression: `boot-seed!` is unconditional but idempotent (identity upserts + conn-deduped index + reconcile to the same desired set) — a faithful fork makes it a no-op. Size today: 12,886 history datoms / 38 txs / 26 MB — copy+rollback is sub-second; even 0→t replay would be seconds. No GC runs anywhere in seon, so every historical commit is retained.

## Recommended mechanism — concrete calls (runs in the wire-server JVM)

```clojure
;; 0. Quiesce or verify: cp -r data/clusters/default data/clusters/fork-<src>-<t>
;;    (rm the copied frozen/ephemeral markers). konserve :file commits write
;;    blobs BEFORE the atomic head rename, but a plain cp racing a live commit
;;    can still tear — verify after connect (sane :max-tx) and retry, or hold
;;    writes for the ~1s copy.

(require '[datahike.api :as d]
         '[datahike.versioning :as dv]
         '[seon.server.store :as store]
         '[seon.server.registry :as registry])

;; 1. Open the COPY unsafely (its stored config still carries the SOURCE's
;;    :store :id — see "identity check" below).
(def cfg (assoc (store/config-for
                 {:seon.server.store/db-name :fork-default-536870940
                  :seon.server.store/backend :file
                  :seon.server.store/path "data/clusters/fork-default-536870940/store"})
                :allow-unsafe-config true))
(def conn (d/connect cfg))

;; 2. Walk head → parents until :max-tx == t (one commit per tx; each stored
;;    db carries :max-tx — verified live, §Q3).
(defn commit-at-t [conn t]
  (loop [db (d/db conn)]
    (cond (= (:max-tx db) t) db
          (< (:max-tx db) t) (throw (ex-info "t predates history head walk" {:t t}))
          :else (recur (dv/commit-as-db conn (first (dv/parent-commit-ids db)))))))

;; 3. Re-identify + roll back: give the at-t db value the FORK's store id and
;;    name, then force :db to it (datahike.versioning/force-branch!,
;;    api-exposed; "like git reset --hard" — versioning.cljc:152-158).
(let [at-t (-> (commit-at-t conn 536870940)
               (assoc-in [:config :store :id]
                         (#'seon.server.store/name->uuid :fork-default-536870940))
               (assoc-in [:config :name] ":fork-default-536870940"))]
  (d/force-branch! at-t :db #{(dv/commit-id at-t)}))

;; 4. Release + reconnect (force-branch! WARNING: existing conns see stale
;;    state — versioning.cljc:156-158). Now the plain registry cfg (no
;;    :allow-unsafe-config) connects cleanly: the head's stored :id matches.
(d/release conn)

;; 5. Boot the ephemeral cluster (supervisor side):
;;    bin/seon cluster create fork-default-536870940 --ephemeral
;;    — cluster_create only mkdirs + starts pod-<name>; the pod's own
;;    ensure-db finds database-exists? true (store + :db key present,
;;    writing.cljc:375-395) and connects WITHOUT create/seed.
```

Cleanup: `bin/seon cluster destroy fork-<src>-<t>` (registry `delete-db!`, `registry.clj:340` — safe because the fork store dir is fully independent).

## Q1 — Reading the full tx-log grouped by transaction

**The sanctioned read is the history db's `:eavt` index; there is no `tx-range` in the fork** (`grep -rn tx-range reference-code/datahike/src` → no hits; the api surface is generated from `datahike/api/specification.cljc`, which has `history` (:467), `since` (:479), `load-entities` (:232) — no tx-range).

`wire/replay-tx-events` (`src/seon/server/wire.clj:348-418`) is the existing producer:

```clojure
(let [datoms (-> db d/history (d/since since-t) (d/datoms :eavt))
      txid   (fn [^datahike.datom.Datom d] (Math/abs (long (.-tx d))))
      by-tx  (->> datoms
                  (filter (fn [d] (let [t (txid d)]
                                    (and (> t since-t) (<= t current-t)))))
                  (group-by txid))
      tx-ids (sort (keys by-tx))]
  ...)
```

Its own docstring (`wire.clj:357-366`) settles the fidelity questions:

> Source: the bitemporal tx-log via `(d/since (d/history db) since-t)`. Datoms (assertions AND retractions) are grouped by their committing tx — retraction datoms carry a NEGATIVE tx, so we group by its absolute value ... The committing tx's own datoms (`:db/txInstant` + any seon tx-meta attrs such as `:seon.store.wire/write-id` and provenance) carry the tx-meta. NB: a card-one upsert's IMPLICIT old-value retraction shows up here (history is complete) ... replay is a superset, never a subset, of what landed.

The negative-tx encoding is the Datom type itself (`reference-code/datahike/src/datahike/datom.cljc:16-20`): `(datom-tx [d] (if (pos? tx) tx (- tx)))`, `(datom-added [d] (pos? tx))`.

**The `replay-tx` wire op** (`src/seon/server/boot.clj:87-105`) wraps this: takes `:seon.store.wire/since-t`, returns ALL missed events in ONE reply (no streaming, no batching), each shaped exactly like a live `tx` broadcast event (5-vectors `[e a v t op]`, tx-meta, write-id). `since-t 0` would replay everything — BUT it has **no upper bound** (always runs to `current-t`, `wire.clj:373-375`) and its overflow cap (`max-replay-txs` 50,000, `wire.clj:339-346`) keeps the **newest** txs — the exactly wrong policy for a 0→t fork replay. So "re-run the producer with an upper bound" is a real but small change (add a `<= tx-id upper-t` filter); it is NOT literally the existing op. The sanctioned full-dump precedent is `datahike.migrate/export-db` (`reference-code/datahike/src/datahike/migrate.clj:8-18`): sort the full history `:eavt` by `(juxt d/datom-tx :e)`.

## Q2 — Re-transacting faithfully

**(a) Explicit eids — yes, and better: raw Datoms keep the original tx too.** `transact-tx-data` (`reference-code/datahike/src/datahike/db/transaction.cljc:1207-1211`):

```clojure
(datom? entity)
(let [[e a v tx added] entity]
  (if added
    (recur (transact-add report [:db/add e a v tx]) entities)
    (recur (transact-retract-datom report entity true) entities)))
```

`transact-add` (`transaction.cljc:693-702`) uses `tx (or tx (current-tx report))` — the datom's original tx wins; `transact-retract-datom` with `keep-tx-id` true (`transaction.cljc:720-725`) keeps the retraction's original tx. Numeric eids pass `entid-strict` unchanged, and `transact-report` calls `advance-max-eid` on every datom (`transaction.cljc:394`, `:511`; `advance-max-eid` at `:72-76` caps below `tx0` so referenced tx-ids don't corrupt `max-eid`) — post-fork tempid allocation cannot collide with replayed eids. This is exactly what `datahike.migrate/import-db` relies on (`migrate.clj:30-39`): it transacts raw Datoms after bumping `:max-tx`.

Per-tx replay stays tx-aligned for free: `current-tx = (inc (:max-tx db-before))` (`transaction.cljc:53-54`) and every transact increments `max-tx` by exactly 1 (`transaction.cljc:1154`), while `tx0 = 0x20000000` (`constants.cljc:4`) is identical for any fresh db — so replaying source tx k as the fork's k-th transact reproduces tx eids exactly, which is what keeps `:seon.error/at` (basis-t = tx eid, `src/seon/error.cljs:29`) meaningful.

**Do NOT use `d/load-entities`** for this: `transact-entities-directly` (`transaction.cljc:1217-1279`) routes every e and tx through a `migration-state` REMAP (`(or (get-in migration-state [:eids e]) max-eid)`, `:1270`) — structure-preserving but not numerically faithful, and non-ref long values holding tx eids (`:seon.error/at`) are never remapped.

**(b) tx-entity datoms / tx-meta.** Pass the source tx-entity's datoms as the `:tx-meta` map (what `replay-tx-events` reconstructs at `wire.clj:403-404`); `flush-tx-meta` (`transaction.cljc:802-821`) turns it into datoms on the current tx and validates each attr against the schema — satisfied because the schema datoms are earlier in the log. `:db/txInstant`: the auto-stamp merges UNDER user tx-meta (`transaction.cljc:1114-1115` `(merge {:db/txInstant (next-tx-instant db-before)} %)`) and the allocator's own docstring (`transaction.cljc:141-155`) says "User-provided `:db/txInstant` in `:tx-meta` still wins" — original timestamps survive. Alternatively, since raw Datoms keep e=orig-tx-id and tx alignment holds, replaying the tx-entity datoms verbatim upserts over the auto-stamp (card-one, `transact-add` `upsert?` at `:703`).

**(c) Schema datoms.** Schema is ordinary datoms: `update-schema` fires on `:db/ident` datoms inside `transact-report` (`transaction.cljc:91+`), so a 0→t replay rebuilds the schema in order. There is NO separate schema bootstrap at store creation: `store/config-for`'s `base-cfg` is only `{:keep-history? true :schema-flexibility :write}` (`src/seon/server/store.clj:117-121`), and `create-entry!` (`src/seon/server/registry.clj:192-212`) just `create-database` + `connect` — the only conn-time schema write is the reactive hook's 3 `:db/ident` upserts (`src/seon/server/boot.clj:121-138` `seed-subscription-schema!`), which are idempotent datahike upserts against the replayed log (identical idents → no-op).

**(d) Retractions mid-log** apply cleanly: as raw Datoms with `added=false` they go through `transact-retract-datom` against the explicit eid, which the fork possesses because eids replay verbatim.

## Q3 — The cheaper alternative: the fork HAS branch/rollback machinery

`datahike.versioning` (`reference-code/datahike/src/datahike/versioning.cljc`) is api-exposed (`api/specification.cljc:571-697`, impls `api/impl.cljc:307-349`):

- `branch! conn from new-branch` (`versioning.cljc:88-131`) — `from` is a branch keyword **or a commit-id uuid**; CoW (stores one new head value; index nodes content-addressed and shared); secondary indices (proximum) CoW-branched natively.
- `commit-as-db conn-or-store cid` (`versioning.cljc:212-223`) — loads the FULL db value stored at any commit.
- `force-branch! db branch parents` (`versioning.cljc:152-198`) — "overwrites the branch head unconditionally, like git reset --hard" — **this IS the roll-back-to-t op the prompt assumed didn't exist.**
- `branch-history conn` (`versioning.cljc:67-86`) walks `:datahike/parents`.

**One commit per transact**, each stored under its cid with `:max-tx`/`:max-eid`/`:meta` (`writing.cljc:33`, `:220-223`, commit chain via `:datahike/parents` `:313-321`). **Live proof (wire REPL 7891, 2026-07-05):** head `{:max-tx 536870950 :datahike/commit-id #uuid "6a4983ee-…" :datahike/parents #{#uuid "6a4983d3-…"}}`; `(commit-as-db conn parent)` → `{:parent-max-tx 536870949 :datom-count 12691}` — the parent commit is a complete queryable db at exactly t-1. So cid-at-t = walk parents from head until `(:max-tx stored-db) == t`, cost one konserve get per tx walked back.

**Why copy + force-branch! beats same-store `branch!`:**

- `delete-db!` / `bin/seon cluster destroy` calls `d/delete-database cfg` (`registry.clj:340-368`) — on a branch-config that deletes the SHARED store, i.e. destroys the source cluster. The cluster model is one dir per cluster (`bin/seon:107`, markers `frozen`/`ephemeral` in the dir, `SEON_CLUSTER_DIR` plumbing) — a branch inside the source dir fights all of it.
- `seon.server.store/config-for` has no `:branch` key (datahike config supports it, `config.cljc:38`, default `:db` at `:104`); copy needs no registry change at all.
- GC: nothing in seon ever calls `d/gc-storage!` (only a comment at `src/seon/embed.clj:232`), so all commits are retained — the walk to any t works. A copied store is fully independent (a `:file` store's blobs live inside its own dir); post-t garbage in the copy is reclaimable later via `gc-storage!` (`gc.cljc:55-77` whitelists branch-reachable keys).

**Why copy alone is not enough:** there is no "open at t writable" — `as-of` is read-only — so the copy must be re-pointed with `force-branch!` to the commit-at-t.

**The store-identity gotcha (load-bearing):** `connect` validates the config `:store :id` against the STORED config (`connector.cljc:140-151` raises `:store-identity-mismatch`) unless `:allow-unsafe-config true` (`connector.cljc:213-214`); `config-for` derives `:id` from the db-name (`store.clj:110-115` `name->uuid`), so a copied store (stored id = source's) won't open under the fork name. Fix inside the fork op: `assoc-in [:config :store :id]` the fork's `name->uuid` on the at-t db value before `force-branch!` writes it as the new head — after that, plain registry `ensure-db!` connects with zero unsafe flags. Do NOT instead reuse the source's `:id`: the in-JVM connection cache is keyed `[store-id branch]` (`connector.cljc:175`) and would hand back the SOURCE's live conn. (The pod's own read-side conn already sets `:allow-unsafe-config true` and derives its id from the cluster name — `src/seon/store/wire.cljs:145-153`, `:94-128` — so it follows the same one-derivation rule and needs the head rewrite too.)

**Copy-while-live caveat:** konserve `:file` commits write content-addressed blobs then atomically rename the head, but a `cp -r` racing a commit can still produce a head that references blobs the cp already passed. Mitigate: verify after connect (walkable head, expected `:max-tx` ≥ t) and retry, or briefly hold writes during the ~sub-second copy.

## Q4 — Wiring the ephemeral cluster

`bin/seon cluster create <name>` (`bin/seon:1093-1158`): validates name, starts wire-server, `mkdir -p data/clusters/<name>`, writes optional `ephemeral`/`frozen` markers, starts `pod-<name>` with `SEON_CLUSTER_DIR=data/clusters/<name>`, `SEON_PORT=0`, per-cluster port file, read-only FS root (`bin/seon:262`). **The supervisor never creates the db** — the pod's boot does, via the `ensure-db` wire op (`src/seon/store/wire.cljs:218-242` sends db-name = dir basename, backend `:file`, path `<cluster-dir>/store`), and the JVM side (`wire.clj:217-236` → `registry/ensure-db!` → `create-entry!` `registry.clj:192-212`) only calls `create-database` when `(not (d/database-exists? cfg))` — `database-exists?` = store dir exists AND `:db` head present (`writing.cljc:375-395`). A pre-populated fork dir therefore connects, never creates.

**Seed: no suppression needed.** `boot-seed!` (`src/seon/client.cljs:2348+`) runs unconditionally at every pod boot but is idempotent by construction, per its own docstring: identity upserts (`:seon.user/id`, `:my.kb.shared/id`), a conn-deduped core index ("an Nth boot on the same store re-seeds nothing"), and the declarative routes+skills set synced through `state/reconcile!` (upsert-by-identity + retract-stale against the SAME manifest → no-op). A faithful fork carries the source's seed datoms in its log, so the presence checks pass naturally. Honest caveat: `boot-seed!` will still append txs after t if the current build's index/desired-set differs from what the store was seeded with (e.g. forking an old store with a newer build) — the world-state at t is intact but the fork's head moves past t at boot; for byte-exact forensics, note the fork's post-boot basis-t.

**Naming/plumbing:** fork name `fork-<src>-<t>` is `valid_cluster_name`-safe (a-zA-Z0-9_-, `bin/seon:1096`). Copy the WHOLE cluster dir (`data/clusters/<src>/` → store + `blobs/` — turn prompt/reply blobs are part of the forensic world; `wire.cljs:87-92` "everything per-cluster on disk (store, blobs) lives under it"), delete copied `frozen`/`ephemeral` markers, then create with `--ephemeral`.

## Q5 — Size / perf reality

Live default cluster (wire REPL 7891, 2026-07-05): **12,886 full-history datoms, 38 txs (max-tx 536870950), max-eid 2323, store dir 26 MB** (recently reset; a long-lived cluster grows, but the shape holds).

- Copy + force-branch!: `cp -r` 26 MB ≈ instant; head→t walk = one konserve get per tx walked; one commit write. **Sub-second end to end.**
- 0→t replay: per-tx `d/transact` at this scale = seconds; the wire `replay-tx` reply is ONE unbatched event list (no streaming; `max-replay-txs` 50,000 keep-newest cap, `wire.clj:339-346`) — fine as-is for gap recovery, wrong policy for forking.
- GC: seon never runs `gc-storage!`, so (a) every commit needed for the walk exists, (b) a replay-based or copy-based fork has zero cross-store references (unlike a same-store branch, whose data is only safe because `gc-storage!` whitelists all branches, `gc.cljc:66-70`).

## Rejected / fallback options

- **Same-store `branch!`** — real and CoW-cheap, but couples fork lifecycle to the source store (destroy nukes both), needs `:branch` plumbed through `store/config-for` + registry + pod config, and breaks the one-dir-per-cluster supervisor model. Keep in the back pocket for in-place experiments, not for ephemeral clusters.
- **0→t replay into a fresh store** — fully correct (Q2 mechanics above) and the only option if versioning commits were ever GC'd; strictly more code and more time than copy+force-branch! today. If built, build it JVM-side over `(d/datoms (d/history db) :eavt)` with an upper bound; do not route datoms through the pod's `seon.db/transact!` (schema-registry boundary checks + agent provenance stamping would fight verbatim replay).
- **`d/load-entities`** — remaps eids (`transaction.cljc:1241-1279`); unfaithful to tx-eid-valued longs like `:seon.error/at`.
