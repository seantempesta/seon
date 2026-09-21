(ns seon.render.page-settings-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.plan :as plan]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(deftest settings-without-overrides-use-the-declared-pair
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "empty-settings")
     (let [created (db/transact! connection
                    (agent/creation-tx {:seon.agent/id "root"
                                        :seon.ns/name 'my.agents.root
                                        :seon.cluster/name "empty-settings"}))]
       (is (:db-after created) (pr-str created)))
     (let [database @connection
           component (:seon.agent/settings
                      (db/pull database '[{:seon.agent/settings [*]}] [:seon.agent/id "root"]))
           ctx (support/fork-cluster-ctx connection "empty-settings")]
       (is (:seon.config/agent component))
       (doseq [[output expected] [[:seon.render/ai 'seon.agent/render-settings-ai]
                                  [:seon.render/html 'seon.agent/render-settings-html]]]
         (let [decision (render/selection {:seon.db/db database :seon.sci.eval/ctx ctx
                                           :seon.sci.admit/caps (config/result-caps (support/effective-config))
                                           :seon.sci.eval/time-limit-ms 10000
                                           :seon.config/on-core-error :panic
                                           :seon.render/value component :seon.render/output output})]
           (is (= expected (:seon.render.selection/selected decision)) (pr-str decision))))))))

(defn- example-forms
  "Each top-level form of one authored Example, as agent-facing source.

  An Example may author more than one form — `my.plan/item` teaches an add
  followed by the read, because a read keeps its own evaluation's immutable
  database snapshot. One evaluation carries exactly one form, so the agent
  sees those as two successive evaluations and so does this test."
  [example]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. example))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof ::eof} reader)]
          (if (= ::eof form)
            forms
            (recur (conj forms (repl/source-text form)))))))))

(defn- authored-examples
  "Every public function's authored Example in one namespace, in doc order.

  DERIVED from program rows through the one owner of the docstring grammar
  (`seon.sci.eval/docstring-parts`, `src/seon/sci/eval.clj:1157`) — not
  scraped out of a docstring by string index. The scrape this replaces
  searched for `(seon.db/transact!` forms that the plan-derivation rewrite
  retired, so it silently returned nothing and handed `nil` to
  `seon.repl/source-text`."
  [database namespace-name]
  (->> (db/q '[:find [(pull ?function [:seon.fn/sym :seon.fn/doc
                                       :seon.fn/doc-order]) ...]
               :in $ ?namespace-name
               :where [?namespace :seon.ns/name ?namespace-name]
                      [?function :seon.fn/ns ?namespace]
                      [?function :seon.fn/private? false]
                      [?function :seon.fn/doc _]]
             database namespace-name)
       (sort-by (juxt #(get % :seon.fn/doc-order Long/MAX_VALUE)
                      :seon.fn/sym))
       (keep (fn [row]
               (let [example (:example (evaluation/docstring-parts
                                        (:seon.fn/doc row)))]
                 (when-not (str/blank? example)
                   [(:seon.fn/sym row) example]))))
       vec))

(deftest effective-settings-and-authored-plan-examples-work-through-sci
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "page-settings"})
     (support/seed-cluster! connection "page-settings")
     (doseq [transaction
             [(agent/creation-tx {:seon.agent/id "juniper" :seon.ns/name 'my.agents.juniper
                                  :seon.cluster/name "page-settings"})
              [{:seon.config/agent [:seon.agent/id "juniper"]
                :seon.config.eval/time-limit-ms 1234 :seon.config.ai/no-provider true}
               {:my.plan/agent [:seon.agent/id "juniper"]
                :my.plan/objective "Verify the examples"
                :my.plan/steps [{:my.plan.item/id "existing" :my.plan.item/title "Existing step"
                                 :my.plan.item/position 0}]}]]]
       (let [result (db/transact! connection transaction)]
         (assert (:db-after result) (pr-str result))))
     (let [base (support/fork-cluster-ctx connection "page-settings")
           ctx (:seon.sci.eval/ctx
                (evaluation/fork-for-turn
                 {:seon.sci.eval/ctx base :seon.db/db @connection
                  :seon.db/connection connection :seon.agent/id "juniper"}))
           request {:seon.sci.eval/ctx ctx :seon.agent/id "juniper"
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 10000 :seon.config/on-core-error :panic}
           evaluate (fn [source]
                      (evaluation/evaluate (assoc request :seon.db/db @connection
                                                 :seon.cluster.eval/source source)))
           result (evaluate "(seon.agent/effective-settings)")
           groups (:seon.sci.admit/value result)
           values (apply merge groups)
           examples (authored-examples @connection 'my.plan)]
       (is (not (:seon.cluster.eval/error result)) (:seon.eval/shown result))
       (is (= ["seon.config.ai" "seon.config.ai.retry" "seon.config.eval" "seon.config.run"]
              (mapv (comp namespace ffirst) (take 4 groups))))
       (is (= 1234 (:seon.config.eval/time-limit-ms values)))
       (is (= (:seon.config.ai/model (config/effective @connection "page-settings"))
              (:seon.config.ai/model values)))
       ;; Turn accounting left the settings groups in `0dca8534e`:
       ;; `:my.agent/turns-left` is `:seon.wake/context-inert` and is read at
       ;; its owner on demand, never mirrored into a settings group.
       (is (not (find values :my.agent/turns-left)) (pr-str values))
       (is (nat-int? (:seon.sci.admit/value (evaluate "(seon.turn/turns-left)"))))
       (is (not (str/includes? (:seon.eval/shown result) "seon.render/ambiguous")))
       ;; THE CLASS: every Example my.plan authors into an agent's context
       ;; must evaluate in that agent's own SCI context. Each authored example
       ;; is self-contained, so order is irrelevant and one failing example
       ;; names its own function.
       (is (seq examples))
       (doseq [[function-symbol example] examples
               source (example-forms example)]
         (let [evaluated (evaluate source)]
           (is (not (:seon.cluster.eval/error evaluated))
               (str function-symbol " example " (pr-str source) ": "
                    (:seon.eval/shown evaluated)))))
       (let [steps (db/q '[:find [(pull ?step [:my.plan.item/id
                                               :my.plan.item/completed-tx]) ...]
                           :where [_ :my.plan/steps ?step]]
                         @connection)
             current (db/q '[:find ?id .
                             :where [_ :my.plan/current-step ?step]
                                    [?step :my.plan.item/id ?id]]
                           @connection)]
         (is (< 1 (count steps)) (pr-str steps))
         (is (some :my.plan.item/completed-tx steps) (pr-str steps))
         (is (string? current) (pr-str steps)))
       (let [documentation (evaluate "(doc my.plan)")
             text (:example (:seon.sci.admit/value documentation))
             source (plan/render-plan-ai {:seon.agent/id "juniper"})]
         (is (string? text) (:seon.eval/shown documentation))
         (is (str/includes? text "my.plan/add!") text)
         (is (= 1 (count (filter #(str/starts-with? % ";;") (str/split-lines source)))))
         (is (not (str/includes? source "transact!"))))
       (is (not-any? #(find values %) [:seon.config.ai/api-key-variable
                                      :seon.config.ai/endpoint
                                      :seon.config.ai/chars-per-token-prior
                                      :seon.config.ai.backup/model]))
       (is (:db-after (db/transact! connection
                       [{:seon.config/agent [:seon.agent/id "juniper"]
                         :seon.config.agent/show-all-settings true}])))
       (let [full (apply merge (:seon.sci.admit/value (evaluate "(seon.agent/effective-settings)")))]
         (is (= "DEEPSEEK_API_KEY" (:seon.config.ai/api-key-variable full)))
         (is (true? (:seon.config.agent/show-all-settings full))))))))
