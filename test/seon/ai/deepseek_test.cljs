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

(defn- with-stubbed-fetch
  "Run `body` (0-arg → Promise) with `js/fetch` replaced by `stub` and
   a test DEEPSEEK_API_KEY; restore both after. Returns a Promise."
  [stub body]
  (let [orig-fetch js/fetch
        orig-key   (.. js/process -env -DEEPSEEK_API_KEY)]
    (set! (.. js/process -env -DEEPSEEK_API_KEY) "test-key")
    (set! js/fetch stub)
    (-> (js/Promise.resolve (body))
        (.finally (fn []
                    (set! js/fetch orig-fetch)
                    (if orig-key
                      (set! (.. js/process -env -DEEPSEEK_API_KEY) orig-key)
                      (js-delete (.-env js/process) "DEEPSEEK_API_KEY")))))))

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
