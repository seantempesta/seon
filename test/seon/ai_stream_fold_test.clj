(ns seon.ai-stream-fold-test
  "Streamed replies: the fold, the wire, and the two exercise blocks.

  The surviving half of the streaming suite after F2 deleted the
  database half whole (`seon.ai.stream`, its schema file, and its five
  attribute tests died with their mechanism): what remains is
  `seon.ai`'s pure wire fold, the real-socket SSE transport, and the
  claim the exercises exist to test — now stronger, because the
  highest-churn thing in the system needs no render machinery AND no
  facts. The zero-datom and never-parked oracles live in the sealed F2
  transport suite.

  NO NETWORK. The wire is exercised against a real http-kit server
  serving CANNED SSE, so the transport branch is genuinely tested —
  status handling, incremental reads, natural EOF — without a provider,
  a key, or a bill.

  THE INVARIANT EVERYTHING HERE DEFENDS, quoted from the maintained
  reference because it is precisely what a partial-display feature
  always breaks: \"Partial display is presentation-only and cannot
  affect transport, parsing, usage, or evaluation.\" So the suite asks,
  repeatedly and from different directions: can presentation change the
  answer? A throwing sink, a slow sink, no sink at all — same
  completion."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [org.httpkit.server :as http]
            [seon.ai :as ai]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.root :as root]
            [seon.test-support :as support]))

;;; ---------------------------------------------------------------------------
;;; Canned wire
;;; ---------------------------------------------------------------------------

(defn- content-chunk
  [text]
  (str "data: {\"choices\":[{\"delta\":{\"content\":" (pr-str text) "}}]}"))

(def ^:private caps
  "The one cap set every surfaces request carries (context-blocks seal)."
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(def ^:private usage-chunk
  "The OpenAI-compatible shape: a final choices-empty chunk with usage."
  "data: {\"choices\":[],\"usage\":{\"completion_tokens\":7,\"prompt_tokens\":3}}")

(def ^:private gemini-chunk
  "Gemini attaches CUMULATIVE usage to a content chunk and never sends a
  usage-only one. The portable fold must retain usage independently of
  choices, which is the only reason this shape is in the suite."
  (str "data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}],"
       "\"usage\":{\"completion_tokens\":9}}"))

(def ^:private lines
  [":keep-alive comment"
   ""
   (content-chunk "Hello")
   (content-chunk ", ")
   (content-chunk "world")
   usage-chunk
   "data: [DONE]"])

;;; ---------------------------------------------------------------------------
;;; The fold
;;; ---------------------------------------------------------------------------

(deftest the-fold-accumulates-text-and-retains-the-newest-usage
  (let [snapshot (ai/stream-fold lines nil)]
    (is (= "Hello, world" (:seon.ai/text snapshot)))
    (is (= 7 (:seon.ai/tokens snapshot))
        "the provider's own completion_tokens, once it has told us")
    (is (= 3 (get (:seon.ai/usage snapshot) "prompt_tokens")))))

(deftest usage-is-retained-independently-of-choices
  ;; Two provider shapes, one fold, and neither assumed.
  (testing "OpenAI-compatible: a final choices-empty usage chunk"
    (is (= 7 (:seon.ai/tokens (ai/stream-fold lines nil)))))
  (testing "Gemini: cumulative usage riding a content chunk"
    (let [snapshot (ai/stream-fold [(content-chunk "hi") gemini-chunk] nil)]
      (is (= "hi!" (:seon.ai/text snapshot)))
      (is (= 9 (:seon.ai/tokens snapshot))))))

(deftest the-token-count-falls-back-to-chunks-until-the-provider-says
  ;; Honest approximation, and named as one: before any usage arrives
  ;; the count is chunks, which is one token per chunk for every
  ;; provider we speak to.
  (let [snapshot (ai/stream-fold [(content-chunk "a") (content-chunk "b")] nil)]
    (is (= "ab" (:seon.ai/text snapshot)))
    (is (= 2 (:seon.ai/tokens snapshot)))))

(deftest unreadable-lines-are-skipped-and-never-fatal
  ;; A keep-alive comment, a blank line, or one malformed chunk has not
  ;; failed the call. Turning presentation noise into a call failure
  ;; would be the streaming path breaking its own invariant.
  (let [noisy (concat [":comment" "" "data: {not json" "garbage"]
                      lines
                      ["data: {\"choices\":[{\"delta\":{}}]}"])]
    (is (= "Hello, world" (:seon.ai/text (ai/stream-fold noisy nil))))))

(deftest the-fold-is-total-over-arbitrary-lines
  (support/assert-check!
   (tc/quick-check
    300
    (prop/for-all [text (gen/vector gen/string-ascii 0 12)]
      (map? (ai/stream-fold text nil)))
    :seed 202607280301)
   "fold totality failed:"))

;;; ---------------------------------------------------------------------------
;;; Presentation cannot affect the answer
;;; ---------------------------------------------------------------------------

(deftest a-sink-sees-monotonically-growing-complete-snapshots
  (let [seen (atom [])
        snapshot (ai/stream-fold lines (fn [p] (swap! seen conj (:seon.ai/text p))))]
    (is (= ["Hello" "Hello, " "Hello, world"] @seen)
        "complete values, never deltas — a consumer that missed one is
         briefly behind rather than permanently wrong")
    (is (= "Hello, world" (:seon.ai/text snapshot)))
    (testing "and it is not called for chunks that added no text"
      (is (= 3 (count @seen)) "the usage chunk and [DONE] are not partials"))))

(deftest a-throwing-sink-cannot-change-the-completion
  ;; THE invariant, from the direction that breaks it in practice.
  (let [with-sink (ai/stream-fold lines (fn [_] (throw (ex-info "broken" {}))))
        without (ai/stream-fold lines nil)]
    (is (= without with-sink))
    (is (= "Hello, world" (:seon.ai/text with-sink)))))

;;; ---------------------------------------------------------------------------
;;; The wire, against a real server serving canned SSE
;;; ---------------------------------------------------------------------------

(defn- with-provider
  [{:keys [status body]} run]
  (let [server (http/run-server
                (fn [_request]
                  {:status (or status 200)
                   :headers {"content-type" "text/event-stream"}
                   :body (or body (str/join "\n" lines))})
                {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try
      (run (str "http://127.0.0.1:" (http/server-port server) "/v1/chat/completions"))
      (finally (http/server-stop! server)))))

(defn- request
  [endpoint extra]
  (merge {:seon.ai/endpoint endpoint
          :seon.ai/model "fixture"
          :seon.ai/api-key-variable "PATH"
          :seon.ai/prompt "hello"
          :seon.ai/timeout-ms 5000}
         extra))

(deftest a-streamed-call-returns-the-same-shape-as-a-one-shot-call
  ;; Downstream — the disposition, the attempt facts, the loop — must
  ;; not be able to tell which transport ran.
  (with-provider {}
    (fn [endpoint]
      (let [completion (ai/complete (request endpoint {:seon.ai/stream? true}))]
        (is (nil? (:seon.error/kind completion)))
        (is (= "Hello, world" (:seon.ai/text completion)))
        (is (= 7 (:seon.ai/tokens completion)))
        (is (= 3 (get (:seon.ai/usage completion) "prompt_tokens")))))))

(deftest the-request-body-asks-for-a-stream-and-for-usage-on-it
  (let [streamed (ai/request-body (request "x" {:seon.ai/stream? true}))
        one-shot (ai/request-body (request "x" {}))]
    (is (true? (get streamed "stream")))
    (is (= {"include_usage" true} (get streamed "stream_options")))
    (is (false? (get one-shot "stream")))
    (is (nil? (get one-shot "stream_options"))
        "a non-streaming request must not carry streaming options")))

(deftest a-stream-that-produces-no-text-is-an-error-not-an-empty-reply
  ;; A provider that streamed nothing has failed the call however
  ;; cleanly it closed the socket.
  (with-provider {:body "data: [DONE]\n"}
    (fn [endpoint]
      (let [completion (ai/complete (request endpoint {:seon.ai/stream? true}))]
        (is (= :seon.ai/unparseable-body (:seon.error/kind completion)))))))

(deftest a-non-2xx-streaming-response-still-reads-its-body
  ;; The error path has to work when the body handler changed under it.
  (with-provider {:status 429 :body "slow down"}
    (fn [endpoint]
      (let [completion (ai/complete (request endpoint {:seon.ai/stream? true}))]
        (is (= :seon.ai/provider-error (:seon.error/kind completion)))
        (is (= 429 (:seon.ai/http-status (:seon.error/data completion))))
        (is (str/includes? (str (:seon.ai/body (:seon.error/data completion)))
                           "slow down"))))))

(deftest a-broken-sink-cannot-fail-a-real-call
  (with-provider {}
    (fn [endpoint]
      (let [completion (ai/complete
                        (request endpoint {:seon.ai/stream? true
                                           :seon.ai/sink
                                           (fn [_] (throw (ex-info "no" {})))}))]
        (is (= "Hello, world" (:seon.ai/text completion)))))))

;;; ---------------------------------------------------------------------------
;;; The two exercise blocks — RE-GROUNDED (F2 §2.2)
;;; ---------------------------------------------------------------------------

(def ^:private agent-id "root")

(deftest the-exercises-are-ordinary-blocks
  ;; The claim the exercises exist to test, unchanged and now stronger:
  ;; the highest-churn thing in the system needs no render machinery of
  ;; its own AND no facts. The blocks read the TRANSIENT
  ;; `:seon.ai/partial` the render pass threads through the one unit
  ;; builder — never a query, because there is no row.
  (support/with-database
    (fn [connection]
      (d/transact
       connection
       [{:seon.cluster.agent/id agent-id
         :seon.cluster.agent/blocks
         [{:seon.render.block/name :tokens :seon.render.block/priority 0
           :seon.render/html `root/tokens-html}
          {:seon.render.block/name :reply :seon.render.block/priority 10
           :seon.render/html `root/text-html}]}])
      (testing "before anything streams, they render idle rather than absent"
        (let [surfaces (block/surfaces @connection
                                       {:seon.cluster.agent/id agent-id
                                        :seon.render/kind :seon.render/html
                                        :seon.sci.admit/caps caps})]
          (is (= 2 (count surfaces)))
          (is (every? (comp nil? :seon.error/value) surfaces))
          (is (str/includes? (hiccup/->string (:seon.render/output (second surfaces)))
                             "idle"))))
      (let [surfaces (block/surfaces @connection
                                     {:seon.cluster.agent/id agent-id
                                      :seon.render/kind :seon.render/html
                                      :seon.sci.admit/caps caps
                                      :seon.ai/partial
                                      {:seon.ai/text "streaming now"
                                       :seon.ai/tokens 42}})
            html (str/join (map (comp hiccup/->string :seon.render/output)
                                surfaces))]
        (is (str/includes? html "42") "the live count")
        (is (str/includes? html "streaming now") "the streamed text")
        (is (str/includes? html "surface-tokens"))
        (is (str/includes? html "surface-reply")
            "each exercise is its own morph target, like every block")))))
