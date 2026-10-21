(ns my.agent
  "An agent's own verbs (from proto/flow f2-final proto.agent's agent API). Referred into
  every agent namespace.
    ($ handle)                the live value of an earlier result (also result/h3)
    (whoami)                  {:id :branch :pid}
    (messages)                the messages delivered and not yet taken, taken
    (send-message tos body)   body to the agents tos (ids), delivered by root
    (source sym)              the recorded source of a definition, printed (also clojure.repl/source, source-fn)"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.agent :as agent] [seon.lib :as lib] [seon.context :as context]))

(defn $ "The live value of an earlier result, by handle (:h3)." {:malli/schema [:=> [:cat :keyword] :any]}
  [h] (get-in @agent/state [:results h]))
(defn whoami "This agent: {:id :branch :pid}." {:malli/schema [:=> [:cat] [:map [:id :seon.agent/id] [:branch :seon.db/branch] [:pid :seon.lib/pid]]]}
  [] {:id (:id @agent/state) :branch (:branch @agent/state) :pid (lib/c-getpid)})
(defn messages "The messages delivered to this agent and not yet taken ({:from :body}), taken." {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (agent/messages))
(defn send-message "Send body to the agents tos (ids); root delivers it by id. nil." {:malli/schema [:=> [:cat [:sequential :seon.agent/id] :any] :nil]}
  [tos body] (agent/send-message tos body))

(defn source-fn
  "The source this branch records for the var sym names (the text of the form that last defined it), or nil: clojure.repl/source-fn."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [sym]
  (when-let [v (resolve sym)]
    (context/source-of (agent/db) (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v)))))))

(defmacro source
  "Print the recorded source of the definition named by sym (as clojure.repl/source does)."
  [sym] `(println (or (source-fn '~sym) (str "Source not found for " '~sym))))
