(ns seon.context-test
  "Contract proof for context derivation between runs."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [seon.context :as context]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.test-support :as test-support]))

(def ^:dynamic ^:private *connection* nil)

(defn- preserving-instrumentation-state
  [body]
  (test-support/with-database
   (fn [connection]
     (test-support/preserving-instrumentation-state
      #(binding [*connection* connection]
         (body))))))

(use-fixtures :each preserving-instrumentation-state)

(deftest message-custody-accepts-an-absent-run-when-instrumented
  (instrument/apply! {:seon.config/on-core-error :panic})
  (is (= :seon.context/history
         (context/message-custody (db/db *connection*) nil "agent" 1))))
