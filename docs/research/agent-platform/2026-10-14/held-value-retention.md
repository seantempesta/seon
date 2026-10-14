---
title: Held-value retention — what a collection keeps for a value this process holds
type: research
date: 2026-10-14
status: design proposal (no source edited)
---

# Held-value retention

**What is broken first.**

1. A value read from a branch that is then retired loses its nodes at the next collection, even a plain one (probe C:
   `Node not found in storage.`).
2. A value whose commit is older than the collection's `remove-before` loses its nodes. It makes no difference
   whether the value was opened before or after the cutoff instant (probes A and D).
3. Default's retention window is not one hour but **95.8 hours**. On a clone of default's store taken at 03:5xZ,
   `seon.cluster.registry/retained-before` moved the cutoff from `2026-10-14T02:51:33Z` to `2026-10-10T03:05:48Z`
   (344,745,573 ms). The cause is one fact naming a commit that is a *current head*: `:db`'s head `6ac9ab8c…`, recorded
   by the collection's own branch evidence (entity 693791, `{:seon.source/commit-id … :seon.store/branch :db}`). A head
   is always marked, so naming one should move nothing.
4. The two docstrings named in [storage-nodes-missing §2](storage-nodes-missing.md#2-how-a-check-jvm-gets-its-store-and-what-a-collection-can-delete)
   overstate the guarantee. Corrections are in §5.

**Recommendation: option (c), read as Datahike's own grace-period contract, with no new mechanism.** A collection keeps
every commit on a live branch's ancestry that is newer than `remove-before`, and every head. So a value is safe for as
long as it is held *within one operation*, provided that:

- it was opened as a head, or as a commit that a current fact names; and
- its branch is not retired by its own holder before the hold ends.

Anything that outlives an operation holds a basis (a commit id or a branch) and reopens. That is already the owner's
ruling ("No world holds a database value", 2026-09-28, `seon.turn.world/basis`, `seon.call-preparation`), and task
`seon.sci.eval/a-context-holds-the-basis-it-acquired-not-the-database-value` applies it to contexts. The work left is
three corrections (§5), one rule written into TASK-CHAIN §2, and one bug fix at `retained-before` (§6), which has a red
proof. For errors-cut E5 the honest contract is "readable while retention keeps it" (§7).

## 1. What a collection keeps (Datahike `c9e57c6`, Konserve `26444d3`, the pinned revisions)

The reference-code working tree holds Datahike `62209ee`. It differs from the pin in `gc.cljc` by 4 documentation
lines only (`git diff --stat c9e57c6 HEAD`).

| Side | Owner | Rule |
|---|---|---|
| Mark | `datahike.gc/reachable-in-branch`, unioned over `(k/get store :branches)` by `datahike.gc/gc-storage!` | From each branch head: mark the head's record and its whole index trees. Walk parents while the *current* commit is newer than `remove-before`, which also marks the first commit beyond it. A commit reached by no live branch is never marked, whatever its age. |
| Extension | `:datahike.gc/reachable-extension` (Seon passes `seon.cluster.registry/referenced-blobs`) | Extra keys unioned into the reachable set. This is the one existing hook for roots the branches do not reach. |
| Sweep | `konserve.gc/sweep!` with cutoff `min(now, datahike.gc-guard/safe-point)` | An unreachable key is deleted when its **`last-write`** is before the cutoff. Opening or reading a value never touches `last-write`. |
| Deletion | `datahike.versioning/delete-branch!` | "The branch will still be accessible until the next gc. Connections to the branch must be released before deletion." Datahike refuses `:branch-has-active-connection` (translated by `seon.cluster.registry/retire-branch!`), but a *value* has no connection and is not counted. |
| Library contract | Datahike `doc/gc.md` "Grace Periods for Distributed Readers" | "Consider your longest-running reader process." Snapshots created after the grace date are kept. |

So the prompt's option (c), "protects anything written or opened after the cutoff", is half right:

- **Written:** protected, by the sweep cutoff.
- **Created:** protected, by `remove-before`.
- **Opened:** never protected (probe D).

Seon's scheduled collection (`seon.maintenance/collect!` → `collection-cutoff`) uses
`registry/retained-before` of `registry/retention-cutoff`. That is the newest head time minus the largest
`:seon.config.db/snapshot-window-ms`, which `config/default.edn` sets to 3,600,000. The window is anchored to the newest
head, so a *head* held at time T stays protected for at least one window after T. The head's child is created after T,
and a parent of an in-range commit is always marked.

## 2. Measurements

**Probe** (appendix). Own JVM on the `tmp/gc-one-enumeration/parent.classpath` archive, whose
`seon.cluster.registry` equals HEAD. Fresh stores under the scratchpad, Seon's `seon.cluster.store/datahike-configuration`.
Each case holds a value reopened from storage (`d/commit-as-db`, so its children are addresses, not objects), runs a
real `d/gc-storage`, empties the node cache and runs `System/gc`, then reads the held value.

| Case | Hold | Collection | Swept | Held value reads |
|---|---|---|---:|---|
| A | head, then superseded by 3 rewrites; commit older than `remove-before` | `remove-before` after it | 8,246 | **`:node-not-found`** (head reads 12,000) |
| B | head; commit newer than `remove-before` | same | 99 | 8,000 |
| C | head of a side branch, which is then retired while held | plain (epoch) | 951 | **`:node-not-found`** |
| D | an old commit opened by id *after* the cutoff instant | `remove-before` after the commit | 8,246 | **`:node-not-found`**; reopening answers nil (record swept) |
| E | as A, with the held commit as an exact root through `:datahike.gc/reachable-extension` | same | 7,863 | 8,000 |
| F | as C, with the held commit as an exact root | plain | 184 | 14,000 |

Each case took 0.7 to 36 s, almost all of it writes. The `:node-not-found` data printed the whole Konserve store record,
which reconfirms evidence fix 4 (the error must carry the store id).

**Default's cutoff on a store clone.** `cp -c -R data/store` took 6.5 s and 211 MB. The clone was opened read-only with
Datahike under default's stored store id. Fifteen branches.

| Measure | Value |
|---|---|
| `retention-cutoff` | `2026-10-14T02:51:33.656Z` (335 ms) |
| `retained-before` | `2026-10-10T03:05:48.083Z` (653 ms), 95.8 h earlier |
| named `:seon.source/commit-id` commits | 5. The oldest is `:db`'s current head, which is a head and is marked anyway. |
| other named attributes | `:seon.squash/source-commit`, `:seon.task/merge-base`, `:seon.task.attempt/commit`, `:seon.test.run/loaded-commit-id`: 1 each, all from 02:58 to 03:32Z today |

Default's 03:00Z collection still reclaimed 433 MB, because deleted branches go at any window.

## 3. Who holds a database value today

| Holder | What it holds | Bounded by | Protected by |
|---|---|---|---|
| SCI context program snapshot | a basis since f51240f34; `seon.sci.eval-test/an-acquired-context-retains-bases-and-reads-its-connection` asserts there is no `:seon.db/db` and no `:seon.sci.eval/loaded-database` | — | nothing to protect |
| Worlds, call preparation | none: "a database is the custody in force's current value" (`seon.call-preparation`, `seon.turn.world/basis`) | — | — |
| Test world (`seon.test/clean-request-handle`) | the base and `built-on` commits read by id; overlay heads in memory | one request | the base and `built-on` are named by the adoption record (`retained-before`). The overlay's disk children belong to the checkpoint commit, rooted by the durable `checkpoint-…` branch. The request branch roots nothing on disk. |
| Root's pass (TASK-CHAIN §2) | chain heads via `seon.db/branch-database`, released after use | one pass, measured 0.45 to 0.7 ms per chain | head held, so at least one window. Exposed only if the same pass retires the branch and then reads the value (case C). |
| Merge (`seon.merge`, `seon.cluster.source/prepare-merge!`) | merge base and attempt commit by id | one merge | named (`:seon.task/merge-base`, `:seon.task.attempt/commit`) |
| `seon.cache` kinds | derived projections and digests, realized at insert (`seon.db/loaded-builder` derefs its delay in place) | — | they do not reread the store |

No registry of held commits exists, in Datahike or in Seon. `release-materialized-db` closes secondary resources only,
and is a no-op for primary-only values. `registry/branch-connected?` tracks connections, not values.

## 4. The options, priced

| | (a) Mark every held commit | (b) A durable ref per held value | (c) Window plus basis (recommended) |
|---|---|---|---|
| Mechanism | A process registry of live held values: weak references or explicit release at about 62 open sites in 17 files plus every `db/db` deref. The extension hook marks each held commit's trees, plus overlay frontends. | `registry/branch!` per hold, deleted on release. Boot's `retire-unclaimed!` reclaims crash leaks. | None. Datahike's grace period, and the ruling that nothing holds a value across operations. |
| Cost | A new mutable registry, where CLAUDE.md allows "any further atom" only with an owner-accepted reason. A hook in Datahike's value constructors or at 62 sites. A gate between enumeration and sweep, so a value opened in between is not missed: a fence. | 17 to 56 ms per hold, plus one roster write under the roster permit, which serializes against sweeps. Against a 0.45 to 0.7 ms chain read that is 25 to 120 times the work. | 0 ms. Three text corrections. |
| Guarantee | Any value the JVM still strongly reaches, at any age, deleted branch included (probes E and F show the hook works). Not across processes. | Every held value, across restarts. | A value held within one operation, opened as a head or as a named commit, on a branch its holder does not retire mid-hold. |
| Sacrifice | It legitimises long holds, which the 2026-09-28 ruling forbids. JVM GC timing decides retention. | Branch churn on hot paths, and every holder must release. | Holds beyond an operation are a defect, by rule. |
| Owner | Datahike fork plus Seon's opening sites | `seon.cluster.registry` | Each holder; the writer of a branch's retirement |
| Deletes | — | — | the false docstring claims; no fence exists to delete |

(b) is already the right tool where a reference must outlive its source branch and the window: test checkpoints, agent
and chain branches. It should not be extended to ephemeral reads. (a)'s *hook* is sound (probes E and F), but only for
roots the **facts** name (§6, option 2), never for what memory happens to hold.

## 5. Recommended changes (one docs-and-docstring slice, Sonnet)

1. **`seon.cluster.registry/plain-cutoff`.** Replace "No commit a live branch, a held value on it or an adopted source
   commit names is pruned" with: "Every commit on a live branch's ancestry is marked, so a value held on a branch that
   stays live survives it. A value on a branch retired while held, or a commit no live branch reaches, does not. The
   scheduled collection uses `retention-cutoff` (`retained-before`), which keeps a held head for at least one snapshot
   window." `the-plain-collection-keeps-held-values-adopted-commits-and-concurrent-writes` already proves the true part.
2. **`seon.test/clean-request-handle`.** Replace "a private branch off its checkpoint commit, which roots that commit for
   its members" with: "a private branch in the overlay, off the checkpoint commit. The durable checkpoint branch roots
   that commit on disk, and the overlay branch roots only the request's in-memory commits."
3. **`:seon.config.db/snapshot-window-ms` description.** Add: "It is also the longest a database value may be held:
   holds are bounded by one operation, and every operation bound (write deadline 600 s, the longest declared test bound
   1,200 s, `:seon.test/long-ms` in `test/`) is below the shipped 3,600,000." This states a relation between two dials. It does not add a check.
4. **TASK-CHAIN §2, step 2.** Add one sentence: "A value read from a chain branch is not read after the same pass
   retires that branch. `run-pass!` re-derives after `:merge`, `:stop` and abandon, as it does today." Root is both the
   holder and the only deleter, so this is ordering at the owner, not a lock (CLAUDE.md "TASK-CHAIN locked" applies only
   to lifecycle attributes; this adds none).

## 6. Red proofs and reaching tests

- **Proof of the hold law:** the existing `seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value`.
  Its `remove-before (Date.)` is a zero window, so it fails exactly when a value survives across eval requests. It is
  flaky: red once in run `e335e49d815e`, green alone in `6ffecfb787d4` and `0e7638709e0e`. That makes it a flake-hunt
  item first (Haiku: run it alone ten times at HEAD). Two corrections are needed before it can diagnose anything:
  - It discards the error. `{:error? true :values ()}` should keep the reply's error map (`seon.test-support/mcp-replies`
    already reads it), so a red names its reader.
  - Weak index children make a cross-request hold fail only sometimes. Each red's error names the reading function, and
    that function is the holder to convert to a basis. No fence is added.
- **Red proof for the bug at `retained-before` (§0 item 3).** New row in `seon.cluster.registry-test`, beside
  `retention-keeps-every-commit-a-current-fact-names`. A fact naming only a current head leaves the cutoff unchanged:
  `(= (registry/retention-cutoff s) (registry/retained-before s (registry/retention-cutoff s)))`. Red today; the clone
  measured 344,745,573 ms of difference. The fix is at the owner: `named-commits` minus the set of current head commits
  (`branch-heads`), which are always marked. Cost: one `branch-heads` read, already performed by `retention-cutoff`. Two
  options (root's choice):
  1. **Recommended.** Exclude heads (the row above).
  2. Replace the time pin with exact roots. The named commits' trees go into `:datahike.gc/reachable-extension`
     (probe E's `commit-keys`, a 10-line `datahike.gc` fork function, `datahike.gc/reachable-from-commit`, which removes
     the probe's copy). This keeps exactly what is named, and also keeps a named commit on a retired branch (probe F).
     **Count before choosing it.** It would retain every `:seon.task.attempt/commit`'s unique tail for as long as the
     attempt fact exists, which contradicts `a-deleted-attempts-storage-is-reclaimed-by-the-next-collection`. Retention
     by value type is too coarse either way. A fact that should keep a commit names its consumer (CLAUDE.md "every
     stored fact names its consumer"), and that is a schema decision for root.
- **Reaching tests** for the slice and the fix:
  - `seon.cluster.registry-test`: `retention-keeps-every-commit-a-current-fact-names`,
    `the-plain-collection-keeps-held-values-adopted-commits-and-concurrent-writes`,
    `an-idle-branch-keeps-the-commit-it-forked-from-through-collection`,
    `a-deleted-attempts-storage-is-reclaimed-by-the-next-collection`, `retiring-one-cluster-reclaims-only-its-own-tail`,
    `implicit-retention-requires-declared-cluster-windows`, `blob-lifetime-follows-schema-derived-history-reachability`.
  - `seon.maintenance-test`: `collection-reports-and-verifies-the-exact-store`,
    `a-branch-that-commits-after-its-root-was-recorded-still-verifies`.
  - The armed test above.

## 7. Errors-cut E5: should a recorded error's commit id keep its commit readable?

**No. The honest contract is "readable while retention keeps it".** The occurrence's commit id is a reference.
`seon.db/commit-database` answers nil once the record is swept (probe D: `:reopen false`), and the reproducer reports that
as a typed unknown (for example `:seon.error/world-unretained`), never as a guess. Within the window, and while the
branch lives, the world reproduces exactly, as E5 claims. Two things make the stronger contract wrong today:

- **It would disable retention store-wide.** If the occurrence's member resolves to `:seon.source/commit-id`, it joins
  `named-commits`, and the cutoff moves behind the oldest recorded occurrence. One such fact costs default 95.8 h of
  retention today (§2).
- **It would still lose most worlds.** An agent's errors happen on its branch, and the branch is retired when its merge
  request closes. A time pin keeps nothing on a retired branch (probe C).

If reproduction across the window turns out to matter, first count how often an error is reproduced more than one hour
after it was recorded. The mechanism is then exact roots (§6, option 2) restricted to *open* occurrences, priced by
their unique nodes. E5 should state this contract on its member and keep the member out of `named-commits`. That
needs the consumer-declared retention decision in §6 option 2, or a reference schema that does not resolve to
`:seon.source/commit-id`, which conflicts with one schema per identity. Root rules on that schema question. It does not
block E5, which resets anyway.

## Limits

- No collection, reset or stop ran on default. Default was read only through `bin/seon status`, and its store only
  through an APFS clone.
- The probes ran in my own JVMs on scratchpad stores and the clone.
- The armed test's flake was not reproduced here (each run takes about 70 s in a throwaway JVM); §6 hands it to the
  flake hunt.
- The 62-site count is `rg` over `src` for `commit-as-db|branch-as-db|commit-database|branch-database`.

## Appendix: the probe

Run: `java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$(cat tmp/gc-one-enumeration/parent.classpath)" clojure.main held.clj <empty-dir>`.

```clojure
(require '[datahike.api :as d] '[datahike.gc :as gc] '[clojure.core.async :as async] 'seon.cluster.store
         '[datahike.index.interface :refer [-mark -seed-root! with-storage]] '[datahike.writing :refer [read-head]])
(defn cfg [dir] (assoc (seon.cluster.store/datahike-configuration dir) :schema-flexibility :read))
(defn fresh! [name] (let [c (cfg (str (first *command-line-args*) "/" name))] (d/create-database c) (d/connect c)))
(defn write! [conn n tag]
  (dotimes [i n] (d/transact conn (vec (for [j (range 400)] {:marker (str tag "-" i "-" j)
                                                            :pad (str tag i j (apply str (repeat 40 (char (+ 97 (mod j 26))))))})))))
(defn rewrite! [conn tag]
  (doseq [batch (partition-all 400 (d/q '[:find [?e ...] :where [?e :marker]] @conn))]
    (d/transact conn (mapv (fn [e] {:db/id e :pad (str tag e)}) batch))))
(defn empty-cache! [conn]
  (some-> ^com.github.benmanes.caffeine.cache.Cache (:datahike.index.interface/node-cache (:store @conn)) .invalidateAll)
  (System/gc) (Thread/sleep 200) (System/gc))
(defn read-all [db] (try (count (d/q '[:find ?m :where [_ :marker ?m]] db))
                         (catch Throwable t [:failed (ex-message t) (:type (ex-data t))])))
(defn collect! [conn remove-before & [opts]] (count (async/<!! (d/gc-storage conn remove-before (or opts {})))))
(defn cold [conn cid] (d/commit-as-db conn cid)) ; reopened from storage: children are addresses
(defn commit-keys ; datahike.gc/reachable-in-branch's head marking, for a commit key
  [store config cid seen]
  (let [{:keys [eavt-key avet-key aevt-key temporal-eavt-key temporal-avet-key temporal-aevt-key eavt-root aevt-root
                avet-root temporal-eavt-root temporal-aevt-root temporal-avet-root schema-meta-key]} (read-head store cid {:sync? true})
        mark (fn [idx root] (when idx (-mark (cond-> (with-storage (:index config) idx (:storage store)) root (-seed-root! root)) seen)))]
    (cond-> (into #{cid} (concat (mark eavt-key eavt-root) (mark aevt-key aevt-root) (mark avet-key avet-root)))
      schema-meta-key (conj schema-meta-key)
      (:keep-history? config) (into (concat (mark temporal-eavt-key temporal-eavt-root) (mark temporal-aevt-key temporal-aevt-root)
                                            (mark temporal-avet-key temporal-avet-root))))))
(defn mark-commit [cids] (fn [{store :datahike.gc/store config :datahike.gc/config}]
                           (let [seen (atom #{})] (into #{} (mapcat #(commit-keys store config % seen)) cids))))
(defn superseded! [conn] (write! conn 10 "later") (rewrite! conn "r1") (rewrite! conn "r2"))
(defn side! [conn dir] (d/branch! conn :db :side) (d/connect (assoc (cfg dir) :branch :side)))
;; A: held beyond the window
(let [conn (fresh! "a") _ (write! conn 20 "base") held (cold conn (d/commit-id @conn)) _ (rewrite! conn "pre")
      cutoff (do (Thread/sleep 50) (java.util.Date.)) _ (Thread/sleep 50) _ (superseded! conn) swept (collect! conn cutoff)]
  (empty-cache! conn) (prn :A swept (read-all held)))
;; B: held inside the window
(let [conn (fresh! "b") _ (write! conn 20 "base") cutoff (java.util.Date.) _ (Thread/sleep 50)
      held (cold conn (d/commit-id @conn)) _ (superseded! conn) swept (collect! conn cutoff)]
  (empty-cache! conn) (prn :B swept (read-all held)))
;; C: branch retired while its value is held; plain collection
(let [dir (str (first *command-line-args*) "/c") conn (fresh! "c") _ (write! conn 5 "base") side (side! conn dir)
      _ (write! side 30 "side") held (cold conn (d/commit-id @side)) _ (d/release side) _ (d/delete-branch! conn :side)
      swept (collect! conn (java.util.Date. 0))]
  (empty-cache! conn) (prn :C swept (read-all held)))
;; D: an old commit opened by id after the cutoff instant
(let [conn (fresh! "d") _ (write! conn 20 "base") cid (d/commit-id @conn) _ (rewrite! conn "pre")
      cutoff (do (Thread/sleep 50) (java.util.Date.)) _ (Thread/sleep 50) _ (superseded! conn)
      held (d/commit-as-db conn cid) swept (collect! conn cutoff)]
  (empty-cache! conn) (prn :D swept (read-all held) (some? (d/commit-as-db conn cid))))
;; E and F: A and C with the held commit as an exact root (pass {:datahike.gc/reachable-extension (mark-commit [cid])})
(shutdown-agents) (System/exit 0)
```

The run that produced the table used the same functions with E and F written out. It is kept at the scratchpad path
`hvp/held.clj` for this session only.
