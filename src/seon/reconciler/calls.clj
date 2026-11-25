(ns seon.reconciler.calls
  "Call delivery, auto-settlement, and merge-before-deliver synchronization for `seon.reconciler`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.store :as store]
            [seon.task.gate :as gate]))

(defn latest-prose-after-msg
  {:malli/schema [:=> [:cat [:sequential :map] :any] [:maybe :string]], :cost/ticks 500000}
  [eval-ents msg-id]
  (when msg-id
    (let [after (drop-while #(not (contains? (:seon.agent.eval/seen-messages %) msg-id)) eval-ents)
          prose (->> after
                     (filter #(and (str/blank? (str (:seon.agent.eval/code %)))
                                   (not (str/blank? (str (:seon.agent.eval/thought %))))))
                     (map :seon.agent.eval/thought)
                     last)]
      prose)))

(defn ^:seon.flow/edge safe-branch-head
  {:malli/schema [:=> [:cat :any :seon.host/db [:maybe :keyword]] :seon.host/db], :seon.flow/edge true}
  [store main-db b]
  (if (or (nil? b) (= b :main))
    main-db
    (or (try (store/head store b) (catch Throwable _ nil)) main-db)))

(def ^:private call-pull-pat
  (quote [:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/callee
          :seon.agent.call/message-id :seon.agent.call/result-schema
          :seon.agent.call/status :seon.agent.call/delivered?]))

(defn pull-call-by-id
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] [:maybe :map]], :cost/ticks 1000000}
  [db cid]
  (when (and db cid (contains? (:schema db) :seon.agent.call/id)
             (first (d/datoms db :avet :seon.agent.call/id cid)))
    (d/pull db call-pull-pat [:seon.agent.call/id cid])))

(defn- pull-calls-by-attr [db attr val]
  (if (and db (contains? (:schema db) attr))
    (mapv #(d/pull db call-pull-pat (:e %)) (d/datoms db :avet attr val))
    []))

(defn compute-auto-settle-ops
  {:malli/schema [:=> [:cat :seon.host/db :keyword [:maybe :keyword] :any :keyword :int [:sequential :map] [:maybe :map] [:set :any] [:sequential :map] [:set :any] :boolean :boolean] [:maybe [:vector :map]]], :cost/ticks 10000000}
  [main-db agent-id b head-tx status eval-count eval-ents last-eval seen inbox settling-set has-pending-outgoing? under-review?]
  (let [incoming-pending (filterv #(= :pending (:seon.agent.call/status %))
                                  (pull-calls-by-attr main-db :seon.agent.call/callee agent-id))]
    (when (and (seq incoming-pending)
               (not has-pending-outgoing?)
               (contains? #{:paused :waiting :merged} status))
      (vec
       (keep (fn [c]
               (let [cid (:seon.agent.call/id c)
                     mid (:seon.agent.call/message-id c)
                     rsch (:seon.agent.call/result-schema c)]
                 (when-not (contains? settling-set [:incoming cid])
                   (cond
                     (= status :paused)
                     (when-not under-review?
                       {:op :fail-call :call-id cid
                        :error {:class :callee-paused :callee agent-id :message (str "Callee " agent-id " is paused")}})

                     (and (= status :merged) mid (contains? seen mid) (nil? rsch))
                     {:op :answer-call :call-id cid :result {:merged? true :branch b :merged-tx head-tx}}

                     (and mid (contains? seen mid))
                     (let [prose (latest-prose-after-msg eval-ents mid)
                           nudge-tag (str "[call:" cid "]")
                           nudged? (boolean (some #(and (= :protocol-nudge (:seon.agent.message/type %))
                                                        (str/includes? (str (:seon.agent.message/body %)) nudge-tag))
                                                  inbox))]
                       (cond
                         (and prose (nil? rsch))
                         {:op :answer-call :call-id cid :result {:answer prose :implicit-prose? true}}

                         (and (not nudged?) (not (contains? settling-set [:nudge cid])))
                         {:op :send-message :from :system :to agent-id :type :protocol-nudge :call-id cid
                          :body (str "Protocol reminder " nudge-tag ": open call " (pr-str cid)
                                     " is still pending and requires evaluating (user/answer! " (pr-str cid) " <value>)"
                                     (when rsch (str " matching Malli schema " (pr-str rsch)))
                                     " (or (user/fail! " (pr-str cid) " \"reason\")). "
                                     "Do not print the answer in prose alone — call (user/answer! ...) now.")}

                         :else
                         {:op :fail-call :call-id cid
                          :error (cond-> {:class (if rsch :schema-mismatch :callee-stalled)
                                          :callee agent-id :evals eval-count :nudged? nudged?
                                          :message (if rsch
                                                     (str "Callee stopped without calling (user/answer! call-id value) matching " rsch)
                                                     (str "Callee " agent-id " stopped after " eval-count " eval(s) without answering call"))}
                                   (:seon.agent.eval/error last-eval) (assoc :last-error (:seon.agent.eval/error last-eval))
                                   prose (assoc :prose prose))}))))))
             incoming-pending)))))

(defn call-delivery-state
  {:malli/schema [:=> [:cat :seon.host/db :keyword :map [:set :any]] :map], :cost/ticks 5000000}
  [main-db agent-id ma settling-set]
  (let [awaiting-ids (set (:seon.agent/awaiting ma))
      awaiting-calls (vec (keep #(pull-call-by-id main-db %) awaiting-ids))
      pending-awaiting (filterv #(= :pending (:seon.agent.call/status %)) awaiting-calls)
      undelivered-awaiting (filterv #(not (:seon.agent.call/delivered? %)) awaiting-calls)
      stale-awaiting (when (and (seq awaiting-ids) (empty? pending-awaiting) (empty? undelivered-awaiting)
                                (not (contains? settling-set [:clear-awaiting agent-id])))
                       (vec awaiting-ids))
      outgoing-calls (pull-calls-by-attr main-db :seon.agent.call/caller agent-id)]
  {:awaiting-count (count undelivered-awaiting)
   :awaiting-blocked? (boolean (seq undelivered-awaiting))
   :awaiting-all-ids (when (and (seq awaiting-ids) (empty? pending-awaiting)) (vec awaiting-ids))
   :awaiting-stale-to-clear stale-awaiting
   :has-pending-outgoing? (boolean (some #(= :pending (:seon.agent.call/status %)) outgoing-calls))
   :awaiting-ready-to-deliver
   (when (and (seq awaiting-calls) (empty? pending-awaiting))
     (vec (keep (fn [c]
                  (let [cid (:seon.agent.call/id c)]
                    (when (and (not (:seon.agent.call/delivered? c))
                               (not (contains? settling-set [:deliver cid])))
                      cid)))
                awaiting-calls)))
   :async-ready-to-deliver
   (vec (keep (fn [c]
                (let [cid (:seon.agent.call/id c)]
                  (when (and (not (contains? awaiting-ids cid))
                             (not= :pending (:seon.agent.call/status c))
                             (not (:seon.agent.call/delivered? c))
                             (not (contains? settling-set [:deliver cid])))
                    cid)))
              outgoing-calls))}))

(defn callers-awaiting-callees
  {:malli/schema [:=> [:cat :seon.host/db [:maybe [:or [:set :keyword] [:sequential :keyword]]]] [:set :keyword]], :cost/ticks 5000000}
  [main-db aids]
  (if (and (seq aids) (contains? (:schema main-db) :seon.agent.call/callee))
    (into #{}
          (for [aid aids
                dt (d/datoms main-db :avet :seon.agent.call/callee aid)
                :let [c (d/pull main-db '[:seon.agent.call/caller :seon.agent.call/status :seon.agent.call/delivered?] (:e dt))]
                :when (and (= :resolved (:seon.agent.call/status c))
                           (not (:seon.agent.call/delivered? c))
                           (:seon.agent.call/caller c))]
            (:seon.agent.call/caller c)))
    #{}))

(defn callee-merge-in-flight?
  {:malli/schema [:=> [:cat :seon.host/db :any :map :any] :boolean], :cost/ticks 5000000}
  [main-db store all-views cid]
  (boolean
   (when-let [c (pull-call-by-id main-db cid)]
     (when (= :resolved (:seon.agent.call/status c))
       (when-let [callee (:seon.agent.call/callee c)]
         (when-let [cv (get all-views callee)]
           (and (not= :main (:branch cv))
           (or (nil? store) (store/branch? store (:branch cv)))
           (contains? #{:running :runnable :testing :merging} (:status cv))
           (gate/branch-has-code-edits? main-db (safe-branch-head store main-db (:branch cv))))))))))