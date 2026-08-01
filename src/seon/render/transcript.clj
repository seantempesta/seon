(ns seon.render.transcript
  "One agent's messages and eval receipts as a bounded REPL transcript.

  The renderer is an agent-level derived product, not a message-family lens:
  messages are reverse connections at d1 while receipts are reached through
  the agent's runs. W4 attaches these twins as one separate render call when
  membership inverts to the walk. Raw facts never acquire a detail level;
  every full, summary, and elided decision is derived for this call."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup])
  (:import [java.io PushbackReader StringReader]))

(def ^:private recent-entry-count
  ;; The measured transcript prototype's stable full-detail tail. The exact
  ;; threshold is quarry evidence, not stored state; a later model evaluation
  ;; may change this one projection policy without rewriting history.
  6)

(def ^:private message-selector
  [:db/id
   :seon.cluster.message/id
   :seon.cluster.message/at
   :seon.cluster.message/content
   :my.message/reason
   {:seon.cluster.message/to [:db/id :seon.cluster.agent/id]}
   {:seon.cluster.message/from [:db/id :seon.cluster.agent/id]}
   {:seon.cluster.message/about [:db/id]}])

(def ^:private receipt-selector
  [:db/id
   :seon.cluster.eval/id
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size
   :seon.cluster.eval/error
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/output
   :seon.problems/id
   :seon.error/kind
   {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
   {:seon.cluster.eval/run [:db/id :seon.cluster.run/id]}])

(defn- message-count
  [db agent-id]
  (or
   (d/q '[:find (count-distinct ?message) .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          (or-join [?message ?agent]
                   [?message :seon.cluster.message/to ?agent]
                   [?message :seon.cluster.message/from ?agent])]
        db agent-id)
   0))

(defn- receipt-count
  [db agent-id]
  (or
   (d/q '[:find (count-distinct ?receipt) .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?agent]
          [?receipt :seon.cluster.eval/run ?run]]
        db agent-id)
   0))

(defn- history-count
  [db agent-id]
  (if (and db agent-id)
    (+ (message-count db agent-id) (receipt-count db agent-id))
    0))

(defn- recent-message-rows
  [db agent-id limit]
  (d/q {:query
        '[:find ?message ?at ?id
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          (or-join [?message ?agent]
                   [?message :seon.cluster.message/to ?agent]
                   [?message :seon.cluster.message/from ?agent])
          [?message :seon.cluster.message/at ?at]
          [?message :seon.cluster.message/id ?id]]
        :args [db agent-id]
        :order-by '[?at :desc ?id :desc]
        :limit limit}))

(defn- recent-receipt-rows
  [db agent-id limit]
  (d/q {:query
        '[:find ?receipt ?at ?id
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?agent]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/at ?at]
          [?receipt :seon.cluster.eval/id ?id]]
        :args [db agent-id]
        :order-by '[?at :desc ?id :desc]
        :limit limit}))

(defn- candidate-entity-ids
  [db agent-id limit]
  (->> (concat
        (map #(into [:message] %) (recent-message-rows db agent-id limit))
        (map #(into [:eval] %) (recent-receipt-rows db agent-id limit)))
       (sort-by (fn [[kind _ at id]]
                  [(.getTime ^java.util.Date at)
                   (case kind :message 0 :eval 1)
                   id])
                #(compare %2 %1))
       (take limit)
       (group-by first)
       (reduce-kv (fn [ids kind rows]
                    (assoc ids kind (mapv second rows)))
                  {})))

(defn- form-sources
  [db receipt-ids]
  (if (seq receipt-ids)
    (into
     {}
     (d/q '[:find ?receipt ?source
            :in $ [?receipt ...]
            :where
            [?receipt :seon.cluster.eval/run ?run]
            [?receipt :seon.cluster.eval/ordinal ?ordinal]
            [?form :seon.cluster.run.form/run ?run]
            [?form :seon.cluster.run.form/ordinal ?ordinal]
            [?form :seon.cluster.run.form/source ?source]]
          db receipt-ids))
    {}))

(defn- pulled-many
  [db selector entity-ids]
  (if (seq entity-ids)
    (d/pull-many db selector entity-ids)
    []))

(defn- identity-attributes
  [db]
  (->> (:schema db)
       (keep (fn [[attribute properties]]
               (when (= :db.unique/identity (:db/unique properties))
                 attribute)))
       (sort-by str)
       vec))

(defn- about-identities
  [db messages]
  (let [about-eids
        (into #{} (keep #(get-in % [:seon.cluster.message/about :db/id]))
              messages)
        attributes (identity-attributes db)
        selector (into [:db/id] attributes)
        candidates
        (if (seq about-eids)
          (into
           []
           (mapcat
            (fn [entity]
              (keep (fn [attribute]
                      (let [identity-value (get entity attribute)]
                        (when (string? identity-value)
                          [(:db/id entity) attribute identity-value])))
                    attributes)))
           (d/pull-many db selector about-eids))
          [])
        candidate-values (into #{} (map #(nth % 2)) candidates)
        identified
        (into
         {}
         (map
          (fn [identity-value]
            [identity-value
             (into
              #{}
              (keep (fn [attribute]
                      (some-> (d/pull db [:db/id]
                                      [attribute identity-value])
                              :db/id)))
              attributes)]))
         candidate-values)]
    (reduce
     (fn [result [entity attribute identity-value]]
       (if (= #{entity} (get identified identity-value))
         (update result entity
                 (fn [current]
                   (first (sort-by (juxt (comp str first) second)
                                   (cond-> [[attribute identity-value]]
                                     current (conj current))))))
         result))
     {}
     candidates)))

(defn- message-entry
  [identities message]
  (let [about-eid (get-in message [:seon.cluster.message/about :db/id])]
    {::kind :message
     ::entity message
     ::id (:seon.cluster.message/id message)
     ::at (:seon.cluster.message/at message)
     ::content (:seon.cluster.message/content message)
     ::from (get-in message [:seon.cluster.message/from
                             :seon.cluster.agent/id])
     ::to (get-in message [:seon.cluster.message/to
                           :seon.cluster.agent/id])
     ::about (second (get identities about-eid))
     ::about-ref? (some? about-eid)
     ::reason (:my.message/reason message)}))

(defn capped-result?
  "True when a receipt stores less result text than its original size."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (let [result-edn (:seon.cluster.eval/result-edn receipt)
        result-size (:seon.cluster.eval/result-size receipt)]
    (and (string? result-edn)
         (integer? result-size)
         (> result-size (count result-edn)))))

(defn- receipt-entry
  [sources receipt]
  (let [receipt-eid (:db/id receipt)
        ordinal (:seon.cluster.eval/ordinal receipt)]
    {::kind :eval
     ::entity receipt
     ::id (:seon.cluster.eval/id receipt)
     ::at (:seon.cluster.eval/at receipt)
     ::ordinal ordinal
     ::run-id (get-in receipt [:seon.cluster.eval/run
                               :seon.cluster.run/id])
     ::source (get sources receipt-eid)
     ::result (:seon.cluster.eval/result-edn receipt)
     ::result-blob (:seon.cluster.eval/result-blob receipt)
     ::result-size (:seon.cluster.eval/result-size receipt)
     ::capped? (capped-result? receipt)
     ::error (:seon.cluster.eval/error receipt)
     ::error-kind (:seon.error/kind receipt)
     ::problem-id (:seon.problems/id receipt)
     ::interrupted-at (:seon.cluster.eval/interrupted-at receipt)
     ::output (:seon.cluster.eval/output receipt)}))

(defn- entry-order
  [entry]
  [(.getTime ^java.util.Date (::at entry))
   (case (::kind entry) :message 0 :eval 1)
   (::id entry)])

(defn- history
  [db agent-id limit]
  (let [ids (candidate-entity-ids db agent-id limit)
        messages (pulled-many db message-selector (:message ids))
        receipts (pulled-many db receipt-selector (:eval ids))
        identities (about-identities db messages)
        sources (form-sources db (:eval ids))]
    (->> (concat (map (partial message-entry identities) messages)
                 (map (partial receipt-entry sources) receipts))
         (sort-by entry-order)
         vec)))

(defn- read-result
  [serialized]
  (when (string? serialized)
    (with-open [reader (PushbackReader. (StringReader. serialized))]
      (try
        (let [value (edn/read {:eof ::eof} reader)
              trailing (edn/read {:eof ::eof} reader)]
          (if (and (not= ::eof value) (= ::eof trailing))
            {::read-value value}
            {::unreadable? true}))
        (catch Throwable _
          {::unreadable? true})))))

(defn- entry-header
  [entry detail]
  (str ";; transcript/entry " (pr-str (::kind entry)) " "
       (pr-str (::id entry)) " " (pr-str detail) "\n"
       ";; at " (pr-str (::at entry))))

(defn- floor-text
  [unit value]
  (let [caps (:seon.sci.admit/caps unit)
        rendered
        (render/render
         {:seon.render/unit
          {:seon.render/value value
           :seon.cluster.agent/id (:seon.cluster.agent/id unit)
           :seon.sci.admit/caps caps
           ;; Transcript owns its aggregate token budget. Its value leaves use
           ;; the generous receipt bounds so presentation defaults cannot
           ;; truncate headers and prose before that budget is applied.
           :seon.render.value/options
           {:seon.render.value/max-depth
            (:seon.config.eval.result/max-depth caps)
            :seon.render.value/max-collection
            (:seon.config.eval.result/max-collection caps)
            :seon.render.value/max-string
            (:seon.config.eval.result/max-string caps)}}
          :seon.render/kind :seon.render/ai})]
    (or (:seon.render/output rendered)
        (pr-str rendered))))

(defn- floor-value
  [unit value]
  (let [text (floor-text unit value)
        {::keys [read-value unreadable?]} (read-result text)]
    (if unreadable? text read-value)))

(defn- bounded-scalar
  [unit value]
  (when (some? value)
    (let [bounded (floor-text unit value)]
      (if (= (pr-str value) bounded) value bounded))))

(defn- rendered-family
  [unit family-unit distance]
  (let [declaration
        ((requiring-resolve 'seon.render.walk/projection)
         family-unit
         {:seon.render/kind :seon.render/ai
          :seon.render/overrides {}
          :seon.render/floor 'seon.render.block/data-prose})
        rendered
        (render/render
         {:seon.render/unit
          (assoc family-unit
                 :seon.render/ai declaration
                 :seon.db/db (:seon.db/db unit)
                 :seon.render/distance distance
                 :seon.sci.admit/caps (:seon.sci.admit/caps unit))
          :seon.render/kind :seon.render/ai})
        output (or (:seon.render/output rendered)
                   (floor-text unit rendered))]
    (bounded-scalar unit output)))

(defn- message-extra
  [entry]
  (cond-> {}
    (::about entry) (assoc :seon.cluster.message/about (::about entry))
    (and (::about-ref? entry) (nil? (::about entry)))
    (assoc :seon.transcript/unresolved-about? true)
    (::reason entry) (assoc :my.message/reason (::reason entry))))

(defn- message-text
  [unit entry detail]
  (let [entity (cond-> (::entity entry)
                 (::content entry)
                 (assoc :seon.cluster.message/content
                        (bounded-scalar unit (::content entry))))
        sentence (rendered-family unit entity 1)
        extra (message-extra entry)]
    (str (bounded-scalar unit (entry-header entry detail)) "\n"
         (pr-str
          (cond-> (list 'comment sentence)
            (seq extra) (concat (list (floor-value unit extra))))))))

(defn- bounded-result
  [unit serialized]
  (when (some? serialized)
    (let [{::keys [read-value unreadable?]} (read-result serialized)]
      (floor-text unit
                  (if unreadable?
                    {:seon.cluster.eval/result-edn serialized
                     :seon.render.transcript/unreadable? true}
                    read-value)))))

(defn- receipt-extra
  [entry]
  (cond-> {}
    (::source entry) (assoc :seon.cluster.run.form/source (::source entry))
    (::error entry) (assoc :seon.cluster.eval/error (::error entry))
    (::error-kind entry) (assoc :seon.error/kind (::error-kind entry))
    (::problem-id entry) (assoc :seon.problems/id (::problem-id entry))
    (::interrupted-at entry)
    (assoc :seon.cluster.eval/interrupted-at (::interrupted-at entry))))

(defn- receipt-text
  [unit entry detail]
  (let [entity
        (cond-> (::entity entry)
          (::result entry)
          (assoc :seon.cluster.eval/result-edn
                 (bounded-result unit (::result entry)))
          (::error entry)
          (assoc :seon.cluster.eval/error
                 (bounded-scalar unit (::error entry)))
          (::output entry)
          (assoc :seon.cluster.eval/output
                 (bounded-scalar unit (::output entry))))
        sentence (rendered-family unit entity 2)
        extra (receipt-extra entry)]
    (str (bounded-scalar unit (entry-header entry detail)) "\n"
         (pr-str (list 'comment sentence (floor-value unit extra)))
         (when (::capped? entry)
           (str "\n; CAPPED: showing " (count (::result entry))
                " of " (::result-size entry)
                " chars — full value "
                (if-some [digest (::result-blob entry)]
                  (str "result-blob " digest)
                  "unavailable")
                " (result-size " (::result-size entry) " chars)")))))

(defn- projected-entry
  [unit entry detail]
  {::kind (::kind entry)
   ::id (::id entry)
   ::at (::at entry)
   ::detail detail
   ::text (case (::kind entry)
            :message (message-text unit entry detail)
            :eval (receipt-text unit entry detail))})

(defn- entry-name
  [entry]
  (keyword (str "seon.transcript." (name (::kind entry))) (::id entry)))

(defn- marker-text
  [elided]
  (str elided " older transcript entr" (if (= 1 elided) "y" "ies")
       " elided by the token budget."))

(defn- ai-output
  [entries elided]
  (str/join
   "\n\n"
   (cond-> []
     (pos? elided)
     (conj (str ";; transcript/elided " elided "\n;; "
                (marker-text elided)))
     (seq entries) (into (map ::text entries)))))

(defn- html-output
  [entries elided]
  (into
   [:section {:id (block/surface-id :transcript)
              :class "seon-transcript"}]
   (cond-> []
     (pos? elided)
     (conj [:p {:class "seon-transcript-elision"
                :data-transcript-elided (str elided)}
            (marker-text elided)])
     (seq entries)
     (conj
      (into
       [:ol {:class "seon-transcript-list"}]
       (map (fn [entry]
              [:li {:id (block/surface-id (entry-name entry))
                    :class "seon-transcript-entry"
                    :data-transcript-id (::id entry)
                    :data-transcript-kind (name (::kind entry))
                    :data-transcript-detail (name (::detail entry))}
               [:pre [:code (::text entry)]]]))
       entries)))))

(defn- output-tokens
  [entries elided]
  (max (tokens/estimate (ai-output entries elided))
       (tokens/estimate (hiccup/->string (html-output entries elided)))))

(defn- fits?
  [budget entries elided]
  (<= (output-tokens entries elided) budget))

(defn- best-summary
  [unit entry newer older-count budget]
  (let [candidate (projected-entry unit entry :summary)]
    (when (fits? budget (into [candidate] newer) older-count)
      candidate)))

(defn- projection
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.cluster.agent/id unit)
        total (history-count db agent-id)
        requested (max 0 (long (get unit ::token-budget 0)))
        ;; The floor is derived from the exact smallest honest twin: the HTML
        ;; wrapper, plus the loud marker whenever facts would be dropped.
        minimum (output-tokens [] total)
        budget (max requested minimum)
        candidate-limit (int (min Integer/MAX_VALUE
                                  (max recent-entry-count budget)))
        entries (if (and db agent-id)
                  (history db agent-id candidate-limit)
                  [])
        acquired (count entries)
        unacquired (- total acquired)
        recent-start (max 0 (- acquired recent-entry-count))]
    (loop [index (dec acquired)
           newer []]
      (if (neg? index)
        {::entries newer
         ::elided unacquired
         ::minimum-token-budget minimum
         ::token-budget budget}
        (let [entry (nth entries index)
              recent? (<= recent-start index)
              full (when recent?
                     (projected-entry unit entry :full))
              with-full (when full (into [full] newer))
              older-count (+ unacquired index)]
          (cond
            (and with-full (fits? budget with-full older-count))
            (recur (dec index) with-full)

            :else
            (if-let [summary
                     (best-summary unit entry newer older-count budget)]
              (recur (dec index) (into [summary] newer))
              {::entries newer
               ::elided (+ unacquired (inc index))
               ::minimum-token-budget minimum
               ::token-budget budget})))))))

(defn minimum-token-budget
  "Minimum budget that can render this history's loud oldest-tail marker."
  {:malli/schema [:=> [:cat :seon.render/unit] [:int {:min 0}]]}
  [unit]
  (output-tokens
   []
   (history-count (:seon.db/db unit) (:seon.cluster.agent/id unit))))

(defn render-ai
  "Render one agent's bounded transcript as reader-valid REPL text."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [{::keys [entries elided]} (projection unit)]
    (ai-output entries elided)))

(defn render-html
  "Render the same bounded transcript with stable block and entry ids."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [{::keys [entries elided]} (projection unit)]
    (html-output entries elided)))
