(ns seon.bootstrap
  "The live-fact generated bootstrap run shared by every new agent."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ai :as ai]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.error.refusal :as error]
            [seon.id :as id]))

(defmacro help
  "Return the calling agent's REPL instructions as a help entity."
  []
  (list 'seon.bootstrap/help-value))

(defn help-value
  "Read the REPL instructions and tools from this program version."
  {:seon/tasks #{"2c66ac931003"}
   :malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.help/help]}
  [database agent-id]
  ;; The fixed prose belongs to this definition; the source read records
  ;; its code version in the same dependency evidence as every other read.
  (db/pull database [:seon.fn/source]
           [:seon.fn/sym 'seon.bootstrap/help-value])
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
                       [?session :seon.ns/requires ?name]
                       [?namespace :seon.ns/name ?name]
                       [?function :seon.fn/ns ?namespace]
                       [?function :seon.fn/private? false]
                       (not [?function :seon.fn/internal? true])]
              database agent-id)]
    ;; `help` is a macro, so its call names no producer; the value carries
    ;; its own pair, the render law's master override (issue
    ;; help-shows-its-map-because-a-macro-call-names-no-producer, option 1).
    {:seon.render/ai 'seon.bootstrap/render-help-ai
     :seon.render/html 'seon.bootstrap/render-help-html
     :seon.help/lines
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
     "Use pull for a known entity's shape, nested refs, and reverse refs such as :seon.message/_to; q for filters, joins, and aggregates; q with inner pull for filtering and shaping. (seon.db/transact! tx-data) writes. Your cluster database is supplied."
     "Define a function with its invoke contract: (defn increment {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1)). The input :cat describes the arguments; the last schema describes the result. A function that can fail returns [:or <success> <declared-refusal-declared-schema>]; a bare :maybe is refused. [:vector X] needs a vector; use vec to convert a lazy seq. Admitted definitions are durable. Auto-check calls your function with generated inputs including each collection's empty value. A deftest becomes a durable test. deftest and is are referred; use clojure.test/testing with its namespace. (my.test/run) runs yours."
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
    [:or :seon.agent/situation :my.plan/agent-not-found-error]]}
  [database agent-id]
  (let [agent-entity
        (db/pull database
                 '[:seon.agent/id
                   {:seon.agent/namespace
                    [:db/id :seon.ns/name
                     :seon.ns/requires]}]
                 [:seon.agent/id agent-id])]
    (if-not (:seon.agent/id agent-entity)
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.bootstrap/opening 'seon.bootstrap/situation
       {:seon.error/message "Create the requested agent before deriving its opening."
        :seon.error/offending agent-id
        :my.plan/refused-member :seon.agent/id
        :my.plan/missing-agent-id agent-id
        :seon.error/expected :seon.agent/entity})
      (let [namespace-entity (:seon.agent/namespace agent-entity)
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
            (turn/episode-runs
             database agent-id)
            ;; UNREAD AND THE TURN BOUND ARE ONE DERIVATION, BY `:t`.
            ;; A message is unanswered exactly while its transaction is
            ;; newer than every turn this agent has opened; the bound is
            ;; the turns taken since the latest wake from outside the
            ;; agent. Neither is stored and neither is counted here.
            unread
            (count (turn/unanswered-triggers
                    database agent-id))]
        (cond->
         {:seon.agent/id agent-id
          :seon.agent/namespace-ref
          [:seon.ns/name (:seon.ns/name namespace-entity)]
          :seon.agent/unread-message-count (long unread)
          :seon.turn/turns-remaining
          (long (max 0 (- (or turn-limit 0) turns-used)))
          :seon.agent/protocol-namespaces
          (->> (:seon.ns/requires namespace-entity)
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
       "entity with the greatest :example/amount, or {} for empty input. Call "
       "it once, query its stored :seon.fn/spec, then complete with a short "
       "reply naming what you built and its contract."))

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
    ;; clojure.edn declares "not readable EDN" as a RuntimeException (EdnReader.java:130, :174-177); every other failure propagates.
    (catch RuntimeException _
      false)))

(defn- contains-history-query?
  [source]
  (try
    (let [elements (set (tree-seq coll? seq (edn/read-string source)))]
      (and (contains? elements :seon.cluster.eval/run)
           (contains? elements :seon.cluster.eval/run)))
    ;; clojure.edn declares "not readable EDN" as a RuntimeException (EdnReader.java:130, :174-177); every other failure propagates.
    (catch RuntimeException _
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
  (let [turn-id (supervision-run-id)
        already-open? (some? (db/pull database [:db/id]
                                      [:seon.turn/id turn-id]))
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
       {:seon.agent/id "root" :seon.turn/id turn-id :seon.db.process/id process :seon.turn/opened-tx "datomic.tx" :seon.turn/starting-ns [:seon.ns/name 'my.agents.root] :seon.turn/reply-size (count (pr-str sources)) :seon.turn/sources sources}))))

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
    _opened-at :seon.turn/opened-tx}]
  (let [id (run-id agent-id)
        message-id (id/id (random-uuid) 8)
        message-entity
        {:seon.message/id message-id :seon.message/to [:seon.agent/id agent-id] :seon.message/content (task-message)}]
    (into [message-entity]
          (turn/generated-run-tx
           db
           {:seon.agent/id agent-id :seon.turn/id id :seon.db.process/id process :seon.turn/opened-tx "datomic.tx" :seon.turn/trigger [:seon.message/id message-id] :seon.turn/starting-ns [:seon.ns/name namespace-name]}))))
