(ns seon.context-cost-test
  "The conversation's cost proofs: a turn prints only its new records (seon.context/printed-run),
  flat in the run's history (root's ruling 2026-10-25: a record's printed message is a function
  of it and the records before it, which never change, so it is printed once and reused by its
  id). Run: bin/test seon.context-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.context] [seon.ctx]
            [datahike.api :as d] [seon.test-store :as ts] [seon.db :as pdb] [seon.task :as task] [seon.vector :as vector] [seon.program-cost-test :as pc]))

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
               :fixed {:calls 16 :bytes 340000}
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
               :sizes [10 100 1000]}))

(defn related-of-changed
  "seon.context/related's arguments at n wiki sections, each with a vector, and the plan's: its
  answer made anew, as when its vector changed."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [unit (fn [seed] (let [v (mapv (fn [i] (Math/sin (* (inc seed) (inc i) 0.37))) (range 768)) m (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % m) v)))
        db (ts/test-db)
        db (d/db-with db (vec (for [i (range n)] {:seon.section/address (str "docs/p.md#s" i) :seon.section/heading (str "S" i)
                                                  :seon.section/lead (str "Section " i " says it.")})))
        db (d/db-with db (task/create-tx db [{:seon.task/ns 'demo :seon.task/name "r" :seon.task/plan "Say it."}]))
        te (pdb/eid db :seon.task/id "demo/r")
        es (into [te] (for [i (range n)] (pdb/eid db :seon.section/address (str "docs/p.md#s" i))))]
    [(d/db-with db (vector/tx-data es (repeat (count es) 1) (mapv unit (range (count es))) (repeat (count es) 10) "m")) te]))

(deftest related-ranks-each-kind-by-one-search
  (cost/proof {:of 'seon.context/related :input 'seon.context-cost-test/related-of-changed
               :n "wiki sections with vectors"
               :work (str "the plan's citations parsed once; seon.context/relevance for each kind: one seon.vector/near "
                          "(every coarse row read, its shortlist of 400 reranked: one seek a row), its pool (the "
                          ":seon.config.render/relevance-pool nearest and the seeds' neighbourhood, none here) "
                          "scored: each pool item's vector one seek, its text tokenized once, the sections' names "
                          "read once: linear in the vectors, flat in them past the pool")
               :growth :linear
               ;; measured 2026-10-27: 695 calls and 11.05 MB at n=250, 845 and 13.08 MB at 500, where
               ;; near's shortlist reaches its 400 rows (one seek a row reranked) and stays. Over the rank
               ;; fusion before it (447 calls, 7.81 MB): the pool's scoring, a few contracted calls an
               ;; item (its text, tokens, scores) for at most 100 items a kind. A row is still near's
               ;; coarse datom read and its kind's membership (~1.48 KB, seon.vector/one-scan-ranks-every-kind).
               ;; 848 calls at n=450 on 2026-10-28: bm25 shares its per-text scoring with seon.docs/tokens (one more contracted call a bm25)
               ;; 2026-10-29 (a-vector-is-its-own-entity, docs/vector.md#a-vector-is-its-own-entity): each near reads one
               ;; datom more a row (the scan of :seon.vector/of it merge-joins with the coarse rows; related makes
               ;; three searches: ~0.65 KB a row more than main's 1.5, measured 2.1 KB a row from 450 to 1,000) and a pool item's vector is a seek more: 13.07 MB at 450, 13.62 MB at 700, 14.22 MB at
               ;; 1,000, 857 calls (from 12.0, 12.4, 12.8 MB and 848)
               :per-n {:bytes 2300.0 :calls 0.0}
               :fixed {:bytes 12200000.0 :calls 860.0}
               :sizes [450 700 1000 2000]}))

(defn ring-of
  "seon.context/pushed's arguments at n forms in a ring, each calling the next: every node two
  neighbours, so the push's reach is bounded by its threshold, not by n. Seeded at the first."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:set :int] number?]]}
  [n]
  (let [db (d/db-with (ts/test-db) (vec (mapcat (fn [i] [{:db/id (str "f" i) :form/id (str "demo/r" i) :form/src "(r)"
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
               ;; measured 2026-10-27: 46 calls and 5.23 MB at 50, 48 and 5.45 MB at 200: the ring's first pushes, each node's
               ;; degree counted once (bounded, `degree`) and its neighbours read once
               :fixed {:calls 48 :bytes 5600000}
               :sizes [50 200 1000]}))

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

(defn task-data-of
  "seon.context/task-data's arguments at n wiki sections with vectors: the task demo/r as of the db,
  without its write set, as the task part draws it."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :boolean]]}
  [n] (let [[db _] (related-of-changed n)] [db (seon.task/task db "demo/r") false]))

(defn namespace-of
  "seon.context/namespace-data's arguments at n definitions: a branch whose namespace app.pc defines
  n functions (seon.program-cost-test/branch-of), and that namespace."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :symbol]]}
  [n] [(first (pc/branch-of n)) 'app.pc])

(deftest the-namespaces-part-costs-the-names-it-shows
  ;; what the namespaces part draws of one namespace (docs/render.md#a-part-answers-data): its
  ;; definitions' datoms, a name each, never their forms read again (agents work in namespaces of hundreds)
  (cost/proof {:of 'seon.context/namespace-data :input 'seon.context-cost-test/namespace-of
               :n "definitions in the namespace"
               :work "the namespace's entity and its ns form's source for its aliases, then one pass over its :var/ns datoms, each var read for its name, kind, test status and its form's order (five seeks, ~7 KB each on Datahike's index): a name per definition, linear in them, no form read"
               :growth :linear
               ;; measured on Jolt 546c3d1f: 410 KB at 10, 3.49 MB at 100 (34 KB a definition); the two queries it
               ;; replaced (get-else per tuple) 1.36 MB and 5.87 MB, a pull each 837 KB and 7.76 MB
               :per-n {:bytes 35000}
               :fixed {:calls 2 :bytes 80000}
               :sizes [10 100 1000]}))

(deftest task-data-costs-what-the-plan-names
  ;; the task's data (task-data over the task as of t), as the task's component draws it when a
  ;; transaction touches what it read (seon.render/render!): what the plan cites read and resolved, flat in the wiki's size
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
  turn's parts as seon.context/request records them, drawn at that turn's commit. Every turn
  sends the same parts (a plan and a run), so the cost is per ref."
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
        [@conn (vec (for [[i c] (map-indexed vector commits)] [:parts a c (zero? i) ids (str tid "#1/" i)]))]))))

;; See docs/render.md#the-branchs-views
(deftest reading-a-request-back-draws-each-turns-parts-once
  (binding [*conn* nil]
    (ts/test-branch!
      (fn [_ conn]
        (binding [*conn* conn]
          (cost/proof {:of 'seon.context/parts-of :input 'seon.context-cost-test/turns-of
                       :n "parts refs of the request (one turn's parts each, each drawn at its own commit)"
                       :work "one page drawn a ref (the walk of the agent's record at that commit), every commit once"
                       :growth :linear
                       ;; measured 2026-10-28 (a plan and a run a page): 2.68 MB / 433 calls at 1, 9.51 MB / 881 at 2,
                       ;; 23.2 MB / 1,777 at 4, 50.7 MB / 3,569 at 8; before the pages map (every page drawn twice):
                       ;; 11.0 MB / 1,195 at 2, 27.7 MB / 2,719 at 4, 61.2 MB / 5,767 at 8 (762 calls a page, not 448)
                       :per-n {:bytes 6800000 :calls 480}
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
               :fixed {:calls 9}
               ;; measured 2026-10-29: 9 contracted calls at 10, 100 and 1,000 evals before the turn (the moved part printed once, the new record once)
               :sizes [10 100 1000]}))
