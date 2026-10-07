(ns seon.program-test
  "Recurring proof for the one build/runtime declaration contract."
  (:require [seon.schema.internal] [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.eval]
            [seon.fn]
            [seon.fn-test]
            [seon.test.accretion :as accretion]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.turn :as turn]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader]
            [seon.test-support :as test-support]))

(def ^:private digest-a (apply str (repeat 64 "a")))
(def ^:private digest-b (apply str (repeat 64 "b")))
(def ^:private digest-c (apply str (repeat 64 "c")))

(defn- reads-back?
  "The oracle: `sym`'s text read by clojure's EDN reader is `sym`."
  [sym]
  (try (= sym (edn/read-string (str sym))) (catch RuntimeException _ false)))

(deftest edn-round-trip-symbol-agrees-with-the-edn-reader
  (testing "edge cases of the reader's symbol grammar"
    (doseq [sym ['a 'a/b '/ (symbol "a" "/") (symbol "1a") (symbol ":a") (symbol "a" "b/c")
                 (symbol nil "a/b") (symbol "" "x") (symbol "a" "") (symbol "") (symbol "+1")
                 (symbol "-") (symbol "a" "1") (symbol "a:" "b") (symbol "a" "b::c") (symbol "nil")
                 (symbol "#a") (symbol "a b") (symbol "a\u0085b" "c") (symbol "a" "\u0085")
                 (symbol ":clj-kondo/unknown-namespace" "f")]]
      (is (= (reads-back? sym) (program/edn-round-trip-symbol? sym)) "the predicate is the reader's answer")))
  (testing "generated symbols over the grammar's delimiting characters"
    (let [part (gen/fmap str/join (gen/vector (gen/elements (vec "ab1:/#'+-.,; \"@~`^()٠\u0085\n\\é?{")) 0 5))
          sym (gen/one-of [(gen/fmap symbol part) (gen/fmap (partial apply symbol) (gen/tuple part part))])
          result (tc/quick-check 3000 (prop/for-all [s sym] (= (reads-back? s) (program/edn-round-trip-symbol? s)))
                                 :seed 20261007)]
      (is (:pass? result) "every generated symbol agrees with the EDN reader")))
  (is (false? (program/edn-round-trip-symbol? "a")) "a string is not a symbol"))

(deftest three-way-classifies-complete-generated-digest-maps
  (let [result
        (tc/quick-check
         100
         (prop/for-all [offset gen/nat]
           (let [identity (fn [label]
                            [:seon.fn/sym
                             (symbol "generated" (str label "-" offset))])
                 unchanged (identity "unchanged")
                 equal-edit (identity "equal-edit")
                 equal-add (identity "equal-add")
                 branch-change (identity "branch-change")
                 head-change (identity "head-change")
                 conflict (identity "conflict")
                 add-conflict (identity "add-conflict")
                 retract-conflict (identity "retract-conflict")
                 equal-retraction (identity "equal-retraction")
                 added (identity "added")
                 retracted (identity "retracted")
                 base {unchanged digest-a equal-edit digest-a
                       branch-change digest-a head-change digest-a
                       conflict digest-a retract-conflict digest-a
                       equal-retraction digest-a retracted digest-a}
                 branch {unchanged digest-a equal-edit digest-b equal-add digest-a
                         branch-change digest-b head-change digest-a
                         conflict digest-b add-conflict digest-b added digest-a}
                 head {unchanged digest-a equal-edit digest-b equal-add digest-a
                       branch-change digest-a head-change digest-b
                       conflict digest-c add-conflict digest-c
                       retract-conflict digest-b retracted digest-a}
                 comparison (program/three-way base branch head)]
             (= comparison
                {:seon.program/unchanged #{unchanged equal-edit equal-add
                                           equal-retraction}
                 :seon.program/changed-on-branch #{branch-change}
                 :seon.program/changed-on-head #{head-change}
                 :seon.program/conflict #{conflict add-conflict retract-conflict}
                 :seon.program/added #{added}
                 :seon.program/retracted #{retracted}
                 :seon.program/conflict-digests
                 {conflict {:seon.program/base-digest digest-a
                            :seon.program/branch-digest digest-b
                            :seon.program/head-digest digest-c}
                  add-conflict {:seon.program/base-digest :seon.program/absent
                                :seon.program/branch-digest digest-b
                                :seon.program/head-digest digest-c}
                  retract-conflict {:seon.program/base-digest digest-a
                                    :seon.program/branch-digest :seon.program/absent
                                    :seon.program/head-digest digest-b}}})))
         :seed 22092026)]
    (test-support/assert-check! result)))

(defn- example-projection
  "The handed program world plus one example family. `row-properties` and
   `digest?` vary the family's comparison declaration."
  [family entity-properties digest?]
  (let [identity-attribute (keyword family "id")
        source-attribute (keyword family "source")
        entity-schema (keyword family "entity")]
    (reduce (fn [projection [schema-key definition]]
              (schema/projection-with-schema
               projection schema-key definition
               {:seon.schema.admission/source :agent}))
            (schema/handed-projection)
            [[source-attribute :string]
             [identity-attribute [:symbol {:seon.db/identity true
                                           :seon.program/row-schema entity-schema
                                           :seon.program/source-attribute source-attribute}]]
             [entity-schema
              (cond-> [:map (merge {:seon.db/attributes true
                                    :seon.program/partition :seon.program}
                                   entity-properties)
                       [identity-attribute identity-attribute]
                       [source-attribute source-attribute]]
                digest? (conj [:seon.program/definition-digest
                               :seon.program/definition-digest]))]])))

(defn- example-database
  "An in-memory genesis value installing the compared families' and `rows`'
   attributes and holding `rows`. A value without declaration rows reads the projection its
   construction boundary supplied (`seon.db/carried-projection`)."
  [projection entities & [history?]]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write :keep-history? (boolean history?)}
        storable? (requiring-resolve 'seon.schema.datahike/storable-attribute-in?)
        declarations (requiring-resolve 'seon.schema.datahike/malli->datahike-schema-in)]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (try
        (d/transact connection
                    (declarations projection
                                  (sort (filter #(storable? projection %)
                                                (into (set (mapcat keys entities))
                                                      (cons :seon.program/definition-digest
                                                            (:seon.program/compared-families
                                                             (program/declaration-families
                                                              projection))))))))
        (when (seq entities) (d/transact connection entities))
        (vary-meta (d/db connection) assoc :seon.schema/projection projection)
        (finally
          (d/release connection)
          (d/delete-database configuration))))))

(deftest a-family-declaring-neither-digest-nor-recomputation-is-unknown
  (let [projection (example-projection "undeclared.example" {} false)
        database (example-database projection
                                   [{:undeclared.example/id 'undeclared.example/f
                                     :undeclared.example/source "(defn f [])"}])
        result (program/digest-map database {:seon.program/max-datoms 100})]
    (is (= #{:undeclared.example/id}
           (:seon.program/unclassified-families
            (program/declaration-families projection))))
    (is (= :seon.program/family-classification (:seon.program/missing-evidence result)))
    (is (= #{:undeclared.example/id} (:seon.program/unclassified-families result)))
    (is (m/validate :seon.program/digest-map-refusal result
                    {:registry (:seon.schema.projection/registry projection)}))))

(deftest declaration-families-come-from-the-supplied-projection
  (let [projection (example-projection "branch.example" {} true)
        recomputed (example-projection "derived.example"
                                       {:seon.program/recomputed true} false)]
    (is (not (contains? program/identity-attributes :branch.example/id))
        "The family exists only in the supplied projection, not the loaded files")
    (is (= {:seon.program/compared-families
            #{:branch.example/id :seon.fn/sym :seon.ns/name :seon.schema/key
              :seon.test/sym}
            :seon.program/recomputed-families
            #{:seon.fn.file/relative-path :seon.lint/id}
            :seon.program/unclassified-families #{}}
           (program/declaration-families projection)))
    (is (= #{:derived.example/id :seon.fn.file/relative-path :seon.lint/id}
           (:seon.program/recomputed-families
            (program/declaration-families recomputed))))))

(deftest program-shapes-derive-once-per-projection
  ;; Task 400bcf52dd53: every caller of `shapes-in` walked all ~3,500 forms,
  ;; about ten walks per contracted defn. One projection value, one walk.
  (let [projection (example-projection "memo.example" {} true)
        other (example-projection "other.example" {} true)]
    (is (identical? (program/shapes-in projection) (program/shapes-in projection)))
    (is (identical? (program/declaration-families projection)
                    (program/declaration-families projection)))
    (is (contains? (program/shapes-in other) :other.example/id))
    (is (not (contains? (program/shapes-in other) :memo.example/id))
        "Another projection value derives its own shapes")))

(deftest a-require-a-definition-does-not-use-leaves-its-digest-unchanged
  ;; Task 434fed5a3a67: swapping one require changed every digest in its namespace.
  (let [entity {:seon.fn/sym 'sample/f
             :seon.fn/source "(defn f [x] (c/lookup x ::s/key))"
             :seon.fn/calls #{'clojure.core/defn 'clojure.core.cache/lookup}
             :seon.fn/keywords #{:seon.schema/key}}
        context {:requires #{'clojure.core.cache 'clojure.string 'seon.schema}
                 :aliases {'c 'clojure.core.cache 'str 'clojure.string 's 'seon.schema}
                 :refers {'join 'clojure.string/join}
                 :imports {}}
        digest (program/definition-digest entity context)]
    (is (= digest (program/definition-digest
                   entity (-> context (update :requires conj 'seon.cache)
                           (assoc-in [:aliases 'sc] 'seon.cache)
                           (assoc-in [:refers 'lookup] 'seon.cache/lookup))))
        "Adding a require, alias and refer it does not use")
    (is (= digest (program/definition-digest
                   entity (-> context (update :requires disj 'clojure.string)
                           (update :aliases dissoc 'str)
                           (assoc :refers {}))))
        "Removing ones it does not use")
    (is (not= digest (program/definition-digest
                      (assoc entity :seon.fn/calls #{'clojure.core/defn 'seon.cache/lookup})
                      (-> context (update :requires conj 'seon.cache)
                          (assoc-in [:aliases 'c] 'seon.cache))))
        "Retargeting the alias its call resolves through")
    (is (not= digest (program/definition-digest
                      (assoc entity :seon.fn/keywords #{:seon.schema.edn/key})
                      (-> context (update :requires conj 'seon.schema.edn)
                          (assoc-in [:aliases 's] 'seon.schema.edn))))
        "Retargeting the alias its keyword resolves through")))

(deftest digest-map-is-exactly-the-stored-declarations-or-a-typed-refusal
  (let [projection (example-projection "branch.example" {} true)
        entities (mapv (fn [n]
                     {:branch.example/id (symbol "branch.example" (str "f" n))
                      :branch.example/source (str "(defn f" n " [])")
                      :seon.program/definition-digest
                      (apply str (repeat 64 (nth "abc" (mod n 3))))})
                   (range 450))
        expected (into {} (map (fn [entity]
                                 [[:branch.example/id (:branch.example/id entity)]
                                  (:seon.program/definition-digest entity)]))
                       entities)
        complete (example-database projection entities)
        undigested (example-database
                    projection
                    (conj entities {:branch.example/id 'branch.example/bare
                                :branch.example/source "(defn bare [])"}))]
    (testing "every stored declaration, across index pages"
      (is (= expected (program/digest-map complete {:seon.program/max-datoms 900}))))
    (testing "a compared declaration without its digest"
      (let [result (program/digest-map undigested {:seon.program/max-datoms 1000})]
        (is (= :seon.program/definition-digest (:seon.program/missing-evidence result)))
        (is (= #{[:branch.example/id 'branch.example/bare]}
               (:seon.program/undigested-declarations result)))
        (is (m/validate :seon.program/digest-map-refusal result
                    {:registry (:seon.schema.projection/registry projection)}))))
    (testing "the datom bound refuses instead of answering part of the map"
      (let [result (program/digest-map complete {:seon.program/max-datoms 899})]
        (is (= :seon.program/read-bound (:seon.program/missing-evidence result)))
        (is (m/validate :seon.program/digest-map-refusal result
                    {:registry (:seon.schema.projection/registry projection)}))))))

(deftest changed-identities-scope-the-digest-maps-to-one-change
  (let [projection (schema/handed-projection)
        f #(symbol "scoped" (name %))
        entity (fn [s digest] {:seon.fn/sym (f s) :seon.program/definition-digest digest})
        base (example-database projection
                               (mapv #(entity % digest-a) [:same :edit :head :gone :revert :conflict])
                               true)
        with (fn [database & transactions]
               (reduce #(vary-meta (:db-after (d/with %1 %2)) assoc :seon.schema/projection projection)
                       database transactions))
        bound {:seon.program/max-datoms 100}
        scoped (fn [branch head]
                  (let [scope (into (program/changed-identities base branch bound)
                                    (program/changed-identities base head bound))]
                    (mapv #(program/digest-map % (assoc bound :seon.program/identities scope))
                          [base branch head])))
        branch (with base [(entity :edit digest-b) (entity :conflict digest-b) (entity :new digest-a)
                           [:db/retractEntity [:seon.fn/sym (f :gone)]]])
        head (with base [(entity :head digest-b) (entity :conflict digest-c)]
                   [(entity :revert digest-b)] [(entity :revert digest-a)])
        maps (scoped branch head)
        refusal #(:seon.program/missing-evidence (program/changed-identities base % bound))]
    (is (= {:seon.program/unchanged #{[:seon.fn/sym (f :revert)]}
            :seon.program/changed-on-branch #{[:seon.fn/sym (f :edit)]}
            :seon.program/changed-on-head #{[:seon.fn/sym (f :head)]}
            :seon.program/added #{[:seon.fn/sym (f :new)]}
            :seon.program/retracted #{[:seon.fn/sym (f :gone)]}
            :seon.program/conflict #{[:seon.fn/sym (f :conflict)]}}
           (dissoc (apply program/three-way maps) :seon.program/conflict-digests)))
    (is (not-any? #(contains? % [:seon.fn/sym (f :same)]) maps) "Unchanged entities are never read")
    (is (= #{[:seon.ns/name 'scoped]}
           (program/changed-identities base (with base [{:seon.ns/name 'scoped :seon.program/definition-digest digest-b}]) bound))
        "a namespace entity (its requires) is a change like any declaration")
    (is (= :seon.program/read-bound
           (:seon.program/missing-evidence
            (program/changed-identities base branch {:seon.program/max-datoms 1}))))
    (is (= :seon.program/definition-digest
           (:seon.program/missing-evidence
            (second (scoped (with base [[:db/retract [:seon.fn/sym (f :edit)]
                                          :seon.program/definition-digest digest-a]])
                             head)))))
    (let [result (refusal (example-database projection [(entity :same digest-a)]))]
      (is (= :seon.program/history result)))))

(deftest three-way-classifies-every-base-branch-head-state
  (let [states [:seon.program/absent digest-a digest-b digest-c]
        cases (for [base states branch states head states]
                [[:seon.fn/sym (symbol "case" (apply str (map #(.indexOf ^java.util.List states %) [base branch head])))]
                 base branch head])
        digests (fn [position]
                  (into {} (keep (fn [[identity & values]]
                                   (let [value (nth values position)]
                                     (when-not (= :seon.program/absent value)
                                       [identity value]))))
                        cases))
        result (program/three-way (digests 0) (digests 1) (digests 2))
        classes [:seon.program/unchanged :seon.program/changed-on-branch
                 :seon.program/changed-on-head :seon.program/conflict
                 :seon.program/added :seon.program/retracted]
        class-of (fn [identity]
                   (filterv #(contains? (get result %) identity) classes))]
    (doseq [[identity base branch head] cases
            :when (not= [:seon.program/absent] (distinct [base branch head]))]
      (let [expected
            (cond
              (= branch head) :seon.program/unchanged
              (= branch base) :seon.program/changed-on-head
              (and (= head base) (= :seon.program/absent base)) :seon.program/added
              (and (= head base) (= :seon.program/absent branch)) :seon.program/retracted
              (= head base) :seon.program/changed-on-branch
              :else :seon.program/conflict)]
        (is (= [expected] (class-of identity)))
        (is (= (= expected :seon.program/conflict)
               (contains? (:seon.program/conflict-digests result) identity)))))
    (is (not (contains? (reduce into #{} (map result classes))
                        (ffirst (filter (fn [[_ & values]]
                                          (every? #{:seon.program/absent} values))
                                        cases)))))))

(deftest digest-map-compares-two-fixture-branches-from-one-commit
  ;; Two canonical fixtures are two isolated agents branched off the member's
  ;; one captured commit; a nested fixture never sees its parent's writes.
  (test-support/with-database
    (fn [connection-a]
      (let [base-map (program/digest-map (db/db connection-a) {:seon.program/max-datoms 1000000})
            [branch-identity head-identity]
            (take 2 (sort-by pr-str (filter #(= :seon.fn/sym (first %)) (keys base-map))))]
        (is (every? some? [branch-identity head-identity]))
        ;; File and lint entities are derived and carry no definition digest.
        (is (not-any? #{:seon.lint/id :seon.fn.file/relative-path}
                      (map first (keys base-map))))
        ;; A real revision: the writer refuses a digest its entity does not produce.
        (test-support/transacted!
         connection-a [(test-support/revision (db/db connection-a) branch-identity
                                              {:seon.fn/doc "Changed on the branch."})])
        (let [branch-map (program/digest-map (db/db connection-a) {:seon.program/max-datoms 1000000})]
          (test-support/with-database
            (fn [connection-b]
              (is (= base-map (program/digest-map (db/db connection-b) {:seon.program/max-datoms 1000000}))
                  "the second fixture branches from the same commit")
              (test-support/transacted!
               connection-b [(test-support/revision (db/db connection-b) head-identity
                                                    {:seon.fn/doc "Changed on the head."})])
              (let [head-map (program/digest-map (db/db connection-b) {:seon.program/max-datoms 1000000})
                    comparison (program/three-way base-map branch-map head-map)]
                (is (= #{branch-identity}
                       (:seon.program/changed-on-branch comparison)))
                (is (= #{head-identity}
                       (:seon.program/changed-on-head comparison)))
                (is (empty? (:seon.program/conflict comparison)))))))))))

(defn- one-event
  [source]
  (let [events (reader/read {:seon.sci.reader/text source
                             :seon.sci.reader/ns 'sample
                             :seon.config.eval.result/max-source
                             (count source)})]
    (is (vector? events))
    (is (= 1 (count events)))
    (first events)))

(defn- refusal-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- source-contract
  [function-symbol spec forms source arglists]
  (let [projection (schema/build-projection forms
                                            {(symbol function-symbol) spec})]
    (program/contract-facts
     {:seon.program/function-symbol (symbol function-symbol)
      :seon.program/spec (pr-str spec)
      :seon.program/source source
      :seon.program/arglists (pr-str arglists)
      :seon.program/compile-options
      (:seon.schema.projection/compile-options projection)
      :seon.program/predicate-functions
      (:seon.schema.projection/predicate-functions projection)
      :seon.program/schema-keys (set (keys forms))
      :seon.program/schema-forms forms})))

(defn- parsed-contract
  [function-symbol spec forms]
  (let [projection (schema/build-projection forms
                                            {(symbol function-symbol) spec})
        compiled (m/function-schema
                  spec (:seon.schema.projection/compile-options projection))
        bindings
        (mapv (fn [arity]
                (let [info (m/-function-info arity)
                      child-count (count (m/children (:input info)))
                      fixed-count (if (= :varargs (:arity info))
                                    (dec child-count) child-count)
                      fixed (mapv #(symbol (str "x" %)) (range fixed-count))]
                  (if (= :varargs (:arity info))
                    (into fixed ['& 'xs])
                    fixed)))
              (m/-function-schema-arities compiled))
        function-name (name (symbol function-symbol))
        source (if (= 1 (count bindings))
                 (pr-str (list 'defn (symbol function-name)
                               (first bindings) nil))
                 (pr-str (list* 'defn (symbol function-name)
                                (map (fn [binding]
                                       (list binding nil)) bindings))))]
    (source-contract function-symbol spec forms source
                     (apply list bindings))))

;; A synthetic schema entity carries the digest its owner derives, as
;; `seon.schema`'s canonical entities do (`seon.program/definition-digest`).
(defn- with-definition-digest
  [entity]
  (assoc entity :seon.program/definition-digest (program/definition-digest entity)))

(defn- analyzed-entity-tx
  "Runtime settlement receives the analyzer's completed row, digested by the
  writer's digest owner as SCI's definition row is, as production does."
  [database options entity]
  (let [source (or (:seon.fn/source entity) (:seon.test/source entity))
        analyzed (if source
                   (test-support/revised-entity
                    database
                    (second (seon.fn/analyze-form database source
                                                  (or (:seon.fn/ns entity) (:seon.test/ns entity))
                                                  entity))
                    {})
                   entity)]
    ((ns-resolve 'seon.turn 'row-tx) database options analyzed)))

(deftest zero-argument-arity-states-no-empty-argument-set
  ;; Storage keeps no empty component set. A derived `[]` differed from every
  ;; stored zero-argument arity, so each republication of `seon.db/db` looked
  ;; like a signature change and relinted its 180 caller files (9.4 s).
  (let [facts (source-contract (quote seon.probe/answer) [:=> [:cat] :int] {}
                               "(defn answer [] 1)" '([]))
        arity (get-in facts [:seon.fn/arities 0])]
    (is (= 0 (:seon.fn.arity/argument-count arity)))
    (is (not (contains? arity :seon.fn.arity/arguments)))))

(deftest positional-and-map-entry-contracts-have-distinct-addresses
  (test-support/with-database
    (fn [connection]
      (let [forms {:sample/ambient :int}
            projection (schema/build-projection forms {})
            compile-options
            (:seon.schema.projection/compile-options projection)
            predicate-functions
            (:seon.schema.projection/predicate-functions projection)
            schema-entity
            (with-definition-digest
             (program/with-contract-facts
             {:seon.program/row
              {:seon.schema/key :sample/ambient
               :seon.schema/form ":int"
               :seon.schema.admission/source :core}
              :seon.program/compile-options compile-options
              :seon.program/predicate-functions predicate-functions
              :seon.program/schema-keys #{:sample/ambient}
              :seon.program/schema-forms forms}))
            positional-spec [:=> [:cat :sample/ambient] :sample/ambient]
            map-spec
            [:=> [:cat [:map [:sample/ambient :sample/ambient]]]
             :sample/ambient]
            entity
            (fn [function-symbol source arglists spec]
              (test-support/program-fn-entity (db/db connection) function-symbol source
                     (merge {:seon.fn/sym function-symbol
                             :seon.fn/ns [:seon.ns/name 'sample]
                             :seon.schema.admission/source :agent
                             :seon.fn/source source
                             :seon.fn/arglists (pr-str arglists)
                             :seon.fn/private? false
                             :seon.fn/spec (pr-str spec)}
                            (source-contract function-symbol spec forms source
                                             arglists))))
            positional
            (entity (quote sample/positional) "(defn positional [ambient] ambient)"
                 '([ambient]) positional-spec)
            mapped
            (entity (quote sample/mapped)
                 "(defn mapped [{:sample/keys [ambient]}] ambient)"
                 '([{:sample/keys [ambient]}]) map-spec)]
        (test-support/transacted! connection
                                  [(test-support/namespace-entity (db/db connection) 'sample)
                                   schema-entity positional mapped])
        (let [positional-address
              (db/q '[:find ?binding-shape
                      :in $ ?function-symbol
                      :where
                      [?function :seon.fn/sym ?function-symbol]
                      [?function :seon.fn/arities ?arity]
                      [?arity :seon.fn.arity/arguments ?argument]
                      [?argument :seon.fn.argument/binding ?binding]
                      [?binding :seon.fn.binding/shape ?binding-shape]]
                    @connection (quote sample/positional))
              map-address
              (db/q '[:find ?binding-shape ?key
                      :in $ ?function-symbol
                      :where
                      [?function :seon.fn/sym ?function-symbol]
                      [?function :seon.fn/arities ?arity]
                      [?arity :seon.fn.arity/arguments ?argument]
                      [?argument :seon.fn.argument/binding ?binding]
                      [?binding :seon.fn.binding/shape ?binding-shape]
                      [?binding :seon.fn.binding/entries ?binding-entry]
                      [?binding-entry :seon.schema.map-entry/key-keyword ?key]]
                    @connection (quote sample/mapped))]
          (is (= 1 (count positional-address)))
          (is (= 1 (count map-address)))
          (is (= [:symbol] (first positional-address)))
          (is (= [:map :sample/ambient] (first map-address))))))))

(deftest source-contract-join-refuses-disagreement-and-records-overrides
  (let [spec [:=> [:cat :int] :int]
        request (fn [source arglists]
                  #(source-contract (quote sample/join) spec {} source arglists))]
    (is (= :analyzer-disagreement
           (:seon.fn.signature/reason
            (refusal-data
             (request "(defn join [x] x)" '([analyzer-x]))))))
    (let [failure (refusal-data (request "(defn join [x y] x)" '([x y])))]
      (is (= :seon.fn/arities (:seon.program/signature-member failure)))
      (is (= 1 (:seon.program/signature-count failure)))
      (is (= 'seon.program/contract-facts (:seon.error/operation failure)))
      (is (= 'sample/join (:seon.error/member failure))))
    ;; An optional trailing slot is one Malli varargs arity, never two fixed
    ;; ones; the refusal names the declaration and both arity sets.
    (let [failure (refusal-data
                   #(source-contract (quote sample/join) [:=> [:cat :int [:? :int]] :int] {}
                                     "(defn join ([x] x) ([x y] y))" '([x] [x y])))]
      (is (= 'sample/join (:seon.error/member failure)))
      (is (= #{[:variadic 1]} (:seon.error/offending failure)))
      (is (= #{[:fixed 1] [:fixed 2]} (:seon.error/expected failure))))
    (is (= :unsupported-declaration
           (:seon.fn.signature/reason
            (refusal-data
             (request "(fn join [x] x)" '([x]))))))
    (is (true?
         (:seon.fn/arglists-override?
          (source-contract
           (quote sample/join) spec {}
           "(defn ^{:arglists '([public-x])} join [x] x)"
           '([public-x])))))))

(deftest exact-source-and-malli-arities-join-generatively
  (let [schema-at (fn [index] (nth [:int :string :keyword :boolean]
                                    (mod index 4)))
        result
        (tc/quick-check
         80
         (prop/for-all
          [fixed-counts (gen/set (gen/choose 0 4)
                                 {:min-elements 1 :max-elements 4})
           variadic-min (gen/one-of [(gen/return nil) (gen/choose 0 3)])]
          (let [fixed (mapv (fn [count]
                              {:join-key [:fixed count]
                               :bindings (mapv #(symbol (str "x" %))
                                               (range count))
                               :input (into [:cat]
                                            (map schema-at)
                                            (range count))})
                            (sort fixed-counts))
                variadic
                (when variadic-min
                  {:join-key [:variadic variadic-min]
                   :bindings
                   (into (mapv #(symbol (str "v" %))
                               (range variadic-min))
                         ['& 'xs])
                   :input
                   (into [:cat]
                         (concat (map schema-at (range variadic-min))
                                 [[:* (schema-at variadic-min)]]))})
                descriptors (cond-> fixed variadic (conj variadic))
                source-descriptors (vec (reverse descriptors))
                arms (mapv (fn [{:keys [input]}] [:=> input :symbol])
                           descriptors)
                spec (if (= 1 (count arms)) (first arms)
                         (into [:function] arms))
                declarations
                (mapv (fn [{:keys [bindings]}] (list bindings nil))
                      source-descriptors)
                source (pr-str (list* 'defn 'generated declarations))
                arglists (apply list (map :bindings source-descriptors))
                facts (source-contract (quote sample/generated) spec {} source
                                       arglists)
                arities (:seon.fn/arities facts)
                actual-keys
                (into #{}
                      (map (fn [arity]
                             (if (:seon.fn.arity/max arity)
                               [:fixed (:seon.fn.arity/min arity)]
                               [:variadic (:seon.fn.arity/min arity)])))
                      arities)]
            (and (= (set (map :join-key descriptors)) actual-keys)
                 (every?
                  (fn [arity]
                    (= (:seon.fn.arity/argument-count arity)
                       (count (:seon.fn.arity/arguments arity))))
                  arities))))
         :seed 202608050012)]
    (test-support/assert-check! result)))

(deftest function-contracts-compile-once-into-complete-query-facts
  (let [forms {:sample/key :keyword
               :sample/value :int
               :sample/enum-value :string}
        spec
        [:function
         [:=> [:cat [:map-of :sample/key :sample/value]] :sample/value]
         [:=> [:cat :sample/value
               [:repeat {:min 0 :max 1} :sample/value]]
          [:enum :sample/enum-value :other]]]
        facts (parsed-contract (quote sample/complete) spec forms)
        arities (:seon.fn/arities facts)]
    (testing "one compiled contract yields Malli's exact ordered arities"
      (is (= [{:seon.fn.arity/arity "1"
               :seon.fn.arity/min 1
               :seon.fn.arity/max 1}
              {:seon.fn.arity/arity ":varargs"
               :seon.fn.arity/min 1
               :seon.fn.arity/max 2}]
             (mapv #(select-keys %
                                 [:seon.fn.arity/arity
                                  :seon.fn.arity/min
                                  :seon.fn.arity/max])
                   arities))))
    (testing "role refs come only from RefSchema observations"
      (is (= #{:sample/key :sample/value}
             (:seon.fn.arity/input-refs (first arities))))
      (is (= #{:sample/value}
             (:seon.fn.arity/output-refs (first arities))))
      (is (= #{:sample/value}
             (:seon.fn.arity/input-refs (second arities))))
      (is (nil? (:seon.fn.arity/output-refs (second arities)))
          "an enum scalar equal to a schema key is not a reference"))
    (testing "the expansion is deterministic"
      (is (= facts (parsed-contract (quote sample/complete) spec forms))))))

(deftest function-contract-role-refs-follow-local-registries
  (let [forms {:sample/value :int}
        spec [:=> {:registry {:local/value :sample/value}}
              [:cat :local/value]
              :sample/value]
        facts (parsed-contract (quote sample/local-registry) spec forms)
        arity (first (:seon.fn/arities facts))]
    (is (= #{:sample/value}
           (:seon.fn.arity/input-refs arity)))
    (is (= #{:sample/value}
           (:seon.fn.arity/output-refs arity)))))

(deftest function-contract-redefinition-replaces-component-facts-exactly
  (test-support/with-database
    (fn [connection]
      (let [function-symbol (quote seon.program/provenance-redefined)
            old-spec [:function
                      [:=> [:cat :int] :int]
                      [:=> [:cat :int :int] :int]]
            new-spec [:=> [:cat :string] :string]
            old-entity
            (test-support/program-fn-entity (db/db connection) function-symbol
                       "(defn provenance-redefined ([x] x) ([x y] x))"
                   (merge {:seon.fn/sym function-symbol
                           :seon.schema.admission/source :agent
                           :seon.fn/ns [:seon.ns/name 'seon.program]
                           :seon.fn/spec (pr-str old-spec)}
                          (parsed-contract function-symbol old-spec {})))
            new-entity
            (test-support/program-fn-entity (db/db connection) function-symbol
                       "(defn provenance-redefined [x] x)"
                   (merge {:seon.fn/sym function-symbol
                           :seon.schema.admission/source :agent
                           :seon.fn/ns [:seon.ns/name 'seon.program]
                           :seon.fn/spec (pr-str new-spec)}
                          (parsed-contract function-symbol new-spec {})))]
        (test-support/transacted! connection [old-entity])
        (let [current
              (db/pull @connection
                       '[* {:seon.fn/arities
                            [* {:seon.fn.arity/arguments
                                [* {:seon.fn.argument/binding [:db/id]}]}]}]
                              [:seon.fn/sym function-symbol])
              old-components
              (into #{}
                    (mapcat
                     (fn [arity]
                       (into [(:db/id arity)]
                             (mapcat (fn [argument]
                                       [(:db/id argument)
                                        (get-in argument
                                                [:seon.fn.argument/binding
                                                 :db/id])]))
                             (:seon.fn.arity/arguments arity))))
                    (:seon.fn/arities current))]
          (test-support/transacted! connection
                                  (program/exact-replacement-tx current new-entity))
          (let [redefined
                (db/pull @connection
                        [:seon.fn/spec
                         {:seon.fn/arities
                          [:seon.fn.arity/min
                           :seon.fn.arity/max
                           {:seon.fn.arity/arguments
                            [:seon.fn.argument/rest?]}]}]
                        [:seon.fn/sym function-symbol])]
            (is (= (pr-str new-spec) (:seon.fn/spec redefined)))
            (is (= [{:seon.fn.arity/min 1
                     :seon.fn.arity/max 1
                     :seon.fn.arity/arguments
                     [{:seon.fn.argument/rest? false}]}]
                   (:seon.fn/arities redefined)))
            ;; A vector keeps an element equal by value (vector-storage design
            ;; V2): the one-argument arity survives as itself; every other old
            ;; component is retracted with its cell.
            (let [components (fn [arities]
                               (into #{} (mapcat (fn [arity]
                                                   (into [(:db/id arity)]
                                                         (mapcat #(vector (:db/id %) (get-in % [:seon.fn.argument/binding :db/id])))
                                                         (:seon.fn.arity/arguments arity))))
                                     arities))
                  kept (components (:seon.fn/arities
                                    (db/pull @connection '[{:seon.fn/arities [:db/id {:seon.fn.arity/arguments [:db/id {:seon.fn.argument/binding [:db/id]}]}]}]
                                             [:seon.fn/sym function-symbol])))
                  removed (remove kept old-components)]
              (is (= 5 (count removed)) "the two-argument arity, its arguments and bindings")
              (is (every? #(empty? (db/datoms @connection :eavt %)) removed)))))))))

(deftest runtime-deletion-refuses-surviving-callers-without-changing-the-definition
  (test-support/with-database
    (fn [connection]
      (let [identity [:seon.fn/sym (quote seon.test/changed-since-green)]
            database (db/db connection)
            before (db/pull database '[*] identity)
            entity-tx analyzed-entity-tx
            result (db/transact!
                    connection
                    [[:db.fn/call
                      (fn [current]
                        (entity-tx current {} {:seon.program/delete-identities [identity]}))]])]
        (is (seq (:seon.fn/form-span before)))
        (is (seq (:seon.fn/call-arities before)))
        (is (some? (:seon.db.write.attempt/request-id result)))
        (is (= before
               (db/pull (db/db connection) '[*] identity)))))))

(deftest identical-runtime-redeclaration-builds-no-datoms
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) 'sample)])
      (let [function-symbol 'sample/idempotent
            source "(defn idempotent {:malli/schema [:=> [:cat :int] :int]} [x] x)"
            entity (test-support/program-fn-entity (db/db connection) function-symbol source
                       (parsed-contract function-symbol [:=> [:cat :int] :int] {}))
            entity-tx (ns-resolve 'seon.turn 'row-tx)]
        (test-support/transacted! connection (entity-tx (db/db connection) {} entity))
        (let [before (db/pull (db/db connection) '[*] [:seon.fn/sym function-symbol])
              replacement (entity-tx (db/db connection) {} entity)]
          (is (empty? replacement))
          (is (= before (db/pull (db/db connection) '[*] [:seon.fn/sym function-symbol]))))))))

(deftest changed-runtime-redeclaration-builds-a-real-replacement
  (test-support/with-database
    (fn [connection]
      (let [function-symbol (quote sample/redefined)
            spec [:=> [:cat :int] :int]
            original
            (merge {:seon.fn/sym function-symbol
                    :seon.fn/ns [:seon.ns/name 'sample]
                    :seon.schema.admission/source :agent
                    :seon.fn/source
                    "(defn redefined {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                    :seon.fn/arglists "([x])"
                    :seon.fn/private? false
                    :seon.fn/spec (pr-str spec)}
                   (parsed-contract function-symbol spec {}))
            changed
            (assoc original :seon.fn/source
                   "(defn redefined {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
            entity-tx analyzed-entity-tx
            declared-content (ns-resolve 'seon.turn 'declared-content)]
        (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) 'sample)])
        (test-support/transacted! connection (entity-tx (db/db connection) {} original))
        (let [current (db/pull @connection '[*]
                               [:seon.fn/sym function-symbol])
              replacement (entity-tx (db/db connection) {} changed)]
          (is (not= (declared-content (db/db connection) current)
                    (declared-content (db/db connection) changed))
              "declared content receives the database before the entity")
          (is (seq replacement))
          (test-support/transacted! connection replacement)
          (is (= (:seon.fn/source changed)
                 (:seon.fn/source
                  (db/pull @connection [:seon.fn/source]
                           [:seon.fn/sym function-symbol])))))))))

(deftest opening-basis-divergence-is-only-claimed-when-it-is-measurable
  ;; The class: reading an UNMEASURED opening basis as an absent declaration
  ;; and reporting that as a concurrent definition. A pull against a run that
  ;; does not exist and a pull against a run that never declared the function
  ;; both produce nil, so the old comparison could not tell "opened on
  ;; nothing" from "opened on no such entity" and refused a redeclaration that
  ;; no concurrent run had touched.
  (test-support/with-database
    (fn [connection]
      (let [function-symbol (quote sample/unmeasured)
            spec [:=> [:cat :int] :int]
            original
            (merge {:seon.fn/sym function-symbol
                    :seon.fn/ns [:seon.ns/name 'sample]
                    :seon.schema.admission/source :agent
                    :seon.fn/source
                    "(defn unmeasured {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                    :seon.fn/arglists "([x])"
                    :seon.fn/private? false
                    :seon.fn/spec (pr-str spec)}
                   (parsed-contract function-symbol spec {}))
            changed
            (assoc original :seon.fn/source
                   "(defn unmeasured {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
            entity-tx analyzed-entity-tx]
        (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) 'sample)])
        (test-support/transacted! connection (entity-tx (db/db connection) {} original))
        (testing "a request with no run opened on nothing and claims nothing"
          (is (seq (entity-tx (db/db connection) {} changed))))
        (testing "a request naming a run with no opening basis says so"
          (let [data (refusal-data
                      #(entity-tx (db/db connection) {:seon.turn/id "absent"}
                               changed))]
            (is (qualified-symbol? (:seon.error/operation data)))
            (is (true? (:seon.turn/missing-opening-datom data))
                "the refusal names the unreadable basis, not a concurrent definition")))
        (is (= (:seon.fn/source original)
               (:seon.fn/source
                (db/pull @connection [:seon.fn/source]
                         [:seon.fn/sym function-symbol])))
            "neither derivation wrote anything")))))

(deftest reader-events-have-one-canonical-declaration-entity
  (let [cases
        [{:label "contracted function"
          :source
          "(defn ^{:malli/schema [:=> [:cat :int] :int]} plus-one [x] (inc x))"
          :expected
          {:seon.fn/sym (quote sample/plus-one)
           :seon.fn/ns [:seon.ns/name 'sample]
           :seon.fn/source
           "(defn ^{:malli/schema [:=> [:cat :int] :int]} plus-one [x] (inc x))"
           :seon.fn/arglists "([x])"
           :seon.fn/private? false
           :seon.fn/spec "[:=> [:cat :int] :int]"
           :seon.schema.admission/source :agent}}
         {:label "private uncontracted function"
          :source "(defn- helper [x] x)"
          :expected
          {:seon.fn/sym (quote sample/helper)
           :seon.fn/ns [:seon.ns/name 'sample]
           :seon.fn/source "(defn- helper [x] x)"
           :seon.fn/arglists "([x])"
           :seon.fn/private? true
           :seon.schema.admission/source :agent}}
         {:label "schema"
          :source "(seon.schema/register! ::amount [:int {:min 0}])"
          :expected
          {:seon.schema/key :sample/amount
           :seon.schema/ns [:seon.ns/name 'sample]
           :seon.schema/form "[:int {:min 0}]"
           :seon.schema.admission/source :agent}}
         {:label "test"
          :source "(clojure.test/deftest smoke (clojure.test/is true))"
          :expected
          {:seon.test/sym (quote sample/smoke)
           :seon.test/ns [:seon.ns/name 'sample]
           :seon.test/source
           "(clojure.test/deftest smoke (clojure.test/is true))"
           :seon.schema.admission/source :agent}}]]
    (doseq [{:keys [label source expected]} cases]
      (testing label
        (let [event (one-event source)]
          ;; Expected data is literal. It is not produced by another path that
          ;; shares `seon.program`'s canonicalizer.
          (let [entity (program/declaration-row (seon.schema/handed-projection) event :all :agent)]
            ;; A schema or namespace digest is its entity alone. A function or
            ;; test digest needs its namespace's resolver context, which only
            ;; `seon.db/definition-digest-of` holds, so this stage mints none.
            (if (or (:seon.schema/key expected) (:seon.ns/name expected))
              (is (= 64 (count (:seon.program/definition-digest entity))))
              (is (not (contains? entity :seon.program/definition-digest))))
            (is (= expected (dissoc entity :seon.program/definition-digest))))
          (if (= "private uncontracted function" label)
            (is (nil? (program/declaration-row (seon.schema/handed-projection) event :contracted :agent)))
            (is (= expected
                   (dissoc (program/declaration-row (seon.schema/handed-projection) event :contracted :agent)
                           :seon.program/definition-digest)))))))))

(deftest every-declaration-entity-satisfies-its-own-output-contract
  ;; The class: `declaration-row` emitting an entity its own declared output
  ;; refuses. Every family REQUIRES `:seon.schema.admission/source`, and
  ;; while it was an event key a caller had to remember, three of the four
  ;; live callers forgot it — invisible until instrumentation was armed on a
  ;; cluster, where an agent's first `defn` died on an `[:or]` complaint that
  ;; named the other families' missing keys instead of the one real absence.
  ;; It is an argument now, so an entity with no admission source is not
  ;; constructable and this test cannot regress by anyone forgetting a key.
  ;;
  ;; The namespace case also pins the reader's own shape becoming the
  ;; persisted one: a reader event names required namespaces as bare symbols
  ;; in a vector, and `:seon.ns/ns` declares a set of lookup refs. An `:as`
  ;; alias is deliberately absent here and is proven in
  ;; `docs/seon/issues/a-component-value-is-refused-by-its-own-ref-shape.md`
  ;; instead: component collections declare `:seon.db/ref`, which admits no
  ;; component entity, so that is a different class at a different owner.
  (doseq [source ["(defn ^{:malli/schema [:=> [:cat :int] :int]} f [x] x)"
                  "(defn- helper [x] x)"
                  "(seon.schema/register! ::amount [:int {:min 0}])"
                  "(clojure.test/deftest smoke (clojure.test/is true))"
                  "(ns sample (:require clojure.set))"]
          policy [:all :contracted]
          admission-source [:core :agent]]
    (testing (str source " " policy " " admission-source)
      (when-let [entity (program/declaration-row (seon.schema/handed-projection) (one-event source) policy admission-source)]
        (is (= admission-source (:seon.schema.admission/source entity))
            "the entity records who admitted it")
        (is ((schema/projection-validator (schema/handed-projection) :seon.program/declaration-row) entity)
            "entity refused by its own output contract"))))
  (testing "a reader event preserves required namespace symbols"
    (is (= #{'clojure.set}
           (:seon.ns/requires
            (program/declaration-row (seon.schema/handed-projection) (one-event "(ns sample (:require clojure.set))") :contracted :agent))))))

(deftest declaration-admission-refuses-ambiguous-or-incomplete-entities
  (testing "one event cannot claim two declaration identity families"
    (let [data
          (refusal-data
           #(program/canonical-row
             {:seon.fn/sym (quote sample/f)
              :seon.fn/ns [:seon.ns/name 'sample]
              :seon.fn/source "(defn f [] 1)"
              :seon.fn/arglists "([])"
              :seon.fn/private? false
              :seon.test/sym (quote sample/f)
              :seon.test/ns [:seon.ns/name 'sample]
              :seon.test/source "(deftest f)"}))]
      (is (= 'seon.program/declaration-refused! (:seon.error/operation data)))
      (is (= #{:seon.fn/sym :seon.test/sym} (:seon.program/identity-attributes data)))
      (is (= [[:seon.fn/sym (quote sample/f)]
              [:seon.test/sym (quote sample/f)]]
             (:seon.error/offending data)))))
  (testing "a recognized family without its reader-required data is loud"
    (doseq [event [{:seon.schema/key :sample/missing-form}
                   {:seon.test/sym (quote sample/missing-source)
                    :seon.test/ns [:seon.ns/name 'sample]}]]
      (let [data (refusal-data #(program/declaration-row (seon.schema/handed-projection) event :all :agent))]
        (is (= 'seon.program/declaration-refused! (:seon.error/operation data)))
        (is (= #{(first (program/row-identity event))} (:seon.program/identity-attributes data)))
        (is (= [(program/row-identity event)]
               (:seon.error/offending data)))))))

(deftest optional-attributes-are-replaced-exactly
  (let [current {:seon.fn/sym (quote sample/f)
                 :seon.fn/ns [:seon.ns/name 'sample]
                 :seon.fn/source "(defn f [] 1)"
                 :seon.fn/arglists "([])"
                 :seon.fn/private? false
                 :seon.fn/doc "old"
                 :seon.fn/spec "[:=> [:cat] :int]"
                 :seon.fn/calls [[:seon.fn/sym (quote sample/old)]]
                 :seon.fn/workload :compute}
        desired {:seon.fn/sym (quote sample/f)
                 :seon.fn/ns [:seon.ns/name 'sample]
                 :seon.fn/source "(defn f [] 2)"
                 :seon.fn/arglists "([])"
                 :seon.fn/private? false}]
    (is (= #{:seon.fn/source :seon.fn/doc :seon.fn/spec
             :seon.fn/calls :seon.fn/workload}
           (set (program/changed-attributes current desired)))))
  (is (= {:seon.test/sym (quote sample/property)
          :seon.test/ns [:seon.ns/name 'sample]
          :seon.test/source "(deftest property)"
          :seon.fn/calls [[:seon.fn/sym (quote sample/helper)]]
          :seon.test/subject [:seon.fn/sym (quote sample/subject)]}
         (program/canonical-row
          {:seon.test/sym (quote sample/property)
           :seon.test/ns [:seon.ns/name 'sample]
           :seon.test/source "(deftest property)"
           :seon.fn/calls [[:seon.fn/sym (quote sample/helper)]]
           :seon.test/subject [:seon.fn/sym (quote sample/subject)]
           :unowned/value :ignored})))
  (is (= [:seon.fn/sym (quote sample/subject)]
         (:seon.test/subject
          (program/canonical-row
           {:seon.fn/sym (quote sample/f)
            :seon.fn/ns [:seon.ns/name 'sample]
            :seon.fn/source "(defn f [] 1)"
            :seon.fn/arglists "([])"
            :seon.fn/private? false
            :seon.test/subject [:seon.fn/sym (quote sample/subject)]})))
      "function entities retain their declared test subject during canonicalization"))

(deftest schema-entity-properties-survive-and-retract-exactly
  (let [current {:seon.schema/key :sample/error
                 :seon.schema/form "[:map {:seon.db/attributes false}]"
                 :seon.schema.admission/source :agent
                 :seon.db/attributes false
                 :seon.render/ai 'sample/render-ai}
        desired (dissoc current :seon.render/ai)]
    (is (= current (program/canonical-row current)))
    (is (= [:seon.render/ai]
           (program/changed-attributes current desired)))))

(deftest runtime-schema-declarations-project-namespaced-properties
  (test-support/with-database
    (fn [connection]
      (let [event (one-event
                   "(seon.schema/register! ::error [:map {:seon.db/attributes false :seon.render/ai seon.render.value/render-ai} [:seon.error/message :seon.error/message]])")
            entity (program/declaration-row (db/carried-projection (db/db connection))
                                         event :contracted :agent)]
        (is (= false (:seon.db/attributes entity)))
        (is (= 'seon.render.value/render-ai (:seon.render/ai entity)))
        (is (= :agent (:seon.schema.admission/source entity)))))))

(deftest arbitrary-qualified-deftest-is-not-a-test-declaration
  (let [source (str "(ns sample (:require [clojure.test :refer [deftest]] "
                    "[foo :as foo]))\n"
                    "(foo/deftest impostor)\n"
                    "(deftest real-test)")
        events (reader/read
                {:seon.sci.reader/text source
                 :seon.config.eval.result/max-source (count source)})]
    (is (vector? events))
    (is (= #{'sample/real-test}
           (into #{} (keep :seon.test/sym) events)))))

(deftest typed-cross-namespace-deletion-retracts-function-and-test
  (test-support/with-database
    (fn [connection]
      (let [now (java.util.Date.)
            namespace-name 'my.agents.registration-test
            namespace-ref [:seon.ns/name namespace-name]
            function-sym (symbol "my.agents.registration-test" "same-name")
            deletion
            (program/deletion-row
             (one-event
              "(ns-unmap 'my.agents.registration-test 'same-name)"))
            settlement
            {:seon.turn/id "registration-delete"
             :seon.cluster.eval/ordinal 0
             :seon.eval/shown "nil"
             :seon.cluster.eval/ns
             [:seon.ns/name 'my.agents.someone-else]
             :seon.program/row deletion}]
        (test-support/seed-cluster! connection "registration-test")
        (test-support/transacted!
         connection
         (into [(test-support/namespace-entity (db/db connection) 'my.agents.someone-else)]
               (agent/creation-tx {:seon.agent/id "registration-test"
                                   :seon.ns/name namespace-name
                                   :seon.cluster/name "registration-test"})))
        (test-support/transacted!
         connection
         [(test-support/program-fn-entity (db/db connection) function-sym
            "(defn same-name {:malli/schema [:=> [:cat] :int]} [] 1)")
          ;; The canonical producer digests with the namespace entity the
          ;; agent's creation stored, not a fresh one.
          (test-support/program-entity (db/db connection) [:seon.test/sym function-sym]
                                    "(clojure.test/deftest same-name)")])
        (test-support/transacted!
                     connection
                     (turn/open-tx {:seon.turn/id "registration-delete" :seon.turn/agent [:seon.agent/id "registration-test"] :seon.turn/opened-tx "datomic.tx"}))
        (test-support/transacted!
                     connection
                     (turn/receipt-start-tx
                      {:seon.turn/id "registration-delete"
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/at now}))
        (is (= {:seon.program/delete-identities
                [[:seon.fn/sym function-sym]
                 [:seon.test/sym function-sym]]
                :seon.program/source
                "(ns-unmap 'my.agents.registration-test 'same-name)"
                :seon.program/ns namespace-ref}
               deletion))
        (doseq [identity-attribute [:seon.fn/sym :seon.test/sym]]
          (is (= function-sym
                 (get (db/pull (db/db connection) '[*]
                               [identity-attribute function-sym])
                      identity-attribute))))
        (test-support/transacted! connection (turn/receipt-settle-tx settlement))
        (doseq [identity-attribute [:seon.fn/sym :seon.test/sym]]
          (is (nil? (db/pull (db/db connection) '[*]
                             [identity-attribute function-sym]))))))))

(deftest schema-unregister-is-one-global-typed-deletion
  (let [event (one-event
               "(seon.schema/unregister! :shared.schema/amount)")]
    (is (= :shared.schema/amount
           (:seon.sci.reader/schema-unregister-key event)))
    (is (= {:seon.program/delete-identities
            [[:seon.schema/key :shared.schema/amount]]
            :seon.program/source
            "(seon.schema/unregister! :shared.schema/amount)"}
           (program/deletion-row event)))))

;;; ---------------------------------------------------------------------------
;;; Program entity ownership is derived from the declared entity maps
;;; ---------------------------------------------------------------------------

(defn- program-entity-map
  "The retained entity schema named by an identity attribute's declaration."
  [projection identity-attribute]
  (let [registry (:seon.schema.projection/registry projection)]
    (mr/schema registry (:seon.program/row-schema
                         (m/properties (mr/schema registry identity-attribute))))))

(deftest declaring-an-attribute-on-a-program-entity-schema-is-sufficient
  ;; The class both 7cfe02790 and 925ca19fe hit: an attribute declared on the
  ;; entity schema, emitted by the indexer, and silently dropped because a second
  ;; literal list did not name it. Declaration is now the whole requirement.
  (let [forms (schema/registered-schemas)
        entity {:seon.fn/sym (quote sample/f)
             :seon.fn/ns [:seon.ns/name 'sample]
             :seon.fn/source "(defn f [] 1)"
             :seon.fn/arglists "([])"
             :seon.fn/private? false
             :sample/declared-schema "carried"}
        declared (update-in forms [:seon.fn/fn 2] conj
                         [:sample/declared-schema {:optional true} :string])]
    (is (nil? (:sample/declared-schema
               (program/canonical-row (program/shapes-in (seon.schema/build-projection forms)) entity)))
        "an undeclared attribute is not a program entity attribute")
    (is (= "carried" (:sample/declared-schema
                      (program/canonical-row (program/shapes-in (seon.schema/build-projection declared)) entity)))
        "declaring it on :seon.fn/fn is sufficient — no code names it")
    (is (contains? (set (program/changed-attributes
                         (program/shapes-in (seon.schema/build-projection declared))
                         entity (dissoc entity :sample/declared-schema)))
                   :sample/declared-schema)
        "and an exact replacement retracts it when the source stops carrying it"))
  (testing "an entry naming another writer stays out of the indexer's hands"
    (let [forms (schema/registered-schemas)
          foreign (update-in forms [:seon.fn/fn 2] conj
                          [:sample/outcome
                           {:optional true
                            :seon.program/written-by 'sample/writer}
                           :string])
          entity {:seon.fn/sym (quote sample/f)
               :seon.fn/ns [:seon.ns/name 'sample]
               :seon.fn/source "(defn f [] 1)"
               :seon.fn/arglists "([])"
               :seon.fn/private? false
               :sample/outcome "written elsewhere"}]
      (is (nil? (:sample/outcome
                 (program/canonical-row (program/shapes-in (seon.schema/build-projection foreign)) entity))))
      (is (not (contains? (set (program/changed-attributes
                                (program/shapes-in (seon.schema/build-projection foreign))
                                entity (dissoc entity :sample/outcome)))
                          :sample/outcome))
          "so an exact re-index can never retract another writer's fact"))))

(deftest every-program-entity-attribute-is-owned-or-names-another-writer
  ;; The drift checker. It fails when a program entity map declares an
  ;; attribute that canonical-row neither keeps nor sees declared as written
  ;; elsewhere — the silent strip cannot return. It also fails LOUDLY when it
  ;; is measuring nothing, because an empty derivation would read as health.
  (let [projection (schema/handed-projection)
        shapes (program/shapes-in projection)]
    (is (= (set program/identity-attributes) (set (keys shapes)))
        "every identity family has a derived shape")
    (is (<= 6 (count shapes)) "the derivation found the program families")
    (let [foreign
          (into {}
                (for [identity-attribute program/identity-attributes
                      :let [definition (program-entity-map projection identity-attribute)
                            owned (:seon.program/owned-attributes
                                   (get shapes identity-attribute))
                            owned (if (coll? owned) (set owned) nil)
                            entries (seon.schema.internal/entity-entries definition)]
                      :when owned]
                  [identity-attribute
                   (into []
                         (keep (fn [entry]
                                 (let [attribute (first entry)
                                       properties (when (map? (second entry))
                                                    (second entry))]
                                   (when-not (or (contains? owned attribute)
                                                 (:seon.program/written-by properties))
                                     attribute))))
                         entries)]))]
      (is (every? empty? (vals foreign))
          "declared program attributes that are neither kept nor declared :seon.program/written-by"))
    (doseq [identity-attribute program/identity-attributes
            :let [shape (get shapes identity-attribute)
                  owned (:seon.program/owned-attributes shape)]]
      (is (qualified-keyword? (:seon.program/source-attribute shape)))
      (when (coll? owned)
        (is (seq owned))
        (is (some #{identity-attribute} owned))))
    (is (some (fn [identity-attribute]
                (some (fn [entry]
                        (and (map? (second entry))
                             (:seon.program/written-by (second entry))))
                      (seon.schema.internal/entity-entries (program-entity-map projection identity-attribute))))
              program/identity-attributes)
        "at least one entry declares another writer, so the exclusion is exercised")))

(deftest program-identity-attributes-are-exactly-the-declared-entity-schemas
  (let [projection (schema/handed-projection)
        forms (:seon.schema.projection/forms projection)
        shapes (program/shapes-in projection)
        declaring (into #{}
                        (keep (fn [[schema-key _definition]]
                                (let [properties (m/properties (mr/schema (:seon.schema.projection/registry projection) schema-key))]
                                  (when (and (:seon.program/row-schema properties)
                                             (:seon.program/source-attribute properties))
                                    schema-key))))
                        forms)]
    (is (seq declaring) "declarations were found")
    (is (= (set program/identity-attributes) declaring)
        "seon.program/identity-attributes names exactly the declared families")
    (is (schema/valid-candidate-value? projection :seon.program/identity-attribute :example/id))
    (is (schema/valid-candidate-value? projection :seon.program/source-attribute :example/source))))

(deftest program-partition-selects-roots-and-components-with-one-query
  (test-support/with-database
    (fn [connection]
    (let [database @connection
          projection (schema/handed-projection)
          attributes (program/program-attributes projection)
          entities (set (db/q '[:find [?e ...] :in $ [?a ...] :where [?e ?a]]
                          database attributes))
          census-attributes #{:seon.fn/sym :seon.ns/name :seon.test/sym
                              :seon.schema/key :seon.fn.file/relative-path
                              :seon.lint/id :seon.fn.arity/arity
                              :seon.fn.argument/rest? :seon.fn.binding/form
                              :seon.ns.alias/local :seon.ns.import/local
                              :seon.ns.refer/local}
          installed (set (db/q '[:find [?a ...] :where [_ ?a]] database))
          census-attributes (set/intersection census-attributes installed)
          expected (set (db/q '[:find ?a (count ?e) :in $ [?a ...] :where [?e ?a]]
                              database census-attributes))
          selected (set (db/q '[:find ?a (count ?e) :in $ [?a ...] [?e ...]
                               :where [?e ?a]] database census-attributes entities))]
      (is (seq entities))
      (is (>= (count expected) 9) "The canonical census contains roots and components")
      (is (= expected selected) "The single program query retains the complete schema census")
      (is (every? attributes #{:seon.fn/sym :seon.ns.alias/local
                              :seon.schema.map-entry/key-edn
                              :seon.program/definition-digest
                              :seon.render/ai :seon.render/html}))
      (is (not-any? attributes #{:seon.test/failures
                                 :seon.test/adoption-inputs :seon.schedule.task/id
                                 :seon.turn/id :seon.message/id}))))))

(deftest identity-bearing-entity-refuses-an-undeclared-partition
  (let [forms {:partition.example/id [:string {:seon.db/identity true}]
               :partition.example/row
               [:map {:seon.db/attributes true}
                [:partition.example/id :partition.example/id]]}
        refusal (refusal-data #(schema/build-projection forms))]
    (is (= :partition.example/row (:seon.schema/identity refusal)))
    (is (= :seon.program/partition (:seon.schema/member refusal)))
    (doseq [partition [:seon.program :seon.data]]
      (is (map? (schema/build-projection
                 (assoc-in forms [:partition.example/row 1 :seon.program/partition]
                           partition)))))))

(deftest resolved-shapes-match-the-current-declarations
  (is (= (program/shapes-in (seon.schema/build-projection (schema/registered-schemas)))
         (program/shapes))
      "the shapes answered with none in hand still describe the live declarations"))

(deftest a-declaration-added-after-the-first-call-is-a-cache-miss
  ;; THE CLASS. `shapes` cached the authored declarations in a process-level
  ;; defonce, so an attribute declared AFTER this JVM started was stripped
  ;; from every entity the indexer built until a restart: the runner lane
  ;; declared :seon.test/long-ms, the analyzer lifted it, canonical-row
  ;; dropped it, and the regression read that as "the indexer does not lift
  ;; it" (issue
  ;; program-shapes-cache-strips-attributes-declared-after-the-jvm-started).
  ;; The cache key is now the resources' own stamp, so a declaration edit is
  ;; a MISS BY CONSTRUCTION and no event has to remember to invalidate it.
  (let [owned (fn [shapes]
                (set (:seon.program/owned-attributes
                      (get shapes :seon.fn.file/relative-path))))
        before (program/shapes)
        declared (update (schema.edn/packaged-forms) :seon.fn.file/file conj
                         [::declared-after {:optional true} :string])
        entity {:seon.fn.file/relative-path "/probe/after-start.clj"
             ::declared-after "carried"}]
    (is (not (contains? (owned before) ::declared-after))
        "the attribute is genuinely absent from the authored declarations")
    (is (nil? (::declared-after (program/canonical-row entity)))
        "so no indexed entity carries it while it is undeclared")
    (let [after (with-redefs [schema.edn/packaged-forms (constantly declared)
                              schema.edn/declaration-stamp
                              (constantly [["declared-after.edn" 1 1]])]
                  (program/shapes))]
      (is (contains? (owned after) ::declared-after)
          "a changed resource stamp re-derives, without restarting the JVM")
      (is (= entity (program/canonical-row (program/shapes-in (seon.schema/build-projection declared)) entity))
          "and the entity built from that population carries the attribute"))
    (is (= before (program/shapes))
        "an unchanged stamp answers the same derivation, so the per-row
         caller pays one stamp and never a resource merge")))

(deftest indexed-and-evaluated-declarations-are-the-same-entities
  (let [source (slurp (io/resource "test/fixtures/program_facts_s1/source.txt"))
        fixture-forms (:seon.schema.edn/forms
                       (#'schema.edn/resource-population
                        "test/fixtures/program_facts_s1/schema.edn"))
        identities [[:seon.ns/name 'sample.s1]
                    [:seon.fn/sym (quote sample.s1/left)]
                    [:seon.fn/sym (quote sample.s1/right)]
                    [:seon.test/sym (quote sample.s1/paired)]
                    [:seon.schema/key :sample.s1/value]]
        entities-from
        (fn [connection]
          (let [database (db/db connection)
                shapes (program/shapes-in (db/carried-projection database))
                identity-attributes (db/identity-attributes database)]
            (into {}
                  (map (fn [identity]
                         (let [pulled (db/pull database '[*] identity)]
                           (is (some? (:db/id pulled)))
                           [identity
                            (apply dissoc
                                   (#'seon.fn/normalized-index-entity
                                    (assoc-in shapes [(first identity) :seon.program/owned-attributes]
                                              (vec (keys pulled)))
                                    database pulled identity-attributes
                                    #(db/pull database '[*] %))
                                   (into [:db/id :seon.schema.admission/source :seon.schema/ns
                                          :seon.fn/file :seon.fn/form-span
                                          :seon.program/analyzed-source-digest]
                                         (filter #(= "seon.fn.file" (namespace %)))
                                         (keys pulled)))])))
                  identities)))
        indexed
        (#'seon.fn-test/with-provenance-file
         "sample/s1.clj" source
         (fn [connection file _]
           (let [database (db/db connection)
                 forms (merge (:seon.schema.projection/forms (db/carried-projection database))
                              fixture-forms)
                 artifact (seon.fn/build-artifact
                           {:seon.fn/source-path (.getPath file)
                            :seon.fn.file/first-party-functions
                            (vec (db/q '[:find [?sym ...] :where [_ :seon.fn/sym ?sym]] database))
                            :seon.schema.projection/forms forms})
                 projection (reduce-kv
                             (fn [projection key definition]
                               (schema/projection-with-schema projection key definition
                                                              {:seon.schema.admission/source :core}))
                             (db/carried-projection database) fixture-forms)
                 entities (mapv
                       #(program/with-contract-facts
                         {:seon.program/row %
                          :seon.program/compile-options (:seon.schema.projection/compile-options projection)
                          :seon.program/predicate-functions (schema/predicate-functions-in projection)
                          :seon.program/schema-keys (set (keys forms))
                          :seon.program/schema-forms forms})
                       (into (:seon.fn.file/rows artifact)
                             (map #(accretion/schema-row forms %))
                             (schema/canonical-schema-entities projection fixture-forms)))
                 selected (filterv #(or (:seon.fn.file/relative-path %)
                                        (some #{(program/row-identity %)} identities)) entities)]
             (test-support/transacted!
              connection (seon.fn/reconcile-tx database selected []))
             (entities-from connection))))
        evaluated
        (test-support/with-database
         (fn [connection]
           (test-support/seed-cluster!
            connection "program-parity"
            {:seon.config.ai/no-provider true :seon.config.test/auto-check-cases 0})
           (test-support/transacted!
            connection
            (agent/creation-tx {:seon.agent/id "program-parity"
                                :seon.ns/name 'my.agents.program-parity
                                :seon.cluster/name "program-parity"}))
           (let [ctx (test-support/fork-cluster-ctx connection "program-parity")
                 routing (test-support/attempt-routing connection)
                 handle (test-support/cluster-handle
                         {:seon.env/environment (test-support/environment "program-parity" connection)
                          :seon.db/connection connection :seon.cluster/name "program-parity"
                          :seon.db.process/id cluster/boot-process-identity
                          :seon.sci.eval/ctx ctx
                          :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))})
                 request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id "program-parity"}
                 events (reader/read {:seon.sci.reader/text source
                                      :seon.sci.reader/ns 'my.agents.program-parity
                                      :seon.config.eval.result/max-source (count source)})
                 sources (mapv :seon.sci.reader/source events)
                 registration (pr-str (list 'seon.schema/register! :sample.s1/value
                                             (:sample.s1/value fixture-forms)))
                 ;; the agent's own graph runs the submission (a submitted run is
                 ;; a value on its request port until its one settlement)
                 submitted (turn/virtual-turn!
                            (assoc request :seon.cluster.reply/text
                                   (str/join "\n" (into [(first sources) registration] (rest sources)))))
                 turn-id (:seon.turn/id submitted)]
             (try
               (is (string? turn-id))
               (when-not turn-id (throw (ex-info "Parity turn refused" submitted)))
               (let [evaluations (seon.eval/of-agent (db/db connection) "program-parity")]
                 (is (seq evaluations))
                 (is (empty? (filter :seon.cluster.eval/error evaluations))))
               (entities-from connection)
               (finally
                 (agent/disarm! request)
                 (doseq [channel [(:seon.cluster.wake/channel handle)
                                  (:seon.render/context-channel handle)
                                  (:seon.turn.loop/completion handle)]]
                   (async/close! channel)))))))]
    (doseq [identity identities]
      (is (= (get indexed identity) (get evaluated identity))))
    (doseq [identity [[:seon.fn/sym (quote sample.s1/left)]
                      [:seon.fn/sym (quote sample.s1/right)]
                      [:seon.test/sym (quote sample.s1/paired)]]]
      (is (seq (:seon.fn/calls (get evaluated identity)))))))

(deftest replacement-dropping-all-set-members-writes-no-empty-set
  ;; Absence is no key: redefining a function without `:seon/tasks` retracts the
  ;; old members and adds nothing, never `:seon.task/tasks #{}`.
  (let [shapes (program/shapes)
        current {:db/id 7 :seon.fn/sym 'my.x/f :seon.task/tasks #{"7bfe767bec09"}}
        desired {:seon.fn/sym 'my.x/f}
        tx (program/exact-replacement-tx-in shapes current desired)
        row (last tx)]
    (is (not (contains? row :seon.task/tasks)))
    (is (some #{[:db/retract 7 :seon.task/tasks "7bfe767bec09"]} tx))))
