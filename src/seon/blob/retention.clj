(ns seon.blob.retention
  "Oldest-first byte retention for the root's shared binary blob store."
  (:require [clojure.java.io :as io]
            [datahike.gc-guard :as guard]
            [konserve.core :as k]
            [konserve.impl.defaults :as defaults]
            [seon.cluster.registry :as registry])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))

(defn- inventory
  [store]
  (into []
        (comp
         (filter #(= :binary (:type %)))
         (map (fn [{content-digest :key last-write :last-write}]
                (let [path (.toPath (io/file (get-in store [:backing :base])
                                             (defaults/key->store-key content-digest)))
                      attributes (Files/readAttributes
                                  path BasicFileAttributes
                                  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
                  (when-not (and (.isRegularFile attributes) last-write)
                    (throw (ex-info "Blob inventory needs a regular file and write time."
                                    {:seon.error/kind :seon.blob.retention/invalid-file
                                     :seon.blob/digest content-digest})))
                  {:seon.blob/digest content-digest
                   :seon.blob/size (.size attributes)
                   ::written-at last-write}))))
        (k/keys store {:sync? true})))

(defn reclaim!
  "Delete oldest unreferenced blobs until the root meets its byte budget."
  {:malli/schema [:=> [:cat :seon.blob.retention/request]
                  :seon.blob.retention/result]}
  [{connection :seon.db/connection maximum :seon.config.blob/max-bytes}]
  (let [store (:store @connection)
        store-id (get-in @connection [:config :store :id])
        permit (guard/acquire-sweep-permit! store-id)]
    (try
      (let [branches (k/get store :branches nil {:sync? true})
            _ (when-not (seq branches)
                (throw (ex-info "Blob retention requires a nonempty branch roster."
                                {:seon.error/kind :seon.blob.retention/missing-roster})))
            referenced (registry/referenced-blobs connection branches false)
            blobs (inventory store)
            before (reduce + 0 (map :seon.blob/size blobs))
            candidates (sort-by (juxt ::written-at :seon.blob/digest)
                                (remove #(referenced (:seon.blob/digest %)) blobs))
            result
            (reduce
             (fn [result blob]
               (if (<= (::bytes-after result) maximum)
                 (reduced result)
                 (do
                   (k/dissoc store (:seon.blob/digest blob) {:sync? true})
                   (-> result
                       (update ::bytes-after - (:seon.blob/size blob))
                       (update ::deleted-count inc)))))
             {::bytes-after before ::deleted-count 0}
             candidates)]
        (cond-> (assoc result
                        ::bytes-before before
                        ::reclaimed-bytes (- before (::bytes-after result)))
          (> (::bytes-after result) maximum)
          (assoc ::excess-bytes (- (::bytes-after result) maximum))))
      (finally
        (guard/release-reachability-permit! permit)))))
