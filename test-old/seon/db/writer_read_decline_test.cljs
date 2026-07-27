(ns seon.db.writer-read-decline-test
  "Pod-side classification for writer read declines."
  (:require [cljs.test :refer [deftest is]]
            [seon.db :as db]
            [seon.db.protocol :as protocol]))

(deftest writer-kind-crosses-and-unknown-database-errors-default
  (let [response-error (deref #'db/response-error)
        steered
        (response-error
         {::protocol/success? false
          ::protocol/error-kind protocol/database-error
          ::protocol/error
          (str "Narrow the query, page the read, or ask the operator to raise "
               ":seon.config.database.read/max-results.")
          :seon.error/kind :user-input})
        unknown
        (response-error
         {::protocol/success? false
          ::protocol/error-kind protocol/database-error
          ::protocol/error "Unknown database failure."})]
    (is (= :user-input (:seon.error/kind steered)))
    (is (= :core-bug (:seon.error/kind unknown)))))
