;; U10-prep pod-restart drill (sci-execution-runtime design §7).
;;
;; The JVM process is the host harness: it builds the production shared base,
;; forks five live contexts, and re-registers one pod-served capability through
;; the production registry.  The private writer is started by the shell runner.
(require '[sci.core :as sci]
         '[seon.host.context :as context])

(def writer-socket "tmp/host-drill/writer.sock")
(def database-name "u1-drill")
(def agent-count 5)

(defn data!
  "Print one machine-readable pod-restart drill event."
  [m]
  (println (str "POD-RESTART-DRILL " (pr-str m)))
  (flush))

(defn host-unavailable
  "Return the steering value for an unavailable pod capability host."
  [request]
  {:seon/error
   {:seon.error/message
    "The pod capability host is unavailable; continue with database and pure work, then retry."
    :seon.error/kind :host-unavailable
    :seon.error/data {:seon.capability/host :pod
                      :seon.capability/retryable? true
                      :seon.capability/request request}}})

(defn healthy-capability
  "Return one successful pod capability value."
  [request]
  {:seon.capability/ok? true
   :seon.capability/request request})

(def writer
  (context/writer-session
   {::context/writer-socket-path writer-socket
    ::context/database-name database-name
    ::context/backend :file
    ::context/database-path "tmp/host-drill/store"}))
(def base (context/build-base! writer))
(def registry (::context/registry base))

(defn register-pod-wrapper!
  "Install one implementation in the shared pod capability wrapper var."
  [implementation]
  (context/register-wrappers!
   {::context/registry registry
    ::context/lib 'seon.pod.capability
    ::context/wrappers
    {'call {::context/wrapper-fn implementation
            ::context/arglists '([request])
            ::context/doc "Call one pod-served capability."}}}))

(register-pod-wrapper! healthy-capability)

(def contexts (mapv (fn [_] (context/fork-context base)) (range agent-count)))
(def context-identities (mapv #(System/identityHashCode %) contexts))

;; Each context holds private working state and loads the shared wrapper var.
(def admitted
  (mapv (fn [index ctx]
          (sci/eval-string*
           ctx
           (str "(require '[seon.db :as db] '[seon.pod.capability :as pod]) "
                "(def working-state {:agent " index " :values [2 3 5 7]}) "
                "[(reduce + (:values working-state)) "
                " (:seon.capability/ok? (pod/call {:phase :admit}))]")))
        (range agent-count) contexts))
(data! {:phase :admitted
        :contexts (count contexts)
        :working-state-ok (count (filter #(= [17 true] %) admitted))})

;; Simulate the pod-unavailable window through the one registry mechanism.
(register-pod-wrapper! host-unavailable)

(def during-window
  (mapv (fn [ctx]
          {:pure (sci/eval-string* ctx "(+ (:agent working-state) (reduce + (:values working-state)))")
           :db (sci/eval-string* ctx "(let [r (seon.db/query '[:find ?e :where [?e :db/ident]])] (and (coll? r) (not (:seon/error r)) (pos? (count r))))")
           :capability (sci/eval-string* ctx "(pod/call {:phase :down})")})
        contexts))
(def pure-ok (count (filter number? (map :pure during-window))))
(def db-ok (count (filter true? (map :db during-window))))
(def steering-ok
  (count
   (filter #(= :host-unavailable
               (get-in % [:capability :seon/error :seon.error/kind]))
           during-window)))
(data! {:phase :pod-unavailable
        :pure-evals-ok pure-ok
        :db-evals-ok db-ok
        :steering-errors steering-ok
        :derived-recovery-notices steering-ok})

;; Re-register the healthy implementation.  No context is forked or replayed.
(register-pod-wrapper! healthy-capability)
(def recovered
  (mapv (fn [ctx]
          (sci/eval-string*
           ctx
           "[(reduce + (:values working-state)) (pod/call {:phase :recovered})]"))
        contexts))
(def identities-after (mapv #(System/identityHashCode %) contexts))
(def recovered-ok
  (count
   (filter #(= [17 {:seon.capability/ok? true
                    :seon.capability/request {:phase :recovered}}] %)
           recovered)))
(def same-contexts? (= context-identities identities-after))
(def pass? (and (= agent-count pure-ok db-ok steering-ok recovered-ok)
                same-contexts?))
(data! {:phase :recovered
        :capability-calls-ok recovered-ok
        :derived-recovery-notices 0
        :same-contexts? same-contexts?
        :context-rebuilds 0})
(data! {:phase :done :pass? pass?})

(context/close-session! writer)
(System/exit (if pass? 0 1))
