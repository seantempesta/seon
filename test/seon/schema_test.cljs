(ns seon.schema-test
  "CLJS-side tests for seon.schema's register! gates.

   Pins the single-segment keyword-namespace gate (gym S-21 paid-run
   finding, 2026-06-10): `:workout/date` landed in a paid run beside
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
    [seon.agent]
    [seon.agent.todo]
    [seon.schema :as schema]
    [seon.test.runner]))

(defn- unregister! [& ks]
  (swap! @#'schema/*schemas #(apply dissoc % ks)))

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
      (is (str/includes? (ex-message e) "(seon.db/store-inventory)")
          "the error teaches reuse-first: consult the store before registering"))))

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

(deftest declared-entity-derives-id-attr
  (schema/register! :schematest.entity/id [:string {:seon.db/identity true}])
  (schema/register! :schematest.entity/label :string)
  (schema/register! :schematest.entity
    [:map {:seon.db/entity true}
     [:schematest.entity/id    :schematest.entity/id]
     [:schematest.entity/label :schematest.entity/label]])
  (testing "marker present → id-attr derived into the stored props"
    (let [props (second (schema/schema-definition :schematest.entity))]
      (is (= true (:seon.db/entity props)) "declared marker preserved")
      (is (= :schematest.entity/id (:seon.entity/id-attr props))
          "id-attr derived from the identity-attr entry")))
  (testing "declared kind enters the catalog"
    (is (contains? (set (schema/entity-schema-keys)) :schematest.entity)))
  (unregister! :schematest.entity/id :schematest.entity/label
               :schematest.entity))

(deftest entity-schema-tempids-carry-the-full-keyword
  ;; downstream boot-fatal repro 2026-06-11: two kinds sharing a NAME segment
  ;; (:a.b/person + :c.d/person) collided on the tempid "schema-person"
  ;; in one seed tx → :transact/upsert conflict → pod exit, no resume.
  ;; Tempids must carry the FULL keyword; they are tx-local and never
  ;; stored, so this is identity-free to change.
  (schema/register! :schematest.one.person/id [:string {:seon.db/identity true}])
  (schema/register! :schematest.two.person/id [:string {:seon.db/identity true}])
  (schema/register! :schematest.one/person
    [:map {:seon.db/entity true}
     [:schematest.one.person/id :schematest.one.person/id]])
  (schema/register! :schematest.two/person
    [:map {:seon.db/entity true}
     [:schematest.two.person/id :schematest.two.person/id]])
  (let [tids (fn [k] (->> (schema/entity-schema-tx-data k)
                          (map second)   ; [:db/add TID attr v]
                          set))
        one  (tids :schematest.one/person)
        two  (tids :schematest.two/person)]
    (is (= 1 (count one)))
    (is (= 1 (count two)))
    (is (not (some one two))
        "same name segment must NOT share a tempid (boot-fatal upsert)"))
  (unregister! :schematest.one.person/id :schematest.two.person/id
               :schematest.one/person :schematest.two/person))

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
    (is (not (contains? (set (schema/entity-schema-keys))
                        :schematest.envelope/lookup-shape))
        "unmarked map never enters the catalog"))
  (unregister! :schematest.envelope/id
               :schematest.envelope/lookup-shape))

(deftest catalog-surfaces-only-declared-kinds
  (let [kinds (set (schema/entity-schema-keys))]
    (testing "the genuine entity kinds are declared and present"
      (doseq [k [:seon.agent :seon.eval :seon.agent.message :seon.fn
                 :seon.ns :seon.schema :seon.agent.todo/todo :seon.test]]
        (is (contains? kinds k) (str k " is a declared entity kind"))))
    (testing "the 8 formerly-phantom request/response wrappers are gone"
      (doseq [k [:seon.agent.todo/write-response
                 :seon.agent.todo/reopen-request
                 :seon.agent.todo/complete-request
                 :seon.agent/render-namespace-request
                 :seon.handler/input
                 :seon.agent.inspect/request
                 :seon.render/assemble-request
                 :seon.effect/wake-request]]
        (is (not (contains? kinds k)) (str k " must NOT be a kind"))))))
