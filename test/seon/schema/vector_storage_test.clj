(ns seon.schema.vector-storage-test
  "A `[:vector T]` attribute stores its elements in bridge cells and reads
  back as the same vector: order, duplicates, `[]` distinct from absent
  (docs/research/agent-platform/vector-storage-design-2026-09-27.md).

  Until `seon.db.vector.edn` is packaged the bridge attributes are synthetic
  here: [[vector-projection]] declares them with the test's own vector
  attributes on the executing projection."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.constants :as const]
            [datahike.query :as dq]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

;;; ---------------------------------------------------------------------------
;;; The world: bridge declarations plus synthetic vector attributes
;;; ---------------------------------------------------------------------------

(def bridge-forms
  "The bridge's declarations (design §2), in dependency order."
  [[:seon.db.vector/position [:int {:min 0}]]
   [:seon.db.vector/cell [:map {:seon.db/attributes true}
                          [:seon.db.vector/position :seon.db.vector/position]]]
   [:seon.db.vector/cells [:set {:seon.db/component true :seon.db/component-schema :seon.db.vector/cell}
                           :seon.db/ref]]
   [:seon.db.vector/present [:set {:seon.db/index true} :qualified-keyword]]])

(def subject-forms
  "Synthetic attributes covering every element kind: scalar, keyword, tuple,
   peer ref and component (with a nested component vector)."
  [[:seon.vector-test.p/id [:string {:seon.db/identity true}]]
   [:seon.vector-test.t/id [:string {:seon.db/identity true}]]
   [:seon.vector-test.p/items [:vector :string]]
   [:seon.vector-test.p/keys [:vector :qualified-keyword]]
   [:seon.vector-test.p/pairs [:vector [:tuple :int :string]]]
   [:seon.vector-test.p/tags [:set :qualified-keyword]]
   [:seon.vector-test.p/peers [:vector :seon.db/ref]]
   [:seon.vector-test.p/note [:or :string [:map-of :qualified-keyword :uuid]]]
   [:seon.vector-test.a/name :string]
   [:seon.vector-test.a/row [:map {:seon.db/attributes true}
                             [:seon.vector-test.a/name :seon.vector-test.a/name]]]
   [:seon.vector-test.k/name :string]
   [:seon.vector-test.k/args [:vector {:seon.db/component true :seon.db/component-schema :seon.vector-test.a/row}
                              :seon.db/ref]]
   [:seon.vector-test.k/row [:map {:seon.db/attributes true}
                             [:seon.vector-test.k/name :seon.vector-test.k/name]
                             [:seon.vector-test.k/args {:optional true} :seon.vector-test.k/args]]]
   [:seon.vector-test.p/kids [:vector {:seon.db/component true :seon.db/component-schema :seon.vector-test.k/row}
                              :seon.db/ref]]])

(defn vector-projection
  "`projection` with `forms` declared, each admitted as a core schema."
  {:malli/schema [:=> [:cat :seon.schema/projection [:vector [:tuple :qualified-keyword :seon.schema/malli-form]]]
                  :seon.schema/projection]}
  [projection forms]
  (reduce (fn [current [schema-key definition]]
            (schema/projection-with-schema current schema-key definition {:seon.schema.admission/source :core}))
          projection forms))

(defn- without-cells
  "`projection` as a value older than the bridge carries it: no cells declared."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.schema/projection]}
  [projection]
  (update projection :seon.schema.projection/forms dissoc :seon.db.vector/cells))

(defn- encoded
  "`transaction` encoded under `projection`, the vector call shown as
   [:vectors requests]."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.store/transaction] :seon.store/transaction-data]}
  [projection transaction]
  (mapv (fn [operation]
          (if (and (vector? operation) (= :db.fn/call (first operation)))
            [:vectors (last operation)]
            operation))
        (schema.datahike/encode-transaction-in projection `vectors-fn transaction)))

(defn- refusal-rule
  "The bridge refusal rule `thunk` throws, or nil when it returns."
  {:malli/schema [:=> [:cat :seon.instrument/callable] [:or :nil :qualified-keyword]]}
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (::schema.datahike/rule (ex-data e)))))

;;; ---------------------------------------------------------------------------
;;; S1: declarations
;;; ---------------------------------------------------------------------------

(deftest vector-attributes-are-declared-by-shape
  (support/with-database
   (fn [_connection]
     (let [without (without-cells (vector-projection (schema/handed-projection) subject-forms))
           with (vector-projection without bridge-forms)
           items :seon.vector-test.p/items]
       (testing "the installed declaration does not change: cardinality-many of the element"
         (is (= {:db/ident items :db/valueType :db.type/string :db/cardinality :db.cardinality/many}
                (schema.datahike/malli->datahike-attr-in with items)))
         (is (= (schema.datahike/malli->datahike-attr-in without :seon.vector-test.p/kids)
                (schema.datahike/malli->datahike-attr-in with :seon.vector-test.p/kids))))
       (testing "the shape is a vector with or without cells; storage in cells needs them declared"
         (is (schema.datahike/vector-shaped-attr-in? without items))
         (is (not (schema.datahike/vector-attribute-in? without items)))
         (is (schema.datahike/vector-attribute-in? with items))
         (is (not (schema.datahike/vector-attribute-in? with :seon.vector-test.p/tags)))
         (is (not (schema.datahike/vector-attribute-in? with :seon.vector-test.p/id))))
       (testing "the set a per-datom loop tests is exactly the attributes the question answers true for"
         (doseq [projection [without with (schema/handed-projection)]]
           (is (= (into #{} (filter #(and (keyword? %) (schema.datahike/vector-attribute-in? projection %)))
                        (keys (:seon.schema.projection/forms projection)))
                  (schema.datahike/vector-attributes-in projection))))
         (is (contains? (schema.datahike/vector-attributes-in with) items))
         (is (= #{} (schema.datahike/vector-attributes-in without))))
       (testing "every bridge attribute is a database attribute, so the population installs all three"
         (is (every? (set (schema.datahike/database-attributes-in with))
                     [:seon.db.vector/cells :seon.db.vector/position :seon.db.vector/present]))
         (is (every? (set (schema.datahike/database-attributes-in (schema/handed-projection)))
                     [:seon.db.vector/cells :seon.db.vector/position :seon.db.vector/present])
             "the packaged seon.db.vector.edn declares all three (the 2026-09-27 boot installed only two)"))
       (testing "the logical collection follows the declared shape"
         (is (= ["b" "a" "b"] (schema.datahike/logical-collection with items ["b" "a" "b"])))
         (is (= #{:a/b} (schema.datahike/logical-collection with :seon.vector-test.p/tags [:a/b :a/b]))))))))

;;; ---------------------------------------------------------------------------
;;; S2: the encoder
;;; ---------------------------------------------------------------------------

(deftest vectors-encode-as-cells-or-one-batched-request
  (support/with-database
   (fn [_connection]
     (let [projection (-> (schema/handed-projection)
                          (vector-projection subject-forms)
                          (vector-projection bridge-forms))
           items :seon.vector-test.p/items]
       (testing "a new entity's vector becomes inline cells and presence"
         (is (= [{:db/id "n"
                  :seon.db.vector/present #{items}
                  :seon.db.vector/cells [{:seon.db.vector/position 0 items ["b"]}
                                         {:seon.db.vector/position 1 items ["a"]}
                                         {:seon.db.vector/position 2 items ["b"]}]}]
                (encoded projection [{:db/id "n" items ["b" "a" "b"]}]))))
       (testing "a new component element's own vectors are inline cells too"
         (is (= [{:db/id "k"
                  :seon.db.vector/present #{:seon.vector-test.p/kids}
                  :seon.db.vector/cells
                  [{:seon.db.vector/position 0
                    :seon.vector-test.p/kids
                    [{:seon.vector-test.k/name "x"
                      :seon.db.vector/present #{:seon.vector-test.k/args}
                      :seon.db.vector/cells [{:seon.db.vector/position 0
                                              :seon.vector-test.k/args [{:seon.vector-test.a/name "1"}]}]}]}]}]
                (encoded projection [{:db/id "k"
                                      :seon.vector-test.p/kids [{:seon.vector-test.k/name "x"
                                                                 :seon.vector-test.k/args [{:seon.vector-test.a/name "1"}]}]}]))))
       (testing "an empty vector is present"
         (is (= [{:db/id "e" :seon.db.vector/present #{items} :seon.db.vector/cells []}]
                (encoded projection [{:db/id "e" items []}]))))
       (testing "a possibly existing entity's vectors and raw vector operations batch into one request call at the end"
         (is (= [{:seon.vector-test.p/id "p"}
                 [:db/add 7 :seon.vector-test.a/name "unrelated"]
                 [:vectors [[[:seon.vector-test.p/id "p"] items :assert ["x"]]
                            [5 items :add "y"]
                            [5 items :retract "z"]
                            [5 items :clear nil]
                            [5 items :clear nil]]]]
                (encoded projection [{:seon.vector-test.p/id "p" items ["x"]}
                                     [:db/add 5 items "y"]
                                     [:db/add 7 :seon.vector-test.a/name "unrelated"]
                                     [:db/retract 5 items "z"]
                                     [:db/retract 5 items]
                                     [:db.fn/retractAttribute 5 items]]))))
       (testing "a tempid whose identity another operation asserts is written by request, never as new cells"
         (is (= [[:db/add "t" :seon.vector-test.p/id "p"]
                 {:db/id "t" :seon.vector-test.p/id "p"}
                 [:vectors [[[:seon.vector-test.p/id "p"] items :assert ["x"]]]]]
                (encoded projection [[:db/add "t" :seon.vector-test.p/id "p"] {:db/id "t" items ["x"]}]))))
       (testing "refusals name the rule"
         (is (= ::schema.datahike/vector-tempid-operation
                (refusal-rule #(encoded projection [[:db/add "r" items "a"]]))))
         (is (= ::schema.datahike/vector-value-not-sequential
                (refusal-rule #(encoded projection [{:db/id "r" items "a"}])))))
       (testing "without cells declared, today's storage is unchanged"
         (let [legacy (without-cells (vector-projection (schema/handed-projection) subject-forms))]
           (is (= [{:db/id "n" items ["b" "a"]}]
                  (encoded legacy [{:db/id "n" items ["b" "a"]}])))))))))

;;; ---------------------------------------------------------------------------
;;; S5: selectors lowered, physical pulls decoded
;;; ---------------------------------------------------------------------------

(deftest selectors-lower-to-cells-and-decode-to-vectors
  (support/with-database
   (fn [_connection]
     (let [projection (-> (schema/handed-projection)
                          (vector-projection subject-forms)
                          (vector-projection bridge-forms))
           physical-of #(first (schema.datahike/vector-pull projection %))
           decoded #(schema.datahike/decode-vectors projection (second (schema.datahike/vector-pull projection %1)) %2)
           items :seon.vector-test.p/items
           kids :seon.vector-test.p/kids
           selector [[items :as :xs :limit 2] [:seon.vector-test.p/pairs :default []]
                     {kids [:seon.vector-test.k/name {:seon.vector-test.k/args [:seon.vector-test.a/name]}]}]
           cell (fn [position attr value] {:db/id (+ 100 position) :seon.db.vector/position position attr [value]})
           physical {:db/id 1
                     :seon.db.vector/present [items kids :seon.vector-test.p/keys]
                     :seon.db.vector/cells
                     [(cell 2 items "c") (cell 0 items "a") (cell 1 items "b")
                      (cell 0 kids {:seon.vector-test.k/name "A"
                                    :seon.db.vector/present [:seon.vector-test.k/args]
                                    :seon.db.vector/cells [(cell 1 :seon.vector-test.k/args {:seon.vector-test.a/name "2"})
                                                           (cell 0 :seon.vector-test.k/args {:seon.vector-test.a/name "1"})]})]}]
       (testing "vector options leave the physical selector; one shared cells relation serves every vector"
         (is (= [:db/id
                 :seon.db.vector/present
                 {:seon.db.vector/cells [:seon.db.vector/position items :seon.vector-test.p/pairs
                                         {kids [:seon.vector-test.k/name
                                                :seon.db.vector/present
                                                {:seon.db.vector/cells [:seon.db.vector/position
                                                                        {:seon.vector-test.k/args [:seon.vector-test.a/name]}]}]}]}]
                (physical-of selector))))
       (testing "every entry of a multi-entry selector map is read (the first alone was, 2026-09-27 review)"
         (is (= [:seon.db.vector/present
                 {:seon.db.vector/cells [:seon.db.vector/position {kids [:seon.vector-test.k/name]} {:seon.vector-test.p/peers [:db/id]}]}]
                (physical-of [{kids [:seon.vector-test.k/name] :seon.vector-test.p/peers [:db/id]}]))))
       (testing "decoding orders by position, applies options to the logical vector and removes bridge keys"
         (is (= {:xs ["a" "b"] :seon.vector-test.p/pairs []
                 kids [{:seon.vector-test.k/name "A" :seon.vector-test.k/args [{:seon.vector-test.a/name "1"} {:seon.vector-test.a/name "2"}]}]}
                (decoded selector physical))))
       (testing "the legacy list forms mean the same options"
         (is (= {items ["a" "b"] :seon.vector-test.p/pairs []}
                (decoded '[(limit :seon.vector-test.p/items 2) (default :seon.vector-test.p/pairs [])] physical))))
       (testing "a wildcard restores every present vector, empty ones included"
         (let [decoded (decoded '[*] physical)]
           (is (= ["a" "b" "c"] (get decoded items)))
           (is (= [] (get decoded :seon.vector-test.p/keys)))
           (is (= [{:seon.vector-test.a/name "1"} {:seon.vector-test.a/name "2"}]
                  (mapv #(dissoc % :db/id) (get-in decoded [kids 0 :seon.vector-test.k/args]))))
           (is (not-any? #(= "seon.db.vector" (namespace %)) (keys decoded)))))
       (testing "a reverse vector attribute reads the owning parents"
         (is (= [{:seon.vector-test.p/_peers [{:seon.db.vector/_cells [:db/id]}]}]
                (physical-of [:seon.vector-test.p/_peers])))
         (is (= {:seon.vector-test.p/_peers [{:db/id 1}]}
                (decoded [:seon.vector-test.p/_peers]
                         {:seon.vector-test.p/_peers [{:seon.db.vector/_cells {:db/id 1}}
                                                      {:seon.db.vector/_cells {:db/id 1}}]}))))
       (testing "a selector naming no vector needs no lowering"
         (is (nil? (schema.datahike/vector-pull projection [:seon.vector-test.p/id])))
         (is (some? (schema.datahike/vector-pull projection '[*]))))))))

;;; ---------------------------------------------------------------------------
;;; A database world: the synthetic declarations installed and stored as rows
;;; ---------------------------------------------------------------------------

(defn- stored-attributes
  "The attribute keys of `forms`, entity map schemas excluded."
  {:malli/schema [:=> [:cat [:vector [:tuple :qualified-keyword :seon.schema/malli-form]]] [:vector :qualified-keyword]]}
  [forms]
  (into [] (comp (remove (fn [[_ definition]] (and (vector? definition) (= :map (first definition)))))
                 (map first))
        forms))

(defn- with-vector-world
  "Call `body` with a connection on an isolated branch whose writer stores
   vectors in cells: the bridge and subject declarations installed and written
   as schema rows, so the writer's carried projection declares them."
  {:malli/schema [:=> [:cat :seon.instrument/callable]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "A fixture returns its body's arbitrary result unchanged."}]]}
  [body]
  (let [forms (into subject-forms bridge-forms)]
    (support/with-database
     {:seon.test-support/extra-schema
      (schema.datahike/malli->datahike-schema-in
       (vector-projection (schema/handed-projection) forms) (stored-attributes forms))}
     (fn [connection]
       (support/transacted! connection (support/schema-entities @connection forms))
       (body connection)))))

(defn- tx!
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction] :seon.db/transaction-report]}
  [connection transaction]
  (support/transacted! connection transaction))

(defn- refused?
  "True when writing `transaction` refuses with `cause` named anywhere in the refusal."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction :qualified-keyword] :boolean]}
  [connection transaction cause]
  (let [result (db/transact! connection transaction)]
    (boolean (and (map? result) (nil? (:db-after result))
                  (some #{cause} (tree-seq coll? seq result))))))

(defn- pulled
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/pull-selector :seon.schema/value] [:or :nil :map]]}
  [connection selector entity]
  (db/pull @connection selector entity))

(defn- effective
  "Datoms a report changed besides its transaction entity."
  {:malli/schema [:=> [:cat :seon.db/transaction-report] :int]}
  [report]
  (count (remove #(<= (long const/tx0) (long (:e %))) (:tx-data report))))

(defn- element-ids
  "The element entity ids of `parent`'s vector `attribute`, in position order."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.schema/value :qualified-keyword] [:vector :int]]}
  [connection parent attribute]
  (mapv :db/id (get (pulled connection [{attribute [:db/id]}] parent) attribute)))

(def ^:private p [:seon.vector-test.p/id "p"])

;;; ---------------------------------------------------------------------------
;;; S3 + S4: the writer's vector function and final validation
;;; ---------------------------------------------------------------------------

(deftest vectors-round-trip-with-order-duplicates-and-presence
  (with-vector-world
   (fn [c]
     (testing "order, duplicates, two-keyword elements and tuples"
       (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "a" "b"]
                :seon.vector-test.p/keys [:seon.vector-test.p/id :seon.vector-test.t/id]
                :seon.vector-test.p/pairs [[1 "x"] [0 "y"] [1 "x"]]}])
       (is (= {:seon.vector-test.p/items ["b" "a" "b"]
               :seon.vector-test.p/keys [:seon.vector-test.p/id :seon.vector-test.t/id]
               :seon.vector-test.p/pairs [[1 "x"] [0 "y"] [1 "x"]]}
              (pulled c [:seon.vector-test.p/items :seon.vector-test.p/keys :seon.vector-test.p/pairs] p))))
     (testing "a new component's inline cells, then an existing entity's diff"
       (tx! c [{:seon.vector-test.p/id "n" :seon.vector-test.p/kids
                [{:seon.vector-test.k/name "k" :seon.vector-test.k/args [{:seon.vector-test.a/name "x"} {:seon.vector-test.a/name "x"}]}]}])
       (let [[kid] (element-ids c [:seon.vector-test.p/id "n"] :seon.vector-test.p/kids)]
         (tx! c [{:db/id kid :seon.vector-test.k/args [{:seon.vector-test.a/name "y"} {:seon.vector-test.a/name "x"} {:seon.vector-test.a/name "x"}]}])
         (is (= ["y" "x" "x"] (mapv :seon.vector-test.a/name (:seon.vector-test.k/args (pulled c '[*] kid)))))))
     (testing "[] is present and empty; retracting the attribute makes it absent"
       (tx! c [{:seon.vector-test.p/id "empty" :seon.vector-test.p/items []} {:seon.vector-test.p/id "absent"}])
       (is (= [] (:seon.vector-test.p/items (pulled c '[*] [:seon.vector-test.p/id "empty"]))))
       (is (not (contains? (pulled c '[*] [:seon.vector-test.p/id "absent"]) :seon.vector-test.p/items)))
       (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items []}])
       (is (= {:seon.vector-test.p/items []} (pulled c [:seon.vector-test.p/items] p)))
       (tx! c [[:db/retract p :seon.vector-test.p/items]])
       (is (= {:seon.vector-test.p/items :none} (pulled c [[:seon.vector-test.p/items :default :none]] p))))
     (testing "a component vector read without a sub-selector holds logical elements (V3)"
       (let [[kid] (:seon.vector-test.p/kids (pulled c [:seon.vector-test.p/kids] [:seon.vector-test.p/id "n"]))]
         (is (= "k" (:seon.vector-test.k/name kid)))
         (is (= [{:seon.vector-test.a/name "y"} {:seon.vector-test.a/name "x"} {:seon.vector-test.a/name "x"}]
                (mapv #(dissoc % :db/id) (:seon.vector-test.k/args kid))))
         (is (not-any? #(= "seon.db.vector" (namespace %)) (keys kid)))))
     (testing "a data value is never read as an entity, whatever its keys (V2: it walked a UUID)"
       (let [note {:seon.db.vector/cells #uuid "00000000-0000-0000-0000-000000000001"}]
         (tx! c [{:seon.vector-test.p/id "d" :seon.vector-test.p/note note}])
         (is (= note (:seon.vector-test.p/note (pulled c '[*] [:seon.vector-test.p/id "d"]))))
         (is (= note (:seon.vector-test.p/note (db/q '[:find (pull ?e [*]) . :where [?e :seon.vector-test.p/id "d"]] @c))))))
     (testing "reasserting an unchanged vector emits nothing"
       (tx! c [{:seon.vector-test.p/id "u" :seon.vector-test.p/items ["a" "b" "a"]
                :seon.vector-test.p/kids [{:seon.vector-test.k/name "x"} {:seon.vector-test.k/name "y"}]}])
       (is (zero? (effective (tx! c [{:seon.vector-test.p/id "u" :seon.vector-test.p/items ["a" "b" "a"]
                                      :seon.vector-test.p/kids [{:seon.vector-test.k/name "x"} {:seon.vector-test.k/name "y"}]}]))))))))

(deftest peer-vectors-keep-ref-semantics
  (with-vector-world
   (fn [c]
     (testing "order, duplicates and a target created in the same transaction"
       (tx! c [{:seon.vector-test.t/id "t1"}])
       (tx! c [{:db/id "t2" :seon.vector-test.t/id "t2"}
               {:seon.vector-test.p/id "p" :seon.vector-test.p/peers [[:seon.vector-test.t/id "t2"] [:seon.vector-test.t/id "t1"] "t2"]}])
       (is (= [{:seon.vector-test.t/id "t2"} {:seon.vector-test.t/id "t1"} {:seon.vector-test.t/id "t2"}]
              (:seon.vector-test.p/peers (pulled c [{:seon.vector-test.p/peers [:seon.vector-test.t/id]}] p)))))
     (testing "retracting a target alone refuses: the element would vanish"
       (is (refused? c [[:db/retractEntity [:seon.vector-test.t/id "t1"]]] :seon.db/vector-element-swept)))
     (testing "retracting a target with the vector reasserted is admitted"
       (tx! c [[:db/retractEntity [:seon.vector-test.t/id "t1"]]
               {:seon.vector-test.p/id "p" :seon.vector-test.p/peers [[:seon.vector-test.t/id "t2"]]}])
       (is (= [{:seon.vector-test.t/id "t2"}]
              (:seon.vector-test.p/peers (pulled c [{:seon.vector-test.p/peers [:seon.vector-test.t/id]}] p)))))
     (testing "retracting the parent removes its cells; the target survives"
       (let [e (:db/id (db/pull @c [:db/id] p))
             cells (mapv :v (d/datoms @c :eavt e :seon.db.vector/cells))]
         (is (seq cells))
         (tx! c [[:db/retractEntity p]])
         (is (every? #(empty? (d/datoms @c :eavt %)) cells)))
       (is (= {:seon.vector-test.t/id "t2"} (pulled c [:seon.vector-test.t/id] [:seon.vector-test.t/id "t2"])))))))

(deftest raw-vector-operations-keep-a-meaning
  (with-vector-world
   (fn [c]
     (tx! c [{:seon.vector-test.t/id "t1"} {:seon.vector-test.t/id "t2"}
             {:seon.vector-test.p/id "p" :seon.vector-test.p/items ["a" "b" "a"] :seon.vector-test.p/peers []}])
     (testing "add appends; retract of a value removes every occurrence"
       (tx! c [[:db/add p :seon.vector-test.p/items "c"]
               [:db/add p :seon.vector-test.p/peers [:seon.vector-test.t/id "t1"]]
               [:db/add p :seon.vector-test.p/peers [:seon.vector-test.t/id "t2"]]])
       (tx! c [[:db/retract p :seon.vector-test.p/items "a"]])
       (is (= {:seon.vector-test.p/items ["b" "c"] :seon.vector-test.p/peers [{:seon.vector-test.t/id "t1"} {:seon.vector-test.t/id "t2"}]}
              (pulled c [:seon.vector-test.p/items {:seon.vector-test.p/peers [:seon.vector-test.t/id]}] p))))
     (testing "one identity asserting a vector twice: the last wins"
       (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["x"]}
               {:seon.vector-test.p/id "p" :seon.vector-test.p/items ["y" "z"]}])
       (is (= {:seon.vector-test.p/items ["y" "z"]} (pulled c [:seon.vector-test.p/items] p))))
     (testing "a raw operation on a new entity and one tempid asserting a vector twice refuse"
       (is (refused? c [[:db/add "r" :seon.vector-test.p/items "a"]] ::schema.datahike/vector-tempid-operation))
       (is (refused? c [{:db/id "x" :seon.vector-test.p/items ["a"]} {:db/id "x" :seon.vector-test.p/items ["b"]}]
                     :seon.db/vector-positions))))))

(deftest component-vectors-keep-identity
  (with-vector-world
   (fn [c]
     (testing "retracting the parent cascades cells and elements"
       (let [entity-datoms #(count (remove (fn [datom] (<= (long const/tx0) (long (:e datom)))) (d/datoms @c :eavt)))
             before (entity-datoms)]
         (tx! c [{:seon.vector-test.p/id "gone" :seon.vector-test.p/kids
                  [{:seon.vector-test.k/name "x" :seon.vector-test.k/args [{:seon.vector-test.a/name "1"} {:seon.vector-test.a/name "2"}]}
                   {:seon.vector-test.k/name "y"}]}])
         (tx! c [[:db/retractEntity [:seon.vector-test.p/id "gone"]]])
         (is (= before (entity-datoms)))))
     (testing "a reorder by id or by value keeps both entities"
       (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/kids [{:seon.vector-test.k/name "A" :seon.vector-test.k/args [{:seon.vector-test.a/name "1"}]}
                                                                    {:seon.vector-test.k/name "B"}]}])
       (let [[a b] (element-ids c p :seon.vector-test.p/kids)]
         (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/kids [{:db/id b} {:db/id a}]}])
         (is (= [b a] (element-ids c p :seon.vector-test.p/kids)))
         (is (= ["B" "A"] (mapv :seon.vector-test.k/name (:seon.vector-test.p/kids (pulled c '[*] p)))))
         (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/kids [{:seon.vector-test.k/name "A" :seon.vector-test.k/args [{:seon.vector-test.a/name "1"}]}
                                                                      {:seon.vector-test.k/name "B"}]}])
         (is (= [a b] (element-ids c p :seon.vector-test.p/kids)))))
     (testing "replacing one element keeps the others and leaves no ownerless child"
       (tx! c [{:seon.vector-test.p/id "r" :seon.vector-test.p/kids [{:seon.vector-test.k/name "A"} {:seon.vector-test.k/name "B"} {:seon.vector-test.k/name "C"}]}])
       (let [[a b cc] (element-ids c [:seon.vector-test.p/id "r"] :seon.vector-test.p/kids)]
         (tx! c [{:seon.vector-test.p/id "r" :seon.vector-test.p/kids [{:seon.vector-test.k/name "A"} {:seon.vector-test.k/name "Z"} {:seon.vector-test.k/name "C"}]}])
         (let [[a' _ c'] (element-ids c [:seon.vector-test.p/id "r"] :seon.vector-test.p/kids)]
           (is (= [a cc] [a' c']))
           (is (empty? (d/datoms @c :eavt b)) "the replaced component is destroyed with its cell"))))
     (testing "a component map named only by a tempid (a writer's row) is matched by its value"
       (let [before (element-ids c p :seon.vector-test.p/kids)
             report (tx! c [{:seon.vector-test.p/id "p"
                             :seon.vector-test.p/kids [{:db/id "a-row" :seon.vector-test.k/name "A"
                                                        :seon.vector-test.k/args [{:db/id "a-arg" :seon.vector-test.a/name "1"}]}
                                                       {:db/id "b-row" :seon.vector-test.k/name "B"}]}])]
         (is (zero? (effective report)))
         (is (= before (element-ids c p :seon.vector-test.p/kids)))))
     (testing "equal independent maps stay two entities"
       (tx! c [{:seon.vector-test.p/id "two" :seon.vector-test.p/kids [{:seon.vector-test.k/name "x"} {:seon.vector-test.k/name "x"}]}])
       (is (= 2 (count (distinct (element-ids c [:seon.vector-test.p/id "two"] :seon.vector-test.p/kids))))))
     (testing "one component twice refuses: a component has one owner"
       (let [[a] (element-ids c p :seon.vector-test.p/kids)]
         (is (refused? c [{:seon.vector-test.p/id "p" :seon.vector-test.p/kids [{:db/id a} {:db/id a}]}]
                       :seon.db/multiple-component-owners))))
     (testing "an append is one cell: link, position, element and the component's datom"
       (tx! c [{:seon.vector-test.p/id "app" :seon.vector-test.p/kids [{:seon.vector-test.k/name "A"}] :seon.vector-test.p/items ["a"]}])
       (is (= 7 (effective (tx! c [{:seon.vector-test.p/id "app" :seon.vector-test.p/kids [{:seon.vector-test.k/name "A"} {:seon.vector-test.k/name "B"}]
                                    :seon.vector-test.p/items ["a" "b"]}])))))
     (testing "a nested vector inside a component updates in place"
       (let [[a] (element-ids c [:seon.vector-test.p/id "app"] :seon.vector-test.p/kids)]
         (tx! c [{:db/id a :seon.vector-test.k/args [{:seon.vector-test.a/name "2"} {:seon.vector-test.a/name "1"}]}])
         (is (= [{:seon.vector-test.a/name "2"} {:seon.vector-test.a/name "1"}]
                (get-in (pulled c [{:seon.vector-test.p/kids [{:seon.vector-test.k/args [:seon.vector-test.a/name]}]}]
                                [:seon.vector-test.p/id "app"])
                        [:seon.vector-test.p/kids 0 :seon.vector-test.k/args]))))))))

(deftest the-writer-refuses-vector-storage-faults
  (with-vector-world
   (fn [c]
     (testing "a vector attribute's datom outside a cell (a write that bypassed the encoder)"
       (tx! c [{:seon.vector-test.p/id "p"}])
       (let [e (:db/id (pulled c [:db/id] p))]
         (is (refused? c [(with-meta [:db/add e :seon.vector-test.p/items "raw"] {:seon.schema.datahike/encoded-operation true})]
                       :seon.db/vector-member-outside-cell))))
     (testing "cells written around the encoder refuse by cause"
       (let [e (:db/id (pulled c [:db/id] p))
             raw (fn [& operations] (mapv #(with-meta % {:seon.schema.datahike/encoded-operation true}) operations))]
         (is (refused? c (raw {:db/id "c" :seon.db.vector/position 0 :seon.vector-test.p/items ["a" "b"]}
                              [:db/add e :seon.db.vector/cells "c"] [:db/add e :seon.db.vector/present :seon.vector-test.p/items])
                       :seon.db/vector-cell-ambiguous))
         (is (refused? c (raw {:db/id "c" :seon.db.vector/position 0 :seon.vector-test.p/items ["a"]}
                              [:db/add e :seon.db.vector/cells "c"])
                       :seon.db/vector-cells-without-presence))
         (is (refused? c (raw {:db/id "c" :seon.db.vector/position 1 :seon.vector-test.p/items ["a"]}
                              [:db/add e :seon.db.vector/cells "c"] [:db/add e :seon.db.vector/present :seon.vector-test.p/items])
                       :seon.db/vector-positions))))
     (testing "a declared bound on the vector refuses at the write"
       (let [forms [[:seon.vector-test.q/id [:string {:seon.db/identity true}]]
                    [:seon.vector-test.q/two [:vector {:max 2} :string]]
                    [:seon.vector-test.q/row [:map {:seon.db/attributes true :seon.program/partition :seon.data} [:seon.vector-test.q/id :seon.vector-test.q/id]
                                              [:seon.vector-test.q/two {:optional true} :seon.vector-test.q/two]]]]]
         (tx! c (schema.datahike/malli->datahike-schema-in
                 (vector-projection (db/carried-projection @c) forms) (stored-attributes forms)))
         (tx! c (support/schema-entities @c forms))
         (tx! c [{:seon.vector-test.q/id "q" :seon.vector-test.q/two ["a" "b"]}])
         (is (refused? c [{:seon.vector-test.q/id "q" :seon.vector-test.q/two ["a" "b" "c"]}] :seon.db/transaction-refused)
             "the submitted vector refuses before it is written")
         (is (= ["a" "b" "c"]
                (:seon.db/offending (db/transact! c [[:db/add [:seon.vector-test.q/id "q"] :seon.vector-test.q/two "c"]])))
             "an append past the bound refuses at the final report, naming the whole logical vector"))))))

;;; ---------------------------------------------------------------------------
;;; S6: reads through seon.db
;;; ---------------------------------------------------------------------------

(deftest pulls-read-logical-vectors-at-every-basis
  (with-vector-world
   (fn [c]
     (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["a" "b"]}])
     (let [t (db/basis-t @c)
           captured (atom [])
           pulled-read (binding [db/*read-evidence-sink* captured]
                         (db/pull @c [:seon.vector-test.p/items] p))
           evidence (db/read-evidence @captured {:seon.db/retain-read-results? true})]
       (testing "the recorded replay request is the logical selector, and replays to the same vector"
         (is (= {:seon.vector-test.p/items ["a" "b"]} pulled-read))
         (is (= [[:seon.vector-test.p/items] p]
                (get-in (first evidence) [:seon.db/read-request :seon.db/pull-arguments])))
         (is (= pulled-read (#'db/replay-read @c (:seon.db/read-request (first evidence))))))
       (testing "a reorder invalidates an ordered read; an unrelated write does not"
         (tx! c [{:seon.vector-test.t/id "unrelated"}])
         (is (true? (db/read-evidence-current? @c evidence)))
         (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "a"]}])
         (is (false? (db/read-evidence-current? @c evidence))))
       (testing "as-of reads the older vector by that value's own declarations"
         (is (= {:seon.vector-test.p/items ["a" "b"]} (db/pull (db/as-of @c t) [:seon.vector-test.p/items] p)))
         (is (= {:seon.vector-test.p/items ["b" "a"]} (db/pull @c [:seon.vector-test.p/items] p))))
       (testing "a history or since view refuses a vector pull by name"
         (is (true? (:seon.db/vector-temporal-view (db/pull (db/history @c) [:seon.vector-test.p/items] p))))
         (is (true? (:seon.db/vector-temporal-view (db/pull (db/since @c t) '[*] p)))))))))

;;; ---------------------------------------------------------------------------
;;; S7: Datalog membership
;;; ---------------------------------------------------------------------------

(deftest datalog-over-a-vector-attribute-means-membership
  (with-vector-world
   (fn [c]
     (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "a" "b"] :seon.vector-test.p/keys [:seon.vector-test.k/k]}
             {:seon.vector-test.p/id "q" :seon.vector-test.p/items ["a"]}
             {:seon.vector-test.p/id "e" :seon.vector-test.p/items []}
             {:seon.vector-test.p/id "r"}])
     (let [database @c]
       (testing "members, and owners by member (a reverse lookup)"
         (is (= #{"a" "b"} (set (db/q '[:find [?v ...] :in $ ?id :where [?e :seon.vector-test.p/id ?id] [?e :seon.vector-test.p/items ?v]]
                                      database "p"))))
         (is (= #{"p" "q"} (set (db/q '[:find [?id ...] :where [?e :seon.vector-test.p/items "a"] [?e :seon.vector-test.p/id ?id]]
                                      database)))))
       (testing "an attribute bound by input, by collection input, or free"
         (is (= #{["b"] ["a"]} (db/q '[:find ?v :in $ ?a :where [?e :seon.vector-test.p/id "p"] [?e ?a ?v]]
                                     database :seon.vector-test.p/items)))
         (is (= #{[:seon.vector-test.p/items "a"] [:seon.vector-test.p/id "q"]}
                (db/q '[:find ?a ?v :in $ [?a ...] :where [?e :seon.vector-test.p/id "q"] [?e ?a ?v]]
                      database [:seon.vector-test.p/items :seon.vector-test.p/id])))
         (is (= #{[:seon.vector-test.p/id "q"] [:seon.vector-test.p/items "a"]}
                (db/q '[:find ?a ?v :where [?e :seon.vector-test.p/id "q"] [?e ?a ?v]] database))
             "free attributes see members, never cells, positions or presence"))
       (testing "not, or, rules and missing? (an empty vector is not missing)"
         (is (= #{"q" "e" "r"} (set (db/q '[:find [?id ...] :where [?e :seon.vector-test.p/id ?id] (not [?e :seon.vector-test.p/items "b"])]
                                          database))))
         (is (= #{"e" "r"} (set (db/q '[:find [?id ...] :where [?e :seon.vector-test.p/id ?id]
                                        (not [?e :seon.vector-test.p/items ?item]
                                             [?other :seon.vector-test.p/items ?item]
                                             [(not= ?e ?other)])]
                                      database)))
             "a not joins only what its siblings bind; ?item and ?other stay local (it lowered to a not-join on them: a Datahike NPE, r6)")
         (is (= #{"p" "r"} (set (db/q '[:find [?id ...] :where [?e :seon.vector-test.p/id ?id]
                                        (or [?e :seon.vector-test.p/items "b"] [?e :seon.vector-test.p/id "r"])]
                                      database))))
         (is (= ["p"] (db/q '[:find [?id ...] :in $ % :where [?e :seon.vector-test.p/id ?id] (has ?e "b")]
                            database '[[(has ?e ?v) [?e :seon.vector-test.p/items ?v]]])))
         (is (= ["r"] (db/q '[:find [?id ...] :where [?e :seon.vector-test.p/id ?id] [(missing? $ ?e :seon.vector-test.p/items)]]
                            database))))
       (testing "missing? of an input-bound entity or attribute reads presence too (review 2026-09-27)"
         (let [id #(:db/id (db/pull database [:db/id] [:seon.vector-test.p/id %]))]
           (is (= #{} (db/q '[:find ?e :in $ ?e ?a :where [(missing? $ ?e ?a)]] database (id "e") :seon.vector-test.p/items)))
           (is (= #{[(id "r")]} (db/q '[:find ?e :in $ ?e ?a :where [(missing? $ ?e ?a)]] database (id "r") :seon.vector-test.p/items)))
           (is (= #{} (db/q '[:find ?e :in $ ?e :where [(missing? $ ?e :seon.vector-test.p/items)]] database (id "e"))))
           (is (= #{[(id "e")]} (db/q '[:find ?e :in $ ?e ?a :where [(missing? $ ?e ?a)]] database (id "e") :seon.vector-test.p/peers)))))
       (testing "a query naming only cardinality-one attributes never derives the declarations (V5)"
         (let [query '[:find ?entity . :in $ ?attribute :where [?entity ?attribute]]
               ask #(#'db/may-read-vectors? (:schema database) (dq/memoized-parse-query %1) {:query %1 :args %2})]
           (is (false? (ask query [database :seon.vector-test.p/id])))
           (is (true? (ask query [database :seon.vector-test.p/items])))))
       (testing "get-else over a vector attribute refuses by name"
         (is (= 'get-else (:seon.db/vector-scalar-function
                           (db/q '[:find ?v :where [?e :seon.vector-test.p/id "p"] [(get-else $ ?e :seon.vector-test.p/items "none") ?v]]
                                 database)))))
       (testing "pull find elements, literal and input-bound, return whole vectors"
         (is (= {:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "a" "b"]}
                (db/q '[:find (pull ?e [:seon.vector-test.p/id :seon.vector-test.p/items]) . :where [?e :seon.vector-test.p/id "p"]]
                      database)))
         (is (= [{:seon.vector-test.p/items ["b" "a" "b"]}]
                (db/q '[:find [(pull ?e ?s) ...] :in $ ?s :where [?e :seon.vector-test.p/id "p"]]
                      database [:seon.vector-test.p/items])))
         (is (= [{:row {:seon.vector-test.p/items ["b" "a" "b"]} :id "p"}]
                (db/q '[:find (pull ?e [:seon.vector-test.p/items]) ?id :keys row id
                        :where [?e :seon.vector-test.p/id "p"] [?e :seon.vector-test.p/id ?id]]
                      database))
             "a return map decodes its pull column (it threw ClassCastException, review 2026-09-27)")
         (is (= [{:seon.vector-test.p/items ["b" "a" "b"]}]
                (db/q '[:find [(pull ?e sel) ...] :in $ sel :where [?e :seon.vector-test.p/id "p"]]
                      database [:seon.vector-test.p/items]))
             "a selector input bound without a leading ? is lowered too (V1: it broke dir, 2026-09-27)")
         (is (= (db/pull database '[*] p)
                (db/q '[:find (pull ?e [*]) . :where [?e :seon.vector-test.p/id "p"]] database))
             "a query's wildcard pull reads every vector a pull reads (V4)")
         (is (= {:seon.vector-test.p/items ["b" "a" "b"]}
                (db/q '[:find (pull ?e [:seon.vector-test.p/items]) . :in $ ?e] database p))
             "a query with no :where lowers its pull (the lowering refused a nil :where, 2026-09-27)"))))))

(deftest datalog-over-history-is-an-exact-membership-change-view
  (with-vector-world
   (fn [c]
     (tx! c [{:seon.vector-test.t/id "t1"} {:seon.vector-test.t/id "t2"}
             {:seon.vector-test.p/id "p" :seon.vector-test.p/items ["a" "b"] :seon.vector-test.p/peers [[:seon.vector-test.t/id "t1"]]}])
     (let [t (db/basis-t @c)]
       (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "c" "b"]
                :seon.vector-test.p/peers [[:seon.vector-test.t/id "t2"] [:seon.vector-test.t/id "t1"]]}])
       (let [database @c
             e (:db/id (db/pull database [:db/id] p))
             t2 (:db/id (db/pull database [:db/id] [:seon.vector-test.t/id "t2"]))
             changes (db/since (db/history database) t)]
         (is (= #{["a" true] ["a" false] ["b" true] ["c" true]}
                (db/q '[:find ?v ?added :in $ ?e :where [?e :seon.vector-test.p/items ?v _ ?added]] (db/history database) e))
             "history: every membership assertion and retraction")
         (is (= #{["c" true] ["a" false] ["b" true]}
                (db/q '[:find ?v ?added :in $ ?e :where [?e :seon.vector-test.p/items ?v _ ?added]] changes e))
             "since: a changed element is a new cell; a moved one changes only its position")
         (is (= #{[t2 true]}
                (db/q '[:find ?x ?added :in $ ?e :where [?e :seon.vector-test.p/peers ?x _ ?added]] changes e))))))))

;;; ---------------------------------------------------------------------------
;;; S8: reports read as logical facts
;;; ---------------------------------------------------------------------------

(deftest reports-name-the-vector-owner
  (with-vector-world
   (fn [c]
     (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["a" "b"]}])
     (let [e (:db/id (db/pull @c [:db/id] p))
           report (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "a" "c"]}])
           logical (remove #(<= (long const/tx0) (long (:e %))) (db/logical-datoms report))]
       (testing "element and position datoms read on the parent; bridge links drop"
         (is (seq logical))
         (is (every? #(= e (:e %)) logical))
         (is (every? #(= :seon.vector-test.p/items (:a %)) logical))
         (is (= #{["c" true]} (into #{} (comp (remove :seon.db.vector/position) (map (juxt :v :added))) logical)))
         (is (= #{["b" 0] ["a" 1]} (into #{} (comp (filter :seon.db.vector/position) (map (juxt :v :seon.db.vector/position))) logical))))
       (testing "a reorder-only write names its owner's identity"
         (let [reorder (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["c" "b" "a"]}])]
           (is (not-any? #(= :seon.vector-test.p/items (:a %)) (remove #(<= (long const/tx0) (long (:e %))) (:tx-data reorder)))
               "the stored report holds only position datoms")
           (is (contains? (seon.fn/report-identities reorder) [:seon.vector-test.p/id "p"]))))
       (testing "a transaction's rendering counts logical facts on the parent"
         (is (= 1 (count (:seon.db.report/entities (db/render-transaction-ai report))))))))))

;;; ---------------------------------------------------------------------------
;;; S9: comparisons see order where storage keeps it
;;; ---------------------------------------------------------------------------

(deftest comparisons-see-order-only-where-storage-keeps-it
  (with-vector-world
   (fn [c]
     (tx! c [{:seon.vector-test.p/id "p" :seon.vector-test.p/items ["b" "a" "b"] :seon.vector-test.p/tags [:x/y]}])
     (testing "a vector stored in cells compares in order; a set as a set"
       (is (= ["b" "a" "b"] (db/stored-collection @c :seon.vector-test.p/items ["b" "a" "b"])))
       (is (not= (db/stored-collection @c :seon.vector-test.p/items ["a" "b"])
                 (db/stored-collection @c :seon.vector-test.p/items ["b" "a"])))
       (is (= #{:x/y} (db/stored-collection @c :seon.vector-test.p/tags [:x/y :x/y]))))
     (testing "an entity's stored values read its vectors whole, without bridge keys"
       (let [stored (db/stored-entity @c (:db/id (db/pull @c [:db/id] p)))]
         (is (= ["b" "a" "b"] (:seon.vector-test.p/items stored)))
         (is (not-any? #(= "seon.db.vector" (namespace %)) (keys stored))))))))

;;; ---------------------------------------------------------------------------
;;; S10: the switch moves stored members into cells
;;; ---------------------------------------------------------------------------

(deftest the-migration-moves-members-into-cells-and-keeps-identities
  (with-vector-world
   (fn [c]
     (let [as-stored #(with-meta % {:seon.schema.datahike/encoded-operation true})
           report (tx! c [(as-stored {:seon.vector-test.p/id "L" :seon.vector-test.p/items ["b" "a"]
                                    :seon.vector-test.p/kids [{:seon.vector-test.k/name "x"} {:seon.vector-test.k/name "y"}]})
                          [:db.fn/call db/vector-migration-tx (db/carried-projection @c)]])
           e (:db/id (db/pull @c [:db/id] [:seon.vector-test.p/id "L"]))
           added-kids (into [] (comp (filter #(and (= e (:e %)) (= :seon.vector-test.p/kids (:a %)) (:added %))) (map :v))
                            (:tx-data report))]
       (testing "members written as the store held them before the switch move into cells, in datom order"
         (is (empty? (d/datoms @c :eavt e :seon.vector-test.p/items)))
         (is (= {:seon.vector-test.p/items ["a" "b"]
                 :seon.vector-test.p/kids [{:seon.vector-test.k/name "x"} {:seon.vector-test.k/name "y"}]}
                (db/pull @c [:seon.vector-test.p/items {:seon.vector-test.p/kids [:seon.vector-test.k/name]}]
                         [:seon.vector-test.p/id "L"]))))
       (testing "the component elements keep their entities (a retract does not cascade)"
         (is (= (sort added-kids) (sort (element-ids c [:seon.vector-test.p/id "L"] :seon.vector-test.p/kids))))
         (is (= 1 (count (d/q '[:find [?k ...] :where [?k :seon.vector-test.k/name "x"]] @c)))))
       (testing "running it again moves nothing"
         (is (empty? (db/vector-migration-tx @c (db/carried-projection @c)))))
       (testing "a member written around the encoder without the migration refuses"
         (is (refused? c [(as-stored [:db/add e :seon.vector-test.p/items "raw"])]
                       :seon.db/vector-member-outside-cell)))))))
