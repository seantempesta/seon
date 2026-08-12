(ns rebirth.probe
  "Probe-only capability proof for rebirth as fact-backed compaction."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.loop :as loop]
            [seon.cluster.registry :as registry]
            [seon.cluster.reply :as reply]
            [seon.cluster.run :as run]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render.transcript :as transcript]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support])
  (:import [java.util Date UUID]))

(def ^:private agent-id "rebirth")
(def ^:private namespace-name 'my.agents.rebirth)
(def ^:private process-id "rebirth-capability-proof")
(def ^:private root-user [:seon.cluster.agent/id "root"])
(def ^:private repl-process [:seon.db.process/id :seon.db.process/repl])
(def ^:private base-instant-ms 1786500000000)

(defn- at [offset]
  (Date. (+ base-instant-ms offset)))

(defn- digest [value]
  (schema/sha-256 [(.getBytes (pr-str value) "UTF-8")]))

(defn- error-value? [value]
  (and (map? value) (contains? value :seon.error/kind)))

(defn- await-fact!
  [connection label probe]
  (let [events (async/promise-chan)
        listener-key (keyword "rebirth.probe" (str (UUID/randomUUID)))]
    (d/listen connection listener-key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (let [[value selected]
            (async/alts!! [events (async/timeout 180000)] :priority true)]
        (when-not (= selected events)
          (throw (ex-info (str "Timed out awaiting " label ".")
                          {:rebirth.probe/label label})))
        value)
      (finally
        (d/unlisten connection listener-key)))))

(defn- closed-run
  [database run-id]
  (db/pull database
           [:seon.cluster.run/id :seon.cluster.run/closed-at]
           [:seon.cluster.run/id run-id]))

(defn- await-closed!
  [connection run-id]
  (await-fact! connection (str "closed run " run-id)
               (fn [database]
                 (let [candidate (closed-run database run-id)]
                   (when (:seon.cluster.run/closed-at candidate)
                     candidate)))))

(defn- execute-planned-run!
  [connection cluster-handle run-id]
  (loop [passes 0]
    (when (> passes 200)
      (throw (ex-info "Planned run exceeded the probe pass bound."
                      {:seon.cluster.run/id run-id})))
    (when-let [next-work
               (work/next-agent-work
                @connection
                {:seon.cluster.agent/id agent-id
                 :seon.cluster.run/process process-id})]
      (when (= run-id (:seon.cluster.run/id next-work))
        (loop/turn {:seon.cluster.loop/cluster cluster-handle
                    :seon.cluster.work/next next-work}
                   (at (+ 10000 passes)))
        (recur (inc passes))))))

(defn- open-agent-run!
  [instance run-id message-id reply-text offset]
  (let [connection (:seon.boot/cluster-connection instance)
        cluster-handle (assoc (:seon.cluster.loop/cluster instance)
                              :seon.cluster.run/process process-id)
        sources (reply/sources reply-text namespace-name)]
    (when (error-value? sources)
      (throw (ex-info "Probe reply did not parse." sources)))
    (let [opened
          (db/transact!
           connection
           {:tx-data
            (into [] cat
                  [(run/open-tx
                    {:seon.cluster.run/id run-id
                     :seon.cluster.run/agent
                     [:seon.cluster.agent/id agent-id]
                     :seon.cluster.run/trigger
                     [:seon.cluster.message/id message-id]
                     :seon.cluster.run/opened-at (at offset)})
                   (run/claim-tx
                    {:seon.cluster.run/id run-id
                     :seon.cluster.run/process process-id
                     :seon.cluster.run/live-processes #{process-id}
                     :seon.cluster.run/now (at offset)})
                   (run/plan-tx
                    {:seon.cluster.run/id run-id
                     :seon.cluster.run/process process-id
                     :seon.cluster.run/starting-ns
                     [:seon.ns/name namespace-name]
                     :seon.cluster.run/plan-digest (digest sources)
                     :seon.cluster.run/sources sources})])})]
      (when (error-value? opened)
        (throw (ex-info "Probe run open was refused." opened)))
      (execute-planned-run! connection cluster-handle run-id)
      (await-closed! connection run-id)
      run-id)))

(defn- message!
  [connection message-id content offset]
  (let [result
        (db/transact!
         connection
         {:tx-data
          [{:seon.cluster.message/id message-id
            :seon.cluster.message/ordinal (long offset)
            :seon.cluster.message/from root-user
            :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
            :seon.cluster.message/content content
            :seon.cluster.message/at (at offset)}]
          :tx-meta {:seon.db/user root-user
                    :seon.db/process repl-process}})]
    (when (error-value? result)
      (throw (ex-info "Probe message transaction was refused." result)))
    (db/basis-t (:db-after result))))

(def ^:private item-map
  "[:map [:probe.rebirth.plan.item/id :probe.rebirth.plan.item/id] [:probe.rebirth.plan.item/text :probe.rebirth.plan.item/text] [:probe.rebirth.plan.item/status :probe.rebirth.plan.item/status] [:probe.rebirth.plan.item/completed-at {:optional true} :probe.rebirth.plan.item/completed-at]]")

(def ^:private plan-map
  (str "[:map [:probe.rebirth.plan/id :probe.rebirth.plan/id] "
       "[:probe.rebirth.plan/title :probe.rebirth.plan/title] "
       "[:probe.rebirth.plan/agent :probe.rebirth.plan/agent] "
       "[:probe.rebirth.plan/items [:vector " item-map "]]]"))

(defn- artifact-reply []
  (str
   "The durable shape comes first. Prose-only rationale: I want the future "
   "agent to remember that status transitions mattered more than chronology.\n"
   "(seon.schema/register! :probe.rebirth.plan/id [:string {:seon.db/identity true}])\n"
   "(seon.schema/register! :probe.rebirth.plan/title :string)\n"
   "(seon.schema/register! :probe.rebirth.plan/agent :seon.db/ref)\n"
   "(seon.schema/register! :probe.rebirth.plan/items [:vector {:seon.db/component true} :seon.db/ref])\n"
   "(seon.schema/register! :probe.rebirth.plan.item/id [:string {:seon.db/identity true}])\n"
   "(seon.schema/register! :probe.rebirth.plan.item/text :string)\n"
   "(seon.schema/register! :probe.rebirth.plan.item/status [:enum :pending :active :done])\n"
   "(seon.schema/register! :probe.rebirth.plan.item/completed-at :inst)\n"
   "(defn ^{:malli/schema [:=> [:cat " plan-map "] [:vector " item-map "]]} current-items [plan] (->> (:probe.rebirth.plan/items plan) (remove #(= :done (:probe.rebirth.plan.item/status %))) (sort-by :probe.rebirth.plan.item/id) vec))\n"
   "(defn ^{:malli/schema [:=> [:cat " plan-map "] :seon.render/ai]} render-plan-ai [plan] (let [items (:probe.rebirth.plan/items plan) remaining (current-items plan) completed (->> items (filter #(= :done (:probe.rebirth.plan.item/status %))) (sort-by :probe.rebirth.plan.item/completed-at #(compare %2 %1))) recent (take 2 completed) older (drop 2 completed)] (str \"Plan \" (:probe.rebirth.plan/title plan) \".\\nRemaining:\\n\" (apply str (map #(str \"- \" (:probe.rebirth.plan.item/id %) \" — \" (:probe.rebirth.plan.item/text %) \"\\n\") remaining)) \"Recent completions:\\n\" (apply str (map #(str \"- \" (:probe.rebirth.plan.item/id %) \" — \" (:probe.rebirth.plan.item/text %) \"\\n\") recent)) (when (seq older) (str \"... \" (count older) \" older completions; requery with (db/q '[:find ?id ?at :where [?item :probe.rebirth.plan.item/id ?id] [?item :probe.rebirth.plan.item/status :done] [?item :probe.rebirth.plan.item/completed-at ?at]] (db/db)).\")))))\n"
   "(defn ^{:malli/schema [:=> [:cat :my.agents.rebirth/namespace-unit] :seon.render/ai]} render-namespace-ai [unit] (str \"Namespace \" (:seon.ns/name unit) \" owns a fact-backed plan, current-items, render-plan-ai, and plan-current-state-test.\"))\n"
   "(seon.schema/register! :my.agents.rebirth/namespace-unit [:map {:seon.render/ai my.agents.rebirth/render-namespace-ai} [:seon.ns/name [:= my.agents.rebirth]] [:seon.ns/doc {:optional true} :seon.ns/doc]])\n"
   "(seon.schema/register! :probe.rebirth.plan/plan [:map {:seon.db/entity true :seon.render/ai my.agents.rebirth/render-plan-ai} [:probe.rebirth.plan/id :probe.rebirth.plan/id] [:probe.rebirth.plan/title :probe.rebirth.plan/title] [:probe.rebirth.plan/agent :probe.rebirth.plan/agent] [:probe.rebirth.plan/items [:vector " item-map "]]])\n"
   "(clojure.test/deftest ^{:seon.test/usage true} plan-current-state-test (clojure.test/is (= [\"remaining\"] (mapv :probe.rebirth.plan.item/id (current-items {:probe.rebirth.plan/id \"sample\" :probe.rebirth.plan/title \"sample\" :probe.rebirth.plan/agent 1 :probe.rebirth.plan/items [{:probe.rebirth.plan.item/id \"done\" :probe.rebirth.plan.item/text \"done\" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst \"2026-08-12T00:00:00.000-00:00\"} {:probe.rebirth.plan.item/id \"remaining\" :probe.rebirth.plan.item/text \"remaining\" :probe.rebirth.plan.item/status :pending}]})))))\n"
   "(seon.test/run #'plan-current-state-test)\n"
   "(my.run/complete \"Declared the plan facts, current-state function, plan and namespace renders, and a green usage test.\")"))

(defn- delta-form [shown-basis]
  (str
   "(db/q {:query '[:find ?id ?tx ?user-id :in $current $delta ?agent-id "
   ":where [$current ?agent :seon.cluster.agent/id ?agent-id] "
   "[$delta ?message :seon.cluster.message/to ?agent ?tx] "
   "[$current ?message :seon.cluster.message/id ?id] "
   "[$delta ?tx :seon.db/user ?user] "
   "[$current ?user :seon.cluster.agent/id ?user-id]] "
   ":args [(db/db) (db/since (db/db) " shown-basis ") "
   (pr-str agent-id) "]})"))

(defn- plan-create-reply [shown-basis]
  (str
   "I am choosing six explicit items because the hidden rationale should not survive unless modeled.\n"
   (delta-form shown-basis) "\n"
   "(my.message/read \"rebirth-message-1\")\n"
   "(db/transact! [{:probe.rebirth.plan/id \"rebirth-plan\" :probe.rebirth.plan/title \"Prove rebirth compacts state\" :probe.rebirth.plan/agent [:seon.cluster.agent/id \"rebirth\"] :probe.rebirth.plan/items [{:probe.rebirth.plan.item/id \"i1\" :probe.rebirth.plan.item/text \"Audit current my.plan\" :probe.rebirth.plan.item/status :active} {:probe.rebirth.plan.item/id \"i2\" :probe.rebirth.plan.item/text \"Capture lived deltas\" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id \"i3\" :probe.rebirth.plan.item/text \"Author durable artifacts\" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id \"i4\" :probe.rebirth.plan.item/text \"Prove current-state rendering\" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id \"i5\" :probe.rebirth.plan.item/text \"Compare deterministic rebirths\" :probe.rebirth.plan.item/status :pending} {:probe.rebirth.plan.item/id \"i6\" :probe.rebirth.plan.item/text \"Record implementation deltas\" :probe.rebirth.plan.item/status :pending}]}])\n"
   "(my.run/complete \"Created the six-item fact-backed plan.\")"))

(defn- plan-refine-reply [shown-basis]
  (str
   "The prose-only insight this turn is that i4 is the conceptual center; this sentence intentionally has no fact.\n"
   (delta-form shown-basis) "\n"
   "(my.message/read \"rebirth-message-2\")\n"
   "(db/transact! [{:probe.rebirth.plan/id \"rebirth-plan\" :probe.rebirth.plan/title \"Prove rebirth compacts fact-backed state\"} {:probe.rebirth.plan.item/id \"i1\" :probe.rebirth.plan.item/text \"Audit current my.plan facts, status fields, timestamps, and renders\" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst \"2026-08-12T01:00:00.000-00:00\"} {:probe.rebirth.plan.item/id \"i2\" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst \"2026-08-12T02:00:00.000-00:00\"} {:probe.rebirth.plan.item/id \"i3\" :probe.rebirth.plan.item/status :active}])\n"
   "(my.run/complete \"Refined the plan and completed i1 and i2.\")"))

(defn- plan-finish-reply [shown-basis]
  (str
   "My final prose-only reasoning is that a declared render is the compaction algorithm. It should disappear.\n"
   (delta-form shown-basis) "\n"
   "(my.message/read \"rebirth-message-3\")\n"
   "(db/transact! [{:probe.rebirth.plan.item/id \"i3\" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst \"2026-08-12T03:00:00.000-00:00\"} {:probe.rebirth.plan.item/id \"i4\" :probe.rebirth.plan.item/status :done :probe.rebirth.plan.item/completed-at #inst \"2026-08-12T04:00:00.000-00:00\"} {:probe.rebirth.plan.item/id \"i5\" :probe.rebirth.plan.item/text \"Byte-compare two empty-history derivations\" :probe.rebirth.plan.item/status :active} {:probe.rebirth.plan.item/id \"i6\" :probe.rebirth.plan.item/status :pending}])\n"
   "missing-plan-helper\n"
   "(current-items :wrong-shape)\n"
   "(my.run/complete \"Completed i3 and i4; i5 is active and i6 remains.\")"))

(defn- raw-run-history [database]
  (db/q
   {:query
    '[:find ?run-id ?opened ?ordinal ?author ?source ?result ?error
      :in $ ?agent-id
      :where
      [?agent :seon.cluster.agent/id ?agent-id]
      [?run :seon.cluster.run/agent ?agent]
      [?run :seon.cluster.run/id ?run-id]
      [?run :seon.cluster.run/opened-at ?opened]
      [?form :seon.cluster.run.form/run ?run]
      [?form :seon.cluster.run.form/ordinal ?ordinal]
      [?form :seon.cluster.run.form/author ?author]
      [?form :seon.cluster.run.form/source ?source]
      [?receipt :seon.cluster.eval/run ?run]
      [(get-else $ ?receipt :seon.cluster.eval/result-edn "") ?result]
      [(get-else $ ?receipt :seon.cluster.eval/error "") ?error]
      [(get-else $ ?receipt :seon.cluster.eval/ordinal -1) ?receipt-ordinal]
      [(= ?receipt-ordinal ?ordinal)]]
    :args [database agent-id]
    :order-by '[?opened :asc ?ordinal :asc]}))

(defn- transcript-entries [instance database]
  (let [cluster-handle (:seon.cluster.loop/cluster instance)
        settings (config/effective database
                                   (get-in instance [:seon.boot/config
                                                     :seon.boot/cluster-name]))]
    (schema/call-with-projection-state
     (get-in cluster-handle
             [:seon.sci.eval/ctx :seon.sci.eval/projection-state])
     #(transcript/history-entries
       {:seon.db/db database
        :seon.db/connection (:seon.boot/cluster-connection instance)
        :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster-handle)
        :seon.cluster.agent/id agent-id
        :seon.render/profile {:seon.render.profile/token-budget 1000000}
        :seon.sci.admit/caps (config/result-caps settings)
        :seon.config/on-core-error :record}))))

(defn- run-generated-rebirth!
  [instance branch run-id]
  (let [store-value (:seon.store/store instance)
        basis (db/commit-id @(:seon.boot/cluster-connection instance))]
    (registry/branch! {:seon.store/store store-value
                       :seon.cluster.registry/from basis
                       :seon.store/branch branch})
    (let [connection (store/open-branch! store-value branch)]
      (try
        (let [ctx (sci.eval/cluster-ctx @connection connection)
              cluster-handle
              (assoc (:seon.cluster.loop/cluster instance)
                     :seon.db/connection connection
                     :seon.sci.eval/ctx ctx
                     :seon.cluster.run/process process-id)
              opened
              (db/transact!
               connection
               (run/generated-run-tx
                @connection
                {:seon.cluster.agent/id agent-id
                 :seon.cluster.run/id run-id
                 :seon.cluster.run/process process-id
                 :seon.cluster.run/opened-at (at 90000)
                 :seon.cluster.run/starting-ns [:seon.ns/name namespace-name]
                 :seon.cluster.run.form/source
                 (bootstrap/entry-source
                  {:seon.repl/comment "; Reborn from current facts with empty history."
                   :seon.repl/form '(help)})}))]
          (when (error-value? opened)
            (throw (ex-info "Rebirth run open was refused." opened)))
          (loop [passes 0]
            (when (> passes 200)
              (throw (ex-info "Rebirth exceeded the probe pass bound."
                              {:seon.cluster.run/id run-id})))
            (when-let [next-work
                       (work/next-agent-work
                        @connection
                        {:seon.cluster.agent/id agent-id
                         :seon.cluster.run/process process-id})]
              (when (= run-id (:seon.cluster.run/id next-work))
                (if (= :call (:seon.cluster.work/situation next-work))
                  :generated
                  (do
                    (loop/turn {:seon.cluster.loop/cluster cluster-handle
                                :seon.cluster.work/next next-work}
                               (at (+ 90000 passes)))
                    (recur (inc passes)))))))
          {:rebirth.probe/run-id run-id
           :rebirth.probe/history (raw-run-history @connection)})
        (finally
          (store/release-branch! connection)
          (registry/retire-branch! {:seon.store/store store-value
                                    :seon.store/branch branch}))))))

(defn- program-graph-evidence [database]
  {:rebirth.probe/functions
   (db/q '[:find ?sym ?spec
           :in $ ?namespace
           :where
           [?ns :seon.ns/name ?namespace]
           [?fn :seon.fn/ns ?ns]
           [?fn :seon.fn/sym ?sym]
           [?fn :seon.fn/spec ?spec]]
         database namespace-name)
   :rebirth.probe/tests
   (db/q '[:find ?sym ?pass ?fail ?error
           :in $ ?namespace
           :where
           [?ns :seon.ns/name ?namespace]
           [?test :seon.test/ns ?ns]
           [?test :seon.test/sym ?sym]
           [(get-else $ ?test :seon.test/pass-count 0) ?pass]
           [(get-else $ ?test :seon.test/fail-count 0) ?fail]
           [(get-else $ ?test :seon.test/error-count 0) ?error]]
         database namespace-name)
   :rebirth.probe/declared-schema-forms
   (db/q '[:find ?schema-key ?form
           :in $ [?schema-key ...]
           :where
           [?schema :seon.schema/key ?schema-key]
           [?schema :seon.schema/form ?form]]
         database
         [:probe.rebirth.plan/plan :my.agents.rebirth/namespace-unit])})

(defn- supersedes-evidence [database]
  {:rebirth.probe/installed?
   (contains? (:schema database) :seon.cluster.run/supersedes)
   :rebirth.probe/schema
   (db/pull database
            [:seon.schema/key :seon.schema/form]
            [:seon.schema/key :seon.cluster.run/supersedes])})

(defn run-proof!
  "Run the isolated rebirth capability probe and return its evidence map."
  [root]
  (support/populate-published-root! root)
  (let [cluster-name "rebirth-capability-proof"
        instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                  :seon.boot/root root})]
    (try
      (let [connection (:seon.boot/cluster-connection instance)
            process (cluster/process-identity
                     (:seon.boot/advertisement instance))]
        (await-closed! connection (bootstrap/run-id "root"))
        (cluster/ensure-entity!
         connection process
         {:seon.cluster.agent/id agent-id
          :seon.cluster/name cluster-name
          :seon.ns/name namespace-name})
        (await-closed! connection (bootstrap/run-id agent-id))
        (agent/disarm! {:seon.cluster.agent/id agent-id
                        :seon.cluster.agent/routing
                        (:seon.cluster.agent/routing instance)})
        (let [basis-0 (message! connection "rebirth-artifacts"
                                "Declare the fact-backed plan and its artifacts."
                                1000)]
          (open-agent-run! instance "rebirth-artifacts-run"
                           "rebirth-artifacts" (artifact-reply) 1100)
          (let [basis-1 (message! connection "rebirth-message-1"
                                  "Create the initial plan items." 2000)]
            (open-agent-run! instance "rebirth-plan-turn-1"
                             "rebirth-message-1"
                             (plan-create-reply basis-0) 2100)
            (let [basis-2 (message! connection "rebirth-message-2"
                                    "Refine the plan and complete early items."
                                    3000)]
              (open-agent-run! instance "rebirth-plan-turn-2"
                               "rebirth-message-2"
                               (plan-refine-reply basis-1) 3100)
              (message! connection "rebirth-message-3"
                        "Complete the centerpiece and leave follow-up work."
                        4000)
              (open-agent-run! instance "rebirth-plan-turn-3"
                               "rebirth-message-3"
                               (plan-finish-reply basis-2) 4100))))
        (let [database @connection
              lived (raw-run-history database)
              rendered-lived (transcript-entries instance database)
              first-rebirth (run-generated-rebirth!
                             instance :rebirth-proof-a "rebirth-proof-a")
              second-rebirth (run-generated-rebirth!
                              instance :rebirth-proof-b "rebirth-proof-b")
              normalized
              (fn [proof]
                (let [own-id (:rebirth.probe/run-id proof)]
                  (mapv (fn [row]
                          (mapv #(if (string? %)
                                   (str/replace % own-id "rebirth")
                                   %)
                                (assoc (vec row) 0 "rebirth")))
                        (:rebirth.probe/history proof))))
              first-bytes (pr-str (normalized first-rebirth))
              second-bytes (pr-str (normalized second-rebirth))
              evidence
              {:rebirth.probe/root root
               :rebirth.probe/basis-t (db/basis-t database)
               :rebirth.probe/lived-history lived
               :rebirth.probe/rendered-lived-history rendered-lived
               :rebirth.probe/lived-tokens
               (tokens/estimate
                (str/join "\n\n" (map :seon.render.history/bytes
                                      rendered-lived)))
               :rebirth.probe/rebirth first-rebirth
               :rebirth.probe/rebirth-tokens (tokens/estimate first-bytes)
               :rebirth.probe/deterministic? (= first-bytes second-bytes)
               :rebirth.probe/program-graph
               (program-graph-evidence database)
               :rebirth.probe/supersedes (supersedes-evidence database)
               :rebirth.probe/current-plan
               (db/pull database
                        '[* {:probe.rebirth.plan/items [*]}]
                        [:probe.rebirth.plan/id "rebirth-plan"])}]
          (spit (io/file root "rebirth-evidence.edn")
                (str (pr-str evidence) "\n"))
          evidence))
      (finally
        (cluster/stop! instance)))))

(defn -main
  "Run the probe at `root` and print its compact outcome."
  [& [root]]
  (let [root (or root "tmp/rebirth/scratch-root")
        evidence (run-proof! root)]
    (println
     (pr-str
      (select-keys evidence
                   [:rebirth.probe/root
                    :rebirth.probe/basis-t
                    :rebirth.probe/lived-tokens
                    :rebirth.probe/rebirth-tokens
                    :rebirth.probe/deterministic?])))))
