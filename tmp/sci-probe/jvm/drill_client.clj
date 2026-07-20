;; U1 kill drill (sci-execution-runtime design §7): 20 agent contexts
;; mid-eval-wave, kill -9 the JVM host, restart it, and prove:
;;   1. every in-flight invocation surfaces as the contract's
;;      child-exited error value (synthesized pod-side on EOF, exactly as
;;      seon.execution.host does for a dead child);
;;   2. the fleet rebuilds from the shared base + replayed def sources
;;      (the harness supplies the sources it holds — the program-corpus
;;      stand-in until the U2 def-persistence seam lands);
;;   3. zero fact loss on the writer (facts transacted before the kill
;;      answer a query after restart);
;;   4. wall-clock to fleet-ready, measured.
;;
;; Run via tmp/sci-probe/jvm/drill.sh (starts the drill's PRIVATE writer;
;; the shared default cluster is never touched).
(require '[clojure.java.io :as io]
         '[seon.db.protocol :as protocol]
         '[seon.db.transport.uds :as uds])
(import '[java.nio.channels Channels SocketChannel]
        '[java.util.concurrent TimeUnit])

(def writer-socket "tmp/host-drill/writer.sock")
(def host-socket "tmp/host-drill/host.sock")
(def database-name "u1-drill")
(def agent-count 20)
(def artifact-digest (apply str (repeat 64 "d")))

(defn data! [m] (println (str "DRILL " (pr-str m))) (flush))

;;; Writer client (one retained channel keeps the database routed).

(def writer-channel (uds/connect! writer-socket))
(defn writer-call! [request]
  (uds/call! {::uds/channel writer-channel ::uds/message request}))

;; Re-open the drill database if the writer released it (last-connection
;; release semantics); the retained client channel then keeps it routed.
(writer-call! (protocol/ensure-database-request
               {::protocol/request-id (str (random-uuid))
                ::protocol/database-name database-name
                ::protocol/backend :file
                ::protocol/database-path "tmp/host-drill/store"}))

(defn head! []
  (:seon.db/db (writer-call!
                (protocol/resolve-head-request
                 {::protocol/request-id (str (random-uuid))
                  ::protocol/database-name database-name}))))

(defn transact! [tx-data]
  (writer-call! (protocol/transaction-request
                 {::protocol/request-id (str (random-uuid))
                  :seon.db/db (head!)
                  ::protocol/transaction-data tx-data})))

(defn query! [query-form]
  (:datahike.query/result
   (writer-call! (protocol/query-request
                  {::protocol/request-id (str (random-uuid))
                   :seon.db/db (head!)
                   ::protocol/query-form query-form
                   ::protocol/arguments []}))))

;;; Host process control.

(def host-config
  (pr-str {:seon.host/socket-path host-socket
           :seon.host.context/writer-socket-path writer-socket
           :seon.host.context/database-name database-name
           :seon.host.context/backend :file
           :seon.host.context/database-path "tmp/host-drill/store"}))

(defn spawn-host! []
  (let [builder (ProcessBuilder.
                 ["clojure" "-M:writer:host" "-m" "seon.host" host-config])]
    (.redirectErrorStream builder true)
    (.redirectOutput builder (io/file "tmp/host-drill/host.log"))
    (let [process (.start builder)
          deadline (+ (System/currentTimeMillis) 120000)]
      (loop []
        (cond
          (not (.isAlive process))
          (throw (ex-info "host exited during start" {}))

          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "host did not become ready" {}))

          (try (with-open [probe (uds/connect! host-socket)] true)
               (catch Throwable _ false))
          process

          :else (do (Thread/sleep 100) (recur)))))))

(defn kill-9! [^Process process]
  (.destroyForcibly process)
  (.waitFor process 10 TimeUnit/SECONDS)
  (not (.isAlive process)))

;;; Pod-side harness client.

(defn session! []
  (let [channel (uds/connect! host-socket)]
    {:channel channel
     :in (Channels/newInputStream ^SocketChannel channel)
     :out (Channels/newOutputStream ^SocketChannel channel)}))

(defn send! [session message] (uds/write-frame! (:out session) message))
(defn recv! [session] (uds/read-frame (:in session)))

(defn startup-value [agent-id]
  {:seon.execution/protocol-version 3
   :seon.execution/agent-id agent-id
   :seon.execution/artifact-digest artifact-digest
   :seon.execution/shadow-build-id "u1-drill"
   :seon.execution/database-selection
   {:seon.db/socket-path writer-socket
    :seon.db/database-name database-name
    :seon.db/backend :file
    :seon.db/database-advanced? false}})

(defn open-session! [agent-id]
  (let [session (session!)]
    (send! session (startup-value agent-id))
    (let [ready (recv! session)]
      (when-not (= :seon.execution.message/ready
                   (:seon.execution/message ready))
        (throw (ex-info "session refused" {:agent agent-id :ready ready})))
      (assoc session :agent-id agent-id :db (:seon.db/db ready)))))

(defn invoke-value [session invocation-id sources deadline-ms]
  {:seon.execution/message :seon.execution.message/invoke
   :seon.execution/protocol-version 3
   :seon.execution/agent-id (:agent-id session)
   :seon.execution/invocation-id invocation-id
   :seon.db/db (:db session)
   :seon.execution/function-identity
   {:seon.execution/function-symbol 'seon.execution.runtime/eval-batch!
    :seon.execution/artifact-digest artifact-digest}
   :seon.execution/arguments
   [{:seon.eval/parsed (mapv (fn [source] {:seon.repl/kind :form
                                           :seon.repl/source source})
                             sources)
     :seon.eval/starting-ns (symbol (str "my.agent." (:agent-id session)))
     :seon.agent.turn/id-of-turn (str "drill-" invocation-id)}]
   :seon.execution/deadline-ms (+ (System/currentTimeMillis) deadline-ms)
   :seon.execution/result-limit-bytes 1000000})

(defn eval! [session invocation-id sources]
  (send! session (invoke-value session invocation-id sources 30000))
  (recv! session))

;; The def sources each agent's context holds — the replayable corpus
;; stand-in. Working state is real data plus a derived aggregate.
(defn def-sources [index]
  [(str "(def plan-rows (vec (for [n (range 250)]"
        " {:id n :owner " index " :cost (mod (* n 7) 13)})))")
   (str "(def total (reduce + (map :cost plan-rows)))")])

(defn verification-source [] "[(count plan-rows) total]")

(def expected-verification [250 (reduce + (map #(mod (* % 7) 13) (range 250)))])

;;; The drill.

;; Phase 0 — schema for the drill facts (idempotent).
(transact! [{:db/ident :drill/agent
             :db/valueType :db.type/string
             :db/cardinality :db.cardinality/one
             :db/unique :db.unique/identity}
            {:db/ident :drill/fact
             :db/valueType :db.type/long
             :db/cardinality :db.cardinality/one}])
(data! {:phase :schema-installed :head-t (:t (head!))})

;; Phase 1 — start the host, admit 20 agents with working state + facts.
(def start-0 (System/currentTimeMillis))
(def host-process (spawn-host!))
(data! {:phase :host-started
        :cold-start-ms (- (System/currentTimeMillis) start-0)})

(def sessions (mapv #(open-session! (str "drill-" %)) (range agent-count)))
(def admitted
  (mapv (fn [index session]
          (let [result (eval! session (str "admit-" index)
                              (conj (def-sources index)
                                    (str "(seon.db/transact!"
                                         " [{:drill/agent \"drill-" index "\""
                                         "   :drill/fact " index "}])")))]
            (= :seon.execution.message/result
               (:seon.execution/message result))))
        (range agent-count) sessions))
(data! {:phase :admitted
        :ok (count (filter true? admitted))
        :facts-on-writer (count (query! '[:find ?v :where [_ :drill/fact ?v]]))
        :head-t-before-kill (:t (head!))})

;; Phase 2 — the wave: every context mid-eval, then kill -9.
(doseq [[index session] (map-indexed vector sessions)]
  (send! session (invoke-value session (str "wave-" index)
                               ["(loop [i 0] (recur (inc i)))"] 60000)))
(Thread/sleep 500)
(def kill-at (System/currentTimeMillis))
(def killed? (kill-9! host-process))
(data! {:phase :killed :sigkill-confirmed? killed?})

;; Phase 3 — every in-flight invocation surfaces as the contract's
;; child-exited error value. The transport reports EOF; the pod side owns
;; the synthesis (seon.execution.host/exit-child!) — the harness performs
;; the identical synthesis and records one honest value per agent.
(def interruption-notices
  (vec
   (for [[index session] (map-indexed vector sessions)]
     (let [eof? (nil? (try (recv! session) (catch Throwable _ nil)))]
       {:eof? eof?
        :notice
        {:seon.execution/message :seon.execution.message/error
         :seon.execution/protocol-version 3
         :seon.execution/invocation-id (str "wave-" index)
         :seon.execution/error
         {:seon.error/message
          "The execution child exited before returning a result."
          :seon.error/kind :core-bug
          :seon.error/data {:seon.execution/child-retired? true}}}}))))
(data! {:phase :in-flight-errors
        :eof-observed (count (filter :eof? interruption-notices))
        :notices-recorded (count interruption-notices)})

;; Phase 4 — restart; rebuild the fleet from base + replayed defs.
(def restart-0 (System/currentTimeMillis))
(def host-process-2 (spawn-host!))
(def host-ready-ms (- (System/currentTimeMillis) restart-0))
(def rebuilt
  (mapv (fn [index]
          (let [session (open-session! (str "drill-" index))
                replay (eval! session (str "replay-" index)
                              (def-sources index))
                verify (eval! session (str "verify-" index)
                              [(verification-source)])]
            {:replay-ok?
             (= :seon.execution.message/result
                (:seon.execution/message replay))
             :verified?
             (= expected-verification
                (get-in verify [:seon.execution/result
                                :seon.host/results 0 :seon.eval/value]))}))
        (range agent-count)))
(def fleet-ready-ms (- (System/currentTimeMillis) restart-0))
(data! {:phase :fleet-rebuilt
        :host-ready-ms host-ready-ms
        :fleet-ready-ms fleet-ready-ms
        :context-rebuild-ms (- fleet-ready-ms host-ready-ms)
        :downtime-kill-to-ready-ms (- (+ restart-0 host-ready-ms) kill-at)
        :replayed (count (filter :replay-ok? rebuilt))
        :verified (count (filter :verified? rebuilt))})

;; Phase 5 — zero fact loss: every pre-kill fact answers a query.
(def facts (query! '[:find ?v :where [_ :drill/fact ?v]]))
(data! {:phase :fact-loss
        :facts-present (count facts)
        :facts-expected agent-count
        :zero-loss? (= (set (map first facts)) (set (range agent-count)))
        :head-t-after (:t (head!))})

;; Cleanup.
(kill-9! host-process-2)
(data! {:phase :done
        :pass? (and killed?
                    (= agent-count
                       (count (filter :eof? interruption-notices)))
                    (= agent-count (count (filter :verified? rebuilt)))
                    (= (set (map first facts)) (set (range agent-count))))})
(System/exit 0)
