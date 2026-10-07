(ns seon.read-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(defn- as-seen-by
  "`evidence` as a later check sees it: from this connection, from a fresh
  connection to the same branch (a new JVM: another generation), or from a
  branch recreated under the same name (another generation whose basis
  transaction is not the read's)."
  {:malli/schema [:=> [:cat [:enum :same-connection :fresh-connection :other-lineage]
                       [:vector :seon.db/read-evidence]]
                  [:vector :seon.db/read-evidence]]}
  [seen-by evidence]
  (case seen-by
    :same-connection evidence
    (mapv (fn [entry]
            (cond-> (assoc-in entry [:datahike.read/revision :datahike.cache/generation] (random-uuid))
              (= :other-lineage seen-by)
              (update-in [:datahike.read/dependency-plan :datahike.query.dependency/sources]
                         (fn [sources]
                           (mapv #(cond-> % (:seon.db/read-basis-t %)
                                    (assoc :seon.db/read-basis-instant #inst "2000-01-01"))
                                 sources)))))
          evidence)))

(defn- inbox-evidence-after-message
  {:malli/schema [:=> [:cat :string [:enum :same-connection :fresh-connection :other-lineage]] :boolean]}
  [recipient seen-by]
  (test-support/with-database
   (fn [connection]
     (is (some? (:db-after (db/transact!
                connection
                (into (test-support/agents-tx @connection ["juniper" "root"])
                  [{:seon.message/id "opening" :seon.message/to [:seon.agent/id "juniper"] :seon.message/content "Opening message"}])))))
     (let [ctx (test-support/fork-cluster-ctx connection)
           captured (atom [])
           evaluation
           (binding [db/*read-evidence-sink* captured]
             (test-support/evaluate
              {:seon.sci.eval/ctx ctx
               :seon.db/connection connection
               :seon.db/db @connection
               :seon.agent/id "juniper"
               :seon.cluster.eval/source "(seon.db/q '[:find [(pull ?m [:seon.message/content]) ...] :where [?m :seon.message/to [:seon.agent/id \"juniper\"]] (not [?m :seon.message/from])])"
               :seon.cluster.eval/ns [:seon.ns/name 'user]
               :seon.sci.admit/caps (config/result-caps config/defaults)
               :seon.sci.eval/time-limit-ms 5000}))
           evidence (db/read-evidence @captured)
           patterns (mapcat :seon.db/read-index-patterns
                            (mapcat #(get-in % [:datahike.read/dependency-plan
                                               :datahike.query.dependency/sources])
                                    evidence))
           juniper (db/q '[:find ?e . :where [?e :seon.agent/id "juniper"]]
                         @connection)]
       (is (vector? (:seon.sci.admit/value evaluation)))
       (is (= "Opening message"
              (get-in evaluation [:seon.sci.admit/value 0 :seon.message/content])))
       (is (some #(= {:seon.db/pattern-attribute :seon.message/to
                      :seon.db/pattern-value juniper} %)
                 patterns))
       ;; The form's evidence is what the form read on its own branch: the
       ;; context's acquisition held the pre-write value and the host
       ;; program's entities, and neither is the form's read.
       (is (= #{(:datahike.cache/connection-id (:cache-context @connection))}
              (into #{} (map #(get-in % [:datahike.read/revision :datahike.cache/connection-id])) evidence)))
       (is (true? (db/read-evidence-current? @connection evidence)))
       (is (some? (:db-after (db/transact!
                  connection
                  [{:seon.message/id "next" :seon.message/to [:seon.agent/id recipient] :seon.message/content "Next message"}]))))
       ;; Replay inputs removed as data: currency is decided by the retained
       ;; index patterns, without re-executing the inbox.
       (db/read-evidence-current?
        @connection
        (as-seen-by seen-by
                    (mapv #(dissoc % :seon.db/read-request :seon.db/read-result-digest
                                   :seon.db/read-result)
                          evidence)))))))

(deftest another-recipients-message-keeps-junipers-inbox-current
  (is (true? (inbox-evidence-after-message "root" :same-connection))))

(deftest junipers-message-invalidates-junipers-inbox
  (is (false? (inbox-evidence-after-message "juniper" :same-connection))))

;; A read's currency is its bound patterns through the index; a fresh connection to the same branch (a new
;; JVM) compared whole-attribute revisions of another generation and reported
;; 9 of root's 10 retained reads changed with 0 changed datoms (2026-09-28).
(deftest a-fresh-connection-keeps-an-unrelated-read-current
  (is (true? (inbox-evidence-after-message "root" :fresh-connection))))

(deftest a-fresh-connection-sees-a-related-write
  (is (false? (inbox-evidence-after-message "juniper" :fresh-connection))))

(deftest a-branch-recreated-under-the-same-name-proves-nothing-by-index
  (is (false? (inbox-evidence-after-message "root" :other-lineage))))

(deftest nested-patterns-and-find-pulls-retain-scoped-index-evidence
  (test-support/with-database
   (fn [connection]
     (is (some? (:db-after (db/transact! connection
                             (into (test-support/agents-tx @connection ["juniper" "root"])
                               [{:seon.message/id "mine" :seon.message/content "mine"
                                 :seon.message/to [:seon.agent/id "juniper"]}
                                {:seon.message/id "theirs" :seon.message/content "theirs"
                                 :seon.message/to [:seon.agent/id "root"]}])))))
     (doseq [where '[[[?m :seon.message/to ?recipient]
                     (not [?m :seon.message/from])]
                    [[?m :seon.message/to ?recipient]
                     (or [?m :seon.message/content "mine"]
                         (and [?m :seon.message/id "mine"]
                              (not [?m :seon.message/from])))]
                    [[?m :seon.message/to ?recipient]
                     (or-join [?m] [?m :seon.message/content "mine"]
                              (and [?m :seon.message/id "mine"]
                                   (not-join [?m] [?m :seon.message/from])))]]]
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
                       :seon.db/pattern-attribute :seon.message/from}} patterns))
         (is (some #{ {:seon.db/pattern-entity mine
                       :seon.db/pattern-attribute :seon.message/content}} patterns))
         (is (true? (db/read-evidence-current? @connection evidence)))
         (is (some? (:db-after (db/transact! connection
                                 [[:db/add [:seon.message/id "theirs"]
                                   :seon.message/content (pr-str where)]
                                  [:db/add [:seon.message/id "theirs"]
                                   :seon.message/from [:seon.agent/id "root"]]]))))
         (is (true? (db/read-evidence-current? @connection evidence)))
         (is (some? (:db-after (db/transact! connection
                                 [[:db/add [:seon.message/id "mine"]
                                   :seon.message/content "changed"]]))))
         (is (false? (db/read-evidence-current? @connection evidence)))
         (test-support/transacted! connection [[:db/add [:seon.message/id "mine"]
                                               :seon.message/content "mine"]])))
     (let [captured (atom [])
           query '[:find [?m ...] :in $ ?recipient
                   :where [?m :seon.message/to ?recipient]
                   (not [?m :seon.message/from])]]
       (test-support/transacted! connection [[:db/add [:seon.message/id "mine"]
                                             :seon.message/from [:seon.agent/id "root"]]])
       (is (empty? (binding [db/*read-evidence-sink* captured]
                     (db/q query @connection [:seon.agent/id "juniper"]))))
       (let [evidence (mapv #(dissoc % :seon.db/read-request
                                   :seon.db/read-result :seon.db/read-result-digest)
                           (db/read-evidence @captured))]
         (is (seq evidence))
         (test-support/transacted! connection [[:db/retract [:seon.message/id "mine"]
                                               :seon.message/from]])
         (is (false? (db/read-evidence-current? @connection evidence)))
         (is (= 1 (count (db/q query @connection [:seon.agent/id "juniper"])))))))))

(deftest a-plan-references-its-read-and-never-copies-the-result
  ;; Root's opening fault read kept one pattern per pulled datom: 30,729,
  ;; stored as 4.5 M characters and decoded on every turn (2026-09-27). Past
  ;; the bound (128) a read stays precise: patterns bound to one entity
  ;; collapse to it, and a read still past it keeps its request and the digest
  ;; of its answer, which currency replays; never "any write of these
  ;; attributes" (the root pull, r8).
  (test-support/with-database
   (fn [connection]
     (test-support/transacted!
      connection
      (into (test-support/agents-tx @connection ["juniper"])
            (map (fn [n] {:seon.message/id (str "m" n) :seon.message/content (str "c" n)
                          :seon.message/to [:seon.agent/id "juniper"]}))
            (range 200)))
     (let [read-of (fn [ids]
                     (let [captured (atom [])
                           result (binding [db/*read-evidence-sink* captured]
                                    (db/pull-many @connection [:seon.message/id :seon.message/content]
                                                  (mapv #(vector :seon.message/id (str "m" %)) ids)))]
                       [result (db/read-evidence @captured)]))
           sources (fn [evidence] (mapcat #(get-in % [:datahike.read/dependency-plan
                                                      :datahike.query.dependency/sources])
                                          evidence))
           [collapsed-result collapsed] (read-of (range 80))
           [replayed-result replayed] (read-of (range 200))]
       (is (= 80 (count collapsed-result)))
       (is (every? #(= [:seon.db/pattern-entity] (keys %))
                   (mapcat :seon.db/read-index-patterns (sources collapsed)))
           "160 attribute patterns collapse to the 80 pulled entities")
       (is (= 200 (count replayed-result)))
       (is (not-any? :seon.db/read-index-patterns (sources replayed)))
       (is (every? :seon.db/read-request replayed) "past the bound the plan keeps its read")
       (is (every? :seon.db/read-result-digest replayed))
       (is (not-any? :seon.db/read-result replayed) "and never the answer itself")
       (test-support/transacted! connection [{:seon.message/id "unrelated" :seon.message/content "x"
                                              :seon.message/to [:seon.agent/id "juniper"]}])
       (is (true? (db/read-evidence-current? @connection collapsed)) "an unrelated entity's write")
       (is (true? (db/read-evidence-current? @connection replayed)) "an unrelated entity's write")
       (test-support/transacted! connection [[:db/add [:seon.message/id "m0"] :seon.message/content "changed"]])
       (is (false? (db/read-evidence-current? @connection collapsed)))
       (is (false? (db/read-evidence-current? @connection replayed)))))))

(deftest a-disjunction-joined-to-a-pattern-keeps-evidence-proportional-to-its-inputs
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           symbols (vec (take 10 (sort (db/q '[:find [?s ...] :where [?e :seon.fn/sym ?s]
                                                [?e :seon.program/definition-digest]]
                                              database))))
           join '[[?e :seon.program/definition-digest ?digest]]]
       (is (= 10 (count symbols)) "the canonical program supplies the inputs")
       (doseq [disjunction '[(or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym])
                             (or-join [?e ?sym] [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym])]
               [in inputs] [['?sym (first symbols)] ['[?sym ...] symbols]]]
         (let [captured (atom [])
               result (binding [db/*read-evidence-sink* captured]
                        (db/q {:find '[?sym ?digest] :in ['$ in] :where (into [disjunction] join)}
                              database inputs))
               patterns (set (mapcat :seon.db/read-index-patterns @captured))
               subjects (if (symbol? inputs) [inputs] inputs)
               expected (into #{{:seon.db/pattern-attribute :seon.program/definition-digest}}
                              (for [subject subjects
                                    attribute [:seon.fn/sym :seon.test/sym]]
                                {:seon.db/pattern-attribute attribute :seon.db/pattern-value subject}))]
           (is (= (count subjects) (count result)) "one digest per input symbol")
           (is (= expected patterns)
               "each input binds its disjuncts; no pattern per entity the join could reach")))))))
