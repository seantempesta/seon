(ns my.schema
  "The agent's schema function (v1's seon.schema/register!): change a schema of the program at
  the REPL, on this agent's branch, as `defn` changes a function. The change is judged
  against the whole population and refused as data; once written, the code this agent
  evaluates next compiles against it (seon.repl binds the branch's registry), the merge
  carries it per key, and write-back rewrites only its entry of its resource (seon.source).
  Platform code compiled into the binary keeps the built schemas until the next generation."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.contract :as contract :refer [defn defn-]] [seon.agent :as agent] [seon.program :as program] [seon.db :as pdb] [seon.config :as config]
            [datahike.api :as d] [seon.edn :as sedn] [malli.core :as m] [seon.schema.datahike :as sd] [clojure.string :as str] [jolt.analyzer]))

(defn- refused
  "The registration-refused error for k: why, and the message of what refused it (a form that is no EDN names its value, seon.program/non-edn; docs/references.md#resolving-and-showing)."
  {:malli/schema [:=> [:cat :qualified-keyword :keyword :string] :seon.schema/registration-refused]}
  [k why message]
  (contract/error :seon.schema/registration-refused #'my.schema/refused (str k ": " message)
                  {:seon.schema/key k :seon.schema/refused-because why}))

(defn- judged
  "The registry of forms and its Datahike declarations on db, {:registry :decls}, or the
  error naming k: malli's of a form that does not compile (a name resolving to nothing
  among them), or an attribute Datahike cannot store."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword [:map-of :qualified-keyword :any]] [:or [:map [:registry :seon.host/registry] [:decls [:vector :seon.schema/declaration]]] :seon.schema/registration-refused]]}
  [db k forms]
  ;; interop edge: malli, the EDN reader and seon.schema.datahike throw their errors
  (try (let [reg (contract/registry-of forms)] {:registry reg :decls (program/schema-declarations db reg)})
       (catch Exception e (refused k (if (:seon.schema/reason (ex-data e)) :unstorable :invalid) (step/error-msg e)))))

(defn- new-key-file
  "The resource a new key k goes to: the one holding most keys of k's namespace on db (ties
  by path), else <schemas-dir>/<namespace>.edn."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] :string]}
  [db k]
  (let [counts (frequencies (for [dt (d/datoms db :aevt :schema/key) :when (= (namespace k) (namespace (:v dt)))
                                  :let [f (:schema/file (d/entity db (:e dt)))] :when f]
                              f))]
    (if (seq counts)
      (key (first (sort-by (fn [[f n]] [(- n) f]) counts)))
      (str config/schemas-dir "/" (namespace k) ".edn"))))

(defn- usage-check
  "How many of attribute k's stored values on db the schema reg names k refuses (v1's
  seon.db/registration-check): one :aevt pass, one validator call per datom."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :seon.host/registry] :seon.schema.usage/check]}
  [db k reg]
  (let [valid? (m/validator (m/schema k {:registry reg}))]
    (reduce (fn [acc dt] (cond-> (update acc :checked inc) (not (valid? (:v dt))) (update :refused inc)))
            {:checked 0 :refused 0} (d/datoms db :aevt k))))

(defn- own-declaration
  "The attribute registered key k is, when no entity map lists it and its value is storable (an
  attribute is a registered key whose schema is its stored value: an agent's own datum, written
  on its own entity): [its declaration], when db does not hold it so; else []."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :seon.host/registry] [:vector :seon.schema/declaration]]}
  [db k reg]
  (let [dcl (sd/declaration reg k)
        entity? (:seon.db/attributes (m/properties (m/deref (m/schema k {:registry reg}))))]
    (if (or (contract/error? dcl) entity? (= dcl (select-keys (get (d/schema db) k) (keys dcl)))) [] [dcl])))

(defn registration
  "Register form v as schema k on db's program: {:tx tx-data :registration {:seon.schema/key k
  :seon.schema/definition v}}, judged against the whole population (the binary's forms with
  db's changes and this one), or :seon.schema/registration-refused: v does not read back as
  itself, does not compile, or declares an attribute Datahike cannot store. The entry keeps
  its resource or takes new-key-file's; a scratch key (scratch?) gets none and is no
  program. A new or changed attribute's declaration comes with it (the key itself is one when
  it is storable and no entity map: own-declaration), and a registration of an
  attribute db stores says how many stored values its form refuses (:seon.schema.usage/check)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :any :boolean] [:or [:map [:tx :seon.db/tx-data] [:registration :seon.schema/registration]] :seon.schema/registration-refused]]}
  [db k v scratch?]
  ;; printed as the resources are written: a namespaced map's keys in full
  (let [text (binding [*print-namespace-maps* false] (sedn/encode v))
        judged (if (program/edn-value? v)
                (judged db k (assoc (merge contract/forms (program/schema-changes db)) k (sedn/decode text)))
                (let [[path x] (program/non-edn v)]
                  (refused k :unreadable (str "a schema form is EDN data, and " (pr-str x) (when (seq path) (str " at " (pr-str path)))
                                              " is no EDN: a predicate is its symbol, so quote the form ('[:vector nat-int?]) or the predicate ('nat-int?)"))))]
    (if (contract/error? judged)
      judged
      (let [e (some->> (pdb/eid db :schema/key k) (d/entity db))
            file (cond scratch? nil (:schema/file e) (:schema/file e) :else (new-key-file db k))]
        {:tx (into (cond-> [(cond-> {:schema/key k :schema/form text} file (assoc :schema/file file))]
                     (:schema/source e) (conj [:db/retract (:db/id e) :schema/source true]))
                   (distinct (concat (:decls judged) (own-declaration db k (:registry judged)))))
         :registration (cond-> {:seon.schema/key k :seon.schema/definition v}
                         (contains? (d/schema db) k) (assoc :seon.schema.usage/check (usage-check db k (:registry judged))))}))))

(declare unknown-key)

(defn forms
  "The schema population this agent's branch sees, {key form}: the binary's forms with the
  branch's registrations; (keys (forms)) are every key. With a key, that key's form (agents asked
  (forms k) four times in step E's paid runs), or, for a key no schema registers,
  :seon.schema/unknown-key naming the nearest registered keys (`unknown-key`): unknown is no nil (a key asked for again and again that reads nil looks absent). See docs/references.md#resolving-and-showing."
  {:malli/schema [:function [:=> [:cat] [:map-of :qualified-keyword :any]]
                  [:=> [:cat :qualified-keyword] :any]]}
  ([] (merge contract/forms (program/schema-changes (agent/db))))
  ([k] (let [fs (forms)] (if (contains? fs k) (get fs k) (unknown-key fs k)))))

(defn unknown-key
  "The answer for k, a key no schema of population fs registers: :seon.schema/unknown-key naming
  the nearest registered keys by Jolt's did-you-mean ranking (jolt.analyzer/suggestions-for, as
  my.agent/no-var ranks names). What (forms k) and my.agent/callers answer of one, never nil or []. See docs/source.md#schemas-at-the-repl."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :any] :qualified-keyword] :seon.schema/unknown-key]}
  [fs k]
  (contract/error :seon.schema/unknown-key #'unknown-key (str k " is no registered schema: the nearest registered keys are named")
                  {:seon.schema/key k
                   :seon.schema/nearest (vec (keep #(let [s (if (str/starts-with? % ":") (subs % 1) %)] (when (str/includes? s "/") (keyword s)))
                                                   (jolt.analyzer/suggestions-for (str k) (map str (keys fs)))))}))

(defn register!
  "Register form v as schema k on this agent's branch. Answers {:seon.schema/key k
  :seon.schema/definition v}, or :seon.schema/registration-refused naming why; a refused
  registration writes nothing. A key in a scratch namespace (`user`, or ^:scratch) is seen
  at the REPL and is no program.

  ## Examples

  ```clojure
  (my.schema/register! :app.shape/area [:int {:min 0}])
  (get (my.schema/forms) :seon.run/status) ; an existing key: start from its form, never retype it
  ```"
  {:malli/schema [:=> [:cat :qualified-keyword :any] [:or :seon.schema/registration :seon.schema/registration-refused]]}
  [k v]
  (let [n (find-ns (symbol (namespace k)))
        r (registration (agent/db) k v (or (= "user" (namespace k)) (boolean (and n (program/scratch-ns? n)))))]
    (if (contract/error? r)
      r
      (do (agent/transact! (:tx r))
          ;; the code this process holds whose contracts reach k, compiled again against it (program/rearm!): a
          ;; registration is in effect for the program as it is when the branch's program loads
          (let [errors (program/rearm! (agent/db) #{k})]
            (cond-> (:registration r) (seq errors) (assoc :seon.schema/rearm-errors errors)))))))
