(ns seon.ai.anthropic-test
  "Tests for the Anthropic Messages API client's pure surface (C-20):
     - request-body defaults: model claude-opus-4-8, max_tokens 16000,
       system TOP-LEVEL (:system, not a messages entry) as a
       content-block ARRAY with cache_control {:type \"ephemeral\"}
       breakpoints; a ctx carrying seon.ctx's in-band stable-boundary
       (task #34) splits — system = [soul block, stable-ctx block],
       BOTH with breakpoints, messages = volatile tail ONLY; a
       boundary-less ctx degrades to the pre-split shape (one system
       block, full ctx as the user msg, NO message cache_control);
       the deepseek body is UNCHANGED by the caching change (plain
       string system message, no cache_control anywhere)
     - thinking is ADAPTIVE-ONLY: config row truthy → {:type
       \"adaptive\"}; falsy → the :thinking key is ABSENT (an explicit
       {:type \"disabled\"} 400s on Fable)
     - sampling params NEVER sent: no :temperature/:top_p/:top_k even
       when the config row carries :seon.ai/temperature (it is
       deepseek-only — sampling 400s on Opus 4.7+/Fable)
     - response parsing: content is an ARRAY of typed blocks — text
       blocks joined, thinking blocks skipped; stop_reason checked
       BEFORE content (\"refusal\" → legible :seon.ai/error envelope)

   The actual HTTP path is proven live against the real API — see the
   C-18+C-20 unit report (two bounded calls, claude-opus-4-8)."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.ai :as ai]
    [seon.ai.anthropic :as anthropic]
    [seon.ai.deepseek :as deepseek]
    [seon.ctx :as ctx]
    [seon.db :as db]))

;; ============================================================
;; Conn helpers — same pattern as seon.ai.deepseek-test.
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
;; Request body — pinned API shape.
;; ============================================================

(deftest request-body-default-shape
  (async done
    (-> (with-conn
          (fn [_conn]
            (let [body (anthropic/request-body {:seon.ai/ctx           "the ctx"
                                                :seon.ai/system-prompt "sys"})]
              (is (= {:model      "claude-opus-4-8"
                      :max_tokens 16000
                      :system     [{:type "text"
                                    :text "sys"
                                    :cache_control {:type "ephemeral"}}]
                      :messages   [{:role "user" :content "the ctx"}]}
                     body)
                  (str "no env, no row → exactly the pinned default body: "
                       "opus-4-8, 16000 max_tokens, top-level :system as a "
                       "block array with cache_control on its only block, one "
                       "user message, NO :thinking key, NO sampling params")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest request-body-never-sends-sampling-params
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; Even a config row carrying temperature (deepseek-only)
            ;; must NOT leak into the anthropic body — 400 on Fable.
            (-> (db/transact!
                  {:seon.db/tx-data [{::ai/id "config" ::ai/temperature 0.3}]})
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?))
                         (let [body (anthropic/request-body {:seon.ai/ctx "hi"})]
                           (is (not (contains? body :temperature))
                               "temperature MUST NOT be sent — 400s on Opus 4.7+/Fable")
                           (is (not (contains? body :top_p)))
                           (is (not (contains? body :top_k)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest thinking-adaptive-or-omitted
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; Falsy (absent row) → :thinking key ABSENT.
            (is (not (contains? (anthropic/request-body {:seon.ai/ctx "hi"})
                                :thinking))
                "thinking off → OMIT the key entirely (never {:type \"disabled\"})")
            (-> (db/transact!
                  {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "true"}]})
                (.then (fn [_]
                         (is (= {:type "adaptive"}
                                (:thinking (anthropic/request-body {:seon.ai/ctx "hi"})))
                             "thinking \"true\" → adaptive (the only on-mode)")
                         ;; An effort string is also just truthy → adaptive
                         ;; (reasoning-effort levels are a deepseek wire
                         ;; concept; never sent here).
                         (db/transact!
                           {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "high"}]})))
                (.then (fn [_]
                         (let [body (anthropic/request-body {:seon.ai/ctx "hi"})]
                           (is (= {:type "adaptive"} (:thinking body)))
                           (is (not (contains? body :reasoning_effort))))
                         (db/transact!
                           {:seon.db/tx-data [{::ai/id "config" ::ai/thinking "false"}]})))
                (.then (fn [_]
                         (is (not (contains? (anthropic/request-body {:seon.ai/ctx "hi"})
                                             :thinking))
                             "\"false\" → back to omitted"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest config-row-model-and-max-tokens-apply
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact!
                  {:seon.db/tx-data [{::ai/id         "config"
                                      ::ai/model      "claude-fable-5"
                                      ::ai/max-tokens 2048}]})
                (.then (fn [_]
                         (let [body (anthropic/request-body {:seon.ai/ctx "hi"})]
                           (is (= "claude-fable-5" (:model body)))
                           (is (= 2048 (:max_tokens body))))
                         ;; Explicit request opts win over the row.
                         (let [body (anthropic/request-body
                                      {:seon.ai/ctx        "hi"
                                       :seon.ai/model      "claude-sonnet-4-6"
                                       :seon.ai/max-tokens 256})]
                           (is (= "claude-sonnet-4-6" (:model body)))
                           (is (= 256 (:max_tokens body)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; Prompt caching (task #34) — the ctx's in-band stable boundary
;; splits the wire body: system = [soul block, stable-ctx block],
;; BOTH cache_control breakpoints (2 of the allowed 4); messages =
;; the volatile tail ONLY. A boundary-less ctx (tests, stub prompts)
;; degrades to the pre-split shape. Wire-shape pins, no live call.
;; ============================================================

(deftest cache-control-on-system-block-only
  (async done
    (-> (with-conn
          (fn [_conn]
            (let [body (anthropic/request-body {:seon.ai/ctx           "the ctx"
                                                :seon.ai/system-prompt "sys"})
                  [sys-block & more] (:system body)]
              (is (vector? (:system body))
                  "system is a content-block ARRAY (a bare string can't carry a breakpoint)")
              (is (nil? more) "boundary-less ctx → exactly ONE system block")
              (is (= {:type "ephemeral"} (:cache_control sys-block))
                  "the last/only system block is the cache breakpoint — caches tools+system")
              (is (= "sys" (:text sys-block)))
              (is (= [{:role "user" :content "the ctx"}] (:messages body))
                  (str "the user message carries NO cache_control — and a "
                       "boundary-less ctx rides through whole (pre-split "
                       "degradation)")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest stable-ctx-splits-into-second-system-block
  (async done
    (-> (with-conn
          (fn [_conn]
            (let [stable   "<system>core</system>\n\n<namespace name=\"seon.db\">…</namespace>"
                  volatile "<transcript>…</transcript>\n\nmy.agent.a=> "
                  full     (str stable "\n\n" ctx/stable-boundary "\n\n" volatile)
                  body     (anthropic/request-body
                             {:seon.ai/ctx full :seon.ai/system-prompt "sys"})
                  [soul-block stable-block & more] (:system body)]
              (is (nil? more) "exactly TWO system blocks on a split ctx")
              (is (= {:type "text" :text "sys"
                      :cache_control {:type "ephemeral"}}
                     soul-block)
                  "block 1 = the soul/system prompt, breakpoint kept")
              (is (= {:type "text" :text stable
                      :cache_control {:type "ephemeral"}}
                     stable-block)
                  (str "block 2 = the ctx's STABLE prefix with the LAST "
                       "breakpoint — caches tools+system+stable, the "
                       "boundary line itself is consumed by the split"))
              (is (= [{:role "user" :content volatile}] (:messages body))
                  "messages carry ONLY the volatile tail, no cache_control"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest blank-half-degrades-to-unsplit
  (async done
    (-> (with-conn
          (fn [_conn]
            ;; Pathological: boundary present but a blank half — never
            ;; send an empty system block or an empty user message.
            (let [full (str "" "\n\n" ctx/stable-boundary "\n\n" "tail only")
                  body (anthropic/request-body
                         {:seon.ai/ctx full :seon.ai/system-prompt "sys"})]
              (is (= 1 (count (:system body)))
                  "blank stable half → no second system block")
              (is (= [{:role "user" :content full}] (:messages body))
                  "…and the ctx rides through whole"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest deepseek-body-unchanged-by-anthropic-caching
  (async done
    (-> (with-conn
          (fn [_conn]
            (let [body (deepseek/request-body {:seon.ai/ctx           "the ctx"
                                               :seon.ai/system-prompt "sys"})]
              (is (= [{:role "system" :content "sys"}
                      {:role "user"   :content "the ctx"}]
                     (:messages body))
                  (str "deepseek keeps its plain-string system MESSAGE — "
                       "cache_control is Anthropic wire vocabulary "
                       "(deepseek's wire auto-caches)"))
              (is (not (contains? body :system))
                  "no top-level :system on the deepseek wire")
              (is (not (str/includes? (pr-str body) ":cache_control"))
                  "no cache_control anywhere in the deepseek body"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; Response parsing — typed content blocks + stop_reason gate.
;; ============================================================

(defn- json [m] (.stringify js/JSON (clj->js m)))

(deftest parse-response-extracts-text-blocks-skips-thinking
  (testing "text blocks joined, thinking blocks skipped"
    (let [resp (anthropic/parse-response
                 (json {:stop_reason "end_turn"
                        :content [{:type "thinking" :thinking "hmm" :signature "s"}
                                  {:type "text" :text "(+ 1 "}
                                  {:type "text" :text "2)"}]
                        :usage {:input_tokens 10 :output_tokens 5}}))]
      (is (= "(+ 1 2)" (:seon.ai/text resp)))
      (is (= "end_turn" (:seon.ai.anthropic/stop-reason resp)))
      (is (= {:input_tokens 10 :output_tokens 5} (:seon.ai/usage resp)))
      (is (not (contains? resp :seon.ai/error))))))

(deftest parse-response-refusal-is-a-legible-error
  (testing "stop_reason refusal (empty content) → error envelope, never a reply"
    (let [resp (anthropic/parse-response
                 (json {:stop_reason "refusal" :content [] :usage {}}))]
      (is (= "" (:seon.ai/text resp)))
      (is (= "refusal" (:seon.ai.anthropic/stop-reason resp)))
      (is (some? (:seon.ai/error resp)) "refusal MUST surface as an error")
      (is (re-find #"refusal" (:seon.ai/msg (:seon.ai/error resp)))))))

(deftest parse-response-garbage-is-an-error-with-raw-body
  (let [resp (anthropic/parse-response "not json {{{")]
    (is (= "" (:seon.ai/text resp)))
    (is (= "not json {{{" (:seon.ai/raw-body (:seon.ai/error resp))))))
