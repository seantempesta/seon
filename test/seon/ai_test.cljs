(ns seon.ai-test
  "seon.ai contract (C-18 LLM settings as data) — call settings are a
   `:seon.ai/config` singleton row: absent env + absent row → each
   adapter's shipped defaults (byte-identical wire bodies, proven in
   the adapter tests); SEON_AI_* env vars own the row across boots
   (set → asserted, unset → retracted); env values parse to the attrs'
   concrete types (unparseable → loudly ignored); thinking-mode parses
   the stored string to the false/true/effort value adapters consume.

   Placeholder values use \"Acme\"-style strings — never a real
   product name."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.db :as db]))

;; ============================================================
;; Pure — sync-tx-data (env/config SEEDS ONCE → the DB OWNS the row).
;; ============================================================

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

;; ============================================================
;; Pure — thinking-mode parses the stored string.
;; ============================================================

(deftest thinking-mode-parses-the-stored-string
  (is (false? (ai/thinking-mode {})) "absent → off (the default)")
  (is (false? (ai/thinking-mode {::ai/thinking "false"})))
  (is (true? (ai/thinking-mode {::ai/thinking "true"})))
  (is (= "high" (ai/thinking-mode {::ai/thinking "high"}))
      "anything else is a reasoning-effort string, passed through"))

;; ============================================================
;; env-row — reads SEON_AI_*, parses to concrete types. SNAPSHOT/
;; RESTORE, never delete-what-we-didn't-set: these tests mutate the
;; OPERATOR's process.env, and the old js-delete teardown wiped a live
;; SEON_AI_PROVIDER=anthropic mid-suite — a paid "Opus" gym run
;; silently drove DeepSeek (opus-live-tests 2026-06-12, limitation 2).
;; ============================================================

(def ^:private seon-ai-env-vars
  ["SEON_AI_PROVIDER" "SEON_AI_MODEL" "SEON_AI_TEMPERATURE"
   "SEON_AI_MAX_TOKENS" "SEON_AI_THINKING" "SEON_AI_TIMEOUT_MS"
   "SEON_AI_BASE_URL" "SEON_AI_API_KEY_ENV"])

(defn- with-env-restored
  "Snapshot every SEON_AI_* var on js process.env, run `body` (which
   may aset/js-delete them freely), then restore each var to EXACTLY
   its prior state — prior value re-asserted, originally-absent vars
   deleted. The operator's provider steering survives the suite."
  [body]
  (let [env   (.. js/process -env)
        saved (into {} (map (fn [k] [k (aget env k)])) seon-ai-env-vars)]
    (try
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

(deftest provider-parses-openai-compat
  (with-env-restored
    (fn [env]
      (aset env "SEON_AI_PROVIDER" "openai-compat")
      (is (= :openai-compat (ai/provider))
          "openai-compat is a known provider (task #30)"))))

(deftest env-row-skips-unparseable-values-loudly
  (with-env-restored
    (fn [env]
      (doseq [v seon-ai-env-vars] (js-delete env v))
      (aset env "SEON_AI_PROVIDER" "openai")          ; not a known provider
      (aset env "SEON_AI_TEMPERATURE" "warm")          ; not a number
      (aset env "SEON_AI_MAX_TOKENS" "lots")           ; not a number
      (is (= {} (ai/env-row))
          "unparseable values are ignored (logged) — adapter defaults apply"))))

(deftest provider-defaults-to-deepseek-and-reads-env-first
  (with-env-restored
    (fn [env]
      (js-delete env "SEON_AI_PROVIDER")
      (is (= :deepseek (ai/provider)) "no env, no row → deepseek")
      (aset env "SEON_AI_PROVIDER" "anthropic")
      (is (= :anthropic (ai/provider))
          "env wins — readable pre-boot, consistent with env owning the row"))))

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

;; ============================================================
;; Store roundtrip — current reads the row at call time; sync tx-data
;; transacted on a FRESH :memory conn (never the live agent conn).
;; ============================================================

(defn- fresh-conn
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
                                         [::ai/id ::ai/provider ::ai/model
                                          ::ai/temperature ::ai/max-tokens
                                          ::ai/thinking ::ai/timeout-ms])
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))

(defn- with-conn
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest current-empty-then-seeded-then-persists
  (async done
    (-> (with-conn
          (fn [conn]
            ;; 1. Empty store → {} — adapters fall back to their defaults.
            (is (= {} (ai/current @conn))
                "absent env + absent row → no overrides")
            ;; 2. "Boot with env on a fresh store": seed the row.
            (-> (db/transact!
                  {:seon.db/tx-data
                   (ai/sync-tx-data
                     {::ai/env {::ai/provider :anthropic
                                ::ai/thinking "true"
                                ::ai/max-tokens 2048}})})
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "config seed transact lands")
                         (let [c (ai/current @conn)]
                           (is (= :anthropic (::ai/provider c)))
                           (is (= 2048 (::ai/max-tokens c)))
                           (is (true? (ai/thinking-mode c))))
                         ;; 3. "Reboot WITHOUT env": the row is configured, so
                         ;;    seed is a NO-OP (nothing retracted) → the config
                         ;;    PERSISTS. The DB owns the row.
                         (is (= [] (ai/sync-tx-data
                                     {::ai/row {::ai/provider :anthropic
                                                ::ai/thinking "true"
                                                ::ai/max-tokens 2048}
                                      ::ai/env {}}))
                             "configured row + no env → no-op seed (no retract)")
                         (let [c (ai/current @conn)]
                           (is (= :anthropic (::ai/provider c))
                               "reboot WITHOUT env → row PERSISTS (DB owns)")
                           (is (= 2048 (::ai/max-tokens c)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
