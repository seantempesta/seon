(ns seon.render.transcript
  "One agent's messages and eval receipts as a bounded REPL transcript.

  The renderer is the schema-declared agent-session projection. Messages are
  reverse connections while evaluations are reached through the
  agent's runs. Raw facts never acquire a detail level; every full, summary,
  and elided decision is derived for this call."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.context :as context]
            [seon.turn :as run]
            [seon.config :as config]
            [seon.error :as error]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.agent :as agent]
            [seon.render.block :as block]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.repl :as repl]
            [seon.sci.admit :as admit])
  (:import [java.io PushbackReader StringReader]))

(def ^:private message-selector
  [:db/id
   :seon.cluster.message/id
   :seon.cluster.message/ordinal
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
   :seon.cluster.eval/source
   :seon.cluster.eval/read-basis-transaction
   :seon.eval/value
   :seon.eval/missing
   :seon.eval/size
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
    [:db/id :seon.turn/id :seon.turn/opened-at
     {:seon.turn/agent
      [:db/id
       :seon.cluster.agent/id
       {:seon.cluster.agent/namespace [:db/id :seon.ns/name]}]}]}])

(def ^:private undisposed-run-selector
  [:db/id
   :seon.turn/id
   :seon.turn/opened-at
   :seon.turn/closed-at
   :seon.turn/plan-digest
   :seon.turn/undisposed-at])

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
     (not-join [?run]
               [_ :seon.turn/supersedes ?run])
     [(= ?run-id ?bootstrap-run-id) ?pinned?]]])

(defn- recent-message-rows
  [db agent-id limit]
  (db/q {:query
        '[:find ?message ?at ?ordinal ?tx
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          (or-join [?message ?agent]
                   [?message :seon.cluster.message/to ?agent]
                   [?message :seon.cluster.message/from ?agent])
          [?message :seon.cluster.message/at ?at ?tx]
          [?message :seon.cluster.message/id _]
          [(get-else $ ?message :seon.cluster.message/ordinal 0)
           ?ordinal]]
        :args [db agent-id]
        :order-by '[?at :desc ?tx :desc ?ordinal :desc ?message :desc]
        :limit limit}))

(defn- recent-receipt-rows
  [db agent-id limit]
  (db/q {:query
        '[:find ?receipt ?at ?id
          :in $ % ?agent-id ?bootstrap-run-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          (active-run ?run ?agent ?bootstrap-run-id false)
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/at ?at]
          [?receipt :seon.cluster.eval/id ?id]]
        :args [db active-runs-rules agent-id (bootstrap/run-id agent-id)]
        :order-by '[?at :desc ?id :desc]
        :limit limit}))

(defn- recent-undisposed-run-rows
  [db agent-id limit]
  (db/q {:query
         '[:find ?run ?at ?id
           :in $ ?agent-id
           :where
           [?agent :seon.cluster.agent/id ?agent-id]
           [?run :seon.turn/agent ?agent]
           [?run :seon.turn/undisposed-at ?at]
           [?run :seon.turn/id ?id]]
         :args [db agent-id]
         :order-by '[?at :desc ?id :desc]
         :limit limit}))

(defn- pinned-receipt-ids
  [db agent-id]
  (db/q '[:find [?receipt ...]
         :in $ % ?agent-id ?bootstrap-run-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         (active-run ?run ?agent ?bootstrap-run-id true)
         [?receipt :seon.cluster.eval/run ?run]]
       db active-runs-rules agent-id (bootstrap/run-id agent-id)))

(defn- bootstrap-task-message-eid
  [db agent-id]
  (db/q '[:find ?message .
          :in $ ?message-id
          :where
          [?message :seon.cluster.message/id ?message-id]]
        db (bootstrap/task-message-id agent-id)))

(defn- candidate-entity-ids
  [db agent-id limit]
  (let [recent
        (->> (concat
              (map (fn [[entity at ordinal tx]]
                     [:message entity at [tx ordinal entity]])
                   (recent-message-rows db agent-id limit))
              (map #(into [:eval] %)
                   (recent-receipt-rows db agent-id limit))
              (map #(into [:run] %)
                   (recent-undisposed-run-rows db agent-id limit)))
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
        (into [] (comp (keep #(get-in % [:seon.cluster.message/about :db/id]))
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
     (map (fn [[message tx ordinal]]
            [message {::transaction tx ::ordinal ordinal}]))
     (db/q '[:find ?message ?tx ?ordinal
            :in $ [?message ...]
            :where
            [?message :seon.cluster.message/at _ ?tx]
            [(get-else $ ?message :seon.cluster.message/ordinal 0)
             ?ordinal]]
          db message-ids))
    {}))

(defn- message-entry
  [database run-id agent-id identities orders message]
  (let [about-eid (get-in message [:seon.cluster.message/about :db/id])]
    (merge
     {::kind :message
      ::entity message
      ::id (:seon.cluster.message/id message)
      ::at (:seon.cluster.message/at message)
      ::content (:seon.cluster.message/content message)
      ::from (get-in message [:seon.cluster.message/from
                              :seon.cluster.agent/id])
      ::to (get-in message [:seon.cluster.message/to
                            :seon.cluster.agent/id])
      ::about (get identities about-eid)
      ::about-ref? (some? about-eid)
      ::reason (:my.message/reason message)}
     (when (= (bootstrap/task-message-id agent-id)
              (:seon.cluster.message/id message))
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
                               :seon.turn/opened-at]))
     ::ordinal ordinal
     ::run-id (get-in receipt [:seon.cluster.eval/run
                               :seon.turn/id])
     ::run-opened-at (get-in receipt [:seon.cluster.eval/run
                                      :seon.turn/opened-at])
     ;; ONE ENTITY PER (run, ordinal): the frozen source is this evaluation's
     ;; own attribute, not a twin form entity joined by ordinal.
     ::source (:seon.cluster.eval/source receipt)
     ::namespace
     (or (get-in receipt [:seon.cluster.eval/ns :seon.ns/name])
         (get-in receipt [:seon.cluster.eval/run
                          :seon.turn/agent
                          :seon.cluster.agent/namespace
                          :seon.ns/name])
         'user)
     ::read-basis (:seon.cluster.eval/read-basis-transaction receipt)
     ::result (:seon.eval/value receipt)
     ::missing (:seon.eval/missing receipt)
     ::size (:seon.eval/size receipt)
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

(defn- undisposed-run-entry
  [run]
  {::kind :run
   ::entity run
   ::id (:seon.turn/id run)
   ::at (:seon.turn/undisposed-at run)
   ::run-id (:seon.turn/id run)
   ::run-opened-at (:seon.turn/opened-at run)})

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
        undisposed-runs
        (pulled-many db undisposed-run-selector (:run ids))
        identities (about-identities db messages)
        identity-attrs (identity-attributes db)
        message-orders (message-order-facts db (:message ids))]
    (->> (concat (map (partial message-entry db run-id agent-id
                               identities message-orders)
                      messages)
                 (map receipt-entry receipts)
                 (map undisposed-run-entry undisposed-runs))
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
                 (assoc :seon.cluster.message/content
                        (bounded-scalar unit (::content entry))))
        sentence (rendered-family unit entity 1)
        extra (cond-> {}
                (::about entry)
                (assoc :seon.cluster.message/about (::about entry))
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
        (when (and (int? (:db/id entity)) (string? (::result entry)))
          (admit/result-handle (:db/id entity))))))

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
      (::missing entry) (assoc :seon.eval/missing (::missing entry))
      (int? (::size entry)) (assoc :seon.eval/size (::size entry))
      (and (::result entry) (nil? (::missing entry)))
      (assoc :seon.repl/value
             (::result entry))
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
  (or (run/render-ai (::entity entry)) ""))

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
        agent-id (:seon.cluster.agent/id unit)
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
                   [?agent :seon.cluster.agent/id ?agent-id]
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
        agent-id (:seon.cluster.agent/id unit)
        candidate-count (admit/required-cap
                         (:seon.sci.admit/caps unit)
                         :seon.config.eval.result/max-nodes)
        evaluated-sources (:seon.cluster.loop/evaluated-sources unit)]
    (if (some? evaluated-sources)
          (mapv
           (fn [{form :seon.cluster.loop/admitted-form
                 evaluation :seon.sci.eval/evaluation
                 ordinal :seon.cluster.eval/ordinal}]
             (receipt-entry
              (cond-> (assoc evaluation
                             :seon.cluster.eval/source
                             (:seon.cluster.eval/source form)
                             :seon.cluster.eval/id
                             (run/receipt-identity
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
        agent-id (:seon.cluster.agent/id unit)
        entries (if (or (some? (:seon.cluster.loop/evaluated-sources unit))
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
        supplied-agent-id (when (map? agent) (:seon.cluster.agent/id agent))
        queried (when (and (nil? supplied-agent-id)
                           db (integer? agent-ref))
                  (db/q '[:find ?agent-id .
                          :in $ ?agent
                          :where
                          [?agent :seon.cluster.agent/id ?agent-id]]
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
    [:seon.db/db :seon.turn/id :seon.cluster.agent/id]
    :seon.error/diagnostic-offending
    (select-keys unit [:seon.turn/id :seon.turn/agent])
    :seon.error/diagnostic-cause ::selected-run-unavailable
    :seon.error/diagnostic-evidence identities}))

(defn render-run-ai
  "Render a bounded run's stored forms and evaluation results."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :string :seon.error/value]]}
  [unit]
  (let [{run-id ::selected-run-id agent-id ::selected-agent-id
         identity-error ::selected-run-error
         :as identities}
        (selected-run-identities unit)]
    (cond
      identity-error identity-error
      (and (:seon.db/db unit) run-id agent-id)
      (let [unit (assoc (assoc unit :seon.cluster.agent/id agent-id)
                        ::selected-run-id run-id)]
        (render-ai unit))
      :else (missing-selected-run unit identities))))

(defn message-form
  "Return the ordinary message read form for one message entity."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/form]}
  [unit]
  (list 'my.message/read (:seon.cluster.message/id unit)))

(defn inbox-form
  "Return the ordinary inbox listing form for messages reached through `to`."
  {:malli/schema [:=> [:cat :seon.cluster.message/to] :seon.render/form]}
  [_recipient]
  ;; The empty request map names the call shape: `inbox` has a map arity
  ;; and a positional arity, so a bare call is ambiguous to call
  ;; preparation (it refused on the live page, 2026-09-08).
  (list 'my.message/inbox {}))

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
        agent-id (:seon.cluster.agent/id unit)
        namespace-name
        (db/q '[:find ?name .
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?agent :seon.cluster.agent/namespace ?namespace]
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

(defn render-run-html
  "Render a bounded run's stored forms and evaluation results as Hiccup."
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
      (let [unit (assoc (assoc unit :seon.cluster.agent/id agent-id)
                        ::selected-run-id run-id)]
        [:section {:class "seon-run-transcript"}
         (run/render-html unit)
         (render-html unit)])
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
   :seon.turn/opened-at
   :seon.turn/closed-at
   :seon.turn/error])

(defn agent-history
  "This agent's own submitted forms and their stored results, newest run first.

  The runs are bounded by the cluster's declared agent child count; the runs
  beyond it are an ordinary elision value carrying their count and a requery
  identity, so nothing is silently dropped."
  {:malli/schema [:=> [:cat :seon.render.transcript/history-request]
                  [:or :seon.render.transcript/history :seon.error/value]]}
  [{database :seon.db/db agent-id :seon.cluster.agent/id}]
  (let [effective (agent-config database)
        limit (long (:seon.config.render.agent/max-children effective))
        caps (config/result-caps effective)
        rows (db/q {:query '[:find ?run ?opened
                             :in $ ?agent-id
                             :where
                             [?agent :seon.cluster.agent/id ?agent-id]
                             [?run :seon.turn/agent ?agent]
                             [?run :seon.turn/id _]
                             [?run :seon.turn/opened-at ?opened]]
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
                                  :seon.cluster.agent/id agent-id
                                  :seon.sci.admit/caps caps
                                  ::selected-run-id
                                  (:seon.turn/id row)}))))))
                  newest)
            omitted (- total (count runs))]
        (cond-> {:seon.cluster.agent/id agent-id
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
                  [:seon.cluster.agent/id agent-id]}))))))

(defn- run-heading
  [run]
  (str "Run " (:seon.turn/id run)
       (when-let [opened (:seon.turn/opened-at run)]
         (str ", opened " (pr-str opened)))
       (if-let [closed (:seon.turn/closed-at run)]
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
                     (:seon.turn/error run)
                     (conj (str "It did not run: "
                                (:seon.turn/error run)))
                     :always
                     (into (map :seon.render.history/bytes)
                           (:seon.render.transcript/entries run)))))
                runs)
          ["No run of mine is recorded yet; this is my first episode."])
         older (conj (print/render-elision-ai older)))))))

(defn- runs-agent-id
  "The agent these runs belong to, read from the runs themselves."
  [database runs]
  (or (some :seon.cluster.agent/id
            (keep :seon.turn/agent runs))
      (when-let [eid (some #(get-in % [:seon.turn/agent :db/id]) runs)]
        (let [found (db/q '[:find ?id .
                            :in $ ?agent
                            :where [?agent :seon.cluster.agent/id ?id]]
                          database eid)]
          (when-not (:seon.error/kind found) found)))))

(defn render-history-ai
  "Render saved evaluations directly; history is never a generated read form."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.db/database-value]
                  [:or :string :seon.error/value]]}
  [runs database]
  (let [rows (if (and (sequential? runs) (every? map? runs)) (vec runs) [])
        agent-id (runs-agent-id database rows)]
    (if agent-id
      (format-history-ai (agent-history {:seon.db/db database
                                        :seon.cluster.agent/id agent-id}))
      "")))

(defn render-history-html
  "`:seon.render/html` — one transcript per run, newest first.

  BOTH PROJECTIONS COME FROM ONE DERIVATION. This renders exactly what
  [[agent-history]] returned, so the page and the agent's context cannot
  disagree; the per-run `render-run-html` path was measured at more than ten
  seconds for three runs, because each call re-counts the agent's whole
  history, and it is not on this path.

  A closed run's results are labeled historical, because a reader must not
  mistake a stored value for a value the agent just produced."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.db/database-value]
                  :seon.render/hiccup]}
  [runs database]
  (let [rows (if (and (sequential? runs) (every? map? runs)) (vec runs) [])
        agent-id (runs-agent-id database rows)
        derived (when agent-id
                  (agent-history {:seon.db/db database
                                  :seon.cluster.agent/id agent-id}))]
    (cond
      (nil? agent-id)
      [:section {:class "seon-family-entry seon-run-history"}
       [:h2 "History (0 runs)"]
       [:p {:class "seon-run-history-empty"}
        "No run of this agent is recorded yet."]]

      (:seon.error/kind derived)
      [:section {:class "seon-family-entry seon-run-history"}
       [:h2 "History"]
       [:p {:class "seon-run-history-unavailable"}
        (:seon.error/message derived)]]

      :else
      (let [shown (:seon.render.transcript/runs derived)
            older (:seon.render.transcript/older-runs derived)
            total (or (:seon.render.data/total older) (count shown))]
        (cond->
         (into [:section {:class "seon-family-entry seon-run-history"}
                [:h2 (str "History (" total " run"
                          (when (not= 1 total) "s") ")")]]
               (map
                (fn [run]
                  (let [closed? (some? (:seon.turn/closed-at run))
                        entries (:seon.render.transcript/entries run)]
                    (cond->
                     [:article {:class "seon-run-history-entry"}
                      [:p {:class "seon-kicker"}
                       (if closed?
                         "Historical run — its results are stored, not fresh"
                         "Open run")]
                      [:h3 [:code (:seon.turn/id run)]]
                      [:p {:class "seon-run-history-window"}
                       (run-heading run)]]
                      (:seon.turn/error run)
                      (conj [:p {:class "seon-run-history-error"}
                             (str "It did not run: "
                                  (:seon.turn/error run))])
                      (seq entries)
                      (conj [:pre {:class "seon-run-history-transcript"}
                             [:code
                              (str/join
                               "\n"
                               (map :seon.render.history/bytes entries))]])
                      (empty? entries)
                      (conj [:p {:class "seon-run-history-empty"}
                             "This run evaluated no form."]))))
                shown))
          older
          (conj [:p {:class "seon-run-history-elision"}
                 (print/render-elision-ai older)]))))))
