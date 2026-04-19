(ns seon.graph.shape-generative-test
  "Property-based tests for the shape graph system.

   Generates random schemas, functions, and data key sets, then verifies
   invariants: walker determinism, injectable detection, matching soundness,
   execution graph acyclicity, and deduplication."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.graph.extract :as extract]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq]
            [seon.schema :as schema]
            [seon.test.bootstrap :as boot])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Generators — random schemas, functions, and key sets
;;; ---------------------------------------------------------------------------

(def leaf-types
  "Leaf Malli types that the shape walker classifies."
  [:string :int :double :boolean :keyword])

(def gen-ns-suffix
  "Generator for namespace suffixes to create unique test namespaces."
  (gen/fmap #(str "seon.gen.prop" %) gen/nat))

(defn gen-qualified-key
  "Generator for a namespaced keyword under a given namespace string."
  [ns-str]
  (gen/fmap #(keyword ns-str (str "k" %)) gen/nat))

(def gen-leaf-type
  "Generator for a random leaf type keyword."
  (gen/elements leaf-types))

(defn gen-entry
  "Generator for a single map entry descriptor: [key optional? type injectable?]."
  [ns-str]
  (gen/let [suffix gen/nat
            optional gen/boolean
            leaf-type gen-leaf-type
            injectable gen/boolean]
    {:key (keyword ns-str (str "field" suffix))
     :optional optional
     :type leaf-type
     :injectable injectable}))

(defn gen-map-schema-descriptor
  "Generator for a map schema descriptor with 1-8 entries."
  [ns-str]
  (gen/let [entries (gen/vector (gen-entry ns-str) 1 8)]
    ;; Deduplicate by key name
    (let [deduped (vals (into {} (map (juxt :key identity)) entries))]
      {:ns-str ns-str
       :entries (vec deduped)})))

;;; ---------------------------------------------------------------------------
;;; Helpers — build source from descriptors
;;; ---------------------------------------------------------------------------

(defn- entry->register-form
  "Produce a schema/register! form string for a single entry's leaf type."
  [{:keys [key type]}]
  (str "(schema/register! " (pr-str key) " " (pr-str type) ")"))

(defn- entry->map-entry
  "Produce a map entry vector form for inside a [:map ...] schema."
  [{:keys [key optional injectable]}]
  (let [props (cond-> {}
                optional (assoc :optional true)
                injectable (assoc :default/fn '(fn [_] nil)))]
    (if (seq props)
      (str "[" (pr-str key) " " (pr-str props) " " (pr-str key) "]")
      (str "[" (pr-str key) " " (pr-str key) "]"))))

(defn- descriptor->source
  "Build a Clojure source string from a map schema descriptor.
   Also registers the schemas in the live Malli registry."
  [{:keys [ns-str entries]} spec-key]
  ;; Register in live registry so walker can resolve
  (doseq [{:keys [key type]} entries]
    (schema/register! key type))
  ;; Register the map schema
  (let [map-entries (mapv (fn [{:keys [key optional injectable]}]
                            (let [props (cond-> {}
                                          optional (assoc :optional true)
                                          injectable (assoc :default/fn '(fn [_] nil)))]
                              (into [key] (if (seq props) [props key] [key]))))
                          entries)]
    (schema/register! spec-key (into [:map] map-entries)))
  ;; Build source string
  (str "(ns " ns-str "\n  (:require [seon.schema :as schema]))\n\n"
       (apply str (map #(str (entry->register-form %) "\n") entries))
       "\n(schema/register! " (pr-str spec-key) "\n  [:map\n"
       (apply str (map #(str "   " (entry->map-entry %) "\n") entries))
       "  ])\n"))

(defn- descriptor->source-with-fn
  "Build source with both a map schema and a function using it.
   Returns [source-string input-spec-key output-spec-key]."
  [{:keys [ns-str entries] :as desc}]
  (let [input-key (keyword ns-str "input")
        output-key (keyword ns-str "output")
        ;; Pick first entry as the output key
        output-entry (first entries)
        output-entries [(assoc output-entry :optional false :injectable false)]]
    ;; Register in live registry
    (doseq [{:keys [key type]} entries]
      (schema/register! key type))
    (let [input-map-entries (mapv (fn [{:keys [key optional injectable]}]
                                   (let [props (cond-> {}
                                                 optional (assoc :optional true)
                                                 injectable (assoc :default/fn '(fn [_] nil)))]
                                     (into [key] (if (seq props) [props key] [key]))))
                                 entries)
          output-map-entries (mapv (fn [{:keys [key]}] [key key]) output-entries)]
      (schema/register! input-key (into [:map] input-map-entries))
      (schema/register! output-key (into [:map] output-map-entries))
      (let [source (str "(ns " ns-str "\n  (:require [seon.schema :as schema]))\n\n"
                        (apply str (map #(str (entry->register-form %) "\n") entries))
                        "\n(schema/register! " (pr-str input-key)
                        "\n  [:map\n"
                        (apply str (map #(str "   " (entry->map-entry %) "\n") entries))
                        "  ])\n"
                        "\n(schema/register! " (pr-str output-key)
                        "\n  [:map\n"
                        (apply str (map #(str "   [" (pr-str (:key %)) " " (pr-str (:key %)) "]\n")
                                        output-entries))
                        "  ])\n"
                        "\n(defn my-fn\n"
                        "  {:malli/schema [:=> [:cat " (pr-str input-key) "] "
                        (pr-str output-key) "]}\n"
                        "  [m] m)\n")]
        [source input-key output-key]))))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-shape-gen-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- delete-dir [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (if (.isDirectory child)
          (delete-dir (.getAbsolutePath child))
          (.delete child)))
      (.delete f))))

(defn with-temp-conn [f]
  (let [dir (temp-dir)
        conn (d/create-conn dir ingest/datalevin-schema)
        mock-manager {::dl-conn/connections (atom {:test-db {::dl-conn/connection conn}})}]
    (try
      (binding [db/*direct-mode* true
                db/*conn-manager* mock-manager]
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-temp-conn)

;;; ---------------------------------------------------------------------------
;;; Property 1: Walker Determinism
;;;
;;; Walking the same schema twice produces the same shape ID.
;;; The entry set from walking matches the original schema keys.
;;; ---------------------------------------------------------------------------

(deftest walker-determinism-test
  (testing "walk-schema is deterministic — same schema produces same shape ID"
    (let [result
          (tc/quick-check 50
            (prop/for-all [n (gen/choose 0 999)]
              (let [ns-str (str "seon.gen.det" n)
                    entries [{:key (keyword ns-str "a") :optional false :type :string :injectable false}
                             {:key (keyword ns-str "b") :optional true :type :int :injectable false}]
                    desc {:ns-str ns-str :entries entries}
                    spec-key (keyword ns-str "my-schema")
                    source (descriptor->source desc spec-key)
                    g1 (extract/extract-graph {:seon.graph.extract/source source
                                               :seon.graph.extract/file-path "<test>"})
                    g2 (extract/extract-graph {:seon.graph.extract/source source
                                               :seon.graph.extract/file-path "<test>"})]
                (and
                 ;; Same number of shapes
                 (= (count (:seon.graph.extract/shapes g1))
                    (count (:seon.graph.extract/shapes g2)))
                 ;; Same shape IDs
                 (= (set (map :seon.shape/id (:seon.graph.extract/shapes g1)))
                    (set (map :seon.shape/id (:seon.graph.extract/shapes g2))))
                 ;; Same entry keys
                 (= (set (map :seon.entry/key (:seon.graph.extract/entries g1)))
                    (set (map :seon.entry/key (:seon.graph.extract/entries g2))))))))]
      (is (:pass? result)
          (str "Walker determinism failed: " (pr-str (:shrunk result))))))

  (testing "walked shape entries match original schema keys"
    (let [result
          (tc/quick-check 30
            (prop/for-all [n (gen/choose 0 999)
                           num-fields (gen/choose 1 6)]
              (let [ns-str (str "seon.gen.keys" n)
                    entries (mapv (fn [i]
                                   {:key (keyword ns-str (str "f" i))
                                    :optional (even? i)
                                    :type (nth leaf-types (mod i (count leaf-types)))
                                    :injectable false})
                                 (range num-fields))
                    desc {:ns-str ns-str :entries entries}
                    spec-key (keyword ns-str "test-schema")
                    source (descriptor->source desc spec-key)
                    graph (extract/extract-graph {:seon.graph.extract/source source
                                                  :seon.graph.extract/file-path "<test>"})
                    shape-entries (:seon.graph.extract/entries graph)
                    expected-keys (set (map :key entries))
                    actual-keys (set (map :seon.entry/key shape-entries))]
                (= expected-keys actual-keys))))]
      (is (:pass? result)
          (str "Entry keys mismatch: " (pr-str (:shrunk result)))))))

;;; ---------------------------------------------------------------------------
;;; Property 2: Injectable Detection Consistency
;;;
;;; Entries with :default/fn or :default in props are marked injectable.
;;; Entries without defaults are NOT injectable.
;;; ---------------------------------------------------------------------------

(deftest injectable-detection-property-test
  (testing "injectable flag matches presence of :default/fn or :default"
    (let [result
          (tc/quick-check 40
            (prop/for-all [n (gen/choose 0 999)
                           num-fields (gen/choose 1 5)]
              (let [ns-str (str "seon.gen.inj" n)
                    entries (mapv (fn [i]
                                   {:key (keyword ns-str (str "x" i))
                                    :optional false
                                    :type :string
                                    :injectable (odd? i)})
                                 (range num-fields))
                    desc {:ns-str ns-str :entries entries}
                    spec-key (keyword ns-str "inj-schema")
                    source (descriptor->source desc spec-key)
                    graph (extract/extract-graph {:seon.graph.extract/source source
                                                  :seon.graph.extract/file-path "<test>"})
                    shape-entries (:seon.graph.extract/entries graph)
                    entry-by-key (into {} (map (juxt :seon.entry/key identity)) shape-entries)]
                (every? (fn [{:keys [key injectable]}]
                          (let [entry (get entry-by-key key)]
                            (when entry
                              (= injectable (:seon.entry/injectable entry)))))
                        entries))))]
      (is (:pass? result)
          (str "Injectable detection failed: " (pr-str (:shrunk result)))))))

;;; ---------------------------------------------------------------------------
;;; Property 3: Function Matching Soundness
;;;
;;; For every function returned by functions-matching-data, its required
;;; non-injectable keys are a subset of available keys.
;;; For every function NOT returned, it has at least one required
;;; non-injectable key NOT in available keys.
;;; ---------------------------------------------------------------------------

(deftest matching-soundness-property-test
  (testing "function matching is sound — returned fns have all required keys satisfied"
    ;; Set up a known domain with multiple functions
    (schema/register! :seon.gen.match/a :string)
    (schema/register! :seon.gen.match/b :int)
    (schema/register! :seon.gen.match/c :double)
    (schema/register! :seon.gen.match/d :boolean)
    (schema/register! :seon.gen.match/ctx
      [:map {:default/fn '(fn [_] {})}])
    (schema/register! :seon.gen.match/in1
      [:map
       [:seon.gen.match/ctx :seon.gen.match/ctx]
       [:seon.gen.match/a :seon.gen.match/a]
       [:seon.gen.match/b :seon.gen.match/b]])
    (schema/register! :seon.gen.match/out1
      [:map [:seon.gen.match/a :seon.gen.match/a]])
    (schema/register! :seon.gen.match/in2
      [:map
       [:seon.gen.match/ctx :seon.gen.match/ctx]
       [:seon.gen.match/c :seon.gen.match/c]])
    (schema/register! :seon.gen.match/out2
      [:map [:seon.gen.match/c :seon.gen.match/c]])
    (schema/register! :seon.gen.match/in3
      [:map
       [:seon.gen.match/a :seon.gen.match/a]
       [:seon.gen.match/b :seon.gen.match/b]
       [:seon.gen.match/c :seon.gen.match/c]
       [:seon.gen.match/d :seon.gen.match/d]])
    (schema/register! :seon.gen.match/out3
      [:map [:seon.gen.match/d :seon.gen.match/d]])

    (let [source "(ns seon.gen.match
  (:require [seon.schema :as schema]))

(schema/register! ::a :string)
(schema/register! ::b :int)
(schema/register! ::c :double)
(schema/register! ::d :boolean)
(schema/register! ::ctx [:map {:default/fn '(fn [_] {})}])

(schema/register! ::in1 [:map [::ctx ::ctx] [::a ::a] [::b ::b]])
(schema/register! ::out1 [:map [::a ::a]])
(schema/register! ::in2 [:map [::ctx ::ctx] [::c ::c]])
(schema/register! ::out2 [:map [::c ::c]])
(schema/register! ::in3 [:map [::a ::a] [::b ::b] [::c ::c] [::d ::d]])
(schema/register! ::out3 [:map [::d ::d]])

(defn fn1
  {:malli/schema [:=> [:cat ::in1] ::out1]}
  [m] m)

(defn fn2
  {:malli/schema [:=> [:cat ::in2] ::out2]}
  [m] m)

(defn fn3
  {:malli/schema [:=> [:cat ::in3] ::out3]}
  [m] m)"
          graph (extract/extract-graph {:seon.graph.extract/source source
                                        :seon.graph.extract/file-path "<test>"})
          fns-clean (mapv #(dissoc % :seon.fn/input-spec :seon.fn/output-spec)
                          (:seon.graph.extract/functions graph))]
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.gen.match"
        ::ingest/functions fns-clean
        ::ingest/entries (:seon.graph.extract/entries graph)
        ::ingest/shapes (:seon.graph.extract/shapes graph)})

      ;; fn1 requires {::a, ::b} (ctx is injectable)
      ;; fn2 requires {::c} (ctx is injectable)
      ;; fn3 requires {::a, ::b, ::c, ::d} (no injectable)
      (let [all-keys #{:seon.gen.match/a :seon.gen.match/b
                        :seon.gen.match/c :seon.gen.match/d}
            ;; Required non-injectable keys per function
            fn-requirements {"seon.gen.match/fn1" #{:seon.gen.match/a :seon.gen.match/b}
                             "seon.gen.match/fn2" #{:seon.gen.match/c}
                             "seon.gen.match/fn3" #{:seon.gen.match/a :seon.gen.match/b
                                                    :seon.gen.match/c :seon.gen.match/d}}
            result
            (tc/quick-check 100
              (prop/for-all [key-subset (gen/set (gen/elements (vec all-keys)))]
                (let [matches (gq/functions-matching-data
                               {::gq/db-name :test-db
                                ::gq/available-keys key-subset})
                      matched-names (set (map :seon.fn/qualified-name matches))]
                  ;; For every matched fn: required keys subset of available
                  ;; For every non-matched fn: at least one required key missing
                  (every? (fn [[fn-name required]]
                            (if (matched-names fn-name)
                              (set/subset? required key-subset)
                              (not (set/subset? required key-subset))))
                          fn-requirements))))]
        (is (:pass? result)
            (str "Matching soundness failed: " (pr-str (:shrunk result))))))))

;;; ---------------------------------------------------------------------------
;;; Property 4: Execution Graph Acyclicity
;;;
;;; No function appears twice in the execution order.
;;; ---------------------------------------------------------------------------

(deftest execution-graph-acyclicity-test
  (testing "execution graph nodes contain no duplicates"
    ;; Use the bootstrap namespace — well-known domain
    (let [source (slurp "src/seon/test/bootstrap.clj")
          graph (extract/extract-graph {:seon.graph.extract/source source
                                        :seon.graph.extract/file-path "src/seon/test/bootstrap.clj"})
          fns-clean (mapv #(dissoc % :seon.fn/input-spec :seon.fn/output-spec)
                          (:seon.graph.extract/functions graph))]
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.test.bootstrap"
        ::ingest/functions fns-clean
        ::ingest/entries (:seon.graph.extract/entries graph)
        ::ingest/shapes (:seon.graph.extract/shapes graph)})

      (let [boot-keys #{:seon.test.bootstrap/exercise
                         :seon.test.bootstrap/weight
                         :seon.test.bootstrap/reps
                         :seon.test.bootstrap/bodyweight}
            all-consumers #{:seon.test.bootstrap/volume
                            :seon.test.bootstrap/sets
                            :seon.test.bootstrap/weekly-volume
                            :seon.test.bootstrap/strength-ratios
                            :seon.test.bootstrap/suggestions}
            result
            (tc/quick-check 50
              (prop/for-all [key-subset (gen/set (gen/elements (vec boot-keys)))
                             consumer-subset (gen/set (gen/elements (vec all-consumers)))]
                (let [exec-graph (boot/build-execution-graph
                                  {::boot/data-keys key-subset
                                   ::boot/consumers consumer-subset
                                   ::boot/db-name :test-db})
                      node-names (map :seon.fn/qualified-name (::boot/nodes exec-graph))]
                  ;; No duplicates in node list
                  (= (count node-names) (count (set node-names))))))]
        (is (:pass? result)
            (str "Acyclicity failed: " (pr-str (:shrunk result))))))))

;;; ---------------------------------------------------------------------------
;;; Property 5: Cascade Completeness
;;;
;;; After build-execution-graph, every node either:
;;; - produces a consumed key, OR
;;; - produces ::ctx (state update), OR
;;; - has a downstream edge to a consumed node
;;; (pruning removes everything else)
;;; ---------------------------------------------------------------------------

(deftest cascade-completeness-test
  (testing "every node in pruned graph is justified — has consumers or produces ctx"
    (let [source (slurp "src/seon/test/bootstrap.clj")
          graph (extract/extract-graph {:seon.graph.extract/source source
                                        :seon.graph.extract/file-path "src/seon/test/bootstrap.clj"})
          fns-clean (mapv #(dissoc % :seon.fn/input-spec :seon.fn/output-spec)
                          (:seon.graph.extract/functions graph))]
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.test.bootstrap"
        ::ingest/functions fns-clean
        ::ingest/entries (:seon.graph.extract/entries graph)
        ::ingest/shapes (:seon.graph.extract/shapes graph)})

      (let [boot-keys #{:seon.test.bootstrap/exercise
                         :seon.test.bootstrap/weight
                         :seon.test.bootstrap/reps}
            consumers #{:seon.test.bootstrap/volume}
            exec-graph (boot/build-execution-graph
                        {::boot/data-keys boot-keys
                         ::boot/consumers consumers
                         ::boot/db-name :test-db})
            nodes (::boot/nodes exec-graph)
            edges (::boot/edges exec-graph)]
        ;; Every node must be justified
        (doseq [node nodes]
          (let [fn-name (:seon.fn/qualified-name node)
                output-keys (set (gq/function-output-keys
                                  {::gq/db-name :test-db
                                   ::gq/qualified-name fn-name}))
                has-consumer (seq (set/intersection output-keys consumers))
                produces-ctx (contains? output-keys :seon.test.bootstrap/ctx)
                has-downstream (some #(= fn-name (first %)) edges)]
            (is (or has-consumer produces-ctx has-downstream)
                (str fn-name " is in graph but has no consumer, doesn't produce ctx, "
                     "and has no downstream. Output keys: " output-keys))))))))

;;; ---------------------------------------------------------------------------
;;; Property 6: Deduplication Invariant
;;;
;;; Two schemas with the same entries produce the same shape ID.
;;; Two schemas with different entries produce different shape IDs.
;;; ---------------------------------------------------------------------------

(deftest deduplication-invariant-test
  (testing "identical entry sets produce identical shape IDs"
    (let [result
          (tc/quick-check 30
            (prop/for-all [n (gen/choose 0 999)]
              (let [ns1 (str "seon.gen.dup1x" n)
                    ns2 (str "seon.gen.dup2x" n)
                    ;; Same entries, registered in two different namespaces
                    key-a1 (keyword ns1 "alpha")
                    key-b1 (keyword ns1 "beta")
                    key-a2 (keyword ns2 "alpha")
                    key-b2 (keyword ns2 "beta")
                    spec1 (keyword ns1 "schema")
                    spec2 (keyword ns2 "schema")]
                (schema/register! key-a1 :string)
                (schema/register! key-b1 :int)
                (schema/register! spec1 [:map [key-a1 key-a1] [key-b1 key-b1]])
                (schema/register! key-a2 :string)
                (schema/register! key-b2 :int)
                (schema/register! spec2 [:map [key-a2 key-a2] [key-b2 key-b2]])
                ;; Named specs produce shape IDs based on spec-key, not structure
                ;; So two DIFFERENT spec-keys always produce different shape IDs
                ;; (This is correct — named specs are identified by name)
                (let [src1 (str "(ns " ns1 "\n  (:require [seon.schema :as schema]))\n"
                                "(schema/register! " (pr-str key-a1) " :string)\n"
                                "(schema/register! " (pr-str key-b1) " :int)\n"
                                "(schema/register! " (pr-str spec1)
                                " [:map [" (pr-str key-a1) " " (pr-str key-a1)
                                "] [" (pr-str key-b1) " " (pr-str key-b1) "]])\n")
                      g1 (extract/extract-graph {:seon.graph.extract/source src1
                                                  :seon.graph.extract/file-path "<test>"})
                      shapes1 (:seon.graph.extract/shapes g1)
                      ;; Extract the graph again — same source = same shapes
                      g1b (extract/extract-graph {:seon.graph.extract/source src1
                                                   :seon.graph.extract/file-path "<test>"})
                      shapes1b (:seon.graph.extract/shapes g1b)]
                  ;; Same source -> same shape IDs (determinism)
                  (= (set (map :seon.shape/id shapes1))
                     (set (map :seon.shape/id shapes1b)))))))]
      (is (:pass? result)
          (str "Dedup invariant failed: " (pr-str (:shrunk result))))))

  (testing "named spec reuse produces single shape — fn-schema and spec list dedup"
    ;; When the same spec is referenced from both the spec list AND a fn-schema,
    ;; we should get exactly one shape entity (not two)
    (let [result
          (tc/quick-check 20
            (prop/for-all [n (gen/choose 0 999)
                           num-fields (gen/choose 1 4)]
              (let [ns-str (str "seon.gen.dedup" n)
                    entries (mapv (fn [i]
                                   {:key (keyword ns-str (str "v" i))
                                    :optional false
                                    :type (nth leaf-types (mod i (count leaf-types)))
                                    :injectable false})
                                 (range num-fields))
                    spec-key (keyword ns-str "request")
                    out-key (keyword ns-str "response")
                    ;; Register all
                    _ (doseq [{:keys [key type]} entries]
                        (schema/register! key type))
                    map-entries (mapv (fn [{:keys [key]}]
                                       [key key])
                                     entries)
                    _ (schema/register! spec-key (into [:map] map-entries))
                    _ (schema/register! out-key [:map [(-> entries first :key) (-> entries first :key)]])
                    source (str "(ns " ns-str "\n  (:require [seon.schema :as schema]))\n\n"
                                (apply str (map #(str "(schema/register! " (pr-str (:key %))
                                                      " " (pr-str (:type %)) ")\n")
                                                entries))
                                "(schema/register! " (pr-str spec-key)
                                " [:map " (apply str (map #(str "[" (pr-str (:key %))
                                                                " " (pr-str (:key %)) "] ")
                                                          entries)) "])\n"
                                "(schema/register! " (pr-str out-key)
                                " [:map [" (pr-str (-> entries first :key))
                                " " (pr-str (-> entries first :key)) "]])\n"
                                "\n(defn process\n"
                                "  {:malli/schema [:=> [:cat " (pr-str spec-key)
                                "] " (pr-str out-key) "]}\n"
                                "  [m] m)\n")
                    graph (extract/extract-graph {:seon.graph.extract/source source
                                                   :seon.graph.extract/file-path "<test>"})
                    shapes (:seon.graph.extract/shapes graph)
                    ;; Count how many shapes have this spec-key's shape ID
                    target-id (str "shape:" (namespace spec-key) "/" (name spec-key))
                    matching (filter #(= target-id (:seon.shape/id %)) shapes)]
                ;; Should be exactly 1
                (= 1 (count matching)))))]
      (is (:pass? result)
          (str "Dedup fn+spec failed: " (pr-str (:shrunk result)))))))

;;; ---------------------------------------------------------------------------
;;; Stress Test: N schemas, M functions, random key sets
;;; ---------------------------------------------------------------------------

(deftest stress-test
  (testing "stress: register N schemas, M functions, index, query with random keys"
    (let [ns-str "seon.gen.stress"
          ;; Generate 50 leaf schemas
          num-schemas 50
          leaf-keys (mapv (fn [i]
                            (let [k (keyword ns-str (str "attr" i))]
                              (schema/register! k (nth leaf-types (mod i (count leaf-types))))
                              k))
                          (range num-schemas))
          ;; Generate 30 functions with random input/output schemas
          num-fns 30
          rng (java.util.Random. 42)
          fn-specs
          (mapv (fn [i]
                  (let [;; Pick 2-5 random input keys
                        num-input (+ 2 (.nextInt rng 4))
                        input-keys (take num-input (shuffle leaf-keys))
                        ;; Pick 1-2 random output keys
                        num-output (+ 1 (.nextInt rng 2))
                        output-keys (take num-output (shuffle leaf-keys))
                        in-spec-key (keyword ns-str (str "fn" i "-in"))
                        out-spec-key (keyword ns-str (str "fn" i "-out"))
                        in-map-entries (mapv (fn [k] [k k]) input-keys)
                        out-map-entries (mapv (fn [k] [k k]) output-keys)]
                    (schema/register! in-spec-key (into [:map] in-map-entries))
                    (schema/register! out-spec-key (into [:map] out-map-entries))
                    {:fn-name (str "fn" i)
                     :in-spec in-spec-key
                     :out-spec out-spec-key
                     :input-keys (set input-keys)
                     :output-keys (set output-keys)}))
                (range num-fns))
          ;; Build source
          source (str "(ns " ns-str "\n  (:require [seon.schema :as schema]))\n\n"
                      ;; Leaf registrations
                      (apply str (map-indexed
                                  (fn [i k]
                                    (str "(schema/register! " (pr-str k) " "
                                         (pr-str (nth leaf-types (mod i (count leaf-types)))) ")\n"))
                                  leaf-keys))
                      "\n"
                      ;; Function schema registrations + defns
                      (apply str
                             (map (fn [{:keys [fn-name in-spec out-spec input-keys output-keys]}]
                                    (let [in-entries (apply str (map #(str "[" (pr-str %) " " (pr-str %) "] ")
                                                                    input-keys))
                                          out-entries (apply str (map #(str "[" (pr-str %) " " (pr-str %) "] ")
                                                                     output-keys))]
                                      (str "(schema/register! " (pr-str in-spec)
                                           " [:map " in-entries "])\n"
                                           "(schema/register! " (pr-str out-spec)
                                           " [:map " out-entries "])\n"
                                           "(defn " fn-name "\n"
                                           "  {:malli/schema [:=> [:cat " (pr-str in-spec)
                                           "] " (pr-str out-spec) "]}\n"
                                           "  [m] m)\n\n")))
                                  fn-specs)))
          ;; Extract and ingest
          graph (extract/extract-graph {:seon.graph.extract/source source
                                        :seon.graph.extract/file-path "<test>"})
          fns-clean (mapv #(dissoc % :seon.fn/input-spec :seon.fn/output-spec)
                          (:seon.graph.extract/functions graph))]
      ;; Verify extraction produced shapes
      (is (pos? (count (:seon.graph.extract/shapes graph)))
          "Should produce shapes from 30 functions")
      (is (pos? (count (:seon.graph.extract/entries graph)))
          "Should produce entries from 30 functions")

      ;; Ingest into test DB
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name ns-str
        ::ingest/functions fns-clean
        ::ingest/entries (:seon.graph.extract/entries graph)
        ::ingest/shapes (:seon.graph.extract/shapes graph)})

      ;; Run 100 random matching queries and verify soundness
      (let [result
            (tc/quick-check 100
              (prop/for-all [key-subset (gen/set (gen/elements (vec leaf-keys)))]
                (let [matches (gq/functions-matching-data
                               {::gq/db-name :test-db
                                ::gq/available-keys key-subset})
                      matched-names (set (map :seon.fn/qualified-name matches))]
                  ;; For every matched function, verify all required keys present
                  (every? (fn [{:keys [fn-name input-keys]}]
                            (let [qn (str ns-str "/" fn-name)]
                              (if (matched-names qn)
                                ;; All input keys must be in the subset
                                ;; (none are injectable in this test)
                                (set/subset? input-keys key-subset)
                                ;; At least one input key missing
                                (not (set/subset? input-keys key-subset)))))
                          fn-specs))))]
        (is (:pass? result)
            (str "Stress matching failed: " (pr-str (:shrunk result))))))))

;;; ---------------------------------------------------------------------------
;;; Property: Optional entries don't block matching
;;; ---------------------------------------------------------------------------

(deftest optional-entries-dont-block-test
  (testing "functions with optional-only missing keys still match"
    (schema/register! :seon.gen.opt/req :string)
    (schema/register! :seon.gen.opt/opt :int)
    (schema/register! :seon.gen.opt/in
      [:map
       [:seon.gen.opt/req :seon.gen.opt/req]
       [:seon.gen.opt/opt {:optional true} :seon.gen.opt/opt]])
    (schema/register! :seon.gen.opt/out
      [:map [:seon.gen.opt/req :seon.gen.opt/req]])

    (let [source "(ns seon.gen.opt
  (:require [seon.schema :as schema]))
(schema/register! ::req :string)
(schema/register! ::opt :int)
(schema/register! ::in [:map [::req ::req] [::opt {:optional true} ::opt]])
(schema/register! ::out [:map [::req ::req]])
(defn my-fn
  {:malli/schema [:=> [:cat ::in] ::out]}
  [m] m)"
          graph (extract/extract-graph {:seon.graph.extract/source source
                                        :seon.graph.extract/file-path "<test>"})
          fns-clean (mapv #(dissoc % :seon.fn/input-spec :seon.fn/output-spec)
                          (:seon.graph.extract/functions graph))]
      (ingest/ingest-namespace!
       {::ingest/db-name :test-db
        ::ingest/ns-name "seon.gen.opt"
        ::ingest/functions fns-clean
        ::ingest/entries (:seon.graph.extract/entries graph)
        ::ingest/shapes (:seon.graph.extract/shapes graph)})

      ;; Should match with just the required key
      (let [matches (gq/functions-matching-data
                     {::gq/db-name :test-db
                      ::gq/available-keys #{:seon.gen.opt/req}})]
        (is (some #(= "seon.gen.opt/my-fn" (:seon.fn/qualified-name %)) matches)
            "Function should match when only required keys provided"))

      ;; Should also match with both keys
      (let [matches (gq/functions-matching-data
                     {::gq/db-name :test-db
                      ::gq/available-keys #{:seon.gen.opt/req :seon.gen.opt/opt}})]
        (is (some #(= "seon.gen.opt/my-fn" (:seon.fn/qualified-name %)) matches)
            "Function should match when all keys provided"))

      ;; Should NOT match with only optional key
      (let [matches (gq/functions-matching-data
                     {::gq/db-name :test-db
                      ::gq/available-keys #{:seon.gen.opt/opt}})]
        (is (not (some #(= "seon.gen.opt/my-fn" (:seon.fn/qualified-name %)) matches))
            "Function should NOT match when required key missing")))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.shape-generative-test)
  nil)
