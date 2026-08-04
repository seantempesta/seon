(ns seon.cluster.mcp-test
  "The MCP surface shares Seon's admitted print-node value chain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
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

(defn- door-evaluation
  [effective value]
  (let [admitted
        (admit/admit
         {:seon.sci.admit/value value
          :seon.sci.admit/interrupt-fn (fn [])
          :seon.sci.admit/caps (config/result-caps effective)
          :seon.config/on-core-error :record})]
    (assoc admitted
           :seon.cluster.eval/ns [:seon.ns/name 'user]
           :seon.sci.eval/ending-ns 'user)))

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

(deftest door-artifact-size-ignores-evaluation-envelope-bulk
  (let [cluster-name "mcp-small-door-value-test"
        effective (config/defaults)
        evaluation
        (assoc (door-evaluation effective 42)
               :seon.sci.eval/internal-detail (apply str (repeat 5000 \x)))
        result (projected cluster-name effective evaluation)
        face (:seon.dev.mcp/value result)]
    (is (= "42" (:seon.dev.mcp/text face)))
    (is (= (:seon.sci.admit/record evaluation)
           (:seon.sci.admit/record face))
        "evaluation diagnostics stay inline beside the text face")
    (is (false? (:seon.dev.mcp/windowed? result)))
    (is (not (contains? result :seon.blob/digest)))))

(deftest jvm-exceptions-retain-the-root-location-and-flat-error
  (let [cluster-name "mcp-jvm-exception-face-test"
        effective (config/defaults)
        inline-ceiling (:seon.config.eval.result/blob-threshold effective)
        throw-site-frame
        ['malli.core$_map_schema$reify__1 'invoke "core.cljc" 1289]
        serving-frame
        ['seon.cluster$mcp_io_prepl 'invokeStatic "cluster.clj" 336]
        small-message "The contract value was wrong."
        oversized-message (apply str (repeat (inc inline-ceiling) \x))
        envelope
        {:via [{:type 'clojure.lang.ExceptionInfo
                :message "The wrapper."
                :at serving-frame}
               {:type 'java.lang.IllegalArgumentException
                :message small-message
                :at throw-site-frame}]
         :trace (into [throw-site-frame serving-frame]
                      (repeat 500 serving-frame))
         :cause small-message
         :phase :execution}]
    (support/with-database
      {:seon.test-support/fresh-store? true}
      (fn [connection]
        (config/apply! {:seon.config/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (db/transact! connection
                      [{:seon.ns/name 'seon.cluster.mcp-test
                        :seon.ns/source "(ns seon.cluster.mcp-test)"}])
        (swap! running-instances assoc cluster-name
               {:seon.boot/cluster-connection connection})
        (try
          (let [result (projected cluster-name effective envelope)
                face (:seon.dev.mcp/value result)
                oversized-result
                (projected cluster-name effective
                           (assoc envelope :cause oversized-message))
                retained-message
                (cluster/mcp-get-value
                 cluster-name (:seon.blob/digest oversized-result)
                 [:seon.error/message] 0)]
            (is (= {:seon.dev.mcp/exception-class
                    "java.lang.IllegalArgumentException"
                    :seon.error/kind :seon.dev.mcp/jvm-exception
                    :seon.error/message small-message
                    :seon.dev.mcp/frame throw-site-frame}
                   face)
                "the root exception location survives instead of the serving frame")
            (is (not (contains? face :seon.dev.mcp/text))
                "the same sentence is not rendered again inside the face")
            (is (false? (:seon.dev.mcp/windowed? result)))
            (is (not (contains? result :seon.blob/digest))
                "a small exception does not retain its bulky prepl envelope")
            (is (< (utf8-size result) 1024)
                "the complete Throwable->map does not become the inline face")
            (is (true? (:seon.dev.mcp/windowed? oversized-result)))
            (is (string? (:seon.blob/digest oversized-result)))
            (is (true? (:seon.dev.mcp/retrievable? oversized-result)))
            (is (= oversized-message
                   (:seon.render.value/window retained-message))
                "a genuinely oversized message remains available by digest"))
          (finally
            (swap! running-instances dissoc cluster-name)))))))

(deftest jvm-nil-deref-is-a-flat-error-value
  (let [cluster-name "mcp-jvm-nil-deref-test"
        effective (config/defaults)
        deref-frame
        ['clojure.core$deref_future 'invokeStatic "core.clj" 2314]
        serving-frame
        ['seon.cluster$mcp_io_prepl 'invokeStatic "cluster.clj" 336]
        result
        (projected
         cluster-name effective
         {:via [{:type 'java.lang.NullPointerException
                 :message
                 "Cannot invoke java.util.concurrent.Future.get() because fut is null"
                 :at deref-frame}]
          :trace [deref-frame serving-frame]
          :cause
          "Cannot invoke java.util.concurrent.Future.get() because fut is null"
          :phase :execution})
        face (:seon.dev.mcp/value result)]
    (is (= {:seon.error/kind :seon.dev.mcp/nil-deref
            :seon.error/message "The evaluated form dereferenced nil."
            :seon.dev.mcp/exception-class "java.lang.NullPointerException"
            :seon.dev.mcp/frame deref-frame}
           face))
    (is (not (str/includes? (pr-str result) "Future.get"))
        "the misleading host overload sentence must not leak")))

(deftest runtime-observation-counts-problems-without-embedding-facts
  (let [cluster-name "mcp-runtime-problem-count-test"
        large-detail (apply str (repeat 20000 \x))]
    (swap! running-instances assoc cluster-name
           {:seon.boot/cluster-connection ::connection})
    (try
      (with-redefs [cluster/readiness
                    (fn [_]
                      {:seon.boot/cluster-name cluster-name
                       :seon.problems/problems
                       {:seon.problems/error-signatures
                        [{:seon.error/fact
                          {:seon.error/data-edn large-detail}}
                         {:seon.error/fact
                          {:seon.error/data-edn large-detail}}]
                        :seon.problems/errored-receipts
                        [{:seon.cluster.eval/error large-detail}]}})]
        (let [result (cluster/mcp-runtime-observation cluster-name)
              ready (:seon.dev.mcp/readiness result)]
          (is (= {:seon.problems/error-signatures 2
                  :seon.problems/errored-receipts 1}
                 (:seon.dev.mcp/problem-counts result)))
          (is (not (contains? ready :seon.problems/problems)))
          (is (< (utf8-size result) 1024))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

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

(deftest door-value-artifacts-drill-from-the-result-root
  (let [cluster-name "mcp-door-value-test"
        effective (config/defaults)
        door-result (door-evaluation effective (vec (range 2000)))
        nested-result
        (door-evaluation effective {:alpha (vec (range 2000)) :omega 42})]
    (support/with-database
      {:seon.test-support/fresh-store? true}
      (fn [connection]
        (config/apply! {:seon.config/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (swap! running-instances assoc cluster-name
               {:seon.boot/cluster-connection connection})
        (try
          (let [stored (projected cluster-name effective door-result)
                content-digest (:seon.blob/digest stored)
                nested-stored (projected cluster-name effective nested-result)
                root (cluster/mcp-get-value
                      cluster-name content-digest [] 0)
                nested (cluster/mcp-get-value
                        cluster-name (:seon.blob/digest nested-stored)
                        [:alpha] 7)
                projected-root (projected cluster-name effective root)]
            (is (= [0 1 2 3 4 5 6 7]
                   (:seon.render.value/window root)))
            (is (= [7 8 9 10 11 12 13 14]
                   (:seon.render.value/window nested)))
            (is (false? (:seon.dev.mcp/windowed? projected-root))
                "reading the result root must not mint another artifact")
            (is (not (contains? projected-root :seon.blob/digest))))
          (finally
            (swap! running-instances dissoc cluster-name)))))))
