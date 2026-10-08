(ns seon.turn.batch-test
  "What a pass writes for the namespace a definition or a `require` touches,
  read back from the agent's branch after the real MCP entrance (`seon.mcp/call`)."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.mcp :as mcp]))

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

(deftest ^{:seon/tasks #{'seon.turn.batch/a-defn-rewrites-its-namespace-row}} a-defn-keeps-its-namespace-row
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

(deftest ^{:seon/tasks #{'seon.turn.batch/a-repl-deftest-in-a-file-backed-test-namespace-is-refused}}
  a-deftest-in-a-namespace-with-stored-refers-is-admitted
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

(deftest ^{:seon/tasks #{'seon.program/an-in-ns-namespace-blocks-diff-and-write-branch}}
  a-removed-namespace-the-agent-stays-in-leaves-diff-answering
  ((requiring-resolve 'seon.test-support/with-running-agent)
   (fn [running agent-id _connection]
     (eval! "(in-ns 'merge-m8.scratch) (defn merge-m8-scratch [x] x)" agent-id running)
     (eval! "(my.program/remove-ns! 'merge-m8.scratch)" agent-id running)
     ;; the next request starts in the removed namespace, as a REPL's *ns* does
     (eval! "(+ 1 1)" agent-id running)
     (let [reply (eval! "(in-ns 'user) (when (:seon.program/missing-evidence (my.program/diff)) (throw (ex-info \"diff refused\" {})))"
                        agent-id running)]
       (is (false? (get reply "isError"))
           "diff answers, not a refusal, after the namespace the agent stood in was removed")))))

(deftest ^{:seon/tasks #{'seon.program/a-deftest-over-a-contracted-defn-refuses-its-contract-facts}}
  a-later-request-redefining-a-contracted-defn-is-admitted-as-written
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

(deftest ^{:seon/tasks #{'my.program/write-back-refuses-a-file-whose-unchanged-functions-reindex-differently}}
  a-rewritten-namespace-row-stores-the-digest-of-what-it-keeps
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
