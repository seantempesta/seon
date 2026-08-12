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
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [org.httpkit.server :as http]
            [seon.ai :as ai]
            [seon.blob :as blob]
            [seon.cluster.loop]
            [seon.test-support :as support])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Canned wire
;;; ---------------------------------------------------------------------------

(defn- content-chunk
  [text]
  (str "data: {\"choices\":[{\"delta\":{\"content\":" (pr-str text) "}}]}"))

(defn- reasoning-chunk
  [text]
  (str "data: {\"choices\":[{\"delta\":{\"reasoning_content\":"
       (pr-str text) "}}]}"))

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

(deftest the-fold-accumulates-reasoning-separately-from-visible-text
  (let [snapshot (ai/stream-fold [(reasoning-chunk "first\n")
                                  (reasoning-chunk "second")
                                  (content-chunk "reply")]
                                 nil)]
    (is (= "first\nsecond" (:seon.ai/reasoning-partial snapshot)))
    (is (= "reply" (:seon.ai/text snapshot)))))

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

(deftest presentation-noise-is-silent-but-malformed-data-refuses-the-stream
  (testing "comments, blank lines, non-data fields, and [DONE] lose no content"
    (let [noisy (concat [":comment" "" "garbage"]
                        lines
                        ["data: {\"choices\":[{\"delta\":{}}]}"])]
      (is (= "Hello, world" (:seon.ai/text (ai/stream-fold noisy nil))))))
  (testing "damaged content-bearing JSON cannot splice valid code around it"
    (let [content-line (content-chunk " unsafe")
          malformed-lines
          [(str/replace-first content-line "\"choices\"" "\"choices")
           "data: {not json"
           (subs content-line 0 (- (count content-line) 2))
           "data: {\"choices\":[{\"delta\":{\"content\":123}}]}"
           "data: {\"error\":{\"message\":\"provider failed\"}}"]
          prefix (content-chunk "(my.run/complete \"safe")
          suffix (content-chunk "\")")]
      (doseq [malformed malformed-lines]
        (let [result (ai/stream-fold [prefix malformed suffix] nil)]
          (is (= :seon.ai/unparseable-body (:seon.error/kind result))
              (str "malformed data must refuse the stream: " malformed))
          (is (= (subs malformed 6) (:seon.ai/body (:seon.error/data result))))
          (is (not (contains? result :seon.ai/text))
              "a partial reply must not retain the completion success shape")))))
  (testing "the parallel reasoning-content path refuses at the same fold"
    (let [result (ai/stream-fold [(reasoning-chunk "first")
                                  (str "data: {\"choices\":[{\"delta\":"
                                       "{\"reasoning_content\":123}}]}")
                                  (reasoning-chunk "second")
                                  (content-chunk "(my.run/complete :safe)")]
                                 nil)]
      (is (= :seon.ai/unparseable-body (:seon.error/kind result)))
      (is (not (contains? result :seon.ai/reasoning-partial))))))

(deftest the-fold-is-total-over-arbitrary-lines
  (support/assert-check!
   (tc/quick-check
    300
    (prop/for-all [text (gen/vector gen/string-ascii 0 12)]
      (map? (ai/stream-fold text nil)))
    :seed 202607280301)
   "fold totality failed:"))

(deftest one-shot-replies-refuse-present-malformed-assistant-fields
  (testing "a malformed reasoning field cannot hide behind valid executable text"
    (let [completion
          (ai/completion-text
           {"choices"
            [{"message" {"content" "(my.run/complete :safe)"
                          "reasoning_content" 123}}]})]
      (is (= :seon.ai/unparseable-body (:seon.error/kind completion)))
      (is (not (contains? completion :seon.ai/text)))))
  (testing "a provider error document cannot hide behind a completion"
    (let [completion
          (ai/completion-text
           {"error" {"message" "provider failed"}
            "choices" [{"message" {"content" "(my.run/complete :safe)"}}]})]
      (is (= :seon.ai/unparseable-body (:seon.error/kind completion)))
      (is (not (contains? completion :seon.ai/text))))))

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

(deftest a-sink-publishes-when-either-reasoning-or-visible-text-changes
  (let [seen (atom [])]
    (ai/stream-fold [(reasoning-chunk "think")
                     usage-chunk
                     (content-chunk "act")]
                    #(swap! seen conj %))
    (is (= [{:seon.ai/text ""
             :seon.ai/reasoning-partial "think"
             :seon.ai/tokens 1}
            {:seon.ai/text "act"
             :seon.ai/reasoning-partial "think"
             :seon.ai/tokens 8
             :seon.ai/usage {"completion_tokens" 7
                             "prompt_tokens" 3}}]
           @seen))))

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
  [{:keys [status body]
    received-body :seon.ai.test/received-body} run]
  (let [server (http/run-server
                (fn [request]
                  (when received-body
                    (deliver received-body (slurp (:body request))))
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
          :seon.ai/max-tokens 8192
          :seon.ai/api-key-variable "PATH"
          :seon.ai/prompt "hello"
          :seon.ai/timeout-ms 5000}
         extra))

(deftest a-malformed-stream-settles-as-an-evidenced-flat-error
  (let [prefix (content-chunk "(my.run/complete \"safe")
        malformed "data: {not json"
        suffix (content-chunk "\")")]
    (with-provider {:body (str/join "\n" [prefix malformed suffix])}
      (fn [endpoint]
        (let [completion (ai/complete
                          (request endpoint {:seon.ai/stream? true}))
              evidence (:seon.error/data completion)]
          (is (= :seon.ai/unparseable-body (:seon.error/kind completion)))
          (is (not (contains? completion :seon.ai/text))
              "the reconstructed program must never be a completion")
          (is (= "{not json" (:seon.ai/body evidence)))
          (is (= 200 (:seon.ai/http-status evidence)))
          (is (= :response (:seon.ai/error-class evidence)))
          (is (true? (:seon.ai/request-transmitted? evidence)))
          (is (true? (:seon.ai/response-started? evidence)))
          (is (true? (:seon.ai/output-observed? evidence)))
          (is (= :fail
                 (ai/disposition {:seon.error/value completion
                                  :seon.ai/backup? true}))
              "corrupted paid output never retries or fails over"))))))

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

(deftest a-loopback-provider-receives-the-descriptor-output-budget
  (let [received (promise)
        server
        (http/run-server
         (fn [request]
           (deliver received (json/read-str (slurp (:body request))))
           {:status 200
            :headers {"content-type" "application/json"}
            :body
            "{\"choices\":[{\"message\":{\"content\":\"bounded\"}}]}"})
         {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try
      (let [endpoint (str "http://127.0.0.1:" (http/server-port server)
                          "/v1/chat/completions")
            completion (ai/complete (request endpoint {}))
            document @received]
        (is (= "bounded" (:seon.ai/text completion)))
        (is (= 8192 (get document "max_tokens")))
        (is (= "fixture" (get document "model"))))
      (finally
        (http/server-stop! server)))))

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

(deftest a-real-jdk-provider-status-commits-with-its-attempt
  (let [received-body (promise)]
    (with-provider {:status 502
                    :body "upstream unavailable"
                    :seon.ai.test/received-body received-body}
    (fn [endpoint]
      (let [target (-> (request endpoint {})
                       (dissoc :seon.ai/prompt)
                       (assoc :seon.ai/thinking :disabled))
            failure (ai/complete (assoc target :seon.ai/prompt "hello"))
            sent-body (:seon.ai.attempt/sent-body failure)
            status (:seon.ai/http-status (:seon.error/data failure))]
        (is (= :seon.ai/provider-error (:seon.error/kind failure)))
        (is (= @received-body sent-body)
            "the exact string posted by the JDK rides the completion")
        (is (= "disabled"
               (get-in (json/read-str sent-body) ["thinking" "type"])))
        (is (instance? Integer status)
            "the JDK value remains an Integer until the transaction boundary")
        (support/with-database
          (fn [connection]
            (db/transact! connection
                        [{:seon.cluster.agent/id "status-agent"}
                         {:seon.cluster.run/id "status-run"}])
            ((ns-resolve 'seon.cluster.loop 'record-attempt!)
             {:seon.db/connection connection
              :seon.cluster.run/process "process/status-test"
              :seon.config.error/recurrence-limit 3
              :seon.sci.admit/caps caps}
             {:seon.ai/target target
              :seon.error/value failure
              :seon.cluster.run/id "status-run"
              :seon.cluster.agent/id "status-agent"
              :seon.ai.attempt/ordinal 0}
             (Date. 1785319000000))
            (let [attempt
                  (db/pull @connection '[*]
                          [:seon.ai.attempt/id "status-run-attempt-0"])]
              (is (= 502 (:seon.ai/http-status attempt)))
              (is (= @received-body (:seon.ai.attempt/sent-body attempt))
                  "the attempt fact is the exact posted string")
              (is (some? (:seon.ai.attempt/error attempt))
                  "the provider error and attempt committed together")))))))))

(deftest settled-reasoning-reuses-the-eval-result-inline-blob-split
  (support/with-database
    {::support/fresh-store? true}
    (fn [connection]
      (db/transact! connection
                  [{:seon.config.eval.result/blob-threshold 65536}
                   {:seon.cluster.agent/id "reasoning-agent"}
                   {:seon.cluster.run/id "reasoning-run"}])
      (let [inline-reasoning "private reasoning"
            large (apply str (repeat 65537 "x"))
            cluster {:seon.db/connection connection
                     :seon.cluster.run/process "process/reasoning-test"
                     :seon.config.error/recurrence-limit 3
                     :seon.sci.admit/caps caps}
            base-request {:seon.ai/target
                          {:seon.ai/endpoint "http://provider.invalid"
                           :seon.ai/model "fixture"}
                          :seon.ai/settings {}
                          :seon.cluster.run/id "reasoning-run"
                          :seon.cluster.agent/id "reasoning-agent"}]
        (doseq [[ordinal reasoning] [[0 inline-reasoning] [1 large]]]
          ((ns-resolve 'seon.cluster.loop 'record-attempt!)
           cluster
           (assoc base-request
                  :seon.ai.attempt/ordinal ordinal
                  :seon.ai/reasoning-content reasoning)
           (Date. 1785319000000)))
        (let [inline (db/pull @connection '[*]
                             [:seon.ai.attempt/id
                              "reasoning-run-attempt-0"])
              oversized (db/pull @connection '[*]
                                [:seon.ai.attempt/id
                                 "reasoning-run-attempt-1"])]
          (is (= inline-reasoning (:seon.ai.attempt/reasoning inline)))
          (is (not (contains? inline :seon.ai.attempt/reasoning-blob)))
          (is (not (contains? oversized :seon.ai.attempt/reasoning)))
          (is (= (long (count large))
                 (:seon.ai.attempt/reasoning-size oversized)))
          (is (= (blob/put! connection large)
                 (:seon.ai.attempt/reasoning-blob oversized))
              "the row carries the content-addressed digest; file-backed
               blob readback is covered by seon.blob-settlement-test"))))))

(deftest a-broken-sink-cannot-fail-a-real-call
  (with-provider {}
    (fn [endpoint]
      (let [completion (ai/complete
                        (request endpoint {:seon.ai/stream? true
                                           :seon.ai/sink
                                           (fn [_] (throw (ex-info "no" {})))}))]
        (is (= "Hello, world" (:seon.ai/text completion)))))))

;;; ---------------------------------------------------------------------------
