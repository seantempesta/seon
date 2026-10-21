(ns my.agent
  "An agent's own verbs (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                the live value of an earlier result (also result/h3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (source sym)              the recorded source of a definition, printed (doc is clojure.repl's)"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [datahike.api :as d]))

(defn $ "The live value of an earlier result, by handle (:h3)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages delivered to this agent and not yet taken ({:from :body}), taken." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn send-message "Send body to the agents tos (ids); root delivers it by id. nil." {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (agent/send-message tos body))

(defn source-of
  "The source this branch records for the definition sym (the text of the form that last defined it), or nil."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [sym]
  (let [sym (if (namespace sym) sym (symbol (str (ns-name *ns*)) (name sym)))]
    (d/q '[:find ?s . :in $ ?v :where [?e :var/name ?v] [?e :var/form ?f] [?f :form/src ?s]] (agent/db) sym)))

(defmacro source
  "Print the recorded source of the definition named by sym (as clojure.repl/source does)."
  [sym] `(println (or (source-of '~sym) (str "Source not found for " '~sym))))

