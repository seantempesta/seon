(ns seon.schema.vector-storage-test
  "A `[:vector T]` attribute stores its elements in bridge cells and reads
  back as the same vector: order, duplicates, `[]` distinct from absent
  (docs/research/agent-platform/vector-storage-design-2026-09-27.md).

  Until `seon.db.vector.edn` is packaged the bridge attributes are synthetic
  here: [[vector-projection]] declares them with the test's own vector
  attributes on the executing projection."
  (:require [clojure.test :refer [deftest is testing]]
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
   [:seon.db.vector/present [:set :qualified-keyword]]])

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
     (let [without (vector-projection (schema/handed-projection) subject-forms)
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
       (testing "refusals name the rule"
         (is (= ::schema.datahike/vector-tempid-operation
                (refusal-rule #(encoded projection [[:db/add "r" items "a"]]))))
         (is (= ::schema.datahike/vector-value-not-sequential
                (refusal-rule #(encoded projection [{:db/id "r" items "a"}])))))
       (testing "without cells declared, today's storage is unchanged"
         (let [legacy (vector-projection (schema/handed-projection) subject-forms)]
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
                (schema.datahike/lower-selector projection selector))))
       (testing "decoding orders by position, applies options to the logical vector and removes bridge keys"
         (is (= {:xs ["a" "b"] :seon.vector-test.p/pairs []
                 kids [{:seon.vector-test.k/name "A" :seon.vector-test.k/args [{:seon.vector-test.a/name "1"} {:seon.vector-test.a/name "2"}]}]}
                (schema.datahike/decode-vectors projection selector physical))))
       (testing "the legacy list forms mean the same options"
         (is (= {items ["a" "b"] :seon.vector-test.p/pairs []}
                (schema.datahike/decode-vectors projection '[(limit :seon.vector-test.p/items 2) (default :seon.vector-test.p/pairs [])]
                                                physical))))
       (testing "a wildcard restores every present vector, empty ones included"
         (let [decoded (schema.datahike/decode-vectors projection '[*] physical)]
           (is (= ["a" "b" "c"] (get decoded items)))
           (is (= [] (get decoded :seon.vector-test.p/keys)))
           (is (= [{:seon.vector-test.a/name "1"} {:seon.vector-test.a/name "2"}]
                  (mapv #(dissoc % :db/id) (get-in decoded [kids 0 :seon.vector-test.k/args]))))
           (is (not-any? #(= "seon.db.vector" (namespace %)) (keys decoded)))))
       (testing "a reverse vector attribute reads the owning parents"
         (is (= [{:seon.vector-test.p/_peers [{:seon.db.vector/_cells [:db/id]}]}]
                (schema.datahike/lower-selector projection [:seon.vector-test.p/_peers])))
         (is (= {:seon.vector-test.p/_peers [{:db/id 1}]}
                (schema.datahike/decode-vectors projection [:seon.vector-test.p/_peers]
                                                {:seon.vector-test.p/_peers [{:seon.db.vector/_cells {:db/id 1}}
                                                                             {:seon.db.vector/_cells {:db/id 1}}]}))))
       (testing "a selector naming no vector needs no lowering"
         (is (not (schema.datahike/vector-selector? projection [:seon.vector-test.p/id])))
         (is (schema.datahike/vector-selector? projection '[*])))))))
