(ns seon.cluster.mcp-test
  "The MCP surface shares Seon's admitted print-node value chain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.operator.runtime :refer [running-instances]]
            [seon.sci.admit :as admit]
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

(deftest door-evaluations-project-the-repl-text-face
  (let [cluster-name "mcp-text-face-test"
        effective (config/defaults)
        result-edn (:seon.cluster.eval/result-edn
                    (admit/admit
                     {:seon.sci.admit/value (vec (range 50000))
                      :seon.sci.admit/interrupt-fn (fn [])
                      :seon.sci.admit/caps (config/result-caps effective)
                      :seon.config/on-core-error :record}))
        evaluation {:seon.cluster.eval/result-edn result-edn
                    :seon.cluster.eval/ns [:seon.ns/name 'user]
                    :seon.sci.eval/ending-ns 'user
                    :seon.sci.admit/capped? true
                    :seon.sci.admit/record {:seon.eval/outcome :ok}}
        result (projected cluster-name effective evaluation)
        face (:seon.dev.mcp/value result)]
    (is (string? (:seon.dev.mcp/text face))
        "a door evaluation projects the printed REPL face")
    (is (str/starts-with? (:seon.dev.mcp/text face) "[0 1 2")
        "the text face reads like a REPL value")
    (is (not (contains? face :seon.cluster.eval/result-edn))
        "the node tree never rides the envelope; the text replaces it")
    (is (< (utf8-size result) 8192))))

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
                         cluster-name content-digest [] 7)
                past-end (cluster/mcp-get-value
                          cluster-name content-digest [] 9000)]
            (is (= (:seon.blob/digest storeless) content-digest))
            (is (true? (:seon.dev.mcp/retrievable? stored)))
            (is (= [7 8 9 10 11 12 13 14]
                   (:seon.render.value/window drilled)))
            (is (= 9000 (:seon.render.value/offset past-end)))
            (is (= 2000 (:seon.render.value/total past-end)))
            (is (true? (:seon.render.value/beyond-end? past-end))))
          (finally
            (swap! running-instances dissoc cluster-name)))))))
