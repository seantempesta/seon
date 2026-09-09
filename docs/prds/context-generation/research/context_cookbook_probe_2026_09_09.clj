(ns context-cookbook-probe-2026-09-09
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.operator :as operator]))

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
        identities (set (d/q '[:find [?a ...] :where [?e :db/ident ?a] [?e :db/unique :db.unique/identity]] after))
        reference (fn [eid]
                    (or (some (fn [database]
                                (some (fn [datom] (when (identities (:a datom)) [(:a datom) (:v datom)]))
                                      (sort-by (comp str :a) (d/datoms database :eavt eid))))
                              [after before]) eid))]
    {:seon.db/tx (get (:tempids report) :db/current-tx)
     :seon.db/datoms (mapv (fn [datom] [(reference (:e datom)) (:a datom) (:v datom) (:added datom)]) (:tx-data report))}))

(defn- byte-count [text] (alength (.getBytes ^String text "UTF-8")))

(defn run-probe!
  "Execute the chart reads and speculative writes on default, retaining exact output bytes."
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

(defn write-cookbook!
  "Render retained results without re-executing their forms."
  []
  (let [result (read-string (slurp "docs/prds/context-generation/research/context_cookbook_probe_2026_09_09.edn"))
        section (fn [{:keys [title thought form output bytes evidence executed]}]
                  (str "\n## " title "\n\n```clojure\n;; " thought "\n" form "\n```\n\n"
                       (when executed (str "Executed in the speculative chain: `" executed "`.\n\n"))
                       "Actual " (if executed "proposed report projection" "JVM result") " (" bytes " UTF-8 bytes"
                       (when evidence (str "; evidence " (pr-str evidence))) "):\n\n```clojure\n" output "\n```\n"))]
    (spit "docs/prds/context-generation/research/context-cookbook-2026-09-09.md"
          (str "---\ntype: research\nstatus: working\ntags: [agent-context, repl, render]\n---\n\n# Context cookbook — executed 2026-09-09\n\n"
               "Read end to end: AGENTS.md (its opening copies turn PRD §10), the data chart r2 including its roadmap, raw-data-forms-probe-2026-09-09.md, and turn PRD §18–§18c. The plan README and working edge ground this slice.\n\n"
               "Surface: MCP, cluster `default`, JVM mode, explicit `(seon.operator/connection \"default\")`; each read receives `database = @connection`. Basis " (:basis result) ". No database write was committed. Counts measure exact `pr-str` results, without a REPL envelope; they are not yet provider-prompt byte counts. The checked-in probe and EDN retain the complete forms and results.\n\n"
               "The target schema is absent on default. The writes below actually ran through `datahike.api/with` on a speculative value derived from default, after the probe installed the explicitly listed proposed attributes there. These establish dependency semantics, not production schema validation, wake delivery, or live target-schema adoption. Event ids are fixed probe inputs; real messages mint fresh event ids.\n\n"
               "## Read choice and evidence\n\nPull describes one known entity or a known set, including nested and reverse refs. Use q for value filters, joins, and aggregates; combine q with inner pull when both filtering and shaping. The live reverse message pull recorded index patterns and returned the incoming messages in one form. Pattern-only aggregate q also recorded index patterns. Inner pull and not recorded attribute-level evidence: correct but coarse. Explicit finite selectors recorded index patterns; wildcard/recursive selectors cannot make that claim. Source: src/seon/db.clj:335–423. Index-pattern presence means constraints at each pattern, not a fully joined result dependency. An invalid read is a refusal, never an empty healthy block.\n\n"
               "## Dependency ledger\n\nDatahike transaction.cljc:640 (identity upsert), :738 (nested maps), :785 (cardinality-one replacement), :997 and :1059 (retractEntity versus retract); pull_api.cljc:304 (reverse refs and component collections). First-party idioms: seon.plan/render-plan-html resolves the plan owner; seon.agent/settings! consumes the full system transaction report; seon.db:335–423 captures read patterns. `datahike.api` has no entid function: use an identity pull.\n\n"
               "## Transaction report proposal — pending db.clj ownership\n\n`src/seon/db.clj` is held by transact-feedback. The executable `report-face` hunk in the adjacent probe is the proposed agent-facing projection: `{:seon.db/tx t :seon.db/datoms [[e a v added?] ...]}`. It resolves e to an installed unique-identity lookup ref, consulting db-before for deleted identities; tempids are already resolved in tx-data. The next read is the db-after; tx-data already says what changed. Neither db-before nor db-after belongs in shown text. Keep the full report for system callers such as seon.agent/settings! that still consume db-after. Ref-valued identities currently print their resolved numeric target; the production face should recursively use the target identity with cycle protection.\n\n"
               "## Verification boundary\n\nThe first batch exceeded the MCP 20-second request bound but completed and wrote its evidence file; a second session returned `(+ 1 1)` in 1 ms. No alternate transport was used. Default remains untouched. Schema-dependent blocks and reseeding require the chart data lane's landing and the owner's batched reset; RESET NEEDED when that schema commit is known.\n"
               (str/join (map section (:reads result)))
               "\n# Speculative writes\n"
               (str/join (map section (:writes result)))))))
