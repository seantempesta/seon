(ns my.kb-test
  "Two contracts, one fresh-:memory conn seeded like the pod boots (never
   the live agent conn):

     1. THE SCAFFOLD: the four shared provenance shapes are registered ONCE;
        my.kb.shared (the cluster-wide instruction singleton, context-v4
        V4-0) seeds EMPTY and idempotent; rows are APPENDED by transact
        (nested component maps under the many-ref) and read back in append
        order via the `instructions` fn; re-seeding never clobbers appended
        rows.

     2. THE WORKED DB MANUAL: every recipe in my.kb compiles AND runs — the
        manual can't bit-rot. `build-kb-example!` registers the sample
        `:my.kb.source/*` schema, seeds 3 sources / 2 authors / 1 component
        finding, and aggregates; the read/mutation recipes are then exercised
        directly and verified by read-back.

   The worked recipes read `db/*conn*` AMBIENTLY (db-omitted), exactly as the
   live pod does, so the test installs the conn on the ROOT `db/*conn*` (a
   `binding` would pop at the first async hop — CLJS dynamic bindings don't
   survive `await`). That root is a SHARED global the whole suite mutates; a
   background fiber from an async-heavy test can `set!` it between our async
   hops. So each `.then` that does an ambient read RE-PINS the test's conn via
   `pinned` first — a synchronous read right after a `set!` can't be
   interleaved, which closes the contamination window."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    [my.kb :as kb]
    [my.kb.shared :as kb-shared]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the
   shipped my.kb.shared seed (the same row seon.client seeds at boot).
   The `:my.kb.source/*` sample schema is NOT pre-installed — db/transact!
   lazy-installs it on the first write (build-kb-example!/remember-sources!)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact! conn
                                {:tx-data (kb-shared/seed-tx-data)})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn, `set!` as the ROOT db/*conn* for `body` (conn →
   Promise), prior root restored after (root set!, not `binding` — CLJS
   dynamic bindings pop at the first microtask boundary). `body` is handed
   the conn so it can RE-PIN before each ambient read (see `pinned`)."
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
;;; 1. THE SCAFFOLD — shared provenance shapes + the instruction singleton.
;;; ───────────────────────────────────────────────────────────────────────

(deftest the-shared-provenance-shapes-are-registered-once
  (is (= :string (schema/schema-definition :my.kb/source-path)))
  (is (= :int (schema/schema-definition :my.kb/source-line)))
  (is (= :int (schema/schema-definition :my.kb/source-line-end))
      "line RANGES are two ints on shared attrs (start + inclusive end) —
       never a string, never a forked plural attr")
  (is (= :inst (schema/schema-definition :my.kb/verified-at)))
  (is (= [:enum :verified :inferred]
         (schema/schema-definition :my.kb/confidence))
      "confidence is the shared enum — domains reference it, never inline it"))

(deftest shared-seed-is-the-empty-singleton
  (let [rows (kb-shared/seed-tx-data)]
    (is (= [{:my.kb.shared/id "shared"}] rows)
        "the seed is ONE empty identity row — the four behavioral
         teachings live in the system prompt (V4-0), never here")))

(deftest shared-instructions-read-empty-then-ordered-after-appends
  (async done
    (-> (with-conn
          (fn [_conn]
            (is (= [] (kb-shared/instructions))
                "fresh store → no rows → [] (the zero state)")
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.kb.shared/id "shared"
                     :my.kb.shared/instructions
                     [{:my.kb.shared/text "Always store provenance with findings."
                       :my.kb.shared/at   (js/Date. 1000)}]}]})
                (.then
                  (fn [{ok? :seon.db/ok?}]
                    (is (true? ok?) "an append is ONE nested-map transact")
                    (db/transact!
                      {:seon.db/tx-data
                       [{:my.kb.shared/id "shared"
                         :my.kb.shared/instructions
                         [{:my.kb.shared/text "Prefer editing an existing schema."
                           :my.kb.shared/at   (js/Date. 2000)}]}]})))
                (.then
                  (fn [{ok? :seon.db/ok?}]
                    (is (true? ok?) "a second agent's append is the same move")
                    (is (= ["Always store provenance with findings."
                            "Prefer editing an existing schema."]
                           (kb-shared/instructions))
                        "re-read shows BOTH rows, oldest append first"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest shared-instructions-append-by-transact
  ;; The reseed-safety contract: appending a row and then re-running
  ;; the boot seed leaves the appended row intact (the seed carries no
  ;; ::instructions value — identity upsert, zero clobber).
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.kb.shared/id "shared"
                     :my.kb.shared/instructions
                     [{:my.kb.shared/text "Survives the reseed."
                       :my.kb.shared/at   (js/Date.)}]}]})
                (.then (fn [_]
                         ;; the pod-restart move: re-transact the seed
                         (d/transact! conn {:tx-data (kb-shared/seed-tx-data)})))
                (.then (fn [_]
                         (is (= ["Survives the reseed."]
                                (kb-shared/instructions))
                             "re-seeding never clobbers appended rows"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; 2. THE WORKED DB MANUAL — register → seed → aggregate, end to end.
;;;    seed: 3 sources (ratings 5,4,5 → rating-total 14, count 3),
;;;    2 authors (mccarthy×2, okasaki×1), s1 has one component finding.
;;; ───────────────────────────────────────────────────────────────────────

(deftest build-kb-example-registers-seeds-and-aggregates
  (async done
    (run-test
      (fn [conn]
        (-> (kb/build-kb-example!)
            (.then (pinned conn
                     (fn [summary]
                       (is (map? summary) "resolves to a map")
                       (is (contains? summary :my.kb/count)
                           "resolves to the stats summary, not a failure envelope")
                       ;; build-kb-example! WROTE to this conn (transact!
                       ;; captures the conn at call time), then reads the stats
                       ;; back via ambient *conn* AFTER its own await — the one
                       ;; read a concurrent fiber could clobber. Re-derive the
                       ;; numbers against the re-pinned conn so it's deterministic.
                       (let [s (kb/source-stats)]
                         (is (= 3 (:my.kb/count s)))
                         (is (= 14 (:my.kb/rating-total s))
                             ":with ?e keeps each entity's row distinct — both 5s count")
                         (is (= {:lisp 2 :foundations 1 :reference 1
                                 :functional 1 :data-structures 1}
                                (:my.kb/topic-counts s))
                             "grouped aggregate counts sources per topic")))))))
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; Read recipes — query shapes + pull/entity.
;;; ───────────────────────────────────────────────────────────────────────

(deftest read-recipes-return-expected-shapes
  (async done
    (run-test
      (fn [conn]
        (-> (kb/build-kb-example!)
            (.then (pinned conn
                     (fn [_]
                       (is (= #{"Recursive Functions of Symbolic Expressions"
                                "LISP 1.5 Programmer's Manual"
                                "Purely Functional Data Structures"}
                              (set (kb/titles)))
                           "collection find → vector of titles")
                       (is (= #{["Recursive Functions of Symbolic Expressions" 5]
                                ["LISP 1.5 Programmer's Manual" 4]
                                ["Purely Functional Data Structures" 5]}
                              (kb/title+rating))
                           "relation find → set of [title rating] tuples")
                       (is (= #{"Recursive Functions of Symbolic Expressions"
                                "LISP 1.5 Programmer's Manual"}
                              (set (kb/titles-by-author "John McCarthy")))
                           ":in-bound input + ref-join → McCarthy's two titles")
                       (let [detail (kb/source-detail "s1")]
                         (is (= "Recursive Functions of Symbolic Expressions"
                                (:my.kb.source/title detail)))
                         (is (= "Code and data share one representation."
                                (-> detail :my.kb.source/findings first :my.kb.finding/text))
                             "component child inlined under [*]")
                         (is (= "John McCarthy"
                                (-> detail :my.kb.source/author :my.kb.author/name))
                             "plain ref expanded by naming it with a sub-pattern"))
                       (let [e (kb/source-entity "s3")]
                         (is (= "Purely Functional Data Structures" (:my.kb.source/title e)))
                         (is (= 5 (:my.kb.source/rating e))))
                       (is (nil? (kb/source-entity "nope"))
                           "unresolved lookup-ref → nil"))))))
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; Write recipes — upsert / retract one attr / cardinality-many replace /
;;; retractEntity cascade, each verified by read-back.
;;; ───────────────────────────────────────────────────────────────────────

(deftest mutation-recipes-modify-in-place
  (async done
    (run-test
      (fn [conn]
        (-> (kb/build-kb-example!)
            ;; UPSERT — retitle in place, no duplicate
            (.then (pinned conn (fn [_] (kb/retitle-source! "s1" "Recursive Functions (rev.)"))))
            (.then (pinned conn
                     (fn [_]
                       (is (= "Recursive Functions (rev.)"
                              (:my.kb.source/title (kb/source-entity "s1"))))
                       (is (= 3 (:my.kb/count (kb/source-stats)))
                           "upsert updated in place — still 3 sources"))))
            ;; RETRACT one attr — clear s2's rating (4)
            (.then (pinned conn (fn [_] (kb/clear-rating! "s2"))))
            (.then (pinned conn
                     (fn [_]
                       (is (nil? (:my.kb.source/rating (kb/source-entity "s2")))
                           "rating retracted")
                       (is (= 10 (:my.kb/rating-total (kb/source-stats)))
                           "rating-total dropped by 4"))))
            ;; cardinality-many REPLACE — overlapping set proves retract-before-add
            ;; is order-correct: :foundations dropped, :history added, the
            ;; surviving :lisp is neither lost nor duplicated.
            (.then (pinned conn (fn [_] (kb/replace-topics! "s1" [:lisp :history]))))
            (.then (pinned conn
                     (fn [_]
                       (is (= #{:lisp :history}
                              (set (:my.kb.source/topics (kb/source-entity "s1"))))
                           "topics replaced, not appended; overlapping :lisp survives"))))
            ;; retractEntity — cascade to the component finding
            (.then (pinned conn (fn [_] (kb/forget-source! "s1"))))
            (.then (pinned conn
                     (fn [_]
                       (is (nil? (kb/source-entity "s1")) "whole entity deleted")
                       (is (nil? (db/entity [:my.kb.finding/id "f1"]))
                           "component finding cascade-deleted with its source")
                       (is (= 2 (:my.kb/count (kb/source-stats)))
                           "down to 2 sources"))))))
      done)))

(deftest remember-sources-write-is-idempotent-upsert
  ;; remember-sources! is driven end-to-end by build-kb-example! above; here we
  ;; call it DIRECTLY and read its envelope. A second call re-transacts the SAME
  ;; identity ids — an upsert, not a duplicate — so the store still holds
  ;; exactly three sources, McCarthy still authoring two of them.
  (async done
    (run-test
      (fn [conn]
        (-> (kb/build-kb-example!)
            (.then (pinned conn (fn [_] (kb/remember-sources!))))
            (.then (pinned conn
                     (fn [{ok? :seon.db/ok?}]
                       (is (true? ok?) "the seed write returns an ok envelope")
                       (is (= 3 (count (kb/titles)))
                           "re-seeding the same ids upserts — still three sources")
                       (is (= 2 (count (kb/titles-by-author "John McCarthy")))
                           "McCarthy still authors exactly two"))))))
      done)))

;;; ───────────────────────────────────────────────────────────────────────
;;; Inventory — the discovery call lists the kinds we just stored.
;;; ───────────────────────────────────────────────────────────────────────

(deftest inventory-lists-the-source-kinds
  (async done
    (run-test
      (fn [conn]
        (-> (kb/build-kb-example!)
            (.then (pinned conn
                     (fn [_]
                       (let [kinds (set (map :seon.db/kind (:seon.db/kinds (kb/inventory))))]
                         (is (contains? kinds :my.kb.source)
                             "the source kind shows once its data lands")
                         (is (contains? kinds :my.kb.author)
                             "the author kind too")
                         (is (contains? kinds :my.kb.finding)
                             "the component finding kind too")))))))
      done)))
