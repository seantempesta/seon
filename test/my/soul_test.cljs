(ns my.soul-test
  "my.soul contract — the agent's IDENTITY read LIVE from disk: SOUL.md
   (+ AGENTS.md when present) read FRESH on every call by
   system-prompt-text, joined into the LLM `system` message by
   seon.ai/effective-system-prompt. NO store, NO seed — a user's edit to
   the file lands next turn for all agents. The universal REPL mechanics
   are NOT here; they are hardcoded in seon.ctx/system-text.

   The conn-bound test runs on a FRESH :memory conn seeded like the pod
   boots (for the config request-params reads) — never the live agent
   conn. The file reads hit the real repo SOUL.md (cwd = repo root)."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.ai.openai-compat :as openai]
    [seon.client :as client]
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

(deftest system-prompt-text-reads-identity-files-live
  ;; The identity is the LIVE text of the on-disk identity files, read
  ;; fresh every call — no conn, no store, no seed.
  (is (= ["SOUL.md"] (soul/soul-files))
      "only SOUL.md exists in-repo (no AGENTS.md) → one identity file")
  (let [text (soul/system-prompt-text)]
    (is (str/starts-with? text "# SOUL.md")
        "the live system prompt IS the SOUL.md text, read from disk")
    (is (re-find #"That is what it means to be Seon" text)
        "identity content is present (read live, not seeded)")
    (is (not (re-find #"YOUR OUTPUT IS A REPL" text))
        "mechanics are NOT in the soul — they are hardcoded in
         seon.ctx/system-text")))

(deftest live-identity-feeds-the-llm-call
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; The LLM call path reads the live identity: request-params'
            ;; system message IS the live SOUL.md text.
            (let [body (openai/request-params {:seon.ai/ctx "hi"})
                  sys  (-> body :messages first :content)]
              (is (= sys (soul/system-prompt-text))
                  "the system message sent to the API is the live identity")
              (is (str/starts-with? sys "# SOUL.md")
                  "and it is the SOUL.md text")
              (is (= "OVERRIDE"
                     (-> (openai/request-params {:seon.ai/ctx "hi"
                                                 :seon.ai/system-prompt "OVERRIDE"})
                         :messages first :content))
                  "an explicit :seon.ai/system-prompt still wins"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest fallback-when-no-identity-file
  ;; When NO identity file is readable, system-prompt-text is "" and the
  ;; call path uses the minimal boot-edge fallback. Point SEON_SOUL_FILE
  ;; at a nonexistent file (and the repo has no AGENTS.md) so soul-files
  ;; resolves to nothing — restored after.
  (let [orig (.. js/process -env -SEON_SOUL_FILE)]
    (set! (.. js/process -env -SEON_SOUL_FILE) "does-not-exist-xyz.md")
    (try
      (is (= [] (soul/soul-files))
          "no readable identity file → no soul files")
      (is (= "" (soul/system-prompt-text))
          "no identity file → empty identity text")
      (is (= ai/fallback-system-prompt
             (ai/effective-system-prompt {:seon.ai/system-prompt nil}))
          "no override + no identity file → the minimal boot-edge fallback")
      (finally
        (if orig
          (set! (.. js/process -env -SEON_SOUL_FILE) orig)
          (js-delete (.. js/process -env) "SEON_SOUL_FILE"))))))
