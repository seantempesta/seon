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
                [{:seon.agent/id "juniper"}
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
               :seon.cluster.eval/source "(seon.db/q '[:find [(pull ?m [:seon.message/content]) ...] :where [?m :seon.message/to [:seon.agent/id \"juniper\"]] (not [?m :seon.message/read-tx])])"
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
              (get-in evaluation [:seon.sci.admit/value 0 :seon.message/content]))
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

(deftest nested-patterns-and-find-pulls-retain-scoped-index-evidence
  (test-support/with-database
   (fn [connection]
     (is (not (:seon.error/kind
               (db/transact! connection
                             [{:seon.agent/id "juniper"}
                              {:seon.agent/id "root"}
                              {:seon.message/id "mine" :seon.message/content "mine"
                               :seon.message/to [:seon.agent/id "juniper"]}
                              {:seon.message/id "theirs" :seon.message/content "theirs"
                               :seon.message/to [:seon.agent/id "root"]}]))))
     (doseq [where '[[[?m :seon.message/to ?recipient]
                     (not [?m :seon.message/read-tx])]
                    [[?m :seon.message/to ?recipient]
                     (or [?m :seon.message/content "mine"]
                         (and [?m :seon.message/id "mine"]
                              (not [?m :seon.message/read-tx])))]
                    [[?m :seon.message/to ?recipient]
                     (or-join [?m] [?m :seon.message/content "mine"]
                              (and [?m :seon.message/id "mine"]
                                   (not-join [?m] [?m :seon.message/read-tx])))]]]
       (let [captured (atom [])
             result (binding [db/*read-evidence-sink* captured]
                      (db/q {:find '[(pull ?m [:seon.message/id :seon.message/content])]
                             :in '[$ ?recipient] :where where}
                            @connection [:seon.agent/id "juniper"]))
             evidence (mapv #(dissoc % :seon.db/read-request
                                    :seon.db/read-result :seon.db/read-result-digest)
                            (db/read-evidence @captured))
             patterns (mapcat :seon.db/read-index-patterns
                              (mapcat #(get-in % [:datahike.read/dependency-plan
                                                 :datahike.query.dependency/sources])
                                      evidence))
             mine (:db/id (db/pull @connection [:db/id] [:seon.message/id "mine"]))]
         (is (= [[{:seon.message/id "mine" :seon.message/content "mine"}]]
                (vec result)))
         (is (some #{ {:seon.db/pattern-entity mine
                       :seon.db/pattern-attribute :seon.message/read-tx}} patterns))
         (is (some #{ {:seon.db/pattern-entity mine
                       :seon.db/pattern-attribute :seon.message/content}} patterns))
         (is (true? (db/read-evidence-current? @connection evidence)))
         (is (not (:seon.error/kind
                   (db/transact! connection
                                 [[:db/add [:seon.message/id "theirs"]
                                   :seon.message/content (pr-str where)]
                                  [:db/add [:seon.message/id "theirs"]
                                   :seon.message/read-tx "datomic.tx"]]))))
         (is (true? (db/read-evidence-current? @connection evidence)))
         (is (not (:seon.error/kind
                   (db/transact! connection
                                 [[:db/add [:seon.message/id "mine"]
                                   :seon.message/content "changed"]]))))
         (is (false? (db/read-evidence-current? @connection evidence)))
         (db/transact! connection [[:db/add [:seon.message/id "mine"]
                                   :seon.message/content "mine"]])))
     (let [captured (atom [])
           query '[:find [?m ...] :in $ ?recipient
                   :where [?m :seon.message/to ?recipient]
                   (not [?m :seon.message/read-tx])]]
       (db/transact! connection [[:db/add [:seon.message/id "mine"]
                                 :seon.message/read-tx "datomic.tx"]])
       (is (empty? (binding [db/*read-evidence-sink* captured]
                     (db/q query @connection [:seon.agent/id "juniper"]))))
       (let [evidence (mapv #(dissoc % :seon.db/read-request
                                   :seon.db/read-result :seon.db/read-result-digest)
                           (db/read-evidence @captured))]
         (is (seq evidence))
         (db/transact! connection [[:db/retract [:seon.message/id "mine"]
                                   :seon.message/read-tx]])
         (is (false? (db/read-evidence-current? @connection evidence)))
         (is (= 1 (count (db/q query @connection [:seon.agent/id "juniper"])))))))))
