(ns seon.ai.deepseek-test
  "Tests for the DeepSeek client's pure surface (post-C-18):
     - request-body with NO env + NO config row is BYTE-IDENTICAL to
       the pre-C-18 wire body (thinking disabled, temperature 0.7,
       max_tokens 4096, deepseek-v4-pro)
     - the :seon.ai/config row drives thinking / model / temperature /
       max-tokens PER CALL (transact a row → the very next
       request-body picks it up; no atoms, no restart)
     - explicit request opts win over the row
     - error classification (:seon.ai/transport? = the one retryable
       class) over a stubbed js/fetch

   The actual HTTP path (timeout abort, error-as-value envelope) is
   proven live against the real API — see the 2026-06-10 unit report.

   Run interactively via MCP eval:

     (require 'seon.ai.deepseek-test :reload)
     (cljs.test/run-tests 'seon.ai.deepseek-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.ai.deepseek :as deepseek]
    [seon.db :as db]))

;; ============================================================
;; Conn helpers — fresh :memory conn carrying the :seon.ai/config
;; attrs (same pattern as seon.web.brand-test), bound as the ROOT
;; db/*conn* so request-body's per-call config read sees it.
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
                                          ::ai/thinking ::ai/timeout-ms
                                          ::ai/base-url ::ai/api-key-env])
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

;; ============================================================
;; Byte-identical default — absent env + absent row sends EXACTLY the
;; pre-C-18 body. The system prompt is pinned explicitly so the
;; assertion is a FULL-map equality, not a key sample.
;; ============================================================

(deftest request-body-default-is-byte-identical-to-pre-c18
  (async done
    (-> (with-conn
          (fn [_conn]
            (is (= {:model       "deepseek-v4-pro"
                    :messages    [{:role "system" :content "sys"}
                                  {:role "user"   :content "the ctx"}]
                    :temperature 0.7
                    :max_tokens  4096
                    :thinking    {:type "disabled"}
                    :stream      false}
                   (deepseek/request-body {:seon.ai/ctx           "the ctx"
                                           :seon.ai/system-prompt "sys"}))
                "no env, no config row → the exact pre-C-18 wire body")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; The config row drives the body PER CALL.
;; ============================================================

(deftest config-row-drives-thinking-and-budgets-per-call
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; 1. Row absent → thinking disabled.
            (is (= {:type "disabled"}
                   (:thinking (deepseek/request-body {:seon.ai/ctx "hi"}))))
            ;; 2. Transact thinking "true" → enabled, SAME call path,
            ;;    no restart, no atom.
            (-> (db/transact!
                  {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "true"}]})
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?))
                         (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
                           (is (= {:type "enabled"} (:thinking body)))
                           (is (not (contains? body :reasoning_effort))))
                         ;; 3. Effort string → enabled + reasoning_effort.
                         (db/transact!
                           {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "high"}]})))
                (.then (fn [_]
                         (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
                           (is (= {:type "enabled"} (:thinking body)))
                           (is (= "high" (:reasoning_effort body))))
                         ;; 4. Model/temperature/max-tokens from the row.
                         (db/transact!
                           {:seon.db/tx-data [{::ai/id          "config"
                                               ::ai/thinking    "false"
                                               ::ai/model       "deepseek-chat"
                                               ::ai/temperature 0.2
                                               ::ai/max-tokens  99}]})))
                (.then (fn [_]
                         (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
                           (is (= {:type "disabled"} (:thinking body)))
                           (is (= "deepseek-chat" (:model body)))
                           (is (= 0.2 (:temperature body)))
                           (is (= 99 (:max_tokens body))))
                         ;; 5. Explicit request opts WIN over the row.
                         (let [body (deepseek/request-body
                                      {:seon.ai/ctx         "x"
                                       :seon.ai/model       "deepseek-v4-pro"
                                       :seon.ai/temperature 0.9
                                       :seon.ai/max-tokens  7})]
                           (is (= "deepseek-v4-pro" (:model body)))
                           (is (= 0.9 (:temperature body)))
                           (is (= 7 (:max_tokens body)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest request-body-defaults-and-overrides
  (testing "defaults fill model/temperature/max_tokens; ctx is the user msg"
    (let [body (deepseek/request-body {:seon.ai/ctx "the ctx"})]
      (is (= "deepseek-v4-pro" (:model body)))
      (is (= false (:stream body)))
      (is (= "the ctx" (-> body :messages second :content)))
      (is (= "user" (-> body :messages second :role)))))
  (testing "explicit opts win"
    (let [body (deepseek/request-body {:seon.ai/ctx           "x"
                                       :seon.ai/model         "deepseek-chat"
                                       :seon.ai/temperature   0.2
                                       :seon.ai/max-tokens    99
                                       :seon.ai/system-prompt "sys"})]
      (is (= "deepseek-chat" (:model body)))
      (is (= 0.2 (:temperature body)))
      (is (= 99 (:max_tokens body)))
      (is (= "sys" (-> body :messages first :content))))))

;; ============================================================
;; Error classification (agent-robustness unit, 2026-06-11).
;; `:seon.ai/transport?` marks the ONE retryable class — fetch threw
;; before any HTTP status (the observed live "fetch failed"). HTTP
;; status errors are processing errors and never carry the flag.
;; js/fetch is stubbed per test; the real global is restored after.
;; ============================================================

(defn- with-env
  "Run `body` (0-arg → Promise) with process.env vars set/deleted per
   `settings` (var-name → string, or nil = deleted); SNAPSHOT every
   touched var first and restore it EXACTLY after (prior value
   re-asserted, originally-absent deleted) — the operator's provider
   steering survives the suite (see ai-test's with-env-restored)."
  [settings body]
  (let [env   (.. js/process -env)
        saved (into {} (map (fn [[k _]] [k (aget env k)])) settings)]
    (doseq [[k v] settings]
      (if (some? v) (aset env k v) (js-delete env k)))
    (-> (js/Promise.resolve (body))
        (.finally (fn []
                    (doseq [[k _] settings]
                      (let [v (get saved k)]
                        (if (some? v) (aset env k v) (js-delete env k)))))))))

(defn- with-fetch
  "Run `body` (0-arg → Promise) with `js/fetch` replaced by `stub`;
   restore the real global after. Returns a Promise."
  [stub body]
  (let [orig-fetch js/fetch]
    (set! js/fetch stub)
    (-> (js/Promise.resolve (body))
        (.finally (fn [] (set! js/fetch orig-fetch))))))

(defn- with-stubbed-fetch
  "Stubbed fetch + a deterministic :deepseek key environment: a test
   DEEPSEEK_API_KEY, with the provider-steering vars (SEON_AI_PROVIDER,
   SEON_AI_API_KEY / SEON_AI_API_KEY_ENV) cleared so an operator env
   can't flip the provider mid-suite. All restored after."
  [stub body]
  (with-env {"DEEPSEEK_API_KEY"    "test-key"
             "SEON_AI_PROVIDER"    nil
             "SEON_AI_API_KEY"     nil
             "SEON_AI_API_KEY_ENV" nil}
    #(with-fetch stub body)))

(defn- ok-json-response
  "A minimal OK chat-completions response object for fetch stubs."
  []
  #js {:ok     true
       :status 200
       :text   (fn [] (js/Promise.resolve
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"))})

(defn- capturing-fetch
  "A fetch stub that records [url opts] into atom `captured` and
   resolves `response`."
  [captured response]
  (fn [url opts]
    (reset! captured {:url     url
                      :headers (js->clj (.-headers opts))
                      :body    (js->clj (.parse js/JSON (.-body opts))
                                        :keywordize-keys true)})
    (js/Promise.resolve response)))

(deftest fetch-throw-is-transport-shaped
  (async done
    (-> (with-stubbed-fetch
          (fn [_ _] (js/Promise.reject (js/TypeError. "fetch failed")))
          #(deepseek/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text) "errors-as-values — empty text, never a rejection")
            (is (true? (:seon.ai/transport? error))
                "fetch threw with no HTTP status → the retryable class")
            (is (not (contains? error :seon.ai/timeout?))
                "a network throw is not a wall-clock abort")
            (is (str/includes? (:seon.ai/msg error) "fetch failed"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest http-status-error-is-not-transport-shaped
  (async done
    (-> (with-stubbed-fetch
          (fn [_ _]
            (js/Promise.resolve
              #js {:ok     false
                   :status 400
                   :text   (fn [] (js/Promise.resolve "bad request"))}))
          #(deepseek/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text))
            (is (= 400 (:seon.ai/status error)))
            (is (not (contains? error :seon.ai/transport?))
                "HTTP/processing errors must NEVER look retryable")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; :openai-compat wire-shape matrix (task #30; downstream ask 24).
;; The deepseek request path IS the openai-compat path — endpoint +
;; bearer key resolve from the :seon.ai/config row / SEON_AI_* env.
;; :seon.ai/base-url semantic: the FULL chat-completions URL, posted
;; as-is. No paid calls — js/fetch is stubbed/captured throughout.
;; ============================================================

(deftest deepseek-url-and-bearer-are-pinned
  ;; Pre-#30 pin: :deepseek still posts to the shipped endpoint with
  ;; the DEEPSEEK_API_KEY bearer and the explicit thinking toggle.
  (async done
    (let [captured (atom nil)]
      (-> (with-stubbed-fetch (capturing-fetch captured (ok-json-response))
            #(deepseek/complete {:seon.ai/ctx "hi" :seon.ai/system-prompt "sys"}))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (nil? error))
              (is (= "ok" text))
              (is (= "https://api.deepseek.com/chat/completions"
                     (:url @captured))
                  ":deepseek keeps the shipped endpoint")
              (is (= "Bearer test-key"
                     (get (:headers @captured) "Authorization"))
                  "DEEPSEEK_API_KEY remains the :deepseek default key")
              (is (= {:type "disabled"} (:thinking (:body @captured)))
                  ":deepseek always sends the explicit thinking toggle")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest openai-compat-row-config-drives-url-bearer-and-body
  (async done
    (let [captured (atom nil)]
      (-> (with-conn
            (fn [_conn]
              (with-env {"SEON_AI_PROVIDER"    "openai-compat"
                         "ACME_GW_KEY"         "gw-secret"
                         "SEON_AI_API_KEY"     nil
                         "SEON_AI_API_KEY_ENV" nil
                         "SEON_AI_BASE_URL"    nil
                         "DEEPSEEK_API_KEY"    "decoy"}  ; must NOT be used
                (fn []
                  (-> (db/transact!
                        {:seon.db/tx-data
                         [{::ai/id          "config"
                           ::ai/base-url    "https://gw.example.com/v1/chat/completions"
                           ::ai/api-key-env "ACME_GW_KEY"}]})
                      (.then
                        (fn [{ok? :seon.db/ok?}]
                          (is (true? ok?) "config row transact lands")
                          (with-fetch (capturing-fetch captured (ok-json-response))
                            #(deepseek/complete {:seon.ai/ctx           "hi"
                                                 :seon.ai/system-prompt "sys"}))))
                      (.then
                        (fn [{:seon.ai/keys [text error]}]
                          (is (nil? error))
                          (is (= "ok" text))
                          (is (= "https://gw.example.com/v1/chat/completions"
                                 (:url @captured))
                              "base-url IS the posted URL — full chat-completions semantic, nothing appended")
                          (is (= "Bearer gw-secret"
                                 (get (:headers @captured) "Authorization"))
                              "key resolves via the api-key-env indirection — never the deepseek default")
                          (let [body (:body @captured)]
                            (is (= "deepseek-v4-pro" (:model body)))
                            (is (= [{:role "system" :content "sys"}
                                    {:role "user"   :content "hi"}]
                                   (:messages body)))
                            (is (= 0.7 (:temperature body)))
                            (is (= 4096 (:max_tokens body)))
                            (is (false? (:stream body))
                                "body identical to the deepseek shape …")
                            (is (not (contains? body :thinking))
                                "… EXCEPT thinking: absent/falsy sends NOTHING for :openai-compat")
                            (is (not (contains? body :reasoning_effort)))))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest openai-compat-thinking-sent-only-when-truthy
  (async done
    (-> (with-conn
          (fn [_conn]
            (with-env {"SEON_AI_PROVIDER" "openai-compat"}
              (fn []
                (is (not (contains? (deepseek/request-body {:seon.ai/ctx "hi"})
                                    :thinking))
                    "no row → no thinking field at all")
                (-> (db/transact!
                      {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "high"}]})
                    (.then
                      (fn [_]
                        (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
                          (is (= {:type "enabled"} (:thinking body))
                              "truthy thinking goes out as today")
                          (is (= "high" (:reasoning_effort body))))
                        (db/transact!
                          {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "false"}]})))
                    (.then
                      (fn [_]
                        (let [body (deepseek/request-body {:seon.ai/ctx "hi"})]
                          (is (not (contains? body :thinking))
                              "explicit \"false\" also sends nothing — graceful no-op")
                          (is (not (contains? body :reasoning_effort)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest openai-compat-missing-base-url-is-a-legible-config-error
  (async done
    (let [called (atom 0)]
      (-> (with-conn
            (fn [_conn]
              (with-env {"SEON_AI_PROVIDER"    "openai-compat"
                         "SEON_AI_BASE_URL"    nil
                         "SEON_AI_API_KEY"     "k"
                         "SEON_AI_API_KEY_ENV" nil}
                (fn []
                  (with-fetch (fn [_ _]
                                (swap! called inc)
                                (js/Promise.resolve (ok-json-response)))
                    #(deepseek/complete {:seon.ai/ctx "hi"}))))))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (= "" text) "error envelope, not a throw to the loop")
              (is (zero? @called) "no fetch is attempted on a config gap")
              (is (str/includes? (:seon.ai/msg error) "SEON_AI_BASE_URL")
                  "the error names the exact env var to set")
              (is (str/includes? (:seon.ai/msg error) ":seon.ai/base-url")
                  "… and the row attr to transact")
              (is (not (contains? error :seon.ai/transport?))
                  "config errors must never look retryable")
              (is (not (contains? error :seon.ai/timeout?)))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest openai-compat-missing-key-is-a-legible-config-error
  (async done
    (-> (with-conn
          (fn [_conn]
            (with-env {"SEON_AI_PROVIDER"    "openai-compat"
                       "SEON_AI_API_KEY"     nil
                       "SEON_AI_API_KEY_ENV" nil
                       "DEEPSEEK_API_KEY"    "decoy"}  ; not a compat key
              (fn []
                (-> (db/transact!
                      {:seon.db/tx-data
                       [{::ai/id       "config"
                         ::ai/base-url "https://gw.example.com/v1/chat/completions"}]})
                    (.then (fn [_] (deepseek/complete {:seon.ai/ctx "hi"}))))))))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text))
            (is (str/includes? (:seon.ai/msg error) "SEON_AI_API_KEY")
                "the error names the conventional key env")
            (is (not (contains? error :seon.ai/transport?)))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest openai-compat-seon-ai-api-key-is-the-direct-fallback
  (async done
    (let [captured (atom nil)]
      (-> (with-conn
            (fn [_conn]
              (with-env {"SEON_AI_PROVIDER"    "openai-compat"
                         "SEON_AI_API_KEY"     "fallback-key"
                         "SEON_AI_API_KEY_ENV" nil
                         "DEEPSEEK_API_KEY"    nil}
                (fn []
                  (-> (db/transact!
                        {:seon.db/tx-data
                         [{::ai/id       "config"
                           ::ai/base-url "https://gw.example.com/v1/chat/completions"}]})
                      (.then
                        (fn [_]
                          (with-fetch (capturing-fetch captured (ok-json-response))
                            #(deepseek/complete {:seon.ai/ctx "hi"})))))))))
          (.then
            (fn [{:seon.ai/keys [error]}]
              (is (nil? error))
              (is (= "Bearer fallback-key"
                     (get (:headers @captured) "Authorization"))
                  "SEON_AI_API_KEY read directly at call time — never transacted")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
