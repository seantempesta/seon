(ns seon.execution
  "Data-only protocol and Bun entry point for one agent execution child."
  (:require
   [cognitect.transit :as transit]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.registry :as mr]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as db.protocol]
   [seon.error :as error]
   [seon.eval :as eval]
   [seon.runtime.admission :as admission]
   [seon.schema :as schema]))

;;; Data contract

(def protocol-version 3)
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

(schema/register! ::protocol-version [:= protocol-version])
(schema/register! ::message :keyword)
(schema/register! ::agent-id [:string {:min 1}])
(schema/register! ::invocation-id [:string {:min 1}])
(schema/register! ::function-symbol :qualified-symbol)
(schema/register! ::compiled-function 'fn?)
(schema/register! ::pin-database? :boolean)
(schema/register!
 ::compiled-function-descriptor
 [:map {:closed true}
  [::compiled-function ::compiled-function]
  [::pin-database? ::pin-database?]])
(schema/register!
 ::compiled-functions
 [:map-of ::function-symbol ::compiled-function-descriptor])
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
(schema/register! ::child-retired? :boolean)
(schema/register! ::run-fence [:map-of :qualified-keyword :any])
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
  [:seon.db/db :seon.db/db]
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
  [:seon.db/db :seon.db/db]])
(schema/register!
 ::result-message
 [:map {:closed true}
  [::message [:= result-message]]
  [::protocol-version ::protocol-version]
  [::invocation-id ::invocation-id]
  [:seon.db/db :seon.db/db]
  [::result ::result]
  [::result-bytes ::result-bytes]])
(schema/register!
 ::error-message
 [:map {:closed true}
  [::message [:= error-message]]
  [::protocol-version ::protocol-version]
  [::invocation-id ::invocation-id]
  [:seon.db/db {:optional true} :seon.db/db]
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
  [:seon.db/db :seon.db/db]
  [::invocation-plans ::invocation-plans]])

(defonce ^:private transit-writer (transit/writer :json))
(defonce ^:private transit-reader (transit/reader :json))
(defonce ^:private text-encoder (js/TextEncoder.))
(defonce ^:private message-validators
  (let [registry (mr/composite-registry
                  (m/default-schemas)
                  (mr/fast-registry (schema/snapshot)))
        options {:registry registry}]
    {::parent-message (m/validator ::parent-message options)
     ::child-message (m/validator ::child-message options)}))

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

(defn- value-type [value]
  (cond
    (nil? value) "nil"
    (record? value) (or (some-> value type str) "record")
    :else (goog/typeOf value)))

(defn- first-non-ordinary
  "Describe the first value that cannot cross the execution IPC boundary."
  ([value] (first-non-ordinary [] value))
  ([path value]
   (cond
     (db.protocol/ordinary-wire-value? value)
     nil

     (or (record? value)
         (fn? value)
         (satisfies? IDeref value)
         (instance? js/Promise value)
         (instance? js/Error value))
     {::value-path path
      ::value-type (value-type value)}

     (map? value)
     (or (some (fn [[key item]]
                 (or (first-non-ordinary (conj path [:key key]) key)
                     (first-non-ordinary (conj path key) item)))
               value)
         {::value-path path ::value-type (value-type value)})

     (or (vector? value) (list? value))
     (or (some identity
               (map-indexed (fn [index item]
                              (first-non-ordinary (conj path index) item))
                            value))
         {::value-path path ::value-type (value-type value)})

     (set? value)
     (or (some #(first-non-ordinary (conj path :set-member) %) value)
         {::value-path path ::value-type (value-type value)})

     :else
     {::value-path path
      ::value-type (value-type value)})))

(defn valid-parent-message?
  "True when a value is one complete ordinary parent message."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [message]
  (and ((::parent-message message-validators) message)
       (db.protocol/ordinary-wire-value? message)))

(defn valid-child-message?
  "True when a value is one complete ordinary child message."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [message]
  (and ((::child-message message-validators) message)
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
              :seon.error/kind :agent
              :seon.error/data (first-non-ordinary value)}}

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

;;; Authored program read at one immutable database value

(def ^:private maximum-program-results 16384)
(def ^:private maximum-program-bytes (* 3 1024 1024))

(def ^:private runtime-namespace-query
  '[:find ?name ?source
    :where
    [?namespace :seon.ns/name ?name]
    [?namespace :seon.ns/source ?source ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(def ^:private runtime-require-edge-query
  '[:find ?name (pull ?edge [*])
    :where
    [?namespace :seon.ns/name ?name]
    [?namespace :seon.ns/require-edges ?edge ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(def ^:private runtime-function-query
  '[:find ?sym ?source ?ns-name
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source ?source ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]
    [?function :seon.fn/ns ?namespace]
    [?namespace :seon.ns/name ?ns-name]])

(def ^:private runtime-test-query
  '[:find ?sym ?source ?ns-name
    :where
    [?test :seon.test/sym ?sym]
    [?test :seon.test/source ?source ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]
    [?test :seon.test/ns ?namespace]
    [?namespace :seon.ns/name ?ns-name]])

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
  '[:find ?requested ?source
    :in $ [?requested ...]
    :where
    [?function :seon.fn/sym ?requested]
    [?function :seon.fn/source ?source ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(defn- query-member [database query arguments]
  {::db.protocol/operation db.protocol/query-operation
   ::db.protocol/query-form query
   ::db.protocol/arguments (into [database] arguments)
   :datahike.resource/max-results maximum-program-results
   :datahike.resource/max-result-weight maximum-program-bytes})

(defn- config-member [database]
  {::db.protocol/operation db.protocol/pull-operation
   ::db/db database
   ::db.protocol/selector '[*]
   ::db.protocol/entity-id [:seon.config/id config/cluster-config-id]
   :datahike.resource/max-work 100000
   :datahike.resource/max-results 256
   :datahike.resource/max-result-weight 65536})

(defn- query-result [member]
  (if (::db.protocol/success? member)
    (:datahike.query/result member)
    (throw (ex-info "Authored program acquisition failed."
                    {:seon.db/error member :seon.error/kind :core-bug}))))

(defn- pull-result [member]
  (if (::db.protocol/success? member)
    (::db.protocol/result member)
    (throw (ex-info "Configuration acquisition failed."
                    {:seon.db/error member :seon.error/kind :core-bug}))))

(defn- normalize-require-edge [edge]
  (cond-> (dissoc edge :db/id)
    (seq (:seon.ns.require/refers edge))
    (update :seon.ns.require/refers #(vec (sort-by str %)))))

(defn- normalize-require-edges [edges]
  (->> edges
       (map normalize-require-edge)
       (sort-by pr-str)
       vec))

(defn- namespace-row [name]
  {:seon.ns/name name
   :seon.ns/require-edges []
   :seon.fn/_ns []
   :seon.test/_ns []})

(defn- ensure-namespace-row [known name]
  (update known name #(or % (namespace-row name))))

(defn canonical-program
  "Canonicalize unordered database rows before source identity hashing."
  {:malli/schema
   [:=>
    [:cat
     [:or [:set [:tuple :keyword :string]]
      [:sequential [:tuple :keyword :string]]]
     [:or [:set [:tuple :keyword :map]]
      [:sequential [:tuple :keyword :map]]]
     [:sequential [:tuple :keyword :string :map]]
     [:or [:set [:tuple :string :string :keyword]]
      [:sequential [:tuple :string :string :keyword]]]
     [:or [:set [:tuple :string :string :keyword]]
      [:sequential [:tuple :string :string :keyword]]]
     [:or [:set [:tuple :keyword :string]]
      [:sequential [:tuple :keyword :string]]]
     [:or [:set [:tuple :string :string]]
      [:sequential [:tuple :string :string]]]]
    :map]}
  [namespace-source-rows require-edge-rows home-rows function-rows test-rows
   schema-rows contract-rows]
  (let [by-name
        (reduce (fn [known [name source]]
                  (assoc known name
                         (assoc (get known name (namespace-row name))
                                :seon.ns/source source)))
                {} namespace-source-rows)
        by-name
        (reduce (fn [known [name edge]]
                  (-> known
                      (ensure-namespace-row name)
                      (update-in [name :seon.ns/require-edges]
                                 conj (normalize-require-edge edge))))
                by-name require-edge-rows)
        by-name
        (reduce (fn [known [name source pulled]]
                  (assoc known name
                         (-> (get known name (namespace-row name))
                             (assoc :seon.ns/source source)
                             (update :seon.ns/require-edges
                                     into
                                     (map normalize-require-edge
                                          (:seon.ns/require-edges pulled))))))
                by-name home-rows)
        by-name
        (reduce (fn [known [sym source name]]
                  (-> known
                      (ensure-namespace-row name)
                      (update-in [name :seon.fn/_ns]
                                 conj
                                 {:seon.fn/sym (symbol sym)
                                  :seon.fn/source source})))
                by-name function-rows)
        by-name
        (reduce (fn [known [sym source name]]
                  (-> known
                      (ensure-namespace-row name)
                      (update-in [name :seon.test/_ns]
                                 conj
                                 {:seon.test/sym (symbol sym)
                                  :seon.test/source source})))
                by-name test-rows)
        namespace-rows
        (->> by-name
             vals
             (map (fn [row]
                    (-> row
                        (update :seon.ns/require-edges
                                #(normalize-require-edges (distinct %)))
                        (update :seon.fn/_ns
                                #(vec (sort-by (comp str :seon.fn/sym)
                                               (distinct %))))
                        (update :seon.test/_ns
                                #(vec (sort-by (comp str :seon.test/sym)
                                               (distinct %)))))))
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
        (.update (if (string? value) value (pr-str value)) "utf8")
        (.digest "hex"))))

(defn invocation-plan
  "Build one ordinary authored call plan."
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
  {:malli/schema
   [:function
    [:=> [:cat ::agent-id ::function-symbol ::arguments :seon.db/db
          ::artifact-digest]
     ::invoke]
    [:=> [:cat ::agent-id ::function-symbol ::arguments :seon.db/db
          ::artifact-digest [:or :nil ::run-fence]]
     ::invoke]]}
  ([agent-id function-symbol arguments database artifact-digest]
   (compiled-invocation agent-id function-symbol arguments database
                        artifact-digest nil))
  ([agent-id function-symbol arguments database artifact-digest run-fence]
   (let [plan (cond-> (invocation-plan agent-id function-symbol arguments)
                run-fence (assoc ::run-fence run-fence))]
     (-> plan
         (dissoc ::function-symbol)
         (assoc ::message invoke-message
                ::protocol-version protocol-version
                :seon.db/db database
                ::function-identity
                {::function-symbol function-symbol
                 ::artifact-digest artifact-digest})))))

(defn ^:async prepare-invocations!
  "Pin ordinary invocation plans to their authored source identities."
  {:malli/schema [:=> [:cat ::prepare-request] :any]}
  [{database :seon.db/db ::keys [invocation-plans]}]
  (let [requested (->> invocation-plans
                       (map (comp str ::function-symbol))
                       distinct
                       vec)
        acquisition
        (await
         (db/execute-many
          {::db/max-result-weight (* 1024 1024)
           ::db/members
           [(query-member database invocation-source-query [requested])]}))
        identities
        (into {}
              (map (fn [[sym source]]
                     [(symbol sym)
                      {::function-symbol (symbol sym)
                       ::source-digest (source-digest source)}]))
              (query-result (first (::db/results acquisition))))]
    (mapv
     (fn [plan]
       (let [identity (get identities (::function-symbol plan))]
         (when-not identity
           (throw (ex-info "No current authored source matches the invocation."
                           {::agent-id (::agent-id plan)
                            ::function-symbol (::function-symbol plan)
                            :seon.error/kind :agent})))
         (-> plan
             (dissoc ::function-symbol)
             (assoc ::message invoke-message
                    ::protocol-version protocol-version
                    :seon.db/db database
                    ::function-identity identity))))
     invocation-plans)))

(defn- program-members [database]
  [(query-member database runtime-namespace-query [])
   (query-member database runtime-require-edge-query [])
   (query-member database runtime-function-query [])
   (query-member database runtime-test-query [])
   (query-member database schema-query [])
   (query-member database function-contract-query [])])

(defn- program-from-results [results]
  (let [[namespaces edges functions tests schemas contracts]
        (mapv query-result results)
        program (canonical-program namespaces edges [] functions tests
                                   schemas contracts)
        source-by-symbol
        (into {}
              (map (fn [[sym source & _]] [(symbol sym) source]))
              (concat functions tests))]
    (assoc program
           ::digest (source-digest program)
           ::source-by-symbol source-by-symbol)))

(defn- ^:async acquire-program!
  [database]
  (let [result (await
                (db/execute-many
                 {::db/db database
                  ::db/max-result-weight maximum-program-bytes
                  ::db/members (program-members database)}))]
    (program-from-results (::db/results result))))

(defn- verify-authored-identity!
  [program invocation]
  (let [identity (::function-identity invocation)
        target (::function-symbol identity)
        source (get (::source-by-symbol program) target)]
    (when-not source
      (throw (ex-info "The requested current agent function does not exist."
                      {::function-symbol target :seon.error/kind :agent})))
    (when-not (= (source-digest source) (::source-digest identity))
      (throw (ex-info "The requested function source is no longer current."
                      {::function-symbol target :seon.error/kind :agent}))))
  program)

(defn- ^:async ensure-compile-state!
  "Return the execution child's one persistent ClojureScript compiler state."
  [state]
  (if-let [compile-state (::compile-state @state)]
    compile-state
    (let [initialized (await (eval/init-bootstrap!))]
      (swap! state
             (fn [current]
               (if (::compile-state current)
                 current
                 (assoc current ::compile-state initialized))))
      (::compile-state @state))))

(defn- ^:async install-program!
  [state invocation program function-symbols verify-identity?]
  (let [compile-state (await (ensure-compile-state! state))
        _ (when verify-identity?
            (verify-authored-identity! program invocation))
        loaded (::program @state)
        function-symbols
        (filterv #(contains? (::source-by-symbol program) %) function-symbols)
        load-request (assoc program ::function-symbols function-symbols)]
    (cond
      (nil? loaded)
      (let [compile-state
            (await (eval/load-authored-program!
                    (assoc load-request ::compile-state compile-state)))]
        (swap! state assoc
               ::program program
               ::compile-state compile-state
               ::authored-symbols (set function-symbols))
        program)

      (= (::digest loaded) (::digest program))
      (let [compile-state
            (await
             (eval/load-authored-program!
              (assoc load-request ::compile-state (::compile-state @state))))]
        (swap! state
               (fn [current]
                 (-> current
                     (assoc ::compile-state compile-state)
                     (update ::authored-symbols into function-symbols))))
        program)

      :else
      (throw (ex-info "Authored source changed; a fresh child is required."
                      {::reload-required? true
                       :seon.error/kind :core-bug})))))

(defn- ^:async ensure-program!
  [state invocation function-symbols verify-identity?]
  (let [program (await (acquire-program! (:seon.db/db invocation)))]
    (await (install-program! state invocation program function-symbols
                             verify-identity?))))

(declare exception-value)

(defn- ^:async prepare-eval-program!
  "Prepare the invocation database value's program and the child's compiler.

   A persisted program error must not remove the agent's repair door. Attempt
   the normal complete-program load, but retain the trusted compiler and exact
   source map when that load fails so `eval-batch!` can replace or remove the
   broken declaration through the same supervised child."
  [state invocation]
  (let [database (:seon.db/db invocation)
        acquired (await
                  (db/execute-many
                   {::db/db database
                    ::db/max-result-weight (+ maximum-program-bytes 65536)
                    ::db/members (conj (program-members database)
                                       (config-member database))}))
        results (::db/results acquired)
        program (program-from-results (subvec results 0 6))
        configuration (db/decode-edn-values (pull-result (nth results 6)))
        symbols (vec (keys (::source-by-symbol program)))
        compile-state (await (ensure-compile-state! state))
        load-error
        (try
          (await (install-program! state invocation program symbols false))
          nil
          (catch :default exception
            (exception-value exception)))]
    (cond-> {::compile-state (or (::compile-state @state) compile-state)
             ::program program
             ::configuration configuration}
      load-error (assoc ::program-load-error load-error))))

(defn- selected-call-error
  [function-symbol exception]
  (let [fault (error/fault-for function-symbol)]
    (when (= :core fault)
      (error/record! {::error/raw exception ::error/fault :core}))
    {::ok? false ::error (exception-value exception)}))

(defn- selected-load-error
  [exception]
  (if (true? (::reload-required? (ex-data exception)))
    (throw exception)
    exception))

(declare invoke-selected!)

(defn- call-selected!
  [state invocation {::keys [function-symbol arguments invoke-selected?]}]
  (if-let [function-value (eval/lookup-value function-symbol)]
    (let [authored? (contains? (::authored-symbols @state) function-symbol)
          arguments (cond-> arguments
                      (and invoke-selected? (not authored?))
                      (conj (partial invoke-selected! state invocation)))]
      (try
        (-> (js/Promise.resolve (apply function-value arguments))
          (.then (fn [value]
                   (cond-> {::ok? true ::value value}
                     authored?
                     (assoc ::source
                            (get-in @state [::program ::source-by-symbol
                                            function-symbol])))))
          (.catch #(selected-call-error function-symbol %)))
        (catch :default exception
          (js/Promise.resolve
           (selected-call-error function-symbol exception)))))
    (let [exception
          (ex-info "The selected function is not loaded in the execution child."
                   {:seon.error/kind :core-bug
                    ::function-symbol function-symbol})]
      (js/Promise.resolve
       (selected-call-error function-symbol exception)))))

(defn- ^:async invoke-selected!
  "Invoke selected compiled or authored functions inside the active child."
  [state invocation calls]
  (let [authored (::authored-symbols @state)
        unresolved
        (->> calls
             (map ::function-symbol)
             distinct
             (remove #(or (contains? authored %)
                          (some? (eval/lookup-value %))))
             vec)
        load-error (atom nil)]
    (when (seq unresolved)
      (try
        (await (ensure-program! state invocation unresolved false))
        (catch :default exception
          (reset! load-error (selected-load-error exception)))))
    (-> (js/Promise.all
         (into-array
          (mapv (fn [{::keys [function-symbol] :as call}]
                  (if (and @load-error
                           (nil? (eval/lookup-value function-symbol)))
                    (js/Promise.resolve
                     (selected-call-error function-symbol @load-error))
                    (call-selected! state invocation call)))
                calls)))
        (.then #(vec (array-seq %))))))

;;; Child owner

(defn- exception-value [exception]
  (let [data (ex-data exception)
        wire-data
        (when (map? data)
          (into {}
                (filter (fn [[_ value]]
                          (db.protocol/ordinary-wire-value? value)))
                data))]
    (cond-> {:seon.error/message (error/->message exception)
             :seon.error/kind (or (:seon.error/kind data) :agent)}
      (seq wire-data) (assoc :seon.error/data wire-data))))

(defn- terminal-message [invocation bounded]
  (if (::ok? bounded)
    {::message result-message
     ::protocol-version protocol-version
     ::invocation-id (::invocation-id invocation)
     :seon.db/db (:seon.db/db invocation)
     ::result (::value bounded)
     ::result-bytes (::result-bytes bounded)}
    {::message error-message
     ::protocol-version protocol-version
     ::invocation-id (::invocation-id invocation)
     :seon.db/db (:seon.db/db invocation)
     ::error (::error bounded)}))

(defn- send! [send-message! message]
  (when-not (valid-child-message? message)
    (throw (ex-info "The child attempted to send an invalid IPC message."
                    {::message message
                     :seon.error/kind :core-bug})))
  (send-message! (encode-message message)))

(defn- settle-active!
  [state token send-message! message]
  (if (identical? token (get-in @state [::active ::token]))
    (do
      (when-let [timer (get-in @state [::active ::timer])]
        (js/clearTimeout timer))
      (send! send-message! message)
      (swap! state
             (fn [current]
               (if (identical? token (get-in current [::active ::token]))
                 (dissoc current ::active)
                 current)))
      true)
    false))

(defn- fail-invocation!
  [state invocation send-message! message kind]
  (send! send-message!
         {::message error-message
          ::protocol-version protocol-version
          ::invocation-id (::invocation-id invocation)
          :seon.db/db (:seon.db/db invocation)
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
    :seon.db/db (:seon.db/db invocation)
   ::error {:seon.error/message "The invocation timed out."
             :seon.error/kind :agent
             :seon.error/data {::child-retired? true}}})
  (exit! 1))

(defn- begin-invocation!
  [state invocation send-message! exit! now-ms]
  (let [current @state
        identity (::function-identity invocation)
        function-symbol (::function-symbol identity)
        compiled? (contains? identity ::artifact-digest)
        compiled-descriptor (when compiled?
                              (get (::compiled-functions current)
                                   function-symbol))
        compiled-function (::compiled-function compiled-descriptor)
        pin-database? (true? (::pin-database? compiled-descriptor))
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
           (or (nil? compiled-function)
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
                       (ensure-program! state invocation
                                        [function-symbol] true))))
            (.then
             (fn [_]
               (if-let [function-value
                        (if compiled?
                          compiled-function
                          (eval/lookup-value function-symbol))]
                 (try
                   (let [value
                         (db/with-agent
                          (::agent-id invocation)
                          (fn []
                            (db/with-tx-context
                             (cond-> (or (::run-fence invocation) {})
                               pin-database?
                               (assoc :seon.db/db (:seon.db/db invocation)))
                             (fn []
                               (if compiled?
                                 (function-value
                                  (::arguments invocation)
                                  (partial invoke-selected! state invocation)
                                  (partial ensure-compile-state! state)
                                  (partial prepare-eval-program! state invocation))
                                 (apply function-value
                                        (::arguments invocation)))))))]
                     (js/Promise.resolve value))
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
                  (terminal-message invocation bounded)))))
            (.catch
             (fn [exception]
               (settle-active!
                state token send-message!
                (terminal-message
                 invocation
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
        :seon.db/db (get-in active [::invocation :seon.db/db])
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
  [compiled-functions startup send-message! on-message! exit!]
  (let [state (atom {::startup startup
                     ::compiled-functions compiled-functions})]
    (-> (db/open-session! (::database-selection startup))
        (.then
         (fn [{database :seon.db/db}]
           (-> (error/with-configuration
                {:seon.config/on-core-error :gate}
                #(admission/prepare-committed!
                  {::admission/record-failures? false
                   ::admission/instrument? false}))
               (.then
                admission/admit-prepared!)
               (.then
                (fn [{::admission/keys [published?] :as publication}]
                  (when-not published?
                    (throw
                     (ex-info "The execution child could not publish the committed program."
                              {:seon.runtime.admission/publication publication
                               :seon.error/kind :core-bug})))
                  (send! send-message!
                         {::message ready-message
                          ::protocol-version protocol-version
                          ::agent-id (::agent-id startup)
                          ::bun-version (or (.-version js/Bun) "unknown")
                          ::shadow-build-id (::shadow-build-id startup)
                          ::artifact-digest (::artifact-digest startup)
                          :seon.db/db database})
                  (on-message!
                   (fn [encoded]
                     (receive! state encoded send-message! exit!
                               (.now js/Date))))
                  state))))))))

(defn- valid-compiled-functions?
  [compiled-functions]
  (schema/valid-candidate-value? ::compiled-functions compiled-functions))

(defn -main
  "Attach one Bun child to its database and serve parent IPC."
  [compiled-functions]
  (when-not (valid-compiled-functions? compiled-functions)
    (throw
     (ex-info "The execution artifact must supply a closed compiled function map."
              {:seon.error/kind :core-bug})))
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
                            (= actual-artifact-digest
                               (::artifact-digest startup)))
               (throw
                (ex-info "The execution child startup identity is invalid."
                         {::startup startup
                          ::actual-artifact-digest actual-artifact-digest
                          :seon.error/kind :core-bug})))
             (start-child! compiled-functions startup
                           send-message! on-message! exit!)))
          (.catch
           (fn [exception]
             (send! send-message!
                    {::message error-message
                     ::protocol-version protocol-version
                     ::invocation-id "startup"
                     ::error (exception-value exception)})
             (exit! 1)))))))
