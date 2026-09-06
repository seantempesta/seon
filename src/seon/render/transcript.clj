(ns seon.render.transcript
  "One agent's messages and eval receipts as a bounded REPL transcript.

  The renderer is the schema-declared agent-session projection. Messages are
  reverse connections while form input and receipts are reached through the
  agent's runs. Raw facts never acquire a detail level; every full, summary,
  and elided decision is derived for this call."
  (:require [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.ai.tokens :as tokens]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.context :as context]
            [seon.cluster.run :as run]
            [seon.error :as error]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.agent :as agent]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.render.walk :as walk]
            [seon.sci.reader :as reader])
  (:import [java.io PushbackReader StringReader]))

(def ^:private recent-entry-count
  ;; The measured transcript prototype's stable full-detail tail. The exact
  ;; threshold is quarry evidence, not stored state; a later model evaluation
  ;; may change this one projection policy without rewriting history.
  6)

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
   :seon.cluster.eval/read-basis-transaction
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size
   :seon.cluster.eval/error
   :seon.cluster.eval/triage-edn
   :seon.cluster.eval/interrupted-at
   :seon.cluster.eval/output
   :seon.problems/id
   :seon.error/kind
   {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
   {:seon.cluster.eval/run
    [:db/id :seon.cluster.run/id :seon.cluster.run/opened-at]}])

(def ^:private form-selector
  [:db/id
   :seon.cluster.run.form/id
   :seon.cluster.run.form/ordinal
   :seon.cluster.run.form/source
   {:seon.cluster.run.form/ns [:db/id :seon.ns/name]}
   {:seon.cluster.run.form/run
    [:db/id
     :seon.cluster.run/id
     :seon.cluster.run/opened-at
     {:seon.cluster.run/agent
      [:db/id
       :seon.cluster.agent/id
       {:seon.cluster.agent/namespace [:db/id :seon.ns/name]}]}]}])

(def ^:private undisposed-run-selector
  [:db/id
   :seon.cluster.run/id
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at
   :seon.cluster.run/plan-digest
   :seon.cluster.run/undisposed-at])

(def ^:private reasoning-attempt-selector
  [:db/id
   :seon.ai.attempt/id
   :seon.ai.attempt/at
   :seon.ai.attempt/reasoning
   :seon.ai.attempt/reasoning-blob
   :seon.ai.attempt/reasoning-size
   {:seon.ai.attempt/run [:db/id :seon.cluster.run/id]}])

(def ^:private active-runs-rules
  '[[(active-run ?run ?agent ?bootstrap-run-id ?pinned?)
     [?run :seon.cluster.run/agent ?agent]
     [?run :seon.cluster.run/id ?run-id]
     (not-join [?run]
               [_ :seon.cluster.run/supersedes ?run])
     [(= ?run-id ?bootstrap-run-id) ?pinned?]]])

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
          :in $ % ?agent-id ?bootstrap-run-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          (active-run ?run ?agent ?bootstrap-run-id ?pinned?)
          [?receipt :seon.cluster.eval/run ?run]]
        db active-runs-rules agent-id (bootstrap/run-id agent-id))
   0))

(defn- comment-only-source?
  [source]
  (let [events (reader/read {:seon.sci.reader/text source
                             :seon.config.eval.result/max-source (count source)
                             :seon.sci.reader/defer-auto-resolve? true})]
    (and (vector? events) (empty? events))))

(defn- comment-form-rows
  [db agent-id]
  (->> (db/q {:query
              '[:find ?form ?at ?id ?source
                :in $ % ?agent-id ?bootstrap-run-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                (active-run ?run ?agent ?bootstrap-run-id ?pinned?)
                [?run :seon.cluster.run/opened-at ?at]
                [?form :seon.cluster.run.form/run ?run]
                [?form :seon.cluster.run.form/id ?id]
                [?form :seon.cluster.run.form/source ?source]
                [?form :seon.cluster.run.form/ordinal ?ordinal]
                (not-join [?run ?ordinal]
                          [?receipt :seon.cluster.eval/run ?run]
                          [?receipt :seon.cluster.eval/ordinal ?ordinal])]
              :args [db active-runs-rules agent-id
                     (bootstrap/run-id agent-id)]
              :order-by '[?at :desc ?id :desc]})
       (filter (fn [[_ _ _ source]] (comment-only-source? source)))))

(defn- history-count
  [db agent-id]
  (if (and db agent-id)
    (+ (message-count db agent-id)
       (receipt-count db agent-id)
       (count (comment-form-rows db agent-id))
       (or (db/q '[:find (count ?run) .
                   :in $ ?agent-id
                   :where
                   [?agent :seon.cluster.agent/id ?agent-id]
                   [?run :seon.cluster.run/agent ?agent]
                   [?run :seon.cluster.run/undisposed-at _]]
                 db agent-id)
           0))
    0))

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

(defn- recent-comment-rows
  [db agent-id limit]
  (take limit (comment-form-rows db agent-id)))

(defn- recent-undisposed-run-rows
  [db agent-id limit]
  (db/q {:query
         '[:find ?run ?at ?id
           :in $ ?agent-id
           :where
           [?agent :seon.cluster.agent/id ?agent-id]
           [?run :seon.cluster.run/agent ?agent]
           [?run :seon.cluster.run/undisposed-at ?at]
           [?run :seon.cluster.run/id ?id]]
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
              (map (fn [[form at id _source]] [:input form at id])
                   (recent-comment-rows db agent-id limit))
              (map #(into [:run] %)
                   (recent-undisposed-run-rows db agent-id limit)))
             (sort-by (fn [[kind _ at id]]
                        [(.getTime ^java.util.Date at)
                         (case kind :message 0 :input 1 :eval 2 :run 3)
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
  "Select a bounded prefix of forms and evaluations for one run.

   The extra row is an omission sentinel; it avoids materializing an
   unbounded run merely to calculate an elision count."
  [db run-id limit]
  (let [bounded-limit (inc (max 0 (int limit)))]
    {:input
     (mapv first
           (db/q {:query
                  '[:find ?form ?ordinal
                    :in $ ?run-id
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?form :seon.cluster.run.form/run ?run]
                    [?form :seon.cluster.run.form/ordinal ?ordinal]
                    (not-join [?run ?ordinal]
                              [?receipt :seon.cluster.eval/run ?run]
                              [?receipt :seon.cluster.eval/ordinal ?ordinal])]
                  :args [db run-id]
                  :order-by '[?ordinal :asc]
                  :limit bounded-limit}))
     :eval
     (mapv first
           (db/q {:query
                  '[:find ?receipt ?ordinal
                    :in $ ?run-id
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?receipt :seon.cluster.eval/run ?run]
                    [?receipt :seon.cluster.eval/ordinal ?ordinal]]
                  :args [db run-id]
                  :order-by '[?ordinal :asc]
                  :limit bounded-limit}))}))

(defn- selected-run-count
  [db run-id limit]
  (let [{:keys [input eval]} (selected-run-entity-ids db run-id limit)]
    (+ (count input) (count eval))))

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
      ::about (second (get identities about-eid))
      ::about-ref? (some? about-eid)
      ::reason (:my.message/reason message)}
     (when (= (bootstrap/task-message-id agent-id)
              (:seon.cluster.message/id message))
       {::bootstrap-trigger? true})
     {::custody
      (context/message-custody database run-id agent-id (:db/id message))}
     (get orders (:db/id message)))))

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
     ::run-opened-at (get-in receipt [:seon.cluster.eval/run
                                      :seon.cluster.run/opened-at])
     ::source (get sources receipt-eid)
     ::namespace (get-in receipt [:seon.cluster.eval/ns :seon.ns/name])
     ::read-basis (:seon.cluster.eval/read-basis-transaction receipt)
     ::result (:seon.cluster.eval/result-edn receipt)
     ::result-blob (:seon.cluster.eval/result-blob receipt)
     ::result-size (:seon.cluster.eval/result-size receipt)
     ::capped? (capped-result? receipt)
     ::error (:seon.cluster.eval/error receipt)
     ::triage-edn (:seon.cluster.eval/triage-edn receipt)
     ::error-kind (:seon.error/kind receipt)
     ::problem-id (:seon.problems/id receipt)
     ::interrupted-at (:seon.cluster.eval/interrupted-at receipt)
     ::output (:seon.cluster.eval/output receipt)}))

(defn- input-entry
  [form]
  {::kind :input
   ::entity form
   ::id (:seon.cluster.run.form/id form)
   ::at (get-in form [:seon.cluster.run.form/run
                      :seon.cluster.run/opened-at])
   ::ordinal (:seon.cluster.run.form/ordinal form)
   ::run-id (get-in form [:seon.cluster.run.form/run
                          :seon.cluster.run/id])
   ::run-opened-at (get-in form [:seon.cluster.run.form/run
                                 :seon.cluster.run/opened-at])
   ::source (:seon.cluster.run.form/source form)
   ::namespace
   (or (get-in form [:seon.cluster.run.form/ns :seon.ns/name])
       (get-in form [:seon.cluster.run.form/run
                     :seon.cluster.run/agent
                     :seon.cluster.agent/namespace
                     :seon.ns/name])
       'user)})

(defn- undisposed-run-entry
  [run]
  {::kind :run
   ::entity run
   ::id (:seon.cluster.run/id run)
   ::at (:seon.cluster.run/undisposed-at run)
   ::run-id (:seon.cluster.run/id run)
   ::run-opened-at (:seon.cluster.run/opened-at run)})

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
        :input [1 at 2
                (.getTime ^java.util.Date (::run-opened-at entry))
                (::ordinal entry)
                (::id entry)]
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
   (let [ids (if selected-run-id
               (selected-run-entity-ids db selected-run-id limit)
               (candidate-entity-ids db agent-id limit))
        messages (pulled-many db message-selector (:message ids))
        receipts (pulled-many db receipt-selector (:eval ids))
        inputs (pulled-many db form-selector (:input ids))
        undisposed-runs
        (pulled-many db undisposed-run-selector (:run ids))
        identities (about-identities db messages)
        identity-attrs (identity-attributes db)
        message-orders (message-order-facts db (:message ids))
        sources (form-sources db (:eval ids))]
    (->> (concat (map (partial message-entry db run-id agent-id
                               identities message-orders)
                      messages)
                 (map input-entry inputs)
                 (map (partial receipt-entry sources) receipts)
                 (map undisposed-run-entry undisposed-runs))
         (map (fn [entry]
                (assoc entry
                       ::root (entry-root identity-attrs entry)
                       ::pinned?
                       (or (::bootstrap-trigger? entry)
                           (and (contains? #{:eval :input} (::kind entry))
                                (= (bootstrap/run-id agent-id)
                                   (::run-id entry)))))))
         (sort-by entry-order)
         vec))))

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

(defn- floor-text
  [unit value]
  ;; Transcript values are immutable history entries. Give the shared value
  ;; floor an explicit, content-stable block identity when this internal
  ;; projection is not itself running as a retained render call.
  (value/render-ai
   (cond-> (assoc unit :seon.render/value value)
     (nil? (:seon.render.call/id unit))
     (assoc :seon.render.call/id [::history-value value]))))

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

(defn- prompted-source
  [entry]
  (str (or (::namespace entry) 'user) "=> " (::source entry)))

(defn- input-text
  [_unit entry _detail]
  (prompted-source entry))

(defn- execution-error-face
  [error]
  (-> {:clojure.error/phase :execution
       :clojure.error/cause error}
      main/ex-str
      str/trim-newline))

(defn- receipt-text
  [unit entry _detail]
  (let [bounded-result-text
        (some->> (::result entry) (bounded-result unit))
        error (some->> (::error entry) (bounded-scalar unit))
        entity
        (cond-> (::entity entry)
          (::result entry)
          (assoc :seon.cluster.eval/result-edn
                 bounded-result-text)
          error
          (assoc :seon.cluster.eval/error
                 (if (::triage-edn entry)
                   error
                   (execution-error-face error)))
          (::triage-edn entry)
          (assoc :seon.cluster.eval/triage-edn (::triage-edn entry))
          (::output entry)
          (assoc :seon.cluster.eval/output
                 (bounded-scalar unit (::output entry))))
        result (rendered-family unit entity 2)
        shown-result
        (if (and (::read-basis entry)
                 (seq bounded-result-text)
                 (str/ends-with? result bounded-result-text))
          (str (subs result 0 (- (count result) (count bounded-result-text)))
               "t=" (::read-basis entry) " " bounded-result-text)
          result)]
    (str (prompted-source entry)
         (when (seq shown-result) (str "\n" shown-result)))))

(defn- undisposed-run-text
  [_unit entry _detail]
  (let [form (list 'db/pull 'db
                   [:seon.cluster.run/undisposed-at]
                   [:seon.cluster.run/id (::id entry)])
        result (run/render-ai (::entity entry))]
    (str "system=> " (pr-str form)
         (when (seq result) (str "\n" result)))))

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
              :input (input-text unit entry detail)
              :eval (receipt-text unit entry detail)
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
                    [?run :seon.cluster.run/id ?run-id]
                    [?attempt :seon.ai.attempt/run ?run]
                    (or [?attempt :seon.ai.attempt/reasoning]
                        [?attempt :seon.ai.attempt/reasoning-blob])]
                  db selected-run-id)
            (db/q '[:find [?attempt ...]
                   :in $ ?agent-id
                   :where
                   [?agent :seon.cluster.agent/id ?agent-id]
                   [?run :seon.cluster.run/agent ?agent]
                   [?attempt :seon.ai.attempt/run ?run]
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
                        ::run-id (get-in attempt [:seon.ai.attempt/run
                                                  :seon.cluster.run/id])
                        ::reasoning reasoning}))))
           (sort-by entry-order)
           vec)
      [])))

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
     (conj (marker-text elided (seq pinned)))
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
  (max (long (or (tokens/estimate (ai-output pinned entries elided)) 0))
       (long (or (tokens/estimate
                  (hiccup/->string (html-output pinned entries elided)))
                 0))))

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
        candidate-limit (long (get-in unit [:seon.sci.admit/caps
                                            :seon.config.eval.result/max-nodes]))
        selected-run-id (::selected-run-id unit)
        total (if selected-run-id
                (selected-run-count db selected-run-id candidate-limit)
                (history-count db agent-id))
        entries (if (and db agent-id)
                  (history db (:seon.cluster.run/id unit)
                           agent-id candidate-limit selected-run-id)
                  [])
        pinned (into []
                     (comp (filter ::pinned?)
                           (map #(projected-entry unit % :full)))
                     entries)
        candidates (into [] (remove ::pinned?) entries)
        projected (mapv #(projected-entry unit % :full) candidates)
        elided (max 0 (- total (count pinned) (count candidates)))
        measured (output-tokens pinned projected elided)]
    {::pinned pinned
     ::entries projected
     ::elided elided
     ::minimum-token-budget measured
     ::token-budget measured}))

(defn minimum-token-budget
  "Measured size of the complete query-bounded transcript."
  {:malli/schema [:=> [:cat :seon.render/unit] [:int {:min 0}]]}
  [unit]
  (::minimum-token-budget (projection (assoc unit ::token-budget 0))))

(defn render-ai
  "Render one agent's bounded messages and faithful REPL session."
  {:malli/schema [:=> [:cat :seon.render/unit] :string]}
  [unit]
  (let [{::keys [pinned entries elided]} (projection unit)]
    (ai-output pinned entries elided)))

(declare transcript-unit)

(defn- selected-run-identities
  [unit]
  (let [db (:seon.db/db unit)
        agent (:seon.cluster.run/agent unit)
        agent-ref (if (map? agent) (:db/id agent) agent)
        supplied-agent-id (when (map? agent) (:seon.cluster.agent/id agent))
        queried (when (and (nil? supplied-agent-id)
                           db (integer? agent-ref))
                  (db/q '[:find ?agent-id .
                          :in $ ?agent
                          :where
                          [?agent :seon.cluster.agent/id ?agent-id]]
                        db agent-ref))]
    {::selected-run-id (:seon.cluster.run/id unit)
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
    :seon.error/diagnostic-member :seon.cluster.run/run
    :seon.error/diagnostic-expected
    [:seon.db/db :seon.cluster.run/id :seon.cluster.agent/id]
    :seon.error/diagnostic-offending
    (select-keys unit [:seon.cluster.run/id :seon.cluster.run/agent])
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
      (let [unit (assoc (transcript-unit
                         (assoc unit :seon.cluster.agent/id agent-id))
                        ::selected-run-id run-id)
            status (run/render-ai unit)
            transcript (render-ai unit)]
        (str status (when (seq transcript) (str "\n" transcript))))
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
  (list 'my.message/inbox))

(defn- receipt-printed-value
  [unit entry]
  (let [text (receipt-text unit entry :full)
        prompt (prompted-source entry)]
    (when (< (count prompt) (count text))
      (subs text (inc (count prompt))))))

(defn- entry-bytes
  [namespace-name form printed-value]
  (str (or namespace-name 'user) "=> "
       (if (string? form) form (pr-str form))
       (when (seq printed-value) (str "\n" printed-value))))

(defn- entry-basis
  [db entry]
  (reduce (fn [latest datom]
            (max latest (long (or (:tx datom) 0))))
          0
          (db/datoms db :eavt (get-in entry [::entity :db/id]))))

(defn history-entries
  "Return one agent's durable transcript as immutable REPL entries.

   Stored form source remains byte-faithful. Synthesized forms are honest
   reads of durable message and undisposed-run facts; values come from settled
   facts and declared renderers, never from executing the displayed form."
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
        candidate-count (long (get-in unit [:seon.sci.admit/caps
                                             :seon.config.eval.result/max-nodes]))
        candidates
        (history db (:seon.cluster.run/id unit) agent-id
                 candidate-count)
        entries
        (mapv
         (fn [entry]
           (let [form (case (::kind entry)
                        :message (message-form (::entity entry))
                        :run (list 'db/pull 'db
                                   [:seon.cluster.run/undisposed-at]
                                   [:seon.cluster.run/id (::id entry)])
                        (::source entry))
                 printed-value
                 (case (::kind entry)
                   :message (rendered-family unit (::entity entry) 1)
                   :input nil
                   :eval (receipt-printed-value unit entry)
                   :run (run/render-ai (::entity entry)))]
             {:seon.render.history/call-id
              [:seon.render.transcript/entry (::kind entry) (::id entry)]
              :seon.render.history/basis-transaction
              (or (::read-basis entry) (entry-basis db entry))
              :seon.render.history/form form
              :seon.render.history/printed-value printed-value
              :seon.render.history/bytes
              (entry-bytes (or (::namespace entry) namespace-name)
                           form printed-value)}))
         candidates)]
    entries))

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
      (let [unit (assoc (transcript-unit
                         (assoc unit :seon.cluster.agent/id agent-id))
                        ::selected-run-id run-id)]
        [:section {:class "seon-run-transcript"}
         (run/render-html unit)
         (render-html unit)])
      :else (missing-selected-run unit identities))))

(defn- transcript-unit
  [unit]
  (assoc unit ::token-budget
         (tokens/estimate-of-characters
          (long (get-in unit [:seon.sci.admit/caps
                              :seon.config.eval.result/max-string])))))

(defn render-session-ai
  "Render the schema-declared agent session while status survives slice 1."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [status (agent/agent-ai unit)]
    (let [history (when (and (:seon.db/db unit)
                             (:seon.sci.admit/caps unit))
                    (render-ai (transcript-unit unit)))]
      (str status (when (seq history) (str "\n" history))))))

(defn render-session-html
  "Render the schema-declared HTML agent session with stable transcript ids."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [status (agent/agent-html unit)]
    (if (and (:seon.db/db unit) (:seon.sci.admit/caps unit))
      (conj status (render-html (transcript-unit unit)))
      status)))
