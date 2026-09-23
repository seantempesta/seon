(ns seon.render.root-pull-test
  "Class regressions for schema-derived root acquisition and membership."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.pull-api :as pull-api]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(def ^:private root-pull-schema
  [{:db/ident ::root-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::node-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::forward
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::edge
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::component
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/isComponent true}
   {:db/ident ::value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private caps (config/result-caps config/defaults))

(defn- request
  [connection]
  {:seon.db/db @connection
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.render.walk/lookup [::root-id "root"]
   :seon.render/distance 1
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record})

(defn- acquire
  [connection]
  (let [acquisition-request (request connection)]
    (walk/root-acquisition
     (assoc acquisition-request :seon.render.walk/root-pull-plan
            (walk/root-pull-plan acquisition-request)))))

(defn- member-lookups
  [acquisition]
  (set (keys (:seon.render.walk/members acquisition))))

(defn- changed-lookups
  [diff kind]
  (into #{} (map :seon.render.walk/lookup) (get diff kind)))

(defn- within-event-backstop
  [f]
  (let [task (future (f))]
    (try
      (deref task (* 1000 support/event-backstop-seconds) ::backstop)
      (finally
        (future-cancel task)))))

(deftest temporal-root-selector-uses-the-origin-schema
  (support/with-database
   (fn [connection]
     (support/transacted!
             connection
             (into (support/agent-tx @connection "temporal-root-agent")
               [{:seon.message/id "temporal-root-message" :seon.message/to [:seon.agent/id "temporal-root-agent"] :seon.message/content "The opening message."}]))
     (support/transacted!
             connection
             (into (support/agent-tx @connection "temporal-root-agent")
               [{:seon.turn/id "temporal-root-run" :seon.turn/agent [:seon.agent/id "temporal-root-agent"] :seon.turn/trigger [:seon.message/id "temporal-root-message"] :seon.turn/opened-tx "datomic.tx"}]))
     (let [current @connection
           temporal (db/as-of current (db/basis-t current))
           current-selector (walk/root-selector current 1 caps)
           temporal-selector (walk/root-selector temporal 1 caps)
           acquisition
           (walk/root-acquisition
            {:seon.db/db temporal
             :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
             :seon.render.walk/lookup
             [:seon.agent/id "temporal-root-agent"]
             :seon.render/distance 1
             :seon.sci.admit/caps caps})
           messages (get-in acquisition
                            [:seon.render.walk/root
                             :seon.message/_to])
           history
           (walk/history
            {:seon.db/db temporal
             :seon.agent/id "temporal-root-agent"
             :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
             :seon.render.walk/lookup
             [:seon.agent/id "temporal-root-agent"]
             :seon.render/distance 1
             :seon.sci.admit/caps caps
             :seon.sci.eval/time-limit-ms 5000
             :seon.config/on-core-error :record
             :seon.render/captured-calls (atom {})
             :seon.render.walk/root-acquisition acquisition})]
       (is (= current-selector temporal-selector)
           "current and as-of values derive one complete selector")
       (is (not= [:db/id] temporal-selector))
       (is (= ["The opening message."]
              (mapv :seon.message/content messages))
           "the as-of root retains the reverse message graph")
       (is (empty? history)
           "an unexecuted message does not invent a saved evaluation")))))

(deftest history-database-neighborhood-terminates-with-origin-schema
  (support/with-database
   (fn [connection]
     (support/transacted!
             connection
             (into (support/agent-tx @connection "historical-walk-agent")
               [{:seon.message/id "historical-walk-message" :seon.message/to [:seon.agent/id "historical-walk-agent"] :seon.message/content "A historical walk must terminate."}]))
     (let [current @connection
           render-request {:seon.db/db current
                    :seon.agent/id "historical-walk-agent"
                    :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                    :seon.render.walk/lookup
                    [:seon.agent/id "historical-walk-agent"]
                    :seon.render/distance 1
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 5000
                    :seon.config/on-core-error :record}
           acquisition (walk/root-acquisition render-request)
           result
           (within-event-backstop
            #(walk/history
              (assoc render-request
                     :seon.db/db (db/history current)
                     :seon.render/captured-calls (atom {})
                     :seon.render.walk/root-acquisition acquisition)))]
       (is (not= ::backstop result)
           "history-db rendering terminates within the declared event bound")
       (is (or (vector? result) (:seon.db/read-operation result))
           "a temporal walk returns units or a loud typed refusal")))))

(deftest root-acquisition-contract-is-projection-neutral
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "one"}])
     (let [acquisition-request (request connection)
           acquisition (walk/root-acquisition acquisition-request)
           units (walk/neighborhood
                  (assoc acquisition-request
                         :seon.render/output :seon.render/ai
                         :seon.sci.eval/time-limit-ms 5000
                         :seon.config/on-core-error :panic
                         :seon.render.walk/root-acquisition acquisition))]
       (is (not (contains? acquisition-request :seon.render/output)))
       (is ((schema/projection-validator (schema/handed-projection) :seon.render.walk/acquisition-request) acquisition-request))
       (is (= [::root-id "root"]
              (first (:seon.render.walk/order acquisition))))
       (is ((schema/projection-validator (schema/handed-projection) :seon.render.walk/units) units))
       (is (every? #(not (contains? % :seon.render.walk/changed-at))
                   units))))))

(deftest compiled-root-plan-rides-its-schema-generation-distance-and-caps
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "one"}])
     (let [initial (walk/root-pull-plan (request connection))
           compile-count (atom 0)]
       (with-redefs [pull-api/compile-pull-plan
                     (fn
                       ([selector-or-plan]
                        (if (pull-api/pull-plan? selector-or-plan)
                          selector-or-plan
                          (do
                            (swap! compile-count inc)
                            (:datahike.pull/plan initial))))
                       ([_database selector-or-plan]
                        (if (pull-api/pull-plan? selector-or-plan)
                          selector-or-plan
                          (do
                            (swap! compile-count inc)
                            (:datahike.pull/plan initial)))))]
         (let [same (walk/root-pull-plan
                     (assoc (request connection)
                            :seon.render.walk/root-acquisition initial))
               nearer (walk/root-pull-plan
                       (assoc (request connection)
                              :seon.render/distance 0
                              :seon.render.walk/root-acquisition initial))
               shallower-caps
               (assoc caps :seon.config.eval.result/max-depth 63)
               recapped (walk/root-pull-plan
                         (assoc (request connection)
                                :seon.sci.admit/caps shallower-caps
                                :seon.render.walk/root-acquisition initial))]
           (is (identical? (:datahike.pull/plan initial)
                           (:datahike.pull/plan same))
               "an unchanged immutable acquisition key reuses its plan")
           (is (= 2 @compile-count)
               "distance and caps changes each compile one replacement")
           (is (= 0 (:seon.render/distance nearer)))
           (is (= shallower-caps (:seon.sci.admit/caps recapped)))))))))

(deftest compiled-root-plan-cache-hit-never-compares-the-selector
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (let [equality-visits (atom 0)
           selector (fn []
                      [(reify Object
                         (equals [_ _]
                           (swap! equality-visits inc)
                           true)
                         (hashCode [_] 0))])
           acquisition-request (request connection)
           compiled-plan (Object.)]
       (with-redefs-fn
         {#'seon.render.walk/root-selector
          (fn [_database _distance _caps] (selector))
          #'pull-api/compile-pull-plan
          (fn [_database _selector] compiled-plan)}
         (fn []
           (is (identical? compiled-plan
                           (:datahike.pull/plan
                            (walk/root-pull-plan acquisition-request))))
           (reset! equality-visits 0)
           (is (identical? compiled-plan
                           (:datahike.pull/plan
                            (walk/root-pull-plan acquisition-request))))
           (is (zero? @equality-visits)
               "cache lookup compares only the generation, distance, and caps key")))))))

(defn- reverse-attribute
  [attribute]
  (keyword (namespace attribute) (str "_" (name attribute))))

(deftest root-selector-is-concrete-and-declared-reverse-evidence-bearing
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection
                          [{::root-id "root"
                            ::component "component"}
                           {:db/id "component"
                            ::node-id "component"
                            ::value "before"}])
     (let [reads (atom [])
           pull db/pull
           count-read (fn [operation f]
                        (fn [& arguments]
                          (swap! reads conj operation)
                          (apply f arguments)))
           acquisition
           (with-redefs [db/q (count-read :q db/q)
                         db/pull (count-read :pull pull)
                         db/pull-many (count-read :pull-many db/pull-many)
                         db/entity (count-read :entity db/entity)
                         db/datoms (count-read :datoms db/datoms)]
             (acquire connection))
           selector (:seon.render.walk/selector acquisition)
           selector-values (tree-seq coll? seq selector)
           selector-map-keys (into #{}
                                   (comp (filter map?) (mapcat keys))
                                   selector-values)
           plan (:datahike.read/dependency-plan
                 (d/pull-with-evidence @connection selector [::root-id "root"]))
           attributes (d/dependency-plan-attributes plan 0)
           ;; the PULL's limit is query work; the AI boundary's own width is
           ;; the render profile's, and it is applied to the pulled values
           width (:seon.config.eval.result/max-collection caps)]
       (is (= 2 (count (filter #{:pull} @reads)))
           "cold acquisition pulls each distinct entity once, without recursively following cycles")
       (is (every? #{:pull :q} @reads)
           "the identity query and entity pulls are the acquisition reads")
       (is (not-any? #{'* :* "*"} selector-values)
           "the selector never widens its dependency fingerprint")
       (is (contains? selector-map-keys
                      [::forward :limit (inc (long width))])
           "the forward stored ref is nested and asks one past the width")
       (is (not (contains? selector-map-keys
                           [(reverse-attribute ::edge) :limit (inc (long width))]))
           "an installed ref does not declare a reverse concern")
       (is (not (contains? selector-map-keys [:seon.message/_to :limit (inc (long width))]))
           "reverse concerns require the pulled entity's matching schema")
       (is (set? attributes))
       (is (every? attributes
                   [::root-id ::node-id ::forward ::edge ::component ::value])
           "explicit component nesting keeps every concrete dependency")))))

(deftest root-membership-ignores-undeclared-refs-and-diffs-components
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection
                          [{::root-id "root"
                            ::component "component"}
                           {:db/id "component"
                            ::node-id "component"
                            ::value "before"}])
     (let [initial (acquire connection)]
       (testing "an undeclared forward ref remains an identity in the root value"
         (support/transacted! connection
                              [{::node-id "forward"}
                               {::root-id "root"
                                ::forward [::node-id "forward"]}])
         (let [with-forward (acquire connection)
               added (walk/membership-diff initial with-forward)]
           (is (not (contains? (member-lookups with-forward)
                               [::node-id "forward"])))
           (is (= "forward" (get-in with-forward
                                    [:seon.render.walk/root ::forward ::node-id])))
           (is (= #{}
                  (changed-lookups added :seon.render.walk/added)))
           (support/transacted! connection
                                [[:db/retract [::root-id "root"] ::forward
                                  [::node-id "forward"]]])
           (let [without-forward (acquire connection)
                 removed (walk/membership-diff with-forward without-forward)]
             (is (= #{}
                    (changed-lookups removed :seon.render.walk/removed))))))

       (testing "an undeclared reverse ref neither adds nor removes a member"
         (let [before-reverse (acquire connection)]
           (support/transacted! connection
                                [{::node-id "reverse"
                                  ::edge [::root-id "root"]}])
           (let [with-reverse (acquire connection)
                 added (walk/membership-diff before-reverse with-reverse)]
             (is (= #{}
                    (changed-lookups added :seon.render.walk/added)))
             (support/transacted! connection
                                  [[:db/retract [::node-id "reverse"] ::edge
                                    [::root-id "root"]]])
             (let [without-reverse (acquire connection)
                   removed
                   (walk/membership-diff with-reverse without-reverse)]
               (is (= #{}
                      (changed-lookups removed
                                       :seon.render.walk/removed)))))))

       (testing "a component-only touch changes the component, not its root"
         (let [before-component (acquire connection)]
           (support/transacted! connection
                                [[:db/add [::node-id "component"] ::value "after"]])
           (let [after-component (acquire connection)
                 changed
                 (walk/membership-diff before-component after-component)]
             (is (= #{[::node-id "component"]}
                    (changed-lookups changed :seon.render.walk/changed)))
             (is (empty? (:seon.render.walk/added changed)))
             (is (empty? (:seon.render.walk/removed changed))))))))))

(deftest supplied-root-acquisition-is-the-only-membership-read
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "one"}])
     (let [render-request (request connection)
           acquisition (walk/root-acquisition render-request)
           reads (atom 0)
           count-read (fn [f]
                        (fn [& arguments]
                          (swap! reads inc)
                          (apply f arguments)))]
       (with-redefs [db/pull (count-read db/pull)
                     db/pull-many (count-read db/pull-many)
                     db/datoms (count-read db/datoms)]
         (walk/neighborhood
          (assoc render-request
                 :seon.render/output :seon.render/ai
                 :seon.render.walk/root-acquisition acquisition))
         (is (zero? @reads)
             "neighborhood consumes the acquisition without discovery"))))))

(deftest installed-identity-selects-a-stable-lookup-ref
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection
                          [{::root-id "root" ::component "both"}
                           {:db/id "both" ::root-id "lexical" ::node-id "declared"}])
     (let [acquisition (acquire connection)]
       (is (contains? (:seon.render.walk/members acquisition)
                      [::node-id "declared"])
           "production acquisition chooses an installed identity")))))

(deftest as-of-revision-comparison-uses-the-database-read-owner
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "one"}])
     (let [captured (atom [])
           database @connection
           fixed (db/as-of database (db/basis-t database))
           render-request (assoc (request connection) :seon.db/db fixed)
           acquisition (binding [db/*read-evidence-sink* captured]
                         (walk/root-acquisition render-request))
           call {:seon.render.call/read-evidence (db/read-evidence @captured)
                 :seon.render.call/output acquisition}]
       (is (empty? (#'web/candidate-call-ids
                    {::root call} fixed))
           "an opening as-of database compares through seon.db revisions")))))

(deftest unrelated-entity-change-keeps-root-read-current
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "retained"}])
     (let [render-request (request connection)
           call-id ::root
           [_ initial-entry] (#'web/acquire-root render-request call-id)]
       (support/transacted! connection [{::node-id "outside" ::value "changed"}])
       (let [database @connection
             retained {call-id initial-entry}
             candidates (#'web/candidate-call-ids retained database)
             pulls (atom 0)
             pull db/pull
             refreshed (with-redefs [db/pull
                                     (fn [& arguments]
                                       (swap! pulls inc)
                                       (apply pull arguments))]
                         (#'web/refresh-root
                          (assoc render-request :seon.db/db database)
                          retained call-id candidates))]
         (is (empty? candidates)
             "the same attribute on an unrelated entity does not invalidate the root")
         (is (zero? @pulls) "Datahike semantic evidence reuses the unchanged entity pull")
         (is (false? (:changed? refreshed)))
         (is (identical?
              (:datahike.pull/plan
               (:seon.render.call/output initial-entry))
              (:datahike.pull/plan (:acquisition refreshed)))
             "W2 hands the retained compiled plan through its replay")
         (is (empty? (#'web/candidate-call-ids
                      {call-id (:entry refreshed)} database))
             "the consumed revision advances even when the result is equal"))))))

(deftest another-entity-schema-cannot-add-a-reverse-read-to-this-root
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (is (:db-after (db/transact! connection [{::root-id "root" ::value "own"}])))
     (let [render-request (request connection)
           before (walk/root-acquisition render-request)
           tx (db/transact! connection
                            [{:seon.message/id "non-agent-reference"
                              :seon.message/content "Only agents declare the inbox concern."
                              :seon.message/to [::root-id "root"]}])
           after (walk/root-acquisition (assoc render-request :seon.db/db @connection))]
       (is (:db-after tx) (pr-str tx))
       (is (true? (db/read-evidence-current?
                   @connection (:seon.render.call/read-evidence before))))
       (is (= (:seon.render.walk/root before) (:seon.render.walk/root after)))
       (is (= #{[::root-id "root"]} (member-lookups after)))))))

(deftest namespace-neighborhood-follows-requires-in-both-directions
  (support/with-database
   (fn [connection]
     (let [tx (db/transact!
               connection
               [{:seon.ns/name 'root-walk.required}
                {:seon.ns/name 'root-walk.subject
                 :seon.ns/requires #{'root-walk.required}}
                {:seon.ns/name 'root-walk.dependent
                 :seon.ns/requires #{'root-walk.subject}}
                (support/program-fn-row
                 (db/db connection) 'root-walk.subject/unrelated
                 "(defn unrelated {:malli/schema [:=> [:cat] :nil]} [] nil)")])
           acquisition
           (walk/root-acquisition
            {:seon.db/db @connection
             :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
             :seon.render.walk/lookup [:seon.ns/name 'root-walk.subject]
             :seon.render/distance 1
             :seon.sci.admit/caps caps})]
       (is (:db-after tx) (pr-str tx))
       (is (= #{[:seon.ns/name 'root-walk.subject]
                [:seon.ns/name 'root-walk.required]
                [:seon.ns/name 'root-walk.dependent]}
              (member-lookups acquisition)))))))

(deftest cold-root-pull-records-an-informational-latency-sample
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "sample"}])
     (let [acquisition-request (request connection)
           pull-plan (walk/root-pull-plan acquisition-request)
           started (System/nanoTime)
           acquisition
           (walk/root-acquisition
            (assoc acquisition-request
                   :seon.render.walk/root-pull-plan pull-plan))
           elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)]
       (println (pr-str {:seon.render.walk/cold-pull-ms elapsed-ms
                         :seon.render.walk/four-query-floor-ms 46.0}))
       (is (seq (:seon.render.walk/order acquisition))
           "latency is recorded while correctness remains the verdict")))))
