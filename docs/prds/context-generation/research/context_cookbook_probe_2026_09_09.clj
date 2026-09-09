(ns context-cookbook-probe-2026-09-09
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.eval :as evaluation]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.agent :as agent]
            [seon.bootstrap :as bootstrap]
            [seon.note :as note]
            [seon.cluster.message :as message]
            [seon.plan :as plan]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.render.value :as value]
            [seon.schema :as schema]))

; Execute through default's MCP JVM session. Every write below is d/with.
; The proposed attributes exist only in a speculative database value.
(def reads
  [["Identity" "I should know who I am and where my forms run."
    '(seon.db/pull database '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}] [:seon.agent/id "juniper"])]
   ["Plan" "I should read the plan component, not select its attributes on the agent; its steps are a set, so I order them by position."
    '(update (:seon.agent/plan (seon.db/pull database '[{:seon.agent/plan [:my.plan/objective {:my.plan/current-step [:my.plan.item/id]} {:my.plan/steps [:my.plan.item/id :my.plan.item/title :my.plan.item/expected-result :my.plan.item/position :my.plan.item/completed-at {:my.plan.item/needs [:my.plan.item/id]}]}]}] [:seon.agent/id "juniper"])) :my.plan/steps #(vec (sort-by :my.plan.item/position %)))]
   ["Settings" "I should pull my overrides; omitted settings inherit defaults, and turns left is derived rather than a stored attribute."
    '(seon.db/pull database '[{:seon.agent/settings [:seon.config.ai/model :seon.config.ai/no-provider :seon.config.eval/time-limit-ms :seon.config.run/max-episode-runs]}] [:seon.agent/id "juniper"])]
   ["Runtime target" "I should inspect unknown-attribute candidates before guessing a runtime attribute."
    '(seon.db/pull database '[{:seon.agent/runtime [{:seon.runtime/turn [:seon.turn/id]} {:seon.runtime/listens [:seon.listen/attribute]}]}] [:seon.agent/id "juniper"])]
   ["Runtime current" "I should find my open turns by filtering for the absence of closed-at."
    '(seon.db/q '[:find [(pull ?t [:seon.turn/id :seon.turn/opened-at {:seon.turn/trigger [:seon.cluster.message/id]}]) ...] :where [?t :seon.turn/agent [:seon.agent/id "juniper"]] (not [?t :seon.turn/closed-at])] database)]
   ["Messages" "I should follow incoming messages with a reverse-ref pull on myself."
    '(get (seon.db/pull database '[{:seon.cluster.message/_to [:seon.cluster.message/id :seon.cluster.message/content {:seon.cluster.message/from [:seon.agent/id]}]}] [:seon.agent/id "juniper"]) :seon.cluster.message/_to [])]
   ["History" "I should inspect stored source and shown text only when needed; my prompt already contains my history."
    '(seon.db/q '[:find ?ordinal ?source :where [?t :seon.turn/agent [:seon.agent/id "juniper"]] [?e :seon.cluster.eval/run ?t] [?e :seon.cluster.eval/ordinal ?ordinal] [?e :seon.cluster.eval/source ?source]] database)]
   ["Faults root" "I should fix faults routed to me as steward before other work."
    '(seon.db/q '[:find [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...] :where [?f :seon.error/steward [:seon.agent/id "root"]]] database)]
   ["Faults juniper" "I should distinguish faults routed to me from faults that happened to me."
    '(seon.db/q '[:find [(pull ?f [:seon.error/kind :seon.error/message :seon.instrument/fn]) ...] :where [?f :seon.error/steward [:seon.agent/id "juniper"]]] database)]
   ["Notes" "I should read my saved notes, including an empty result so later notes can refresh this read."
    '(get (seon.db/pull database '[{:my.note/_agent [:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}]}] [:seon.agent/id "juniper"]) :my.note/_agent [])]
   ["Namespace" "I should inspect the declarations owned by my namespace before choosing attributes."
    '(seon.db/pull database '[:seon.ns/name {:seon.schema/_ns [:seon.schema/key :seon.schema/form]} {:seon.fn/_ns [:seon.fn/sym :seon.fn/doc]}] [:seon.ns/name 'my.agents.juniper])]
   ["Namespace counts" "I should count facts with q; a pull describes entities but does not aggregate them."
    '(seon.db/q '[:find ?a (count ?e) :in $ [?a ...] :where [?e ?a _]] database [:example/order :example/customer :example/amount])]
   ["Root agents" "I should select all agents with q and shape each record with inner pull."
    '(seon.db/q '[:find [(pull ?a [:seon.agent/id {:seon.agent/plan [{:my.plan/current-step [:my.plan.item/title]}]}]) ...] :where [?a :seon.agent/id]] database)]
   ["Orders" "I should read order ids, customers, and amounts before completing the query step."
    '(seon.db/q '[:find ?id ?customer ?amount :where [?e :example/order ?id] [?e :example/customer ?customer] [?e :example/amount ?amount]] database)]
   ["Customer totals" "I should group by customer and sum amounts rather than add the rows myself."
    '(seon.db/q '[:find ?customer (sum ?amount) :where [?e :example/customer ?customer] [?e :example/amount ?amount]] database)]])

(def writes
  [["Add a step" "I should upsert by the plan's identity; an identity-less nested map would silently replace the component."
    '[{:my.plan/agent [:seon.agent/id "juniper"] :my.plan/steps [{:my.plan.item/id "juniper/verify" :my.plan.item/title "Verify the new total" :my.plan.item/done-when "The fresh sum includes the new order." :my.plan.item/position 6}]}]]
   ["Complete a step" "I have seen the query result; with no clock function, I record completion as a ref to datomic.tx."
    '[[:db/add [:my.plan.item/id "juniper/query"] :my.plan.item/completed-tx "datomic.tx"]]]
   ["Make current" "I should make the aggregate step current after completing the query."
    '[[:db/add [:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step [:my.plan.item/id "juniper/aggregate"]]]]
   ["Remove a step" "I should use retractEntity to remove the item and incoming refs; retracting only the component edge leaves an orphan."
    '[[:db.fn/retractEntity [:my.plan.item/id "juniper/verify"]]]]
   ["Send a message" "I should include an event identity when I transact a message to root."
    '[{:seon.message/id "c00cb001" :seon.message/to [:seon.agent/id "root"] :seon.message/from [:seon.agent/id "juniper"] :seon.message/content "The query found Ada totals 115."}]]
   ["Answer a message" "I should link the reply to the question and mark that question handled in the same transaction."
    '[{:seon.message/id "c00cb002" :seon.message/to [:seon.agent/id "root"] :seon.message/from [:seon.agent/id "juniper"] :seon.message/content "I will add 40 and read the new total." :seon.message/about [:seon.message/id "c00cb000"]} [:db/add [:seon.message/id "c00cb000"] :seon.message/read-tx "datomic.tx"]]]
   ["Change a setting" "I should address my settings component by identity so I preserve its other overrides."
    '[{:seon.config/agent [:seon.agent/id "juniper"] :seon.config.eval/time-limit-ms 2500}]]
   ["Declare a listen" "I should add an attribute pattern to my runtime listens."
    '[{:seon.runtime/agent [:seon.agent/id "juniper"] :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}]]
   ["Transact a note" "I should save the verified query result as a note linked to its step."
    '[{:my.note/id "juniper/orders-observed" :my.note/agent [:seon.agent/id "juniper"] :my.note/about [:my.plan.item/id "juniper/query"] :my.note/content "Read four orders; next compute customer totals."}]]])

(defn- proposed-database [database]
  (let [attribute (fn [ident value-type & [properties]]
                    (merge {:db/ident ident :db/valueType value-type :db/cardinality :db.cardinality/one} properties))
        unique-identity {:db/unique :db.unique/identity}
        proposed [(attribute :my.plan/agent :db.type/ref unique-identity)
                  (attribute :seon.config/agent :db.type/ref unique-identity)
                  (attribute :seon.runtime/agent :db.type/ref unique-identity)
                  (attribute :my.plan.item/done-when :db.type/string)
                  (attribute :my.plan.item/completed-tx :db.type/ref)
                  (attribute :seon.agent/runtime :db.type/ref {:db/isComponent true})
                  (attribute :seon.runtime/turn :db.type/ref)
                  (attribute :seon.runtime/listens :db.type/ref {:db/cardinality :db.cardinality/many :db/isComponent true})
                  (attribute :seon.listen/attribute :db.type/keyword)
                  (attribute :seon.message/id :db.type/string unique-identity)
                  (attribute :seon.message/to :db.type/ref)
                  (attribute :seon.message/from :db.type/ref)
                  (attribute :seon.message/about :db.type/ref)
                  (attribute :seon.message/content :db.type/string)
                  (attribute :seon.message/read-tx :db.type/ref)]
        absent (remove #(d/pull database [:db/ident] (:db/ident %)) proposed)
        with-schema (:db-after (d/with database (vec absent)))
        agent-row (d/pull with-schema '[:db/id {:seon.agent/plan [:db/id]} {:seon.agent/settings [:db/id]}] [:seon.agent/id "juniper"])]
    (:db-after
     (d/with with-schema
       [{:db/id (get-in agent-row [:seon.agent/plan :db/id]) :my.plan/agent [:seon.agent/id "juniper"]}
        {:db/id (get-in agent-row [:seon.agent/settings :db/id]) :seon.config/agent [:seon.agent/id "juniper"]}
        {:seon.agent/id "juniper" :seon.agent/runtime {:seon.runtime/agent [:seon.agent/id "juniper"]}}
        {:seon.message/id "c00cb000" :seon.message/to [:seon.agent/id "juniper"] :seon.message/from [:seon.agent/id "root"] :seon.message/content "Please inspect the orders."}]))))

(defn- report-face [report]
  (let [before (:db-before report) after (:db-after report)
        identities (set (d/q '[:find [?a ...] :where [?e :db/ident ?a] [?e :db/unique :db.unique/identity]] after))]
    (letfn [(reference [eid visited]
              (if (visited eid) eid
                  (or (some (fn [database]
                              (some (fn [datom]
                                      (when (identities (:a datom))
                                        [(:a datom)
                                         (if (= :db.type/ref (get-in database [:schema (:a datom) :db/valueType]))
                                           (reference (:v datom) (conj visited eid))
                                           (:v datom))]))
                                    (sort-by (comp str :a) (d/datoms database :eavt eid))))
                            [after before]) eid)))]
      {:seon.db/tx (get (:tempids report) :db/current-tx)
       :seon.db/datoms (mapv (fn [datom] [(reference (:e datom) #{}) (:a datom) (:v datom) (:added datom)]) (:tx-data report))})))

(defn- byte-count [text] (alength (.getBytes ^String text "UTF-8")))

(defn run-probe!
  "Execute chart reads and speculative writes, retaining exact output bytes."
  []
  (let [connection (operator/connection "default") database @connection
        run-read (fn [[title thought form]]
                   (let [sink (atom [])
                         value (binding [db/*read-evidence-sink* sink]
                                 ((eval (list 'fn ['database] form)) database))
                         output (pr-str value)]
                     {:title title :thought thought :form (pr-str form) :output output
                      :bytes (byte-count output)
                      :evidence (mapv #(if (seq (:seon.db/read-index-patterns %)) :index-patterns :attribute-level) @sink)}))
        read-results (mapv run-read reads)
        proposed (proposed-database database)
        write-results (:results
                       (reduce (fn [{:keys [database results]} [title thought tx]]
                                 (let [report (d/with database tx) output (pr-str (report-face report))]
                                   {:database (:db-after report)
                                    :results (conj results {:title title :thought thought :form (pr-str (list 'seon.db/transact! tx))
                                                           :executed (pr-str (list 'datahike.api/with 'database tx))
                                                           :output output :bytes (byte-count output)})}))
                               {:database proposed :results []} writes))
        result {:reads read-results :writes write-results :basis (:max-tx database)}]
    (spit "docs/prds/context-generation/research/context_cookbook_probe_2026_09_09.edn" (pr-str result))
    (mapv #(select-keys % [:title :bytes :evidence]) (concat read-results write-results))))

(defn probe-proposed-reads!
  "Read target-shaped speculative results and append their actual outputs."
  []
  (let [connection (operator/connection "default")
        initial (proposed-database @connection)
        after (reduce (fn [database [_ _ tx]] (:db-after (d/with database tx))) initial writes)
        forms
        [["Runtime after declaring a listen" "I should verify that my runtime contains the listen I added."
          '(seon.db/pull database '[{:seon.agent/runtime [{:seon.runtime/listens [:seon.listen/attribute]}]}] [:seon.agent/id "juniper"])]
         ["Target messages, reverse pull" "I should read root's incoming messages through seon.message/_to."
          '(seon.db/pull database '[{:seon.message/_to [:seon.message/id :seon.message/content {:seon.message/from [:seon.agent/id]} {:seon.message/about [:seon.message/id]}]}] [:seon.agent/id "root"])]
         ["Completion instant" "I should derive the completion instant from its transaction ref."
          '(seon.db/pull database '[:my.plan.item/id {:my.plan.item/completed-tx [:db/txInstant]}] [:my.plan.item/id "juniper/query"])]
         ["Removal verification" "I should verify the deleted item is absent, not merely detached from the plan."
          '(seon.db/pull database [:my.plan.item/id] [:my.plan.item/id "juniper/verify"])]
         ["Plan membership after removal" "I should see the original six steps and the new current step after removing my probe item."
          '(seon.db/pull database '[{:my.plan/current-step [:my.plan.item/id]} {:my.plan/steps [:my.plan.item/id :my.plan.item/position]}] [:my.plan/agent [:seon.agent/id "juniper"]])]
         ["Handled question" "I should verify the question carries the transaction that handled it."
          '(seon.db/pull database '[:seon.message/id {:seon.message/read-tx [:db/txInstant]}] [:seon.message/id "c00cb000"])]
         ["Settings preservation" "I should see my changed time limit alongside the untouched overrides."
          '(seon.db/pull database [:seon.config.eval/time-limit-ms :seon.config.ai/no-provider :seon.config.run/max-episode-runs] [:seon.config/agent [:seon.agent/id "juniper"]])]
         ["Saved note" "I should see my saved note linked to the query step."
          '(seon.db/pull database '[:my.note/id :my.note/content {:my.note/about [:my.plan.item/id]}] [:my.note/id "juniper/orders-observed"])]]
        results (mapv (fn [[title thought form]]
                        (let [output (pr-str ((eval (list 'fn ['database] form)) after))]
                          {:title title :thought thought :form (pr-str form)
                           :output output :bytes (byte-count output)})) forms)
        face (report-face (d/with initial [[:db/add [:my.plan/agent [:seon.agent/id "juniper"]]
                                            :my.plan/current-step [:my.plan.item/id "juniper/aggregate"]]]))
        path "docs/prds/context-generation/research/context_cookbook_proposed_reads_2026_09_09.edn"]
    (spit path (pr-str {:reads results :recursive-report face}))
    (spit "docs/prds/context-generation/research/context-cookbook-2026-09-09.md"
          (str "\n## Target shapes after the speculative writes\n\n"
               "These reads execute on the final `:db-after` of the same nine-write `with` chain; they do not claim that target schema or wake behavior is installed on default.\n"
               (str/join (map (fn [{:keys [title thought form output] n :bytes}]
                               (str "\n### " title "\n\n```clojure\n;; " thought "\n" form
                                    "\n```\n\nActual result, " n " UTF-8 bytes:\n\n```clojure\n" output "\n```\n")) results))
               "\nThe report prototype now follows ref-valued identities recursively, with a visited-entity set for cycles. Actual executed result, "
               (byte-count (pr-str face)) " UTF-8 bytes:\n\n```clojure\n" (pr-str face) "\n```\n")
          :append true)
    (mapv #(select-keys % [:title :bytes]) results)))

(defn probe-prompt!
  "Save the current provider prompt and the real component rendering."
  []
  (let [connection (operator/connection "default") database @connection
        projection (schema/projection-from-database database)
        handle (:seon.turn.loop/cluster (get @runtime/running-instances "default"))]
    (schema/call-with-projection
     projection
     (fn []
       (let [configuration (config/effective database "default")
             raw (db/pull database '[{:seon.agent/plan [{:my.plan/steps [:my.plan.item/id :my.plan.item/position]}]}] [:seon.agent/id "juniper"])
             shown (value/render-ai {:seon.db/db database :seon.agent/id "juniper"
                                     :seon.render.call/id [:context-cookbook/position]
                                     :seon.sci.admit/caps (config/result-caps configuration)
                                     :seon.render/value raw
                                     :seon.render/profile (render/agent-render-profile configuration)})
             evaluations (evaluation/of-agent database "juniper")
             turn-id (:seon.turn/id (db/pull database [:seon.turn/id]
                                            (get-in (last evaluations) [:seon.cluster.eval/run :db/id])))
             prompt-result (render/acquire-context!
                            (merge handle {:seon.db/db database :seon.agent/id "juniper"
                                           :seon.turn/id turn-id
                                           :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)}))
             prompt (:seon.cluster.prompt/text prompt-result)
             result (cond-> {:component-shown shown :source-count (count evaluations)
                             :sources (mapv :seon.cluster.eval/source evaluations)}
                      (string? shown) (assoc :component-bytes (byte-count shown))
                      (string? prompt) (assoc :prompt-bytes (byte-count prompt))
                      (not (string? prompt)) (assoc :prompt-refusal prompt-result))]
         (when (string? prompt)
           (spit "docs/prds/context-generation/research/context_cookbook_prompt_2026_09_09.txt" prompt))
         (spit "docs/prds/context-generation/research/context_cookbook_render_2026_09_09.edn" (pr-str result))
         result)))))

(defn probe-result-projections!
  "Verify loaded report and set rendering functions without committing a write."
  []
  (let [connection (operator/connection "default")
        database @connection
        configuration (config/effective database "default")
        report (d/with database
                       [[:db/add "cookbook-result" :my.plan.item/id "cookbook/result"]
                        [:db/add "cookbook-result" :my.plan.item/title "Verify the output"]])
        result ((ns-resolve 'seon.db 'transaction-result) report)
        shown-report (pr-str result)
        shown (value/render-ai
               {:seon.db/db database :seon.agent/id "juniper"
                :seon.render.call/id [:context-cookbook/set]
                :seon.sci.admit/caps (config/result-caps configuration)
                :seon.render/value
                {:my.plan/steps #{{:my.plan.item/id "cookbook/a" :my.plan.item/position 2}
                                  {:my.plan.item/id "cookbook/b" :my.plan.item/position 1}}}
                :seon.render/profile (render/agent-render-profile configuration)})
        evidence {:report shown-report :report-bytes (byte-count shown-report)
                  :set-shown shown :set-bytes (byte-count shown)
                  :position-order? (< (str/index-of shown "cookbook/b")
                                      (str/index-of shown "cookbook/a"))
                  :set-preserved? (set? (:my.plan/steps (read-string shown)))
                  :default-unchanged? (= (db/basis-t database) (db/basis-t @connection))}]
    (spit "docs/prds/context-generation/research/context_cookbook_results_2026_09_09.edn"
          (pr-str evidence))
    evidence))

(defn- agent-form [form]
  (walk/postwalk
   (fn [value]
     (if (and (seq? value) (#{'seon.db/pull 'seon.db/q} (first value)))
       (apply list (remove #{'database} value))
       value))
   form))

(defn- agent-source [form]
  (repl/source-text (agent-form form)))

(defn recheck-agent-forms!
  "Execute bare reads beside explicit reads at one basis; speculate every write."
  []
  (let [connection (operator/connection "default")
        database @connection
        check-read
        (fn [database [title thought original]]
          (let [source (agent-source original)
                sink (atom [])
                explicit ((eval (list 'fn ['database] original)) database)
                bare (binding [db/*conn* connection db/*read-database* database
                               db/*read-evidence-sink* sink]
                       (eval (read-string source)))
                output (pr-str bare)]
            (assert (= explicit bare) (str "Source changed the result: " title))
            {:title title :thought thought :form source :output output
             :bytes (byte-count output) :unchanged? true
             :evidence (mapv #(if (seq (:seon.db/read-index-patterns %))
                                :index-patterns :attribute-level) @sink)}))
        read-results (mapv #(check-read database %) reads)
        speculative
        (reduce
         (fn [{:keys [database results]} [title thought tx]]
           (let [source (agent-source (list 'seon.db/transact! tx))
                 parsed-tx (second (read-string source))
                 _ (assert (= tx parsed-tx) (str "Source changed transaction data: " title))
                 report (d/with database parsed-tx)
                 output (pr-str ((ns-resolve 'seon.db 'transaction-result) report))]
             {:database (:db-after report)
              :results (conj results {:title title :thought thought :form source
                                      :output output :bytes (byte-count output)
                                      :unchanged? true})}))
         {:database (proposed-database database) :results []} writes)
        proposed-records
        (:reads (read-string (slurp "docs/prds/context-generation/research/context_cookbook_proposed_reads_2026_09_09.edn")))
        proposed-results
        (mapv #(check-read (:database speculative)
                          [(:title %) (:thought %) (read-string (:form %))])
              proposed-records)
        result {:basis (db/basis-t database) :reads read-results
                :writes (:results speculative) :proposed-reads proposed-results}]
    (spit "docs/prds/context-generation/research/context_cookbook_rechecked_2026_09_09.edn"
          (pr-str result))
    (mapv #(select-keys % [:title :bytes :unchanged?])
          (concat read-results (:results speculative) proposed-results))))

(defn probe-fault-blocks!
  "Verify root and routed-steward source using default and a speculative value."
  []
  (let [connection (operator/connection "default")
        database @connection
        projection (schema/projection-from-database database)]
    (schema/call-with-projection
     projection
     (fn []
       (let [routed (:db-after
                     (d/with database
                             [{:seon.error/id "cookbook/routed"
                               :seon.error/kind :seon.ai/no-credential
                               :seon.error/message "Verify repair routing."
                               :seon.error/agent [:seon.agent/id "root"]
                               :seon.error/steward [:seon.agent/id "juniper"]}]))
             records
             (mapv
              (fn [[label basis agent-id]]
                (let [source (error/render-faults-ai
                              {:seon.db/db basis
                               :seon.render.walk/attribute :seon.error/steward
                               :seon.render/value {:seon.error/steward [:seon.agent/id agent-id]}})]
                  (cond-> {:label label :emitted? (boolean source)}
                    source
                    (merge
                     (let [result (binding [db/*conn* connection db/*read-database* basis]
                                    (eval (read-string source)))
                           shown (pr-str result)]
                       {:source source :source-bytes (byte-count source)
                        :output shown :bytes (byte-count shown)})))))
              [["root-empty" database "root"]
               ["ordinary-empty" database "juniper"]
               ["routed-steward" routed "juniper"]])
             after @connection
             result {:basis (db/basis-t database) :records records
                     :default-basis-after (db/basis-t after)
                     :probe-id-absent? (nil? (:seon.error/id
                                             (db/pull after [:seon.error/id]
                                                      [:seon.error/id "cookbook/routed"])))
                     :default-unchanged? (= (db/basis-t database) (db/basis-t after))}]
         (spit "docs/prds/context-generation/research/context_cookbook_faults_2026_09_09.edn"
               (pr-str result))
         result)))))

(defn probe-identity-shape!
  "Compare the selected nested pull shape with the agent's shown value."
  []
  (let [database @(operator/connection "default")
        projection (schema/projection-from-database database)]
    (schema/call-with-projection
     projection
     (fn []
       (let [raw (db/pull database
                          '[:seon.agent/id {:seon.agent/namespace
                                           [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
                          [:seon.agent/id "juniper"])
             configuration (config/effective database "default")
             shown (value/render-ai
                    {:seon.db/db database :seon.agent/id "juniper"
                     :seon.render.call/id [:context-cookbook/identity-shape]
                     :seon.sci.admit/caps (config/result-caps configuration)
                     :seon.render/profile (render/agent-render-profile configuration)
                     :seon.render/value raw})
             result {:raw raw :shown shown :bytes (byte-count shown)
                     :basis (db/basis-t database)
                     :shape-preserved? (= raw (read-string shown))}]
         (assert (:shape-preserved? result))
         (spit "docs/prds/context-generation/research/context_cookbook_identity_2026_09_09.edn"
               (pr-str result))
         result)))))

(defn probe-directory!
  "Execute the agent's directory form and retain its shown text and evidence."
  []
  (let [connection (operator/connection "default")
        database @connection
        projection (schema/projection-from-database database)
        handle (:seon.turn.loop/cluster (get @runtime/running-instances "default"))]
    (schema/call-with-projection
     projection
     (fn []
       (let [source (repl/source-text '(dir my.agents.juniper))
             captured (atom [])
             evaluation
             (binding [db/*read-evidence-sink* captured]
               ((requiring-resolve 'seon.sci.eval/evaluate)
                {:seon.cluster.eval/source source
                 :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                 :seon.db/db database
                 :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                 :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms handle)
                 :seon.config/on-core-error :panic}))
             output (pr-str (:seon.sci.admit/value evaluation))
             shown (:seon.eval/value evaluation)
             result {:source source :source-bytes (byte-count source)
                     :output output :bytes (byte-count output)
                     :shown shown :shown-bytes (byte-count shown)
                     :evidence (mapv #(if (seq (:seon.db/read-index-patterns %))
                                       :index-patterns :attribute-level) @captured)
                     :basis (db/basis-t database)
                     :default-unchanged? (= (db/basis-t database) (db/basis-t @connection))}]
         (assert (not (:seon.cluster.eval/error evaluation)))
         (spit "docs/prds/context-generation/research/context_cookbook_directory_2026_09_09.edn"
               (pr-str result))
         result)))))

(defn probe-rendered-blocks!
  "Execute the exact generated block source against default without committing."
  []
  (let [connection (operator/connection "default") database @connection
        projection (schema/projection-from-database database)
        configuration (schema/call-with-projection projection #(config/effective database "default"))
        unit {:seon.db/db database :seon.agent/id "juniper"
              :seon.render.call/id [:context-cookbook/raw-block]
              :seon.sci.admit/caps (config/result-caps configuration)
              :seon.render/profile (render/agent-render-profile configuration)}]
    (schema/call-with-projection
     projection
     (fn []
       (let [records
             (mapv
              (fn [[label source]]
                (let [sink (atom [])
                      raw (binding [db/*conn* connection db/*read-database* database
                                    db/*read-evidence-sink* sink]
                            (eval (read-string source)))
                      shown (value/render-ai (assoc unit :seon.render/value raw))]
                  {:label label :source source :source-bytes (byte-count source)
                   :output (pr-str raw) :bytes (byte-count (pr-str raw))
                   :shown shown :shown-bytes (byte-count shown)
                   :evidence (mapv #(if (seq (:seon.db/read-index-patterns %))
                                     :index-patterns :attribute-level) @sink)}))
              [["Plan" (plan/render-plan-ai unit)]
               ["Settings" (agent/render-settings-ai unit)]
               ["Messages" (message/render-inbox-ai [:seon.agent/id "juniper"])]])
             result {:basis (db/basis-t database) :records records
                     :default-unchanged? (= (db/basis-t database) (db/basis-t @connection))}]
         (spit "docs/prds/context-generation/research/context_cookbook_blocks_2026_09_09.edn"
               (pr-str result))
         (mapv #(select-keys % [:label :source-bytes :bytes :shown-bytes :evidence]) records))))))

(defn probe-notes-help!
  "Execute the notes read and help's transaction-time example without a commit."
  []
  (let [connection (operator/connection "default") database @connection
        projection (schema/projection-from-database database)]
    (schema/call-with-projection
     projection
     (fn []
       (let [source (note/render-notes-ai {:seon.db/db database :seon.agent/id "juniper"})
             notes (binding [db/*conn* connection db/*read-database* database]
                     (eval (read-string source)))
             help (bootstrap/help-value database "juniper")
             handle (:seon.turn.loop/cluster (get @runtime/running-instances "default"))
             shown-help (:seon.eval/value
                         ((requiring-resolve 'seon.sci.eval/evaluate)
                          {:seon.cluster.eval/source "(help)" :seon.agent/id "juniper"
                           :seon.db/db database :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                           :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                           :seon.sci.eval/time-limit-ms 10000 :seon.config/on-core-error :panic}))
             line (nth help 10)
             write-form (read-string (subs line (str/index-of line "(seon.db/transact!")))
             report (d/with database (second write-form))
             time-form '(seon.db/pull '[:my.note/id {:my.note/about [:db/txInstant]}]
                                     [:my.note/id "observation"])
             time-value (binding [db/*conn* connection db/*read-database* (:db-after report)]
                          (eval time-form))
             record (fn [source output]
                      {:source source :source-bytes (byte-count source)
                       :output (pr-str output) :bytes (byte-count (pr-str output))})
             result {:basis (db/basis-t database)
                     :notes (record source notes)
                     :help (assoc (record "(help)" help)
                                  :shown shown-help :shown-bytes (byte-count shown-help))
                     :write (record (repl/source-text write-form)
                                    (#'db/transaction-result report))
                     :time (record (repl/source-text time-form) time-value)
                     :default-unchanged? (= (db/basis-t database) (db/basis-t @connection))}]
         (assert (inst? (get-in time-value [:my.note/about :db/txInstant])))
         (spit "docs/prds/context-generation/research/context_cookbook_notes_help_2026_09_09.edn"
               (pr-str result))
         (mapv (fn [key] [key (select-keys (get result key) [:source-bytes :bytes])])
               [:notes :help :write :time]))))))
