(ns seon.render.transcript
  "One agent's messages and eval receipts as a bounded REPL transcript.

  The renderer is an agent-level derived product, not a message-family lens:
  messages are reverse connections at d1 while receipts are reached through
  the agent's runs. W4 attaches these twins as one separate render call when
  membership inverts to the walk. Raw facts never acquire a detail level;
  every full, summary, and elided decision is derived for this call."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.ai.tokens :as tokens]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.print :as print]
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

(def ^:private reasoning-attempt-selector
  [:db/id
   :seon.ai.attempt/id
   :seon.ai.attempt/at
   :seon.ai.attempt/reasoning
   :seon.ai.attempt/reasoning-blob
   :seon.ai.attempt/reasoning-size
   {:seon.ai.attempt/run [:db/id :seon.cluster.run/id]}])

(defn- message-count
  [db agent-id]
  (or
   (db/q '[:find (count-distinct ?message) .
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
   (db/q '[:find (count-distinct ?receipt) .
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
  (db/q {:query
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
  (db/q {:query
        '[:find ?receipt ?at ?id
          :in $ ?agent-id ?bootstrap-run-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?agent]
          [?run :seon.cluster.run/id ?run-id]
          [(not= ?run-id ?bootstrap-run-id)]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/at ?at]
          [?receipt :seon.cluster.eval/id ?id]]
        :args [db agent-id (bootstrap/run-id agent-id)]
        :order-by '[?at :desc ?id :desc]
        :limit limit}))

(defn- pinned-receipt-ids
  [db agent-id]
  (db/q '[:find [?receipt ...]
         :in $ ?agent-id ?run-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?run :seon.cluster.run/agent ?agent]
         [?run :seon.cluster.run/id ?run-id]
         [?receipt :seon.cluster.eval/run ?run]]
       db agent-id (bootstrap/run-id agent-id)))

(defn- candidate-entity-ids
  [db agent-id limit]
  (let [recent
        (->> (concat
              (map #(into [:message] %)
                   (recent-message-rows db agent-id limit))
              (map #(into [:eval] %)
                   (recent-receipt-rows db agent-id limit)))
             (sort-by (fn [[kind _ at id]]
                        [(.getTime ^java.util.Date at)
                         (case kind :message 0 :eval 1)
                         id])
                      #(compare %2 %1))
             (take limit)
             (group-by first)
             (reduce-kv (fn [ids kind rows]
                          (assoc ids kind (mapv second rows)))
                        {}))]
    (update recent :eval
            (fn [receipt-ids]
              (into []
                    (distinct
                     (concat (pinned-receipt-ids db agent-id)
                             receipt-ids)))))))

(defn- form-sources
  [db receipt-ids]
  (if (seq receipt-ids)
    (into
     {}
     (db/q '[:find ?receipt ?source
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
    (db/pull-many db selector entity-ids)
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
           (db/pull-many db selector about-eids))
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
                      (some-> (db/pull db [:db/id]
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
   (case (::kind entry) :message 0 :attempt 1 :eval 2)
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
         (map (fn [entry]
                (assoc entry ::pinned?
                       (and (= :eval (::kind entry))
                            (= (bootstrap/run-id agent-id)
                               (::run-id entry))))))
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
           :seon.print/options (:seon.print/options unit)}
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
      (cond
        unreadable?
        (floor-text unit {:seon.cluster.eval/result-edn serialized
                          :seon.render.transcript/unreadable? true})

        (and (map? read-value) (:seon.print/face read-value))
        (print/emit-text read-value
                         (merge (print/default-options)
                                (:seon.print/options unit)))

        :else (floor-text unit read-value)))))

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
   ::run-id (::run-id entry)
   ::detail detail
   ::text (case (::kind entry)
            :message (message-text unit entry detail)
            :eval (receipt-text unit entry detail))})

(defn reasoning-disclosure
  "A collapsed, exact reasoning display shared by live and settled HTML."
  {:malli/schema [:=> [:cat :string] :seon.render/hiccup]}
  [reasoning]
  (let [first-line (or (first (str/split-lines reasoning)) "")]
    [:details {:class "seon-attempt-reasoning"}
     [:summary [:span (str first-line "…")]]
     [:div {:class "seon-attempt-reasoning-body"}
      [:pre [:code reasoning]]]]))

(defn- reasoning-attempts
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.cluster.agent/id unit)
        connection (:seon.store/branch-connection unit)]
    (if (and db agent-id)
      (->> (db/q '[:find [?attempt ...]
                   :in $ ?agent-id
                   :where
                   [?agent :seon.cluster.agent/id ?agent-id]
                   [?run :seon.cluster.run/agent ?agent]
                   [?attempt :seon.ai.attempt/run ?run]
                   (or [?attempt :seon.ai.attempt/reasoning]
                       [?attempt :seon.ai.attempt/reasoning-blob])]
                 db agent-id)
           (pulled-many db reasoning-attempt-selector)
           (keep (fn [attempt]
                   (let [digest (:seon.ai.attempt/reasoning-blob attempt)
                         reasoning (or (:seon.ai.attempt/reasoning attempt)
                                       (when (and connection digest)
                                         (blob/get connection digest)))]
                     (when (string? reasoning)
                       {::kind :attempt
                        ::id (:seon.ai.attempt/id attempt)
                        ::at (:seon.ai.attempt/at attempt)
                        ::run-id (get-in attempt [:seon.ai.attempt/run
                                                  :seon.cluster.run/id])
                        ::reasoning reasoning}))))
           (sort-by entry-order)
           vec)
      [])))

(defn- entry-name
  [entry]
  (keyword (str "seon.transcript." (name (::kind entry))) (::id entry)))

(defn- marker-text
  [elided pinned?]
  (str elided " " (if pinned? "middle" "older") " transcript entr"
       (if (= 1 elided) "y" "ies") " elided by the token budget."))

(defn- ai-output
  [pinned entries elided]
  (str/join
   "\n\n"
   (cond-> []
     (seq pinned) (into (map ::text pinned))
     (pos? elided)
     (conj (str ";; transcript/elided " elided "\n;; "
                (marker-text elided (seq pinned))))
     (seq entries) (into (map ::text entries)))))

(defn- html-entries
  [entries]
  (into
   [:ol {:class "seon-transcript-list"}]
   (map (fn [entry]
          [:li {:id (block/surface-id (entry-name entry))
                :class (str "seon-transcript-entry"
                            (when (= :attempt (::kind entry))
                              " seon-transcript-attempt"))
                :data-transcript-id (::id entry)
                :data-transcript-kind (name (::kind entry))
                :data-transcript-detail (some-> (::detail entry) name)}
           (if (= :attempt (::kind entry))
             (reasoning-disclosure (::reasoning entry))
             [:pre [:code (::text entry)]])]))
   entries))

(defn- html-output
  [pinned entries elided]
  (into
   [:section {:id (block/surface-id :transcript)
              :class "seon-transcript"}]
   (cond-> []
     (seq pinned)
     (conj (html-entries pinned))
     (pos? elided)
     (conj [:p {:class "seon-transcript-elision"
                :data-transcript-elided (str elided)}
            (marker-text elided (seq pinned))])
     (seq entries)
     (conj (html-entries entries)))))

(defn- output-tokens
  [pinned entries elided]
  (max (tokens/estimate (ai-output pinned entries elided))
       (tokens/estimate
        (hiccup/->string (html-output pinned entries elided)))))

(defn- fits?
  [budget pinned entries elided]
  (<= (output-tokens pinned entries elided) budget))

(defn- best-summary
  [unit pinned entry newer older-count budget]
  (let [candidate (projected-entry unit entry :summary)]
    (when (fits? budget pinned (into [candidate] newer) older-count)
      candidate)))

(defn- projection
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.cluster.agent/id unit)
        total (history-count db agent-id)
        requested (max 0 (long (get unit ::token-budget 0)))
        candidate-limit (int (min Integer/MAX_VALUE
                                  (max recent-entry-count requested)))
        entries (if (and db agent-id)
                  (history db agent-id candidate-limit)
                  [])
        pinned (into []
                     (comp (filter ::pinned?)
                           (map #(projected-entry unit % :full)))
                     entries)
        candidates (into [] (remove ::pinned?) entries)
        pinned-count (count pinned)
        acquired (count candidates)
        unacquired (- total pinned-count acquired)
        ;; The floor is the exact smallest honest twin: the full pinned
        ;; bootstrap prefix plus the loud marker for everything after it.
        minimum (output-tokens pinned [] (- total pinned-count))
        budget (max requested minimum)
        recent-start (max 0 (- acquired recent-entry-count))]
    (loop [index (dec acquired)
           newer []]
      (if (neg? index)
        {::pinned pinned
         ::entries newer
         ::elided unacquired
         ::minimum-token-budget minimum
         ::token-budget budget}
        (let [entry (nth candidates index)
              recent? (<= recent-start index)
              full (when recent?
                     (projected-entry unit entry :full))
              with-full (when full (into [full] newer))
              older-count (+ unacquired index)]
          (cond
            (and with-full (fits? budget pinned with-full older-count))
            (recur (dec index) with-full)

            :else
            (if-let [summary
                     (best-summary
                      unit pinned entry newer older-count budget)]
              (recur (dec index) (into [summary] newer))
              {::pinned pinned
               ::entries newer
               ::elided (+ unacquired (inc index))
               ::minimum-token-budget minimum
               ::token-budget budget})))))))

(defn minimum-token-budget
  "Minimum budget preserving the bootstrap and a loud elision marker."
  {:malli/schema [:=> [:cat :seon.render/unit] [:int {:min 0}]]}
  [unit]
  (::minimum-token-budget (projection (assoc unit ::token-budget 0))))

(defn render-ai
  "Render one agent's bounded transcript as reader-valid REPL text."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [{::keys [pinned entries elided]} (projection unit)]
    (ai-output pinned entries elided)))

(defn render-html
  "Render the same bounded transcript with stable block and entry ids."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [{::keys [pinned entries elided]} (projection unit)]
    ;; Reasoning is deliberately joined only after the shared projection has
    ;; made every token-budget decision. It therefore changes neither the AI
    ;; bytes nor which transcript entries the agent receives.
    (html-output pinned
                 (sort-by entry-order
                          (into entries (reasoning-attempts unit)))
                 elided)))
