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
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as kernel]
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
                            :seon.message/message
                            :seon.message/to}
                          :seon.schema/key))
            (schema/canonical-schema-rows)))
     (db/transact!
      connection
      [{:seon.ns/name 'fixture.history}
       {:seon.agent/id "history-agent"
        :seon.agent/namespace [:seon.ns/name 'fixture.history]}
       {:seon.message/id "history-message" :seon.message/to [:seon.agent/id "history-agent"] :seon.message/content "Read me." :seon.message/inbox [:seon.agent/id "history-agent"]}])
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
                    [:seon.message/id "history-message"])]
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
                (selected message :seon.message/to)))
         (is (= '(my.message/inbox)
                (transcript/inbox-form
                 (:seon.message/to (render/transacted message)))))
         (is (= '(my.message/inbox)
                (render/render-form-value
                 (assoc (render-request database ctx message)
                        :seon.render.walk/attribute
                        :seon.message/to)))))
       (testing "the entity floor uses the projection's declared identity"
         (let [agent-entity (db/pull database '[*]
                                     [:seon.agent/id "history-agent"])
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
  (support/with-database
   (fn [connection]
     (let [valid? #'render/valid-projection?
           projection (kernel/context-projection
                       (support/fork-cluster-ctx connection))
           entry {:seon.repl/comment "; think"
                  :seon.repl/form '(help)}]
       (is (valid? projection :seon.render/form '(help)))
       (is (valid? projection :seon.render/form entry))
       (is (valid? projection :seon.render/form
                   [entry {:seon.repl/form '(dir 'my.turn)}]))
       (is (not (valid? projection :seon.render/form
                        {:seon.repl/comment "; no act"})))
       (is (valid? projection :seon.render/form
                   {:seon.error/kind :seon.render/failure
                    :seon.error/message "failed"}))))))

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
    :seon.repl/subject [:seon.agent/id "worker"]
    :seon.repl/entry {:seon.repl/form '(help)}}
   {:seon.repl/key :run-namespace
    :seon.repl/subject 'my.turn
    :seon.repl/entry {:seon.repl/form '(dir (quote my.turn))}}
   {:seon.repl/key :complete-doc
    :seon.repl/subject 'my.turn/complete
    :seon.repl/entry {:seon.repl/form '(doc (quote my.turn/complete))}}
   {:seon.repl/key :message-namespace
    :seon.repl/subject 'my.message
    :seon.repl/entry {:seon.repl/form '(dir (quote my.message))}}
   {:seon.repl/key :inbox-doc
    :seon.repl/subject 'my.message/inbox
    :seon.repl/entry {:seon.repl/form '(doc (quote my.message/inbox))}}
   {:seon.repl/key :read-doc
    :seon.repl/subject 'my.message/read
    :seon.repl/entry {:seon.repl/form '(doc (quote my.message/read))}}
   {:seon.repl/key :inbox
    :seon.repl/subject 'my.message/inbox
    :seon.repl/entry {:seon.repl/form '(my.message/inbox)}}
   {:seon.repl/key :message
    :seon.repl/subject [:seon.message/id "task-1"]
    :seon.repl/entry {:seon.repl/form '(my.message/read "task-1")}}])

(defn- episode-request
  [candidates settled]
  {:seon.repl/root-key :root
   :seon.repl/candidates candidates
   :seon.repl/settled settled
   :seon.print/identity-attributes
   #{:seon.agent/id :seon.message/id :seon.ns/name}})

(deftest generated-episodes-have-two-independent-gates
  (let [settled
        [{:seon.repl/key :root
          :seon.sci.admit/print-node
          (settled-node {:seon.agent/id "worker"
                         :seon.agent/protocol-namespaces
                         ['my.message 'my.turn]
                         :outside/reference 'outside.ns})}
         {:seon.repl/key :run-namespace
          :seon.sci.admit/print-node
          (settled-node ['my.turn/complete 'my.turn/wait])}
         {:seon.repl/key :message-namespace
          :seon.sci.admit/print-node
          (settled-node ['my.message/inbox 'my.message/read])}
         {:seon.repl/key :complete-doc
          :seon.sci.admit/print-node (settled-node nil)}
         {:seon.repl/key :inbox-doc
          :seon.sci.admit/print-node (settled-node nil)}
         {:seon.repl/key :read-doc
          :seon.sci.admit/print-node (settled-node nil)}
         {:seon.repl/key :inbox
          :seon.sci.admit/print-node
          (settled-node [{:seon.message/id "task-1"}])}]
        candidates episode-candidates
        result (walk/ordered-episode (episode-request candidates settled))
        episode-keys (mapv :seon.repl/key result)]
    (is (= [:root :run-namespace :complete-doc :message-namespace
            :inbox-doc :read-doc :inbox :message]
           episode-keys)
        "listings and docs explain each later use in carried fact order")
    (is (< (.indexOf episode-keys :inbox) (.indexOf episode-keys :message))
        "an entity id must appear in the inbox value before its read")
    (is (not (some #(= '(dir (quote outside.ns))
                       (:seon.repl/form %))
                   result))
        "an introduced symbol with no pulled candidate grows nothing")
    (is (= result
           (walk/ordered-episode (episode-request candidates settled)))
        "the same pull and settled values derive byte-identical data")))

(deftest every-emitted-form-is-at-the-explained-set-fixed-point
  (let [settled
        [{:seon.repl/key :root
          :seon.sci.admit/print-node
          (settled-node {:seon.agent/protocol-namespaces
                         ['my.message]})}
         {:seon.repl/key :message-namespace
          :seon.sci.admit/print-node
          (settled-node ['my.message/inbox 'my.message/read])}
         {:seon.repl/key :inbox-doc
          :seon.sci.admit/print-node (settled-node nil)}
         {:seon.repl/key :inbox
          :seon.sci.admit/print-node
          (settled-node [{:seon.message/id "task-1"}])}
         {:seon.repl/key :read-doc
          :seon.sci.admit/print-node (settled-node nil)}]
        candidates (remove #(contains? #{:run-namespace :complete-doc}
                                       (:seon.repl/key %))
                           episode-candidates)
        episode (walk/ordered-episode (episode-request candidates settled))]
    (is (= [:root :message-namespace :inbox-doc :read-doc :inbox :message]
           (mapv :seon.repl/key episode)))
    (is (= '(my.message/read "task-1")
           (:seon.repl/form (peek episode))))))

(deftest the-generated-prefix-stops-at-the-first-unsettled-entry
  (let [root-settled
        [{:seon.repl/key :root
          :seon.sci.admit/print-node
          (settled-node {:seon.agent/protocol-namespaces ['my.turn]})}]
        result (walk/ordered-episode
                (episode-request episode-candidates root-settled))]
    (is (= [:root :run-namespace] (mapv :seon.repl/key result)))
    (is (= '(dir (quote my.turn)) (:seon.repl/form (peek result))))))
