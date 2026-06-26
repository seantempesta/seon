(ns seon.db.examples-test
  "Proves every recipe in seon.db.examples compiles AND runs against a
   fresh :memory db — the manual can't bit-rot. Uses the same
   set!-root-*conn* idiom as the live pod (and seon.db-test), so the
   ambient (db-omitted) forms work across async hops exactly as they do
   in production."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.agent]                         ; loads :seon.schema/key — store-inventory's attr guard names it
    [seon.db :as db]
    [seon.db.examples :as ex]))

;;; ───────────────────────────────────────────────────────────────────────
;;; Fresh :memory conn per test. No pre-installed schema — db/transact!
;;; installs datahike schema for registered attrs on first write.
;;; ───────────────────────────────────────────────────────────────────────

(defn- fresh-conn []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false}))))))

(defn- with-conn
  "Fresh conn `set!` as the ROOT db/*conn* for `body` (conn → Promise),
   prior root restored after. set!, not `binding` — CLJS dynamic bindings
   pop at the first async hop; the live pod set!s the root at boot, which
   is why the db-omitted forms auto-inject."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; The centerpiece: register → seed → aggregate, end to end.
;;; ───────────────────────────────────────────────────────────────────────

(deftest workflow-builds-and-aggregates
  (async done
    (-> (with-conn
          (fn [_]
            (-> (ex/build-reading-log!)
                (.then (fn [summary]
                         (is (map? summary) "resolves to the stats summary")
                         (is (= 3 (:seon.db.examples/count summary)))
                         (is (= 14 (:seon.db.examples/rating-total summary)))
                         (is (= {:fiction 2 :canlit 1 :short-stories 1 :poetry 1 :travel 1}
                                (:seon.db.examples/tag-counts summary))
                             "grouped aggregate counts readings per tag"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; Read recipes — query shapes + pull/entity.
;;; ───────────────────────────────────────────────────────────────────────

(deftest read-recipes-return-expected-shapes
  (async done
    (-> (with-conn
          (fn [_]
            (-> (ex/build-reading-log!)
                (.then
                  (fn [_]
                    (is (= #{"Lives of Girls and Women"
                             "Dance of the Happy Shades"
                             "The Narrow Road to the Deep North"}
                           (set (ex/titles)))
                        "collection find → vector of titles")
                    (is (= #{["Lives of Girls and Women" 5]
                             ["Dance of the Happy Shades" 4]
                             ["The Narrow Road to the Deep North" 5]}
                           (ex/title+rating))
                        "relation find → set of [title rating] tuples")
                    (is (= #{"Lives of Girls and Women" "Dance of the Happy Shades"}
                           (set (ex/titles-by-author "Alice Munro")))
                        ":in-bound input + ref-join")
                    (let [detail (ex/reading-detail "r1")]
                      (is (= "Lives of Girls and Women" (:my.reading/title detail)))
                      (is (= "Re-read the opening chapter."
                             (-> detail :my.reading/notes first :my.reading.note/body))
                          "component child inlined under [*]")
                      (is (= "Alice Munro"
                             (-> detail :my.reading/author :my.reading.author/name))
                          "plain ref expanded by naming it with a sub-pattern"))
                    (let [e (ex/reading-entity "r3")]
                      (is (= "The Narrow Road to the Deep North" (:my.reading/title e)))
                      (is (= 5 (:my.reading/rating e))))
                    (is (nil? (ex/reading-entity "nope"))
                        "unresolved lookup-ref → nil"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; Write recipes — upsert / retract one attr / cardinality-many replace /
;;; retractEntity, each verified by read-back.
;;; ───────────────────────────────────────────────────────────────────────

(deftest mutation-recipes-modify-in-place
  (async done
    (-> (with-conn
          (fn [_]
            (-> (ex/build-reading-log!)
                ;; UPSERT — rename in place, no duplicate
                (.then (fn [_] (ex/rename-reading! "r1" "Lives of Girls and Women (rev.)")))
                (.then (fn [_]
                         (is (= "Lives of Girls and Women (rev.)"
                                (:my.reading/title (ex/reading-entity "r1"))))
                         (is (= 3 (:seon.db.examples/count (ex/reading-stats)))
                             "upsert updated in place — still 3 readings")))
                ;; RETRACT one attr
                (.then (fn [_] (ex/clear-rating! "r2")))
                (.then (fn [_]
                         (is (nil? (:my.reading/rating (ex/reading-entity "r2")))
                             "rating retracted")
                         (is (= 10 (:seon.db.examples/rating-total (ex/reading-stats)))
                             "rating-total dropped by 4")))
                ;; cardinality-many REPLACE
                (.then (fn [_] (ex/replace-tags! "r3" [:haiku])))
                (.then (fn [_]
                         (is (= #{:haiku}
                                (set (:my.reading/tags (ex/reading-entity "r3"))))
                             "tags replaced, not appended")))
                ;; retractEntity
                (.then (fn [_] (ex/delete-reading! "r3")))
                (.then (fn [_]
                         (is (nil? (ex/reading-entity "r3")) "whole entity deleted")
                         (is (= 2 (:seon.db.examples/count (ex/reading-stats)))
                             "down to 2 readings"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; Inventory — the discovery call lists the kinds we just stored.
;;; ───────────────────────────────────────────────────────────────────────

(deftest inventory-lists-stored-kinds
  (async done
    (-> (with-conn
          (fn [_]
            (-> (ex/build-reading-log!)
                (.then (fn [_]
                         (let [kinds (set (map :seon.db/kind (ex/inventory)))]
                           (is (contains? kinds :my.reading)
                               "the reading kind shows once its data lands")
                           (is (contains? kinds :my.reading.author)
                               "the author kind too")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
