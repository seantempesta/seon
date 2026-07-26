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
    [seon.ai.provider :as provider]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.instrument :as instrument]
    [seon.schema :as schema]))

;; ============================================================
;; Pure — sync-tx-data (env/config SEEDS ONCE → the DB OWNS the row).
;; ============================================================

(deftest provider-locality-has-one-leaf-definition
  (is (identical? ai/provider-locality provider/provider-locality))
  (is (true? (ai/frontier-provider? :openai-compat)))
  (is (false? (ai/frontier-provider? :diffusiongemma)))
  (is (= (set (keys provider/provider-locality))
         (set (drop 1 (schema/schema-definition :seon.ai/provider))))))

(deftest agent-config-pull-pattern-uses-declared-agent-attributes
  (let [entity-attributes
        (into #{}
              (map first)
              (drop 2 (schema/schema-definition ::ai/agent-config)))]
    (is (= (set (ai/agent-config-pull-pattern)) entity-attributes)
        "every pulled agent configuration attribute is installed by the entity schema")))

(deftest config-pull-pattern-contract-accepts-component-pulls
  (let [sym 'seon.ai/config-pull-pattern
        target {::instrument/sym sym
                ::instrument/schema-form
                (-> #'ai/config-pull-pattern meta :malli/schema)}]
    (try
      (let [result (instrument/instrument-targets! [target])
            pull-pattern (ai/config-pull-pattern)]
        (is (true? (::instrument/ok? result)))
        (is (= #{{:seon.config/provider-descriptors '[*]}
                 {:seon.config/model-variants '[*]}}
               (set (filter map? pull-pattern))))
        (is (every? #(or (qualified-keyword? %) (map? %))
                    pull-pattern)))
      (finally
        (instrument/instrument-delta!
         {::instrument/changed-syms #{sym}
          ::instrument/targets []})))))

(deftest agent-override-schemas-are-native-and-reject-inherit
  (let [expected
        {::ai/agent-provider ::ai/provider
         ::ai/agent-model ::ai/model
         ::ai/agent-temperature ::ai/temperature
         ::ai/agent-max-tokens ::ai/max-tokens
         ::ai/agent-completion-limit-field ::ai/completion-limit-field
         ::ai/agent-thinking ::ai/thinking
         ::ai/agent-timeout-ms ::ai/timeout-ms
         ::ai/agent-base-url ::ai/base-url
         ::ai/agent-api-key-env ::ai/api-key-env
         ::ai/agent-dg-backend ::ai/dg-backend
         ::ai/agent-extra-body-edn ::ai/extra-body-edn
         ::ai/agent-max-retries [:int {:min 0}]
         ::ai/agent-attempt-timeout-ms ::ai/timeout-ms
         ::ai/agent-fallback-variant :seon.config/model-variant}
        facets (into {}
                     (map (juxt :db/ident :db/valueType))
                     (db/malli->datahike-schema (keys expected)))]
    (doseq [[attribute definition] expected]
      (is (= definition (schema/schema-definition attribute))
          (str attribute " has one ordinary native schema"))
      (is (false? (schema/valid-candidate-value? attribute :inherit))
          (str attribute " rejects explicit :inherit")))
    (is (= :db.type/double (facets ::ai/agent-temperature)))
    (doseq [attribute [::ai/agent-max-tokens
                       ::ai/agent-timeout-ms
                       ::ai/agent-max-retries
                       ::ai/agent-attempt-timeout-ms]]
      (is (= :db.type/long (facets attribute))
          (str attribute " installs as a native long")))
    (let [entity {:seon.agent/id "native-agent"
                  ::ai/agent-temperature 0.25
                  ::ai/agent-max-tokens 8192
                  ::ai/agent-timeout-ms 180000
                  ::ai/agent-max-retries 2
                  ::ai/agent-attempt-timeout-ms 240000}]
      (is (= [entity] (db/encode-edn-slot-values [entity]))
          "native scalar overrides never enter the EDN-slot encoder"))))

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
              (js/Promise.resolve
               {:db-before database
                :db-after (assoc database :t 536870913)})))
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
                (is (= ai/configuration-read-profile
                       (select-keys member
                                    (keys ai/configuration-read-profile)))
                    "the distinct AI singleton consumes its one named profile")
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
          ::ai/provider :deepseek
          ::ai/model "global-model"
          ::ai/timeout-ms 1111
          :seon.config.model-transport/response-identity-cap 31}
         {:seon.agent/id "agent-1"
          ::ai/agent-provider :openai-compat
          ::ai/agent-model "agent-model"
          ::ai/agent-temperature 0.0
          ::ai/agent-max-tokens 4096
          ::ai/agent-completion-limit-field :max-completion-tokens
          ::ai/agent-timeout-ms 2222
          ::ai/agent-attempt-timeout-ms 3333
          ::ai/agent-base-url "https://agent.example/v1"
          ::ai/agent-api-key-env "AGENT_API_KEY"
          ::ai/agent-dg-backend :vllm
          ::ai/agent-extra-body-edn "{:agent-option true}"
          ::ai/agent-max-retries 2})
        config (::ai/resolved-config resolution)]
    (is (= :deepseek
           (get-in default-resolution [::ai/resolved-config ::ai/provider])))
    (is (= :openai-compat (ai/resolved-adapter config)))
    (is (= :openai-compat (::ai/provider config)))
    (is (= "agent-model" (::ai/model config)))
    (is (= 0.0 (::ai/temperature config)))
    (is (= 4096 (::ai/max-tokens config)))
    (is (= :max-completion-tokens (::ai/completion-limit-field config)))
    (is (= 2222 (::ai/timeout-ms config)))
    (is (= 3333 (::ai/agent-attempt-timeout-ms resolution)))
    (is (= "https://agent.example/v1" (::ai/base-url config)))
    (is (= "AGENT_API_KEY" (::ai/api-key-env config)))
    (is (= :vllm (::ai/dg-backend config)))
    (is (= {:agent-option true} (::ai/extra-body resolution)))
    (is (= 64 (count (::ai/extra-body-digest config))))
    (is (= 2 (::ai/agent-max-retries resolution)))
    (is (= 31
           (:seon.config.model-transport/response-identity-cap config)))
    (doseq [attr [::ai/provider ::ai/model ::ai/temperature ::ai/max-tokens
                  ::ai/completion-limit-field ::ai/timeout-ms
                  ::ai/base-url ::ai/api-key-env ::ai/dg-backend
                  ::ai/extra-body-digest]]
      (is (= :agent-override (get-in resolution [::ai/provenance attr]))
          (str attr " comes from the agent entity")))
    (is (= :config-row
           (get-in resolution
                   [::ai/provenance
                    :seon.config.model-transport/response-identity-cap])))))

(deftest absent-agent-override-inherits-the-global-row
  (let [resolution
        (ai/resolved-config-from-rows
         {::ai/provider :openai-compat
          ::ai/model "global-model"
          ::ai/max-tokens 2048}
         {:seon.agent/id "agent-with-no-overrides"})]
    (is (= "global-model"
           (get-in resolution [::ai/resolved-config ::ai/model])))
    (is (= 2048
           (get-in resolution [::ai/resolved-config ::ai/max-tokens])))
    (is (= :config-row
           (get-in resolution [::ai/provenance ::ai/model])))
    (is (= :config-row
           (get-in resolution [::ai/provenance ::ai/max-tokens])))))

(deftest r36-reply-configuration-resolves-each-axis-with-legacy-precedence
  (let [cluster {:seon.ai/wire-stream? false
                 :seon.ai/reply-evaluation :batch
                 :seon.config/repl-mode :batch}]
    (is (= {:seon.ai/wire-stream? false
            :seon.ai/reply-evaluation :batch}
           (ai/reply-configuration-from-rows cluster {})))
    (is (= {:seon.ai/wire-stream? true
            :seon.ai/reply-evaluation :first-form}
           (ai/reply-configuration-from-rows
            cluster {:seon.config/repl-mode :stream}))
        "an agent legacy pair outranks exact singleton facts")
    (is (= {:seon.ai/wire-stream? false
            :seon.ai/reply-evaluation :first-form}
           (ai/reply-configuration-from-rows
            {:seon.config/repl-mode :stream}
            {:seon.ai/wire-stream? false}))
        "an exact agent fact overrides only its own axis")
    (is (= {:seon.ai/wire-stream? true
            :seon.ai/reply-evaluation :batch}
           (ai/reply-configuration-from-rows
            {:seon.ai/wire-stream? true
             :seon.ai/reply-evaluation :batch}
            {})))))

(deftest declared-fallback-variant-resolves-from-pulled-component-children
  (let [resolution
        (ai/resolved-config-from-rows
         {::ai/provider :deepseek
          ::ai/model "primary-model"
          :seon.config/model-variants
          [{:seon.config/model-variant :planning
            ::ai/agent-provider :openai-compat
            ::ai/agent-model "fallback-model"
            ::ai/agent-base-url "https://fallback.example/v1"
            ::ai/agent-api-key-env "FALLBACK_API_KEY"}]}
         {::ai/agent-fallback-variant :planning})
        fallback (::ai/fallback-config-resolution resolution)
        fallback-config (::ai/resolved-config fallback)]
    (is (= :planning (::ai/fallback-variant resolution)))
    (is (= [:openai-compat
           "fallback-model"
           "https://fallback.example/v1"
           "FALLBACK_API_KEY"]
           (mapv #(get fallback-config %)
                 [::ai/provider ::ai/model ::ai/base-url ::ai/api-key-env]))
        "the decoded accessor preserves a declared fallback from the pulled child vector")
    (doseq [attribute [::ai/provider ::ai/model ::ai/base-url ::ai/api-key-env]]
      (is (= :agent-override (get-in fallback [::ai/provenance attribute]))
          (str attribute " remains a variant override")))))

(deftest resolution-carries-the-attempt-cap-once-at-acquisition
  ;; I6 (frozen-turn-inputs): the per-attempt wall-clock cap is resolved by
  ;; the builder — agent override, else the SEON_LLM_ATTEMPT_TIMEOUT_MS
  ;; process default — so the turn's retry loop never re-reads env.
  (let [env (.-env js/process)
        saved (aget env "SEON_LLM_ATTEMPT_TIMEOUT_MS")]
    (try
      (aset env "SEON_LLM_ATTEMPT_TIMEOUT_MS" "7777")
      (is (= 7777
             (::ai/agent-attempt-timeout-ms
              (ai/resolved-config-from-rows {} {})))
          "a no-override resolution still carries the process default")
      (is (= 3333
             (::ai/agent-attempt-timeout-ms
              (ai/resolved-config-from-rows
               {} {::ai/agent-attempt-timeout-ms 3333})))
          "the agent's own override wins over the process default")
      (finally
        (if (some? saved)
          (aset env "SEON_LLM_ATTEMPT_TIMEOUT_MS" saved)
          (js-delete env "SEON_LLM_ATTEMPT_TIMEOUT_MS"))))))

(deftest compatible-gateway-settings-are-independent-per-agent
  (let [global {::ai/provider :deepseek
                ::ai/model "deepseek-v4-pro"}
        kimi (ai/resolved-config-from-rows
               global
               {::ai/agent-provider :openai-compat
                ::ai/agent-model "kimi-k3"
                ::ai/agent-completion-limit-field :max-completion-tokens
                ::ai/agent-base-url "https://api.moonshot.ai/v1"
                ::ai/agent-api-key-env "MOONSHOT_API_KEY"
                ::ai/agent-timeout-ms 180000
                ::ai/agent-attempt-timeout-ms 240000})
        muse (ai/resolved-config-from-rows
               global
               {::ai/agent-provider :openai-compat
                ::ai/agent-model "muse-spark-1.1"
                ::ai/agent-base-url "https://api.meta.ai/v1"
                ::ai/agent-api-key-env "META_API_KEY"
                ::ai/agent-thinking "minimal"})
        kimi-config (::ai/resolved-config kimi)
        muse-config (::ai/resolved-config muse)]
    (is (= ["kimi-k3" "https://api.moonshot.ai/v1"
            "MOONSHOT_API_KEY" 180000 :max-completion-tokens]
           (mapv kimi-config
                 [::ai/model ::ai/base-url ::ai/api-key-env ::ai/timeout-ms
                  ::ai/completion-limit-field])))
    (is (= 240000 (::ai/agent-attempt-timeout-ms kimi)))
    (is (= ["muse-spark-1.1" "https://api.meta.ai/v1"
            "META_API_KEY" "minimal"]
           (mapv muse-config
                 [::ai/model ::ai/base-url ::ai/api-key-env ::ai/thinking])))
    (is (= :deepseek (::ai/provider (::ai/resolved-config
                                      (ai/resolved-config-from-rows global {}))))
        "an unrelated agent still inherits the cluster provider")))
