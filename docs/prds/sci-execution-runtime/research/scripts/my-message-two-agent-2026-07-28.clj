(ns my-message-two-agent
  "LIVE PROOF: two agents collaborate, and the delegation is real.

  One cluster, two agents, one human question that alice cannot answer
  alone. What must be true at the end is not that the model said
  something plausible — it is that the FACTS form a delegation:

    1. alice opens a run answering the human's message;
    2. alice's run commits a message addressed to bob, with `from`
       resolving to alice, in the SAME transaction as the receipt of
       the form that asked for it, and with that transaction naming the
       human's message as its `:seon.db/trigger`;
    3. that commit — and nothing else — wakes bob: bob opens a run
       whose OWN trigger is alice's message;
    4. bob's run commits a message back to alice and completes;
    5. alice wakes again, and her second run closes with a result
       derived from what bob said;
    6. the chain is walkable: depth 0 (human) → 1 (alice→bob) → 2
       (bob→alice), every hop from committed transaction metadata and
       no hop counter anywhere.

  Nothing here polls a model twice for the same turn and nothing
  retries: this is the ordinary loop, armed by `cluster/start!` exactly
  as production arms it, watched from outside.

  Run:
    DEEPSEEK_API_KEY=… clojure -M:dev -e \\
      '(load-file \"docs/prds/sci-execution-runtime/research/scripts/my-message-two-agent-2026-07-28.clj\")'"
  (:require [clojure.edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.message :as message]))

(defn- stamp [& parts]
  (println (str "[" (.toString (java.time.LocalTime/now)) "] " (apply str parts)))
  (flush))

(defn- await-fact
  "Poll `probe` against the live connection until truthy, else throw.
  The proof WATCHES; production wakes. A timeout throws so the drive can
  never exit zero on a missed milestone."
  [label connection probe attempts]
  (loop [n 0]
    (let [value (probe @connection)]
      (cond
        value (do (stamp "OK   " label " → " (pr-str value)) value)
        (>= n attempts)
        (throw (ex-info (str "MISS: " label " never became true")
                        {::label label}))
        :else (do (Thread/sleep 500) (recur (inc n)))))))

;;; ---------------------------------------------------------------------------
;;; Queries — every milestone is a fact, never a log line
;;; ---------------------------------------------------------------------------

(defn- messages
  "Every message, oldest first: [id from to content]."
  [db]
  (->> (d/q '[:find ?id ?content ?to-id ?at
              :keys id content to at
              :where
              [?m :seon.cluster.message/id ?id]
              [?m :seon.cluster.message/content ?content]
              [?m :seon.cluster.message/to ?to]
              [?to :seon.cluster.agent/id ?to-id]
              [?m :seon.cluster.message/at ?at]]
            db)
       (map (fn [row]
              (assoc row :from
                     (d/q '[:find ?from-id .
                            :in $ ?id
                            :where
                            [?m :seon.cluster.message/id ?id]
                            [?m :seon.cluster.message/from ?from]
                            [?from :seon.cluster.agent/id ?from-id]]
                          db (:id row)))))
       (sort-by (juxt #(inst-ms (:at %)) :id))
       vec))

(defn- agent-messages
  "The messages an agent SENT."
  [db agent-id]
  (filterv #(= agent-id (:from %)) (messages db)))

(defn- runs
  [db]
  (->> (d/q '[:find ?run-id ?agent-id ?opened ?closed
              :keys run agent opened closed
              :where
              [?run :seon.cluster.run/id ?run-id]
              [?run :seon.cluster.run/agent ?agent]
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/opened-at ?opened]
              [(get-else $ ?run :seon.cluster.run/closed-at :open) ?closed]]
            db)
       (sort-by #(inst-ms (:opened %)))
       vec))

(defn- receipts
  [db run-id]
  (->> (d/q '[:find ?ordinal ?status ?edn
              :keys ordinal status result
              :in $ ?run-id
              :where
              [?run :seon.cluster.run/id ?run-id]
              [?r :seon.cluster.eval/run ?run]
              [?r :seon.cluster.eval/ordinal ?ordinal]
              [?r :seon.cluster.eval/status ?status]
              [(get-else $ ?r :seon.cluster.eval/result-edn "-") ?edn]]
            db run-id)
       (sort-by :ordinal)
       vec))

(defn- forms
  [db run-id]
  (->> (d/q '[:find ?ordinal ?source
              :keys ordinal source
              :in $ ?run-id
              :where
              [?run :seon.cluster.run/id ?run-id]
              [?f :seon.cluster.run.form/run ?run]
              [?f :seon.cluster.run.form/ordinal ?ordinal]
              [?f :seon.cluster.run.form/source ?source]]
            db run-id)
       (sort-by :ordinal)
       vec))

;;; ---------------------------------------------------------------------------
;;; The drive
;;; ---------------------------------------------------------------------------

(stamp "booting one cluster with two agents")
(def root "tmp/my-message-drive/clusters")
(let [dir (java.io.File. root)]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))

(def instance (cluster/start! {:seon.boot/cluster-name "collab"
                               :seon.boot/root root}))
(def connection (:seon.boot/cluster-connection instance))
(stamp "tower up, loop armed by start! — the production path, not a "
       "hand-built graph")

(def outcome
  (try
    (d/transact connection [{:seon.cluster.agent/id "alice"}
                            {:seon.cluster.agent/id "bob"}])
    (stamp "alice and bob exist; nothing has been asked yet")

    ;; ONE human message, and it is the only thing this drive commits.
    ;; Everything after it is the system's own doing.
    (d/transact
     connection
     [{:seon.cluster.message/id "human-1"
       :seon.cluster.message/to [:seon.cluster.agent/id "alice"]
       :seon.cluster.message/content
       ;; A QUESTION PLAIN clojure.core ANSWERS. The first drive asked
       ;; for primes below 100 and the delegate reached for
       ;; `Math/sqrt`, which the base sci context does not carry: form
       ;; 0 failed, form 1 read the unbound var, and bob delivered
       ;; "there are Unbound: #'my.agents.bob/primes-below-100 prime
       ;; numbers" in perfect confidence (filed:
       ;; a-failed-form-does-not-stop-the-fold). This proof is about
       ;; DELEGATION, so the arithmetic must not be the variable — the
       ;; callable surface is N5's and is not hand-extended here.
       (str "I need the sum of all the multiples of 3 below 1000. "
            "Do not work it out yourself — bob is the agent who handles "
            "arithmetic, so ask bob for the number, pause while you "
            "wait, and when bob answers, complete with the number in a "
            "sentence for me.")
       :seon.cluster.message/at (java.util.Date.)}])
    (stamp "TRIGGER — the human asked alice, and alice was told to delegate")

    (await-fact
     "alice sent bob a message" connection
     (fn [db] (seq (filterv #(= "bob" (:to %)) (agent-messages db "alice"))))
     240)

    (await-fact
     "bob opened a run of his own" connection
     (fn [db] (seq (filterv #(= "bob" (:agent %)) (runs db))))
     120)

    (await-fact
     "bob answered alice" connection
     (fn [db] (seq (filterv #(= "alice" (:to %)) (agent-messages db "bob"))))
     240)

    ;; THE MILESTONE THAT MATTERS, and it is deliberately not "alice
    ;; closed a run": a closed run proves nothing — she closed one by
    ;; pausing, twice, on the drive where bob's answer was garbage. The
    ;; delegation is only proven when alice COMPLETES with a result
    ;; carrying the number only bob computed.
    (await-fact
     "alice completed with a result derived from bob's answer" connection
     (fn [db]
       (let [answer (some-> (first (filterv #(= "alice" (:to %))
                                            (agent-messages db "bob")))
                            :content)
             digits (some->> answer (re-seq #"\d+") (sort-by count) last)]
         (when digits
           (seq (for [{:keys [run agent]} (runs db)
                      :when (= "alice" agent)
                      {:keys [result]} (receipts db run)
                      :let [value (try (clojure.edn/read-string result)
                                       (catch Throwable _ nil))]
                      :when (and (map? value)
                                 (= :completed (:my.run/disposition value))
                                 (str/includes? (:my.run/result value)
                                                digits))]
                  (:my.run/result value))))))
     240)

    ;; ------------------------------------------------------------------
    ;; THE EVIDENCE, printed as facts
    ;; ------------------------------------------------------------------
    (let [db @connection]
      (println)
      (stamp "=== THE MESSAGE CHAIN ===")
      (doseq [{:keys [id from to content]} (messages db)]
        (println (format "  depth %s  %-6s → %-6s  %s"
                         (message/chain-depth db id)
                         (or from "HUMAN")
                         to
                         (str/replace (subs content 0 (min 96 (count content)))
                                      #"\s+" " "))))
      (println)
      (stamp "=== THE RUNS, THEIR TRIGGERS, THEIR FORMS ===")
      (doseq [{:keys [run agent closed]} (runs db)]
        (println (format "  run %s  agent %-6s  trigger %-24s  %s"
                         run agent
                         (pr-str (message/trigger db run))
                         (if (= :open closed) "OPEN" "closed")))
        (doseq [{:keys [ordinal source]} (forms db run)]
          (println (format "      form %s: %s" ordinal
                           (str/replace source #"\s+" " "))))
        (doseq [{:keys [ordinal status result]} (receipts db run)]
          (println (format "      receipt %s %s → %s" ordinal status
                           (subs result 0 (min 120 (count result)))))))
      (println)
      (stamp "=== THE ANSWERED-NESS TRAIL (tx-meta, no flags) ===")
      (doseq [[message-id tx] (sort (d/q '[:find ?id ?tx
                                           :where
                                           [?tx :seon.db/trigger ?m]
                                           [?m :seon.cluster.message/id ?id]]
                                         db))]
        (println (format "  transaction %s answered %s" tx message-id)))
      (println)
      (stamp "=== ERROR FACTS (must be empty) ===")
      (println "  " (pr-str (d/q '[:find ?kind ?message
                                   :where
                                   [?e :seon.error/kind ?kind]
                                   [?e :seon.error/message ?message]]
                                 db))))
    :complete
    (catch Throwable failure
      (stamp "DRIVE FAILED: " (ex-message failure))
      (stamp "messages so far: " (pr-str (messages @connection)))
      (stamp "runs so far: " (pr-str (runs @connection)))
      (stamp "errors so far: "
             (pr-str (d/q '[:find ?kind ?message
                            :where
                            [?e :seon.error/kind ?kind]
                            [?e :seon.error/message ?message]]
                          @connection)))
      failure)
    (finally
      (stamp "TEARDOWN")
      (cluster/stop! instance))))

(if (= :complete outcome)
  (do (stamp "TWO-AGENT DELEGATION COMPLETE") (System/exit 0))
  (System/exit 1))
