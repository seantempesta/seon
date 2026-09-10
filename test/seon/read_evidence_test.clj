(ns seon.read-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as eval]
            [seon.test-support :as test-support]))

(defn- inbox-evidence-after-message
  [recipient]
  (test-support/with-database
   (fn [connection]
     (is (not (:seon.error/kind
               (db/transact!
                connection
                [{:seon.cluster/name "read-evidence"}
                 {:seon.agent/id "juniper"}
                 {:seon.agent/id "root"}
                 {:seon.message/id "opening" :seon.message/to [:seon.agent/id "juniper"] :seon.message/content "Opening message" :seon.message/inbox [:seon.agent/id "juniper"]}]))))
     (let [ctx (test-support/fork-cluster-ctx connection)
           captured (atom [])
           evaluation
           (binding [db/*read-evidence-sink* captured]
             (eval/evaluate
              {:seon.sci.eval/ctx ctx
               :seon.db/connection connection
               :seon.db/db @connection
               :seon.agent/id "juniper"
               :seon.cluster.eval/source "(my.message/inbox (seon.db/db) \"juniper\")"
               :seon.cluster.eval/ns [:seon.ns/name 'user]
               :seon.sci.admit/caps (config/result-caps (config/defaults))
               :seon.sci.eval/time-limit-ms 5000
               :seon.config/on-core-error :panic}))
           evidence (db/read-evidence @captured)
           basis (db/basis-t @connection)
           patterns (mapcat :seon.db/read-index-patterns
                            (mapcat #(get-in % [:datahike.read/dependency-plan
                                               :datahike.query.dependency/sources])
                                    evidence))
           juniper (db/q '[:find ?e . :where [?e :seon.agent/id "juniper"]]
                         @connection)]
       (is (nil? (:seon.error/kind evaluation)) (pr-str evaluation))
       (is (= "Opening message"
              (get-in evaluation [:seon.sci.admit/value 0 :my.message/content]))
           (pr-str evaluation))
       (is (some #(= {:seon.db/pattern-attribute :seon.message/to
                      :seon.db/pattern-value juniper} %)
                 patterns)
           (pr-str evidence))
       (is (true? (db/read-evidence-current? @connection evidence)))
       (is (not (:seon.error/kind
                 (db/transact!
                  connection
                  [{:seon.message/id "next" :seon.message/to [:seon.agent/id recipient] :seon.message/content "Next message" :seon.message/inbox [:seon.agent/id recipient]}]))))
       ;; Remove replay inputs as data: these assertions must be decided by
       ;; retained index evidence, without re-executing the inbox.
       (let [changes (db/read-evidence-changes @connection evidence basis)]
         (if (= recipient "root")
           (is (empty? changes) (pr-str changes))
           (is (some #(and (= :seon.message/to (:a %))
                           (= juniper (:v %))) changes)
               (pr-str changes))))
       (db/read-evidence-current?
        @connection
        (mapv #(dissoc % :seon.db/read-request :seon.db/read-result-digest
                      :seon.db/read-result)
              evidence))))))

(deftest another-recipients-message-keeps-junipers-inbox-current
  (is (true? (inbox-evidence-after-message "root"))))

(deftest junipers-message-invalidates-junipers-inbox
  (is (false? (inbox-evidence-after-message "juniper"))))
