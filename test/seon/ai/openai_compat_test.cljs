(ns seon.ai.openai-compat-test
  "Tests for the OpenAI-compatible client (official `openai` SDK):
     - request-params with NO env + NO config row keeps the pre-SDK wire
       shape (deepseek-v4-pro, temperature 0.7, max_tokens 4096,
       thinking disabled) PLUS the streaming knobs (no `:stream false`;
       `:stream_options {:include_usage true}`)
     - the :seon.ai/config row drives thinking / model / temperature /
       max-tokens PER CALL; explicit request opts win over the row
     - tools / tool_choice included only when passed; :extra-body is a
       SEPARATE 2nd-arg body, never inlined into params
     - SDK error classification onto the :seon.ai/error envelope
       (transport / status) over an injected fetch
     - happy-path streaming assembly + provider-fields (#25) + tool-calls

   THE WIRE-TEST SEAM: `seon.ai.openai-compat/*fetch*` is a dynamic var
   the adapter's `make-client` injects as the SDK `:fetch` option when
   bound. We ROOT-`set!` it (not `binding`) because `complete` is an
   instrumented ^:async fn whose body runs past a `binding`'s
   synchronous unwind — a root set! survives the async boundary. Each
   test restores it to nil in a `.finally`.

   Run interactively via MCP eval:

     (require 'seon.ai.openai-compat-test :reload)
     (cljs.test/run-tests 'seon.ai.openai-compat-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    ["openai" :as OpenAI]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.ai.openai-compat :as openai]
    [seon.agent]
    [seon.agent.message]
    [seon.db :as db]))

;; ============================================================
;; Conn helpers — fresh :memory conn carrying the :seon.ai/config
;; attrs, bound as the ROOT db/*conn* so request-params' per-call
;; config read sees it.
;; ============================================================

(defn- fresh-conn
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data (into (db/malli->datahike-schema
                                                  [::ai/id ::ai/provider ::ai/model
                                                   ::ai/temperature ::ai/max-tokens
                                                   ::ai/thinking ::ai/timeout-ms
                                                   ::ai/base-url ::ai/api-key-env
                                                   ::ai/extra-body-edn])
                                                (db/tx-meta-datahike-schema))})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

;; Forward ref — with-env is defined with the other env/fetch helpers
;; below, but the provider-pinned pure-shape tests above it use it.
(declare with-env)

;; ============================================================
;; Pure request-params shape.
;; ============================================================

(deftest request-params-default-shape
  ;; Pin provider=deepseek: clear the ambient SEON_AI_PROVIDER (this pod may
  ;; be deployed :openai-compat) + fresh empty row → provider defaults to
  ;; :deepseek, so the deepseek-specific :thinking toggle is deterministic.
  ;;
  ;; NESTING: with-conn OUTERMOST, with-env INSIDE — matches the working
  ;; base-url-strip-reconciliation pattern. with-env is a forward-declared
  ;; var (defined below); when it heads the `->` thread the shadow-cljs
  ;; analyzer can't see its promise return and wraps the call in an
  ;; `(await (async-IIFE))`, which unwraps the promise to a plain value
  ;; BEFORE the `.then` — so `<value>.then` throws and the test wedges
  ;; (done never fires). with-conn is defined above the tests, so heading
  ;; the thread with it compiles to a clean `with_conn(...).then(...)`.
  (async done
    (-> (with-conn
          (fn [_conn]
            (with-env {"SEON_AI_PROVIDER" nil}
              (fn []
                (let [params (openai/request-params {:seon.ai/ctx           "the ctx"
                                                     :seon.ai/system-prompt "sys"})]
                  (is (= {:model          "deepseek-v4-pro"
                          :messages       [{:role "system" :content "sys"}
                                           {:role "user"   :content "the ctx"}]
                          :temperature    0.7
                          :max_tokens     4096
                          :stream_options {:include_usage true}
                          :thinking       {:type "disabled"}}
                         params)
                      (str "no env, no row → the pre-SDK body shape PLUS "
                           ":stream_options, and NO :stream key (the SDK owns "
                           "streaming)"))
                  (is (not (contains? params :stream))
                      "no manual :stream flag — the SDK sets it"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest config-row-drives-params-per-call
  ;; Pin provider=deepseek (see request-params-default-shape) so the
  ;; deepseek :thinking toggle is asserted regardless of this pod's deploy.
  ;; NESTING: with-conn OUTERMOST, with-env INSIDE (see
  ;; request-params-default-shape for why with-env must not head the thread).
  (async done
    (-> (with-conn
          (fn [_conn]
           (with-env {"SEON_AI_PROVIDER" nil}
            (fn []
              ;; Row absent → thinking disabled.
              (is (= {:type "disabled"}
                     (:thinking (openai/request-params {:seon.ai/ctx "hi"}))))
              (-> (db/transact!
                    {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "true"}]})
                  (.then (fn [{ok? :seon.db/ok?}]
                           (is (true? ok?))
                           (let [p (openai/request-params {:seon.ai/ctx "hi"})]
                             (is (= {:type "enabled"} (:thinking p)))
                             (is (not (contains? p :reasoning_effort))))
                           (db/transact!
                             {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "high"}]})))
                  (.then (fn [_]
                           (let [p (openai/request-params {:seon.ai/ctx "hi"})]
                             (is (= {:type "enabled"} (:thinking p)))
                             (is (= "high" (:reasoning_effort p))))
                           (db/transact!
                             {:seon.db/tx-data [{::ai/id          "config"
                                                 ::ai/thinking    "false"
                                                 ::ai/model       "deepseek-chat"
                                                 ::ai/temperature 0.2
                                                 ::ai/max-tokens  99}]})))
                  (.then (fn [_]
                           (let [p (openai/request-params {:seon.ai/ctx "hi"})]
                             (is (= {:type "disabled"} (:thinking p)))
                             (is (= "deepseek-chat" (:model p)))
                             (is (= 0.2 (:temperature p)))
                             (is (= 99 (:max_tokens p))))
                           ;; Explicit request opts WIN over the row.
                           (let [p (openai/request-params
                                     {:seon.ai/ctx         "x"
                                      :seon.ai/model       "deepseek-v4-pro"
                                      :seon.ai/temperature 0.9
                                      :seon.ai/max-tokens  7})]
                             (is (= "deepseek-v4-pro" (:model p)))
                             (is (= 0.9 (:temperature p)))
                             (is (= 7 (:max_tokens p)))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest compat-thinking-sends-only-standard-reasoning-effort
  ;; :openai-compat NEVER sends the vendor :thinking field — strict
  ;; gateways (Meta Model API, vLLM) HTTP-400 unknown params (verified
  ;; live against api.meta.ai 2026-07-10). An effort STRING goes out as
  ;; the standard :reasoning_effort; "true" has no standard wire form on
  ;; a generic gateway and sends NEITHER field.
  ;; NESTING: with-conn OUTERMOST, with-env INSIDE (see
  ;; request-params-default-shape for why with-env must not head the thread).
  (async done
    (-> (with-conn
          (fn [_conn]
           (with-env {"SEON_AI_PROVIDER" nil}
            (fn []
              (-> (db/transact!
                    {:seon.db/tx-data [{::ai/id       "config"
                                        ::ai/provider :openai-compat
                                        ::ai/thinking "minimal"}]})
                  (.then (fn [{ok? :seon.db/ok?}]
                           (is (true? ok?))
                           (let [p (openai/request-params {:seon.ai/ctx "hi"})]
                             (is (= "minimal" (:reasoning_effort p))
                                 "effort string → the STANDARD param")
                             (is (not (contains? p :thinking))
                                 "vendor :thinking never sent on compat"))
                           (db/transact!
                             {:seon.db/tx-data [{::ai/id       "config"
                                                 ::ai/thinking "true"}]})))
                  (.then (fn [_]
                           (let [p (openai/request-params {:seon.ai/ctx "hi"})]
                             (is (not (contains? p :thinking))
                                 "\"true\" on compat → no vendor field")
                             (is (not (contains? p :reasoning_effort))
                                 "\"true\" on compat → no invented effort")))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest tools-included-only-when-passed
  (async done
    (-> (with-conn
          (fn [_conn]
            (let [tools [{:type "function"
                          :function {:name "f" :parameters {}}}]
                  p0    (openai/request-params {:seon.ai/ctx "hi"})
                  p1    (openai/request-params {:seon.ai/ctx         "hi"
                                                :seon.ai/tools       tools
                                                :seon.ai/tool-choice "auto"})]
              (is (not (contains? p0 :tools)) "no tools opt → no :tools key")
              (is (not (contains? p0 :tool_choice)))
              (is (= tools (:tools p1)) "tools passthrough verbatim")
              (is (= "auto" (:tool_choice p1))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest extra-body-not-in-request-params-output
  ;; request-params builds ONLY the typed wire body; :extra-body is
  ;; merged in by `complete` (see extra-body-reaches-the-wire), so it is
  ;; absent from request-params' own output.
  (async done
    (-> (with-conn
          (fn [_conn]
            (let [p (openai/request-params
                      {:seon.ai/ctx        "hi"
                       :seon.ai/extra-body {:chat_template_kwargs {:enable_thinking false}}})]
              (is (not (contains? p :extra-body))
                  ":seon.ai/extra-body is not echoed as a wire key")
              (is (not (contains? p :chat_template_kwargs))
                  "request-params does not itself inline extra-body (complete does)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; Wire-test seam — inject a fetch into the SDK client via *fetch*.
;; ============================================================

(defn- with-env
  "Run `body` (0-arg → Promise) with process.env vars set/deleted per
   `settings` (var-name → string, or nil = deleted); snapshot every
   touched var first and restore it EXACTLY after."
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
  "Run `body` (0-arg → Promise) with `seon.ai.openai-compat/*fetch*`
   root-set! to `stub`; restore nil after. Root set! (not binding)
   because complete's instrumented ^:async body runs past a binding's
   synchronous unwind."
  [stub body]
  (set! openai/*fetch* stub)
  (-> (js/Promise.resolve (body))
      (.finally (fn [] (set! openai/*fetch* nil)))))

(defn- with-stubbed
  "Stubbed fetch + a deterministic :deepseek key environment (a test
   DEEPSEEK_API_KEY, provider-steering vars cleared so an operator env
   can't flip the provider mid-suite). All restored after."
  [stub body]
  (with-env {"DEEPSEEK_API_KEY"    "test-key"
             "SEON_AI_PROVIDER"    nil
             "SEON_AI_API_KEY"     nil
             "SEON_AI_API_KEY_ENV" nil}
    #(with-fetch stub body)))

(defn- sse-stream
  "A ReadableStream that enqueues `s` (UTF-8) and closes — the shape an
   injected fetch returns as a streaming Response body."
  [s]
  (js/ReadableStream.
    #js{:start (fn [ctrl]
                 (.enqueue ctrl (.encode (js/TextEncoder.) s))
                 (.close ctrl))}))

(defn- chunk-line [m] (str "data: " (.stringify js/JSON (clj->js m)) "\n\n"))

(def ^:private usage-fixture
  {:prompt_tokens 3 :completion_tokens 5 :total_tokens 8})

(defn- sse-completion
  "A canned OpenAI streaming chat-completion: one content-delta chunk, a
   finish chunk, a usage-only final chunk, then [DONE]. `extra-top`
   merges into the first chunk's top-level (to exercise provider-fields)
   and `tool-calls` rides the content delta when given."
  ([] (sse-completion {} nil))
  ([extra-top tool-calls]
   (str (chunk-line (merge {:id "x" :object "chat.completion.chunk" :created 1
                            :model "m"
                            :choices [{:index 0
                                       :delta (cond-> {:role "assistant" :content "hi"}
                                                tool-calls (assoc :tool_calls tool-calls))
                                       :finish_reason nil}]}
                           extra-top))
        (chunk-line {:id "x" :object "chat.completion.chunk" :created 1 :model "m"
                     :choices [{:index 0 :delta {} :finish_reason "stop"}]})
        (chunk-line {:id "x" :object "chat.completion.chunk" :created 1 :model "m"
                     :choices [] :usage usage-fixture})
        "data: [DONE]\n\n")))

(defn- streaming-fetch
  "An injected fetch that records [url init] into `captured` and returns
   a 200 streaming Response whose body is `sse-string`."
  [captured sse-string]
  (fn [url init]
    (reset! captured {:url     url
                      :auth    (some-> init .-headers (.get "authorization"))
                      :signal  (.-signal init)
                      :body    (js->clj (.parse js/JSON (.-body init))
                                        :keywordize-keys true)})
    (js/Promise.resolve
      (js/Response. (sse-stream sse-string)
                    #js{:status 200 :headers #js{"content-type" "text/event-stream"}}))))

(deftest happy-path-streams-text-and-usage
  (async done
    (let [captured (atom nil)]
      ;; Fresh empty row (with-conn) so the live pod's :openai-compat config
      ;; row doesn't override the stubbed :deepseek key path / endpoint.
      (-> (with-conn
            (fn [_conn]
              (with-stubbed (streaming-fetch captured (sse-completion))
                #(openai/complete {:seon.ai/ctx "hi" :seon.ai/system-prompt "sys"}))))
          (.then
            (fn [{:seon.ai/keys [text usage error] :as resp}]
              (is (nil? error))
              (is (= "hi" text) "the streamed content deltas assemble to text")
              (is (= usage-fixture usage) "usage ALWAYS set on success")
              (is (= "stop" (:seon.ai.openai-compat/finish-reason resp)))
              ;; The SDK posts to <root>/chat/completions and sends the
              ;; bearer — proves sdk-base-url strip-reconciliation here is
              ;; the default /v1 root path.
              (is (str/ends-with? (:url @captured) "/chat/completions"))
              (is (= "Bearer test-key" (:auth @captured)))
              (is (= {:include_usage true} (-> @captured :body :stream_options))
                  ":stream_options rides the wire")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest agent-adapter-threads-attempt-signal-to-sdk-fetch
  (async done
    (let [captured  (atom nil)
          controller (js/AbortController.)
          signal    (.-signal controller)]
      (-> (with-conn
            (fn [_conn]
              (with-stubbed
                (fn [_url init]
                  (reset! captured (.-signal init))
                  (.abort controller)
                  (js/Promise.reject (js/DOMException. "aborted" "AbortError")))
                #((openai/agent-adapter)
                  {:seon.ai/ctx "hi" :seon.ai/abort-signal signal}))))
          (.then (fn [{:seon.ai/keys [error]}]
                   (is (true? (:seon.ai/timeout? error)))
                   (is (true? (.-aborted @captured))
                       "the SDK links the attempt signal to its fetch controller")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest base-url-strip-reconciliation
  (async done
    (let [captured (atom nil)]
      (-> (with-conn
            (fn [_conn]
              (with-env {"SEON_AI_PROVIDER"    "openai-compat"
                         "ACME_GW_KEY"         "gw-secret"
                         "SEON_AI_API_KEY"     nil
                         "SEON_AI_API_KEY_ENV" nil
                         "SEON_AI_BASE_URL"    nil
                         "DEEPSEEK_API_KEY"    "decoy"}
                (fn []
                  (-> (db/transact!
                        {:seon.db/tx-data
                         ;; The LEGACY full chat-completions URL — the
                         ;; adapter must strip it to the /v1 root, then
                         ;; the SDK re-appends /chat/completions.
                         [{::ai/id          "config"
                           ::ai/base-url    "https://gw.example.com/v1/chat/completions"
                           ::ai/api-key-env "ACME_GW_KEY"}]})
                      (.then
                        (fn [{ok? :seon.db/ok?}]
                          (is (true? ok?))
                          (with-fetch (streaming-fetch captured (sse-completion))
                            #(openai/complete {:seon.ai/ctx "hi"}))))
                      (.then
                        (fn [{:seon.ai/keys [error]}]
                          (is (nil? error))
                          (is (= "https://gw.example.com/v1/chat/completions"
                                 (:url @captured))
                              "full /v1/chat/completions config → stripped to /v1, re-appended by the SDK = same URL")
                          (is (= "Bearer gw-secret" (:auth @captured))
                              "key resolves via api-key-env, never the deepseek default"))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest provider-fields-and-tool-calls-surface
  (async done
    (let [captured (atom nil)
          tc       [{:index 0 :id "t1" :type "function"
                     :function {:name "f" :arguments "{}"}}]]
      (-> (with-stubbed
            (streaming-fetch captured
                             (sse-completion {:seon_unknown "keepme"} tc))
            #(openai/complete {:seon.ai/ctx "hi"}))
          (.then
            (fn [{:seon.ai/keys [tool-calls provider-fields error]}]
              (is (nil? error))
              (is (= {:seon_unknown "keepme"} provider-fields)
                  "an unrecognized top-level completion field is preserved (#25)")
              (is (seq tool-calls) "tool_calls surfaced when present")
              (is (= "t1" (-> tool-calls first :id)))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest extra-body-reaches-the-wire
  (async done
    (let [captured (atom nil)]
      (-> (with-stubbed (streaming-fetch captured (sse-completion))
            #(openai/complete
               {:seon.ai/ctx        "hi"
                :seon.ai/extra-body {:chat_template_kwargs {:enable_thinking false}}}))
          (.then
            (fn [{:seon.ai/keys [error]}]
              (is (nil? error))
              (let [body (:body @captured)]
                (is (= {:enable_thinking false}
                       (:chat_template_kwargs body))
                    ":extra-body is merged INTO the request params (1st arg), reaching the wire body verbatim")
                ;; Regression guard: extra-body must NOT clobber the body.
                ;; Using the SDK's 2nd-arg {:body …} REPLACED the whole
                ;; payload, dropping model/messages → 400 on every
                ;; extra-body call (verified live). model + messages MUST
                ;; survive alongside the merged field.
                (is (some? (:model body)) "model survives the extra-body merge")
                (is (seq (:messages body)) "messages survive the extra-body merge"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest extra-body-config-row-reaches-the-loop
  ;; The DATA-ONLY door (task #30): the agent turn loop builds the adapter
  ;; with NO request opts, so :extra-body must flow from the config row.
  ;; A row carrying ::extra-body-edn (env SEON_AI_EXTRA_BODY) is decoded by
  ;; ai/config-extra-body and merged into the wire body — proving the loop
  ;; (no per-call opt) can suppress Qwen's <think> via enable_thinking.
  (async done
    (let [captured (atom nil)]
      (-> (with-conn
            (fn [_conn]
              (-> (db/transact!
                    {:seon.db/tx-data
                     [{:seon.ai/id "config"
                       :seon.ai/extra-body-edn
                       "{:chat_template_kwargs {:enable_thinking false}}"}]})
                  (.then
                    (fn [{ok? :seon.db/ok?}]
                      (is (true? ok?) "::extra-body-edn is a storable string attr")
                      ;; NO :seon.ai/extra-body opt — only the row.
                      (with-stubbed (streaming-fetch captured (sse-completion))
                        #(openai/complete {:seon.ai/ctx "hi"})))))))
          (.then
            (fn [{:seon.ai/keys [error]}]
              (is (nil? error))
              (let [body (:body @captured)]
                (is (= {:enable_thinking false} (:chat_template_kwargs body))
                    "config-row ::extra-body-edn decodes + reaches the wire with no per-call opt")
                (is (some? (:model body)) "model survives")
                (is (seq (:messages body)) "messages survive"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

;; ============================================================
;; Error classification — the SDK's error classes onto the envelope.
;; ============================================================

(deftest fetch-throw-is-transport-shaped
  (async done
    (-> (with-stubbed
          (fn [_ _] (js/Promise.reject (js/TypeError. "fetch failed")))
          #(openai/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text) "errors-as-values — empty text, never a rejection")
            (is (true? (:seon.ai/transport? error))
                "the SDK wraps a thrown fetch in APIConnectionError → the retryable class")
            (is (not (contains? error :seon.ai/timeout?))
                "a network throw is not a wall-clock abort")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest http-status-error-is-not-transport-shaped
  (async done
    (-> (with-stubbed
          (fn [_ _]
            (js/Promise.resolve
              (js/Response. "bad request"
                            #js{:status 400
                                :headers #js{"content-type" "application/json"}})))
          #(openai/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text))
            (is (= 400 (:seon.ai/status error))
                "the SDK turns a 400 Response into an APIError carrying .status")
            (is (not (contains? error :seon.ai/transport?))
                "HTTP/processing errors must NEVER look retryable")
            (is (not (contains? error :seon.ai/timeout?)))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest timeout-class-maps-to-timeout-flag
  ;; A constructed APIConnectionTimeoutError (a SUBCLASS of
  ;; APIConnectionError) must map to :timeout?, NOT :transport? — the
  ;; branch order in error->envelope checks timeout/abort first.
  (testing "error->envelope classifies the timeout subclass correctly"
    (let [classify @#'openai/error->envelope
          tmo      (new (.-APIConnectionTimeoutError OpenAI) #js{})
          env      (classify "OpenAI-compat" tmo)]
      (is (true? (:seon.ai/timeout? env)))
      (is (not (contains? env :seon.ai/transport?))
          "timeout is checked BEFORE the connection class it subclasses"))))

(deftest max-retries-zero-means-one-fetch-on-500
  (async done
    (let [calls (atom 0)]
      (-> (with-stubbed
            (fn [_ _]
              (swap! calls inc)
              (js/Promise.resolve
                (js/Response. "boom"
                              #js{:status 500
                                  :headers #js{"content-type" "application/json"}})))
            #(openai/complete {:seon.ai/ctx "hi"}))
          (.then
            (fn [{:seon.ai/keys [error]}]
              (is (= 500 (:seon.ai/status error)))
              (is (= 1 @calls)
                  "maxRetries:0 — the SDK does NOT retry; the agent loop is the sole retry authority")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest retry-after-header-surfaces-as-ms-on-429
  ;; A 429 carrying a `Retry-After` (delta-seconds) is mapped to
  ;; :seon.ai/retry-after-ms (parsed to ms) so the agent loop's backoff
  ;; can honor the server's directive. Still NON-transport (the retry
  ;; decision lives in seon.agent.turn/llm-retryable?, on :status).
  (async done
    (-> (with-stubbed
          (fn [_ _]
            (js/Promise.resolve
              (js/Response. "rate limited"
                            #js{:status 429
                                :headers #js{"content-type"  "application/json"
                                             "retry-after"   "2"}})))
          #(openai/complete {:seon.ai/ctx "hi"}))
        (.then
          (fn [{:seon.ai/keys [error]}]
            (is (= 429 (:seon.ai/status error)))
            (is (= 2000 (:seon.ai/retry-after-ms error))
                "Retry-After delta-seconds parsed to ms")
            (is (not (contains? error :seon.ai/transport?))
                "a 429 is status-shaped, never transport-flagged")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; Config-gap guards (no fetch attempted).
;; ============================================================

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
                                (js/Promise.resolve
                                  (js/Response. "" #js{:status 200})))
                    #(openai/complete {:seon.ai/ctx "hi"}))))))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (= "" text) "error envelope, not a throw")
              (is (zero? @called) "no fetch attempted on a config gap")
              (is (str/includes? (:seon.ai/msg error) "SEON_AI_BASE_URL"))
              (is (str/includes? (:seon.ai/msg error) ":seon.ai/base-url"))
              (is (not (contains? error :seon.ai/transport?)))
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
                       "DEEPSEEK_API_KEY"    "decoy"}
              (fn []
                (-> (db/transact!
                      {:seon.db/tx-data
                       [{::ai/id       "config"
                         ::ai/base-url "https://gw.example.com/v1"}]})
                    (.then (fn [_] (openai/complete {:seon.ai/ctx "hi"}))))))))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text))
            (is (str/includes? (:seon.ai/msg error) "SEON_AI_API_KEY"))
            (is (not (contains? error :seon.ai/transport?)))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; repl-mode :stream — the delta-by-delta consumer that ABORTS at the first
;; complete top-level form. Scripted async-iterable stub (no network): the
;; cheap delimiter-balance gate + the parse-forms confirm + the abort, all
;; exercised on `stream-until-form!`.
;; ============================================================

(defn- scripted-stream
  "A minimal SDK-stream STUB: async-iterable emitting one content delta per
   `pieces` element, plus an `.abort` recorded in `aborted`. Mirrors the
   ChatCompletionStream surface `stream-until-form!` touches
   (`[Symbol.asyncIterator]` → chunks with `.choices[0].delta.content`)."
  [pieces aborted]
  (let [i (atom 0)
        mk (fn [c] #js{:choices #js[#js{:delta #js{:content c}}]})]
    (js-obj
      "abort" (fn [] (reset! aborted true))
      js/Symbol.asyncIterator
      (fn []
        (js-obj "next"
                (fn []
                  (js/Promise.resolve
                    (let [n @i]
                      (if (< n (count pieces))
                        (do (swap! i inc) #js{:value (mk (nth pieces n)) :done false})
                        #js{:value js/undefined :done true})))))))))

(deftest stream-aborts-at-first-complete-form
  ;; the form streams across two deltas; the third delta (a fabricated tail)
  ;; must NEVER be reached — the stream aborts the instant the form closes.
  (async done
    (let [aborted (atom false)
          s (scripted-stream ["(+ 1" " 2)" " ⟹ 3 fabricated"] aborted)]
      (-> (openai/stream-until-form! s)
          (.then (fn [{::openai/keys [text aborted?]}]
                   (is (= "(+ 1 2)" text) "accumulated exactly through the first form")
                   (is (true? aborted?) "reported aborted")
                   (is (true? @aborted) "the SDK stream .abort was called")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest stream-keeps-going-past-a-demoted-data-literal
  ;; a bare {…} closes at delimiter-depth 0 but demotes to prose — the
  ;; consumer must keep streaming until a REAL evaluable form completes.
  (async done
    (let [aborted (atom false)
          s (scripted-stream [";; think\n" "{:a 1}\n" "(message/user \"hi\")"] aborted)]
      (-> (openai/stream-until-form! s)
          (.then (fn [{::openai/keys [text aborted?]}]
                   (is (true? aborted?))
                   (is (str/includes? text "(message/user \"hi\")")
                       "streamed through the real form, past the demoted literal")
                   (is (str/includes? text ";; think")
                       "leading comment (thinking) is kept with the form")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest stream-natural-end-when-no-form-completes
  ;; only comments stream — no top-level form ever closes, so the consumer
  ;; runs to the stream's natural end and reports NOT aborted.
  (async done
    (let [aborted (atom false)
          s (scripted-stream [";; just\n" ";; comments\n"] aborted)]
      (-> (openai/stream-until-form! s)
          (.then (fn [{::openai/keys [aborted?]}]
                   (is (false? aborted?) "no form → natural end, not aborted")
                   (is (false? @aborted) ".abort never called")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(defn- rejecting-stream
  "A minimal SDK-stream STUB whose iterator REJECTS on `.next` — the shape
   a transport failure (timeout / connection reset) takes on the
   ChatCompletionStream async-iteration surface."
  [err]
  (js-obj
    "abort" (fn [])
    js/Symbol.asyncIterator
    (fn [] (js-obj "next" (fn [] (js/Promise.reject err))))))

(deftest stream-transport-failure-is-a-value-not-a-rejection
  ;; THE P4-BENCH CRASH GUARD (2026-07-10): stream-until-form! is an
  ;; instrumented ^:async fn — a rejection propagating out of it records a
  ;; :core fault datom at the wrapper (pod-fatal under the dev :crash
  ;; dial) even though complete catches it one frame up. So the contract
  ;; is: NEVER reject; the SDK failure comes back as the ::error VALUE.
  ;; The process-level hook also asserts no rejection escapes to Node.
  (async done
    (let [escaped (atom [])
          handler (fn [reason _] (swap! escaped conj reason))
          _       (.on js/process "unhandledRejection" handler)
          s       (rejecting-stream (js/Error. "Request timed out."))]
      (-> (openai/stream-until-form! s)
          (.then (fn [{::openai/keys [text aborted? error]}]
                   (is (= "" text))
                   (is (false? aborted?))
                   (is (= "Request timed out." (some-> error .-message))
                       "the SDK failure rides the result map as ::error"))
                 (fn [e]
                   (is false (str "REJECTED — must be errors-as-values: " e))))
          ;; two macrotasks so Node would have fired any escaped rejection
          (.then (fn [_] (js/Promise. (fn [res] (js/setTimeout res 0)))))
          (.then (fn [_] (js/Promise. (fn [res] (js/setTimeout res 0)))))
          (.then (fn [_]
                   (.off js/process "unhandledRejection" handler)
                   (is (empty? @escaped)
                       (str "unhandledRejection escaped: "
                            (pr-str (mapv #(some-> ^js % .-message) @escaped))))
                   (done)))))))

(deftest stream-mode-transport-failure-is-errors-as-values
  ;; the whole complete path in repl-mode :stream — a transport-level
  ;; fetch rejection resolves to the retryable envelope, never a throw.
  (async done
    (let [escaped (atom [])
          handler (fn [reason _] (swap! escaped conj reason))
          _       (.on js/process "unhandledRejection" handler)]
      (-> (with-stubbed
            (fn [_ _] (js/Promise.reject (js/TypeError. "fetch failed")))
            #(openai/complete {:seon.ai/ctx "hi" :seon.ai/stream? true}))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (= "" text) "errors-as-values — empty text, never a rejection")
              (is (true? (:seon.ai/transport? error))
                  "stream-mode transport failure is the retryable class")))
          (.then (fn [_] (js/Promise. (fn [res] (js/setTimeout res 0)))))
          (.then (fn [_] (js/Promise. (fn [res] (js/setTimeout res 0)))))
          (.then (fn [_]
                   (.off js/process "unhandledRejection" handler)
                   (is (empty? @escaped)
                       (str "unhandledRejection escaped: "
                            (pr-str (mapv #(some-> ^js % .-message) @escaped))))
                   (done)))
          (.catch (fn [e]
                    (.off js/process "unhandledRejection" handler)
                    (is false (str "threw — " e))
                    (done)))))))
