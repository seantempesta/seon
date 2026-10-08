(ns tx-census.probe
  (:require [datahike.api :as d] [datahike.connections :as dc] [seon.profile :as profile]
            [konserve.core :as konserve] [konserve.impl.defaults :as kd]))
(def log (atom []))
(defn conns [] (into {} (for [[_ {:keys [conn count]}] @dc/*connections* :when (and conn (pos? count))]
                          [(:branch (:config @conn)) conn])))
(defn summarize [branch r]
  (let [tx (get-in r [:tempids :db/current-tx])
        datoms (:tx-data r)
        txd (filter #(= tx (:e %)) datoms)
        op (some #(when (= :seon.db.transaction/operation (:a %)) (:v %)) txd)
        agent (some #(when (= :seon.db.transaction/agent (:a %)) (:v %)) txd)]
    {:branch branch :tx tx :at (System/currentTimeMillis) :op op :agent agent
     :datoms (count datoms) :retracts (count (remove :added datoms))
     :attrs (into (sorted-set) (map #(str (namespace (:a %)) "/" (name (:a %)))) datoms)
     :tx-attrs (into (sorted-set) (map #(str (namespace (:a %)) "/" (name (:a %)))) txd)
     :entities (count (into #{} (map :e) datoms))
     :commit (get-in r [:tx-meta :db/commitId])}))
(def watched (atom #{}))
(defn watch! []
  (doseq [[b c] (conns) :when (not (@watched c))]
    (swap! watched conj c)
    (d/listen c ::census (fn [r] (swap! log conj (summarize b r))))))
(defn unwatch! [] (doseq [c @watched] (try (d/unlisten c ::census) (catch Throwable _))) (reset! watched #{}) :off)
(def watcher (atom nil))
(defn start-watcher! [] (reset! watcher (future (while true (try (watch!) (catch Throwable _)) (Thread/sleep 5)))))
(defn stop-watcher! [] (some-> @watcher future-cancel) :stopped)
(def store-dir (atom nil))
(def the-store (atom nil))
(def events (java.util.concurrent.ConcurrentLinkedQueue.))
(defn ev [{op :api-op k :key v :value kvs :kvs}]
  (let [pairs (if (= :multi-assoc op) kvs [[k v]])
        cid (some (fn [[_ v]] (when (map? v) (get-in v [:meta :datahike/commit-id]))) pairs)
        branch (some (fn [[_ v]] (when (map? v) (get-in v [:config :branch]))) pairs)]
    {:op op :cid cid :branch branch :keys (mapv first pairs)
     :head-key (some (fn [[k v]] (when (and (keyword? k) (map? v)) k)) pairs)}))
(defn hook! []
  (let [c (get (conns) :cluster-default)]
    (reset! the-store (:store @c))
    (reset! store-dir (java.io.File. ^String (get-in @c [:config :store :path])))
    (konserve/remove-write-hook! @the-store ::census) (.clear events)
    (konserve/add-write-hook! @the-store ::census (fn [e] (.add events (ev e)))) :hooked))
(defn fsize [k] (let [f (java.io.File. ^java.io.File @store-dir ^String (kd/key->store-key k))] (if (.exists f) (.length f) 0)))
(defn commits-written []
  (loop [[e & more] (vec events) pending [] out {}]
    (cond (nil? e) (assoc out :orphan {:objects (count pending) :bytes (reduce + (map fsize (distinct pending)))})
          (and (:cid e) (:head-key e)) (recur more pending (update out (:cid e) assoc :head-bytes (fsize (:head-key e))))
          (:cid e) (let [ks (distinct (conj pending (first (:keys e))))]
                     (recur more [] (assoc out (:cid e) {:objects (count ks) :bytes (reduce + (map fsize ks))
                                                          :record-bytes (fsize (first (:keys e)))})))
          :else (recur more (into pending (:keys e)) out))))
(defn transact-calls [] (reduce + 0 (for [c (profile/cells) :when (= 'seon.db/transact! (:seon.profile/sym c))]
                                      (:seon.profile/calls (profile/observation c)))))
(def window (atom nil))
(defn start! [] (watch!) (hook!) (reset! log []) (reset! window {:t0 (System/currentTimeMillis) :calls0 (transact-calls)}) :started)
(defn stop! []
  (Thread/sleep 200)
  (let [{:keys [t0 calls0]} @window
        written (commits-written)
        rows @log]
    {:ms (- (System/currentTimeMillis) t0)
     :transact-calls (- (transact-calls) calls0)
     :durable (count rows)
     :commits (count (into #{} (map :commit) rows))
     :written-commits (dec (count written))
     :bytes (reduce + (keep :bytes (vals written)))
     :head-bytes (reduce + (keep :head-bytes (vals written)))
     :orphan (:orphan written)
     :rows (mapv (fn [r] (let [w (written (:commit r))]
                           (-> r (dissoc :commit) (update :at - t0)
                               (assoc :objects (:objects w) :bytes (:bytes w)))))
                 (sort-by :at rows))}))
(defn brief [r] (update r :rows (fn [rows] (mapv #(-> % (update :attrs count) (dissoc :tx-attrs)) rows))))
