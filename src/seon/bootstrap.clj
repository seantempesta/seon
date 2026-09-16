(ns seon.bootstrap
  "The live-fact generated bootstrap run shared by every new agent."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.plan :as plan]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.id :as id]
            [seon.render :as render]
            [seon.render.walk :as walk]
            [seon.schema :as schema]
            [seon.sci.kernel :as sci.kernel]))

(defmacro help
  "Return the calling agent's REPL instructions as a help entity."
  []
  (list 'seon.bootstrap/help-value))

(defn help-value
  "Read the REPL instructions and tools from this program version."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  :seon.help/help]}
  [database agent-id]
  ;; The fixed prose belongs to this definition; the source read records
  ;; its code version in the same dependency evidence as every other read.
  (db/pull database [:seon.fn/source]
           [:seon.fn/sym "seon.bootstrap/help-value"])
  (let [namespace-name
        (db/q '[:find ?name . :in $ ?id
                :where [?agent :seon.agent/id ?id]
                       [?agent :seon.agent/namespace ?namespace]
                       [?namespace :seon.ns/name ?name]] database agent-id)
        issue? (some? (db/q '[:find ?issue . :in $ ?id :where
                              [?agent :seon.agent/id ?id]
                              [?issue :seon.issue/agent ?agent]] database agent-id))
        tools
        (db/q '[:find [?name ...]
                :in $ ?agent-id
                :where [?agent :seon.agent/id ?agent-id]
                       [?agent :seon.agent/namespace ?session]
                       [?session :seon.ns/requires ?namespace]
                       [?namespace :seon.ns/name ?name]
                       [?function :seon.fn/ns ?namespace]
                       [?function :seon.fn/private? false]
                       (not [?function :seon.fn/internal? true])]
              database agent-id)]
    {:seon.help/lines
    [(str "The prompt shows your namespace " namespace-name
          " and is drawn for you. Send only ;; thinking comments and forms.")
     "Results are data: chain them with ->>, sort-by, filter, map, and get-in. Functions are callable by their fully qualified symbols."
     "Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; do not complete a step in the same reply as the form that does the work."
     "The REPL supplies responses: :value (or :error) is result data, :out is printed text, and :result names the live value. Do not write responses yourself."
     "result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys."
     (str "When unsure, use (dir ns) first, for example (dir my.note). Copy the exact function name, including ! on mutations. (doc my.note/add!) gives its request keys, return shape and example. (dir " namespace-name
          ") lists your own public functions and schema declarations.")
     "Your plan is your instructions. Read its current step and completion criterion before acting; a done-query completes it automatically when the facts match, and you mark a step without one complete only after seeing the result. Update an existing component by its identity or :db/id: a new identity-less nested map replaces it."
     "Read incoming messages with a reverse-ref pull on your agent. (my.message/send {:my.message/to \"root\" :my.message/content \"...\"}) sends; sending does not end your turn. Remove an entity and its incoming refs with (seon.db/transact! [[:db.fn/retractEntity lookup-ref]]); retract removes only the named fact."
     "Use pull for a known entity's shape, nested refs, and reverse refs such as :seon.message/_inbox; q for filters, joins, and aggregates; q with inner pull for filtering and shaping. (seon.db/transact! tx-data) writes. Your cluster database is supplied."
     "Define a function with its invoke contract: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1)). The input :cat describes the arguments; the last schema describes the result. A function that can fail returns [:or <success> :seon.error/value]; a bare :maybe is refused. [:vector X] needs a vector; use vec to convert a lazy seq. Admitted definitions are durable. Auto-check calls your function with generated inputs including each collection's empty value. A deftest becomes a durable test. deftest and is are referred; use clojure.test/testing with its namespace. (my.test/run) runs yours."
     (str "A mistake returns :error data. Read the expected schema, offending value, and attribute candidates before retrying. Time is the transaction: a ref value \"datomic.tx\" names this write, for example (seon.db/transact! [{:my.note/id \"observation\" :my.note/agent [:seon.agent/id "
          (pr-str agent-id)
          "] :my.note/content \"Verified\" :my.note/about \"datomic.tx\"}]); pull :db/txInstant through that ref.")
     (if issue?
       "Each reply is one turn. Your issue's tests or detector decide done. Continue while it is open and turns remain; the system reports the result after each turn and tells root when your budget is exhausted."
       "Each reply is one turn. (seon.turn/turns-left) reads your remaining turns; settings contain the configured limit. (my.agent/done) must be the last form of your reply; it ends your session early.")
     (str "Tools: "
          (str/join ", " (sort tools)) ". Inspect one with dir.")]}))

(defn render-help-ai
  "Show help lines as bare text, one per line."
  {:malli/schema [:=> [:cat :seon.help/help] :string]}
  [instructions]
  (str/join "\n" (:seon.help/lines instructions)))

(defn render-help-html
  "Show the same help lines as a list."
  {:malli/schema [:=> [:cat :seon.help/help] :seon.render/hiccup]}
  [instructions]
  (let [line-html
        (fn [line]
          (loop [remaining line children [:li]]
            (let [start (.indexOf ^String remaining "(")]
              (if (or (neg? start) (str/includes? remaining "\n"))
                (conj children remaining)
                (let [source (subs remaining start)
                      length (try
                               (with-open [reader (clojure.lang.LineNumberingPushbackReader.
                                                   (java.io.StringReader. source))]
                                 (edn/read {:readers {} :default tagged-literal} reader)
                                 (dec (.getColumnNumber reader)))
                               (catch Exception _ 0))]
                  (if (pos? length)
                    (recur (subs source length)
                           (conj children (subs remaining 0 start)
                                 [:code (subs source 0 length)]))
                    (conj children remaining)))))))]
    (into [:ul {:class "seon-help seon-help-instructions"}]
          (map line-html (:seon.help/lines instructions)))))

(defn situation
  "Derive one agent's live opening seeds from current database facts."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.agent/id]
    [:or :seon.agent/situation :seon.error/value]]}
  [database agent-id]
  (let [agent
        (db/pull database
                 '[:seon.agent/id
                   {:seon.agent/namespace
                    [:db/id :seon.ns/name
                     {:seon.ns/requires [:seon.ns/name]}]}]
                 [:seon.agent/id agent-id])]
    (if-not (:seon.agent/id agent)
      {:seon.agent/no-such-agent agent-id
       :seon.error/kind :seon.agent/no-such-agent
       :seon.error/message (str "No agent has id " (pr-str agent-id) ".")
       :seon.error/data {:seon.agent/id agent-id}}
      (let [namespace (:seon.agent/namespace agent)
            run (when-let [id (turn/open-for-agent database [:seon.agent/id agent-id])]
                  (db/pull database
                           '[:seon.turn/id
                             {:seon.turn/trigger [:seon.message/id]}]
                           [:seon.turn/id id]))
            turn-limit
            (or (:seon.config.run/max-episode-runs (ai/agent-overlay database agent-id))
                (db/q '[:find ?limit .
                        :where [?config :seon.config/cluster _]
                               [?config :seon.config.run/max-episode-runs ?limit]]
                      database))
            turns-used
            ((requiring-resolve 'seon.turn/episode-runs)
             database agent-id)
            ;; UNREAD AND THE TURN BOUND ARE ONE DERIVATION, BY `:t`.
            ;; A message is unanswered exactly while its transaction is
            ;; newer than every turn this agent has opened; the bound is
            ;; the turns taken since the latest wake from outside the
            ;; agent. Neither is stored and neither is counted here.
            unread
            (count ((requiring-resolve
                     'seon.turn/unanswered-triggers)
                    database agent-id))]
        (cond->
         {:seon.agent/id agent-id
          :seon.agent/namespace-ref
          [:seon.ns/name (:seon.ns/name namespace)]
          :seon.agent/unread-message-count (long unread)
          :seon.turn/turns-remaining
          (long (max 0 (- (or turn-limit 0) turns-used)))
          :seon.agent/protocol-namespaces
          (->> (:seon.ns/requires namespace)
               (map :seon.ns/name)
               sort
               vec)}
          run
          (assoc :seon.agent/open-run-ref
                 [:seon.turn/id
                  (:seon.turn/id run)])
          (:seon.turn/trigger run)
          (assoc :seon.turn/trigger
                 [:seon.message/id
                  (get-in run [:seon.turn/trigger
                               :seon.message/id])]))))))

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
  {:malli/schema [:=> [:cat :seon.agent/id]
                  :seon.turn/id]}
  [agent-id]
  (id/digest 12 [::turn agent-id]))

(defn task-message-id
  "Read the bootstrap turn's recorded task message identity."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:maybe :seon.message/id]]}
  [database agent-id]
  (db/q '[:find ?id . :in $ ?turn-id
          :where [?turn :seon.turn/id ?turn-id]
                 [?turn :seon.turn/trigger ?message]
                 [?message :seon.message/id ?id]]
        database (run-id agent-id)))

(defn task-message
  "The small real assignment that the shipped bootstrap episode completes."
  {:malli/schema [:=> [:cat] :seon.message/content]}
  []
  (str "Define a durable contracted function named largest that returns the "
       "row with the greatest :example/amount, or {} for empty input. Call "
       "it once, query its stored :seon.fn/spec, then complete with a short "
       "reply naming what you built and its contract."))

(defn- entry-form-source
  "The reader source of one entry's FORM, without its comment.

  The comment is a fact beside the source, not part of it: a prompt line
  holds exactly the one form it prompts for, and the stored source is what
  the reader read. This is also the string a stored generated form is
  compared against, so the two must be derived the same way."
  [{form :seon.repl/form}]
  (pr-str form))

(defn- entry-cost-source
  "Everything one entry costs the agent to read: its comment and its form."
  [{comment :seon.repl/comment :as entry}]
  (str (when comment (str comment "\n")) (entry-form-source entry)))

(defn- entries
  [rendered]
  (cond
    (and (map? rendered) (:seon.repl/form rendered)) [rendered]
    (and (vector? rendered) (every? :seon.repl/form rendered)) rendered
    (sequential? rendered) [{:seon.repl/form rendered}]
    :else []))

(defn- lookup-namespace-name
  "The namespace NAME a `[:seon.ns/name my.foo]` lookup identifies, or nil.

  The name is what an executable `dir` form and a demonstration-namespace
  membership test need. It is never a candidate's subject: a subject is the
  entity the candidate is about, spelled as the `:seon.render.walk/lookup`
  every other candidate producer hands, and `seon.render.walk/reference-keys`
  already relates that lookup to this bare symbol for the frontier."
  [lookup]
  (when (and (vector? lookup) (= :seon.ns/name (first lookup)))
    (second lookup)))

(defn- green-usage-result?
  [database namespace-name]
  (boolean
   (db/q '[:find ?test .
           :in $ ?namespace-name
           :where
           [?namespace :seon.ns/name ?namespace-name]
           [?test :seon.test/ns ?namespace]
           [?test :seon.test/usage true]
           [?test :seon.test/pass-count ?passes]
           [(< 0 ?passes)]
           [?test :seon.test/fail-count 0]
           [?test :seon.test/error-count 0]]
         database namespace-name)))

(defn- authored-results?
  [database namespace-name]
  (boolean
   (and namespace-name
        (db/q '[:find ?function .
                :in $ ?namespace-name
                :where
                [?namespace :seon.ns/name ?namespace-name]
                [?function :seon.fn/ns ?namespace]
                [?function :seon.fn/private? false]]
              database namespace-name)
        (green-usage-result? database namespace-name))))

(defn- usage-demonstration-namespaces
  [database]
  (set
   (db/q '[:find [?namespace-name ...]
           :where
           [?namespace :seon.ns/name ?namespace-name]
           [?function :seon.fn/ns ?namespace]
           [?test :seon.fn/calls ?function]
           [?test :seon.test/usage true]]
         database)))

(defn- own-namespace-name
  [acquisition]
  (get-in acquisition
          [:seon.render.walk/root
           :seon.agent/namespace
           :seon.ns/name]))

(defn- opening-candidate-lookups
  [database acquisition]
  (let [order (:seon.render.walk/order acquisition)
        root (first order)
        own-namespace (own-namespace-name acquisition)
        own-lookup [:seon.ns/name own-namespace]]
    (if (and (authored-results? database own-namespace)
             (some #{own-lookup} order))
      (into [root own-lookup] (remove #{root own-lookup}) order)
      order)))

(defn- executable-namespace-entry
  [lookup entry]
  (if-let [namespace-name (lookup-namespace-name lookup)]
    (assoc entry :seon.repl/form (list 'dir namespace-name))
    entry))

(defn- direct-candidates
  [request acquisition]
  (let [database (:seon.db/db request)
        root (first (:seon.render.walk/order acquisition))
        own-results? (authored-results? database
                                        (own-namespace-name acquisition))
        demonstration-namespaces
        (when own-results? (usage-demonstration-namespaces database))]
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
                   rendered-entries
                   (let [rendered-entries (entries rendered)]
                     (if (and own-results?
                              (lookup-namespace-name lookup)
                              (contains? demonstration-namespaces
                                         (lookup-namespace-name lookup)))
                       (take 1 rendered-entries)
                       rendered-entries))]
               (when (or (= lookup root)
                         (lookup-namespace-name lookup)
                         (some seq (map (comp walk/form-symbols :seon.repl/form)
                                        rendered-entries)))
                 (map-indexed
                  (fn [index entry]
                    {:seon.repl/key [lookup index]
                     :seon.repl/subject lookup
                     :seon.repl/previous-key
                     (when (pos? index) [lookup (dec index)])
                     :seon.repl/entry
                     (executable-namespace-entry lookup entry)})
                  rendered-entries))))
          (opening-candidate-lookups database acquisition)))))

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

(defn- beyond-closure-budget
  [database agent-id]
  (let [attribute :seon.config.bootstrap/beyond-closure-token-budget
        budget
        (db/q '[:find ?budget .
                :in $ ?agent-id
                :where
                [?agent :seon.agent/id ?agent-id]
                [?cluster :seon.cluster/config ?config]
                [?config :seon.config.bootstrap/beyond-closure-token-budget
                 ?budget]]
              database agent-id)]
    (cond
      (:seon.error/kind budget) budget
      (int? budget) budget
      :else
      {:seon.config/required-absent attribute
       :seon.error/kind :seon.config/required-absent
       :seon.error/message
       (str "Generated opening intent membership requires config key "
            attribute ".")})))

(defn- demonstrated-namespace-names
  [database agent-id]
  (set
   (concat
    (db/q '[:find [?target-name ...]
            :in $ ?agent-id
            :where
            [?agent :seon.agent/id ?agent-id]
            [?agent :seon.agent/namespace ?own-namespace]
            [?artifact :seon.fn/ns ?own-namespace]
            [?artifact :seon.fn/private? false]
            [?artifact :seon.fn/spec]
            [?artifact :seon.fn/calls ?target]
            [?target :seon.fn/ns ?target-namespace]
            [?target-namespace :seon.ns/name ?target-name]]
          database agent-id)
    (db/q '[:find [?target-name ...]
            :in $ ?agent-id
            :where
            [?agent :seon.agent/id ?agent-id]
            [?agent :seon.agent/namespace ?own-namespace]
            [?artifact :seon.test/ns ?own-namespace]
            [?artifact :seon.test/usage true]
            [?artifact :seon.test/pass-count ?passes]
            [(< 0 ?passes)]
            [?artifact :seon.test/fail-count 0]
            [?artifact :seon.test/error-count 0]
            [?artifact :seon.fn/calls ?target]
            [?target :seon.fn/ns ?target-namespace]
            [?target-namespace :seon.ns/name ?target-name]]
          database agent-id))))

(defn- intent-acquisition
  [request subject]
  (let [lookup (walk/entity-lookup (:seon.db/db request) subject)]
    (if (:seon.error/kind lookup)
      {:seon.render.walk/root lookup
       :seon.render.walk/members {}
       :seon.render.walk/order []}
      (walk/root-acquisition
       (-> request
           (assoc :seon.render.walk/lookup lookup
                  :seon.render/distance 1)
           (dissoc :seon.render.walk/root-acquisition
                   :seon.render.walk/root-pull-plan))))))

(defn- registered-schema-key-subject?
  [acquisition]
  (some? (get-in acquisition [:seon.render.walk/root :seon.schema/key])))

(defn- usage-demonstration-candidates
  [database subject subject-lookup]
  (into []
        (map
         (fn [test-symbol]
           {:seon.repl/key [[:seon.test/sym test-symbol] :demonstration]
            :seon.repl/subject subject-lookup
            :seon.repl/entry
            {:seon.repl/comment
             "; First real use — the indexed call-edge demonstration."
             :seon.repl/form
             (list 'clojure.test/test-var
                   (list 'var (symbol test-symbol)))}}))
        (db/q '[:find [?test-symbol ...]
                :in $ ?subject
                :where
                [?test :seon.test/usage true]
                [?test :seon.test/sym ?test-symbol]
                [?test :seon.fn/calls ?subject]
                [?test :seon.test/pass-count ?passes]
                [(< 0 ?passes)]
                [?test :seon.test/fail-count 0]
                [?test :seon.test/error-count 0]]
              database subject)))

(defn- subject-candidates
  [request demonstrated subject acquisition]
  (let [registered-key? (registered-schema-key-subject? acquisition)
        subject-lookup (walk/entity-lookup (:seon.db/db request) subject)
        owner-namespace
        (get-in acquisition [:seon.render.walk/root :seon.fn/ns :seon.ns/name])
        owner-lookup (when owner-namespace [:seon.ns/name owner-namespace])
        direct
        (into []
              (filter
               (fn [candidate]
                 (let [[lookup index] (:seon.repl/key candidate)]
                   (and (or (= lookup subject-lookup)
                            (= lookup owner-lookup))
                        (not (and (= lookup owner-lookup)
                                  (pos? index)
                                  (or registered-key?
                                      (contains? demonstrated
                                                 owner-namespace))))))))
              (direct-candidates request acquisition))
        direct
        (into []
              (remove (comp integer? :seon.repl/subject))
              direct)]
    (if (or registered-key? (contains? demonstrated owner-namespace))
      direct
      (into direct (usage-demonstration-candidates
                    (:seon.db/db request) subject subject-lookup)))))

(defn- candidate-cost
  [candidate]
  (long (or (tokens/estimate
             (entry-cost-source (:seon.repl/entry candidate)))
            0)))

(defn- restrict-acquisition
  [acquisition candidates]
  (let [lookups (set (map (comp first :seon.repl/key) candidates))]
    (-> acquisition
        (update :seon.render.walk/members select-keys lookups)
        (update :seon.render.walk/order #(into [] (filter lookups) %)))))

(defn- admitted-intent
  [request subjects budget excluded-keys]
  (let [demonstrated
        (demonstrated-namespace-names
         (:seon.db/db request)
         (second (:seon.render.walk/lookup request)))
        units
        (mapv (fn [subject]
                (let [acquisition (intent-acquisition request subject)]
                  {:my.plan/subject subject
                   :my.plan/acquisition acquisition
                   :my.plan/candidates
                   (subject-candidates request demonstrated subject
                                       acquisition)}))
              subjects)
        state
        (reduce
         (fn [{spent :my.plan/spent seen :my.plan/seen :as state} unit]
           (let [fresh (remove #(contains? seen (:seon.repl/key %))
                               (:my.plan/candidates unit))
                 admission
                 (reduce
                  (fn [{entry-spent :my.plan/spent :as admitted} candidate]
                    (let [next-spent (+ entry-spent (candidate-cost candidate))]
                      (if (<= next-spent budget)
                        (-> admitted
                            (assoc :my.plan/spent next-spent)
                            (update :my.plan/candidates conj candidate)
                            (update :my.plan/seen conj (:seon.repl/key candidate)))
                        (reduced (assoc admitted :my.plan/full? true)))))
                  {:my.plan/spent spent
                   :my.plan/candidates []
                   :my.plan/seen seen}
                  fresh)
                 admitted (:my.plan/candidates admission)
                 state-with-candidates
                 (-> state
                     (assoc :my.plan/spent (:my.plan/spent admission)
                            :my.plan/seen (:my.plan/seen admission))
                     (update :my.plan/candidates into admitted))
                 next-state
                 (if (seq admitted)
                   (-> state-with-candidates
                       (update :my.plan/subjects conj
                               (walk/entity-lookup
                                (:seon.db/db request)
                                (:my.plan/subject unit)))
                       (update :my.plan/acquisitions conj
                               (restrict-acquisition
                                (:my.plan/acquisition unit) admitted)))
                   state-with-candidates)]
             (if (:my.plan/full? admission) (reduced next-state) next-state)))
         {:my.plan/spent 0
          :my.plan/seen excluded-keys
          :my.plan/subjects []
          :my.plan/candidates []
          :my.plan/acquisitions []}
         units)]
    (select-keys state [:my.plan/subjects :my.plan/candidates
                        :my.plan/acquisitions])))

(defn pull-result
  "Pull and render the bounded candidate neighborhood for one opening."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :map]}
  [request]
  (let [acquisition (walk/root-acquisition request)
        root (:seon.render.walk/root acquisition)
        order (:seon.render.walk/order acquisition)]
    (cond
      (:seon.error/kind root)
      root

      (or (nil? root) (empty? order))
      {::root-acquisition-empty true
       :seon.error/kind ::root-acquisition-empty
       :seon.error/message
       "The generated opening root pull returned no membership data."
       :seon.error/data
       {:seon.render.walk/lookup (:seon.render.walk/lookup request)
        :seon.render.walk/root-present? (some? root)
        :seon.render.walk/member-count
        (count (:seon.render.walk/members acquisition))}}

      :else
      (let [agent-id (second (:seon.render.walk/lookup request))
            budget (beyond-closure-budget (:seon.db/db request) agent-id)
            subjects (when-not (:seon.error/kind budget)
                       (plan/ready-subjects (:seon.db/db request) agent-id))]
        (cond
          (:seon.error/kind budget) budget
          (:seon.error/kind subjects) subjects
          :else
          (let [base-candidates
                (into (direct-candidates request acquisition)
                      (listing-candidates request acquisition))
                admitted
                (admitted-intent request subjects budget
                                 (set (map :seon.repl/key base-candidates)))
                intent-candidates (:my.plan/candidates admitted)
                joined
                (walk/join-membership
                 acquisition (:my.plan/acquisitions admitted))
                identities
                (set (db/populated-identity-attributes
                      (:seon.db/db request)))]
            {:seon.repl/root-key [(first order) 0]
             :seon.repl/candidates (into base-candidates intent-candidates)
             :seon.print/identity-attributes identities
             :my.plan/intent-subjects
             (:my.plan/subjects admitted)
             :seon.render.walk/root-acquisition joined}))))))

(defn- root-candidate
  [request root-key]
  (let [agent-id (second (:seon.render.walk/lookup request))
        rendered
        (render/render-call
         (assoc request
                :seon.render/value
                (situation (:seon.db/db request) agent-id)
                :seon.render/output :seon.render/form
                :seon.render.call/selected-producer
                'seon.cluster.agent/situation-form
                :seon.render.call/id
                [:seon.render/form (:seon.render.walk/lookup request)]))]
    {:seon.repl/key root-key
     :seon.repl/subject (:seon.render.walk/lookup request)
     :seon.repl/entry (first (entries rendered))}))

(defn- next-entry-in
  [request run-id]
  (let [rows
        (db/q {:query
               '[:find ?ordinal ?source ?result
                 :in $ ?run-id
                 :where
                 [?run :seon.turn/id ?run-id]
                 [?form :seon.cluster.eval/run ?run]
                 [?form :seon.cluster.eval/ordinal ?ordinal]
                 [?form :seon.cluster.eval/source ?source]
                 [?receipt :seon.cluster.eval/run ?run]
                 [?receipt :seon.cluster.eval/ordinal ?ordinal]
                 [?receipt :seon.eval/shown ?result]]
               :args [(:seon.db/db request) run-id]
               :order-by '[?ordinal :asc]})
        pull (pull-result request)]
    (if (:seon.error/kind pull)
      pull
      (let [candidates
            (let [root (root-candidate request (:seon.repl/root-key pull))]
              (into [root]
                    (remove #(= (:seon.repl/key root) (:seon.repl/key %)))
                    (:seon.repl/candidates pull)))
            candidate-by-source
            (reduce (fn [by-source candidate]
                      (let [source (entry-form-source
                                    (:seon.repl/entry candidate))]
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
                        (let [message
                              "A stored generated form is outside the pull."]
                          (throw
                           (ex-info message
                                    {::prefix-drift true
                                     :seon.error/kind ::prefix-drift
                                     :seon.error/message message
                                     :seon.turn/id run-id
                                     :seon.cluster.eval/source source}))))
                      {:seon.repl/key (:seon.repl/key candidate)
                       :seon.sci.admit/print-node (edn/read-string result)}))
                  rows)
            episode
            (walk/ordered-episode (assoc pull
                                         :seon.repl/candidates candidates
                                         :seon.repl/settled settled))
            index (count rows)
            prior-sources (mapv second rows)
            expected-sources (mapv entry-form-source (take index episode))]
        (when-not (= prior-sources expected-sources)
          (let [message
                (str "The generated opening prefix differs from its receipts: expected "
                     (pr-str expected-sources) " actual " (pr-str prior-sources))]
            (throw
             (ex-info message
                      {::prefix-drift true
                       :seon.error/kind ::prefix-drift
                       :seon.error/message message
                       :seon.turn/id run-id
                       :seon.bootstrap/expected expected-sources
                       :seon.bootstrap/actual prior-sources}))))
        (nth episode index nil)))))

(defn next-entry
  "Derive the next generated entry from receipts already stored on the run."
  {:malli/schema [:=> [:cat :seon.render.walk/request :seon.turn/id]
                  [:or :nil :seon.repl/entry :seon.error/value]]}
  [request run-id]
  (let [projection
        (or (schema/handed-projection)
            (sci.kernel/context-projection (:seon.sci.eval/ctx request)))]
    (schema/call-with-projection
     projection
     #(next-entry-in request run-id))))

(defn- digest-value
  [value]
  (id/digest 64 value))

(defn supervision-run-id
  "The deterministic identity of root's first-agent supervision run."
  {:malli/schema [:=> [:cat] :seon.turn/id]}
  []
  (id/digest 12 [::supervision "root"]))

(defn- settled-form-sources
  [database agent-id]
  (db/q '[:find [?source ...]
          :in $ ?agent-id
          :where
          [?agent :seon.agent/id ?agent-id]
          [?run :seon.turn/agent ?agent]
          [?form :seon.cluster.eval/run ?run]
          [?form :seon.cluster.eval/ordinal ?ordinal]
          [?form :seon.cluster.eval/source ?source]
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
           (contains? elements :seon.cluster.eval/run)))
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
           [?root :seon.agent/id "root"]
           [?message :seon.message/from ?root]]
         database)))

(defn supervision-tx
  "Open root's self-erasing two-form lesson when its first agent arrives.

  Each action is omitted when root's durable history already proves it. The
  returned forms use the ordinary system-run transaction path and therefore
  acquire ordinary execution receipts."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.db.process/id
                       :seon.agent/id]
                  :seon.store/transaction-data]}
  [database process agent-id]
  (let [run-id (supervision-run-id)
        already-open? (some? (db/pull database [:db/id]
                                      [:seon.turn/id run-id]))
        read? (not (root-read-agent-history? database))
        send? (not (root-messaged-agent? database))
        read-expression
        (str "(seon.db/q {:query '[:find ?at ?source ?result "
             ":in $ ?agent-id :where "
             "[?agent :seon.agent/id ?agent-id] "
             "[?run :seon.turn/agent ?agent] "
             "[?form :seon.cluster.eval/run ?run] "
             "[?form :seon.cluster.eval/ordinal ?ordinal] "
             "[?form :seon.cluster.eval/source ?source] "
             "[?receipt :seon.cluster.eval/run ?run] "
             "[?receipt :seon.cluster.eval/ordinal ?ordinal] "
             "[?receipt :seon.cluster.eval/at ?at] "
             "[?receipt :seon.eval/shown ?result]] "
             ":args [(seon.db/db) " (pr-str agent-id) "] "
             ":order-by '[?at :desc] :limit 2})")
        read-source
        (if send?
          read-expression
          (str "(let [history " read-expression "] "
               "(assoc (seon.run/complete \"Read " agent-id
               "'s recent history.\") :my.turn/supervision history))"))
        send-expression
        (pr-str (list 'my.message/send
                      {:my.message/to agent-id
                       :my.message/content "What are you doing?"}))
        send-source
        (str "(merge " send-expression
             " (seon.run/complete \"Read " agent-id
             "'s recent history and asked what it is doing.\"))")
        sources
        (cond-> []
          read?
          (conj {:seon.cluster.eval/source read-source
                 :seon.ns/name 'my.agents.root})
          send?
          (conj {:seon.cluster.eval/source send-source
                 :seon.ns/name 'my.agents.root}))]
    (if (or already-open? (empty? sources))
      []
      (turn/system-run-tx
       database
       {:seon.agent/id "root" :seon.turn/id run-id :seon.db.process/id process :seon.turn/opened-tx "datomic.tx" :seon.turn/starting-ns [:seon.ns/name 'my.agents.root] :seon.turn/reply-size (count (pr-str sources)) :seon.turn/sources sources}))))

(defn seed-tx
  "Transaction data opening, claiming, and freezing one bootstrap run."
  {:malli/schema
   [:=>
    [:cat
     :seon.db/database-value
     [:map
      [:seon.agent/id :seon.agent/id]
      [:seon.cluster/name :seon.cluster/name]
      [:seon.ns/name :seon.ns/name]
      [:seon.db.process/id :seon.db.process/id]
      [:seon.turn/opened-tx :seon.turn/opened-tx]]]
    :seon.store/transaction-data]}
  [db
   {agent-id :seon.agent/id
    namespace-name :seon.ns/name
    process :seon.db.process/id
    opened-at :seon.turn/opened-tx}]
  (let [id (run-id agent-id)
        message-id (id/id (random-uuid) 8)
        namespace-row
        {:seon.ns/name namespace-name
         :seon.ns/requires
         [[:seon.ns/name 'my.turn]
          [:seon.ns/name 'my.message]
          [:seon.ns/name 'clojure.test]
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
           :seon.ns.refer/target-name 'doc}
          {:seon.ns.refer/local 'deftest
           :seon.ns.refer/target-ns 'clojure.test
           :seon.ns.refer/target-name 'deftest}
          {:seon.ns.refer/local 'is
           :seon.ns.refer/target-ns 'clojure.test
           :seon.ns.refer/target-name 'is}]}
        message-row
        {:seon.message/id message-id :seon.message/to [:seon.agent/id agent-id] :seon.message/content (task-message) :seon.message/inbox [:seon.agent/id agent-id]}]
    (into [namespace-row message-row]
          (turn/generated-run-tx
           db
           {:seon.agent/id agent-id :seon.turn/id id :seon.db.process/id process :seon.turn/opened-tx "datomic.tx" :seon.turn/trigger [:seon.message/id message-id] :seon.turn/starting-ns [:seon.ns/name namespace-name]}))))
