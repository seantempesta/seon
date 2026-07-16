(ns seon.execution
  "Data-only protocol and Bun entry point for one agent execution child."
  (:require
   [cognitect.transit :as transit]
   [clojure.walk :as walk]
   [shadow.cljs.devtools.client.env :as shadow-env]
   [seon.db :as db]
   [seon.db.coordinate]
   [seon.db.protocol :as db.protocol]
   [seon.error :as error]
   [seon.eval :as eval]
   [seon.schema :as schema]))

;;; Data contract

(def protocol-version 2)
(def maximum-invocation-ms (* 10 60 1000))
(def maximum-result-bytes
  ;; Reserve room for the terminal envelope inside the database protocol's
  ;; already-agreed host-wide frame ceiling.
  (- db.protocol/maximum-frame-bytes (* 64 1024)))

(def invoke-message :seon.execution.message/invoke)
(def cancel-message :seon.execution.message/cancel)
(def shutdown-message :seon.execution.message/shutdown)
(def ready-message :seon.execution.message/ready)
(def result-message :seon.execution.message/result)
(def error-message :seon.execution.message/error)
(def stopped-message :seon.execution.message/stopped)
(def ^:private compiled-entrypoint 'seon.execution.runtime/render-prompt!)

(schema/register! ::protocol-version [:= protocol-version])
(schema/register! ::message :keyword)
(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::invocation-id [:string {:min 1}])
(schema/register! ::function-symbol :qualified-symbol)
(schema/register! ::digest [:re "^[0-9a-f]{64}$"])
(schema/register! ::artifact-digest ::digest)
(schema/register! ::function-identity
                  [:or
                   [:map {:closed true}
                    [::function-symbol ::function-symbol]
                    [::source-digest ::digest]]
                   [:map {:closed true}
                    [::function-symbol ::function-symbol]
                    [::artifact-digest ::digest]]])
(schema/register! ::arguments [:vector :any])
(schema/register! ::deadline-ms [:int {:min 0}])
(schema/register! ::result-limit-bytes
                  [:int {:min 1 :max maximum-result-bytes}])
(schema/register! ::run-fence [:map-of :qualified-keyword :any])
(schema/register! ::coordinate :seon.db.coordinate/coordinate)
(schema/register! ::database-attachment :seon.db.coordinate/attachment)
(schema/register! ::shadow-build-id [:string {:min 1}])
(schema/register! ::bun-version [:string {:min 1}])
(schema/register! ::database-selection :seon.db/open-session-request)
(schema/register!
 ::startup
 [:map {:closed true}
  [::protocol-version ::protocol-version]
  [::agent-id ::agent-id]
  [::artifact-digest ::artifact-digest]
  [::shadow-build-id ::shadow-build-id]
  [::database-selection ::database-selection]])
(schema/register!
 ::invoke
 [:map {:closed true}
  [::message [:= invoke-message]]
  [::protocol-version ::protocol-version]
  [::agent-id ::agent-id]
  [::invocation-id ::invocation-id]
  [::coordinate ::coordinate]
  [::function-identity ::function-identity]
  [::arguments ::arguments]
  [::deadline-ms ::deadline-ms]
  [::result-limit-bytes ::result-limit-bytes]
  [::run-fence {:optional true} ::run-fence]])
(schema/register!
 ::cancel
 [:map {:closed true}
  [::message [:= cancel-message]]
  [::protocol-version ::protocol-version]
  [::invocation-id ::invocation-id]])
(schema/register!
 ::shutdown
 [:map {:closed true}
  [::message [:= shutdown-message]]
  [::protocol-version ::protocol-version]])
(schema/register! ::parent-message [:or ::invoke ::cancel ::shutdown])
(schema/register! ::result :any)
(schema/register! ::result-bytes [:int {:min 1 :max maximum-result-bytes}])
(schema/register! ::error
                  [:map {:closed true}
                   [:seon.error/message [:string {:min 1}]]
                   [:seon.error/kind :keyword]
                   [:seon.error/data {:optional true} :map]])
(schema/register!
 ::ready
 [:map {:closed true}
  [::message [:= ready-message]]
  [::protocol-version ::protocol-version]
  [::agent-id ::agent-id]
  [::bun-version ::bun-version]
  [::shadow-build-id ::shadow-build-id]
  [::artifact-digest ::artifact-digest]
  [::database-attachment ::database-attachment]
  [::coordinate ::coordinate]])
(schema/register!
 ::result-message
 [:map {:closed true}
  [::message [:= result-message]]
  [::protocol-version ::protocol-version]
  [::invocation-id ::invocation-id]
  [::coordinate ::coordinate]
  [::result ::result]
  [::result-bytes ::result-bytes]])
(schema/register!
 ::error-message
 [:map {:closed true}
  [::message [:= error-message]]
  [::protocol-version ::protocol-version]
  [::invocation-id ::invocation-id]
  [::coordinate {:optional true} ::coordinate]
  [::error ::error]])
(schema/register!
 ::stopped
 [:map {:closed true}
  [::message [:= stopped-message]]
  [::protocol-version ::protocol-version]])
(schema/register! ::child-message
                  [:or ::ready ::result-message ::error-message ::stopped])

(schema/register!
 ::invocation-plan
 [:map {:closed true}
  [::agent-id ::agent-id]
  [::invocation-id ::invocation-id]
  [::function-symbol ::function-symbol]
  [::arguments ::arguments]
  [::deadline-ms ::deadline-ms]
  [::result-limit-bytes ::result-limit-bytes]
  [::run-fence {:optional true} ::run-fence]])
(schema/register! ::invocation-plans [:vector {:min 1} ::invocation-plan])
(schema/register!
 ::prepare-request
 [:map {:closed true}
  [::coordinate ::coordinate]
  [::invocation-plans ::invocation-plans]])

(defonce ^:private transit-writer (transit/writer :json))
(defonce ^:private transit-reader (transit/reader :json))
(defonce ^:private text-encoder (js/TextEncoder.))

(defn encode-message
  "Encode one eager ordinary IPC value as Transit JSON."
  {:malli/schema [:=> [:cat :map] :string]}
  [message]
  (when-not (db.protocol/ordinary-wire-value? message)
    (throw (ex-info "Execution IPC accepts only eager ordinary data."
                    {::message message
                     :seon.error/kind :core-bug})))
  (transit/write transit-writer message))

(defn decode-message
  "Decode one Transit JSON IPC string into eager ordinary data."
  {:malli/schema [:=> [:cat :string] :map]}
  [encoded]
  (let [message (transit/read transit-reader encoded)]
    (when-not (and (map? message)
                   (db.protocol/ordinary-wire-value? message))
      (throw (ex-info "Execution IPC decoded a non-ordinary value."
                      {:seon.error/kind :core-bug})))
    message))

(defn valid-parent-message?
  "True when a value is one complete ordinary parent message."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [message]
  (and (schema/valid-candidate-value? ::parent-message message)
       (db.protocol/ordinary-wire-value? message)))

(defn valid-child-message?
  "True when a value is one complete ordinary child message."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [message]
  (and (schema/valid-candidate-value? ::child-message message)
       (db.protocol/ordinary-wire-value? message)))

(defn bounded-result
  "Return an ordinary result or a bounded execution error value."
  {:malli/schema [:=> [:cat :any ::result-limit-bytes] :map]}
  [value result-limit]
  (let [value (walk/postwalk identity value)]
  (cond
    (not (db.protocol/ordinary-wire-value? value))
    {::ok? false
     ::error {:seon.error/message
              "The function returned a value that cannot cross IPC."
              :seon.error/kind :agent}}

    :else
    (let [encoded (encode-message {::value value})
          byte-count (.-byteLength (.encode text-encoder encoded))]
      (if (<= byte-count result-limit)
        {::ok? true ::value value ::result-bytes byte-count}
        {::ok? false
         ::error {:seon.error/message
                  "The function result exceeded its byte limit."
                  :seon.error/kind :agent
                  :seon.error/data
                  {::result-bytes byte-count
                   ::result-limit-bytes result-limit}}})))))

;;; Coordinate-pinned authored program

(def ^:private maximum-program-rows 2048)
(def ^:private maximum-program-bytes (* 3 1024 1024))

(def ^:private authored-function-query
  '[:find ?sym ?source ?ns-name ?ns-source
           (pull ?namespace [{:seon.ns/require-edges [*]}])
    :in $ ?agent-id
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source ?source ?tx]
    [?tx :seon.db/user ?author]
    [?author :seon.agent/id ?agent-id]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]
    [?function :seon.fn/ns ?namespace]
    [?namespace :seon.ns/name ?ns-name]
    [(get-else $ ?namespace :seon.ns/source "") ?ns-source]])

(def ^:private schema-query
  '[:find ?key ?form
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form]])

(def ^:private function-contract-query
  '[:find ?sym ?form
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/spec ?form]])

(def ^:private invocation-source-query
  '[:find ?sym ?source
    :in $ ?agent-id [?requested ...]
    :where
    [?function :seon.fn/sym ?sym]
    [(= ?sym ?requested)]
    [?function :seon.fn/source ?source ?tx]
    [?tx :seon.db/user ?author]
    [?author :seon.agent/id ?agent-id]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(defn- query-member [query arguments]
  {::db.protocol/operation db.protocol/query-operation
   ::db.protocol/query-form query
   ::db.protocol/arguments arguments
   :datahike.resource/max-results maximum-program-rows
   :datahike.resource/max-result-weight maximum-program-bytes})

(defn- query-result [member]
  (if (::db.protocol/success? member)
    (:datahike.query/result member)
    (throw (ex-info "Authored program acquisition failed."
                    {:seon.db/error member :seon.error/kind :core-bug}))))

(defn- normalize-require-edges [pulled]
  (->> (:seon.ns/require-edges pulled)
       (map (fn [edge]
              (cond-> (dissoc edge :db/id)
                (seq (:seon.ns.require/refers edge))
                (update :seon.ns.require/refers
                        #(vec (sort-by str %))))))
       (sort-by pr-str)
       vec))

(defn canonical-program
  "Canonicalize unordered database rows before source identity hashing."
  {:malli/schema [:=> [:cat [:sequential :any]
                       [:sequential :any]
                       [:sequential :any]] :map]}
  [function-rows schema-rows contract-rows]
  (let [namespace-rows
        (->> function-rows
             (group-by #(nth % 2))
             (map
              (fn [[ns-name rows]]
                (let [rows (sort-by (comp str first) rows)
                      [_ _ _ ns-source pulled] (first rows)]
                  {:seon.ns/name ns-name
                   :seon.ns/source ns-source
                   :seon.ns/require-edges (normalize-require-edges pulled)
                   :seon.fn/symbols (mapv (comp symbol first) rows)
                   :seon.fn/sources (->> rows (map second) distinct sort vec)})))
             (sort-by (comp str :seon.ns/name))
             vec)]
    {::namespace-rows namespace-rows
     ::schema-forms (->> schema-rows (map vec) (sort-by (comp str first)) vec)
     ::function-contracts (->> contract-rows
                               (map vec)
                               (sort-by (comp str first))
                               vec)}))

(defn source-digest
  "Return the canonical SHA-256 identity of one authored source value."
  {:malli/schema [:=> [:cat :any] ::digest]}
  [value]
  (let [crypto (js/require "node:crypto")]
    (-> (.createHash crypto "sha256")
        (.update (pr-str value) "utf8")
        (.digest "hex"))))

(defn invocation-plan
  "Build one ordinary authored call plan for a captured render coordinate."
  {:malli/schema [:=> [:cat ::agent-id ::function-symbol ::arguments]
                  ::invocation-plan]}
  [agent-id function-symbol arguments]
  {::agent-id agent-id
   ::invocation-id (str (random-uuid))
   ::function-symbol function-symbol
   ::arguments arguments
   ::deadline-ms (+ (.now js/Date) maximum-invocation-ms)
   ::result-limit-bytes maximum-result-bytes})

(defn compiled-invocation
  "Pin one parent-selected core call to the verified execution artifact."
  {:malli/schema [:=> [:cat ::agent-id ::arguments ::coordinate
                       ::artifact-digest]
                  ::invoke]}
  [agent-id arguments coordinate artifact-digest]
  (let [plan (invocation-plan agent-id compiled-entrypoint arguments)]
    (-> plan
        (dissoc ::function-symbol)
        (assoc ::message invoke-message
               ::protocol-version protocol-version
               ::coordinate coordinate
               ::function-identity
               {::function-symbol compiled-entrypoint
                ::artifact-digest artifact-digest}))))

(defn ^:async prepare-invocations!
  "Pin ordinary invocation plans to their authored source identities."
  {:malli/schema [:=> [:cat ::prepare-request] :any]}
  [{::keys [coordinate invocation-plans]}]
  (let [by-agent (->> invocation-plans
                      (group-by ::agent-id)
                      (sort-by key)
                      vec)
        acquisition
        (await
         (db/execute-many
          {::db/coordinate coordinate
           ::db/max-result-weight (* 1024 1024)
           ::db/members
           (mapv (fn [[agent-id plans]]
                   (query-member invocation-source-query
                                 [agent-id
                                  (mapv (comp str ::function-symbol) plans)]))
                 by-agent)}))
        _ (when-not (= coordinate (::db/coordinate acquisition))
            (throw
             (ex-info "Invocation source acquisition moved coordinates."
                      {:seon.error/kind :core-bug})))
        identities
        (reduce
         (fn [known [[agent-id _] member]]
           (into known
                 (map (fn [[sym source]]
                        [[agent-id (symbol sym)]
                         {::function-symbol (symbol sym)
                          ::source-digest (source-digest source)}]))
                 (query-result member)))
         {}
         (map vector by-agent (::db/results acquisition)))]
    (mapv
     (fn [plan]
       (let [identity (get identities [(::agent-id plan)
                                       (::function-symbol plan)])]
         (when-not identity
           (throw (ex-info "No current authored source matches the invocation."
                           {::agent-id (::agent-id plan)
                            ::function-symbol (::function-symbol plan)
                            :seon.error/kind :agent})))
         (-> plan
             (dissoc ::function-symbol)
             (assoc ::message invoke-message
                    ::protocol-version protocol-version
                    ::coordinate coordinate
                    ::function-identity identity))))
     invocation-plans)))

(defn- ^:async acquire-program! [invocation]
  (let [coordinate (::coordinate invocation)
        result (await
                (db/execute-many
                 {::db/coordinate coordinate
                  ::db/max-result-weight maximum-program-bytes
                  ::db/members
                  [(query-member authored-function-query
                                 [(::agent-id invocation)])
                   (query-member schema-query [])
                   (query-member function-contract-query [])]}))
        [functions schemas contracts] (mapv query-result (::db/results result))
        program (canonical-program functions schemas contracts)
        target (get-in invocation [::function-identity
                                   ::function-symbol])
        source (some (fn [[sym source & _]]
                       (when (= target (symbol sym)) source))
                     functions)]
    (when-not (= coordinate (::db/coordinate result))
      (throw (ex-info "Authored program acquisition moved coordinates."
                      {:seon.error/kind :core-bug})))
    (when-not source
      (throw (ex-info "The requested current agent function does not exist."
                      {::function-symbol target :seon.error/kind :agent})))
    (when-not (= (source-digest source)
                 (get-in invocation [::function-identity
                                     ::source-digest]))
      (throw (ex-info "The requested function source is no longer current."
                      {::function-symbol target :seon.error/kind :agent})))
    (assoc program ::digest (source-digest program))))

(defn- ^:async ensure-program! [state invocation]
  (let [program (await (acquire-program! invocation))
        loaded (::program @state)]
    (cond
      (nil? loaded)
      (let [compile-state
            (await (eval/load-authored-program!
                    (assoc program ::function-symbols
                           [(get-in invocation [::function-identity
                                                ::function-symbol])])))]
        (swap! state assoc ::program program ::compile-state compile-state)
          program)

      (= (::digest loaded) (::digest program))
      (do
        (await
         (eval/load-authored-program!
          (assoc program
                 ::function-symbols
                 [(get-in invocation [::function-identity
                                      ::function-symbol])]
                 ::compile-state (::compile-state @state))))
        program)

      :else
      (throw (ex-info "Authored source changed; a fresh child is required."
                      {::reload-required? true
                       :seon.error/kind :core-bug})))))

;;; Child owner

(defn- exception-value [exception]
  (let [data (ex-data exception)]
    (cond-> {:seon.error/message (error/->message exception)
             :seon.error/kind (or (:seon.error/kind data) :agent)}
      (and (map? data) (db.protocol/ordinary-wire-value? data))
      (assoc :seon.error/data data))))

(defn- terminal-message [invocation coordinate bounded]
  (if (::ok? bounded)
    {::message result-message
     ::protocol-version protocol-version
     ::invocation-id (::invocation-id invocation)
     ::coordinate coordinate
     ::result (::value bounded)
     ::result-bytes (::result-bytes bounded)}
    {::message error-message
     ::protocol-version protocol-version
     ::invocation-id (::invocation-id invocation)
     ::coordinate coordinate
     ::error (::error bounded)}))

(defn- send! [send-message! message]
  (when-not (valid-child-message? message)
    (throw (ex-info "The child attempted to send an invalid IPC message."
                    {::message message
                     :seon.error/kind :core-bug})))
  (send-message! (encode-message message)))

(defn- settle-active!
  [state token send-message! message]
  (let [settled? (atom false)]
    (swap! state
           (fn [current]
             (if (identical? token (get-in current [::active ::token]))
               (do
                 (reset! settled? true)
                 (when-let [timer (get-in current [::active ::timer])]
                   (js/clearTimeout timer))
                 (dissoc current ::active))
               current)))
    (when @settled? (send! send-message! message))
    @settled?))

(defn- fail-invocation!
  [state invocation send-message! message kind]
  (send! send-message!
         {::message error-message
          ::protocol-version protocol-version
          ::invocation-id (::invocation-id invocation)
          ::coordinate (::coordinate invocation)
          ::error {:seon.error/message message
                   :seon.error/kind kind}})
  state)

(defn- ensure-session! [state]
  (db/open-session! (get-in @state [::startup ::database-selection])))

(defn- timeout-invocation!
  [state token invocation send-message! exit!]
  (db/close-session!)
  (swap! state assoc ::poisoned? true)
  (settle-active!
   state token send-message!
   {::message error-message
    ::protocol-version protocol-version
    ::invocation-id (::invocation-id invocation)
    ::coordinate (::coordinate invocation)
    ::error {:seon.error/message "The invocation timed out."
             :seon.error/kind :agent}})
  (exit! 1))

(defn- begin-invocation!
  [state invocation send-message! exit! now-ms]
  (let [current @state
        identity (::function-identity invocation)
        function-symbol (::function-symbol identity)
        compiled? (contains? identity ::artifact-digest)
        remaining (- (::deadline-ms invocation) now-ms)]
    (cond
      (::shutting-down? current)
      (fail-invocation! state invocation send-message!
                        "The execution child is shutting down." :core-bug)

      (::poisoned? current)
      (fail-invocation! state invocation send-message!
                        "The execution child is retiring." :core-bug)

      (::active current)
      (fail-invocation! state invocation send-message!
                        "The execution child already has an active invocation."
                        :core-bug)

      (not= (get-in current [::startup ::agent-id]) (::agent-id invocation))
      (fail-invocation! state invocation send-message!
                        "The invocation names another agent." :core-bug)

      (and compiled?
           (or (not= compiled-entrypoint function-symbol)
               (not= (::artifact-digest identity)
                     (get-in current [::startup ::artifact-digest]))))
      (fail-invocation! state invocation send-message!
                        "The compiled function identity is not trusted by this artifact."
                        :core-bug)

      (not (pos? remaining))
      (fail-invocation! state invocation send-message!
                        "The invocation deadline has elapsed." :agent)

      :else
      (let [token (js-obj)
            compiled-function-value
            (when compiled?
              (eval/lookup-value function-symbol))
            timeout-ms (min remaining maximum-invocation-ms)
            timer
            (js/setTimeout
             (fn []
               (timeout-invocation! state token invocation send-message! exit!))
             timeout-ms)]
        (swap! state assoc ::active {::token token
                                     ::invocation invocation
                                     ::timer timer})
        (-> (ensure-session! state)
            (.then (fn [_]
                     (when-not compiled?
                       (ensure-program! state invocation))))
            (.then
             (fn [_]
               (if-let [function-value
                        (if compiled?
                          compiled-function-value
                          (eval/lookup-value function-symbol))]
                 (try
                   (js/Promise.resolve
                    (db/with-agent
                     (::agent-id invocation)
                     (fn []
                       (db/with-tx-context
                        (assoc (or (::run-fence invocation) {})
                               ::db/coordinate (::coordinate invocation))
                        (fn [] (apply function-value (::arguments invocation)))))))
                   (catch :default exception
                     (js/Promise.reject exception)))
                 (js/Promise.reject
                  (ex-info "The granted function is not loaded in the child."
                           {::function-symbol function-symbol
                            :seon.error/kind :core-bug})))))
            (.then
             (fn [value]
               (let [bounded (bounded-result value
                                             (::result-limit-bytes invocation))]
                 (settle-active!
                  state token send-message!
                  (terminal-message invocation (::coordinate invocation)
                                    bounded)))))
            (.catch
             (fn [exception]
               (settle-active!
                state token send-message!
                (terminal-message
                 invocation (::coordinate invocation)
                 {::ok? false ::error (exception-value exception)})))))
        state))))

(defn- cancel-active! [state invocation-id send-message!]
  (when-let [active (::active @state)]
    (when (= invocation-id (get-in active [::invocation ::invocation-id]))
      (db/close-session!)
      (settle-active!
       state (::token active) send-message!
       {::message error-message
        ::protocol-version protocol-version
        ::invocation-id invocation-id
        ::coordinate (get-in active [::invocation ::coordinate])
        ::error {:seon.error/message "The invocation was canceled."
                 :seon.error/kind :agent}}))))

(defn- shutdown!
  [state send-message! exit!]
  (swap! state assoc ::shutting-down? true)
  (when-let [invocation-id (get-in @state [::active ::invocation
                                            ::invocation-id])]
    (cancel-active! state invocation-id send-message!))
  (db/close-session!)
  (send! send-message!
         {::message stopped-message ::protocol-version protocol-version})
  (exit! 0))

(defn- receive!
  [state encoded send-message! exit! now-ms]
  (try
    (let [message (decode-message encoded)]
      (if-not (valid-parent-message? message)
        (send! send-message!
               {::message error-message
                ::protocol-version protocol-version
                ::invocation-id (or (::invocation-id message) "invalid")
                ::error {:seon.error/message
                         "The parent sent an invalid execution message."
                         :seon.error/kind :core-bug}})
        (case (::message message)
          :seon.execution.message/invoke
          (begin-invocation! state message send-message! exit! now-ms)

          :seon.execution.message/cancel
          (do
            (cancel-active! state (::invocation-id message) send-message!)
            ;; A canceled cljs.js evaluation may still mutate compiler/global
            ;; state after Promise settlement. Never reuse that process.
            (swap! state assoc ::poisoned? true)
            (exit! 1))

          :seon.execution.message/shutdown
          (shutdown! state send-message! exit!))))
    (catch :default exception
      (send! send-message!
             {::message error-message
              ::protocol-version protocol-version
              ::invocation-id "invalid"
              ::error (exception-value exception)}))))

(defn- start-child!
  [startup send-message! on-message! exit!]
  (let [state (atom {::startup startup})]
    (-> (db/open-session! (::database-selection startup))
        (.then
         (fn [{::db/keys [attachment coordinate]}]
           (send! send-message!
                  {::message ready-message
                   ::protocol-version protocol-version
                   ::agent-id (::agent-id startup)
                   ::bun-version (or (.-version js/Bun) "unknown")
                   ::shadow-build-id (::shadow-build-id startup)
                   ::artifact-digest (::artifact-digest startup)
                   ::database-attachment attachment
                   ::coordinate coordinate})
           (on-message!
            (fn [encoded]
              (receive! state encoded send-message! exit! (.now js/Date))))
           state)))))

(defn -main
  "Attach one Bun child to its database and serve parent IPC."
  []
  (let [encoded-startup (aget (.-argv js/process) 2)
        send-message! (fn [encoded] (.send js/process encoded))
        on-message! (fn [handler] (.on js/process "message" handler))
        ;; Give Bun's IPC queue one event-loop turn to flush the terminal
        ;; stopped/error value before ending the child.
        exit! (fn [status]
                (js/setTimeout (fn [] (.exit js/process status)) 0))]
    (when-not (string? encoded-startup)
      (throw (ex-info "The execution child requires a Transit startup value."
                      {:seon.error/kind :core-bug})))
    (let [startup (decode-message encoded-startup)
          artifact-path (aget (.-argv js/process) 1)]
      (-> (.arrayBuffer (js/Bun.file artifact-path))
          (.then
           (fn [artifact-bytes]
             (let [hasher (js/Bun.CryptoHasher. "sha256")]
               (.update hasher artifact-bytes)
               (.digest hasher "hex"))))
          (.then
           (fn [actual-artifact-digest]
             (when-not (and (schema/valid-candidate-value? ::startup startup)
                            (= shadow-env/build-id
                               (::shadow-build-id startup))
                            (= actual-artifact-digest
                               (::artifact-digest startup)))
               (throw
                (ex-info "The execution child startup identity is invalid."
                         {::startup startup
                          ::compiled-shadow-build-id shadow-env/build-id
                          ::actual-artifact-digest actual-artifact-digest
                          :seon.error/kind :core-bug})))
             (start-child! startup send-message! on-message! exit!)))
          (.catch
           (fn [exception]
             (send! send-message!
                    {::message error-message
                     ::protocol-version protocol-version
                     ::invocation-id "startup"
                     ::error (exception-value exception)})
             (exit! 1)))))))
