(ns seon.cluster.mcp-test
  "The MCP surface shares Seon's admitted print-node value chain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.operator.runtime :refer [running-instances]]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(defn- projected
  [cluster-name effective value]
  (cluster/project-next-prepl-value!)
  (edn/read-string (cluster/mcp-valf cluster-name effective value)))

(defn- utf8-size
  [value]
  (alength (.getBytes ^String (pr-str value) "UTF-8")))

(defn- running-instance
  [connection cluster-name]
  (let [projection (schema/projection-from-database @connection)
        environment
        (env/environment
         {:seon.boot/cluster-name cluster-name
          :seon.db/basis-t (db/basis-t @connection)
          :seon.schema/projection projection})]
    {:seon.boot/cluster-connection connection
     :seon.sci.eval/ctx
     (env/carry-state {} (env/environment-state environment))}))

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

(deftest mcp-config-reads-receive-the-running-cluster-projection
  (let [cluster-name "mcp-projection-test"
        connection (atom ::database)
        projection (schema/build-projection (schema/snapshot))
        projection-state
        (env/environment-state
         (env/environment
          {:seon.boot/cluster-name cluster-name
           :seon.db/basis-t 0
           :seon.schema/projection projection}))
        observed (atom nil)]
    (swap! running-instances assoc cluster-name
           {:seon.boot/cluster-connection connection
            :seon.sci.eval/ctx
            (env/carry-state {} projection-state)})
    (try
      (with-redefs [config/effective
                    (fn [_ _]
                      (reset! observed (some? (schema/handed-projection)))
                      (config/defaults))]
        (is (= 42 (get-in (projected cluster-name (config/defaults) 42)
                          [:seon.dev.mcp/value]))
            "the MCP projection remains an ordinary successful value")
        (is (true? @observed)
            "the config read receives the projection already carried by ctx"))
      (finally
        (swap! running-instances dissoc cluster-name)))))

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

(deftest door-top-level-strings-use-the-shared-value-window
  (let [cluster-name "mcp-top-level-string-window-test"
        effective (config/defaults)
        evaluation (door-evaluation effective
                                    (apply str (repeat 1048576 \x)))
        artifact
        (render.value/artifact
         {:seon.sci.admit/print-node
          (edn/read-string (:seon.cluster.eval/result-edn evaluation))
          :seon.sci.admit/capped?
          (:seon.sci.admit/capped? evaluation)})
        artifact-content (render.value/artifact-edn artifact)
        result (projected cluster-name effective evaluation)
        text (get-in result [:seon.dev.mcp/value :seon.dev.mcp/text])]
    (is (< (utf8-size result) 8192)
        "a scalar face is bounded by the same window as structural values")
    (is (< (* 10 (utf8-size text)) (:seon.blob/size result))
        "the inline face is at least an order of magnitude smaller than its artifact")
    (is (true? (:seon.dev.mcp/windowed? result)))
    (is (true? (:seon.sci.admit/capped? result)))
    (is (= (blob/digest artifact-content) (:seon.blob/digest result))
        "windowing retains the complete artifact digest")
    (is (= (count artifact-content) (:seon.blob/size result))
        "windowing retains the complete artifact size")))

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
        (config/apply! {:seon.db/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (db/transact! connection
                      [{:seon.ns/name 'seon.cluster.mcp-test
                        :seon.ns/source "(ns seon.cluster.mcp-test)"}])
        (swap! running-instances assoc cluster-name
               (running-instance connection cluster-name))
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
            (is (= throw-site-frame (:seon.dev.mcp/frame face))
                "the root exception location survives instead of the serving frame")
            (is (= {:seon.error/diagnostic-layer :development-mcp
                    :seon.error/diagnostic-operation :evaluate-jvm
                    :seon.error/diagnostic-member :exception
                    :seon.error/diagnostic-expected
                    :successful-prepl-evaluation
                    :seon.error/diagnostic-offending
                    "java.lang.IllegalArgumentException"
                    :seon.error/diagnostic-cause small-message
                    :seon.error/diagnostic-evidence-availability
                    :seon.error/known
                    :seon.error/diagnostic-evidence
                    {:seon.dev.mcp/frame throw-site-frame}}
                   (select-keys
                    (:seon.error/data face)
                    [:seon.error/diagnostic-layer
                     :seon.error/diagnostic-operation
                     :seon.error/diagnostic-member
                     :seon.error/diagnostic-expected
                     :seon.error/diagnostic-offending
                     :seon.error/diagnostic-cause
                     :seon.error/diagnostic-evidence-availability
                     :seon.error/diagnostic-evidence])))
            (is (not (contains? face :seon.dev.mcp/text))
                "the same sentence is not rendered again inside the face")
            (is (false? (:seon.dev.mcp/windowed? result)))
            (is (not (contains? result :seon.blob/digest))
                "a small exception does not retain its bulky prepl envelope")
            ;; Diagnostic evidence accretes without changing the boundary:
            ;; require the flat face to stay an order of magnitude smaller
            ;; than the complete Throwable->map instead of freezing one byte
            ;; count for today's declared evidence attributes.
            (is (< (* 10 (utf8-size result)) (utf8-size envelope))
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
    (is (= :seon.dev.mcp/nil-deref (:seon.error/kind face)))
    (is (= deref-frame (:seon.dev.mcp/frame face)))
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
        (config/apply! {:seon.db/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (swap! running-instances assoc cluster-name
               (running-instance connection cluster-name))
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

(deftest retrievable-artifacts-have-an-identified-no-history-root
  (let [cluster-name "mcp-durable-artifact-test"
        effective (config/defaults)
        value (vec (range 2000))]
    (support/with-database
      {:seon.test-support/fresh-store? true}
      (fn [connection]
        (config/apply! {:seon.db/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (swap! running-instances assoc cluster-name
               (running-instance connection cluster-name))
        (try
          (let [stored (projected cluster-name effective value)
                content-digest (:seon.blob/digest stored)
                artifact-id
                (db/q
                 '[:find ?id .
                   :in $ ?digest
                   :where
                   [?artifact :seon.dev.mcp.artifact/id ?id]
                   [?artifact :seon.dev.mcp.artifact/digest ?digest]]
                 (db/db connection)
                 content-digest)]
            (is (true? (:seon.dev.mcp/retrievable? stored))
                "retrievability is returned only after the root commits")
            (is (= content-digest artifact-id)
                "the content digest identifies its durable artifact root")
            (is (true?
                 (:db/noHistory
                  (schema.datahike/malli->datahike-attr
                   :seon.dev.mcp.artifact/digest)))
                "the direct digest root derives Datahike noHistory")
            (db/transact!
             connection
             [[:db.fn/retractEntity
               [:seon.dev.mcp.artifact/id artifact-id]]])
            (is (empty?
                 (db/q
                  '[:find [?digest ...]
                    :where
                    [_ :seon.dev.mcp.artifact/digest ?digest]]
                  (db/history (db/db connection))))
                "explicit root retraction does not retain the digest in history")
            (is (= :seon.dev.mcp/value-not-found
                   (:seon.error/kind
                    (cluster/mcp-get-value
                     cluster-name content-digest [] 0)))
                "retraction ends the durable retrieval promise immediately"))
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
        (config/apply! {:seon.db/connection connection
                        :seon.boot/cluster-name cluster-name})
        (support/seed-cluster! connection cluster-name)
        (swap! running-instances assoc cluster-name
               (running-instance connection cluster-name))
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
