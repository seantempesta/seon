(ns datahike.reads-test
  "Datahike's read recording, as Seon's render cache uses it (fork branch jolt/with-reads): what a read scanned, by
  entity and value, and whether a transaction touched it."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [datahike.datom :as dd] [seon.test-store :as ts] [seon.cost :as cost]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow]))

(defn- seeded
  "The test store's seed with two tasks, t1 needing t2, and t1's eid."
  []
  (let [db (d/db-with (ts/test-db) [{:db/id "t2" :seon.task/id "r/t2"}
                                    {:db/id "t1" :seon.task/id "r/t1" :seon.task/needs ["t2"]}])]
    [db (:db/id (d/entity db [:seon.task/id "r/t1"])) (:db/id (d/entity db [:seon.task/id "r/t2"]))]))

(deftest a-read-records-what-it-scanned
  (let [[db t1 t2] (seeded)]
    (testing "a pull with one nested ref: the entity, then the entity it refers to"
      (let [[v reads] (d/with-reads #(d/pull db [:seon.task/id {:seon.task/needs [:seon.task/id]}] t1))
            idx (d/reads-index reads)]
        (is (= {:seon.task/id "r/t1" :seon.task/needs [{:seon.task/id "r/t2"}]} v))
        (is (some #{[:e t1] [:ea t1 :seon.task/id]} idx) (pr-str idx))
        (is (some #{[:e t2] [:ea t2 :seon.task/id]} idx) (pr-str idx))
        (is (not (contains? idx :any)) "no open range: it read two entities, not an index")))
    (testing "a unique point query: one attribute and value"
      (let [[v reads] (d/with-reads #(d/q '[:find ?e . :in $ ?id :where [?e :seon.task/id ?id]] db "r/t2"))]
        (is (= t2 v))
        (is (= #{[:av :seon.task/id "r/t2"]} (d/reads-index reads)))))
    (testing "an attribute scan: the attribute"
      (let [[_ reads] (d/with-reads #(d/q '[:find ?id :where [?e :seon.task/id ?id]] db))]
        (is (= #{[:a :seon.task/id]} (d/reads-index reads)))))))

;; Estimate: a seeded test db and 3 pulls under the recorder, ~0.3M; 4 d/with of one datom each ~0.2M = ~500000
(deftest ^{:seon.test/estimate {:ticks 500000}} a-pull-records-the-attributes-it-read
  ;; task datahike.pull-api/a-pull-of-an-attribute-records-its-slice: a pull records [:ea e a] for each attribute of its
  ;; pattern and no wider key, so a reader of one slice of an entity is not reached by a commit to its other attributes
  ;; (a :plan-only track reran on a :seon.task/ns change, root's trial 2026-11-14). The entity's :eavt range is still read
  ;; once (cost unchanged). A missing entity records [:e e], so its first datom reaches the read.
  (let [[db t1 _] (seeded)
        reads #(d/reads-index (second (d/with-reads %)))
        tx (fn [tx-data] (:tx-data (d/with db tx-data)))
        two (reads #(d/pull db [:seon.task/id :seon.task/plan] t1))
        one (reads #(d/pull db [:seon.task/plan] t1))]
    (is (= #{[:ea t1 :seon.task/id] [:ea t1 :seon.task/plan]} two) (pr-str two))
    (is (= #{[:ea t1 :seon.task/plan]} one) (str "one attribute, and no [:e e] from the existence probe: " (pr-str one)))
    (is (d/reads-touched? two (tx [{:db/id t1 :seon.task/plan "Changed."}])) "an attribute it read")
    (is (not (d/reads-touched? two (tx [{:db/id t1 :seon.task/ns 'x.y}]))) "an attribute of the entity it did not read")
    (is (not (d/reads-touched? one (tx [{:db/id t1 :seon.task/ns 'x.z}]))) "nor another attribute, for a one-attribute pull")
    (is (contains? (reads #(d/pull db [:seon.task/plan] 987654321)) [:e 987654321]) "a missing entity: its first datom reaches it")
    ;; a read answers the same recorded or not (web-2, 2026-11-15: a recorded pull of an attribute the schema lacks threw)
    (is (= (d/pull db [:seon.task/plan :no.such/attribute] t1)
           (first (d/with-reads #(d/pull db [:seon.task/plan :no.such/attribute] t1))))
        "an attribute the schema does not define: the same answer inside the recorder as outside")))

;; Estimate: a seeded db, 200 pulls of one attribute under one recorder ~1M; one planned join query ~0.2M; 3 d/with ~0.2M = ~1500000
(deftest ^{:seon.test/estimate {:ticks 1500000}} a-recorder-holds-one-range-a-key-and-the-planner-records
  ;; task datahike.reads/a-lazy-read-records-into-the-read-it-belongs-to (2) and context's T9: the recorder kept one range a
  ;; read, so 200 reads of one attribute held 200 ranges for one key (565 B each); it holds each range once. And a query the
  ;; planner runs records what it read: with-reads no longer turns the planner off (on Jolt its cursors read through the
  ;; index protocol, which records), so a commit to what a planned join read reaches it.
  (let [[db t1 t2] (seeded)
        one (count (second (d/with-reads #(d/pull db [:seon.task/plan] t1))))
        [_ rs] (d/with-reads #(dotimes [_ 200] (d/pull db [:seon.task/plan] t1)))
        q '[:find ?id :in $ ?e :where [?e :seon.task/needs ?n] [?n :seon.task/id ?id]]
        ;; the planner on, whatever with-reads binds: what this asks is that the planner's reads are recorded
        [v qrs] (d/with-reads #(binding [datahike.query/*disable-planner* false] (d/q q db t1)))
        idx (d/reads-index qrs)
        tx (fn [tx-data] (:tx-data (d/with db tx-data)))]
    (is (= one (count rs)) (str "200 pulls of one attribute hold what one pull holds (" one "): " (count rs)))
    (is (= #{["r/t2"]} v))
    (is (d/reads-touched? idx (tx [{:db/id t1 :seon.task/needs [{:seon.task/id "r/t3"}]}])) "a need added to the entity it joined from")
    (is (d/reads-touched? idx (tx [[:db/retract t2 :seon.task/id "r/t2"]])) "the joined entity's id, retracted")
    (is (not (d/reads-touched? idx (tx [{:db/id t2 :seon.task/plan "Other."}]))) "an attribute the join did not read")))

;; Estimate: a seeded db and two pulls under nested recorders, ~0.2M = 200000
(deftest ^{:seon.test/estimate {:ticks 200000}} a-nested-with-reads-records-into-the-outer
  ;; task datahike.reads/with-reads-nests-in-an-outer-recorder: a read inside a nested with-reads reached only the inner
  ;; recorder, so a pass whose component recorded its own reads lost them (seon.ratom/deref-capture copied them out by
  ;; hand). Recording composes: the inner reads are the outer's too.
  (let [[db t1 t2] (seeded)
        [[_ inner] outer] (d/with-reads #(do (d/pull db [:seon.task/id] t2)
                                             (d/with-reads (fn [] (d/pull db [:seon.task/plan] t1)))))]
    (is (= #{[:ea t1 :seon.task/plan]} (d/reads-index inner)) (pr-str (d/reads-index inner)))
    (is (= #{[:ea t1 :seon.task/plan] [:ea t2 :seon.task/id]} (d/reads-index outer)) (pr-str (d/reads-index outer)))))

(deftest a-transaction-touches-only-what-a-read-scanned
  (let [[db t1 t2] (seeded)
        point (d/reads-index (second (d/with-reads #(d/q '[:find ?e . :in $ ?id :where [?e :seon.task/id ?id]] db "r/t2"))))
        pulled (d/reads-index (second (d/with-reads #(d/pull db [:seon.task/id :seon.task/plan] t1))))
        tx (fn [tx-data] (:tx-data (d/with db tx-data)))]
    (is (d/reads-touched? pulled (tx [{:db/id t1 :seon.task/plan "Changed."}])) "a datom of the pulled entity, added")
    (is (d/reads-touched? pulled (tx [[:db/retractEntity t1]])) "retracted")
    (is (not (d/reads-touched? pulled (tx [{:db/id t2 :seon.task/plan "Other."}]))) "another entity's datom")
    (is (not (d/reads-touched? point (tx [{:db/id t2 :seon.task/plan "x"}]))) "the found entity's other attribute: the answer is the same")
    (is (d/reads-touched? point (tx [[:db/retract t2 :seon.task/id "r/t2"]])) "the value read, retracted")
    (is (not (d/reads-touched? point (tx [{:seon.task/id "r/t3"}]))) "another value of the attribute")
    (is (d/reads-touched? point (tx [{:db/ident :r/new :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])) "a schema datom touches every read")))

(deftest a-read-inside-a-flow-step-is-recorded
  ;; root's render proc reads inside a :compute step's transform, which flow runs on a pool
  ;; thread; the recorder is a dynamic binding there, and a read the step makes from a go
  ;; block inside it is recorded too (binding is conveyed to go blocks; not to a raw Thread)
  (let [[db t1] (seeded)
        got (promise)
        step (fn ([] {:ins {:in "a read"} :outs {} :workload :compute})
               ([_] {}) ([s _] s)
               ([s _ _] (deliver got (d/with-reads #(do (d/pull db [:seon.task/id] t1)
                                                        (a/<!! (a/go (d/pull db [:seon.task/plan] t1))))))
                [s {}]))
        g (flow/create-flow {:procs {:p {:proc (flow/process step)}}})]
    (flow/start g)
    (flow/resume g)
    @(flow/inject g [:p :in] [:read])
    (let [[_ reads] @got]
      (flow/stop g)
      (is (contains? (d/reads-index reads) [:ea t1 :seon.task/id]) (pr-str reads))
      (is (contains? (d/reads-index reads) [:ea t1 :seon.task/plan]) "the go block's read too"))))

(defn- reads-of "n reads, each one entity's attribute ([:ea e a])." [n] (into #{} (map (fn [i] [:ea (+ 1000 i) :seon.task/id])) (range n)))
(defn- datoms-of "n datoms none of the reads holds." [n] (vec (for [i (range n)] (dd/datom (+ 5000000 i) :seon.task/plan (str i) 536870913 true))))
(defn by-datoms "The input of size n: 100 reads, a transaction of n datoms." [n] [(reads-of 100) (datoms-of n)])
(defn by-reads "The input of size n: n reads, a transaction of 100 datoms." [n] [(reads-of n) (datoms-of 100)])

(deftest a-transaction-costs-its-datoms-to-match
  (cost/proof
    {:of 'datahike.reads/reads-touched? :input 'datahike.reads-test/by-datoms
     :n "datoms in the transaction"
     :work "per datom, its five keys (a vector each) and one set lookup a key; nothing per read"
     :growth :linear
     ;; measured 2026-10-24: 98.6 KB / 983 KB / 9.83 MB at 100 / 1,000 / 10,000 datoms
     :per-n {:bytes 984}
     :fixed {:bytes 1000}
     :sizes [100 300 1000 10000]}))

(deftest matching-is-flat-in-what-was-read
  (cost/proof
    {:of 'datahike.reads/reads-touched? :input 'datahike.reads-test/by-reads
     :n "reads recorded (keys in the index)"
     :work "the 100 datoms' keys, each one set lookup, whatever the index holds"
     :growth :constant
     ;; measured 2026-10-24: 98.6 / 99.8 / 99.1 KB at 100 / 1,000 / 10,000 reads
     :fixed {:bytes 100000}
     :sizes [100 300 1000 10000]}))
