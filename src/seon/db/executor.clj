(ns seon.db.executor
  "One bounded capacity and fairness owner for JVM database work."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.set :as set]
            [seon.db.branch :as branch]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::request :map)
(schema/register! ::connection-id [:tuple :uuid :keyword])
(schema/register! ::generation :uuid)
(schema/register! ::scope
                  [:map {:closed true}
                   [::database-name ::database-name]
                   [::branch/connection-id ::branch/connection-id]
                   [::connection-id ::connection-id]
                   [::generation ::generation]])
(schema/register! ::scopes [:set {:min 1} ::scope])
(schema/register! ::job-id
                  [:or ::scope
                   :seon.db.protocol/request-id
                   [:tuple :seon.db.protocol/request-id [:or :keyword :int]]])
(schema/register! ::request-id :seon.db.protocol/request-id)
(schema/register! ::work-class :keyword)
(schema/register! ::request-bytes [:int {:min 0}])
(schema/register! ::reserved-work-class ::work-class)
(schema/register! ::reserved-request-bytes [:int {:min 0}])
(schema/register! ::maximum-active [:int {:min 1}])
(schema/register! ::maximum-queued [:int {:min 1}])
(schema/register! ::maximum-queued-by-database [:int {:min 1}])
(schema/register! ::accepted? :boolean)
(schema/register! ::joined? :boolean)
(schema/register! ::abandoned-count [:int {:min 0}])
(schema/register! ::execute [:map-of ::work-class 'fn?])
(schema/register! ::complete! 'fn?)
(schema/register! ::outcome [:tuple [:enum ::value ::throwable] :any])
(schema/register! ::completion
                  [:map
                   [::job-id ::job-id]
                   [::request-id {:optional true} ::request-id]
                   [::outcome ::outcome]])
(schema/register! ::capacity :map)
(schema/register! ::executor 'map?)
(schema/register! ::start-request
                  [:map [::capacity ::capacity]
                   [::execute ::execute]
                   [::complete! {:optional true} ::complete!]])
(schema/register! ::submit-request
                  [:map
                   [::executor ::executor]
                   [::work-class ::work-class]
                   [::database-name ::database-name]
                   [::scope ::scope]
                   [::scopes {:optional true} ::scopes]
                   [::job-id ::job-id]
                   [::request-id {:optional true} ::request-id]
                   [::request ::request]
                   [::reserved-work-class {:optional true} ::reserved-work-class]
                   [::reserved-request-bytes {:optional true}
                    ::reserved-request-bytes]
                   [::request-bytes {:optional true} ::request-bytes]])
(schema/register! ::remove-database-request
                  [:map [::executor ::executor] [::scope ::scope]])
(schema/register! ::cancel 'fn?)
(schema/register! ::cancellation [:enum :not-found :queued :running])
(schema/register! ::cancel-request [:map [::executor ::executor] [::job-id ::job-id]])
(schema/register! ::cancel-response
                  [:map [::cancellation ::cancellation]
                   [::request {:optional true} ::request]])
(schema/register! ::fence-and-drain-request
                  [:map [::executor ::executor] [::scope ::scope]
                   [::cancel ::cancel]
                   [::abandon-work-classes {:optional true} [:set ::work-class]]])
(schema/register! ::remove-database-response
                  [:map [::abandoned-count ::abandoned-count]])
(schema/register! ::stop-request [:map [::executor ::executor]])
(schema/register! ::stopped? :boolean)
(schema/register! ::stop-response [:map [::stopped? ::stopped?]])
(schema/register! ::queued [:int {:min 0}])
(schema/register! ::running [:int {:min 0}])
(schema/register! ::running-by-database [:map-of ::database-name ::running])
(schema/register! ::running-by-class [:map-of ::work-class ::running])
(schema/register! ::queued-by-class [:map-of ::work-class ::queued])
(schema/register! ::queued-request-bytes [:int {:min 0}])
(schema/register! ::retained-identities [:int {:min 0}])
(schema/register! ::fenced-scopes [:int {:min 0}])
(schema/register! ::fenced [:int {:min 0}])
(schema/register! ::completed [:int {:min 0}])
(schema/register! ::rejected [:int {:min 0}])
(schema/register! ::evidence
                  [:map
                   [::capacity ::capacity]
                   [::queued ::queued]
                   [::queued-by-class ::queued-by-class]
                   [::queued-request-bytes ::queued-request-bytes]
                   [::running ::running]
                   [::running-by-class ::running-by-class]
                   [::running-by-database ::running-by-database]
                   [::retained-identities ::retained-identities]
                   [::fenced-scopes ::fenced-scopes]
                   [::fenced ::fenced]
                   [::completed ::completed]
                   [::rejected ::rejected]
                   [::stopped? ::stopped?]])

(def ^:private empty-queue clojure.lang.PersistentQueue/EMPTY)
(def ^:private cpu-classes #{:read :knn :hnsw :delivery})
(def ^:private serialized-classes #{:mutation :delivery})

(defn capacity
  "Return one immutable authority capacity map from the selected processors."
  ([] (capacity (.availableProcessors (Runtime/getRuntime))))
  ([selected-processors]
   (let [processors (max 1 selected-processors)
         cpu-workers (max 1 (dec processors))
         knn (max 1 (min 2 (quot cpu-workers 2)))
         mutation (max 1 (min 4 (quot (inc processors) 2)))
         provider (min 6 processors)
         read-queue (max 16 (* 8 cpu-workers))
         read-database-queue (min read-queue
                                  (max 16 (* 4 cpu-workers)))]
     {::available-processors (.availableProcessors (Runtime/getRuntime))
      ::selected-processors processors
      ::cpu-workers cpu-workers
      ::maximum-request-bytes (+ Integer/BYTES protocol/maximum-frame-bytes)
      ::maximum-queued-request-bytes (* (cond (<= processors 2) 8
                                                (<= processors 4) 16
                                                :else 32)
                                             1024 1024)
      ::classes
      {:read {::maximum-active cpu-workers
              ::maximum-queued read-queue
              ::maximum-queued-by-database read-database-queue}
       :knn {::maximum-active knn
             ::maximum-queued (max 4 (* 2 knn))
             ::maximum-queued-by-database 2}
       :provider {::maximum-active provider
                  ::maximum-queued (* 2 provider)
                  ::maximum-queued-by-database 2}
       :mutation {::maximum-active mutation
                  ::maximum-queued (max 8 (* 4 mutation))
                  ::maximum-queued-by-database 8}
       :delivery {::maximum-active cpu-workers
                  ::maximum-queued (max 16 (* 4 cpu-workers))
                  ::maximum-queued-by-database 1}
       :hnsw {::maximum-active 1
              ::maximum-queued 1
              ::maximum-queued-by-database 1}}})))

(defn- empty-class-ready []
  {::database-order empty-queue ::by-database {}})

(defn- empty-state [classes]
  {::class-order (vec classes)
   ::class-cursor 0
   ::io-class-cursor 0
   ::ready (zipmap classes (repeat (empty-class-ready)))
   ::jobs {}
   ::closed-scopes #{}
   ::running-by-class {}
   ::running-by-class-database {}
   ::running-by-database {}})

(defn- add-database [ready database-name]
  (if (contains? (::by-database ready) database-name)
    ready
    (-> ready
        (update ::database-order conj database-name)
        (assoc-in [::by-database database-name] empty-queue))))

(defn- enqueue [state work-class database-name work]
  (update-in state [::ready work-class]
             (fn [ready]
               (-> (add-database ready database-name)
                   (update-in [::by-database database-name] conj work)))))

(defn- take-database [ready eligible-database?]
  (let [ready-count (count (::database-order ready))]
    (loop [remaining ready-count
           ready ready]
      (if (zero? remaining)
        [ready nil]
        (let [database-name (peek (::database-order ready))
              ready (update ready ::database-order pop)
              queue (get-in ready [::by-database database-name])]
          (if (and (seq queue) (eligible-database? database-name))
            (let [next-queue (pop queue)]
              [(if (seq next-queue)
                 (-> ready
                     (update ::database-order conj database-name)
                     (assoc-in [::by-database database-name] next-queue))
                 (update ready ::by-database dissoc database-name))
               (peek queue)])
            (recur (dec remaining)
                   (if (seq queue)
                     (update ready ::database-order conj database-name)
                     (update ready ::by-database dissoc database-name)))))))))

(defn- eligible-class? [state capacity work-class]
  (let [active (get-in state [::running-by-class work-class] 0)
        maximum (get-in capacity [::classes work-class ::maximum-active])
        cpu-running (reduce + 0 (map #(get-in state [::running-by-class %] 0)
                                     cpu-classes))]
    (and (< active maximum)
         (or (not (cpu-classes work-class))
             (< cpu-running (::cpu-workers capacity))))))

(defn- take-ready [state capacity allowed-classes]
  (let [order (::class-order state)
        n (count order)
        cursor-key (if (= allowed-classes cpu-classes)
                     ::class-cursor
                     ::io-class-cursor)]
    (loop [offset 0]
      (if (= offset n)
        [state nil]
        (let [index (mod (+ (get state cursor-key 0) offset) n)
              work-class (nth order index)]
          (if (and (allowed-classes work-class)
                   (eligible-class? state capacity work-class))
            (let [eligible-database?
                  (if (serialized-classes work-class)
                    #(zero? (get-in state [::running-by-class-database
                                           [work-class %]] 0))
                    (constantly true))
                  [ready work] (take-database
                                (get-in state [::ready work-class])
                                eligible-database?)]
              (if work
                [(-> state
                     (assoc cursor-key (mod (inc index) n))
                     (assoc-in [::ready work-class] ready)
                     (assoc-in [::jobs (::job-id work) ::status] :running)
                     (update-in [::running-by-class work-class] (fnil inc 0))
                     (update-in [::running-by-class-database
                                 [work-class (::database-name work)]]
                                (fnil inc 0))
                     (update-in [::running-by-database (::database-name work)]
                                (fnil inc 0)))
                 work]
                (recur (inc offset))))
            (recur (inc offset))))))))

(defn- queued-jobs [state]
  (filter (fn [[_ job]] (= :queued (::status job))) (::jobs state)))

(defn- queued-count [state] (count (queued-jobs state)))
(defn- queued-by-class [state]
  (frequencies (map (comp ::work-class val) (queued-jobs state))))
(defn- queued-by-class-database [state work-class database-name]
  (count (filter (fn [[_ job]]
                   (and (= :queued (::status job))
                        (= work-class (::work-class job))
                        (= database-name (::database-name job))))
                 (::jobs state))))
(defn- queued-request-bytes [state]
  (+ (reduce + 0 (map (comp ::request-bytes val) (queued-jobs state)))
     (reduce + 0 (keep (comp ::reserved-request-bytes val) (::jobs state)))))

(defn- reserved-count
  [state work-class database-name]
  (count
           (filter (fn [[_ job]]
             (and (= work-class (::reserved-work-class job))
                  (not= (::work-class job) (::reserved-work-class job))
                  (or (nil? database-name)
                      (= database-name (::database-name job)))))
           (::jobs state))))

(defn- remove-queued-work [state owners]
  (update state ::ready
          (fn [ready]
            (into {}
                  (map (fn [[work-class class-ready]]
                         (let [by-database
                               (into {}
                                     (keep
                                      (fn [[database-name queue]]
                                        (let [remaining
                                              (into empty-queue
                                                    (remove
                                                     #(contains? owners
                                                                 (::owner %)))
                                                    queue)]
                                          (when (seq remaining)
                                            [database-name remaining]))))
                                     (::by-database class-ready))]
                           [work-class
                            (assoc class-ready
                                   ::database-order
                                   (into empty-queue
                                         (filter #(contains? by-database %))
                                         (::database-order class-ready))
                                   ::by-database by-database)]))
                       ready)))))

(defn- take-work! [executor allowed-classes]
  (locking (::lock executor)
    (loop []
      (let [[state work] (take-ready @(::state executor) (::capacity executor)
                                     allowed-classes)]
        (cond
          work (do (reset! (::state executor) state) work)
          @(::stopped executor) nil
          :else (do (.wait ^Object (::lock executor)) (recur)))))))

(defn- decrement-running
  [state {::keys [work-class database-name]}]
  (let [class-left (dec (get-in state [::running-by-class work-class]))
        class-db-left (dec (get-in state [::running-by-class-database
                                          [work-class database-name]]))
        db-left (dec (get-in state [::running-by-database database-name]))]
    (cond-> state
      (zero? class-left) (update ::running-by-class dissoc work-class)
      (pos? class-left) (assoc-in [::running-by-class work-class] class-left)
      (zero? class-db-left)
      (update ::running-by-class-database dissoc [work-class database-name])
      (pos? class-db-left)
      (assoc-in [::running-by-class-database [work-class database-name]]
                class-db-left)
      (zero? db-left) (update ::running-by-database dissoc database-name)
      (pos? db-left) (assoc-in [::running-by-database database-name] db-left))))

(defn- publish-completion!
  [executor {::keys [job-id request-id]} outcome]
  (try
    ((::complete! executor)
     (cond-> {::job-id job-id ::outcome outcome}
       request-id (assoc ::request-id request-id)))
    (catch Throwable throwable
      (log/error throwable "database executor completion failed"
                 {::job-id job-id ::request-id request-id}))))

(defn- finish-work! [executor work]
  (locking (::lock executor)
    (let [{::keys [job-id owner]} work]
      (let [current (get-in @(::state executor) [::jobs job-id])
            owns-completion? (identical? owner (::owner current))
            canceled? (true? (::canceled? current))]
        (swap! (::state executor)
               (fn [state]
                 (cond-> (decrement-running state work)
                   owns-completion? (update ::jobs dissoc job-id))))
        (swap! (::counts executor) update ::completed inc)
        (.notifyAll ^Object (::lock executor))
        {::owns-completion? owns-completion?
         ::canceled? canceled?}))))

(defn continue-with
  "Return a private dispatcher value that advances one admitted job in place."
  ([work-class request]
   (continue-with work-class request (constantly true)))
  ([work-class request continue?]
   {::continue-work-class work-class
    ::continue-request request
    ::continue? continue?}))

(defn- continue-work!
  [executor work continuation]
  (locking (::lock executor)
    (let [{::keys [job-id owner scopes reserved-work-class
                   reserved-request-bytes]} work
          next-class (::continue-work-class continuation)
          continue? (::continue? continuation)
          state @(::state executor)
          current (get-in state [::jobs job-id])]
      (when (and (= next-class reserved-work-class)
                 (identical? owner (::owner current))
                 (not (::canceled? current))
                 (empty? (set/intersection (::closed-scopes state) scopes))
                 (continue?))
        (let [next-work (-> current
                            (assoc ::work-class next-class
                                   ::request (::continue-request continuation)
                                   ::request-bytes reserved-request-bytes
                                   ::status :queued)
                            (dissoc ::reserved-work-class
                                    ::reserved-request-bytes))]
          (reset! (::state executor)
                  (-> (decrement-running state work)
                      (enqueue next-class (::database-name work) next-work)
                      (assoc-in [::jobs job-id] next-work)))
          (.notifyAll ^Object (::lock executor))
          true)))))

(defn- finish-outcome! [executor work outcome]
  (let [continuation (when (= ::value (first outcome))
                       (let [value (second outcome)]
                         (when (and (map? value)
                                    (::continue-work-class value))
                           value)))]
    (if (and continuation (continue-work! executor work continuation))
      nil
      (let [outcome
            (if continuation
              [::throwable
               (ex-info "The database request was canceled before its next phase."
                        {::job-id (::job-id work)})]
              outcome)
            {::keys [owns-completion? canceled?]}
            (finish-work! executor work)
            outcome (if canceled?
                      [::throwable
                       (ex-info "The database request was canceled."
                                {::job-id (::job-id work)})]
                      outcome)]
        (when owns-completion?
          (publish-completion! executor work outcome))))))

(defn- run-work! [executor work]
  (let [execute (get (::execute executor) (::work-class work))
        outcome (try
                  [::value (execute (::request work))]
                  (catch Throwable throwable [::throwable throwable]))
        value (second outcome)]
    (if (and (= ::value (first outcome))
             (satisfies? async-protocols/ReadPort value))
      (async/take!
       value
       (fn [completed]
         (finish-outcome!
          executor work
          (cond
            (instance? Throwable completed) [::throwable completed]
            (nil? completed)
            [::throwable
             (ex-info "Asynchronous database work closed without a result."
                      {::job-id (::job-id work)})]
            :else [::value completed]))))
      (finish-outcome! executor work outcome))))

(defn- run-worker! [executor allowed-classes]
  (loop []
    (when-let [work (take-work! executor allowed-classes)]
      (if (#{:provider :mutation} (::work-class work))
        (.submit ^ExecutorService (::provider-executor executor)
                 ^Runnable #(run-work! executor work))
        (run-work! executor work))
      (recur))))

(defn- validate-capacity! [capacity classes]
  (when-not (and (pos-int? (::cpu-workers capacity))
                 (pos-int? (::maximum-request-bytes capacity))
                 (pos-int? (::maximum-queued-request-bytes capacity)))
    (throw (ex-info "Executor capacity contains an invalid process bound."
                    {::capacity capacity})))
  (doseq [work-class classes
          :let [limits (get-in capacity [::classes work-class])]]
    (when-not (and limits
                   (pos-int? (::maximum-active limits))
                   (pos-int? (::maximum-queued limits))
                   (pos-int? (::maximum-queued-by-database limits)))
      (throw (ex-info "Executor class capacity is absent or invalid."
                      {::work-class work-class ::capacity limits}))))
  capacity)

(defn start!
  "Start the authority-wide bounded dispatcher."
  {:malli/schema [:=> [:cat ::start-request] ::executor]}
  [{::keys [capacity execute complete!]
    :or {complete! (fn [_completion] nil)}}]
  (let [classes (vec (keys execute))
        _ (validate-capacity! capacity classes)
        provider-executor (Executors/newVirtualThreadPerTaskExecutor)
        executor {::capacity capacity
                  ::execute execute
                  ::complete! complete!
                  ::provider-executor provider-executor
                  ::lock (Object.)
                  ::state (atom (empty-state classes))
                  ::stopped (atom false)
                  ::counts (atom {::completed 0 ::rejected 0 ::fenced 0})}
        workers (mapv (fn [index]
                        (doto (Thread. ^Runnable #(run-worker! executor cpu-classes)
                                       (str "seon-database-cpu-" index))
                          (.setDaemon true)
                          (.start)))
                      (range (::cpu-workers capacity)))
        provider-dispatcher
        (doto (Thread. ^Runnable #(run-worker! executor #{:provider :mutation})
                       "seon-database-provider-dispatch")
          (.setDaemon true)
          (.start))]
    (assoc executor ::worker-threads (conj workers provider-dispatcher))))

(defn- rejection! [executor database-name work-class message]
  (swap! (::counts executor) update ::rejected inc)
  (let [outcome [::throwable (ex-info message
                                      {::database-name database-name
                                       ::work-class work-class})]]
    [{::accepted? false ::joined? false} outcome]))

(defn- admit! [{::keys [executor work-class database-name scope scopes job-id request-id
                        request request-bytes reserved-work-class
                        reserved-request-bytes]
                 :or {request-bytes 0 reserved-request-bytes 0}
                 :as submission}]
  (let [scopes (or scopes #{scope})
        _ (when-not (contains? scopes scope)
            (throw (ex-info "The scheduled database scope must be owned by the job."
                            {::scope scope ::scopes scopes})))
        admission
        (locking (::lock executor)
          (let [state @(::state executor)]
      (if-let [existing (get-in state [::jobs job-id])]
        [{::accepted? false ::joined? true}]
        (let [owner (Object.)
              class-capacity (get-in (::capacity executor) [::classes work-class])
              class-count (get (queued-by-class state) work-class 0)
              database-count (queued-by-class-database state work-class database-name)
              distinct-reservation? (and reserved-work-class
                                         (not= reserved-work-class work-class))
              reserved-capacity (when distinct-reservation?
                                  (get-in (::capacity executor)
                                          [::classes reserved-work-class]))
              queued-bytes (queued-request-bytes state)
              valid? (and class-capacity
                          (or (not distinct-reservation?)
                              (and reserved-capacity
                                   (< (+ (get (queued-by-class state)
                                              reserved-work-class 0)
                                         (reserved-count state reserved-work-class nil))
                                      (::maximum-queued reserved-capacity))
                                   (< (+ (queued-by-class-database
                                          state reserved-work-class database-name)
                                         (reserved-count state reserved-work-class
                                                         database-name))
                                      (::maximum-queued-by-database
                                       reserved-capacity))))
                          (not @(::stopped executor))
                          (empty? (set/intersection (::closed-scopes state)
                                                    scopes))
                          (<= request-bytes (::maximum-request-bytes (::capacity executor)))
                          (< class-count (::maximum-queued class-capacity))
                          (< database-count (::maximum-queued-by-database class-capacity))
                          (<= (+ queued-bytes request-bytes reserved-request-bytes)
                              (::maximum-queued-request-bytes (::capacity executor))))]
          (if-not valid?
            (rejection! executor database-name work-class
                        "The database work queue is full, fenced, or stopped.")
            (let [work {::work-class work-class
                        ::database-name database-name
                        ::scope scope
                        ::scopes scopes
                        ::job-id job-id
                        ::request-id request-id
                        ::request request
                        ::request-bytes request-bytes
                        ::reserved-work-class reserved-work-class
                        ::reserved-request-bytes reserved-request-bytes
                        ::owner owner}]
              (reset! (::state executor)
                      (-> state
                          (enqueue work-class database-name work)
                          (assoc-in [::jobs job-id]
                                    (assoc work ::status :queued))))
              (.notifyAll ^Object (::lock executor))
              [{::accepted? true ::joined? false}]))))))]
    (when-let [outcome (second admission)]
      (publish-completion! executor submission outcome))
    (first admission)))

(defn try-submit!
  "Try to admit work and return ordinary admission evidence."
  [request] (admit! request))

(defn- owns-scope?
  [scope job]
  (contains? (or (::scopes job) #{(::scope job)}) scope))

(defn- fence-scope! [executor scope abandon-work-classes]
  (let [{::keys [removed] :as fenced}
        (locking (::lock executor)
          (let [state @(::state executor)
                matching (into {} (filter (fn [[_ job]] (owns-scope? scope job)))
                               (::jobs state))
                queued (into {} (filter (fn [[_ job]] (= :queued (::status job))))
                             matching)
                abandoned-running
                (into {}
                      (filter (fn [[_ job]]
                                (and (= :running (::status job))
                                     (nil? (::request-id job))
                                     (abandon-work-classes (::work-class job)))))
                      matching)
                removed (merge queued abandoned-running)
                owners (set (map (comp ::owner val) removed))
                next-state
                (-> state
                    (remove-queued-work owners)
                    (update ::closed-scopes conj scope)
                    (update ::jobs #(apply dissoc % (keys removed))))]
            (reset! (::state executor) next-state)
            (swap! (::counts executor) update ::fenced + (count matching))
            (.notifyAll ^Object (::lock executor))
            {::matching matching ::queued queued ::removed removed}))
        outcome [::throwable
                 (ex-info "The database scope closed before completion."
                          {::scope scope})]]
    (doseq [[_ work] removed]
      (publish-completion! executor work outcome))
    (dissoc fenced ::removed)))

(defn remove-database!
  "Fence a scope and abandon all of its queued and running work."
  {:malli/schema [:=> [:cat ::remove-database-request]
                  ::remove-database-response]}
  [{::keys [executor scope]}]
  (let [{::keys [queued]} (fence-scope! executor scope #{:read :provider :knn
                                                         :hnsw :delivery
                                                         :mutation})]
    {::abandoned-count (count queued)}))

(defn release-scope!
  "Forget a fully drained fence after its database value is released."
  [{::keys [executor scope]}]
  (locking (::lock executor)
    (when (let [state @(::state executor)]
            (some (fn [[_ job]] (owns-scope? scope job)) (::jobs state)))
      (throw (ex-info "Cannot release a scope while work still owns it."
                      {::scope scope})))
    (swap! (::state executor) update ::closed-scopes disj scope))
  nil)

(defn fence-and-drain!
  "Fence a scope, cancel and drain reads, and abandon selected classes."
  {:malli/schema [:=> [:cat ::fence-and-drain-request]
                  ::remove-database-response]}
  [{::keys [executor scope cancel abandon-work-classes]
    :or {abandon-work-classes #{}}}]
  (let [{::keys [matching queued] :as fenced}
        (fence-scope! executor scope abandon-work-classes)
        removed (set (keys (merge queued
                                  (into {}
                                        (filter (fn [[_ job]]
                                                  (abandon-work-classes
                                                   (::work-class job))))
                                        matching))))
        running (remove (fn [[job-id _]] (contains? removed job-id)) matching)]
    (doseq [[job-id _] running]
      (try (cancel job-id) (catch Throwable _)))
    (locking (::lock executor)
      (loop []
        (when (let [state @(::state executor)]
                (some (fn [[_ job]] (owns-scope? scope job)) (::jobs state)))
          (.wait ^Object (::lock executor))
          (recur))))
    {::abandoned-count (count queued)}))

(defn cancel!
  "Cancel queued work or return the running request for native cancellation."
  {:malli/schema [:=> [:cat ::cancel-request] ::cancel-response]}
  [{::keys [executor job-id]}]
  (let [{::keys [completion] :as canceled}
        (locking (::lock executor)
          (if-let [{::keys [status request] :as job}
                   (get-in @(::state executor) [::jobs job-id])]
            (let [outcome [::throwable
                           (ex-info "The database request was canceled."
                                    {::job-id job-id})]]
              (if (= :queued status)
                (do
                  (swap! (::state executor)
                         #(-> %
                              (remove-queued-work #{(::owner job)})
                              (update ::jobs dissoc job-id)))
                  (swap! (::counts executor) update ::fenced inc)
                  {::cancellation :queued
                   ::completion [job outcome]})
                (do
                  (swap! (::state executor) assoc-in
                         [::jobs job-id ::canceled?] true)
                  {::cancellation :running ::request request})))
            {::cancellation :not-found}))]
    (when completion
      (let [[work outcome] completion]
        (publish-completion! executor work outcome)))
    (dissoc canceled ::completion)))

(defn cancel-queued!
  "Cancel only queued work without marking a running job canceled."
  [{::keys [executor job-id]}]
  (let [{::keys [completion] :as canceled}
        (locking (::lock executor)
          (if-let [{::keys [status] :as job}
                   (get-in @(::state executor) [::jobs job-id])]
            (if (= :queued status)
              (let [outcome [::throwable
                             (ex-info "The database request was canceled."
                                      {::job-id job-id})]]
                (swap! (::state executor)
                       #(-> %
                            (remove-queued-work #{(::owner job)})
                            (update ::jobs dissoc job-id)))
                (swap! (::counts executor) update ::fenced inc)
                (.notifyAll ^Object (::lock executor))
                {::cancellation :queued ::completion [job outcome]})
              {::cancellation :running ::request (::request job)})
            {::cancellation :not-found}))]
    (when completion
      (let [[work outcome] completion]
        (publish-completion! executor work outcome)))
    (dissoc canceled ::completion)))

(defn cancel-request!
  "Cancel every internal job owned by one retained public request."
  [{::keys [executor request-id]}]
  (let [{::keys [queued] :as canceled}
        (locking (::lock executor)
          (let [state @(::state executor)
                matching (into {}
                               (filter (fn [[_ job]]
                                         (= request-id (::request-id job))))
                               (::jobs state))
                queued (into {}
                             (filter (fn [[_ job]] (= :queued (::status job))))
                             matching)
                running-map (apply dissoc matching (keys queued))
                running (vals running-map)
                owners (set (map (comp ::owner val) queued))
                outcome-for
                (fn [job-id]
                  [::throwable
                   (ex-info "The database request was canceled."
                            {::job-id job-id ::request-id request-id})])]
            (swap! (::state executor)
                   (fn [current]
                     (-> current
                         (remove-queued-work owners)
                         (update ::jobs #(apply dissoc % (keys queued)))
                         (update ::jobs
                                 (fn [jobs]
                                   (reduce
                                    (fn [current job-id]
                                      (assoc-in current [job-id ::canceled?] true))
                                    jobs (keys running-map)))))))
            (.notifyAll ^Object (::lock executor))
            {::cancellation (cond
                              (seq running) :running
                              (seq queued) :queued
                              :else :not-found)
             ::canceled? (boolean (seq matching))
             ::requests (mapv ::request running)
             ::queued (mapv (fn [[job-id work]]
                              [work (outcome-for job-id)])
                            queued)}))]
    (doseq [[work outcome] queued]
      (publish-completion! executor work outcome))
    (dissoc canceled ::queued)))

(defn evidence
  "Return bounded dispatcher counts without retaining request values."
  {:malli/schema [:=> [:cat ::executor] ::evidence]}
  [executor]
  (locking (::lock executor)
    (let [state @(::state executor)
          counts @(::counts executor)
          running-by-class (::running-by-class state)]
      {::capacity (::capacity executor)
       ::queued (queued-count state)
       ::queued-by-class (queued-by-class state)
       ::queued-request-bytes (queued-request-bytes state)
       ::running (reduce + 0 (vals running-by-class))
       ::running-by-class running-by-class
       ::running-by-database (::running-by-database state)
       ::retained-identities (count (::jobs state))
       ::fenced-scopes (count (::closed-scopes state))
       ::fenced (::fenced counts)
       ::completed (::completed counts)
       ::rejected (::rejected counts)
       ::stopped? @(::stopped executor)})))

(defn stop!
  "Stop admission, drain accepted work, and close owned executors."
  {:malli/schema [:=> [:cat ::stop-request] ::stop-response]}
  [{::keys [executor]}]
  (locking (::lock executor)
    (reset! (::stopped executor) true)
    (.notifyAll ^Object (::lock executor)))
  (run! #(.join ^Thread %) (::worker-threads executor))
  (.shutdown ^ExecutorService (::provider-executor executor))
  (when-not (.awaitTermination ^ExecutorService (::provider-executor executor)
                               5 TimeUnit/SECONDS)
    (.shutdownNow ^ExecutorService (::provider-executor executor)))
  {::stopped? true})
