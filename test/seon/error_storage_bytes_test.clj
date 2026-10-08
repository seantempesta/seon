(ns seon.error-storage-bytes-test
  "Measures the logical bytes one recorded error stores: the strings its transaction writes and the blobs those strings name."
  (:require [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.id :as id]
            [seon.render :as render]
            [seon.test-support :as support]))

(defn- recorded-bytes
  "Record `source` as a normalization error and answer `{:datom-text :blob :blobs}` in UTF-8 bytes."
  [connection source]
  (let [dials config/defaults
        recording (error/recording
                   (db/db connection)
                   {:seon.error/source source
                    :seon.error/declared-schema :seon.error/normalization-error
                    :seon.error/id (id/id)
                    :seon.error/at (java.util.Date.)
                    :seon.error/process "error-storage-bytes-test"
                    :seon.schema/projection (db/carried-projection (db/db connection))
                    :seon.sci.admit/caps (config/result-caps dials)
                    :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes dials)
                    :seon.config.error/recurrence-limit (:seon.config.error/recurrence-limit dials)
                    :seon.db/connection connection
                    :seon.render/profile (render/agent-render-profile dials)})
        report (blob/with-publication!
                 connection (:seon.blob/staged-writes recording)
                 #(support/transacted! connection (:seon.db/tx-data recording)))
        strings (keep #(let [v (.-v ^datahike.datom.Datom %)] (when (string? v) v)) (:tx-data report))
        digest? #(re-matches #"[0-9a-f]{64}" %)
        sized (map (fn [s] (if (digest? s)
                             [:blob (some-> (blob/get connection s) blob/utf8-size)]
                             [:datom-text (blob/utf8-size s)]))
                   strings)
        total (fn [kind] (reduce + 0 (keep #(when (= kind (first %)) (second %)) sized)))]
    {:datom-text (total :datom-text) :blob (total :blob)
     :blobs (count (filter #(= :blob (first %)) sized))}))

(deftest one-recorded-error-store-bytes
  (support/with-database
   (fn [connection]
     (let [value (mapv (fn [n] {:error-storage-bytes/n n :error-storage-bytes/items (vec (range 20))}) (range 20))
           measured (recorded-bytes connection
                                    {:seon.error/at (java.util.Date.)
                                     :seon.error/layer ::recording
                                     :seon.error/operation 'seon.error-storage-bytes-test/observation
                                     :seon.error/message "A recorded observation."
                                     :seon.error/offending value})]
       (println "ERROR-STORE-BYTES" measured)
       (is (pos? (:datom-text measured)))))))
