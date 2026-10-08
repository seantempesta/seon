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
        strings (distinct (keep #(let [v (.-v ^datahike.datom.Datom %)] (when (string? v) v)) (:tx-data report)))
        digest? #(re-matches #"[0-9a-f]{64}" %)
        blob-sizes (keep #(when (digest? %) (some-> (blob/get connection %) blob/utf8-size)) strings)]
    {:datom-text (reduce + 0 (map blob/utf8-size (remove digest? strings)))
     :blob (reduce + 0 blob-sizes)
     :blobs (count blob-sizes)}))

(deftest one-recorded-error-stores-its-shown-text-and-no-blob
  (support/with-database
   (fn [connection]
     (let [value (mapv (fn [n] {:error-storage-bytes/n n :error-storage-bytes/items (vec (range 20))}) (range 20))
           measured (recorded-bytes connection
                                    {:seon.error/at (java.util.Date.)
                                     :seon.error/layer ::recording
                                     :seon.error/operation 'seon.error-storage-bytes-test/observation
                                     :seon.error/message "A recorded observation."
                                     :seon.error/offending value})]
       (is (pos? (:datom-text measured)))
       (is (zero? (:blobs measured)) "a shown text under the threshold stores no blob")))))
