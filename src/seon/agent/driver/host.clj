(ns seon.agent.driver.host
  "JVM claimant leaf: database interest, virtual-thread custody, and eval."
  (:require [my.blob.core :as blob.core]
            [seon.agent.driver :as driver]
            [seon.agent.run.core :as run.core]
            [seon.agent.turn.core :as turn.core]
            [seon.content-hash :as content-hash]
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
           [java.util.concurrent Callable ExecutorService Future]))

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
   :seon.db.leaf/schema-projection (constantly nil)
   :seon.db.leaf/cache-schema-projection! (fn [_] nil)
   :seon.db.leaf/schema-validation? (constantly false)})

(defn database-leaf
  "Portable database leaf over the host's retained writer pool."
  [writer]
  (db.host/leaf writer #(database-context writer)))

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
  [agent-id database run-id claim-epoch turn-id program]
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
   :seon.execution/deadline-ms (+ (System/currentTimeMillis) 120000)
   :seon.execution/result-limit-bytes 1048576
   :seon.execution/run-fence
   {:seon.agent.run/id run-id
    :seon.agent.run/claim-epoch claim-epoch}})

(defn- run-eval-batch!
  [host storage-view run claim-epoch database]
  (let [agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        turn (:seon.agent.run/current-turn run)
        turn-id (:seon.agent.turn/id turn)
        fence (run.core/run-fence agent-id run-id claim-epoch)
        program (reply-program storage-view turn agent-id)]
    (if (:my.blob/error program)
      {:seon.error/message (:my.blob/error program)
       :seon.error/kind :core-bug}
      (let [host-session (driver-session host agent-id)
            task
            (.submit
             ^ExecutorService (:seon.host/eval-pool host)
             ^Callable
             (fn []
               (invoke/execute-invocation!
                host-session
                (invocation agent-id database run-id claim-epoch
                            turn-id program))))
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
    :eval (eval-step! host storage-view claim)
    :settle-eval (settle-eval-step! host storage-view claim)
    {:seon.error/message "The JVM claimant was assigned an ineligible phase."
     :seon.error/kind :core-bug}))

(defn start!
  "Start the interest-driven JVM claimant and return its stop handle."
  [host storage-view]
  (let [writer (:seon.host/writer host)
        database-leaf (database-leaf writer)
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
         #{:seon.agent.driver.capability/eval}
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
