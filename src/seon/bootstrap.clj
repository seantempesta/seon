(ns seon.bootstrap
  "The live-fact generated bootstrap run shared by every new agent."
  (:require [clojure.edn :as edn]
            [seon.cluster.run :as run]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.schema :as schema]))

(defmacro help
  "Read the calling agent's live situation.

  The returned situation is the generated opening's control surface. Its
  schema members are the seeds: adding a derived member to that shape is how
  the opening grows. The value is pulled live from current facts; no member is
  copied onto the agent as stored presentation state."
  []
  (list 'seon.bootstrap/situation))

(defn situation
  "Derive one agent's live opening seeds from current database facts."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.cluster.agent/id]
    [:or :seon.cluster.agent/situation :seon.error/value]]}
  [database agent-id]
  (let [agent
        (db/pull database
                 '[:seon.cluster.agent/id
                   {:seon.cluster.agent/namespace
                    [:db/id :seon.ns/name
                     {:seon.ns/requires [:seon.ns/name]}]}
                   {:seon.cluster.agent/run
                    [:seon.cluster.run/id
                     {:seon.cluster.run/trigger
                      [:seon.cluster.message/id]}]}]
                 [:seon.cluster.agent/id agent-id])]
    (if-not (:seon.cluster.agent/id agent)
      {:seon.error/kind :seon.cluster.agent/no-such-agent
       :seon.error/message (str "No agent has id " (pr-str agent-id) ".")
       :seon.error/data {:seon.cluster.agent/id agent-id}}
      (let [namespace (:seon.cluster.agent/namespace agent)
            run (:seon.cluster.agent/run agent)
            turn-limit
            (db/q '[:find ?limit .
                   :where [_ :seon.config.run/max-episode-runs ?limit]]
                 database)
            turns-used
            ((requiring-resolve 'seon.cluster.work/episode-runs)
             database agent-id)
            unread
            (or (db/q '[:find (count ?message) .
                        :in $ ?agent-id
                        :where
                        [?agent :seon.cluster.agent/id ?agent-id]
                        [?message :seon.cluster.message/to ?agent]
                        (not-join [?message]
                          [?run :seon.cluster.run/trigger ?message])]
                      database agent-id)
                0)]
        (cond->
         {:seon.cluster.agent/id agent-id
          :seon.cluster.agent/namespace-ref
          [:seon.ns/name (:seon.ns/name namespace)]
          :seon.cluster.agent/unread-message-count (long unread)
          :seon.cluster.run/turns-remaining
          (long (max 0 (- (or turn-limit 0) turns-used)))
          :seon.cluster.agent/protocol-namespaces
          (->> (:seon.ns/requires namespace)
               (map :seon.ns/name)
               sort
               vec)}
          run
          (assoc :seon.cluster.agent/open-run-ref
                 [:seon.cluster.run/id
                  (:seon.cluster.run/id run)])
          (:seon.cluster.run/trigger run)
          (assoc :seon.cluster.run/trigger
                 [:seon.cluster.message/id
                  (get-in run [:seon.cluster.run/trigger
                               :seon.cluster.message/id])]))))))

(defmacro dir
  "List the public names in namespace-name through Clojure's REPL macro."
  [namespace-name]
  (list 'clojure.repl/dir namespace-name))

(defmacro doc
  "Print documentation for symbol through Clojure's REPL macro."
  [documented-symbol]
  (list 'clojure.repl/doc documented-symbol))

(defn run-id
  "The deterministic id of an agent's system-authored bootstrap run."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id]
                  :seon.cluster.run/id]}
  [agent-id]
  (str "bootstrap:" agent-id))

(defn task-message-id
  "The deterministic identity of one agent's real bootstrap task message."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id]
                  :seon.cluster.message/id]}
  [agent-id]
  (str "bootstrap-task:" agent-id))

(defn task-message
  "The small real assignment that the shipped bootstrap episode completes."
  {:malli/schema [:=> [:cat] :seon.cluster.message/content]}
  []
  (str "Define a durable contracted function named largest that returns the "
       "row with the greatest :example/amount, or {} for empty input. Call "
       "it once, query its stored :seon.fn/spec, then complete with a short "
       "reply naming what you built and its contract."))

(defn entry-source
  "Render one comment/form entry as ordinary reader source."
  {:malli/schema [:=> [:cat :seon.repl/entry]
                  :seon.cluster.run.form/source]}
  [{comment :seon.repl/comment form :seon.repl/form}]
  (str (when comment (str comment "\n")) (pr-str form)))

(defn- entries
  [rendered]
  (cond
    (and (map? rendered) (:seon.repl/form rendered)) [rendered]
    (and (vector? rendered) (every? :seon.repl/form rendered)) rendered
    (sequential? rendered) [{:seon.repl/form rendered}]
    :else []))

(defn- namespace-subject
  [lookup]
  (when (and (vector? lookup) (= :seon.ns/name (first lookup)))
    (second lookup)))

(defn- direct-candidates
  [request acquisition]
  (let [root (first (:seon.render.walk/order acquisition))]
    (into []
          (mapcat
           (fn [lookup]
             (let [member (get-in acquisition [:seon.render.walk/members lookup])
                   value (:seon.render/value member)
                   rendered
                   (render/render-call
                    (cond-> (assoc request
                                   :seon.render/value value
                                   :seon.render/output :seon.render/form
                                   :seon.render.call/id
                                   [:seon.render/form lookup])
                      (:seon.ns/name value)
                      (assoc :seon.render/namespace (:seon.ns/name value))))
                   rendered-entries (entries rendered)
                   subject (or (namespace-subject lookup) lookup)]
               (when (or (= lookup root)
                         (namespace-subject lookup)
                         (some seq (map (comp walk/form-symbols :seon.repl/form)
                                        rendered-entries)))
                 (map-indexed
                  (fn [index entry]
                    {:seon.repl/key [lookup index]
                     :seon.repl/subject subject
                     :seon.repl/previous-key
                     (when (pos? index) [lookup (dec index)])
                     :seon.repl/entry entry})
                  rendered-entries))))
          (:seon.render.walk/order acquisition)))))

(defn- listing-candidates
  [request acquisition]
  (into []
        (comp
         (filter :seon.render.walk/attribute)
         (mapcat
          (fn [unit]
            (let [rendered-entries (entries (:seon.render/output unit))]
              (keep-indexed
               (fn [index entry]
                 (when (seq (walk/form-symbols (:seon.repl/form entry)))
                   {:seon.repl/key [(:seon.render.walk/lookup unit)
                                    :listing index]
                    :seon.repl/subject (:seon.render.walk/lookup unit)
                    :seon.repl/entry entry}))
               rendered-entries)))))
        (walk/neighborhood
         (assoc request
                :seon.render.walk/root-acquisition acquisition
                :seon.render/output :seon.render/form))))

(defn pull-result
  "Pull and render the bounded candidate neighborhood for one opening."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :map]}
  [request]
  (let [acquisition (walk/root-acquisition request)
        identities (->> (vals (:seon.schema.projection/shape-rows
                               (schema/current-projection)))
                        (keep :seon.entity/id-attr)
                        set)]
    {:seon.repl/root-key
     [(:seon.render.walk/lookup request) 0]
     :seon.repl/candidates
     (into (direct-candidates request acquisition)
           (listing-candidates request acquisition))
     :seon.print/identity-attributes identities}))

(defn- root-candidate
  [request]
  (let [rendered
        (render/render-call
         (assoc request
                :seon.render/value
                (db/pull (:seon.db/db request) '[*]
                         (:seon.render.walk/lookup request))
                :seon.render/output :seon.render/form
                :seon.render.call/id
                [:seon.render/form (:seon.render.walk/lookup request)]))]
    {:seon.repl/key [(:seon.render.walk/lookup request) 0]
     :seon.repl/subject (:seon.render.walk/lookup request)
     :seon.repl/entry (first (entries rendered))}))

(defn next-entry
  "Derive the next generated entry from receipts already stored on the run."
  {:malli/schema [:=> [:cat :seon.render.walk/request :seon.cluster.run/id]
                  [:maybe :seon.repl/entry]]}
  [request run-id]
  (let [rows
        (db/q {:query
               '[:find ?ordinal ?source ?result
                 :in $ ?run-id
                 :where
                 [?run :seon.cluster.run/id ?run-id]
                 [?form :seon.cluster.run.form/run ?run]
                 [?form :seon.cluster.run.form/ordinal ?ordinal]
                 [?form :seon.cluster.run.form/source ?source]
                 [?receipt :seon.cluster.eval/run ?run]
                 [?receipt :seon.cluster.eval/ordinal ?ordinal]
                 [?receipt :seon.cluster.eval/result-edn ?result]]
               :args [(:seon.db/db request) run-id]
               :order-by '[?ordinal :asc]})
        pull (pull-result request)
        candidates
        (let [root (root-candidate request)]
          (into [root]
                (remove #(= (:seon.repl/key root) (:seon.repl/key %)))
                (:seon.repl/candidates pull)))
        candidate-by-source
        (reduce (fn [by-source candidate]
                  (let [source (entry-source (:seon.repl/entry candidate))]
                    ;; Identical structural reads can explain several pulled
                    ;; members. Candidate order is already stable; retain its
                    ;; first subject so receipts have one deterministic key.
                    (if (contains? by-source source)
                      by-source
                      (assoc by-source source candidate))))
                {}
                candidates)
        settled
        (mapv (fn [[_ source result]]
                (let [candidate (get candidate-by-source source)]
                  (when-not candidate
                    (throw
                     (ex-info "A stored generated form is outside the pull."
                              {:seon.error/kind ::prefix-drift
                               :seon.cluster.run/id run-id
                               :seon.cluster.run.form/source source})))
                  {:seon.repl/key (:seon.repl/key candidate)
                   :seon.sci.admit/print-node (edn/read-string result)}))
              rows)
        episode
        (walk/ordered-episode (assoc pull
                                     :seon.repl/candidates candidates
                                     :seon.repl/settled settled))
        index (count rows)
        prior-sources (mapv second rows)
        expected-sources (mapv entry-source (take index episode))]
    (when-not (= prior-sources expected-sources)
      (throw
       (ex-info
        (str "The generated opening prefix differs from its receipts: expected "
             (pr-str expected-sources) " actual " (pr-str prior-sources))
                {:seon.error/kind ::prefix-drift
                 :seon.cluster.run/id run-id
                 :seon.bootstrap/expected expected-sources
                 :seon.bootstrap/actual prior-sources})))
    (nth episode index nil)))

(defn- digest-value
  [value]
  (schema/sha-256 [(.getBytes (pr-str value) "UTF-8")]))

(defn supervision-run-id
  "The deterministic identity of root's first-agent supervision run."
  {:malli/schema [:=> [:cat] :seon.cluster.run/id]}
  []
  "bootstrap-supervision:root")

(defn- settled-form-sources
  [database agent-id]
  (db/q '[:find [?source ...]
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?run :seon.cluster.run/agent ?agent]
          [?form :seon.cluster.run.form/run ?run]
          [?form :seon.cluster.run.form/ordinal ?ordinal]
          [?form :seon.cluster.run.form/source ?source]
          [?receipt :seon.cluster.eval/run ?run]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]]
        database agent-id))

(defn- calls-symbol?
  [source called]
  (try
    (boolean
     (some #{called}
           (tree-seq coll? seq (edn/read-string source))))
    (catch Throwable _
      false)))

(defn- contains-history-query?
  [source]
  (try
    (let [elements (set (tree-seq coll? seq (edn/read-string source)))]
      (and (contains? elements :seon.cluster.eval/run)
           (contains? elements :seon.cluster.run.form/run)))
    (catch Throwable _
      false)))

(defn- root-read-agent-history?
  [database]
  (boolean
   (some #(or (calls-symbol? % 'seon.render.transcript/history-entries)
              (contains-history-query? %))
         (settled-form-sources database "root"))))

(defn- root-messaged-agent?
  [database]
  (some?
   (db/q '[:find ?message .
           :where
           [?root :seon.cluster.agent/id "root"]
           [?message :seon.cluster.message/from ?root]]
         database)))

(defn supervision-tx
  "Open root's self-erasing two-form lesson when its first agent arrives.

  Each action is omitted when root's durable history already proves it. The
  returned forms use the ordinary system-run transaction path and therefore
  acquire ordinary execution receipts."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/process
                       :seon.cluster.run/opened-at
                       :seon.cluster.agent/id]
                  :seon.store/transaction-data]}
  [database process opened-at agent-id]
  (let [run-id (supervision-run-id)
        already-open? (some? (db/pull database [:db/id]
                                      [:seon.cluster.run/id run-id]))
        read? (not (root-read-agent-history? database))
        send? (not (root-messaged-agent? database))
        read-expression
        (str "(db/q {:query '[:find ?at ?source ?result "
             ":in $ ?agent-id :where "
             "[?agent :seon.cluster.agent/id ?agent-id] "
             "[?run :seon.cluster.run/agent ?agent] "
             "[?form :seon.cluster.run.form/run ?run] "
             "[?form :seon.cluster.run.form/ordinal ?ordinal] "
             "[?form :seon.cluster.run.form/source ?source] "
             "[?receipt :seon.cluster.eval/run ?run] "
             "[?receipt :seon.cluster.eval/ordinal ?ordinal] "
             "[?receipt :seon.cluster.eval/at ?at] "
             "[?receipt :seon.cluster.eval/result-edn ?result]] "
             ":args [(db/db) " (pr-str agent-id) "] "
             ":order-by '[?at :desc] :limit 2})")
        read-source
        (if send?
          read-expression
          (str "(let [history " read-expression "] "
               "(assoc (run/complete \"Read " agent-id
               "'s recent history.\") :my.run/supervision history))"))
        send-expression
        (str "(my.message/send " (pr-str agent-id)
             " \"What are you doing?\")")
        send-source
        (str "(merge " send-expression
             " (run/complete \"Read " agent-id
             "'s recent history and asked what it is doing.\"))")
        sources
        (cond-> []
          read?
          (conj {:seon.cluster.run.form/source read-source
                 :seon.ns/name 'my.agents.root})
          send?
          (conj {:seon.cluster.run.form/source send-source
                 :seon.ns/name 'my.agents.root}))]
    (if (or already-open? (empty? sources))
      []
      (run/system-run-tx
       database
       {:seon.cluster.agent/id "root"
        :seon.cluster.run/id run-id
        :seon.cluster.run/process process
        :seon.cluster.run/opened-at opened-at
        :seon.cluster.run/starting-ns [:seon.ns/name 'my.agents.root]
        :seon.cluster.run/plan-digest (digest-value sources)
        :seon.cluster.run/sources sources}))))

(defn seed-tx
  "Transaction data opening, claiming, and freezing one bootstrap run."
  {:malli/schema
   [:=>
    [:cat
     :seon.db/database-value
     [:map
      [:seon.cluster.agent/id :seon.cluster.agent/id]
      [:seon.cluster/name :seon.cluster/name]
      [:seon.ns/name :seon.ns/name]
      [:seon.cluster.run/process :seon.cluster.run/process]
      [:seon.cluster.run/opened-at :seon.cluster.run/opened-at]]]
    :seon.store/transaction-data]}
  [db
   {agent-id :seon.cluster.agent/id
    namespace-name :seon.ns/name
    process :seon.cluster.run/process
    opened-at :seon.cluster.run/opened-at}]
  (let [id (run-id agent-id)
        message-id (task-message-id agent-id)
        namespace-row
        {:seon.ns/name namespace-name
         :seon.ns/requires
         [[:seon.ns/name 'my.run]
          [:seon.ns/name 'my.message]
          [:seon.ns/name 'seon.bootstrap]]
         :seon.ns/refers
         [{:seon.ns.refer/local 'help
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'help}
          {:seon.ns.refer/local 'dir
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'dir}
          {:seon.ns.refer/local 'doc
           :seon.ns.refer/target-ns 'seon.bootstrap
           :seon.ns.refer/target-name 'doc}]}
        message-row
        {:seon.cluster.message/id message-id
         :seon.cluster.message/ordinal 0
         :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/content (task-message)
         :seon.cluster.message/at opened-at}]
    (into [namespace-row message-row]
          (run/generated-run-tx
           db
           {:seon.cluster.agent/id agent-id
            :seon.cluster.run/id id
            :seon.cluster.run/process process
            :seon.cluster.run/opened-at opened-at
            :seon.cluster.run/trigger
            [:seon.cluster.message/id message-id]
            :seon.cluster.run/starting-ns [:seon.ns/name namespace-name]
            :seon.cluster.run.form/source
            (entry-source
             {:seon.repl/comment
              "; A new run just opened. Why am I awake — do I have messages?"
              :seon.repl/form '(help)})}))))
