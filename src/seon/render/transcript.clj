(ns seon.render.transcript
  "One agent's messages, turns, and evaluation results.

  The renderer is the schema-declared agent-session projection. Messages are
  reverse connections while evaluations are reached through the
  agent's turns. Raw facts never acquire a detail level; every full, summary,
  and elided decision is derived for this call."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.message :as message]
            [seon.context :as context]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.error :as error]
            [seon.eval :as evaluation]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.agent :as agent]
            [seon.render.block :as block]
            [seon.render.route :as route]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.sci.admit :as admit])
  (:import [java.io PushbackReader StringReader]))

(def ^:private message-selector
  [:db/id
   :seon.message/id
   :seon.message/content
   :my.message/reason
   {:seon.message/to [:db/id :seon.agent/id]}
   {:seon.message/from [:db/id :seon.agent/id]}
   {:seon.message/about [:db/id]}])

(def ^:private receipt-selector
  [:db/id
   :seon.cluster.eval/id
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/source
   :seon.cluster.eval/read-basis-transaction
   :seon.eval/shown
   :seon.eval/renderer
   :seon.cluster.eval/error
   :seon.cluster.eval/triage-edn
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/output
   :seon.cluster.eval/comment
   ;; THE FORM'S OWN PRINT OPTIONS. `bounded-result` reads these off the
   ;; entry; without them in the pull, every store-derived render printed
   ;; under the shipped default while the in-memory one printed under the
   ;; agent's choice, so the page and the stored bytes disagreed.
   :seon.print/length
   :seon.print/level
   :seon.eval/duration-ms
   :seon.sci.eval/ending-ns
   :seon.problems/id
   :seon.error/kind
   {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
   {:seon.cluster.eval/run
    [:db/id :seon.turn/id {:seon.turn/opened-tx [:db/id :db/txInstant]}
     {:seon.turn/agent
      [:db/id
       :seon.agent/id
       {:seon.agent/namespace [:db/id :seon.ns/name]}]}]}])



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

(defn- recent-message-rows
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

(defn- recent-receipt-rows
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
  [db agent-id]
  (db/q '[:find [?receipt ...]
         :in $ % ?agent-id ?bootstrap-run-id
         :where
         [?agent :seon.agent/id ?agent-id]
         (active-run ?run ?agent ?bootstrap-run-id true)
         [?receipt :seon.cluster.eval/run ?run]]
       db active-runs-rules agent-id (bootstrap/run-id agent-id)))

(defn- bootstrap-task-message-eid
  [db agent-id]
  (db/q '[:find ?message .
          :in $ ?message-id
          :where
          [?message :seon.message/id ?message-id]]
        db (bootstrap/task-message-id db agent-id)))

(defn- candidate-entity-ids
  [db agent-id limit]
  (let [recent
        (->> (concat
              (map (fn [[entity at ordinal tx]]
                     [:message entity at [tx ordinal entity]])
                   (recent-message-rows db agent-id limit))
              (map #(into [:eval] %)
                   (recent-receipt-rows db agent-id limit)))
             (sort-by (fn [[kind _ at id]]
                        [(.getTime ^java.util.Date at)
                         (case kind :message 0 :eval 2 :run 3)
                         id])
                      #(compare %2 %1))
             (take limit)
             (group-by first)
             (reduce-kv (fn [ids kind rows]
                          (assoc ids kind (mapv second rows)))
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
        (into [] (comp (keep #(get-in % [:seon.message/about :db/id]))
                       (distinct))
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
  [database run-id agent-id identities orders message]
  (let [about-eid (get-in message [:seon.message/about :db/id])]
    (merge
     {::kind :message
      ::entity message
      ::id (:seon.message/id message)
      ::content (:seon.message/content message)
      ::from (get-in message [:seon.message/from
                              :seon.agent/id])
      ::to (get-in message [:seon.message/to
                            :seon.agent/id])
      ::about (get identities about-eid)
      ::about-ref? (some? about-eid)
      ::reason (:my.message/reason message)}
     (when (= (bootstrap/task-message-id database agent-id)
              (:seon.message/id message))
       {::bootstrap-trigger? true})
     {::custody
      (context/message-custody database run-id agent-id (:db/id message))}
     (get orders (:db/id message)))))

(defn- receipt-entry
  [receipt]
  (let [ordinal (:seon.cluster.eval/ordinal receipt)]
    {::kind :eval
     ::entity receipt
     ::id (:seon.cluster.eval/id receipt)
     ;; A FROZEN EVALUATION THAT NEVER STARTED HAS NO START INSTANT, and it
     ;; is still a transcript entry — the run's own opening is when the agent
     ;; wrote it, which is exactly what the retired `:input` kind used.
     ::at (or (:seon.cluster.eval/at receipt)
              (get-in receipt [:seon.cluster.eval/run
                               :seon.turn/opened-tx :db/txInstant]))
     ::ordinal ordinal
     ::run-id (get-in receipt [:seon.cluster.eval/run
                               :seon.turn/id])
     ::run-opened-at (get-in receipt [:seon.cluster.eval/run
                                      :seon.turn/opened-tx :db/txInstant])
     ;; ONE ENTITY PER (run, ordinal): the frozen source is this evaluation's
     ;; own attribute, not a twin form entity joined by ordinal.
     ::source (:seon.cluster.eval/source receipt)
     ::namespace
     (or (get-in receipt [:seon.cluster.eval/ns :seon.ns/name])
         (get-in receipt [:seon.cluster.eval/run
                          :seon.turn/agent
                          :seon.agent/namespace
                          :seon.ns/name])
         'user)
     ::read-basis (:seon.cluster.eval/read-basis-transaction receipt)
     ::result (:seon.eval/shown receipt)
     ::renderer (:seon.eval/renderer receipt)
     ::error (:seon.cluster.eval/error receipt)
     ::triage-edn (:seon.cluster.eval/triage-edn receipt)
     ::error-kind (:seon.error/kind receipt)
     ::problem-id (:seon.problems/id receipt)
     ::interrupted-at (:seon.cluster.eval/interrupted-at receipt)
     ::comment (:seon.cluster.eval/comment receipt)
     ::duration-ms (:seon.eval/duration-ms receipt)
     ::print-length (:seon.print/length receipt)
     ::print-level (:seon.print/level receipt)
     ::ending-ns (:seon.sci.eval/ending-ns receipt)
     ::output (:seon.cluster.eval/output receipt)}))



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
        identities (about-identities db messages)
        identity-attrs (identity-attributes db)
        message-orders (message-order-facts db (:message ids))]
    (->> (concat (map (partial message-entry db run-id agent-id
                               identities message-orders)
                      messages)
                 (map receipt-entry receipts)
                 [])
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
  [unit value]
  ;; Transcript values are immutable history entries. Give the shared value
  ;; floor an explicit, content-stable block identity when this internal
  ;; projection is not itself running as a retained render call.
  ;;
  ;; THE FLOOR IS A RENDER CALL AND A RENDER CALL NEEDS ITS SCI CONTEXT. A
  ;; derivation reached without one — an attribute-declared producer receives
  ;; the attribute value and the call-prepared database, and nothing else —
  ;; prints the value with the reader's own printer rather than throwing an
  ;; instrumentation violation into a transcript that was only reporting
  ;; the history it was only reporting.
  (if (:seon.sci.eval/ctx unit)
    (value/render-ai
     (cond-> (assoc unit :seon.render/value value)
       (nil? (:seon.render.call/id unit))
       (assoc :seon.render.call/id [::history-value value])))
    (pr-str value)))

(defn- bounded-scalar
  [unit value]
  (when (some? value)
    (let [bounded (floor-text unit value)]
      (if (= (pr-str value) bounded) value bounded))))

(defn- rendered-family
  [unit family-unit distance]
  (let [db (:seon.db/db unit)
        owner (walk/owning-namespace db family-unit)
        rendered (render/render-call
                  (cond-> (assoc unit
                                 :seon.render/value family-unit
                                 :seon.render/output :seon.render/ai
                                 :seon.render.call/id
                                 [::history-entity (:db/id family-unit)]
                                 :seon.render/distance distance)
                    owner (assoc :seon.render/namespace owner)))
        output (if (:seon.error/kind rendered)
                 (floor-text unit rendered)
                 rendered)]
    (bounded-scalar unit output)))

(defn- message-text
  [unit entry _detail]
  (let [entity (cond-> (::entity entry)
                 (::content entry)
                 (assoc :seon.message/content
                        (bounded-scalar unit (::content entry))))
        sentence (rendered-family unit entity 1)
        extra (cond-> {}
                (::about entry)
                (assoc :seon.message/about (::about entry))
                (and (::about-ref? entry) (nil? (::about entry)))
                (assoc :seon.transcript/unresolved-about? true)
                (::reason entry) (assoc :my.message/reason (::reason entry)))]
    (str (case (::custody entry)
           :seon.context/current-trigger "Current run instruction:\n"
           :seon.context/pending
           "Pending message — awaiting its own run; not this run's instruction:\n"
           "")
         sentence
         (when (seq extra) (str "\n" (floor-text unit extra))))))

(defn- entry-handle
  "The handle naming one entry's value, or nil.

  A stored evaluation derives it from its own entity id; an in-memory one
  carries the handle the fork actually bound. Either way the name comes from
  the identity the value is reachable under, never from an ordinal that
  restarts in every run — and never for a value that was not stored, which
  has no node for the predicate to admit and which the turn's fork therefore
  binds to nothing."
  [entry]
  (let [entity (::entity entry)]
    (or (:seon.repl/handle entity)
        (when (and (string? (:seon.cluster.eval/id entity)) (string? (::result entry)))
          (admit/result-handle (:seon.cluster.eval/id entity))))))

(defn- emission
  "One transcript entry as the REPL emission `seon.repl` renders.

  The transcript owns the BOUNDING — the render unit's floor, its elision
  root, and its print options are what keep a value inside this call's
  budget — and `seon.repl` owns the GRAMMAR. The already-bounded value text
  travels as the node, so the one generator never re-derives what this call
  already decided, and the page, the history unit and the prompt read the
  same bytes because they read the same function."
  [unit entry]
  (let [handle (entry-handle entry)]
    (cond-> {:seon.cluster.eval/source (::source entry)
             :seon.ns/name (or (::namespace entry) 'user)}
      (::comment entry) (assoc :seon.cluster.eval/comment (::comment entry))
      (::ordinal entry) (assoc :seon.cluster.eval/ordinal (::ordinal entry))
      handle (assoc :seon.repl/handle handle)
      ;; MISSING IS A TERMINAL FACT OF THE EVALUATION, so it travels as one
      ;; and `seon.repl` writes the one sentence for it. Bounding a value
      ;; that was never stored is not a thing this call can do.
      (and (::result entry) (nil? (::missing entry)))
      (assoc :seon.repl/value
             (::result entry))
      (::renderer entry) (assoc :seon.eval/renderer (::renderer entry))
      (int? (::print-length entry)) (assoc :seon.print/length
                                           (::print-length entry))
      (int? (::print-level entry)) (assoc :seon.print/level
                                          (::print-level entry))
      (::error entry) (assoc :seon.cluster.eval/error
                             (::error entry))
      (::triage-edn entry) (assoc :seon.cluster.eval/triage-edn
                                  (::triage-edn entry))
      (::output entry) (assoc :seon.cluster.eval/output
                              (::output entry))
      (::ending-ns entry) (assoc :seon.sci.eval/ending-ns (::ending-ns entry))
      (::duration-ms entry) (assoc :seon.eval/duration-ms
                                   (::duration-ms entry)))))

(defn- evaluation-text
  "One frozen form or settled evaluation, through the one REPL generator.

  A frozen form and its evaluation were two arms calling the same function
  with the same argument. `repl/text` already emits a prompt line and no
  response when nothing has settled — absence of a terminal fact IS running —
  so the distinction the two arms encoded is one the generator derives."
  [unit entry _detail]
  (repl/text (emission unit entry)))

(defn- undisposed-run-text
  "The run's own `:seon.render/ai`, with no grammar of its own.

  This used to build `\"system=> \" (pr-str form)` — a third prompt grammar
  for a subject that is not a form anyone evaluated. An undisposed run's
  evaluations are ordinary transcript entries rendered by `seon.repl/text`;
  the run entry says only what the run itself says."
  [_unit entry _detail]
  (or (turn/render-ai (::entity entry)) ""))

(defn- entry-name
  [entry]
  (keyword (str "seon.transcript." (name (::kind entry))) (::id entry)))

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
              :message (message-text unit entry detail)
              :eval (evaluation-text unit entry detail)
              :run (undisposed-run-text unit entry detail))}))

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
  (str/join
   "\n\n"
   (cond-> []
     (seq pinned) (into (map ::text pinned))
     (seq entries) (into (map ::text entries)))))

(defn- html-entries
  [entries]
  (into
   [:ol {:class "seon-transcript-list"}]
   (map (fn [entry]
          [:li (cond->
                 {:id (block/surface-id (entry-name entry))
                  :class (str "seon-transcript-entry"
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
  ;; the entries the transcript holds.
  [pinned entries]
  (into
   [:section {:id (block/surface-id :transcript)
              :class "seon-transcript"}]
   (cond-> []
     (seq pinned)
     (conj (html-entries pinned))
     (seq entries)
     (conj (html-entries entries)))))

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
          (history db (:seon.turn/id unit) agent-id
                   candidate-count (::selected-run-id unit)
                   (:seon.context.contribution/evaluations unit)))))

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
                        db agent-ref))]
    {::selected-run-id (:seon.turn/id unit)
     ::selected-agent-id
     (or supplied-agent-id (when-not (:seon.error/kind queried) queried))
     ::selected-run-error (when (:seon.error/kind queried) queried)}))

(defn- missing-selected-run
  [unit identities]
  (error/diagnostic
   {:seon.error/kind ::selected-run-unavailable
    :seon.error/message
    "The selected run is unavailable because its run, agent, or database identity is missing."
    :seon.error/diagnostic-layer :render
    :seon.error/diagnostic-operation 'seon.render.transcript/render-run
    :seon.error/diagnostic-member :seon.turn/turn
    :seon.error/diagnostic-expected
    [:seon.db/db :seon.turn/id :seon.agent/id]
    :seon.error/diagnostic-offending
    (select-keys unit [:seon.turn/id :seon.turn/agent])
    :seon.error/diagnostic-cause ::selected-run-unavailable
    :seon.error/diagnostic-evidence identities}))

(defn render-run-ai
  "The prompt owns evaluation text; a turn concern emits no AI text."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.error/value]]}
  [_unit]
  "")

(defn message-form
  "Return the ordinary message read form for one message entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'my.message/read {:my.message/id (:seon.message/id unit)}))

(defn inbox-form
  "Read the recipient's pending messages through the exact reverse inbox edge."
  {:malli/schema [:=> [:cat :seon.message/inbox] :seon.render/form]}
  [recipient]
  (list 'seon.db/pull
        (list 'quote '[{:seon.message/_inbox
                       [:seon.message/id :seon.message/content
                        {:seon.message/from [:seon.agent/id]}]}])
        recipient))

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
        agent-id (:seon.agent/id unit)
        namespace-name
        (db/q '[:find ?name .
                :in $ ?agent-id
                :where
                [?agent :seon.agent/id ?agent-id]
                [?agent :seon.agent/namespace ?namespace]
                [?namespace :seon.ns/name ?name]]
              db agent-id)
        candidates (candidate-history unit)
        entries
        (mapv
         (fn [entry]
           ;; ONE GENERATOR. The bytes are `seon.repl/text`'s, so this unit,
           ;; the debug page's AI column and the provider prompt cannot drift
           ;; apart: there is no second place that decides how an evaluation
           ;; reads.
           (let [entry (cond-> entry
                         (nil? (::namespace entry))
                         (assoc ::namespace namespace-name))
                 emitted (emission unit entry)]
             {:seon.render.history/call-id
              [:seon.render.transcript/entry (::kind entry) (::id entry)]
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
  [database turn-id]
  (let [row (db/pull database
                     '[:seon.turn/id {:seon.turn/opened-tx [:db/id :db/txInstant]} :seon.turn/reply-size
                       {:seon.turn/trigger [:seon.message/id]}]
                     [:seon.turn/id turn-id])
        evaluations (db/q '[:find (count ?evaluation) . :in $ ?id
                            :where [?turn :seon.turn/id ?id]
                                   [?evaluation :seon.cluster.eval/run ?turn]]
                          database turn-id)]
    (if (:seon.error/kind row)
      [:p (:seon.error/message row)]
      [:article {:class "seon-turn-header"}
       [:h3 (str "Turn " turn-id)]
       [:dl
        [:dt "Opened"] [:dd (pr-str (get-in row [:seon.turn/opened-tx :db/txInstant]))]
        [:dt "Trigger"] [:dd (or (get-in row [:seon.turn/trigger :seon.message/id]) "None")]
        [:dt "Evaluations"] [:dd (if (:seon.error/kind evaluations)
                                   (:seon.error/message evaluations)
                                   (str (or evaluations 0)))]
        [:dt "Reply"] [:dd (if (find row :seon.turn/reply-size) "Recorded" "None")]]])))

(defn render-run-html
  "Show a turn's header; its evaluations belong in the prompt pane."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (let [{run-id ::selected-run-id agent-id ::selected-agent-id
         identity-error ::selected-run-error
         :as identities}
        (selected-run-identities unit)]
    (cond
      identity-error identity-error
      (and (:seon.db/db unit) run-id agent-id)
      (turn-header (:seon.db/db unit) run-id)
      :else (missing-selected-run unit identities))))

(defn render-session-ai
  "Render the schema-declared agent session while status survives slice 1."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [status (agent/agent-ai unit)]
    (let [history (when (and (:seon.db/db unit)
                             (:seon.sci.admit/caps unit))
                    (render-ai unit))]
      (str status (when (seq history) (str "\n" history))))))

(defn render-session-html
  "Render the schema-declared HTML agent session with stable transcript ids."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [status (agent/agent-html unit)]
    (if (and (:seon.db/db unit) (:seon.sci.admit/caps unit))
      (conj status (render-html unit))
      status)))

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

(defn- agent-config
  "The effective configuration of the cluster this agent belongs to."
  [database]
  (let [cluster-name
        (db/q '[:find ?cluster-name .
                :where [_ :seon.cluster/name ?cluster-name]] database)
        effective (when (and cluster-name
                             (not (:seon.error/kind cluster-name)))
                    (config/effective database cluster-name))]
    (if (and (map? effective) (nil? (:seon.error/kind effective)))
      effective
      (config/defaults))))

(def ^:private history-run-selector
  [:seon.turn/id
   {:seon.turn/opened-tx [:db/id :db/txInstant]}
   {:seon.turn/closed-tx [:db/id :db/txInstant]}])

(defn agent-history
  "This agent's own submitted forms and their stored results, newest run first.

  The runs are bounded by the cluster's declared agent child count; the runs
  beyond it are an ordinary elision value carrying their count and a requery
  identity, so nothing is silently dropped."
  {:malli/schema [:=> [:cat :seon.render.transcript/history-request]
                  [:or :seon.render.transcript/history :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.agent/id}]
  (let [effective (agent-config database)
        limit (long (:seon.config.render.agent/max-children effective))
        caps (config/result-caps effective)
        rows (db/q {:query '[:find ?run ?opened
                             :in $ ?agent-id
                             :where
                             [?agent :seon.agent/id ?agent-id]
                             [?run :seon.turn/agent ?agent]
                             [?run :seon.turn/id _]
                             [?run :seon.turn/opened-tx ?opened]]
                    :args [database agent-id]
                    :order-by '[?opened :desc ?run :desc]})]
    (if (:seon.error/kind rows)
      rows
      (let [total (count rows)
            newest (into [] (take limit) rows)
            runs
            (into []
                  (keep
                   (fn [[eid _opened]]
                     (let [row (db/pull database history-run-selector eid)]
                       (when-not (:seon.error/kind row)
                         (assoc row
                                :seon.render.transcript/entries
                                (history-entries
                                 {:seon.db/db database
                                  :seon.agent/id agent-id
                                  :seon.sci.admit/caps caps
                                  ::selected-run-id
                                  (:seon.turn/id row)}))))))
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

(defn- run-heading
  [run]
  (str "Run " (:seon.turn/id run)
       (when-let [opened (get-in run [:seon.turn/opened-tx :db/txInstant])]
         (str ", opened " (pr-str opened)))
       (if-let [closed (get-in run [:seon.turn/closed-tx :db/txInstant])]
         (str ", closed " (pr-str closed))
         ", still open")
       "."))

(defn format-history-ai
  "Format this agent's own history as the REPL session it was.

  The bytes per entry are the run loop's own, so an error appears exactly as
  the loop printed it rather than in a second error shape invented here."
  {:malli/schema [:=> [:cat [:or :seon.render.transcript/history
                             :seon.error/value]]
                  [:or :string :seon.error/value]]}
  [derived]
  (if (:seon.error/kind derived)
    derived
    (let [runs (:seon.render.transcript/runs derived)
          older (:seon.render.transcript/older-runs derived)]
      (str/join
       "\n\n"
       (cond->
        (if (seq runs)
          (mapv (fn [run]
                  (str/join
                   "\n"
                   (cond-> [(run-heading run)]
                     :always
                     (into (map :seon.render.history/bytes)
                           (:seon.render.transcript/entries run)))))
                runs)
          ["No run of mine is recorded yet; this is my first episode."])
         older (conj (print/render-elision-ai older)))))))

(defn render-history-ai
  "The prompt is this concern's AI projection, so emit no duplicate text."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.db/database-value]
                  [:or :string :seon.error/value]]}
  [_turns _database]
  "")

(defn render-history-html
  "Show all turn headers, newest first, without repeating evaluations."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.db/database-value]
                  :seon.render/hiccup]}
  [turns database]
  (let [rows (if (and (coll? turns) (every? map? turns)) turns [])
        ordered (sort-by (juxt #(get-in % [:seon.turn/opened-tx :db/id]) :seon.turn/id)
                         #(compare %2 %1) rows)]
    (into [:section {:class "seon-turn-history"}
           [:h2 (str "Turns (" (count rows) ")")]]
          (map #(turn-header database (:seon.turn/id %)) ordered))))

(defn- runtime-owner
  [unit]
  (let [runtime (or (:seon.render/value unit) unit)
        owner (:seon.runtime/agent runtime)
        agent-id (or (when owner
                       (:seon.agent/id
                        (db/pull (:seon.db/db unit) [:seon.agent/id]
                                 (if (map? owner) (:db/id owner) owner))))
                     (:seon.agent/id unit))]
    (or agent-id
        (error/diagnostic
         {:seon.error/kind :seon.db/not-found
          :seon.error/message "The runtime component's owner could not be resolved."
          :seon.error/diagnostic-layer :seon.render
          :seon.error/diagnostic-operation 'seon.render.transcript/render-runtime-ai
          :seon.error/diagnostic-member :seon.runtime/agent
          :seon.error/diagnostic-expected :seon.agent/id
          :seon.error/diagnostic-offending (or owner :seon.error/unknown)
          :seon.error/diagnostic-cause :seon.db/not-found
          :seon.error/diagnostic-evidence [:seon.runtime/agent]}))))

(defn render-runtime-ai
  "Read my runtime trigger and listens without observing turn-history churn."
  {:malli/schema [:=> [:cat :seon.render/unit] [:or :seon.render/source :seon.error/value]]}
  [unit]
  (let [agent-id (runtime-owner unit)]
    (if (:seon.error/kind agent-id) agent-id
      (str ";; My trigger and listens; (seon.db/pull '[{:seon.agent/runtime [:seon.runtime/turns]}] [:seon.agent/id "
           (pr-str agent-id) "]) reads my turns on demand.\n"
       (repl/source-text
        (list 'seon.db/pull
              (list 'quote
                    '[{:seon.agent/runtime
                       [{:seon.runtime/trigger
                         [:seon.message/id :seon.message/content
                          {:seon.message/from [:seon.agent/id]}]}
                        {:seon.runtime/listens
                         [:seon.listen/attribute :seon.listen/entity :seon.listen/value]}]}])
              [:seon.agent/id agent-id]))))))

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
                               "outside this cluster")
          (when-let [content (:seon.message/content trigger)]
            (str ": " (first (str/split-lines content)))))]))

(defn- runtime-turn-table [turns latest-trigger connection]
  [:details {:class "seon-turn-history" :data-preserve-attr "open"}
   [:summary (str "Turns (" (count turns) ")")]
   (if (seq turns)
     [:table {:class "seon-runtime-turns" :style {:width "100%" :text-align "left"}}
      [:thead [:tr (for [label ["Opened" "Duration" "Trigger" "Evaluations" "Reply"]]
                     [:th {:scope "col"} label])]]
      [:tbody
       (for [[index row] (map-indexed vector turns)]
         (let [opened (get-in row [:seon.turn/opened-tx :db/txInstant])
               closed (get-in row [:seon.turn/closed-tx :db/txInstant])
               trigger (or (:seon.turn/trigger row)
                           (when (zero? index) latest-trigger))
               reply (or (:seon.turn/reply row)
                         (when (and connection (:seon.turn/reply-blob row))
                           (blob/get connection (:seon.turn/reply-blob row))))]
           [:tr
            [:td (runtime-time opened)]
            [:td (if (:seon.turn/closed-tx row)
                   (runtime-duration opened closed) "open")]
            [:td (or (runtime-message-link trigger)
                     (if trigger "Trigger details unavailable" "No recorded trigger"))]
            [:td (str (count (:seon.cluster.eval/_run row))) ]
            [:td (cond
                   (seq reply) (first (str/split-lines reply))
                   (:seon.turn/reply-blob row) "Reply stored separately"
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
                         :seon.ai.attempt/usage-edn]}])

(defn- turn-rows [database agent-id]
  (let [rows (db/q '[:find ?opened (pull ?t pattern) :in $ ?agent-id pattern
                     :where [?a :seon.agent/id ?agent-id]
                            [?a :seon.agent/runtime ?runtime]
                            [?runtime :seon.runtime/turns ?t]
                            [?t :seon.turn/id _ ?opened]]
                   database agent-id debug-turn-selector)]
    (if (:seon.error/kind rows) rows
        (mapv (fn [ordinal [_ row]] (assoc row ::ordinal ordinal))
              (range) (sort-by (juxt first #(get-in % [1 :seon.turn/id])) rows)))))

(defn- turn-kind [database row]
  (cond
    (seq (:seon.turn/attempts row)) "Provider"
    (= :generate (:seon.turn.work/situation row)) "System"
    (= :call (:seon.turn.work/situation row))
    (let [opening (turn/opening-db database (:seon.turn/id row))
          agent-id (get-in row [:seon.turn/agent :seon.agent/id])
          overlay (get-in (db/pull opening
                                  '[{:seon.agent/settings [:seon.config.ai/no-provider]}]
                                  [:seon.agent/id agent-id])
                          [:seon.agent/settings])
          no-provider (get overlay :seon.config.ai/no-provider
                           (db/q '[:find ?v . :where [?config :seon.config/cluster _]
                                    [?config :seon.config.ai/no-provider ?v]] opening))]
      (if no-provider "Virtual" "No provider attempt"))
    (or (:seon.turn/reply row) (:seon.turn/reply-blob row)) "System"
    :else "No provider attempt"))

(defn- utf8-size [text]
  (alength (.getBytes ^String text "UTF-8")))

(defn- turn-reply [connection row]
  (or (:seon.turn/reply row)
      (when (and connection (:seon.turn/reply-blob row))
        (blob/get connection (:seon.turn/reply-blob row)))))

(defn- readable-shown [source]
  (try
    (with-open [reader (PushbackReader. (StringReader. source))]
      (let [value (edn/read {:eof ::eof} reader)]
        (when (= ::eof (edn/read {:eof ::eof} reader))
          {::value value})))
    (catch Exception _ nil)))

(defn- attempt-usage [attempt]
  (let [usage (some-> (:seon.ai.attempt/usage-edn attempt) readable-shown ::value)
        prompt (get usage "prompt_tokens")
        hit (or (get usage "prompt_cache_hit_tokens") (get-in usage ["prompt_tokens_details" "cached_tokens"]))
        miss (or (get usage "prompt_cache_miss_tokens")
                 (when (and (number? prompt) (number? hit)) (- prompt hit)))
        out (get usage "completion_tokens")]
    (into {} (filter (comp number? val)) {::prompt prompt ::hit hit ::miss miss ::out out})))

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

(defn- session-origin [database row saved]
  (cond
    (:seon.cluster.eval/error saved) "error"
    (= "System" (turn-kind database row)) (if (zero? (::ordinal row)) "opening" "re-read")
    :else "agent"))

(defn- session-header [request rows]
  (let [agent-id (:seon.agent/id request)
        agent-row (db/pull (:seon.db/db request)
                           '[{:seon.agent/namespace [:seon.ns/name]}
                             {:seon.agent/plan [:my.plan/objective]}]
                           [:seon.agent/id agent-id])
        active (last (remove :seon.turn/closed-tx rows))
        latest (or active (last rows))
        selected (or (:seon.turn/id request) (:seon.turn/id latest))
        raw? (::raw? request)]
    [:header {:class "seon-session-header"}
     [:div {:class "seon-session-identity"}
      [:h1 agent-id
       [:span {:class "seon-session-namespace"}
        (str (get-in agent-row [:seon.agent/namespace :seon.ns/name]))]]
      [:p {:class "seon-session-state"}
       [:span {:class (if active "seon-status-running" "seon-status-idle")}
        (if active "● running" "● idle")]
       (when latest
         (list " since " (runtime-time (get-in latest [(if active :seon.turn/opened-tx :seon.turn/closed-tx) :db/txInstant]))))
       " · " (:seon.cluster/name request)]]
     (when-let [objective (get-in agent-row [:seon.agent/plan :my.plan/objective])]
       [:p {:class "seon-session-objective"} objective])
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
               (str "evt.preventDefault(); history.replaceState(null, '', '"
                    (session-url agent-id selected true) "'); @get('"
                    (session-url agent-id selected true) "')"))}
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
  (let [rows (turn-rows (:seon.db/db request) (:seon.agent/id request))]
    (session-header request (if (vector? rows) rows []))))

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
      :else (str (utf8-size (or first-shown "")) " → " (utf8-size (or last-shown "")) " shown bytes"))))

(defn render-session-loading
  "Select only the latest turn initially; acquire its prompt on demand."
  {:malli/schema [:=> [:cat [:and :seon.render/unit
                             [:map [:seon.db/db :seon.db/db] [:seon.agent/id :seon.agent/id]]]]
                  :seon.render/hiccup]}
  [request]
  (let [agent-id (:seon.agent/id request)
        rows (turn-rows (:seon.db/db request) agent-id)
        selected (or (:seon.turn/id request) (when (vector? rows) (:seon.turn/id (last rows))))
        url (when selected (session-url agent-id selected (::raw? request)))]
    [:section {:id (session-id agent-id) :class "seon-session" :data-ignore-morph ""}
     (session-header request (if (vector? rows) rows []))
     [:h2 "Session"]
     (cond
       (:seon.error/kind rows) [:p {:class "seon-emission-error"} (:seon.error/message rows)]
       selected
       [:p {:role "status" :data-init (str "@get('" url "')")}
        "Loading the selected turn’s saved prompt…"]
       :else [:p "No turns recorded."])]))

(defn render-session
  "The selected turn's context, in acquisition order, with byte-exact colour.
  Metadata lives outside emission bytes; the raw toggle uses acquisition's
  complete prompt unchanged. Historical provider turns use their opening DB."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [database (:seon.db/db request)
        agent-id (:seon.agent/id request)
        rows (turn-rows database agent-id)
        selected (or (some #(when (= (:seon.turn/id request) (:seon.turn/id %)) %) rows)
                     (last rows))
        selected-id (:seon.turn/id selected)
        acquired (render/acquire-context! (assoc request :seon.turn/id selected-id))
        opening (:seon.db/db acquired)
        entries (:seon.render.history/entries acquired)
        prompt (:seon.cluster.prompt/text acquired)
        by-eid (into {} (map (juxt :db/id identity)) rows)
        saved (when (seq entries)
                (db/pull-many opening '[* {:seon.cluster.eval/ns [:seon.ns/name]}]
                              (mapv :seon.render.history/subject entries)))
        rereads (->> saved
                     (filter #(= "re-read" (session-origin database
                                         (get by-eid (get-in % [:seon.cluster.eval/run :db/id])) %)))
                     (group-by :seon.cluster.eval/source)
                     (filter #(< 1 (count (val %))))
                     (sort-by first))
        source-signals (into {} (map-indexed (fn [index [source _]]
                                               [source (str "reread" index)]) rereads))
        turn-bytes (reduce (fn [sizes [segment evaluation-row]]
                             (update sizes (get-in evaluation-row [:seon.cluster.eval/run :db/id])
                                     (fnil + 0) (utf8-size segment)))
                           {} (map vector (:seon.render.history/segments acquired) saved))
        raw? (::raw? request)]
    [:section {:id (session-id agent-id) :class "seon-session" :data-ignore-morph ""
               :data-signals (str "{" (str/join "," (map #(str % ":false") (vals source-signals))) "}")
               :data-session-loaded selected-id}
     [:div {:class "seon-session-sticky"}
     (session-header request rows)
     [:header {:class "seon-session-heading"}
      [:div [:h2 (str "Context at turn " (::ordinal selected))]
       [:p (str "turn " (::ordinal selected) " · " (str/lower-case (turn-kind database selected))
                " · ") (runtime-time (get-in selected [:seon.turn/opened-tx :db/txInstant]))]
       (when (seq (:seon.turn/attempts selected))
         (into [:ul {:class "seon-session-meta"}] (map attempt-html) (:seon.turn/attempts selected)))]]
     [:nav {:class "seon-session-selection" :aria-label "Select turn"}
      (for [row rows]
        [:a {:href (session-url agent-id (:seon.turn/id row) raw?)
             :data-turn-id (:seon.turn/id row)
             :data-turn-kind (turn-kind database row)
             :aria-current (if (= selected-id (:seon.turn/id row)) "true" "false")
             (keyword "data-on:click")
             (str "evt.preventDefault(); @get('" (session-url agent-id (:seon.turn/id row) raw?) "')")}
         (str (::ordinal row))])]
     (when prompt
       [:p {:class "seon-session-meta"}
        (str (format "%,d" (utf8-size prompt)) " bytes · ≈" (format "%,d" (tokens/estimate prompt))
             " tokens · " (count entries) " emissions · oldest → newest")])]
     (if (:seon.error/kind acquired)
       [:p {:class "seon-emission-error"} (:seon.error/message acquired)]
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
           [:pre {:class "seon-session-raw" :data-prompt-bytes (utf8-size prompt)} [:code prompt]]
           (into [:div {:class "seon-session-emissions"}]
                 (map-indexed
                  (fn [position [entry evaluation-row]]
                    (let [row (get by-eid (get-in evaluation-row [:seon.cluster.eval/run :db/id]))
                          origin (session-origin database row evaluation-row)
                          emission (repl/entity-emission evaluation-row)
                          previous (when (pos? position) (nth saved (dec position)))
                          boundary? (not= (:seon.cluster.eval/run evaluation-row)
                                          (:seon.cluster.eval/run previous))]
                      [:section (cond-> {:class "seon-session-entry"}
                                  (and (= "re-read" origin)
                                       (get source-signals (:seon.cluster.eval/source evaluation-row)))
                                  (assoc :data-show (str "$" (get source-signals (:seon.cluster.eval/source evaluation-row)))))
                       (when boundary?
                         [:header {:class "seon-session-boundary"}
                          (str "Turn " (::ordinal row) " · " (turn-kind database row) " · ")
                          (runtime-time (get-in row [:seon.turn/opened-tx :db/txInstant]))
                          (str " · " (get turn-bytes (:db/id row) 0) " bytes added")
                          (when (seq (:seon.turn/attempts row))
                            (into [:ul] (map attempt-html) (:seon.turn/attempts row)))])
                       [:div {:class "seon-session-line"}
                        [:aside {:class (str "seon-session-gutter seon-origin-" origin)}
                         [:span (str (::ordinal row))] [:span (str "● " origin)]]
                        [:pre {:data-emission-bytes (+ (if (pos? position) 2 0)
                                                       (utf8-size (:seon.render.history/bytes entry)))}
                         (when (pos? position) [:span "\n\n"])
                         (if (= (:seon.render.history/bytes entry) (repl/text emission))
                           (repl/render-emission-html emission)
                           [:code (:seon.render.history/bytes entry)])]]]))
                  (map vector entries saved))))
         [:span {:class "seon-session-end" :data-init "el.scrollIntoView({block:'end'})"}]]])]))

(defn- ledger-evaluations [request]
  (let [database (:seon.db/db request)
        saved (evaluation/of-agent database (:seon.agent/id request)
               '[:seon.cluster.eval/source :seon.cluster.eval/comment
                 :seon.eval/shown :seon.eval/renderer :seon.cluster.eval/error
                 :seon.cluster.eval/output :seon.error/kind
                 :seon.cluster.eval/read-basis-transaction
                 :seon.cluster.eval/interrupted-at :seon.cluster.eval/triage-edn
                 :seon.eval/duration-ms :seon.sci.eval/ending-ns
                 :seon.print/length :seon.print/level
                 {:seon.cluster.eval/ns [:seon.ns/name]}
                 {:seon.cluster.eval/read-evidence [*]}])
        acquired (when (and (vector? saved) (seq saved))
                   (render/acquire-context! (dissoc request :seon.turn/id)))
        bytes (zipmap (map :seon.render.history/subject (:seon.render.history/entries acquired))
                      (map utf8-size (:seon.render.history/segments acquired)))]
    (cond (:seon.error/kind saved) saved
          (:seon.error/kind acquired) acquired
          :else
      (group-by #(get-in % [:seon.cluster.eval/run :db/id])
        (map
          (fn [row]
            (let [emission (repl/entity-emission (assoc row :seon.db/db database))]
              (assoc row ::emission emission
                     ::outcome (cond (:seon.cluster.eval/interrupted-at row) "interrupted"
                                     (:seon.cluster.eval/error row) "error"
                                     (:seon.eval/shown row) "value" :else "out")
                     ::contributed-bytes (get bytes [:seon.cluster.eval/id (:seon.cluster.eval/id row)] 0)))) saved)))))

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

(defn- emission-label
  "Human block labels over saved renderer identities and the read form's declared attributes."
  [saved]
  (let [form (::value (readable-shown (:seon.cluster.eval/source saved)))
        parts (set (filter #(or (keyword? %) (symbol? %)) (tree-seq coll? seq form)))]
    (or (get {'seon.bootstrap/render-help-ai "help"
              'seon.plan/render-plan-ai "plan"
              'seon.cluster.message/render-inbox-ai "inbox"
              'seon.agent/render-settings-ai "settings"
              'seon.render.transcript/render-runtime-ai "runtime"
              'seon.cluster.agent/render-identity-ai "identity"}
             (:seon.eval/renderer saved))
        (cond
          (parts :seon.agent/plan) "plan"
          (parts :seon.agent/runtime) "runtime"
          (parts :seon.message/_inbox) "inbox"
          (parts :my.note/_agent) "notes"
          (or (parts :seon.agent/settings) (parts 'seon.agent/effective-settings)) "settings"
          (parts :seon.agent/id) "identity"
          :else (first (str/split-lines (:seon.cluster.eval/source saved)))))))

(defn- reply-intent [reply]
  (when-let [line (some #(let [line (str/trim %)]
                          (when (str/starts-with? line ";")
                            (str/trim (apply str (drop-while (fn [c] (= c \;)) line)))))
                       (str/split-lines (or reply "")))]
    (if (> (count line) 90) (str (subs line 0 90) "…") line)))

(defn- results-summary [counts]
    (if (empty? counts) "no forms"
      (str/join " · " (keep #(when-let [n (get counts %)]
                              (str n " " % (when (and (> n 1) (not= % "out")) "s")))
                            ["value" "error" "interrupted" "out"]))))

(defn- ledger-effects
  "Derive completed steps, sent messages and installed definitions at this turn's transactions."
  [database agent-id row]
  (let [opened (db/q '[:find ?tx . :in $ ?id :where [_ :seon.turn/id ?id ?tx]] database (:seon.turn/id row))
        closed (get-in row [:seon.turn/closed-tx :db/id])]
    (if-not (and opened closed) {::details "Turn is still open."}
      (let [steps (db/q '[:find ?id :in $ ?agent ?from ?to
                          :where [?a :seon.agent/id ?agent] [?tx :seon.db/user ?a]
                                 [?s :my.plan.item/completed-tx ?tx]
                                 [(<= ?from ?tx)] [(<= ?tx ?to)] [?s :my.plan.item/id ?id]]
                        database agent-id opened closed)
            messages (db/q '[:find ?target :in $ ?agent ?from ?to
                             :where [?a :seon.agent/id ?agent] [?m :seon.message/from ?a ?tx]
                                    [(<= ?from ?tx)] [(<= ?tx ?to)] [?m :seon.message/to ?recipient]
                                    [?recipient :seon.agent/id ?target]] database agent-id opened closed)
            definitions (db/q '[:find ?symbol :in $ ?from ?to
                                :where (or [?f :seon.fn/sym ?symbol ?tx] [?f :seon.test/sym ?symbol ?tx])
                                       [(<= ?from ?tx)] [(<= ?tx ?to)]] database opened closed)
            effects (concat (map #(str "plan step " (first %) " completed") steps)
                            (map #(str "message sent to " (first %)) messages)
                            (map #(str "definition installed during this turn: " (first %)) definitions))]
        {::details (if (seq effects) (str/join " · " effects) "—")
         ::summary (str/join " · " (cond-> []
                                    (seq steps) (conj "step completed")
                                    (seq messages) (conj "message sent")
                                    (seq definitions) (conj "definition installed")))}))))

(defn- emission-byte-count [emissions]
  (reduce + 0 (map ::contributed-bytes emissions)))

(defn- ledger-rows [database rows evaluations]
  (if (:seon.error/kind rows) rows
    (mapv (fn [row]
            (let [attempts (mapv #(assoc % ::usage (attempt-usage %))
                                 (sort-by :seon.ai.attempt/ordinal (:seon.turn/attempts row)))
                  own (get evaluations (:db/id row) [])]
              (assoc row ::kind (turn-kind database row)
                     :seon.turn/attempts attempts ::attempt (last attempts)
                     ::bytes (emission-byte-count own)
                     ::outcomes (frequencies (map ::outcome own))))) rows)))

(defn- ledger-turn-body [request rows evaluations row]
  (let [database (:seon.db/db request)
        ordinal (::ordinal row)
        provider? (= "Provider" (::kind row))
        authored? (not= "System" (::kind row))
        own (get evaluations (:db/id row) [])
        generated (if authored?
                    (->> rows (take ordinal) reverse
                         (take-while #(= "System" (::kind %))) reverse
                         (mapcat #(get evaluations (:db/id %) []))) own)
        added-bytes (emission-byte-count generated)
        reply (or (turn-reply (:seon.db/connection request) row) "")
        turn-id (:seon.turn/id row)
        capture (when provider?
                  (db/q '[:find ?text . :in $ ?id :where [?t :seon.turn/id ?id]
                          [?c :seon.context.capture/run ?t] [?c :seon.context.capture/prompt ?text]] database turn-id))
        attempt (::attempt row)
        usage (::usage attempt)
        calibration (when (:seon.ai/model attempt)
                      ((requiring-resolve 'seon.cluster.prompt/model-calibration) database (:seon.ai/model attempt)))
        opening (filter #(= (:db/id (first rows)) (get-in % [:seon.cluster.eval/run :db/id])) generated)
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
            [:summary (emission-label saved)] (ledger-emissions [saved])])])
      (if (seq generated)
        (list
         (for [matches groups]
           (let [source (:seon.cluster.eval/source (first matches))
                 prior (->> rows (take ordinal)
                            (mapcat #(get evaluations (:db/id %) []))
                            (take-while #(not= (:seon.cluster.eval/id %)
                                              (:seon.cluster.eval/id (first matches))))
                            (filter #(= source (:seon.cluster.eval/source %))) last)]
             [:details {:class "seon-ledger-generated"}
              [:summary [:code (emission-label (first matches))]
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
               (when capture (str ": " (format "%,d" (utf8-size capture)) " bytes · rebuilt ≈"
                                  (format "%,d" (tokens/estimate capture calibration)) " tokens"))
               (when-let [billed (::prompt usage)] (str " · billed " (format "%,d" billed))))]
         [:div {:id (ledger-context-id turn-id)}]])]
     (when authored?
       (list
        [:section {:class "seon-ledger-reply" :data-author "agent"}
         (ledger-heading "AGENT REPLIED" "agent" (str (format "%,d" (utf8-size reply)) " bytes"))
         [:pre {:data-reply-bytes (utf8-size reply) :data-reply-turn turn-id} (colour-source reply)]]
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
          (str "Effects: " (::details (ledger-effects database (:seon.agent/id request) row)))]]))]))

(defn render-ledger-turn
  "Load one card from saved reply and evaluations without executing forms."
  {:malli/schema [:=> [:cat :seon.cluster.prompt/request] :seon.render/hiccup]}
  [request]
  (let [rows (turn-rows (:seon.db/db request) (:seon.agent/id request))]
    (let [evaluations (ledger-evaluations request)]
      (if (:seon.error/kind evaluations)
        [:div {:id (ledger-body-id (:seon.turn/id request))} [:p (:seon.error/message evaluations)]]
        (let [rows (ledger-rows (:seon.db/db request) rows evaluations)
              row (some #(when (= (:seon.turn/id request) (:seon.turn/id %)) %) rows)]
          (ledger-turn-body request rows evaluations row))))))

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
              (cond (= :seon.sci.reader/fabricated-response (some-> (:seon.error/kind saved) keyword))
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
                (filter #(= :seon.sci.reader/fabricated-response (some-> (:seon.error/kind %) keyword)) evaluations))))

(defn- empty-reply-problem
  "Detect settled replies that produced no evaluation evidence."
  [rows evaluations]
  (let [evaluated (set (map #(get-in % [:seon.cluster.eval/run :db/id]) evaluations))]
    (finding :empty-replies "Replies with zero evaluations"
             (for [row rows
                   :when (and (:seon.turn/closed-tx row)
                              (seq (:seon.turn/attempts row))
                              (or (some? (:seon.turn/reply row)) (:seon.turn/reply-blob row))
                              (not (evaluated (:db/id row))))]
               {::turn row ::detail "A reply was saved, but no evaluations followed."}))))

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
                    (filter #(let [row (get by-eid (get-in % [:seon.cluster.eval/run :db/id]))]
                               (and (pos? (::ordinal row)) (= "System" (turn-kind database row)))))
                    (group-by :seon.cluster.eval/source)
                    (filter #(< 1 (count (val %))))
                    (sort-by first))]
    (assoc (finding :churn "Repeated system reads"
                    (mapcat (fn [[_ matches]] (map #(evaluation-match by-eid %) matches)) groups))
           ::groups
           (mapv (fn [[_ matches]]
                   {::detail (str (emission-label (first matches)) " · ×" (count matches)
                                  " · " (reread-summary matches))
                    ::bytes (reduce + (map ::contributed-bytes matches))
                    ::matches (mapv #(evaluation-match by-eid %) matches)}) groups))))

(defn- directory-problem
  "Detect directory results omitting public function facts at their read basis."
  [database by-eid evaluations]
  (let [directories
        (keep (fn [saved]
                (let [form (some-> (:seon.cluster.eval/source saved) readable-shown ::value)
                      argument (when (seq? form) (second form))
                      ns-name (if (and (seq? argument) (= 'quote (first argument))) (second argument) argument)]
                  (when (and (seq? form) (#{'dir 'clojure.core/dir} (first form)) (symbol? ns-name)
                             (not (:seon.cluster.eval/error saved)))
                    [saved ns-name]))) evaluations)
        checks
        (mapv (fn [[saved ns-name]]
                (let [shown (some-> (:seon.eval/shown saved) readable-shown ::value)
                      basis (:seon.cluster.eval/read-basis-transaction saved)
                      expected (when basis
                                 (db/q '[:find ?sym ?private :in $ ?name
                                         :where [?ns :seon.ns/name ?name]
                                                [?f :seon.fn/ns ?ns] [?f :seon.fn/sym ?sym]
                                                [(get-else $ ?f :seon.fn/private? false) ?private]]
                                       (db/as-of database basis) ns-name))
                      observed (when (map? shown) (get shown :functions))]
                  (if (and (coll? observed) basis (not (:seon.error/kind expected)))
                    (let [syms (set (map #(str (:sym %)) observed))
                          missing (sort (keep (fn [[sym private?]] (when (and (not private?) (not (syms sym))) sym)) expected))]
                      (when (seq missing)
                        (assoc (evaluation-match by-eid saved) ::detail
                               (str ns-name " omitted " (str/join ", " missing)))))
                    (assoc (evaluation-match by-eid saved) ::unavailable true
                           ::detail "Directory shown text or read basis unavailable.")))) directories)]
    (assoc (finding :directory "Incomplete directory results" (remove ::unavailable (remove nil? checks)))
           ::unknown (count (filter ::unavailable checks)))))

(defn- fault-problems
  "Detect delivered core faults and turns whose trigger points at a fault fact."
  [database agent-id rows]
  (let [messages (db/q '[:find ?mid ?error :in $ ?agent-id
                         :where [?a :seon.agent/id ?agent-id] [?m :seon.message/to ?a]
                                [?m :seon.message/about ?f] [?f :seon.error/id ?error]
                                [?m :seon.message/id ?mid]] database agent-id)]
    (if (:seon.error/kind messages)
      [(assoc (finding :faults "Fault delivery" []) ::unknown 1)
       (assoc (finding :fault-turns "Turns opened by faults" []) ::unknown 1)]
      (let [fault-message-ids (set (map first messages))
            triggered (filter #(fault-message-ids (get-in % [:seon.turn/trigger :seon.message/id])) rows)]
        [(finding :faults "Fault notifications delivered"
                  (for [[mid _] messages]
                    {::message mid ::detail "Delivered core fault"
                     ::turn (first (filter #(= mid (get-in % [:seon.turn/trigger :seon.message/id])) rows))}))
         (finding :fault-turns "Turns opened by faults"
                  (map #(hash-map ::turn % ::detail "The trigger message references a core fault.") triggered))]))))

(defn- prefix-problem
  "Detect cache prefix replacement beyond growth and the 128-token tolerance."
  [rows]
  (let [attempts (vec (for [row rows a (sort-by :seon.ai.attempt/ordinal (:seon.turn/attempts row))]
                        {::turn row ::attempt a ::usage (::usage a)}))
        pairs (partition 2 1 attempts)
        measured (filter #(every? number? [(get-in (first %) [::usage ::prompt])
                                           (get-in (second %) [::usage ::prompt])
                                           (get-in (second %) [::usage ::miss])]) pairs)
        changed (keep (fn [[previous current]]
                        (let [resent (- (get-in current [::usage ::miss])
                                        (- (get-in current [::usage ::prompt]) (get-in previous [::usage ::prompt])))]
                          (when (> resent 128)
                            {::turn (::turn current) ::detail (str "Prefix changed, " (format "%,d" resent) " tokens re-sent")}))) measured)]
    (assoc (finding :prefix "Prefix changed" changed)
           ::stable (- (count measured) (count changed)) ::measured (count measured)
           ::unknown (+ (- (count pairs) (count measured)) (if (empty? pairs) 1 0)))))


(defn- session-budget
  "Detect exhausted turn/step budgets and unavailable billing or rate evidence."
  [database agent-id rows]
  (let [attempts (vec (for [row rows a (:seon.turn/attempts row)]
                        {::turn row ::attempt a ::usage (::usage a)}))
        totals (reduce #(merge-with + %1 (::usage %2)) {} attempts)
        models (into {} (for [model (distinct (keep #(get-in % [::attempt :seon.ai/model]) attempts))]
                          [model (db/pull database
                                         [:seon.ai.model/input-usd-per-mtok
                                          :seon.ai.model/cached-input-usd-per-mtok
                                          :seon.ai.model/output-usd-per-mtok]
                                         [:seon.ai.model/id model])]))
        costs (mapv (fn [{a ::attempt u ::usage}]
                      (let [rates (get models (:seon.ai/model a))
                            input (:seon.ai.model/input-usd-per-mtok rates)
                            cached (:seon.ai.model/cached-input-usd-per-mtok rates)
                            output (:seon.ai.model/output-usd-per-mtok rates)]
                        (when (every? number? [input cached output (::miss u) (::hit u) (::out u)])
                          (/ (+ (* input (::miss u)) (* cached (::hit u)) (* output (::out u))) 1000000.0)))) attempts)
        plan ((requiring-resolve 'seon.plan/plan) {:seon.db/db database :seon.agent/id agent-id})
        steps (:my.plan/steps plan)
        per-step (frequencies
                  (for [row rows :when (seq (:seon.turn/attempts row))]
                    (let [basis (turn/opening-db database (:seon.turn/id row))]
                      (when-not (:seon.error/kind basis)
                        (get-in (db/pull basis
                                         '[{:seon.agent/plan [{:my.plan/current-step [:my.plan.item/id]}]}]
                                         [:seon.agent/id agent-id])
                                [:seon.agent/plan :my.plan/current-step :my.plan.item/id])))))]
    {::used (turn/episode-runs database agent-id)
     ::bound (#'turn/max-episode-runs database agent-id)
     ::completed (count (filter :my.plan.item/completed-tx steps))
     ::steps (count steps) ::plan-unavailable (boolean (:seon.error/kind plan))
     ::per-step per-step ::totals totals ::attempts (count attempts)
     ::missing-usage (count (remove #(every? number? (map (::usage %) [::prompt ::hit ::miss ::out])) attempts))
     ::cost (when (every? number? costs) (reduce + 0 costs))
     ::missing-rates (count (filter
                             (fn [{a ::attempt}]
                               (not (every? number?
                                      (map #(get-in models [(:seon.ai/model a) %])
                                           [:seon.ai.model/input-usd-per-mtok
                                            :seon.ai.model/cached-input-usd-per-mtok
                                            :seon.ai.model/output-usd-per-mtok])))) attempts))}))

(defn- session-problems [request rows evaluations]
  (let [database (:seon.db/db request)
        by-eid (into {} (map (juxt :db/id identity)) rows)
        saved (mapcat #(get evaluations (:db/id %) []) rows)]
    {::budget (session-budget database (:seon.agent/id request) rows)
     ::rules (into [(fabricated-problem by-eid saved)
                    (error-problem by-eid saved)
                    (churn-problem database by-eid saved)
                    (repeated-problem by-eid saved)
                    (empty-reply-problem rows saved)
                    (directory-problem database by-eid saved)
                    (prefix-problem rows)]
                   (fault-problems database (:seon.agent/id request) rows))}))

(defn- problem-links [agent-id matches]
  [:span {:class "seon-problem-links"}
   (for [row (sort-by ::ordinal (distinct (keep ::turn matches)))
         :let [href (ledger-url agent-id (:seon.turn/id row) {})]]
     [:a {:href href
          (keyword "data-on:click")
          (str "evt.preventDefault(); history.replaceState(null, '', '" href "'); @get('"
               (ledger-url agent-id (:seon.turn/id row) {:ledger "true"}) "')")}
      (str "turn " (::ordinal row))])])

(defn- budget-html [budget]
  [:div {:class "seon-session-budget"}
   [:p (str "Budget · " (::used budget) "/" (or (::bound budget) "unavailable") " turns used · "
            (if (::plan-unavailable budget) "plan unavailable"
              (str (::completed budget) "/" (::steps budget) " steps complete")))]
   [:p (str "Reported tokens · " (str/join " · "
              (for [[k label] [[::prompt "in"] [::hit "hit"] [::miss "miss"] [::out "out"]]]
                (str (format "%,d" (get (::totals budget) k 0)) " " label)))
            " · " (cond (number? (::cost budget)) (format "$%.5f at rates on file" (double (::cost budget)))
                         (pos? (::missing-rates budget)) "no rate on file"
                         :else "cost unavailable: incomplete usage")
            (when (pos? (::missing-usage budget)) (str " · usage unavailable on " (::missing-usage budget) " attempts")))]
   [:details [:summary "Provider turns per plan step"]
    (if (seq (::per-step budget))
      (for [[step n] (sort-by (comp str key) (::per-step budget))]
        [:p (str (or step "No step selected at opening") " · " n)])
      [:p "No provider turns recorded."])]] )

(defn- problem-summary [request problems]
  (let [by-code (into {} (map (juxt ::code identity)) (::rules problems))
        budget (::budget problems)]
    [:div {:class "seon-problem-summary"}
     [:p (str (::used budget) "/" (or (::bound budget) "unavailable") " turns used · "
              (::completed budget) "/" (::steps budget) " steps complete")]
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
         (str (::label rule) " · " (::count rule)
              (when (pos? (get rule ::unknown 0)) (str " · " (::unknown rule) " checks unavailable")))]
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
             (str "● Prefix stable on " (::stable rule) "/" (::measured rule) " attempts")
             (str "● " (::label rule) " · 0"))])])]))

(defn- ledger-strip
  "Navigate every stored turn; area shows added bytes and colour shows recorded outcome."
  [request rows evaluations selected problems]
  (let [amounts (into {} (map (fn [row] [(:seon.turn/id row)
                                        (::bytes row)]) rows))
        largest (reduce max 1 (vals amounts))
        marks (reduce (fn [result rule]
                        (reduce (fn [result match]
                                  (if-let [id (:seon.turn/id (::turn match))]
                                    (update result id (fnil conj #{}) (::label rule)) result))
                                result (::matches rule))) {} (::rules problems))]
    [:nav {:class "seon-ledger-strip" :aria-label "Select a turn" :data-author "seon"}
     [:p "Turns · filled: provider · hollow: generated or virtual · width: context added · red: errors · amber: no result/open · dot: problem"]
     [:div {:class "seon-ledger-strip-cells"}
      (for [row rows
            :let [turn-id (:seon.turn/id row)
                  own (get evaluations (:db/id row) [])
                  provider? (seq (:seon.turn/attempts row))
                  errors (get (::outcomes row) "error" 0)
                  tone (cond (pos? errors) "error"
                             (and provider? (or (empty? own) (nil? (:seon.turn/closed-tx row)))) "warning"
                             provider? "success" :else "neutral")
                  kind (::kind row)
                  amount (get amounts turn-id)
                  attempt (::attempt row)
                  usage (::usage attempt)
                  href (ledger-url (:seon.agent/id request) turn-id {})]]
        [:a {:href href :data-strip-turn turn-id :data-added-bytes amount
             :aria-current (when (= selected turn-id) "step")
             :class (str "seon-ledger-turn-cell seon-ledger-turn-cell-" tone
                         (when provider? " seon-ledger-turn-cell-provider"))
             :style (str "width:" (max 24 (long (* 96 (/ amount largest)))) "px")
             :title (str "Turn " (::ordinal row) " · " kind " · " turn-id "\n"
                         (when-let [opened (get-in row [:seon.turn/opened-tx :db/txInstant])]
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
             (str "evt.preventDefault(); history.replaceState(null, '', '" href "'); @get('"
                  (ledger-url (:seon.agent/id request) turn-id {:ledger "true"}) "')")}
         (str (::ordinal row) (when (seq (get marks turn-id)) "·"))])]]))

(defn- turn-story [request evaluations row]
  (let [own (get evaluations (:db/id row) [])
        authored? (not= "System" (::kind row))]
    (str/join " · "
      (remove str/blank?
        (if authored?
          [(reply-intent (turn-reply (:seon.db/connection request) row))
           (results-summary (::outcomes row))
           (::summary (ledger-effects (:seon.db/db request) (:seon.agent/id request) row))
           (when (:seon.turn/closed-tx row)
             (case (:seon.turn/disposition row) :wait "done" :completed "completed" nil))]
          [(cond (zero? (::ordinal row)) (str "opening · " (count own) " emissions")
                 (empty? own) "no new emissions"
                 :else (str "re-read " (str/join ", " (distinct (map emission-label own)))))])))))

(defn render-ledger
  "Turn ledger: generated context, raw model reply, evaluated results.
  Only selected and last three cards have bodies on initial render."
  {:malli/schema [:=> [:cat [:and :seon.render/unit
                             [:map [:seon.db/db :seon.db/db] [:seon.agent/id :seon.agent/id]]]]
                  :seon.render/hiccup]}
  [request]
  (let [database (:seon.db/db request)
        rows (turn-rows database (:seon.agent/id request))
        selected (or (:seon.turn/id request) (:seon.turn/id (last rows)))
        evaluations (ledger-evaluations request)
        rows (ledger-rows (:seon.db/db request) rows evaluations)
        expanded (conj (set (map :seon.turn/id (take-last 3 rows))) selected)
        problems (when-not (or (:seon.error/kind rows) (:seon.error/kind evaluations))
                   (session-problems request rows evaluations))]
    [:section {:id (session-id (:seon.agent/id request)) :class "seon-session seon-ledger" :data-author "seon"}
     [:div {:class "seon-session-sticky"} (session-header request rows)
      [:h2 "Turn ledger"]
      (when problems (problem-summary request problems))
      (when-not (:seon.error/kind evaluations) (ledger-strip request rows evaluations selected problems))]
     (when problems (problems-html request problems))
     (cond
       (:seon.error/kind evaluations) [:p (:seon.error/message evaluations)]
       (seq rows)
       (for [row rows
             :let [turn-id (:seon.turn/id row)
                   attempt (::attempt row)
                   usage (::usage attempt)]]
         [:details {:class "seon-ledger-turn" :open (contains? expanded turn-id)
                    :data-turn-id turn-id :data-turn-kind (::kind row)
                    :id (block/surface-id (keyword "turn" turn-id))
                    :data-init (when (= selected turn-id)
                                 "el.style.scrollMarginTop = (el.closest('.seon-ledger').querySelector('.seon-session-sticky').offsetHeight + 8) + 'px'; el.scrollIntoView({block:'start'})")
                    (keyword "data-on:toggle")
                    (str "if(el.open && !el.querySelector('[data-ledger-loaded]')) @get('"
                         (ledger-url (:seon.agent/id request) turn-id {:card "true"}) "')")}
          [:summary
           (str "Turn " (::ordinal row) " · " (str/lower-case (::kind row)) " · ")
           (runtime-time (get-in row [:seon.turn/opened-tx :db/txInstant]))
           (when attempt
             (str " · " (:seon.ai/model attempt)
                  " · " (format "%,d" (get usage ::prompt 0)) " in / "
                  (format "%,d" (get usage ::out 0)) " out"))
           (str " · " (runtime-duration (get-in row [:seon.turn/opened-tx :db/txInstant])
                                       (get-in row [:seon.turn/closed-tx :db/txInstant])))
           [:span {:class "seon-ledger-story" :data-turn-story turn-id}
            (str " · " (turn-story request evaluations row))]]
          (if (contains? expanded turn-id)
            (ledger-turn-body request rows evaluations row)
            [:div {:id (ledger-body-id turn-id)}])])
       :else [:p "No turns recorded."])]))

(defn render-runtime-html
  "Show transaction times, message triggers, listens, and newest-first turns."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (let [agent-id (runtime-owner unit)
        database (:seon.db/db unit)
        row (if (:seon.error/kind agent-id) agent-id
                (db/pull database
                     [{:seon.agent/runtime
                       [{:seon.runtime/turns runtime-turn-selector}
                        {:seon.runtime/trigger runtime-message-selector}
                        {:seon.runtime/listens [:seon.listen/attribute]}]}]
                     [:seon.agent/id agent-id]))]
    (cond
      (:seon.error/kind row) row
      (nil? (:seon.agent/runtime row))
      (error/diagnostic
       {:seon.error/kind :seon.db/not-found
        :seon.error/message "The agent's runtime component is unavailable."
        :seon.error/diagnostic-layer :seon.render
        :seon.error/diagnostic-operation 'seon.render.transcript/render-runtime-html
        :seon.error/diagnostic-member :seon.agent/runtime
        :seon.error/diagnostic-expected :seon.runtime/entity
        :seon.error/diagnostic-offending agent-id
        :seon.error/diagnostic-cause :seon.db/not-found
        :seon.error/diagnostic-evidence [:seon.agent/runtime]})
      :else
        (let [runtime (:seon.agent/runtime row)
              turns (sort-by (juxt #(some-> (get-in % [:seon.turn/opened-tx :db/txInstant])
                                            (.getTime)) :seon.turn/id)
                             #(compare %2 %1) (:seon.runtime/turns runtime))
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
             [:div {:class "seon-runtime-trigger"}
              [:p "Woke on " (or (runtime-message-link trigger) "a recorded fact")]
              (message/render-html (assoc trigger :seon.db/db database))])
           [:div {:class "seon-runtime-listens" :style {:display "flex" :gap "0.5rem" :flex-wrap "wrap"}}
            [:span "Listening:"]
            (if (seq (:seon.runtime/listens runtime))
              (for [attribute (sort (distinct (keep :seon.listen/attribute (:seon.runtime/listens runtime))))]
                [:code {:style {:border "1px solid currentColor" :border-radius "1rem" :padding "0.1rem 0.5rem"}}
                 (str attribute)])
              [:span "No listened attributes."])]
           (runtime-turn-table turns trigger (:seon.db/connection unit))]))))
