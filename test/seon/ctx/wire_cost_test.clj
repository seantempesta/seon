(ns seon.ctx.wire-cost-test
  "The conversation's cost proofs: a request costs its records (seon.ctx.wire/request), each a function of that record alone
  (docs/render.md#the-models-context-a-cached-prefix-then-the-turn), and what reads them. Run: bin/test seon.ctx.wire-cost-test"
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest]] [seon.contract :refer [defn defn-]] [seon.cost :as cost] [seon.ctx] [seon.ctx] [seon.doc] [seon.fixtures] [seon.render]
            [datahike.api :as d] [seon.test-store :as ts] [my.test :as t] [seon.db :as pdb] [seon.task :as task] [seon.vector :as vector] [seon.ctx.status] [seon.ctx.wire] [seon.ctx.search] [seon.ctx.wire-test]))

(defn- item
  "Eval record i of a run as the conversation reads it ([ref v k tx]): a model's call whose value
  is a production-shaped row set (a few maps of strings and numbers), each unlike the others."
  {:malli/schema [:=> [:cat nat-int?] :any]}
  [i]
  (let [id (str "e" i)]
    [[:eval id]
     {:seon.eval/id id :seon.eval/by {:seon.agent/key "a1"}
      :seon.eval/code (str "(rows " i ")") :seon.eval/call-id (str "c-" i) :seon.eval/intent (str "Read the rows of part " i ".") :seon.eval/response {:handle (symbol "result" id) :value (vec (for [j (range 6)] {:part i :row j :name (str "row-" i "-" j)}))
                                    :ms 0.5 :ticks 1000 :bytes 4096}}
     :seon.db/eval-fact (inc i)]))

(defn- ticks-bytes
  "f on the args input-fn answers at n, called once to warm and then measured as one call (seon.cost/measure: this thread's own ticks and bytes), the input built before it."
  {:malli/schema [:=> [:cat :any :seon.host/fn pos-int?] [:map [:ticks number?] [:bytes number?]]]}
  [f input-fn n] (let [args (input-fn n)] (apply @f args) (select-keys (cost/measure @f args) [:ticks :bytes]))) ; one warm-up call: the first fills caches

(defn- flat?
  "Whether the dimension d of measure small and of measure large is within the ratio r of each other: flat in n."
  {:malli/schema [:=> [:cat :keyword :map :map number?] :boolean]}
  [d small large r] (<= (get large d) (* r (get small d))))

(defn agent-named
  "The input at n: a main db holding a session of n evals by agent b/a1 (as a
  woken or loaded session's first request reads it cold), each record production-shaped (item),
  and the agent's entity id."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :int]]}
  [n]
  (let [db (d/db-with (ts/test-db) (seon.doc/load-tx (ts/test-db) "docs" {seon.doc/system-page "# You are a Seon agent\n\nThe rules."}))
        db (d/db-with db
                      (into [{:seon.agent/key "b/a1" :seon.agent/turn n}]
                            (for [i (range n) :let [[_ v] (item i)]]
                              (assoc v :seon.eval/agent {:seon.agent/key "b/a1"}))))]
    [db (:db/id (d/entity db [:seon.agent/key "b/a1"]))]))

(defn- turn-tx
  "Turn t of agent-named's agent as production writes it: its eval i's model call with the reply that holds it, and a message taken at every other turn, delivered in the same transaction as the call (so it reads at that turn)."
  {:malli/schema [:=> [:cat nat-int?] [:vector :map]]}
  [i]
  (let [t (inc i) call #(seon.task/call-ref "b/a1" t)]
    (cond-> [{:seon.eval/id (str "e" i) :seon.eval/call (assoc (call) :seon.model-call/origin :model :seon.model-call/reply (str "Reading the rows of part " i "."))}]
      (even? i) (conj {:seon.message/id (str "m" i) :seon.message/to [{:seon.agent/key "b/a1"}] :seon.message/from {:seon.agent/key "owner"} :seon.message/body (str "How is part " i "?")}))))

(defn records-of
  "The input at n of seon.ctx.wire/request: the agent's ctx value over agent-named's db, as a request reads it, each turn production-shaped (`turn-tx`)."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n] (let [[db e] (agent-named n)
            db (reduce (fn [db i] (d/db-with db (turn-tx i))) db (range n))]
        [(:tree (seon.ctx/build (seon.db/index) db e :b)) db]))

(deftest ^{:seon.test/estimate {:ticks 5000000}} a-request-reads-what-the-agents-ctx-holds
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn; the owner, 2026-10-26: nothing in a request is read from
  ;; anywhere but its ctx: a record the ctx does not hold is not sent
  ;; estimate: 3 turns built and read once each, ~1.4k ticks a contracted call, a few hundred calls a turn: ~1M ticks
  (let [[c db] (records-of 3)
        evals (fn [c] (filter #(= :eval (ffirst %)) (seon.fixtures/tree-parts c db)))]
    (clojure.test/is (= 3 (count (evals c))))
    (clojure.test/is (= 2 (count (evals (update c :seon.eval/_agent dissoc (first (keys (:seon.eval/_agent c))))))) "what the pattern does not reach is no record")))

(defn ring-of
  "seon.ctx.search/pushed's arguments at n forms in a ring, each calling the next: every node two
  neighbours, so the push's reach is bounded by its threshold, not by n. Seeded at the first."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/fn [:set :int] number?]]}
  [n]
  (let [db (d/db-with (t/world) (vec (mapcat (fn [i] [{:db/id (str "f" i) :form/id (str "demo/r" i) :form/src "(r)"
                                                          :form/refs #{(symbol "demo" (str "r" (mod (inc i) n)))}}
                                                         {:var/name (symbol "demo" (str "r" i)) :var/form (str "f" i)}])
                                                 (range n))))]
    [db (#'seon.ctx.search/section-names db) #{(pdb/eid db :form/id "demo/r0")} 0.001]))

(deftest ^{:seon.test/estimate {:ticks 8000000}} a-push-reads-what-its-threshold-reaches
  ;; a local push (Andersen, Chung and Lang 2006): at most 1/(alpha eps) pushes, each node's neighbours read once, so the reads are flat in
  ;; the ring's size. Estimate: bytes ~4.9 MB at any n (measured 2026-10-31 at 30 and 100); a ring ten times larger reads the same.
  (let [small (ticks-bytes #'seon.ctx.search/pushed ring-of 30) large (ticks-bytes #'seon.ctx.search/pushed ring-of 60)]
    (clojure.test/is (flat? :bytes small large 1.15))
    (clojure.test/is (< (:bytes large) 4900000))))

(defn hub-of
  "seon.ctx.search/pushed's arguments at n forms each naming hub var demo/h, seeded at a form that calls
  h (as a function calls seon.contract/checked): the push's mass reaches h's form, whose callers are
  read to :hub (seon.config.render/relevance-features), so it is never read whole."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :seon.host/fn [:set :int] number?]]}
  [n]
  (let [db (d/db-with (ts/test-db) (into [{:db/id "h" :form/id "demo/h" :form/src "(defn h [])"} {:var/name 'demo/h :var/form "h"}
                                          {:db/id "s" :form/id "demo/s" :form/src "(defn s [] (h))" :form/refs #{'demo/h}} {:var/name 'demo/s :var/form "s"}]
                                         (for [i (range n)] {:form/id (str "demo/u" i) :form/src "(h)" :form/refs #{'demo/h}})))]
    [db (#'seon.ctx.search/section-names db) #{(pdb/eid db :form/id "demo/s")} 0.001]))

(deftest ^{:seon.test/estimate {:ticks 12000000}} a-push-counts-a-hub-and-reads-it-not
  ;; the seed pushed, the hub read to :hub (64 datoms a read) and found past it, so it is not pushed and the rest of its callers are never
  ;; read: flat in them. Estimate: bytes under 1 MB at any number of callers.
  (let [small (ticks-bytes #'seon.ctx.search/pushed hub-of 50) large (ticks-bytes #'seon.ctx.search/pushed hub-of 150)]
    (clojure.test/is (flat? :bytes small large 1.1))
    (clojure.test/is (< (:bytes large) 1000000))))

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
    [(d/db-with db (vector/tx-data es (repeat (count es) "1") (mapv unit (range (count es))) "m")) te]))

(defn task-data-of
  "seon.ctx.status/task-data's arguments at n wiki sections with vectors: the task demo/r as of the db and
  its cites, as my.task/data answers it at the REPL."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map [:vector :map]]]}
  [n] (let [[db _] (sections-of n)] [db (seon.task/task db "demo/r") (seon.task/task-cites db "demo/r")]))

(deftest ^{:seon.test/estimate {:ticks 3000000}} task-data-costs-what-the-plan-names
  ;; the task's data (task-data over the task as of t), as my.task/data answers it at the REPL: what the plan cites read and resolved, flat
  ;; in the wiki's size. Estimate: eighteen contracted calls at ~1.4k ticks, reads and parses included: ~25k ticks (the owner, 2026-11-15)
  (let [small (ticks-bytes #'seon.ctx.status/task-data task-data-of 2) large (ticks-bytes #'seon.ctx.status/task-data task-data-of 8)]
    (clojure.test/is (flat? :ticks small large 1.1))
    (clojure.test/is (< (:ticks large) 25000))))

(defn request-of
  "The input at n: the agent's walk value after n earlier turns, each production-shaped (`turn-tx`), read once by a request, then one new
  turn written, ctx patched by its datoms and the walk advanced by them (seon.render/walk), and the db after: so the proof's request
  reads that one new record against the n read before, as a turn's does."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :map :seon.host/db]]}
  [n]
  (let [[db0 a] (agent-named (inc n))
        db (reduce (fn [db i] (d/db-with db (turn-tx i))) db0 (range n))
        ix (seon.db/index)
        c (seon.ctx/build ix db a :b)
        opts (fn [db c] {:db db :tree (:tree c) :root #'seon.render.ui/agent :index ix :changes seon.ctx/changes :model-only true
                         :outputs #{:seon.render/hiccup :seon.render/openai-compat}})
        p (seon.render/walk nil (opts db c))
        _ (seon.ctx.wire/request p db)
        r (d/with db (conj (turn-tx n) {:seon.agent/key "b/a1" :seon.agent/turn (inc n)}))
        db1 (:db-after r)]
    [(seon.render/walk p (assoc (opts db1 (seon.ctx/patch c db1 (:tx-data r) :b)) :moved (:tx-data r))) db1]))

(deftest ^{:seon.test/estimate {:ticks 10000000}} a-requests-cost-is-its-records
  ;; docs/render.md#the-models-context-a-cached-prefix-then-the-turn: a turn reads what moved and walks the rest: linear in the turns,
  ;; the wire body holds n messages anyway. Estimate: 1.26M ticks + 11.2k a turn, 0.6 MB + 15 KB a turn (2026-11-14, web)
  (let [small (ticks-bytes #'seon.ctx.wire/request request-of 2) large (ticks-bytes #'seon.ctx.wire/request request-of 4)]
    (clojure.test/is (< (:ticks large) (+ 1260000 (* 11200 4))))
    (clojure.test/is (< (:bytes large) (+ 600000 (* 15000 4))))
    (clojure.test/is (< (- (:ticks large) (:ticks small)) (* 11200 2)) "two more turns cost two turns")))

(defn greens-of
  "seon.ctx.status/status's arguments for agent a1 on task demo/f, its proof red, on a branch holding n more green tests no
  proof names: the tests a run judged that the done rule never reads, grown."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :map :map]]}
  [n]
  (let [db (#'seon.ctx.wire-test/db-f (#'seon.ctx.wire-test/db-with-program))
        b (get-in db [:config :branch])
        ;; each run's verdict on the branch, as seon.agent/record-results! writes it
        verdict (fn [t st fs] (cond-> {:seon.proof/id (seon.lib/digest (str t)) :seon.proof/test t :seon.proof/status st :seon.proof/route :live
                                       :seon.proof/ran-on #{b} :seon.proof/reads-digest (seon.lib/digest (str t " reads"))}
                                (seq fs) (assoc :seon.proof/failures fs)))
        db (d/db-with db (into [{:seon.agent/key "a1" :seon.agent/task [:seon.task/id "demo/f"]}
                                (verdict 'demo-test/f-test :fail [{:type :fail :expected "(= 4 (f 2))" :actual "(not (= 4 5))"}])]
                               (for [i (range n)] (verdict (symbol "other-test" (str "t" i)) :pass nil))))]
    [db (:tree (seon.ctx/build (seon.db/index) db (pdb/eid db :seon.agent/key "a1") :b)) {}]))

;; See docs/render.md#how-the-work-stands
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-status-costs-its-proofs-and-its-reds
  ;; the status read every test's verdict on the branch each turn; the done rule reads the task's proofs and the tests that are not
  ;; green, so the greens no proof names cost nothing. Estimate: ~0.46 MB at any number of greens (measured 2026-11-10)
  (ts/test-branch!
    (fn [_ _]
      (let [small (ticks-bytes #'seon.ctx.status/status greens-of 5) large (ticks-bytes #'seon.ctx.status/status greens-of 60)]
        (clojure.test/is (flat? :bytes small large 1.1))
        (clojure.test/is (< (:bytes large) 500000))))))

(defn test-ns-of
  "seon.ctx.status/test-shape's arguments for an unwritten proof of namespace demo-test, which holds n helpers and n tests, no
  test calling a helper (each test is read until one uses a helper): the namespace a proof goes into, grown."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :seon.host/db :qualified-symbol]]}
  [n]
  (let [db (d/db-with (t/world)
                      (into [{:db/id "ns" :ns/name 'demo-test :ns/form {:form/id "demo-test/ns" :form/src "(ns demo-test (:require [clojure.test :refer [deftest is]]))"}}]
                            (mapcat (fn [i] [{:var/name (symbol "demo-test" (str "helper-" i)) :var/ns "ns" :var/form {:form/id (str "demo-test/helper-" i) :form/src (str "(defn helper-" i " [] " i ")")}}
                                             {:var/name (symbol "demo-test" (str "t" i)) :var/ns "ns" :var/meta #{:test} :var/form {:form/id (str "demo-test/t" i) :form/src (str "(deftest t" i " (is (= " i " (inc " (dec i) "))))")}}])
                                    (range n))))]
    [db 'demo-test/unwritten]))

;; See docs/render.md#how-the-work-stands
(deftest ^{:seon.test/estimate {:ticks 2000000}} an-unwritten-proofs-shape-costs-its-namespace-once
  ;; each var of the namespace read once, each test's source scanned once for the heads it calls: linear, no regex per test and helper.
  ;; Estimate, from what it reads: fixed, the namespace's entity and its ns form's :require slice (~2 KB), one AVET seek for its vars
  ;; and one for its forms (~2 KB each) and the use-fixtures scan of the forms that define nothing (~2 KB): ~10 KB, 50 KB with slack.
  ;; A helper and a test (two vars): each var's entity with its name and meta read (~4 KB), its form's source (~1 KB), the test's
  ;; source scanned once for the heads it calls (one re-seq and a set, ~4 KB), and its place in the sort: ~20 KB, 25 KB with slack.
  ;; Measured 40 KB at 1, 509 KB at 24 (result/a4069406): the 353 KB fixed part measured before was two datalog queries' engine
  ;; (62 KB and 255 KB on a 256-datom world), now AVET seeks
  (let [small (ticks-bytes #'seon.ctx.status/test-shape test-ns-of 4) large (ticks-bytes #'seon.ctx.status/test-shape test-ns-of 24)]
    (clojure.test/is (< (:bytes large) (+ 50000 (* 25000 24))))
    (clojure.test/is (< (- (:bytes large) (:bytes small)) (* 25000 20)) "twenty more vars cost twenty vars")))

(deftest ^{:seon.test/estimate {:ticks 2000000}} a-turns-page-message-does-no-cold-draw
  ;; docs/render.md#a-page-is-a-kept-walk: a turn's page message is made from the parts the placing render's walk keeps
  ;; (:seon.ctx.wire/parts), never from a page drawn cold at the turn's points (2026-11-11: ~7.1M ticks a turn on a live walk). Estimate:
  ;; no draw, a few contracted calls at ~1.4k ticks: under 60k ticks, against 163k for the cold draw even of this small agent.
  (let [[db a] (agent-named 3) here {:commit (d/commit-id db) :t (:max-tx db)}
        calls (volatile! 0)
        opts (with-meta {:seon.db/db db :seon.render/index (seon.db/index)} {:seon.ctx.wire/parts (fn [] (vswap! calls inc) [{} {}])})]
    (clojure.test/is (= [] (seon.ctx.wire/page-diff-msgs opts a here here 1)) "nothing moved between the kept parts")
    (clojure.test/is (= 1 @calls) "the kept parts are what the message is made from")
    (clojure.test/is (< (:ticks (cost/measure @#'seon.ctx.wire/page-diff-msgs [opts a here here 1])) 60000))))
