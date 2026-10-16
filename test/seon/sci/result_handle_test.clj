(ns seon.sci.result-handle-test
  "A form's result handle is publication's binding (`seon.sci.eval/bind-result!`),
  not a write of the form: only a form that defines is analyzed."
  (:require [seon.config]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.batch :as batch]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.sci.observe :as observe]
            [seon.test-support :as support]
            [seon.turn]
            [seon.turn.world :as world]))

(defn- analyzed
  "A program run of `text` with handles: its values, the Vars it bound, the
  first handle's value and its record-form calls."
  {:malli/schema [:=> [:cat :seon.db/connection :string] [:map-of :keyword :seon.schema/value]]}
  [connection text]
  (let [basis (db/db connection)
        request (-> (support/code-request
                     {:seon.batch/world (world/world {:seon.db/connection connection :seon.boot/cluster-name "default"
                                                            :seon.sci.eval/ctx (sci.eval/fork-cluster-ctx (sci.eval/base-ctx basis) (db/db connection) connection)
 :seon.batch/sink (seon.batch/sink) :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms seon.config/defaults) :seon.agent/id "result-handle"})
                      :seon.batch/observe observe/observe
                      ;; the turn's show stage: it binds each value under its handle
                      :seon.batch/show #'seon.turn/show-row
                      :seon.sci.eval/time-limit-ms 5000
                      :seon.batch/start-ns 'user
                      :seon.batch/text text})
                    ;; each form shown under its own handle, as a run's are
                    (update :seon.turn/frame-request
                            (fn [frame] (fn [ordinal namespace-name]
                                          (assoc (frame ordinal namespace-name)
                                                 :seon.repl/handle (symbol "result" (str "ehandle" ordinal))))))
                    (dissoc :seon.batch/settle))
        [result calls] (support/armed-calls '#{seon.turn.batch/record-form} #(batch/evaluate-batch request))]
    {:values (mapv :seon.batch/value (:seon.batch/entries result))
     :bound (mapv :seon.batch/sym (filter #(= :bind (:seon.batch/op %)) (:seon.batch/writes result)))
     :handle (sci/eval-string* (get-in request [:seon.batch/world :seon.sci.eval/ctx]) "result/ehandle0")
     :analyzed (get calls 'seon.turn.batch/record-form)}))

(deftest only-a-defining-form-is-analyzed
  (support/with-database
   (fn [connection]
     (let [plain (analyzed connection "(+ 1 1) (+ 2 2) (str \"a\" \"b\")")
           defined (analyzed connection "(defn twice \"Doubles x.\" {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x))")]
       (is (= [2 4 "ab"] (:values plain)))
       (is (= 2 (:handle plain)) "each value is still bound under its handle")
       (is (= {:bound [] :analyzed 0} (select-keys plain [:bound :analyzed]))
           "a form that defines nothing writes nothing and is not analyzed")
       (is (= {:bound ['user/twice] :analyzed 1} (select-keys defined [:bound :analyzed]))
           "one defn is its one write, analyzed once")))))
