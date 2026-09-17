(ns seon.classification-test
  "Classification follows declared facts when names and membership disagree."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.cluster.instruction :as instruction]
            [seon.db :as db]
            [seon.program :as program]
            [seon.operator.state :as operator.state]
            [seon.fresh-operator]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [sci.core :as sci]
            [seon.test-support :as test-support]))

(deftest dial-membership-and-request-routes-accrete-from-declarations
  (test-support/with-database
    (fn [connection]
      (let [forms (:seon.schema.projection/forms
                   (schema/projection-from-database @connection))
            extended (assoc forms
                            :sample/heat
                            [:double {:seon.config/dial true
                                      :seon.ai/request-attribute :seon.ai/temperature}]
                            :seon.config.ai/impostor :double)
            routes (ai/request-attributes extended)]
        (is (seq (config/dial-attributes forms)))
        (is (seq (ai/request-attributes forms)))
        (is (= :seon.ai/temperature
               (db/q '[:find ?attribute .
                       :where [?schema :seon.schema/key :seon.config.ai/temperature]
                              [?schema :seon.ai/request-attribute ?attribute]]
                     @connection)))
        (is (= (conj (config/dial-attributes forms) :sample/heat)
               (config/dial-attributes extended)))
        (is (= (assoc (ai/request-attributes forms)
                       :sample/heat :seon.ai/temperature)
               routes))
        (is (= {:seon.ai/temperature 0.5}
               (#'seon.ai/primary-setting-entries
                extended {:sample/heat 0.5 :seon.config.ai/impostor 1.0})))
        (is (not (contains? routes :seon.config.ai/impostor)))))))

(deftest interpreter-bindings-accrete-only-from-explained-declarations
  (test-support/with-database
    (fn [connection]
      (let [forms (:seon.schema.projection/forms
                   (schema/projection-from-database @connection))
            before (set (program/base-context-injected-symbols forms))
            after (set (program/base-context-injected-symbols
                        (assoc forms
                               :sample/binding
                               [:symbol {:seon.sci.binding/target 'clojure.core/inc
                                         :seon.sci.binding/reason "Synthetic interpreter integration."}]
                               :seon.sci.binding/impostor :symbol)))]
        (is (seq before))
        (is (= (conj before 'clojure.core/inc) after))
        (is (not (contains? after 'seon.sci.binding/impostor)))))))

(deftest ordinary-callables-are-acquired-without-bootstrap-membership
  (test-support/with-database
    (fn [connection]
      (let [ctx (sci.eval/build-base-ctx (seon.schema/handed-projection))
            acquired (sci.eval/acquire! {:seon.sci.eval/ctx ctx
                                        :seon.db/db @connection})]
        (is (not (:seon.error/kind acquired)) (pr-str acquired))
        (is (not (contains? (set (program/base-context-injected-symbols))
                            'my.turn/complete)))
        (is (= {:my.turn/disposition :completed :my.turn/result "done"}
               (sci/eval-string* ctx "(my.turn/complete {:my.turn/result \"done\"})")))
        (is (some? (sci/resolve ctx 'seon.schema/register!)))))))

(deftest namespace-context-follows-relevance-not-spelling
  (test-support/with-database
    (fn [connection]
      (let [before (set (instruction/toolkit-namespaces @connection))]
        (is (seq before) "Canonical namespace declarations must be indexed.")
        (let [report (db/transact! connection
                      [{:seon.ns/name 'sample.relevant
                        :seon.ns/context-relevant? true}
                       {:seon.ns/name 'my.impostor}
                       {:seon.fn/sym "sample.relevant/call"
                        :seon.schema.admission/source :core
                        :seon.fn/ns [:seon.ns/name 'sample.relevant]
                        :seon.fn/spec "[:=> [:cat] :int]"}
                       {:seon.fn/sym "my.impostor/call"
                        :seon.schema.admission/source :core
                        :seon.fn/ns [:seon.ns/name 'my.impostor]
                        :seon.fn/spec "[:=> [:cat] :int]"}])]
          (is (not (:seon.error/kind report)) (pr-str report)))
        (is (= (conj before 'sample.relevant)
               (set (instruction/toolkit-namespaces @connection))))))))

(deftest process-claim-installations-follow-the-exact-root-fact
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            (.toPath (io/file "tmp")) "n7-claims-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        root (operator.state/canonical-path (io/file directory "managed"))
        installation (operator.state/canonical-path (io/file directory "checkout"))
        record {:seon.operator.process-record/generation (random-uuid)
                :seon.operator.process-record/root root
                :seon.operator.process-record/repository-root installation
                :seon.operator.process-record/log (str root "/process.log")
                :seon.boot/pid 1
                :seon.boot/start-instant #inst "2026-09-15T00:00:00Z"}
        observation {:seon.operator.state/root root
                     :seon.operator.process-record/repository-root installation}]
    (try
    (operator.state/write-process-claim! installation record)
    (is (empty? (:seon.fresh-operator/process-records
                 (#'seon.fresh-operator/read-process-records root []))))
    (is (= [record] (:seon.fresh-operator/process-records
                     (#'seon.fresh-operator/read-process-records root [observation]))))
    (is (= #{} (operator.state/process-claim-repositories root [])))
    (is (= #{installation}
           (operator.state/process-claim-repositories root [observation])))
    (is (= #{}
           (operator.state/process-claim-repositories
            root [(assoc observation :seon.operator.state/root (str root "-other"))])))
    (is (= #{}
           (operator.state/process-claim-repositories
            root [{:seon.operator.state/command (str "seon.cluster start! " root)}])))
    (#'seon.fresh-operator/clear-process-record! root record)
    (is (empty? (:records (operator.state/process-claims installation))))
    (finally (test-support/delete-recursively! directory)))))
