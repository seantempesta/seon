(ns seon.program-plan-writer-test
  "Planning-projection acquisition against the real memory writer."
  (:require [clojure.test :refer [deftest is]]
            [seon.capability :as capability]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.writer :as writer]
            [seon.db.writer-test-support :as writer-test]
            [seon.host.context :as context]
            [seon.host-registry-writer-test :as registry-test]
            [seon.program.edge :as edge]
            [seon.program.plan :as plan])
  (:import [java.io File]))

(defn- private-value [symbol]
  (var-get (ns-resolve 'seon.host-registry-writer-test symbol)))

(defn- socket-path []
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "program-plan-" (random-uuid) ".sock")))))

(deftest acquisition-reconstructs-the-committed-edge-bundle-at-its-fence
  (let [database-name (str "program-plan-" (random-uuid))
        request-path (socket-path)
        server
        (writer-test/start!
         {::writer/dependencies ((private-value 'dependencies))
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path})
        session
        (context/writer-session
         {::context/writer-socket-path request-path
          ::context/database-name database-name
          ::context/backend :memory})
        seed! (private-value 'seed-schema-rows!)
        register! (private-value 'register-runtime-schemas!)
        schema-rows (private-value 'corpus-schema-rows)
        bundle
        {::edge/function-symbol "fixture.writer/root"
         ::edge/generation "root-generation"
         ::edge/calls #{"seon.db/query"}
         ::edge/read-attributes #{:seon.fn/sym}
         ::edge/written-attributes #{}
         ::edge/all-at-basis? false
         ::edge/uncertainties #{}
         ::edge/terminals
         [{::edge/terminal-symbol "seon.db/query"
           ::edge/effect :read
           ::edge/required-bindings #{"seon.db/query"}
           ::edge/terminal-generation "query-generation"}]}]
    (try
      (register! schema-rows)
      (let [genesis
            (seed!
             session
             (into schema-rows
                   [{:seon.user/id "user"}
                    {:seon.db.process/id :seon.db.process/repl}]))]
        (is (true? (::protocol/success? genesis)) (pr-str genesis)))
      (let [installed
            (seed!
             session
             [{:seon.user/id "bootstrap"
               :seon.db/user [:seon.user/id "user"]
               :seon.db/process
               [:seon.db.process/id :seon.db.process/repl]}])]
        (is (true? (::protocol/success? installed)) (pr-str installed)))
      ;; Fresh writers install declared attributes lazily on first asserted
      ;; use. Seed one disposable complete row before exercising the exact-set
      ;; transition, whose leading retractions intentionally assume that
      ;; committed schema installation has already happened.
      (let [installed-edges
            (seed!
             session
             [{:db/id "terminal-install"
               ::edge/terminal-symbol "fixture.writer/install-terminal"
               ::edge/effect :pure
               ::edge/required-bindings #{"fixture.writer/install-terminal"}
               ::edge/terminal-generation "install-terminal-generation"}
              {:seon.fn/sym "fixture.writer/install"
               ::edge/generation "install-generation"
               ::edge/calls #{"fixture.writer/install-terminal"}
               ::edge/read-attributes #{:seon.fn/sym}
               ::edge/written-attributes #{:seon.fn/sym}
               ::edge/all-at-basis? false
               ::edge/uncertainties #{:dynamic-call}
               ::edge/terminal-refs #{"terminal-install"}}])]
        (is (true? (::protocol/success? installed-edges))
            (pr-str installed-edges)))
      (let [persisted (seed! session (edge/transition-tx bundle))]
        (is (true? (::protocol/success? persisted)) (pr-str persisted)))
      (let [database (context/resolve-head! session)
            leaf (db.host/leaf session (constantly {}))
            artifacts
            (capability/installed-artifact-inventory
             (capability/installed-leaf-inventory
              :jvm
              [{:seon.capability/binding "fixture.writer/install-terminal"
                :seon.capability/effect :pure
                :seon.capability/remote? false}]))
            acquired (binding [db/*leaf* leaf]
                       (plan/acquire-planning-projection database artifacts))
            reconstructed
            (get-in acquired
                    [:seon.execution/edge-bundles "fixture.writer/root"])]
        (is (= (:t database) (:seon.execution/basis-t acquired)))
        (is (= (:datahike/commit-id database)
               (:seon.execution/commit-id acquired)))
        (is (= bundle reconstructed))
        (is (= (edge/program-graph-digest
                (vals (:seon.execution/edge-bundles acquired)))
               (:seon.execution/graph-digest acquired)))
        (is (= artifacts
               (:seon.execution/artifact-inventories acquired))))
      (finally
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))))))
