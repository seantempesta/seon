(ns seon.sci.library-aliases-test
  "An agent in a file namespace resolves the libraries its ns form requires
  through their aliases and full names, as the namespace's compiled code does."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- step
  "The parsed items of `agent-id`'s eval request over `code`, in SCI mode or `mode`."
  {:malli/schema [:function
                  [:=> [:cat :string :string :string] [:sequential :seon.schema/value]]
                  [:=> [:cat :string :string :string :string] [:sequential :seon.schema/value]]]}
  ([code agent-id running] (step code agent-id running "sci"))
  ([code agent-id running mode]
   (map #(support/reply (get % "text"))
        (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                       "arguments" {"code" code "agent" agent-id "cluster" running
                                                                    "mode" mode}})))
             "content"))))

(defn- host-state
  "What the host JVM holds of `namespace-name`: whether it is loaded, and the
  identity of each named Var's root there."
  {:malli/schema [:=> [:cat :symbol [:sequential :qualified-symbol]] :map]}
  [namespace-name symbols]
  {:loaded? (some? (find-ns namespace-name))
   :roots (into {} (map (fn [sym] [sym (some-> (find-ns (symbol (namespace sym)))
                                               (ns-resolve (symbol (name sym)))
                                               deref
                                               System/identityHashCode)]))
                symbols)})

(deftest ^{:seon.test/long "three MCP eval requests on a fresh agent branch"
           :seon.test/long-ms 15000}
  a-file-namespace-resolves-its-library-aliases
  ;; seon.error requires [malli.core :as m] and [malli.error :as me]; nothing
  ;; in the agent's requests requires either
  (support/with-running-agent
   (fn [running agent-id _]
     (let [prefix "(in-ns 'seon.error) "
           items (fn [code] (step (str prefix code) agent-id running))
           values (fn [code] (keep :seon.repl/value (items code)))]
       (is (= [:int :int]
              (rest (values "(m/type (m/schema :int)) (malli.core/type :int)"))))
       (let [defined (items (str "(defn alias-probe {:malli/schema [:=> [:cat :any] :string]} [x]"
                                 " (or (me/error-message {:schema (m/schema :int) :value x}) \"\"))"))]
         (is (= ['seon.error/alias-probe []]
                [(some #(get-in % [:seon.repl/value :seon.render.var/symbol]) defined)
                 (keep :seon.repl/error defined)])
             "the defn using the file's aliases is admitted without error"))
       (is (= "should be an integer" (last (values "(alias-probe :a)"))))))))

(deftest ^{:seon.test/long "two MCP eval requests on a fresh agent branch, the second running its branch test step"
           :seon.test/long-ms 15000}
  an-agent-in-a-test-namespace-writes-a-proof-with-its-helpers
  ;; my.program-test aliases seon.test-support as support and defines the
  ;; private helper shown-value; neither is required by the agent
  (support/with-running-agent
   (fn [running agent-id _]
     (let [values (fn [items] (keep :seon.repl/value items))
           entered (step "(in-ns 'my.program-test) [(fn? support/agent-value) (fn? shown-value)]" agent-id running)
           proof (step (str "(deftest agent-written-proof"
                            " (is (fn? support/agent-value)) (is (fn? shown-value)))")
                       agent-id running)]
       (is (= [true true] (last (values entered))) "the file's alias and private helper resolve")
       (is (= [] (keep :seon.repl/error proof)) "the deftest using them is admitted")
       (is (= {:seon.test/passed? true :seon.turn.loop/red []}
              (some #(get-in % [:seon.turn.loop/tests :seon.turn.loop/reaching]) proof))
           "the branch test step runs the new test green")))))

(deftest ^{:seon/tasks #{'seon.sci.lookup/an-agent-runs-its-proofs-with-clojure-test}
           :seon.test/long "one MCP eval request on a fresh agent branch that runs a named test through my.test/check"
           :seon.test/long-ms 15000}
  an-agent-requires-a-test-namespace-and-runs-its-deftest
  ;; seon.id-test is a test namespace the agent's base context holds empty
  (support/with-running-agent
   (fn [running agent-id _]
     (let [items (step (str "(require 'seon.id-test)"
                            " (def run (clojure.test/run-test-var #'seon.id-test/a-host-object-has-no-identity))"
                            " [(select-keys run [:test :fail :error :type]) (mapv :seon.test/sym (:seon.test/results run))"
                            "  (string? (:seon.test.run/id run))]")
                       agent-id running)]
       (is (= [] (keep :seon.repl/error items)) "the require and the run answer without error")
       (is (= [{:test 1 :fail 0 :error 0 :type :summary} ['seon.id-test/a-host-object-has-no-identity] true]
              (last (keep :seon.repl/value items)))
           "the deftest ran through the recorded test path and answers its summary")))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-test-namespace-an-agent-requires-is-interpreted-not-host-loaded}
           :seon.test/long "one MCP eval request on a fresh agent branch that runs a named test through my.test/check"
           :seon.test/long-ms 15000}
  a-test-namespace-an-agent-runs-is-interpreted-and-never-loads-into-the-host
  ;; the deftest runs from seon.id-test's rows in the agent's SCI world
  (support/with-running-agent
   (fn [running agent-id _]
     (let [before (host-state 'seon.id-test ['seon.id-test/a-host-object-has-no-identity])
           items (step (str "(require 'seon.id-test)"
                            " (select-keys (clojure.test/run-test-var #'seon.id-test/a-host-object-has-no-identity)"
                            "  [:test :fail :error])")
                       agent-id running)]
       (is (= {:test 1 :fail 0 :error 0} (last (keep :seon.repl/value items))) "the deftest ran green")
       (is (= before (host-state 'seon.id-test ['seon.id-test/a-host-object-has-no-identity]))
           "the host JVM neither loaded nor reloaded the test namespace")))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-test-namespace-an-agent-requires-is-interpreted-not-host-loaded}
           :seon.test/long "two MCP eval requests on a fresh agent branch, the second running a file test through my.test/check"
           :seon.test/long-ms 15000}
  a-file-test-run-at-the-repl-sees-the-branch-redefinition-of-its-subject
  (support/with-running-agent
   (fn [running agent-id _]
     (let [before (host-state 'seon.program-test [])
           redefined (step (str "(in-ns 'seon.program)"
                                " (defn- edn-symbol-text?"
                                "  \"Whether `s` reads as one symbol token; this branch refuses every text.\""
                                "  {:malli/schema [:=> [:cat :string] :boolean]} [^String s] false)")
                           agent-id running)
           run (step (str "(require 'seon.program-test)"
                          " (select-keys (clojure.test/run-test-var"
                          " #'seon.program-test/edn-symbol-text-agrees-with-the-reader-on-slash-led-text)"
                          " [:test :pass :fail :error])")
                     agent-id running)]
       (is (= [] (keep :seon.repl/error redefined)) "the branch redefinition is admitted")
       (is (= {:test 1 :pass 5 :fail 3 :error 0} (last (keep :seon.repl/value run)))
           "the three texts that read as symbols now fail against the branch's definition")
       (is (= before (host-state 'seon.program-test [])) "the host JVM did not load the test namespace")))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-non-root-agent-never-loads-into-the-shared-jvm}
           :seon.test/long "three MCP eval requests on a fresh agent branch"
           :seon.test/long-ms 15000}
  a-non-root-agent-never-loads-into-the-shared-jvm
  (support/with-running-agent
   (fn [running agent-id _]
     (let [watched ['seon.sci.forgive/segments 'seon.sci.reader-test/fences-are-whitespace-between-reader-forms]
           before [(host-state 'seon.sci.forgive watched) (host-state 'seon.sci.reader-test watched)]
           reloaded (step "(require 'seon.sci.forgive :reload) (require 'seon.sci.reader-test) :done" agent-id running)
           host (step "(require 'seon.sci.reader-test :reload)" agent-id running "jvm")]
       (is (= :done (last (keep :seon.repl/value reloaded))) "both requires answer in SCI")
       (is (= ["root"] (keep (comp :seon.error/expected :seon.repl/error) host))
           "a non-root JVM-mode request is refused, naming root")
       (is (= before [(host-state 'seon.sci.forgive watched) (host-state 'seon.sci.reader-test watched)])
           "no namespace or Var of the host JVM was loaded, reloaded or replaced")))))
