(ns seon.schema-test
  "CLJS-side tests for seon.schema's register! gates.

   Pins the single-segment keyword-namespace gate (paid evaluation finding,
   2026-06-10): `:workout/date` landed beside
   the established `:my.workout/date` despite the teaching banning
   it — register! now refuses single-segment namespaces with a guiding
   error (the established register! failure mode: a thrown
   `:user-input` ex-info, surfaced to agents as an error envelope).

   Also pins the `{:seon.db/entity true}` marker (user decision
   2026-06-10): entity-kind-ness is DECLARED, never inferred — only
   marked :map schemas derive `:seon.entity/id-attr` and enter the
   catalog; an unmarked map carrying an identity-attr entry registers
   SILENTLY. The behavioral nudge lives in
   seon.warn/check-unmarked-entity-kinds (fires where rows exist).

   Run via bin/test-cljs."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    [malli.core :as m]
    [malli.registry :as mr]
    [seon.agent]
    [my.plan]
    [seon.schema :as schema]
    [seon.test.runner]))

(defn- unregister! [& ks]
  (schema/restore! (apply dissoc (schema/snapshot) ks)))

(defn- candidate-catalog []
  (:seon.schema.projection/catalog
    (schema/build-projection (schema/snapshot))))

(deftest activation-publishes-one-state-through-a-stable-default-registry
  (let [before (schema/snapshot-state)
        attr :schematest.publication/value
        setter-calls (atom 0)]
    (try
      (schema/relink-registry!)
      (let [string-projection
            (schema/build-projection (assoc (schema/snapshot) attr :string))]
        (schema/activate-projection! string-projection)
        (schema/register! attr :int)
        (testing "candidate validation is explicit and does not leak to default"
          (is (true? (schema/valid-candidate-value? attr 1)))
          (is (false? (schema/valid-candidate-value? attr "active")))
          (is (true? (m/validate attr "active")))
          (is (false? (m/validate attr 1))))
        (let [int-projection (schema/build-projection (schema/snapshot))]
          (with-redefs [mr/set-default-registry!
                        (fn [_]
                          (swap! setter-calls inc)
                          true)]
            (schema/activate-projection! int-projection))
          (testing "normal activation is one Seon-state publication"
            (is (zero? @setter-calls))
            (is (identical? int-projection (schema/current-projection)))
            (is (identical? (:seon.schema.projection/forms int-projection)
                            (schema/snapshot))))
          (testing "the same installed default facade follows the active state"
            (is (true? (m/validate attr 1)))
            (is (false? (m/validate attr "retired"))))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest projection-derives-exact-transitive-schema-dependencies
  (let [forms {:schematest.dependency/leaf :int
               :schematest.dependency/branch
               [:vector :schematest.dependency/leaf]
               :schematest.dependency/root
               [:schema
                {:registry
                 {:schematest.dependency/local
                  [:tuple :schematest.dependency/branch]}}
                :schematest.dependency/local]
               :schematest.dependency/unrelated
               [:enum :schematest.dependency/leaf]}
        function-form [:=> [:cat :schematest.dependency/root] :boolean]
        function-sym 'schematest.dependency/use-root
        projection (schema/build-projection forms
                                            {function-sym function-form})]
    (testing "Malli's schema walk follows local refs and records canonical refs"
      (is (= #{:schematest.dependency/branch}
             (get-in projection
                     [:seon.schema.projection/schema-dependencies
                      :schematest.dependency/root])))
      (is (= #{:schematest.dependency/leaf}
             (get-in projection
                     [:seon.schema.projection/schema-dependencies
                      :schematest.dependency/branch]))))
    (testing "keyword enum values are data, not false dependency edges"
      (is (= #{}
             (get-in projection
                     [:seon.schema.projection/schema-dependencies
                      :schematest.dependency/unrelated]))))
    (testing "reverse closure includes the changed key and only real dependents"
      (is (= #{:schematest.dependency/leaf
               :schematest.dependency/branch
               :schematest.dependency/root}
             (schema/dependent-schema-keys
               projection #{:schematest.dependency/leaf}))))
    (testing "function forms use the same exact reference mechanism"
      (is (= #{:schematest.dependency/root}
             (schema/direct-references
               projection function-form)))
      (is (= #{:schematest.dependency/root}
             (get-in projection
                     [:seon.schema.projection/function-dependencies
                      function-sym]))))))

(deftest single-segment-keyword-namespace-is-refused-with-guidance
  (testing "the S-21 defect shape — :workout/date"
    (let [e (try (schema/register! :workout/date :string)
                 nil
                 (catch :default e e))]
      (is (some? e) "register! must throw, not register")
      (is (not (schema/registered? :workout/date))
          "nothing landed in the registry")
      (is (= :seon.schema/single-segment-namespace
             (:seon.schema/error (ex-data e))))
      (is (= :user-input (:seon.error/kind (ex-data e)))
          "agent-input error kind — the established register! failure mode")
      (is (re-find #":\w+\.\w+/\w+" (ex-message e))
          "the error GUIDES: names a corrected multi-segment keyword example")
      (is (str/includes? (ex-message e) "inspect the installed schema")
          "the error teaches reuse-first discovery before registering"))))

(deftest nilable-value-schema-is-refused-with-guidance
  ;; Live-drive finding 2026-07-13: a Muse agent ran
  ;; (register! :my.reading/rating [:maybe :int]) and it returned ok — a
  ;; "false ok". seon bans nilable value schemas (a stored value is never
  ;; nil; absent = the key is simply omitted). register! now refuses a
  ;; top-level [:maybe <builtin>] with a guiding :user-input ex-info.
  (testing "the smell shape — [:maybe :int]"
    (let [e (try (schema/register! :my.reading/rating [:maybe :int])
                 nil
                 (catch :default e e))]
      (is (some? e) "register! must throw, not register")
      (is (not (schema/registered? :my.reading/rating))
          "nothing landed in the registry (throw precedes the swap!)")
      (is (= :seon.schema/nilable-value-schema
             (:seon.schema/error (ex-data e))))
      (is (= :user-input (:seon.error/kind (ex-data e)))
          "agent-input error kind — surfaced to agents as an error envelope")
      (is (str/includes? (ex-message e) "(schema/register! :my.reading/rating :int)")
          "the error GUIDES: names the copy-pasteable base-type registration")
      (is (str/includes? (ex-message e) "{:optional true}")
          "the error teaches the fix: mark the FIELD optional, don't store nil")))
  (testing "a :maybe around a REGISTERED domain type is a nullable fn-slot — allowed"
    ;; [:maybe ::registered] and [:maybe [:or …]] are deliberate nullable
    ;; return/arg schemas (e.g. :my.plan/tree-response); only a :maybe around
    ;; a raw builtin is the mis-modeled-attr case register! rejects.
    (schema/register! :schematest.slot/base :string)
    (is (= :schematest.slot/opt
           (schema/register! :schematest.slot/opt [:maybe :schematest.slot/base])))
    (unregister! :schematest.slot/opt :schematest.slot/base))
  (testing "the base type still registers cleanly"
    (is (= :my.reading/rating (schema/register! :my.reading/rating :int)))
    (unregister! :my.reading/rating)))

(deftest multi-segment-and-bare-keys-still-register
  (testing "multi-segment data domain"
    (is (= :schematest.workout/date
           (schema/register! :schematest.workout/date :string))))
  (testing "seon.* core-style two-segment namespace"
    (is (= :schematest.gate/ok?
           (schema/register! :schematest.gate/ok? :boolean))))
  (testing "un-namespaced entity-kind keys (the :seon.agent.message shape)"
    (is (= :schematest.kind
           (schema/register! :schematest.kind
                             [:map [:schematest.workout/date
                                    :schematest.workout/date]]))))
  (unregister! :schematest.workout/date :schematest.gate/ok?
               :schematest.kind))

;; --- {:seon.db/entity true} — declared entity kinds (P2/#11) ---

(deftest declared-entity-keeps-canonical-form-and-derives-catalog-entry
  (schema/register! :schematest.entity/id [:string {:seon.db/identity true}])
  (schema/register! :schematest.entity/label :string)
  (schema/register! :schematest.entity
    [:map {:seon.db/entity true}
     [:schematest.entity/id    :schematest.entity/id]
     [:schematest.entity/label :schematest.entity/label]])
  (testing "canonical form preserves only authored properties"
    (let [props (second (schema/schema-definition :schematest.entity))]
      (is (= true (:seon.db/entity props)) "declared marker preserved")
      (is (not (contains? props :seon.entity/id-attr))
          "derived catalog metadata is not written into the canonical form")))
  (testing "declared kind enters the catalog"
    (let [row (some #(when (= :schematest.entity
                              (:seon.schema.catalog/key %)) %)
                    (candidate-catalog))]
      (is (some? row))
      (is (= :schematest.entity/id
             (:seon.schema.catalog/id-attr row))
          "the disposable catalog derives the identity attribute")))
  (unregister! :schematest.entity/id :schematest.entity/label
               :schematest.entity))

(deftest unmarked-map-with-id-key-does-not-derive-and-is-silent
  ;; The old register!-time warn was a false-positive generator by
  ;; construction: at registration an id-carrying map is
  ;; indistinguishable between unmarked-entity and legitimate envelope.
  ;; register! is now SILENT on this concern; the BEHAVIORAL nudge
  ;; lives in seon.warn/check-unmarked-entity-kinds, which fires only
  ;; where rows actually exist (see seon.warn-test).
  (schema/register! :schematest.envelope/id [:string {:seon.db/identity true}])
  (testing "the envelope shape — id entry, NO marker — registers silently"
    (let [form  [:map [:schematest.envelope/id :schematest.envelope/id]]
          warns (atom [])
          orig  (.-warn js/console)]
      (set! (.-warn js/console) (fn [& args] (swap! warns conj (vec args))))
      (try
        (schema/register! :schematest.envelope/lookup-shape form)
        (finally (set! (.-warn js/console) orig)))
      (is (empty? @warns)
          "register! emits NO warning — the nudge lives where rows exist"))
    (is (schema/registered? :schematest.envelope/lookup-shape)
        "the registration lands")
    (is (nil? (:seon.entity/id-attr
                (second (schema/schema-definition
                          :schematest.envelope/lookup-shape))))
        "no id-attr derived — entity-kind-ness is declared, not inferred")
    (is (not (some #(= :schematest.envelope/lookup-shape
                        (:seon.schema.catalog/key %))
                   (candidate-catalog)))
        "unmarked map never enters the catalog"))
  (unregister! :schematest.envelope/id
               :schematest.envelope/lookup-shape))

(deftest catalog-surfaces-only-declared-kinds
  (let [kinds (into #{} (map :seon.schema.catalog/key)
                    (schema/entity-catalog))]
    (testing "the genuine entity kinds are declared and present"
      (doseq [k [:seon.agent :seon.eval :seon.agent.message :seon.fn
                 :seon.ns :seon.schema :my.plan/step :seon.test]]
        (is (contains? kinds k) (str k " is a declared entity kind"))))
    (testing "the 8 formerly-phantom request/response wrappers are gone"
      ;; `reopen-request`/`complete-request` were consolidated into the single
      ;; `::id-request` in Phase 6a; the invariant (a request/response :map is
      ;; never an entity kind) holds for the LIVE wrappers that replaced them.
      (doseq [k [:my.plan/write-response
                 :my.plan/status-response
                 :my.plan/id-request
                 :seon.agent.ctx/render-namespace-request
                 :seon.handler/input
                 :seon.agent.debug/request
                 :seon.render/assemble-request
                 :seon.effect/wake-request]]
        (is (not (contains? kinds k)) (str k " must NOT be a kind"))))))
