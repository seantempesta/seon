(ns seon.render.value-options-test
  "Database-backed options at the routed structural-value boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.render.web :as web]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps config/defaults))

(deftest data-response-reads-the-presentation-window-per-request
  (support/with-database
    (fn [connection]
      (config/apply!
       {:seon.db/connection connection
        :seon.boot/cluster-name "options-test"
        :seon.config/manifest
        {:seon.render.value/max-collection 3}})
      (support/seed-cluster! connection "options-test")
      (let [response
            (#'web/data-response
             ;; SUPPLY EVERY DECLARED INPUT, exactly like production: the
             ;; render service carries the cluster's SCI context, and the
             ;; projection every `/data` response reads comes from it.
             {:seon.store/connection-object connection
              :seon.agent/id "root"
              :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
              :seon.sci.admit/caps caps}
             {:query-string ""})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "showing 1–3 of")
            "the database-backed dial sizes this request's window")))))
