(ns seon.db.browser-projection-test
  "Focused bounded entity and reference projection proofs."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.db.browser :as browser]))

(def ^:private projection-schema
  [{:db/ident ::name
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string
    :db/unique :db.unique/identity}
   {:db/ident ::rank
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/long}
   {:db/ident ::owner
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/ref}])

(defn- fresh-fixture
  "Create one isolated graph with two incoming refs and an eid-like scalar."
  []
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? true}]
    (-> (d/create-database config)
        (.then (fn [_] (d/connect config {:sync? false})))
        (.then
          (fn [conn]
            (-> (d/transact! conn {:tx-data projection-schema})
                (.then
                  (fn [_]
                    (d/transact!
                      conn
                      {:tx-data
                       [{:db/id "target" ::name "target"}
                        {:db/id "source-a" ::name "source-a" ::owner "target"}
                        {:db/id "source-b" ::name "source-b" ::owner "target"}]})))
                (.then
                  (fn [_]
                    (let [dbv @conn
                          target (d/q '[:find ?e . :in $ ?attribute ?name
                                        :where [?e ?attribute ?name]]
                                      dbv ::name "target")
                          source-a (d/q '[:find ?e . :in $ ?attribute ?name
                                          :where [?e ?attribute ?name]]
                                        dbv ::name "source-a")]
                      (-> (d/transact!
                            conn
                            {:tx-data [{:db/id source-a ::rank target}]})
                          (.then
                            (fn [_]
                              {:seon.db.browser-projection-test/conn conn
                               :seon.db.browser-projection-test/target target
                               :seon.db.browser-projection-test/source-a source-a}))))))))))))

(defn- with-fixture [body done]
  (-> (fresh-fixture)
      (.then body)
      (.catch (fn [error]
                (is false (str "projection test rejected — " error))))
      (.then (fn [_] (done)))))

(defn- entity-request [dbv coordinate entity limit]
  {:seon.db/db dbv
   ::browser/database-coordinate coordinate
   ::browser/entity entity
   ::browser/limit limit})

(defn- reverse-request [dbv coordinate attribute target limit]
  {:seon.db/db dbv
   ::browser/database-coordinate coordinate
   ::browser/attribute attribute
   ::browser/target-entity target
   ::browser/limit limit})

(deftest cursor-last-is-validated-before-an-index-read
  (async done
    (with-fixture
      (fn [{::keys [conn source-a]}]
        (let [dbv @conn
              coordinate (db/head-coordinate dbv)
              request (entity-request dbv coordinate source-a 1)
              first-page (browser/entity-page request)
              payload (browser/decode-cursor (::browser/next-cursor first-page))
              noncanonical
              (assoc-in payload
                        [:seon.db.browser.cursor/last :seon.db/v]
                        [:seon.db.browser.cursor.value/keyword "not-canonical"])
              wrong-prefix
              (update-in payload
                         [:seon.db.browser.cursor/last :seon.db/e]
                         inc)
              noncanonical-token (@#'browser/encode-payload noncanonical)
              wrong-prefix-token (@#'browser/encode-payload wrong-prefix)
              index-reads (atom 0)
              reverse-reads (atom 0)]
          (with-redefs [db/index-datoms
                        (fn [_] (swap! index-reads inc) [])
                        db/rseek-datoms
                        (fn [_] (swap! reverse-reads inc) [])]
            (is (= :seon.db.browser.cursor.error/invalid-payload
                   (::browser/error
                     (browser/entity-page
                       (assoc request ::browser/cursor noncanonical-token)))))
            (is (= :seon.db.browser.cursor.error/request-mismatch
                   (::browser/error
                     (browser/entity-page
                       (assoc request ::browser/cursor wrong-prefix-token))))))
          (is (zero? @index-reads))
          (is (zero? @reverse-reads))))
      done)))

(deftest entity-page-is-bounded-and-derives-reference-shape
  (async done
    (with-fixture
      (fn [{::keys [conn target source-a]}]
        (let [dbv @conn
              coordinate (db/head-coordinate dbv)
              requests (atom [])
              original db/index-datoms
              page
              (with-redefs [db/index-datoms
                            (fn [request]
                              (swap! requests conj request)
                              (original request))]
                (browser/entity-page
                  (entity-request dbv coordinate source-a 10)))
              facts (::browser/facts page)
              by-attribute (into {} (map (juxt ::browser/attribute identity)) facts)]
          (is (= 3 (count facts)))
          (is (every? #(= source-a (::browser/entity %)) facts))
          (is (= target (::browser/value (get by-attribute ::rank))))
          (is (false? (::browser/reference? (get by-attribute ::rank)))
              "a scalar equal to an eid is not a reference")
          (is (= target (::browser/value (get by-attribute ::owner))))
          (is (true? (::browser/reference? (get by-attribute ::owner))))
          (is (= [{::db/index :eavt
                   ::db/components [source-a]
                   ::db/index-limit 11
                   ::db/seek? false}]
                 (mapv #(select-keys % [::db/index ::db/components
                                        ::db/index-limit ::db/seek?])
                       @requests)))
          (let [schema-reads (atom 0)
                original-schema db/installed-schema
                missing
                (with-redefs [db/installed-schema
                              (fn [value]
                                (swap! schema-reads inc)
                                (original-schema value))]
                  (browser/entity-page
                    (entity-request dbv coordinate 999999 10)))]
            (is (empty? (::browser/facts missing)))
            (is (zero? @schema-reads)
                "an empty entity page does not derive ref metadata"))))
      done)))

(deftest reverse-reference-page-reuses-bounded-avet-cursors
  (async done
    (with-fixture
      (fn [{::keys [conn target]}]
        (let [dbv @conn
              coordinate (db/head-coordinate dbv)
              request (reverse-request dbv coordinate ::owner target 1)
              calls (atom [])
              original db/index-datoms
              [first-page second-page]
              (with-redefs [db/index-datoms
                            (fn [index-request]
                              (swap! calls conj index-request)
                              (original index-request))]
                (let [first-page (browser/reverse-reference-page request)]
                  [first-page
                   (browser/reverse-reference-page
                     (assoc request ::browser/cursor
                       (::browser/next-cursor first-page)))]))
              first-fact (first (::browser/facts first-page))
              second-fact (first (::browser/facts second-page))]
          (is (= target (::browser/value first-fact)))
          (is (= target (::browser/value second-fact)))
          (is (= ::owner (::browser/attribute first-fact)))
          (is (true? (::browser/reference? first-fact)))
          (is (not= (::browser/entity first-fact)
                    (::browser/entity second-fact)))
          (is (= [[::owner target] [::owner target]]
                 (mapv #(subvec (::db/components %) 0 2) @calls)))
          (is (= [2 4] (mapv #(count (::db/components %)) @calls)))
          (is (= [2 4] (mapv ::db/index-limit @calls)))
          (is (= [false true] (mapv ::db/seek? @calls)))))
      done)))

(deftest reverse-reference-rejects-invalid-attributes-before-avet
  (async done
    (with-fixture
      (fn [{::keys [conn target]}]
        (let [dbv @conn
              coordinate (db/head-coordinate dbv)
              reads (atom 0)]
          (with-redefs [db/index-datoms (fn [_] (swap! reads inc) [])
                        db/rseek-datoms (fn [_] (swap! reads inc) [])]
            (testing "an installed scalar is not a reverse-ref projection"
              (is (= :seon.db.browser.projection.error/not-reference
                     (::browser/error
                       (browser/reverse-reference-page
                         (reverse-request dbv coordinate ::rank target 10))))))
            (testing "an unknown attribute is rejected before Datahike"
              (is (= :seon.db.browser.projection.error/unknown-attribute
                     (::browser/error
                       (browser/reverse-reference-page
                         (reverse-request dbv coordinate ::missing target 10)))))))
          (is (zero? @reads))))
      done)))
