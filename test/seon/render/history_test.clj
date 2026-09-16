(ns seon.render.history-test
  "Class regressions for self-generating, append-only REPL history."
  (:require [clojure.test :refer [deftest is testing]]
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
     (support/transacted!
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
                                   :seon.message/to
                                   :seon.message/inbox
                                   :seon.agent/agent}
                                 :seon.schema/key))
                   (schema/canonical-schema-rows)))
     (support/transacted!
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
         (is (= '(my.message/read #:my.message{:id "history-message"})
                (transcript/message-form message))))
       (testing "an attribute declaration precedes the landed entity shape"
         (is (= 'seon.render.transcript/inbox-form
                (selected message :seon.message/inbox)))
         (let [recipient (:seon.message/inbox (render/transacted message))
               form (transcript/inbox-form recipient)]
           (is (= 'seon.db/pull (first form)))
           (is (contains? (first (second (second form))) :seon.message/_inbox)
               (pr-str form))
           (is (= recipient (last form)))))
       (testing "an attribute with no declared pair reaches the generic printer"
         ;; Owner ruling 2026-09-17 (decision 9, option 1): asking about one
         ;; attribute never silently answers about the entity, so every
         ;; uncurated attribute is visibly generic and therefore findable.
         ;; `:seon.message/to` is declared with no render pair while the
         ;; message entity map declares `message-form`.
         (is (= 'seon.render/render-form
                (selected message :seon.message/to)))
         (is (= '(seon.db/pull (quote [*]) [:seon.message/id "history-message"])
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
           ;; `seon.cluster.agent/situation-form` is declared on the DERIVED
           ;; situation map, not on the agent's attribute map, which declares
           ;; no form pair at all — so the floor answers with the identity
           ;; read the projection declares.
           (is (= 'seon.render/render-form producer))
           (is (= '(seon.db/pull (quote [*]) [:seon.agent/id "history-agent"])
                  form))))
       (testing "an absent attribute reaches the floor, not the entity's pair"
         (let [request (assoc (render-request database ctx namespace-entity)
                              :seon.render.walk/attribute :seon.ns/requires)
               producer (selected namespace-entity :seon.ns/requires)
               form (render/render-form request)]
           ;; `:seon.ns/requires` declares no render pair and this namespace
           ;; carries none, while the namespace entity map declares
           ;; `namespace-form`. Before the ruling the request silently became
           ;; an entity-scoped one and answered `(dir 'fixture.history)`.
           (is (= 'seon.render/render-form producer))
           (is (= '(seon.db/pull (quote [*]) [:seon.ns/name fixture.history])
                  form))))))))

;; THE NEIGHBOUR CASE. The walk stamps on a member both the attribute it
;; reached an entity THROUGH and, for a declared concern, the attribute the
;; member stands FOR. Only the second scopes a render request: a neighbour is
;; never the attribute's value, so it renders by its own declared shape.
;; Removing the owning-entity fallback (owner ruling 2026-09-17, decision 9
;; option 1) must not take this with it — here the namespace reached through
;; `:seon.agent/namespace` must still answer `(dir 'fixture.neighbour)` and
;; not the generic entity pull the floor would give an attribute-scoped
;; request.
(deftest a-neighbour-the-walk-reached-renders-by-its-own-shape
  (support/with-database
   (fn [connection]
     (support/transacted!
      connection
      (into []
            (filter (comp #{:seon.render/form
                            :seon.render/output
                            :seon.render/rendered
                            :seon.render/call-request
                            :seon.ns/ns
                            :seon.agent/agent}
                          :seon.schema/key))
            (schema/canonical-schema-rows)))
     (support/transacted!
      connection
      [{:seon.ns/name 'fixture.neighbour}
       {:seon.agent/id "neighbour-agent"
        :seon.agent/namespace [:seon.ns/name 'fixture.neighbour]}])
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection)
           agent-lookup [:seon.agent/id "neighbour-agent"]
           namespace-lookup [:seon.ns/name 'fixture.neighbour]
           request (assoc (render-request database ctx
                                          (db/pull database '[*] agent-lookup))
                          :seon.render/distance 1
                          :seon.render.walk/lookup agent-lookup)
           units (walk/neighborhood request)
           namespace-unit
           (first (filter #(and (= namespace-lookup (:seon.render.walk/lookup %))
                                (:seon.render/output %))
                          units))]
       (is namespace-unit (pr-str (mapv :seon.render.walk/lookup units)))
       (is (= :seon.agent/namespace
              (:seon.render.walk/attribute namespace-unit))
           (pr-str namespace-unit))
       (is (nil? (:seon.error/value namespace-unit)) (pr-str namespace-unit))
       (is (= '(dir (quote fixture.neighbour))
              (:seon.render/output namespace-unit))
           (pr-str namespace-unit))))))

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
    :seon.repl/subject [:seon.ns/name 'my.turn]
    :seon.repl/entry {:seon.repl/form '(dir (quote my.turn))}}
   {:seon.repl/key :complete-doc
    :seon.repl/subject [:seon.fn/sym "my.turn/complete"]
    :seon.repl/entry {:seon.repl/form '(doc (quote my.turn/complete))}}
   {:seon.repl/key :message-namespace
    :seon.repl/subject [:seon.ns/name 'my.message]
    :seon.repl/entry {:seon.repl/form '(dir (quote my.message))}}
   {:seon.repl/key :inbox-doc
    :seon.repl/subject [:seon.fn/sym "my.message/inbox"]
    :seon.repl/entry {:seon.repl/form '(doc (quote my.message/inbox))}}
   {:seon.repl/key :read-doc
    :seon.repl/subject [:seon.fn/sym "my.message/read"]
    :seon.repl/entry {:seon.repl/form '(doc (quote my.message/read))}}
   {:seon.repl/key :inbox
    :seon.repl/subject [:seon.fn/sym "my.message/inbox"]
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
        candidates (into [] (remove #(contains? #{:run-namespace :complete-doc}
                                                (:seon.repl/key %)))
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
