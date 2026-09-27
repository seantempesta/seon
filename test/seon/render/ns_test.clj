(ns seon.render.ns-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.render.hiccup :as hiccup]
            [seon.render.ns :as sut]
            [seon.issue :as issue]
            [seon.test-support :as support])
  (:import [java.io PushbackReader StringReader]))

(deftest identity-reads-stewardship-without-an-agent-cluster-attribute
  (support/with-database
   (fn [connection]
     ;; The first agent created in a namespace stewards it; a later one shares it.
     (support/transacted! connection
                          (support/agent-tx @connection "record-steward" 'my.agents.record))
     (support/transacted! connection
                          (support/agent-tx @connection "record-agent" 'my.agents.record))
     (let [unit {:seon.db/db @connection
                 :seon.agent/id "record-agent"}
           source (agent/render-identity-ai unit)
           form (with-open [reader (PushbackReader. (StringReader. source))]
                  (is (= '(help) (read reader)))
                  (read reader))
           selector (second (second form))
           observed (db/pull @connection selector (nth form 2))]
       (is (= "record-agent" (:seon.agent/id observed)))
       (is (= 'my.agents.record
              (get-in observed [:seon.agent/namespace :seon.ns/name])))
       (is (= "record-steward"
              (get-in observed [:seon.agent/namespace :seon.ns/steward
                                :seon.agent/id])))
       (is (not (str/includes? source ":seon.agent/cluster")))))))

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

(deftest a-large-namespace-renders-less-and-names-how-to-continue
  ;; Root ruling 2026-09-26: a pair must not fail on a large namespace. The
  ;; members are one reverse pull; the AI face shows the first
  ;; max-collection names with the total and `dir`; HTML shows them all.
  (support/with-database
    (fn [connection]
      (let [caps (assoc (config/result-caps (support/effective-config))
                        :seon.config.eval.result/max-collection 1)
            unit (assoc (namespace-unit @connection 'seon.flow 1 100000)
                        :seon.sci.admit/caps caps)
            ai (sut/render-ai unit)]
        (is (string? ai) (pr-str ai))
        (is (re-find #"\n;; public functions \(first 1 of \d+ by name; `dir` below lists every one\): \S+\n" ai) ai)
        (is (str/includes? ai "(dir seon.flow)"))
        (is (vector? (sut/render-html unit)))))))

(deftest a-namespace-renders-as-one-graph-node
  ;; Class regression (the namespace page dump): the node is its declaration,
  ;; doc and public functions by name; never member sources or schemas.
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "fresh" 'my.agents.fresh))
      (let [db @connection
            flow (sut/render-ai (namespace-unit db 'seon.flow 1 100000))
            html (hiccup/->string (sut/render-html (namespace-unit db 'seon.flow 1 100000)))
            fresh (sut/render-ai (namespace-unit db 'my.agents.fresh 1 256))]
        (testing "distance zero is its one header line"
          (is (str/starts-with? (sut/render-ai (namespace-unit db 'seon.flow 0 100000)) ";; Namespace seon.flow — "))
          (is (= 1 (count (str/split-lines (sut/render-ai (namespace-unit db 'seon.flow 0 100000)))))))
        (testing "distance one is the stored ns form, public functions by name, then dir"
          (is (str/includes? flow (:seon.ns/source (namespace-unit db 'seon.flow 1 100000))))
          (is (re-find #"\n;; public functions: .*start-work-launcher!" flow))
          (is (not (str/includes? flow "work-launcher-step")) "private functions are not named")
          (is (not (str/includes? flow "(defn ")) "no member source")
          (is (str/ends-with? flow "(dir seon.flow)")))
        (testing "HTML is the same node: a namespace link, requires and function names"
          (is (str/includes? html "href=\"/ns/seon.flow\""))
          (is (str/includes? html "<code>start-work-launcher!</code>"))
          (is (not (str/includes? html "seon-namespace-source"))))
        (testing "an empty agent namespace names no functions and is no error"
          (is (str/includes? fresh ";; public functions: none"))
          (is (str/ends-with? fresh "(dir my.agents.fresh)")))))))


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
  (testing "each AI projection is source: a teaching comment, then a form"
    (is (= (str ";; Here `str` is an alias for clojure.string, so `str/name`"
                " reads a Var\n"
                ";; in that namespace — the `:as` half of"
                " [clojure.string :as str].\n"
                "(dir (quote clojure.string))")
           (sut/render-alias-ai {:seon.ns.alias/local 'str
                                 :seon.ns.alias/target-ns 'clojure.string})))
    (is (= (str ";; Here the bare symbol `q` is seon.db/q —\n"
                ";; the `:refer` half of [seon.db :refer [q]].\n"
                "(doc seon.db/q)")
           (sut/render-refer-ai {:seon.ns.refer/local 'q
                                 :seon.ns.refer/target-ns 'seon.db
                                 :seon.ns.refer/target-name 'q})))))

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
    ;; THE AI PROJECTION IS SOURCE (owner ruling 2026-09-07): teaching
    ;; comments, then a form the agent can rerun. It stopped being a bare
    ;; quoted libspec, which told the agent nothing it could act on.
    (is (str/includes? (sut/render-alias-ai alias-entity)
                       "(dir (quote clojure.string))"))
    (is (str/includes? (sut/render-alias-ai same-name)
                       "(dir (quote seon.fn))"))
    (is (str/includes? (hiccup/->string html) "<code>str</code> → <code>clojure.string</code>"))
    (is (not (str/includes? (hiccup/->string html)
                            "[clojure.string :as str]")))))

(deftest task-and-schema-entities-render-as-graph-nodes
  ;; Class regression: a task and a schema are graph nodes with their own pair.
  ;; Distance 0 names the node; distance 1 shows its content with neighbours as names.
  (support/with-database
   (fn [connection]
     (let [handle (issue/task-handle 'tn.a "render-node" :function)
           entry (str "[#:seon.task{:id \"" handle "\" :name \"render-node\" :kind :function"
                      " :intent \"Render one node.\\nThen stop.\" :must-read #{tn.a/f}}]")
           indexed (issue/index-tasks! {:seon.db/connection connection :seon.task/files {"resources/seon/tasks/tn.a.edn" entry}})
           database @connection
           unit (fn [lookup distance]
                  (let [value (db/pull database '[*] lookup)]
                    (assoc value :seon.db/db database :seon.render/value value :seon.render/distance distance)))
           forms (fn [source] (read-string (str "[" source "]")))]
       (is (:seon.task/changed? indexed) (pr-str indexed))
       (testing "a task at distance 0 is its one header line"
         (is (= (str ";; Task " handle " \"render-node\": a function task in tn.a")
                (sut/task-ai (unit [:seon.task/id handle] 0)))))
       (testing "a task at distance 1 shows its intent, derived done-when and names, then the expansion call"
         (let [source (sut/task-ai (unit [:seon.task/id handle] 1))]
           (is (str/includes? source ";; Intent: Render one node.\n;;         Then stop."))
           (is (str/includes? source ";; Done when these proofs pass: none"))
           (is (str/includes? source ";; Must read: tn.a/f"))
           (is (= [(list 'seon.issue/task-edges (list 'seon.db/db) handle)] (forms source)))
           (is (hiccup/hiccup? (sut/task-html (unit [:seon.task/id handle] 1))))))
       (testing "a schema renders as the register! form of its stored definition"
         (let [source (sut/schema-ai (unit [:seon.schema/key :seon.task/intent] 1))]
           (is (= [(list 'seon.schema/register! :seon.task/intent [:string {:min 1}])] (forms source)))
           (is (= ";; Schema :seon.task/intent" (sut/schema-ai (unit [:seon.schema/key :seon.task/intent] 0))))))
       (testing "a described schema's header carries its doc line"
         (is (str/starts-with? (sut/schema-ai (unit [:seon.schema/key :seon.task/ns] 0))
                               ";; Schema :seon.task/ns — The owning namespace")))))))

(deftest a-function-renders-its-source-and-neighbours-by-name
  ;; Class regression: the function node is its authored form plus callees and
  ;; callers as names; a widely called function renders less, never a clipped list.
  (support/with-database
    (fn [connection]
      (let [db @connection
            unit (fn [sym distance]
                   (let [value (db/pull db '[*] [:seon.fn/sym sym])]
                     (assoc value :seon.db/db db :seon.render/value value :seon.render/distance distance)))
            launcher (sut/function-ai (unit 'seon.flow/start-work-launcher! 1))
            query (sut/function-ai (unit 'seon.db/q 1))]
        (is (= (str ";; seon.flow/start-work-launcher! — " (first (str/split-lines (:seon.fn/doc (unit 'seon.flow/start-work-launcher! 1)))))
               (sut/function-ai (unit 'seon.flow/start-work-launcher! 0))))
        (is (str/includes? launcher (str "\n" (:seon.fn/source (unit 'seon.flow/start-work-launcher! 1)) "\n;; calls: ")))
        (is (re-find #"\n;; called by: \d+ functions, listed by \(my\.program/callers \{:seon\.program/subject 'seon\.db/q\}\)$" query))
        (is (hiccup/hiccup? (sut/function-html (unit 'seon.flow/start-work-launcher! 1))))))))
