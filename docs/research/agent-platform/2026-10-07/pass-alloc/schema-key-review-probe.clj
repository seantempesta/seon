;; Observations through Datahike owners; not a test-suite runner. See schema-key-review-notes.md.
(require '[datahike.api :as d] '[datahike.writing :as w] '[datahike.versioning :as v]
         '[hasch.core :as h] '[konserve.core :as k])
(import '[java.lang.management ManagementFactory])
(def meta-keys [:schema :rschema :system-entities :ident-ref-map :ref-ident-map])
(defn keyed [db] (:schema-meta-key (second (w/db->stored db false))))
(defn observe [label db]
  (let [expected (h/uuid (select-keys db meta-keys)) actual (keyed db)
        stored (k/get (:store db) (:branch (:config db)) nil {:sync? true})]
    (prn {:case label :equal (= expected actual) :expected expected :actual actual
          :stored-equal (= expected (:schema-meta-key stored))})))
(try
  (let [cfg {:store {:backend :memory :id (random-uuid)} :keep-history? true}
        _ (d/create-database cfg) c (d/connect cfg)]
    (try
      (observe :fresh @c)
      (doseq [[label tx] [[:add [{:db/ident :r/a :db/valueType :db.type/string :db/cardinality :db.cardinality/one}]]
                         [:change [[:db/add [:db/ident :r/a] :db/doc "doc"]]]
                         [:retract-property [[:db/retract [:db/ident :r/a] :db/doc "doc"]]]
                         [:rename [[:db/retract 1 :db/ident :r/a] [:db/add 1 :db/ident :r/b]]]
                         [:retract-attribute [[:db/retractEntity [:db/ident :r/b]]]]]]
        (d/transact c tx) (observe label @c))
      (d/transact c [{:db/ident :r/a :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
      (v/branch! c :db :other)
      (let [other (d/connect (assoc cfg :branch :other))]
        (try
          (d/transact other [{:db/ident :r/c :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
          (observe :other @other)
          (d/transact c [{:r/a "first"}]) (observe :back-to-base @c)
          (d/transact other [{:r/c 1}]) (observe :back-to-other @other)
          (v/merge! c #{:other} [{:db/ident :r/c :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
          (observe :merge @c)
          (let [stored (second (w/db->stored @c false))
                restored (w/stored->db stored (:store @c))
                legacy (merge (dissoc stored :schema-meta-key) (select-keys @c meta-keys))
                upgraded (w/stored->db legacy (:store @c))]
            (observe :restored restored)
            (observe :inline-legacy upgraded))
          (finally (d/release other))))
      (let [date (java.util.Date. 1000)]
        (d/transact c [{:db/ident :r/mutable :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db.secondary/config {:at date}}])
        (let [before @c old-key (keyed before)]
          (.setTime date 2000)
          (prn {:case :mutable-config :same-map (identical? (:schema before) (:schema @c))
                :date-retained (identical? date (get-in @c [:schema :r/mutable :db.secondary/config :at]))
                :old old-key :current (keyed @c) :expected (h/uuid (select-keys @c meta-keys))})
          (d/transact c [{:r/a "after-mutation"}])
          (observe :mutable-config-commit @c)))
      (finally (d/release c) (d/delete-database cfg))))
  (catch Throwable e (prn {:error (.getMessage e) :data (ex-data e)})))
(doseq [refs? [false true]]
  (let [cfg {:store {:backend :memory :id (random-uuid)} :attribute-refs? refs?}
        _ (d/create-database cfg) c (d/connect cfg)]
    (try
      (d/transact c [{:db/ident :edge/a :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
      (let [a @c b (:db-after (d/with a [{:db/ident :edge/b :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]))
            expected (mapv #(h/uuid (select-keys % meta-keys)) [a b])
            cases (mapv (fn [n] (future (mapv (fn [i] (let [j (mod (+ i n) 2)]
                                                      (= (nth expected j) (keyed (nth [a b] j))))) (range 40)))) (range 4))]
        (prn {:case :concurrent-distinct-schemas :attribute-refs? refs? :comparisons 160
              :all-equal (every? true? (mapcat deref cases))}))
      (finally (d/release c) (d/delete-database cfg)))))
(doseq [[value mutate!] [[(java.util.Date. 1000) #(.setTime ^java.util.Date % 2000)]
                         [(byte-array [1 2]) #(aset-byte ^bytes % 0 (byte 3))]]]
  (let [cfg {:store {:backend :memory :id (random-uuid)}}
        _ (d/create-database cfg) c (d/connect cfg)]
    (try
      (d/transact c [{:db/ident :edge/m :db/valueType :db.type/string :db/cardinality :db.cardinality/one
                      :db.secondary/config {:value value}}])
      (let [before @c old (keyed before)]
        (mutate! value)
        (d/transact c [{:edge/m "x"}])
        (let [expected (h/uuid (select-keys @c meta-keys))
              stored (k/get (:store @c) :db nil {:sync? true})]
          (prn {:case :mutable-leaf :class (.getName (class value))
                :same-schema (identical? (:schema before) (:schema @c))
                :key-changed (not= old expected) :expected expected
                :equal (= expected (keyed @c)) :stored-equal (= expected (:schema-meta-key stored))})))
      (finally (d/release c) (d/delete-database cfg)))))
(shutdown-agents)
(System/exit 0)
