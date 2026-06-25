(ns my.soul-test
  "my.soul + the system-message DECOUPLING contract.

   my.soul is the thin LIVE-read helper over the identity files (SOUL.md
   / AGENTS.md, read FRESH, no store/seed). Those files are CONTEXT
   sections (`seon.ctx.doc/doc-section`), NOT the LLM `system` message.

   The decoupling this test pins:
     - the LLM `system` role message is the HARDCODED, system-specific
       seon mechanics (`seon.ctx/system-text`) — NOT the soul, NOT a
       file, with NO fallback const;
     - my.soul reads the identity files live for the teachings validator.

   The conn-bound test runs on a FRESH :memory conn seeded like the pod
   boots (so the adapter's config read works) — never the live agent
   conn. File reads hit the real repo files (cwd = repo root)."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.ai.openai-compat :as openai]
    [seon.client :as client]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [my.soul :as soul]))

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema."
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

(deftest identity-text-reads-files-live
  ;; The identity is the LIVE text of the on-disk identity files — no
  ;; conn, no store, no seed. We pin the MECHANISM (files read, joined),
  ;; not any wording: the soul carries NO load-bearing content.
  (let [files (soul/soul-files)
        text  (soul/identity-text)]
    (is (vector? files) "soul-files is the set of present identity files")
    (is (every? string? files))
    (if (seq files)
      (is (not (str/blank? text)) "present file(s) → non-blank live text")
      (is (= "" text) "no identity file → empty text"))))

(deftest system-message-is-hardcoded-mechanics-not-the-soul
  ;; THE decoupling: the LLM system message is the hardcoded
  ;; system-specific mechanics, NOT the soul/any file.
  (is (= ctx/system-text (ai/effective-system-prompt {}))
      "system message = the hardcoded seon mechanics (seon.ctx/system-text)")
  (is (= ctx/system-text (ai/effective-system-prompt {:seon.ai/system-prompt nil}))
      "no override → still the hardcoded mechanics (no fallback const)")
  (is (= "OVERRIDE" (ai/effective-system-prompt {:seon.ai/system-prompt "OVERRIDE"}))
      "an explicit override still wins")
  ;; The system message is NOT the soul text (decoupled).
  (when (seq (soul/soul-files))
    (is (not= (soul/identity-text) (ai/effective-system-prompt {}))
        "the system message is NOT the soul file text"))
  ;; No dead fallback const survives.
  (is (not (contains? (ns-publics 'seon.ai) 'fallback-system-prompt))
      "fallback-system-prompt is DELETED — no fallback path"))

(deftest llm-call-system-message-is-the-hardcoded-mechanics
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; The adapter's system message IS the hardcoded mechanics —
            ;; NOT the live soul text, NOT a fallback.
            (let [body (openai/request-params {:seon.ai/ctx "hi"})
                  sys  (-> body :messages first :content)]
              (is (= sys ctx/system-text)
                  "the system message sent to the API is the hardcoded mechanics")
              (is (= "OVERRIDE"
                     (-> (openai/request-params {:seon.ai/ctx "hi"
                                                 :seon.ai/system-prompt "OVERRIDE"})
                         :messages first :content))
                  "an explicit :seon.ai/system-prompt still wins"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
