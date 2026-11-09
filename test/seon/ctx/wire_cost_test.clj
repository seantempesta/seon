(ns seon.ctx.wire-cost-test
  "The conversation's cost proofs: a request costs its records (seon.ctx.wire/request), each a function of that record alone
  (docs/render.md#the-models-context-a-cached-prefix-then-the-turn), and what reads them. Run: bin/test seon.ctx.wire-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.ctx] [seon.ctx] [seon.doc] [seon.fixtures] [seon.render]
            [datahike.api :as d] [seon.test-store :as ts] [my.test :as t] [seon.db :as pdb] [seon.task :as task] [seon.vector :as vector]))

(defn- item
  "Eval record i of a run as the conversation reads it ([ref v k tx]): a model's call whose value
  is a production-shaped row set (a few maps of strings and numbers), each unlike the others."
  {:malli/schema [:=> [:cat nat-int?] :any]}
  [i]
  (let [id (str "e" i)]
    [[:eval id]
     {:seon.eval/id id :seon.eval/by "a1"
      :seon.eval/code (str "(rows " i ")") :seon.eval/call-id (str "c-" i) :seon.eval/intent (str "Read the rows of part " i ".") :seon.eval/response {:handle (symbol "result" id) :value (vec (for [j (range 6)] {:part i :row j :name (str "row-" i "-" j)}))
                                    :ms 0.5 :ticks 1000 :bytes 4096}}
     :seon.db/eval-fact (inc i)]))

(defn session-of
  "The input at n: a main db holding a session of n evals by agent b/a1 (as a
  woken or loaded session's first request reads it cold), each record production-shaped (item),
  and the agent's entity id."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [db (d/db-with (ts/test-db) (seon.doc/load-tx (ts/test-db) "docs" {seon.ctx.wire/system-page "# You are a Seon agent\n\nThe rules."}))
        db (d/db-with db
                      (into [{:seon.agent/key "b/a1" :seon.agent/turn n}]
                            (for [i (range n) :let [[_ v] (item i)]]
                              (assoc v :seon.eval/agent {:seon.agent/key "b/a1"}))))]
    [db (:db/id (d/entity db [:seon.agent/key "b/a1"]))]))

(defn- turn-tx
  "Turn t of session-of's agent as production writes it: its eval i's model call with the reply that holds it, and a message taken at every other turn, naming that call."
  {:malli/schema [:=> [:cat nat-int?] [:vector :map]]}
  [i]
  (let [t (inc i) call #(seon.task/call-ref "b/a1" t)]
    (cond-> [{:seon.eval/id (str "e" i) :seon.eval/call (assoc (call) :seon.model-call/origin :model :seon.model-call/reply (str "Reading the rows of part " i "."))}]
      (even? i) (conj {:seon.message/id (str "m" i) :seon.message/to [{:seon.agent/key "b/a1"}] :seon.message/call (call) :seon.message/from "owner" :seon.message/body (str "How is part " i "?")}))))

(defn records-of
  "The input at n of seon.ctx.wire/request: the agent's ctx value over session-of's db, as a request reads it, each turn production-shaped (`turn-tx`)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n] (let [[db e] (session-of n)
            db (reduce (fn [db i] (d/db-with db (turn-tx i))) db (range n))]
        [(:tree (seon.ctx/build db e :b)) db]))

(deftest a-request-reads-what-the-agents-ctx-holds
  ;; docs/render.md#the-render-is-an-eval; the owner, 2026-10-26: nothing in a request is read from
  ;; anywhere but its ctx: a record the ctx does not hold is not sent
  (let [[c db] (records-of 6)
        evals (fn [c] (filter #(= :eval (ffirst %)) (seon.fixtures/tree-parts c db)))]
    (clojure.test/is (= 6 (count (evals c))))
    (clojure.test/is (= 5 (count (evals (update c :seon.eval/_agent dissoc (first (keys (:seon.eval/_agent c))))))) "what the pattern does not reach is no record")))

(defn ring-of
  "seon.ctx.search/pushed's arguments at n forms in a ring, each calling the next: every node two
  neighbours, so the push's reach is bounded by its threshold, not by n. Seeded at the first."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:set :int] number?]]}
  [n]
  (let [db (d/db-with (t/world) (vec (mapcat (fn [i] [{:db/id (str "f" i) :form/id (str "demo/r" i) :form/src "(r)"
                                                          :form/refs #{(symbol "demo" (str "r" (mod (inc i) n)))}}
                                                         {:var/name (symbol "demo" (str "r" i)) :var/form (str "f" i)}])
                                                 (range n))))]
    [db {} #{(pdb/eid db :form/id "demo/r0")} 0.001]))

(deftest a-push-reads-what-its-threshold-reaches
  (cost/proof {:of 'seon.ctx.search/pushed :input 'seon.ctx.wire-cost-test/ring-of
               :n "forms in the reference graph"
               :work (str "a local push (Andersen, Chung and Lang 2006): each push keeps at least alpha eps of the "
                          "one unit of mass, so at most 1/(alpha eps) pushes, each node's neighbours read once: "
                          "flat in the store's size")
               :growth :constant
               ;; on the world, sizes 30 100 300 (2026-10-31): 4.45 MB and 44 calls at 30, 4.89 MB and 45 at 100
               ;; measured 2026-10-27: 46 calls and 5.23 MB at 50, 48 and 5.45 MB at 200: the ring's first pushes, each node's
               ;; degree counted once (bounded, `degree`) and its neighbours read once
               :fixed {:calls 46 :bytes 4900000}
               :sizes [30 100 300]}))

(defn hub-of
  "seon.ctx.search/pushed's arguments at n forms each naming hub var demo/h, seeded at a form that calls
  h (as a function calls seon.contract/checked): the push's mass reaches h's form, whose callers are
  read to :hub (seon.config.render/relevance-features), so it is never read whole."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:set :int] number?]]}
  [n]
  (let [db (d/db-with (ts/test-db) (into [{:db/id "h" :form/id "demo/h" :form/src "(defn h [])"} {:var/name 'demo/h :var/form "h"}
                                          {:db/id "s" :form/id "demo/s" :form/src "(defn s [] (h))" :form/refs #{'demo/h}} {:var/name 'demo/s :var/form "s"}]
                                         (for [i (range n)] {:form/id (str "demo/u" i) :form/src "(h)" :form/refs #{'demo/h}})))]
    [db {} #{(pdb/eid db :form/id "demo/s")} 0.001]))

(deftest a-push-counts-a-hub-and-reads-it-not
  (cost/proof {:of 'seon.ctx.search/pushed :input 'seon.ctx.wire-cost-test/hub-of
               :n "forms naming the hub its seed calls"
               :work (str "the seed pushed, the hub read to :hub (64 datoms a read) and found past it, so it "
                          "is not pushed and the rest of its callers are never read: flat in them")
               :growth :constant
               :fixed {:calls 10 :bytes 1000000}
               :sizes [200 500 1000]}))

(defn sections-of
  "A db of n wiki sections, each with a vector, and the task demo/r with its own: the sections a task's data is drawn over."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [unit (fn [seed] (let [v (mapv (fn [i] (Math/sin (* (inc seed) (inc i) 0.37))) (range 768)) m (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % m) v)))
        db (ts/test-db)
        db (d/db-with db (vec (for [i (range n)] {:seon.doc/address (str "docs/p.md#s" i) :seon.doc/heading (str "S" i)
                                                  :seon.doc/lead (str "Section " i " says it.")})))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan "Say it."}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range n)] (pdb/eid db :seon.doc/address (str "docs/p.md#s" i))))]
    [(d/db-with db (vector/tx-data es (repeat (count es) 1) (mapv unit (range (count es))) "m")) te]))

(defn task-data-of
  "seon.ctx.status/task-data's arguments at n wiki sections with vectors: the task demo/r as of the db and
  its cites, as my.task/data answers it at the REPL."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:vector :map]]]}
  [n] (let [[db _] (sections-of n)] [db (seon.task/task db "demo/r") (seon.task/task-cites db "demo/r")]))

(deftest task-data-costs-what-the-plan-names
  ;; the task's data (task-data over the task as of t), as my.task/data answers it at the REPL: what the plan
  ;; cites read and resolved, its write set among it, flat in the wiki's size
  (cost/proof {:of 'seon.ctx.status/task-data :input 'seon.ctx.wire-cost-test/task-data-of
               :n "wiki sections with vectors"
               :work "the task read with its accumulated plan, its references parsed and each resolved, its proofs' forms, the cited sections viewed, similar off: eighteen contracted calls, flat in the wiki"
               :growth :constant
               :fixed {:calls 18}
               :sizes [10 100 1000]}))

(defn request-of
  "The input at n: the live walk of the agent's tree after n earlier turns, each production-shaped (`turn-tx`), read once by a request, then one new turn written, ctx patched by its datoms and the walk flushed:
  so the proof's request prints that one new record against the n read before, as a turn's does."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [[db0 a] (session-of (inc n))
        db (reduce (fn [db i] (d/db-with db (turn-tx i))) db0 (range n))
        cell (volatile! db)
        c (seon.ctx/build db a :b)
        src (seon.ratom/atom (:tree c))
        w (assoc (seon.render/walker {:ref src :changes seon.ctx/changes} #(deref cell) [(seon.render/declared-var :seon.render/render-ctx) {}]) :model-only true)]
    (seon.render/flush! w nil)
    (seon.ctx.wire/request w)
    (let [r (d/with db (conj (turn-tx n) {:seon.agent/key "b/a1" :seon.agent/turn (inc n)}))]
      (vreset! cell (:db-after r))
      (reset! src (:tree (seon.ctx/patch c (:db-after r) (:tx-data r) :b)))
      (seon.render/flush! w (:tx-data r)))
    [w]))

(deftest a-requests-cost-is-its-records
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: a request is a reading of the tree, each component's messages kept on it by its
  ;; revision, so a turn reads what moved and walks the rest. Linear, not flat: the wire body holds n messages anyway
  (cost/proof {:of 'seon.ctx.wire/request :input 'seon.ctx.wire-cost-test/request-of
               :n "evals of the session before the turn"
               :work "the new turn's messages read, each earlier component's as they were kept, the tree walked once; the page at the end, its forms made once"
               :growth :linear
               :per-n {:calls 8 :bytes 15000}
               :fixed {:calls 900 :bytes 600000}
               :sizes [10 30 100]}))
