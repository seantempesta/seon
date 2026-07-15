(ns seon.agent-retry-test
  "The bounded LLM transport retry (revived 2026-07-02; originally the
   agent-robustness unit, 2026-06-11).

   Pins `seon.agent.turn/ask-and-eval!` — the turn body whose
   `call-llm!` is the SOLE LLM retry authority (the adapters ship
   `maxRetries 0`). Current semantics under test:

     - a TRANSIENT provider failure — TRANSPORT-shaped
       (`:seon.ai/transport?`), HTTP 429, or HTTP 5xx — is retried with
       backoff, bounded by the agent's effective max-retries
       (`:seon.ai/agent-max-retries` datom when an int, else the
       default 4); every retry lands on the record as
       `:seon.agent.turn/llm-retries n`
     - a NON-transient error (HTTP 4xx other than 429) NEVER retries:
       one call, and no llm-retries key (optional = absent)
     - exhaustion closes the turn `:error` with the failure captured as
       data on `:seon.agent.turn/error` (a bounded string) — no throw
     - a recovery mid-retry is NOT an error, and still records the
       retry honestly

   Hermetic: blobs re-pointed to a pid-scoped tmp dir (the success path
   captures the raw reply blob), each test on a fresh :memory conn
   root-`set!` as `db/*conn*` (CLJS bindings don't survive await). The
   blank-reply success path evals zero forms, so `compile-state nil` is
   never touched."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async use-fixtures]]
    [my.blob :as blob]
    [seon.agent.turn :as turn]
    [seon.ai :as ai]
    [seon.client :as client]
    [seon.db :as db]))

;; ---------------------------------------------------------------------------
;; Fixtures — pid-scoped blob dir (reply capture on the success path),
;; fresh conn per test.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/agent-retry-test-" (.-pid js/process))))

(defonce ^:private !saved-storage-view (atom nil))

(use-fixtures :once
  {:before (fn []
             (reset! !saved-storage-view @blob/!storage-view)
             (reset! blob/!storage-view
                     {:my.blob/writable-dir fixture-dir
                      :my.blob/read-only-dirs []})
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))
   :after  (fn []
             (reset! blob/!storage-view @!saved-storage-view)
             (.rmSync nfs fixture-dir #js {:recursive true :force true}))})

(defn- with-conn
  "Open a fresh schema-loaded conn, `set!` it as the ROOT `db/*conn*`
   (a plain `binding` does NOT survive Promise/await boundaries in
   CLJS), run `body` (0-arg, may return a Promise), restore after."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [prev db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body))
                     (.finally (fn [] (set! db/*conn* prev)))))))))

(defn- run-test [body done]
  (-> (with-conn body)
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

;; ---------------------------------------------------------------------------
;; Stub LLM responses + the call harness.
;; ---------------------------------------------------------------------------

(defn- transport-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg        "DeepSeek fetch failed: fetch failed"
                   :seon.ai/transport? true}})

(defn- http-400-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg    "DeepSeek HTTP 400: bad request"
                   :seon.ai/status 400}})

(defn- rate-limit-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg    "DeepSeek HTTP 429: rate limited"
                   :seon.ai/status 429}})

(defn- adapter-timeout-failure []
  {:text ""
   :seon.ai/error {:seon.ai/msg "OpenAI-compatible request timed out"
                   :seon.ai/timeout? true}})

(defn- counting-llm-fn
  "An llm-fn stub returning the responses in `resps` in order (last one
   repeats). `!calls` counts invocations."
  [!calls resps]
  (fn [_ctx]
    (let [n (swap! !calls inc)]
      (js/Promise.resolve (nth resps (dec n) (last resps))))))

(defn- ^:async ask!
  "Seed the agent row (optionally capping `:seon.ai/agent-max-retries`),
   then run one `ask-and-eval!` turn body with the stub `llm-fn`."
  [agent-id llm-fn max-retries]
  (let [env (await
              (db/transact!
                {:seon.db/tx-data
                 [(cond-> {:seon.agent/id agent-id}
                    max-retries
                    (assoc :seon.ai/agent-max-retries max-retries))]}))]
    (when-not (:seon.db/ok? env)
      (throw (ex-info "agent-retry-test: seed transact failed" env)))
    (await
      (turn/open-turn!
        {:seon.agent/id agent-id
         :seon.agent.turn/prompt-text "ctx"}
        (fn ^:async run-test-turn! [turn-id]
          (await
            (turn/ask-and-eval!
              {:seon.agent/id              agent-id
               :seon.agent/llm-fn          llm-fn
               :seon.agent/compile-state   nil
               :seon.agent.turn/id-of-turn turn-id
               :seon.agent.turn/turn-idx   1
               :seon.agent.turn/prompt-text "ctx"})))))))

(defn- persisted-attempts
  [turn-id]
  (->> (:seon.agent.turn/llm-attempts
         (db/pull {:seon.db/pull-pattern
                   '[{:seon.agent.turn/llm-attempts [*]}]
                   :seon.db/ref [:seon.agent.turn/id turn-id]}))
       (sort-by :seon.ai.attempt/ordinal)
       vec))

;; ---------------------------------------------------------------------------
;; The pins.
;; ---------------------------------------------------------------------------

(deftest transport-error-retries-to-the-cap-then-fails-honestly
  (async done
    (run-test
      (fn ^:async run []
        (let [!calls (atom 0)
              ;; cap the retry budget at 1 via the agent's own
              ;; :seon.ai/agent-max-retries datom — the per-agent knob
              ;; call-llm! actually reads (ai/agent-max-retries).
              result (await (ask! "AGTretrycap001"
                                  (counting-llm-fn !calls [(transport-failure)])
                                  1))]
          (testing "retries stop at the agent's cap — never a loop"
            (is (= 2 @!calls) "1 attempt + the capped 1 retry"))
          (testing "the turn body reports :error when the retry fails"
            (is (= :error (:seon.agent.turn/status result)))
            (is (= 0 (:seon.agent/eval-count result))))
          (testing "the retry is on the record"
            (is (= 1 (:seon.agent.turn/llm-retries result))))
          (testing "the failure is captured as data on the turn"
            (is (string? (:seon.agent.turn/error result)))
            (is (str/includes? (:seon.agent.turn/error result)
                               "fetch failed")))
          (let [attempts (persisted-attempts (:seon.agent.turn/id result))]
            (is (= [0 1] (mapv :seon.ai.attempt/ordinal attempts)))
            (is (= [:provider-error :provider-error]
                   (mapv :seon.ai.attempt/outcome attempts)))
            (is (every? false? (map :seon.ai.attempt/stream? attempts)))
            (is (every? #(= :openai-compat (:seon.ai.attempt/adapter %))
                        attempts)))))
      done)))

(deftest http-4xx-error-never-retries
  (async done
    (run-test
      (fn ^:async run []
        (let [!calls (atom 0)
              result (await (ask! "AGTretry400001"
                                  (counting-llm-fn !calls [(http-400-failure)])
                                  nil))]
          (is (= 1 @!calls)
              "HTTP 4xx (other than 429) is not transient — one call only")
          (is (= :error (:seon.agent.turn/status result)))
          (is (not (contains? result :seon.agent.turn/llm-retries))
              "optional = absent — no retry happened, no key stored")
          (let [[attempt] (persisted-attempts (:seon.agent.turn/id result))]
            (is (= :provider-error (:seon.ai.attempt/outcome attempt)))
            (is (= 400 (:seon.ai.attempt/error-status attempt)))
            (is (= 0 (:seon.ai.attempt/ordinal attempt))))))
      done)))

(deftest transport-error-then-recovery-records-the-retry
  (async done
    (run-test
      (fn ^:async run []
        ;; blank reply on recovery — the success path with ZERO forms, so
        ;; nothing evals; compile-state nil is never touched.
        (let [!calls (atom 0)
              result (await (ask! "AGTretryrec001"
                                  (counting-llm-fn
                                    !calls
                                    [(transport-failure)
                                     {:text ""
                                      :seon.ai/raw
                                      {:seon.ai/response-model "response-model"
                                       :seon.ai/system-fingerprint "fp-1"
                                       :seon.ai/request-id "req-1"}}])
                                  nil))]
          (is (= 2 @!calls))
          (testing "recovered turn is NOT an error"
            (is (nil? (:seon.agent.turn/status result))
                "no :error status — open-turn! closes it :done"))
          (testing "the recovery still records the retry honestly"
            (is (= 1 (:seon.agent.turn/llm-retries result))))
          (let [attempts (persisted-attempts (:seon.agent.turn/id result))]
            (is (= [0 1] (mapv :seon.ai.attempt/ordinal attempts)))
            (is (= [:provider-error :success]
                   (mapv :seon.ai.attempt/outcome attempts)))
            (is (= "response-model"
                   (:seon.ai.attempt/response-model (second attempts))))
            (is (= "fp-1"
                   (:seon.ai.attempt/system-fingerprint (second attempts))))
            (is (= "req-1"
                   (:seon.ai.attempt/request-id (second attempts)))))))
      done)))

(deftest retry-persists-ordered-immutable-config-drift
  (async done
    (run-test
      (fn ^:async run []
        (let [seed
              (await
                (db/transact!
                  {:seon.db/tx-data
                   [{:seon.ai/id "config"
                     :seon.ai/provider :openai-compat
                     :seon.ai/model "model-a"
                     :seon.ai/temperature 0.0
                     :seon.ai/base-url "https://user:secret@a.example/v1?sig=hide#frag"
                     :seon.ai/timeout-ms 1111}]}))
              _ (is (true? (:seon.db/ok? seed)))
              !calls (atom 0)
              llm-fn
              (fn ^:async retry-with-drift [arg]
                (let [n (swap! !calls inc)]
                  (if (= 1 n)
                    (let [changed
                          (await
                            (db/transact!
                              {:seon.db/tx-data
                               [{:seon.ai/id "config"
                                 :seon.ai/model "model-b"
                                 :seon.ai/base-url "https://b.example/v1"
                                 :seon.ai/timeout-ms 2222}]}))]
                      (is (true? (:seon.db/ok? changed)))
                      (transport-failure))
                    {:text ""
                     :seon.ai/raw
                     {:seon.ai/config-evidence
                      (ai/config-evidence (:seon.ai/config-resolution arg))}})))
              result (await (ask! "AGTretrydrift1" llm-fn 1))
              attempts (persisted-attempts (:seon.agent.turn/id result))]
          (is (= [0 1] (mapv :seon.ai.attempt/ordinal attempts)))
          (is (= ["model-a" "model-b"]
                 (mapv :seon.ai.attempt/requested-model attempts)))
          (is (= [1111 2222]
                 (mapv :seon.ai.attempt/adapter-timeout-ms attempts)))
          (is (= [0.0 0.0]
                 (mapv :seon.ai.attempt/temperature attempts))
              "zero is retained as a real sampling value, not absence")
          (is (= ["https://a.example/v1/chat/completions"
                  "https://b.example/v1/chat/completions"]
                 (mapv :seon.ai.attempt/endpoint attempts)))
          (is (not (str/includes? (pr-str attempts) "user:secret")))
          (is (not (str/includes? (pr-str attempts) "sig=hide")))
          (is (not= (:seon.ai.attempt/commit-id (first attempts))
                    (:seon.ai.attempt/commit-id (second attempts))))))
      done)))

(deftest rate-limit-429-is-transient-and-retried
  (async done
    (run-test
      (fn ^:async run []
        (let [!calls (atom 0)
              result (await (ask! "AGTretry429001"
                                  (counting-llm-fn
                                    !calls [(rate-limit-failure) {:text ""}])
                                  nil))]
          (is (= 2 @!calls) "429 is rate-limit-shaped — retried like transport")
          (is (nil? (:seon.agent.turn/status result)))
          (is (= 1 (:seon.agent.turn/llm-retries result)))))
      done)))

(deftest adapter-timeout-is-distinct-from-the-outer-attempt-cap
  (async done
    (run-test
      (fn ^:async run []
        (let [result (await (ask! "AGTadapttime01"
                                  (counting-llm-fn
                                    (atom 0) [(adapter-timeout-failure)])
                                  1))
              [attempt] (persisted-attempts (:seon.agent.turn/id result))]
          (is (= :error (:seon.agent.turn/status result)))
          (is (= :adapter-timeout (:seon.ai.attempt/outcome attempt)))
          (is (int? (:seon.ai.attempt/adapter-timeout-ms attempt)))
          (is (int? (:seon.ai.attempt/outer-timeout-ms attempt)))))
      done)))

(deftest provider-evidence-error-persists-as-a-bounded-attempt-fact
  (async done
    (run-test
      (fn ^:async run []
        (let [message
              "Provider response identity is invalid or exceeds the evidence bound."
              result
              (await
                (ask! "AGTeviderror01"
                      (counting-llm-fn
                        (atom 0)
                        [{:text ""
                          :seon.ai/error
                          {:seon.ai/msg "response identity validation failed"
                           :seon.ai/evidence-error message}}])
                      0))
              [attempt] (persisted-attempts (:seon.agent.turn/id result))]
          (is (= :provider-error (:seon.ai.attempt/outcome attempt)))
          (is (= message (:seon.ai.attempt/evidence-error attempt)))
          (is (< (count (:seon.ai.attempt/evidence-error attempt)) 512))))
      done)))

(deftest attempt-timeout-aborts-provider-and-never-retries
  (async done
    (let [env   (.. js/process -env)
          saved (aget env "SEON_LLM_ATTEMPT_TIMEOUT_MS")
          !calls (atom 0)
          !signals (atom [])
          !aborted (atom 0)
          llm-fn (fn [arg]
                   (let [signal (:seon.ai/abort-signal arg)]
                     (swap! !calls inc)
                     (swap! !signals conj signal)
                     (.addEventListener signal "abort"
                       (fn [] (swap! !aborted inc))
                       #js{:once true})
                     (js/Promise. (fn [_ _]))))]
      (aset env "SEON_LLM_ATTEMPT_TIMEOUT_MS" "30")
      (-> (with-conn
            (fn ^:async run []
              (let [result (await (ask! "AGTretryabort1" llm-fn 3))]
                (is (= 1 @!calls) "timeout is nonretryable")
                (is (= 1 @!aborted) "the provider signal was actively aborted")
                (is (= 1 (count @!signals)))
                (is (true? (.-aborted (first @!signals))))
                (is (= :error (:seon.agent.turn/status result)))
                (is (str/includes? (:seon.agent.turn/error result)
                                   "provider request cancelled"))
                (is (not (contains? result :seon.agent.turn/llm-retries)))
                (let [[attempt]
                      (persisted-attempts (:seon.agent.turn/id result))]
                  (is (= :outer-timeout (:seon.ai.attempt/outcome attempt)))
                  (is (= 30 (:seon.ai.attempt/outer-timeout-ms attempt)))
                  (is (uuid? (:seon.ai.attempt/commit-id attempt)))))))
          (.finally (fn []
                      (if (some? saved)
                        (aset env "SEON_LLM_ATTEMPT_TIMEOUT_MS" saved)
                        (js-delete env "SEON_LLM_ATTEMPT_TIMEOUT_MS"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
