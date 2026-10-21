(ns my.schema
  "The agent's schema verbs (v1's seon.schema/register! and unregister!): change a schema of
  the program at the REPL, on this agent's branch, as `defn` changes a function. The change
  is judged against the whole population and refused as data; once written, the code this
  agent evaluates next compiles against it (seon.repl binds the branch's registry), the merge
  carries it per key, and write-back rewrites only its entry of its resource (seon.source).
  Platform code compiled into the binary keeps the built schemas until the next generation."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.agent :as agent] [seon.schema :as schema] [seon.program :as program]))

(defn- scratch-key?
  "Is k in a scratch namespace of this process (seon.program/scratch-ns?: `user`, or declared
  ^:scratch): seen at the REPL, no program (owner, 2026-10-21)."
  {:malli/schema [:=> [:cat :qualified-keyword] :boolean]}
  [k]
  (let [n (find-ns (symbol (namespace k)))]
    (or (= "user" (namespace k)) (boolean (and n (program/scratch-ns? n))))))

(defn register!
  "Register form v as schema k on this agent's branch. Answers {:seon.schema/key k
  :seon.schema/definition v}, or :seon.schema/registration-refused naming why (its form is no
  EDN data, does not compile against the population, or declares an attribute Datahike cannot
  store); a refused registration writes nothing. A key in a scratch namespace (`user`, the
  agent's own) is seen at the REPL and is no program.

  ## Examples

  ```clojure
  (my.schema/register! :seon.attempt/status [:enum :running :ended :refused])
  (my.schema/register! :app.shape/area [:int {:min 0}])
  ```"
  {:malli/schema [:=> [:cat :qualified-keyword :any] [:or :seon.schema/registration :seon.schema/registration-refused]]}
  [k v]
  (let [r (schema/registration (agent/db) k v (scratch-key? k))]
    (if (contract/error? r)
      r
      (do (agent/transact! (:tx r)) (:registration r)))))

(defn unregister!
  "Remove schema k from this agent's branch. Answers k, or :seon.schema/registration-refused
  when another schema still names it."
  {:malli/schema [:=> [:cat :qualified-keyword] [:or :qualified-keyword :seon.schema/registration-refused]]}
  [k]
  (let [r (schema/removal (agent/db) k)]
    (if (contract/error? r)
      r
      (do (when (seq (:tx r)) (agent/transact! (:tx r))) k))))
