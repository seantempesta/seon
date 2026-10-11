(ns seon.render.root-pull-test
  "Class regressions for schema-derived root acquisition and membership."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.program :as program]))

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

(def ^:private component-forms
  "The component attribute's declared target: the writer validates a component
   as its whole owning value, so ::component names the entity schema it owns."
  [[::root-id [:string {:seon.db/identity true}]]
   [::node-id [:string {:seon.db/identity true}]]
   [::value :string]
   [::node [:map {:seon.db/attributes true :seon.program/partition :seon.data}
            [::node-id {:optional true} ::node-id]
            [::root-id {:optional true} ::root-id]
            [::value {:optional true} ::value]]]
   [::component [:and {:seon.db/component true :seon.db/component-schema ::node} :seon.db/ref]]])

(defn- with-component-database
  "An isolated branch with the synthetic attributes installed and the component
   attribute declared as schema entities, through the canonical writer."
  [body]
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection (support/schema-entities @connection component-forms))
     (body connection))))

(def ^:private caps (config/result-caps config/defaults))

(defn- request
  [connection]
  {:seon.db/db @connection
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.render.walk/lookup [::root-id "root"]
   :seon.render/distance 1
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000})

(defn- acquire
  [connection]
  (let [acquisition-request (request connection)]
    (walk/root-acquisition
     (assoc acquisition-request :seon.render.walk/root-pull-plan
            (walk/root-pull-plan acquisition-request)))))

(defn- member-lookups
  [acquisition]
  (set (keys (:seon.render.walk/members acquisition))))

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
                             :seon.message/_to])]
       (is (= current-selector temporal-selector)
           "current and as-of values derive one complete selector")
       (is (not= [:db/id] temporal-selector))
       (is (= ["The opening message."]
              (mapv :seon.message/content messages))
           "the as-of root retains the reverse message graph")))))

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
                         :seon.render.walk/root-acquisition acquisition))]
       (is (not (contains? acquisition-request :seon.render/output)))
       (is ((schema/projection-validator (schema/handed-projection) :seon.render.walk/acquisition-request) acquisition-request))
       (is (= [::root-id "root"]
              (first (:seon.render.walk/order acquisition))))
       (is ((schema/projection-validator (schema/handed-projection) :seon.render.walk/units) units))
       (is (every? #(not (contains? % :seon.render.walk/changed-at))
                   units))))))

(deftest compiled-root-plan-is-keyed-by-its-selector-and-keeps-caller-bounds
  ;; The plan cache is keyed by projection fingerprint, installed schema and
  ;; selector (e34164b25, seon.schema/projection-cache-value); distance and caps
  ;; stay the caller's, never the cached plan's.
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "one"}])
     (let [initial (walk/root-pull-plan (request connection))
           same (walk/root-pull-plan (request connection))
           nearer (walk/root-pull-plan (assoc (request connection) :seon.render/distance 0))
           shallower-caps (assoc caps :seon.config.eval.result/max-depth 63)
           recapped (walk/root-pull-plan (assoc (request connection) :seon.sci.admit/caps shallower-caps))]
       (is (identical? (:datahike.pull/plan initial) (:datahike.pull/plan same))
           "an equal selector under the same schema reuses its compiled plan")
       (is (= (:seon.render.walk/selector nearer)
              (walk/root-selector @connection 0 caps))
           "a changed distance compiles the plan of its own selector")
       (is (= 0 (:seon.render/distance nearer)))
       (is (= shallower-caps (:seon.sci.admit/caps recapped)))))))

(defn- reverse-attribute
  [attribute]
  (keyword (namespace attribute) (str "_" (name attribute))))

(deftest root-selector-is-concrete-and-declared-reverse-evidence-bearing
  (with-component-database
   (fn [connection]
     (support/transacted! connection
                          [{::root-id "root"
                            ::component "component"}
                           {:db/id "component"
                            ::node-id "component"
                            ::value "before"}])
     ;; Each entity pull binds its own read-evidence sink (walk's
     ;; acquire-entity) and returns its evidence in the acquisition, so the
     ;; acquisition's reads are counted from that evidence.
     (let [acquisition (acquire connection)
           evidence (:seon.render.call/read-evidence acquisition)
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
       (is (= 2 (count evidence))
           "cold acquisition pulls each distinct entity once, without recursively following cycles")
       (is (every? :datahike.read/dependency-plan evidence)
           "each acquisition read is an entity pull carrying its dependency plan")
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

(deftest root-membership-ignores-undeclared-refs
  (with-component-database
   (fn [connection]
     (support/transacted! connection
                          [{::root-id "root"
                            ::component "component"}
                           {:db/id "component"
                            ::node-id "component"
                            ::value "before"}])
     (let [initial (member-lookups (acquire connection))]
       (testing "an undeclared forward ref remains an identity in the root value"
         (support/transacted! connection
                              [{::node-id "forward"}
                               {::root-id "root"
                                ::forward [::node-id "forward"]}])
         (let [with-forward (acquire connection)]
           (is (= initial (member-lookups with-forward)))
           (is (= "forward" (get-in with-forward
                                    [:seon.render.walk/root ::forward ::node-id])))))
       (testing "an undeclared reverse ref adds no member"
         (support/transacted! connection
                              [{::node-id "reverse"
                                ::edge [::root-id "root"]}])
         (is (= initial (member-lookups (acquire connection)))))))))

(deftest supplied-root-acquisition-is-the-only-membership-read
  (support/with-database
   {:seon.test-support/extra-schema root-pull-schema}
   (fn [connection]
     (support/transacted! connection [{::root-id "root" ::value "one"}])
     (let [render-request (request connection)
           acquisition (walk/root-acquisition render-request)
           [_ reads] (support/read-operations
                      #(walk/neighborhood
                        (assoc render-request
                               :seon.render/output :seon.render/ai
                               ;; A fixed render profile: an omitted one is derived
                               ;; from the cluster's configuration entity (a pull
                               ;; that is not membership discovery).
                               :seon.render/profile (render/agent-render-profile config/defaults)
                               :seon.render.walk/root-acquisition acquisition)))]
       (is (empty? (filter #{:pull :pull-many :index} reads))
           "neighborhood consumes the acquisition without discovery")))))

(deftest installed-identity-selects-a-stable-lookup-ref
  (with-component-database
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
     (let [database @connection
           fixed (db/as-of database (db/basis-t database))
           render-request (assoc (request connection) :seon.db/db fixed)
           ;; The retained call entry through its producer, never built by hand.
           [_ call] (#'web/acquire-root render-request ::root)]
       (is (empty? (#'web/candidate-call-ids
                    {::root call} fixed false))
           "an opening as-of database compares through seon.db revisions")))))

(deftest ^{:seon/tasks #{'seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current}}
  unrelated-entity-change-keeps-root-read-current
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
             candidates (#'web/candidate-call-ids retained database false)
             [refreshed reads] (support/read-operations
                                #(#'web/refresh-root
                                  (assoc render-request :seon.db/db database)
                                  retained call-id candidates))]
         (spit (str (System/getenv "SEON_CHECKOUT") "/tmp/astra-cost-handoff/c5-evidence.edn")
               (value/core-text
                (mapv (fn [evidence]
                        (let [revision (:datahike.read/revision evidence)
                              current (#'db/dependency-revision database
                                        (:datahike.read/dependency-plan evidence)
                                        (:seon.db/source-argument-position evidence))
                              moved (#'db/changed-read-attributes revision current)]
                          {:current? (db/read-evidence-current? database [evidence])
                           :moved moved
                           :no-history (into #{} (filter #(get-in database [:schema % :db/noHistory])) moved)
                           :patterns (mapv :seon.db/read-index-patterns
                                      (get-in evidence [:datahike.read/dependency-plan
                                                        :datahike.query.dependency/sources]))}))
                      (:seon.render.call/read-evidence initial-entry))))
         (is (empty? candidates)
             "the same attribute on an unrelated entity does not invalidate the root")
         (is (not-any? #{:pull} reads) "Datahike semantic evidence reuses the unchanged entity pull")
         (is (false? (:changed? refreshed)))
         (is (identical?
              (:datahike.pull/plan
               (:seon.render.call/output initial-entry))
              (:datahike.pull/plan (:acquisition refreshed)))
             "W2 hands the retained compiled plan through its replay")
         (is (empty? (#'web/candidate-call-ids
                      {call-id (:entry refreshed)} database false))
             "the consumed revision advances even when the result is equal"))))))

(deftest ^{:seon/tasks #{'seon.db/an-unrelated-entity-write-keeps-a-reads-evidence-current}}
  another-entity-schema-cannot-add-a-reverse-read-to-this-root
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
       (is (:db-after tx))
       (is (true? (db/read-evidence-current?
                   @connection (:seon.render.call/read-evidence before))))
       (is (= (:seon.render.walk/root before) (:seon.render.walk/root after)))
       (is (= #{[::root-id "root"]} (member-lookups after)))))))

(deftest namespace-neighborhood-follows-requires-in-both-directions
  (support/with-database
   (fn [connection]
     (let [tx (db/transact!
               connection
               [(support/namespace-entity @connection 'root-walk.required)
                (program/declaration-row
                 (db/carried-projection @connection)
                 {:seon.ns/name 'root-walk.subject :seon.ns/source "(ns root-walk.subject)"
                  :seon.ns/requires #{'root-walk.required}}
                 :all :agent)
                (program/declaration-row
                 (db/carried-projection @connection)
                 {:seon.ns/name 'root-walk.dependent :seon.ns/source "(ns root-walk.dependent)"
                  :seon.ns/requires #{'root-walk.subject}}
                 :all :agent)
                (support/program-fn-entity
                 (db/db connection) 'root-walk.subject/unrelated
                 "(defn unrelated {:malli/schema [:=> [:cat] :nil]} [] nil)")])
           acquisition
           (walk/root-acquisition
            {:seon.db/db @connection
             :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
             :seon.render.walk/lookup [:seon.ns/name 'root-walk.subject]
             :seon.render/distance 1
             :seon.sci.admit/caps caps})]
       (is (:db-after tx))
       (is (= #{[:seon.ns/name 'root-walk.subject]
                [:seon.ns/name 'root-walk.required]
                [:seon.ns/name 'root-walk.dependent]}
              (member-lookups acquisition)))))))

