(ns my.kb-test
  "my.kb scaffold contract: the four shared provenance shapes are
   registered ONCE; my.kb.shared (the cluster-wide instruction
   singleton, context-v4 V4-0) seeds EMPTY and idempotent; rows are
   APPENDED by transact (nested component maps under the many-ref) and
   read back in append order via the `instructions` fn; re-seeding
   never clobbers appended rows. All on a FRESH :memory conn seeded
   like the pod boots — never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    [my.kb]
    [my.kb.shared :as kb-shared]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the
   shipped my.kb.shared seed (the same row seon.client seeds at boot)."
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
   dynamic bindings pop at the first microtask boundary)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

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
