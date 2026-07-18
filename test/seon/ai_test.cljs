(ns seon.ai-test
  "seon.ai contract (C-18 LLM settings as data) — call settings are a
   `:seon.ai/config` singleton row: absent env + absent row → each
   adapter's shipped defaults (byte-identical wire bodies, proven in
   the adapter tests); SEON_AI_* env vars seed an unconfigured row once,
   after which the database owns it. Environment values parse to the attrs'
   concrete types (unparseable → loudly ignored); thinking-mode parses the
   stored string to the false/true/effort value adapters consume.

   Placeholder values use \"Acme\"-style strings — never a real
   product name."
  (:require
    [cljs.test :refer [deftest is async]]
    [seon.agent.ctx :as ctx]
    [seon.ai :as ai]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]))

;; ============================================================
;; Pure — sync-tx-data (env/config SEEDS ONCE → the DB OWNS the row).
;; ============================================================

(deftest agent-config-pull-pattern-uses-declared-agent-attributes
  (let [entity-attributes
        (into #{}
              (map first)
              (drop 2 (schema/schema-definition ::ai/agent-config)))]
    (is (= (set (ai/agent-config-pull-pattern)) entity-attributes)
        "every pulled agent configuration attribute is installed by the entity schema")))

(deftest sync-tx-data-seeds-once-then-db-owns
  (is (= [] (ai/sync-tx-data {::ai/env {}}))
      "no env + no row → nothing to seed (adapter defaults at call time)")
  (is (= [{::ai/id "config" ::ai/thinking "true"}]
         (ai/sync-tx-data {::ai/env {::ai/thinking "true"}}))
      "env set + unconfigured row → ONE seed upsert")
  (is (= [] (ai/sync-tx-data {::ai/row {::ai/thinking "true"}
                              ::ai/env {::ai/thinking "true"}}))
      "row already configured → DB owns, no-op (even when env matches)")
  (is (= [] (ai/sync-tx-data {::ai/row {::ai/thinking "true"}
                              ::ai/env {::ai/thinking "high"}}))
      "row configured + env DIFFERS → DB owns, env IGNORED (not re-asserted)")
  (is (= [] (ai/sync-tx-data {::ai/row {::ai/thinking "true"}
                              ::ai/env {}}))
      "row configured + env unset → DB owns, NOT retracted (runtime switch persists)")
  (is (= [] (ai/sync-tx-data
              {::ai/row {::ai/provider :deepseek ::ai/model "deepseek-chat"}
               ::ai/env {::ai/provider :anthropic ::ai/max-tokens 2048}}))
      "configured row → DB owns; env never overrides after the initial seed"))

(deftest sync-acquires-once-and-fences-the-seed
  (async done
    (let [database {:db-name "default"
                    :t 536870912
                    :as-of nil
                    :since nil
                    :history false
                    :datahike/commit-id
                    #uuid "00000000-0000-0000-0000-000000000072"}
          original-execute-many db/execute-many
          original-transact db/transact!
          original-env-row ai/env-row
          calls (atom [])]
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
                {::db/db database
                 ::db/results
                 [{::protocol/success? true
                   ::protocol/result nil}]})))
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:transact request])
              (js/Promise.resolve {::db/ok? true})))
      (set! ai/env-row (constantly {::ai/thinking "true"}))
      (-> (ai/sync!)
          (.then
            (fn [result]
              (let [[[_ acquire] [_ transact]] @calls
                    member (first (::db/members acquire))]
                (is (= {::ai/synced? true} result))
                (is (= 1 (count (::db/members acquire)))
                    "startup performs one authority acquisition")
                (is (= protocol/pull-operation
                       (::protocol/operation member)))
                (is (= [::ai/id "config"]
                       (::protocol/entity-id member)))
                (is (= 100000 (:datahike.resource/max-work member)))
                (is (= 256 (:datahike.resource/max-results member))
                    "pull result budgets count retained nodes, not entities")
                (is (= (* 1024 1024)
                       (:datahike.resource/max-result-weight member)))
                (is (identical? database (::db/expected-db transact))
                    "the immutable database value fences the seed")
                (is (= [{::ai/id "config" ::ai/thinking "true"}]
                       (::db/tx-data transact))))))
          (.catch (fn [error]
                    (is false (str "AI sync proof threw: " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (set! db/transact! original-transact)
              (set! ai/env-row original-env-row)
              (done)))))))

;; ============================================================
;; Pure — thinking-mode parses the stored string.
;; ============================================================

(deftest process-local-abort-signal-reports-cancellation
  (let [controller (js/AbortController.)
        signal     (.-signal controller)]
    (is (false? (ai/aborted? signal)))
    (.abort controller)
    (is (true? (ai/aborted? signal)))
    (is (false? (ai/aborted? nil)))))

(deftest thinking-mode-parses-the-stored-string
  (is (false? (ai/thinking-mode {})) "absent → off (the default)")
  (is (false? (ai/thinking-mode {::ai/thinking "false"})))
  (is (true? (ai/thinking-mode {::ai/thinking "true"})))
  (is (= "high" (ai/thinking-mode {::ai/thinking "high"}))
      "anything else is a reasoning-effort string, passed through"))

(deftest effective-system-prompt-is-pure-request-data
  (is (= "frozen cluster prompt"
         (ai/effective-system-prompt
          {::ai/system-prompt "frozen cluster prompt"}))
      "the prompt compiler's frozen cluster value is preserved")
  (is (= ctx/system-text
         (ai/effective-system-prompt {}))
      "a direct adapter call without a compiled prompt uses the shipped default"))

;; ============================================================
;; env-row — reads SEON_AI_*, parses to concrete types. SNAPSHOT/
;; RESTORE, never delete-what-we-didn't-set: these tests mutate the
;; OPERATOR's process.env, and the old js-delete teardown wiped a live
;; SEON_AI_PROVIDER=anthropic mid-suite — a paid evaluation silently drove
;; DeepSeek (opus-live-tests 2026-06-12, limitation 2).
;; ============================================================

(def ^:private seon-ai-env-vars
  ["SEON_AI_PROVIDER" "SEON_AI_MODEL" "SEON_AI_TEMPERATURE"
   "SEON_AI_MAX_TOKENS" "SEON_AI_THINKING" "SEON_AI_TIMEOUT_MS"
   "SEON_AI_BASE_URL" "SEON_AI_API_KEY_ENV" "SEON_DG_BACKEND"
   "SEON_AI_EXTRA_BODY"])

(defn- with-env-restored
  "Snapshot every recognized AI var on js process.env, clear the test's input
   surface, run `body` (which may aset/js-delete values freely), then restore
   each var to EXACTLY its prior state — prior value re-asserted,
   originally-absent vars deleted. The operator's provider steering survives
   the suite without influencing an example."
  [body]
  (let [env   (.. js/process -env)
        saved (into {} (map (fn [k] [k (aget env k)])) seon-ai-env-vars)]
    (try
      (doseq [k seon-ai-env-vars]
        (js-delete env k))
      (body env)
      (finally
        (doseq [k seon-ai-env-vars]
          (let [v (get saved k)]
            (if (some? v)            ; absent js prop reads as undefined
              (aset env k v)
              (js-delete env k))))))))

(deftest env-row-reads-and-parses-set-vars
  (with-env-restored
    (fn [env]
      (aset env "SEON_AI_PROVIDER" "anthropic")
      (aset env "SEON_AI_MODEL" "claude-opus-4-8")
      (aset env "SEON_AI_TEMPERATURE" "0.3")
      (aset env "SEON_AI_MAX_TOKENS" "2048")
      (aset env "SEON_AI_THINKING" "true")
      (aset env "SEON_AI_TIMEOUT_MS" "")        ; blank = unset
      (aset env "SEON_AI_BASE_URL" "https://gw.example.com/v1/chat/completions")
      (aset env "SEON_AI_API_KEY_ENV" "ACME_GW_KEY")
      (is (= {::ai/provider    :anthropic
              ::ai/model       "claude-opus-4-8"
              ::ai/temperature 0.3
              ::ai/max-tokens  2048
              ::ai/thinking    "true"
              ::ai/base-url    "https://gw.example.com/v1/chat/completions"
              ::ai/api-key-env "ACME_GW_KEY"}
             (ai/env-row))
          "set vars parse to the attrs' concrete types; blank/unset absent"))))

(deftest env-row-skips-unparseable-values-loudly
  (with-env-restored
    (fn [env]
      (aset env "SEON_AI_PROVIDER" "openai")          ; not a known provider
      (aset env "SEON_AI_TEMPERATURE" "warm")          ; not a number
      (aset env "SEON_AI_MAX_TOKENS" "lots")           ; not a number
      (is (= {} (ai/env-row))
          "unparseable values are ignored (logged) — adapter defaults apply"))))

(deftest env-row-parses-openai-compatible-provider
  (with-env-restored
    (fn [env]
      (aset env "SEON_AI_PROVIDER" "openai-compat")
      (is (= {::ai/provider :openai-compat} (ai/env-row))))))

(deftest with-env-restored-restores-prior-state
  ;; The fixture's own contract: a var set BEFORE the body survives the
  ;; body deleting it; a var absent before stays absent after the body
  ;; sets it. This is the exact regression that flipped a paid opus run
  ;; to deepseek.
  (let [env (.. js/process -env)]
    (with-env-restored
      (fn [_]
        (aset env "SEON_AI_PROVIDER" "anthropic")   ; "operator steering"
        (js-delete env "SEON_AI_MODEL")             ; "operator unset"
        (with-env-restored
          (fn [_]
            (js-delete env "SEON_AI_PROVIDER")      ; rude inner test
            (aset env "SEON_AI_MODEL" "x")))
        (is (= "anthropic" (aget env "SEON_AI_PROVIDER"))
            "deleted-by-test operator value is re-asserted on teardown")
        (is (nil? (aget env "SEON_AI_MODEL"))
            "set-by-test var that was absent before is deleted on teardown")))))

(deftest ordinary-rows-resolve-explicit-config
  (let [default-resolution (ai/resolved-config-from-rows {} {})
        resolution
        (ai/resolved-config-from-rows
         {::ai/id "config"
          ::ai/provider :openai-compat
          ::ai/model "global-model"
          ::ai/timeout-ms 1111
          :seon.config.model-transport/response-identity-cap 31}
         {:seon.agent/id "agent-1"
          ::ai/agent-model (pr-str "agent-model")
          ::ai/agent-temperature (pr-str 0.0)
          ::ai/agent-max-retries (pr-str 2)
          ::ai/agent-thinking (pr-str :inherit)})
        config (::ai/resolved-config resolution)]
    (is (= :deepseek
           (get-in default-resolution [::ai/resolved-config ::ai/provider])))
    (is (= :openai-compat (ai/resolved-adapter config)))
    (is (= :openai-compat (::ai/provider config)))
    (is (= "agent-model" (::ai/model config)))
    (is (= 0.0 (::ai/temperature config)))
    (is (= 1111 (::ai/timeout-ms config)))
    (is (= 2 (::ai/agent-max-retries resolution)))
    (is (= 31
           (:seon.config.model-transport/response-identity-cap config)))
    (is (= :agent-override (get-in resolution [::ai/provenance ::ai/model])))
    (is (= :config-row
           (get-in resolution
                   [::ai/provenance
                    :seon.config.model-transport/response-identity-cap])))))
