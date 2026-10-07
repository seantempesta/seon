(ns seon.render.transcript
  "One agent's messages, turns, and evaluation results.

  The renderer is the schema-declared agent-session projection. Messages are
  reverse connections while evaluations are reached through the
  agent's turns. Raw facts never acquire a detail level; every full, summary,
  and elided decision is derived for this call."
  (:require [seon.error.refusal]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.message :as message]
            [seon.context :as context]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.env :as env]
            [seon.error :as error]
            [seon.render :as render]
            [seon.render.block :as block]
            [seon.render.route :as route]
            [seon.render.value :as value]
            [seon.repl :as repl]
            [malli.registry :as mr]
            [malli.core :as m]
            [seon.sci.admit :as admit])
  (:import [java.io PushbackReader StringReader]))

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
   ;; THE FORM'S OWN PRINT OPTIONS, as evidence of the session's dynamic
   ;; bindings at evaluation time. They do NOT re-bound the shown text:
   ;; presentation is decided once, by the render profile, when the
   ;; evaluation stores what it showed (`src/seon/render/value.clj:448`).
   :seon.print/length
   :seon.print/level
   :seon.eval/duration-ms
   :seon.sci.eval/ending-ns
   :seon.problems/id
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

(def ^:private active-runs-rules
  '[[(active-run ?run ?agent ?bootstrap-run-id ?pinned?)
     [?run :seon.turn/agent ?agent]
     [?run :seon.turn/id ?run-id]
     [(= ?run-id ?bootstrap-run-id) ?pinned?]]])

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
          :in $ % ?agent-id ?bootstrap-run-id
          :where
          [?agent :seon.agent/id ?agent-id]
          (active-run ?run ?agent ?bootstrap-run-id false)
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/at ?at]
          [?receipt :seon.cluster.eval/id ?id]]
        :args [db active-runs-rules agent-id (bootstrap/run-id agent-id)]
        :order-by '[?at :desc ?id :desc]
        :limit limit}))



(defn- pinned-receipt-ids
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or [:sequential :int] :seon.db/error-result]]}
  [db agent-id]
  (db/q '[:find [?receipt ...]
         :in $ % ?agent-id ?bootstrap-run-id
         :where
         [?agent :seon.agent/id ?agent-id]
         (active-run ?run ?agent ?bootstrap-run-id true)
         [?receipt :seon.cluster.eval/run ?run]]
       db active-runs-rules agent-id (bootstrap/run-id agent-id)))

(defn- bootstrap-task-message-eid
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :int :seon.db/error-result]]}
  [db agent-id]
  (db/q '[:find ?message .
          :in $ ?turn-id
          :where
          [?turn :seon.turn/id ?turn-id]
          [?turn :seon.turn/trigger ?message]
          [?message :seon.message/id]]
        db (bootstrap/run-id agent-id)))

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
                        {}))
        task-message-eid (bootstrap-task-message-eid db agent-id)]
    (-> recent
        (update :message
                (fn [message-eids]
                  (into []
                        (distinct
                         (concat (when task-message-eid [task-message-eid])
                                 message-eids)))))
        (update :eval
                (fn [receipt-ids]
                  (into []
                        (distinct
                         (concat (pinned-receipt-ids db agent-id)
                                 receipt-ids))))))))

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
     (when (= (bootstrap/task-message-id database agent-id)
              (:seon.message/id message))
       {::bootstrap-trigger? true})
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
      (::bootstrap-trigger? entry)
      [0 2 nil nil (::id entry)]

      (and (::pinned? entry) (= :eval (::kind entry)))
      [0 (inc (* 2 (::ordinal entry))) nil nil (::id entry)]

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

(defn- branch-errors
  "The errors each settled evaluation's settlement saved on it
  (`:seon.cluster.eval/faults`, `seon.turn/fault-window`: the window
  (previous settlement, this settlement], read once by the writer), keyed by
  evaluation entity id, for `:all` of the agent's evaluations or the `shown`
  set, each as its value (`seon.error/value` of `seon.error/latest-fact`)
  with its occurrence count, origin branch and resolution when resolved. Nothing is reconstructed from occurrence counters.
  A refused read is thrown whole."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id
                       [:or [:= :all] [:set :int]]]
                  [:map-of :int [:vector {:min 1} :map]]]}
  [db agent-id shown]
  (let [entities (read-or-throw
              (db/q '[:find ?e ?error :in $ ?agent-id
                      :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent]
                      [?e :seon.cluster.eval/run ?run] [?e :seon.cluster.eval/faults ?error]]
                    db agent-id))
        selector (when (seq entities) (error/observation-selector (db/carried-projection db)))]
    (into {}
          (keep (fn [[eid group]]
                  (when (or (= :all shown) (contains? shown eid))
                    [eid (mapv (fn [[_ error]]
                                 (let [pulled (read-or-throw (db/pull db selector error))
                                       resolved (read-or-throw (error/resolution db (:seon.error/signature pulled)))]
                                   ;; ONE ERROR, SHOWN ONCE: its value (message
                                   ;; once, evidence by blob reference) and what
                                   ;; this window adds. The whole observation
                                   ;; repeated the message three times (base,
                                   ;; occurrence, capped data-edn) and printed
                                   ;; the evidence inline; it stays reachable
                                   ;; by :seon.error/id.
                                   (let [fact (error/latest-fact pulled)]
                                     (cond-> (merge (error/value fact)
                                                    (select-keys fact [:seon.error.occurrence/count
                                                                       :seon.error.occurrence/branch]))
                                       resolved (assoc :seon.error/resolution resolved)))))
                               group)])))
          (group-by first entities))))

(defn- history
  ([db run-id agent-id limit]
   (history db run-id agent-id limit nil))
  ([db run-id agent-id limit selected-run-id]
   (history db run-id agent-id limit selected-run-id nil))
  ([db run-id agent-id limit selected-run-id selected-evaluations]
   (let [ids (cond
               (some? selected-evaluations) {:eval (vec selected-evaluations)}
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
                (assoc entry
                       ::root (entry-root identity-attrs entry)
                       ::pinned?
                       (or (::bootstrap-trigger? entry)
                           (and (= :eval (::kind entry))
                                (= (bootstrap/run-id agent-id)
                                   (::run-id entry)))))))
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
  [pinned entries]
  (str/join "\n\n" (map ::text (concat pinned entries))))

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
  [pinned entries]
  (into
   [:section {:class "seon-transcript"}]
   (map html-entries)
   (filter seq [pinned entries])))

(defn- candidate-history
  "The shared source of transcript entries, supplied evaluations or stored history."
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.agent/id unit)
        candidate-count (admit/required-cap
                         (:seon.sci.admit/caps unit)
                         :seon.config.eval.result/max-nodes)
        evaluated-sources (:seon.turn.loop/evaluated-sources unit)]
    (if (some? evaluated-sources)
          (mapv
           (fn [{form :seon.turn.loop/admitted-form
                 evaluation :seon.sci.eval/evaluation
                 ordinal :seon.cluster.eval/ordinal}]
             (receipt-entry
              (cond-> (assoc evaluation
                             :seon.cluster.eval/source
                             (:seon.cluster.eval/source form)
                             :seon.cluster.eval/id
                             (turn/receipt-identity
                              (:seon.turn/id unit) ordinal)
                             :seon.cluster.eval/ordinal ordinal
                             :seon.cluster.eval/ns
                             {:seon.ns/name
                              (second (:seon.cluster.eval/ns form))}
                             :seon.cluster.eval/read-basis-transaction
                             (or (:seon.cluster.eval/read-basis-transaction
                                  evaluation)
                                 (db/basis-t db)))
                ;; THE COMMENT IS A FACT OF THE FORM THE AGENT WROTE, and it
                ;; travels on the admitted form until settlement moves it to
                ;; the evaluation. Reading only the evaluation dropped the
                ;; agent's own prose out of every in-memory render, so the
                ;; page's preview and the stored history disagreed by a line.
                (:seon.cluster.eval/comment form)
                (assoc :seon.cluster.eval/comment
                       (:seon.cluster.eval/comment form))
                ;; The same for the print options the form set: stored they
                ;; are `:seon.print/length` / `/level`, in flight they are
                ;; the evaluation's `:seon.print/options` map.
                (int? (get-in evaluation [:seon.print/options
                                          :seon.print/length]))
                (assoc :seon.print/length
                       (get-in evaluation [:seon.print/options
                                           :seon.print/length]))
                (int? (get-in evaluation [:seon.print/options
                                          :seon.print/level]))
                (assoc :seon.print/level
                       (get-in evaluation [:seon.print/options
                                           :seon.print/level])))))
           evaluated-sources)
          (let [entries (history db (:seon.turn/id unit) agent-id
                                 candidate-count (::selected-run-id unit)
                                 (:seon.context.contribution/evaluations unit))
                evaluations (into #{} (keep #(when (= :eval (::kind %)) (get-in % [::entity :db/id])))
                                  entries)
                ;; a caller rendering many runs derives the agent's windows once
                errors (cond (::branch-errors unit) (::branch-errors unit)
                             (seq evaluations) (branch-errors db agent-id evaluations)
                             :else {})]
            ;; The branch's new errors are bounded once, by the value renderer.
            (mapv (fn [entry]
                    (if-some [found (get errors (get-in entry [::entity :db/id]))]
                      (let [text (floor-text (assoc unit :seon.render.value/root (::root entry))
                                             found)]
                        (if (string? text)
                          (assoc-in entry [::entity :seon.repl/errors] text)
                          (throw (ex-info (str (:seon.error/message text)) text))))
                      entry))
                  entries)))))

(defn- projection
  [unit]
  (let [db (:seon.db/db unit)
        agent-id (:seon.agent/id unit)
        entries (if (or (some? (:seon.turn.loop/evaluated-sources unit))
                        (and db agent-id))
                  (candidate-history unit)
                  [])
        pinned (into []
                     (comp (filter ::pinned?)
                           (map #(projected-entry unit % :full)))
                     entries)
        candidates (into [] (remove ::pinned?) entries)]
    {::pinned pinned
     ::entries (mapv #(projected-entry unit % :full) candidates)}))

(defn render-ai
  "Render one agent's bounded messages and faithful REPL session."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [{::keys [pinned entries]} (projection unit)]
    (ai-output pinned entries)))

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
        candidates (candidate-history unit)
        entries
        (mapv
         (fn [entry]
           ;; ONE GENERATOR. The bytes are `seon.repl/text`'s, so this unit,
           ;; the debug page's AI column and the provider prompt cannot drift
           ;; apart: there is no second place that decides how an evaluation
           ;; reads.
           (let [emitted (repl/entity-emission (assoc (::entity entry) :seon.db/db db))]
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
  (let [{::keys [pinned entries]} (projection unit)]
    ;; Reasoning is joined only after the shared projection, so it changes
    ;; neither the AI bytes nor which transcript entries the agent receives.
    (html-output pinned
                 (sort-by entry-order
                          (into entries (reasoning-attempts unit))))))

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
      [:section {:class "seon-turn"}
       (turn-header (:seon.db/db unit) run-id)
       [:p (turn/render-ai unit)]
       (render-html (assoc unit :seon.agent/id agent-id
                          ::selected-run-id run-id))]
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

(defn- turn-kind [database entity]
  (cond
    (seq (:seon.turn/attempts entity)) "Provider"
    (= :generate (:seon.turn.work/situation entity)) "System"
    (= :call (:seon.turn.work/situation entity))
    (let [opening (turn/opening-db database (:seon.turn/id entity))
          agent-id (get-in entity [:seon.turn/agent :seon.agent/id])
          overlay (get-in (db/pull opening
                                  '[{:seon.agent/settings [:seon.config.ai/no-provider]}]
                                  [:seon.agent/id agent-id])
                          [:seon.agent/settings])
          no-provider (get overlay :seon.config.ai/no-provider
                           (config/agent-cluster-dial opening agent-id :seon.config.ai/no-provider))]
      (if no-provider "Virtual" "No provider attempt"))
    (or (:seon.turn/reply entity) (:seon.turn/reply-blob entity)) "System"
    :else "No provider attempt"))

(defn- readable-shown [source]
  (try
    (with-open [reader (PushbackReader. (StringReader. source))]
      (let [value (edn/read {:eof ::eof} reader)]
        (when (= ::eof (edn/read {:eof ::eof} reader))
          {::value value})))
    (catch Exception _ nil)))

(defn- attempt-usage [attempt]
  (let [prompt (:seon.ai.usage/prompt-tokens attempt)
        hit (:seon.ai.usage/cached-tokens attempt)
        miss (when (and (number? prompt) (number? hit)) (- prompt hit))
        out (:seon.ai.usage/completion-tokens attempt)]
    (into {} (filter (comp number? val))
          {::prompt prompt ::hit hit ::miss miss ::out out})))

(defn- attempt-html [attempt]
  (let [usage (attempt-usage attempt)]
    [:li {:class "seon-debug-attempt"}
     (str "Attempt " (:seon.ai.attempt/ordinal attempt) " · "
          (or (:seon.ai/model attempt) "model unavailable")
          " · finish: " (or (:seon.ai.attempt/finish-reason attempt) "unavailable")
          " · prompt tokens: " (if-let [n (::prompt usage)] (format "%,d" n) "unavailable")
          " · completion tokens: " (if-let [n (::out usage)] (format "%,d" n) "unavailable")
          " · cache-hit tokens: " (if-let [n (::hit usage)]
                                     (format "%,d" n) "unavailable")
          " · miss: " (if-let [n (::miss usage)] (format "%,d" n) "unavailable"))]))

(defn- session-id [agent-id]
  (block/surface-id (keyword "debug-session" agent-id)))

(defn- session-url [agent-id turn-id raw?]
  (route/path ::route/agent-debug {:id agent-id}
              (cond-> {:turn turn-id} raw? (assoc :prompt "true"))))

(defn- nav-click
  "The Datastar click that moves the address to `href` and fetches `fetch-url`."
  [href fetch-url]
  (str "evt.preventDefault(); history.replaceState(null, '', '" href "'); @get('" fetch-url "')"))

(defn- outline-url
  [agent-id]
  (route/path ::route/agent-debug {:id agent-id} {:outline "true"}))

(defn- session-origin [database entity saved]
  (cond
    (:seon.cluster.eval/error saved) "error"
    (= "System" (turn-kind database entity)) (if (zero? (::ordinal entity)) "opening" "re-read")
    :else "agent"))

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

(defn- session-header [request entities]
  (let [agent-id (:seon.agent/id request)
        agent-entity (db/pull (:seon.db/db request)
                           '[{:seon.agent/namespace [:seon.ns/name]}]
                           [:seon.agent/id agent-id])
        active (last (remove :seon.turn/closed-tx entities))
        latest (or active (last entities))
        stalled (session-stall request entities)
        selected (or (:seon.turn/id request) (:seon.turn/id latest))
        raw? (::raw? request)]
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
      (when (and selected (not= false (::debug? request)))
        [:a {:class "seon-session-toggle" :href (session-url agent-id selected (not raw?))
             (keyword "data-on:click")
             (when-not raw?
               (let [raw-url (session-url agent-id selected true)]
                 (nav-click raw-url raw-url)))}
         (if raw? "Turn ledger" "As the model saw it")])
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

(defn- changed-paths [before after path]
  (cond
    (= before after) []
    (and (map? before) (map? after))
    (mapcat #(changed-paths (get before % ::absent) (get after % ::absent) (conj path %))
            (distinct (concat (keys before) (keys after))))
    (and (vector? before) (vector? after) (= (count before) (count after)))
    (mapcat #(changed-paths (nth before %) (nth after %) (conj path %)) (range (count before)))
    :else [{::path path ::before before ::after after}]))

(defn- reread-summary [evaluations]
  (let [first-shown (:seon.eval/shown (first evaluations))
        last-shown (:seon.eval/shown (last evaluations))
        before (when first-shown (readable-shown first-shown))
        after (when last-shown (readable-shown last-shown))
        changes (when (and before after) (vec (changed-paths (::value before) (::value after) [])))]
    (cond
      (= first-shown last-shown) "identical shown text"
      (and (= 1 (count changes)) (seq (::path (first changes))))
      (let [change (first changes)]
        (str "only " (last (::path change)) " changed"
             (when (and (number? (::before change)) (number? (::after change)))
               (str " " (::before change) " → " (::after change)))))
      (and (seq changes) (seq (::path (first changes)))) (str (count changes) " changed paths")
      :else (str (blob/utf8-size (or first-shown "")) " → " (blob/utf8-size (or last-shown "")) " shown bytes"))))

(defn render-session-loading
  "Select only the latest turn initially; acquire its prompt on demand."
  {:malli/schema [:=> [:cat [:and :seon.render/unit
                             [:map [:seon.db/db :seon.db/db] [:seon.agent/id :seon.agent/id]]]]
                  :seon.render/hiccup]}
  [request]
  (let [agent-id (:seon.agent/id request)
        entities (turn-entities (:seon.db/db request) agent-id)
        selected (or (:seon.turn/id request) (when (vector? entities) (:seon.turn/id (last entities))))
        ;; THE OUTLINE IS NOT TURN-SCOPED. It names every turn, so its
        ;; deferred fetch carries no turn id; the selected-turn prompt keeps
        ;; its own url unchanged.
        url (cond
              (not selected) nil
              (::outline? request) (outline-url agent-id)
              :else (session-url agent-id selected (::raw? request)))]
    [:section {:id (session-id agent-id) :class "seon-session" :data-ignore-morph ""}
     (session-header request (if (vector? entities) entities []))
     [:h2 "Session"]
     (cond
       (db/read-refusal? entities) [:p {:class "seon-emission-error"} (:seon.error/message entities)]
       url
       [:p {:role "status" :data-init (str "@get('" url "')")}
        (if (::outline? request)
          "Loading the agent’s history outline…"
          "Loading the selected turn’s saved prompt…")]
       :else [:p "No turns recorded."])]))

(defn render-session
  "The selected turn's context, in acquisition order, with byte-exact colour.
  Metadata lives outside emission bytes; the raw toggle uses acquisition's
  complete prompt unchanged. Historical provider turns use their opening DB."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [database (:seon.db/db request)
        agent-id (:seon.agent/id request)
        entities (turn-entities database agent-id)
        selected (or (some #(when (= (:seon.turn/id request) (:seon.turn/id %)) %) entities)
                     (last entities))
        selected-id (:seon.turn/id selected)
        acquired (try
                   (render/acquire-context! (assoc request :seon.turn/id selected-id))
                   (catch clojure.lang.ExceptionInfo failure
                     (let [refusal (ex-data failure)]
                       (if (and (:seon.instrument/fn refusal)
                                (:seon.instrument/check refusal))
                         refusal
                         (throw failure)))))
        entries (:seon.render.history/entries acquired)
        prompt (:seon.cluster.prompt/text acquired)
        by-eid (into {} (map (juxt :db/id identity)) entities)
        saved (map :seon.render/value entries)
        rereads (->> saved
                     (filter #(= "re-read" (session-origin database
                                         (get by-eid (get-in % [:seon.cluster.eval/run :db/id])) %)))
                     (group-by :seon.cluster.eval/source)
                     (filter #(< 1 (count (val %))))
                     (sort-by first))
        source-signals (into {} (map-indexed (fn [index [source _]]
                                               [source (str "reread" index)]) rereads))
        turn-bytes (reduce (fn [sizes [segment evaluation-entity]]
                             (update sizes (get-in evaluation-entity [:seon.cluster.eval/run :db/id])
                                     (fnil + 0) (blob/utf8-size segment)))
                           {} (map vector (:seon.render.history/segments acquired) saved))
        raw? (::raw? request)]
    [:section {:id (session-id agent-id) :class "seon-session" :data-ignore-morph ""
               :data-signals (str "{" (str/join "," (map #(str % ":false") (vals source-signals))) "}")
               :data-session-loaded selected-id}
     [:div {:class "seon-session-sticky"}
     (session-header request entities)
     [:header {:class "seon-session-heading"}
      [:div [:h2 (str "Context at turn " (::ordinal selected))]
       [:p (str "turn " (::ordinal selected) " · " (str/lower-case (turn-kind database selected))
                " · ") (runtime-time (get-in selected [:seon.turn/opened-tx :db/txInstant]))]
       (when (seq (:seon.turn/attempts selected))
         (into [:ul {:class "seon-session-meta"}] (map attempt-html) (:seon.turn/attempts selected)))]]
     [:nav {:class "seon-session-selection" :aria-label "Select turn"}
      (for [entity entities]
        [:a {:href (session-url agent-id (:seon.turn/id entity) raw?)
             :data-turn-id (:seon.turn/id entity)
             :data-turn-kind (turn-kind database entity)
             :aria-current (if (= selected-id (:seon.turn/id entity)) "true" "false")
             (keyword "data-on:click")
             (str "evt.preventDefault(); @get('" (session-url agent-id (:seon.turn/id entity) raw?) "')")}
         (str (::ordinal entity))])]
     (when prompt
       [:p {:class "seon-session-meta"}
        (str (format "%,d" (blob/utf8-size prompt)) " bytes · ≈" (format "%,d" (tokens/estimate prompt))
             " tokens · " (count entries) " emissions · oldest → newest")])]
     ;; An acquisition without prompt text is the refusal caught above (or a
     ;; web refusal); it renders as that failure, never as an empty prompt.
     (if (or (:seon.render.web/refused-member acquired) (not (string? prompt)))
       [:div {:class "seon-emission-error"}
        (error/render-html (assoc request :seon.render/value acquired))
        (value/render-html (assoc request :seon.render/value acquired))]
       [:div
        (when (and (not raw?) (seq rereads))
          [:div {:class "seon-session-rereads"}
           [:p "Repeated system reads are folded in place. Expand to see their exact positions."]
           (for [[source matches] rereads]
             (let [signal (get source-signals source)
                   form (::value (readable-shown source))]
               [:details {(keyword "data-on:toggle") (str "$" signal "=el.open")}
                [:summary
                (str (if (seq? form) (first form) (first (str/split-lines source)))
                     (when-let [attribute (first (filter qualified-keyword? (tree-seq coll? seq form)))]
                       (str " · " attribute))
                     " · re-read ×" (count matches) " · " (reread-summary matches))]
                [:p "Expanded at their original positions below."]]))])
        [:div {:class "seon-session-scroll"}
         (if raw?
           [:pre {:class "seon-session-raw" :data-prompt-bytes (blob/utf8-size prompt)} [:code prompt]]
           (into [:div {:class "seon-session-emissions"}]
                 (map-indexed
                  (fn [position [entry evaluation-entity]]
                    (let [entity (get by-eid (get-in evaluation-entity [:seon.cluster.eval/run :db/id]))
                          origin (session-origin database entity evaluation-entity)
                          emission (repl/entity-emission (assoc evaluation-entity :seon.db/db database))
                          previous (when (pos? position) (nth saved (dec position)))
                          boundary? (not= (:seon.cluster.eval/run evaluation-entity)
                                          (:seon.cluster.eval/run previous))]
                      [:section (cond-> {:class "seon-session-entry"}
                                  (and (= "re-read" origin)
                                       (get source-signals (:seon.cluster.eval/source evaluation-entity)))
                                  (assoc :data-show (str "$" (get source-signals (:seon.cluster.eval/source evaluation-entity)))))
                       (when boundary?
                         [:header {:class "seon-session-boundary"}
                          (str "Turn " (::ordinal entity) " · " (turn-kind database entity) " · ")
                          (runtime-time (get-in entity [:seon.turn/opened-tx :db/txInstant]))
                          (str " · " (get turn-bytes (:db/id entity) 0) " bytes added")
                          (when (seq (:seon.turn/attempts entity))
                            (into [:ul] (map attempt-html) (:seon.turn/attempts entity)))])
                       [:div {:class "seon-session-line"}
                        [:aside {:class (str "seon-session-gutter seon-origin-" origin)}
                         [:span (str (::ordinal entity))] [:span (str "● " origin)]]
                        [:pre {:data-emission-bytes (+ (if (pos? position) 2 0)
                                                       (blob/utf8-size (:seon.render.history/bytes entry)))}
                         (when (pos? position) [:span "\n\n"])
                         (if (= (:seon.render.history/bytes entry) (repl/text emission))
                           (repl/render-emission-html emission)
                           [:code (:seon.render.history/bytes entry)])]]]))
                  (map vector entries saved))))
         [:span {:class "seon-session-end" :data-init "el.scrollIntoView({block:'end'})"}]]])]))

(defn- emission-label [projection saved]
  (let [attributes (distinct
                     ;; the attributes Datahike reports the read depended on
                     (for [evidence (:seon.cluster.eval/read-evidence saved)
                           source (get-in evidence [:datahike.read/dependency-plan
                                                    :datahike.query.dependency/sources])
                           :let [depended (:datahike.query.source/attributes source)]
                           :when (set? depended)
                           attribute (sort depended)
                           :when (keyword? attribute)] attribute))
        matches (when-let [renderer (or (some-> (get-in saved [:seon.eval/renderer-fn :seon.fn/sym]) symbol)
                                        (:seon.eval/renderer saved))]
                  (filter #(= renderer (:seon.render/ai %))
                          (vals (:seon.schema.projection/shape-rows projection))))
        schema-key (or (when (= 1 (count attributes)) (first attributes))
                       (when (= 1 (count matches)) (:seon.schema/key (first matches))))]
    (or (:title (some-> (mr/schema (:seon.schema.projection/registry projection) schema-key) m/properties))
        (some-> schema-key str)
        (str "Evaluation " (inc (or (:seon.cluster.eval/ordinal saved) 0))))))

;;; ---------------------------------------------------------------------------
;;; The outline
;;;
;;; THE SESSION PANEL IS AN OUTLINE, NOT A DUMP (owner, 2026-09-16). Every unit
;;; in the agent's history is ONE evaluation rendered through the evaluation
;;; schema's declared pair, and it knows its origin: the turn that produced it,
;;; its ordinal, the entity whose render declared it (`:seon.eval/origin`), the
;;; renderer that produced its shown text (`:seon.eval/renderer`), and what it
;;; costs under the agent's own calibration.
;;;
;;; NOTHING HERE RENDERS A SECOND TIME. The AI bytes are the ones acquisition
;;; already composed — the same bytes `seon.cluster.prompt/compose` joins — and
;;; the HTML is the same declared pair the agent page selects. There is no
;;; presentation clipping on this path (2.4): the shown text each unit carries
;;; was bounded once, at evaluation time, by the value renderer.
;;; ---------------------------------------------------------------------------

(defn- outline-signal
  "One Datastar signal per unit: false shows the HTML pair, true the AI bytes."
  [evaluation-id]
  (str "ai" (str/replace (str evaluation-id) #"[^A-Za-z0-9]" "")))

(defn- outline-turn-kind
  "The turn's kind as the outline names it, derived, never stamped.

  `turn-kind` already derives provider/system/virtual from the turn's own
  facts; the agent's FIRST system turn is its opening, which is the same rule
  `session-origin` uses for a unit."
  [database entity]
  (let [kind (turn-kind database entity)]
    (if (and (zero? (::ordinal entity)) (= "System" kind)) "Opening" kind)))

(defn- outline-calibration
  "The calibration the prompt itself would price these units against.

  A model with recorded usage supplies its own ratio through
  `seon.cluster.prompt/agent-calibration` under the cluster the request's
  context environment names; an agent with no settled attempt yet uses the
  measured shipped prior rather than inventing one. A calibration refusal
  (no named cluster, no configuration) is returned for the outline to show."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :seon.boot/cluster-name]
                       :seon.agent/id [:sequential :map]]
                  [:or :seon.ai.tokens/calibration :seon.error/value]]}
  [database cluster-name agent-id entities]
  (let [model (some :seon.ai/model
                    (reverse (sort-by :seon.ai.attempt/ordinal
                                      (mapcat :seon.turn/attempts entities))))
        fitted (when model
                 ((requiring-resolve 'seon.cluster.prompt/agent-calibration)
                  database cluster-name agent-id model))]
    (or fitted tokens/shipped-calibration)))

(defn- outline-renderer
  "The declared renderer that produced this unit's saved shown text.

  Absent means the value renderer's own AI projection made it; the unit
  itself is always emitted by the evaluation schema's declared pair."
  [saved]
  (or (some-> (get-in saved [:seon.eval/renderer-fn :seon.fn/sym]) symbol)
      (:seon.eval/renderer saved)
      'seon.render.value/render-ai))

(defn- outline-unit
  "One collapsed unit line and, expanded, its two honest projections."
  [request projection calibration database entity entry saved]
  (let [bytes (or (:seon.render.history/bytes entry) "")
        evaluation-id (:seon.cluster.eval/id saved)
        signal (outline-signal evaluation-id)
        origin (session-origin database entity saved)
        declared (:seon.eval/origin saved)
        html (render/render-call
              (assoc request :seon.render/value saved
                     :seon.render/output :seon.render/html
                     :seon.render.call/id (:seon.render.history/call-id entry)))]
    [:details {:class "seon-outline-unit" :data-evaluation-id evaluation-id
               :data-unit-origin origin :data-preserve-attr "open"}
     [:summary {:class "seon-outline-unit-line"}
      [:span {:class (str "seon-outline-ordinal seon-origin-" origin)}
       (str "#" (:seon.cluster.eval/ordinal saved))]
      [:span {:class "seon-outline-origin"} origin]
      (when declared
        [:span {:class "seon-outline-declared"}
         (str "declared by " (pr-str declared))])
      [:span {:class "seon-outline-schema"}
       (str (emission-label projection saved) " · " (outline-renderer saved))]
      [:span {:class "seon-outline-tokens"}
       (str "≈" (format "%,d" (tokens/estimate bytes calibration)) " tokens")]]
     [:div {:class "seon-outline-unit-body"}
      [:button {:type "button" :class "seon-outline-toggle"
                (keyword "data-on:click") (str "$" signal " = !$" signal)}
       [:span {:data-show (str "!$" signal)} "As the model saw it"]
       [:span {:data-show (str "$" signal)} "Rendered"]]
      [:div {:class "seon-outline-html" :data-show (str "!$" signal)}
       (if (:seon.render/refused-member html)
         [:div {:class "seon-emission-error"}
          (error/render-html (assoc request :seon.render/value html))]
         html)]
      [:pre {:class "seon-outline-ai" :data-show (str "$" signal)
             :data-ai-bytes (blob/utf8-size bytes)}
       [:code bytes]]]]))

(defn- outline-everything
  "The composed prompt, exactly as the provider boundary receives it.

  This is `seon.cluster.prompt/prompt`'s own text — `compose` over the
  selection, then the turn frame — not a second assembly of the same units.
  A refusal is shown as the refusal it is."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [composed (try
                   ((requiring-resolve 'seon.cluster.prompt/prompt)
                    (:seon.db/db request) request)
                   (catch clojure.lang.ExceptionInfo failure
                     (let [refusal (ex-data failure)]
                       (if (or (:seon.instrument/check refusal) (:seon.render/refused-member refusal) (:seon.render.web/refused-member refusal))
                         (assoc (assoc refusal :seon.cluster.prompt/error-agent-id (:seon.agent/id request)
                       :seon.cluster.prompt/derivation-observation
                       {:seon.error.evidence/attribute :seon.error/message
                        :seon.error.evidence/value (:seon.error/message refusal)}) :seon.error/type :seon.cluster.prompt/error)
                         (throw failure)))))
        refusal (:seon.cluster.prompt/error-agent-id composed)]
    [:details {:class "seon-outline-everything" :data-preserve-attr "open"}
     [:summary
      (if refusal
        "Show everything · the composed prompt was refused"
        (let [text (:seon.cluster.prompt/text composed)]
          (str "Show everything · the composed prompt · "
               (format "%,d" (blob/utf8-size text)) " bytes · ≈"
               (format "%,d" (tokens/estimate text)) " tokens")))]
     (if refusal
       [:div {:class "seon-emission-error"}
        (error/render-html (assoc request :seon.render/value composed))]
       [:pre {:class "seon-session-raw"
              :data-prompt-bytes (blob/utf8-size (:seon.cluster.prompt/text composed))}
        [:code (:seon.cluster.prompt/text composed)]])]))

(defn render-outline
  "The agent's history as an OUTLINE: one line per turn, one line per unit.

  A collapsed turn names its kind and its size; a collapsed unit names its
  ordinal, what produced it, the schema whose render made it and its tokens.
  Expanding a unit shows the declared HTML pair, with one toggle to the exact
  AI bytes the model read. Every number and every byte derives from the same
  acquisition the prompt composes.

  AN UNAVAILABLE PANEL SAYS SO. A refused acquisition renders the refusal;
  it never leaves the reader a spinner."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [database (:seon.db/db request)
        agent-id (:seon.agent/id request)
        entities (turn-entities database agent-id)
        acquired (try
                   (render/acquire-context! (dissoc request :seon.turn/id))
                   (catch clojure.lang.ExceptionInfo failure
                     (let [refusal (ex-data failure)]
                       (if (or (:seon.instrument/check refusal) (:seon.render/refused-member refusal) (:seon.render.web/refused-member refusal))
                         (assoc refusal :seon.render.web/refused-member :seon.render/context-request)
                         (throw failure)))))
        entries (:seon.render.history/entries acquired)
        saved (mapv :seon.render/value entries)
        by-eid (into {} (map (juxt :db/id identity)) (if (vector? entities) entities []))
        projection (when-let [ctx (:seon.sci.eval/ctx request)]
                     ((requiring-resolve 'seon.sci.kernel/context-projection) ctx))
        calibration (outline-calibration database (:seon.boot/cluster-name (env/of (:seon.sci.eval/ctx request))) agent-id
                                         (if (vector? entities) entities []))
        by-turn (group-by #(get-in (:seon.render/value %)
                                   [:seon.cluster.eval/run :db/id])
                          entries)
        signals (str "{" (str/join "," (map #(str (outline-signal (:seon.cluster.eval/id %))
                                                  ":false")
                                            saved))
                     "}")]
    [:section {:id (session-id agent-id) :class "seon-session seon-outline"
               :data-ignore-morph "" :data-signals signals}
     (session-header request (if (vector? entities) entities []))
     [:h2 "Session"]
     (cond
       (db/read-refusal? entities)
       [:div {:class "seon-emission-error"}
        (error/render-html (assoc request :seon.render/value entities))]

       (:seon.render.web/refused-member acquired)
       [:div {:class "seon-emission-error"}
        (error/render-html (assoc request :seon.render/value acquired))]

       (:seon.error/at calibration)
       [:div {:class "seon-emission-error"}
        (error/render-html (assoc request :seon.render/value calibration))]

       (empty? entities) [:p "No turns recorded."]

       :else
       [:div {:class "seon-outline-turns"}
        (for [entity entities
              :let [units (get by-turn (:db/id entity) [])
                    turn-bytes (transduce (map #(or (:seon.render.history/bytes %) "")) str units)]]
          [:details {:class "seon-outline-turn" :data-turn-id (:seon.turn/id entity)
                     :data-turn-kind (outline-turn-kind database entity)
                     :data-preserve-attr "open"}
           [:summary {:class "seon-outline-turn-line"}
            [:span {:class "seon-outline-turn-ordinal"} (str "Turn " (::ordinal entity))]
            [:span {:class "seon-outline-turn-kind"} (outline-turn-kind database entity)]
            [:span {:class "seon-outline-turn-units"}
             (str (count units) (if (= 1 (count units)) " unit" " units"))]
            [:span {:class "seon-outline-tokens"}
             (str "≈" (format "%,d" (tokens/estimate turn-bytes calibration)) " tokens")]]
           (if (seq units)
             (into [:div {:class "seon-outline-units"}]
                   (map (fn [entry]
                          (let [value (:seon.render/value entry)]
                            (outline-unit request projection calibration database
                                          (get by-eid (get-in value [:seon.cluster.eval/run :db/id]))
                                          entry value))))
                   units)
             [:p {:class "seon-outline-empty"} "This turn stored no evaluations."])])
        (outline-everything request)])]))

(defn- ledger-acquisition {:malli/schema [:=> [:cat :seon.render/context-request] [:or :seon.render/acquired-context :seon.render/context-change-result :seon.render.web/context-error]]}
  [request]
  (let [selector '[:seon.cluster.eval/source :seon.cluster.eval/comment
                 :seon.eval/shown :seon.eval/renderer
                 {:seon.eval/renderer-fn [:db/id :seon.fn/sym]} :seon.cluster.eval/error
                 :seon.cluster.eval/output :seon.error/at :seon.error/layer :seon.error/operation :seon.error/data-edn
                 :seon.cluster.eval/read-basis-transaction
                 :seon.cluster.eval/interrupted-at :seon.cluster.eval/diagnostics
                 :seon.eval/duration-ms :seon.sci.eval/ending-ns
                 :seon.print/length :seon.print/level
                 {:seon.cluster.eval/ns [:seon.ns/name]}
                 {:seon.cluster.eval/read-evidence [*]}]]
    (render/acquire-context!
     (assoc (dissoc request :seon.turn/id)
            ::ledger? true :seon.db/pull-selector selector))))

(defn- ledger-evaluations {:malli/schema [:=> [:cat :seon.render/context-request] [:or :nil [:map-of :int [:vector :map]] :seon.render.web/context-error]]}
  [request]
  (let [acquired (ledger-acquisition request)]
    (if (:seon.render.web/refused-member acquired) acquired
        (get-in acquired [::ledger-data ::evaluations]))))

(defn- ledger-url [agent-id turn-id query]
  (route/path ::route/agent-debug {:id agent-id} (assoc query :turn turn-id)))

(defn- ledger-body-id [turn-id]
  (block/surface-id (keyword "ledger-turn" turn-id)))

(defn- ledger-context-id [turn-id]
  (block/surface-id (keyword "ledger-context" turn-id)))

(defn- colour-source [source]
  (into [:code]
        (map (fn [[style token]] [:span {:class (str "seon-syntax-" style)} token]))
        (repl/syntax-tokens source)))

(defn- ledger-heading [label author detail]
  [:header {:class "seon-ledger-section-heading" :data-author "seon"}
   [:h3 label] [:span detail] [:span {:class "seon-ledger-author"} (str "[" author "]")]])

(defn- ledger-emissions [evaluations]
  (for [saved evaluations]
    [:pre (repl/render-emission-html (::emission saved))]))

(defn- reply-intent
  "The reply's first comment line, whole: HTML never clips."
  {:malli/schema [:=> [:cat [:or :nil :string]] [:or :nil :string]]}
  [reply]
  (some #(let [line (str/trim %)]
           (when (str/starts-with? line ";")
             (str/trim (apply str (drop-while (fn [c] (= c \;)) line)))))
        (str/split-lines (or reply ""))))

(defn- results-summary [counts]
    (if (empty? counts) "no forms"
      (str/join " · " (keep #(when-let [n (get counts %)]
                              (str n " " % (when (and (> n 1) (not= % "out")) "s")))
                            ["value" "error" "interrupted" "out"]))))

(defn- ledger-effects
  "Acquire effect facts for all closed turns in one batch per fact family."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:sequential :map]] [:or [:map [:seon.render.transcript/messages :map] [:seon.render.transcript/definitions :map]] :seon.db/error-result]]}
  [database agent-id entities]
  (let [intervals (db/q '[:find ?id ?opened ?closed :in $ [?id ...]
                          :where [?turn :seon.turn/id ?id ?opened]
                                 [?turn :seon.turn/closed-tx ?closed]]
                        database (mapv :seon.turn/id entities))
        messages (db/q '[:find ?id ?target :in $ ?agent [[?id ?from ?to]]
                          :where [?a :seon.agent/id ?agent] [?m :seon.message/from ?a ?tx]
                                 [(<= ?from ?tx)] [(<= ?tx ?to)] [?m :seon.message/to ?recipient]
                                 [?recipient :seon.agent/id ?target]] database agent-id intervals)
        definitions (db/q '[:find ?symbol ?tx
                             :where (or [?f :seon.fn/sym ?symbol ?tx]
                                        [?f :seon.test/sym ?symbol ?tx])] database)]
    (if-let [failure (some #(when (db/read-refusal? %) %) [intervals messages definitions])]
      failure
      {::messages (group-by first messages)
       ;; Acquire each definition once, then relate it to the saved intervals.
       ;; Supplying every interval to the query repeats the program scan.
       ::definitions (group-by first
                       (set (for [[id from to] intervals
                                  [function-symbol tx] definitions
                                  :when (<= from tx to)]
                              [id function-symbol])))})))

(defn- turn-effects {:malli/schema [:=> [:cat :map :map] :map]}
  [effects entity]
  (let [turn-id (:seon.turn/id entity)
        messages (get (::messages effects) turn-id)
        definitions (get (::definitions effects) turn-id)
        details (concat (map #(str "message sent to " (second %)) messages)
                        (map #(str "definition installed during this turn: " (second %)) definitions))]
    (cond
      (db/read-refusal? effects) {::details (:seon.error/message effects)}
      (not (:seon.turn/closed-tx entity)) {::details "Turn is still open."}
      :else {::details (if (seq details) (str/join " · " details) "—")
             ::summary (str/join " · " (cond-> []
                                       (seq messages) (conj "message sent")
                                       (seq definitions) (conj "definition installed")))})))

(defn- emission-byte-count [emissions]
  (reduce + 0 (map ::contributed-bytes emissions)))

(defn- ledger-entities
  {:malli/schema [:=> [:cat :seon.db/database-value [:or [:sequential :map] :seon.db/error-result] [:maybe :map]]
                  [:or [:vector :map] :seon.db/error-result]]}
  [database entities evaluations]
  (if (db/read-refusal? entities) entities
    (mapv (fn [entity]
            (let [attempts (mapv #(assoc % ::usage (attempt-usage %))
                                 (sort-by :seon.ai.attempt/ordinal (:seon.turn/attempts entity)))
                  own (get evaluations (:db/id entity) [])]
              (assoc entity ::kind (turn-kind database entity)
                     :seon.turn/attempts attempts ::attempt (last attempts)
                     ::bytes (emission-byte-count own)
                     ::outcomes (frequencies (map ::outcome own))))) entities)))

(defn acquire-ledger-data
  "Carry ledger entities and evaluation displays on the acquired history basis."
  {:malli/schema [:=> [:cat :map [:vector :map] [:vector :string]] :map]}
  [request entries segments]
  (let [database (:seon.db/db request)
        agent-id (:seon.agent/id request)
        projection ((requiring-resolve 'seon.sci.kernel/context-projection) (:seon.sci.eval/ctx request))
        evaluations
        (group-by #(get-in % [:seon.cluster.eval/run :db/id])
          (mapv (fn [entry segment]
                  (let [entity (:seon.render/value entry)]
                    (assoc entity ::emission (repl/entity-emission (assoc entity :seon.db/db database))
                           ::label (emission-label projection entity)
                           ::outcome (cond (:seon.cluster.eval/interrupted-at entity) "interrupted"
                                           (:seon.cluster.eval/error entity) "error"
                                           (:seon.eval/shown entity) "value" :else "out")
                           ::contributed-bytes (blob/utf8-size segment)))) entries segments))
        entities (ledger-entities database (turn-entities database agent-id) evaluations)
        effects (ledger-effects database agent-id entities)
        calibrations (into {} (for [model (distinct (keep #(get-in % [::attempt :seon.ai/model]) entities))]
                                [model ((requiring-resolve 'seon.cluster.prompt/agent-calibration)
                                        database (:seon.boot/cluster-name (env/of (:seon.sci.eval/ctx request))) agent-id model)]))]
    {::evaluations evaluations
     ::rows (mapv #(assoc % ::effects (turn-effects effects %)) entities)
     ::calibrations calibrations}))

(defn- ledger-turn-body {:malli/schema [:=> [:cat :map [:sequential :map] [:or :nil :map] [:or :nil :map]] :seon.render/hiccup]}
  [request entities evaluations entity]
  (let [database (:seon.db/db request)
        ordinal (::ordinal entity)
        provider? (= "Provider" (::kind entity))
        authored? (not= "System" (::kind entity))
        own (get evaluations (:db/id entity) [])
        generated (if authored?
                    (->> entities (take ordinal) reverse
                         (take-while #(= "System" (::kind %))) reverse
                         (mapcat #(get evaluations (:db/id %) []))) own)
        added-bytes (emission-byte-count generated)
        reply (or (turn-reply (:seon.db/connection request) entity) "")
        turn-id (:seon.turn/id entity)
        capture (when provider?
                  (db/q '[:find ?text . :in $ ?id :where [?t :seon.turn/id ?id]
                          [?c :seon.context.capture/run ?t] [?c :seon.context.capture/prompt ?text]] database turn-id))
        attempt (::attempt entity)
        usage (::usage attempt)
        calibration (get (::calibrations request) (:seon.ai/model attempt))
        opening (filter #(= (:db/id (first entities)) (get-in % [:seon.cluster.eval/run :db/id])) generated)
        later (if authored? (remove (set opening) generated) generated)
        groups (partition-by :seon.cluster.eval/source later)]
    [:div {:id (ledger-body-id turn-id) :data-ledger-loaded turn-id :class "seon-ledger-body"}
     [:section {:class "seon-ledger-sent" :data-author "seon"}
      (ledger-heading (cond provider? "WE SENT"
                            authored? "CONTEXT BEFORE REPLY"
                            (zero? ordinal) "WE GENERATED (opening)"
                            :else (str "WE GENERATED (system turn " ordinal ")"))
                      "seon" (str (count generated) " emissions · " (format "%,d" added-bytes) " bytes"))
      (when authored? [:p {:class "seon-ledger-note"}
                      (if (seq opening) "Opening + since-diff before the first reply."
                        "Generated context added before this reply; earlier results remain in the full context.")])
      (when (and authored? (seq opening))
        [:details {:class "seon-ledger-generated" :data-opening-emissions (count opening)}
         [:summary (str "opening (" (count opening) " emissions)")]
         (for [saved opening]
           [:details {:class "seon-ledger-generated"}
            [:summary (::label saved)] (ledger-emissions [saved])])])
      (if (seq generated)
        (list
         (for [matches groups]
           (let [source (:seon.cluster.eval/source (first matches))
                 prior (->> entities (take ordinal)
                            (mapcat #(get evaluations (:db/id %) []))
                            (take-while #(not= (:seon.cluster.eval/id %)
                                              (:seon.cluster.eval/id (first matches))))
                            (filter #(= source (:seon.cluster.eval/source %))) last)]
             [:details {:class "seon-ledger-generated"}
              [:summary [:code (::label (first matches))]
               (when prior
                 [:span (str " · re-read · " (reread-summary [prior (last matches)]))])]
              (ledger-emissions matches)])))
        [:p "Nothing new — no generated context was added before this turn."])
      (when authored?
        [:details {:class "seon-ledger-full-context"
                   (keyword "data-on:toggle")
                   (str "if(el.open && !el.querySelector('[data-context-loaded]')) @get('"
                        (ledger-url (:seon.agent/id request) turn-id {:context "true"}) "')")}
         [:summary
          (str (if provider? "Full context as sent" "Full context before reply")
               (when capture
                 (str ": " (format "%,d" (blob/utf8-size capture)) " bytes · "
                      (if (or (db/read-refusal? calibration) (:seon.config/error-key calibration) (:seon.config/missing-effective calibration) (:seon.cluster.prompt/missing-cluster calibration) (:seon.cluster.prompt/missing-config calibration))
                        "estimate unavailable"
                        (str "rebuilt ≈" (format "%,d" (tokens/estimate capture calibration)) " tokens"))))
               (when-let [billed (::prompt usage)] (str " · billed " (format "%,d" billed))))]
         [:div {:id (ledger-context-id turn-id)}]])]
     (when authored?
       (list
        [:section {:class "seon-ledger-reply" :data-author "agent"}
         (ledger-heading "AGENT REPLIED" "agent" (str (format "%,d" (blob/utf8-size reply)) " bytes"))
         [:pre {:data-reply-bytes (blob/utf8-size reply) :data-reply-turn turn-id} (colour-source reply)]]
        [:section {:class (str "seon-ledger-results"
                              (when (some :seon.cluster.eval/error own) " seon-ledger-results-error"))
                   :data-author "seon" :data-evaluation-count (count own)}
         (ledger-heading "RESULTS (evaluated by seon)" "seon"
                         (str (count own) (if (= 1 (count own)) " evaluation" " evaluations")))
         (if (seq own)
           (for [saved own
                 :let [outcome (::outcome saved)
                       answer (or (repl/response (::emission saved)) "No response recorded.")
                       rendered (colour-source answer)
                       long? (> (count (str/split-lines answer)) 6)]]
             [:article {:class (str "seon-ledger-result seon-ledger-result-" outcome)
                        :data-result-ordinal (:seon.cluster.eval/ordinal saved)}
              [:header [:span (str (inc (:seon.cluster.eval/ordinal saved)))]
               [:code (first (str/split-lines (or (:seon.cluster.eval/source saved) "Reader input")))]
               [:strong outcome]]
              (if long?
                [:details [:summary
                           [:pre (colour-source (str/join "\n" (take 6 (str/split-lines answer))))]
                           "Show all"] [:pre rendered]]
                [:pre rendered])])
           [:p "No evaluations recorded for this reply."])
         [:p {:class "seon-ledger-effects"}
          (str "Effects: " (::details (::effects entity)))]]))]))

(defn render-ledger-turn
  "Load one card from saved reply and evaluations without executing forms."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [acquired (ledger-acquisition request)
        {::keys [rows evaluations calibrations]} (::ledger-data acquired)]
    (if (:seon.render.web/refused-member acquired)
      [:div {:id (ledger-body-id (:seon.turn/id request))} [:p (:seon.error/message acquired)]]
      (let [entity (some #(when (= (:seon.turn/id request) (:seon.turn/id %)) %) rows)]
        (ledger-turn-body (assoc request ::calibrations calibrations) rows evaluations entity)))))

(defn render-ledger-context
  "Expand the same faithful per-turn transcript beneath its ledger card."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [rendered (render-session request)]
    [:div {:id (ledger-context-id (:seon.turn/id request)) :data-context-loaded "true"
           :data-signals (:data-signals (second rendered))}
     [:p "Exact saved prompt · readline, input, then response"]
     (last rendered)]))


(defn- finding [code label matches]
  {::code code ::label label ::count (count matches) ::matches (vec matches)})

(defn- evaluation-match [by-eid saved]
  {::turn (get by-eid (get-in saved [:seon.cluster.eval/run :db/id]))
   ::detail (let [form (::value (readable-shown (:seon.cluster.eval/source saved)))]
              (cond (= "reply" (get-in (::value (readable-shown (:seon.eval/shown saved))) [:seon.error/data :seon.sci.reader/phase]))
                    "Agent-written REPL response"
                    (and (seq? form) (symbol? (first form))) (str (first form))
                    :else "Unreadable reply form"))})

(defn- error-problem
  "Detect evaluated forms that failed, independently of provider success."
  [by-eid evaluations]
  (finding :errors "Error evaluations"
           (map #(evaluation-match by-eid %) (filter :seon.cluster.eval/error evaluations))))

(defn- fabricated-problem
  "Detect agent-authored responses rejected by the reply grammar."
  [by-eid evaluations]
  (finding :fabricated "Fabricated responses"
           (map #(evaluation-match by-eid %)
                (filter #(= "reply" (get-in (::value (readable-shown (:seon.eval/shown %))) [:seon.error/data :seon.sci.reader/phase])) evaluations))))

(defn- empty-reply-problem
  "Detect settled replies that produced no evaluation evidence."
  [entities evaluations]
  (let [evaluated (set (map #(get-in % [:seon.cluster.eval/run :db/id]) evaluations))]
    (finding :empty-replies "Replies with zero evaluations"
             (for [entity entities
                   :when (and (:seon.turn/closed-tx entity)
                              (seq (:seon.turn/attempts entity))
                              (or (some? (:seon.turn/reply entity)) (:seon.turn/reply-blob entity))
                              (not (evaluated (:db/id entity))))]
               {::turn entity ::detail "A reply was saved, but no evaluations followed."}))))

(defn- repeated-problem
  "Detect repeated provider forms whose saved results did not change."
  [by-eid evaluations]
  (finding :repeated "Repeated forms with identical results"
           (mapcat (fn [[_ matches]] (map #(evaluation-match by-eid %) (rest matches)))
                   (group-by (juxt :seon.cluster.eval/source :seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/output)
                             (filter #(seq (:seon.turn/attempts (get by-eid (get-in % [:seon.cluster.eval/run :db/id])))) evaluations)))))

(defn- churn-problem
  "Detect prompt growth from repeated system reads, including changed keys."
  [database by-eid evaluations]
  (let [groups (->> evaluations
                    (filter #(let [entity (get by-eid (get-in % [:seon.cluster.eval/run :db/id]))]
                               (and (pos? (::ordinal entity)) (= "System" (turn-kind database entity)))))
                    (group-by :seon.cluster.eval/source)
                    (filter #(< 1 (count (val %))))
                    (sort-by first))]
    (assoc (finding :churn "Repeated system reads"
                    (mapcat (fn [[_ matches]] (map #(evaluation-match by-eid %) matches)) groups))
           ::groups
           (mapv (fn [[_ matches]]
                   {::detail (str (::label (first matches)) " · ×" (count matches)
                                  " · " (reread-summary matches))
                    ::bytes (reduce + (map ::contributed-bytes matches))
                    ::matches (mapv #(evaluation-match by-eid %) matches)}) groups))))

(defn- directory-problem
  "Passive history displays saved directory bytes; integrity remains unchecked."
  [_database _by-eid _evaluations]
  (assoc (finding :directory "Directory integrity" [])
         ::unknown 1 ::not-checked true))

(defn- fault-problems
  "Detect delivered core faults and turns whose trigger points at a fault fact."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:sequential :map]] [:vector :map]]}
  [database agent-id entities]
  (let [messages (db/q '[:find ?mid ?error (sum ?count) :with ?occurrence :in $ ?agent-id
                         :where [?a :seon.agent/id ?agent-id] [?m :seon.message/to ?a]
                                [?m :seon.message/about ?error] [?f :seon.error/signature ?error]
                                [?m :seon.message/id ?mid]
                                [?f :seon.error/occurrences ?occurrence]
                                [?occurrence :seon.error.occurrence/count ?count]] database agent-id)]
    (if (db/read-refusal? messages)
      [(assoc (finding :faults "Fault delivery" []) ::unknown 1)
       (assoc (finding :fault-turns "Turns opened by faults" []) ::unknown 1)]
      (let [fault-message-ids (set (map first messages))
            triggered (filter #(fault-message-ids (get-in % [:seon.turn/trigger :seon.message/id])) entities)]
        [(finding :faults "Fault notifications delivered"
                  (for [[mid signature count] messages]
                    {::message mid ::detail (str "Error " signature "; occurrences: " count)
                     ::turn (first (filter #(= mid (get-in % [:seon.turn/trigger :seon.message/id])) entities))}))
         (finding :fault-turns "Turns opened by faults"
                  (map #(hash-map ::turn % ::detail "The trigger message references a core fault.") triggered))]))))

(defn prefix-subject
  "One turn's captured-prefix subject; its declared pair is render-captured-prefix."
  {:malli/schema [:=> [:cat :seon.turn/id :seon.agent/id] :seon.render.transcript/prefix-subject]}
  [turn-id agent-id]
  {:seon.turn/id turn-id :seon.agent/id agent-id})

(defn render-captured-prefix
  "The captured history bytes, excluding the turn owner's changing frame."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :nil :string :seon.db/error-result]]}
  [{database :seon.db/db turn-id :seon.turn/id agent-id :seon.agent/id}]
  (let [text (db/q '[:find ?text . :in $ ?id
                     :where [?t :seon.turn/id ?id] [?c :seon.context.capture/run ?t]
                            [?c :seon.context.capture/prompt ?text]] database turn-id)
        opening (when (string? text) (turn/opening-db database turn-id))
        frame (when (and opening (not (or (db/read-refusal? opening) (:seon.turn/missing-opening-datom opening))))
                (repl/frame opening agent-id))
        suffix (str "\n\n" frame)]
    (when frame
      (cond (= text frame) ""
            (str/ends-with? text suffix) (subs text 0 (- (count text) (count suffix)))
            :else text))))

(defn- prefix-problem
  "Compare captured history prefixes; the owner's changing turn frame is outside history."
  [request entities]
  (let [entities (filter #(seq (:seon.turn/attempts %)) entities)
        cache (render/shared-cache (:seon.sci.eval/ctx request))
        registration [::prefix (:seon.agent/id request)]
        retained (get-in @cache [:seon.render.web/calls registration] {})
        captured (atom {})
        ;; The caller names its cluster on the request; a refused profile is
        ;; passed on, never carried as the prefix render's profile.
        profile (render/request-profile
                 (cond-> request
                   (:seon.cluster/name request)
                   (assoc :seon.boot/cluster-name (:seon.cluster/name request))))
        _ (when (:seon.render/refused-member profile)
            (throw (ex-info (:seon.error/message profile) profile)))
        request (assoc request :seon.render/profile profile
                               :seon.render/output :seon.render/ai
                               :seon.render.call/function 'seon.render.transcript/prefix-subject
                               :seon.render/retained-calls retained
                               :seon.render/captured-calls captured)
        prefixes (mapv (fn [entity]
                         {::turn entity
                          ::prefix (render/render-call
                                     (assoc request
                                       :seon.render.call/id
                                       [[:seon.ai.attempt/id (:seon.ai.attempt/id
                                                              (last (sort-by :seon.ai.attempt/ordinal (:seon.turn/attempts entity))))]]
                                       :seon.render/value (prefix-subject (:seon.turn/id entity)
                                                                          (:seon.agent/id request))))}) entities)
        pairs (partition 2 1 prefixes)
        measured (filter #(every? (comp string? ::prefix) %) pairs)
        changed (for [[previous current] measured
                      :when (not (str/starts-with? (::prefix current) (::prefix previous)))]
                  {::turn (::turn current) ::detail "Captured history prefix bytes changed."})]
    (swap! cache assoc-in [:seon.render.web/calls registration] @captured)
    (assoc (finding :prefix "Prefix changed" changed)
           ::stable (- (count measured) (count changed)) ::measured (count measured)
           ::unknown (+ (- (count pairs) (count measured)) (if (empty? pairs) 1 0)))))

(defn- session-budget
  "Detect exhausted turn/step budgets and unavailable billing or rate evidence."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id [:sequential :map]] :map]}
  [database agent-id entities]
  (let [attempts (vec (for [entity entities a (:seon.turn/attempts entity)]
                        {::turn entity ::attempt a ::usage (::usage a)}))
        totals (reduce #(merge-with + %1 (::usage %2)) {} attempts)
        models (into {} (for [model (distinct (keep #(get-in % [::attempt :seon.ai/model]) attempts))]
                          [model (db/pull database
                                         [:seon.ai.model/input-usd-per-mtok
                                          :seon.ai.model/cached-input-usd-per-mtok
                                          :seon.ai.model/output-usd-per-mtok]
                                         [:seon.ai.model/id model])]))
        rates-of (fn [a] (mapv #(get-in models [(:seon.ai/model a) %])
                               [:seon.ai.model/input-usd-per-mtok
                                :seon.ai.model/cached-input-usd-per-mtok
                                :seon.ai.model/output-usd-per-mtok]))
        costs (mapv (fn [{a ::attempt u ::usage}]
                      (let [[input cached output :as rates] (rates-of a)]
                        (when (every? number? (into rates [(::miss u) (::hit u) (::out u)]))
                          (/ (+ (* input (::miss u)) (* cached (::hit u)) (* output (::out u))) 1000000.0)))) attempts)]
    {::used (turn/episode-runs database agent-id)
     ::bound (#'turn/max-episode-runs database agent-id)
     ::totals totals ::attempts (count attempts)
     ::missing-usage (count (remove #(every? number? (map (::usage %) [::prompt ::hit ::miss ::out])) attempts))
     ::cost (when (every? number? costs) (reduce + 0 costs))
     ::missing-rates (count (remove #(every? number? (rates-of (::attempt %))) attempts))}))

(defn- session-problems {:malli/schema [:=> [:cat :map [:sequential :map] [:or :nil :map]] :map]}
  [request entities evaluations]
  (let [database (:seon.db/db request)
        by-eid (into {} (map (juxt :db/id identity)) entities)
        saved (mapcat #(get evaluations (:db/id %) []) entities)
        saved-by-eid (into {} (map (juxt :db/id identity)) saved)
        ;; A later read-basis assertion is a silent refresh of this evaluation.
        ;; History preserves every refresh; the initial settlement does not count.
        refreshes (db/q '[:find ?evaluation ?t
                          :in $ $history [?evaluation ...]
                          :where [$ ?evaluation :seon.eval/shown _ ?shown-t]
                          [$history ?evaluation :seon.cluster.eval/read-basis-transaction _ ?t true]
                          [(> ?t ?shown-t)]]
                        database (db/history database) (vec (keys saved-by-eid)))
        empty-emissions (filter #(and (= "System" (::kind (get by-eid (get-in % [:seon.cluster.eval/run :db/id]))))
                                      (= {:seon.repl/changes {}}
                                         (::value (readable-shown (:seon.eval/shown %))))) saved)]
    {::budget (session-budget database (:seon.agent/id request) entities)
     ::rules (into [(finding :stalled "Session stalled" (keep identity [(session-stall request entities)]))
                    (fabricated-problem by-eid saved)
                    (error-problem by-eid saved)
                    (churn-problem database by-eid saved)
                    (assoc (finding :stale-but-unchanged "stale-but-unchanged reads"
                                    (map #(evaluation-match by-eid %)
                                         (concat empty-emissions
                                                 (when-not (db/read-refusal? refreshes)
                                                   (map #(get saved-by-eid (first %)) refreshes)))))
                           ::unknown (if (db/read-refusal? refreshes) 1 0))
                    (repeated-problem by-eid saved)
                    (empty-reply-problem entities saved)
                    (directory-problem database by-eid saved)
                    (prefix-problem request entities)]
                   (fault-problems database (:seon.agent/id request) entities))}))

(defn- problem-links [agent-id matches]
  [:span {:class "seon-problem-links"}
   (for [entity (sort-by ::ordinal (distinct (keep ::turn matches)))
         :let [href (ledger-url agent-id (:seon.turn/id entity) {})]]
     [:a {:href href
          (keyword "data-on:click")
          (nav-click href (ledger-url agent-id (:seon.turn/id entity) {:ledger "true"}))}
      (str "turn " (::ordinal entity))])])

(defn- budget-html [budget]
  [:div {:class "seon-session-budget"}
   [:p (str "Budget · " (::used budget) "/" (or (::bound budget) "unavailable") " turns used")]
   [:p (str "Billing tokens · " (str/join " · "
              (for [[k label] [[::prompt "in"] [::hit "hit"] [::miss "miss"] [::out "out"]]]
                (str (format "%,d" (get (::totals budget) k 0)) " " label)))
            " · " (cond (number? (::cost budget)) (format "$%.5f at rates on file" (double (::cost budget)))
                         (pos? (::missing-rates budget)) "no rate on file"
                         :else "cost unavailable: incomplete usage")
            (when (pos? (::missing-usage budget)) (str " · usage unavailable on " (::missing-usage budget) " attempts")))]])

(defn- problem-summary [request problems]
  (let [by-code (into {} (map (juxt ::code identity)) (::rules problems))
        budget (::budget problems)]
    [:div {:class "seon-problem-summary"}
     [:p (str (::used budget) "/" (or (::bound budget) "unavailable") " turns used")]
     [:p [:span {:class (when (pos? (get-in by-code [:errors ::count])) "seon-emission-error")}
          (str (get-in by-code [:errors ::count]) " evaluation errors")]
      " · " [:span {:class (when (pos? (get-in by-code [:faults ::count])) "seon-emission-error")}
              (str (get-in by-code [:faults ::count]) " faults delivered")]
      " · " [:span {:class (when (pos? (get-in by-code [:churn ::count])) "seon-problem-warning")}
              (str (get-in by-code [:churn ::count]) " repeated system reads")]
      " · "
      [:a {:href (str "#" (block/surface-id (keyword "session-problems" (:seon.agent/id request))))}
       "Review problems"]]]))

(defn- problems-html [request problems]
  (let [agent-id (:seon.agent/id request)
        rules (::rules problems)
        active (filter #(or (pos? (::count %)) (pos? (get % ::unknown 0))) rules)
        passed (remove (set active) rules)]
    [:section {:class "seon-session-problems" :data-author "seon"
               :id (block/surface-id (keyword "session-problems" agent-id))
               :data-init "el.style.scrollMarginTop = (el.closest('.seon-ledger').querySelector('.seon-session-sticky').offsetHeight + 8) + 'px'"}
     [:h2 "What went wrong"]
     (budget-html (::budget problems))
     (for [rule active]
       [:details {:data-problem (name (::code rule)) :data-problem-count (::count rule)}
        [:summary
         [:span {:class (if (#{:churn :prefix :repeated} (::code rule)) "seon-problem-warning" "seon-emission-error")} "● "]
         (if (::not-checked rule)
           (str (::label rule) " · not checked")
           (str (::label rule) " · " (::count rule)
                (when (pos? (get rule ::unknown 0)) (str " · " (::unknown rule) " checks unavailable"))))]
        (if (::groups rule)
          (for [group (::groups rule)]
            [:div [:p (str (::detail group) " · " (format "%,d" (::bytes group)) " bytes")]
             (problem-links agent-id (::matches group))])
          [:div
           (problem-links agent-id (::matches rule))
           (for [detail (distinct (map ::detail (::matches rule)))] [:p detail])])])
     (when (seq passed)
       [:details {:class "seon-checks-passed"}
        [:summary (str "Checks passed · " (count passed))]
        (for [rule passed]
          [:p {:data-problem (name (::code rule)) :data-problem-count 0}
           (if (= :prefix (::code rule))
             (str "● Prefix stable on " (::stable rule) "/" (::measured rule) " captured turn pairs")
             (str "● " (::label rule) " · 0"))])])]))

(defn- ledger-strip
  "Navigate every stored turn; area shows added bytes and colour shows recorded outcome."
  [request entities evaluations selected problems]
  (let [amounts (into {} (map (fn [entity] [(:seon.turn/id entity)
                                        (::bytes entity)]) entities))
        largest (reduce max 1 (vals amounts))
        marks (reduce (fn [result rule]
                        (reduce (fn [result match]
                                  (if-let [id (:seon.turn/id (::turn match))]
                                    (update result id (fnil conj #{}) (::label rule)) result))
                                result (::matches rule))) {} (::rules problems))]
    [:nav {:class "seon-ledger-strip" :aria-label "Select a turn" :data-author "seon"}
     [:p "Turns · filled: provider · hollow: generated or virtual · width: context added · red: errors · amber: no result/open · dot: problem"]
     [:div {:class "seon-ledger-strip-cells"}
      (for [entity entities
            :let [turn-id (:seon.turn/id entity)
                  own (get evaluations (:db/id entity) [])
                  provider? (seq (:seon.turn/attempts entity))
                  errors (get (::outcomes entity) "error" 0)
                  tone (cond (pos? errors) "error"
                             (and provider? (or (empty? own) (nil? (:seon.turn/closed-tx entity)))) "warning"
                             provider? "success" :else "neutral")
                  kind (::kind entity)
                  amount (get amounts turn-id)
                  attempt (::attempt entity)
                  usage (::usage attempt)
                  href (ledger-url (:seon.agent/id request) turn-id {})]]
        [:a {:href href :data-strip-turn turn-id :data-added-bytes amount
             :aria-current (when (= selected turn-id) "step")
             :class (str "seon-ledger-turn-cell seon-ledger-turn-cell-" tone
                         (when provider? " seon-ledger-turn-cell-provider"))
             :style (str "width:" (max 24 (long (* 96 (/ amount largest)))) "px")
             :title (str "Turn " (::ordinal entity) " · " kind " · " turn-id "\n"
                         (when-let [opened (get-in entity [:seon.turn/opened-tx :db/txInstant])]
                           (format "%tT" opened))
                         " · " (format "%,d" amount) " bytes added"
                         (when provider?
                           (str "\n"
                                (str/join " · "
                                  (for [[label value] [["in" (::prompt usage)]
                                                      ["hit" (::hit usage)]
                                                      ["miss" (::miss usage)]
                                                      ["out" (::out usage)]]]
                                    (str (if (number? value) (format "%,d" value) "unavailable") " " label)))))
                         (when-let [labels (seq (get marks turn-id))]
                           (str "\n" (str/join " · " (sort labels)))))
             (keyword "data-on:click")
             (nav-click href (ledger-url (:seon.agent/id request) turn-id {:ledger "true"}))}
         (str (::ordinal entity) (when (seq (get marks turn-id)) "·"))])]]))

(defn- turn-story [request evaluations entity]
  (let [own (get evaluations (:db/id entity) [])
        authored? (not= "System" (::kind entity))]
    (str/join " · "
      (remove str/blank?
        (if authored?
          [(reply-intent (turn-reply (:seon.db/connection request) entity))
           (results-summary (::outcomes entity))
           (::summary (::effects entity))
           (when (:seon.turn/closed-tx entity)
             (case (:seon.turn/disposition entity) :wait "done" :completed "completed" nil))]
          [(cond (zero? (::ordinal entity)) (str "opening · " (count own) " emissions")
                 (empty? own) "no new emissions"
                 :else (str "re-read " (str/join ", " (distinct (map ::label own)))))])))))

(defn render-ledger
  "Turn ledger: generated context, raw model reply, evaluated results.
  Only selected and last three cards have bodies on initial render."
  {:malli/schema [:=> [:cat [:and :seon.render/unit
                             [:map [:seon.db/db :seon.db/db] [:seon.agent/id :seon.agent/id]]]]
                  :seon.render/hiccup]}
  [request]
  (let [acquired (ledger-acquisition request)
        {::keys [rows evaluations calibrations]} (::ledger-data acquired)
        refusal (when (:seon.render.web/refused-member acquired) acquired)
        request (assoc request ::calibrations calibrations)
        selected (or (:seon.turn/id request) (:seon.turn/id (last rows)))
        expanded (conj (set (map :seon.turn/id (take-last 3 rows))) selected)
        problems (when-not (or (db/read-refusal? rows) refusal)
                   (session-problems request rows evaluations))]
    [:section {:id (session-id (:seon.agent/id request)) :class "seon-session seon-ledger" :data-author "seon"}
     [:div {:class "seon-session-sticky"} (session-header request rows)
      [:h2 "Turn ledger"]
      (when problems (problem-summary request problems))
      (when-not refusal (ledger-strip request rows evaluations selected problems))]
     (when problems (problems-html request problems))
     (cond
       refusal [:p (:seon.error/message refusal)]
       (seq rows)
       (for [entity rows
             :let [turn-id (:seon.turn/id entity)
                   attempt (::attempt entity)
                   usage (::usage attempt)]]
         [:details {:class "seon-ledger-turn" :open (contains? expanded turn-id)
                    :data-turn-id turn-id :data-turn-kind (::kind entity)
                    :id (block/surface-id (keyword "turn" turn-id))
                    :data-init (when (= selected turn-id)
                                 "el.style.scrollMarginTop = (el.closest('.seon-ledger').querySelector('.seon-session-sticky').offsetHeight + 8) + 'px'; el.scrollIntoView({block:'start'})")
                    (keyword "data-on:toggle")
                    (str "if(el.open && !el.querySelector('[data-ledger-loaded]')) @get('"
                         (ledger-url (:seon.agent/id request) turn-id {:card "true"}) "')")}
          [:summary
           (str "Turn " (::ordinal entity) " · " (str/lower-case (::kind entity)) " · ")
           (runtime-time (get-in entity [:seon.turn/opened-tx :db/txInstant]))
           (when attempt
             (str " · " (:seon.ai/model attempt)
                  " · " (format "%,d" (get usage ::prompt 0)) " in / "
                  (format "%,d" (get usage ::out 0)) " out"))
           (str " · " (runtime-duration (get-in entity [:seon.turn/opened-tx :db/txInstant])
                                       (get-in entity [:seon.turn/closed-tx :db/txInstant])))
           [:span {:class "seon-ledger-story" :data-turn-story turn-id}
            (str " · " (turn-story request evaluations entity))]]
          (if (contains? expanded turn-id)
            (ledger-turn-body request rows evaluations entity)
            [:div {:id (ledger-body-id turn-id)}])])
       :else [:p "No turns recorded."])]))

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
