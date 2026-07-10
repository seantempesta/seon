(ns seon.db.schema-divergence-test
  "Re-register divergence gate (real-REPL semantics, item C8,
   2026-07-10). `schema/register!` is a malli-side upsert — re-register
   IS update. The datahike side is constrained: an attr already
   installed in the store cannot change `:db/valueType` /
   `:db/cardinality` / `:db/unique` in place
   (datahike.schema/find-invalid-schema-updates rejects those, even
   with zero datoms). Before this gate, a diverging re-register was
   silently IGNORED by `ensure-datahike-attrs!` (the attr was skipped
   as installed) — malli validated the NEW shape while the store held
   the OLD one, until a value crashed the writer.

   Pinned here:
   - a COMPATIBLE re-register (same derived datahike shape — e.g.
     tightened malli constraints) transacts fine;
   - an INCOMPATIBLE one fails the NEXT transact touching the attr
     with a legible `:seon.db/schema-divergence` envelope naming the
     installed vs registered shape, the datom count, and the migration
     move. Never silent divergence, never a crash.

   Fresh :memory conn per test; test keys are unregistered after."
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.db :as db]
    [seon.schema :as schema]))

(defn- unregister!
  "Drop test keys from the in-memory registry — keeps the process-shared
   registry clean across suite runs."
  [& ks]
  (swap! @#'schema/*schemas #(apply dissoc % ks)))

(defn- with-conn
  "Fresh :memory conn as ROOT `db/*conn*` for `body` (0-arg → Promise)."
  [body]
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (let [prev db/*conn*]
                   (set! db/*conn* conn)
                   (-> (js/Promise.resolve (body))
                       (.finally (fn [] (set! db/*conn* prev))))))))))

(deftest compatible-re-register-works
  (async done
    ;; `done` rides the OUTER chain, AFTER with-conn's conn-restore —
    ;; done-inside-the-body races the restore and leaks a stale root
    ;; conn into later test nses (caught 2026-07-10: it made
    ;; error-record-test's buffered-record! persist instead of buffer).
    (-> (with-conn
          (fn []
            (schema/register! :probe.div/name :string)
            (-> (db/transact! {:seon.db/tx-data [{:probe.div/name "one"}]})
                (.then (fn [r]
                         (is (true? (:seon.db/ok? r)))
                         ;; SAME derived datahike shape — a tightened malli
                         ;; constraint is a compatible re-register.
                         (schema/register! :probe.div/name [:string {:min 1}])
                         (db/transact! {:seon.db/tx-data
                                        [{:probe.div/name "two"}]})))
                (.then (fn [r]
                         (is (true? (:seon.db/ok? r))
                             "compatible re-register transacts fine")))
                (.finally (fn [] (unregister! :probe.div/name))))))
        (.finally done))))

(deftest incompatible-re-register-is-a-loud-value
  (async done
    (-> (with-conn
          (fn []
            (schema/register! :probe.div/count :string)
            (-> (db/transact! {:seon.db/tx-data [{:probe.div/count "3"}]})
                (.then (fn [r]
                         (is (true? (:seon.db/ok? r)))
                         ;; re-register with a DIFFERENT valueType — malli-side
                         ;; this succeeds (redefinition IS update)…
                         (schema/register! :probe.div/count :int)
                         ;; …but the store cannot follow: the next transact
                         ;; touching the attr surfaces the divergence.
                         (db/transact! {:seon.db/tx-data
                                        [{:probe.div/count 4}]})))
                (.then (fn [r]
                         (is (false? (:seon.db/ok? r))
                             "diverging re-register fails LOUD, as a value")
                         (let [msg  (str (get-in r [:seon.db/error
                                                    :seon.error/message]))
                               code (get-in r [:seon.db/error :seon.error/data
                                               :seon.db/error])]
                           (is (= :seon.db/schema-divergence code))
                           (testing "the envelope names the constraint + shapes"
                             (is (str/includes? msg ":probe.div/count"))
                             (is (str/includes? msg ":db.type/string"))
                             (is (str/includes? msg ":db.type/long")))
                           (testing "…and the migration move"
                             (is (str/includes? msg "NEW attribute name"))))))
                (.finally (fn [] (unregister! :probe.div/count))))))
        (.finally done))))

(deftest cardinality-divergence-is-caught-too
  (async done
    (-> (with-conn
          (fn []
            (schema/register! :probe.div/tag :keyword)
            (-> (db/transact! {:seon.db/tx-data [{:probe.div/tag :a}]})
                (.then (fn [_]
                         (schema/register! :probe.div/tag [:vector :keyword])
                         (db/transact! {:seon.db/tx-data
                                        [{:probe.div/tag [:a :b]}]})))
                (.then (fn [r]
                         (is (false? (:seon.db/ok? r)))
                         (is (= :seon.db/schema-divergence
                                (get-in r [:seon.db/error :seon.error/data
                                           :seon.db/error])))))
                (.finally (fn [] (unregister! :probe.div/tag))))))
        (.finally done))))
