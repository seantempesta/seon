(ns seon.db.examples-test
  "Proves every recipe in seon.db.examples compiles AND runs against a
   fresh :memory db — the manual can't bit-rot.

   The examples read `db/*conn*` AMBIENTLY (db-omitted), exactly as the
   live pod does, so a test must install a conn on the ROOT `db/*conn*`
   (a `binding` would pop at the first async hop — CLJS dynamic bindings
   don't survive `await`). That root is a SHARED global the whole suite
   (~20 namespaces) mutates; a background fiber from an earlier
   async-heavy test (the agent loop, the ticker) can `set!` it between
   our async hops. So each `.then` RE-PINS the test's conn via `pinned`
   right before any ambient read — a synchronous read right after a
   `set!` can't be interleaved, which closes the contamination window.
   (Contrast seon.db-test, which threads `::db/conn` explicitly and so
   never touches the root.)"
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
  "Run `(body conn)` with a fresh conn installed on the ROOT db/*conn*;
   restore the prior root after `body` settles. `body` is handed the conn
   so it can RE-PIN before each ambient read (see `pinned`)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- pinned
  "Wrap a `.then` callback so it RE-PINS `conn` as the root db/*conn*
   before running. A concurrent test's fiber may have `set!` the shared
   root during the preceding await; re-pinning means every ambient
   (db-omitted) read in `f` resolves against THIS test's conn. The reads
   in `f` are synchronous, so nothing interleaves between the `set!` and
   them."
  [conn f]
  (fn [x] (set! db/*conn* conn) (f x)))

(defn- run-test
  "with-conn + cljs.test/async glue: call `(chain conn)` (returns a
   Promise), then `done`; a rejection anywhere fails the test loudly."
  [chain done]
  (-> (with-conn (fn [conn] (chain conn)))
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

;;; ───────────────────────────────────────────────────────────────────────
;;; The centerpiece: register → seed → aggregate, end to end.
;;; ───────────────────────────────────────────────────────────────────────

(deftest workflow-builds-and-aggregates
  (async done
    (run-test
      (fn [conn]
        (-> (ex/build-reading-log!)
            (.then (pinned conn
                     (fn [summary]
                       (is (map? summary) "resolves to a map")
                       (is (contains? summary :seon.db.examples/count)
                           "resolves to the stats summary, not a failure envelope")
                       ;; build-reading-log! WROTE to this conn (transact!
                       ;; captures the conn at call time), then reads the
                       ;; stats back via ambient *conn* AFTER its own await —
                       ;; the one read a concurrent test's fiber could clobber.
                       ;; Re-derive the numbers against the re-pinned conn so
                       ;; the assertion is deterministic.
                       (let [s (ex/reading-stats)]
                         (is (= 3 (:seon.db.examples/count s)))
                         (is (= 14 (:seon.db.examples/rating-total s))
                             ":with ?e keeps each entity's row distinct — both 5s count")
                         (is (= {:fiction 2 :canlit 1 :short-stories 1 :poetry 1 :travel 1}
                                (:seon.db.examples/tag-counts s))
                             "grouped aggregate counts readings per tag")))))))
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; Read recipes — query shapes + pull/entity.
;;; ───────────────────────────────────────────────────────────────────────

(deftest read-recipes-return-expected-shapes
  (async done
    (run-test
      (fn [conn]
        (-> (ex/build-reading-log!)
            (.then (pinned conn
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
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; Write recipes — upsert / retract one attr / cardinality-many replace /
;;; retractEntity, each verified by read-back.
;;; ───────────────────────────────────────────────────────────────────────

(deftest mutation-recipes-modify-in-place
  (async done
    (run-test
      (fn [conn]
        (-> (ex/build-reading-log!)
            ;; UPSERT — rename in place, no duplicate
            (.then (pinned conn (fn [_] (ex/rename-reading! "r1" "Lives of Girls and Women (rev.)"))))
            (.then (pinned conn
                     (fn [_]
                       (is (= "Lives of Girls and Women (rev.)"
                              (:my.reading/title (ex/reading-entity "r1"))))
                       (is (= 3 (:seon.db.examples/count (ex/reading-stats)))
                           "upsert updated in place — still 3 readings"))))
            ;; RETRACT one attr
            (.then (pinned conn (fn [_] (ex/clear-rating! "r2"))))
            (.then (pinned conn
                     (fn [_]
                       (is (nil? (:my.reading/rating (ex/reading-entity "r2")))
                           "rating retracted")
                       (is (= 10 (:seon.db.examples/rating-total (ex/reading-stats)))
                           "rating-total dropped by 4"))))
            ;; cardinality-many REPLACE — overlapping set proves the
            ;; retract-before-add is order-correct: :travel dropped, :haiku
            ;; added, and the surviving :poetry is neither lost nor duplicated.
            (.then (pinned conn (fn [_] (ex/replace-tags! "r3" [:poetry :haiku]))))
            (.then (pinned conn
                     (fn [_]
                       (is (= #{:poetry :haiku}
                              (set (:my.reading/tags (ex/reading-entity "r3"))))
                           "tags replaced, not appended; overlapping value survives"))))
            ;; retractEntity
            (.then (pinned conn (fn [_] (ex/delete-reading! "r3"))))
            (.then (pinned conn
                     (fn [_]
                       (is (nil? (ex/reading-entity "r3")) "whole entity deleted")
                       (is (= 2 (:seon.db.examples/count (ex/reading-stats)))
                           "down to 2 readings"))))))
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; Inventory — the discovery call lists the kinds we just stored.
;;; ───────────────────────────────────────────────────────────────────────

(deftest inventory-lists-stored-kinds
  (async done
    (run-test
      (fn [conn]
        (-> (ex/build-reading-log!)
            (.then (pinned conn
                     (fn [_]
                       (let [kinds (set (map :seon.db/kind (ex/inventory)))]
                         (is (contains? kinds :my.reading)
                             "the reading kind shows once its data lands")
                         (is (contains? kinds :my.reading.author)
                             "the author kind too")))))))
      done)))
