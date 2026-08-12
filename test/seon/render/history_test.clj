(ns seon.render.history-test
  "Class regressions for self-generating, append-only REPL history."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.render :as render]
            [seon.render.ns :as render.ns]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(def ^:private caps (config/result-caps (config/defaults)))

(defn- render-request
  [database ctx value]
  {:seon.db/db database
   :seon.sci.eval/ctx ctx
   :seon.render/value value
   :seon.render/output :seon.render/form
   :seon.sci.admit/caps caps
   :seon.sci.eval/time-limit-ms 5000
   :seon.config/on-core-error :record})

(deftest form-is-the-third-output-of-the-existing-selection-chain
  (support/with-database
   (fn [connection]
     (db/transact!
      connection
      (into []
            (filter (comp #{:seon.render/form
                            :seon.render/output
                            :seon.render/rendered
                            :seon.render/call-request
                            :seon.ns/ns
                            :seon.fn/fn
                            :seon.schema/schema
                            :seon.cluster.message/message
                            :seon.cluster.message/to}
                          :seon.schema/key))
            (schema/canonical-schema-rows)))
     (db/transact!
      connection
      [{:seon.ns/name 'fixture.history}
       {:seon.cluster.agent/id "history-agent"
        :seon.cluster.agent/namespace [:seon.ns/name 'fixture.history]}
       {:seon.cluster.message/id "history-message"
        :seon.cluster.message/to [:seon.cluster.agent/id "history-agent"]
        :seon.cluster.message/at (java.util.Date. 1786400000000)
        :seon.cluster.message/content "Read me."}])
     (let [database @connection
           ctx (support/fork-cluster-ctx connection)
           selected
           (fn [value & [attribute]]
             (#'seon.render/producer
              (cond-> (render-request database ctx value)
                attribute
                (assoc :seon.render.walk/attribute attribute))
              :seon.render/form
              :seon.render/form))
           namespace-entity
           (db/pull database '[*] [:seon.ns/name 'fixture.history])
           message
           (db/pull database '[*]
                    [:seon.cluster.message/id "history-message"])]
       (testing "shape owners declare ordinary doc/dir/read forms"
         (is (= 'seon.render.ns/namespace-form
                (selected namespace-entity)))
         (is (= '(dir (quote fixture.history))
                (render.ns/namespace-form namespace-entity)))
         (is (= 'seon.render.transcript/message-form
                (selected message)))
         (is (= '(my.message/read "history-message")
                (transcript/message-form message))))
       (testing "an attribute declaration precedes the landed entity shape"
         (is (= 'seon.render.transcript/inbox-form
                (selected message :seon.cluster.message/to)))
         (is (= '(my.message/inbox) (transcript/inbox-form message))))
       (testing "the entity floor uses the projection's declared identity"
         (let [agent-entity (db/pull database '[*]
                                     [:seon.cluster.agent/id "history-agent"])
               producer (selected agent-entity)
               form (render/render-form-value
                     (render-request database ctx agent-entity))]
           (is (= 'seon.cluster.agent/situation-form producer))
           (is (= {:seon.repl/comment
                   "; A new run just opened. Why am I awake — do I have messages?"
                   :seon.repl/form '(help)}
                  form))))
       (testing "the attribute floor is a listing query"
         (let [request (assoc (render-request database ctx namespace-entity)
                              :seon.render.walk/attribute :seon.ns/requires)
               producer (selected namespace-entity :seon.ns/requires)
               form (render/render-form request)]
           (is (= 'seon.render/render-form producer))
           (is (= 'db/q (first form)))
           (is (str/includes? (pr-str form) ":seon.ns/requires"))))))))

(deftest form-output-validation-is-the-declared-open-shape
  (let [valid? #'render/valid-projection?
        entry {:seon.repl/comment "; think"
               :seon.repl/form '(help)}]
    (is (valid? :seon.render/form '(help)))
    (is (valid? :seon.render/form entry))
    (is (valid? :seon.render/form [entry {:seon.repl/form '(dir 'my.run)}]))
    (is (not (valid? :seon.render/form {:seon.repl/comment "; no act"})))
    (is (valid? :seon.render/form
                {:seon.error/kind :seon.render/failure
                 :seon.error/message "failed"}))))

(defn- settled-node
  [value]
  (:seon.sci.admit/print-node
   (admit/admit-value
    {:seon.sci.admit/value value
     :seon.sci.admit/interrupt-fn (fn [])
     :seon.sci.admit/caps caps
     :seon.config/on-core-error :record})))

(def ^:private episode-candidates
  [{:seon.repl/key :root
    :seon.repl/subject [:seon.cluster.agent/id "worker"]
    :seon.repl/entry {:seon.repl/form '(help)}}
   {:seon.repl/key :run-namespace
    :seon.repl/subject 'my.run
    :seon.repl/entry {:seon.repl/form '(dir (quote my.run))}}
   {:seon.repl/key :complete-doc
    :seon.repl/subject 'my.run/complete
    :seon.repl/entry {:seon.repl/form '(doc (quote my.run/complete))}}
   {:seon.repl/key :inbox
    :seon.repl/subject 'my.message
    :seon.repl/entry {:seon.repl/form '(my.message/inbox)}}
   {:seon.repl/key :message
    :seon.repl/subject [:seon.cluster.message/id "task-1"]
    :seon.repl/entry {:seon.repl/form '(my.message/read "task-1")}}])

(defn- episode-request
  [candidates settled]
  {:seon.repl/root-key :root
   :seon.repl/candidates candidates
   :seon.repl/settled settled
   :seon.print/identity-attributes
   #{:seon.cluster.agent/id :seon.cluster.message/id :seon.ns/name}})

(deftest generated-episodes-have-two-independent-gates
  (let [settled
        [{:seon.repl/key :root
          :seon.sci.admit/print-node
          (settled-node {:seon.cluster.agent/id "worker"
                         :seon.cluster.agent/protocol-namespaces
                         ['my.message 'my.run]
                         :outside/reference 'outside.ns})}
         {:seon.repl/key :run-namespace
          :seon.sci.admit/print-node
          (settled-node ['my.run/complete 'my.run/wait])}
         {:seon.repl/key :complete-doc
          :seon.sci.admit/print-node (settled-node nil)}
         {:seon.repl/key :inbox
          :seon.sci.admit/print-node
          (settled-node [{:seon.cluster.message/id "task-1"}])}]
        candidates episode-candidates
        result (walk/ordered-episode (episode-request candidates settled))
        episode-keys (mapv :seon.repl/key result)]
    (is (= [:root :run-namespace :complete-doc :inbox :message] episode-keys)
        "a listing value introduces each later lookup, with stable ties")
    (is (< (.indexOf episode-keys :inbox) (.indexOf episode-keys :message))
        "an entity id must appear in the inbox value before its read")
    (is (not (some #(= '(dir (quote outside.ns))
                       (:seon.repl/form %))
                   result))
        "an introduced symbol with no pulled candidate grows nothing")
    (is (= result
           (walk/ordered-episode (episode-request candidates settled)))
        "the same pull and settled values derive byte-identical data")))

(deftest the-generated-prefix-stops-at-the-first-unsettled-entry
  (let [root-settled
        [{:seon.repl/key :root
          :seon.sci.admit/print-node
          (settled-node {:seon.cluster.agent/protocol-namespaces ['my.run]})}]
        result (walk/ordered-episode
                (episode-request episode-candidates root-settled))]
    (is (= [:root :run-namespace] (mapv :seon.repl/key result)))
    (is (= '(dir (quote my.run)) (:seon.repl/form (peek result))))))

(deftest advancing-bases-only-append-to-the-prompt-prefix
  (let [first-entry
        {:seon.render.history/call-id [:entity :alpha]
         :seon.render.history/basis-transaction 10
         :seon.render.history/bytes "user=> (alpha)\n:alpha"}
        repeated (assoc first-entry :seon.render.history/bytes "rewritten")
        second-entry
        {:seon.render.history/call-id [:entity :beta]
         :seon.render.history/basis-transaction 11
         :seon.render.history/bytes "user=> (beta)\n:beta"}
        prompt-n (web/append-history [] [first-entry])
        prompt-n+1 (web/append-history prompt-n [repeated second-entry])
        prompt-bytes #(str/join "\n\n" (map :seon.render.history/bytes %))]
    (is (= [first-entry second-entry] prompt-n+1)
        "the old observation is never reserialized or rewritten")
    (is (str/starts-with? (prompt-bytes prompt-n+1)
                          (prompt-bytes prompt-n))
        "prompt N is a byte prefix of prompt N+1")))
