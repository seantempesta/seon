(ns seon.bootstrap-test
  "The live-fact opening generator and its agent-creation seam."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.plan :as plan]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as cluster.agent]
            [seon.turn :as turn]

            [seon.config :as config]
            [seon.db :as db]
            [seon.render.walk :as walk]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(def ^:private agent-id "bootstrap-agent")
(def ^:private namespace-name 'my.agents.bootstrap-agent)

(defn- seed-cluster! [connection cluster-name]
  (support/seed-cluster!
   connection cluster-name
   {:seon.config.bootstrap/beyond-closure-token-budget 1024})
  (cluster/ensure-cluster-entity!
   connection cluster-name cluster/boot-process-identity))

(defn- generator-request [connection]
  {:seon.db/db @connection
   :seon.db/connection connection
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.render.walk/lookup [:seon.agent/id agent-id]
   :seon.sci.admit/caps (config/result-caps (config/defaults))
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record
   :seon.render/output :seon.render/form
   :seon.render/distance 3})

(deftest creation-opens-one-zero-form-generated-run-with-a-real-task
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "generated-bootstrap")
      (let [request {:seon.agent/id agent-id
                     :seon.cluster/name "generated-bootstrap"
                     :seon.ns/name namespace-name}
            process cluster/boot-process-identity
            result (cluster/ensure-entity! connection process request)
            run-id (bootstrap/run-id agent-id)
            run (db/pull @connection
                         '[:seon.turn/id
                           :seon.turn.work/situation
                           :seon.turn/reply-size
                           {:seon.cluster.eval/_run
                            [:seon.cluster.eval/ordinal
                             :seon.cluster.eval/author
                             :seon.cluster.eval/source]}
                           {:seon.turn/trigger
                            [:seon.message/id
                             :seon.message/content]}]
                         [:seon.turn/id run-id])]
        (is (= run-id (:seon.turn/id result)))

        (is (nil? (:seon.turn/reply-size run))
            "a generated run has no frozen authored plan")
        (is (= :generate (:seon.turn.work/situation run)))
        ;; ONE ENTITY PER (run, ordinal): the evaluations point AT the run,
        ;; and the run keeps no component mirror of that back-edge.
        (is (empty? (:seon.cluster.eval/_run run))
            "creation admits no evaluation outside the generator")
        (is (= (bootstrap/task-message-id @connection agent-id)
               (get-in run [:seon.turn/trigger
                            :seon.message/id])))
        (is (= (bootstrap/task-message)
               (get-in run [:seon.turn/trigger
                            :seon.message/content])))
        (is (= {:seon.turn.work/situation :generate
                :seon.turn/id run-id
                :seon.agent/id agent-id}
               (turn/next-agent-work
                @connection {:seon.agent/id agent-id
                             :seon.db.process/id process})))))))

(deftest drive-free-generation-is-pure-deterministic-and-pull-gated
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "generated-proof")
      (cluster/ensure-entity!
       connection cluster/boot-process-identity
       {:seon.agent/id agent-id
        :seon.cluster/name "generated-proof"
        :seon.ns/name namespace-name})
      (let [request (generator-request connection)
            pull (bootstrap/pull-result request)
            first-entry
            (bootstrap/next-entry request (bootstrap/run-id agent-id))
            second-entry
            (bootstrap/next-entry
             (generator-request connection) (bootstrap/run-id agent-id))]
        (is (= first-entry second-entry)
            "same agent state derives byte-identical episode data")
        (is (= '(help) (:seon.repl/form first-entry))
            "the zero-form run derives help from its live situation")
        (is (not-any? #(= [:seon.ns/name 'outside.pull] (:seon.repl/subject %))
                      (:seon.repl/candidates pull))
            "membership comes only from the bounded pull")
        (let [opening-source (pr-str (:seon.repl/form first-entry))
              situation (bootstrap/situation @connection agent-id)
              node (:seon.sci.admit/print-node
                    (admit/admit-value
                     {:seon.sci.admit/value situation
                      :seon.sci.admit/interrupt-fn (fn [])
                      :seon.sci.admit/caps
                      (config/result-caps (config/defaults))
                      :seon.config/on-core-error :record}))]
          (support/transacted!
                  connection
                  (turn/append-generated-tx
                   {:seon.turn/id (bootstrap/run-id agent-id)
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.cluster.eval/at (java.util.Date.)
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/source opening-source
                    :seon.ns/name namespace-name}))
          (support/transacted!
                  connection
                  (turn/receipt-settle-tx
                   {:seon.turn/id (bootstrap/run-id agent-id)
                    :seon.cluster.eval/ordinal 0
                    :seon.eval/shown (pr-str node)}))
          (let [post-receipt-pull
                (bootstrap/pull-result (generator-request connection))
                listing-candidates
                (filter #(= :listing (second (:seon.repl/key %)))
                        (:seon.repl/candidates post-receipt-pull))
                next-entry
                (bootstrap/next-entry
                 (generator-request connection)
                 (bootstrap/run-id agent-id))]
            (is (= opening-source
                   (db/q '[:find ?source .
                           :in $ ?run-id
                           :where
                           [?run :seon.turn/id ?run-id]
                           [?form :seon.cluster.eval/run ?run]
                           [?form :seon.cluster.eval/ordinal 0]
                           [?form :seon.cluster.eval/source ?source]]
                         @connection (bootstrap/run-id agent-id)))
                "the first derived entry remains byte-identical in history")
            (is (every? #(= (first (:seon.repl/key %))
                            (:seon.repl/subject %))
                        listing-candidates)
                "listing subjects are their pulled stable identities")
            (is (map? next-entry)
                "the live post-receipt pull derives one successor entry")
            (is (not= opening-source (pr-str (:seon.repl/form next-entry))))
            (is (= next-entry
                   (bootstrap/next-entry
                    (generator-request connection)
                    (bootstrap/run-id agent-id)))
                "same post-receipt state derives byte-identical data")))))))

(deftest reborn-opening-retains-authored-namespace-membership
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "reborn-namespace-membership")
      (cluster/ensure-entity!
       connection cluster/boot-process-identity
       {:seon.agent/id agent-id
        :seon.cluster/name "reborn-namespace-membership"
        :seon.ns/name namespace-name})
      (support/transacted!
              connection
              [{:seon.fn/sym (str namespace-name "/current-items")
                :seon.schema.admission/source :core
                :seon.fn/ns [:seon.ns/name namespace-name]
                :seon.fn/source "(defn current-items [items] items)"
                :seon.fn/arglists "([items])"
                :seon.fn/private? false
                :seon.fn/spec "[:=> [:cat [:vector :int]] [:vector :int]]"}
               {:seon.test/sym (str namespace-name "/current-items-test")
                :seon.schema.admission/source :core
                :seon.test/ns [:seon.ns/name namespace-name]
                :seon.test/source "(deftest current-items-test)"
                :seon.test/usage true
                :seon.test/pass-count 1
                :seon.test/fail-count 0
                :seon.test/error-count 0
                :seon.test/run-basis-t (db/basis-t @connection)
                :seon.test/run-at (java.util.Date. 1786500000000)}])
      (let [request (generator-request connection)
            pull (bootstrap/pull-result request)
            namespace-key [[:seon.ns/name namespace-name] 0]
            namespace-candidate
            (some #(when (= namespace-key (:seon.repl/key %)) %)
                  (:seon.repl/candidates pull))
            help-node
            (:seon.sci.admit/print-node
             (admit/admit-value
              {:seon.sci.admit/value
               (bootstrap/situation @connection agent-id)
               :seon.sci.admit/interrupt-fn (fn [])
               :seon.sci.admit/caps
               (config/result-caps (config/defaults))
               :seon.config/on-core-error :record}))
            episode
            (walk/ordered-episode
             (assoc pull :seon.repl/settled
                    [{:seon.repl/key (:seon.repl/root-key pull)
                      :seon.sci.admit/print-node help-node}]))
            candidate-forms
            (map (comp :seon.repl/form :seon.repl/entry)
                 (:seon.repl/candidates pull))]
        (is (contains? (:seon.print/identity-attributes pull)
                       :seon.ns/name)
            "the exact cluster projection preserves namespace identities")
        (is (= [:seon.ns/name namespace-name]
               (:seon.repl/subject namespace-candidate))
            "a namespace candidate's subject is its entity lookup")
        (is (= (list 'dir namespace-name)
               (get-in namespace-candidate
                       [:seon.repl/entry :seon.repl/form]))
            "authored function and green-test facts retain the namespace dir read")
        (is (= (list 'dir namespace-name)
               (:seon.repl/form (second episode)))
            "the content-bearing own namespace is the first gap after help")
        (is (not-any? #(and (seq? %) (= 'defn (first %))) candidate-forms)
            "a green authored usage result removes the worked defn lesson")))))

(deftest every-opening-candidate-subject-is-an-entity-lookup
  ;; A candidate's subject is the entity the candidate is ABOUT, and the one
  ;; spelling for that is :seon.render.walk/lookup. A producer that handed a
  ;; lookup's VALUE instead made seon.render.walk/ordered-episode refuse the
  ;; whole opening, so the class regression drives the real armed walk over
  ;; the candidates seon.bootstrap actually produces.
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "candidate-subject-lookups")
      (cluster/ensure-entity!
       connection cluster/boot-process-identity
       {:seon.agent/id agent-id
        :seon.cluster/name "candidate-subject-lookups"
        :seon.ns/name namespace-name})
      (support/transacted!
       connection
       [{:seon.fn/sym (str namespace-name "/current-items")
         :seon.schema.admission/source :core
         :seon.fn/ns [:seon.ns/name namespace-name]
         :seon.fn/source "(defn current-items [items] items)"
         :seon.fn/arglists "([items])"
         :seon.fn/private? false
         :seon.fn/spec "[:=> [:cat [:vector :int]] [:vector :int]]"}
        {:seon.test/sym (str namespace-name "/current-items-test")
         :seon.schema.admission/source :core
         :seon.test/ns [:seon.ns/name namespace-name]
         :seon.test/source "(deftest current-items-test)"
         :seon.test/usage true
         :seon.test/pass-count 1
         :seon.test/fail-count 0
         :seon.test/error-count 0
         :seon.test/run-basis-t (db/basis-t @connection)
         :seon.test/run-at (java.util.Date. 1786500000000)}])
      (let [pull (bootstrap/pull-result (generator-request connection))
            candidates (:seon.repl/candidates pull)
            namespace-subjects
            (filter #(= :seon.ns/name (first (:seon.repl/subject %)))
                    candidates)]
        (is (seq candidates)
            "the opening pull produces candidates at all")
        (is (seq namespace-subjects)
            "a namespace candidate is among them — the producer that broke")
        (doseq [candidate candidates]
          (is ((schema/projection-validator (schema/handed-projection) :seon.render.walk/lookup) (:seon.repl/subject candidate))
              (str "candidate " (pr-str (:seon.repl/key candidate))
                   " carries subject " (pr-str (:seon.repl/subject candidate))
                   ", which is not a :seon.render.walk/lookup")))
        (let [episode (walk/ordered-episode
                       (assoc pull :seon.repl/settled []))]
          (is (vector? episode)
              "the armed walk derives an episode from the real candidates")
          (is (= [(:seon.repl/root-key pull)]
                 (mapv :seon.repl/key episode))
              "with nothing settled the episode is exactly the root entry"))))))

(deftest authored-plan-machinery-is-deleted
  (is (nil? (io/resource "seon/bootstrap.edn")))
  (doseq [old '[packaged-forms population-tx ordered-sources agent-sources
                plan-digest help-text entry-source]]
    (is (nil? (ns-resolve 'seon.bootstrap old)) (str old " is deleted"))))

(defn- candidate-sources
  [pull]
  ;; The stored source of a generated form is its FORM, without the comment
  ;; that introduces it: the comment is its own fact beside it.
  (mapv (comp pr-str :seon.repl/form :seon.repl/entry)
        (:seon.repl/candidates pull)))

(deftest intent-membership-is-the-only-opening-delta-and-is-budgeted
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "intent-membership")
      (cluster/ensure-entity!
       connection cluster/boot-process-identity
       {:seon.agent/id agent-id
        :seon.cluster/name "intent-membership"
        :seon.ns/name namespace-name})
      (support/transacted!
              connection
              [{:seon.ns/name 'fixture.intent}
               {:seon.fn/sym "fixture.intent/target"
                :seon.schema.admission/source :core
                :seon.fn/ns [:seon.ns/name 'fixture.intent]
                :seon.fn/source "(defn target [x] (inc x))"
                :seon.fn/arglists "([x])"
                :seon.fn/private? false
                :seon.fn/spec "[:=> [:cat :int] :int]"}
               {:seon.test/sym "fixture.intent/target-usage"
                :seon.schema.admission/source :core
                :seon.test/ns [:seon.ns/name 'fixture.intent]
                :seon.test/source
                "(clojure.test/deftest target-usage (clojure.test/is (= 2 (target 1))))"
                :seon.test/usage true
                :seon.fn/calls [[:seon.fn/sym "fixture.intent/target"]]
                :seon.test/pass-count 1
                :seon.test/fail-count 0
                :seon.test/error-count 0
                :seon.test/run-basis-t (db/basis-t @connection)
                :seon.test/run-at (java.util.Date. 1786500000000)}])
      (plan/add! {:my.plan.item/id "use-target"
                  :my.plan.item/title "Use the target"}
                 connection agent-id)
      (let [request (generator-request connection)
            before (bootstrap/pull-result request)
            before-bytes (candidate-sources before)]
        (is (= before-bytes
               (candidate-sources (bootstrap/pull-result request)))
            "an agent with no :about refs has a byte-identical opening")
        (support/transacted!
                connection
                [[:db/add [:my.plan.item/id "use-target"]
                  :my.plan.item/about
                  ['fixture.intent/target]]])
        (let [after (bootstrap/pull-result (generator-request connection))
              before-sources (set (candidate-sources before))
              delta (into []
                          (remove before-sources)
                          (candidate-sources after))]
          (is (= ["(seon.db/pull (quote [*]) [:seon.fn/sym \"fixture.intent/target\"])"
                  "(dir fixture.intent)"
                  "(clojure.test/test-var (var fixture.intent/target-usage))"]
                 delta)
              "the subject doc and owning namespace join membership")
          (is (some #{"; First real use — the indexed call-edge demonstration."}
                    (map (comp :seon.repl/comment :seon.repl/entry)
                         (:seon.repl/candidates after)))
              "the prose that introduces a generated form is its own fact")
          (is (some #(str/includes? % "target-usage") delta)
              "first real use carries its call-edge usage demonstration")
          (is (= [[:seon.fn/sym "fixture.intent/target"]]
                 (:my.plan/intent-subjects after)))
          (is (= (set delta)
                 (set (remove before-sources (candidate-sources after))))
              "the opening delta is exactly the admitted subject units"))
        (support/transacted!
                connection
                [[:db/add
                  (db/q '[:find ?config .
                          :where
                          [?cluster :seon.cluster/name "intent-membership"]
                          [?cluster :seon.cluster/config ?config]]
                        @connection)
                  :seon.config.bootstrap/beyond-closure-token-budget 1]])
        (let [capped (bootstrap/pull-result (generator-request connection))]
          (is (empty? (remove (set (candidate-sources before))
                              (candidate-sources capped)))
              "whole entries exceeding the budget are not admitted"))))))

(deftest absent-intent-budget-refuses-loudly
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "missing-intent-budget")
      ;; ABSENT IS A FACT, not a fixture that failed to write one:
      ;; config/default.edn ships the dial, so the only way to observe the
      ;; loud refusal is to retract it.
      (support/transacted!
       connection
       [[:db/retract [:seon.config/cluster "missing-intent-budget"]
         :seon.config.bootstrap/beyond-closure-token-budget]])
      (cluster/ensure-entity!
       connection cluster/boot-process-identity
       {:seon.agent/id agent-id
        :seon.cluster/name "missing-intent-budget"
        :seon.ns/name namespace-name})
      (let [result (bootstrap/pull-result (generator-request connection))]
        (is (= :seon.config.bootstrap/beyond-closure-token-budget (:seon.config/error-key result)))
        (is (= :seon.config.bootstrap/beyond-closure-token-budget
               (:seon.config/required-absent result)))))))

(deftest missing-or-failed-membership-is-not-a-fixed-point
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "generated-membership-failure")
      (let [request (generator-request connection)
            failed-pull
            {:seon.error/at (java.util.Date.)
             :seon.error/layer :seon.db/read
             :seon.error/operation 'seon.db/pull
             :seon.db/read-operation :pull
             :seon.db.read/target [:seon.agent/id agent-id]
             :seon.error/message "injected failed membership pull"
             :seon.error/data {:seon.db/operation "datahike.pull/result"}}
            cases
            [["missing root"
              {:seon.render.walk/root nil
               :seon.render.walk/members {}
               :seon.render.walk/order []}
              :seon.bootstrap/acquired-member-count]
             ["failed pull"
              {:seon.render.walk/root failed-pull
               :seon.render.walk/members {}
               :seon.render.walk/order []}
              :seon.db/read-operation]]]
        (doseq [[label acquisition expected-member] cases]
          (let [result
                (with-redefs [walk/root-acquisition
                              (constantly acquisition)]
                  (bootstrap/next-entry request "bootstrap:missing"))]
            (is (map? result) label)
            (is (contains? result expected-member) label)
            (when (= :seon.bootstrap/acquired-member-count expected-member)
              (is (zero? (:seon.bootstrap/acquired-member-count result)) label))
            (is (not (nil? result))
                (str label " must not look like a completed frontier"))))))))

(deftest first-agent-supervision-is-one-self-erasing-system-run
  (support/with-database
    (fn [connection]
      (seed-cluster! connection "supervision")
      (support/transacted!
              connection
              (into (cluster.agent/creation-tx
                     {:seon.agent/id "root"
                      :seon.cluster/name "supervision"
                      :seon.ns/name 'my.agents.root})
                    (cluster.agent/creation-tx
                     {:seon.agent/id "worker"
                      :seon.cluster/name "supervision"
                      :seon.ns/name 'my.agents.worker})))
      (let [tx (bootstrap/supervision-tx
                @connection cluster/boot-process-identity
                "worker")]
        (is (seq tx))
        (support/transacted! connection tx)
        (let [sources
              (db/q '[:find [?source ...]
                      :in $ ?run-id
                      :where
                      [?run :seon.turn/id ?run-id]
                      [?form :seon.cluster.eval/run ?run]
                      [?form :seon.cluster.eval/source ?source]]
                    @connection (bootstrap/supervision-run-id))]
          (is (= 2 (count sources)))
          (is (some #(str/includes? % "my.message/send") sources))
          (is (some #(str/includes? % "seon.eval/shown") sources))
          (is (every? #(or (str/includes? % "run/complete")
                           (not (str/includes? % "my.message/send")))
                      sources))
          (is (empty? (bootstrap/supervision-tx
                       @connection cluster/boot-process-identity
                       "worker"))))))))
