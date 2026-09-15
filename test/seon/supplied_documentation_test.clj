(ns seon.supplied-documentation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
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
    :seon.sci.eval/time-limit-ms 10000
    :seon.config/on-core-error :panic}
      turn? (assoc :seon.turn/id "supplied-docs-turn"
                   :seon.cluster.eval/ordinal 0
                   :seon.boot/cluster-name "supplied-docs")))))

(deftest docs-separate-runtime-keys-and-preserve-callable-examples
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "supplied-docs")
     (db/transact! connection [{:seon.agent/id "supplied-docs"} {:seon.agent/id "root"}])
     (let [ctx (support/fork-cluster-ctx connection "supplied-docs")]
       (doseq [sym ['my.message/send 'my.note/add!]]
         (let [documented (evaluate ctx connection (str "(doc " sym ")"))
               doc (:seon.sci.admit/value documented)
               directory (evaluate ctx connection (str "(dir " (namespace sym) ")"))
               row (some #(when (= sym (:sym %)) %)
                         (get-in directory [:seon.sci.admit/value :functions]))
               entries (filter vector? (rest (second (:in doc))))]
           (is (nil? (:seon.cluster.eval/error documented)))
           (is (= #{:seon.db/connection :seon.agent/id} (set (:supplied doc))))
           (is (= (:supplied doc) (:supplied row)))
           (is (= (:in doc) (:in row)))
           (is (seq entries))
           (is (not-any? #{:seon.db/connection :seon.agent/id} (map first entries)))
           (is (str/includes? (:seon.eval/shown directory) ":supplied"))
           (let [example (evaluate ctx connection (:example doc) false)]
             (is (nil? (:seon.cluster.eval/error example)) (pr-str example))
             (is (nil? (get-in example [:seon.sci.admit/value :seon.error/kind]))
                 (pr-str example)))))))))

(deftest invalid-runtime-key-tells-the-agent-to-omit-it
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection "supplied-docs")
           result (evaluate ctx connection
                            "(my.message/send {:my.message/to \"root\" :my.message/content \"Hello\" :seon.db/connection nil})")
           value (:seon.sci.admit/value result)]
       (is (= :seon.instrument/contract-violated (:seon.error/kind value)))
       (is (str/includes? (:seon.error/message value)
                          ":seon.db/connection is supplied by the runtime; do not pass it"))
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] @connection)))))))

(deftest lookup-ref-refusal-names-the-actual-and-accepted-shapes
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection "supplied-docs")
           result (evaluate ctx connection
                            "(my.message/send {:my.message/to \"root\" :my.message/content \"Hello\" :my.message/about [:seon.message/id \"f500b1f2\"]})")
           value (:seon.sci.admit/value result)]
       (is (= :seon.instrument/contract-violated (:seon.error/kind value)))
       (is (str/includes? (:seon.error/message value) "got a lookup-ref vector"))
       (is (str/includes? (:seon.error/message value) "Pass the string id"))
       (is (str/includes? (:seon.error/message value) ":my.message/about"))
       (let [numeric (evaluate ctx connection
                               "(my.message/send {:my.message/to \"root\" :my.message/content \"Hello\" :my.message/about [:seon.message/id 42]})")
             message (get-in numeric [:seon.sci.admit/value :seon.error/message])]
         (is (str/includes? message "got a lookup-ref vector"))
         (is (str/includes? message "Pass a string id"))
         (is (not (str/includes? message "second element"))))
       (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] @connection)))))))

(deftest missing-runtime-key-is-a-runtime-fault
  (support/with-database
   (fn [connection]
     (let [environment (env/environment {:seon.boot/cluster-name "supplied-docs"
                                         :seon.db/connection connection})
           failure (binding [effect/*request-context* {:seon.env/environment environment}]
                     (try (message/send {:my.message/to "root" :my.message/content "Hello"})
                          (catch Exception cause (ex-data cause))))]
       (is (= :seon.instrument/missing-supplied-key (:seon.error/kind failure)))
       (is (str/includes? (:seon.error/message failure) "Runtime fault:"))
       (is (str/includes? (:seon.error/message failure) "do not pass it"))))))
