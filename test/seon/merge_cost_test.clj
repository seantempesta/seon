(ns seon.merge-cost-test
  "The one merge costs what changed, never the program: over branches that hold n forms and each
  changed one, seon.writer/candidate (seon.merge/decide and carry, the replay) reads only the facts the branch wrote."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.lib] [clojure.test :refer [deftest is use-fixtures]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc] [seon.vector :as vector] [seon.merge :as merge]
            [seon.writer] [clojure.java.shell]))

;; One store on disk of this test's own for its three proofs (a branch each, removed by the namespace's fixture), the last resort (docs/storage.md): the in-memory test
;; store gives its index nodes no addresses, so no two db values of it share a stored subtree
;; and a structural diff of them reads everything; LMDB, as in production, shares them.
(def ^:private store
  (delay (:conn (pdb/open! :lmdb (str (System/getProperty "java.io.tmpdir") "/seon-merge-cost-" (random-uuid)) 0 :db))))

(defn three-of
  "seon.writer/candidate's arguments: theirs (main), base, a branch holding n forms (app.pc/f0..fn-1), and ours (the branch head),
  each a branch of base that redefined one form of its own, all read from the store, and no meta."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db :map]]}
  [n]
  (let [conn @store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "n" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (pdb/connect (assoc cfg :branch b))
        _ (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) (range n)))})
        side (fn [nm i] (let [s (keyword (str (name b) "-" nm))
                              _ (d/branch! conn b s)
                              c (pdb/connect (assoc cfg :branch s))]
                          (d/transact c {:tx-data (program/record-tx @c (#'pc/acct i "(inc x)"))})
                          (d/release c)
                          (pdb/head conn s)))
        base (pdb/head conn b)
        ours (side "ours" 1) theirs (side "theirs" (dec n))]
    (d/release bc)
    [theirs base ours {}]))

;; a-three-way-costs-the-changes-not-the-program, re-pointed from seon.merge/delta-three-way (deleted) at the one merge
(deftest ^{:seon.test/estimate {:ticks 50000000}} a-three-way-costs-the-changes-not-the-program
  (cost/proof
      {:of 'seon.writer/candidate :input 'seon.merge-cost-test/three-of
       :n "forms the branches hold"
       :work (str "the facts the branch wrote since base, read from its one commit (d/changes, never a diff of the values), "
                  "each fact's key read on base, ours and theirs by index seeks (log n each), the same facts carried and "
                  "replayed as one transaction through the write check onto theirs: a fixed number of facts, whatever n")
       :growth :log
       ;; estimate, as arithmetic: one redefinition writes ~10 facts (the new form's ~8 and the var's :var/form, its retraction
       ;; included), read once from the commit and mapped to main's entities once (seon.merge/mapped, ~0.2 MB); decide seeks each of ~10
       ;; keys on three dbs, 30 seeks of ~20 KB, log n deep (~0.6 MB); carry checks each entity for a whole delete (~0.2 MB); the replay's write
       ;; check of one transaction of ~10 datoms and the 2 entities it touches (~1 MB); settling (no entry) and the cites of no task
       ;; (~0.2 MB): ~2.6 MB fixed; as n grows only the ~60 seeks deepen, a node more each per tree level. The old three-way
       ;; walked one :aevt range per program attribute and side (~120 descents, 10-14 MB), which the one merge never reads
       :per-n {:ns 200000 :bytes 500000}
       :fixed {:ns 4000000 :bytes 3000000}
       :sizes [10 30 100]}))

(defn data-of
  "seon.merge/decide's arguments for a merge of data: base holding n data attributes (:app.data/a0..an-1, longs) and one
  entity with a value of the first, the branch and main, each a branch of it that changed that one value, all read from the store."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (let [conn @store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "d" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (pdb/connect (assoc cfg :branch b))
        attrs (mapv #(keyword "app.data" (str "a" %)) (range n))
        _ (d/transact bc {:tx-data (mapv #(hash-map :db/ident % :db/valueType :db.type/long :db/cardinality :db.cardinality/one) attrs)})
        _ (d/transact bc {:tx-data [{:app.data/a0 1}]})
        e (d/q '[:find ?e . :where [?e :app.data/a0 1]] @bc)
        side (fn [nm v] (let [s (keyword (str (name b) "-" nm))
                              _ (d/branch! conn b s)
                              c (pdb/connect (assoc cfg :branch s))]
                          (d/transact c {:tx-data [[:db/add e :app.data/a0 v]]})
                          (d/release c)
                          (pdb/head conn s)))
        r [(pdb/head conn b) (side "ours" 2) (side "theirs" 3)]]
    (d/release bc)
    r))

;; estimate: its fixtures (data-of 10, 30, 100) declare 140 attributes in three schema transactions at ~35k an attribute
;; (0.4M, 1.1M, 3.5M) and their commits (~1.2M each), then three branches, three connects and four transactions a size
;; (~1.3M a size); the proof's warm calls of the merge (~20 a size at ~0.2M): ~20M. It was 47M: data-of builds its
;; attributes with hash-map, and each schema datom before :db/ident rebuilt the whole reverse schema (Datahike, fixed)
(deftest ^{:seon.test/estimate {:ticks 20000000}} a-merge-reads-the-data-attributes-from-the-index
  ;; task seon.merge/data-attrs-read-from-the-attribute-index: a merge of declared data (:all)
  ;; read which attributes are data from (d/schema db), which rebuilds the whole schema map on
  ;; every call, once per db per read; agents merge main at every green. The one merge's decision
  ;; (seon.merge/decide, re-pointed from the deleted delta-three-way) asks only the attributes of the facts
  ;; the branch wrote (a schema map lookup, the entity index's derived set), so it costs the same however
  ;; many attributes the store declares
  (cost/proof
      {:of 'seon.merge/decide :input 'seon.merge-cost-test/data-of
       :n "data attributes the store declares, one value changed on each side"
       :work (str "the facts the branch wrote (one value, from its one commit), the key each bears read on base, the branch "
                  "and main by one seek, and the conflict's entity named: never the schema, so never the attributes' count")
       :growth :constant
       ;; estimate, as arithmetic, for decide: the branch's one commit read (d/changes, ~4 datoms, ~0.1 MB); its facts filtered
       ;; (one entity-index read, a held? seek each on the branch and base, ~0.05 MB); the one key read on three dbs (3 seeks,
       ;; ~0.06 MB); the conflict named, the entity's identity sought by each identity attribute of the index on the branch and
       ;; main (~2 x 40 seeks, ~0.6 MB, the largest part); the branch's entries queried (~0.05 MB): ~0.9 MB, whatever n.
       ;; The history below measured the deleted delta-three-way
       ;; measured, 5 repeats a size: 562 KB / 39 calls at 100, 592 KB at 300, 585 KB at 1,000
       ;; (43 calls since touched-keys asks each of the 4 differing datoms' attributes whether a key
       ;; reads it, one call apiece: measured with the filter inline, in a def and as a contracted
       ;; function, 43, 43 and 47); reading (d/schema db) per db per read it was 8.11 / 11.84 / 25.57 MB and 2,726 / 3,926 /
       ;; 8,126 calls; 46 since touched-keys calls touched-by (which takes datoms a caller already holds) once per side;
       ;; 71 since a fact is a side's change only if it wrote it, about 23 more, split approximately: seon.merge/derived? once per
       ;; attribute asked whether it is data (~14: the differing datoms' attributes on two dbs, the keys' on three), and per changed key
       ;; and side one seon.merge/owned?, one tx-branch of the other side's last writer and one per datom it added (~9);
       ;; 47 since the derived attributes are the entity index's own (one seon.db/index read per data-attr?, 7, never a derived? per
       ;; attribute) and a transaction's entity bears no key (its instant, runtime and branch ride with its datoms): measured 47;
       ;; 49 since a datom a side added is its change only if base's history doesn't hold it (seon.merge/shared?, one per side here)
       ;; 58 since a side's own write older than base's at its key is a conflict (seon.merge/overtaken, one per changed key and side, ~9)
       :fixed {:bytes 1000000}
       :sizes [10 30 100]}))

(defn embedded-of
  "seon.merge/delta-changes's arguments for a main that only embedded (main's side of a merge, as seon.merge/readiness reads it):
  base holding n forms, and theirs a branch of it that wrote a vector fact set on each of the n forms (what seon.vector writes of a
  form's text), no form's program content changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db [:set :keyword]]]}
  [n]
  (let [conn @store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "e" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (pdb/connect (assoc cfg :branch b))
        _ (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) (range n)))})
        es (vec (d/q '[:find [?e ...] :where [?e :form/id _]] @bc))
        s (keyword (str (name b) "-theirs"))
        _ (d/branch! conn b s)
        c (pdb/connect (assoc cfg :branch s))
        _ (d/transact c {:tx-data (vector/tx-data es (vec (repeat n "1")) (vec (repeat n [0.5 0.5])) "m")})
        r [(pdb/head conn b) (pdb/head conn s) #{}]]
    (d/release c) (d/release bc)
    r))

(def ^:private diff-base
  ;; a branch of the store holding 5,000 entities of two attributes (10,000 :eavt datoms, 11,462 with the schema's), built once:
  ;; its :eavt has three levels (root at level 2, 11 children), as at 20,000 (40 children), so two commits share stored
  ;; subtrees as a large store's do; 20,000 cost ~4x the ticks for the same proof (deps, 2026-11-13)
  (delay (let [conn @store
               cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
               b (keyword (str "diff-" (random-uuid)))
               _ (d/branch! conn :db b)
               bc (pdb/connect (assoc cfg :branch b))]
           (d/transact bc {:tx-data [{:db/ident :app.diff/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                                     {:db/ident :app.diff/m :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]})
           (doseq [part (partition-all 2000 (range 5000))]
             (d/transact bc {:tx-data (mapv (fn [i] {:app.diff/n i :app.diff/m i}) part)}))
           (let [es (vec (d/q '[:find [?e ...] :where [?e :app.diff/n _]] @bc))]
             (d/release bc)
             [cfg b es]))))

(defn diff-of
  "datoms-diff's arguments for two consecutive commits of one branch n values apart: the base
  branch's head (diff-base) and the head of a branch of it whose one commit replaced :app.diff/m on
  n entities spread over the store (2n + 1 datoms differ, the transaction's own included)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db [:= :eavt] [:= []]]]}
  [n]
  (let [[cfg b es] @diff-base
        conn @store
        s (keyword (str (name b) "-" n "-" (random-uuid)))
        _ (d/branch! conn b s)
        c (pdb/connect (assoc cfg :branch s))
        rnd (java.util.Random. n)
        _ (d/transact c {:tx-data (mapv (fn [e] [:db/add e :app.diff/m (- (inc (.nextInt rnd 1000000)))])
                                        (take n (distinct (repeatedly #(nth es (.nextInt rnd (count es)))))))})
        r [(pdb/head conn b) (pdb/head conn s) :eavt []]]
    (d/release c)
    ;; diffed once here, so the nodes that differ are read from the store before the measure: a first call's
    ;; reads follow what the node cache holds after the commit, not the walk (the cold read is the store's cost)
    (apply d/datoms-diff r)
    r))

;; estimate: the fixture's 10,000 datoms in transactions of 4,000 (~7k ticks a datom in a large transaction, not a
;; 10-datom one's ~12k: ~75M), then three sizes of branch, transact and measured diffs (~10M). Measured 86M
(deftest ^{:seon.test/estimate {:ticks 90000000}} a-diff-of-two-commits-costs-the-nodes-that-differ
  ;; See docs/storage.md#merges-read-what-changed (seon.ctx/moved's call: two values of one branch, :eavt, the whole range)
  (let [[a b] (diff-of 10) [gone added] (d/datoms-diff a b :eavt [])]
    (is (= [10 13] [(count gone) (count added)]) "ten values replaced, and the transaction's own datoms: its instant, runtime and branch"))
  (cost/proof
    {:of 'datahike.api/datoms-diff :input 'seon.merge-cost-test/diff-of
     :n "values replaced on entities spread over a 10,000-datom store of three :eavt levels, in one commit"
     :work (str "the nodes that differ between the two values and nothing else: every child stored at an "
                "address both hold is skipped unread, the children of each differing branch are compared by "
                "one hashed lookup each and the differing leaves' datoms merged once; at most one root-to-leaf "
                "path of differing nodes per value replaced")
     :growth :linear
     ;; measured 2026-11-11 at 1, 10 and 100, the nodes read: 61k, 250k and 1.22M ticks, 104 KB, 417 KB and 1.55 MB
     ;; (Datahike 6def526a; sublinear at 100: the changes begin to share branches). At ae20699b (each leaf copied by
     ;; .keys, a vector per child) 71.5k, 304k and 1.60M ticks, 289 KB, 1.37 MB and 6.95 MB; before it (a membership
     ;; lookup of every datom in both whole sets, java.util.HashSet.retainAll, which Jolt answers in n x m) 113k,
     ;; 768k and 6.91M ticks
     :per-n {:ticks 16000 :bytes 90000}
     :fixed {:ticks 60000 :bytes 300000}
     :sizes [1 10 100]}))

(use-fixtures :once
  (fn [run]
    (try (run)
         (finally (when (realized? store)
                    (let [path (get-in @@store [:config :store :path])]
                      (d/release @store) (seon.lib/remove-tree! path)))))))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-main-that-only-embedded-changes-no-key
  (let [[base theirs das] (embedded-of 10)]
    (is (empty? (merge/touched-keys base [theirs] das)) "embeddings are no program content")
    (is (= {} (merge/delta-changes base theirs das)) "so main changed nothing")
    ;; the one merge of that main into a branch that changed nothing: no conflict, nothing to replay
    (is (nil? (:conflicts (merge/decide base base theirs))))
    (is (= [] (:txs (merge/carry base base theirs))))))

(deftest ^{:seon.test/estimate {:ticks 35000000}} a-main-that-only-embedded-costs-nothing
  ;; (its digest does not reach delta-changes: a quoted name; this line moves it when the producer changes)
  ;; task seon.merge/touched-keys-are-what-the-program-changed: an entity whose only differing
  ;; datoms are a platform record's (seon.vector's embeddings) bears no changed key, so reading
  ;; main's change (seon.merge/delta-changes, main's side of seon.merge/readiness; re-pointed from the
  ;; deleted delta-three-way, which read both sides) reads none of its n forms. The one merge's decision
  ;; reads only the branch's facts, never main's embeddings
  (cost/proof
    {:of 'seon.merge/delta-changes :input 'seon.merge-cost-test/embedded-of
     :n "forms main embedded, none changed"
     :work (str "one :aevt descent per program attribute on main's side (the embedding attributes are not walked), "
                "none differing: no key is read, whatever the number of forms main embedded")
     :growth :constant
     ;; estimate, as arithmetic: one side's ~60 program attributes, one :aevt descent each (~30 KB a descent at these sizes), ~1.8 MB;
     ;; no key, so no content read: ~2 MB, half the two-sided figure the deleted delta-three-way measured (3.55 MB, below)
     ;; measured: 16 calls and 3.55 MB at 10 (the descents), the same at 100 and 1,000. Walking the one
     ;; :eavt diff instead read every embedded datom (the five seon.vector attributes of each form):
     ;; 0.67 MB at 10, 6.3 MB at 100, 63 MB at 1,000, and on a root of 4,363 embedded forms 313M ticks
     ;; and 640 MB for no key. Before that again: 536 calls, 11 MB at n = 10
     ;; 27 calls since 2026-10-30: seon.merge/content-attr, one contracted call per side's filter, was
     ;; uncontracted (as a contracted predicate asked of each attribute it was 1,771)
     ;; 28 since the base is checked for keys a side wrote before the other rewrote them (seon.merge/overtaken-base, one call, no key here)
     :fixed {:bytes 2000000}
     :sizes [10 30 100]}))

(defn moved-main-of
  "merge-main's arguments: base (a store branch holding 2 forms, app.pc/f0..f1), head, a branch of it that redefined f0 once, and main,
  base after n new forms of its own (app.pc/f2..fn+1): a branch that wrote one form against a main that moved n."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (let [conn @store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "m" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (pdb/connect (assoc cfg :branch b))
        _ (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) (range 2))) :tx-meta {:seon.tx/branch :main}})
        base (pdb/head conn b)
        hb (keyword (str (name b) "-head"))
        _ (d/branch! conn b hb)
        hc (pdb/connect (assoc cfg :branch hb))
        _ (d/transact hc {:tx-data (program/record-tx @hc (#'pc/acct 0 "(inc x)")) :tx-meta {:seon.tx/branch hb}})
        _ (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) (range 2 (+ 2 n)))) :tx-meta {:seon.tx/branch :main}})
        r [base (pdb/head conn hb) (pdb/head conn b)]]
    (d/release hc) (d/release bc)
    r))

;; Estimate, from its parts: the branch's change read from its one commit (changed-datoms, d/changes: the ~10 facts one redefinition
;; writes); each fact held? on the branch and base (~20 seeks) and mapped to main's entity by its identity (identity-of over the entity
;; index's identity attributes, a seek each, for the ~3 entities); each of the ~3 keys read on base, main and the branch (held-at, 9
;; seeks); the branch's unmerged entries queried once and entry-eids again for the settling retraction: ~2 MB, the identity seeks most
;; of it. (It was 117 contracted calls on the deleted program-key decision.) Nothing of main's n forms is read: constant in n
(deftest ^{:seon.test/estimate {:ticks 60000000}} merge-main-costs-the-branchs-change-not-mains-moves
  (cost/proof
      {:of 'seon.merge/decide :input 'seon.merge-cost-test/moved-main-of
       :n "forms main defined since the branch's base"
       :work (str "the datoms the branch wrote since base (one diff of base and head), the keys they bear, and main's content at "
                  "those keys only: main's own moves are never read, so the same work however far main moved")
       :growth :constant
       :fixed {:bytes 2000000}
       :sizes [10 30 100]}))


