(ns seon.turn.gate
  "The install gate of the one REPL path, judging a change: a changed
  contracted function's seeded contract check on the working value blocks
  only on its own contract, and its reaching tests, each run as a test agent
  in its own namespace, block only when newly red (red after, not red on the
  batch's basis). Its evidence is data (`:seon.batch/gate`)."
  (:require [sci.core :as sci]
            [seon.batch]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as sfn]
            [seon.id :as id]
            [seon.sci.batch :as sci.batch]
            [seon.sci.eval :as eval]
            [seon.test.accretion :as accretion]
            [seon.turn.world :as world]))

(defn- test-outcomes
  "Each reaching test run as a test agent in a fork of world `w`: its
  namespace's resolver bindings installed from its stored namespace entity,
  its stored source evaluated through the one path there (so it calls the
  world's definitions, not the Vars an earlier acquisition compiled it
  against), then run. Its pass, fail and error counts, or `:absent` when `w`'s
  value holds no such test."
  {:malli/schema [:=> [:cat :seon.batch/world [:vector :qualified-symbol]] :map]}
  [w tests]
  (let [db (:seon.db/db w)
        agent-world (update w :seon.sci.eval/ctx sci/fork)
        ctx (:seon.sci.eval/ctx agent-world)
        stored (into {} (keep (fn [t] (let [e (db/pull db [:seon.test/source {:seon.test/ns [:seon.ns/name]}] [:seon.test/sym t])]
                                        (when (:seon.test/source e) [t e]))))
                     tests)]
    (doseq [n (distinct (keep #(get-in % [:seon.test/ns :seon.ns/name]) (vals stored)))
            :let [ns-entity (db/pull db '[*] [:seon.ns/name n])]]
      (sci/create-ns n)
      (when-not (sci/find-ns ctx n) (sci/eval-form ctx (list 'clojure.core/create-ns (list 'quote n))))
      (#'eval/install-namespace-bindings! ctx n (#'eval/row-bindings ns-entity)))
    (doseq [[_ {src :seon.test/source {n :seon.ns/name} :seon.test/ns}] stored]
      (seon.batch/evaluate-batch (sci.batch/acquire-request {:seon.batch/text src :seon.batch/world agent-world :seon.batch/start-ns n})))
    (let [vars (into {} (keep (fn [t] (when-let [v (and (stored t) (sci/resolve ctx t))] [t v]))) tests)
          results (when (seq vars)
                    (world/with-world agent-world #(eval/run-tests {:seon.sci.eval/ctx ctx :seon.test/vars (vec (vals vars))
                                                              :seon.sci.eval/time-limit-ms (or (:seon.sci.eval/time-limit-ms w) 30000)})))
          by-sym (into {} (when (vector? results) (map (juxt :seon.test/sym identity) results)))]
      (into {} (for [t tests]
                 [t (if-let [r (get by-sym t)]
                      (select-keys r [:seon.test/pass-count :seon.test/fail-count :seon.test/error-count])
                      :absent)])))))

(defn- red?
  "Whether a test outcome counts a failure or an error."
  {:malli/schema [:=> [:cat [:maybe [:or [:= :absent] [:map [:seon.test/fail-count {:optional true} nat-int?] [:seon.test/error-count {:optional true} nat-int?]]]]] :boolean]}
  [outcome]
  (and (map? outcome) (pos? (+ (:seon.test/fail-count outcome 0) (:seon.test/error-count outcome 0)))))

(defn gate-entity
  "The install gate for one changed contracted function, judging the change:
  its seeded contract check on the working value blocks only on its own
  contract (a callee's contract refusing a generated argument is a contract
  finding, delivered as feedback), and its reaching tests block only when
  newly red: red after the change and not red on the batch's basis (run in a
  world acquired from the basis database, never reused as green). Answers the
  gate value as data."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.db/database-value :seon.program/row] :seon.batch/gate]}
  [{world :seon.batch/world} working {sym :seon.fn/sym :as row}]
  (let [after-world (world/read-only-world world working)
        {fork :seon.sci.eval/ctx turn-id :seon.turn/id cluster :seon.boot/cluster-name limit :seon.sci.eval/time-limit-ms} after-world
        effective (config/effective working (or cluster "default"))
        check (world/with-world after-world
                #(accretion/auto-check
                  {:seon.sci.eval/ctx fork :seon.db/db working :seon.program/row row
                   :seon.schema/projection (db/carried-projection working)
                   :seon.config.test/auto-check-cases (:seon.config.test/auto-check-cases effective)
                   :seon.test.accretion/seed (accretion/seed-for (id/evaluation (or turn-id "batch") 0))
                   :seon.sci.eval/time-limit-ms (or limit 30000)
                   :seon.sci.admit/caps (:seon.sci.admit/caps (#'eval/instrumentation-config working))}))
        failures (for [g (:seon.test.accretion/failure-groups (accretion/gate-report {:seon.fn/sym sym :seon.test.accretion/results []
                                                                                       :seon.test.accretion/auto-check check}))
                       f (:seon.test.accretion/failures g)]
                   f)
        callee (fn [f] (let [a (:seon.test.accretion/actual f)
                             by (when (map? a) (:seon.instrument/fn a))]
                         (when (and by (not= by sym)) by)))
        own (vec (for [f failures :when (not (callee f))]
                   (let [a (:seon.test.accretion/actual f)
                         message (if (map? a) (:seon.error/message a) (str a))]
                     (cond-> {:seon.gate/arguments (:seon.test.accretion/arguments f)}
                       message (assoc :seon.gate/error message)))))
        findings (vec (distinct (for [f failures :let [c (callee f)] :when c]
                                  {:seon.gate/contract c :seon.gate/arguments (:seon.test.accretion/arguments f)})))
        tests (let [t (sfn/gate-set working sym)] (if (vector? t) t []))
        after (when (seq tests) (test-outcomes after-world tests))
        base (when (some red? (vals after))
               (when-let [base-ctx (:seon.sci.eval/base-ctx world)]
                 (test-outcomes (assoc world :seon.sci.eval/ctx
                                       (eval/fork-cluster-ctx base-ctx (:seon.db/db world) (:seon.db/connection world)))
                                tests)))
        newly-red (vec (for [[t o] after :when (and (red? o) (not (red? (get base t))))] (assoc o :seon.test/sym t)))
        still-red (vec (for [[t o] after :when (and (red? o) (red? (get base t)))] (assoc o :seon.test/sym t)))]
    (cond-> {:seon.gate/sym sym
             :seon.gate/blocks? (boolean (or (seq own) (seq newly-red)))
             :seon.gate/tests (count tests)}
      (seq own) (assoc :seon.gate/own-failures (vec (take 3 own)))
      (seq findings) (assoc :seon.gate/contract-findings findings)
      (seq newly-red) (assoc :seon.gate/newly-red newly-red)
      (seq still-red) (assoc :seon.gate/still-red still-red))))

(defn gate-deferred
  "The install gate for entities whose callees were unbound when they were
  defined (`:seon.batch/deferred`), on the batch's final working value: a
  refusal is the defining form's facts error and withdraws the row."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/batch] :seon.batch/batch]}
  [request batch]
  (reduce (fn [b [i e]]
            (let [entities (for [s (get-in e [:seon.batch/change :seon.batch/deferred])
                             :let [row (get-in b [:seon.batch/program :entities s])]
                             :when (and (map? row) (:seon.fn/spec row))]
                         row)
                  refused (first (filter :seon.gate/blocks?
                                         (map #(gate-entity request (:seon.batch/working b) %) entities)))]
              (if refused
                (-> b
                    (assoc-in [:seon.batch/entries i :seon.batch/facts-error]
                              (assoc refused :seon.error/message (str "The install gate refused " (:seon.gate/sym refused))))
                    (update-in [:seon.batch/program :entities] dissoc (:seon.gate/sym refused)))
                b)))
          batch
          (map-indexed vector (:seon.batch/entries batch))))

