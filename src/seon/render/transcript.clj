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

(defn- receipt-entry
  [sources receipt]
  (let [receipt-eid (:db/id receipt)
        ordinal (:seon.cluster.eval/ordinal receipt)]
    {::kind :eval
     ::id (:seon.cluster.eval/id receipt)
     ::at (:seon.cluster.eval/at receipt)
     ::ordinal ordinal
     ::run-id (get-in receipt [:seon.cluster.eval/run
                               :seon.cluster.run/id])
     ::source (get sources receipt-eid)
     ::result (:seon.cluster.eval/result-edn receipt)
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

(defn- readable-source?
  [source]
  (when (string? source)
    (with-open [reader (PushbackReader. (StringReader. source))]
      (try
        (loop []
          (let [form (read {:eof ::eof
                            :read-cond :allow
                            :features #{:clj}}
                           reader)]
            (if (= ::eof form) true (recur))))
        (catch Throwable _
          false)))))

(defn- result-shape
  [serialized]
  (let [{::keys [read-value unreadable?]} (read-result serialized)
        value read-value]
    (cond
      (nil? serialized) nil
      unreadable? "unreadable stored result"
      (map? value) (str "map with " (count value) " entr"
                        (if (= 1 (count value)) "y" "ies"))
      (vector? value) (str "vector with " (count value) " items")
      (set? value) (str "set with " (count value) " members")
      (sequential? value) "sequence"
      (string? value) (str "string, " (tokens/estimate value) " tokens")
      (keyword? value) "keyword"
      (symbol? value) "symbol"
      (number? value) "number"
      (boolean? value) "boolean"
      (nil? value) "nil"
      :else (.getSimpleName (class value)))))

(defn- wait-note
  [serialized]
  (let [value (::read-value (read-result serialized))]
    (when (and (map? value) (= :wait (:my.run/disposition value)))
      (:my.run/note value))))

(defn- entry-header
  [entry detail]
  (str ";; transcript/entry " (pr-str (::kind entry)) " "
       (pr-str (::id entry)) " " (pr-str detail) "\n"
       ";; at " (pr-str (::at entry))))

(defn- message-form
  [entry]
  (let [to (::to entry)
        about (::about entry)]
    (cond
      (and (::about-ref? entry) (nil? about))
      (list 'comment
            (cond-> {:seon.transcript/unresolved-about? true
                     :seon.cluster.message/to to
                     :seon.cluster.message/content (::content entry)}
              (::reason entry) (assoc :my.message/reason (::reason entry))))

      (::reason entry)
      (list 'my.message/decline to about (::reason entry))

      (::from entry)
      (cond-> (list 'my.message/send to (::content entry))
        about (concat (list about)))

      :else (::content entry))))

(defn- message-direction
  [agent-id entry]
  (cond
    (= agent-id (::from entry))
    (str ";; You sent this to agent " (::to entry) ".")

    (::from entry)
    (str ";; Agent " (::from entry) " sent this to you.")

    :else ";; This arrived from outside the agent population."))

(defn- clipped
  [value token-budget]
  (when (some? value)
    (tokens/clip-str (str value) token-budget)))

(defn- full-message
  [agent-id entry]
  (str (entry-header entry :full) "\n"
       (message-direction agent-id entry) "\n"
       (pr-str (message-form entry))))

(defn- summary-message
  [agent-id entry preview-budget]
  (let [entry (cond-> (assoc entry ::content
                              (clipped (::content entry) preview-budget))
                (::reason entry)
                (assoc ::reason (clipped (::reason entry) preview-budget)))]
    (str (entry-header entry :summary) "\n"
         (message-direction agent-id entry) "\n"
         (pr-str (message-form entry)))))

(defn- full-eval
  [entry]
  (let [source (if (readable-source? (::source entry))
                 (::source entry)
                 (pr-str
                  (list 'comment
                        {:seon.cluster.run.form/source (::source entry)
                         :seon.render.transcript/unreadable? true})))
        result (when (::result entry)
                 (let [{::keys [unreadable?]} (read-result (::result entry))]
                   (str ";; =>\n"
                        (if unreadable?
                          (pr-str
                           (list 'comment
                                 {:seon.cluster.eval/result-edn
                                  (::result entry)
                                  :seon.render.transcript/unreadable? true}))
                          (::result entry)))))
        error (when (::error entry)
                (pr-str
                 (list
                  'comment
                  (cond-> {:seon.cluster.eval/error (::error entry)}
                    (::error-kind entry)
                    (assoc :seon.error/kind (::error-kind entry))
                    (::problem-id entry)
                    (assoc :seon.problems/id (::problem-id entry))))))
        interrupted
        (when (::interrupted-at entry)
          (pr-str
           (list 'comment
                 {:seon.cluster.eval/interrupted-at (::interrupted-at entry)}
                 "Its effect may have happened; nothing was retried.")))
        running
        (when-not (or result error interrupted)
          (pr-str (list 'comment
                        {:seon.cluster.eval/state :running})))
        output (when-let [printed (::output entry)]
                 (pr-str (list 'comment
                               {:seon.cluster.eval/output printed})))]
    (str/join
     "\n"
     (cond-> [(entry-header entry :full)
              source]
       output (conj output)
       result (conj result)
       error (conj error)
       interrupted (conj interrupted)
       running (conj running)))))

(defn- summary-data
  [entry preview-budget]
  (let [half (quot preview-budget 2)]
    (cond-> {:seon.transcript/eval (::id entry)
             :seon.cluster.eval/at (::at entry)
             :seon.cluster.eval/ordinal (::ordinal entry)
             :seon.cluster.run.form/source
             (clipped (or (::source entry) "<missing source>") half)}
      (::result entry)
      (assoc :seon.cluster.eval/result-summary
             (result-shape (::result entry)))
      (wait-note (::result entry))
      (assoc :my.run/note (clipped (wait-note (::result entry)) half))
      (::error entry)
      (assoc :seon.cluster.eval/error (clipped (::error entry) half))
      (::error-kind entry)
      (assoc :seon.error/kind (::error-kind entry))
      (::problem-id entry)
      (assoc :seon.problems/id (::problem-id entry))
      (::interrupted-at entry)
      (assoc :seon.cluster.eval/interrupted-at (::interrupted-at entry)
             :seon.transcript/effect-may-have-happened? true)
      (::output entry)
      (assoc :seon.cluster.eval/output-tokens
             (tokens/estimate (::output entry))))))

(defn- summary-entry
  [agent-id entry preview-budget]
  (case (::kind entry)
    :message (summary-message agent-id entry preview-budget)
    :eval (str (entry-header entry :summary) "\n"
               (pr-str (list 'comment
                             (summary-data entry preview-budget))))))

(defn- projected-entry
  [agent-id entry detail preview-budget]
  {::kind (::kind entry)
   ::id (::id entry)
   ::at (::at entry)
   ::detail detail
   ::text (case detail
            :full (case (::kind entry)
                    :message (full-message agent-id entry)
                    :eval (full-eval entry))
            :summary (summary-entry agent-id entry preview-budget))})

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
  [agent-id entry newer older-count budget]
  (letfn [(candidate [preview]
            (projected-entry agent-id entry :summary preview))
          (fits-preview? [preview]
            (fits? budget
                   (into [(candidate preview)] newer)
                   older-count))]
    (when (fits-preview? 0)
      (loop [low 0
             high budget]
        (if (< low high)
          (let [middle (quot (inc (+ low high)) 2)]
            (if (fits-preview? middle)
              (recur middle high)
              (recur low (dec middle))))
          (candidate low))))))

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
                     (projected-entry agent-id entry :full 0))
              with-full (when full (into [full] newer))
              older-count (+ unacquired index)]
          (cond
            (and with-full (fits? budget with-full older-count))
            (recur (dec index) with-full)

            :else
            (if-let [summary
                     (best-summary agent-id entry newer older-count budget)]
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
