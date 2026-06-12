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
;; Pure — sync-tx-data (env owns the row; brand_test's four cases).
;; ============================================================

(deftest sync-tx-data-covers-the-four-env-row-cases
  (is (= [] (ai/sync-tx-data {::ai/env {}}))
      "no env + no row → nothing to do (adapter defaults at call time)")
  (is (= [{::ai/id "config" ::ai/thinking "true"}]
         (ai/sync-tx-data {::ai/env {::ai/thinking "true"}}))
      "env set + no row → one identity-upsert assert")
  (is (= [] (ai/sync-tx-data {::ai/row {::ai/thinking "true"}
                              ::ai/env {::ai/thinking "true"}}))
      "env equals row → idempotent, transacts nothing")
  (is (= [{::ai/id "config" ::ai/thinking "high"}]
         (ai/sync-tx-data {::ai/row {::ai/thinking "true"}
                           ::ai/env {::ai/thinking "high"}}))
      "env changed → re-assert (last-write-wins upsert)")
  (is (= [[:db/retract [::ai/id "config"] ::ai/thinking "true"]]
         (ai/sync-tx-data {::ai/row {::ai/thinking "true"}
                           ::ai/env {}}))
      "env unset but row present → retract — defaults return next call")
  (is (= [[:db/retract [::ai/id "config"] ::ai/model "deepseek-chat"]
          {::ai/id "config" ::ai/provider :anthropic ::ai/max-tokens 2048}]
         (ai/sync-tx-data
           {::ai/row {::ai/provider :deepseek ::ai/model "deepseek-chat"}
            ::ai/env {::ai/provider :anthropic ::ai/max-tokens 2048}}))
      "mixed: retracts first, then one assert map for the set attrs"))

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
;; env-row — reads SEON_AI_*, parses to concrete types (set/cleaned
;; around the assertion).
;; ============================================================

(deftest env-row-reads-and-parses-set-vars
  (let [env (.. js/process -env)]
    (try
      (aset env "SEON_AI_PROVIDER" "anthropic")
      (aset env "SEON_AI_MODEL" "claude-opus-4-8")
      (aset env "SEON_AI_TEMPERATURE" "0.3")
      (aset env "SEON_AI_MAX_TOKENS" "2048")
      (aset env "SEON_AI_THINKING" "true")
      (aset env "SEON_AI_TIMEOUT_MS" "")        ; blank = unset
      (is (= {::ai/provider    :anthropic
              ::ai/model       "claude-opus-4-8"
              ::ai/temperature 0.3
              ::ai/max-tokens  2048
              ::ai/thinking    "true"}
             (ai/env-row))
          "set vars parse to the attrs' concrete types; blank/unset absent")
      (finally
        (doseq [v ["SEON_AI_PROVIDER" "SEON_AI_MODEL" "SEON_AI_TEMPERATURE"
                   "SEON_AI_MAX_TOKENS" "SEON_AI_THINKING" "SEON_AI_TIMEOUT_MS"]]
          (js-delete env v))))))

(deftest env-row-skips-unparseable-values-loudly
  (let [env (.. js/process -env)]
    (try
      (aset env "SEON_AI_PROVIDER" "openai")          ; not a known provider
      (aset env "SEON_AI_TEMPERATURE" "warm")          ; not a number
      (aset env "SEON_AI_MAX_TOKENS" "lots")           ; not a number
      (is (= {} (ai/env-row))
          "unparseable values are ignored (logged) — adapter defaults apply")
      (finally
        (doseq [v ["SEON_AI_PROVIDER" "SEON_AI_TEMPERATURE" "SEON_AI_MAX_TOKENS"]]
          (js-delete env v))))))

(deftest provider-defaults-to-deepseek-and-reads-env-first
  (let [env (.. js/process -env)]
    (try
      (js-delete env "SEON_AI_PROVIDER")
      (is (= :deepseek (ai/provider)) "no env, no row → deepseek")
      (aset env "SEON_AI_PROVIDER" "anthropic")
      (is (= :anthropic (ai/provider))
          "env wins — readable pre-boot, consistent with env owning the row")
      (finally
        (js-delete env "SEON_AI_PROVIDER")))))

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

(deftest current-empty-then-rowed-then-retracted
  (async done
    (-> (with-conn
          (fn [conn]
            ;; 1. Empty store → {} — adapters fall back to their defaults.
            (is (= {} (ai/current @conn))
                "absent env + absent row → no overrides")
            ;; 2. "Boot with env": transact the sync tx-data.
            (-> (db/transact!
                  {:seon.db/tx-data
                   (ai/sync-tx-data
                     {::ai/env {::ai/provider :anthropic
                                ::ai/thinking "true"
                                ::ai/max-tokens 2048}})})
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "config sync transact lands")
                         (let [c (ai/current @conn)]
                           (is (= :anthropic (::ai/provider c)))
                           (is (= 2048 (::ai/max-tokens c)))
                           (is (true? (ai/thinking-mode c))))
                         ;; 3. "Reboot WITHOUT env": sync against the now-
                         ;;    populated row retracts — defaults return.
                         (db/transact!
                           {:seon.db/tx-data
                            (ai/sync-tx-data
                              {::ai/row {::ai/provider :anthropic
                                         ::ai/thinking "true"
                                         ::ai/max-tokens 2048}
                               ::ai/env {}})})))
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "unset-env sync transact lands")
                         (is (= {} (ai/current @conn))
                             "env removed → no overrides — adapter defaults return"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
