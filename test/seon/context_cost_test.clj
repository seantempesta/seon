(ns seon.context-cost-test
  "The conversation's cost proofs: a request costs its records (seon.context/request), each a function of that record alone
  (docs/render.md#the-models-context-a-cached-prefix-then-the-turn), and what reads them. Run: bin/test seon.context-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.context] [seon.ctx]
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
  (let [db (d/db-with (ts/test-db)
                      (vec (for [i (range n) :let [[_ v] (item i)]]
                             (assoc v :seon.eval/agent {:seon.agent/key "b/a1"}))))]
    [db (:db/id (d/entity db [:seon.agent/key "b/a1"]))]))

(defn records-of
  "The input at n of seon.context/records: the agent's ctx value over session-of's db, as a request reads it."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n] (let [[db e] (session-of n)
            ;; one eval a turn
            db (d/db-with db (vec (for [i (range n)] [:db/add [:seon.eval/id (str "e" i)] :seon.eval/turn (inc i)])))]
        [(:tree (seon.ctx/build db e :b)) db]))

(deftest a-request-reads-what-the-agents-ctx-holds
  ;; task seon.render/one-way-to-make-what-an-agent-is-sent, step 5; the owner, 2026-10-26: nothing in a request is read from
  ;; anywhere but its ctx. Later records read newest first come out as all of them do, and a record the ctx does not hold
  ;; is not sent
  (let [[c db] (records-of 6)
        all (seon.context/records c db)
        since 4]
    (clojure.test/is (= 6 (count all)))
    (clojure.test/is (= (filterv #(>= (ffirst %) since) all) (seon.context/records c db since)) "the new ones, in order")
    (clojure.test/is (= 5 (count (seon.context/records (update c :evals dissoc (first (keys (:evals c)))) db))) "what the pattern does not reach is no record")))

(deftest a-sessions-records-cost-one-read-per-record
  ;; design agent-loop section 1: the conversation is the session's, so the first request after
  ;; a wake or a load reads every record of it, cold; a later request reads only what is new
  ;; (a request prints them all: a-requests-cost-is-its-records)
  (cost/proof {:of 'seon.context/records :input 'seon.context-cost-test/records-of
               :n "eval records of the session"
               :work (str "per record: its member of ctx read and one place in the sort by its turn number; "
                          "no message or reply costs nothing")
               :growth :linear
               ;; measured 2026-10-25: 798 KB at 10, 5.46 MB at 100, 52.8 MB at 1,000 (about 52 KB a
               ;; record: its pull and its record's data value), one contracted call (records itself)
               :per-n {:bytes 52500}
               :fixed {:bytes 300000 :calls 1}
               :sizes [10 30 100]}))

(defn ring-of
  "seon.context/pushed's arguments at n forms in a ring, each calling the next: every node two
  neighbours, so the push's reach is bounded by its threshold, not by n. Seeded at the first."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:set :int] number?]]}
  [n]
  (let [db (d/db-with (t/world) (vec (mapcat (fn [i] [{:db/id (str "f" i) :form/id (str "demo/r" i) :form/src "(r)"
                                                          :form/refs #{(symbol "demo" (str "r" (mod (inc i) n)))}}
                                                         {:var/name (symbol "demo" (str "r" i)) :var/form (str "f" i)}])
                                                 (range n))))]
    [db {} #{(pdb/eid db :form/id "demo/r0")} 0.001]))

(deftest a-push-reads-what-its-threshold-reaches
  (cost/proof {:of 'seon.context/pushed :input 'seon.context-cost-test/ring-of
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
  "seon.context/pushed's arguments at n forms each naming hub var demo/h, seeded at a form that calls
  h (as a function calls seon.contract/checked): the push's mass reaches h's form, whose callers are
  read to :hub (seon.config.render/relevance-features), so it is never read whole."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:set :int] number?]]}
  [n]
  (let [db (d/db-with (ts/test-db) (into [{:db/id "h" :form/id "demo/h" :form/src "(defn h [])"} {:var/name 'demo/h :var/form "h"}
                                          {:db/id "s" :form/id "demo/s" :form/src "(defn s [] (h))" :form/refs #{'demo/h}} {:var/name 'demo/s :var/form "s"}]
                                         (for [i (range n)] {:form/id (str "demo/u" i) :form/src "(h)" :form/refs #{'demo/h}})))]
    [db {} #{(pdb/eid db :form/id "demo/s")} 0.001]))

(deftest a-push-counts-a-hub-and-reads-it-not
  (cost/proof {:of 'seon.context/pushed :input 'seon.context-cost-test/hub-of
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
    [(d/db-with db (vector/tx-data es (repeat (count es) 1) (mapv unit (range (count es))) (repeat (count es) 10) "m")) te]))

(defn task-data-of
  "seon.context/task-data's arguments at n wiki sections with vectors: the task demo/r as of the db and
  its cites, as my.task/data answers it at the REPL."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:vector :map]]]}
  [n] (let [[db _] (sections-of n)] [db (seon.task/task db "demo/r") (seon.task/task-cites db "demo/r")]))

(deftest task-data-costs-what-the-plan-names
  ;; the task's data (task-data over the task as of t), as my.task/data answers it at the REPL: what the plan
  ;; cites read and resolved, its write set among it, flat in the wiki's size
  (cost/proof {:of 'seon.context/task-data :input 'seon.context-cost-test/task-data-of
               :n "wiki sections with vectors"
               :work "the task read with its accumulated plan, its references parsed and each resolved, its proofs' forms, the cited sections viewed, similar off: eighteen contracted calls, flat in the wiki"
               :growth :constant
               :fixed {:calls 18}
               :sizes [10 100 1000]}))

(defn request-of
  "The input at n: seon.context/request's arguments for the model turn after n earlier evals, one a turn, each production-shaped
  (item), the page's parts the walk's (two) drawn as the call Seon made, since the session holds no eval of it, and :printed, all
  of them but the newest printed by a request before."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n]
  (let [[db a] (session-of n)
        db (d/db-with db (vec (for [i (range n)] [:db/add [:seon.eval/id (str "e" i)] :seon.eval/turn (inc i)])))
        part (fn [k text] [:section {:id k} text])
        opts {:db db :basis {:commit (d/commit-id db) :task "demo/f" :turn (inc n) :max-turns 9999 :origin :model :agent "b/a1"}
              :help "help" :help-ref [:section 1 1]
              :parts {"task-1" (part "task-1" "Make f.") "status-1" (part "status-1" "0 of 9")}
              :ctx (:tree (seon.ctx/build db a :b))}
        printed (:printed (seon.context/request {:db/id a} opts))]
    [{:db/id a} (assoc opts :printed (dissoc printed [:eval (str "e" (dec n))]))]))

(deftest a-requests-cost-is-its-records
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: a request is the system prompt, the examples and the agent's
  ;; records in turn order, each by its own render, a function of that record alone, so a turn prints its new record and reuses the rest
  ;; (:printed); without the cache a record cost 546 calls and 0.32 MB in every request, n squared over a session
  (cost/proof {:of 'seon.context/request :input 'seon.context-cost-test/request-of
               :n "evals of the session before the turn"
               :work "the one new record printed; each earlier record read from ctx and compared with the one it was printed from; the page at the end, drawn once"
               :growth :linear
               :per-n {:calls 8 :bytes 15000}
               :fixed {:calls 900 :bytes 600000}
               :sizes [10 30 100]}))
