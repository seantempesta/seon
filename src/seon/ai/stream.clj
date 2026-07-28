(ns seon.ai.stream
  "Streamed replies as facts: the isolated sink, and the two blocks that
  read it.

  THE SPLIT THIS NAMESPACE EXISTS FOR. `seon.ai` owns the wire and the
  fold and knows nothing about a database; this owns the database and
  knows nothing about a wire. Between them is one function of one
  argument — the sink — which is exactly the seam that keeps a
  presentation feature from reaching into transport.

  THE SINK IS ISOLATED AND NON-BLOCKING, which is a requirement rather
  than a nicety. It runs on the thread reading the provider's socket, so
  anything slow inside it slows the model call — the one thing a
  presentation feature may never do. So it does no work at all: it
  replaces the pending snapshot in a latest-wins mailbox and returns. A
  separate virtual thread drains that mailbox at the configured cadence
  and commits. Presentation may lag and may DROP intermediate snapshots;
  both are correct, because every snapshot is a complete value and the
  next one supersedes it.

  The two exercise blocks are ordinary blocks. That is the claim being
  tested: the highest-churn thing in the system needed no render
  machinery of its own — a key, a function, and the same per-block morph
  every other surface gets.

  Crash walk. A kill mid-stream leaves a partial row with no settled
  reply. That is visible rather than silent: the row's `at` stops
  advancing, and `settle-tx` never ran, so nothing pretends the reply
  finished. The next turn's facts supersede it and `settle-tx` retracts
  it whenever it next runs — the partial is never mistaken for the
  durable reply because the durable reply is a different attribute
  entirely."
  (:require [datahike.api :as d]
            [seon.render.block :as block]
            [seon.schema.edn :as schema.edn])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/stream.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Facts
;;; ---------------------------------------------------------------------------

(defn snapshot-tx
  "Transaction data for one coalesced snapshot. PURE.

  A COMPLETE VALUE, never a delta. A consumer that missed a delta would
  be permanently wrong; a consumer that misses a snapshot is briefly
  behind, and the next one repairs it. This is also what makes the
  no-history facet safe — there is nothing to reconstruct from history
  because every value stands alone.

  Upserts on `:seon.ai.stream/id`, so a stream row is written in place
  and twenty-two snapshots are twenty-two values of one entity rather
  than twenty-two entities."
  {:malli/schema [:=> [:cat :seon.ai.stream/id :seon.cluster.agent/id
                       :seon.ai/partial :inst]
                  [:vector :any]]}
  [id agent-id {:keys [:seon.ai/text :seon.ai/tokens]} at]
  [{:seon.ai.stream/id id
    :seon.ai.stream/agent [:seon.cluster.agent/id agent-id]
    :seon.ai.stream/text text
    :seon.ai.stream/tokens tokens
    :seon.ai.stream/at at}])

(defn settle-tx
  "Transaction data retracting the stream row. PURE.

  RIDES THE TRANSACTION THAT SETTLES THE REAL FACT, which is the whole
  contract: there must be no instant in which both a partial and a
  settled reply exist, because then something has to decide which is
  real. The caller conjes this onto the transaction that commits the
  reply rather than committing it separately.

  Retracting an absent row is empty tx-data, not an error — a call that
  never streamed has nothing to clean up, and making the caller check
  would put the knowledge in two places."
  {:malli/schema [:=> [:cat :any :seon.ai.stream/id] [:vector :any]]}
  [db id]
  (if (seq (d/q '[:find [?stream ...] :in $ ?id
                  :where [?stream :seon.ai.stream/id ?id]]
                db id))
    [[:db/retractEntity [:seon.ai.stream/id id]]]
    []))

;;; ---------------------------------------------------------------------------
;;; The sink
;;; ---------------------------------------------------------------------------

(defn publisher
  "Start an isolated publisher and return `{sink stop!}`.

  `sink` is what `seon.ai` calls on the provider's socket thread. It
  offers the newest snapshot into a one-slot mailbox and returns —
  no database, no lock, no allocation beyond the offer. A full mailbox
  means a commit is already pending and the newer value will be picked
  up when it drains, so a failed offer is CORRECT rather than a drop to
  report.

  `stop!` ends the draining thread. It commits one final snapshot first,
  so the last thing a reader sees is the complete streamed text rather
  than whatever the cadence happened to catch — a stream that ended
  mid-word on screen would be indistinguishable from one that stalled.

  Nothing here throws into the caller. A commit that fails leaves the
  page briefly stale, which is the correct failure for presentation and
  the reason the sink was isolated in the first place."
  {:malli/schema [:=> [:cat :seon.ai.stream/publisher-request]
                  :seon.ai.stream/publisher]}
  [{stream-id :seon.ai.stream/id
    agent-id :seon.cluster.agent/id
    connection :seon.store/connection
    cadence :seon.config.ai.stream/publish-ms}]
  (let [mailbox (ArrayBlockingQueue. 1)
        latest (atom nil)
        running (volatile! true)
        commit! (fn [snapshot]
                  (when snapshot
                    (try
                      (d/transact connection
                                  (snapshot-tx stream-id agent-id snapshot
                                               (java.util.Date.)))
                      (catch Throwable _ nil))))
        worker (.start
                (Thread/ofVirtual)
                (fn []
                  (loop []
                    (when @running
                      (when (.poll mailbox cadence TimeUnit/MILLISECONDS)
                        (commit! @latest))
                      (recur)))))]
    {:seon.ai.stream/sink
     (fn [snapshot]
       ;; the ONLY work on the provider's thread
       (reset! latest snapshot)
       (.offer mailbox :look)
       nil)
     :seon.ai.stream/stop!
     (fn []
       (vreset! running false)
       ;; the complete text, so the last thing on screen is the whole
       ;; reply rather than wherever the cadence stopped
       (commit! @latest)
       nil)
     :seon.ai.stream/worker worker}))

;;; ---------------------------------------------------------------------------
;;; The two exercise blocks
;;; ---------------------------------------------------------------------------

(defn- row
  [unit]
  (let [db (:seon.db/db unit)]
    (first
     (d/q '[:find [(pull ?stream [*]) ...]
            :in $ ?agent-id
            :where
            [?agent :seon.cluster.agent/id ?agent-id]
            [?stream :seon.ai.stream/agent ?agent]]
          db (:seon.cluster.agent/id unit)))))

(defn tokens-html
  "EXERCISE ONE — a per-run token counter updating live in the browser
  while a model call streams.

  An ordinary block. It queries one attribute and returns hiccup, and
  the fact that its value changes twenty times a second is the
  pipeline's problem rather than its own — which is the point of the
  exercise. Nothing here knows it is high-churn."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [found (row unit)]
    [:div {:id (block/surface-id :tokens) :class "seon-stream-tokens"}
     [:span {:class "seon-stream-label"} "tokens"]
     [:span {:class "seon-stream-count"}
      (str (or (:seon.ai.stream/tokens found) 0))]]))

(defn text-html
  "EXERCISE TWO — the model's reply streaming into the interface as it
  generates.

  Also an ordinary block, and deliberately the same shape as the
  counter: two exercises, one mechanism, no streaming-specific render
  path. The blinking cursor is CSS on an empty span rather than a
  character in the text, so the reply's bytes are exactly the model's
  and a copy-paste does not pick up decoration."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [found (row unit)
        text (:seon.ai.stream/text found)]
    [:div {:id (block/surface-id :reply) :class "seon-stream-reply"}
     (if text
       [:span {:class "seon-stream-text"} text]
       [:span {:class "seon-stream-idle"} "idle"])
     (when text [:span {:class "seon-stream-cursor"} ""])]))
