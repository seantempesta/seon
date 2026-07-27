(ns seon.flow.indexer-test
  "Standing proof that JVM indexing uses the shared Flow machinery."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow-monitor :as flow-monitor]
            [clojure.datafy :as datafy]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.db.program :as program]
            [seon.flow :as sut]
            [seon.ns.source :as ns-source]
            [seon.program.edge :as edge]
            [seon.schema :as schema])
  (:import [java.io PushbackReader StringReader]
           [java.net ServerSocket URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent CountDownLatch ExecutorService TimeUnit]))

(def ^:private event-backstop-seconds 20)

(def ^:private fixture-root
  (io/file "test" "seon" "flow" "fixtures"))

(def ^:private good-fixtures
  (sorted-map
   'seon.flow.fixtures.alpha (io/file fixture-root "alpha.cljc")
   'seon.flow.fixtures.beta (io/file fixture-root "beta.cljc")
   'seon.flow.fixtures.gamma (io/file fixture-root "gamma.cljc")))

(def ^:private malformed-fixture
  (io/file fixture-root "malformed.cljc"))

(def ^:private eof (Object.))

(def ^:private program-attributes
  [:seon.ns/name
   :seon.ns/require-edges
   :seon.ns.require/target
   :seon.ns.require/alias
   :seon.ns.require/refers
   :seon.ns.require/refer-all?
   :seon.ns.require/as-alias?
   :seon.fn/sym
   :seon.fn/ns
   :seon.fn/source
   :seon.fn/spec
   :seon.fn/doc
   :seon.fn/arglists
   :seon.fn/private?
   :seon.schema/key
   :seon.schema/ns
   :seon.schema/form
   :seon.db.id/generator
   ::edge/generation
   ::edge/calls
   ::edge/read-attributes
   ::edge/written-attributes
   ::edge/all-at-basis?
   ::edge/uncertainties
   ::edge/terminal-symbol
   ::edge/effect
   ::edge/required-bindings
   ::edge/terminal-generation
   ::edge/terminal-refs])

(def ^:private fault-schema
  [{:db/ident ::fault-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::fault-proc
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::fault-message
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident ::fault-drop-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::fault-drop-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn- await-latch!
  [^CountDownLatch latch event]
  (when-not (.await latch event-backstop-seconds TimeUnit/SECONDS)
    (throw
     (ex-info
      "The indexer testbed did not observe its required event."
      {::event event}))))

(defn- await-condition!
  [event predicate]
  (let [limit (+ (System/nanoTime)
                 (.toNanos TimeUnit/SECONDS event-backstop-seconds))]
    (loop []
      (cond
        (predicate) true
        (< (System/nanoTime) limit)
        (do (Thread/sleep 5) (recur))
        :else
        (throw
         (ex-info
          "The indexer testbed condition did not become true."
          {::event event}))))))

(defn- fixture-sources
  []
  (into
   (sorted-map)
   (map (fn [[namespace file]]
          [namespace (slurp file)]))
   good-fixtures))

(defn- read-forms
  [source]
  (with-open [reader (PushbackReader. (StringReader. source))]
    (loop [forms []]
      (let [form
            (read
             {:eof eof
              :read-cond :allow
              :features #{:clj}}
             reader)]
        (if (identical? eof form)
          forms
          (recur (conj forms form)))))))

(defn- namespace-form?
  [form]
  (and (seq? form) (= 'ns (first form))))

(defn- function-form?
  [form]
  (and (seq? form)
       (contains? #{"defn" "defn-"} (some-> form first name))))

(defn- schema-form?
  [form]
  (and (seq? form)
       (= 'schema/register! (first form))
       (keyword? (second form))))

(defn- function-resolution
  [namespace forms require-edges]
  (let [{::ns-source/keys [aliases nses refers]}
        (ns-source/edges->require-info require-edges)
        current-vars
        (into #{}
              (keep
               (fn [form]
                 (when (function-form? form)
                   (second form))))
              forms)
        referred-symbols
        (into
         {}
         (mapcat
          (fn [[target names]]
            (map
             (fn [referred]
               [referred (symbol (str target) (name referred))])
             names)))
         refers)
        fixture-effects
        (into
         {'clojure.core/inc :pure
          'clojure.core/+ :pure
          'clojure.core/boolean :pure}
         (map
          (fn [function-name]
            [(symbol (str namespace) (name function-name)) :pure]))
         current-vars)]
    {::edge/namespace namespace
     ::edge/aliases aliases
     ::edge/refers referred-symbols
     ::edge/current-vars current-vars
     ::edge/core-vars #{'inc '+ 'boolean}
     ::edge/known-namespaces
     (into #{namespace 'clojure.core 'seon.schema} nses)
     ::edge/macro-symbols #{}
     ::edge/effects fixture-effects}))

(defn- function-row
  [namespace resolution form]
  (let [function-name (second form)
        function-symbol (str namespace "/" function-name)
        arguments (some #(when (vector? %) %) (drop 2 form))
        doc (or (some #(when (string? %) %) (drop 2 form)) "")
        bundle
        ;; analyze-function currently hashes a pr-str form, so its generation
        ;; inherits this dynamic print binding. Fixing the protected pure core
        ;; belongs to its production owner; the testbed makes the call
        ;; deterministic at its adapter boundary.
        (binding [*print-namespace-maps* false]
          (edge/analyze-function
           {::edge/function-symbol function-symbol
            ::edge/form form
            ::edge/resolution resolution}))]
    (assoc
     (merge
      {:seon.fn/sym function-symbol
       :seon.fn/ns [:seon.ns/name namespace]
       :seon.fn/source (pr-str form)
       :seon.fn/spec ""
       :seon.fn/doc doc
       :seon.fn/arglists (pr-str (list arguments))
       :seon.fn/private? (= 'defn- (first form))}
      (select-keys bundle edge/stored-function-attrs))
     ::bundle bundle)))

(defn- compile-source-rows
  [expected-namespace source]
  (let [forms (read-forms source)
        namespace-form (first (filter namespace-form? forms))
        namespace (second namespace-form)]
    (when-not (= expected-namespace namespace)
      (throw
       (ex-info
        "The fixture namespace did not match its enumerated identity."
        {::expected-namespace expected-namespace
         ::actual-namespace namespace})))
    (let [require-edges (ns-source/require-edges-from-source source)
          resolution
          (function-resolution namespace forms require-edges)]
      (into
       [{:seon.ns/name namespace
         :seon.ns/source source
         :seon.ns/require-edges require-edges}]
       (concat
        (map #(function-row namespace resolution %)
             (filter function-form? forms))
        (map
         (fn [form]
           {:seon.schema/key (second form)
            :seon.schema/ns [:seon.ns/name namespace]
            :seon.schema/form (pr-str (nth form 2))
            ;; compile-tx-data uses this sentinel when the optional generator
            ;; is intentionally absent. Supplying it makes the desired row
            ;; explicit and avoids manufacturing a retract of nil.
            :seon.db.id/generator :seon.db.id.generator/absent})
         (filter schema-form? forms)))))))

(defn- all-desired-rows
  [sources]
  (into []
        (mapcat (fn [[namespace source]]
                  (compile-source-rows namespace source)))
        sources))

(defn- compile-program-tx-data
  [database rows]
  (into
   (program/compile-tx-data
    database
    (mapv #(dissoc % ::bundle) rows))
   (mapcat edge/transition-tx)
   (keep ::bundle rows)))

(defn- with-program-database
  [body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write
         :keep-history? true}
        _ (d/create-database configuration)
        connection (d/connect configuration)
        registered-attributes
        (filter schema/registered? program-attributes)
        ;; :seon.ns/source is registered by the full runtime context, not by
        ;; either pure indexer namespace. The isolated test installs its
        ;; established Datahike shape directly.
        testbed-schema
        (conj
         (db/malli->datahike-schema registered-attributes)
         {:db/ident :seon.ns/source
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one})]
    (try
      (d/transact connection testbed-schema)
      (d/transact connection fault-schema)
      (d/transact
       connection
       [{::fault-drop-id "indexer"
         ::fault-drop-count 0}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- canonical-edge
  [edge]
  (dissoc edge :db/id))

(defn- program-facts
  [database]
  {:namespaces
   (into
    (sorted-map)
    (map
     (fn [[namespace source pulled]]
       [namespace
        {:source source
         :require-edges
         (into #{}
               (map canonical-edge)
               (:seon.ns/require-edges pulled))}]))
    (d/q
     '[:find ?namespace ?source
              (pull ?entity [{:seon.ns/require-edges [*]}])
       :where
       [?entity :seon.ns/name ?namespace]
       [?entity :seon.ns/source ?source]]
     database))
   :functions
   (into
    (sorted-map)
    (map
     (fn [[sym namespace source generation pulled]]
       [sym
        {:namespace namespace
         :source source
         :generation generation
         :calls (set (:seon.program.edge/calls pulled))
         :reads (set (:seon.program.edge/read-attributes pulled))
         :writes (set (:seon.program.edge/written-attributes pulled))
         :uncertainties
         (set (:seon.program.edge/uncertainties pulled))}]))
    (d/q
     '[:find ?sym ?namespace ?source ?generation
              (pull ?function
                    [:seon.program.edge/calls
                     :seon.program.edge/read-attributes
                     :seon.program.edge/written-attributes
                     :seon.program.edge/uncertainties])
       :where
       [?function :seon.fn/sym ?sym]
       [?function :seon.fn/ns ?namespace-entity]
       [?namespace-entity :seon.ns/name ?namespace]
       [?function :seon.fn/source ?source]
       [?function :seon.program.edge/generation ?generation]]
     database))
   :schemas
   (into
    (sorted-map)
    (map
     (fn [[key namespace form]]
       [key {:namespace namespace :form form}]))
    (d/q
     '[:find ?key ?namespace ?form
       :where
       [?schema :seon.schema/key ?key]
       [?schema :seon.schema/ns ?namespace-entity]
       [?namespace-entity :seon.ns/name ?namespace]
       [?schema :seon.schema/form ?form]]
     database))})

(defn- direct-program-facts
  [sources]
  (with-program-database
    (fn [connection]
      (let [tx-data
            (compile-program-tx-data
             @connection (all-desired-rows sources))]
        (d/transact connection tx-data)
        (program-facts @connection)))))

(defn- compile-namespace-fn
  ([connection]
   (compile-namespace-fn connection (fn [_namespace])))
  ([connection compiled!]
   (let [rows-by-namespace (atom (sorted-map))]
     (fn [{::sut/keys [changed-namespace changed-source]}]
       (let [rows (compile-source-rows changed-namespace changed-source)
             desired
             (-> (swap! rows-by-namespace assoc changed-namespace rows)
                 vals
                 (->> (into [] cat)))
             changed-bundles
             (into [] (keep ::bundle) rows)
             tx-data
             (into
              (program/compile-tx-data
               @connection
               (mapv #(dissoc % ::bundle) desired))
              (mapcat edge/transition-tx)
              changed-bundles)]
         (compiled! changed-namespace)
         {::namespace changed-namespace
          ::tx-data tx-data})))))

(defn- committed-namespace-count
  [database]
  (d/q
   '[:find (count ?namespace) .
     :where [?namespace :seon.ns/name]]
   database))

(defn- create-indexer-flow
  [{::keys [connection commit-page! stopped! index-buffer commit-buffer
            compiled!]}]
  (let [compute-executor (sut/bounded-platform-executor 1)
        graph
        (flow/create-flow
         {:procs
          {:source-enumerator
           {:proc
            (sut/source-enumerator-proc
             {::sut/read-sources fixture-sources})}
           :indexer
           {:proc
            (sut/indexer-proc
             {::sut/compile-namespace-fn
              (compile-namespace-fn connection
                                    (or compiled! (fn [_namespace])))
              ::sut/compute-timeout-ms 10000})
            :chan-opts
            {::sut/index-request {:buf-or-n index-buffer}}}
           :page-committer
           {:proc
            (sut/database-proc
             {::sut/read-facts
              #(hash-map ::committed-namespaces
                         (committed-namespace-count @connection))
              ::sut/step-fn
              (fn [_facts page]
                (commit-page! page))
              ::sut/stopped! stopped!})
            :chan-opts
            {::sut/wake {:buf-or-n commit-buffer}}}}
          :conns
          [[[:source-enumerator ::sut/index-request]
            [:indexer ::sut/index-request]]
           [[:indexer ::sut/tx-page]
            [:page-committer ::sut/wake]]]
          :compute-exec compute-executor})]
    {::graph graph
     ::compute-executor compute-executor}))

(defn- stop-executor!
  [^ExecutorService executor]
  (.shutdownNow executor)
  (when-not (.awaitTermination
             executor event-backstop-seconds TimeUnit/SECONDS)
    (throw
     (ex-info
      "The indexer compute executor did not terminate."
      {::event ::executor-termination}))))

(defn- channel-data
  [graph pid input-id]
  (get-in (datafy/datafy graph) [:chans :ins [pid input-id]]))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- connect-monitor!
  [^HttpClient client port]
  (let [messages (atom [])
        partial-message (atom "")
        opened (CountDownLatch. 1)
        listener
        (reify WebSocket$Listener
          (onOpen [_ socket]
            (.countDown opened)
            (.request socket 1))
          (onText [_ socket text last?]
            (swap! partial-message str text)
            (when last?
              (swap! messages conj @partial-message)
              (reset! partial-message ""))
            (.request socket 1)
            nil))
        socket
        (-> client
            .newWebSocketBuilder
            (.buildAsync
             (URI/create (str "ws://127.0.0.1:" port "/flow-socket"))
             listener)
            .join)]
    (await-latch! opened ::monitor-open)
    {::socket socket ::messages messages}))

(defn- commit-fault!
  [connection fault]
  (d/transact
   connection
   [{::fault-id (random-uuid)
     ::fault-proc (::flow/pid fault)
     ::fault-message (ex-message (::flow/ex fault))}]))

(defn- fault-procs
  [database]
  (set
   (d/q
    '[:find [?proc ...]
      :where
      [?fault :seon.flow.indexer-test/fault-id]
      [?fault :seon.flow.indexer-test/fault-proc ?proc]]
    database)))

(defn- changed-program-namespaces
  [database since-basis]
  (let [changed-entities
        (into
         #{}
         (map #(nth % 0))
         (d/datoms (d/since database since-basis) :eavt))]
    (into
     #{}
     (keep
      (fn [entity-id]
        (let [entity (d/pull
                      database
                      [:seon.ns/name
                       {:seon.fn/ns [:seon.ns/name]}
                       {:seon.schema/ns [:seon.ns/name]}]
                      entity-id)]
          (or (:seon.ns/name entity)
              (get-in entity [:seon.fn/ns :seon.ns/name])
              (get-in entity [:seon.schema/ns :seon.ns/name])))))
     changed-entities)))

(deftest drain-increment-fault-and-monitor-share-one-flow
  (with-program-database
    (fn [connection]
      (let [sources (fixture-sources)
            expected (direct-program-facts sources)
            committed-pages (atom 0)
            stopped (CountDownLatch. 1)
            commit-page!
            (fn [{::keys [tx-data]}]
              (d/transact connection tx-data)
              (swap! committed-pages inc))
            {::keys [graph compute-executor]}
            (create-indexer-flow
             {::connection connection
              ::commit-page! commit-page!
              ::stopped! (fn [_] (.countDown stopped))
              ::index-buffer 2
              ::commit-buffer 2})
            started (flow/start graph)
            fanout
            (sut/start-error-fanout!
             {::sut/graph graph
              ::sut/started started
              ::sut/fault-buffer-capacity 8
              ::sut/monitor-buffer-capacity 32
              ::sut/read-core-error-mode (constantly :record)
              ::sut/commit-fault! #(commit-fault! connection %)
              ::sut/commit-drop!
              (fn [_]
                (d/transact
                 connection
                 [{::fault-drop-id "indexer"
                   ::fault-drop-count 1}]))
              ::sut/panic! (fn [_])})
            port (free-port)
            monitor-state
            (flow-monitor/start-server
             {:flow (::sut/graph fanout)
              :port port})
            monitor
            (connect-monitor! (HttpClient/newHttpClient) port)]
        (try
          (flow/resume graph)
          @(flow/inject
            graph [:source-enumerator ::sut/source-event]
            [{::drain true}])
          (await-condition!
           ::initial-drain
           #(= 3 @committed-pages))

          (testing "one-shot drain equals one direct complete reconciliation"
            (is (= expected (program-facts @connection))))

          (testing "Flow Monitor names every proc and observes progress"
            (await-condition!
             ::monitor-topology-and-progress
             (fn []
               (let [text (str/join "\n" @(::messages monitor))]
                 (and
                  (every?
                   #(str/includes? text %)
                   ["~:source-enumerator"
                    "~:indexer"
                    "~:page-committer"])
                  (str/includes? text "namespace-indexed")))))
            (is (= 3
                   (::flow/count
                    (flow/ping-proc graph :page-committer)))))

          (testing "one changed namespace produces only that namespace delta"
            (let [before (:max-tx @connection)
                  changed-alpha
                  (str/replace
                   (get sources 'seon.flow.fixtures.alpha)
                   "(inc value)"
                   "(+ value 2)")]
              @(flow/inject
                graph [:source-enumerator ::sut/source-event]
                [{::sut/changed-namespace 'seon.flow.fixtures.alpha
                  ::sut/changed-source changed-alpha}])
              (await-condition!
               ::incremental-commit
               #(= 4 @committed-pages))
              (is (= #{'seon.flow.fixtures.alpha}
                     (changed-program-namespaces @connection before)))
              (is (= 1
                     (d/q
                      '[:find (count ?function) .
                        :where
                        [?function :seon.fn/sym
                         "seon.flow.fixtures.alpha/lookup-value"]]
                      @connection)))
              (is (= #{"clojure.core/+"}
                     (set
                      (d/q
                       '[:find [?call ...]
                         :where
                         [?function :seon.fn/sym
                          "seon.flow.fixtures.alpha/lookup-value"]
                         [?function :seon.program.edge/calls ?call]]
                       @connection)))
                  "the exact edge transition retracts the superseded call")))

          (testing "malformed source is a core fault and later work continues"
            @(flow/inject
              graph [:source-enumerator ::sut/source-event]
              [{::sut/changed-namespace
                'seon.flow.fixtures.malformed
                ::sut/changed-source (slurp malformed-fixture)}])
            (await-condition!
             ::malformed-fault
             #(= #{:indexer} (fault-procs @connection)))
            @(flow/inject
              graph [:source-enumerator ::sut/source-event]
              [{::sut/changed-namespace 'seon.flow.fixtures.gamma
                ::sut/changed-source
                (get sources 'seon.flow.fixtures.gamma)}])
            (await-condition!
             ::post-fault-commit
             #(= 5 @committed-pages))
            (is (= 5
                   (::flow/count
                    (flow/ping-proc graph :indexer)))))
          (finally
            (.join
             (.sendClose
              ^WebSocket (::socket monitor)
              WebSocket/NORMAL_CLOSURE
              "test complete"))
            (flow-monitor/stop-server monitor-state)
            (sut/stop-error-fanout! fanout)
            (flow/stop graph)
            (await-latch! stopped ::page-committer-stopped)
            (stop-executor! compute-executor)))))))

(deftest slow-page-committer-backpressures-without-loss
  (with-program-database
    (fn [connection]
      (let [commit-started (CountDownLatch. 1)
            third-page-compiled (CountDownLatch. 3)
            release-commit (CountDownLatch. 1)
            stopped (CountDownLatch. 1)
            committed-pages (atom 0)
            commit-page!
            (fn [{::keys [tx-data]}]
              (.countDown commit-started)
              (await-latch! release-commit ::release-slow-commit)
              (d/transact connection tx-data)
              (swap! committed-pages inc))
            {::keys [graph compute-executor]}
            (create-indexer-flow
             {::connection connection
              ::commit-page! commit-page!
              ::stopped! (fn [_] (.countDown stopped))
              ::compiled!
              (fn [_namespace] (.countDown third-page-compiled))
              ::index-buffer 3
              ::commit-buffer 1})]
        (try
          (flow/start graph)
          (flow/resume graph)
          @(flow/inject
            graph [:source-enumerator ::sut/source-event]
            [{::drain true}])
          (await-latch! commit-started ::slow-commit-started)
          (await-latch! third-page-compiled ::third-page-compiled)
          (await-condition!
           ::indexer-parked
           #(nil? (flow/ping-proc
                   graph :indexer :timeout-ms 20)))
          (is (nil? (flow/ping-proc
                     graph :indexer :timeout-ms 50))
              "the indexer is parked delivering to the full fixed buffer")
          (.countDown release-commit)
          (await-condition!
           ::slow-drain-complete
           #(= 3 @committed-pages))
          (is (= 3 (committed-namespace-count @connection)))
          (finally
            (.countDown release-commit)
            (flow/stop graph)
            (await-latch! stopped ::slow-page-committer-stopped)
            (stop-executor! compute-executor)))))))

(deftest graph-recreation-resumes-an-interrupted-drain-idempotently
  (with-program-database
    (fn [connection]
      (let [sources (fixture-sources)
            expected (direct-program-facts sources)
            first-started (CountDownLatch. 1)
            release-first (CountDownLatch. 1)
            first-stopped (CountDownLatch. 1)
            first-pages (atom 0)
            first-testbed
            (create-indexer-flow
             {::connection connection
              ::commit-page!
              (fn [{::keys [tx-data]}]
                (.countDown first-started)
                (await-latch! release-first ::release-first-graph)
                (d/transact connection tx-data)
                (swap! first-pages inc))
              ::stopped! (fn [_] (.countDown first-stopped))
              ::index-buffer 3
              ::commit-buffer 1})
            first-graph (::graph first-testbed)]
        (flow/start first-graph)
        (flow/resume first-graph)
        @(flow/inject
          first-graph [:source-enumerator ::sut/source-event]
          [{::drain true}])
        (await-latch! first-started ::first-graph-mid-drain)
        (flow/stop first-graph)
        (.countDown release-first)
        (await-latch! first-stopped ::first-graph-stopped)
        (stop-executor! (::compute-executor first-testbed))

        (let [replacement-stopped (CountDownLatch. 1)
              replacement-pages (atom 0)
              replacement
              (create-indexer-flow
               {::connection connection
                ::commit-page!
                (fn [{::keys [tx-data]}]
                  (d/transact connection tx-data)
                  (swap! replacement-pages inc))
                ::stopped!
                (fn [_] (.countDown replacement-stopped))
                ::index-buffer 3
                ::commit-buffer 2})
              replacement-graph (::graph replacement)]
          (try
            (flow/start replacement-graph)
            (flow/resume replacement-graph)
            @(flow/inject
              replacement-graph [:source-enumerator ::sut/source-event]
              [{::drain true}])
            (await-condition!
             ::replacement-drain
             #(= 3 @replacement-pages))
            (is (= expected (program-facts @connection)))
            (is (= 3
                   (d/q
                    '[:find (count ?function) .
                      :where [?function :seon.fn/sym]]
                    @connection)))
            (is (= 3
                   (d/q
                    '[:find (count ?schema) .
                      :where [?schema :seon.schema/key]]
                    @connection)))
            (finally
              (flow/stop replacement-graph)
              (await-latch!
               replacement-stopped ::replacement-page-committer-stopped)
              (stop-executor! (::compute-executor replacement)))))))))
