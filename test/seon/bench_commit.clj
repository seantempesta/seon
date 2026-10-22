(ns seon.bench-commit
  "What a commit writes to a durable store (task seon.db/the-store-grows-with-what-it-keeps):
  keys and bytes added to the LMDB store, and data.mdb's growth, for a branch, a 1-datom
  commit, one eval's recording transaction (seon.repl/record-tx through the agent's run-ahead
  writer: datahike.writer/apply! then commit-pending!), 100 evals committed one by one, and the
  same 100 in one commit; on a fresh store seeded at each size.
  jolt -A:test run -m seon.bench-commit DIR N..."
  (:require [seon.db :as pdb] [seon.repl :as repl] [datahike.api :as d] [datahike.writer :as dw]
            [konserve.jolt.lmdb :as klmdb] [konserve.jolt.lmdb-ffi :as l] [clojure.string :as str] [seon.edn]))

(defn- snapshot
  "Every key of the store at path with its value's byte count, and data.mdb's size."
  [path]
  (let [db (klmdb/open-env! path nil)]
    {:kv (l/read-txn db (fn [txn] (into {} (for [k (l/all-keys db txn)] [k (alength ^bytes (l/get-bytes db txn k))]))))
     :file (.length (java.io.File. (str path "/data.mdb")))}))

(defn- delta
  "Keys and bytes added between snapshots a and b (a changed key counts its new size)."
  [a b]
  (let [added (for [[k n] (:kv b) :when (not= n (get (:kv a) k))] [k n])]
    {:keys (count added) :bytes (reduce + 0 (map second added))
     :max-value (reduce max 0 (map second added))
     :sizes (when (<= (count added) 40) (vec (sort > (map second added))))
     :file-bytes (- (:file b) (:file a))}))

(defn- items
  "n reply items as the agent folds them: a form of form-bytes and a printed value of shown-bytes."
  [n form-bytes shown-bytes]
  (vec (for [i (range n)]
         {:id i :tag :ret :form (str "(let [x " i "] " (apply str (repeat (max 0 (- form-bytes 12)) "a")) ")")
          :printed (apply str (repeat shown-bytes "v")) :ms 1.25 :alloc-bytes 4096})))

(defn- eval-tx [k shown]
  (repl/record-tx :agent-1 (str "agent-1-" k) (items 1 300 shown) k))

(defn- open-store!
  "A fresh LMDB store at path seeded as seon.db/open! seeds one, with branching factor bf."
  [path n bf]
  (let [cfg (assoc-in (pdb/store-cfg :lmdb path) [:index-config :branching-factor] bf)]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data pdb/schema})
      (doseq [tx (pdb/seed-tx n)] (d/transact conn {:tx-data tx}))
      (pdb/sync! cfg)
      {:conn conn :cfg cfg})))

(defn- measure! [dir n shown bf]
  (let [path (str dir "/store-" n "-" shown "-" bf)
        {:keys [conn cfg]} (open-store! path n bf)
        seed-datoms (count (d/datoms @conn :eavt))
        step (fn [f] (let [a (snapshot path) r (f) _ (pdb/sync! cfg) b (snapshot path)] [(delta a b) r]))
        [branch _] (step #(d/branch! conn :db :b1))
        c1 (d/connect (assoc cfg :branch :b1))
        commit1 (fn [tx] (dw/apply! c1 {:tx-data tx}) (dw/commit-pending! c1))
        [one _] (step #(commit1 [{:seon.agent/id :probe}]))
        eval-datoms (count (:tx-data (d/with @c1 (eval-tx 0 shown))))
        [ev _] (step #(commit1 (eval-tx 1 shown)))
        [ev100 _] (step #(doseq [k (range 2 102)] (commit1 (eval-tx k shown))))
        _ (d/branch! conn :db :b2)
        c2 (d/connect (assoc cfg :branch :b2))
        [one100 _] (step #(do (doseq [k (range 2 102)] (dw/apply! c2 {:tx-data (eval-tx k shown)}))
                              (dw/commit-pending! c2)))]
    (d/release c1) (d/release c2) (d/release conn)
    {:seed-datoms seed-datoms :bf bf :shown-bytes shown :eval-datoms eval-datoms
     :a-branch branch :b-one-datom one :c-one-eval ev :d-100-evals ev100 :e-100-in-one one100}))

(defn- turn-tx
  "One model turn's writes as seon.turn/act! makes them: its eval record (one form, a printed
  value of 2,000 bytes), its turn facts, and its split; with small, every value over 1 KB is a
  40-byte reference instead. ctx bytes: the context text the turn stored before
  (:seon.turn/context); :basis: what it stores now, the basis its context is made from again
  (seon.context/render) and its evaluations as the next turn shows them (2,000 bytes, its
  eval's printed value)."
  [k ctx small]
  (let [id (str "att-1/" k) sz (fn [n] (if small 40 n))]
    [(if small (repl/record-tx :agent-1 (str "agent-1-" k) (items 1 40 0) k) (eval-tx k 2000))
     [(merge {:seon.turn/id id :seon.turn/attempt "att-1" :seon.turn/n k :seon.turn/origin :model}
             (if (= :basis ctx)
               {:seon.turn/basis {:commit (random-uuid) :task "t/x" :attempt "att-1" :turn k :max-turns 50 :origin :model
                                  :observed {:results {'t-test/x :fail} :run-errors [] :refusals [{:refusal :proof-red :symbol 't-test/x}]
                                             :reds [{:test 't-test/x :type :fail :expected "(= 6 (f 2))" :actual "(not (= 6 4))"}]}}
                :seon.turn/calls [{:id "call-0" :code "(f 2)" :evals (apply str (repeat (sz 2000) "e"))}]}
               ;; before: the context text inline (the attribute it was stored in is gone; a string
               ;; attribute of the same bytes writes the same)
               {:seon.turn/finish (apply str (repeat ctx "c"))}))
      {:seon.turn/id id :seon.turn/reply (apply str (repeat (sz 1500) "r"))
       :seon.turn/reasoning (apply str (repeat (sz 3000) "z")) :seon.turn/forms 1 :seon.turn/errors 0
       :seon.turn/red 1 :seon.turn/ms 4000 :seon.turn/tokens 9000}]
     [{:seon.turn/id id :seon.turn/split {:context-ms 3 :model-ms 4000 :eval-ms 2 :record-ms 0}}]]))

(defn- measure-turns!
  "50 turns on a branch of a fresh store of seed n, committed every `every` turns."
  [dir n ctx every & [small]]
  (let [path (str dir "/turns-" n "-" ctx "-" every (when small "-small"))
        {:keys [conn cfg]} (open-store! path n 64)
        _ (d/branch! conn :db :b1)
        c1 (d/connect (assoc cfg :branch :b1))
        a (snapshot path)]
    (doseq [k (range 50)]
      (doseq [tx (turn-tx k ctx small)] (dw/apply! c1 {:tx-data tx}))
      (when (zero? (mod (inc k) every)) (dw/commit-pending! c1)))
    (pdb/sync! cfg)
    (let [b (snapshot path)]
      (d/release c1) (d/release conn)
      {:seed n :context-bytes ctx :turns-per-commit every :turns 50
       :written (dissoc (delta a b) :sizes) :own-bytes (when-not small (* 50 (+ (if (= :basis ctx) 2400 ctx) 1500 3000 2400)))})))

(defn -main
  "DIR BF,BF.. N.. : every seed size n and branching factor, printed values of 200 and 2,000 bytes."
  [dir bfs & ns]
  (when (= bfs "turns-out-of-line")
    ;; the same turns with every value over 1 KB held out of the index as a 40-byte reference:
    ;; what the index would write if large values were stored once beside it
    (doseq [every [1 10 50]] (println (pr-str (measure-turns! dir 2000 40 every :small))))
    (System/exit 0))
  (when (= bfs "turns")
    (doseq [ctx [8000 32000] every [1 10 50]] (println (pr-str (measure-turns! dir 2000 ctx every))))
    (System/exit 0))
  (when (= bfs "turns-basis")
    ;; the same turns storing their basis instead of their context: the context's size no longer
    ;; reaches the store (an 8 KB and a 32 KB context write the same)
    (doseq [every [1 10 50]] (println (pr-str (measure-turns! dir 2000 :basis every))))
    (System/exit 0))
  (doseq [n (map parse-long ns) bf (map parse-long (str/split bfs #",")) shown [200 2000]]
    (println (pr-str (measure! dir n shown bf)))))
