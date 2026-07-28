(ns seon.ai.stream-test
  "Streamed replies: the fold, the facts, the sink, and the two blocks.

  NO NETWORK. The wire is exercised against a real http-kit server
  serving CANNED SSE, so the transport branch is genuinely tested —
  status handling, incremental reads, natural EOF — without a provider,
  a key, or a bill. The one thing a provider proves that a fixture
  cannot is that real chunk shapes match the fold, and that is an
  orchestrator-owned live drive rather than a suite item.

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
            [seon.ai.stream :as stream]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]))

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
  (let [result
        (tc/quick-check
         300
         (prop/for-all [text (gen/vector gen/string-ascii 0 12)]
           (map? (ai/stream-fold text nil)))
         :seed 202607280301)]
    (is (true? (:result result)) (str "fold totality failed: " (pr-str result)))))

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
;;; The facts
;;; ---------------------------------------------------------------------------

(def ^:private agent-id "root")

(defn- with-database
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (vec (seon.schema/canonical-database-attributes))))
      (d/transact connection [{:seon.cluster.agent/id agent-id}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(deftest the-partial-attributes-carry-no-history
  ;; The facet is the whole reason this path is allowed on the database.
  ;; A regression here would silently accumulate a retained value per
  ;; snapshot of a string that is about to be superseded anyway.
  (doseq [attribute [:seon.ai.stream/text :seon.ai.stream/tokens
                     :seon.ai.stream/at]]
    (is (true? (:db/noHistory (schema.datahike/malli->datahike-attr attribute)))
        (str attribute " must be no-history")))
  (testing "and the partial text is deliberately unindexed"
    (is (not (:db/index (schema.datahike/malli->datahike-attr
                         :seon.ai.stream/text))))))

(deftest snapshots-upsert-one-row
  (with-database
    (fn [connection]
      (dotimes [index 5]
        (d/transact connection
                    (stream/snapshot-tx "s-1" agent-id
                                        {:seon.ai/text (str "chunk " index)
                                         :seon.ai/tokens index}
                                        #inst "2026-07-28T00:00:00.000-00:00")))
      (let [rows (d/q '[:find ?text ?tokens
                        :where
                        [?s :seon.ai.stream/text ?text]
                        [?s :seon.ai.stream/tokens ?tokens]]
                      (d/db connection))]
        (is (= #{["chunk 4" 4]} rows)
            "five snapshots are five VALUES of one entity, not five rows")))))

(deftest settling-retracts-the-partial-and-is-idempotent
  (with-database
    (fn [connection]
      (d/transact connection
                  (stream/snapshot-tx "s-1" agent-id
                                      {:seon.ai/text "partial" :seon.ai/tokens 1}
                                      #inst "2026-07-28T00:00:00.000-00:00"))
      (is (seq (stream/settle-tx (d/db connection) "s-1")))
      (d/transact connection (stream/settle-tx (d/db connection) "s-1"))
      (is (= #{} (d/q '[:find ?t :where [?s :seon.ai.stream/text ?t]]
                      (d/db connection)))
          "no instant in which a partial and a settled reply both exist")
      (testing "and settling what is already gone is no transaction"
        (is (= [] (stream/settle-tx (d/db connection) "s-1")))
        (is (= [] (stream/settle-tx (d/db connection) "never-existed")))))))

;;; ---------------------------------------------------------------------------
;;; The isolated sink
;;; ---------------------------------------------------------------------------

(deftest the-sink-does-no-work-on-the-callers-thread
  ;; It runs on the thread reading the provider's socket. Anything slow
  ;; here slows the model call, which is the one thing presentation may
  ;; never do.
  (with-database
    (fn [connection]
      (let [published (stream/publisher
                       {:seon.ai.stream/id "s-1"
                        :seon.cluster.agent/id agent-id
                        :seon.store/connection connection
                        :seon.config.ai.stream/publish-ms 20})
            sink (:seon.ai.stream/sink published)]
        (try
          (let [start (System/nanoTime)]
            (dotimes [index 2000]
              (sink {:seon.ai/text (str index) :seon.ai/tokens index}))
            (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
              ;; a generous bound: the point is that 2000 calls cost
              ;; nothing like 2000 transactions, not a tuned number
              (is (< elapsed-ms 250)
                  (str "2000 sink calls took " elapsed-ms "ms — the sink is "
                       "doing work it must not do"))))
          (finally ((:seon.ai.stream/stop! published))))))))

(deftest publishing-coalesces-and-stop-commits-the-complete-value
  (with-database
    (fn [connection]
      (let [published (stream/publisher
                       {:seon.ai.stream/id "s-1"
                        :seon.cluster.agent/id agent-id
                        :seon.store/connection connection
                        :seon.config.ai.stream/publish-ms 40})
            sink (:seon.ai.stream/sink published)]
        (dotimes [index 200]
          (sink {:seon.ai/text (str "text-" index) :seon.ai/tokens index}))
        ((:seon.ai.stream/stop! published))
        (let [rows (d/q '[:find ?text ?tokens
                          :where
                          [?s :seon.ai.stream/text ?text]
                          [?s :seon.ai.stream/tokens ?tokens]]
                        (d/db connection))]
          (is (= #{["text-199" 199]} rows)
              "stop! commits the COMPLETE value, so the last thing on
               screen is the whole reply and not wherever the cadence
               happened to stop"))))))

;;; ---------------------------------------------------------------------------
;;; The two exercise blocks
;;; ---------------------------------------------------------------------------

(deftest the-exercises-are-ordinary-blocks
  ;; The claim the exercises exist to test: the highest-churn thing in
  ;; the system needed no render machinery of its own.
  (with-database
    (fn [connection]
      (d/transact connection
                  [{:seon.cluster.agent/id agent-id
                    :seon.cluster.agent/blocks
                    [{:seon.render.block/name :tokens :seon.render.block/priority 0
                      :seon.render/html `stream/tokens-html}
                     {:seon.render.block/name :reply :seon.render.block/priority 10
                      :seon.render/html `stream/text-html}]}])
      (testing "before anything streams, they render idle rather than absent"
        (let [surfaces (block/surfaces (d/db connection)
                                       {:seon.cluster.agent/id agent-id
                                        :seon.render/kind :seon.render/html
                                        :seon.sci.admit/caps caps})]
          (is (= 2 (count surfaces)))
          (is (every? (comp nil? :seon.error/value) surfaces))
          (is (str/includes? (hiccup/->string (:seon.render/output (second surfaces)))
                             "idle"))))
      (d/transact connection
                  (stream/snapshot-tx "s-1" agent-id
                                      {:seon.ai/text "streaming now"
                                       :seon.ai/tokens 42}
                                      #inst "2026-07-28T00:00:00.000-00:00"))
      (let [surfaces (block/surfaces (d/db connection)
                                     {:seon.cluster.agent/id agent-id
                                      :seon.render/kind :seon.render/html
                                      :seon.sci.admit/caps caps})
            html (str/join (map (comp hiccup/->string :seon.render/output)
                                surfaces))]
        (is (str/includes? html "42") "the live count")
        (is (str/includes? html "streaming now") "the streamed text")
        (is (str/includes? html "surface-tokens"))
        (is (str/includes? html "surface-reply")
            "each exercise is its own morph target, like every block")))))
