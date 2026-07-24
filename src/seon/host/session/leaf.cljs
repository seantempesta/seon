(ns seon.host.session.leaf
  "Invoke authored symbols through the JVM guarded-host session."
  (:require [seon.content-hash :as content-hash]
            [seon.db :as db]
            [seon.db.protocol :as db.protocol]
            [seon.db.transport.uds :as uds]
            [seon.host.session :as protocol]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(schema/register! ::database :seon.db/db)
(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::function-symbol :qualified-symbol)
(schema/register! ::arguments [:vector :any])
(schema/register!
 ::invoke-authored-request
 [:map {:closed true}
  [::database ::database]
  [::agent-id ::agent-id]
  [::function-symbol ::function-symbol]
  [::arguments ::arguments]])

(def ^:private maximum-invocation-ms (* 10 60 1000))

(def ^:private invocation-source-query
  '[:find ?requested ?source
    :in $ ?requested
    :where
    [?function :seon.fn/sym ?requested]
    [?function :seon.fn/source ?source ?tx]
    (or-join [?function ?tx]
      (and [?tx :seon.db/process ?process]
           [?process :seon.db.process/id :seon.db.process/repl])
      (and [?function :seon.packages/package ?package]
           [?package :seon.packages/as _]))])

(defonce ^:private !tails (atom {}))

(defn- deferred []
  (let [!resolve (atom nil)
        promise (js/Promise. #(reset! !resolve %))]
    {::promise promise ::resolve! @!resolve}))

(defn- client-error-frame [invocation-id message data]
  {:seon.execution/message protocol/error-message
   :seon.execution/protocol-version protocol/protocol-version
   :seon.execution/invocation-id invocation-id
   :seon.execution/error
   (cond-> {:seon.error/message message
            :seon.error/kind :core-bug}
     (seq data) (assoc :seon.error/data data))})

(defn- descriptor-values [agent-id]
  (let [descriptor launch/process-launch-descriptor
        runtime (::launch/runtime descriptor)
        database (::launch/database descriptor)
        writer (::launch/writer-owner descriptor)
        host (::launch/host-owner descriptor)]
    {:socket-path (::launch/eval-socket-path host)
     :startup
     {:seon.execution/protocol-version protocol/protocol-version
      :seon.execution/agent-id agent-id
      :seon.launch/execution-digest (::launch/execution-digest runtime)
      :seon.launch/application-digest (::launch/application-digest runtime)
      :seon.execution/database-selection
      (cond->
       {:seon.db/socket-path (::launch/request-socket-path writer)
        :seon.db/database-name (::db.protocol/database-name database)}
        (::db.protocol/backend database)
        (assoc :seon.db/backend (::db.protocol/backend database))
        (::db.protocol/database-path database)
        (assoc :seon.db/database-path
               (::db.protocol/database-path database)))}}))

(defn- ready-frame? [startup message]
  (and (schema/valid-candidate-value? ::protocol/ready message)
       (= (:seon.execution/agent-id startup)
          (:seon.execution/agent-id message))
       (= (:seon.launch/execution-digest startup)
          (:seon.launch/execution-digest message))
       (= (:seon.launch/application-digest startup)
          (:seon.launch/application-digest message))
       (= (get-in startup [:seon.execution/database-selection
                           :seon.db/database-name])
          (:db-name (:seon.db/db message)))))

(defn- source-digest! [database function-symbol]
  (let [requested (str function-symbol)]
    (-> (db/query
         {::db/db database
          ::db/query invocation-source-query
          ::db/args [requested]
          ::db/max-results 2
          ::db/max-result-weight (* 64 1024)})
        (.then
         (fn [rows]
           (cond
             (:seon.error/message rows)
             (throw
              (ex-info (:seon.error/message rows)
                       {:seon.error/kind :core-bug
                        ::function-symbol function-symbol}))

             (= 1 (count rows))
             (content-hash/sha-256 (second (first rows)))

             :else
             (throw
              (ex-info "No unique current authored source matches the invocation."
                       {:seon.error/kind :agent
                        ::function-symbol function-symbol
                        :seon.host.session/source-count (count rows)}))))))))

(defn- invoke-once!
  [{::keys [database agent-id function-symbol arguments]}]
  (let [invocation-id (str (random-uuid))
        completion (deferred)
        !stream (atom nil)
        !phase (atom :startup)
        !settled? (atom false)
        finish!
        (fn [frame]
          (when (compare-and-set! !settled? false true)
            (reset! !phase :settled)
            ((::resolve! completion) frame)
            (when-let [stream @!stream]
              (uds/close-stream! stream))))]
    (-> (source-digest! database function-symbol)
        (.then
         (fn [source-digest]
           (let [{:keys [socket-path startup]} (descriptor-values agent-id)
                 invocation
                 {:seon.execution/message protocol/invoke-message
                  :seon.execution/protocol-version protocol/protocol-version
                  :seon.execution/agent-id agent-id
                  :seon.execution/invocation-id invocation-id
                  :seon.db/db database
                  :seon.execution/function-identity
                  {:seon.execution/function-symbol function-symbol
                   :seon.execution/source-digest source-digest}
                  :seon.execution/arguments arguments
                  :seon.execution/deadline-ms
                  (+ (.now js/Date) maximum-invocation-ms)
                  :seon.execution/result-limit-bytes
                  protocol/maximum-result-bytes}]
             (-> (uds/connect-stream!
                  {::uds/socket-path socket-path
                   ::uds/on-text!
                   (fn [text]
                     (try
                       (let [message (uds/decode text)]
                         (case @!phase
                           :startup
                           (cond
                             (ready-frame? startup message)
                             (do
                               (reset! !phase :invoke)
                               (when-let [send-error
                                          ((::uds/send-text! @!stream)
                                           (uds/encode invocation))]
                                 (finish!
                                  (client-error-frame
                                   invocation-id
                                   "The authored invocation could not be sent."
                                   {:seon.error/cause
                                    (:seon.error/message send-error)}))))

                             (and (= protocol/error-message
                                     (:seon.execution/message message))
                                  (= "startup"
                                     (:seon.execution/invocation-id message)))
                             (finish! message)

                             :else
                             (finish!
                              (client-error-frame
                               invocation-id
                               "The JVM host returned an invalid ready frame."
                               {:seon.host.session/frame message})))

                           :invoke
                           (if (and
                                (= invocation-id
                                   (:seon.execution/invocation-id message))
                                (or
                                 (schema/valid-candidate-value?
                                  ::protocol/result message)
                                 (schema/valid-candidate-value?
                                  ::protocol/error message)))
                             (finish! message)
                             (finish!
                              (client-error-frame
                               invocation-id
                               "The JVM host returned an uncorrelated frame."
                               {:seon.host.session/frame message})))

                           nil))
                       (catch :default exception
                         (finish!
                          (client-error-frame
                           invocation-id
                           "The JVM host returned an unreadable frame."
                           {:seon.error/cause (ex-message exception)})))))
                   ::uds/on-close!
                   (fn [exception]
                     (finish!
                      (client-error-frame
                       invocation-id
                       "The JVM host session closed before settlement."
                       {:seon.error/cause (ex-message exception)})))})
                 (.then
                  (fn [stream]
                    (reset! !stream stream)
                    (when-let [send-error
                               ((::uds/send-text! stream)
                                (uds/encode startup))]
                      (finish!
                       (client-error-frame
                        invocation-id
                        "The JVM host startup frame could not be sent."
                        {:seon.error/cause
                         (:seon.error/message send-error)})))))
                 (.catch
                  (fn [exception]
                    (finish!
                     (client-error-frame
                      invocation-id
                      "The JVM host session could not be opened."
                      {:seon.error/cause (ex-message exception)}))))))))
        (.catch
         (fn [exception]
           (finish!
            (client-error-frame
             invocation-id
             "The authored function identity could not be acquired."
             {:seon.error/cause (ex-message exception)
              :seon.error/data (ex-data exception)})))))
    (::promise completion)))

(defn ^:async invoke-authored!
  "Invoke one digest-pinned authored symbol through the JVM eval door."
  {:malli/schema [:=> [:cat ::invoke-authored-request] :map]}
  [{::keys [agent-id] :as request}]
  (let [!work (atom nil)]
    (swap! !tails
           (fn [tails]
             (let [prior (get tails agent-id)
                   work (if prior
                          (.then prior #(invoke-once! request))
                          (invoke-once! request))]
               (reset! !work work)
               (assoc tails agent-id work))))
    (let [work @!work]
      (try
        (await work)
        (finally
          (swap! !tails
                 (fn [tails]
                   (if (identical? work (get tails agent-id))
                     (dissoc tails agent-id)
                     tails))))))))
