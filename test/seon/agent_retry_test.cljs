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
      (turn/ask-and-eval!
        {:seon.agent/id              agent-id
         :seon.agent/llm-fn          llm-fn
         :seon.agent/compile-state   nil      ; never touched: zero forms eval'd
         :seon.agent.turn/id-of-turn "TRNretrytest01"
         :seon.agent.turn/turn-idx   1
         :seon.agent.turn/prompt-text "ctx"}))))

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
                               "fetch failed")))))
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
              "optional = absent — no retry happened, no key stored")))
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
                                    !calls [(transport-failure) {:text ""}])
                                  nil))]
          (is (= 2 @!calls))
          (testing "recovered turn is NOT an error"
            (is (nil? (:seon.agent.turn/status result))
                "no :error status — open-turn! closes it :done"))
          (testing "the recovery still records the retry honestly"
            (is (= 1 (:seon.agent.turn/llm-retries result))))))
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
