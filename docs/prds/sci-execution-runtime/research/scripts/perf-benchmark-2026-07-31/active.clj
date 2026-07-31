;; Section 3b — ACTIVE agents: M agents driving real turns concurrently against
;; the sanctioned local Ollama server (qwen3.5:35b-a3b-coding-nvfp4). This is a
;; REAL provider, not a stub. A local 35B model on one Mac is the bottleneck by
;; construction, so these numbers measure the SYSTEM's fairness and dispatch,
;; not throughput anyone should quote as a product capability.
(require '[bench.support :as s] '[seon.cluster :as cluster] '[seon.cluster.agent :as agent]
         '[seon.cluster.store :as store] '[seon.cluster.message :as message]
         '[datahike.api :as d] '[clojure.core.async.flow :as flow])
(def instances @@(ns-resolve 'seon.cluster 'running-instances))
(def inst (get instances "scale-10"))
(def conn (:seon.boot/cluster-connection inst))
(def routing (:seon.cluster.agent/routing inst))
(def handle (:seon.cluster.loop/cluster inst))
(println :ARMED-AT-START (sort (keys (:seon.cluster.agent/armed @routing))))
(defn create! [n]
  (store/transact! conn
    (into [] (mapcat (fn [i] (agent/creation-tx {:seon.cluster.agent/id (str "active-" i)
                                                 :seon.ns/name (symbol (str "my.agents.active" i))
                                                 :seon.cluster/name "scale-10"})))
          (range n))))
(println :CREATE (pr-str (select-keys (create! 10) [:seon.error/kind])))
(Thread/sleep 3000)
(println :ARMED-AFTER (count (:seon.cluster.agent/armed @routing)))
(defn message! [agent-id token]
  (let [eid (d/q '[:find ?e . :in $ ?id :where [?e :seon.cluster.agent/id ?id]] @conn agent-id)]
    (store/transact! conn [{:seon.cluster.message/id (str "drive-" agent-id "-" token)
                            :seon.cluster.message/to eid
                            :seon.cluster.message/content
                            "Evaluate (+ 1 2) and then call (my.run/complete {:my.run/disposition :completed})."
                            :seon.cluster.message/at (java.util.Date.)}])))
(defn runs-for [ids]
  (into {} (map (fn [id]
                  [id (d/q '[:find ?opened ?closed
                             :in $ ?id
                             :where [?a :seon.cluster.agent/id ?id]
                             [?r :seon.cluster.run/agent ?a]
                             [?r :seon.cluster.run/opened-at ?opened]
                             [(get-else $ ?r :seon.cluster.run/closed-at false) ?closed]]
                           @conn id)]))
        ids))
(defn drive! [m label]
  (let [ids (mapv (fn [i] (str "active-" i)) (range m))
        token (str (long (rand 1e9)))
        t0 (System/nanoTime)
        _ (doseq [id ids] (message! id token))
        sent (System/nanoTime)
        deadline (+ sent (* 400 1000000000))]
    ;; wait until every agent has an opened run, recording first-open time
    (let [opened (atom {})]
      (loop []
        (doseq [id ids]
          (when-not (contains? @opened id)
            (when (seq (get (runs-for [id]) id))
              (swap! opened assoc id (System/nanoTime)))))
        (when (and (< (count @opened) m) (< (System/nanoTime) deadline))
          (Thread/sleep 50) (recur)))
      ;; then wait for every run to close
      (let [closed (atom {})]
        (loop []
          (let [current (runs-for ids)]
            (doseq [[id rows] current]
              (when (and (not (contains? @closed id))
                         (every? (fn [[_ c]] (inst? c)) rows)
                         (seq rows))
                (swap! closed assoc id (System/nanoTime))))
            (when (and (< (count @closed) m) (< (System/nanoTime) deadline))
              (Thread/sleep 200) (recur))))
        (println label :agents m
                 :message-commit-ms (/ (Math/round (/ (- sent t0) 1000.0)) 1000.0)
                 :wake->run-open (pr-str (s/quantiles (map (fn [[_ v]] (- v sent)) @opened)))
                 :run-settled (pr-str (s/quantiles (map (fn [[_ v]] (- v sent)) @closed)))
                 :opened (count @opened) :closed (count @closed)
                 :wall-s (/ (Math/round (/ (- (System/nanoTime) sent) 1e7)) 100.0)
                 :turns-per-min (/ (Math/round (* 100.0 (/ (* 60e9 (count @closed))
                                                           (- (System/nanoTime) sent)))) 100.0))))))
(drive! 2 :ACTIVE-2)
(drive! 5 :ACTIVE-5)
(drive! 10 :ACTIVE-10)
(println :MEM (pr-str (s/memory)))
(println :ACTIVE-DONE)
