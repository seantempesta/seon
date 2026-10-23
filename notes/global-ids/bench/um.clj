;; U at 10 and 100 branches: boot, upkeep per commit, and the owner's query over (d/history U)
(require '[datahike.api :as d] '[seon.db :as pdb] '[seon.union :as u] '[seon.union-test :as ut] 'clojure.java.shell)
(defn ms [t0] (/ (- (System/nanoTime) t0) 1e6))
(defmacro timed [label n & body] `(let [_# (do ~@body) t0# (System/nanoTime)] (dotimes [_# ~n] ~@body) (let [m# (/ (ms t0#) ~n)] (println (format "  %-46s %9.3f ms" ~label m#)) m#)))
(def path "/Users/sean/src/seon-jolt/next-global-ids/target/m/union")
(clojure.java.shell/sh "rm" "-rf" path)
(def o (pdb/open! :lmdb path 20000 :db))
(def conn (:conn o)) (def cfg (:cfg o))
(defn turn [c b i k] ; one turn: a reply to root's message and the turn's facts, ~55 datoms
  (d/transact c {:tx-data (into [{:seon.message/id (str b "/r" k) :seon.message/from (name b) :seon.message/reply-to (str "m-" (name b)) :seon.message/body (str "reply " k " of " (name b))}]
                                (for [j (range 12)] {:note/text (str (name b) " turn " k " fact " j)}))
                 :tx-meta (pdb/tx-meta b)}))
(println "main datoms" (count (d/datoms @conn :eavt)))
(doseq [nb [10 100]]
  (let [bs (vec (for [i (range nb)] (keyword (str "n" nb "-a" i))))]
    (doseq [b bs] (d/branch! conn :db b))
    (d/transact conn {:tx-data (vec (for [b bs] {:seon.message/id (str "m-" (name b)) :seon.message/from "root" :seon.message/to (name b) :seon.message/body (str "main moved, " (name b) ", merge main")}))
                      :tx-meta (pdb/tx-meta :db)})
    (doseq [b bs] (let [c (d/connect (assoc cfg :branch b))] (turn c b 0 0) (d/release c)))
    (println "==" nb "branches, each one turn")
    (let [t0 (System/nanoTime)
          un (reduce (fn [un b] (u/catch-up un b (pdb/branch-base conn :db b) (pdb/head conn b)))
                     (u/of-history (u/empty-union @conn) :db @conn) bs)
          boot (ms t0)
          U (:db un) H (d/history U)]
      (println (format "  boot: main's history + %d branches %.0f ms; U holds %d datoms" nb boot (count (d/datoms H :eavt))))
      (let [b (first bs) c (d/connect (assoc cfg :branch b))]
        (turn c b 0 1)
        (let [from (d/commit-as-db conn (get-in un [:applied b])) to (pdb/head conn b)]
          (timed "upkeep: one branch commit (catch-up)" 20 (u/catch-up un b from to)))
        (d/release c))
      (timed "owner's query, one agent's conversation" 50 (d/q ut/conversation H (name (first bs))))
      (timed "every conversation (all branches)" 10 (d/q '[:find ?to ?reply ?br :where [?m :seon.message/to ?to] [?m :seon.message/id ?id] [?r :seon.message/reply-to ?id ?tx] [?r :seon.message/body ?reply] [?tx :seon.tx/branch ?br]] H))
      (println "  rows, every conversation:" (count (d/q '[:find ?to ?reply ?br :where [?m :seon.message/to ?to] [?m :seon.message/id ?id] [?r :seon.message/reply-to ?id ?tx] [?r :seon.message/body ?reply] [?tx :seon.tx/branch ?br]] H))))))
(clojure.java.shell/sh "rm" "-rf" path)
