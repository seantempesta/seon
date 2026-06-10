(ns my.kb-test
  "my.kb scaffold contract: the four shared provenance shapes are
   registered ONCE; my.kb.instruction (the first worked domain) seeds
   valid, provenance-carrying rows; the instructions view is derived
   (priority-ordered, runtime-editable by transact, vanishes when no
   rows). All on a FRESH :memory conn seeded like the pod boots —
   never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]
    [my.kb]
    [my.kb.instruction :as instruction]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the
   shipped instruction seed (the same rows seon.client seeds at boot)."
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
                                {:tx-data (instruction/seed-tx-data)})))
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
  (is (= :inst (schema/schema-definition :my.kb/verified-at)))
  (is (= [:enum :verified :inferred]
         (schema/schema-definition :my.kb/confidence))
      "confidence is the shared enum — domains reference it, never inline it"))

(deftest seed-rows-are-valid-instructions-with-provenance
  (let [rows (instruction/seed-tx-data)]
    (is (= ["consult-before-research" "store-proactively"
            "reply-every-asked-turn" "namespace-map"]
           (map :my.kb.instruction/id rows))
        "the four shipped instructions, priority order")
    (doseq [row rows]
      (is (m/validate :my.kb.instruction/instruction row)
          (str (:my.kb.instruction/id row) " validates against the entity schema"))
      (is (= "src/my/kb/instruction.cljs" (:my.kb/source-path row))
          "every seed row carries the shared provenance source-path")
      (is (= :verified (:my.kb/confidence row))))))

(deftest instructions-block-renders-seeded-rows-priority-ordered
  (async done
    (-> (with-conn
          (fn [conn]
            (let [block (instruction/instructions-block @conn)]
              (is (re-find #"(?s)\[consult-before-research\].*\[store-proactively\].*\[reply-every-asked-turn\].*\[namespace-map\]"
                           block)
                  "rows render smallest priority first")
              (is (re-find #"identity upsert" block)
                  "the block teaches the runtime-edit verb")
              (is (= block (instruction/instructions-section
                             {:seon.db/db @conn :seon.agent/id "any"}))
                  "the section fn is the block over the render's db"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest instructions-are-runtime-editable-by-transact
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.kb.instruction/id   "namespace-map"
                     :my.kb.instruction/text "AMENDED: namespaces moved."}]})
                (.then
                  (fn [{ok? :seon.db/ok?}]
                    (is (true? ok?) "the edit is one identity-upsert transact")
                    (let [block (instruction/instructions-block @conn)]
                      (is (re-find #"\[namespace-map\] AMENDED: namespaces moved\."
                                   block)
                          "next render shows the amended text — no restart, no re-seed")
                      (is (not (re-find #"Your code is my\.\*" block))
                          "the old text is gone (last-write-wins, not accumulated)")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest instructions-block-vanishes-without-rows
  (async done
    (let [cfg {:store              {:backend :memory :id (random-uuid)}
               :schema-flexibility :write
               :keep-history?      true}]
      (-> (d/create-database cfg)
          (.then (fn [_] (d/connect cfg {:sync? false})))
          (.then (fn [conn]
                   (is (= "" (instruction/instructions-block @conn))
                       "no rows → empty string, the section vanishes")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
