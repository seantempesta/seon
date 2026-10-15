(ns seon.render.transcript
  "One agent's messages, turns, and evaluation results.

  The renderer is the schema-declared agent-session projection. Messages are
  reverse connections while evaluations are reached through the
  agent's turns. Raw facts never acquire a detail level; every full, summary,
  and elided decision is derived for this call."
  (:require [seon.error.refusal]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.blob :as blob]
            [seon.cluster.message :as message]
            [seon.context :as context]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.error :as error]
            [seon.render.block :as block]
            [seon.render.route :as route]
            [seon.render.value :as value]
            [seon.repl :as repl]
            [seon.sci.admit :as admit]))

(def ^:private message-selector
  [:db/id
   :seon.message/id
   :seon.message/content
   :my.message/reason
   {:seon.message/to [:db/id :seon.agent/id]}
   {:seon.message/from [:db/id :seon.agent/id]}
   :seon.message/about])

(def ^:private receipt-selector
  ;; Plus every stored measure the declared bounds read (seon.repl/cost-attributes),
  ;; so the reply's findings see what the receipt recorded.
  (into
  [:db/id
   :seon.cluster.eval/id
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/source
   :seon.cluster.eval/read-basis-transaction
   :seon.eval/shown
   :seon.eval/renderer
   {:seon.eval/renderer-fn [:seon.fn/sym]}
   :seon.cluster.eval/error
   :seon.cluster.eval/diagnostics
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/output
   :seon.cluster.eval/comment
   :seon.cluster.eval/delimiter-repairs
   ;; THE FORM'S OWN PRINT OPTIONS, as evidence of the session's dynamic
   ;; bindings at evaluation time. They do NOT re-bound the shown text:
   ;; presentation is decided once, by the render profile, when the
   ;; evaluation stores what it showed (`src/seon/render/value.clj:448`).
   :seon.print/length
   :seon.print/level
   :seon.eval/duration-ms
   :seon.sci.eval/ending-ns
   :seon.error/at :seon.error/layer :seon.error/operation :seon.error/data-edn
   {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
   {:seon.cluster.eval/run
    [:db/id :seon.turn/id {:seon.turn/opened-tx [:db/id :db/txInstant]}
     {:seon.turn/agent
      [:db/id
       :seon.agent/id
       {:seon.agent/namespace [:db/id :seon.ns/name]}]}]}]
  (repl/cost-attributes)))

(def ^:private reasoning-attempt-selector
  [:db/id
   :seon.ai.attempt/id
   :seon.ai.attempt/at
   :seon.ai.attempt/reasoning
   :seon.ai.attempt/reasoning-blob
   :seon.ai.attempt/reasoning-size
   {:seon.turn/_attempts [:db/id :seon.turn/id]}])

(defn- recent-message-entities
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :int] [:or [:sequential [:tuple :int :inst :string :int]] :seon.db/error-result]]}
  [db agent-id limit]
  (db/q {:query
        '[:find ?message ?at ?ordinal ?tx
          :in $ ?agent-id
          :where
          [?agent :seon.agent/id ?agent-id]
          (or-join [?message ?agent]
                   [?message :seon.message/to ?agent]
                   [?message :seon.message/from ?agent])
          [?message :seon.message/to _ ?tx]
          [?tx :db/txInstant ?at]
          [?message :seon.message/id ?ordinal]]
        :args [db agent-id]
        :order-by '[?at :desc ?tx :desc ?ordinal :desc ?message :desc]
        :limit limit}))

(defn- recent-receipt-entities
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :int] [:or [:sequential [:tuple :int :inst :string]] :seon.db/error-result]]}
  [db agent-id limit]
  (db/q {:query
        '[:find ?receipt ?at ?id
          :in $ ?agent-id
          :where
          [?agent :seon.agent/id ?agent-id]
          [?run :seon.turn/agent ?agent]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/at ?at]
          [?receipt :seon.cluster.eval/id ?id]]
        :args [db agent-id]
        :order-by '[?at :desc ?id :desc]
        :limit limit}))

(defn- candidate-entity-ids
  [db agent-id limit]
  (let [recent
        (->> (concat
              (map (fn [[entity at ordinal tx]]
                     [:message entity at [tx ordinal entity]])
                   (recent-message-entities db agent-id limit))
              (map #(into [:eval] %)
                   (recent-receipt-entities db agent-id limit)))
             (sort-by (fn [[kind _ at id]]
                        [(.getTime ^java.util.Date at)
                         (case kind :message 0 :eval 2 :run 3)
                         id])
                      #(compare %2 %1))
             (take limit)
             (group-by first)
             (reduce-kv (fn [ids kind entities]
                          (assoc ids kind (mapv second entities)))
                        {}))]
    recent))

(defn- selected-run-entity-ids
  "Select a bounded prefix of one run's evaluations.

   The extra row is an omission sentinel; it avoids materializing an
   unbounded run merely to calculate an elision count."
  [db run-id limit]
  (let [bounded-limit (inc (max 0 (int limit)))]
    {:eval
     (mapv first
           (db/q {:query
                  '[:find ?receipt ?ordinal
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?receipt :seon.cluster.eval/run ?run]
                    [?receipt :seon.cluster.eval/ordinal ?ordinal]]
                  :args [db run-id]
                  :order-by '[?ordinal :asc]
                  :limit bounded-limit}))}))

(defn- pulled-many
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/pull-selector [:maybe [:sequential :seon.db/entity-id]]] [:or [:sequential [:maybe :seon.db/pulled-entity]] :seon.db/error-result]]}
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

(defn- message-order-facts
  [db message-ids]
  (if (seq message-ids)
    (into
     {}
     (map (fn [[message tx at]]
            [message {::transaction tx ::ordinal 0 ::at at}]))
     (db/q '[:find ?message ?tx ?at
            :in $ [?message ...]
            :where
            [?message :seon.message/to _ ?tx]
            [?tx :db/txInstant ?at]]
          db message-ids))
    {}))

(defn- message-entry
  [database run-id agent-id orders message]
  (let [subject (:seon.message/about message)]
    (merge
     {::kind :message
      ::entity message
      ::id (:seon.message/id message)
      ::content (:seon.message/content message)
      ::from (get-in message [:seon.message/from
                              :seon.agent/id])
      ::to (get-in message [:seon.message/to
                            :seon.agent/id])
      ::about subject
      ::reason (:my.message/reason message)}
     {::custody
      (context/message-custody database run-id agent-id (:db/id message))}
     (get orders (:db/id message)))))

(defn- receipt-entry
  "One evaluation entity as a transcript entry. The entity itself is what
  `seon.repl/entity-emission` renders, so it carries its resolved namespace
  name here (the pull's own, else its agent's, else `user`)."
  {:malli/schema [:=> [:cat :map] :map]}
  [receipt]
  (let [namespace-name (or (get-in receipt [:seon.cluster.eval/ns :seon.ns/name])
                           (get-in receipt [:seon.cluster.eval/run
                                            :seon.turn/agent
                                            :seon.agent/namespace
                                            :seon.ns/name])
                           'user)]
    {::kind :eval
     ::entity (assoc-in receipt [:seon.cluster.eval/ns :seon.ns/name] namespace-name)
     ::id (:seon.cluster.eval/id receipt)
     ;; A FROZEN EVALUATION THAT NEVER STARTED HAS NO START INSTANT, and it
     ;; is still a transcript entry — the run's own opening is when the agent
     ;; wrote it, which is exactly what the retired `:input` kind used.
     ::at (or (:seon.cluster.eval/at receipt)
              (get-in receipt [:seon.cluster.eval/run
                               :seon.turn/opened-tx :db/txInstant]))
     ::ordinal (:seon.cluster.eval/ordinal receipt)
     ::run-id (get-in receipt [:seon.cluster.eval/run
                               :seon.turn/id])
     ::run-opened-at (get-in receipt [:seon.cluster.eval/run
                                      :seon.turn/opened-tx :db/txInstant])
     ::source (:seon.cluster.eval/source receipt)
     ::read-basis (:seon.cluster.eval/read-basis-transaction receipt)
     ::error (:seon.cluster.eval/error receipt)}))

(defn- entry-order
  [entry]
  (let [at (.getTime ^java.util.Date (::at entry))]
    (cond
      :else
      (case (::kind entry)
        :message [1 at 0
                  (::transaction entry)
                  (::ordinal entry)
                  (get-in entry [::entity :db/id])]
        :attempt [1 at 1 nil nil (::id entry)]
        :eval [1 at 3
               (.getTime ^java.util.Date (::run-opened-at entry))
               (::ordinal entry)
               (::id entry)]
        :run [1 at 4
              (.getTime ^java.util.Date (::run-opened-at entry))
              nil
              (::id entry)]))))

(defn- entry-root
  "The durable identity every value this entry renders is rooted at.

  Derived from the entity's own declared unique identity attribute, so a new
  transcript entry kind is rooted without a per-kind rule; the entity id is
  the honest fallback when a pulled entity carries no identity attribute."
  [identity-attrs entry]
  (let [entity (::entity entry)]
    (or (some (fn [attribute]
                (when-some [identity-value (get entity attribute)]
                  [attribute identity-value]))
              identity-attrs)
        [:db/id (:db/id entity)])))

(defn- read-or-throw
  "A read's value, or its refusal thrown whole."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [result]
  (if (and (map? result) (:seon.error/at result))
    (throw (ex-info (str (:seon.error/message result "A branch-error read refused.")) result))
    result))

(defn- fault-values
  "Each referenced error as its agent reads it: the value of its latest fact
  (`seon.error/value` of `seon.error/latest-fact`) with its occurrence count
  and origin branch, and its resolution when resolved. A refused read is
  thrown whole."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector {:min 1} :seon.db/ref]] [:vector {:min 1} :map]]}
  [db errors]
  (let [selector (error/observation-selector (db/carried-projection db))]
    (mapv (fn [error]
            (let [pulled (read-or-throw (db/pull db selector error))
                  resolved (read-or-throw (error/resolution db (:seon.error/signature pulled)))
                  ;; ONE ERROR, SHOWN ONCE: its value (message once, evidence
                  ;; by blob reference) and what this window adds; the whole
                  ;; observation stays reachable by :seon.error/id.
                  fact (error/latest-fact pulled)]
              (cond-> (merge (error/value fact)
                             (select-keys fact [:seon.error.occurrence/count
                                                :seon.error.occurrence/branch]))
                resolved (assoc :seon.error/resolution resolved))))
          errors)))

(defn- branch-errors
  "The errors each settled evaluation's settlement saved on it
  (`:seon.cluster.eval/faults`, `seon.turn/fault-window`: the window
  (previous settlement, this settlement], read once by the writer), keyed by
  evaluation entity id, for `:all` of the agent's evaluations or the `shown`
  set, each as its `fault-values`. Nothing is reconstructed from occurrence
  counters. A refused read is thrown whole."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id
                       [:or [:= :all] [:set :int]]]
                  [:map-of :int [:vector {:min 1} :map]]]}
  [db agent-id shown]
  (let [entities (read-or-throw
              (db/q '[:find ?e ?error :in $ ?agent-id
                      :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent]
                      [?e :seon.cluster.eval/run ?run] [?e :seon.cluster.eval/faults ?error]]
                    db agent-id))]
    (into {}
          (keep (fn [[eid group]]
                  (when (or (= :all shown) (contains? shown eid))
                    [eid (fault-values db (mapv second group))])))
          (group-by first entities))))

(defn- history
  ([db run-id agent-id limit]
   (history db run-id agent-id limit nil))
  ([db run-id agent-id limit selected-run-id]
   (let [ids (cond
               selected-run-id (selected-run-entity-ids db selected-run-id limit)
               :else (candidate-entity-ids db agent-id limit))
        messages (pulled-many db message-selector (:message ids))
        receipts (pulled-many db receipt-selector (:eval ids))
        identity-attrs (identity-attributes db)
        message-orders (message-order-facts db (:message ids))]
    (->> (concat (map (partial message-entry db run-id agent-id
                               message-orders)
                      messages)
                 (map receipt-entry receipts))
         (map (fn [entry]
                (assoc entry ::root (entry-root identity-attrs entry))))
         (sort-by entry-order)
         vec))))

(defn- floor-text
  "One history value as the value renderer's text, the one clipping spot.
  Without an SCI context the renderer skips declared pairs and prints the
  value structurally (`seon.render.value/value-node*`)."
  {:malli/schema [:=> [:cat :seon.render/unit [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A transcript history value is an arbitrary Clojure value; the value renderer owns its presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}]] [:or :string :seon.render.value/missing-root-identity-error :seon.render.value/insufficient-floor-budget-error]]}
  [unit value]
  ;; Transcript values are immutable history entries. Give the shared value
  ;; floor an explicit, content-stable block identity when this internal
  ;; projection is not itself running as a retained render call.
  (value/render-ai
   (cond-> (assoc unit :seon.render/value value)
     (nil? (:seon.render.call/id unit))
     (assoc :seon.render.call/id [::history-value value]))))

(defn- message-text
  [unit entry]
  (let [entity (cond-> (::entity entry)
                 (::content entry)
                 (assoc :seon.message/content (::content entry)))
        ; History already holds the message facts. The entry names the
        ; ordinary read form for a future evaluation; the terminal formatter
        ; shows the content here without executing that form.
        sentence (str (pr-str (list 'my.message/read {:my.message/id (:seon.message/id entity)})) "\n"
                      (message/format-ai (assoc entity :seon.db/db (:seon.db/db unit))))
        extra (cond-> {}
                (::about entry)
                (assoc :seon.message/about (::about entry))
                (::reason entry) (assoc :my.message/reason (::reason entry)))]
    (str (case (::custody entry)
           :seon.context/current-trigger "Current run instruction:\n"
           :seon.context/pending
           "Pending message — awaiting its own run; not this run's instruction:\n"
           "")
         sentence
         (when (seq extra) (str "\n" (floor-text unit extra))))))

(defn- projected-entry
  [unit entry detail]
  ;; Every value this entry renders through the floor is rooted at the entry's
  ;; own durable identity, so `value/node-id` never refuses for a
  ;; caller-supplied root the entry already has, and an elided value names a
  ;; requery identity a reader can actually pull.
  (let [unit (assoc unit :seon.render.value/root (::root entry))]
    {::kind (::kind entry)
     ::id (::id entry)
     ::at (::at entry)
     ::ordinal (::ordinal entry)
     ::run-id (::run-id entry)
     ::run-opened-at (::run-opened-at entry)
     ::detail detail
     ::execution-error? (some? (::error entry))
     ::text (case (::kind entry)
              :message (message-text unit entry)
              ;; One frozen form or settled evaluation, through the one REPL
              ;; generator: `repl/text` emits a prompt line and no response
              ;; when nothing has settled, so running needs no second arm.
              :eval (repl/text (repl/entity-emission
                          (assoc (::entity entry) :seon.db/db (:seon.db/db unit))))
              ;; The run's own `:seon.render/ai`, with no grammar of its own.
              :run (or (turn/render-ai (::entity entry)) ""))}))

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
        agent-id (:seon.agent/id unit)
        selected-run-id (::selected-run-id unit)
        connection (:seon.db/connection unit)]
    (if (and db agent-id)
      (->> (if selected-run-id
            (db/q '[:find [?attempt ...]
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?run :seon.turn/attempts ?attempt]
                    (or [?attempt :seon.ai.attempt/reasoning]
                        [?attempt :seon.ai.attempt/reasoning-blob])]
                  db selected-run-id)
            (db/q '[:find [?attempt ...]
                   :in $ ?agent-id
                   :where
                   [?agent :seon.agent/id ?agent-id]
                   [?run :seon.turn/agent ?agent]
                   [?run :seon.turn/attempts ?attempt]
                   (or [?attempt :seon.ai.attempt/reasoning]
                       [?attempt :seon.ai.attempt/reasoning-blob])]
                  db agent-id))
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
                        ::run-id (get-in attempt [:seon.turn/_attempts 0
                                                  :seon.turn/id])
                        ::reasoning reasoning}))))
           (sort-by entry-order)
           vec)
      [])))

(defn- ai-output
  ;; NO ELISION HERE. Presentation elides in exactly one place — the value
  ;; renderer's AI projection (`seon.render.value`) — so the transcript
  ;; renders the history its own QUERY bound admitted and hands each value
  ;; to that renderer. The token ladder this namespace carried was a second
  ;; elision mechanism whose only driver (`best-summary`) had no caller, and
  ;; whose reported count came from the history query's limit rather than
  ;; from any budget.
  [entries]
  (str/join "\n\n" (map ::text entries)))

(defn- html-entries
  [entries]
  (into
   [:ol {:class "seon-transcript-list"}]
   (map (fn [entry]
          [:li (cond->
                 {:class (str "seon-transcript-entry"
                              (when (= :attempt (::kind entry))
                                " seon-transcript-attempt"))
                  :data-transcript-id (::id entry)
                  :data-transcript-kind (name (::kind entry))
                  :data-transcript-detail (some-> (::detail entry) name)}
                 (::execution-error? entry)
                 (assoc :data-transcript-error "true"))
           (if (= :attempt (::kind entry))
             (reasoning-disclosure (::reasoning entry))
             [:pre [:code (::text entry)]])]))
   entries))

(defn- html-output
  ;; HTML is not bounded at all (owner ruling, 2026-09-07): the page serves
  ;; the entries the transcript holds. A turn renders as a node of a page, so
  ;; it carries no page element ids: two turns on one page never collide.
  [entries]
  (into
   [:section {:class "seon-transcript"}]
   (map html-entries)
   (filter seq [entries])))

(defn- with-errors
  "`entry` with `errors` printed once, by the value printer, as its
  `:seon.repl/errors`, rooted at the entry's own identity."
  {:malli/schema [:=> [:cat :seon.render/unit :map [:vector {:min 1} :map]] :map]}
  [unit entry errors]
  (let [text (floor-text (assoc unit :seon.render.value/root (::root entry)) errors)]
    (if (string? text)
      (assoc-in entry [::entity :seon.repl/errors] text)
      (throw (ex-info (str (:seon.error/message text)) text)))))

(defn- held-entry
  "One evaluation the caller holds (in flight, or the receipt its settlement
  wrote) with its admitted form, as the transcript entry its stored entity
  would be: the form's source and comment, the evaluation's namespace else
  the form's, its run and identity, and its faults' errors."
  {:malli/schema [:=> [:cat :seon.render/unit [:vector :qualified-keyword] :seon.turn.loop/evaluated-source] :map]}
  [unit identity-attrs {form :seon.turn.loop/admitted-form
                        evaluation :seon.sci.eval/evaluation
                        ordinal :seon.cluster.eval/ordinal}]
  (let [db (:seon.db/db unit)
        run-id (:seon.turn/id unit)
        ;; what the writer stores: the evaluation's namespace overrides the frozen form's
        namespace-ref (or (:seon.cluster.eval/ns evaluation) (:seon.cluster.eval/ns form))
        namespace-name (if (map? namespace-ref) (:seon.ns/name namespace-ref) (second namespace-ref))
        entry (receipt-entry
               (cond-> (assoc evaluation
                              :seon.cluster.eval/source (:seon.cluster.eval/source form)
                              :seon.cluster.eval/id (turn/receipt-identity run-id ordinal)
                              :seon.cluster.eval/run [:seon.turn/id run-id]
                              :seon.cluster.eval/ordinal ordinal
                              :seon.cluster.eval/read-basis-transaction
                              (or (:seon.cluster.eval/read-basis-transaction evaluation)
                                  (db/basis-t db)))
                 namespace-name (assoc :seon.cluster.eval/ns {:seon.ns/name namespace-name})
                 ;; THE COMMENT IS A FACT OF THE FORM THE AGENT WROTE, and it
                 ;; travels on the admitted form until settlement moves it to
                 ;; the evaluation.
                 (:seon.cluster.eval/comment form)
                 (assoc :seon.cluster.eval/comment (:seon.cluster.eval/comment form))
                 (:seon.cluster.eval/delimiter-repairs form)
                 (assoc :seon.cluster.eval/delimiter-repairs (:seon.cluster.eval/delimiter-repairs form))
                 ;; The print options the form set: stored they are
                 ;; `:seon.print/length` / `/level`, in flight they are the
                 ;; evaluation's `:seon.print/options` map.
                 (int? (get-in evaluation [:seon.print/options :seon.print/length]))
                 (assoc :seon.print/length (get-in evaluation [:seon.print/options :seon.print/length]))
                 (int? (get-in evaluation [:seon.print/options :seon.print/level]))
                 (assoc :seon.print/level (get-in evaluation [:seon.print/options :seon.print/level]))))
        entry (assoc entry ::root (entry-root identity-attrs entry))
        faults (:seon.cluster.eval/faults evaluation)]
    (if (seq faults)
      (with-errors unit entry (fault-values db (vec faults)))
      entry)))

(defn- candidate-history
  "The shared source of transcript entries: the evaluations the caller holds
  (`held-entry`), else stored history."
  {:malli/schema [:=> [:cat :seon.render/unit] [:vector :map]]}
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.agent/id unit)
        candidate-count (admit/required-cap
                         (:seon.sci.admit/caps unit)
                         :seon.config.eval.result/max-nodes)
        evaluated-sources (:seon.turn.loop/evaluated-sources unit)]
    (if (some? evaluated-sources)
      (let [identity-attrs (identity-attributes db)]
        (mapv #(held-entry unit identity-attrs %) evaluated-sources))
      (let [entries (history db (:seon.turn/id unit) agent-id
                             candidate-count (::selected-run-id unit))
            evaluations (into #{} (keep #(when (= :eval (::kind %)) (get-in % [::entity :db/id])))
                              entries)
            ;; a caller rendering many runs derives the agent's windows once
            errors (cond (::branch-errors unit) (::branch-errors unit)
                         (seq evaluations) (branch-errors db agent-id evaluations)
                         :else {})]
        ;; The branch's new errors are bounded once, by the value renderer.
        (mapv (fn [entry]
                (if-some [found (get errors (get-in entry [::entity :db/id]))]
                  (with-errors unit entry found)
                  entry))
              entries)))))

(defn- projection
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.agent/id unit)
        entries (if (or (some? (:seon.turn.loop/evaluated-sources unit))
                        (and db agent-id))
                  (candidate-history unit)
                  [])]
    {::entries (mapv #(projected-entry unit % :full) entries)}))

(defn render-ai
  "Render one agent's bounded messages and faithful REPL session."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (ai-output (::entries (projection unit))))

(defn- selected-run-identities
  {:malli/schema [:=> [:cat :seon.render/unit] :map]}
  [unit]
  (let [db (:seon.db/db unit)
        agent (:seon.turn/agent unit)
        agent-ref (if (map? agent) (:db/id agent) agent)
        supplied-agent-id (when (map? agent) (:seon.agent/id agent))
        queried (when (and (nil? supplied-agent-id)
                           db (integer? agent-ref))
                  (db/q '[:find ?agent-id .
                          :in $ ?agent
                          :where
                          [?agent :seon.agent/id ?agent-id]]
                        db agent-ref))
        ;; A run the database does not hold is not selected: its turn would
        ;; pull a nil opened-tx.
        run (when (and db (:seon.turn/id unit))
              (db/q '[:find ?run . :in $ ?id :where [?run :seon.turn/id ?id]]
                    db (:seon.turn/id unit)))]
    {::selected-run-id (when (integer? run) (:seon.turn/id unit))
     ::selected-agent-id
     (or supplied-agent-id (when-not (db/read-refusal? queried) queried))
     ::selected-run-error (some #(when (db/read-refusal? %) %) [queried run])}))

(defn- request-error
  "One refused render request, declared as `:seon.render.transcript/request-error`."
  [operation data]
  (assoc (seon.error.refusal/diagnostic (java.util.Date.) :seon.render.transcript/render operation data) :seon.error/type :seon.render.transcript/request-error))

(defn- missing-selected-run
  {:malli/schema [:=> [:cat :seon.render/unit :map] :seon.render.transcript/request-error]}
  [unit identities]
  (request-error 'seon.render.transcript/missing-selected-run
                 {:seon.error/message "The selected run is unavailable because its run, agent, or database identity is missing."
                  :seon.error/fix "Supply the expected member and repeat the requested operation."
                  :seon.error/member :seon.turn/turn
                  :seon.render.transcript/refused-member :seon.turn/turn
                  :seon.error/expected [:seon.db/db :seon.turn/id :seon.agent/id]
                  :seon.error/offending unit
                  :seon.render.transcript/selection-identities identities}))

(defn render-run-ai
  "Render only the selected turn's state and evaluations."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.render.transcript/error-result]]}
  [unit]
  (let [{run-id ::selected-run-id agent-id ::selected-agent-id
         identity-error ::selected-run-error :as identities}
        (selected-run-identities unit)]
    (cond
      identity-error identity-error
      (and (:seon.db/db unit) run-id agent-id)
      (str/join "\n\n"
                (remove str/blank?
                        [(turn/render-ai unit)
                         (render-ai (assoc unit :seon.agent/id agent-id
                                           ::selected-run-id run-id))]))
      :else (missing-selected-run unit identities))))

(defn message-form
  "Return the ordinary message read form for one message entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/read-form]}
  [unit]
  (list 'my.message/read {:my.message/id (:seon.message/id unit)}))

(defn- entry-basis
  [db entry]
  (reduce (fn [latest datom]
            (max latest (long (or (:tx datom) 0))))
          0
          (db/datoms db :eavt (get-in entry [::entity :db/id]))))

(defn history-entries
  "Return evaluated forms as immutable REPL entries.

   Use supplied in-memory evaluations when present, otherwise query stored
   evaluations. Both use the same result formatting. This function neither
   executes source nor persists it."
  {:malli/schema [:=> [:cat :seon.render/unit] [:vector :map]]}
  [unit]
  (let [db (:seon.db/db unit)
        held? (some? (:seon.turn.loop/evaluated-sources unit))
        candidates (candidate-history unit)
        entries
        (mapv
         (fn [entry]
           ;; ONE GENERATOR. The bytes are `seon.repl/text`'s, so this unit,
           ;; the debug page's AI column and the provider prompt cannot drift
           ;; apart: there is no second place that decides how an evaluation
           ;; reads.
           (let [emitted (repl/entity-emission
                          ;; a held evaluation is this pass's own: nothing to
                          ;; resolve or compare against stored history
                          (if held? (::entity entry) (assoc (::entity entry) :seon.db/db db)))]
             {:seon.render.history/call-id
              [:seon.render.transcript/entry (::kind entry) (::id entry)]
              :seon.cluster.eval/ordinal (::ordinal entry)
              :seon.render.history/basis-transaction
              (or (::read-basis entry) (entry-basis db entry))
              :seon.render.history/form (::source entry)
              :seon.render.history/printed-value (repl/response emitted)
              :seon.render.history/bytes (repl/text emitted)}))
         (filterv #(= :eval (::kind %)) candidates))]
    entries))

(defn render-html
  "Render the same bounded transcript with stable block and entry ids."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  ;; Reasoning is joined only after the shared projection, so it changes
  ;; neither the AI bytes nor which transcript entries the agent receives.
  (html-output (sort-by entry-order
                        (into (::entries (projection unit)) (reasoning-attempts unit)))))

(defn- turn-header
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/id] :seon.render/hiccup]}
  [database turn-id]
  (let [entity (db/pull database
                     '[:seon.turn/id {:seon.turn/opened-tx [:db/id :db/txInstant]} :seon.turn/reply-size
                       {:seon.turn/trigger [:seon.message/id]}]
                     [:seon.turn/id turn-id])
        evaluations (db/q '[:find (count ?evaluation) . :in $ ?id
                            :where [?turn :seon.turn/id ?id]
                                   [?evaluation :seon.cluster.eval/run ?turn]]
                          database turn-id)]
    (if (db/read-refusal? entity)
      [:p (:seon.error/message entity)]
      [:article {:class "seon-turn-header"}
       [:h3 (str "Turn " turn-id)]
       [:dl
        [:dt "Opened"] [:dd (pr-str (get-in entity [:seon.turn/opened-tx :db/txInstant]))]
        [:dt "Trigger"] [:dd (or (get-in entity [:seon.turn/trigger :seon.message/id]) "None")]
        [:dt "Evaluations"] [:dd (if (db/read-refusal? evaluations)
                                   (:seon.error/message evaluations)
                                   (str (or evaluations 0)))]
        [:dt "Reply"] [:dd (if (find entity :seon.turn/reply-size) "Recorded" "None")]]])))

(def ^:private member-selector
  [:seon.test.member/symbol :seon.test.member/began? :seon.test.member/ended?
   :seon.test.member/pass-count :seon.test.member/fail-count :seon.test.member/error-count
   {:seon.test.member/failures [:seon.test.failure/message]}])

(defn- member-status
  "A test member's live status: `:pending` before its body began, `:running` until it ended, then `:green` or `:red`."
  {:malli/schema [:=> [:cat :map] [:enum :pending :running :green :red]]}
  [member]
  (cond (not (:seon.test.member/began? member)) :pending
        (not (:seon.test.member/ended? member)) :running
        (pos? (+ (:seon.test.member/fail-count member 0) (:seon.test.member/error-count member 0))) :red
        :else :green))

(defn step-tests
  "The tests the automatic step of run `run-id` selected on `database`, its agent's branch: the members of every test
  run whose selection transaction falls from the run's opening up to its agent's next run's opening, each with its
  live status (`member-status`), by test symbol."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/id] [:vector :map]]}
  [database run-id]
  (let [[opened owner] (db/q '[:find [?opened ?agent] :in $ ?id
                               :where [?run :seon.turn/id ?id] [?run :seon.turn/opened-tx ?opened]
                                      [?run :seon.turn/agent ?agent]]
                             database run-id)
        next-opening (when opened
                       (db/q '[:find (min ?later) . :in $ ?agent ?opened
                               :where [?run :seon.turn/agent ?agent] [?run :seon.turn/opened-tx ?later]
                                      [(> ?later ?opened)]]
                             database owner opened))
        runs (when opened
               (db/q '[:find [?test-run ...] :in $ ?from ?until
                       :where [?test-run :seon.test.run/selection-tx ?tx] [(>= ?tx ?from)] [(< ?tx ?until)]]
                     database opened (or next-opening Long/MAX_VALUE)))]
    (into [] (comp (mapcat #(:seon.test.run/members (db/pull database [{:seon.test.run/members member-selector}] %)))
                   (map #(assoc % :seon.render.transcript/status (member-status %))))
          (sort runs))))

(defn- test-html
  {:malli/schema [:=> [:cat :map] :seon.render/hiccup]}
  [member]
  (let [status (name (:seon.render.transcript/status member))
        test-name (str (:seon.test.member/symbol member))]
    (into [:li {:class (str "seon-test-" status) :data-status status :data-test test-name}
           [:code test-name] (str " " status)]
          (keep #(some->> (:seon.test.failure/message %) (vector :div {:class "seon-test-failure"})))
          (:seon.test.member/failures member))))

(defn- cost-html
  "One evaluation's stored cost: wall milliseconds, allocated megabytes and the calls its code made."
  {:malli/schema [:=> [:cat :map] :seon.render/hiccup]}
  [evaluation]
  [:li {:class "seon-eval-cost" :data-ordinal (str (:seon.cluster.eval/ordinal evaluation))}
   (str "form " (:seon.cluster.eval/ordinal evaluation)
        (some->> (:seon.eval/duration-ms evaluation) (str " · ms "))
        (some->> (:seon.eval/allocated-bytes evaluation) double (* 1e-6) (format " · %.1f MB"))
        (some->> (:seon.cluster.eval/call-count evaluation) (str " · calls ")))])

(defn- step-html
  "Run `run-id`'s automatic test step and each evaluation's cost, as the owner watches them live."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.turn/id] :seon.render/hiccup]}
  [database run-id]
  (let [tests (step-tests database run-id)
        costs (sort-by :seon.cluster.eval/ordinal
                       (db/q '[:find [(pull ?e [:seon.cluster.eval/ordinal :seon.eval/duration-ms
                                                 :seon.eval/allocated-bytes :seon.cluster.eval/call-count]) ...]
                               :in $ ?id :where [?run :seon.turn/id ?id] [?e :seon.cluster.eval/run ?run]]
                             database run-id))]
    [:section {:class "seon-eval-step"}
     (into [:ul {:class "seon-eval-costs"}] (map cost-html) costs)
     (if (seq tests)
       (into [:ul {:class "seon-test-step"}] (map test-html) tests)
       [:p {:class "seon-test-step"} "No tests ran."])]))

(defn render-run-html
  "Show the selected turn's header, state and evaluations."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.render.transcript/error-result]]}
  [unit]
  (let [{run-id ::selected-run-id agent-id ::selected-agent-id
         identity-error ::selected-run-error
         :as identities}
        (selected-run-identities unit)]
    (cond
      identity-error identity-error
      (and (:seon.db/db unit) run-id agent-id)
      [:section {:class "seon-turn" :data-turn run-id}
       (turn-header (:seon.db/db unit) run-id)
       [:p (turn/render-ai unit)]
       (render-html (assoc unit :seon.agent/id agent-id
                          ::selected-run-id run-id))
       (step-html (:seon.db/db unit) run-id)]
      :else (missing-selected-run unit identities))))

;;; ---------------------------------------------------------------------------
;;; The history unit
;;;
;;; `:seon.turn/_agent` is the agent's own REPL past. It reaches both
;;; projections through ONE derivation — `history-entries`, the same function
;;; and the same result printer the run loop itself uses — so what a person
;;; reads on the page and what an agent reads in its context are the same
;;; bytes. The bound is the render profile's child count, and what it leaves
;;; out is an ordinary elision value and not a truncation.
;;; ---------------------------------------------------------------------------

(def ^:private history-run-selector
  [:seon.turn/id
   {:seon.turn/opened-tx [:db/id :db/txInstant]}
   {:seon.turn/closed-tx [:db/id :db/txInstant]}])

(defn- agent-history-under
  "The history entities bounded by one cluster's effective configuration."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.config/effective]
                  [:or :seon.render.transcript/history :seon.db/error-result]]}
  [database agent-id effective]
  (let [limit (long (:seon.config.render.agent/max-children effective))
        caps (config/result-caps effective)
        entities (db/q {:query '[:find ?run ?opened
                             :in $ ?agent-id
                             :where
                             [?agent :seon.agent/id ?agent-id]
                             [?run :seon.turn/agent ?agent]
                             [?run :seon.turn/id _]
                             [?run :seon.turn/opened-tx ?opened]]
                    :args [database agent-id]
                    :order-by '[?opened :desc ?run :desc]})]
    (if (db/read-refusal? entities)
      entities
      (let [total (count entities)
            newest (into [] (take limit) entities)
            errors (branch-errors database agent-id :all)
            runs
            (into []
                  (keep
                   (fn [[eid _opened]]
                     (let [entity (db/pull database history-run-selector eid)]
                       (when-not (db/read-refusal? entity)
                         (assoc entity
                                :seon.render.transcript/entries
                                (history-entries
                                 {:seon.db/db database
                                  :seon.agent/id agent-id
                                  :seon.sci.admit/caps caps
                                  ::branch-errors errors
                                  ::selected-run-id
                                  (:seon.turn/id entity)}))))))
                  newest)
            omitted (- total (count runs))]
        (cond-> {:seon.agent/id agent-id
                 :seon.render.transcript/runs runs}
          (pos? omitted)
          (assoc :seon.render.transcript/older-runs
                 {:seon.print/face :seon.print/elided
                  :seon.print/omitted omitted
                  :seon.print/elision-unit :children
                  :seon.render.data/total total
                  :seon.render.data/path [:seon.render.transcript/runs]
                  :seon.render.data/next-offset (count runs)
                  :seon.render.profile/id :seon.render.profile/agent
                  :seon.print/requery-id
                  [:seon.agent/id agent-id]}))))))


(defn agent-history
  "This agent's own submitted forms and their stored results, newest run first.

  The runs are bounded by the cluster's declared agent child count; the runs
  beyond it are an ordinary elision value carrying their count and a requery
  identity, so nothing is silently dropped."
  {:malli/schema [:=> [:cat :seon.render.transcript/history-request]
                  [:or :seon.render.transcript/history :seon.db/error-result
                   :seon.config/error :seon.schema/validation-refusal]]}
  [{database :seon.db/db agent-id :seon.agent/id cluster-name :seon.boot/cluster-name}]
  (let [effective (config/effective database cluster-name)]
    (if (:seon.error/at effective)
      effective
      (agent-history-under database agent-id effective))))

(defn render-history-ai
  "The prompt is this concern's AI projection, so emit no duplicate text."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.db/database-value] [:or :string :seon.db/error-result]]}
  [_turns _database]
  "")

(defn render-history-html
  "Show all turn headers, newest first, without repeating evaluations."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The value renderer and its projections operate on arbitrary Clojure results, including scalar and nil results; the render profile owns presentation bounds.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.db/database-value] :seon.render/hiccup]}
  [turns database]
  (let [entities (if (and (coll? turns) (every? map? turns)) turns [])
        ordered (sort-by (juxt #(get-in % [:seon.turn/opened-tx :db/id]) :seon.turn/id)
                         #(compare %2 %1) entities)]
    (into [:section {:class "seon-turn-history"}
           [:h2 (str "Turns (" (count entities) ")")]]
          (map #(turn-header database (:seon.turn/id %)) ordered))))

(defn- runtime-owner

  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.agent/id :seon.render.transcript/request-error]]}
  [unit]
  (let [runtime (or (:seon.render/value unit) unit)
        owner (:seon.runtime/agent runtime)
        agent-id (or (when owner
                       (:seon.agent/id
                        (db/pull (:seon.db/db unit) [:seon.agent/id]
                                 (if (map? owner) (:db/id owner) owner))))
                     (:seon.agent/id unit))]
    (or agent-id
        (request-error 'seon.render.transcript/runtime-owner
                       {:seon.error/message "The runtime component's owner could not be resolved."
                        :seon.error/fix "Supply the expected member and repeat the requested operation."
                        :seon.render.transcript/refused-member :seon.agent/runtime
                        :seon.error/member :seon.runtime/agent
                        :seon.error/expected :seon.agent/id
                        :seon.error/offending unit}))))

(def ^:private runtime-selector
  '[{:seon.agent/runtime
     [{:seon.runtime/trigger
       [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]}]}
      {:seon.runtime/listens [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}])

(defn render-runtime-ai
  "My trigger and listens as data, offering the read of my turns.

  The turns are left out: every run changes them, and the read of this
  face must not."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render.transcript/runtime-view :seon.render.transcript/error-result
                   :seon.db/error-result]]}
  [unit]
  (let [agent-id (runtime-owner unit)
        pulled (when-not (:seon.render.transcript/refused-member agent-id)
                 (db/pull (:seon.db/db unit) runtime-selector [:seon.agent/id agent-id]))]
    (cond
      (:seon.render.transcript/refused-member agent-id) agent-id
      (db/read-refusal? pulled) pulled
      :else
      (assoc (get pulled :seon.agent/runtime {})
             :seon.render/operations
             [(list 'seon.db/pull ''[{:seon.turn/_agent [:seon.turn/id]}] [:seon.agent/id agent-id])]))))

(defn render-runtime-form
  "The read that finds my trigger and listens again. Shown, never executed."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/read-form :seon.render.transcript/error-result]]}
  [unit]
  (let [agent-id (runtime-owner unit)]
    (if (:seon.render.transcript/refused-member agent-id)
      agent-id
      (list 'seon.db/pull (list 'quote runtime-selector) [:seon.agent/id agent-id]))))

(def ^:private runtime-message-selector
  '[:seon.message/id :seon.message/content
    {:seon.message/from [:seon.agent/id]}
    {:seon.message/to [:seon.agent/id]}])

(def ^:private runtime-turn-selector
  [:seon.turn/id :seon.turn/reply :seon.turn/reply-blob
   {:seon.turn/opened-tx [:db/txInstant]}
   {:seon.turn/closed-tx [:db/txInstant]}
   {:seon.turn/trigger runtime-message-selector}
   {:seon.cluster.eval/_run [:seon.cluster.eval/id]}])

(defn- runtime-time [instant]
  (if (inst? instant)
    (let [iso (str (.toInstant ^java.util.Date instant))]
      [:time {:datetime iso
              :data-text (str "new Date('" iso "').toLocaleTimeString()")}
       (.format (java.text.SimpleDateFormat. "HH:mm:ss") instant)])
    "Time unavailable"))

(defn- runtime-duration [opened closed]
  (if (and (inst? opened) (inst? closed))
    (let [millis (- (.getTime ^java.util.Date closed)
                    (.getTime ^java.util.Date opened))]
      (cond
        (neg? millis) "Duration unavailable"
        (< millis 1000) (str millis " ms")
        (< millis 60000) (format "%.1f s" (/ millis 1000.0))
        (< millis 120000) (format "%.0f s" (/ millis 1000.0))
        (< millis 3600000) (str (quot millis 60000) " min")
        (< millis 86400000) (str (quot millis 3600000) " h " (mod (quot millis 60000) 60) " min")
        :else (str (quot millis 86400000) " d " (mod (quot millis 3600000) 24) " h")))
    "Duration unavailable"))

(defn- runtime-message-link [trigger]
  (when-let [message-id (:seon.message/id trigger)]
    [:a {:href (route/path ::route/data {} {:entity (pr-str [:seon.message/id message-id])})}
     (str "Message from " (or (get-in trigger [:seon.message/from :seon.agent/id])
                               "outside this cluster"))]))

(defn- turn-reply [connection entity]
  (or (:seon.turn/reply entity)
      (when (and connection (:seon.turn/reply-blob entity))
        (blob/get connection (:seon.turn/reply-blob entity)))))

(defn- runtime-turn-table [turns latest-trigger connection]
  [:details {:class "seon-turn-history" :data-preserve-attr "open"}
   [:summary (str "Turns (" (count turns) ")")]
   (if (seq turns)
     [:table {:class "seon-runtime-turns" :style {:width "100%" :text-align "left"}}
      [:thead [:tr (for [label ["Opened" "Duration" "Trigger" "Evaluations" "Reply"]]
                     [:th {:scope "col"} label])]]
      [:tbody
       (for [[index entity] (map-indexed vector turns)]
         (let [opened (get-in entity [:seon.turn/opened-tx :db/txInstant])
               closed (get-in entity [:seon.turn/closed-tx :db/txInstant])
               trigger (or (:seon.turn/trigger entity)
                           (when (zero? index) latest-trigger))
               reply (turn-reply connection entity)]
           [:tr
            [:td (runtime-time opened)]
            [:td (if (:seon.turn/closed-tx entity)
                   (runtime-duration opened closed) "open")]
            [:td (or (runtime-message-link trigger)
                     (if trigger "Trigger details unavailable" "No recorded trigger"))]
            [:td (str (count (:seon.cluster.eval/_run entity))) ]
            [:td (cond
                   (seq reply) (first (str/split-lines reply))
                   (:seon.turn/reply-blob entity) "Reply stored separately"
                   :else "—")]]))]]
     [:p "No turns recorded."])])

(def ^:private debug-turn-selector
  [:db/id :seon.turn/id :seon.turn/reply :seon.turn/reply-blob
   :seon.turn/reply-size :seon.turn/disposition :seon.turn.work/situation
   {:seon.turn/agent [:seon.agent/id]}
   {:seon.turn/starting-ns [:seon.ns/name]}
   {:seon.turn/opened-tx [:db/txInstant]}
   {:seon.turn/closed-tx [:db/id :db/txInstant]}
   {:seon.turn/trigger runtime-message-selector}
   {:seon.turn/attempts [:seon.ai.attempt/id :seon.ai.attempt/ordinal
                         :seon.ai/model :seon.ai.attempt/finish-reason
                         :seon.ai.usage/prompt-tokens
                         :seon.ai.usage/completion-tokens
                         :seon.ai.usage/total-tokens
                         :seon.ai.usage/cached-tokens
                         {:seon.ai.attempt/error [:seon.error/at :seon.error/layer :seon.error/operation :seon.error/data-edn]}]}])

(defn- turn-entities
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:vector :map] :seon.db/error-result]]}
  [database agent-id]
  (let [entities (db/q '[:find ?opened (pull ?t pattern) :in $ ?agent-id pattern
                     :where [?a :seon.agent/id ?agent-id]
                            [?t :seon.turn/agent ?a]
                            [?t :seon.turn/id _ ?opened]]
                   database agent-id debug-turn-selector)]
    (if (db/read-refusal? entities) entities
        (mapv (fn [ordinal [_ entity]] (assoc entity ::ordinal ordinal))
              (range) (sort-by (juxt first #(get-in % [1 :seon.turn/id])) entities)))))

(defn- session-stall [request entities]
  (let [database (:seon.db/db request)
        agent-id (:seon.agent/id request)
        latest (last entities)
        failure (some :seon.ai.attempt/error
                      (reverse (sort-by :seon.ai.attempt/ordinal
                                        (:seon.turn/attempts latest))))]
    (when (and latest (:seon.turn/closed-tx latest)
               (every? :seon.turn/closed-tx entities)
               (nil? (:seon.turn/reply-size latest))
               (nil? (:seon.turn/reply latest))
               (nil? (:seon.turn/reply-blob latest))
               failure (pos? (turn/turns-left database agent-id))
               (nil? (turn/next-agent-work database {:seon.agent/id agent-id})))
      (let [at (get-in latest [:seon.turn/closed-tx :db/txInstant])]
        {::turn latest
         ::detail (str "stalled: " (or (:seon.error/operation failure) "unknown provider refusal")
                       " at " (if (inst? at)
                                (.format (java.text.SimpleDateFormat. "HH:mm") at)
                                "unknown time")
                       ", waiting for an outside wake")}))))

(defn- session-header
  "The session header: identity, state and the page toolbar."
  {:malli/schema [:=> [:cat :map [:sequential :map]] :seon.render/hiccup]}
  [request entities]
  (let [agent-id (:seon.agent/id request)
        agent-entity (db/pull (:seon.db/db request)
                           '[{:seon.agent/namespace [:seon.ns/name]}]
                           [:seon.agent/id agent-id])
        active (last (remove :seon.turn/closed-tx entities))
        latest (or active (last entities))
        stalled (session-stall request entities)]
    [:header {:class "seon-session-header"}
     [:div {:class "seon-session-identity"}
      [:h1 agent-id
       [:span {:class "seon-session-namespace"}
        (str (get-in agent-entity [:seon.agent/namespace :seon.ns/name]))]]
      [:p {:class "seon-session-state"}
       [:span {:class (cond active "seon-status-running"
                            stalled "seon-emission-error"
                            :else "seon-status-idle")}
        (cond active "● running" stalled (str "● " (::detail stalled)) :else "● idle")]
       (when (and latest (not stalled))
         (list " since " (runtime-time (get-in latest [(if active :seon.turn/opened-tx :seon.turn/closed-tx) :db/txInstant]))))
       " · " (:seon.cluster/name request)]]
     [:div {:class "seon-session-toolbar"}
      [:nav {:class "seon-session-nav" :aria-label "Agent pages"}
       [:a {:href (route/path ::route/agent {:id agent-id})
            :aria-current (when (= false (::debug? request)) "page")} "agent"]
       " | "
       [:a {:href (route/path ::route/agent-debug {:id agent-id})
            :aria-current (when (not= false (::debug? request)) "page")} "debug"]]
      (::toolbar request)
      (when (not= false (::debug? request))
        [:a {:href (route/path ::route/agent-debug {:id agent-id})} "Latest"])
      (when (::message-form request)
        [:details {:class "seon-session-message" :id (block/surface-id (keyword "session-message" agent-id))
                   :data-preserve-attr "open"}
         [:summary "Message"]
         (::message-form request)])]]))

(defn render-agent-header
  "One human-labelled header for the agent and its debug session."
  {:malli/schema [:=> [:cat [:and :seon.render/unit
                             [:map [:seon.db/db :seon.db/db] [:seon.agent/id :seon.agent/id]]]]
                  :seon.render/hiccup]}
  [request]
  (let [entities (turn-entities (:seon.db/db request) (:seon.agent/id request))]
    (session-header request (if (vector? entities) entities []))))

(defn render-runtime-html
  "Show transaction times, message triggers, listens, and newest-first turns."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.render.transcript/error-result]]}
  [unit]
  (let [agent-id (runtime-owner unit)
        database (:seon.db/db unit)
        entity (if (:seon.render.transcript/refused-member agent-id) agent-id
                (db/pull database
                     ;; :db/id keeps a runtime with no turns, trigger or
                     ;; listens yet (a new agent's) from pulling as absent.
                     [{:seon.turn/_agent runtime-turn-selector}
                      {:seon.agent/runtime
                       [:db/id
                        {:seon.runtime/trigger runtime-message-selector}
                        {:seon.runtime/listens [:seon.listen/attribute]}]}]
                     [:seon.agent/id agent-id]))]
    (cond
      (or (db/read-refusal? entity) (:seon.render.transcript/refused-member entity)) entity
      (nil? (:seon.agent/runtime entity))
      (request-error 'seon.render.transcript/render-runtime-html
                     {:seon.error/message "The agent's runtime component is unavailable."
                      :seon.error/fix "Supply the expected member and repeat the requested operation."
                      :seon.render.transcript/refused-member :seon.agent/runtime
                      :seon.error/expected :seon.runtime/entity
                      :seon.error/offending agent-id})
      :else
        (let [runtime (:seon.agent/runtime entity)
              turns (sort-by (juxt #(some-> (get-in % [:seon.turn/opened-tx :db/txInstant])
                                            (.getTime)) :seon.turn/id)
                             #(compare %2 %1) (:seon.turn/_agent entity))
              open (first (remove :seon.turn/closed-tx turns))
              since (if open (get-in open [:seon.turn/opened-tx :db/txInstant])
                        (last (sort (keep #(get-in % [:seon.turn/closed-tx :db/txInstant]) turns))))
              trigger (:seon.runtime/trigger runtime)]
          [:section {:class "seon-runtime"}
           [:h2 "Runtime"]
           [:p {:class "seon-runtime-state"}
            (if open "Turn open" "Idle")
            (when since
              (list " since " (runtime-time since) " ("
                    [:span {:data-text (str "(() => {const m=Math.max(0,Math.floor((Date.now()-"
                                            (.getTime ^java.util.Date since)
                                            ")/60000));return m>=1440?Math.floor(m/1440)+' d '+Math.floor(m%1440/60)+' h':m>=60?Math.floor(m/60)+' h '+m%60+' min':m+' min'})()")}
                     (runtime-duration since (java.util.Date.))] ")"))]
           (when trigger
             ;; the trigger message is its own graph node; the runtime links it
             [:div {:class "seon-runtime-trigger"}
              [:p "Woke on " (or (runtime-message-link trigger) "a recorded fact")]])
           [:div {:class "seon-runtime-listens" :style {:display "flex" :gap "0.5rem" :flex-wrap "wrap"}}
            [:span "Listening:"]
            (if (seq (:seon.runtime/listens runtime))
              (for [attribute (sort (distinct (keep :seon.listen/attribute (:seon.runtime/listens runtime))))]
                [:code {:style {:border "1px solid currentColor" :border-radius "1rem" :padding "0.1rem 0.5rem"}}
                 (str attribute)])
              [:span "No listened attributes."])]
           (runtime-turn-table turns trigger (:seon.db/connection unit))]))))
