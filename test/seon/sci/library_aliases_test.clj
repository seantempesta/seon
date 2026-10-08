(ns seon.sci.library-aliases-test
  "An agent in a file namespace resolves the libraries its ns form requires
  through their aliases and full names, as the namespace's compiled code does."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- step
  "The parsed items of `agent-id`'s eval request over `code`."
  {:malli/schema [:=> [:cat :string :string :string] [:sequential :seon.schema/value]]}
  [code agent-id running]
  (map #(support/reply (get % "text"))
       (get (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                      "arguments" {"code" code "agent" agent-id "cluster" running}})))
            "content")))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-file-namespaces-library-aliases-do-not-resolve-until-required}
           :seon.test/long "three MCP eval requests on a fresh agent branch"
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
