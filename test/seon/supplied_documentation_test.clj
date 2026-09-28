(ns seon.supplied-documentation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.schema :as schema]
            [my.message :as message]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(defn- evaluate
  ([ctx connection source] (evaluate ctx connection source true))
  ([ctx connection source turn?]
   (evaluation/evaluate
    (cond-> {:seon.sci.eval/ctx ctx :seon.db/db @connection
    :seon.db/connection connection :seon.agent/id "supplied-docs"
    :seon.cluster.eval/source source
    :seon.sci.admit/caps (config/result-caps (support/effective-config))
    :seon.sci.eval/time-limit-ms 10000}
      turn? (assoc :seon.turn/id "supplied-docs-turn"
                   :seon.cluster.eval/ordinal 0
                   :seon.boot/cluster-name "supplied-docs")))))

(defn- problem
  "A refusal's first problem: where it is, what was given and the fix."
  {:malli/schema [:=> [:cat :map] :map]}
  [refusal]
  (select-keys (first (:seon.error/problems refusal))
               [:seon.error/path :seon.error/actual-description :seon.error/fix]))

(deftest docs-separate-runtime-keys-and-preserve-callable-examples
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "supplied-docs")
     ;; An agent's branch is required: these agents work live on the fixture's own branch.
     (let [branch (get-in (db/db connection) [:config :branch])]
       (support/transacted! connection [{:seon.agent/id "supplied-docs" :seon.agent/branch branch}
                                        {:seon.agent/id "root" :seon.agent/branch branch}]))
     (let [ctx (support/fork-cluster-ctx connection "supplied-docs")]
       (doseq [sym ['my.message/send 'my.note/add!]]
         (let [documented (evaluate ctx connection (str "(doc " sym ")"))
               doc (:seon.sci.admit/value documented)
               directory (evaluate ctx connection (str "(dir " (namespace sym) ")"))
               entity (some #(when (= sym (:sym %)) %)
                         (get-in directory [:seon.sci.admit/value :functions]))
               entries (filter vector? (rest (second (:in doc))))]
           (is (nil? (:seon.cluster.eval/error documented)))
           (is (= #{:seon.db/connection :seon.agent/id} (set (:supplied doc))))
           (is (= (:supplied doc) (:supplied entity)))
           (is (= (:in doc) (:in entity)))
           (is (seq entries))
           (is (not-any? #{:seon.db/connection :seon.agent/id} (map first entries)))
           (is (str/includes? (:seon.eval/shown directory) ":supplied"))
           (let [example (evaluate ctx connection (:example doc) false)]
             (is (nil? (:seon.cluster.eval/error example)))
             (is (nil? (:seon.cluster.eval/error example))))))))))

(deftest invalid-runtime-key-tells-the-agent-to-omit-it
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection "supplied-docs")
           result (evaluate ctx connection
                            "(my.message/send {:my.message/to \"root\" :my.message/content \"Hello\" :seon.db/connection nil})")
           value (:seon.sci.admit/value result)]
       (is ((schema/projection-validator (schema/handed-projection)
                                         :seon.instrument/contract-error) value))
       (is (= {:seon.error/path [:seon.db/connection]
               :seon.error/fix ":seon.db/connection is supplied by the runtime; do not pass it. Remove it from the request map."}
              (select-keys (first (:seon.error/problems value)) [:seon.error/path :seon.error/fix]))
           "the refusal's fix tells the agent to omit the supplied key")
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] @connection)))))))

(deftest lookup-ref-refusal-names-the-actual-and-accepted-shapes
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection "supplied-docs")
           result (evaluate ctx connection
                            "(my.message/send {:my.message/to \"root\" :my.message/content \"Hello\" :my.message/about [:seon.message/id \"f500b1f2\"]})")
           value (:seon.sci.admit/value result)]
       (is ((schema/projection-validator (schema/handed-projection)
                                         :seon.instrument/contract-error) value))
       (is (= {:seon.error/path [:my.message/about]
               :seon.error/actual-description "a lookup-ref vector"
               :seon.error/fix "Pass the string id (the second element of the lookup-ref vector) at [:my.message/about]."}
              (problem value))
           "a string-keyed lookup ref names its shape and the id to pass")
       (let [numeric (evaluate ctx connection
                               "(my.message/send {:my.message/to \"root\" :my.message/content \"Hello\" :my.message/about [:seon.message/id 42]})")]
         (is (= {:seon.error/path [:my.message/about]
                 :seon.error/actual-description "a lookup-ref vector"
                 :seon.error/fix "Pass a string id at [:my.message/about]."}
                (problem (:seon.sci.admit/value numeric)))
             "a lookup ref without a string id asks for one, not for its second element"))
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] @connection)))))))

(deftest a-missing-agent-is-refused-by-name-before-the-send
  ;; A test body runs as its member, so the send is prepared from that world:
  ;; the connection is supplied and the absent agent refuses by name at call
  ;; preparation (e686980fa), before the contract ever sees a missing key.
  (support/with-database
   (fn [connection]
     (let [environment (env/environment {:seon.boot/cluster-name "supplied-docs"
                                         :seon.db/connection connection})
           refusal (binding [effect/*request-context* {:seon.env/environment environment}]
                     (try (message/send {:my.message/to "root" :my.message/content "Hello"})
                          (catch Exception cause (ex-data cause))))]
       (is (= {:seon.call-preparation/unavailable-key :seon.agent/id
               :seon.call-preparation/target-symbol 'my.message/send}
              (select-keys refusal [:seon.call-preparation/unavailable-key
                                    :seon.call-preparation/target-symbol]))
           "the refusal names the send and the agent its world lacks")
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] @connection))
           "no message is written")))))
