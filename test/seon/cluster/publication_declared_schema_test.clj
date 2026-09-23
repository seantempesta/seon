(ns seon.cluster.publication-declared-schema-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as source-test]
            [seon.id :as id]
            [seon.schema :as schema]))

(deftest ^{:seon.test/long "Publish the canonical program and acquire its stored error declared-schemas."
           :seon.test/long-ms 600000}
  publication-admits-optional-in-memory-offending-members
  (#'source-test/with-store
    (fn [opened]
      (let [published (#'source-test/publish opened (id/digest 64 [:runner-declared-schemas]))
            database (source/database opened (:seon.source/commit-id published))]
        (try
          (let [projection (schema/projection-from-database database)
                base {:seon.error/at (java.util.Date.)
                      :seon.error/layer :seon.test/selection
                      :seon.error/operation 'seon.test/run
                      :seon.error/message "Invalid producer input."}]
            (is (uuid? (:seon.source/commit-id published)))
            (doseq [[declared-schema members]
                    [[:seon.test.runner/invalid-marker-reason-error
                      {:seon.test.runner/test-sym 'example/check :seon.test.runner/marker-key :seon.test/long}]
                     [:seon.test.runner/unknown-worker-command-error
                      {:seon.test.runner/worker-id "worker" :seon.test.runner/worker-command-key :unknown}]
                     [:seon.test.runner/worker-launch-failure-error
                      {:seon.test.runner/worker-id "worker" :seon.test.runner/worker-error-log "worker.log"}]]]
              (let [valid? (schema/projection-validator projection declared-schema)
                    value (merge base members)]
                (is (true? (valid? value)) (str declared-schema " does not require a raw object"))
                (is (true? (valid? (assoc value :seon.error/offending {:input [1 :bad]})))
                    (str declared-schema " accepts the producer's raw observation"))
                (is (false? (valid? base)) (str declared-schema " still requires domain facts")))))
          (finally (d/release-materialized-db database)))))))
