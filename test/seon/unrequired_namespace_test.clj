(ns seon.unrequired-namespace-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- finding-of
  "The reply's `:seon.repl/findings` entry of kind `:auto-required`, or nil."
  [reply]
  (some #(when (= :auto-required (:seon.repl/finding %)) %) (:seon.repl/findings reply)))

(deftest a-defn-naming-an-unrequired-namespace-requires-it-and-says-so
  (support/with-running-agent
   (fn [running agent-id _]
     (let [call (fn [code]
                  (support/mcp-reply
                   (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                             "arguments" {"cluster" running "agent" agent-id "code" code}})))))
           unrequired (call "(ns my.urn) (defn f {:malli/schema [:=> [:cat :string] :string]} [s] (clojure.string/upper-case s))")
           required (call "(ns my.urr (:require [clojure.string])) (defn f {:malli/schema [:=> [:cat :string] :string]} [s] (clojure.string/upper-case s))")]
       (is (= #{'clojure.string} (set (:seon.eval/auto-required (finding-of unrequired)))))
       (is (nil? (finding-of required)))))))
