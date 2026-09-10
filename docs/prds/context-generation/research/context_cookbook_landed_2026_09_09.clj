(ns context-cookbook-landed-2026-09-09
  (:refer-clojure :exclude [bytes])
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.eval :as evaluation]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]))

(def directory "docs/prds/context-generation/research/")
(defn bytes "Count exact UTF-8 bytes." [text] (alength (.getBytes text "UTF-8")))

(defn write-examples "Return the nine agent-authored write forms." []
  (let [[add remove] ((resolve 'context-page-probe-2026-09-09/plan-examples))]
    [["Add a step" "I should update the existing component by db/id; an identity-less nested map silently replaces it." add]
     ["Complete a step" "I have seen the result; with no clock function I record completion through datomic.tx."
      '(seon.db/transact! [[:db/add [:my.plan.item/id "juniper/read"] :my.plan.item/completed-tx "datomic.tx"]])]
     ["Make current" "I should make the define step current by the plan's owner identity."
      '(seon.db/transact! [[:db/add [:my.plan/agent [:seon.agent/id "juniper"]] :my.plan/current-step [:my.plan.item/id "juniper/define"]]])]
     ["Remove a step" "I should remove the child and incoming refs with retractEntity; retract alone removes only one fact." remove]
     ["Send a message" "I should set inbox as well as to so the new message wakes its recipient."
      '(seon.db/transact! [{:seon.message/id "c00cb001" :seon.message/to [:seon.agent/id "root"] :seon.message/inbox [:seon.agent/id "root"] :seon.message/from [:seon.agent/id "juniper"] :seon.message/content "Ada totals 115."}])]
     ["Answer a message" "I should link my answer and clear the question's inbox edge in the same transaction."
      '(let [question (-> (seon.db/pull '[{:seon.message/_inbox [:seon.message/id]}]
                                       [:seon.agent/id "juniper"])
                          :seon.message/_inbox first :seon.message/id)]
         (seon.db/transact!
          [{:seon.message/id "c00cb002" :seon.message/to [:seon.agent/id "root"]
            :seon.message/inbox [:seon.agent/id "root"] :seon.message/from [:seon.agent/id "juniper"]
            :seon.message/content "Ada totals 115 before the additional order."
            :seon.message/about [:seon.message/id question]}
           [:db/add [:seon.message/id question] :seon.message/read-tx "datomic.tx"]
           [:db/retract [:seon.message/id question] :seon.message/inbox [:seon.agent/id "juniper"]]]))]
     ["Change a setting" "I should address my settings component by identity to preserve its other overrides."
      '(seon.db/transact! [{:seon.config/agent [:seon.agent/id "juniper"] :seon.config.eval/time-limit-ms 2500}])]
     ["Declare a listen" "I should add an attribute pattern to the runtime's listens."
      '(seon.db/transact! [{:seon.runtime/agent [:seon.agent/id "juniper"] :seon.runtime/listens [{:seon.listen/attribute :example/amount}]}])]
     ["Transact a note" "I should save the observed total and link the note to the completed read step."
      '(seon.db/transact! [{:my.note/id "juniper/orders-observed" :my.note/agent [:seon.agent/id "juniper"] :my.note/about [:my.plan.item/id "juniper/read"] :my.note/content "Ada totals 115 before the additional order."}])]]))

(defn probe! "Execute the fixture reads and speculate writes without committing." [cluster-name]
  (let [connection (operator/connection cluster-name)
        database @connection
        handle (:seon.turn.loop/cluster (get @runtime/running-instances cluster-name))]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [rows (evaluation/of-agent database "juniper")
             _ (assert (seq rows))
             root-source (agent/render-identity-ai {:seon.db/db database :seon.agent/id "root"})
             root-agents (subs root-source (+ 2 (str/last-index-of root-source "\n\n")))
             root-faults (error/render-faults-ai
                          {:seon.db/db database :seon.render.walk/attribute :seon.error/steward
                           :seon.render/value {:seon.error/steward [:seon.agent/id "root"]}})
             reads (into (mapv (fn [row] [(:seon.cluster.eval/comment row) (:seon.cluster.eval/source row)]) rows)
                         [[";; I should inspect my stored evaluations only when needed; the prompt already is my history."
                           "(->> (seon.eval/of-agent) (mapv #(select-keys % [:seon.cluster.eval/ordinal :seon.cluster.eval/source])))"]
                          [nil root-faults]
                          [nil root-agents]
                          [";; I should group customer values and sum amounts with q."
                           (repl/source-text '(seon.db/q '[:find ?customer (sum ?amount) :where [?e :example/customer ?customer] [?e :example/amount ?amount]]))]])
             ctx (:seon.sci.eval/ctx (sci.eval/fork-for-turn
                                     (assoc handle :seon.db/db database :seon.agent/id "juniper")))
             results
             (mapv (fn [[comment source]]
                     (let [sink (atom [])
                           result (binding [db/*read-evidence-sink* sink]
                                    (sci.eval/evaluate
                                   (assoc handle :seon.sci.eval/ctx ctx :seon.db/db database
                                          :seon.agent/id "juniper" :seon.cluster.eval/source source
                                          :seon.sci.admit/caps (config/result-caps (config/effective database cluster-name))
                                          :seon.sci.eval/time-limit-ms 10000)))
                           shown (:seon.eval/shown result)]
                       (assert (not (:seon.cluster.eval/error result)) (pr-str (select-keys result [:seon.cluster.eval/error :seon.eval/shown])))
                       {:comment comment :source source :source-bytes (bytes source)
                        :shown shown :bytes (bytes shown)
                        :evidence (mapv #(if (seq (:seon.db/read-index-patterns %)) :index-patterns :attribute-level)
                                        @sink)})) reads)
             writes
             (reduce (fn [{:keys [database results]} [title thought form]]
                       (let [source (repl/source-text form)
                             parsed (read-string source)
                             _ (assert (= form parsed))
                             speculative (walk/postwalk
                                          (fn [x] (if (and (seq? x) (= 'seon.db/transact! (first x)))
                                                    (list 'datahike.api/with 'database (second x)) x)) parsed)
                             report (binding [db/*conn* connection db/*read-database* database]
                                      ((eval (list 'fn ['database] speculative)) database))
                             _ (assert (:db-after report))
                             shown (pr-str (#'db/transaction-result report))]
                         {:database (:db-after report)
                          :results (conj results {:title title :comment (str ";; " thought)
                                                 :source source :source-bytes (bytes source)
                                                 :shown shown :bytes (bytes shown)})}))
                     {:database database :results []} (write-examples))
             record {:cluster cluster-name :basis (db/basis-t database)
                     :reads results :writes (:results writes)
                     :fixture-unchanged? (= (db/basis-t database) (db/basis-t @connection))
                     :score :unavailable}]
         (assert (:fixture-unchanged? record))
         (spit (str directory "context_cookbook_landed_2026_09_09.edn") (pr-str record))
         (mapv #(select-keys % [:title :source-bytes :bytes :evidence])
               (concat results (:results writes))))))))
