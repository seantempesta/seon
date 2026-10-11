---
title: "Q2: persist content revisions in the Datahike commit"
type: research
status: owner-delegated decision; implementation remains H2
lane: sol-q2-revisions
date: 2026-10-11
---

# Decision

Choose **A: persist the prior revision context and modified attributes in each Datahike
commit**. The current path is stored commit → conservative opening context → Seon's
remembered revision chain → content-keyed acquisition and reach. H2 makes it stored
commit → restored native context → attribute lookup, removing the in-memory bridge.

The cheapest measured population scan for B takes **31–35 ms and 21.6–24.9 MB**,
before resolving transaction numbers to commit identities. Reading retained lineage adds
**22–41 ms and 41.9–58.9 MB**; both measured heads have an unavailable ancestor.
A's measured live payload is **5,439 bytes**. This favors paying a small bounded
attribute-map serialization per commit instead of reconstructing across history on cache
misses. No guard, refusal tier, retry or second cache is part of the decision.

These are measurements of reconstruction components, **not a completed, correct B**.
A current/history scan cannot recover the exact native revision context: transactions
are branch-local numbers, retractions of noHistory attributes disappear, and unknown or
schema operations advance the conservative revision without necessarily leaving ordinary
datoms. A correct B would also resolve lineage and compare the relevant parent snapshots.
The measured scan is the cost of this candidate algorithm, not a mathematical lower bound
on every possible indexed algorithm. No existing attribute-revision reconstruction seam
was found in the pinned fork. Adding such machinery to Seon is not justified by these numbers.

## Live evidence

Surface: MCP eval_clj, agent root, cluster default, mode jvm; explicit
`seon.cluster.boot/connection`. The forms read immutable values, never reload source,
start a provider, clear the shared cache, stop/reset default or create a branch.
MCP records its ordinary evaluation bookkeeping on main, so main advanced between probes.
Default started at 10:25:14 UTC; observations below were around 11:14–11:17 UTC.
Datahike pin: cb3bce514507ecc96d36b6957ad10f6e892f51cf.
The running archive was c6113d2f3cab43cfc88788e6993668da749f0753.
Astra's bridge commit is 7267d54fbbfd1530cefc4307f84c6088ea46185e.

Default's main is stored as branch :cluster-default. All seven branch heads were read.
The largest by current datoms and operation count was :agent-e24a9f457842
(commit 6acb6ab9-559d-5f42-8b04-840c666bdb59): 599,737 current datoms,
116,860 history datoms, 774,969 operations. Main initially had 597,849 current
datoms and 115,852 history datoms. Primary-only materialization used
`datahike.writing/stored->db` with secondary-indices? false; it opened no native
secondary resources or connections requiring retirement.

| Component | Main | Largest agent branch |
|---|---:|---:|
| Datoms visited per optimized scan | 713,897 | 716,597 |
| Distinct attributes encountered | 435 | 439 |
| Scan samples, ms | 34.66 / 31.77 / 31.15 | 33.32 / 32.75 / 31.42 |
| Thread allocation samples, decimal MB | 24.86 / 21.63 / 21.63 | 21.92 / 21.71 / 21.71 |
| Retained-lineage read, ms / MB | 41.17 / 58.92 | 22.48 / 41.91 |
| Commit ids visited / unavailable | 59 / 1 | 43 / 1 |
| Available distinct max-tx values | 58 | 42 |

The scan is O(current + historical datoms), maintaining O(distinct attributes) output.
The lineage read is O(retained ancestor commits) and stores O(commits) ids; it reads
commit records, not datoms. Component sums are roughly 72–76 ms / 81–84 MB on main
and 54–56 ms / 64 MB on the branch, **not measured end-to-end reconstruction**.
They omit snapshot comparisons, handling missing history and tx-to-commit attribution.
They are comparable to the hybrid's entire 50–65 ms warm agent-start floor.

First, a plain persistent-map/keyword scan measured main 333.74 / 54.33 / 50.39 ms,
776.76 / 271.80 / 265.72 MB; branch 88.01 / 71.78 / 45.63 ms,
324.87 / 282.98 / 267.01 MB (result/e148abbedac02).
The transient builder and direct Datom fields above removed that probe's repeated
allocation; the decision uses the smaller algorithm (result/ec32f5caaf762).
All index traversal samples used the existing live process and warmed dependency caches.
There was no process-cold disk-cache experiment or forced GC. MB is calling-thread
allocation from `seon.profile/measure`, not retained heap. Request envelopes include
MCP printing and recording separately; optimized scan evaluation 213 ms, whole request
262.4 ms / 142.7 MB. Every successful measurement request was under one second.

## A: map size and growth

Use the existing `konserve.serializers/fressian-serializer` and
`konserve.protocols/-serialize` into an in-memory output stream with the store's
write handlers. These are raw Fressian payload sizes, without store framing,
compression, index writes or fsync; A's actual write-latency increment is not measured here.

| Population | Entries | Raw map bytes |
|---|---:|---:|
| Live main native attribute revisions | 158 | 5,342 |
| Same map plus conservative revision, complete proposed prior context | 158 | 5,439 |
| Main's observed current/history attributes, each given a distinct UUID | 435 | 15,833 |
| Largest branch's observed attributes, each given a distinct UUID | 439 | 16,049 |
| Every main schema attribute, each given a distinct UUID | 2,344 | 67,265 |

The last three are **sizing models**, not recovered native maps; distinct UUIDs avoid
optimistic serialization sharing. The largest branch's bridge entry was absent
(result/e64228543cdfa), so its exact native map size is unavailable after reopen.
Its observed 439-attribute model is about 16 KB. The live main context is exact.
The bridge weighs that live map at 10,368 bytes under its installed 64 × (4 + entries)
estimate, rather than an actual heap measurement.

Growth is O(distinct attributes revised since the conservative advance), not O(datoms),
agents or number of transactions. Repeated writes to an attribute replace its UUID;
schema/unknown operations clear the map at `datahike.db/advance-cache-context`.
Schema accretion can raise the population; the 2,344-entry model is a snapshot,
not a timeless ceiling. Over C retained commits storage grows as the sum of their map
sizes: at today's live size 10,000 commits add about 54.4 MB of raw context payload;
at the 439-entry model about 160.5 MB, before compression and reclamation.
This is a full prior map per commit: it avoids a parent-chain read on every load.
A delta-only format saves repeated bytes but recreates the ancestry dependence and is
not selected. Modified attributes are also stored; their size follows the transaction's
distinct attribute count and must be measured with H2's write clock row.

## Cache misses

At entry `seon.cache/status` held 487.79 MB of its 512.00 MB budget (95.3%).
During probes it rose to 493.73 MB (96.4%). The bridge kind held only 34,048 estimated
bytes, with 87 hits, 752 misses, **58 evictions** since JVM start; final misses 753.
The absent largest-branch entry demonstrates an actual miss for a retained branch head.
The probe itself caused one additional lookup miss when asking for that absent entry.

Restart empties all process-local entries. Eviction loses individual entries and can recur
for the same commit, so B is **not once per commit per process** at a bounded 512 MB budget.
The observed 58 evictions over roughly 50 minutes show existing pressure; they do not
identify which commits were revisited or forecast a miss rate after H4 removes overlays.
The 10% hit rate is lookup-level, not agent-start-level: the bridge's loader returns nil
for unknown commits, so its 752 misses must not be called 752 successful reconstructions.
A miss costs B only if that head is requested again. A will read the persisted context
with the commit it already loads, independent of the central cache's survival.

## H2 interface and proof

At `datahike.writing/db->stored`, retain the prior conservative revision, prior
attribute revisions and the modified-attribute set **before**
`datahike.writing/create-commit-id`. Never include the not-yet-known commit id in
its own identity input. `datahike.writing/stored->db`,
`datahike.versioning/attached-cache-context` and `datahike.connector/connect`
restore prior + modified-at-current-commit using the existing
`datahike.db/advance-cache-context` semantics, including conservative advances.
A persisted commit is its own authority; no Seon chain walk remains.
Old commits retain today's conservative interpretation; this decision adds no migration.

Remove `seon.db/remember-revisions!`, `seon.db/recorded-revisions`,
:seon.db/commit-revisions and the remembered-chain loop in
`seon.db/content-revision`; convert `seon.cluster.registry/branch!` and
`seon.cluster.registry/fork!` in the same H2 slice. H3 then uses this one identity.

The proposed H2 regression `seon.db-test/a-reopened-commit-carries-its-content-revisions`
must materialize from the raw store with no remembered entry, compare unchanged program
attribute revisions and the changed attribute's commit, and include schema/unknown
advance and restart/eviction cases. It is an implementation proof to add red before H2,
not an existing green test or proof supplied by this documentation commit.
Retain Astra's existing bridge regression until its callers convert.

## Reproduce the measurements

The following bounded JVM expression is the optimized scan used above; repeat main
and the named largest branch three times, with explicit root/default MCP selection.

```clojure
(let [c (seon.cluster.boot/connection "default")
      store (:store @c)
      scan (fn [db]
             (persistent!
               (reduce (fn [m ^datahike.datom.Datom d]
                         (let [a (.-a d) t (.-tx d)]
                           (assoc! m a (max (long (get m a 0)) (long t)))))
                       (transient {})
                       (concat (:eavt db) (:temporal-eavt db)))))]
  (mapv (fn [b]
          (let [db (if (= b :cluster-default) @c
                     (datahike.writing/stored->db
                       (datahike.writing/read-head store b) store
                       {:secondary-indices? false}))]
            {:branch b
             :datoms-read (+ (count (:eavt db)) (count (:temporal-eavt db)))
             :samples (mapv (fn [_]
                              (let [m (seon.profile/measure #(scan db))]
                                {:ms (:seon.profile/ms m)
                                 :mb (/ (:seon.profile/alloc-bytes m) 1000000.0)
                                 :attributes (count (:seon.profile/value m))}))
                            (range 3))}))
        [:cluster-default :agent-e24a9f457842]))
```

For the lineage measurement, start with the head's commit id and follow
:datahike/parents via `datahike.writing/read-head` under
`seon.profile/measure`, keeping a set of visited ids; stop each ancestry path when
the stored record is absent. No database materialization or datom reads are in that leg.
Its exact bounded form follows.

```clojure
(let [c (seon.cluster.boot/connection "default") db @c store (:store db)
      size (fn [value] (let [out (java.io.ByteArrayOutputStream.)]
                        (konserve.protocols/-serialize
                          (konserve.serializers/fressian-serializer)
                          out (:write-handlers store) value)
                        (.size out)))
      revisions (get-in db [:cache-context :datahike.cache/attribute-revisions])
      context (select-keys (:cache-context db)
                [:datahike.cache/conservative-revision :datahike.cache/attribute-revisions])
      attrs (keys (:schema db))]
  {:live-revisions (count revisions)
   :live-map-fressian-bytes (size revisions)
   :live-context-fressian-bytes (size context)
   :bridge-estimated-bytes (* 64 (+ 4 (count revisions)))
   :all-schema-attrs (count attrs)
   :all-schema-map-distinct-uuid-bytes
     (size (into {} (map (fn [a] [a (random-uuid)])) attrs))
   :largest-bridge
     (some-> (#'seon.db/recorded-revisions
               (get-in (datahike.writing/read-head store :agent-e24a9f457842)
                 [:meta :datahike/commit-id]))
             :datahike.cache/attribute-revisions count)})

(let [c (seon.cluster.boot/connection "default") store (:store @c)]
  (mapv (fn [b]
          (let [head (datahike.writing/read-head store b)
                m (seon.profile/measure
                    (fn []
                      (loop [todo [(get-in head [:meta :datahike/commit-id])]
                             seen #{} missing #{} txs #{}]
                        (if-let [cid (peek todo)]
                          (if (seen cid)
                            (recur (pop todo) seen missing txs)
                            (if-let [raw (datahike.writing/read-head store cid)]
                              (recur (into (pop todo) (get-in raw [:meta :datahike/parents]))
                                     (conj seen cid) missing (conj txs (:max-tx raw)))
                              (recur (pop todo) (conj seen cid) (conj missing cid) txs)))
                          {:commits (count seen) :missing (count missing)
                           :txs (count txs)}))))]
            {:branch b :ms (:seon.profile/ms m)
             :mb (/ (:seon.profile/alloc-bytes m) 1000000.0)
             :lineage (:seon.profile/value m)}))
        [:cluster-default :agent-e24a9f457842]))
```

## Verification and limits

Default reported :faulted at entry: two failed tests, two open faults and the existing
cache discipline warning, with all boot layers ready. This work did not repair or restart it.
One exploratory value printed as #unprintable with ClassCastException
(result/e07b6bb1691b9); smaller summaries supplied all measurements. A malformed probe
(result/eedca05bb6633) returned a reader error; the corrected form above ran successfully.

Foreign boundary: other lanes held src/seon/schema.clj, src/seon/instrument.clj and
related test/benchmark edits; they were not loaded, edited, restored or staged.
Only this document and resources/seon/tasks/seon.db.edn are included in the candidate.
Reaching check: `seon.task-ingestion-test/the-checkouts-records-ingest-by-symbol-and-repeat-as-a-no-op`
reads all shipped task files and checks repeat ingestion. The implementation's H2 proof
does not exist yet. No runtime declaration changes; net source/test lines zero. Full-tree clj-kondo with
--cache false: 6,689 ms, zero errors, 1,513 existing warnings. Named candidate check
passed through bin/lane commit (first landing b7fdd1685): run 2beb6f1c0574, one
executed test, three assertions, zero failures or errors, body 2,799 ms. Total check
40,764 ms; throwaway boot 26,979 ms, publication 3,233 ms and adoption 553 ms.
The check published and adopted HEAD plus only these two paths; default stayed at
pid 8540 and its original start instant. These platform-check costs are separate
from the Q2 reconstruction measurements. No pre-existing red was encountered in
the named test; the two entry-status failed tests were not run in this narrow check.
