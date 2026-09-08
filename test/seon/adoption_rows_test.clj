(ns seon.adoption-rows-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(deftest development-acquisition-contains-one-agent-row-fault
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "adoption-rows")
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "adoption-rows"
                      :seon.config/manifest {}})
      (let [source-database @connection
            namespace-name 'acquire.rows
            agent-id "acquire-rows-author"
            good-source
            (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                 "good [x] (inc x))")
            authored-ctx (eval/build-base-ctx)
            _ (sci/add-namespace! authored-ctx namespace-name {})
            _ (sci/binding [sci/ns (sci/create-ns namespace-name)]
                (sci/eval-string* authored-ctx good-source))
            good-root-edn
            (binding [*print-meta* true]
              (pr-str
               (first
                (sci/var-root-data authored-ctx
                                   ['acquire.rows/good]))))]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id
           :seon.cluster.agent/namespace
           {:seon.ns/name namespace-name
            :seon.ns/source "(ns acquire.rows)"}}
          {:seon.fn/sym "acquire.rows/bad"
           :seon.schema.admission/source :agent
           :seon.fn/ns [:seon.ns/name namespace-name]
           :seon.fn/source
           (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                "bad [x] x)")
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"}
          {:seon.fn/sym "acquire.rows/good"
           :seon.schema.admission/source :agent
           :seon.fn/ns [:seon.ns/name namespace-name]
           :seon.fn/source good-source
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"}
          {:seon.def/key
           (pr-str [agent-id "acquire.rows/good#root"])
           :seon.def/id "acquire.rows/good#root"
           :seon.def/agent [:seon.cluster.agent/id agent-id]
           :seon.def/ns [:seon.ns/name namespace-name]
           :seon.def/name 'good#root
           :seon.def/value-edn good-root-edn
           :seon.def/ordinal 0
           :seon.schema.admission/source :agent}])
        (let [ctx
              (assoc (eval/build-base-ctx)
                     :seon.sci.eval/custody
                     {:seon.db/connection connection})
              acquired
              (#'cluster/acquire-development!
               connection "adoption-rows" ctx
               (schema/projection-from-database @connection))
              refusal
              (first
               (db/q '[:find [(pull ?error [*]) ...]
                       :where
                       [?error :seon.error/kind
                        :seon.sci.eval/acquisition-refused]]
                     @connection))]
          (is (nil? (db/pull source-database [:seon.ns/name]
                            [:seon.ns/name namespace-name])))
          (is (= 1 (count (db/q '[:find [?e ...] :where
                                  [?e :seon.error/kind :seon.sci.eval/acquisition-refused]]
                                @connection))))
          (is (= 42 (sci/eval-string* ctx "(acquire.rows/good 41)"))
              "a later valid row installs and works")
          (is (= 1 (count (:seon.sci.eval/acquisition-refusals acquired)))
              (pr-str (mapv #(select-keys % [:seon.error/message :seon.error/kind])
                            (:seon.sci.eval/acquisition-refusals acquired))))
          (is (true?
               (:seon.sci.eval/acquisition-refusals-recorded? acquired)))
          (is (some? refusal) "the contained agent mistake is a durable fact")
          (is (str/includes? (:seon.error/message refusal)
                             "[:seon.fn/sym \"acquire.rows/bad\"]"))
          (is (str/includes? (:seon.error/message refusal)
                             "no durable root descriptor")
              "the fact retains the row's typed cause as queryable evidence"))))))
