(ns seon.agent.driver.host
  "JVM claimant leaf: database interest, virtual-thread custody, and eval."
  (:require [my.blob :as blob]
            [my.blob.core :as blob.core]
            [my.blob.host :as blob.host]
            [seon.ai.core :as ai.core]
            [seon.agent.driver :as driver]
            [seon.agent.run.core :as run.core]
            [seon.agent.turn.core :as turn.core]
            [seon.agent.turn.llm :as turn.llm]
            [seon.content-hash :as content-hash]
            [seon.config.resolve :as config.resolve]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.eval.receipt :as eval.receipt]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host.instrument :as instrument]
            [seon.host.invoke :as invoke]
            [seon.host.session :as session]
            [seon.repl.parse :as repl.parse])
  (:import [java.nio.file Files Path]
           [java.util.concurrent Callable ExecutorService Future
            ScheduledExecutorService TimeUnit]))

(def ^:private artifact-digest
  "0000000000000000000000000000000000000000000000000000000000000000")

(defn- database-context [writer]
  {:seon.db.leaf/current-tx-context (constantly nil)
   :seon.db.leaf/current-agent-id (constantly nil)
   :seon.db.leaf/with-read-evidence (fn [f] (f))
   :seon.db.leaf/record-read-evidence! (fn [_] nil)
   :seon.db.leaf/with-agent (fn [_ f] (f))
   :seon.db.leaf/without-agent (fn [f] (f))
   :seon.db.leaf/with-tx-context (fn [_ f] (f))
   :seon.db.leaf/install-configuration-context! (fn [_] nil)
   :seon.db.leaf/schema-projection
   (fn []
     (let [projection
           (::context/projection @(::context/projection-state writer))]
       (when (seq (:seon.schema.projection/forms projection)) projection)))
   :seon.db.leaf/cache-schema-projection!
   (fn [projection]
     (reset! (::context/projection-state writer)
             {::context/projection projection}))
   :seon.db.leaf/schema-validation?
   (fn []
     (boolean
      (seq
       (:seon.schema.projection/forms
        (::context/projection @(::context/projection-state writer))))))})

(defn database-leaf
  "Portable database leaf over the host's retained writer pool."
  [writer]
  (db.host/leaf writer #(database-context writer)))

(defn claimant-capabilities
  "Derive claimant policy from installed platform leaves."
  [host]
  (cond-> #{:seon.agent.driver.capability/eval}
    (and (fn? (:seon.agent.driver/llm-transport! host))
         (map? (:seon.agent.driver/blob-leaf host)))
    (conj :seon.agent.driver.capability/llm)))

(defn- resolve-llm-context!
  [database agent-id]
  (let [agent-row
        (db/pull {::db/db database
                  ::db/pull-pattern (ai.core/agent-config-pull-pattern)
                  ::db/ref [:seon.agent/id agent-id]})
        config-row
        (db/decode-edn-values
         (db/pull {::db/db database
                   ::db/pull-pattern (ai.core/config-pull-pattern)
                   ::db/ref [:seon.config/id "cluster"]}))
        attempt-timeout-ms (:seon.ai/agent-attempt-timeout-ms agent-row)]
    (if-not (pos-int? attempt-timeout-ms)
      {:seon.error/message
       "The acquired agent has no positive durable LLM attempt timeout."
       :seon.error/kind :configuration}
      {:seon.ai/config-resolution
       (ai.core/resolved-config-from-rows
        ai.core/shipped-defaults config-row agent-row attempt-timeout-ms)
       :seon.config.llm-retry/maximum-wait-ms
       (:seon.config.llm-retry/maximum-wait-ms config-row)
       :seon.config.llm-retry/maximum-total-wait-ms
       (:seon.config.llm-retry/maximum-total-wait-ms config-row)
       :seon.config.llm-retry/default-retries
       (:seon.config.llm-retry/default-retries config-row)
       :seon.config/repl-mode
       (or (:seon.config/repl-mode agent-row)
           (:seon.config/repl-mode config-row)
           :batch)})))

(defn- bounded-llm-transport!
  [host request]
  (let [timeout-ms (:seon.ai/request-timeout-ms request)
        interrupted-by-deadline? (atom false)
        driver-thread (Thread/currentThread)
        task
        (.schedule
         ^ScheduledExecutorService (:seon.host/watchdog host)
         ^Runnable
         (fn []
           (reset! interrupted-by-deadline? true)
           (.interrupt driver-thread))
         (long timeout-ms) TimeUnit/MILLISECONDS)]
    (try
      ((:seon.agent.driver/llm-transport! host) request)
      (catch InterruptedException interrupted
        (if @interrupted-by-deadline?
          (do
            (Thread/interrupted)
            {:seon.ai/error
             {:seon.ai/msg
              (str "LLM attempt exceeded the claimant deadline ("
                   timeout-ms "ms).")
              :seon.ai/timeout? true
              :seon.ai/outer-timeout? true}})
          (throw interrupted)))
      (finally
        (.cancel task false)))))

(defn- ensure-context! [host agent-id]
  (or (get @(:seon.host/contexts host) agent-id)
      (let [writer (:seon.host/writer host)
            created (context/fork-context (:seon.host/base host))]
        (binding [context/*agent-id* agent-id]
          (context/restore-context-defs!
           writer created (host.eval/agent-home-ns agent-id)))
        (context/install-registered-wrappers!
         {::context/registry (get-in host [:seon.host/base
                                          ::context/registry])
          ::context/ctx created
          ::context/lib (host.eval/agent-home-ns agent-id)})
        (instrument/reconcile-current-context!
         (::instrument/state host) created)
        (get (swap! (:seon.host/contexts host)
                    #(if (contains? % agent-id) % (assoc % agent-id created)))
             agent-id))))

(defn- driver-session [host agent-id]
  {::session/startup
   (atom {:seon.execution/agent-id agent-id
          :seon.execution/artifact-digest artifact-digest})
   ::session/active (atom nil)
   ::session/active-run (atom nil)
   ::session/cancel-requested? (atom false)
   ::session/interrupt-lock (Object.)
   ::session/interrupt-fired? (atom false)
   ::session/worker-phase (atom :idle)
   ::session/live-values (atom {::session/order [] ::session/values {}})
   ::session/contexts (:seon.host/contexts host)
   ::session/writer (:seon.host/writer host)
   ::session/projection-state (:seon.host/projection-state host)
   ::session/eval-pool (:seon.host/eval-pool host)
   ::session/watchdog (:seon.host/watchdog host)
   ::instrument/state (::instrument/state host)
   ::session/ctx (ensure-context! host agent-id)})

(defn- blob-path [directory hash]
  (let [[shard filename] (blob.core/blob-path-parts hash)]
    (.resolve (.resolve (Path/of ^String directory (make-array String 0))
                       ^String shard)
              ^String filename)))

(defn- read-blob
  [{:my.blob/keys [writable-dir read-only-dirs]} hash]
  (if-not (blob.core/valid-hash? hash)
    (blob.core/bad-hash hash)
    (if-let [path
             (some (fn [directory]
                     (let [candidate (blob-path directory hash)]
                       (when (Files/exists candidate
                                           (make-array java.nio.file.LinkOption 0))
                         candidate)))
                   (cons writable-dir read-only-dirs))]
      (let [content (Files/readString path)
            actual (content-hash/sha-256 content)]
        (if (= hash actual)
          {:my.blob/ok? true
           :my.blob/hash hash
           :my.blob/content content}
          {:my.blob/ok? false
           :my.blob/hash hash
           :my.blob/actual-digest actual
           :my.blob/error "The reply blob failed its content-address check."}))
      (blob.core/not-found hash))))

(defn- current-turn! [database turn-id]
  (db/pull
   {::db/db database
    ::db/pull-pattern
    [:seon.agent.turn/id :seon.agent.turn/phase :seon.agent.turn/status
     {:seon.agent.turn/reply-blob [:my.blob/hash]}
     {:seon.agent.turn/evals
      [:seon.eval/id :seon.eval/status :seon.eval/ok?]}]
    ::db/ref [:seon.agent.turn/id turn-id]}))

(defn- reply-program [storage-view turn agent-id]
  (let [hash (get-in turn [:seon.agent.turn/reply-blob :my.blob/hash])
        reply (read-blob storage-view hash)]
    (if-not (:my.blob/ok? reply)
      reply
      (turn.core/reply-program
       (:my.blob/content reply) false (host.eval/agent-home-ns agent-id)))))

(defn- invocation
  [agent-id database run-id claim-epoch turn-id program configuration]
  {:seon.execution/invocation-id (str (random-uuid))
   :seon.execution/agent-id agent-id
   :seon.db/db database
   :seon.execution/function-identity
   {:seon.execution/function-symbol 'seon.execution.runtime/eval-batch!
    :seon.execution/artifact-digest artifact-digest}
   :seon.execution/arguments
   [{:seon.eval/parsed (:seon.repl/eval-entries program)
     :seon.eval/starting-ns (host.eval/agent-home-ns agent-id)
     :seon.agent.turn/id-of-turn turn-id
     :seon.agent.run/id-of-run run-id}]
   :seon.execution/deadline-ms
   (+ (System/currentTimeMillis)
      (:seon.config.claim-driver/invocation-deadline-ms configuration))
   :seon.execution/result-limit-bytes
   (:seon.config.claim-driver/invocation-result-maximum-bytes configuration)
   :seon.execution/run-fence
   {:seon.agent.run/id run-id
    :seon.agent.run/claim-epoch claim-epoch}})

(defn- invocation-configuration! [database]
  (let [singleton
        (db/pull
         {::db/db database
          ::db/pull-pattern
          (into [:seon.config/id] config.resolve/claim-driver-attributes)
          ::db/ref [:seon.config/id :seon.config/singleton]})
        configuration
        (config.resolve/claim-driver-configuration singleton)
        missing
        (some #(when-not (pos-int? (get configuration %)) %)
              [:seon.config.claim-driver/invocation-deadline-ms
               :seon.config.claim-driver/invocation-result-maximum-bytes])]
    (if missing
      {:seon.error/message
       (str "The JVM claimant limit " missing
            " is unavailable; explicitly apply the governing config.")
       :seon.error/kind :core-bug
       :seon.error/data {:seon.config/key missing}}
      configuration)))

(defn- run-eval-batch!
  [host storage-view run claim-epoch database]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        turn (:seon.agent.run/current-turn run)
        turn-id (:seon.agent.turn/id turn)
        fence (run.core/run-fence agent-id run-id claim-epoch)
        program (reply-program storage-view turn agent-id)
        invocation-configuration (invocation-configuration! database)]
    (cond
      (:my.blob/error program)
      {:seon.error/message (:my.blob/error program)
       :seon.error/kind :core-bug}

      (:seon.error/message invocation-configuration)
      invocation-configuration

      :else
      (let [host-session (driver-session host agent-id)
            task
            (.submit
             ^ExecutorService (:seon.host/eval-pool host)
             ^Callable
             (fn []
               (invoke/execute-invocation!
               host-session
                (invocation agent-id database run-id claim-epoch
                            turn-id program invocation-configuration))))
            batch (.get ^Future task)
            executable-count
            (count (filter #(contains? #{:form :read}
                                       (:seon.repl/kind %))
                           (:seon.repl/eval-entries program)))
            attempted (+ (or (:seon.eval/n-ok batch) 0)
                         (or (:seon.eval/n-fail batch) 0))]
        (if (and (pos? executable-count) (zero? attempted))
          {:seon.error/message
           "The JVM execution tier dropped an executable batch without recording a receipt."
           :seon.error/kind :core-bug
           :seon.error/data
           {:seon.agent/id agent-id
            :seon.agent.turn/id turn-id
            :seon.eval/executable-count executable-count}}
          (let [head (context/resolve-head! (:seon.host/writer host))
                terminal
                (db/transact!
                 {::db/db head
                  ::db/tx-data
                  (turn.core/advance-phase-tx-data
                   fence turn-id :evaling :evaled [])})]
            (if (:seon.error/message terminal)
              terminal
              {:seon.db/db (:db-after terminal)
               :seon.agent.driver/eval-batch batch
               :seon.agent.driver/program program})))))))

(defn- eval-step!
  [host storage-view
   {:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        turn-id (get-in run [:seon.agent.run/current-turn
                             :seon.agent.turn/id])
        fence (run.core/run-fence agent-id run-id claim-epoch)
        phase-report
        (db/transact!
         {::db/db database
          ::db/tx-data
          (turn.core/advance-phase-tx-data
           fence turn-id :reply-ready :evaling [])})]
    (if (:seon.error/message phase-report)
      phase-report
      (run-eval-batch!
       host storage-view run claim-epoch (:db-after phase-report)))))

(defn- settle-eval-step!
  [host storage-view
   {:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        turn-id (get-in run [:seon.agent.run/current-turn
                             :seon.agent.turn/id])
        turn (current-turn! database turn-id)
        running (filter #(= :running (:seon.eval/status %))
                        (:seon.agent.turn/evals turn))
        fence (run.core/run-fence agent-id run-id claim-epoch)
        receipts (:seon.agent.turn/evals turn)]
    (if (empty? receipts)
      ;; The claimant died after committing :evaling but before the first
      ;; receipt. No form was admitted, so replaying the batch is exact.
      (run-eval-batch! host storage-view run claim-epoch database)
      (let [tx-data
            (into
             fence
             (concat
              [(turn.core/phase-fence turn-id :evaling)]
              (mapcat
               (fn [receipt]
                 (eval.receipt/terminal-tx-data
                  {:seon.eval/id (:seon.eval/id receipt)
                   :seon.eval/status :interrupted}))
               running)
              [[:db.fn/cas [:seon.agent.turn/id turn-id]
                :seon.agent.turn/phase :evaling :evaled]]))
            report (db/transact!
                    {::db/db database
                     ::db/tx-data tx-data})]
        (if (:seon.error/message report)
          report
          {:seon.db/db (:db-after report)})))))

(defn- execute-step! [host storage-view claim]
  (case (:seon.agent.driver/step claim)
    :open-attempt
    (binding [blob/*leaf* (:seon.agent.driver/blob-leaf host)]
      (let [result
            (turn.llm/llm-phase!
             (assoc claim
                    :seon.agent.turn/now! #(java.util.Date.)
                    :seon.agent.turn/resolve-context!
                    (fn [agent-id database _run-id]
                      (resolve-llm-context! database agent-id))
                    :seon.agent.turn/transport!
                    #(bounded-llm-transport! host %)))]
        (or (:seon.retry/result result) result)))

    :settle-attempt
    (binding [blob/*leaf* (:seon.agent.driver/blob-leaf host)]
      (let [result
            (turn.llm/llm-phase!
             (assoc claim
                    :seon.agent.turn/now! #(java.util.Date.)
                    :seon.agent.turn/resolve-context!
                    (fn [agent-id database _run-id]
                      (resolve-llm-context! database agent-id))
                    :seon.agent.turn/transport!
                    #(bounded-llm-transport! host %)))]
        (or (:seon.retry/result result) result)))

    :eval (eval-step! host storage-view claim)
    :settle-eval (settle-eval-step! host storage-view claim)
    {:seon.error/message "The JVM claimant was assigned an ineligible phase."
     :seon.error/kind :core-bug}))

(defn start!
  "Start the interest-driven JVM claimant and return its stop handle."
  [host storage-view]
  (let [writer (:seon.host/writer host)
        database-leaf (database-leaf writer)
        database-functions (db/bind-leaf database-leaf)
        blob-leaf
        (blob.host/services
         {::blob.host/current-db! (get database-functions 'db)
          ::blob.host/query! (get database-functions 'query)
          ::blob.host/transact! (get database-functions 'transact!)})
        _ ((:my.blob/configure-storage-view! blob-leaf) storage-view)
        host (assoc host :seon.agent.driver/blob-leaf blob-leaf)
        handles (atom {})
        leaf-holder (atom nil)
        dispatch!
        (fn [request]
          (let [run-id (:seon.agent.run/id request)
                agent-id (:seon.agent/id request)
                thread
                (.unstarted
                 (.name (Thread/ofVirtual)
                        (str "seon-driver-" agent-id "-" run-id))
                 ^Runnable
                 (bound-fn []
                   (try
                     (driver/call-with-leaf
                      @leaf-holder database-leaf
                     #(driver/drive-run! request))
                     (finally
                       (let [current (Thread/currentThread)]
                         (swap! handles
                                #(if (identical? (get % run-id) current)
                                   (dissoc % run-id)
                                   %)))))))
                installed? (atom false)]
            (swap! handles
                   #(if (contains? % run-id)
                      %
                      (do (reset! installed? true)
                          (assoc % run-id thread))))
            (when @installed? (.start thread))
            run-id))
        platform-leaf
        {:seon.agent.driver/capabilities
         (claimant-capabilities host)
         :seon.agent.driver/now #(java.util.Date.)
         :seon.agent.driver/dispatch-run! dispatch!
         :seon.agent.driver/execute-step!
         #(execute-step! host storage-view %)}
        _ (reset! leaf-holder platform-leaf)
        scan!
        #(driver/call-with-leaf platform-leaf database-leaf driver/scan!)
        listener
        (db.host/listen!
         writer
         {:seon.db/key :seon.agent.driver/claimable-runs
          :datahike.read/dependency-plan :all
          :seon.db/handler (fn [_] (scan!))})]
    (scan!)
    {:seon.agent.driver/listener listener
     :seon.agent.driver/handles handles
     :seon.agent.driver/stop!
     (fn []
       (db.host/unlisten! writer listener)
       (doseq [thread (vals @handles)]
         (.interrupt ^Thread thread))
       nil)}))
