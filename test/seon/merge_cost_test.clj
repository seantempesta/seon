(ns seon.merge-cost-test
  "A merge's three-way costs what changed, never the program (task
  seon.merge/program-cost-follows-what-changed): over branches that hold n forms and each
  changed one, seon.merge/delta-three-way reads only the keys the datoms that differ bear."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is use-fixtures]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc] [seon.vector :as vector] [seon.merge :as merge]
            [clojure.java.shell]))

;; A store on disk of this test's own, the last resort (docs/storage.md): the in-memory test
;; store gives its index nodes no addresses, so no two db values of it share a stored subtree
;; and a structural diff of them reads everything; LMDB, as in production, shares them.
(def ^:private store
  (delay (:conn (pdb/open! :lmdb (str (System/getProperty "user.dir") "/target/merge-cost-" (random-uuid)) 0 :db))))

(defn three-of
  "delta-three-way's arguments: base, a branch holding n forms (app.pc/f0..fn-1), and ours
  and theirs, each a branch of it that redefined one form of its own, all read from the store."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db [:set :keyword]]]}
  [n]
  (let [conn @store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "n" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (d/connect (assoc cfg :branch b))
        _ (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) (range n)))})
        side (fn [nm i] (let [s (keyword (str (name b) "-" nm))
                              _ (d/branch! conn b s)
                              c (d/connect (assoc cfg :branch s))]
                          (d/transact c {:tx-data (program/record-tx @c (#'pc/acct i "(inc x)"))})
                          (d/release c)
                          (pdb/head conn s)))
        r [(pdb/head conn b) (side "ours" 1) (side "theirs" (dec n)) #{}]]
    (d/release bc)
    r))

(deftest a-three-way-costs-the-changes-not-the-program
  (try
    (cost/proof
      {:of 'seon.merge/delta-three-way :input 'seon.merge-cost-test/three-of
       :n "forms the branches hold"
       :work (str "the datoms that differ between base and each side (datoms-diff skips every subtree they "
                  "share: the paths of the leaves each side's commit rewrote, log n deep), the program keys "
                  "their entities bear, and each key's content read on base, ours and theirs by index seeks "
                  "(log n each): a fixed number of keys, whatever n")
       :growth :log
       ;; measured 2026-10-23 (load ~30), entity keys read by one seek each: 8.0, 9.5 and
       ;; 8.1 ms, 3.9, 5.1 and 5.4 MB at 100, 1k and 10k. Since touched-keys walks one :aevt range per
       ;; program attribute and side (task seon.merge/touched-keys-skips-platform-records): 10.2, 9.7, 10.8
       ;; and 14.1 MB at 100, 300, 1k and 10k, 156 calls, the descents of the content attributes
       ;; (about 60 KB each) being most of it
       ;; past 1,000 forms each of the ~120 descents (59 attributes, two sides) gains a tree level: 12.2 and
       ;; 14.1 MB at 3k and 10k, a step the fit on four increments reads as n^0.83 against log's n^0.60. That
       ;; is the cost of one :aevt range an attribute and side; one walk over all their ranges (a
       ;; multi-prefix datoms-diff in our Datahike, task seon.reconcile/one-observe-descends-once) would
       ;; descend once. Until then the proof judges the sizes a merge meets
       :per-n {:ns 400000 :bytes 500000}
       :fixed {:ns 4000000 :bytes 6800000}
       :sizes [100 300 1000]})
    (finally (when (realized? store)
               (let [path (get-in @@store [:config :store :path])]
                 (d/release @store) (clojure.java.shell/sh "rm" "-rf" path))))))

;; data-of's store, a second of this test's own: the three-way proof releases and removes its
;; store at its end
(def ^:private data-store
  (delay (:conn (pdb/open! :lmdb (str (System/getProperty "user.dir") "/target/merge-data-cost-" (random-uuid)) 0 :db))))

(defn data-of
  "delta-three-way's arguments for a merge of declared data (:all): base holding n data
  attributes (:app.data/a0..an-1, longs) and one entity with a value of the first, and ours and
  theirs, each a branch of it that changed that one value, all read from the store."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db [:= :all]]]}
  [n]
  (let [conn @data-store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "d" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (d/connect (assoc cfg :branch b))
        attrs (mapv #(keyword "app.data" (str "a" %)) (range n))
        _ (d/transact bc {:tx-data (mapv #(hash-map :db/ident % :db/valueType :db.type/long :db/cardinality :db.cardinality/one) attrs)})
        _ (d/transact bc {:tx-data [{:app.data/a0 1}]})
        e (d/q '[:find ?e . :where [?e :app.data/a0 1]] @bc)
        side (fn [nm v] (let [s (keyword (str (name b) "-" nm))
                              _ (d/branch! conn b s)
                              c (d/connect (assoc cfg :branch s))]
                          (d/transact c {:tx-data [[:db/add e :app.data/a0 v]]})
                          (d/release c)
                          (pdb/head conn s)))
        r [(pdb/head conn b) (side "ours" 2) (side "theirs" 3) :all]]
    (d/release bc)
    r))

(deftest a-merge-reads-the-data-attributes-from-the-index
  ;; task seon.merge/data-attrs-read-from-the-attribute-index: a merge of declared data (:all)
  ;; read which attributes are data from (d/schema db), which rebuilds the whole schema map on
  ;; every call, once per db per read; agents merge main at every green. Now whether an attribute
  ;; is data is asked of the attributes of the datoms that differ (seon.merge/data-attr?), so the
  ;; merge costs the same however many attributes the store declares
  (try
    (cost/proof
      {:of 'seon.merge/delta-three-way :input 'seon.merge-cost-test/data-of
       :n "data attributes the store declares, one value changed on each side"
       :work (str "the datoms that differ (one value each side), the keys their entity bears, each "
                  "asked whether its attribute is data: never the schema, so never the attributes' count")
       :growth :constant
       ;; measured, 5 repeats a size: 562 KB / 39 calls at 100, 592 KB at 300, 585 KB at 1,000
       ;; (43 calls since touched-keys asks each of the 4 differing datoms' attributes whether a key
       ;; reads it, one call apiece: measured with the filter inline, in a def and as a contracted
       ;; function, 43, 43 and 47); reading (d/schema db) per db per read it was 8.11 / 11.84 / 25.57 MB and 2,726 / 3,926 /
       ;; 8,126 calls
       :fixed {:bytes 650000 :calls 43}
       :sizes [100 300 1000]})
    (finally (when (realized? data-store)
               (let [path (get-in @@data-store [:config :store :path])]
                 (d/release @data-store) (clojure.java.shell/sh "rm" "-rf" path))))))

;; embedded-of's store, a third of this test's own, for the same reason as data-store: the other proofs
;; release and remove theirs at their end, and tests of one shard share the process
(def ^:private embedded-store
  (delay (:conn (pdb/open! :lmdb (str (System/getProperty "user.dir") "/target/merge-embedded-cost-" (random-uuid)) 0 :db))))

(defn embedded-of
  "delta-three-way's arguments for a main that only embedded: base holding n forms, ours changing
  nothing, and theirs a branch of it that wrote a vector fact set on each of the n forms (what
  seon.vector writes of a form's text), no form's program content changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db [:set :keyword]]]}
  [n]
  (let [conn @embedded-store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "e" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (d/connect (assoc cfg :branch b))
        _ (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) (range n)))})
        es (vec (d/q '[:find [?e ...] :where [?e :form/id _]] @bc))
        s (keyword (str (name b) "-theirs"))
        _ (d/branch! conn b s)
        c (d/connect (assoc cfg :branch s))
        _ (d/transact c {:tx-data (vector/tx-data es (vec (repeat n 1)) (vec (repeat n [0.5 0.5])) (vec (repeat n 1)) "m")})
        r [(pdb/head conn b) (pdb/head conn b) (pdb/head conn s) #{}]]
    (d/release c) (d/release bc)
    r))

(use-fixtures :once
  (fn [run]
    (try (run)
         (finally (when (realized? embedded-store)
                    (let [path (get-in @@embedded-store [:config :store :path])]
                      (d/release @embedded-store) (clojure.java.shell/sh "rm" "-rf" path)))))))

(deftest a-main-that-only-embedded-changes-no-key
  (let [[base ours theirs das] (embedded-of 10)]
    (is (empty? (merge/touched-keys base [theirs] das)) "embeddings are no program content")
    (is (= {:theirs {} :apply [] :conflicts []} (select-keys (merge/delta-three-way base ours theirs das) [:theirs :apply :conflicts])) "so main changed nothing")))

(deftest a-main-that-only-embedded-costs-nothing
  ;; task seon.merge/touched-keys-are-what-the-program-changed: an entity whose only differing
  ;; datoms are a platform record's (seon.vector's embeddings) bears no changed key, so the
  ;; merge reads none of its n forms (calls flat; bytes are the diff of the datoms that differ)
  (cost/proof
    {:of 'seon.merge/delta-three-way :input 'seon.merge-cost-test/embedded-of
     :n "forms main embedded, none changed"
     :work (str "one :aevt descent per program attribute and side (the embedding attributes are not walked), "
                "none differing: no key is read, whatever the number of forms main embedded")
     :growth :constant
     ;; measured: 16 calls and 3.55 MB at 10 (the descents), the same at 100 and 1,000. Walking the one
     ;; :eavt diff instead read every embedded datom (the five seon.vector attributes of each form):
     ;; 0.67 MB at 10, 6.3 MB at 100, 63 MB at 1,000, and on a root of 4,363 embedded forms 313M ticks
     ;; and 640 MB for no key. Before that again: 536 calls, 11 MB at n = 10
     :fixed {:bytes 4000000 :calls 20}
     :sizes [10 100 1000]}))
