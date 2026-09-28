(ns seon.error-result-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [malli.registry :as mr]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(deftest a-canonical-result-declarations-are-installed
  (support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           registry (:seon.schema.projection/registry projection)]
       (doseq [attribute [:seon.error/result-id :seon.error/shown]]
         (is (some? (mr/schema registry attribute))
             (str "Canonical fixture's compiled registry is missing " attribute)))))))

(defn- request [connection source]
  (let [dials config/defaults]
    {:seon.error/source source
     :seon.error/declared-schema :seon.error/normalization-error
     :seon.error/id (id/id)
     :seon.error/at (java.util.Date.)
     :seon.error/process "error-result-test"
     :seon.schema/projection (db/carried-projection (db/db connection))
     :seon.sci.admit/caps (config/result-caps dials)
     :seon.config.error/max-evidence-bytes (:seon.config.error/max-evidence-bytes dials)
     :seon.config.error/recurrence-limit (:seon.config.error/recurrence-limit dials)
     :seon.db/connection connection
     :seon.render/profile (render/agent-render-profile dials)}))

(defn- observation [value]
  {:seon.error/at (java.util.Date.)
   :seon.error/layer ::recording
   :seon.error/operation 'seon.error-result-test/observation
   :seon.error/message "A recorded observation."
   :seon.error/offending value})

(defn- measured [stage f]
  (let [started (System/nanoTime)]
    (try
      (f)
      (finally
        (println "ERROR-RESULT-TIMING" stage
                 (/ (- (System/nanoTime) started) 1e6) "ms")))))

(defn- verify-stored!
  "`value` is the observed value as the error stores it: a place is named by
  its class and identity, never entered (seon.error.refusal/referenced, 9e051dba7)."
  [connection request value]
  (let [started (System/nanoTime)
        recording (measured :recording #(error/recording (db/db connection) request))
        report (measured :transaction #(support/transacted! connection (:seon.db/tx-data recording)))
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)
        projection (:seon.schema/projection request)
        entity (db/pull (:db-after report) (error/observation-selector projection)
                     (:seon.error/ref recording))
        stored (error/latest-fact entity)
        result-id (:seon.error/result-id stored)
        unit {:seon.render/value value
              :seon.repl/handle (admit/result-handle result-id)
              :seon.render.call/id (admit/result-handle result-id)
              :seon.schema/projection projection
              :seon.render/profile (:seon.render/profile request)}
        ;; the value is stored ONCE, as its admitted artifact (the record
        ;; get_value reads), never as a second full-HTML copy
        complete (blob/get connection (:seon.error/data-blob stored))
        artifact (render.value/read-artifact complete)
        shown (render.value/render-ai-data (render.value/prepare unit))]
    (is (< elapsed-ms 1000) (str "error recording and transaction: " elapsed-ms " ms"))
    (is (string? result-id))
    (is (map? (:seon.sci.admit/print-node artifact)))
    (is (= shown (:seon.error/shown stored)))
    (is (schema/valid-candidate-value? projection :seon.error/base stored))
    (is (= shown (:seon.error/shown (error/render-ai {:seon.render/value entity}))))
    (is (:seon.error/data-edn stored) "Legacy evidence remains written during accretion.")
    {:stored stored :complete complete :shown shown
     :stored-value (render.value/artifact-value artifact)}))

(deftest core-results-use-the-printer-for-arbitrary-objects
  (support/with-database
   (fn [connection]
     (let [place (atom 42)
           value [place identity (Object.)]
           named (str "#object[clojure.lang.Atom 0x" (Integer/toHexString (System/identityHashCode place)) "]")]
       (verify-stored! connection (request connection (observation value)) (assoc value 0 named))))))

(deftest large-result-keeps-complete-and-profile-capped-printer-text
  (support/with-database
   (fn [connection]
     (let [value (mapv (fn [n] {:error-result-test/n n :error-result-test/items (vec (range 20))}) (range 20))
           request (-> (request connection (observation value))
                       (assoc-in [:seon.render/profile :seon.render.profile/max-children] 2))
           {:keys [complete shown stored-value]} (verify-stored! connection request value)]
       (is (= value (get-in stored-value [:seon.error/problems 0 :seon.error/offending]))
           "a value with no problem paths is recorded whole at its own path, under the evidence bound")
       (is (= (id/id value 64) (:seon.error/offending-digest stored-value)))
       (is (not= complete shown))
       (is (< (count shown) (count complete)))))))

(deftest a-large-off-path-request-records-a-small-blob
  ;; publication-cost-opus 2026-09-27: a contract refusal whose request carried
  ;; all of an analysis's source text recorded a 7.9 MB blob. The live refusal
  ;; keeps the actual object; the recording keeps the value at the problem
  ;; path and a digest of the whole.
  (support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           caps (config/result-caps config/defaults)
           source-text (apply str (repeat 400000 "x"))
           carrier {:error-result-test/source source-text}
           contracted (instrument/wrap-interpreted
                       'error-result-test/f "[:=> [:cat :map :int] :int]" projection caps (fn [_ n] n))
           refusal (support/refusal-data #(contracted carrier "not an int"))
           recording (error/recording (db/db connection) (request connection refusal))
           report (support/transacted! connection (:seon.db/tx-data recording))
           stored (error/latest-fact (db/pull (:db-after report)
                                              (error/observation-selector projection)
                                              (:seon.error/ref recording)))
           content (blob/get connection (:seon.error/data-blob stored))
           record (render.value/artifact-value (render.value/read-artifact content))]
       (is (identical? carrier (first (:seon.error/offending refusal)))
           "the in-memory refusal keeps the actual object")
       (is (< (count content) 4096) (str "the recorded blob is " (count content) " characters"))
       (is (= "not an int" (get-in record [:seon.error/problems 0 :seon.error/offending])))
       (is (not (str/includes? content source-text)))
       (is (= 64 (count (:seon.error/offending-digest record)))
           "the whole offending value is identified by its digest")))))
