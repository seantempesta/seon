(ns seon.context-cost-test
  "The conversation's cost proofs: a turn prints only its new records (seon.context/printed-run),
  flat in the run's history (root's ruling 2026-10-25: a record's printed message is a function
  of it and the records before it, which never change, so it is printed once and reused by its
  id). Run: bin/test seon.context-cost-test"
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
      :seon.eval/record {:code (str "(rows " i ")") :call-id (str "c-" i) :call 0 :prose (str "Read the rows of part " i ".")
                         :response {:handle (symbol "result" id) :value (vec (for [j (range 6)] {:part i :row j :name (str "row-" i "-" j)}))
                                    :ms 0.5 :ticks 1000 :bytes 4096}
                         :line (str "[{:part " i " …}]")}}
     :seon.db/eval-fact (inc i)]))

(defn held-of
  "The input at n: the run's next record, and the cache of its n records before it, printed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :any :map]]}
  [n] [[(item n)] (#'seon.context/printed-run (mapv item (range n)) nil)])

(deftest a-turn-prints-its-new-records-flat-in-the-history
  (cost/proof {:of 'seon.context/printed-run :input 'seon.context-cost-test/held-of
               :n "records of the run printed before (the cache)"
               :work (str "the new record printed whole, once, by the one printer, a function of "
                          "that record alone; nothing of the earlier records is printed again")
               :growth :constant
               ;; measured 2026-10-25: 16 calls and 329 KB at 10 held records (the new record's two prints)
               ;; 2026-11-04, the owner's yes ("Yes, raise by 4"), old 560: a record's text goes through the one lookup, record-messages,
               ;; seon.render/model, model-declared, contract/properties (seon.render/one-way-to-make-what-an-agent-is-sent, step 4). Re-pinned
               ;; to what it measures at step 6, which deletes part-change and parts-message from this path
               :fixed {:calls 564 :bytes 340000}
               :sizes [10 100 1000]}))

(defn session-of
  "The input at n: a main db holding a session of n evals by agent b/a1 across two runs (as a
  woken or loaded session's first request reads it cold), each record production-shaped (item),
  and the agent's entity id."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [db (d/db-with (ts/test-db)
                      (vec (for [i (range n) :let [[_ v] (item i)]]
                             (assoc v :seon.eval/agent {:seon.agent/key "b/a1"}
                                    :seon.eval/record (assoc (:seon.eval/record v) :run (if (< i (quot n 2)) "demo/f#1" "demo/f#2"))))))]
    [db (:db/id (d/entity db [:seon.agent/key "b/a1"]))]))

(deftest a-sessions-records-cost-one-read-per-record
  ;; design agent-loop section 1: the conversation is the session's, so the first request after
  ;; a wake or a load reads every record of it, cold; a later request reads only what is new
  ;; (:printed, a-turn-prints-its-new-records-flat-in-the-history)
  (cost/proof {:of 'seon.context/records :input 'seon.context-cost-test/session-of
               :n "eval records of the session"
               :work (str "per record: its datom found by the agent's AVET range (one seek), one pull of three attributes "
                          "(its record a data value), and one place in the sort by tx; the messages' query adds nothing "
                          "when none came")
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

(defn namespaces-at
  "seon.context/namespaces-of's argument at n definitions: the record of an agent whose task cites one var of a
  namespace that defines n, its proof calling two of them."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map]]}
  [n]
  (let [db (d/db-with (ts/test-db)
                      (into [{:db/id "n" :ns/name 'app.w} {:db/id "tf" :form/id "app.w-test/t" :form/src "(deftest t (is (f0)))" :form/refs #{'app.w/f0 'app.w/f1}}
                             {:var/name 'app.w-test/t :var/kind :test :var/form "tf"}]
                            (for [i (range n)] {:var/name (symbol "app.w" (str "f" i)) :var/ns "n" :var/kind :fn})))
        db (d/db-with db (seon.task/create-tx db [{:seon.task/ns 'app :seon.task/name "w" :seon.task/proofs #{'app.w-test/t} :seon.task/plan "Make `app.w/f0` right."}]))
        db (d/db-with db [{:seon.agent/key "b/w1" :seon.agent/task [:seon.task/id "app/w"]}])]
    [(:tree (seon.ctx/build db (pdb/eid db :seon.agent/key "b/w1") :b))]))

(deftest the-namespaces-part-costs-what-it-shows
  ;; the namespaces part (docs/render.md#a-part-answers-data) is what the record holds of the task's names: the
  ;; namespace's size never reaches it (a bench run's part listed ~170 names the task never needed, 1,256 tokens)
  (cost/proof {:of 'seon.context/namespaces-of :input 'seon.context-cost-test/namespaces-at
               :n "definitions in the cited namespace"
               :work "the task's cites, its needs' and its proofs' vars as the record holds them, each name grouped by its namespace: what it shows, flat in the namespace's definitions"
               :growth :constant
               ;; measured: 7 contracted calls, 26.9 KB at 10 definitions; namespace-data, which it replaces, read every
               ;; definition of each namespace (34 KB a definition: 3.49 MB at 100)
               ;; 8: 7 + the current-ns call. Accepted by the owner, 2026-11-03 ("Record 616 and 8")
               :fixed {:calls 8 :bytes 30000}
               :sizes [10 100 1000]}))

(deftest task-data-costs-what-the-plan-names
  ;; the task's data (task-data over the task as of t), as my.task/data answers it at the REPL: what the plan
  ;; cites read and resolved, its write set among it, flat in the wiki's size
  (cost/proof {:of 'seon.context/task-data :input 'seon.context-cost-test/task-data-of
               :n "wiki sections with vectors"
               :work "the task read with its accumulated plan, its references parsed and each resolved, its proofs' forms, the cited sections viewed, similar off: eighteen contracted calls, flat in the wiki"
               :growth :constant
               :fixed {:calls 18}
               :sizes [10 100 1000]}))

(def ^:dynamic *conn*
  "The branch's connection the parts-of proof reads and writes (bound by its deftest)."
  nil)

(defn turns-of
  "The input at n: the db of a branch where agent b/p<n> took n turns on a task (its plan changing
  each turn, one commit a turn: *conn*), and the refs a request sent, [:parts a c whole? ids call], each
  turn's parts as seon.context/request records them, drawn at that turn's commit, every one after the first
  sent as what changed since the turn before ([... was-commit]: its commit is the turn before's, drawn once).
  Every turn sends the same parts (a plan and a run), so the cost is per ref."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db [:vector :any]]]}
  [n]
  (let [conn *conn* k (str "b/p" n) tid (str "demo/p" n)]
    (d/transact conn (task/create-tx @conn [{:seon.task/ns 'demo :seon.task/name (str "p" n) :seon.task/plan "Make p."
                                             :seon.task/proofs #{'demo-test/p-test}}]))
    (d/transact conn [{:seon.run/id (str tid "#1") :seon.run/task [:seon.task/id tid] :seon.run/agent {:seon.agent/key k}}])
    (let [commits (vec (for [i (range n)]
                         (do (d/transact conn [{:seon.task/id tid :seon.task/plan (str "Make p, step " i ".")}])
                             (d/commit-id @conn))))
          a (:db/id (d/entity @conn [:seon.agent/key k]))]
      (let [ids (vec (keys (seon.context/drawn-parts @conn (seon.ctx/build @conn a (:seon.agent/branch (d/entity @conn a))))))]
        [@conn (vec (for [[i c] (map-indexed vector commits)]
                      (cond-> [:parts a c (zero? i) ids (str tid "#1/" i)] (pos? i) (conj (commits (dec i))))))]))))

;; See docs/render.md#the-branchs-views
(deftest reading-a-request-back-draws-each-turns-parts-once
  (binding [*conn* nil]
    (ts/test-branch!
      (fn [_ conn]
        (binding [*conn* conn]
          (cost/proof {:of 'seon.context/parts-of :input 'seon.context-cost-test/turns-of
                       :n "parts refs of the request (one turn's parts each, each drawn at its own commit)"
                       :work "one page drawn a ref (the walk of the agent's record at that commit), every commit once; 488 calls a ref since the layout places the cites from ctx (seon.render.ui/page-cites, its stubs filled from where ctx holds them: the cites' joins are the pull's, where the draw used to query them), 480 before; 516 since the task part and the status read the pulled task (seon.render.ui/task-model, seon.context/task-of: the accumulated plan over the joined needs), 1.36 MB -> 1.23 MB a ref"
                       :growth :linear
                       ;; measured 2026-10-28 (a plan and a run a page): 2.68 MB / 433 calls at 1, 9.51 MB / 881 at 2,
                       ;; 23.2 MB / 1,777 at 4, 50.7 MB / 3,569 at 8; before the pages map (every page drawn twice):
                       ;; 11.0 MB / 1,195 at 2, 27.7 MB / 2,719 at 4, 61.2 MB / 5,767 at 8 (762 calls a page, not 448)
                       :per-n {:bytes 6800000 :calls 516}
                       :fixed {:bytes 500000 :calls 50}
                       :sizes [1 2 4 8]}))))))


(defn request-of
  "The input at n: seon.context/request's arguments for the turn after n earlier evals, each printed by the
  turn that follows it (the run's print kept, :printed), the page's parts the walk's (two, one moved since the
  request before), the previous request's refs recorded on its model call."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :map]]}
  [n]
  (let [[db a] (session-of n)
        basis (fn [t] {:commit (d/commit-id db) :task "demo/f" :run "demo/f#1" :turn t :max-turns 9 :origin :model :agent "b/a1"})
        turn (fn [t] [{:db/id (str "t" t) :seon.turn/id (str "demo/f#1/" t) :seon.turn/run {:seon.run/id "demo/f#1"} :seon.turn/agent {:seon.agent/key "b/a1"}
                       :seon.turn/n t :seon.turn/origin :model}
                      {:seon.model-call/id (str "demo/f#1/" t) :seon.model-call/turn (str "t" t) :seon.model-call/basis (basis t)}])
        sent (fn [t r] [{:seon.model-call/id (str "demo/f#1/" t) :seon.model-call/sent (:sent r)}])
        opts (fn [db t parts printed] {:db db :basis (basis t) :help "help" :help-ref [:section 1 1] :parts parts :printed printed})
        e {:db/id a}
        part (fn [k text] [:section {:id k} text])
        parts (fn [t] {"task-1" (part "task-1" "Make f.") "status-1" (part "status-1" (str t " of 9"))})
        db1 (d/db-with db (turn 0))
        r0 (seon.context/request e (opts db1 0 (parts 1) nil))
        db2 (d/db-with db1 (concat (sent 0 r0) (turn 1)))
        r1 (seon.context/request e (opts db2 1 (parts 2) (:printed r0)))
        db3 (d/db-with db2 (concat (sent 1 r1)
                                   [{:seon.eval/id "e-last" :seon.eval/by "a1" :seon.eval/agent {:seon.agent/key "b/a1"}
                                     :seon.eval/record {:code "(f)" :call-id "c-last" :call 0 :prose "Last." :response {:handle 'result/e-last :value 1}}}]
                                   (turn 2)))]
    [e (opts db3 2 (parts 3) (:printed r1))]))

(deftest a-turns-request-costs-its-parts-flat-in-the-session
  ;; task seon.context/the-context-is-the-agents-evals-and-seons (R1): a request is the previous one and
  ;; what was added, so with the run's print it prints the parts that moved and the new record, whatever
  ;; the session held before (the replacement of the cold page draws of seon.context/world, 35M ticks a page)
  (cost/proof {:of 'seon.context/request :input 'seon.context-cost-test/request-of
               :n "evals of the session before the turn"
               :work "the previous call and its refs read once, the parts that moved printed in one message, the new record printed; nothing earlier is printed or drawn again"
               :growth :constant
               ;; +5 with what changed of a moved part: seon.context/part-change and its two model reads (2026-10-31)
               ;; 2026-11-04, the owner's yes ("Yes, raise by 4"), old 332: a record's text goes through the one lookup, record-messages,
               ;; seon.render/model, model-declared, contract/properties (seon.render/one-way-to-make-what-an-agent-is-sent, step 4). Re-pinned
               ;; to what it measures at step 6, which deletes part-change and parts-message from this path
               :fixed {:calls 336}
               ;; measured 2026-10-29: 9 contracted calls at 10, 100 and 1,000 evals before the turn (the moved part printed once, the new record once)
               :sizes [10 30 100]}))
