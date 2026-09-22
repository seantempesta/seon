(ns seon.cluster.mcp-test
  "The MCP surface shares Seon's admitted print-node value chain."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.operator.runtime :refer [running-instances]]
            [seon.oversight :as oversight]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest missing-artifacts-have-a-real-marker-under-armed-admission
  (let [missing {:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 123}
        artifact (render.value/artifact missing)]
    (is (= missing (render.value/artifact-value artifact)))
    (is (some? (:seon.sci.admit/print-node artifact)))
    (let [refusal (support/refusal-data
                   #(admit/admit-value
                     {:seon.sci.admit/value missing
                      :seon.sci.admit/caps {}
                      :seon.sci.admit/interrupt-fn (fn [])
                      :seon.config/on-core-error :record}))]
      (is ((schema/projection-validator (schema/handed-projection)
                                        :seon.instrument/contract-error) refusal))
      (is (= 'seon.sci.admit/admit-value
             (:seon.instrument/fn refusal))
          "bounded admission still requires its declared limits"))))

(defn- projected
  [cluster-name effective value & [evaluation? exception?]]
  (cluster/project-next-prepl-value! (boolean evaluation?))
  (edn/read-string (cluster/mcp-valf cluster-name effective value (boolean exception?))))

(defn- utf8-size
  [value]
  (alength (.getBytes ^String (pr-str value) "UTF-8")))

(defn- running-instance
  [connection cluster-name]
  (let [projection (schema/projection-from-database (db/db connection))
        environment
        (env/environment
         {:seon.boot/cluster-name cluster-name
          :seon.db/connection connection
          :seon.db/basis-t (db/basis-t (db/db connection))
          :seon.schema/projection projection})]
    {:seon.boot/cluster-connection connection
     :seon.turn.loop/cluster (env/carry-state {} (env/environment-state environment))
     :seon.sci.eval/ctx
     (env/carry-state {} (env/environment-state environment))}))

(defn- sci-evaluation
  [effective source]
  (support/with-database
    (fn [connection]
      (sci.eval/evaluate
       {:seon.cluster.eval/source source
        :seon.cluster.eval/ns [:seon.ns/name 'user]
        :seon.db/db (db/db connection)
        :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
        :seon.sci.admit/caps (config/result-caps effective)
        :seon.render/profile (render/agent-render-profile effective)
        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms effective)
        :seon.config/on-core-error :panic}))))

(defn- observed-query-shape
  []
  (let [text (apply str (repeat 36 \x))]
    (mapv (fn [row]
            (into {:db/id row}
                  (map (fn [field]
                         [(keyword "seon.fn" (str "field-" field)) text]))
                  (range 7)))
          (range 116))))

(deftest mcp-config-reads-receive-the-running-cluster-projection
  (support/with-database
   (fn [connection]
     (let [cluster-name "mcp-projection-test"
           database (db/db connection)
           projection (db/carried-projection database)
           projection-state (:seon.sci.eval/projection-state (meta database))
           observed (atom nil)]
       (swap! running-instances assoc cluster-name
              {:seon.boot/cluster-connection connection
               :seon.sci.eval/ctx (env/carry-state {} projection-state)})
       (try
         (with-redefs [config/effective
                       (fn [value _]
                         (reset! observed
                                 (identical? projection (db/carried-projection value)))
                         config/defaults)]
           (is (= 42 (get-in (projected cluster-name config/defaults 42)
                            [:seon.dev.mcp/value])))
           (is (true? @observed)
               "the config read receives the projection carried by its database"))
         (finally
           (swap! running-instances dissoc cluster-name)))))))

(deftest nested-bulk-is-bounded-by-the-shared-value-window
  (let [cluster-name "mcp-nested-window-test"
        effective config/defaults
        oversized-string
        (apply str
               (repeat (inc (:seon.config.eval.result/max-string effective))
                       \x))
        nested-value {:rows (vec (range 50000)) :text oversized-string}
        result (projected cluster-name effective nested-value)]
    (is (< (utf8-size result) 8192)
        "nested collection and string bulk cannot escape the value window")
    (is (true? (:seon.dev.mcp/windowed? result)))
    (is (string? (:seon.blob/digest result)))))

(deftest sci-evaluations-project-the-repl-text-face
  (let [cluster-name "mcp-text-face-test"
        effective config/defaults
        evaluation (sci-evaluation effective "(vec (range 50000))")
        result (projected cluster-name effective evaluation true false)
        face (:seon.dev.mcp/value result)]
    (is (string? (:seon.dev.mcp/text face))
        "an SCI evaluation projects the printed REPL face")
    (is (str/starts-with? (:seon.dev.mcp/text face) "[0 1 2")
        "the text face reads like a REPL value")
    (is (= (:seon.eval/shown evaluation) (:seon.dev.mcp/text face))
        "the saved observation is not rendered again")
    (is (not (contains? face :seon.sci.admit/value))
        "the live result does not ride beside its shown text")
    (is (< (utf8-size result) 8192))))

(deftest sci-top-level-strings-use-the-shared-value-window
  (let [cluster-name "mcp-top-level-string-window-test"
        effective config/defaults
        evaluation (sci-evaluation effective
                                    "(apply str (repeat 1048576 \\x))")
        result (projected cluster-name effective evaluation true false)
        text (get-in result [:seon.dev.mcp/value :seon.dev.mcp/text])]
    (is (< (utf8-size result) 8192)
        "a scalar face is bounded by the same window as structural values")
    (is (= (:seon.eval/shown evaluation) text))
    (is (false? (:seon.dev.mcp/windowed? result)))
    (is (not (contains? result :seon.blob/digest))
        "SCI results retain their live objects, not serialized result blobs")))

(deftest ordinary-mcp-results-always-use-the-explicit-mcp-profile
  (let [cluster-name "mcp-unconditional-fit-test"
        effective (assoc config/defaults
                         :seon.config.eval.result/blob-threshold 1000000)
        text (apply str (repeat 36 \x))
        result (projected cluster-name effective (observed-query-shape))
        face (:seon.dev.mcp/value result)
        rendered (pr-str face)
        elisions (filter #(and (map? %) (:seon.print/omitted %))
                         (tree-seq coll? seq face))]
    (is (str/includes? rendered (pr-str text))
        "the observed 36-character strings remain readable")
    (is (str/includes? rendered ":seon.render.profile/mcp")
        "the below-blob-threshold result still carries the MCP fit profile")
    (is (some #(= [] (:seon.render.data/path %)) elisions)
        "collection elisions retain their coordinates")
    (is (some #(seq (:seon.render.data/path %)) elisions)
        "nested map-member elisions retain their coordinates too")
    (is (every? #(and (pos-int? (:seon.print/omitted %))
                     (string? (:seon.print/requery-refusal %))) elisions)
        "a cut without a stored artifact names why it cannot be requeried")
    (is (false? (:seon.dev.mcp/windowed? result))
        "presentation fitting does not invent a durable artifact")))

(deftest sci-artifact-size-ignores-evaluation-envelope-bulk
  (let [cluster-name "mcp-small-sci-value-test"
        effective config/defaults
        evaluation
        (assoc (sci-evaluation effective "42")
               :seon.sci.eval/internal-detail (apply str (repeat 5000 \x)))
        result (projected cluster-name effective evaluation true false)
        face (:seon.dev.mcp/value result)]
    (is (= "42" (:seon.dev.mcp/text face)))
    (is (= (:seon.sci.admit/record evaluation)
           (:seon.sci.admit/record face))
        "evaluation diagnostics stay inline beside the text face")
    (is (false? (:seon.dev.mcp/windowed? result)))
    (is (not (contains? result :seon.blob/digest)))))

(defn- jvm-exceptions-retain-the-root-location-and-flat-error
  [connection]
  (let [cluster-name "mcp-jvm-exception-face-test"
        effective config/defaults
        inline-ceiling (:seon.config.eval.result/blob-threshold effective)
        dependency-frame
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
                :at dependency-frame}]
         :trace (into [dependency-frame serving-frame]
                      (repeat 500 serving-frame))
         :cause small-message
         :phase :execution}]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (support/transacted! connection
                         [{:seon.ns/name 'seon.cluster.mcp-test
                           :seon.ns/source "(ns seon.cluster.mcp-test)"}])
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [result (projected cluster-name effective envelope false true)
            face (:seon.dev.mcp/value result)
            oversized-result
            (projected cluster-name effective
                       (assoc envelope :cause oversized-message) false true)
            retained-message
            (cluster/mcp-get-value
             cluster-name (:seon.blob/digest oversized-result)
             [:seon.error/message] 0)]
        (is (= serving-frame (:seon.error/frame face))
            "the first first-party frame survives instead of the dependency frame")
        (is (= 'java.lang.IllegalArgumentException
               (:seon.error/exception-class face)))
        (is (= small-message (:seon.error/message face)))
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
        (is (= (subs oversized-message 0 72)
               (:seon.render.value/window retained-message))
            "a genuinely oversized message remains pageable by digest")
        (is (= (count oversized-message)
               (:seon.render.value/total retained-message))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(deftest jvm-nil-deref-is-a-flat-error-value
  (let [cluster-name "mcp-jvm-nil-deref-test"
        effective config/defaults
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
          :phase :execution} false true)
        face (:seon.dev.mcp/value result)]
    (is (= 'java.lang.NullPointerException
           (:seon.error/exception-class face)))
    (is (= serving-frame (:seon.error/frame face)))
    (is (= "The evaluated form dereferenced nil."
           (:seon.error/message face)))
    (is (not (str/includes? (pr-str result) "Future.get"))
        "the misleading host overload sentence must not leak")))

(deftest jvm-ex-info-projects-its-message-class-and-first-party-frame
  (let [cluster-name "mcp-jvm-ex-info-test"
        dependency-frame
        ['malli.core$_map_schema$reify__1 'invoke "core.cljc" 1289]
        first-party-frame
        ['seon.cluster$mcp_io_prepl 'invokeStatic "cluster.clj" 336]
        message "The JVM evaluation refused the supplied member."
        face
        (:seon.dev.mcp/value
         (projected
          cluster-name config/defaults
          {:via [{:type 'clojure.lang.ExceptionInfo
                  :message message
                  :at dependency-frame}]
           :trace [dependency-frame first-party-frame]
           :cause message
           :phase :execution}
          false true))]
    (is (= message (:seon.error/message face)))
    (is (= 'clojure.lang.ExceptionInfo
           (:seon.error/exception-class face)))
    (is (= first-party-frame (:seon.error/frame face)))))

(deftest runtime-observation-counts-problems-without-embedding-facts
  (let [cluster-name "mcp-runtime-problem-count-test"
        large-detail (apply str (repeat 20000 \x))]
    (swap! running-instances assoc cluster-name
           {:seon.boot/cluster-connection ::connection})
    (try
      (with-redefs [boot/readiness
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

(deftest runtime-observation-shows-unavailable-problems-with-their-cause
  ;; `seon.problems/problems` may answer a declared refusal instead of the
  ;; per-family map (observed on default 2026-09-23: latest-results refused
  ;; an incomplete member). The observation must not count the refusal's
  ;; keys (a Date is not countable) nor show counts that read as healthy.
  (let [cluster-name "mcp-runtime-problems-unavailable-test"
        refusal {:seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.test/execution
                 :seon.error/operation 'seon.test.runner/latest-results
                 :seon.error/message
                 "The test execution evidence does not authorize this transition."
                 :seon.error/expected :completed-member
                 :seon.error/offending 'example-test/incomplete
                 :seon.test/execution-refusal :seon.test/population-unknown}]
    (swap! running-instances assoc cluster-name
           {:seon.boot/cluster-connection ::connection})
    (try
      (with-redefs [boot/readiness
                    (fn [_]
                      {:seon.boot/cluster-name cluster-name
                       :seon.problems/problems refusal})]
        (let [result (cluster/mcp-runtime-observation cluster-name)]
          (is (= refusal (:seon.dev.mcp/problems-unavailable result)))
          (is (nil? (find result :seon.dev.mcp/problem-counts)))
          (is (not (contains? (:seon.dev.mcp/readiness result)
                              :seon.problems/problems)))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(deftest ^{:seon.test/fixture-observation
           "Observes that flow health uses an ordinary canonical branch; the fresh-store owner is referenced only to assert zero acquisitions."}
  live-runtime-observation-hands-its-projection-to-flow-health
  (let [cluster-name "mcp-runtime-projection-test"
        fresh-stores (atom 0)
        acquire @#'support/with-fresh-database]
    (with-redefs-fn
      {#'support/with-fresh-database
       (fn [& args] (swap! fresh-stores inc) (apply acquire args))}
      (fn []
        (support/with-database
         (fn [connection]
           (config/apply! {:seon.db/connection connection
                          :seon.boot/cluster-name cluster-name})
           (support/seed-cluster! connection cluster-name)
           (swap! running-instances assoc cluster-name
                  (assoc (running-instance connection cluster-name)
                         :seon.flow/graph ::graph))
           (try
             (with-redefs
              [boot/readiness
               (fn [_] {:seon.boot/cluster-name cluster-name})
               oversight/flow-status
               (fn [database _]
                 (let [effective (config/effective database cluster-name)]
                   (if (or (:seon.config/missing-effective effective) (:seon.config/error-key effective) (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
                     effective
                     {:seon.oversight/agents []
                      :seon.oversight/plumbing []})))]
               (let [result (cluster/mcp-runtime-observation cluster-name)]
                 (is (= :observed (:seon.dev.mcp/health result)))
                 (is (= {:seon.oversight/agents []
                         :seon.oversight/plumbing []}
                        (:seon.dev.mcp/flow result)))
                 (is (= :observed (:seon.dev.mcp/health result)))))
             (finally
               (swap! running-instances dissoc cluster-name)))))))
    (is (zero? @fresh-stores)
        "Flow-health projection needs only an ordinary canonical branch.")))

(defn- oversized-values-share-one-digest-across-storeless-and-stored-modes
  [connection]
  (let [cluster-name "mcp-value-test"
        value (vec (range 2000))
        effective config/defaults
        storeless (projected cluster-name effective value)]
    (is (true? (:seon.dev.mcp/windowed? storeless)))
    (is (false? (:seon.dev.mcp/retrievable? storeless)))
    (is (str/includes? (:seon.dev.mcp/remainder storeless)
                       "not retrievable"))
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
        (swap! running-instances dissoc cluster-name)))))

(defn- stored-strings-page-by-character-offset
  [connection]
  (let [cluster-name "mcp-string-page-test"
        effective config/defaults
        value (apply str (take 4975 (cycle "abcdefghijklmnopqrstuvwxyz")))
        page-size (:seon.print/width (print/default-options))]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [stored (projected cluster-name effective value)
            digest (:seon.blob/digest stored)
            first-page (cluster/mcp-get-value cluster-name digest [] 0)
            next-page
            (cluster/mcp-get-value cluster-name digest [] page-size)
            past-end
            (cluster/mcp-get-value cluster-name digest [] (count value))]
        (is (= (subs value 0 page-size)
               (:seon.render.value/window first-page)))
        (is (= (subs value page-size (* 2 page-size))
               (:seon.render.value/window next-page)))
        (is (= page-size (:seon.render.value/shown first-page)))
        (is (= (count value) (:seon.render.value/total first-page)))
        (is (seq (:seon.render.value/window first-page))
            "a non-empty stored string has a non-empty first window")
        (is (= (subs value (dec (count value)))
               (:seon.render.value/window past-end))
            "a past-end page of a non-empty string remains non-empty")
        (is (true? (:seon.render.value/beyond-end? past-end))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(defn- retrievable-artifacts-have-an-identified-no-history-root
  {:malli/schema [:=> [:cat :seon.db/connection] :boolean]}
  [connection]
  (let [cluster-name "mcp-durable-artifact-test"
        effective config/defaults
        value (vec (range 3000))]
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
        (let [requery (some :seon.print/requery-form
                            (tree-seq coll? seq (:seon.dev.mcp/value stored)))]
          (is (seq requery) "a clipped value supplies an executable requery")
          (is (= value (eval requery))
              "the advertised requery reads the complete stored value"))
        (is (= content-digest artifact-id)
            "the content digest identifies its durable artifact root")
        (is (true?
             (:db/noHistory
              (schema.datahike/malli->datahike-attr-in (seon.schema/handed-projection) :seon.dev.mcp.artifact/digest)))
            "the direct digest root derives Datahike noHistory")
        (support/transacted!
                connection
                [[:db.fn/retractEntity
                  [:seon.dev.mcp.artifact/id artifact-id]]])
        (is (empty?
             (db/q
              '[:find [?digest ...]
                :in $ ?digest
                :where
                [_ :seon.dev.mcp.artifact/digest ?digest]]
              (db/history (db/db connection)) content-digest))
            "explicit root retraction does not retain the digest in history")
        (is (= content-digest
               (:seon.dev.mcp/value-not-found
                (cluster/mcp-get-value
                 cluster-name content-digest [] 0)))
            "retraction ends the durable retrieval promise immediately"))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(defn- ordinary-value-artifacts-drill-from-the-result-root
  [connection]
  (let [cluster-name "mcp-sci-value-test"
        effective config/defaults
        value (assoc (vec (range 2000)) 1999 2999)
        nested-value {:alpha value :omega 42}]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [stored (projected cluster-name effective value)
            content-digest (:seon.blob/digest stored)
            nested-stored (projected cluster-name effective nested-value)
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
        (swap! running-instances dissoc cluster-name)))))

(deftest ^{:seon.test/fixture-observation
           "Artifact blobs are store-global: identity, paging and root retraction require a private physical store."}
  artifact-lifecycle-preserves-identity-paging-and-retraction
  (let [fresh-stores (atom 0)
        executed (atom [])
        acquire @#'support/with-fresh-database
        scenarios
        [[:exception jvm-exceptions-retain-the-root-location-and-flat-error]
         [:identity oversized-values-share-one-digest-across-storeless-and-stored-modes]
         [:string stored-strings-page-by-character-offset]
         [:nested ordinary-value-artifacts-drill-from-the-result-root]
         [:retraction retrievable-artifacts-have-an-identified-no-history-root]]]
    (with-redefs-fn
      {#'support/with-fresh-database
       (fn [& args] (swap! fresh-stores inc) (apply acquire args))}
      (fn []
        (support/with-database
         {:seon.test-support/fresh-store? true}
         (fn [connection]
           (doseq [[scenario verify!] scenarios]
             (verify! connection)
             (swap! executed conj scenario))))))
    (is (= [:exception :identity :string :nested :retraction] @executed)
        "All five artifact cases execute, with root retraction last.")
    (is (= 1 @fresh-stores)
        "Blob-global isolation requires exactly one fresh physical store.")))
