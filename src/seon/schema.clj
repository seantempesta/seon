(ns seon.schema
  "Schemas are part of the program (owner, 2026-10-21: \"EDN for offline compiled into
  program and register at the repl and that can write to the edn if it's merged to main\").
  The built registry is seon.contract's, from the resources the binary was built from. A
  branch's program holds the schema entries seon.source imported (`:schema/source`: the
  binary holds them) and those an agent registered or a merge carried; this namespace
  derives that branch's population, and judges a registration against it (v1's
  seon.schema/register!, without its candidate collectors and projections: each agent is a
  process of its own, and its population is a function of its branch).

  - `changes`: what the branch's entries change against the binary's forms;
  - `branch-registry`: the registry an evaluation on the branch binds, so the code it
    defines next and every schema naming a changed key see the change;
  - `registration`, `removal`: the tx-data of a change, or the refusal as data."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.program :as program] [seon.schema.datahike :as sd]
            [datahike.api :as d] [malli.registry :as mr] [clojure.edn :as edn]))

(defn form-text
  "Schema form v as an entry's EDN text: printed as the resources are written (a namespaced
  map's keys in full), no print limits."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (binding [*print-namespace-maps* false] (program/pr-edn v)))

(defn- imported-keys
  "The keys of every schema resource as db imported it (its :schema.file/text)."
  {:malli/schema [:=> [:cat :seon.host/db] [:set :qualified-keyword]]}
  [db]
  (into #{} (for [dt (d/datoms db :aevt :schema.file/text) k (keys (edn/read-string (:v dt)))] k)))

(defn changes
  "What db's schema entries change against the binary's forms (seon.contract/forms):
  {:put {k form} :removed #{k}}, :put every entry the binary does not hold (no
  :schema/source), :removed every imported key db no longer holds. Cost: two counts over the
  entries when nothing changed; reading the imported texts when something did."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:put [:map-of :qualified-keyword :any]] [:removed [:set :qualified-keyword]]]]}
  [db]
  (let [n-key (count (d/datoms db :aevt :schema/key))
        n-src (count (d/datoms db :aevt :schema/source))]
    (if (and (= n-key n-src) (or (zero? n-src) (= n-src (count contract/forms))))
      {:put {} :removed #{}}
      (let [held (into {} (for [dt (d/datoms db :aevt :schema/key) :let [x (d/entity db (:e dt))]]
                            [(:v dt) x]))]
        {:put (into {} (for [[k x] held :when (not (:schema/source x))] [k (edn/read-string (:schema/form x))]))
         :removed (into #{} (remove held) (imported-keys db))}))))

(defn change-key
  "What branch-registry's answer depends on, read cheaply (two passes over the entries'
  datoms): how many entries db holds, and the forms of those the binary does not. Equal keys,
  equal registries: a removal changes the count, a registration the forms."
  {:malli/schema [:=> [:cat :seon.host/db] [:tuple :int [:map-of :qualified-keyword :string]]]}
  [db]
  (let [src (into #{} (map :e) (d/datoms db :aevt :schema/source))
        ks (d/datoms db :aevt :schema/key)]
    [(count ks) (into {} (for [dt ks :when (not (src (:e dt)))] [(:v dt) (:schema/form (d/entity db (:e dt)))]))]))

(defn- population
  "The binary's forms with a branch's changes."
  {:malli/schema [:=> [:cat [:map [:put :map] [:removed [:set :qualified-keyword]]]] [:map-of :qualified-keyword :any]]}
  [{:keys [put removed]}]
  (merge (apply dissoc contract/forms removed) put))

(defn branch-registry
  "The registry an evaluation on db binds as malli.registry/*registry*: the whole population
  recompiled with db's changes (a schema naming a changed key compiles against it), or an
  empty registry when db changes nothing (the built registry answers)."
  {:malli/schema [:=> [:cat :seon.host/db] :any]}
  [db]
  (let [c (changes db)]
    (if (and (empty? (:put c)) (empty? (:removed c))) {} (contract/registry-of (population c)))))

(defn- entry
  "Schema k's entry entity on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] :any]}
  [db k] (some->> (first (d/datoms db :avet :schema/key k)) :e (d/entity db)))

(defn- refused
  "The registration-refused error for k: why, and the message of what refused it."
  {:malli/schema [:=> [:cat :qualified-keyword :keyword :string] :seon.schema/registration-refused]}
  [k why message]
  (contract/error :seon.schema/registration-refused 'seon.schema/registration (str k ": " message)
                  {:seon.schema/key k :seon.schema/refused-because why}))

(defn- compiled
  "The registry of forms with its Datahike declarations, or the refusal naming k: malli's
  refusal of a form that does not compile (a name that resolves to nothing among them), or
  a stored attribute Datahike cannot hold."
  {:malli/schema [:=> [:cat :qualified-keyword [:map-of :qualified-keyword :any]] [:or [:map [:registry :any] [:declarations [:vector :seon.schema/declaration]]] :seon.schema/registration-refused]]}
  [k forms]
  ;; interop edge: malli and seon.schema.datahike throw their refusals
  (try (let [reg (contract/registry-of forms)] {:registry reg :declarations (sd/declarations reg)})
       (catch Exception e (refused k (if (:seon.schema/reason (ex-data e)) :unstorable :invalid) (or (ex-message e) (str (type e)))))))

(defn- scratch-key?
  "Is k in a scratch namespace (`user`, or one this process declares ^:scratch): seen at the
  REPL, no program (owner, 2026-10-21)."
  {:malli/schema [:=> [:cat :qualified-keyword] :boolean]}
  [k]
  (let [n (find-ns (symbol (namespace k)))]
    (or (= "user" (namespace k)) (boolean (and n (program/scratch-ns? n))))))

(defn- new-key-file
  "The resource a new key k goes to: the one holding most keys of k's namespace on db (ties
  by path), else <schemas-dir>/<namespace>.edn (owner, 2026-10-21)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] :string]}
  [db k]
  (let [counts (frequencies (for [dt (d/datoms db :aevt :schema/key)
                                  :when (= (namespace k) (namespace (:v dt)))
                                  :let [f (:schema/file (d/entity db (:e dt)))] :when f]
                              f))]
    (if (seq counts)
      (key (first (sort-by (fn [[f n]] [(- n) f]) counts)))
      (str contract/schemas-dir "/" (namespace k) ".edn"))))

(defn- new-declarations
  "The declarations db's Datahike schema does not hold as declared."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.schema/declaration]] [:vector :seon.schema/declaration]]}
  [db declarations]
  (let [have (d/schema db)]
    (filterv (fn [dcl] (not= dcl (select-keys (get have (:db/ident dcl)) (keys dcl)))) declarations)))

(defn registration
  "Register form v as schema k on db's program: {:tx tx-data :registration {:seon.schema/key k
  :seon.schema/definition v}}, judged against the whole population (the binary's forms with
  db's changes and this one): refused as data when v does not read back as EDN, does not
  compile, or declares an attribute Datahike cannot store. The entry keeps its resource, or
  takes new-key-file's; a scratch key gets none. A new or changed attribute's declaration is
  in the tx-data, so the branch can store its facts at once."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword :any] [:or [:map [:tx :seon.db/tx-data] [:registration :seon.schema/registration]] :seon.schema/registration-refused]]}
  [db k v]
  (let [text (form-text v)]
    (if-not (program/edn-value? v)
      (refused k :unreadable "a schema form is EDN data: it must print and read back equal")
      (let [r (compiled k (assoc (population (changes db)) k v))]
        (if (contract/error? r)
          r
          (let [e (entry db k)
                file (cond (scratch-key? k) nil
                           (:schema/file e) (:schema/file e)
                           :else (new-key-file db k))]
            {:tx (into (cond-> [(cond-> {:schema/key k :schema/form text} file (assoc :schema/file file))]
                         (:schema/source e) (conj [:db/retract (:db/id e) :schema/source true]))
                       (new-declarations db (:declarations r)))
             :registration {:seon.schema/key k :seon.schema/definition v}}))))))

(defn declarations-tx
  "The Datahike declarations db's schema population needs and db does not hold as declared
  (a merge that carries a schema change carries its attribute)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.schema/declaration]]}
  [db]
  (let [c (changes db)]
    (if (and (empty? (:put c)) (empty? (:removed c)))
      []
      (new-declarations db (sd/declarations (contract/registry-of (population c)))))))

(defn removal
  "Remove schema k from db's program: {:tx tx-data}, or the refusal when the population
  without it does not compile (a schema still names it)."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword] [:or [:map [:tx :seon.db/tx-data]] :seon.schema/registration-refused]]}
  [db k]
  (let [e (entry db k)
        r (compiled k (dissoc (population (changes db)) k))]
    (cond (contract/error? r) r
          e {:tx [[:db/retractEntity (:db/id e)]]}
          :else {:tx []})))
