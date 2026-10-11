(ns seon.turn.batch-test
  "What a pass writes for the namespace a definition or a `require` touches,
  read back from the agent's branch after the real MCP entrance (`seon.mcp/call`)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster.host :as host]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.fn :as function]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.operator.runtime :refer [running-instances]]
            [seon.program :as program]))

(defn- eval!
  "`code` through the MCP entrance as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(def ^:private kept
  "The namespace attributes only a namespace's owner writes: the pass must keep them."
  {:seon.ns/name 'merge-m8.fixture
   :seon.ns/doc "A documented namespace."
   :seon.ns/source "(ns merge-m8.fixture)"
   :seon.task/tasks #{'merge-m8.fixture/a-task}
   :seon.fn/file {:seon.fn.file/relative-path "src/merge_m8/fixture.clj"
                 :seon.fn.file/digest (apply str (repeat 64 "a"))}
   :seon.schema.admission/source :core})

(defn- ns-row
  "Namespace `merge-m8.fixture` as stored on `connection`."
  {:malli/schema [:=> [:cat :seon.db/connection] [:maybe :map]]}
  [connection]
  (db/pull (db/db connection)
           [:seon.ns/doc :seon.ns/source :seon.task/tasks :seon.schema.admission/source :seon.program/definition-digest
            {:seon.fn/file [:seon.fn.file/relative-path]} :seon.ns/requires
            {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}]
           [:seon.ns/name 'merge-m8.fixture]))

(deftest a-defn-keeps-its-namespace-row
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (is (= {} (select-keys (db/transact! connection [kept]) [:seon.error/message :seon.error/problems])))
     (let [before (ns-row connection)
           defined (eval! "(in-ns 'merge-m8.fixture) (defn merge-m8-probe [x] x)" agent-id running)]
       (is (= "A documented namespace." (:seon.ns/doc before)))
       (is (some? (:seon.fn/sym (db/pull (db/db connection) [:seon.fn/sym] [:seon.fn/sym 'merge-m8.fixture/merge-m8-probe])))
           (str "the definition is stored: " (keys defined)))
       (is (= before (ns-row connection)) "a definition leaves its namespace row as it was")
       (eval! "(in-ns 'merge-m8.fixture) (require '[clojure.string :as merge-m8-str])" agent-id running)
       (let [after (ns-row connection)]
         (is (= #{'merge-m8-str} (into #{} (map :seon.ns.alias/local) (:seon.ns/aliases after))))
         (is (= (dissoc before :seon.ns/source :seon.program/definition-digest)
                (dissoc after :seon.ns/aliases :seon.ns/requires :seon.ns/source :seon.program/definition-digest))
             "a require changes only the require attributes"))))))

(deftest a-deftest-in-a-namespace-with-stored-refers-is-admitted
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (is (= {} (select-keys (db/transact! connection
                                          [{:seon.ns/name 'merge-m8.test-fixture
                                            :seon.ns/refers #{{:seon.ns.refer/local 'deftest
                                                               :seon.ns.refer/target-ns 'clojure.test
                                                               :seon.ns.refer/target-name 'deftest}
                                                              {:seon.ns.refer/local 'is
                                                               :seon.ns.refer/target-ns 'clojure.test
                                                               :seon.ns.refer/target-name 'is}}}])
                            [:seon.error/message :seon.error/problems])))
     (eval! "(in-ns 'merge-m8.test-fixture) (deftest merge-m8-probe-test (is (= 1 1)))" agent-id running)
     (is (some? (:seon.test/sym (db/pull (db/db connection) [:seon.test/sym]
                                         [:seon.test/sym 'merge-m8.test-fixture/merge-m8-probe-test])))
         "the deftest is admitted to the program under the namespace's stored refers")
     (is (= #{'deftest 'is}
            (into #{} (map :seon.ns.refer/local)
                  (:seon.ns/refers (db/pull (db/db connection) [{:seon.ns/refers [:seon.ns.refer/local]}]
                                            [:seon.ns/name 'merge-m8.test-fixture]))))
         "the namespace keeps its refers"))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/a-proc-step-runs-in-its-environments-world-again}}
  a-removed-namespace-the-agent-stays-in-leaves-diff-answering
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id _connection]
     (eval! "(in-ns 'merge-m8.scratch) (defn merge-m8-scratch [x] x)" agent-id running)
     (eval! "(my.program/remove-ns! 'merge-m8.scratch)" agent-id running)
     ;; the next request starts in the removed namespace, as a REPL's *ns* does
     (eval! "(+ 1 1)" agent-id running)
     (let [reply (eval! "(in-ns 'user) (my.program/diff)"
                        agent-id running)]
       (is (= #{:added :changed :removed}
              (set (keys (:seon.repl/value ((requiring-resolve 'seon.test-support/mcp-reply) reply))))))
       (is (false? (get reply "isError"))
           "diff answers, not a refusal, after the namespace the agent stood in was removed")))))

(deftest a-later-request-redefining-a-contracted-defn-is-admitted-as-written
  ;; each row: request 1 defines, request 2 redefines the same name; the
  ;; second definition is read as the agent's context binds it, never as
  ;; the base it forked from bound the shared Var
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (eval! "(in-ns 'h3.redefine)" agent-id running)
     (doseq [[first-code second-code stored expected]
             [["(defn as-test {:malli/schema [:=> [:cat] :any]} [] 1)"
               "(clojure.test/deftest as-test (clojure.test/is (= 1 1)))"
               #(db/pull % [:seon.test/sym :seon.fn/arities] [:seon.test/sym 'h3.redefine/as-test])
               {:seon.test/sym 'h3.redefine/as-test}]
              ["(defn new-arity {:malli/schema [:=> [:cat :int] :int]} [x] x)"
               "(defn new-arity {:malli/schema [:=> [:cat] :int]} [] 1)"
               #(db/pull % [:seon.fn/spec] [:seon.fn/sym 'h3.redefine/new-arity])
               {:seon.fn/spec "[:=> [:cat] :int]"}]]]
       (eval! first-code agent-id running)
       (let [reply (eval! second-code agent-id running)]
         (is (false? (get reply "isError")) "the redefinition is admitted, not refused")
         (is (= expected (stored (db/db connection)))
             "the stored row is the second definition's, without the first one's contract"))))))

(deftest a-rewritten-namespace-row-stores-the-digest-of-what-it-keeps
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (is (= {} (select-keys (db/transact! connection [kept]) [:seon.error/message :seon.error/problems])))
     (eval! "(in-ns 'merge-m8.fixture) (require '[clojure.set :as merge-m8-set]) (ns-unalias 'merge-m8.fixture 'merge-m8-set)"
            agent-id running)
     (let [database (db/db connection)
           {id :db/id digest :seon.program/definition-digest doc :seon.ns/doc}
           (db/pull database [:db/id :seon.program/definition-digest :seon.ns/doc] [:seon.ns/name 'merge-m8.fixture])]
       (is (= "A documented namespace." doc) "the pass keeps the namespace's doc")
       (is (= (db/namespace-digest-of database id {}) digest)
           "the stored digest is the digest of the stored row, doc and tasks included")))))

(deftest a-repl-require-is-written-back-in-the-ns-form-and-reindexes-equal
  ;; a new agent id gets its own branch off the cluster head, which holds the indexed file
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running _ connection]
     (let [suffix (id/id)
           agent-id (str "ns-form-" suffix)
           fixture-ns (symbol (str "nsform" suffix ".core"))
           path (str "src/nsform" suffix "/core.clj")
           root ((requiring-resolve 'seon.test-support/temp-root) (str "ns-form-test-" suffix))
           file (io/file root path)]
       (io/make-parents file)
       (spit file (str "(ns " fixture-ns "\n  \"Fixture.\"\n  (:require [clojure.string :as str]))\n\n"
                       "(defn joined\n  \"Joined words.\"\n  {:malli/schema [:=> [:cat [:sequential :string]] :string]}\n  [words]\n  (str/join \" \" words))\n\n"
                       "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))\n"))
       (try
         (let [entities (function/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})]
           ((requiring-resolve 'seon.test-support/transacted!)
            connection (function/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
           (eval! (str "(in-ns '" fixture-ns ") (require '[clojure.set :as cset])") agent-id running)
           (eval! "(defn callee\n  \"The union with one.\"\n  {:malli/schema [:=> [:cat [:set :int]] [:set :int]]}\n  [xs]\n  (cset/union xs #{1}))"
                  agent-id running)
           ;; `my.program/write-branch!` compares with the cluster's home branch, which this
           ;; harness's member branch is not: write back between the fork point and the branch head
           (let [base (db/commit-id (db/db connection))
                 branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id agent-id]))
                 agent-connection (-> (host/hosted (get-in @running-instances [running :seon.turn.loop/cluster]) branch)
                                      ::host/execution :seon.db/connection)
                 written (source/write-back!
                          {:seon.db/connection agent-connection :seon.fn/root root
                           :seon.source/accepted {:seon.program/identities #{[:seon.ns/name fixture-ns]
                                                                             [:seon.fn/sym (symbol (str fixture-ns) "callee")]}
                                                  :seon.source/base-commit-id base
                                                  :seon.source/commit-id (db/commit-id (db/db agent-connection))}})
                 held (into {} (map (juxt :seon.fn/sym :seon.program/definition-digest))
                            (db/q '[:find [(pull ?f [:seon.fn/sym :seon.program/definition-digest]) ...]
                                    :in $ ?n :where [?f :seon.fn/sym ?s] [(namespace ?s) ?ns] [(= ?ns ?n)]]
                                  (db/db agent-connection) (str fixture-ns)))
                 reindexed (function/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})]
             (is (= {} (select-keys written [:seon.error/message :seon.source/write-back-refused :seon.error/offending]))
                 "write-back answers no refusal")
             (is (= [path] (:seon.source/changed-paths written)) "write-back writes the namespace's file")
             (is (= held (into {} (keep (juxt :seon.fn/sym :seon.program/definition-digest))
                               (filter :seon.fn/sym reindexed)))
                 "the written file reindexes to the branch's digests")
             (is (= {'str 'clojure.string 'cset 'clojure.set}
                    (into {} (comp (filter #(= fixture-ns (:seon.ns/name %))) (mapcat :seon.ns/aliases)
                                   (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns)))
                          reindexed))
                 "the written ns form declares the alias the REPL added")))
         (finally
           (when-let [branch (:seon.agent/branch (db/pull (db/db connection) [:seon.agent/branch] [:seon.agent/id agent-id]))]
             (some-> (host/hosted (get-in @running-instances [running :seon.turn.loop/cluster]) branch) host/retire!))
           ((requiring-resolve 'seon.test-support/delete-root!) root)))))))

(deftest an-unchanged-committed-nilable-contract-is-admitted-and-written-back
  ;; the nilable-return rule judges a new or changed contract; one equal to
  ;; the committed contract keeps its committed admission at the REPL and in
  ;; write-back's reindex
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id connection]
     (let [suffix (id/id)
           fixture-ns (symbol (str "nilable" suffix ".core"))
           path (str "src/nilable" suffix "/core.clj")
           root ((requiring-resolve 'seon.test-support/temp-root) (str "nilable-test-" suffix))
           file (io/file root path)
           contract "{:malli/schema [:=> [:cat [:sequential :int]] [:maybe :int]]}"
           row (fn [sym] (db/pull (db/db connection) [:seon.fn/doc :seon.fn/spec]
                                  [:seon.fn/sym (symbol (str fixture-ns) sym)]))]
       (io/make-parents file)
       (spit file (str "(ns " fixture-ns "\n  \"Fixture.\")\n\n"
                       "(defn head\n  \"The first number.\"\n  " contract "\n  [xs]\n  (first xs))\n\n"
                       "(defn callee\n  \"Add one.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 1))\n"))
       (try
         (let [entities (function/analyze-entities {:seon.fn/root root :seon.fn/roots ["src"]})]
           ((requiring-resolve 'seon.test-support/transacted!)
            connection (function/reconcile-tx (db/db connection) entities (mapv program/row-identity entities)))
           (let [base (db/commit-id (db/db connection))]
             (eval! (str "(in-ns '" fixture-ns ")") agent-id running)
             (eval! (str "(defn head\n  \"The first number, if any.\"\n  " contract "\n  [xs]\n  (first xs))")
                    agent-id running)
             (is (= "The first number, if any." (:seon.fn/doc (row "head")))
                 "a new body under the committed nilable contract is admitted")
             (eval! "(defn head {:malli/schema [:=> [:cat [:sequential :int]] [:maybe :string]]} [xs] (some-> xs first str))"
                    agent-id running)
             (is (= "[:=> [:cat [:sequential :int]] [:maybe :int]]" (:seon.fn/spec (row "head")))
                 "a changed nilable contract is still refused")
             (eval! "(defn head\n  \"The first number.\"\n  {:malli/schema [:=> [:cat [:sequential :int]] [:maybe :int]]}\n  [xs]\n  (first xs))"
                    agent-id running)
             (eval! "(defn callee\n  \"Add two.\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  (+ x 2))"
                    agent-id running)
             (let [written (source/write-back!
                            {:seon.db/connection connection :seon.fn/root root
                             :seon.source/accepted {:seon.program/identities #{[:seon.fn/sym (symbol (str fixture-ns) "callee")]}
                                                    :seon.source/base-commit-id base
                                                    :seon.source/commit-id (db/commit-id (db/db connection))}})]
               (is (= {} (select-keys written [:seon.error/message :seon.source/write-back-refused :seon.error/offending]))
                   "write-back of another function reindexes the unchanged nilable one equal")
               (is (= [path] (:seon.source/changed-paths written)) "write-back writes the namespace's file"))))
         (finally
           ((requiring-resolve 'seon.test-support/delete-root!) root)))))))
