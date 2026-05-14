(ns seon.health.workout-test
  "Tests for seon.health.workout and seon.health.workout.render.
   Verifies render functions, scanner detection of *ctx* specs,
   and page renderer identification."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.db :as db]
            [seon.graph.extract :as extract]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq]
            [seon.graph.scanner :as scanner]
            [seon.health.workout :as workout]
            [seon.health.workout.render :as workout-render]
            [seon.render :as render]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(def runtime-graph-malli-schema
  "Aggregated Malli :map schema covering the graph-entity attrs this test
   transacts into the test `:seon.runtime` db (specs, entries, shapes,
   functions). Built by merging the entries of `seon.graph.ingest`'s
   per-entity Malli schemas — those are the source of truth for the
   `:seon.fn/*`, `:seon.spec/*`, `:seon.shape/*`, `:seon.entry/*`, and
   `:seon.ns/*` attributes, each individually registered via
   `seon.schema/register!`.

   The flow's conn-process passes this through
   `seon.db.datahike.schema/malli-map->datahike-schema` at :init, which
   installs one datahike ident per entry.

   The graph entities use intra-DB lookup-refs (`[:seon.shape/id ...]`,
   `[:seon.fn/qualified-name ...]`, etc.) — that's a same-DB `:db.type/ref`.
   The Malli-to-datahike bridge maps bare `:seon.db/ref` to `:db.type/uuid`
   (cross-DB convention, Decision 6 of the migration PRD). To get a same-DB
   `:db.type/ref`, we override those ref-valued entries with an `:or`
   schema carrying `:seon.db/value-type :db.type/ref` in properties, which
   the bridge respects."
  (let [base-entries (mapcat rest
                             [ingest/ns-entity-schema
                              ingest/fn-entity-schema
                              ingest/spec-entity-schema
                              ingest/shape-entity-schema
                              ingest/entry-entity-schema])
        ;; Attribute keys that need to be same-DB refs, not cross-DB UUIDs.
        ;; Cardinality follows from the leaf shape ([:vector :seon.db/ref]
        ;; stays cardinality-many).
        ref-one      #{:seon.fn/input-spec :seon.fn/output-spec
                       :seon.fn/input-shape :seon.fn/output-shape
                       :seon.entry/value-shape}
        ref-many     #{:seon.shape/entries}
        override (fn [entry]
                   (let [k (first entry)
                         opts (when (map? (second entry)) (second entry))
                         tail (cond
                                (ref-one k)
                                [[:or {:seon.db/value-type :db.type/ref} :seon.db/ref]]
                                (ref-many k)
                                [[:vector [:or {:seon.db/value-type :db.type/ref}
                                           :seon.db/ref]]]
                                :else nil)]
                     (if tail
                       (vec (concat [k] (when opts [opts]) tail))
                       entry)))]
    (into [:map] (map override base-entries))))

(use-fixtures :each
  (tu/with-test-db-fixture
    {::tu/namespaces [:seon.runtime]
     ::tu/schemas    {:seon.runtime runtime-graph-malli-schema}}))

;;; ---------------------------------------------------------------------------
;;; workout-set-render Tests
;;; ---------------------------------------------------------------------------

(deftest workout-set-render-test
  (testing "workout-set-render produces both HTML and AI output"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}
          result (workout/workout-set-render data)]
      (is (= "Squat — 5x5 @ 100kg" (:seon.render/ai result)))
      (is (vector? (:seon.render/html result)))
      (is (= :tr (first (:seon.render/html result)))))))

(deftest workout-set-render-formats-test
  (testing "AI format is concise and readable"
    (let [bench {::workout/exercise "Bench Press"
                 ::workout/sets 3
                 ::workout/reps 8
                 ::workout/weight 80}]
      (is (= "Bench Press — 3x8 @ 80kg"
             (:seon.render/ai (workout/workout-set-render bench))))))

  (testing "HTML format contains all fields"
    (let [deadlift {::workout/exercise "Deadlift"
                    ::workout/sets 1
                    ::workout/reps 5
                    ::workout/weight 150}
          html (:seon.render/html (workout/workout-set-render deadlift))]
      (is (= :tr (first html)))
      (is (= 4 (count (filter #(and (vector? %) (= :td (first %))) html)))))))

;;; ---------------------------------------------------------------------------
;;; Companion Render Tests
;;; ---------------------------------------------------------------------------

(deftest companion-workout-set-test
  (testing "workout.render/workout-set matches parent output"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}
          result (workout-render/workout-set data)]
      (is (= "Squat — 5x5 @ 100kg" (:seon.render/ai result)))
      (is (= :tr (first (:seon.render/html result)))))))

(deftest page-render-test
  (testing "page-render produces HTML and AI from ctx data"
    (let [ctx-val {::workout/workouts workout/workouts}
          result (workout-render/page-render {::workout/*ctx* ctx-val})]
      (is (string? (:seon.render/ai result)))
      (is (str/includes? (:seon.render/ai result) "5 exercises"))
      (is (str/includes? (:seon.render/ai result) "Squat"))
      (is (vector? (:seon.render/html result)))
      (is (= :main#morph (first (:seon.render/html result)))))))

(deftest page-render-empty-test
  (testing "page-render handles empty workouts"
    (let [ctx-val {::workout/workouts []}
          result (workout-render/page-render {::workout/*ctx* ctx-val})]
      (is (str/includes? (:seon.render/ai result) "0 exercises")))))

;;; ---------------------------------------------------------------------------
;;; Scanner Tests
;;; ---------------------------------------------------------------------------

(deftest scanner-picks-up-specs-test
  (testing "scan-file finds request and response specs with correct contains-keys"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          by-key (into {} (map (juxt :seon.spec/key identity)) specs)]
      (is (>= (count specs) 2) "Should find render specs")

      (let [req (get by-key ::workout/workout-set-render-request)]
        (is req "Request spec should exist")
        (is (= :map (:seon.spec/base-type req)))
        (is (= (set [::workout/exercise ::workout/sets
                     ::workout/reps ::workout/weight])
               (set (:seon.spec/contains-keys req)))))

      (let [resp (get by-key ::workout/workout-set-render-response)]
        (is resp "Response spec should exist")
        (is (= (set [:seon.render/html :seon.render/ai])
               (set (:seon.spec/contains-keys resp))))))))

(deftest scanner-detects-ctx-spec-test
  (testing "scan-file marks namespace as dynamic when ::*ctx* spec exists"
    (let [specs (scanner/scan-file
                 {::scanner/file-path "src/seon/health/workout.clj"})
          ns-entity (some #(when (:seon.ns/dynamic? %) %) specs)]
      (is ns-entity "Should produce a namespace entity with :seon.ns/dynamic?")
      (is (= "seon.health.workout" (:seon.ns/name ns-entity)))
      (is (true? (:seon.ns/dynamic? ns-entity))))))

(deftest extract-detects-page-renderer-test
  (testing "extract-graph links page-render to specs - render detection at query time"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout/render.clj"})
          fn-entity (first (filter #(= "seon.health.workout.render/page-render"
                                       (:seon.fn/qualified-name %))
                                   (::extract/functions graph)))
          specs (::extract/specs graph)
          input-spec (first (filter #(= :seon.health.workout.render/page-render-request
                                        (:seon.spec/key %)) specs))
          output-spec (first (filter #(= :seon.health.workout.render/page-render-response
                                         (:seon.spec/key %)) specs))]
      (is (some? fn-entity) "Should find page-render function")
      ;; Function is linked to specs
      (is (some? (:seon.fn/input-spec fn-entity)))
      (is (some? (:seon.fn/output-spec fn-entity)))
      ;; Input spec contains *ctx* key (used for page renderer detection at query time)
      (is (some? input-spec))
      (is (some #(str/ends-with? (name %) "*ctx*") (:seon.spec/contains-keys input-spec)))
      ;; Output spec contains :seon.render/html
      (is (some? output-spec))
      (is (contains? (set (:seon.spec/contains-keys output-spec)) :seon.render/html)))))

(deftest extract-links-workout-render-fn-test
  (testing "extract-graph links workout-set-render to its specs"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout.clj"})
          fn-entity (first (filter #(= "seon.health.workout/workout-set-render"
                                       (:seon.fn/qualified-name %))
                                   (::extract/functions graph)))
          specs (::extract/specs graph)
          input-spec (first (filter #(= ::workout/workout-set-render-request
                                        (:seon.spec/key %)) specs))]
      (is (some? fn-entity) "Should find workout-set-render")
      (is (= [:seon.spec/key ::workout/workout-set-render-request]
             (:seon.fn/input-spec fn-entity)))
      (is (= [:seon.spec/key ::workout/workout-set-render-response]
             (:seon.fn/output-spec fn-entity)))
      ;; Input spec contains the required keys (used for resolution at query time)
      (is (some? input-spec))
      (is (= (set [::workout/exercise ::workout/sets
                   ::workout/reps ::workout/weight])
             (set (:seon.spec/contains-keys input-spec)))))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests
;;; ---------------------------------------------------------------------------

(defn- coerce-ints->longs
  "Walk a graph entity coll and coerce any Integer values on long-typed
   datahike attrs to Long. Workaround for a `seon.graph.extract` smell:
   :seon.fn/row, :seon.var/row, :seon.call/row are produced as Integer
   but the datahike schema bridge maps Malli `:int` to `:db.type/long`,
   which requires `java.lang.Long`. Datalevin tolerated the Integer.

   This belongs in `seon.graph.extract` (or the datahike bridge), not in
   the test — flagged in the migration report as a follow-up."
  [coll]
  (let [long-attrs #{:seon.fn/row :seon.var/row :seon.call/row}]
    (mapv (fn [m]
            (reduce-kv (fn [acc k v]
                         (assoc acc k (if (and (long-attrs k) (integer? v))
                                        (long v)
                                        v)))
                       {} m))
          coll)))

(deftest find-renderer-integration-test
  (testing "find-renderer discovers workout-set-render after ingestion"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout.clj"})]
      ;; Dependency order with a cycle break:
      ;;   - specs first (functions ref them via [:seon.spec/key ...]).
      ;;   - shapes and entries form a cycle (shapes hold
      ;;     :seon.shape/entries refs to entries; some entries hold
      ;;     :seon.entry/value-shape refs back to shapes). Datahike
      ;;     resolves lookup-refs against pre-existing entities only — not
      ;;     against same-tx tempids — so we transact shape stubs first
      ;;     (id-only), then entries (which can now look up shape ids),
      ;;     then full shapes (which can now look up entry ids).
      ;;   - functions last (ref specs and shapes via lookup-refs).
      (db/transact! :seon.runtime (vec (::extract/specs graph)))
      (db/transact! :seon.runtime
                    (mapv (fn [s] (select-keys s [:seon.shape/id]))
                          (::extract/shapes graph)))
      (db/transact! :seon.runtime (vec (::extract/entries graph)))
      (db/transact! :seon.runtime (vec (::extract/shapes graph)))
      (db/transact! :seon.runtime (coerce-ints->longs (::extract/functions graph))))

    (gq/invalidate-output-key-cache!)
    (let [workout-data {::workout/exercise "Squat"
                        ::workout/sets 3
                        ::workout/reps 8
                        ::workout/weight 100}]
      (is (= "seon.health.workout/workout-set-render"
             (render/find-renderer :seon.runtime workout-data :html)))
      (is (= "seon.health.workout/workout-set-render"
             (render/find-renderer :seon.runtime workout-data :ai))))))

(deftest try-render-test
  (testing "try-render returns nil when no renderer is registered"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}]
      (is (nil? (render/try-render data :ai))))))

(deftest has-renderer-test
  (testing "has-renderer? returns false when no renderer registered"
    (let [data {::workout/exercise "Squat"
                ::workout/sets 5
                ::workout/reps 5
                ::workout/weight 100}]
      (is (not (render/has-renderer? data :ai))))))

(deftest initial-state-test
  (testing "initial-state returns map with ::workouts"
    (let [state (workout/initial-state)]
      (is (map? state))
      (is (= workout/workouts (::workout/workouts state)))
      (is (= 5 (count (::workout/workouts state)))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.health.workout-test)
  nil)
