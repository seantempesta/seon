(require '[datahike.api :as d] '[clojure.pprint :as pp])
(defn show [l v] (println (str "\n;; === " l " ===")) (pp/pprint v))

(def cfg {:store {:backend :memory :id #uuid "3f2b7c10-1111-4222-8333-444455556666"} :keep-history? true :schema-flexibility :write})
(when (d/database-exists? cfg) (d/delete-database cfg))
(d/create-database cfg)
(def conn (d/connect cfg))

(d/transact conn [{:db/ident :seon.message/id :db/valueType :db.type/string
                   :db/unique :db.unique/identity :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.message/text :db/valueType :db.type/string
                   :db/cardinality :db.cardinality/one}
                  {:db/ident :seon.message/read? :db/valueType :db.type/boolean
                   :db/cardinality :db.cardinality/one}])

(d/transact conn [{:seon.message/id "m1" :seon.message/text "hello"  :seon.message/read? false}
                  {:seon.message/id "m2" :seon.message/text "second" :seon.message/read? false}
                  {:seon.message/id "m3" :seon.message/text "third"  :seon.message/read? false}])

(def basis-t (:max-tx (d/db conn)))
(show "basis-t after the seed transaction" basis-t)

;; three kinds of change after the basis
(d/transact conn [[:db/add [:seon.message/id "m1"] :seon.message/read? true]])           ; changed
(d/transact conn [{:seon.message/id "m4" :seon.message/text "fourth" :seon.message/read? false}]) ; added
(d/transact conn [[:db/retractEntity [:seon.message/id "m2"]]])                          ; removed

(def db (d/db conn))
(show "current max-tx" (:max-tx db))

(show "q over current db"
      (sort (d/q '[:find ?id ?r :where [?e :seon.message/id ?id] [?e :seon.message/read? ?r]] db)))
(show "q over (as-of db basis-t)"
      (sort (d/q '[:find ?id ?r :where [?e :seon.message/id ?id] [?e :seon.message/read? ?r]] (d/as-of db basis-t))))

(show "datoms of (since db basis-t) :eavt  -- what `since` actually exposes"
      (mapv (juxt :e :a :v :tx :added) (d/datoms (d/since db basis-t) :eavt)))

(show "q over (since db basis-t) : which identities have any change?"
      (sort (d/q '[:find ?id :where [?e :seon.message/id ?id]] (d/since db basis-t))))

(show "history datoms with tx > basis-t (assertions AND retractions)"
      (->> (d/datoms (d/history db) :eavt)
           (filter #(> (:tx %) basis-t))
           (mapv (juxt :e :a :v :tx :added))))
