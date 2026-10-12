(ns seon.render.ns-test
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as sut]
            [seon.render.schema :as render.schema]
            [seon.task :as task]
            [seon.test-support :as support])
  )

(deftest identity-reads-stewardship-from-the-namespace
  (support/with-database
   (fn [connection]
     ;; The first agent created in a namespace stewards it; a later one shares it.
     (support/transacted! connection
                          (support/agent-tx @connection "record-steward" 'my.agents.record))
     (support/transacted! connection
                          (support/agent-tx @connection "record-agent" 'my.agents.record))
     (let [unit {:seon.db/db @connection
                 :seon.agent/id "record-agent"}
           observed (agent/render-identity-ai unit)]
       (is (= "record-agent" (:seon.agent/id observed)))
       (is (= 'my.agents.record
              (get-in observed [:seon.agent/namespace :seon.ns/name])))
       (is (= "record-steward"
              (get-in observed [:seon.agent/namespace :seon.ns/steward
                                :seon.agent/id])))
       (is (some? (:seon.agent/cluster observed)) "an agent names its cluster (648369489); the steward comes from the namespace")
       (is (= ['(help)] (:seon.render/operations observed)))
       (is (= (list 'seon.db/pull (list 'quote (second (second (:seon.repl/form (agent/identity-form unit)))))
                    [:seon.agent/id "record-agent"])
              (:seon.repl/form (agent/identity-form unit)))
           "the form face is the identity read")))))

(defn- namespace-unit
  [db namespace-name distance token-budget]
  (assoc (db/pull db [:db/id
                     :seon.ns/name
                     :seon.ns/source
                     :seon.ns/doc
                     :seon.ns/requires
                     {:seon.ns/aliases
                      [:seon.ns.alias/local :seon.ns.alias/target-ns]}
                     {:seon.ns/imports
                      [:seon.ns.import/local
                       :seon.ns.import/target-class]}
                     {:seon.ns/refers
                      [:seon.ns.refer/local
                       :seon.ns.refer/target-ns
                       :seon.ns.refer/target-name]}]
                 [:seon.ns/name namespace-name])
         :seon.db/db db
         :seon.render/distance distance
         :seon.render/profile
         {:seon.render.profile/token-budget token-budget}))

(deftest a-namespace-renders-as-one-graph-node
  ;; Class regression (the namespace page dump): the node is its declaration,
  ;; doc and public functions with their contracts, as data; never member
  ;; sources or schemas. The value printer bounds a large namespace (root
  ;; ruling 2026-09-26), so the render reads it whole.
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "fresh" 'my.agents.fresh))
      (let [db @connection
            flow (sut/render-ai (namespace-unit db 'seon.flow 1 100000))
            html (hiccup/->string (sut/render-html (namespace-unit db 'seon.flow 1 100000)))
            fresh (sut/render-ai (namespace-unit db 'my.agents.fresh 1 256))
            names (set (map first (:seon.repl/rows flow)))]
        (testing "distance zero is its name and doc line"
          (is (= #{:seon.ns/name :seon.ns/doc} (set (keys (sut/render-ai (namespace-unit db 'seon.flow 0 100000))))))
          (is (= 'seon.flow (:seon.ns/name flow))))
        (testing "distance one is its requires and dir's columns and rows"
          (is (= (sort (:seon.ns/requires (namespace-unit db 'seon.flow 1 100000))) (:seon.ns/requires flow)))
          (is (= [:sym :arglists :summary] (:seon.repl/columns flow)))
          (is (contains? names 'seon.flow/start-work-launcher!))
          (is (not-any? #(= "work-launcher-step" (name %)) names) "private functions are not listed")
          (is (every? #(= 3 (count %)) (:seon.repl/rows flow)) "one row per function, no member source"))
        (testing "HTML is the same node: a namespace link, requires and function names"
          (is (str/includes? html "href=\"/ns/seon.flow\""))
          (is (str/includes? html "<code>start-work-launcher!</code>"))
          (is (not (str/includes? html "seon-namespace-source"))))
        (testing "an empty agent namespace lists no functions and is no error"
          (is (= [] (:seon.repl/rows fresh)))
          (is (= '(dir (quote my.agents.fresh)) (sut/namespace-form {:seon.ns/name 'my.agents.fresh}))
              "the form face is the dir that reads it again"))))))

(deftest a-namespace-directory-resolves-aliased-keywords-and-omits-private-functions
  (support/with-database
    (fn [connection]
      (let [database @connection
            ns-entity (assoc (support/namespace-entity database 'my.dir.sample)
                             :seon.ns/aliases #{{:seon.ns.alias/local 'str :seon.ns.alias/target-ns 'clojure.string}})
            _ (support/transacted! connection [ns-entity])
            database @connection
            functions [(support/program-fn-entity database 'my.dir.sample/shown "(defn shown \"Shown.\" [{id ::str/id}] id)")
                       (support/program-fn-entity database 'my.dir.sample/hidden "(defn- hidden [x] x)")]
            _ (support/transacted! connection functions)
            rows (:seon.repl/rows (sut/render-ai (namespace-unit @connection 'my.dir.sample 1 100000)))]
        (is (= [['my.dir.sample/shown '([{id :clojure.string/id}]) "Shown."]] rows))))))

(deftest namespace-bindings-render-in-clojures-own-words
  (testing "an alias names its target namespace, a refer names its Var"
    (is (= [:article {:class "seon-family-entry seon-namespace-alias-entry"}
            [:p {:class "seon-kicker"} "Namespace alias"]
            [:p [:code "str"] " → " [:code "clojure.string"]]]
           (sut/render-alias-html
            {:seon.ns.alias/local 'str
             :seon.ns.alias/target-ns 'clojure.string}))
        "the alias is shown without a duplicate libspec")
    (is (= [:article {:class "seon-family-entry seon-namespace-refer-entry"}
            [:p {:class "seon-kicker"} "Namespace refer"]
            [:p [:code "q"] " ← " [:code "seon.db/q"]]]
           (sut/render-refer-html
            {:seon.ns.refer/local 'q
             :seon.ns.refer/target-ns 'seon.db
             :seon.ns.refer/target-name 'q})))
    (is (= [:article {:class "seon-family-entry seon-namespace-import-entry"}
            [:p {:class "seon-kicker"} "Namespace import"]
            [:p [:code "Date"] " → " [:code "java.util.Date"]]]
           (sut/render-import-html
            {:seon.ns.import/local 'Date
             :seon.ns.import/target-class 'java.util.Date}))))
  (testing "each AI projection is the binding as data; its form face looks there"
    (is (= {:seon.ns.alias/local 'str :seon.ns.alias/target-ns 'clojure.string}
           (sut/render-alias-ai {:db/id 7 :seon.ns.alias/local 'str
                                 :seon.ns.alias/target-ns 'clojure.string})))
    (is (= '(dir clojure.string) (sut/render-alias-form {:seon.ns.alias/local 'str
                                                          :seon.ns.alias/target-ns 'clojure.string})))
    (is (= '(doc seon.db/q)
           (sut/render-refer-form {:seon.ns.refer/local 'q
                                   :seon.ns.refer/target-ns 'seon.db
                                   :seon.ns.refer/target-name 'q})))
    (is (= {:seon.ns.import/local 'Date :seon.ns.import/target-class 'java.util.Date}
           (sut/render-import-ai {:seon.ns.import/local 'Date
                                  :seon.ns.import/target-class 'java.util.Date})))))

;; DELETED 2026-08-29 (owner gate ruling): compact registered-map
;; rendering is the filed issue
;; docs/seon/issues/registered-render-producers-fall-through-to-generic-map-rendering.md
;; and the S2 (render data) rebuild's seam — the wave restores designed
;; regressions; the doomed pins are parked, not polished.

(deftest namespace-alias-renders-local-and-target-roles
  (let [alias-entity {:seon.ns.alias/local 'str
                   :seon.ns.alias/target-ns 'clojure.string}
        same-name {:seon.ns.alias/local 'seon.fn
                   :seon.ns.alias/target-ns 'seon.fn}
        html (sut/render-alias-html alias-entity)]
    (is (= same-name (sut/render-alias-ai same-name)) "an alias may equal its target")
    (is (str/includes? (hiccup/->string html) "<code>str</code> → <code>clojure.string</code>"))
    (is (not (str/includes? (hiccup/->string html)
                            "[clojure.string :as str]")))))

(deftest task-and-schema-entities-render-as-graph-nodes
  ;; Class regression: a task and a schema are graph nodes with their own pair.
  ;; Distance 0 names the node; distance 1 shows its content with neighbours as names.
  (support/with-database
   (fn [connection]
     (let [handle (task/task-handle 'tn.a "render-node")
           entry (str "[#:seon.task{:name \"render-node\""
                      " :intent \"Render one node with `tn.a/f`.\\nThen stop.\"}]")
           indexed (task/index-tasks! {:seon.db/connection connection :seon.task/files {"resources/seon/tasks/tn.a.edn" entry}})
           database @connection
           unit (fn [lookup distance]
                  (let [value (db/pull database '[*] lookup)]
                    (assoc value :seon.db/db database :seon.render/value value :seon.render/distance distance)))]
       (is (:seon.task/changed? indexed))
       (testing "a task at distance 0 is its name"
         (is (= {:seon.task/id handle :seon.task/sym 'tn.a/render-node :seon.task/name "render-node" :seon.task/ns 'tn.a}
                (sut/task-ai (unit [:seon.task/id handle] 0)))))
       (testing "a task at distance 1 shows its intent and the references it cites; its proofs and subjects are related data"
         (let [shown (sut/task-ai (unit [:seon.task/id handle] 1))]
           (is (= "Render one node with `tn.a/f`.\nThen stop." (:seon.task/intent shown)))
           (is (= #{'tn.a/f} (:seon.task/references shown)))
           (is (= (list 'seon.task/task-edges (list 'seon.db/db) (list 'quote 'tn.a/render-node)) (sut/task-form (unit [:seon.task/id handle] 1)))
               "the form face is the edges read")
           (is (hiccup/hiccup? (sut/task-html (unit [:seon.task/id handle] 1))))))
       (testing "a schema's AI face is data: the form it replaces and what names the key"
         (let [usage (render.schema/render-ai
                      (assoc (unit [:seon.schema/key :seon.task/intent] 1)
                             :seon.render/value {:seon.schema/key :seon.task/intent
                                                 :seon.schema/definition [:string {:min 400}]}))]
           (is (= [:string {:min 1}] (:seon.schema.usage/previous usage)))
           (is (some #{:seon.task/task} (:seon.schema.usage/referenced-by usage)))
           (is (not (contains? usage :seon.schema.usage/check))
               "the render validates nothing: a check is only the registration's record")))
       (testing "listed among others, a schema is its key and doc line as data"
         (is (= {:seon.schema/key :seon.task/intent}
                (render.schema/render-ai (unit [:seon.schema/key :seon.task/intent] 0))))
         (is (str/starts-with? (:seon.schema.usage/doc (render.schema/render-ai (unit [:seon.schema/key :seon.task/ns] 0)))
                               "The owning namespace")))
       (testing "the form face is the usage read, shown and never run"
         (is (= (list 'seon.render.schema/usage '(seon.db/db) :seon.task/intent (list 'quote [:string {:min 1}]))
                (render.schema/render-form (unit [:seon.schema/key :seon.task/intent] 1)))))
       (testing "a stored-data check judges each entity's reconstructed value, once, at registration"
         (let [check (db/stored-value-check database :seon.task/intent [:string {:max 0}])]
           (is (pos? (:seon.schema.usage/checked check)))
           (is (= (:seon.schema.usage/checked check) (:seon.schema.usage/failing check))
               "every stored intent is a non-empty string, so none satisfies an empty one"))
         (let [form (clojure.edn/read-string
                     (db/q '[:find ?f . :in $ ?k :where [?s :seon.schema/key ?k] [?s :seon.schema/form ?f]]
                           database :seon.fn.arity/input-refs))
               check (db/stored-value-check database :seon.fn.arity/input-refs form)]
           (is (zero? (:seon.schema.usage/failing check))
               "a cardinality-many value is judged as its set, never datom by datom (Astra's 200/200)"))
         (is (nil? (binding [db/*read-database* database]
                     (db/registration-check :seon.task/intent [:string {:min 1}])))
             "an unchanged form reads nothing"))))))

(deftest a-function-renders-its-source-by-name
  ;; Class regression: the function node is its authored form; what it calls
  ;; and what calls it are its related data (seon.render.walk/related).
  (support/with-database
    (fn [connection]
      (let [db @connection
            unit (fn [sym distance]
                   (let [value (db/pull db '[*] [:seon.fn/sym sym])]
                     (assoc value :seon.db/db db :seon.render/value value :seon.render/distance distance)))
            launcher (sut/function-ai (unit 'seon.flow/start-work-launcher! 1))]
        (is (= {:seon.fn/sym 'seon.flow/start-work-launcher!
                :seon.fn/doc (first (str/split-lines (:seon.fn/doc (unit 'seon.flow/start-work-launcher! 1))))}
               (sut/function-ai (unit 'seon.flow/start-work-launcher! 0))))
        (is (= (str/split-lines (:seon.fn/source (unit 'seon.flow/start-work-launcher! 1)))
               (:seon.render.ns/source-lines launcher))
            "the source is its lines, so the printer shows its layout unescaped")
        (is (not (contains? launcher :seon.fn/doc)) "a shown source carries the doc, so it is not stated twice")
        (is (hiccup/hiccup? (sut/function-html (unit 'seon.flow/start-work-launcher! 1))))))))

(deftest an-agents-namespace-concern-is-its-name-requires-and-definition-count
  ;; The :seon.agent/namespace concern printed the raw pulled namespace entity
  ;; (:db/id, the escaped ns source; 366 tokens for root on default); its
  ;; render is the namespace's name, doc line, requires and definition count.
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "ns-owner" 'my.agents.ns-owner))
     (let [database @connection
           by-agent (sut/agent-namespace-ai {:seon.db/db database :seon.agent/id "ns-owner"})
           by-value (sut/agent-namespace-ai {:seon.db/db database
                                             :seon.render/value (db/pull database '[*] [:seon.ns/name 'my.agents.ns-owner])})]
       (is (= 'my.agents.ns-owner (:seon.ns/name by-agent)))
       (is (= 0 (:seon.render.ns/definition-count by-agent)))
       (is (vector? (:seon.ns/requires by-agent)))
       (is (not (contains? by-agent :db/id)))
       (is (= by-agent by-value) "the walked namespace and the agent's assignment render alike")
       (is (= '(dir my.agents.ns-owner) (sut/agent-namespace-form {:seon.db/db database :seon.agent/id "ns-owner"}))
           "the form face is the namespace's dir")))))

(deftest a-doc-line-is-the-docstrings-first-sentence
  (let [shown (fn [doc] (:seon.ns/doc (sut/render-ai {:seon.render/value {:seon.ns/name 'seon.example :seon.ns/doc doc}
                                                     :seon.db/db (db/db) :seon.render/distance 0})))]
    (is (= "The one database namespace for all things Datahike."
           (shown "The one database namespace for all things Datahike. Reads and writes use\n  the branch connection.")))
    (is (= "Change supplied setting overrides and return my resulting overrides."
           (shown "Change supplied setting overrides and return\n  my resulting overrides.\n\n  More detail.")))
    (is (= "A summary with no period" (shown "A summary with no period\n\n  Then a body.")))))
