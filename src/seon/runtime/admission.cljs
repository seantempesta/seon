(ns seon.runtime.admission
  "One process-local admission boundary for executable runtime work.

   Canonical program/schema facts remain database truth. This namespace owns
   only whether the current process has reconstructed and verified one exact
   committed generation. Closing admission hides process-local wrapper and
   projection surgery from agent, schedule, and web execution boundaries."
  (:require
    [cljs.reader :as reader]
    [seon.db :as db]
    [seon.error :as error]
    [seon.instrument :as instrument]
    [seon.schema :as schema]))

(schema/register! ::status
  [:enum :starting :publishing :available :unavailable])
(schema/register! ::generation :int)
(schema/register! ::reason :string)
(schema/register! ::admitted? :boolean)
(schema/register! ::published? :boolean)
(schema/register! ::recovered? :boolean)
(schema/register! ::state
  [:map
   [::status ::status]
   [::generation {:optional true} ::generation]
   [::reason {:optional true} ::reason]])

(defonce ^:private !state
  (atom {::status :starting}))

(defn state
  "Immutable process admission state.

   The optional generation is the accepted schema projection fingerprint, not
   a second counter or durable program identity."
  {:malli/schema [:=> [:cat] ::state]}
  []
  @!state)

(defn available?
  "True only after the process has verified one committed generation."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (= :available (::status @!state)))

(defn unavailable
  "Typed refusal returned by executable boundaries while admission is closed.

   Refusal is observation only. It never records another core fault."
  {:malli/schema [:=> [:cat]
                  [:map
                   [::admitted? [:= false]]
                   [:seon/error :map]]]
   :seon.fn/agent-facing? false}
  []
  (let [{::keys [status generation reason]} @!state]
    {::admitted? false
     :seon/error
     (cond->
       {:seon.error/kind :seon.runtime/unavailable
        :seon.error/message
        "Runtime program generation is unavailable; inspect the recorded core fault and restart after repairing canonical program facts."
        :seon.error/data {::status status}}
       generation
       (assoc-in [:seon.error/data ::generation] generation)

       reason
       (assoc-in [:seon.error/data ::reason] reason))}))

(defn begin-publication!
  "Synchronously close executable admission for one publication transition.

   Returns true only to the caller that changed `:starting` or `:available` to
   `:publishing`. Concurrent/repeated callers do not acquire ownership."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (#{:starting :available} status)
              {::status :publishing
               ::generation (::generation current)}
              current)))]
    (and (not= before after)
         (= :publishing (::status after)))))

(defn- transition-unavailable!
  [reason generation]
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (= :publishing status)
              (cond-> {::status :unavailable ::reason reason}
                generation (assoc ::generation generation))
              current)))]
    (and (= :publishing (::status before))
         (= :unavailable (::status after)))))

(defn mark-unavailable!
  "Fail closed after an owned publication occurrence.

   The first transition from `:publishing` records one core fault. Repeated
   calls and boundary refusals are idempotent and never create an error census."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.error/raw :any]
      [::reason ::reason]
      [::generation {:optional true} ::generation]]]
    :boolean]}
  [{raw :seon.error/raw ::keys [reason generation]}]
  (let [owned? (transition-unavailable! reason generation)]
    (when owned?
      (error/record! {:seon.error/raw raw :seon.error/fault :core}))
    owned?))

(defn- admit-generation!
  "Open admission for the verified committed projection fingerprint."
  [generation]
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (= :publishing status)
              {::status :available ::generation generation}
              current)))]
    (and (= :publishing (::status before))
         (= :available (::status after)))))

(defn ^:no-doc committed-projection
  "Build the canonical projection from one already-frozen database value."
  {:malli/schema [:=> [:catn [::database :any]] :map]}
  [database]
  (let [forms
        (into {}
              (map (fn [[key form]]
                     [key (reader/read-string form)]))
              (db/query
                '[:find ?key ?form
                  :where
                  [?schema :seon.schema/key ?key]
                  [?schema :seon.schema/form ?form]]
                database))
        function-contracts
        (into {}
              (map (fn [[sym form]]
                     [(symbol sym) (reader/read-string form)]))
              (db/query
                '[:find ?sym ?form
                  :where
                  [?function :seon.fn/sym ?sym]
                  [?function :seon.fn/spec ?form]]
                database))]
    (schema/build-projection forms function-contracts)))

(defn- reconcile-committed!
  [database old-projection]
  (let [projection (committed-projection database)
        stats
        (instrument/reconcile-projection!
          {::instrument/old-projection old-projection
           ::instrument/new-projection projection})]
    (when (false? (::instrument/ok? stats))
      (throw
        (ex-info
          "Committed program generation failed complete wrapper verification"
          {:seon.instrument/stats stats
           ::generation
           (:seon.schema.projection/fingerprint projection)})))
    (schema/activate-projection! projection)
    {::projection projection
     ::instrumentation stats
     ::generation (:seon.schema.projection/fingerprint projection)}))

(defn publish-committed!
  "Reconstruct, reconcile, verify, and admit the current committed program.

   Normal callers acquire publication here. Cold boot may call
   [[begin-publication!]] before replaying stored namespaces, then invoke this
   function while the state is already `:publishing`. One failed attempt is
   recorded once and repaired from a newly frozen current database value. The
   previous generation is never reopened after a commit."
  {:malli/schema
   [:=> [:cat]
    [:map
     [::published? :boolean]
     [::recovered? :boolean]
     [::generation {:optional true} ::generation]
     [::instrumentation {:optional true} :map]
     [:seon/error {:optional true} :map]]]}
  []
  (let [owned? (or (= :publishing (::status @!state))
                   (begin-publication!))]
    (if-not owned?
      (assoc (unavailable) ::published? false ::recovered? false)
      (let [old-projection (schema/current-projection)]
        (try
          (let [{::keys [generation instrumentation]}
                (reconcile-committed! @db/*conn* old-projection)]
            (admit-generation! generation)
            {::published? true
             ::recovered? false
             ::generation generation
             ::instrumentation instrumentation})
          (catch :default original
            ;; The occurrence is one fault whether reconstruction repairs it
            ;; or the process remains unavailable. Boundary refusals never
            ;; write another row.
            (error/record!
              {:seon.error/raw original :seon.error/fault :core})
            (try
              (let [{::keys [generation instrumentation]}
                    (reconcile-committed! @db/*conn* old-projection)]
                (admit-generation! generation)
                {::published? true
                 ::recovered? true
                 ::generation generation
                 ::instrumentation instrumentation})
              (catch :default repair
                (let [generation
                      (or (::generation (ex-data repair))
                          (::generation @!state))
                      reason
                      (str "Committed program reconstruction failed: "
                           (or (.-message repair) (str repair)))]
                  (transition-unavailable! reason generation)
                  {::published? false
                   ::recovered? false
                   ::generation generation
                   :seon/error
                   (:seon/error (unavailable))})))))))))
