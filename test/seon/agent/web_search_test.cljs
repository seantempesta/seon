(ns seon.agent.web-search-test
  "Contract tests for `seon.agent.web/search` — the grounded web-search verb.

   Two layers, both hermetic (NO live API, no network):

   1. `parse-grounding` (PURE) — a keywordized Gemini grounding body →
      backend-agnostic rows (from groundingChunks), per-row snippets
      (joined from the groundingSupports segments citing that chunk), the
      grounded ::answer, the executed ::queries, and an HONEST pre-cap
      ::result-count that a max-results cap does not lie about.
   2. The verb envelope — every outcome RESOLVES to a search-response
      (errors are values): ungranted (SEON_WEB default-deny), no backend
      key (GEMINI_API_KEY absent), an unwired backend, and the success
      path assembled through the int/!gemini-impl test seam.

   The gemini transport is FAKED via int/!gemini-impl; SEON_WEB /
   GEMINI_API_KEY are set per test and restored; the backend is pinned via
   int/!search-config-override. A fresh :memory conn is root-set! as
   db/*conn* so the best-effort projection tx has somewhere to land."
  (:require
    [cljs.test :refer [deftest is testing async use-fixtures]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.web :as web]
    [seon.agent.web.internal :as int]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]))

;; ---------------------------------------------------------------------------
;; Fixture — the REAL grounding shape (captured from a live gemini-3.1-flash-lite
;; probe 2026-07-06: "current stable Clojure version"). Two chunks, one support
;; citing BOTH (groundingChunkIndices [0 1]), a synthesized answer.
;; ---------------------------------------------------------------------------

(def ^:private grounding-body
  {:candidates
   [{:content
     {:parts [{:text "The current stable version of Clojure is 1.12.5, released on May 12, 2026."}]}
     :groundingMetadata
     {:webSearchQueries ["current stable Clojure version"]
      :groundingChunks
      [{:web {:uri   "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA"
              :title "clojure.org"}}
       {:web {:uri   "https://vertexaisearch.cloud.google.com/grounding-api-redirect/BBB"
              :title "github.com"}}]
      :groundingSupports
      [{:segment {:startIndex 0 :endIndex 77
                  :text "The current stable version of Clojure is 1.12.5"}
        :groundingChunkIndices [0 1]}]}}]})

;; ---------------------------------------------------------------------------
;; Env + conn fixtures.
;; ---------------------------------------------------------------------------

(defonce ^:private !saved-web (atom nil))
(defonce ^:private !saved-key (atom nil))

(defn- set-env! [k v]
  (if (some? v) (aset (.-env js/process) k v) (js-delete (.-env js/process) k)))

(use-fixtures :once
  {:before (fn []
             (reset! !saved-web (aget (.-env js/process) "SEON_WEB"))
             (reset! !saved-key (aget (.-env js/process) "GEMINI_API_KEY"))
             (set-env! "SEON_WEB" "1")
             (set-env! "GEMINI_API_KEY" "test-key-not-real")
             (reset! int/!search-config-override
                     {:seon.agent.web/search-backend :gemini-grounding
                      :seon.agent.web/search-model   "gemini-3.1-flash-lite"}))
   :after  (fn []
             (set-env! "SEON_WEB" @!saved-web)
             (set-env! "GEMINI_API_KEY" @!saved-key)
             (reset! int/!search-config-override nil))})

(use-fixtures :each
  {:before (fn []
             ;; each test starts from the granted/keyed/gemini baseline
             (set-env! "SEON_WEB" "1")
             (set-env! "GEMINI_API_KEY" "test-key-not-real")
             (reset! int/!search-config-override
                     {:seon.agent.web/search-backend :gemini-grounding
                      :seon.agent.web/search-model   "gemini-3.1-flash-lite"}))
   :after  (fn [] (reset! int/!gemini-impl nil))})

(defn- fresh-conn []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn {:tx-data (into (db/malli->datahike-schema
                                                         client/agent-bootstrap-attrs)
                                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))

(defn- run-test [chain done]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (chain conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

;; A faked gemini backend resolving the fixture body as the ok transport shape.
(defn- fake-gemini [body]
  (fn [_q _model _key _timeout]
    (js/Promise.resolve {:seon.agent.web/ok? true :seon.agent.web/body body})))

;; ===========================================================================
;; 1. parse-grounding — PURE.
;; ===========================================================================

(deftest parse-grounding-derives-rows-answer-queries
  (let [{rows  :seon.agent.web/results
         total :seon.agent.web/result-count
         ans   :seon.agent.web/answer
         qs    :seon.agent.web/queries}
        (int/parse-grounding grounding-body 10)]
    (is (= 2 total) "honest pre-cap total = chunks with a url")
    (is (= 2 (count rows)))
    (is (= ["current stable Clojure version"] qs) "executed queries carried")
    (is (str/includes? ans "1.12.5") "the synthesized answer is the candidate text")
    (testing "row 0"
      (let [r0 (first rows)]
        (is (= "https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA"
               (:seon.agent.web/url r0)))
        (is (= "clojure.org" (:seon.agent.web/title r0)))
        (is (= 0 (:seon.agent.web/rank r0)) "rank = 0-based chunk position")
        (is (str/includes? (:seon.agent.web/snippet r0) "1.12.5")
            "snippet joins the groundingSupports segment citing chunk 0")))
    (testing "row 1 also cited by the shared support"
      (let [r1 (second rows)]
        (is (= 1 (:seon.agent.web/rank r1)))
        (is (str/includes? (:seon.agent.web/snippet r1) "1.12.5")
            "a support citing [0 1] attributes its segment to BOTH chunks")))))

(deftest parse-grounding-caps-rows-but-count-is-honest
  (let [{rows  :seon.agent.web/results
         total :seon.agent.web/result-count}
        (int/parse-grounding grounding-body 1)]
    (is (= 1 (count rows)) "results capped at max-results")
    (is (= 2 total) "result-count is the HONEST pre-cap total, never the cap")))

(deftest parse-grounding-tolerates-missing-metadata
  (testing "a body with no groundingMetadata degrades to empty rows, no throw"
    (let [{rows  :seon.agent.web/results
           total :seon.agent.web/result-count
           qs    :seon.agent.web/queries
           ans   :seon.agent.web/answer}
          (int/parse-grounding {:candidates [{:content {:parts [{:text "plain answer"}]}}]} 10)]
      (is (= [] rows))
      (is (= 0 total))
      (is (= [] qs))
      (is (= "plain answer" ans))))
  (testing "a chunk missing its snippet still yields a row (snippet optional)"
    (let [body {:candidates
                [{:groundingMetadata
                  {:groundingChunks [{:web {:uri "https://x.test/1" :title "x"}}]}}]}
          {rows :seon.agent.web/results} (int/parse-grounding body 10)]
      (is (= 1 (count rows)))
      (is (not (contains? (first rows) :seon.agent.web/snippet))
          "no support ⇒ no snippet key (absent, never nil/blank)"))))

;; ===========================================================================
;; 2. Verb envelope.
;; ===========================================================================

(deftest search-success-assembles-response
  (async done
    (reset! int/!gemini-impl (fake-gemini grounding-body))
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query "current stable Clojure version"})
            (.then (fn [{ok?   :seon.agent.web/ok?
                         q     :seon.agent.web/query
                         be    :seon.agent.web/backend
                         rows  :seon.agent.web/results
                         total :seon.agent.web/result-count
                         ans   :seon.agent.web/answer
                         atoks :seon.agent.web/answer-tokens
                         qs    :seon.agent.web/queries
                         hint  :seon.agent.web/hint}]
                     (is (true? ok?))
                     (is (= "current stable Clojure version" q))
                     (is (= :gemini-grounding be) "the response names its backend")
                     (is (= 2 (count rows)))
                     (is (= 2 total))
                     (is (= 0 (:seon.agent.web/rank (first rows))))
                     (is (str/includes? ans "1.12.5"))
                     (is (= (tokens/estimate ans) atoks) "answer-tokens is the token estimate, not chars")
                     (is (= ["current stable Clojure version"] qs))
                     (is (re-find #"(?i)redirect" hint) "the redirect-URI hint is standing")))))
      done)))

(deftest search-honors-max-results-cap
  (async done
    (reset! int/!gemini-impl (fake-gemini grounding-body))
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query       "clojure"
                         :seon.agent.web/max-results 1})
            (.then (fn [{rows  :seon.agent.web/results
                         total :seon.agent.web/result-count}]
                     (is (= 1 (count rows)) "results honor max-results")
                     (is (= 2 total) "result-count stays the honest pre-cap total")))))
      done)))

(deftest search-ungranted-is-an-error-value
  (async done
    (set-env! "SEON_WEB" "0")
    (reset! int/!gemini-impl (fn [& _] (throw (js/Error. "backend must not run when ungranted"))))
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query "anything"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         q   :seon.agent.web/query
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (= "anything" q) "the error arm echoes the query")
                     (is (re-find #"(?i)SEON_WEB|not granted" msg) "names the grant")))))
      done)))

(deftest search-no-key-is-an-error-value
  (async done
    (set-env! "GEMINI_API_KEY" nil)
    (reset! int/!gemini-impl (fn [& _] (throw (js/Error. "backend must not run with no key"))))
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query "anything"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (re-find #"(?i)GEMINI_API_KEY|no search backend key" msg)
                         "names the missing key")))))
      done)))

(deftest search-blank-query-is-an-error-value
  (async done
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query "   "})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (re-find #"(?i)query" msg) "names the required query")))))
      done)))

(deftest search-unwired-backend-refuses-legibly
  (async done
    (reset! int/!search-config-override
            {:seon.agent.web/search-backend :serper
             :seon.agent.web/search-model   "n/a"})
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query "anything"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (re-find #"(?i)serper|not wired" msg) "names the unwired backend")))))
      done)))

(deftest search-backend-transport-error-passes-through
  (async done
    (reset! int/!gemini-impl
            (fn [q & _] (js/Promise.resolve (int/search-err q "gemini grounding HTTP 429 — quota"))))
    (run-test
      (fn [_]
        (-> (web/search {:seon.agent.web/query "anything"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?) "a backend HTTP/quota failure surfaces as the error value")
                     (is (str/includes? msg "429") "the provider status/message rides through")))))
      done)))

;; ===========================================================================
;; 3. grants surfaces the effective search backend.
;; ===========================================================================

(deftest grants-surfaces-search-backend
  (testing "keyed + configured ⇒ the backend"
    (set-env! "GEMINI_API_KEY" "test-key-not-real")
    (is (= :gemini-grounding (:seon.agent.web/search-backend (web/grants)))))
  (testing "no key ⇒ :none (no search can run)"
    (set-env! "GEMINI_API_KEY" nil)
    (is (= :none (:seon.agent.web/search-backend (web/grants))))
    (set-env! "GEMINI_API_KEY" "test-key-not-real")))
