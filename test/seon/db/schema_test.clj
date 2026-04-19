(ns seon.db.schema-test
  "Tests for Malli → Datalevin schema bridge."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db.schema :as dbs]
            [seon.db.tx :as tx]))

(deftest malli-type->datalevin-type-test
  (testing "maps basic Malli types to Datalevin types"
    (is (= :db.type/string  (dbs/malli-type->datalevin-type :string)))
    (is (= :db.type/long    (dbs/malli-type->datalevin-type :int)))
    (is (= :db.type/double  (dbs/malli-type->datalevin-type :double)))
    (is (= :db.type/float   (dbs/malli-type->datalevin-type :float)))
    (is (= :db.type/boolean (dbs/malli-type->datalevin-type :boolean)))
    (is (= :db.type/keyword (dbs/malli-type->datalevin-type :keyword)))
    (is (= :db.type/symbol  (dbs/malli-type->datalevin-type :symbol)))
    (is (= :db.type/uuid    (dbs/malli-type->datalevin-type :uuid)))
    (is (= :db.type/instant (dbs/malli-type->datalevin-type :inst))))

  (testing "maps predicate types"
    (is (= :db.type/string  (dbs/malli-type->datalevin-type 'string?)))
    (is (= :db.type/instant (dbs/malli-type->datalevin-type 'inst?))))

  (testing "returns nil for unmappable types"
    (is (nil? (dbs/malli-type->datalevin-type :any)))
    (is (nil? (dbs/malli-type->datalevin-type :map)))))

(deftest bridge-basic-types-test
  (testing "string and int"
    (is (= {:foo/bar {:db/valueType :db.type/string}
            :foo/baz {:db/valueType :db.type/long}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/bar :string] [:foo/baz :int]]))))

  (testing ":maybe unwraps to inner type"
    (is (= {:foo/bar {:db/valueType :db.type/string}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/bar [:maybe :string]]]))))

  (testing ":vector becomes cardinality-many"
    (is (= {:foo/tags {:db/valueType :db.type/keyword
                       :db/cardinality :db.cardinality/many}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/tags [:vector :keyword]]]))))

  (testing ":set same as :vector"
    (is (= {:foo/tags {:db/valueType :db.type/string
                       :db/cardinality :db.cardinality/many}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/tags [:set :string]]])))))

(deftest bridge-enum-test
  (testing "keyword enum infers :db.type/keyword"
    (is (= {:foo/status {:db/valueType :db.type/keyword}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/status [:enum :active :inactive]]]))))

  (testing "string enum infers :db.type/string"
    (is (= {:foo/role {:db/valueType :db.type/string}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/role [:enum "admin" "user"]]])))))

(deftest bridge-db-props-test
  (testing ":db/* properties pass through verbatim"
    (is (= {:foo/id {:db/valueType :db.type/uuid
                     :db/unique :db.unique/identity}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/id {:db/unique :db.unique/identity} :uuid]]))))

  (testing ":db/valueType override for unmappable types"
    (is (= {:foo/data {:db/valueType :db.type/string}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/data {:db/valueType :db.type/string} :any]]))))

  (testing "nested :map becomes ref + component"
    (let [result (dbs/malli-map->datalevin-schema
                   [:map [:foo/child [:map [:bar/name :string]]]])]
      (is (= {:db/valueType :db.type/ref :db/isComponent true}
             (:foo/child result)))
      (is (= {:db/valueType :db.type/string}
             (:bar/name result))))))

(deftest bridge-inst-test
  (testing "inst? predicate maps to :db.type/instant"
    (is (= {:foo/at {:db/valueType :db.type/instant}}
           (dbs/malli-map->datalevin-schema
             [:map [:foo/at inst?]]))))

  (testing ":inst keyword maps to :db.type/instant"
    (is (= :db.type/instant (dbs/malli-type->datalevin-type :inst)))))

(deftest bridge-matches-existing-ctx-schema-test
  (testing "bridge output matches hand-written seon.ctx schema shape"
    (let [ctx-malli [:map
                     [:seon.ctx/instance-id {:db/unique :db.unique/identity} :string]
                     [:seon.ctx/namespace :symbol]
                     [:seon.ctx/data :string]
                     [:seon.ctx/updated-at inst?]]
          derived (dbs/malli-map->datalevin-schema ctx-malli)]
      (is (= :db.type/string (:db/valueType (:seon.ctx/instance-id derived))))
      (is (= :db.unique/identity (:db/unique (:seon.ctx/instance-id derived))))
      (is (= :db.type/symbol (:db/valueType (:seon.ctx/namespace derived))))
      (is (= :db.type/string (:db/valueType (:seon.ctx/data derived))))
      (is (= :db.type/instant (:db/valueType (:seon.ctx/updated-at derived)))))))

(deftest tx-schema-self-consistency-test
  (testing "datalevin-schema is derived from entity-schema via bridge"
    (is (= (dbs/malli-map->datalevin-schema tx/entity-schema)
           tx/datalevin-schema))))

(deftest tx-build-tx-entity-test
  (testing "produces valid structure with required fields"
    (let [entity (tx/build-tx-entity "seon.runtime" nil)]
      (is (= :db/current-tx (:db/id entity)))
      (is (inst? (:seon.db.tx/at entity)))
      (is (= "seon.runtime" (:seon.db.tx/caller entity)))
      (is (= :system (:seon.db.tx/source entity)))))

  (testing "REPL caller infers :repl source"
    (is (= :repl (:seon.db.tx/source (tx/build-tx-entity "user" nil))))
    (is (= :repl (:seon.db.tx/source (tx/build-tx-entity "user.foo" nil)))))

  (testing "agent caller infers :agent source"
    (is (= :agent (:seon.db.tx/source (tx/build-tx-entity "seon.agent.trading" nil)))))

  (testing "extra metadata merges"
    (let [entity (tx/build-tx-entity "seon.runtime"
                   {:seon.db.tx/session-id "a1b2"
                    :seon.db.tx/op :scan})]
      (is (= "a1b2" (:seon.db.tx/session-id entity)))
      (is (= :scan (:seon.db.tx/op entity)))))

  (testing "explicit source overrides inferred"
    (is (= :migration (:seon.db.tx/source
                         (tx/build-tx-entity "seon.runtime"
                           {:seon.db.tx/source :migration}))))))
