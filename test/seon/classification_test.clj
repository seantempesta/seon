(ns seon.classification-test
  "Classification follows declared facts when names and membership disagree."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.cluster.instruction :as instruction]
            [seon.db :as db]
            [seon.program :as program]
            [seon.cluster.process :as operator.process]
            [seon.operator]
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
            routes (ai/request-attributes (seon.schema/build-projection extended))]
        (is (seq (config/dial-attributes (seon.schema/build-projection forms))))
        (is (seq (ai/request-attributes (seon.schema/build-projection forms))))
        (is (= :seon.ai/temperature
               (db/q '[:find ?attribute .
                       :where [?schema :seon.schema/key :seon.config.ai/temperature]
                              [?schema :seon.ai/request-attribute ?attribute]]
                     @connection)))
        (is (= (conj (config/dial-attributes (seon.schema/build-projection forms)) :sample/heat)
               (config/dial-attributes (seon.schema/build-projection extended))))
        (is (= (assoc (ai/request-attributes (seon.schema/build-projection forms))
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
            before (set (program/base-context-injected-symbols (seon.schema/build-projection forms)))
            after (set (program/base-context-injected-symbols (seon.schema/build-projection (assoc forms
                               :sample/binding
                               [:symbol {:seon.sci.binding/target 'clojure.core/inc
                                         :seon.sci.binding/reason "Synthetic interpreter integration."}]
                               :seon.sci.binding/impostor :symbol))))]
        (is (seq before))
        (is (= (conj before 'clojure.core/inc) after))
        (is (not (contains? after 'seon.sci.binding/impostor)))))))

(deftest ordinary-callables-are-acquired-without-bootstrap-membership
  (test-support/with-database
    (fn [connection]
      (let [ctx (sci.eval/build-base-ctx (seon.schema/handed-projection))
            acquired (sci.eval/acquire! {:seon.sci.eval/ctx ctx
                                        :seon.db/db @connection})]
        (is (map? acquired) (pr-str acquired))
        (is (not (contains? (set (program/base-context-injected-symbols (seon.schema/handed-projection)))
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
          (is (some? (:db-after report)) (pr-str report)))
        (is (= (conj before 'sample.relevant)
               (set (instruction/toolkit-namespaces @connection))))))))
