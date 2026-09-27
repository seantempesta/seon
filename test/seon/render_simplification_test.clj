(ns seon.render-simplification-test
  "Behavioral gates for ruling #50's minimal render model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.cluster]
            [seon.fn]
            [seon.render]
            [seon.render.web]
            [seon.turn]
            [clojure.core.async]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.eval :as eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(def ^:private render-profile
  (seon.render/agent-render-profile config/defaults))

(def ^:private caps (config/result-caps config/defaults))

(def ^:private fixture-a 'seon.render-simplification.fixture-a)
(def ^:private fixture-b 'seon.render-simplification.fixture-b)
(def ^:private fixture-ambiguous
  'seon.render-simplification.fixture-ambiguous)

(defn authored-source
  "Return the declared source used by the retained-execution regression."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_unit]
  "(+ 1 1)")

(defn- target-call
  [namespace-name function-name request]
  (if-let [function (ns-resolve namespace-name function-name)]
    (function request)
    ::not-landed))

(defn- render-request [database ctx owning-namespace rendered-value]
  (cond->
    {:seon.db/db database,
     :seon.sci.eval/ctx ctx,
     :seon.render.call/id [:seon.render-simplification-test/floor],
     :seon.render/value rendered-value,
     :seon.render/profile render-profile,
     :seon.sci.admit/caps caps,
     :seon.sci.eval/time-limit-ms 2000}
    owning-namespace
    (assoc :seon.render.call/function (symbol (str owning-namespace) "subject"))))

(defn- schema-entity
  "One schema declaration entity as the program owner writes it (with its digest)."
  [connection schema-key form]
  (program/declaration-row (db/carried-projection (db/db connection))
                           {:seon.schema/key schema-key :seon.schema/form (pr-str form)}
                           :all :core))

(defn declare-subject!
  "Declare a fixture subject through the canonical writers: a schema named
  like PRODUCER (default `<namespace>/subject`) whose AI/HTML pair names the
  given renderers, and the contracted PRODUCER returning it. A request naming
  that producer (:seon.render.call/function) selects the pair."
  {:malli/schema [:=> [:cat :seon.db/connection :symbol [:map-of :qualified-keyword :qualified-symbol]]
                  :qualified-symbol]}
  [connection named pair]
  (let [producer (if (qualified-symbol? named) named (symbol (str named) "subject"))
        subject (keyword (namespace producer) (name producer))]
    (support/transacted! connection
                         [(schema-entity connection subject
                                      [:map pair [:seon.ns/name {:optional true} :seon.ns/name]])])
    (support/transacted! connection
                         [(support/program-fn-entity
                           (db/db connection) producer
                           (str "(defn " (name producer) " {:malli/schema [:=> [:cat] " subject "]} [] {})"))])
    producer))

(defn- declare-fixture-subjects!
  "The fixture A and B subjects most regressions render."
  [connection]
  (declare-subject! connection fixture-a
                    {:seon.render/ai 'seon.render-simplification.fixture-a/namespace-ai})
  (declare-subject! connection fixture-b
                    {:seon.render/ai 'seon.render-simplification.fixture-b/namespace-ai
                     :seon.render/html 'seon.render-simplification.fixture-b/holes-html}))

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
     (let [database (db/db connection)
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
           (is (string? (render-ai request)))))))))

(deftest
  attribute-declared-producers-select-for-every-projection
  (support/with-database
    (fn [connection]
      (db/transact! connection (support/agent-tx @connection "plan-owner"))
      (let [database (db/db connection)
            owner (db/q '[:find ?e . :where [?e :seon.agent/id "plan-owner"]] database)
            entity {:my.plan/agent owner, :my.plan/objective "Verify the plan"}
            ctx (support/fork-cluster-ctx connection)]
        (doseq [[output producer] [[:seon.render/ai 'seon.plan/render-plan-ai]
                                   [:seon.render/html 'seon.plan/render-plan-html]]]
          (let [request (assoc
                          (render-request database ctx nil entity)
                          :seon.render/output
                          output)
                decision (selection request)
                argument (#'seon.render/render-invocation-argument
                           (kernel/context-projection ctx)
                           request
                           producer)]
            (is (= producer (:seon.render.selection/selected decision)))
            (is (= entity (:seon.render/value argument)))
            (is (= database (:seon.db/db argument))
                "the render boundary passes its database in the unit")
            (is
              (not=
                producer
                (:seon.render.selection/selected
                  (selection (assoc request :seon.render/value [])))))
            (if (= output :seon.render/ai)
              (is (str/includes? (render-ai request) "(seon.plan/plan {})"))
              (is
                (str/includes?
                  (hiccup/->string (render-html request))
                  "Verify the plan")))))))))

(deftest
  pulled-entity-selection-and-invocation-share-transaction-shape
  (support/with-database
    (fn [connection]
      (db/transact!
        connection
        (into (support/agent-tx @connection "pulled-render-owner")
          [{:my.plan.item/id "pulled-render-item",
            :my.plan.item/title "Render the pulled item",
            :my.plan.item/agent [:seon.agent/id "pulled-render-owner"],
            :my.plan.item/about ['seon.plan/render-item-html]}]))
      (let [database (db/db connection)
            pulled (db/pull database '[*] [:my.plan.item/id "pulled-render-item"])
            prepared (value/transacted pulled database)
            request (assoc
                      (render-request
                        database
                        (support/fork-cluster-ctx connection)
                        nil
                        pulled)
                      :seon.render/output
                      :seon.render/html)
            decision (selection request)
            declared-stage (some #(when (= :declared (:seon.render.selection.stage/name %)) %)
                                 (:seon.render.selection/stages decision))
            rendered (render-html request)]
        (is (integer? (:my.plan.item/agent prepared)))
        (is
          (= ['seon.plan/render-item-html] (:my.plan.item/about prepared))
          "a scalar EDN vector is not mistaken for cardinality-many")
        (is
          (= prepared (value/transacted (dissoc pulled :db/id) database))
          "normalization does not depend on a root :db/id projection")
        (is (= 'seon.plan/render-item-html (:seon.render.selection/selected decision)))
        (is (= :selected (:seon.render.selection.stage/status declared-stage))
            "the item's identity names its entity schema's pair")
        (is (str/includes? (hiccup/->string rendered) "Render the pulled item"))))))

(deftest
  non-rendering-more-specific-schema-does-not-shadow-agent-identity
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "agent-render-selection")
      (db/transact!
        connection
        (agent/creation-tx
          {:seon.agent/id "identity-agent",
           :seon.cluster/name "agent-render-selection",
           :seon.ns/name fixture-a}))
      (let [database (db/db connection)
            pulled (db/pull database '[*] [:seon.agent/id "identity-agent"])
            request (assoc
                      (render-request
                        database
                        (support/fork-cluster-ctx connection)
                        nil
                        pulled)
                      :seon.render/output
                      :seon.render/ai
                      :seon.render/profile
                      {:seon.render.profile/id :test/agent,
                       :seon.render.profile/token-budget 100,
                       :seon.render.profile/max-depth 4,
                       :seon.render.profile/max-children 10,
                       :seon.render.profile/composition :multiline})
            projection-state-var (ns-resolve 'seon.schema '*projection-state*)]
        (with-bindings
          {projection-state-var nil}
          (let [decision (selection request)]
            (is
              (=
                'seon.cluster.agent/render-identity-ai
                (:seon.render.selection/selected decision)))
            (is (= (agent/render-identity-ai pulled) (render-ai request)))))))))

(deftest function-arities-are-matched-without-recompilation
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
          matching [:=> [:cat argument-schema] :seon.render/ai]})]
    ;; The first question compiles each arity once into the projection's
    ;; holder; every later question must reuse it.
    (schema/function-matching-outputs-in projection matching [{:seon.render/value 7}])
    (schema/function-matching-outputs-in projection cross-arity [{:seon.render/value 7}])
    (with-redefs [m/function-schema
                  (fn [& _] (throw (ex-info "unexpected recompilation" {})))
                  m/validator
                  (fn [& _] (throw (ex-info "unexpected validator compilation" {})))]
      (is (schema/function-accepts-in?
           projection matching [{:seon.render/value 7}]))
      (is (schema/function-returns-in?
           projection matching :seon.render/ai))
      (is (schema/function-accepts-and-returns-in?
           projection matching [{:seon.render/value 7}] :seon.render/ai))
      (is (= [:int] (schema/function-matching-outputs-in
                     projection cross-arity [{:seon.render/value 7}]))))))

(deftest missing-render-profile-remains-a-flat-error
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           missing-profile (config/effective database "missing-profile")
           result (render-html
                   (assoc (render-request database ctx nil
                                          {:fixture/value 1})
                          :seon.render/html
                          'seon.render-simplification.fixture-b/holes-html
                          :seon.render/profile missing-profile))]
       (is (some? (:seon.config/missing-effective result)))
       (is (= "missing-profile" (:seon.config/missing-effective result)))))))

(deftest floor-selection-is-recorded-on-the-retained-call
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
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
         (is (= [:no-match :no-match :no-match :selected]
                (stage-statuses decision))))))))

(deftest
  unchanged-retained-call-skips-discovery-and-invocation
  (support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.ns/name fixture-a, :seon.ns/doc "one"}])
      (declare-fixture-subjects! connection)
      (let [ctx (support/fork-cluster-ctx connection)
            call-id [:seon.render-simplification-test/cached-namespace]
            helper 'seon.render-simplification.fixture-a/cache-helper
            ;; The armed counters of discovery and invocation; no Var is
            ;; redefined.
            counted '#{seon.render/selection seon.sci.kernel/invoke}
            request (fn [database retained captured]
                      (cond->
                        (assoc
                          (render-request database ctx fixture-a {:seon.ns/name fixture-a})
                          :seon.render.call/id
                          call-id
                          :seon.render/output
                          :seon.render/ai
                          :seon.render/captured-calls
                          captured
                          :seon.render/candidate-call-ids
                          #{call-id})
                        retained
                        (assoc :seon.render/retained-calls retained)))]
        (let [definitions '[(defn cache-helper [] "H1")
                            (defn ^{:malli/schema [:=> [:cat :seon.render/unit] :seon.render/ai]}
                              namespace-ai [value]
                              (str
                                "A:"
                                (:seon.ns/name value)
                                ":"
                                (seon.db/q
                                  '[:find
                                    ?doc
                                    .
                                    :in
                                    $
                                    ?name
                                    :where
                                    [?namespace :seon.ns/name ?name]
                                    [?namespace :seon.ns/doc ?doc]]
                                  (:seon.ns/name value))
                                ":"
                                (cache-helper)))]]
          (let [requests (mapv
                           (fn [definition]
                             (let [source (pr-str definition) name (second definition)]
                               {:seon.cluster.eval/source source,
                                :seon.cluster.eval/ns [:seon.ns/name fixture-a],
                                :seon.program/row
                                {:seon.fn/sym (symbol (str fixture-a) (str name)),
                                 :seon.fn/ns [:seon.ns/name fixture-a],
                                 :seon.fn/source source,
                                 :seon.fn/arglists (pr-str (list (nth definition 2))),
                                 :seon.fn/private? (= 'cache-helper name),
                                 :seon.schema.admission/source :agent}}))
                           definitions)
                ;; Analyzed facts, then the digest of each FINAL entity (the writer's).
                entities (mapv #(support/revised-entity (db/db connection) (second %) {})
                           (seon.fn/analyze-forms (db/db connection) requests))
                report (db/transact! connection entities)]
            (is (:db-after report) (pr-str report))
            (is (some #{helper} (:seon.fn/calls (second entities))))
            (sci/binding
              [sci/ns (sci/create-ns fixture-a)]
              (doseq [definition definitions] (sci/eval-form ctx definition)))))
        (let [opening (support/armed-counts counted)
              counts #(let [now (merge-with - (support/armed-counts counted) opening)]
                        {:discoveries (get now 'seon.render/selection)
                         :invocations (get now 'seon.sci.kernel/invoke)})
              first-captured (atom {})
              first-output (target-call
                             'seon.render
                             'render-call
                             (request (db/db connection) nil first-captured))
              retained @first-captured
              second-captured (atom {})
              second-output (target-call
                              'seon.render
                              'render-call
                              (request (db/db connection) retained second-captured))]
          (is (= (str "A:" fixture-a ":one:H1") first-output second-output))
          (is
            (=
              {:discoveries 1, :invocations 1}
              (counts))
            "the unchanged call skips candidate and nested invocation work")
          (db/transact! connection [{:seon.cluster/name "cache-unrelated"}])
          (let [unrelated-captured (atom {})
                unrelated-output (target-call
                                   'seon.render
                                   'render-call
                                   (request
                                     (db/db connection)
                                     @second-captured
                                     unrelated-captured))]
            (is (= first-output unrelated-output))
            (is
              (=
                {:discoveries 1, :invocations 1}
                (counts))
              "an unrelated database revision retains the call")
            (db/transact! connection [{:seon.ns/name fixture-a, :seon.ns/doc "two"}])
            (let [relevant-captured (atom {})
                  relevant-output (target-call
                                    'seon.render
                                    'render-call
                                    (request
                                      (db/db connection)
                                      @unrelated-captured
                                      relevant-captured))]
              (is (= (str "A:" fixture-a ":two:H1") relevant-output))
              (is
                (=
                  {:discoveries 2, :invocations 2}
                  (counts))
                "a changed read dependency invalidates the call")
              (sci/binding
                [sci/ns (sci/create-ns fixture-a)]
                (sci/eval-form ctx '(defn cache-helper [] "H2")))
              (kernel/cache-function!
                ctx
                helper
                {:seon.sci.eval/function-private? true,
                 :seon.fn/source "(defn cache-helper [] \"H2\")"})
              (let [helper-captured (atom {})
                    helper-output (target-call
                                    'seon.render
                                    'render-call
                                    (request
                                      (db/db connection)
                                      @relevant-captured
                                      helper-captured))]
                (is (= (str "A:" fixture-a ":two:H2") helper-output))
                (is
                  (=
                    {:discoveries 3, :invocations 3}
                    (counts))
                  "a helper program change invalidates its caller")))))))))

(deftest
  nested-values-render-their-declared-faces
  (support/with-database
    (fn [connection]
      ;; An identity-less value nested under a registered attribute gets that
      ;; attribute's named schema (design §1): the report renders its pair.
      (support/transacted! connection
                           [(schema-entity connection :seon.render-simplification-test/report
                                        :seon.db/transaction-report)])
      (let [database (db/db connection)
            ctx (support/fork-cluster-ctx connection)
            report (db/transact! connection [])
            request (render-request
                      database
                      ctx
                      nil
                      {:probe/database database,
                       :seon.render-simplification-test/report report})
            ai (render-ai request)
            html (hiccup/->string (render-html request))]
        (is (str/includes? ai "database"))
        (is (str/includes? ai "basis transaction"))
        (is (not (str/includes? ai "#datahike.db.DB")))
        (doseq [rendered [ai html]]
          (is
            (str/includes?
              rendered
              (if (= rendered ai) "Wrote 0 facts on 0 entities." "Committed transaction")))
          (is (not (str/includes? rendered "datahike.db.TxReport"))))))))

(deftest overlapping-declared-returns-refuse-loudly-and-deterministically
  (support/with-database
   (fn [connection]
     (let [first-ai 'seon.render-simplification.fixture-ambiguous/first-ai
           second-ai 'seon.render-simplification.fixture-ambiguous/second-ai
           producer 'seon.render-simplification.fixture-ambiguous/subject]
       ;; Two declared return alternatives the same value satisfies, naming
       ;; different renderers: selection names both rather than choosing.
       (support/transacted!
        connection
        (vec (for [[schema-key renderer] [[:seon.render-simplification.fixture-ambiguous/first first-ai]
                                          [:seon.render-simplification.fixture-ambiguous/second second-ai]]]
               (schema-entity connection schema-key
                           [:map {:seon.render/ai renderer} [:seon.ns/name :seon.ns/name]]))))
       (support/transacted!
        connection
        [(support/program-fn-entity
          (db/db connection) producer
          (str "(defn subject {:malli/schema [:=> [:cat] [:or "
               ":seon.render-simplification.fixture-ambiguous/first "
               ":seon.render-simplification.fixture-ambiguous/second]]} [] {})"))])
       (let [database (db/db connection)
             ctx (support/fork-cluster-ctx connection)
             request (render-request database ctx fixture-ambiguous
                                     {:seon.ns/name fixture-ambiguous})
             result (render-ai request)
             decision (selection (assoc request :seon.render/output :seon.render/ai))
             expected #{(str first-ai) (str second-ai)}]
         (is (seq (:seon.render/candidates result)) (pr-str result))
         (is (seq (:seon.render/candidates (:seon.render.selection/selected decision))))
         (is (= :ambiguous
                (some #(when (= :declared (:seon.render.selection.stage/name %))
                         (:seon.render.selection.stage/status %))
                      (:seon.render.selection/stages decision))))
         (is (= expected (set (map str (:seon.render/candidates (:seon.error/data result))))))
         (is (= (sort expected) (map str (:seon.render/candidates (:seon.error/data result))))))))))

(deftest renderer-invocation-is-sci-only-and-live-var-backed
  (support/with-database
   (fn [connection]
     (declare-fixture-subjects! connection)
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           request (render-request database ctx fixture-b
                                   {:seon.ns/name fixture-b})]
       ;; Acquisition is not invocation: acquire the program the value holds
       ;; first, so the trap observes only the renderer's invocation.
       (eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db database})
       (with-redefs [clojure.core/requiring-resolve
                     (fn [& _]
                       (throw (ex-info "compiled resolver trap" {})))]
         (is (= (str "B:" fixture-b) (render-ai request))))
       (sci/binding [sci/ns (sci/create-ns fixture-b)]
         (sci/eval-form
          ctx
          '(defn namespace-ai [value]
             (str "B2:" (:seon.ns/name value)))))
       (is (= (str "B2:" fixture-b) (render-ai request))
           "the declared pair invokes the live SCI Var")
       (sci/binding [sci/ns (sci/create-ns fixture-b)]
         (sci/eval-form
          ctx
          '(defn holes-html [_value]
             [:div (seon.render.hiccup/raw
                    "<script>raw-agent-bytes</script>")])))
       (let [raw-result (render-html request)]
         (is (hiccup/hiccup? raw-result))
         (is (not (hiccup/raw? (get-in raw-result [1]))))
         (is (str/includes? (hiccup/->string raw-result)
                            "&lt;script&gt;raw-agent-bytes&lt;/script&gt;"))
         (is (not (str/includes? (hiccup/->string raw-result)
                                 "<script>raw-agent-bytes</script>"))))))))

(deftest cold-context-reacquires-the-same-entity
  (support/with-database
   (fn [connection]
     (declare-fixture-subjects! connection)
     (let [database (db/db connection)
           request (fn [ctx]
                     (render-request database ctx fixture-b
                                     {:seon.ns/name fixture-b}))]
       (is (= (str "B:" fixture-b)
              (render-ai (request (support/fork-cluster-ctx connection)))))
       (is (= (str "B:" fixture-b)
              (render-ai (request (support/fork-cluster-ctx connection)))))))))

(deftest an-agent-neighborhood-renders-its-database-reading-units
  ;; The system turn opens from this neighborhood (`seon.turn/declared-sources`);
  ;; a unit renderer reading `(:seon.db/db unit)` (runtime owner, saved shown
  ;; text) refused a nil database and every turn open ended `:error`.
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "neighborhood-owner"))
      (let [units (walk/neighborhood
                    {:seon.db/db (db/db connection),
                     :seon.sci.eval/ctx (support/fork-cluster-ctx connection),
                     :seon.render.walk/lookup [:seon.agent/id "neighborhood-owner"],
                     :seon.render/output :seon.render/ai,
                     :seon.sci.admit/caps caps,
                     :seon.sci.eval/time-limit-ms 2000})]
        (is (seq units))
        (is (empty? (keep :seon.error/value units)) (pr-str (keep :seon.error/value units)))
        (is (some #(str/includes? (str (:seon.render/output %)) "\"neighborhood-owner\"") units)
            "the runtime unit names its owner, read through the unit's database")))))

(deftest
  distance-spends-only-real-ref-hops-and-caps-win
  (support/with-database
    (fn [connection]
      ;; `:seon.ns/requires` holds namespace symbols; the walk hops by name.
      (support/transacted!
        connection
        [[:db/add [:seon.ns/name fixture-a] :seon.ns/requires fixture-b]])
      (let [database (db/db connection)
            ctx (support/fork-cluster-ctx connection)
            request {:seon.db/db database,
                     :seon.sci.eval/ctx ctx,
                     :seon.render.walk/lookup [:seon.ns/name fixture-a],
                     :seon.render/output :seon.render/ai,
                     :seon.sci.admit/caps caps,
                     :seon.sci.eval/time-limit-ms 2000}
            at-zero (flat-units (walk/neighborhood (assoc request :seon.render/distance 0)))
            at-one (flat-units (walk/neighborhood (assoc request :seon.render/distance 1)))
            capped (flat-units
                     (walk/neighborhood
                       (assoc
                         request
                         :seon.render/distance
                         4
                         :seon.sci.admit/caps
                         (assoc caps :seon.config.eval.result/max-nodes 1))))]
        (is (= #{[:seon.ns/name fixture-a]} (set (map :seon.render.walk/lookup at-zero))))
        (is (contains? (set (map :seon.render.walk/lookup at-one)) [:seon.ns/name fixture-b]))
        (is (= #{[:seon.ns/name fixture-a]} (set (map :seon.render.walk/lookup capped))))
        (is
          (true? (get-in (first capped) [:seon.error/value :seon.db/invalid-read])))
        (is
          (=
            {:datahike/budget-exceeded true,
             :datahike.budget/name :query-work,
             :datahike.budget/allowed 1,
             :datahike.budget/observed 2}
            (get-in
              (first capped)
              [:seon.error/value :seon.error/data :seon.db/dependency-data])))))))

(deftest old-slot-and-ref-markers-are-inert-renderer-output
  (support/with-database
   (fn [connection]
     (declare-fixture-subjects! connection)
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           expected [:section {:data-slot "inert"}
                     [:span {:data-ref "[:db/id 7]"} "also inert"]]
           result
           (render-html (render-request database ctx fixture-b {:seon.ns/name fixture-b}))]
       (is (= expected result))
       (is (hiccup/hiccup? result))))))

(deftest settled-package-is-reused-by-every-join
  ;; A package is keyed by `:seon.render/surface-id`, which is at least nine
  ;; characters, so the fixture uses a real one rather than a short label.
  (let [surface-id "block-one"
        keyframe "<article id=\"block-one\">one</article>"
        keyframe-bytes (.getBytes keyframe "UTF-8")
        package {:seon.render.package/revision 7
                 :seon.render.package/base-revision 6
                 :seon.render.package/basis-transaction 1
                 :seon.render.package/streaming? false
                 :seon.render.package/keyframe {surface-id keyframe}
                 :seon.render.package/keyframe-bytes keyframe-bytes
                 :seon.render.package/keyframe-size (alength keyframe-bytes)
                 :seon.render.package/delta {surface-id keyframe}
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
     (declare-fixture-subjects! connection)
     (let [ctx (support/fork-cluster-ctx connection)
           invoke kernel/invoke
           invocations (atom 0)
           request (fn [call-id retained captured]
                     (assoc (render-request (db/db connection) ctx fixture-a
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
                   (:seon.ns/name value))))))
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

(deftest
  authored-source-invocation-reuses-one-stored-run-across-presentations
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "source-cache")
      (db/transact!
        connection
        (agent/creation-tx
          ;; Its own namespace: fixture-a's renderers would be this viewer's
          ;; defaults and outrank the subject's declared pair.
          {:seon.agent/id "source-cache-agent",
           :seon.ns/name 'my.agents.source-cache,
           :seon.cluster/name "source-cache"}))
      (declare-subject! connection fixture-a
                        {:seon.render/ai 'seon.render-simplification-test/authored-source})
      (let [database (db/db connection)
            ctx (support/fork-cluster-ctx connection)
            handle (support/cluster-handle
                     {:seon.db/connection connection,
                      :seon.cluster/name "source-cache",
                      :seon.db.process/id seon.cluster/boot-process-identity,
                      :seon.sci.eval/ctx ctx})
            previews (atom 0)
            preview seon.turn/preview-sources
            invoke #'seon.render.web/render-source-call
            request (fn [id invocations captured calls]
                      (assoc
                        (render-request database ctx fixture-a {:seon.ns/name fixture-a})
                        :seon.render/output
                        :seon.render/ai
                        :seon.render.call/id
                        id
                        :seon.render/retained-calls
                        {}
                        :seon.render/captured-calls
                        calls
                        :seon.render/invocations
                        invocations
                        :seon.render/captured-invocations
                        captured
                        :seon.agent/id
                        "source-cache-agent"
                        :seon.turn.loop/cluster
                        handle))]
        (try
          (with-redefs
            [seon.turn/preview-sources (fn [request] (swap! previews inc) (preview request))]
            (let [first-invocations (atom {})
                  first-calls (atom {})
                  first-output (invoke (request [:debug] {} first-invocations first-calls))
                  second-invocations (atom {})
                  second-calls (atom {})
                  second-output (invoke
                                  (request
                                    [:context]
                                    @first-invocations
                                    second-invocations
                                    second-calls))]
              (is (string? first-output) (pr-str first-output))
              (is (str/includes? first-output "2"))
              (is (= first-output second-output))
              (is (= 1 @previews) "both presentations reuse one real preview")
              (is
                (= (db/basis-t database) (db/basis-t (db/db connection)))
                "previewing does not write a turn or evaluation")
              (is
                (=
                  (get-in @first-calls [[:debug] :seon.render.call/source-run-id])
                  (get-in @second-calls [[:context] :seon.render.call/source-run-id])))
              (is (seq @first-invocations))))
          (finally
            (doseq [key [:seon.cluster.wake/channel
                         :seon.render/context-channel
                         :seon.turn.loop/completion]]
              (clojure.core.async/close! (get handle key)))))))))

(deftest a-program-change-replaces-a-private-definition-and-says-so
  ;; Root ruling 2026-09-25: a context's own definition survives its symbol's
  ;; first lazy install, but a changed program entity advances in place over it
  ;; at the next idle boundary, and the evaluation says so.
  (support/with-database
   (fn [connection]
     (declare-fixture-subjects! connection)
     (let [ctx (support/fork-cluster-ctx connection)
           renderer 'seon.render-simplification.fixture-a/namespace-ai
           render #(target-call 'seon.render 'render-call
                                (assoc (render-request (db/db connection) ctx fixture-a
                                                       {:seon.ns/name fixture-a})
                                       :seon.render/output :seon.render/ai))]
       (sci/binding [sci/ns (sci/create-ns fixture-a)]
         (sci/eval-form ctx '(defn namespace-ai [value] (str "private:" (:seon.ns/name value)))))
       (is (= (str "private:" fixture-a) (render)) "the first lazy install keeps it")
       (support/transacted!
        connection
        [(support/revision (db/db connection) [:seon.fn/sym renderer]
                           {:seon.fn/source "(defn namespace-ai [value] (str \"program:\" (:seon.ns/name value)))"})])
       (let [evaluation (eval/evaluate
                         {:seon.sci.eval/ctx ctx
                          :seon.db/db (db/db connection)
                          :seon.cluster.eval/ns [:seon.ns/name fixture-a]
                          :seon.cluster.eval/source "1"
                          :seon.sci.admit/caps caps
                          :seon.sci.eval/time-limit-ms 2000})]
         (is (str/includes? (pr-str evaluation)
                            (str "PROGRAM CHANGE replaced your definition of " renderer))
             (pr-str evaluation)))
       (is (= (str "program:" fixture-a) (render)) "the changed program entity runs")))))

(deftest generic-renderer-receives-the-acquired-entity-id
  (support/with-database
   (fn [connection]
     (db/transact! connection [{:seon.ns/name fixture-a
                                :seon.ns/doc "identity input"}])
     (declare-fixture-subjects! connection)
     (let [database (db/db connection)
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
       {:seon.agent/id "owner-b"
        :seon.cluster/name "render-failure"
        :seon.ns/name fixture-b}))
     (let [failure {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.render/render
                    :seon.error/operation 'seon.render/render-call
                    :seon.error/message "secret stack and renderer symbol"
                    :seon.error/data
                    {:seon.fn/sym
                     'seon.render-simplification.fixture-b/holes-html}}
           outcome
           (target-call
            'seon.render 'renderer-failure
            {:seon.db/db (db/db connection)
             :seon.render/namespace fixture-b
             :seon.error/value failure})]
       (db/transact! connection (:seon.db/tx-data outcome))
       (let [html (:seon.render/html outcome)
             browser (str html)
             messages
             (db/q '[:find [?content ...]
                    :in $ ?owner
                    :where
                    [?agent :seon.agent/id ?owner]
                    [?message :seon.message/to ?agent]
                    [?message :seon.message/content ?content]]
                  (db/db connection) "owner-b")]
         (is (str/includes? browser "unavailable"))
         (is (not (str/includes? browser "secret stack")))
         (is (not (str/includes? browser "holes-html")))
         (is (= 1 (count messages)))
         (is (boolean
              (some #(str/includes? % "renderer") messages))))))))

(deftest a-refused-database-read-is-not-an-absent-database
  ;; THE CLASS: an invalid database read fell through to the request's or
  ;; context's projection, so a render answered against another program.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           refused (db/q '[:find ?e . :where [?e :seon.render-simplification-test/absent _]]
                         database)
           thrown (try (seon.render/request-projection
                        {:seon.db/db refused
                         :seon.schema/projection (db/carried-projection database)})
                       nil
                       (catch clojure.lang.ExceptionInfo error (ex-data error)))]
       (is (true? (:seon.db/invalid-read refused)) (pr-str refused))
       (is (= refused thrown) "the refused read itself is the refusal")
       (is (identical? (db/carried-projection database)
                       (seon.render/request-projection {:seon.db/db database})))
       (is (nil? (seon.render/request-projection {}))
           "no database, projection or context is nothing declared")))))

(deftest a-supplied-projection-is-the-one-a-render-asks
  ;; THE CLASS: evaluation used the projection it held while its render chose
  ;; the database's, so one request answered with two programs. Evaluation
  ;; now hands its projection, and a supplied projection wins.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           supplied (assoc (db/carried-projection database)
                           ::supplied true)]
       (is (identical? supplied
                       (seon.render/request-projection
                        {:seon.db/db database :seon.schema/projection supplied})))))))

(deftest an-evaluation-renders-with-the-profile-its-caller-names
  ;; THE CLASS: a request naming its cluster only on `:seon.boot/cluster-name`
  ;; got a refused profile, which the result's render then used AS a profile.
  ;; The caller's name wins; a request naming none is refused, never rendered.
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           cluster-name (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database)
           named (seon.render/request-profile
                  {:seon.db/db database :seon.boot/cluster-name cluster-name})
           unnamed (seon.render/request-profile {:seon.db/db database})
           evaluation (eval/evaluate
                       {:seon.cluster.eval/source "(inc 1)"
                        :seon.sci.eval/ctx (support/fork-cluster-ctx connection nil)
                        :seon.db/db database
                        :seon.boot/cluster-name cluster-name
                        :seon.sci.admit/caps caps
                        :seon.sci.eval/time-limit-ms 2000})]
       (is (string? cluster-name))
       (is (keyword? (:seon.render.profile/id named)) (pr-str named))
       (is (= :seon.render/profile (:seon.render/refused-member unnamed)))
       (is (= :seon.boot/cluster-name (:seon.error/member unnamed)))
       (is (= "2" (:seon.eval/shown evaluation)) (pr-str evaluation))))))

(deftest a-carried-partial-profile-is-refused-at-request-profile
  ;; THE CLASS: a request carrying a profile missing its declared keys passed
  ;; through request-profile and failed deep in seon.error/prepare-result and
  ;; seon.print/fit. The entry refuses it by the member that carried it; a
  ;; complete profile and a named refusal still pass unchanged.
  (let [profile @@#'seon.render/default-agent-profile
        refusal (try (seon.render/request-profile
                      {:seon.render/profile {:seon.render.profile/token-budget 10}})
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (identical? profile (seon.render/request-profile {:seon.render/profile profile})))
    (is (= :input (:seon.instrument/check refusal)) (pr-str refusal))
    (is (= 'seon.render/request-profile (:seon.error/operation refusal)))
    (is (some #(= [:seon.render/profile :seon.render.profile/id] (:seon.error/path %))
              (:seon.error/problems refusal))
        (pr-str (:seon.error/problems refusal)))))
