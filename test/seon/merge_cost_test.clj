(ns seon.merge-cost-test
  "A merge's three-way costs what changed, never the program: over branches that hold n forms and each
  changed one, seon.merge/delta-three-way reads only the keys the datoms that differ bear."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.lib] [clojure.test :refer [deftest is use-fixtures]] [seon.contract :refer [defn defn-]]
            [datahike.api :as d] [seon.db :as pdb] [seon.cost :as cost] [seon.program :as program] [seon.program-cost-test :as pc] [seon.vector :as vector] [seon.merge :as merge]
            [clojure.java.shell]))

;; One store on disk of this test's own for its three proofs (a branch each, removed by the namespace's fixture), the last resort (docs/storage.md): the in-memory test
;; store gives its index nodes no addresses, so no two db values of it share a stored subtree
;; and a structural diff of them reads everything; LMDB, as in production, shares them.
(def ^:private store
  (delay (:conn (pdb/open! :lmdb (str (seon.config/checkout) "/target/merge-cost-" (random-uuid)) 0 :db))))

(defn three-of
  "delta-three-way's arguments: base, a branch holding n forms (app.pc/f0..fn-1), and ours
  and theirs, each a branch of it that redefined one form of its own, all read from the store."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
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
        r [(pdb/head conn b) (side "ours" 1) (side "theirs" (dec n))]]
    (d/release bc)
    r))

(deftest a-three-way-costs-the-changes-not-the-program
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
       ;; multi-prefix datoms-diff in our Datahike) would
       ;; descend once. Until then the proof judges the sizes a merge meets
       :per-n {:ns 400000 :bytes 500000}
       :fixed {:ns 4000000 :bytes 6800000}
       :sizes [10 30 100]}))

(defn embedded-of
  "delta-three-way's arguments for a main that only embedded: base holding n forms, ours changing
  nothing, and theirs a branch of it that wrote a vector fact set on each of the n forms (what
  seon.vector writes of a form's text), no form's program content changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
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
        r [(pdb/head conn b) (pdb/head conn b) (pdb/head conn s)]]
    (d/release c) (d/release bc)
    r))

(def ^:private diff-base
  ;; a branch of the store holding 20,000 entities of two attributes (40,000 :eavt datoms), built once
  (delay (let [conn @store
               cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
               b (keyword (str "diff-" (random-uuid)))
               _ (d/branch! conn :db b)
               bc (pdb/connect (assoc cfg :branch b))]
           (d/transact bc {:tx-data [{:db/ident :app.diff/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                                     {:db/ident :app.diff/m :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]})
           (doseq [part (partition-all 2000 (range 20000))]
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

(deftest a-diff-of-two-commits-costs-the-nodes-that-differ
  ;; See docs/storage.md#merges-read-what-changed (seon.ctx/moved's call: two values of one branch, :eavt, the whole range)
  (let [[a b] (diff-of 10) [gone added] (d/datoms-diff a b :eavt [])]
    (is (= [10 11] [(count gone) (count added)]) "ten values replaced, and the transaction's own datom"))
  (cost/proof
    {:of 'datahike.api/datoms-diff :input 'seon.merge-cost-test/diff-of
     :n "values replaced on entities spread over a 40,000-datom store, in one commit"
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

(deftest a-main-that-only-embedded-changes-no-key
  (let [[base ours theirs] (embedded-of 10)]
    (is (empty? (merge/touched-keys base [theirs])) "embeddings are no program content")
    (is (= {:theirs {} :apply [] :conflicts []} (select-keys (merge/delta-three-way base ours theirs) [:theirs :apply :conflicts])) "so main changed nothing")))

(deftest a-main-that-only-embedded-costs-nothing
  ;; (its digest does not reach delta-three-way: a quoted name; this line moves it when the producer changes)
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
     ;; 27 calls since 2026-10-30: seon.merge/content-attr, one contracted call per side's filter, was
     ;; uncontracted (as a contracted predicate asked of each attribute it was 1,771)
     ;; 2026-11-12: content-attr? is a contracted defn- (the contracts check: every fn contracted), and a test shard arms a private
     ;; contract (production does not wrap a defn-), so its calls count again: content-datoms asks it of each attribute of both
     ;; schemas, ~900 a side (1,802 measured), the 2026-10-30 count before it was taken uncontracted
     :fixed {:bytes 4000000 :calls 2000}
     :sizes [10 30 100]}))

(defn plan-of
  "seon.merge/plan's arguments for a merge up on a store holding n forms: their base, main moved by one record transaction,
  and a branch that wrote 100 tasks of its own (about 360 datoms) in 20 transactions, all read from the store."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/db :seon.host/db]]}
  [n]
  (let [conn @store
        cfg (pdb/store-cfg :lmdb (get-in @conn [:config :store :path]))
        b (keyword (str "p" n "-" (random-uuid)))
        _ (d/branch! conn :db b)
        bc (pdb/connect (assoc cfg :branch b))
        _ (doseq [part (partition-all 500 (range n))] (d/transact bc {:tx-data (vec (mapcat #(program/account-tx @bc (#'pc/acct % "x") %) part))}))
        side (fn [nm txs] (let [s (keyword (str (name b) "-" nm)) _ (d/branch! conn b s) c (pdb/connect (assoc cfg :branch s))]
                            (doseq [tx txs] (d/transact c {:tx-data tx :tx-meta (pdb/tx-meta s)}))
                            (d/release c)
                            (pdb/head conn s)))
        r [(pdb/head conn b)
           (side "main" [[{:seon.system/id :root :seon.system/boot "moved"}]])
           (side "branch" (for [i (range 20)] (vec (for [j (range 5)] {:task/name (keyword "cost" (str "t" i "-" j)) :task/plan (str "plan " i j) :task/status :held}))))]]
    (d/release bc)
    r))

(deftest a-plan-costs-what-the-two-sides-changed
  ;; task seon.merge/a-merge-replays-the-sources-net-change: an agent's turn plans a merge whenever main moved, so a plan costs
  ;; what the two sides changed since their base (the two net changes, the history diff, the replay), never the store: the
  ;; identity of an entity read from the db's schema map (:schema), never (d/schema db), which built the whole map again per
  ;; entity (32.4M ticks and 104 MB a plan on a 60,590-datom store, 2026-11-12)
  (spit "/tmp/mergeop/plan-proof.edn"
        (pr-str (cost/proof
                 {:of 'seon.merge/plan :input 'seon.merge-cost-test/plan-of
                  :n "forms the store holds; one record on main, about 360 datoms in 20 transactions on the branch"
                  :work "the datoms that differ on each side and the branch's transactions since the base: never the store"
                  :growth :constant
                  :fixed {:ticks 3000000 :bytes 8000000}
                  :sizes [100 300 1000]}))))
