(ns seon.db.executor
  "Bounded JVM execution of authoritative database requests.

   The public owner is `seon.db.writer`. This namespace keeps the queue
   selection and Java executor resources private. Requests retain their
   existing database name, operation, and request id; execution does not add a
   second routing or protocol vocabulary."
  (:require [seon.db.coordinate :as coordinate]
            [seon.db.protocol]
            [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::request :map)
(schema/register! ::connection-id [:tuple :uuid :keyword])
(schema/register! ::generation :uuid)
(schema/register!
 ::scope
 [:map {:closed true}
  [::database-name ::database-name]
  [::coordinate/attachment ::coordinate/attachment]
  [::connection-id ::connection-id]
  [::generation ::generation]])
(schema/register! ::job-id :seon.db.protocol/request-id)
(schema/register! ::maximum-queued [:int {:min 1}])
(schema/register! ::accepted? :boolean)
(schema/register! ::joined? :boolean)
(schema/register! ::abandoned-count [:int {:min 0}])
(schema/register! ::execute 'fn?)
(schema/register! ::workers [:int {:min 1}])
(schema/register! ::name :keyword)
(schema/register!
 ::start-request
 [:map
  [::name ::name]
  [::workers ::workers]
  [::maximum-queued ::maximum-queued]
  [::execute ::execute]])
(schema/register! ::executor 'map?)
(schema/register!
 ::submit-request
 [:map
  [::executor ::executor]
  [::database-name ::database-name]
  [::scope ::scope]
  [::job-id ::job-id]
  [::request ::request]])
(schema/register!
 ::remove-database-request
 [:map [::executor ::executor] [::scope ::scope]])
(schema/register!
 ::remove-database-response
 [:map [::abandoned-count ::abandoned-count]])
(schema/register! ::stop-request [:map [::executor ::executor]])
(schema/register! ::stopped? :boolean)
(schema/register! ::stop-response [:map [::stopped? ::stopped?]])
(schema/register! ::queued [:int {:min 0}])
(schema/register! ::running [:int {:min 0}])
(schema/register! ::running-by-database [:map-of ::database-name ::running])
(schema/register! ::retained-identities [:int {:min 0}])
(schema/register! ::fenced [:int {:min 0}])
(schema/register! ::completed [:int {:min 0}])
(schema/register! ::rejected [:int {:min 0}])
(schema/register!
 ::evidence
 [:map
  [::name ::name]
  [::queued ::queued]
  [::running ::running]
  [::running-by-database ::running-by-database]
  [::retained-identities ::retained-identities]
  [::fenced ::fenced]
  [::completed ::completed]
  [::rejected ::rejected]
  [::stopped? ::stopped?]])

(def ^:private empty-queue clojure.lang.PersistentQueue/EMPTY)

(defn- empty-ready
  []
  {::database-order []
   ::ready {}
   ::jobs {}
   ::closed-scopes #{}
   ::cursor 0})

(defn- add-database
  [state database-name]
  (if (contains? (::ready state) database-name)
    state
    (-> state
        (update ::database-order conj database-name)
        (assoc-in [::ready database-name] empty-queue))))

(defn- enqueue
  [state database-name request maximum-queued]
  (let [state* (add-database state database-name)
        queue (get-in state* [::ready database-name])]
    (if (>= (count queue) maximum-queued)
      [state* false]
      [(update-in state* [::ready database-name] conj request) true])))

(defn- take-ready
  [state]
  (let [database-order (::database-order state)
        database-count (count database-order)]
    (if (zero? database-count)
      [state nil]
      (loop [offset 0]
        (if (= offset database-count)
          [state nil]
          (let [index (mod (+ (::cursor state) offset) database-count)
                database-name (nth database-order index)
                queue (get-in state [::ready database-name])]
            (if (seq queue)
              [(-> state
                   (assoc ::cursor (mod (inc index) database-count))
                   (assoc-in [::ready database-name] (pop queue)))
               (peek queue)]
              (recur (inc offset)))))))))

(defn- remove-database
  [state database-name]
  (let [database-order (::database-order state)
        removed-index (.indexOf ^java.util.List database-order database-name)]
    (if (neg? removed-index)
      [state []]
      (let [abandoned (vec (get-in state [::ready database-name]))
            remaining (into (subvec database-order 0 removed-index)
                            (subvec database-order (inc removed-index)))
            old-cursor (::cursor state)
            cursor (cond
                     (empty? remaining) 0
                     (< removed-index old-cursor) (dec old-cursor)
                     (= old-cursor (count remaining)) 0
                     :else old-cursor)]
        [(-> state
             (assoc ::database-order remaining
                    ::cursor cursor)
             (update ::ready dissoc database-name))
         abandoned]))))

(defn- queued-count
  [state]
  (transduce (map (comp count val)) + 0 (::ready state)))

(defn- increment-running
  [counts database-name]
  (-> counts
      (update ::running inc)
      (update-in [::running-by-database database-name] (fnil inc 0))))

(defn- decrement-running
  [counts database-name]
  (let [remaining (dec (get-in counts [::running-by-database database-name]))]
    (cond-> (-> counts
                (update ::running dec)
                (update ::completed inc))
      (zero? remaining) (update ::running-by-database dissoc database-name)
      (pos? remaining) (assoc-in [::running-by-database database-name]
                                 remaining))))

(defn- take-work!
  [executor]
  (let [lock (::lock executor)
        state (::state executor)
        stopped? (::stopped executor)]
    (locking lock
      (loop []
        (let [[next-state work] (take-ready @state)]
          (cond
            work
            (do
              (reset! state next-state)
              (swap! state assoc-in [::jobs (::job-id work) ::status] :running)
              (swap! (::counts executor) increment-running
                     (::database-name work))
              work)

            @stopped?
            nil

            :else
            (do
              (.wait ^Object lock)
              (recur))))))))

(defn- finish-work!
  [executor database-name job-id result]
  (locking (::lock executor)
    (swap! (::state executor)
           (fn [state]
             (if (identical? result (get-in state [::jobs job-id ::result]))
               (update state ::jobs dissoc job-id)
               state)))
    (swap! (::counts executor) decrement-running database-name)))

(defn- run-worker!
  [executor]
  (loop []
    (when-let [{::keys [database-name request result job-id]}
               (take-work! executor)]
      (try
        (deliver result [::value ((::execute executor) request)])
        (catch Throwable throwable
          (deliver result [::throwable throwable]))
        (finally
          (finish-work! executor database-name job-id result)))
      (recur))))

(defn start!
  "Start one bounded shared executor.

   Database selection happens before a worker begins the request."
  {:malli/schema [:=> [:cat ::start-request] ::executor]}
  [{::keys [name workers maximum-queued execute]}]
  (let [executor
        {::name name
         ::maximum-queued maximum-queued
         ::execute execute
         ::lock (Object.)
         ::state (atom (empty-ready))
         ::stopped (atom false)
         ::counts (atom {::running 0
                         ::running-by-database {}
                         ::completed 0
                         ::rejected 0
                         ::fenced 0})}
        worker-threads
        (mapv
         (fn [index]
           (doto
            (Thread. ^Runnable #(run-worker! executor)
                     (str "seon-database-" (clojure.core/name name) "-" index))
             (.setDaemon true)
             (.start)))
         (range workers))]
    (assoc executor ::worker-threads worker-threads)))

(defn- admit!
  [{::keys [executor database-name scope job-id request]}]
  (locking (::lock executor)
    (let [state @(::state executor)]
      (if-let [existing (get-in state [::jobs job-id])]
        [{::accepted? false ::joined? true} (::result existing)]
        (let [result (promise)
              work {::database-name database-name
                    ::scope scope
                    ::job-id job-id
                    ::request request
                    ::result result}
              [next-state accepted?]
              (if (or @(::stopped executor)
                      (contains? (::closed-scopes state) scope))
                [state false]
                (enqueue state database-name work
                         (::maximum-queued executor)))]
          (if accepted?
            (do
              (reset! (::state executor)
                      (assoc-in next-state [::jobs job-id]
                                {::scope scope ::status :queued
                                 ::result result}))
              (.notifyAll ^Object (::lock executor))
              [{::accepted? true ::joined? false} result])
            (do
              (swap! (::counts executor) update ::rejected inc)
              (deliver
               result
               [::throwable
                (ex-info "The database request queue is full or stopped."
                         {::name (::name executor)
                          ::database-name database-name})])
              [{::accepted? false ::joined? false} result])))))))

(defn try-submit!
  "Try to admit one job and return only ordinary admission evidence."
  {:malli/schema [:=> [:cat ::submit-request]
                  [:map [::accepted? ::accepted?] [::joined? ::joined?]]]}
  [request]
  (first (admit! request)))

(defn submit-async!
  "Submit or join one job and return its internal result promise immediately."
  {:malli/schema [:=> [:cat ::submit-request] :any]}
  [request]
  (second (admit! request)))

(defn submit!
  "Submit one request under its existing database name and await its result."
  {:malli/schema [:=> [:cat ::submit-request] :any]}
  [request]
  (let [[outcome value] @(submit-async! request)]
    (if (= ::throwable outcome)
      (throw value)
      value)))

(defn remove-database!
  "Fence one exact scope and settle every queued or running request."
  {:malli/schema
   [:=> [:cat ::remove-database-request] ::remove-database-response]}
  [{::keys [executor scope]}]
  (locking (::lock executor)
    (let [state @(::state executor)
          matching-jobs
          (into {}
                (filter (fn [[_ job]] (= scope (::scope job))))
                (::jobs state))
          queued-results
          (into #{}
                (keep (fn [[_ {::keys [status result]}]]
                        (when (= :queued status) result)))
                matching-jobs)
          next-ready
          (into {}
                (map (fn [[database-name queue]]
                       [database-name
                        (into empty-queue
                              (remove #(contains? queued-results (::result %)))
                              queue)]))
                (::ready state))
          [next-state _]
          (remove-database
           (-> state
               (assoc ::ready next-ready)
               (update ::closed-scopes conj scope)
               (update ::jobs #(apply dissoc % (keys matching-jobs))))
           (::database-name scope))
          abandoned (count queued-results)]
      (reset! (::state executor) next-state)
      (swap! (::counts executor) update ::fenced + (count matching-jobs))
      (doseq [[_ {::keys [result]}] matching-jobs]
        (deliver
         result
         [::throwable
          (ex-info "The database scope closed before request completion."
                   {::scope scope})]))
      {::abandoned-count abandoned})))

(defn evidence
  "Return bounded executor counts without retaining requests or results."
  {:malli/schema [:=> [:cat ::executor] ::evidence]}
  [executor]
  (let [counts @(::counts executor)]
    {::name (::name executor)
     ::queued (locking (::lock executor)
                (queued-count @(::state executor)))
     ::running (::running counts)
     ::running-by-database (::running-by-database counts)
     ::retained-identities (locking (::lock executor)
                             (count (::jobs @(::state executor))))
     ::fenced (::fenced counts)
     ::completed (::completed counts)
     ::rejected (::rejected counts)
     ::stopped? @(::stopped executor)}))

(defn stop!
  "Reject new requests, drain accepted requests, and join the bounded workers."
  {:malli/schema [:=> [:cat ::stop-request] ::stop-response]}
  [{::keys [executor]}]
  (locking (::lock executor)
    (reset! (::stopped executor) true)
    (.notifyAll ^Object (::lock executor)))
  (run! #(.join ^Thread %) (::worker-threads executor))
  {::stopped? true})
