(ns seon.cluster.mcp-test
  "The MCP surface shares Seon's admitted print-node value chain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.operator.runtime :refer [running-instances]]
            [seon.test-support :as support]))

(defn- projected
  [cluster-name effective value]
  (cluster/project-next-prepl-value!)
  (edn/read-string (cluster/mcp-valf cluster-name effective value)))

(defn- utf8-size
  [value]
  (alength (.getBytes ^String (pr-str value) "UTF-8")))

(deftest nested-bulk-is-bounded-by-the-shared-value-window
  (let [cluster-name "mcp-nested-window-test"
        effective (config/defaults)
        oversized-string
        (apply str
               (repeat (inc (:seon.config.eval.result/max-string effective))
                       \x))
        evaluation-shaped-value
        {:seon.sci.admit/value (vec (range 50000))
         :seon.cluster.eval/result-edn oversized-string}
        result (projected cluster-name effective evaluation-shaped-value)]
    (is (< (utf8-size result) 8192)
        "nested collection and string bulk cannot escape the value window")
    (is (true? (:seon.dev.mcp/windowed? result)))
    (is (string? (:seon.blob/digest result)))))

(deftest oversized-values-share-one-digest-across-storeless-and-stored-modes
  (let [cluster-name "mcp-value-test"
        value (vec (range 2000))
        effective (config/defaults)
        storeless (projected cluster-name effective value)]
    (is (true? (:seon.dev.mcp/windowed? storeless)))
    (is (false? (:seon.dev.mcp/retrievable? storeless)))
    (is (str/includes? (:seon.dev.mcp/remainder storeless)
                       "not retrievable"))
    (support/with-database
      {:seon.test-support/fresh-store? true}
      (fn [connection]
        (config/apply! {:seon.config/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (swap! running-instances assoc cluster-name
               {:seon.boot/cluster-connection connection})
        (try
          (let [stored (projected cluster-name effective value)
                content-digest (:seon.blob/digest stored)
                drilled (cluster/mcp-get-value
                         cluster-name content-digest [] 7)]
            (is (= (:seon.blob/digest storeless) content-digest))
            (is (true? (:seon.dev.mcp/retrievable? stored)))
            (is (= [7 8 9 10 11 12 13 14] drilled)))
          (finally
            (swap! running-instances dissoc cluster-name)))))))
