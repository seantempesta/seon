(ns seon.merge-cost-test
  "The one merge costs what changed, never the program: over branches that hold n forms and each
  changed one, seon.writer/candidate (seon.merge/decide and carry, the replay) reads only the facts the branch wrote."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.lib] [clojure.test :refer [deftest is]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc] [seon.vector :as vector] [seon.merge :as merge]
            [seon.writer] [seon.test-store :as ts]))

;; Each fixture is a branch of the shared test store (seon.test-store/test-branch!, docs/testing.md#testing-in-the-world), as stored values: a
;; structural diff and a commit's changes (d/changes) need committed values, which a db value of d/db-with is not. The branch is deleted when its
;; fixture returns; the head values it answers stay readable (immutable nodes).

(defn three-of
  "seon.writer/candidate's arguments: theirs (main), base, a branch holding n forms (app.pc/f0..fn-1), and ours (the branch head),
  each a branch of base that redefined one form of its own, all read from the store, and no meta."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db :map]]}
  [n]
  (ts/test-branch!
    (fn [cfg conn]
      (let [b (:branch cfg)
            _ (d/transact conn {:tx-data (vec (mapcat #(program/account-tx @conn (#'pc/acct % "x") %) (range n)))})
            side (fn [nm i] (let [s (keyword (str (name b) "-" nm))
                                  _ (d/branch! conn b s)
                                  c (pdb/connect (assoc cfg :branch s))]
                              (d/transact c {:tx-data (program/record-tx @c (#'pc/acct i "(inc x)"))})
                              (d/release c)
                              (pdb/head conn s)))
            base (pdb/head conn b)
            ours (side "ours" 1) theirs (side "theirs" (dec n))]
        [theirs base ours {}]))))

;; a-three-way-costs-the-changes-not-the-program, re-pointed from seon.merge/delta-three-way (deleted) at the one merge
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-three-way-costs-the-changes-not-the-program
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
       :per-n {:bytes 20000}
       ;; measured at 3, 6 and 12 forms: 0.93 MB at each (a fixed set of seeks); bounds held to ~1.3x of it, so a walk of the program's attributes (+0.8 MB) is red
       :fixed {:bytes 1000000}
       :sizes [3 6 12]}))

(defn data-of
  "seon.merge/decide's arguments for a merge of data: base holding n data attributes (:app.data/a0..an-1, longs) and one
  entity with a value of the first, the branch and main, each a branch of it that changed that one value, all read from the store."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (ts/test-branch!
    (fn [cfg conn]
      (let [b (:branch cfg)
            attrs (mapv #(keyword "app.data" (str "a" %)) (range n))
            _ (d/transact conn {:tx-data (mapv #(hash-map :db/ident % :db/valueType :db.type/long :db/cardinality :db.cardinality/one) attrs)})
            _ (d/transact conn {:tx-data [{:app.data/a0 1}]})
            e (d/q '[:find ?e . :where [?e :app.data/a0 1]] @conn)
            side (fn [nm v] (let [s (keyword (str (name b) "-" nm))
                                  _ (d/branch! conn b s)
                                  c (pdb/connect (assoc cfg :branch s))]
                              (d/transact c {:tx-data [[:db/add e :app.data/a0 v]]})
                              (d/release c)
                              (pdb/head conn s)))]
        [(pdb/head conn b) (side "ours" 2) (side "theirs" 3)]))))

;; estimate: a fixture (data-of) is ~1.5M ticks fixed (a test-store branch 0.4M, two side branches with a connect and a transaction each 0.4M)
;; and ~35k an attribute declared; four builds (the warm call, then 3, 6 and 12 attributes): 4 x 1.5M + 21 x 35k, ~7M, and the merge's
;; ~20 warm calls a size at ~0.2M: 3M less what the fixtures share; ~8M. Sizes are small because each fixture's fixed cost is paid 4 times
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-merge-reads-the-data-attributes-from-the-index
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
       :fixed {:bytes 550000}
       :sizes [3 6 12]}))

(defn embedded-of
  "seon.merge/delta-changes's arguments for a main that only embedded (main's side of a merge, as seon.merge/readiness reads it):
  base holding n forms, and theirs a branch of it that wrote a vector fact set on each of the n forms (what seon.vector writes of a
  form's text), no form's program content changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db [:set :keyword]]]}
  [n]
  (ts/test-branch!
    (fn [cfg conn]
      (let [b (:branch cfg)
            _ (d/transact conn {:tx-data (vec (mapcat #(program/account-tx @conn (#'pc/acct % "x") %) (range n)))})
            es (vec (d/q '[:find [?e ...] :where [?e :form/id ?i] [(clojure.string/starts-with? ?i "app.pc/f")]] @conn))
            s (keyword (str (name b) "-theirs"))
            _ (d/branch! conn b s)
            c (pdb/connect (assoc cfg :branch s))
            _ (d/transact c {:tx-data (vector/tx-data es (vec (repeat (count es) "1")) (vec (repeat (count es) [0.5 0.5])) "m")})
            r [(pdb/head conn b) (pdb/head conn s) #{}]]
        (d/release c)
        r))))

(defn diff-of
  "datoms-diff's arguments for the test store's seed (~5,900 datoms, so two commits share stored subtrees) and the head of a branch
  of it that replaced :var/doc on n entities spread over it (2n + 1 datoms differ)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db [:= :eavt] [:= []]]]}
  [n]
  (ts/test-branch!
    (fn [cfg conn]
      (let [b (:branch cfg)
            es (vec (sort (d/q '[:find [?e ...] :where [?e :var/doc _]] @conn)))
            a (pdb/head conn b)
            s (keyword (str (name b) "-" n))
            _ (d/branch! conn b s)
            c (pdb/connect (assoc cfg :branch s))
            rnd (java.util.Random. n)
            _ (d/transact c {:tx-data (mapv (fn [e] [:db/add e :var/doc (str "doc " (.nextInt rnd 1000000))])
                                            (take n (distinct (repeatedly #(nth es (.nextInt rnd (count es)))))))})
            r [a (pdb/head conn s) :eavt []]]
        (d/release c)
        ;; diffed once here, so the nodes that differ are read from the store before the measure (the cold read is the store's cost)
        (apply d/datoms-diff r)
        r))))

;; estimate: three sizes of a test-store branch (0.4M), a side branch, connect and a transaction of n values (~0.4M + n x 12k) and a diff
;; read once before the measure and once measured (~0.1M + n x 4k, the nodes that differ): 3 x 1M + 111 x 16k, plus the first assertion's
;; fixture of 10 (~1M): ~6M; ~8M budgeted
(deftest ^{:seon.test/estimate {:ticks 8000000}} a-diff-of-two-commits-costs-the-nodes-that-differ
  ;; See docs/storage.md#merges-read-what-changed (seon.ctx/moved's call: two values of one branch, :eavt, the whole range)
  (let [[a b] (diff-of 10) [gone added] (d/datoms-diff a b :eavt [])]
    (is (= [10 13] [(count gone) (count added)]) "ten values replaced, and the transaction's own datoms: its instant, runtime and branch"))
  (cost/proof
    {:of 'datahike.api/datoms-diff :input 'seon.merge-cost-test/diff-of
     :n "values replaced on entities spread over a 5,900-datom store of three :eavt levels, in one commit"
     :work (str "the nodes that differ between the two values and nothing else: every child stored at an "
                "address both hold is skipped unread, the children of each differing branch are compared by "
                "one hashed lookup each and the differing leaves' datoms merged once; at most one root-to-leaf "
                "path of differing nodes per value replaced")
     :growth :linear
     ;; measured at 1, 10 and 100 on a 5,900-datom store: 106k, 293k and 374k ticks (sublinear at 100: the changes share branches). Measured 2026-11-11 on a 10,000-datom store at the same sizes, the nodes read: 61k, 250k and 1.22M ticks, 104 KB, 417 KB and 1.55 MB
     ;; (Datahike 6def526a; sublinear at 100: the changes begin to share branches). At ae20699b (each leaf copied by
     ;; .keys, a vector per child) 71.5k, 304k and 1.60M ticks, 289 KB, 1.37 MB and 6.95 MB; before it (a membership
     ;; lookup of every datom in both whole sets, java.util.HashSet.retainAll, which Jolt answers in n x m) 113k,
     ;; 768k and 6.91M ticks
     :per-n {:ticks 5000 :bytes 1000}
     :fixed {:ticks 150000 :bytes 100000}
     :sizes [1 10 100]}))

(deftest ^{:seon.test/estimate {:ticks 4000000}} a-main-that-only-embedded-changes-no-key
  (let [[base theirs das] (embedded-of 3)]
    (is (empty? (merge/touched-keys base [theirs] das)) "embeddings are no program content")
    (is (= {} (merge/delta-changes base theirs das)) "so main changed nothing")
    ;; the one merge of that main into a branch that changed nothing: no conflict, nothing to replay
    (is (nil? (:conflicts (merge/decide base base theirs))))
    (is (= [] (:txs (merge/carry base base theirs))))))

(deftest ^{:seon.test/estimate {:ticks 12000000}} a-main-that-only-embedded-costs-nothing
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
     :fixed {:bytes 1200000}
     :sizes [3 6 12]}))

(defn moved-main-of
  "merge-main's arguments: base (a store branch holding 2 forms, app.pc/f0..f1), head, a branch of it that redefined f0 once, and main,
  base after n new forms of its own (app.pc/f2..fn+1): a branch that wrote one form against a main that moved n."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (ts/test-branch!
    (fn [cfg conn]
      (let [b (:branch cfg)
            _ (d/transact conn {:tx-data (vec (mapcat #(program/account-tx @conn (#'pc/acct % "x") %) (range 2))) :tx-meta {:seon.tx/branch :main}})
            base (pdb/head conn b)
            hb (keyword (str (name b) "-head"))
            _ (d/branch! conn b hb)
            hc (pdb/connect (assoc cfg :branch hb))
            _ (d/transact hc {:tx-data (program/record-tx @hc (#'pc/acct 0 "(inc x)")) :tx-meta {:seon.tx/branch hb}})
            _ (d/transact conn {:tx-data (vec (mapcat #(program/account-tx @conn (#'pc/acct % "x") %) (range 2 (+ 2 n)))) :tx-meta {:seon.tx/branch :main}})
            r [base (pdb/head conn hb) (pdb/head conn b)]]
        (d/release hc)
        r))))

;; Estimate, from its parts: the branch's change read from its one commit (changed-datoms, d/changes: the ~10 facts one redefinition
;; writes); each fact held? on the branch and base (~20 seeks) and mapped to main's entity by its identity (identity-of over the entity
;; index's identity attributes, a seek each, for the ~3 entities); each of the ~3 keys read on base, main and the branch (held-at, 9
;; seeks); the branch's unmerged entries queried once and entry-eids again for the settling retraction: ~2 MB, the identity seeks most
;; of it. (It was 117 contracted calls on the deleted program-key decision.) Nothing of main's n forms is read: constant in n
(deftest ^{:seon.test/estimate {:ticks 9000000}} merge-main-costs-the-branchs-change-not-mains-moves
  (cost/proof
      {:of 'seon.merge/decide :input 'seon.merge-cost-test/moved-main-of
       :n "forms main defined since the branch's base"
       :work (str "the datoms the branch wrote since base (one diff of base and head), the keys they bear, and main's content at "
                  "those keys only: main's own moves are never read, so the same work however far main moved")
       :growth :constant
       :fixed {:bytes 420000}
       :sizes [3 6 12]}))


