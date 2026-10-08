(ns seon.error-result-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [malli.registry :as mr]
            [seon.blob :as blob]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.mcp :as mcp]
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
             "Canonical fixture's compiled registry is missing"))))))

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

(defn- recorded
  "Record `request` and answer the recording and the stored latest fact."
  [connection request]
  (let [recording (measured :recording #(error/recording (db/db connection) request))
        report (measured :transaction
                         #(blob/with-publication! connection (:seon.blob/staged-writes recording)
                            (fn [] (support/transacted! connection (:seon.db/tx-data recording)))))
        projection (:seon.schema/projection request)
        entity (db/pull (:db-after report) (error/observation-selector projection)
                        (:seon.error/ref recording))]
    {:recording recording :entity entity :stored (error/latest-fact entity)}))

(defn- verify-stored!
  "`value` is the observed value as the error stores it: a place is named by
  its class and identity, never entered (seon.error.refusal/referenced, 9e051dba7)."
  [connection request value]
  (let [started (System/nanoTime)
        {:keys [recording entity stored]} (recorded connection request)
        elapsed-ms (/ (- (System/nanoTime) started) 1e6)
        projection (:seon.schema/projection request)
        result-id (:seon.error/result-id stored)
        unit {:seon.render/value value
              :seon.repl/handle (admit/result-handle result-id)
              :seon.render.call/id (admit/result-handle result-id)
              :seon.schema/projection projection
              :seon.render/profile (:seon.render/profile request)}
        shown (render.value/render-ai-data (render.value/prepare unit))]
    (is (< elapsed-ms 1000) "error recording and transaction stay within their bound")
    (is (string? result-id))
    (is (= shown (:seon.error/shown stored)))
    (is (empty? (:seon.blob/staged-writes recording)) "shown text under the threshold stages no blob")
    (is (schema/valid-candidate-value? projection :seon.error/base stored))
    (is (= shown (:seon.error/shown (error/render-ai {:seon.render/value entity}))))
    (is (:seon.error/data-edn stored) "Legacy evidence remains written during accretion.")
    {:stored stored :shown shown}))

(deftest core-results-use-the-printer-for-arbitrary-objects
  (support/with-database
   (fn [connection]
     (let [place (atom 42)
           value [place identity (Object.)]
           named (str "#object[clojure.lang.Atom 0x" (Integer/toHexString (System/identityHashCode place)) "]")]
       (verify-stored! connection (request connection (observation value)) (assoc value 0 named))))))

(deftest large-result-stores-the-profile-capped-printer-text
  (support/with-database
   (fn [connection]
     (let [value (mapv (fn [n] {:error-result-test/n n :error-result-test/items (vec (range 20))}) (range 20))
           request (-> (request connection (observation value))
                       (assoc-in [:seon.render/profile :seon.render.profile/max-children] 2))
           {:keys [stored shown]} (verify-stored! connection request value)]
       (is (= (blob/utf8-size shown) (:seon.error/shown-size stored)))
       (is (< (count shown) (count (:seon.error/data-edn stored)))
           "the shown text is cut by the profile, the legacy evidence is not")))))

(deftest error-stores-shown-text-not-node-tree
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           value {:error-result-test/rows (vec (range 50))}
           {:keys [recording stored]}
           (recorded connection (assoc (request connection (observation value)) :seon.sci.eval/ctx ctx))]
       (is (string? (:seon.error/shown stored)))
       (is (<= (:seon.error/shown-size stored) 4096))
       (is (not (contains? stored :seon.error/data-blob)) "no node-tree blob rides the fact")
       (is (empty? (:seon.blob/staged-writes recording)))
       (is (= value (sci/eval-string* ctx (str (admit/result-handle (:seon.error/result-id stored)))))
           "the handle resolves to the live value")))))

(deftest a-large-off-path-request-stores-only-its-shown-text
  ;; publication-cost-opus 2026-09-27: a contract refusal whose request carried
  ;; all of an analysis's source text recorded a 7.9 MB blob. The live refusal
  ;; keeps the actual object; the recording keeps only text.
  (support/with-database
   (fn [connection]
     (let [projection (db/carried-projection (db/db connection))
           caps (config/result-caps config/defaults)
           source-text (apply str (repeat 400000 "x"))
           carrier {:error-result-test/source source-text}
           contracted (instrument/wrap-interpreted
                       'error-result-test/f "[:=> [:cat :map :int] :int]" projection caps (fn [_ n] n))
           refusal (support/refusal-data #(contracted carrier "not an int"))
           {:keys [recording stored]} (recorded connection (request connection refusal))]
       (is (identical? carrier (first (:seon.error/offending refusal)))
           "the in-memory refusal keeps the actual object")
       (is (empty? (:seon.blob/staged-writes recording)))
       (is (< (:seon.error/shown-size stored) 4096) "the recorded text has the expected size")))))

(deftest shown-text-over-the-threshold-is-staged-with-the-fact
  (support/with-database
   (fn [connection]
     (let [value (apply str (repeat 6000 "x"))
           profile (render/agent-render-profile (assoc config/defaults :seon.config.render.agent/token-budget 100000))
           {:keys [recording stored]}
           (recorded connection (assoc (request connection (observation value)) :seon.render/profile profile))
           digest (:seon.error/shown-blob stored)]
       (is (string? digest))
       (is (not (contains? stored :seon.error/shown)))
       (is (= [digest] (mapv :seon.blob/digest (:seon.blob/staged-writes recording))))
       (is (= (:seon.error/shown-size stored) (blob/utf8-size (blob/get connection digest))))))))

(deftest a-refused-settlement-leaves-no-error-blob
  (support/with-database
   (fn [connection]
     (let [value (apply str (repeat 6000 "x"))
           profile (render/agent-render-profile (assoc config/defaults :seon.config.render.agent/token-budget 100000))
           recording (error/recording (db/db connection)
                                      (assoc (request connection (observation value)) :seon.render/profile profile))
           digest (:seon.blob/digest (first (:seon.blob/staged-writes recording)))]
       (is (string? digest))
       (is (nil? (blob/get connection digest)) "preparing the error writes no blob")
       (is (thrown? clojure.lang.ExceptionInfo
                    (blob/with-publication! connection (:seon.blob/staged-writes recording)
                      #(throw (ex-info "refused settlement" {})))))
       (is (nil? (db/q '[:find ?e . :in $ ?d :where [?e :seon.error/shown-blob ?d]] (db/db connection) digest))
           "no fact names the digest")))))

(deftest a-refused-call-reaches-the-agent-with-a-handle-and-no-node-tree-blob
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                                        "arguments" {"code" code "agent" agent-id "cluster" running}})))]
                    (get (last (get result "content")) "text")))
           _ (call "(defn error-result-refused {:malli/schema [:=> [:cat :int] :int]} [x] x)")
           refused (call "(error-result-refused \"not an int\")")
           data (support/reply refused)
           leaves (tree-seq coll? seq data)]
       (is (some #(and (map-entry? %) (= :seon.repl/result (key %))) leaves)
           "the error reaches the agent with a result handle to dig into")
       (is (not-any? #(and (map-entry? %) (#{:seon.error/data-blob :seon.error.occurrence/blob-digest} (key %))) leaves)
           "no node-tree blob digest rides the reply")))))
