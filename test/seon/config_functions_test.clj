(ns seon.config-functions-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical fixture acquisition, one refused config write, then declaration and config writes; measured 13.05 s. The symbol query itself is 4.70 ms; writer-wide work remains a measured defect."
           :seon.test/long-ms 20000}
  manifest-functions-must-exist-before-reconciliation
  (support/with-database
   (fn [connection]
     (let [function 'my.note/config-supplier
           compiled (config/compile-manifest
                     {:seon.boot/cluster-name "config-functions"
                      :seon.config/manifest
                      {:seon.config.web/search-result-projection function}})
           before (db/basis-t (db/db connection))
           refusal (support/refusal-data #(config/apply-compiled! connection compiled))]
       (is (some? refusal))
       (is (str/includes? (:seon.error/message refusal) (str function)))
       (is (str/includes? (:seon.error/message refusal) ":seon.config.web/search-result-projection"))
       (is (= before (db/basis-t (db/db connection))))
       (support/transacted!
        connection
        [(support/program-fn-row (db/db connection) function "(defn config-supplier [] {})")])
       (let [result (config/apply-compiled! connection compiled)]
         (is (nil? (:seon.error/at result)) (pr-str result))
         (is (= function
                (:seon.config.web/search-result-projection
                 (db/pull (db/db connection) [:seon.config.web/search-result-projection]
                          [:seon.config/cluster "config-functions"])))))))))
