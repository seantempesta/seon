;; The delivery comparison's measurements (design agent-loop 3a), each one :eval of root (bin/ask 8140) on this
;; worktree's root with agents a1 (branch :mx) and a2 (:my) spawned; 2026-10-25, n = 100 and 10.
(let [w (fn [req] (deref (seon.root/call! sys {:op :write :req req})))
      ms (fn [f] (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6)))
      med (fn [xs] (let [v (vec (sort xs))] {:median (seon.lib/r2 (nth v (quot (count v) 2))) :p90 (seon.lib/r2 (nth v (int (* 0.9 (count v)))))}))
      msg (fn [i tag] [{:seon.message/id (str tag "-" i "-" (random-uuid)) :seon.message/from "a1" :seon.message/body (str "hello " i)}])
      a (vec (for [i (range 100)] (ms #(w {:op :tx :branch :db :tx-data (msg i "A")}))))
      b (vec (for [i (range 100)]
               (ms #(let [m (msg i "B") id (:seon.message/id (first m))
                          _ (w {:op :tx :branch :mx :tx-data m})
                          src (:db (w {:op :branch-db :branch :mx}))
                          e (datahike.api/pull src [:seon.message/id :seon.message/from :seon.message/body] [:seon.message/id id])]
                      (w {:op :tx :branch :my :tx-data [e]})))))
      datoms-of (fn [db] (let [t (:max-tx db)] (count (filter #(= t (:tx %)) (datahike.api/datoms db :eavt)))))]
  {:A-main-write-ms (med a) :B-branch-write-plus-merge-ms (med b)
   :A-datoms-per-message (datoms-of (:db (w {:op :branch-db :branch :db})))})
(let [w (fn [req] (deref (seon.root/call! sys {:op :write :req req})))
      db (fn [] (:db (w {:op :branch-db :branch :mx})))
      t0 (:max-tx (db))
      _ (dotimes [i 10] (deref (seon.root/submit! sys :a1 (str "(def x" i " (range " i "))"))))
      d1 (db)
      ds (filter #(> (:tx %) t0) (datahike.api/datoms d1 :eavt))
      txs (frequencies (map :tx ds))]
  {:evals 10 :branch-commits (count txs) :datoms (count ds) :datoms-per-commit (seon.lib/r2 (/ (count ds) (max 1 (count txs))))
   :message-datoms (count (filter #(= "seon.message" (namespace (:a %))) ds))})
