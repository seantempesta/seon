(ns seon.merge-safety-test
  "The one merge's promise, as a generated property and the few cases it cannot generate. See docs/decisions.md, 'A merge replays
  the source's transactions on the branch that receives it'."
  (:require [clojure.test :refer [deftest is]] [clojure.test.check :as tc] [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop] [datahike.api :as d] [datahike.writer :as dw] [seon.db :as pdb]
            [seon.merge :as pm] [seon.program :as program] [seon.test-store :as ts] [seon.proofs :as proofs] [my.schema :as schema]))

(defn- replay-up!
  "Branch br merged into main on conn as the writer's merge does it (seon.writer/octopus-merge): its plan, then each group a
  transaction through the checked write, the last with the branch as merge parent, one commit; nothing on a conflict or a
  refusal. Answers the plan, with :refused {:tx :error} when the write check refused a group."
  [conn br]
  (let [main @conn head (pdb/head conn br) p (pm/plan (pdb/branch-base conn (pdb/main-branch (:config main)) br) main head)
        a (when (:groups p) (pm/apply-groups! conn main (pdb/tx-meta (pdb/main-branch (:config main))) (:groups p)))
        fin (when (and a (not (:refused a))) (dw/apply! conn {:parents #{(d/commit-id head)} :tx-data [] :tx-meta (pdb/tx-meta (pdb/main-branch (:config main)))} {:op 'merge!}))]
    (if (and a (not (:refused a)) (map? fin))
      (do (dw/commit-pending! conn) p)
      (do (dw/abandon-pending! conn) (cond-> p (:refused a) (assoc :refused {:tx (:tx (:refused a)) :error (ex-data (:exception (:refused a)))}))))))

(defn- world
  "(f conn bc br ts vs) on branch br of a fresh main holding four tasks ts; vs, vars a ref may name."
  [f]
  (ts/test-branch!
   (fn [cfg conn]
     (d/transact conn (vec (for [i (range 4)] {:task/name (keyword "gen" (str "t" i)) :task/plan "p" :task/status :held})))
     (let [br (keyword (str (name (:branch cfg)) "-b")) _ (d/branch! conn (:branch cfg) br) bc (pdb/connect (assoc cfg :branch br))]
       (try (f conn bc br (mapv #(pdb/eid @conn :task/name (keyword "gen" (str "t" %))) (range 4)) (vec (take 3 (map :e (d/datoms @conn :aevt :var/form)))))
            (finally (d/release bc) (d/delete-branch! conn br)))))))

;; ---- the property: replaying a branch's transactions onto main gives main the branch's value at every [e a] the branch changed
;; and leaves every other [e a] as main had it; a write both sides changed to two ends is refused whole, and so is a transaction
;; main's write check refuses: nothing is written

(def ^:private writes
  "Small transactions over four tasks: a plan (cardinality one), a status, a ref added or retracted (cardinality many), a task retracted."
  (gen/vector (gen/tuple (gen/elements [:main :branch]) (gen/elements [:plan :status :ref+ :ref- :gone]) (gen/choose 0 3) (gen/choose 0 2) (gen/choose 0 3)) 1 8))

(defn- merges-as-promised? [ws]
  (world
   (fn [conn bc _ ts vs]
     (let [base @conn
           tx (fn [[_ op i j k]] (case op :plan [[:db/add (ts i) :task/plan (str "p" k)]] :status [[:db/add (ts i) :task/status ([:held :ready :done :held] k)]]
                                    :ref+ [[:db/add (ts i) :task/refs (vs j)]] :ref- [[:db/retract (ts i) :task/refs (vs j)]] :gone [[:db/retractEntity (ts i)]]))]
       (doseq [[side :as w] ws] (try (d/transact (if (= side :main) conn bc) {:tx-data (tx w)}) (catch Exception _ nil)))
       (let [main0 @conn nb (:keys (pm/net-change base @bc)) nm (:keys (pm/net-change base main0)) r (replay-up! conn (:branch (:config @bc)))
             na (:keys (pm/net-change base @conn))]
         (if (or (:conflicts r) (:refused r))
           (= (d/commit-id main0) (d/commit-id @conn))
           (and (every? (fn [[k v]] (= v (get na k))) nb) (every? (fn [[k v]] (= v (get na k))) (apply dissoc nm (keys nb)))
                (every? #(or (contains? nb %) (contains? nm %)) (keys na)))))))))

(deftest any-branch-merges-as-promised
  (let [r (tc/quick-check 40 (prop/for-all [ws writes] (merges-as-promised? ws)) :seed (proofs/test-seed))]
    (is (:pass? r) (pr-str (select-keys r [:seed :num-tests]) (get-in r [:shrunk :smallest])))))

;; ---- what the property cannot generate, and its red seeds kept --------------------------------

;; the property's seed 8 (40 cases), shrunk: the branch added a ref and then retracted the task, main added the same ref. Net, the
;; branch never changed the ref, but its retraction, replayed whole, would take main's: a key the replay writes is the branch's
;; change (seon.merge/plan, written), so it conflicts with main's and nothing is written
(deftest a-key-the-replay-writes-back-is-a-change
  (world (fn [conn bc br ts vs]
           (d/transact bc [[:db/add (ts 1) :task/refs (vs 1)]]) (d/transact bc [[:db/retractEntity (ts 1)]])
           (d/transact conn [[:db/add (ts 1) :task/refs (vs 1)]])
           (let [t0 (:max-tx @conn)] (is (seq (:conflicts (replay-up! conn br)))) (is (= t0 (:max-tx @conn)))))))

;; docs/agent.md#merging: two entities one identity names, each side's own counter id: no key both changed, and Datahike's unique
;; constraint refuses the branch's replayed transaction (:transact/unique); nothing is written
(deftest one-identity-on-two-entities-is-refused
  (world (fn [conn bc br _ _]
           (doseq [c [conn bc]] (d/transact c [{:task/name :gen/same :task/plan "p" :task/status :held}]))
           (let [t0 (:max-tx @conn) r (replay-up! conn br)]
             (is (= :transact/unique (get-in r [:refused :error :error])) (pr-str r))
             (is (= t0 (:max-tx @conn)))))))

;; docs/source.md#schemas-at-the-repl: a branch registers an entity of its own and writes one: main holds the attribute and the value
(deftest a-branchs-new-attribute-and-its-value-merge-up
  (world (fn [conn bc br _ _]
           (doseq [[k v] [[:app.mg/id [:and {:seon.db/identity true} :string]] [:app.mg/size :int]
                          [:app.mg/thing [:map {:seon.db/attributes true} [:app.mg/id :app.mg/id] [:app.mg/size :app.mg/size]]]]]
             (d/transact bc {:tx-data (:tx (schema/registration @bc k v false)) :tx-meta (pdb/tx-meta br)}))
           (let [k (program/schema-changes @bc)]
             (d/transact bc {:tx-data [{:app.mg/id "one" :app.mg/size 3}] :tx-meta (pdb/tx-meta br) :seon.db/schemas {:forms k :registry (program/schema-registry k)}}))
           (is (nil? (:refused (replay-up! conn br))))
           (is (= 3 (:app.mg/size (d/entity @conn [:app.mg/id "one"])))))))

;; a merge down after a merge up: main's copies of the branch's own transactions are no change of either side and are not replayed
;; back (they name the branch), so the branch keeps a value it changed since
(deftest a-branchs-own-transactions-come-back-as-nothing
  (world (fn [conn bc br ts _]
           (d/transact bc {:tx-data [[:db/add (ts 0) :task/plan "one"]] :tx-meta (pdb/tx-meta br)})
           (replay-up! conn br)
           (d/transact bc {:tx-data [[:db/add (ts 0) :task/plan "two"]] :tx-meta (pdb/tx-meta br)})
           (let [base (pdb/branch-base conn (pdb/main-branch (:config @conn)) br) p (pm/plan base @bc @conn)]
             (is (= {} (:keys (pm/net-change base @conn))) "main's copy is no change")
             (is (= {:groups []} p) "and nothing comes back")))))

;; docs/storage.md#history: main's instants rise with its t after a merge though the branch's clock ran ahead
(deftest mains-instants-rise-with-its-t-after-a-merge
  (world (fn [conn bc br ts _]
           (with-redefs [datahike.tools/get-date (constantly (java.util.Date. (+ (System/currentTimeMillis) 31536000000)))]
             (d/transact bc {:tx-data [[:db/add (ts 1) :task/plan "ahead"]] :tx-meta (pdb/tx-meta br)}))
           (d/transact conn [[:db/add (ts 2) :task/plan "now"]])
           (replay-up! conn br)
           (let [ins (map #(.getTime ^java.util.Date (:v %)) (sort-by :e (d/datoms @conn :aevt :db/txInstant)))]
             (is (apply <= ins))))))

;; datahike.datom/value=: the same bytes written on both sides are no conflict
(deftest byte-values-compare-by-content
  (is (= [] (pm/conflicts {:keys {[1 :seon.vector/f16] (byte-array [1 2])}} {:keys {[1 :seon.vector/f16] (byte-array [1 2])}}))))
