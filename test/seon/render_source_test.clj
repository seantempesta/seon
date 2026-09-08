(ns seon.render-source-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.blob :as blob]
            [seon.cluster.agent :as agent]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.data :as data]
            [seon.render.transcript :as transcript]
            [seon.render.web :as web]
            [seon.sci.eval :as eval]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn authored-source
  "A source-producing fixture whose contract is indexed like any producer."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_unit]
  "(+ 1 1)")

(defn database-source
  "A fixture preview whose result depends on one real database attribute."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_unit]
  "(seon.db/pull [:my.plan.item/title] [:my.plan.item/id \"preview-item\"])")

(defn- cluster-handle
  "This suite's cluster identity, through the ONE canonical handle fixture.

  The structural members and the shipped dials are the fixture's; only the
  cluster's own identity and this suite's pinned channel and limit are the
  caller's. This used to be `(merge (config/defaults) …)` — a SECOND
  mechanism for the same shape (§2.5), and one that pours every effective
  config dial into the handle rather than the members
  `:seon.cluster.loop/cluster` declares, so a renamed dial would leave the
  fixture working while production broke."
  [connection ctx channel cluster-name]
  (support/cluster-handle
   {:seon.db/connection connection
    :seon.cluster/name cluster-name
    :seon.cluster.run/process "preview-test"
    :seon.sci.eval/ctx ctx
    :seon.cluster.wake/channel channel
    :seon.render/context-channel channel
    :seon.cluster.loop/completion channel
    :seon.sci.admit/caps caps
    :seon.config.eval/time-limit-ms 2000
    :seon.config/on-core-error :panic}))

(deftest preview-cache-is-memory-only-and-invalidates-on-read-changes
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "memory-preview")
     (db/transact! connection
                   (into (agent/creation-tx
                          {:seon.cluster.agent/id "memory-preview-agent"
                           :seon.ns/name 'my.agents.memory-preview
                           :seon.cluster/name "memory-preview"})
                         [{:my.plan.item/id "preview-item" :my.plan.item/title "Before"}]))
     (db/transact! connection
                   (run/open-tx
                    {:seon.cluster.run/id "agent-is-busy"
                     :seon.cluster.run/agent [:seon.cluster.agent/id "memory-preview-agent"]
                     :seon.cluster.run/opened-at (java.util.Date.)}))
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           channel (async/chan 1)
           cluster (cluster-handle connection ctx channel "memory-preview")
           source-call (ns-resolve 'seon.render.web 'render-source-call)
           calls (atom {})
           invocations (atom {})
           evaluated (atom 0)
           original-evaluate eval/evaluate
           request {:seon.db/db database :seon.sci.eval/ctx ctx
                    :seon.render/value {}
                    :seon.render/namespace 'my.agents.memory-preview
                    :seon.render/ai 'seon.render-source-test/database-source
                    :seon.render/output :seon.render/ai
                    :seon.render/profile (render/agent-render-profile (config/defaults))
                    :seon.render.call/id [:memory-preview]
                    :seon.render/captured-calls calls
                    :seon.render/captured-invocations invocations
                    :seon.sci.admit/caps caps :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.cluster.agent/id "memory-preview-agent"
                    :seon.cluster.loop/cluster cluster}
           preview
           (with-redefs [db/transact! (fn [& _] (throw (ex-info "preview wrote database facts" {})))
                         blob/stage! (fn [& _] (throw (ex-info "preview staged a blob" {})))
                         agent/submit-source! (fn [& _] (throw (ex-info "preview submitted a run" {})))
                         eval/evaluate (fn [request]
                                         (swap! evaluated inc)
                                         (original-evaluate request))]
             (let [first-output (source-call request)]
               (is (= first-output (source-call request))
                   "the same production call can appear twice in one page pass")
               (is (= first-output
                      (source-call (assoc request
                                          :seon.render/retained-calls @calls
                                          :seon.render/invocations @invocations
                                          :seon.render/captured-calls (atom {})
                                          :seon.render/captured-invocations (atom {})))))
               first-output))
           entry (get @calls [:memory-preview])
           cached-id (:seon.render.call/source-run-id entry)
           state {:seon.cluster.loop/cluster cluster
                  :seon.render.web/interest (atom {})
                  :seon.render.web/invocations @invocations
                  :seon.render.web/calls {[:memory-preview] @calls}
                  :seon.render.web/ai-calls {}}]
       (try
         (is (= 1 @evaluated))
         (let [invalidated
               ((ns-resolve 'seon.render.web 'invalidate-runtime-derived-state)
                (assoc state :seon.render.web/registration (atom {[:memory-preview] 1})))]
           (with-redefs [eval/evaluate (fn [& _] (throw (ex-info "unchanged runtime wake re-evaluated" {})))]
             (is (= preview
                    (source-call
                     (assoc request
                            :seon.render/retained-calls
                            (get-in invalidated [:seon.render.web/calls [:memory-preview]])
                            :seon.render/invocations (:seon.render.web/invocations invalidated)
                            :seon.render/captured-calls (atom {})
                            :seon.render/captured-invocations (atom {})))))))
         (is (str/includes? preview "Before"))
         (is (= (db/basis-t database) (db/basis-t @connection))
             "previewing, including the busy agent, changes no facts")
         (is (nil? (db/pull @connection [:seon.cluster.run/id] [:seon.cluster.run/id cached-id])))
         (db/transact! connection [{:my.plan.item/id "preview-item" :my.plan.item/title "After"}])
         (let [next-calls (atom {})
               next-output (source-call
                            (assoc request :seon.db/db @connection
                                   :seon.render/retained-calls @calls
                                   :seon.render/invocations @invocations
                                   :seon.render/captured-calls next-calls
                                   :seon.render/captured-invocations (atom {})))]
           (is (str/includes? next-output "After"))
           (is (not= cached-id (get-in @next-calls [[:memory-preview] :seon.render.call/source-run-id]))))
         (finally (async/close! channel)))))))

(deftest default-source-reproduces-the-exact-reached-value
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:my.plan.item/id "source-provenance"
        :my.plan.item/title "Exact title"}])
     (let [database @connection
           lookup [:my.plan.item/id "source-provenance"]
           entity (db/pull database '[*] lookup)
           cursor {:seon.render.data/path [:my.plan.item/title]
                   :seon.render.data/offset 0}
           entity-source
           (render/render-default-ai-source
            {:seon.db/db database :seon.render/value entity})
           scalar-source
           (render/render-default-ai-source
            {:seon.db/db database
             :seon.render/value "Exact title"
             :seon.render.value/root lookup
             :seon.render.data/cursor cursor})
           ctx (support/fork-cluster-ctx connection)
           evaluate (fn [source]
                      (:seon.sci.admit/value
                       (eval/evaluate
                        {:seon.cluster.eval/source source
                         :seon.sci.eval/ctx ctx
                         :seon.sci.admit/caps caps
                         :seon.sci.eval/time-limit-ms 2000
                         :seon.config/on-core-error :panic})))]
       (testing "entity source uses the public omitted-database pull"
         (is (= '(seon.db/pull (quote [*])
                               [:my.plan.item/id "source-provenance"])
                (read-string entity-source)))
         (is (= entity (evaluate entity-source))))
       (testing "a cursor constrains the source to the reached scalar"
         (is (= "Exact title" (evaluate scalar-source)))
         (is (= '(seon.render.data/pull-at
                   (quote [*])
                   [:my.plan.item/id "source-provenance"]
                   {:seon.render.data/path [:my.plan.item/title]
                    :seon.render.data/offset 0})
                (read-string scalar-source))))
       (testing "query and path failures remain flat diagnostics"
         (is (:seon.error/kind
              (data/pull-at '[*] [:my.plan.item/id "absent"] cursor)))
         (is (:seon.error/kind
              (data/pull-at '[*] lookup
                            {:seon.render.data/path [:no.such/path]
                             :seon.render.data/offset 0}))))))))

(deftest anonymous-values-refuse-to-forge-source-provenance
  (let [failure
        (render/render-default-ai-source
         {:seon.render/value (Object.)})]
    (is (= :seon.render/missing-source-provenance
           (:seon.error/kind failure)))
    (is (= :seon.render.value/root
           (:seon.error/diagnostic-member failure)))
    (is (not (re-find #"#object" (pr-str failure))))))

(deftest source-output-selects-a-source-builder-before-the-terminal-floor
  (support/with-database
   (fn [connection]
     (let [request {:seon.db/db @connection
                    :seon.sci.eval/ctx
                    (support/fork-cluster-ctx connection)
                    :seon.render/value 7
                    :seon.render/output :seon.render/ai
                    :seon.render.call/source-output? true
                    :seon.render.call/id [:source-test/default]
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic}
           decision (render/selection request)]
       (is (= 'seon.render/render-default-ai-source
              (:seon.render.selection/selected decision)))))))

(deftest source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "source-contract")
     (db/transact! connection
                   (agent/creation-tx
                    {:seon.cluster.agent/id "source-contract-agent"
                     :seon.ns/name 'my.agents.source-contract
                     :seon.cluster/name "source-contract"}))
     (doseq [[run-id source result] [["declared-source" "(+ 1 1)"
                                     "#:seon.print{:face :seon.print/number, :value 2}"]
                                    ["stored-transcript" "(println \"already ran\")"
                                     "#:seon.print{:face :seon.print/nil, :value nil}"]]]
       (db/transact!
        connection
        [{:seon.cluster.run/id run-id
          :seon.cluster.run/agent [:seon.cluster.agent/id "source-contract-agent"]
          :seon.cluster.run/opened-at #inst "2026-09-06T20:00:00Z"
          :seon.cluster.run/closed-at #inst "2026-09-06T20:00:01Z"
          :seon.cluster.run/starting-ns [:seon.ns/name 'my.agents.source-contract]}
         {:seon.cluster.eval/id (str run-id "/0")
          :seon.cluster.eval/run [:seon.cluster.run/id run-id]
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/at #inst "2026-09-06T20:00:00Z"
          :seon.cluster.eval/source source
          :seon.cluster.eval/ns [:seon.ns/name 'my.agents.source-contract]
          :seon.cluster.eval/result-edn result}]))
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           source-call (ns-resolve 'seon.render.web 'render-source-call)
           captured-calls (atom {})
           captured-invocations (atom {})
           channel (async/chan 1)
           cluster (cluster-handle connection ctx channel "source-contract")
           evaluations (atom 0)
           original-evaluate eval/evaluate
           request
           (fn [id producer value]
             {:seon.db/db database :seon.sci.eval/ctx ctx
              :seon.render/value value :seon.render/ai producer
              :seon.render/output :seon.render/ai
              :seon.render/profile
              {:seon.render.profile/id :test/source-contract
               :seon.render.profile/token-budget 1000
               :seon.render.profile/max-depth 8
               :seon.render.profile/max-children 100
               :seon.render.profile/composition
               :multiline}
              :seon.render.call/id [id]
              :seon.render/captured-calls captured-calls
              :seon.render/captured-invocations captured-invocations
              :seon.sci.admit/caps caps :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic
              :seon.cluster.agent/id "source-contract-agent"
              :seon.cluster.loop/cluster cluster
              :seon.cluster.agent/routing (atom {})})
           stored-run (db/pull database '[*] [:seon.cluster.run/id "stored-transcript"])
           output-refs
           (fn [function-symbol]
             (set (db/q '[:find [?key ...] :in $ ?symbol
                          :where [?function :seon.fn/sym ?symbol]
                          [?function :seon.fn/arities ?arity]
                          [?arity :seon.fn.arity/output-refs ?schema]
                          [?schema :seon.schema/key ?key]]
                        database function-symbol)))]
       (is (= "(+ 1 1)" (authored-source {})))
       (is (contains? (output-refs "seon.render-source-test/authored-source")
                      :seon.render/source))
       (is (not (contains? (output-refs "seon.render.transcript/render-run-ai")
                           :seon.render/source)))
       (with-redefs [agent/submit-source! (fn [& _] (throw (ex-info "preview submitted" {})))
                     eval/evaluate (fn [request] (swap! evaluations inc) (original-evaluate request))]
         (is (str/includes? (source-call (request :source 'seon.render-source-test/authored-source {}))
                            "2"))
         (let [unowned-request
               (-> (request :unowned 'seon.render-source-test/authored-source
                            {:seon.cluster.agent/id "source-contract-agent"})
                   (dissoc :seon.cluster.agent/id)
                   (assoc :seon.render/namespace 'my.agents.source-contract
                          :seon.render/captured-calls (atom {})
                          :seon.render/captured-invocations (atom {})))
               refusal (source-call unowned-request)]
           (is (= :seon.render.web/owner-not-ensured (:seon.error/kind refusal)))
           (is (= refusal (source-call unowned-request))
               "a repeated unowned interest remains a retained preview refusal")
           (is (= 1 @evaluations)
               "no source is submitted without its viewing agent")
           (is (str/includes?
                (source-call (assoc unowned-request
                                    :seon.cluster.agent/id "source-contract-agent"))
                "2")
               "entity attributes cannot make absent and present execution custody identical")
           (is (= 2 @evaluations))))
         (let [terminal-request (request :terminal 'seon.render.transcript/render-run-ai stored-run)
               terminal (source-call terminal-request)
               original-output (transcript/render-run-ai
                                (merge terminal-request stored-run))
               retained (get @captured-calls [:terminal])]
           (is (= original-output terminal))
           (is (str/includes? terminal "already ran"))
           (is (= 2 @evaluations)
               "stored transcript source is never submitted again")
           (is (nil? (:seon.cluster.run/id retained))
               "a run-valued input does not prove what evaluations a renderer displayed")
           (is (nil? (:seon.context.contribution/evaluations retained)))
           (is (nil? (:seon.render.call/source-run-id retained)))
           (is (= (db/q '[:find (count ?evaluation) .
                          :where [?evaluation :seon.cluster.eval/id]] database)
                  (db/q '[:find (count ?evaluation) .
                          :where [?evaluation :seon.cluster.eval/id]] @connection))))
       (async/close! channel)))))
