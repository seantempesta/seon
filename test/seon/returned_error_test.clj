(ns seon.returned-error-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.db :as db]
            [seon.repl :as repl]
            [seon.test-support :as support]))

(deftest returned-refusal-is-a-schema-first-error-with-unchanged-history
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "returned-error"})
     (support/seed-cluster! connection "returned-error")
     (let [setup (db/transact! connection [(support/task-row "erroritem000" "Verify")])
           _ (is (:db-after setup))
           ctx (support/fork-cluster-ctx connection "returned-error")
           configuration (support/effective-config)
           source "(seon.db/transact! [[:db/add [:seon.task/id \"erroritem000\"] :seon.task/plan 42]])"
           before (db/basis-t @connection)
           result (support/evaluate
                   {:seon.cluster.eval/source source
                    :seon.sci.eval/ctx ctx
                    :seon.db/db @connection
                    :seon.sci.admit/caps (config/result-caps configuration)
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
           shown (:seon.eval/shown result)
           response (repl/response result)
           diagnostic (:seon.sci.admit/value result)]
       (is (string? (:seon.db.write.attempt/request-id diagnostic)))
       (is (:seon.schema/form diagnostic))
       (is (= 42 (:seon.db/offending diagnostic)))
       ;; §2.4's refusal grammar: the shown text names the layer and member
       ;; that refused, the expected shape, and the offending value. The
       ;; legacy two-line "Expected:/Got:" form survives in no producer —
       ;; the refusal schema's render is seon.error/render-ai —
       ;; and reaches an agent only as already-saved shown text, asserted
       ;; at the end of this test.
       (is (str/includes? shown "seon.db/transact! refused"))
       (is (str/includes? shown "expected a string"))
       (is (str/includes? shown "42"))
       (is (< (tokens/estimate shown) 200))
       (is (= shown (:seon.repl/value (into {} (#'repl/response-entries result))))
           "the reply's :value is the shown text")
       (is (= 'seon.error/render-ai (:seon.eval/renderer result)))
       (is (= before (db/basis-t @connection)))
       (is (= "Verify" (:seon.task/plan
                        (db/pull @connection [:seon.task/plan]
                                 [:seon.task/id "erroritem000"]))))
       (is (= response
              (repl/response (dissoc result :seon.sci.admit/value :seon.sci.eval/ctx))))
       ;; Saved shown prose is the printer's text: the reply splices it unchanged.
       (is (= "Expected: saved schema\nGot: saved value"
              (:seon.repl/error
               (into {} (#'repl/response-entries {:seon.cluster.eval/error "saved refusal"
                                                  :seon.eval/shown "Expected: saved schema\nGot: saved value"})))))))))
