(ns seon.merge-cost-test
  "A merge's three-way costs what changed, never the program (task
  seon.merge/program-cost-follows-what-changed): over branches that hold n forms and each
  changed one, seon.merge/delta-three-way reads only the keys the datoms that differ bear."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc]
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
       ;; measured 2026-10-23 (load ~60): 6.2, 7.1 and 8.4 ms, 6.5, 7.6 and 7.8 MB at 100, 1k and 10k
       :per-n {:ns 400000 :bytes 300000}
       :fixed {:ns 4000000 :bytes 5000000}
       :sizes [100 1000 10000]})
    (finally (when (realized? store)
               (let [path (get-in @@store [:config :store :path])]
                 (d/release @store) (clojure.java.shell/sh "rm" "-rf" path))))))
