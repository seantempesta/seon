(ns my.note
  "Durable current notes for one agent.

  Notes are small current facts, not a knowledge system. Updating an identity
  replaces its content, and forgetting retracts the current entity while
  Datahike retains its history."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.render.value :as render.value]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schemas/my.note.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Current values and rendering
;;; ---------------------------------------------------------------------------

(def ^:private note-selector
  '[:my.note/id
    :my.note/content
    {:my.note/agent [:db/id]}
    {:my.note/about [:db/id]}])

(defn- error-value?
  [value]
  (and (map? value) (keyword? (:seon.error/kind value))))

(defn- note-value
  [value]
  (cond
    (and (map? value) (map? (:seon.render/value value)))
    (render.value/transacted (:seon.render/value value))

    (map? value)
    (render.value/transacted value)

    :else value))

(defn- note-row
  [row]
  (note-value row))

(defn- note-line
  [note]
  (str (pr-str (:my.note/id note))
       (when-let [about (:my.note/about note)]
         (str " about " (pr-str about)))
       ": " (:my.note/content note)))

(defn render-note-ai
  "Render one current note as text."
  {:malli/schema [:=> [:cat :my.note/note] :seon.render/ai]}
  [note]
  (str "Note " (note-line (note-value note))))

(defn render-notes-ai
  "Render the bounded current note collection as text."
  {:malli/schema [:=> [:cat :my.note/notes] :seon.render/ai]}
  [notes]
  (if (seq notes)
    (str "Current notes (" (count notes) "):\n"
         (str/join "\n" (map #(str "- " (note-line %)) notes)))
    "No current notes."))

(defn- note-html
  [note]
  (cond-> [:article {:class "seon-family-entry my-note-entry"}
           [:h3 (str "Note " (pr-str (:my.note/id note)))]
           [:p (:my.note/content note)]]
    (:my.note/about note)
    (conj [:p {:class "my-note-about"}
           (str "About " (pr-str (:my.note/about note)))])))

(defn render-note-html
  "Render one current note as Hiccup."
  {:malli/schema [:=> [:cat :my.note/note] :seon.render/hiccup]}
  [note]
  (note-html (note-value note)))

(defn render-notes-html
  "Render the bounded current note collection as Hiccup."
  {:malli/schema [:=> [:cat :my.note/notes] :seon.render/hiccup]}
  [notes]
  (into [:section {:class "seon-family-entry my-notes"}
         [:h3 (str "Current notes (" (count notes) ")")]]
        (map note-html)
        notes))

(defn render-note-form
  "Render the current database read for one note."
  {:malli/schema [:=> [:cat :my.note/note] :seon.render/form]}
  [note]
  (list 'seon.db/pull
        (list 'quote '[*])
        [:my.note/id (:my.note/id (note-value note))]))

(defn render-notes-form
  "Render the current database read for this agent's notes."
  {:malli/schema [:=> [:cat :my.note/notes] :seon.render/form]}
  [_notes]
  (list 'my.note/notes))

;;; ---------------------------------------------------------------------------
;;; Current-fact transitions
;;; ---------------------------------------------------------------------------

(defn- refuse!
  [marker subject message data]
  (throw (ex-info message
                  {marker subject
                   :seon.error/kind marker
                   :seon.error/message message
                   :seon.error/data data})))

(defn- agent-eid
  [database agent-id]
  (first
   (db/q '[:find [?agent ...]
           :in $ ?agent-id
           :where [?agent :seon.cluster.agent/id ?agent-id]]
         database agent-id)))

(defn- note-eid
  [database note-id]
  (first
   (db/q '[:find [?note ...]
           :in $ ?note-id
           :where [?note :my.note/id ?note-id]]
         database note-id)))

(defn- add-note-call
  [database request]
  (let [id (:my.note/id request)
        agent-id (:seon.cluster.agent/id request)
        agent-entity (agent-eid database agent-id)
        existing (note-eid database id)
        existing-agent
        (when existing
          (db/q '[:find ?agent .
                  :in $ ?note
                  :where [?note :my.note/agent ?agent]]
                database existing))
        about? (contains? request :my.note/about)
        about (:my.note/about request)]
    (when-not agent-entity
      (refuse! ::agent-not-found
               agent-id
               (str "There is no agent named " (pr-str agent-id) ".")
               {:seon.cluster.agent/id agent-id}))
    (when (and existing (not= agent-entity existing-agent))
      (refuse! ::identity-owned-by-another-agent
               id
               (str "Note " (pr-str id) " belongs to another agent.")
               {:my.note/id id}))
    (when (and about? (nil? (db/entity database about)))
      (refuse! ::about-not-found
               about
               (str "The note subject " (pr-str about) " does not exist.")
               {:my.note/about about}))
    [(cond-> {:my.note/id id
              :my.note/agent agent-entity
              :my.note/content (:my.note/content request)}
       about? (assoc :my.note/about about))]))

(defn- forget-note-call
  [database request]
  (let [id (:my.note/id request)
        agent-id (:seon.cluster.agent/id request)
        agent-entity (agent-eid database agent-id)
        note (note-eid database id)
        owner
        (when note
          (db/q '[:find ?agent .
                  :in $ ?note
                  :where [?note :my.note/agent ?agent]]
                database note))]
    (when-not note
      (refuse! ::not-found
               id
               (str "There is no current note named " (pr-str id) ".")
               {:my.note/id id}))
    (when (not= agent-entity owner)
      (refuse! ::not-owned
               id
               (str "Note " (pr-str id) " belongs to another agent.")
               {:my.note/id id
                :seon.cluster.agent/id agent-id}))
    [[:db.fn/retractEntity note]]))

(defn- transact-note!
  [connection agent-id tx-data]
  (db/transact!
   connection
   {:tx-data tx-data
    :tx-meta {:seon.db/user [:seon.cluster.agent/id agent-id]}}))

(defn- add-note!
  [id content about? about connection agent-id]
  (let [request
        (cond-> {:my.note/id id
                 :my.note/content content
                 :seon.cluster.agent/id agent-id}
          about? (assoc :my.note/about about))
        result
        (transact-note!
         connection agent-id
         [[:db.fn/call #'add-note-call request]])]
    (if (error-value? result)
      result
      (let [note (db/pull (:db-after result) note-selector
                          [:my.note/id id])]
        (if (error-value? note) note (note-row note))))))

(defn add!
  "Add or update one note owned by the calling agent."
  {:malli/schema
   [:function
    [:=> [:cat :my.note/id :my.note/content
          :seon.db/connection :seon.cluster.agent/id]
     [:or :my.note/note :seon.error/value]]
    [:=> [:cat :my.note/id :my.note/content :my.note/about
          :seon.db/connection :seon.cluster.agent/id]
     [:or :my.note/note :seon.error/value]]]}
  ([id content connection agent-id]
   (add-note! id content false nil connection agent-id))
  ([id content about connection agent-id]
   (add-note! id content true about connection agent-id)))

(defn forget!
  "Forget one current note while retaining its Datahike history."
  {:malli/schema
   [:=> [:cat :my.note/id :seon.db/connection :seon.cluster.agent/id]
    [:or :my.note/id :seon.error/value]]}
  [id connection agent-id]
  (let [result
        (transact-note!
         connection agent-id
         [[:db.fn/call #'forget-note-call
           {:my.note/id id :seon.cluster.agent/id agent-id}]])]
    (if (error-value? result) result id)))

(defn notes
  "List this agent's bounded current notes in identity order."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
    [:or :my.note/notes :seon.error/value]]}
  [database agent-id]
  (let [rows
        (db/q '[:find ?id ?note
                :in $ ?agent-id
                :where
                [?agent :seon.cluster.agent/id ?agent-id]
                [?note :my.note/agent ?agent]
                [?note :my.note/id ?id]]
              database agent-id)]
    (if (error-value? rows)
      rows
      (->> rows
           (sort-by first)
           (mapv (fn [[_ note]]
                   (note-row (db/pull database note-selector note))))))))
