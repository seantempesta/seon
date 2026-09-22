(ns seon.error.refusal-test
  "Cause-chain readers preserve producer errors and ordinary exception data.
  Their error-handling contracts use the base; each producer owns its named output."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.cluster.reply :as reply]
            [seon.error.refusal :as error.refusal]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private entries [#'error.refusal/refusal #'error/refusal])

(def ^:private base
  {:seon.error/at #inst "2026-09-18T00:00:00Z"
   :seon.error/layer :seon.error.refusal-test/probe
   :seon.error/operation 'seon.error.refusal-test/probe})

(def ^:private domain
  (assoc base :seon.agent/error-agent-id "refusal-agent"))

(deftest refusal-readers-declare-the-error-handling-base
  (doseq [entry entries]
    (is (= [:or :nil :map :seon.error/base]
           (last (:malli/schema (meta entry)))))))

(deftest armed-refusal-returns-every-declared-family-without-refusing
  (test-support/preserving-instrumentation-state
   (fn []
     (test-support/with-database
      (fn [connection]
        (let [projection (schema/projection-from-database (db/db connection))
              caps (config/result-caps (test-support/effective-config))
              buried (fn [data]
                       (RuntimeException. "wrapper" (ex-info "buried" data)))]
          (doseq [entry entries]
            (#'instrument/arm-var! entry (:malli/schema (meta entry))
                                   projection projection caps
                                   {:seon.config/on-core-error :panic}))
          (doseq [entry entries
                  [label data] [[:base base]
                                [:declared-schema domain]
                                [:unclassified {:rule :ordinary-ex-data}]]]
            (testing (str entry " " label)
              (let [returned (@entry (buried data))]
                (is (= (cond-> data
                         (:seon.error/at data) (assoc :seon.error/message "buried")) returned)
                    "The armed wrapper admits the value the cause chain carried."))))
          (testing "each classified family validates against a declared alternative"
            (doseq [[value declared-schema] [[base nil]
                                   [domain :seon.agent/error]]]
              (is ((schema/projection-validator projection :seon.error/base) value)
                  (pr-str value))
              (when declared-schema
                (is ((seon.schema/projection-validator projection declared-schema) value)
                    (pr-str value)))))
          (doseq [entry entries]
            (testing (str entry " carries no data anywhere in the chain")
              (is (nil? (@entry (RuntimeException. "no data"))))
              (is (nil? (@entry nil)))))))))))

(deftest constructor-preserves-open-domain-maps
  (doseq [value [{:seon.error/at (java.util.Date. 0)
                    :seon.error/layer :x/y
                    :seon.error/operation 'a/b
                    :x/member 1}
                   (sorted-map :seon.error/at (java.util.Date. 0)
                               :seon.error/layer :x/y
                               :seon.error/operation 'a/b
                               :x/member 1)]]
       (is (= value (error.refusal/diagnostic value)))))

(deftest constructor-consumes-only-the-throwable-input
  (let [failure (doto (Exception. "specific cause")
                     (.setStackTrace
                      (into-array StackTraceElement
                                  [(StackTraceElement. "example.Failure" "run" "failure.clj" 42)])))
           value {:seon.error/at (java.util.Date. 0)
                  :seon.error/layer :x/y
                  :seon.error/operation 'a/b
                  :seon.error/cause [:seon.error/id "recorded-cause"]
                  :x/member {:x/detail "retained"}}
           returned (error.refusal/diagnostic (assoc value :seon.error/throwable failure))]
       (is (= (assoc value :seon.error/frame '[example.Failure run "failure.clj" 42]
                          :seon.error/exception-class 'java.lang.Exception)
              returned))
       (.setStackTrace failure (make-array StackTraceElement 0))
       (is (= (assoc value :seon.error/exception-class 'java.lang.Exception)
              (error.refusal/diagnostic (assoc value :seon.error/throwable failure))))))

(deftest constructor-output-keeps-the-producing-contract
  (let [projection (-> (schema/handed-projection)
                          (schema/projection-with-schema :x/member :int
                           {:seon.schema.admission/source :core})
                          (schema/projection-with-schema
                           :x/y-error [:and :seon.error/base [:map [:x/member [:= 1]]]]
                           {:seon.schema.admission/source :core})
                          (schema/projection-with-schema
                           :x/z-error [:and :seon.error/base [:map [:x/member [:= 2]]]]
                           {:seon.schema.admission/source :core}))
           caps (config/result-caps (test-support/effective-config))
           value {:seon.error/at (java.util.Date. 0) :seon.error/layer :x/y
                  :seon.error/operation 'a/b :x/member 1}
           accepted (instrument/wrap-interpreted
                     'x/accepted "[:=> [:cat :map] :x/y-error]"
                     projection :panic caps error.refusal/diagnostic)
           refused (instrument/wrap-interpreted
                    'x/refused "[:=> [:cat :map] :x/z-error]"
                    projection :panic caps error.refusal/diagnostic)]
       (is (= value (accepted value)))
       (let [result (test-support/refusal-data #(refused value))]
         (is ((schema/projection-validator projection :seon.instrument/contract-error) result))
         (is (= 'x/refused (:seon.error/operation result))))))

(deftest facade-is-retired-and-reader-refusal-is-a-literal
  (is (nil? (ns-resolve 'seon.error 'diagnostic)))
  (let [value (with-redefs [error.refusal/diagnostic
                            (fn [_] (throw (ex-info "Unexpected constructor call" {})))]
                (reply/sources "; comment only" 'user 100))]
    (is (true? (:seon.cluster.reply/no-forms value)))
    (is (inst? (:seon.error/at value)))
    (is (= 'seon.cluster.reply/sources (:seon.error/operation value)))))
