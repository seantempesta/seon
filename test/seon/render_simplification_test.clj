(ns seon.render-simplification-test
  "Behavioral gates for ruling #50's minimal render model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

(def ^:private fixture-a 'seon.render-simplification.fixture-a)
(def ^:private fixture-b 'seon.render-simplification.fixture-b)
(def ^:private fixture-ambiguous
  'seon.render-simplification.fixture-ambiguous)

(defn- target-call
  [namespace-name function-name request]
  (if-let [function (ns-resolve namespace-name function-name)]
    (function request)
    ::not-landed))

(defn- render-request
  [database ctx owning-namespace rendered-value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/namespace owning-namespace
   :seon.render.call/id [:seon.render-simplification-test/floor]
   :seon.render/value rendered-value
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic})

(defn- render-ai
  [request]
  (target-call 'seon.render 'render-ai request))

(defn- render-html
  [request]
  (target-call 'seon.render 'render-html request))

(defn- selection
  [request]
  (target-call 'seon.render 'selection request))

(defn- stage-statuses
  [decision]
  (mapv :seon.render.selection.stage/status
        (:seon.render.selection/stages decision)))

(defn- flat-units
  [neighborhood]
  neighborhood)

(deftest floor-totality-uses-one-prepared-value
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)]
       (doseq [rendered-value [7
                               [1 2 3]
                               {:open/declared 1 :open/extra 2}
                               {:a 1 :b 2}
                               {:rows [{:a 1}]}]]
         (let [request (render-request database ctx nil rendered-value)
               floor-unit {:seon.render/value rendered-value
                           :seon.render.call/id
                           [:seon.render-simplification-test/floor]
                           :seon.sci.admit/caps caps}]
           (is (= (value/render-ai floor-unit) (render-ai request)))
           (is (= (value/render-html floor-unit) (render-html request)))
           (is (not= :seon.render/missing-declaration
                     (:seon.error/kind (render-ai request))))))))))

(deftest pulled-entity-selection-and-invocation-share-transaction-shape
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.cluster.agent/id "pulled-render-owner"}
       {:my.plan.item/id "pulled-render-item"
        :my.plan.item/title "Render the pulled item"
        :my.plan.item/agent
        [:seon.cluster.agent/id "pulled-render-owner"]
        :my.plan.item/about ['my.plan/render-item-html]}])
     (let [database @connection
           pulled (db/pull database '[*]
                           [:my.plan.item/id "pulled-render-item"])
           prepared (value/transacted pulled database)
           request (assoc (render-request
                           database (support/fork-cluster-ctx connection)
                           'my.plan pulled)
                          :seon.render/output :seon.render/html)
           decision (selection request)
           namespace-stage
           (nth (:seon.render.selection/stages decision) 2)
           candidate
           (some #(when (= 'my.plan/render-item-html
                           (:seon.render.selection.candidate/producer %))
                    %)
                 (:seon.render.selection.stage/candidates namespace-stage))
           rendered (render-html request)]
       (is (integer? (:my.plan.item/agent prepared)))
       (is (= ['my.plan/render-item-html]
              (:my.plan.item/about prepared))
           "a scalar EDN vector is not mistaken for cardinality-many")
       (is (= prepared (value/transacted (dissoc pulled :db/id) database))
           "normalization does not depend on a root :db/id projection")
       (is (= 'my.plan/render-item-html
              (:seon.render.selection/selected decision)))
       (is (= :selected
              (:seon.render.selection.stage/status namespace-stage)))
       (is (= :compatible
              (:seon.render.selection.candidate/status candidate)))
       (is (str/includes? (hiccup/->string rendered)
                          "Render the pulled item"))))))

(deftest non-rendering-more-specific-schema-does-not-shadow-agent-identity
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "agent-render-selection")
     (db/transact!
      connection
      (agent/creation-tx
       {:seon.cluster.agent/id "identity-agent"
        :seon.cluster/name "agent-render-selection"
        :seon.ns/name fixture-a}))
     (let [database @connection
           pulled (db/pull database '[*]
                           [:seon.cluster.agent/id "identity-agent"])
           request (assoc (render-request database
                                          (support/fork-cluster-ctx connection)
                                          nil pulled)
                          :seon.render/output :seon.render/ai
                          :seon.render/profile
                          {:seon.render.profile/id :test/agent
                           :seon.render.profile/token-budget 100
                           :seon.render.profile/max-depth 4
                           :seon.render.profile/max-children 10
                           :seon.render.profile/composition
                           :seon.render.profile.composition/context})
           projection-state-var
           (ns-resolve 'seon.schema '*projection-state*)]
       (with-bindings {projection-state-var nil}
         (let [decision (selection request)]
           (is (= 'seon.cluster.agent/render-identity-ai
                  (:seon.render.selection/selected decision)))
           (is (str/includes? (render-ai request) "identity-agent"))))))))

(deftest candidate-input-and-output-must-fit-the-same-arity
  (support/with-database
   (fn [connection]
     (let [cross-arity 'probe.render/cross-arity
           matching 'probe.render/matching
           argument-schema [:map [:seon.render/value :int]]
           projection
           (schema/build-projection
            (schema.edn/packaged-forms)
            {cross-arity
             [:function
              [:=> [:cat argument-schema] :int]
              [:=> [:cat :string :string] :seon.render/ai]]
             matching [:=> [:cat argument-schema] :seon.render/ai]})
           request
           (assoc (render-request @connection {} 'probe.render 7)
                  :seon.render/output :seon.render/ai
                  :seon.render/profile
                  {:seon.render.profile/id :seon.render.profile/agent
                   :seon.render.profile/token-budget 100
                   :seon.render.profile/max-depth 4
                   :seon.render.profile/max-children 10
                   :seon.render.profile/composition
                   :seon.render.profile.composition/context})]
       (with-redefs [kernel/context-projection (constantly projection)
                     kernel/public-functions-in
                     (fn [_ctx _namespace-name] [cross-arity matching])]
         (let [decision (selection request)
               namespace-stage
               (nth (:seon.render.selection/stages decision) 2)]
           (is (= matching (:seon.render.selection/selected decision)))
           (is (= [:explicit-value :explicit-request :namespace
                   :schema :floor]
                  (mapv :seon.render.selection.stage/name
                        (:seon.render.selection/stages decision))))
           (is (= [:no-match :no-match :selected
                   :not-consulted :not-consulted]
                  (stage-statuses decision)))
           (is (= [{:seon.render.selection.candidate/producer cross-arity
                    :seon.render.selection.candidate/status :rejected
                    :seon.render.selection.candidate/reason
                    :no-same-arity-match}
                   {:seon.render.selection.candidate/producer matching
                    :seon.render.selection.candidate/status :compatible}]
                  (:seon.render.selection.stage/candidates
                   namespace-stage)))))))))

(deftest explicit-value-selection-records-a-value-without-calling-it-a-producer
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           decision
           (selection
            (assoc (render-request @connection ctx nil
                                   {:seon.render/ai "already rendered"})
                   :seon.render/output :seon.render/ai))
           explicit-stage
           (first (:seon.render.selection/stages decision))]
       (is (= "already rendered"
              (:seon.render.selection/selected decision)))
       (is (= "already rendered"
              (:seon.render.selection.stage/value explicit-stage)))
       (is (nil? (:seon.render.selection.stage/candidates explicit-stage)))
       (is (= [:selected :not-consulted :not-consulted
               :not-consulted :not-consulted]
              (stage-statuses decision)))))))

(deftest missing-render-profile-remains-a-flat-error
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           missing-profile (config/effective database "missing-profile")
           result (render-html
                   (assoc (render-request database ctx fixture-b
                                          {:fixture/value 1})
                          :seon.render/html
                          'seon.render-simplification.fixture-b/holes-html
                          :seon.render/profile missing-profile))]
       (is (= ::config/missing-effective (:seon.error/kind result)))
       (is (= "missing-profile" (:seon.config/missing-effective result)))))))

(deftest floor-selection-is-recorded-on-the-retained-call
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           call-id [:seon.render-simplification-test/counted-floor]
           captured (atom {})
           request (assoc (render-request database ctx nil {:a 1})
                          :seon.render.call/id call-id
                          :seon.render/output :seon.render/ai
                          :seon.render/captured-calls captured)]
       (is (string? (target-call 'seon.render 'render-call request)))
       (is (true?
            (get-in @captured
                    [call-id
                     :seon.render.call/static-evidence
                     :seon.render/would-fall-to-floor?])))
       (let [decision
             (get-in @captured
                     [call-id :seon.render.call/static-evidence
                      :seon.render/selection])]
         (is (= 'seon.render.value/render-ai
                (:seon.render.selection/selected decision)))
         (is (= [:no-match :no-match :no-match :no-match :selected]
                (stage-statuses decision))))))))

(deftest unchanged-retained-call-skips-discovery-and-invocation
  (support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:seon.ns/name fixture-a
                     :seon.ns/doc "one"}])
     (let [ctx (support/fork-cluster-ctx connection)
           call-id [:seon.render-simplification-test/cached-namespace]
           helper 'seon.render-simplification.fixture-a/cache-helper
           discoveries (atom 0)
           invocations (atom 0)
           public-functions-in kernel/public-functions-in
           invoke kernel/invoke
           request
           (fn [database retained captured]
             (cond->
              (assoc (render-request database ctx fixture-a
                                     {:seon.ns/name fixture-a})
                     :seon.render.call/id call-id
                     :seon.render/output :seon.render/ai
                     :seon.render/captured-calls captured
                     :seon.render/candidate-call-ids #{call-id})
               retained (assoc :seon.render/retained-calls retained)))]
       (sci/binding [sci/ns (sci/create-ns fixture-a)]
         (sci/eval-form ctx '(defn cache-helper [] "H1"))
         (sci/eval-form
          ctx
          '(defn namespace-ai [value]
             (str "A:" (:seon.ns/name value) ":"
                  (seon.db/q
                   '[:find ?doc .
                     :in $ ?name
                     :where
                     [?namespace :seon.ns/name ?name]
                     [?namespace :seon.ns/doc ?doc]]
                   (:seon.db/db value) (:seon.ns/name value))
                  ":" (cache-helper)))))
       (with-redefs [kernel/public-functions-in
                     (fn [candidate-ctx namespace-name]
                       (swap! discoveries inc)
                       (public-functions-in candidate-ctx namespace-name))
                     kernel/invoke
                     (fn [invocation]
                       (swap! invocations inc)
                       (invoke invocation))]
         (let [first-captured (atom {})
               first-output
               (target-call 'seon.render 'render-call
                            (request @connection nil first-captured))
               retained @first-captured
               second-captured (atom {})
               second-output
               (target-call 'seon.render 'render-call
                            (request @connection retained second-captured))]
           (is (= (str "A:" fixture-a ":one:H1")
                  first-output second-output))
           (is (= {:discoveries 1 :invocations 1}
                  {:discoveries @discoveries :invocations @invocations})
               "the unchanged call skips candidate and nested invocation work")
           (db/transact! connection [{:seon.cluster/name "cache-unrelated"}])
           (let [unrelated-captured (atom {})
                 unrelated-output
                 (target-call 'seon.render 'render-call
                              (request @connection @second-captured
                                       unrelated-captured))]
             (is (= first-output unrelated-output))
             (is (= {:discoveries 1 :invocations 1}
                    {:discoveries @discoveries :invocations @invocations})
                 "an unrelated database revision retains the call")
             (db/transact! connection
                           [{:seon.ns/name fixture-a
                             :seon.ns/doc "two"}])
             (let [relevant-captured (atom {})
                   relevant-output
                   (target-call 'seon.render 'render-call
                                (request @connection @unrelated-captured
                                         relevant-captured))]
               (is (= (str "A:" fixture-a ":two:H1") relevant-output))
               (is (= {:discoveries 2 :invocations 2}
                      {:discoveries @discoveries :invocations @invocations})
                   "a changed read dependency invalidates the call")
               (sci/binding [sci/ns (sci/create-ns fixture-a)]
                 (sci/eval-form ctx '(defn cache-helper [] "H2")))
           (kernel/cache-function!
                ctx helper
                {:seon.sci.eval/function-private? true
                 :seon.fn/source "(defn cache-helper [] \"H2\")"})
               (let [helper-captured (atom {})
                     helper-output
                     (target-call 'seon.render 'render-call
                                  (request @connection @relevant-captured
                                           helper-captured))]
                 (is (= (str "A:" fixture-a ":two:H2") helper-output))
                 (is (= {:discoveries 3 :invocations 3}
                        {:discoveries @discoveries
                         :invocations @invocations})
                     "a helper program change invalidates its caller"))))))))))

(deftest nested-values-render-their-declared-faces
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           report (binding [db/*conn* connection]
                    (db/transact! []))
           request (render-request database ctx nil
                                   {:probe/database database
                                    :probe/report report})
           ai (render-ai request)
           html (hiccup/->string (render-html request))]
       (is (str/includes? ai "database"))
       (is (str/includes? ai "basis transaction"))
       (is (not (str/includes? ai "#datahike.db.DB")))
       (doseq [rendered [ai html]]
         (is (str/includes? rendered "Committed transaction"))
         (is (not (str/includes? rendered "datahike.db.TxReport"))))

       (let [first-producer
             'seon.render-simplification.fixture-ambiguous/first-ai
             second-producer
             'seon.render-simplification.fixture-ambiguous/second-ai
             matches
             [{:seon.schema/key :fixture.render/second
               :seon.render/ai second-producer
               :seon.render/html second-producer}
              {:seon.schema/key :fixture.render/first
               :seon.render/ai first-producer
               :seon.render/html first-producer}]
             matching-shapes-in schema/matching-shapes-in
             render-with-ambiguity
             (fn [render]
               (with-redefs
                [schema/matching-shapes-in
                 (fn [projection value]
                   (if (:fixture.render/ambiguous value)
                     matches
                     (matching-shapes-in projection value)))]
                 (render
                  (render-request database ctx nil
                                  {:probe/nested
                                   {:fixture.render/ambiguous true}}))))
             ambiguous-ai (render-with-ambiguity render-ai)
             ambiguous-html
             (hiccup/->string (render-with-ambiguity render-html))]
         (doseq [rendered [ambiguous-ai ambiguous-html]]
           (is (str/includes? rendered "seon.render/ambiguous"))
           (is (< (str/index-of rendered (str first-producer))
                  (str/index-of rendered (str second-producer))))))))))

(deftest owning-namespace-alone-selects-across-a-walk
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           rendered-value {:seon.ns/name fixture-b}
           request (render-request database ctx fixture-b rendered-value)]
       (is (= (str "B:" fixture-b) (render-ai request)))
       (is (= (str "A:" fixture-b)
              (render-ai (assoc request :seon.render/namespace fixture-a))))
       (let [walked (walk/neighborhood
                     {:seon.db/db database
                      :seon.sci.eval/ctx ctx
                      :seon.render.walk/lookup [:seon.ns/name fixture-b]
                      :seon.render/output :seon.render/ai
                      :seon.sci.admit/caps caps
                      :seon.sci.eval/time-limit-ms 2000
                      :seon.config/on-core-error :panic
                      :seon.render/distance 0})
             root-unit (first walked)]
         (is (vector? walked))
         (is (= (str "B:" fixture-b) (:seon.render/output root-unit)))
         (is (not-any? #(contains? root-unit %)
                       [:seon.render/unit
                        :seon.render/kind
                        :seon.render/projection
                        :seon.render/would-fall-to-floor?])))
       (db/transact! connection
                     [[:db.fn/retractEntity
                       [:seon.fn/sym
                        "seon.render-simplification.fixture-b/namespace-ai"]]])
       (let [without-owner-function
             (render-ai (assoc request :seon.db/db @connection))]
         (is (not (str/starts-with? (str without-owner-function) "A:")))
         (is (not= :seon.render/missing-declaration
                   (:seon.error/kind without-owner-function))))))))

(deftest overlapping-contracts-refuse-loudly-and-deterministically
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           request (render-request database ctx fixture-ambiguous
                                   {:seon.ns/name fixture-ambiguous})
           result (render-ai request)
           decision (selection
                     (assoc request :seon.render/output :seon.render/ai))
           walked (walk/neighborhood
                   {:seon.db/db database
                    :seon.sci.eval/ctx ctx
                    :seon.render.walk/lookup
                    [:seon.ns/name fixture-ambiguous]
                    :seon.render/output :seon.render/ai
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.render/distance 0})
           expected #{"seon.render-simplification.fixture-ambiguous/first-ai"
                      "seon.render-simplification.fixture-ambiguous/second-ai"}]
       (is (= :seon.render/ambiguous (:seon.error/kind result)))
       (is (= :seon.render/ambiguous
              (:seon.error/kind
               (:seon.render.selection/selected decision))))
       (is (= [:no-match :no-match :ambiguous
               :not-consulted :not-consulted]
              (stage-statuses decision)))
       (is (= expected
              (set (:seon.render/candidates (:seon.error/data result)))))
       (is (= (sort expected)
              (:seon.render/candidates (:seon.error/data result))))
       (is (= :seon.render/ambiguous
              (get-in (first walked)
                      [:seon.error/value :seon.error/kind])))
       (is (= expected
              (set (get-in (first walked)
                           [:seon.error/value
                            :seon.error/data
                            :seon.render/candidates]))))))))

(deftest renderer-invocation-is-sci-only-and-live-var-backed
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           request (render-request database ctx fixture-b
                                   {:seon.ns/name fixture-b})]
       (with-redefs [clojure.core/requiring-resolve
                     (fn [& _]
                       (throw (ex-info "compiled resolver trap" {})))]
         (is (= (str "B:" fixture-b) (render-ai request))))
       (sci/binding [sci/ns (sci/create-ns fixture-b)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [value]
             (str "B2:" (:seon.ns/name value)))))
       (is (= (str "B2:" fixture-b) (render-ai request)))
       (sci/binding [sci/ns (sci/create-ns fixture-b)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [_value]
             [:div (seon.render.hiccup/raw
                    "<script>raw-agent-bytes</script>")])))
       (let [raw-result
             (render-html
              (assoc (render-request database ctx fixture-b {:fixture/value 1})
                     :seon.render/html
                     'seon.render-simplification.fixture-b/namespace-ai))]
         (is (hiccup/hiccup? raw-result))
         (is (not
              (hiccup/raw?
               (get-in raw-result
                       [1]))))
         (is (str/includes? (hiccup/->string raw-result)
                            "&lt;script&gt;raw-agent-bytes&lt;/script&gt;"))
         (is (not (str/includes? (hiccup/->string raw-result)
                                 "<script>raw-agent-bytes</script>"))))
       (let [evaluation
             (eval/evaluate
              {:seon.cluster.run.form/source
               (str "(defn live-html "
                    "{:malli/schema [:=> [:cat :map] :seon.render/hiccup]} "
                    "[_value] [:article {:class \"live-html\"} \"live\"])")
               :seon.cluster.run.form/ns [:seon.ns/name fixture-a]
               :seon.sci.eval/ctx ctx
               :seon.sci.admit/caps caps
               :seon.sci.eval/time-limit-ms 2000
               :seon.config/on-core-error :panic})
             row (dissoc (:seon.program/row evaluation)
                         :seon.sci.eval/evaluated?)
             report
             (db/transact! connection [row])]
         (eval/install-evaluated-rows!
          {:seon.sci.eval/ctx ctx
           :seon.db/db (:db-after report)
           :seon.sci.eval/installations
           [{:seon.program/row row
             :seon.sci.eval/evaluation evaluation}]})
         (is (some #{'seon.render-simplification.fixture-a/live-html}
                   (kernel/public-functions-in ctx fixture-a))
             "the terminal install publishes a new renderer candidate")
         (is (= [:article {:class "live-html"} "live"]
                (render-html
                 (render-request @connection ctx fixture-a
                                 {:seon.ns/name fixture-a})))
             "an ordinary durable defn auto-wires onto the next render"))))))

(deftest cold-context-reacquires-the-same-row
  (support/with-database
   (fn [connection]
     (let [database @connection
           request (fn [ctx]
                     (render-request database ctx fixture-b
                                     {:seon.ns/name fixture-b}))]
       (is (= (str "B:" fixture-b)
              (render-ai (request (support/fork-cluster-ctx connection)))))
       (is (= (str "B:" fixture-b)
              (render-ai (request (support/fork-cluster-ctx connection)))))))))

(deftest distance-spends-only-real-ref-hops-and-caps-win
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [[:db/add [:seon.ns/name fixture-a]
        :seon.ns/requires [:seon.ns/name fixture-b]]])
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           request {:seon.db/db database
                    :seon.sci.eval/ctx ctx
                    :seon.render.walk/lookup [:seon.ns/name fixture-a]
                    :seon.render/output :seon.render/ai
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic}
           at-zero (flat-units (walk/neighborhood
                                (assoc request :seon.render/distance 0)))
           at-one (flat-units (walk/neighborhood
                               (assoc request :seon.render/distance 1)))
           capped (flat-units
                   (walk/neighborhood
                    (assoc request
                           :seon.render/distance 4
                           :seon.sci.admit/caps
                           (assoc caps
                                  :seon.config.eval.result/max-nodes 1))))]
       (is (= #{[:seon.ns/name fixture-a]}
              (set (map :seon.render.walk/lookup at-zero))))
       (is (contains? (set (map :seon.render.walk/lookup at-one))
                      [:seon.ns/name fixture-b]))
       (is (= #{[:seon.ns/name fixture-a]}
              (set (map :seon.render.walk/lookup
                        (remove :seon.error/value capped)))))))))

(deftest old-slot-and-ref-markers-are-inert-renderer-output
  (support/with-database
   (fn [connection]
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           expected [:section {:data-slot "inert"}
                     [:span {:data-ref "[:db/id 7]"} "also inert"]]
           result
           (render-html
            (assoc (render-request database ctx fixture-b {:fixture/value 1})
                   :seon.render/html
                   'seon.render-simplification.fixture-b/holes-html))]
       (is (= expected result))
       (is (hiccup/hiccup? result))))))

(deftest settled-package-is-reused-by-every-join
  (let [keyframe "<article id=\"one\">one</article>"
        keyframe-bytes (.getBytes keyframe "UTF-8")
        package {:seon.render.package/revision 7
                 :seon.render.package/base-revision 6
                 :seon.render.package/basis-transaction 1
                 :seon.render.package/streaming? false
                 :seon.render.package/keyframe {"one" keyframe}
                 :seon.render.package/keyframe-bytes keyframe-bytes
                 :seon.render.package/keyframe-size (alength keyframe-bytes)
                 :seon.render.package/delta {"one" keyframe}
                 :seon.render.package/delta-bytes keyframe-bytes
                 :seon.render.package/delta-size (alength keyframe-bytes)}]
    (with-redefs [hiccup/->string (fn [& _]
                                   (throw (ex-info "serialized on join" {})))]
      (is (identical? package
                      (target-call 'seon.render.web 'join-package package)))
      (is (identical? package
                      (target-call 'seon.render.web 'join-package package))))))

(deftest shared-invocation-cache-reuses-identical-entity-inputs-across-call-ids
  (support/with-database
   (fn [connection]
     (db/transact! connection
                   [{:seon.ns/name fixture-a
                     :seon.ns/doc "one"}])
     (let [ctx (support/fork-cluster-ctx connection)
           invoke kernel/invoke
           invocations (atom 0)
           request (fn [call-id retained captured]
                     (assoc (render-request @connection ctx fixture-a
                                            {:seon.ns/name fixture-a})
                            :seon.render/output :seon.render/ai
                            :seon.render.call/id call-id
                            :seon.render/invocations retained
                            :seon.render/captured-invocations captured))]
       (sci/binding [sci/ns (sci/create-ns fixture-a)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [value]
             (str "A:" (:seon.ns/name value) ":"
                  (seon.db/q
                   '[:find ?doc .
                     :in $ ?name
                     :where
                     [?namespace :seon.ns/name ?name]
                     [?namespace :seon.ns/doc ?doc]]
                   (:seon.db/db value) (:seon.ns/name value))))))
       (with-redefs [kernel/invoke (fn [invocation]
                                    (swap! invocations inc)
                                    (invoke invocation))]
         (let [first-captured (atom {})
               first-output (target-call 'seon.render 'render-call
                                         (request [:debug] {} first-captured))]
           (let [second-captured (atom {})
                 second-output (target-call 'seon.render 'render-call
                                            (request [:context] @first-captured
                                                     second-captured))]
             (is (= (str "A:" fixture-a ":one") first-output second-output))
             (is (= 1 @invocations)
                 "the second presentation reuses the raw invocation")
             (is (some (comp seq :seon.render.call/read-evidence)
                       (mapcat identity (vals @second-captured)))
                 "a cache hit retains read evidence from the invocation")
             (db/transact! connection
                           [{:seon.cluster/name "cache-unrelated"}])
             (let [unrelated-captured (atom {})
                   unrelated-output
                   (target-call 'seon.render 'render-call
                                (request [:unrelated] @second-captured
                                         unrelated-captured))]
               (is (= second-output unrelated-output))
               (is (= 1 @invocations)
                   "an unrelated transaction does not invoke again")
               (db/transact! connection
                             [{:seon.ns/name fixture-a
                               :seon.ns/doc "two"}])
               (let [relevant-captured (atom {})
                     relevant-output
                     (target-call 'seon.render 'render-call
                                  (request [:changed] @unrelated-captured
                                           relevant-captured))]
                 (is (= (str "A:" fixture-a ":two") relevant-output))
                 (is (= 2 @invocations)
                     "a changed read dependency invokes again"))))))))))

(deftest authored-source-invocation-reuses-one-stored-run-across-presentations
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "source-cache")
     (db/transact!
      connection
      (concat
       (agent/creation-tx
        {:seon.cluster.agent/id "source-cache-agent"
         :seon.ns/name fixture-a
         :seon.cluster/name "source-cache"})
       [{:seon.cluster.run/id "source-cache-run"
         :seon.cluster.run/agent
         [:seon.cluster.agent/id "source-cache-agent"]
         :seon.cluster.run/opened-at #inst "2026-09-06T20:00:00Z"
         :seon.cluster.run/starting-ns [:seon.ns/name fixture-a]}
        {:seon.cluster.eval/id "source-cache-eval"
         :seon.cluster.eval/run
         [:seon.cluster.run/id "source-cache-run"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at #inst "2026-09-06T20:00:00Z"
         :seon.cluster.eval/source "(+ 1 1)"
         :seon.cluster.eval/ns [:seon.ns/name fixture-a]}]))
     (let [ctx (support/fork-cluster-ctx connection)
           invoke kernel/invoke
           source-invocations (atom 0)
           submissions (atom 0)
           source-call (ns-resolve 'seon.render.web 'render-source-call)
           submit-var (ns-resolve 'seon.cluster.agent 'submit-source!)
           request
           (fn [call-id retained captured calls retained-calls value]
             (assoc (render-request @connection ctx fixture-a
                                    value)
                    :seon.render/output :seon.render/ai
                    :seon.render/profile
                    {:seon.render.profile/id :test/source-cache
                     :seon.render.profile/token-budget 1000
                     :seon.render.profile/max-depth 8
                     :seon.render.profile/max-children 100
                     :seon.render.profile/composition
                     :seon.render.profile.composition/context}
                    :seon.render.call/id call-id
                    :seon.render/retained-calls retained-calls
                    :seon.render/captured-calls calls
                    :seon.render/invocations retained
                    :seon.render/captured-invocations captured
                    :seon.cluster.agent/id "source-cache-agent"
                    :seon.cluster.loop/cluster {}
                    :seon.cluster.agent/routing (atom {})))]
       (sci/binding [sci/ns (sci/create-ns fixture-a)]
         (sci/eval-form ctx '(defn namespace-ai [_] "(+ 1 1)")))
       (with-redefs-fn
         {#'kernel/invoke
          (fn [invocation]
            (swap! source-invocations inc)
            (invoke invocation))
          submit-var
          (fn [_]
            (swap! submissions inc)
            {:seon.cluster.run/id "source-cache-run"})}
         (fn []
           (let [first-invocations (atom {})
                 first-calls (atom {})
                 first-output
                 (source-call (request [:debug] {} first-invocations
                                       first-calls {}
                                       {:seon.ns/name fixture-a}))
                 _ (db/transact!
                    connection
                    [{:seon.cluster.eval/id "source-cache-eval"
                      :seon.cluster.eval/result-edn
                      "#:seon.print{:face :seon.print/number, :value 2}"}
                     {:seon.cluster.run/id "source-cache-run"
                      :seon.cluster.run/closed-at
                      #inst "2026-09-06T20:00:01Z"}])
                 second-invocations (atom {})
                 second-calls (atom {})
                 second-output
                 (source-call
                  (request [:debug] @first-invocations second-invocations
                           second-calls @first-calls
                           {:seon.ns/name fixture-a
                            :seon.cluster.run/_agent [{:db/id 9001}]}))
                 third-invocations (atom {})
                 third-calls (atom {})
                 third-output
                 (source-call
                  (request [:context] @second-invocations third-invocations
                           third-calls {}
                           {:seon.ns/name fixture-a
                            :seon.cluster.run/_agent [{:db/id 9001}]}))
                 retained (some-> @third-invocations vals first peek)]
             (is (nil? first-output)
                 "the pending run has no invented synchronous output")
             (is (= second-output third-output))
             (is (str/includes? second-output "2"))
             (is (= 2 @source-invocations)
                 "one entity change regenerates source; the second presentation reuses it")
             (is (= 1 @submissions)
                 "pending and terminal refreshes retain one execution identity")
             (is (= "(+ 1 1)" (:seon.render.call/source retained)))
             (is (= "source-cache-run"
                    (:seon.render.call/source-run-id retained)))
             (is (= third-output
                    (:seon.render.call/output retained))))))))))

(deftest generic-renderer-receives-the-acquired-entity-id
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.ns/name fixture-a
                                :seon.ns/doc "identity input"}])
     (let [database @connection
           entity (db/pull database '[*] [:seon.ns/name fixture-a])
           ctx (support/fork-cluster-ctx connection)]
       (sci/binding [sci/ns (sci/create-ns fixture-a)]
         (sci/eval-form ctx '(defn namespace-ai [value]
                               (str (:db/id value) ":"
                                    (:seon.ns/name value)))))
       (is (= (str (:db/id entity) ":" fixture-a)
              (target-call
               'seon.render 'render-call
               (assoc (render-request database ctx fixture-a entity)
                      :seon.render/output :seon.render/ai)))
           "a generic renderer can use the acquired entity identity")))))

(deftest broken-renderer-is-private-to-browser-and-loud-to-owner
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "render-failure")
     (db/transact!
      connection
      (agent/creation-tx
       {:seon.cluster.agent/id "owner-b"
        :seon.cluster/name "render-failure"
        :seon.ns/name fixture-b}))
     (let [failure {:seon.error/kind :render.test/broken
                    :seon.error/message "secret stack and renderer symbol"
                    :seon.error/data
                    {:seon.fn/sym
                     "seon.render-simplification.fixture-b/holes-html"}}
           outcome
           (target-call
            'seon.render 'renderer-failure
            {:seon.db/db @connection
             :seon.render/namespace fixture-b
             :seon.error/value failure})]
       (db/transact! connection (:seon.db/tx-data outcome))
       (let [html (:seon.render/html outcome)
             browser (str html)
             messages
             (db/q '[:find [?content ...]
                    :in $ ?owner
                    :where
                    [?agent :seon.cluster.agent/id ?owner]
                    [?message :seon.cluster.message/to ?agent]
                    [?message :seon.cluster.message/content ?content]]
                  @connection "owner-b")]
         (is (str/includes? browser "unavailable"))
         (is (not (str/includes? browser "secret stack")))
         (is (not (str/includes? browser "holes-html")))
         (is (= 1 (count messages)))
         (is (boolean
              (some #(str/includes? % "renderer") messages))))))))
