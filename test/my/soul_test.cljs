(ns my.soul-test
  "my.soul contract — the store-resident system prompt: seeded at boot
   from SOUL.md + the REPL mechanics, SEED-ONLY-IF-ABSENT (a runtime
   edit survives reboot — the user-facing promise), priority-joined by
   system-prompt-text, and read by the LLM call path
   (seon.ai.deepseek/effective-system-prompt → request-body's system
   message). All on a FRESH :memory conn seeded like the pod boots —
   never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.ai.deepseek :as deepseek]
    [seon.client :as client]
    [seon.db :as db]
    [my.soul :as soul]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema (which
   includes the :my.soul/* attrs via client/agent-bootstrap-attrs) —
   NO soul rows yet."
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
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh conn, `set!` as the ROOT db/*conn* for `body` (conn →
   Promise), prior root restored after (root set!, not `binding` —
   CLJS dynamic bindings pop at the first microtask boundary)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- soul-seed!
  "Seed the soul rows on the ambient conn exactly like the pod boots:
   tx-data from the conn's current db (seed-only-if-absent). Promise
   of the transact envelope (nil when nothing to seed)."
  [conn]
  (let [tx (soul/seed-tx-data @conn)]
    (when (seq tx)
      (db/transact! {:seon.db/tx-data tx}))))

(deftest soul-seeds-soul-md-and-mechanics-once
  (async done
    (-> (with-conn
          (fn [conn]
            (let [rows (soul/seed-tx-data @conn)]
              (is (= ["identity" "repl-mechanics"]
                     (map :my.soul/id rows))
                  "fresh store → both shipped rows, priority order")
              (doseq [row rows]
                (is (m/validate :my.soul/section row)
                    (str (:my.soul/id row) " validates")))
              (is (re-find #"That is what it means to be Seon"
                           (:my.soul/text (first rows)))
                  "identity row is SOUL.md (read from the repo)")
              (is (= "SOUL.md" (:my.kb/source-path (first rows))))
              (is (re-find #"YOUR OUTPUT IS A REPL"
                           (:my.soul/text (second rows)))
                  "mechanics row carries the REPL contract")
              (-> (soul-seed! conn)
                  (.then (fn [{ok? :seon.db/ok?}]
                           (is (true? ok?) "seed transact lands")
                           (is (= [] (soul/seed-tx-data @conn))
                               "second boot on a seeded store re-seeds NOTHING")
                           (let [text (soul/system-prompt-text @conn)]
                             (is (str/starts-with? text "# SOUL.md")
                                 "identity (priority 10) leads the joined prompt")
                             (is (re-find #"YOUR OUTPUT IS A REPL" text)
                                 "mechanics (priority 20) follows"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest soul-edit-survives-reseed-and-feeds-the-llm-call
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (soul-seed! conn)
                (.then (fn [_]
                         ;; The runtime edit — one identity-upsert transact.
                         (db/transact!
                           {:seon.db/tx-data
                            [{:my.soul/id   "identity"
                              :my.soul/text "EDITED SOUL: serve the porpoise."}]})))
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "the edit is one transact")
                         ;; "Reboot": run the boot seed again against the
                         ;; edited store — it must emit NOTHING (no clobber).
                         (is (= [] (soul/seed-tx-data @conn))
                             "re-seeding an edited store emits no rows — the edit survives reboot")
                         (let [text (soul/system-prompt-text @conn)]
                           (is (str/starts-with? text "EDITED SOUL: serve the porpoise.")
                               "next prompt reads the edited text")
                           (is (not (re-find #"That is what it means to be Seon" text))
                               "the shipped identity text is gone (last-write-wins)"))
                         ;; The LLM call path reads the store: request-body's
                         ;; system message IS the store text.
                         (let [body (deepseek/request-body {:seon.ai/ctx "hi"})
                               sys  (-> body :messages first :content)]
                           (is (str/starts-with? sys "EDITED SOUL: serve the porpoise.")
                               "the system message sent to the API is the store-resident soul")
                           (is (= sys (soul/system-prompt-text @conn)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest soul-fallback-when-store-has-no-rows
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; conn has the boot schema but NO soul rows.
            (is (= "" (soul/system-prompt-text @db/*conn*))
                "no rows → empty string")
            (let [body (deepseek/request-body {:seon.ai/ctx "hi"})
                  sys  (-> body :messages first :content)]
              (is (= deepseek/fallback-system-prompt sys)
                  "empty store → the minimal boot-edge fallback")
              (is (= "OVERRIDE"
                     (-> (deepseek/request-body {:seon.ai/ctx "hi"
                                                 :seon.ai/system-prompt "OVERRIDE"})
                         :messages first :content))
                  "an explicit :seon.ai/system-prompt still wins"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
