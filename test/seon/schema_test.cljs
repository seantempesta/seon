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
    [seon.agent.lifecycle]
    [seon.eval :as seval]
    [seon.handlers.eval]
    [seon.handlers.fn]
    [seon.handlers.message]
    [seon.handlers.ns]
    [seon.handlers.schema]
    [seon.handlers.test]
    [my.plan]
    [seon.schema :as schema]
    [seon.test.runner]))

(defn- unregister! [& ks]
  (schema/restore! (apply dissoc (schema/snapshot) ks)))

(defn- candidate-catalog []
  (:seon.schema.projection/catalog
    (schema/build-projection (schema/snapshot))))

(deftype DiagnosticCountingMap [n visits poison-touches]
  IMap
  (-dissoc [this _] this)
  ISeqable
  (-seq [_]
    (letfn [(entries [i]
              (lazy-seq
                (when (< i n)
                  (swap! visits inc)
                  (when (= i schema/shape-input-key-limit)
                    (swap! poison-touches inc)
                    (throw (js/Error. "poison beyond diagnostic key budget")))
                  (cons [(keyword "schematest.input" (str "key-" i)) i]
                        (entries (inc i))))))]
      (entries 0))))

(deftest every-registered-render-handler-resolves
  (let [handlers
        (for [entry (candidate-catalog)
              [channel handler]
              [[:seon.render/ai :seon.schema.catalog/render-ai]
               [:seon.render/html :seon.schema.catalog/render-html]]
              :let [handler-symbol (get entry handler)]
              :when handler-symbol]
          {:seon.schema.catalog/key
           (:seon.schema.catalog/key entry)
           :seon.render/channel channel
           :seon.render/handler handler-symbol})]
    (is (seq handlers)
        "the entity catalog must expose registered render handlers")
    (doseq [{:seon.schema.catalog/keys [key]
             :seon.render/keys [channel handler]} handlers]
      (testing (str key " " channel " " handler)
        (is (qualified-symbol? handler)
            "the registered handler must be qualified symbol data")
        (is (fn? (seval/lookup-value handler))
            "every registered render handler must resolve to a function")))))

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
          (is (false? (m/validate attr 1)))
          (let [validator (schema/candidate-validator attr)
                explainer (schema/candidate-explainer attr)]
            (is (true? (validator 1)))
            (is (false? (validator "active")))
            (is (map? (explainer "active")))))
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

(deftest shape-matching-reads-only-the-activated-projection
  (let [before (schema/snapshot-state)
        attr :schematest.shape/value
        shape :schematest.shape/string-map
        string-forms {attr :string
                      shape [:map [attr attr]]}]
    (try
      (let [active (schema/build-projection string-forms)]
        (schema/activate-projection! active)
        (schema/register! attr :int)
        (testing "unactivated candidate declarations cannot change matching"
          (is (= [shape]
                 (mapv :seon.schema/key
                       (schema/matching-shapes {attr "active"}))))
          (is (empty? (schema/matching-shapes {attr 1}))))
        (schema/restore! string-forms)
        (testing "candidate restoration also leaves the active generation alone"
          (is (= [shape]
                 (mapv :seon.schema/key
                       (schema/matching-shapes {attr "still-active"}))))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest equal-fingerprint-projection-replacement-rotates-validator-generation
  (let [before (schema/snapshot-state)
        attr :schematest.rotation/value
        shape :schematest.rotation/map
        forms {attr :string shape [:map [attr attr]]}
        first-projection (schema/build-projection forms)
        second-projection (schema/build-projection forms)
        compiled-against (atom [])]
    (try
      (is (= (:seon.schema.projection/fingerprint first-projection)
             (:seon.schema.projection/fingerprint second-projection)))
      (is (not (identical? first-projection second-projection)))
      (with-redefs [schema/projection-validator
                    (fn [projection _schema-key]
                      (swap! compiled-against conj projection)
                      (constantly true))]
        (schema/activate-projection! first-projection)
        (schema/matching-shapes {attr "one"})
        (schema/activate-projection! second-projection)
        (schema/matching-shapes {attr "two"}))
      (is (= 2 (count @compiled-against)))
      (is (identical? first-projection (first @compiled-against)))
      (is (identical? second-projection (second @compiled-against)))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest shape-explanation-shares-the-activated-generation
  (let [before (schema/snapshot-state)
        attr :schematest.explanation/value
        shape :schematest.explanation/map
        forms {attr :string shape [:map [attr attr]]}
        first-projection (schema/build-projection forms)
        second-projection (schema/build-projection forms)
        compiled-against (atom [])]
    (try
      (schema/activate-projection! first-projection)
      (is (nil? (schema/explain-shape shape {attr "valid"})))
      (is (map? (schema/explain-shape shape {attr 1})))
      (schema/register! attr :int)
      (testing "candidate mutation cannot change active explanation"
        (is (nil? (schema/explain-shape shape {attr "still-valid"})))
        (is (map? (schema/explain-shape shape {attr 2}))))
      (with-redefs [schema/projection-explainer
                    (fn [projection _schema-key]
                      (swap! compiled-against conj projection)
                      (constantly {:errors [:instrumented]}))]
        (schema/activate-projection! second-projection)
        (is (= {:errors [:instrumented]}
               (schema/explain-shape shape {attr 3}))))
      (is (= 1 (count @compiled-against)))
      (is (identical? second-projection (first @compiled-against)))
      (testing "a non-shape key is a caller defect, not candidate fallback"
        (let [error (try
                      (schema/explain-shape attr "value")
                      nil
                      (catch :default error error))]
          (is (= :seon.schema/unknown-shape
                 (:seon.schema/error (ex-data error))))
          (is (= :core-bug (:seon.error/kind (ex-data error))))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest shape-candidates-and-matches-have-distinct-honest-contracts
  (let [before (schema/snapshot-state)
        a :schematest.ambiguity/a
        b :schematest.ambiguity/b
        alpha :schematest.ambiguity/alpha
        beta :schematest.ambiguity/beta
        specific :schematest.ambiguity/specific
        forms {a :string
               b :int
               alpha [:map [a a]]
               beta [:map [a a]]
               specific [:map [a a] [b b]]}]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (testing "all valid open-map matches survive in specificity order"
        (is (= [specific alpha beta]
               (mapv :seon.schema/key
                     (schema/matching-shapes
                       {a "ok" b 7 :schematest.ambiguity/extra true})))))
      (testing "wrong types remain structural candidates, never matches"
        (is (= [specific alpha beta]
               (mapv :seon.schema/key
                     (schema/candidate-shapes {a 42 b "wrong"}))))
        (is (empty? (schema/matching-shapes {a 42 b "wrong"}))))
      (testing "a missing required key remains diagnostic only"
        (is (= [specific alpha beta]
               (mapv :seon.schema/key
                     (schema/candidate-shapes {a "partial"}))))
        (is (= [alpha beta]
               (mapv :seon.schema/key
                     (schema/matching-shapes {a "partial"})))))
      (testing "no indexed-key overlap is the ordinary empty state"
        (is (= [] (schema/candidate-shapes
                    {:schematest.ambiguity/unrelated true})))
        (is (= [] (schema/matching-shapes
                    {:schematest.ambiguity/unrelated true}))))
      (testing "structural rows cannot claim validity or explanation"
        (let [row (first (schema/candidate-shapes
                           {a "partial"
                            :seon.render.value/elided true}))]
          (is (not (contains? row :seon.schema/valid?)))
          (is (not (contains? row :seon.schema/explanation)))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest diagnostic-candidate-work-is-bounded-before-retention
  (let [before (schema/snapshot-state)
        shared :schematest.bound/shared
        shapes (into {}
                     (map (fn [i]
                            [(keyword "schematest.bound" (str "shape-" i))
                             [:map [shared shared]]]))
                     (range 400))
        forms (assoc shapes shared :string)
        visits (atom [])]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (let [rows (binding [schema/*candidate-visit!*
                           (fn [schema-key]
                             (swap! visits conj schema-key))]
                   (schema/candidate-shapes
                     {shared "value"}))]
        (is (= schema/shape-candidate-limit (count @visits))
            "instrumented index visits, not only retained output, are capped")
        (is (= schema/shape-candidate-limit (count rows)))
        (is (= (sort-by (juxt (comp - count
                                   :seon.schema/required-attrs)
                              (comp str :seon.schema/key))
                       rows)
               rows)))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest diagnostic-input-key-work-is-bounded-before-sorting
  (let [before (schema/snapshot-state)
        first-key :schematest.input/key-0
        shape :schematest.input/shape
        forms {first-key :int
               shape [:map [first-key first-key]]}
        entry-visits (atom 0)
        poison-touches (atom 0)
        value (DiagnosticCountingMap. 1000000 entry-visits poison-touches)]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (is (= [shape]
             (mapv :seon.schema/key (schema/candidate-shapes value))))
      (is (= schema/shape-input-key-limit @entry-visits)
          "the map walk stops before sorting or schema-index lookup")
      (is (zero? @poison-touches)
          "the first entry beyond the diagnostic window is never realized")
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest diagnostic-window-is-construction-order-deterministic
  (let [before (schema/snapshot-state)
        attrs (mapv #(keyword "schematest.determinism.attr" (str "k-" %))
                    (range 40))
        shape-key (fn [attr]
                    (keyword "schematest.determinism.shape" (name attr)))
        forms (into {}
                    (mapcat (fn [attr]
                              [[attr :int]
                               [(shape-key attr) [:map [attr attr]]]]))
                    attrs)
        entries (mapv vector attrs (range 40))
        forward (into {} entries)
        reverse-order (into {} (reverse entries))
        small-entries (subvec entries 0 6)
        small-forward (into (array-map) small-entries)
        small-reverse (into (array-map) (reverse small-entries))]
    (try
      (schema/activate-projection! (schema/build-projection forms))
      (testing "large equal persistent maps produce byte-identical row order"
        (let [a (schema/candidate-shapes forward)
              b (schema/candidate-shapes reverse-order)]
          (is (= forward reverse-order))
          (is (= a b))
          (is (= (pr-str a) (pr-str b)))))
      (testing "small array maps remain deterministic because all keys fit"
        (let [a (schema/candidate-shapes small-forward)
              b (schema/candidate-shapes small-reverse)]
          (is (= small-forward small-reverse))
          (is (= a b))
          (is (= (pr-str a) (pr-str b)))))
      (finally
        (schema/restore-state! before)
        (schema/relink-registry!)))))

(deftest shape-rows-preserve-authored-render-and-derived-identity-metadata
  (let [id :schematest.metadata/id
        label :schematest.metadata/label
        request :schematest.metadata/request
        entity :schematest.metadata/entity
        forms {id [:string {:seon.db/identity true}]
               label :string
               request [:map {:seon.render/html 'schematest.metadata/request-html}
                        [label label]]
               entity [:map {:seon.db/entity true
                             :seon.render/ai 'schematest.metadata/entity-ai}
                       [id id]
                       [label label]]}
        projection (schema/build-projection forms)
        rows (:seon.schema.projection/shape-rows projection)]
    (is (= 'schematest.metadata/request-html
           (get-in rows [request :seon.render/html])))
    (is (false? (get-in rows [request :seon.schema/entity?])))
    (is (= 'schematest.metadata/entity-ai
           (get-in rows [entity :seon.render/ai])))
    (is (true? (get-in rows [entity :seon.schema/entity?])))
    (is (= id (get-in rows [entity :seon.entity/id-attr])))))

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

(deftest projection-validation-is-independent-of-declaration-order
  (let [before (schema/snapshot-state)
        parent :schematest.forward/parent
        child :schematest.forward/child]
    (try
      (testing "a declaration may reference a schema loaded by a later namespace"
        (is (= parent (schema/register! parent [:vector child])))
        (is (= child (schema/register! child :string)))
        (let [projection (schema/build-projection (schema/snapshot))]
          (is (= #{child}
                 (get-in projection
                         [:seon.schema.projection/schema-dependencies
                          parent])))))
      (testing "the complete projection still rejects a genuinely absent ref"
        (let [error (try
                      (schema/build-projection {parent [:vector child]})
                      nil
                      (catch :default error error))]
          (is (= :seon.schema/invalid-schema
                 (:seon.schema/error (ex-data error))))))
      (finally
        (schema/restore-state! before)))))

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
  ;; nil; absent = the key is simply omitted). Registration refuses every
  ;; top-level [:maybe X] immediately with a guiding :user-input ex-info.
  (testing "the smell shape — [:maybe :int]"
    (let [e (try (schema/register! :my.reading/rating [:maybe :int])
                 nil
                 (catch :default e e))]
      (is (some? e) "register! must reject before mutating the candidate")
      (is (not (schema/registered? :my.reading/rating)))
      (is (= :seon.schema/nilable-value-schema
             (:seon.schema/error (ex-data e))))
      (is (= :user-input (:seon.error/kind (ex-data e)))
          "agent-input error kind — surfaced to agents as an error envelope")
      (is (str/includes? (ex-message e)
                         "(schema/register! :my.reading/rating :int)")
          "the error GUIDES: names the copy-pasteable base-type registration")
      (is (str/includes? (ex-message e) "{:optional true}")
          "the error teaches the fix: mark the FIELD optional, don't store nil")))
  (testing "a registered domain type is still forbidden under top-level :maybe"
    (schema/register! :schematest.slot/base :string)
    (let [e (try
              (schema/register! :schematest.slot/opt
                                [:maybe :schematest.slot/base])
              nil
              (catch :default e e))]
      (is (= :seon.schema/nilable-value-schema
             (:seon.schema/error (ex-data e))))
      (is (not (schema/registered? :schematest.slot/opt))))
    (unregister! :schematest.slot/base))
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

(deftest explicit-projection-cache-rotates-by-object-not-fingerprint
  (let [attr :schematest.projection-cache/id
        shape :schematest.projection-cache/shape
        forms {attr :int shape [:map [attr attr]]}
        first-projection (schema/build-projection forms)
        equal-projection (schema/build-projection forms)
        validators (atom 0)
        original-validator m/validator]
    (is (= (:seon.schema.projection/fingerprint first-projection)
           (:seon.schema.projection/fingerprint equal-projection)))
    (is (not (identical? first-projection equal-projection)))
    (with-redefs [m/validator (fn
                               ([form]
                                (swap! validators inc)
                                (original-validator form))
                               ([form options]
                                (swap! validators inc)
                                (original-validator form options)))]
      (schema/matching-shapes-in first-projection {attr 1})
      (let [after-first @validators]
        (schema/matching-shapes-in first-projection {attr 2})
        (is (= after-first @validators)
            "the same projection reuses its compiler")
        (schema/matching-shapes-in equal-projection {attr 3})
        (let [after-rotation @validators]
          (is (> after-rotation after-first)
              "a different projection object rotates the cache")
          (schema/matching-shapes-in equal-projection {attr 4})
          (is (= after-rotation @validators)
              "equal fingerprints do not preserve another object's compiler"))))))

(deftest ambient-selection-survives-an-interleaved-explicit-generation
  (let [before (schema/snapshot-state)
        attr :schematest.projection-race/id
        shape :schematest.projection-race/shape
        active (schema/build-projection {attr :int shape [:map [attr attr]]})
        explicit (schema/build-projection
                   {attr :string shape [:map [attr attr]]})
        interleaved? (atom false)
        original-validator m/validator]
    (try
      (schema/activate-projection! active)
      (with-redefs [m/validator
                    (fn
                      ([form]
                      (when (compare-and-set! interleaved? false true)
                        (is (= [shape]
                               (mapv :seon.schema/key
                                     (schema/matching-shapes-in
                                       explicit {attr "explicit"})))))
                       (original-validator form))
                      ([form options]
                       (when (compare-and-set! interleaved? false true)
                         (is (= [shape]
                                (mapv :seon.schema/key
                                      (schema/matching-shapes-in
                                        explicit {attr "explicit"})))))
                       (original-validator form options)))]
        (is (= [shape]
               (mapv :seon.schema/key
                     (schema/matching-shapes {attr 1}))))
        (is @interleaved?)
        (is (= "[:schematest.projection-race/shape]"
               (pr-str (mapv :seon.schema/key
                             (schema/matching-shapes-in
                               explicit {attr "B"})))))
        (is (= "[:schematest.projection-race/shape]"
               (pr-str (mapv :seon.schema/key
                             (schema/matching-shapes {attr 2})))))
        (is (empty? (schema/matching-shapes {attr "not-A"}))))
      (finally (schema/restore-state! before)))))

(deftest committed-relation-sets-are-honest-projection-input
  (let [projection
        (schema/projection-from-rows
          {:seon.schema/schema-rows
           #{[:schematest.rows/id ":int"]
             [:schematest.rows/shape
              "[:map [:schematest.rows/id :schematest.rows/id]]"]}
           :seon.schema/function-contract-rows #{}})]
    (is (= [:schematest.rows/shape]
           (mapv :seon.schema/key
                 (schema/matching-shapes-in
                   projection {:schematest.rows/id 1}))))))
