(ns my.schema
  "The agent's schema verb (v1's seon.schema/register!): change a schema of the program at
  the REPL, on this agent's branch, as `defn` changes a function. The change is judged
  against the whole population and refused as data; once written, the code this agent
  evaluates next compiles against it (seon.repl binds the branch's registry), the merge
  carries it per key, and write-back rewrites only its entry of its resource (seon.source).
  Platform code compiled into the binary keeps the built schemas until the next generation."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.agent :as agent] [seon.program :as program]
            [datahike.api :as d] [clojure.edn :as edn]))

(defn- refused
  "The registration-refused error for k: why, and the message of what refused it."
  {:malli/schema [:=> [:cat :qualified-keyword :keyword :string] :seon.schema/registration-refused]}
  [k why message]
  (contract/error :seon.schema/registration-refused 'my.schema/registration (str k ": " message)
                  {:seon.schema/key k :seon.schema/refused-because why}))

(defn- judged
  "The registry of forms with its Datahike declarations on db, or the refusal naming k:
  malli's of a form that does not compile (a name resolving to nothing among them), or an
  attribute Datahike cannot store."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword [:map-of :qualified-keyword :any]] [:or [:vector :seon.schema/declaration] :seon.schema/registration-refused]]}
  [db k forms]
  ;; interop edge: malli, the EDN reader and seon.schema.datahike throw their refusals
  (try (program/schema-declarations db (contract/registry-of forms))
       (catch Exception e (refused k (if (:seon.schema/reason (ex-data e)) :unstorable :invalid) (or (ex-message e) (str (type e)))))))

(defn- new-key-file
  "The resource a new key k goes to: the one holding most keys of k's namespace on db (ties
  by path), else <schemas-dir>/<namespace>.edn (owner, 2026-10-21)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] :string]}
  [db k]
  (let [counts (frequencies (for [dt (d/datoms db :aevt :schema/key) :when (= (namespace k) (namespace (:v dt)))
                                  :let [f (:schema/file (d/entity db (:e dt)))] :when f]
                              f))]
    (if (seq counts)
      (key (first (sort-by (fn [[f n]] [(- n) f]) counts)))
      (str contract/schemas-dir "/" (namespace k) ".edn"))))

(defn registration
  "Register form v as schema k on db's program: {:tx tx-data :registration {:seon.schema/key k
  :seon.schema/definition v}}, judged against the whole population (the binary's forms with
  db's changes and this one), or :seon.schema/registration-refused: v does not read back as
  itself, does not compile, or declares an attribute Datahike cannot store. The entry keeps
  its resource or takes new-key-file's; a scratch key (scratch?) gets none and is no
  program. A new or changed attribute's declaration comes with it."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :any :boolean] [:or [:map [:tx :seon.db/tx-data] [:registration :seon.schema/registration]] :seon.schema/registration-refused]]}
  [db k v scratch?]
  ;; printed as the resources are written: a namespaced map's keys in full
  (let [text (binding [*print-namespace-maps* false] (program/pr-edn v))
        decls (if (program/edn-value? v)
                (judged db k (assoc (merge contract/forms (program/schema-changes db)) k (edn/read-string text)))
                (refused k :unreadable "a schema form is EDN data: it must print and read back equal"))]
    (if (contract/error? decls)
      decls
      (let [e (some->> (first (d/datoms db :avet :schema/key k)) :e (d/entity db))
            file (cond scratch? nil (:schema/file e) (:schema/file e) :else (new-key-file db k))]
        {:tx (into (cond-> [(cond-> {:schema/key k :schema/form text} file (assoc :schema/file file))]
                     (:schema/source e) (conj [:db/retract (:db/id e) :schema/source true]))
                   decls)
         :registration {:seon.schema/key k :seon.schema/definition v}}))))

(defn register!
  "Register form v as schema k on this agent's branch. Answers {:seon.schema/key k
  :seon.schema/definition v}, or :seon.schema/registration-refused naming why; a refused
  registration writes nothing. A key in a scratch namespace (`user`, or ^:scratch) is seen
  at the REPL and is no program.

  ## Examples

  ```clojure
  (my.schema/register! :seon.attempt/status [:enum :running :ended :refused])
  (my.schema/register! :app.shape/area [:int {:min 0}])
  ```"
  {:malli/schema [:=> [:cat :qualified-keyword :any] [:or :seon.schema/registration :seon.schema/registration-refused]]}
  [k v]
  (let [n (find-ns (symbol (namespace k)))
        r (registration (agent/db) k v (or (= "user" (namespace k)) (boolean (and n (program/scratch-ns? n)))))]
    (if (contract/error? r)
      r
      (do (agent/transact! (:tx r)) (:registration r)))))
