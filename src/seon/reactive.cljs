(ns seon.reactive
  "Registered reactive reads over immutable database values.

   Each registration owns one writer interest, one active computation, and one
   newest pending database value. Datahike interprets captured read evidence;
   this namespace only schedules demanded recomputation and delivers changed
   Clojure values."
  (:require
   [seon.config :as config]
   [seon.db :as db]
   [seon.error :as error]
   [seon.log :as log]
   [seon.schema :as schema]))

(schema/register! ::key :any)
(schema/register! ::consumer-key :any)
(schema/register! ::compute 'fn?)
(schema/register! ::notify 'fn?)
(schema/register! ::settle-ms 'fn?)
(schema/register! ::failed? :boolean)
(schema/register!
 ::observe-request
 [:map {:closed true}
  [::key ::key]
  [::consumer-key ::consumer-key]
  [::compute ::compute]
  [::notify ::notify]
  [::settle-ms {:optional true} ::settle-ms]
  [::db {:optional true} :seon.db/db]])
(schema/register!
 ::unobserve-request
 [:map {:closed true}
  [::key ::key]
  [::consumer-key ::consumer-key]])

(def ^:private empty-runtime {::registrations {} ::measurements {}})

(defonce ^{:private true
           :doc "Process-local owners for demanded reactive computations."}
  !runtime (atom empty-runtime))

(defonce ^{:private true
           :doc "Reactive timing acquired from database configuration."}
  !policy (atom config/default-reactive-policy))

(defn- registration-gauges [runtime]
  (let [registrations (vals (::registrations runtime))]
    {::registration-count (count registrations)
     ::active-count (count (filter ::active registrations))
     ::pending-count (count (filter ::pending-db registrations))
     ::timer-count (count (filter ::timer registrations))
     ::consumer-count (reduce + 0 (map (comp count ::consumers)
                                       registrations))}))

(defn- record-count! [measurement amount]
  (swap! !runtime update-in [::measurements measurement] (fnil + 0) amount))

(defn- record-basis! [measurement database]
  (when-let [basis-transaction (:t database)]
    (swap! !runtime assoc-in [::measurements measurement] basis-transaction)))

(defn- record-high-water! []
  (swap! !runtime
         (fn [runtime]
           (let [{::keys [active-count pending-count]}
                 (registration-gauges runtime)]
             (-> runtime
                 (update-in [::measurements ::active-high-water]
                            (fnil max 0) active-count)
                 (update-in [::measurements ::pending-high-water]
                            (fnil max 0) pending-count))))))

(defn configure!
  "Install the database-acquired reactive timing policy."
  {:malli/schema [:=> [:cat :seon.config/reactive] :nil]}
  [policy]
  (reset! !policy policy)
  nil)

(defn- monotonic-ms [] (.now js/performance))
(defn- set-timer! [callback delay-ms] (js/setTimeout callback delay-ms))
(defn- clear-timer! [timer] (js/clearTimeout timer))

(defn- newer-database [left right]
  (cond
    (nil? left) right
    (nil? right) left
    (> (:t right) (:t left)) right
    :else left))

(defn- branch-identity [database]
  (select-keys database [:db-name :store-id :as-of :since :history]))

(defn- evidence-signature [read-evidence]
  (if (= :all read-evidence)
    :all
    (into #{}
          (map (fn [entry]
                 [(branch-identity (::db/db entry))
                  (::db/source-argument-position entry)
                  (:datahike.read/dependency-plan entry)]))
          read-evidence)))

(defn- interest-key [key]
  [::registration key])

(defn- error-value [exception]
  (let [value (error/->map exception)]
    (cond-> value
      (nil? (:seon.error/kind value))
      (assoc :seon.error/kind :core-bug))))

(defn- result-envelope [result]
  (let [envelope
        (if (and (map? result)
                 (contains? result ::db/value)
                 (or (= :all (::db/read-evidence result))
                     (vector? (::db/read-evidence result))))
          result
          {::db/value
           (if (and (map? result) (:seon.error/message result))
             result
             {:seon.error/message
              "Reactive computation returned no read evidence."
              :seon.error/kind :core-bug})
           ::db/read-evidence :all
           ::failed? true})]
    (cond-> envelope
      (and (map? (::db/value envelope))
           (:seon.error/message (::db/value envelope)))
      (assoc ::failed? true))))

(defn- notify-one! [notify value]
  (try
    (let [result (notify value)]
      (when (and result (= "function" (goog/typeOf (.-catch result))))
        (.catch result
                #(log/error-console! "seon.reactive"
                                     "reactive consumer rejected" %))))
    (catch :default exception
      (log/error-console! "seon.reactive"
                          "reactive consumer threw" exception))))

(defn- notify-consumers! [registration value]
  (let [consumers (vals (::consumers registration))]
    (doseq [notify consumers]
      (notify-one! notify value))
    (record-count! ::notifications-delivered (count consumers))))

(declare enqueue! install-interest! start-evaluation!)

(defn- settle-delay [registration event]
  (let [configured (:seon.config/reactive-settle-ms @!policy)]
    (if-let [select-delay (::settle-ms registration)]
      (try
        (max 0 (or (select-delay event @!policy) configured))
        (catch :default _ configured))
      configured)))

(defn- due-at [registration now]
  (min (+ (::dirty-at registration)
          (:seon.config/reactive-max-latency-ms @!policy))
       (+ now (::pending-settle-ms registration))))

(defn- arm! [key registration-id]
  (let [registration (get-in @!runtime [::registrations key])]
    (when (and (= registration-id (::registration-id registration))
               (::pending-db registration)
               (nil? (::active registration)))
      (let [now (monotonic-ms)
            target-due-at (due-at registration now)
            prior-timer (::timer registration)
            prior-due-at (::due-at registration)]
        (when-not (and prior-timer (= target-due-at prior-due-at))
          (let [token (random-uuid)
                timer (set-timer!
                       #(start-evaluation! key registration-id token)
                       (max 0 (- target-due-at now)))]
            (swap! !runtime
                   (fn [runtime]
                     (let [current (get-in runtime [::registrations key])]
                       (if (= registration-id (::registration-id current))
                         (assoc-in runtime [::registrations key]
                                   (assoc current
                                          ::timer timer
                                          ::timer-token token
                                          ::due-at target-due-at))
                         runtime))))
            (when prior-timer (clear-timer! prior-timer))))))))

(def ^:private error-evidence-transaction-attributes
  (into error/persisted-attributes
        #{:db/txInstant :seon.db/user :seon.db/process}))

(defn- error-evidence-event?
  [event]
  (let [attributes (into #{} (keep #(when (vector? %) (nth % 1 nil)))
                         (:tx-data event))]
    (and (contains? attributes :seon.error/fault)
         (every? error-evidence-transaction-attributes attributes))))

(defn- enqueue!
  [key registration-id database event]
  (let [now (monotonic-ms)
        error-evidence? (error-evidence-event? event)
        [before after]
        (swap-vals!
         !runtime
         (fn [runtime]
           (let [path [::registrations key]
                 registration (get-in runtime path)]
             (if (= registration-id (::registration-id registration))
               (assoc-in
                runtime path
                (-> registration
                    (assoc ::pending-db
                           (newer-database (::pending-db registration)
                                           database))
                    (assoc ::dirty-at (or (::dirty-at registration) now))
                    (assoc ::pending-settle-ms
                           (max (or (::pending-settle-ms registration) 0)
                                (settle-delay registration event)))
                    (assoc ::pending-error-evidence-only?
                           (and error-evidence?
                                (or (nil? (::pending-db registration))
                                    (::pending-error-evidence-only?
                                     registration))))))
               runtime))))
        prior (get-in before [::registrations key])
        registration (get-in after [::registrations key])]
    (when (and (::pending-db prior)
               (> (:t database) (:t (::pending-db prior))))
      (record-count! ::newest-pending-replacements 1))
    (record-high-water!)
    (when (and (= registration-id (::registration-id registration))
               (nil? (::active registration)))
      (arm! key registration-id))))

(defn- transaction! [key registration-id install-id event]
  (let [registration (get-in @!runtime [::registrations key])]
    (when (and (= registration-id (::registration-id registration))
               (= install-id (::interest-install-id registration)))
      (when-let [database (:db-after event)]
        (let [error-evidence? (error-evidence-event? event)]
          (if (and (::failed? registration) error-evidence?)
            (record-count! ::failure-evidence-events-suppressed 1)
            (enqueue! key registration-id database event)))))))

(defn- resolve-ready! [registration value]
  (when-let [resolve (::resolve-ready registration)]
    (resolve value)))

(defn- fail-registration! [key registration-id failure]
  (let [[before after]
        (swap-vals!
         !runtime
         (fn [runtime]
           (let [registration (get-in runtime [::registrations key])]
             (if (= registration-id (::registration-id registration))
               (assoc-in runtime [::registrations key]
                         (-> registration
                             (dissoc ::active ::pending-db ::dirty-at
                                     ::pending-settle-ms
                                     ::pending-error-evidence-only? ::timer
                                     ::timer-token ::due-at)
                             (assoc ::delivered? true
                                    ::value failure)))
               runtime))))
        prior (get-in before [::registrations key])
        current (get-in after [::registrations key])]
    (when (and (= registration-id (::registration-id prior))
               (= registration-id (::registration-id current)))
      (notify-consumers! current failure)
      (resolve-ready! current failure))))

(defn- interest-installed!
  [key registration-id install-id basis-db signature initial?]
  (let [registration (get-in @!runtime [::registrations key])]
    (when (and (= registration-id (::registration-id registration))
               (= install-id (::interest-install-id registration)))
      (.catch
       (.then
        (db/db)
        (fn [ack-db]
          (if (:seon.error/message ack-db)
            (fail-registration! key registration-id ack-db)
            (do
              (swap! !runtime
                     (fn [runtime]
                       (let [current (get-in runtime [::registrations key])]
                         (if (and (= registration-id
                                     (::registration-id current))
                                  (= install-id
                                     (::interest-install-id current)))
                           (assoc-in runtime [::registrations key]
                                     (assoc current
                                            ::installed-signature signature
                                            ::interest-db ack-db))
                           runtime))))
              (record-basis! ::last-installed-interest-t ack-db)
              (if initial?
                (do
                  (enqueue! key registration-id ack-db nil)
                  (start-evaluation! key registration-id nil))
                (when (> (:t ack-db) (:t basis-db))
                  (enqueue! key registration-id ack-db nil)))))))
       #(fail-registration! key registration-id (error-value %))))))

(defn- install-interest!
  [key registration-id basis-db read-evidence initial?]
  (let [install-id (random-uuid)
        signature (evidence-signature read-evidence)
        request
        (cond-> {::db/key (interest-key key)
                 ::db/db basis-db
                 ::db/handler #(transaction! key registration-id install-id %)}
          (= :all read-evidence)
          (assoc ::db/dependency-plan :all)

          (vector? read-evidence)
          (assoc ::db/read-evidence read-evidence))]
    (swap! !runtime
           (fn [runtime]
             (let [registration (get-in runtime [::registrations key])]
               (if (= registration-id (::registration-id registration))
                 (assoc-in runtime [::registrations key]
                           (assoc registration ::interest-install-id install-id))
                 runtime))))
    (if (and (vector? read-evidence) (empty? read-evidence))
      (-> (db/unlisten! {::db/key (interest-key key)})
          (.then (fn [_]
                   (swap! !runtime
                          (fn [runtime]
                            (let [registration
                                  (get-in runtime [::registrations key])]
                              (if (and (= registration-id
                                          (::registration-id registration))
                                       (= install-id
                                          (::interest-install-id registration)))
                                (assoc-in runtime [::registrations key]
                                          (assoc registration
                                                 ::installed-signature #{}))
                                runtime))))))
          (.catch #(fail-registration! key registration-id (error-value %))))
      (-> (db/listen! request)
          (.then
           (fn [result]
             (if (= (interest-key key) result)
               (interest-installed! key registration-id install-id basis-db
                                    signature initial?)
               (fail-registration! key registration-id result))))
          (.catch #(fail-registration! key registration-id
                                      (error-value %)))))))

(defn- finish-evaluation!
  [key registration-id evaluation-id basis-db raw-result]
  (let [result (result-envelope raw-result)
        value (::db/value result)
        read-evidence (::db/read-evidence result)
        failed? (true? (::failed? result))
        signature (evidence-signature read-evidence)
        [before after]
        (swap-vals!
         !runtime
         (fn [runtime]
           (let [path [::registrations key]
                 registration (get-in runtime path)]
             (if (and (= registration-id (::registration-id registration))
                      (= evaluation-id (get-in registration
                                               [::active ::evaluation-id])))
               (let [pending-error-db
                     (when (and failed?
                                (::pending-error-evidence-only? registration))
                       (::pending-db registration))]
                 (assoc-in
                  runtime path
                  (cond->
                   (-> registration
                       (dissoc ::active ::timer ::timer-token ::due-at)
                       (assoc ::delivered? true
                              ::failed? failed?
                              ::value value
                              ::basis-db (or pending-error-db basis-db)
                              ::read-evidence read-evidence))
                    pending-error-db
                    (dissoc ::pending-db ::dirty-at ::pending-settle-ms
                            ::pending-error-evidence-only?))))
               runtime))))
        prior (get-in before [::registrations key])
        registration (get-in after [::registrations key])
        completed? (and (= registration-id (::registration-id registration))
                        (= evaluation-id
                           (get-in prior [::active ::evaluation-id])))]
    (when completed?
      (record-count! ::evaluations-completed 1)
      (record-basis! ::last-completed-t basis-db)
      (if (or (not (::delivered? prior))
              (not= (::value prior) value))
        (notify-consumers! registration value)
        (record-count! ::equal-notifications-suppressed 1))
      (resolve-ready! registration value)
      (swap! !runtime update-in [::registrations key]
             dissoc ::resolve-ready)
      (if (= signature (::installed-signature prior))
        (when (::pending-db registration)
          (arm! key registration-id))
        (install-interest! key registration-id (::basis-db registration)
                           read-evidence false)))))

(defn- start-evaluation! [key registration-id timer-token]
  (let [evaluation-id (random-uuid)
        [before after]
        (swap-vals!
         !runtime
         (fn [runtime]
           (let [path [::registrations key]
                 registration (get-in runtime path)
                 token-valid? (or (nil? timer-token)
                                  (= timer-token (::timer-token registration)))]
             (if (and (= registration-id (::registration-id registration))
                      token-valid?
                      (nil? (::active registration))
                      (::pending-db registration))
               (assoc-in
                runtime path
                (-> registration
                    (assoc ::active
                           {::evaluation-id evaluation-id
                            ::db (::pending-db registration)})
                    (dissoc ::pending-db ::dirty-at ::pending-settle-ms
                            ::pending-error-evidence-only?
                            ::timer ::timer-token ::due-at)))
               runtime))))
        prior (get-in before [::registrations key])
        registration (get-in after [::registrations key])
        active (::active registration)]
    (when (and (= registration-id (::registration-id registration))
               (= evaluation-id (::evaluation-id active)))
      (record-count! ::evaluations-started 1)
      (record-high-water!)
      (when-let [timer (::timer prior)] (clear-timer! timer))
      (let [database (::db active)]
        (-> (js/Promise.resolve nil)
            (.then (fn [_] ((::compute registration) database)))
            (.then #(finish-evaluation! key registration-id evaluation-id
                                       database %))
            (.catch #(finish-evaluation!
                      key registration-id evaluation-id database
                      {::db/value (error-value %)
                       ::db/read-evidence :all
                       ::failed? true})))))))

(defn ^:async observe!
  "Attach one consumer to a registered reactive computation.

   The first consumer installs an all-attributes interest before evaluating.
   Later consumers receive the established current value immediately."
  {:malli/schema [:=> [:cat ::observe-request] ::consumer-key]}
  [{::keys [key consumer-key compute notify settle-ms]
    database ::db/db}]
  (let [database (or database (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [registration-id (random-uuid)
            resolve-ready (atom nil)
            ready (js/Promise. (fn [resolve _] (reset! resolve-ready resolve)))
            [before after]
            (swap-vals!
             !runtime
             (fn [runtime]
               (let [path [::registrations key]
                     existing (get-in runtime path)]
                 (if existing
                   (assoc-in runtime path
                             (assoc-in existing [::consumers consumer-key]
                                       notify))
                   (assoc-in runtime path
                             (cond->
                              {::registration-id registration-id
                               ::key key
                               ::compute compute
                               ::consumers {consumer-key notify}
                               ::ready ready
                               ::resolve-ready @resolve-ready}
                               settle-ms (assoc ::settle-ms settle-ms)))))))
            prior (get-in before [::registrations key])
            registration (get-in after [::registrations key])]
        (if prior
          (do
            (when (::delivered? registration)
              (notify-one! notify (::value registration))
              (record-count! ::notifications-delivered 1))
            (when-not (::delivered? registration)
              (await (::ready registration)))
            consumer-key)
          (do
            (install-interest! key registration-id database :all true)
            (await ready)
            consumer-key))))))

(defn ^:async unobserve!
  "Detach one consumer and release all registration state after the last one."
  {:malli/schema [:=> [:cat ::unobserve-request] :boolean]}
  [{::keys [key consumer-key]}]
  (let [[before after]
        (swap-vals!
         !runtime
         (fn [runtime]
           (let [path [::registrations key]
                 registration (get-in runtime path)
                 remaining (dissoc (::consumers registration) consumer-key)]
             (cond
               (nil? registration) runtime
               (seq remaining) (assoc-in runtime path
                                         (assoc registration
                                                ::consumers remaining))
               :else (update runtime ::registrations dissoc key)))))
        prior (get-in before [::registrations key])
        remaining (get-in after [::registrations key])]
    (when (and prior (nil? remaining))
      (when-let [timer (::timer prior)] (clear-timer! timer))
      (resolve-ready!
       prior
       {:seon.error/message
        "Reactive registration was released before its first value."
        :seon.error/kind :canceled})
      (await (db/unlisten! {::db/key (interest-key key)})))
    (boolean prior)))

(defn ^:async close!
  "Release every reactive computation, timer, value, and database interest."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (let [[before _] (reset-vals! !runtime empty-runtime)]
    (doseq [[_key registration] (::registrations before)]
      (when-let [timer (::timer registration)] (clear-timer! timer))
      (resolve-ready!
       registration
       {:seon.error/message
        "Reactive runtime closed before the first value was available."
        :seon.error/kind :canceled}))
    (await
     (js/Promise.all
      (clj->js
       (mapv (fn [key]
               (db/unlisten! {::db/key (interest-key key)}))
             (keys (::registrations before))))))
    nil))

(defn measurements
  "Return bounded ownership counts for tests and runtime diagnostics."
  {:malli/schema [:=> [:cat] :map]}
  []
  (merge (::measurements @!runtime)
         (registration-gauges @!runtime)))

(defn reset-measurements!
  "Reset bounded reactive totals without changing registrations or work."
  {:malli/schema [:=> [:cat] :map]}
  []
  (swap! !runtime assoc ::measurements {})
  (measurements))
