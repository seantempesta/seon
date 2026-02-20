(ns seon.web.stats
  "Database statistics queries for dashboard display.

   Stub namespace - trading stats queries removed with XTDB.
   Retained for disk usage and cache infrastructure used by other modules."
  (:require [clojure.java.io :as io]))

(defn- dir-size
  "Calculate total size of a directory in bytes."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (->> (file-seq f)
           (filter #(.isFile %))
           (map #(.length %))
           (reduce + 0))
      0)))

(defn- format-bytes
  "Format bytes as human-readable string."
  [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    (< bytes (* 1024 1024 1024)) (format "%.1f MB" (/ bytes (* 1024.0 1024)))
    :else (format "%.2f GB" (/ bytes (* 1024.0 1024 1024)))))

(defn get-disk-usage
  "Get disk usage for the XTDB data directory.
   Returns {:bytes n :formatted string}."
  []
  (let [xtdb-path "data/xtdb"
        bytes (dir-size xtdb-path)]
    {:bytes bytes
     :formatted (format-bytes bytes)}))

(defn get-database-stats
  "Return empty database stats. Trading stats queries removed with XTDB."
  []
  (let [disk (get-disk-usage)]
    {:total-records 0
     :by-symbol []
     :date-range nil
     :date-ranges-by-symbol []
     :distinct-symbols []
     :symbols-count 0
     :latest-timestamp nil
     :disk-usage disk
     :empty? true}))

;; ========================================
;; In-Memory Cache
;; ========================================

(defonce stats-cache (atom {:stats nil
                            :updated-at 0
                            :ttl-ms 30000}))

(defn get-cached-stats
  "Get database stats with caching."
  []
  (let [now (System/currentTimeMillis)
        {:keys [stats updated-at ttl-ms]} @stats-cache]
    (if (and stats (< (- now updated-at) ttl-ms))
      stats
      (let [fresh-stats (get-database-stats)]
        (reset! stats-cache {:stats fresh-stats
                             :updated-at now
                             :ttl-ms ttl-ms})
        fresh-stats))))

(defn invalidate-cache!
  "Force invalidation of the stats cache."
  []
  (swap! stats-cache assoc :updated-at 0))

(defn get-cached-stats-nonblocking
  "Get database stats from cache only - never runs queries.
   Returns nil if cache is empty or expired."
  []
  (let [now (System/currentTimeMillis)
        {:keys [stats updated-at ttl-ms]} @stats-cache]
    (when (and stats (< (- now updated-at) ttl-ms))
      stats)))
