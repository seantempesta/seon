(ns seon.db
  "The database side: schema of the example program, the seed, the store, and the merge
  delta (a branch's net change since its merge base, as tx-data that replays onto main).
  From proto/flow f2-final proto.db; the store's node codec is X1's jfasl
  (konserve.jolt.fasl-store, FaslSerializer), and the JVM-written-store reader stays behind
  (BAGGAGE.md)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.profile :as profile] [seon.contract :refer [defn defn-] :as contract] [seon.print :as print] [clojure.string :as str] [malli.core :as m] [malli.registry] [seon.schema.datahike :as sd] [seon.config :as cfg]
            [datahike.connections] [datahike.writing] [datahike.config] [datahike.store] [konserve.store] [hasch.core :as hasch] [datahike.api :as d] [org.replikativ.persistent-sorted-set :as pss] [konserve.core :as k] [konserve.jolt.store] [konserve.jolt.lmdb :as klmdb]
            [konserve.jolt.lmdb-ffi :as lmdb-ffi] [konserve.jolt.posix :as posix] [seon.lib :as lib] [jolt.ffi :as ffi] [datahike.ids] [datahike.core]))

(def schema
  "The Datahike schema, derived from the registry's entity schemas (resources/seon/schemas,
  seon.schema.datahike; session D1): one source of truth with the contracts."
  (sd/declarations contract/registry))

(def layout
  "How a store lays out its records, fixed when it is made: its index nodes addressed by their content (:crypto-hash?, so
  branches holding the same datoms name one node: docs/sharing.md#what-a-branchs-nodes-are) and its history kept or not
  (:seon.config.store/keep-history?, v1's ruling 23). A store made otherwise opens as it was made (a connect adopts both:
  Datahike's store-fixed-record-keys), so a kept store is named by it too (`schema-digest`) and made again with it."
  {:crypto-hash? true :keep-history? (cfg/value :seon.config.store/keep-history?)})

(def schema-digest
  "The digest of the schema and the store's `layout`, 8 hex digits of its hasch uuid: a kept store of regenerable output is
  named by it (`kept-store!`), so a changed schema or layout makes a new store rather than one that refuses
  to open (:seon.db/types-differ), keeps the old layout, or is migrated."
  (subs (clojure.string/replace (str (hasch/uuid [schema layout])) "-" "") 0 8))

(defn invalid-datom
  "The first value of tx-data its attribute's declared schema refuses, as the error :seon.db/invalid-datom naming the
  attribute and where in the value, or nil: an attribute's schema is what the store holds it to, at the write, where
  the store itself checks only a value's type. A map's attributes and :db/add's are read; a ref is its entity's own
  (a nested map is walked, an id or lookup names one); an attribute no schema declares (an agent's own, its registry
  its branch's, when own, {:forms the branch's schema changes :registry their registry}, does not name it) and a transaction function are not asked. See docs/storage.md#the-write-checks-every-datom."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.db/tx-data] [:maybe :seon.error/base]]
                  [:=> [:cat :seon.host/db :seon.db/tx-data [:maybe [:map [:forms :map] [:registry :any]]]] [:maybe :seon.error/base]]]}
  ([db tx-data] (invalid-datom db tx-data nil))
  ([db tx-data {:keys [forms registry]}]
  (let [schema (:schema db)
        known? (fn [a] (or (contains? contract/forms a) (contains? forms a)))
        schema-of (fn [a] (or (if (contains? forms a) (malli.registry/schema registry a) (contract/held a))
                                 ;; a name the registry holds no instance of (a form given in place of a registered name)
                                 (if (contains? forms a) (m/schema a {:registry registry}) (contract/schema a))))
        ;; an attribute's validator is found once a transaction: a transaction writes many datoms of few attributes
        seen (volatile! {})
        valid-of (fn [a] (or (get @seen a) (let [f (m/validator (schema-of a))] (vswap! seen assoc a f) f)))]
    (letfn [(refused [a v]
              (when-not ((valid-of a) v)
                (let [ps (contract/problems (schema-of a) v)]
                  (contract/error :seon.db/invalid-datom #'invalid-datom (let [{:keys [in value]} (first ps)] (str a " refuses this value at " in ": " (:seon.print/code value)))
                                  {:attribute a :seon.error/problems ps}))))
            (value [a v]
              (let [{:db/keys [valueType cardinality]} (get schema a)
                    many? (= :db.cardinality/many cardinality)
                    vs (if (and many? (or (sequential? v) (set? v))) v [v])]
                (if (= :db.type/ref valueType)
                  (some #(when (map? %) (entity %)) vs)
                  ;; a many attribute's schema is of its set, each value one member of it
                  (refused a (if many? (set vs) v)))))
            (entity [m]
              (some (fn [[a v]] (when (and (qualified-keyword? a) (known? a) (some? v)) (value a v))) m))]
      (some (fn [op]
              (cond (map? op) (when-not (record? op) (entity op))
                    (and (vector? op) (= :db/add (first op)) (known? (nth op 2 nil))) (value (nth op 2) (nth op 3 nil))))
            tx-data)))))

;; ---- one definition of each entity (docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together) ----------

(defn identity-attr?
  "Is attribute a an upsert key ([:and {:seon.db/identity true} ...])? See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [a] (boolean (:seon.db/identity (contract/properties a))))

(defn- form-props
  "The properties of named schema k's form in forms ([:type {props} ...]), or nil. See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map :any] [:maybe :map]]}
  [forms k] (let [f (get forms k)] (when (and (vector? f) (map? (second f))) (second f))))

(defn- parents-in
  "The named schemas k's form in forms extends ([:and p ...] or [:merge p ...]), or the one it is an alias of (a form that is a
  registered schema's name, :seon.task/plan :seon.db/text), so an alias inherits its properties. See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword]] [:vector :qualified-keyword]]}
  [forms k]
  (let [form (get forms k)]
    (cond (and (vector? form) (#{:and :merge} (first form))) (filterv #(and (qualified-keyword? %) (contains? forms %)) (rest form))
          (and (qualified-keyword? form) (contains? forms form)) [form]
          :else [])))

(defn parents
  "The named schemas k's form extends ([:and p ...] or [:merge p ...]) or is an alias of (`parents-in`), in the forms index ix was built from (`entity-index`: the
  binary's `index`, or a branch's, `index-of`, `index-of-db`). See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map [:maybe :qualified-keyword]] [:vector :qualified-keyword]]}
  [ix k] (parents-in (:forms ix) k))

(defn- own-entries
  "The [key props? schema] entries a schema's form names itself: its [:map ...] members, inline or in an [:and ...] arm,
  never those of a schema it extends (`parents`), so an attribute is named by one entity schema."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form]
  (cond (and (vector? form) (= :map (first form))) (filterv vector? (rest form))
        (and (vector? form) (#{:and :merge} (first form))) (vec (mapcat #(when-not (keyword? %) (own-entries %)) (rest form)))
        :else []))

(defn own-keys
  "The keys a schema's form names itself (`own-entries`). See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form] (mapv first (own-entries form)))

(defn- entity-index
  "What schema forms say an entity is: {:entity-schemas {attribute schema}, each attribute an entity schema
  ({:seon.db/attributes true}) names itself (`own-keys`), to that schema; :identity-schemas, those of them that are upsert
  keys; :required-keys {schema [attribute ...]}, the keys each requires, its own and those of the schemas it extends, and
  :own-required, only its own; :schema-identities, :identity-schemas turned around; :targets, each ref attribute's target
  entity schemas ({:seon.db/to}); :drawn-schemas, the entity schemas that declare a render (:seon.render/hiccup), theirs or a parent's; :history-schemas,
  those that are history (seon.render/history?: a model render, :seon.render/openai-compat, theirs or a parent's, and no :seon.ctx/pattern on
  any; docs/render.md#the-page); and :forms, the forms it was built from}. It is the one value every reader of what an entity
  is takes first (ix): a branch's own entity schema is recognized by its own index, never the binary's.
  See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map] [:map [:forms :map] [:entity-schemas :map] [:identity-schemas :map] [:schema-identities :map] [:drawn-schemas [:set :any]] [:history-schemas [:set :any]] [:required-keys :map] [:own-required :map] [:targets :map] [:required-refs [:vector :qualified-keyword]]]]}
  [forms]
  (let [entity? #(:seon.db/attributes (form-props forms %))
        required (fn [form] (for [e (own-entries form) :let [p (second e)] :when (not (and (map? p) (:optional p)))] (first e)))
        es (into {} (for [[k form] forms :when (entity? k) a (own-keys form)] [a k]))
        ids (into {} (filter #(:seon.db/identity (form-props forms (key %)))) es)]
    {:forms forms
     :entity-schemas es
     :identity-schemas ids
     :schema-identities (into {} (map (fn [[a k]] [k a])) ids)
     ;; the identity schemas that declare a render, theirs or a parent's: what seon.render/schema-of names a pulled map by, one lookup
     :drawn-schemas (into #{} (filter (fn [k] (some #(:seon.render/hiccup (form-props forms %)) (tree-seq (constantly true) #(parents-in forms %) k)))) (distinct (vals es)))
     ;; the entity schemas the conversation is made of (seon.render/history? reads this set): a model render declared, theirs or a parent's, and
     ;; no pattern of their own nor a parent's, as the nearest property is read (seon.render/schema-property); an agent has a page, never history
     :history-schemas (into #{} (filter (fn [k] (let [chain (tree-seq (constantly true) #(parents-in forms %) k)]
                                                  (and (some #(:seon.render/openai-compat (form-props forms %)) chain)
                                                       (not-any? #(:seon.ctx/pattern (form-props forms %)) chain)))))
                            (distinct (vals es)))
     :required-keys (into {} (for [k (distinct (vals es))]
                               [k (vec (distinct (mapcat #(required (get forms %)) (filter entity? (tree-seq (constantly true) #(parents-in forms %) k)))))]))
     :own-required (into {} (for [k (distinct (vals es))] [k (vec (required (get forms k)))]))
     ;; each ref attribute's target entity schemas, as its own schema states them ({:seon.db/to [...]}), once
     :targets (into {} (for [[a _] es :let [to (:seon.db/to (form-props forms a))] :when to] [a to]))
     ;; the ref attributes some entity schema requires: what the write check's cascade follows from a retracted entity, once
     :required-refs (vec (sort (distinct (for [k (distinct (vals es)) a (required (get forms k)) :when (:seon.db/to (form-props forms a))] a))))}))

(def ^:private binary-index
  "The binary's `entity-index`, derived once from its registry. See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  (entity-index contract/forms))

(defn index
  "The binary's `entity-index`: what every write is held to unless its branch changed an entity schema (`index-of`), and what
  a caller with no branch of its own reads (`index-of-db` is a db's). See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat] :map]}
  [] binary-index)

(declare extends?)

(defn reaching
  "The ref attributes whose targets entity schema s is or extends, in the entity index ix: the refs that reach an s, each read in
  reverse (:ns/_name) to pull what points at it. See docs/data-modeling.md#things-relate-by-refs."
  {:malli/schema [:=> [:cat :map :qualified-keyword] [:vector :qualified-keyword]]}
  [ix s] (vec (sort (for [[a ts] (:targets ix) :when (some #(extends? ix s %) ts)] a))))

(defn attributes-of
  "The attributes entity schema s names in the entity index ix, its own and those of the schemas it extends. See
  docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map :qualified-keyword] [:vector :qualified-keyword]]}
  [ix s] (vec (distinct (concat (mapcat #(attributes-of ix %) (parents ix s)) (own-keys (get (:forms ix) s))))))

(defn- reverse-attr {:malli/schema [:=> [:cat :qualified-keyword] :qualified-keyword]} [a] (keyword (namespace a) (str "_" (name a))))

(defn owners
  "Each of attributes as with the entity schema that owns it, {attribute schema}, as the entity index ix says (its :entity-schemas):
  the one reading of which entity schemas attributes belong to, for the write check (each schema a write touched) and for a render
  (the one schema that names a map). An attribute no entity schema owns is left out. See docs/data-modeling.md#a-thing-is-its-attributes."
  {:malli/schema [:=> [:cat :map :any] :map]}
  [ix as] (let [es (:entity-schemas ix)] (into {} (keep (fn [a] (when-let [s (es a)] [a s]))) as)))

(defn implied-pattern
  "The pull entity schema s implies: its own attributes, each ref it names followed to its first target and each ref
  that reaches it followed back to the schema that owns it, a schema already walked not followed again (Malli's
  ::walked-refs), so no join loops. What can relate to an s; an entity's :seon.ctx/pattern chooses from it. See
  docs/data-modeling.md#things-relate-by-refs."
  {:malli/schema [:function [:=> [:cat :map :qualified-keyword] [:vector :any]] [:=> [:cat :map :qualified-keyword [:set :any]] [:vector :any]]]}
  ([ix s] (implied-pattern ix s #{}))
  ([ix s walked]
   (let [w (conj walked s) as (attributes-of ix s) targets (:targets ix)]
     (vec (concat (remove targets as)
                  (for [a as :when (targets a) :let [t (first (remove w (targets a)))] :when t] {a (implied-pattern ix t w)})
                  (for [a (reaching ix s) :let [o (get (owners ix [a]) a)] :when (and o (not (w o)))] {(reverse-attr a) (implied-pattern ix o w)}))))))

(defn extends?
  "Does schema a extend schema b, itself or through the schemas it extends (`parents`, in the entity index ix)? See docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat :map :qualified-keyword :qualified-keyword] :boolean]}
  [ix a b] (boolean (or (= a b) (some #(extends? ix % b) (parents ix a)))))

(defn most-specific
  "What one entity schema map e is, for a render, which needs one: of the schemas owning its attributes (`owners`, the reading the write
  check makes too, which holds the entity to each of them and names none as the one), the best match by attributes held: of
  the schemas owning the attributes e holds, the best match, the one with the most of its attributes present, those it
  inherits (`parents`) counted, so a schema beats the one it extends; nil for none, {:ambiguous [s ...]} for a tie. One lookup a key, then a sum a candidate
  over its parents. The order of e's keys never decides. See docs/data-modeling.md#a-thing-is-its-attributes."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [ix e]
  (let [own (frequencies (vals (owners ix (keys e))))]
    (when (seq own)
      (let [score (fn score [s] (+ (get own s 0) (reduce + 0 (map score (parents ix s)))))
            ranked (sort-by (comp - second) (map (juxt identity score) (keys own)))
            [[s0 n0] [s1 n1]] ranked]
        (if (and s1 (= n0 n1)) {:ambiguous (vec (sort (map first (filter #(= n0 (second %)) ranked))))} s0)))))

(defn index-of
  "The `entity-index` of a branch's own schema changes (schemas, {:forms :registry}, or nil): the binary's (`index`) when it
  changed none, else derived again from the registry with the branch's forms over it, a cost of the registry's size (about
  1.8 MB, 0.9 ms), built, never kept here: its holder (a walk, an agent) keeps it until a schema entry moves. See
  docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [schemas]
  (if (seq (:forms schemas)) (entity-index (merge contract/forms (:forms schemas))) binary-index))

(defn- write-index
  "The `entity-index` a write is held to, given its branch's own schema changes ({:forms :registry}, or nil): the binary's
  (`index`), unless the branch changed an entity schema or an attribute one names, when it is `index-of` them: a cost of the
  registry's size, paid only by the writes of a branch that changed what an entity is. See
  docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat [:maybe :map]] :map]}
  [schemas]
  (let [forms (:forms schemas)]
    (if (some (fn [k] (or (contains? (owners binary-index [k]) k) (contains? (:required-keys binary-index) k)
                          (:seon.db/attributes (form-props forms k))))
              (keys forms))
      (index-of schemas)
      binary-index)))

(defn invalid-entity
  "The first entity a transaction's report (r) left holding attributes an entity schema owns without one that schema
  requires, as the error :seon.db/invalid-entity, or nil; ix is the `entity-index` the write is held to (`write-index`, the
  binary's by default). A schema is asked of an entity when the transaction added or retracted an attribute it owns; it
  refuses only what this write broke: on an entity that was that thing before (it held a key the schema itself
  requires), a required key it held before and not after; on one the write makes that thing, every one it lacks. An entity
  incomplete before stays as it was (a whole-store scan's to report), and one left holding none of them has stopped
  being that thing. Presence is one seek
  per (entity, key), so a write costs its touched entities' required keys, never an entity's size or the store's; values
  are each datom's own check (`invalid-datom`). The first refusal by entity id, then schema, and every entity it broke in
  :entities (each by its identity). See
  docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:function [:=> [:cat :map] [:maybe :seon.error/base]] [:=> [:cat :map :map] [:maybe :seon.error/base]]]}
  ([r] (invalid-entity r binary-index))
  ([{:keys [db-before db-after tx-data]} {:keys [identity-schemas required-keys own-required] :as ix}]
   (let [has? (fn [db e a] (boolean (first (d/datoms db :eavt e a))))
         ;; {[e schema] {attribute added?}}: what the write did to each touched entity's attributes of each schema; a key
         ;; it only added is held after with no seek (a write that creates an entity seeks nothing after)
         own (owners ix (distinct (map :a tx-data)))
         touched (reduce (fn [m {:keys [e a tx added]}]
                           (if-let [k (when (not= e tx) (own a))]
                             (update-in m [[e k] a] #(if (nil? %) (boolean added) (and % added)))
                             m))
                         {} tx-data)
         broken (keep (fn [[[e k] did]]
                        (let [added? (boolean (some true? (vals did)))
                              req (required-keys k)
                              after (filterv #(or (true? (get did %)) (has? db-after e %)) req)]
                          (when (or added? (seq after))
                            (when-let [gone (seq (remove (set after) req))]
                              (let [was? (some #(has? db-before e %) (own-required k))
                                    broke (if was? (filter #(has? db-before e %) gone) gone)]
                                (when (seq broke) {:at [e (str k)] :e e :k k :req req :broke (vec broke)}))))))
                      touched)]
     (when (seq broken)
       (let [ident (fn [{:keys [e req]}] (if-let [a (some #(when (contains? identity-schemas %) %) req)]
                                           (if-let [v (:v (first (d/datoms db-after :eavt e a)))] [a v] e) e))
             text #(print/text (ident %) (:part print/budgets))
             all (sort-by :at broken)
             {:keys [k broke] :as b} (first all)]
         ;; every entity the write broke is named, so one turn fixes them all
         (contract/error :seon.db/invalid-entity #'invalid-entity
                         (str (text b) " holds attributes of " k ", which requires "
                              (str/join ", " broke) ": a write that leaves them out breaks it"
                              (when (next all) (str " (and " (dec (count all)) " more, each in :entities)")))
                         {:entity (text b) :schema k :missing broke :entities (mapv text all)}))))))

(defn supplied-instant
  "The typed refusal of a transaction that supplies its own time, or nil: :db/txInstant in tx-meta, or asserted by a map or a
  :db/add of tx-data. The store stamps a transaction's time, strictly after its branch's last (Datahike's next-tx-instant), so
  no two transactions of a branch share an instant and none is back-dated (the owner: \"We should never record two
  transaction times that are the same\"). A carried datom (a record, a merge's) is not asked, as invalid-datom does not ask
  it. See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :any [:maybe :map]] [:maybe :seon.error/base]]}
  [tx-data tx-meta]
  (when (or (contains? tx-meta :db/txInstant)
            (some (fn [op] (cond (map? op) (and (not (record? op)) (contains? op :db/txInstant))
                                 (vector? op) (and (= :db/add (first op)) (= :db/txInstant (nth op 2 nil)))
                                 :else false))
                  tx-data))
    (contract/error :seon.db/invalid-datom #'supplied-instant
                    "a transaction's time is the store's: :db/txInstant is never supplied (in tx-data or tx-meta); the store stamps it after the branch's last"
                    {:attribute :db/txInstant})))

(defn- refuse!
  "Throw error e (a typed refusal) as the transaction's answer. See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :map] :nil]}
  [e] (throw (ex-info (:seon.error/message e) e)))

(defn- checked
  "Transaction tx-data applied to db by apply-fn (Datahike's, given the tx-data: its report), held to both checks: each
  datom's value (`invalid-datom`, with schemas {:forms :registry}, a branch's own) before, a lookup ref to nothing
  (:seon.db/no-target), each entity it touched (`invalid-entity`, by the branch's `write-index`) after; an entity retracted whole takes the entities that require a ref to it with it.
  A refusal throws its typed error, and the value is as it was. See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :any :any [:maybe :map] ifn?] :map]}
  [db tx-data schemas apply-fn]
  (when-let [e (supplied-instant tx-data nil)] (refuse! e))
  (let [ix (write-index schemas)
        ;; ends-with: tx-data with each entity retracted whole followed by the entities that require a ref to it (a vector's
        ;; identity, :seon.vector/of; a cite's :seon.cite/target; a form's :form/ns): such an entity has no meaning without its
        ;; target, and the entity check would refuse it left without a key its schema requires, so it ends with it, as Datahike
        ;; ends a component with its parent; derived from the schema (a ref attribute an entity schema requires), so no writer
        ;; retracts it by hand. One AVET seek per required ref a retraction names (no attribute is a component, so none of
        ;; Datahike's own cascade is followed). A cite it ends leaves its task :seon.task/gone, its target's identity value
        ;; added, in the same write (docs/references.md#task-references): the text that still names it is the task's problem
        gone-tx (fn [ended cites]
                  (let [ids (keys (:identity-schemas ix))
                        by (reduce (fn [m [x e]]
                                     (let [of (:v (first (d/datoms db :eavt x :seon.cite/of)))
                                           v (some #(:v (first (d/datoms db :eavt e %))) ids)]
                                       (if (and of (not (ended of)) (seq (d/datoms db :eavt of :seon.task/id)) (some? v))
                                         (update m of (fnil conj []) v)
                                         m)))
                                   {} cites)]
                    (for [[t vs] by]
                      [:db/add t :seon.task/gone (vec (distinct (into (vec (:v (first (d/datoms db :eavt t :seon.task/gone)))) vs)))])))
        ends-with (fn [tx-data]
                    (let [whole? (fn [op] (and (vector? op) (#{:db.fn/retractEntity :db/retractEntity} (first op))))]
                      (if-not (some whole? tx-data)
                        tx-data
                        (let [schema (:schema db)
                              ;; the refs some entity schema requires, read once with the index (:required-refs), not walked each write
                              refs (filterv #(= :db.type/ref (get-in schema [% :db/valueType])) (:required-refs ix))]
                          (loop [todo (keep #(when (whole? %) (:db/id (d/entity db (second %)))) tx-data) seen #{} cites [] out (vec tx-data)]
                            (if-let [e (first todo)]
                              (if (seen e)
                                (recur (rest todo) seen cites out)
                                (let [found (for [a refs d (d/datoms db :avet a e)] [a (:e d)])
                                      dependents (map second found)]
                                  (recur (concat (rest todo) dependents) (conj seen e)
                                         ;; the cites among them, from the same seek
                                         (into cites (for [[a x] found :when (= a :seon.cite/target)] [x e]))
                                         (into out (for [x dependents :when (not (seen x))] [:db.fn/retractEntity x])))))
                              (into out (gone-tx seen cites))))))))
        tx-data (ends-with tx-data)]
    (when-let [e (invalid-datom db tx-data schemas)] (refuse! e))
    ;; Datahike refuses a lookup ref that names no entity (a cite's target, a wiki link: its :entity-id/missing): made the
    ;; typed :seon.db/no-target naming the lookup; any other throw is Datahike's own (docs/references.md#an-entity-cites)
    (let [r (try (apply-fn tx-data)
                 (catch clojure.lang.ExceptionInfo x
                   (let [{:keys [error entity-id]} (ex-data x)]
                     (if (and (= :entity-id/missing error) (vector? entity-id) (= 2 (count entity-id)) (qualified-keyword? (first entity-id)))
                       ;; who names it: the entity of this write holding that lookup (a value of it, or one of a set of them: a
                       ;; section's links); a cite's is what it is of, a lookup ref or the tempid of an entity the same write makes
                       ;; (a task, a section), each read as its identity value (its task id, its address)
                       (let [holds? (fn [m] (some (fn [v] (or (= v entity-id) (and (coll? v) (some #{entity-id} v)))) (vals m)))
                             m (some #(when (and (map? %) (holds? %)) %) tx-data)
                             named (fn [x] (or (:seon.task/id x) (:seon.doc/address x)))
                             of (:seon.cite/of m)
                             by (cond (vector? of) (second of)
                                      (some? of) (some #(when (and (map? %) (= of (:db/id %))) (named %)) tx-data)
                                      :else (some-> m named))]
                         (refuse! (contract/error :seon.db/no-target #'checked (str (if by (str by " cites ") "a cite or a link names ") (second entity-id)
                                                                                    ", which nothing on this branch is: a cite or a link names what exists, or "
                                                                                    "what the same write made before it; a name that does not exist yet is written in two backticks, ``ns/name``")
                                                  (cond-> {:target entity-id} by (assoc :by by)))))
                       (throw x)))))]
      (when-let [e (invalid-entity r ix)] (refuse! e))
      r)))

(defn transact-checked
  "The writer's transact (`datahike.writing/transact!`) through both checks (`checked`): the connection's 'transact!
  (`connect` adds it to every connection), so root's batch and an agent's run-ahead apply pass it with no call of their own. arg-map's
  :seon.db/schemas (a branch's own schema changes, {:forms :registry}) are the per-datom check's. See
  docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :any :map] :map]}
  [db arg-map]
  (when-let [e (supplied-instant nil (:tx-meta arg-map))] (refuse! e))
  (checked db (:tx-data arg-map) (:seon.db/schemas arg-map) #(datahike.writing/transact! db (-> arg-map (dissoc :seon.db/schemas) (assoc :tx-data %)))))

(defn with
  "The one transaction on a database value (Datahike's `d/with`) through both checks (`checked`): every speculation, an
  agent's test fork and a test's world write through it, as the writer's transact does. Answers the report; schemas
  are a branch's own ({:forms :registry}) or nil. A Datahike transaction call at a site docs/storage.md does not
  name is red (bin/laws transactions). See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:function [:=> [:cat :any :any] :map] [:=> [:cat :any :any [:maybe :map]] :map]]}
  ([db tx-data] (with db tx-data nil))
  ([db tx-data schemas] (checked db tx-data schemas #(d/with db %))))

(defn speculate
  "tx-data on db as a hypothetical (Datahike's `d/with`), unchecked: nothing of it is stored, so it is not data entering
  the system; the write that may follow (the same transaction through a connection's `'transact!`) is checked there, once
  (validation at transaction time, never the same data checked again). A state the system shows or
  relies on is written, never only speculated. Each caller is named in docs/storage.md (bin/laws transactions). Answers the report."
  {:malli/schema [:=> [:cat :any :any] :map]}
  [db tx-data] (d/with db tx-data))

(defn speculate-db
  "The db after the hypothetical tx-data (`speculate`'s :db-after). See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [db tx-data] (:db-after (speculate db tx-data)))

(defn project-tx
  "tx-data cut to the attributes attr? keeps (a set of them, or a predicate), so a speculation that reads only those attributes after it (a config check,
  a count) applies what it reads and not the rest: each map keeps its :db/id and those attributes (one left with none goes),
  each :db/add and :db/retract of such an attribute stays, and every :db/retractEntity stays, in order. Tempids join the maps
  that kept them as before. The full import of the checkout speculated whole cost 2.7 G ticks and 4.7 GB a call.
  See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :any [:or [:set :keyword] :seon.host/fn]] [:vector :any]]}
  [tx-data attr?]
  (into [] (keep (fn [x] (cond (map? x) (let [m (into {} (filter (fn [[k _]] (or (= :db/id k) (attr? k)))) x)]
                                         (when (seq (dissoc m :db/id)) m))
                               (= :db/retractEntity (first x)) x
                               (#{:db/add :db/retract} (first x)) (when (attr? (nth x 2)) x)
                               :else x)))
        tx-data))

(defn db-with
  "The db after tx-data (`with`'s :db-after). See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:function [:=> [:cat :any :any] :any] [:=> [:cat :any :any [:maybe :map]] :any]]}
  ([db tx-data] (:db-after (with db tx-data)))
  ([db tx-data schemas] (:db-after (with db tx-data schemas))))

(defn eid
  "The entity id of the entity whose unique attribute a has value v on db, or nil: one seek
  in a's AVET index, never a query (a Datalog query here cost ~95 us and 100 KB at 100
  tasks, planned on every call). The one entity lookup (docs/storage.md#entity-ids)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :any] [:maybe :int]]}
  [db a v] (:e (first (d/datoms db :avet a v))))

(def ^:private result-parts
  "The keys of an eval's response that are its result (:seon.result/*): what an answer is, and what the handle hashes."
  {:value :seon.result/value :ex :seon.result/ex :out :seon.result/out :err :seon.result/err :forms :seon.result/forms})

(def ^:private response-rank
  "The order an eval's response keys have always been held in (seon.repl/response), so what a reader prints of it does not move
  when its result is kept apart; any other key follows."
  {:handle 0 :forms 1 :value 2 :recorded 3 :redefined 4 :unmapped 5 :warnings 6 :out 7 :err 8 :ex 9 :ms 10 :ticks 11 :bytes 12})

(defn with-response
  "Eval e holding whole response r as it is kept: its value, error, output and forms in its result (:seon.eval/result, a nested map
  whose identity is :seon.result/hash, the full hash as text of [value ex out err forms] by hasch's uuid, the same in every process;
  the value of an eval's own response, an edit!'s, is hashed without its cost), the rest of r on :seon.eval/response. The hash is always of what's stored, so one hash is one value on every branch. No result when r answers none of
  them. See docs/users.md#what-each-thing-is."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [e r]
  (let [parts (select-keys r (keys result-parts))
        v (:value r)
        v (if (and (map? v) (not (record? v)) (contains? v :handle)) (dissoc v :handle :ms :ticks :bytes :fns :record) v)]
    (cond-> (assoc e :seon.eval/response (apply dissoc r (keys result-parts)))
      (seq parts) (assoc :seon.eval/result (into {:seon.result/hash (str (hasch/uuid [v (:ex r) (:out r) (:err r) (:forms r)]))}
                                                 (keep (fn [[k a]] (when (some? (get parts k)) [a (get parts k)])))
                                                 result-parts)))))

(defn response-of
  "Eval e's whole response, as an eval has always answered it: what it keeps itself (:seon.eval/response) with its result's value,
  error, output and forms (:seon.eval/result, read from db when e holds only its ref); a result with no value and no error answers
  :value nil. An eval holding no result answers its response as it is. See docs/users.md#what-each-thing-is."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:maybe :map]]}
  [db e]
  (let [resp (:seon.eval/response e)
        x (:seon.eval/result e)
        r (cond (nil? x) nil (contains? x :seon.result/hash) x :else (d/pull db '[*] (:db/id x)))]
    (if (nil? r)
      resp
      (let [parts (into {} (keep (fn [[k a]] (when (contains? r a) [k (get r a)]))) result-parts)
            parts (cond-> parts (not (or (contains? parts :value) (contains? parts :ex))) (assoc :value nil))]
        (into (array-map) (sort-by (fn [[k]] (get response-rank k 99)) (concat resp parts)))))))

(defn seed-tx
  "Transactions of an example program sized by n (the test store's and the check store's
  seed, never a root's own): n/110 namespaces (none below 110), each one tx of the
  namespace and its 20 contracted functions (each a form and its var: 12 datoms, so about
  2n datoms in all), then one tx of a task referring to two of its vars."
  {:malli/schema [:=> [:cat :int] [:sequential :seon.db/tx-data]]}
  [n]
  (let [nns (quot n 110)]
    (mapcat (fn [k]
              (let [nsym (symbol (str "app.m" k))]
                [(into [{:db/id "ns" :ns/name nsym :ns/doc (str "Module " k " of the example program.")}]
                       (for [i (range 20)
                             :let [src (str "(defn f" i " {:malli/schema [:=> [:cat :int] :int]} [x] (+ x " i "))")]
                             e [{:db/id (str "form " i) :form/id (str nsym "/f" i) :form/src src :form/ns "ns" :form/order i
                                 :form/defines [(str "var " i)]}
                                {:db/id (str "var " i) :var/name (symbol (str nsym) (str "f" i)) :var/ns "ns" :var/doc (str "f" i " of module " k)
                                 :var/form (str "form " i) :var/meta #{:arglists} :var/contract "[:=> [:cat :int] :int]"}]]
                         e))
                 [{:task/name (keyword (str "app.m" k) "tidy") :task/plan (str "Tidy module " k ": name every value by its schema.")
                   :task/status :held :task/refs [[:var/name (symbol (str nsym) "f0")] [:var/name (symbol (str nsym) "f1")]]}]]))
            (range nns))))

;; :writer :sync (vendor datahike.writer, session F1): root's Flow :writer proc is the only
;; caller of transact, so it is THE serial writer; Datahike runs no queue, commit loop or
;; go block of its own (memory: commit p50 0.49 -> 0.39 ms, 639 -> 446 KB/tx, 2 threads
;; fewer per connection; src/proto/f1_bench.clj).
(def writer-cfg {:backend :sync})

(defn connect
  "A connection to the store and branch of cfg (Datahike's `d/connect`), its writer's transact the one path's
  (`transact-checked`), so every write through it is checked. The one way a connection is opened (bin/laws
  transactions): the hook is added here, never stored in cfg, which is plain data that travels (a fork's message, a
  stored config): a Var in it could not be encoded, and the fork it rode never came.
  See docs/storage.md#the-write-checks-every-entity-it-touches."
  {:malli/schema [:=> [:cat :map] :any]}
  [cfg] (d/connect (assoc-in cfg [:writer :write-fn-map] {'transact! transact-checked})))

;; ---- the durable store ----------------------------------------------------------
;; konserve on Jolt (libs/konserve): LMDB (konserve.jolt.lmdb) or the POSIX filestore,
;; holding E4a's real index nodes, one content-addressed node per key. Branching factor
;; 64: a commit rewrites the nodes it touched, so smaller nodes mean fewer bytes printed
;; and written per commit (dev/dur.clj: 512 -> 64 cut a small commit 27 -> 16 ms with
;; fsync, before the node emitter). LMDB runs MDB_NOSYNC and the flusher syncs the env
;; once for every commit so far (sync!), so a commit is at most one drive flush, not one per node.
;; MDB_SAFENOSYNC (our LMDB fork's): an OS crash rolls the store back to the last sync, never
;; corrupts it (docs/failure-points.md#lmdb-crash-safety).

(def ^:private MDB_NOSYNC 0x10000)
(def ^:private MDB_SAFENOSYNC 0x8000)

(defn data-file
  "The file that holds the durable store at path: an LMDB store's data.mdb, or path itself for
  the :file backend. What tells whether a store is there, and what the ids page's stamp names
  (ids!, docs/storage.md#ids)."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string] :string]}
  [backend path] (if (= backend :lmdb) (str path "/data.mdb") path))

(defn- stored-id
  "The id the store cfg names was made with, read from its :db head's stored config (0.8 ms),
  or nil when no store is there yet. The store it connects for the read is released."
  {:malli/schema [:=> [:cat :seon.db/cfg] [:maybe :uuid]]}
  [cfg]
  (let [{:keys [backend path]} (:store cfg)]
    (when (posix/exists? (data-file backend path))
      (let [store (konserve.store/connect-store (:store cfg) {:sync? true})
            id (-> (datahike.store/add-cache-and-handlers store (datahike.config/load-config cfg))
                   (datahike.writing/read-head :db)
                   (get-in [:config :store :id]))]
        (konserve.store/release-store (:store cfg) store {:sync? true})
        id))))

(defn store-cfg
  "Datahike config for the durable store at path; backend :lmdb (default) or :file. A store is
  named by the id it records, never by where it lies: a kept run opens wherever it is copied,
  moved or archived (stored-id); the path names only a store this config is about to make.
  A copy keeps its original's id, as a replica does, so one process opens one of them. It names
  no :keep-history? and no :crypto-hash? (the store's `layout`): they are fixed when a store is made (Datahike's store-fixed-record-keys;
  made-cfg), and a connect adopts what the store records, so a kept store made either way opens."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string] :seon.db/cfg]}
  [backend path]
  (let [cfg {:store (cond-> {:backend backend :path path :id (hasch/uuid path)
                             ;; how a value becomes bytes, where konserve reads it (konserve.impl.defaults/normalize-store-config)
                             :config {:encoding {:serializer :FaslSerializer}}}
                      (= backend :lmdb) (assoc :lmdb {:flags (bit-or MDB_NOSYNC MDB_SAFENOSYNC) :map-size (cfg/value :seon.config.store/lmdb-map-bytes)})
                      ;; konserve's per-value compressor (its header names it, so stored values of either kind read)
                      (#{:lz4 :zstd} (cfg/value :seon.config.store/compressor)) (assoc-in [:config :encoding :compressor] {:type (cfg/value :seon.config.store/compressor)})
                      (= backend :file) (assoc-in [:config :defer-immutable-sync?] true))
             :schema-flexibility :write
             :store-cache-bytes (cfg/value :seon.config.store/node-cache-bytes)
             :writer writer-cfg
             :index-config (let [n (cfg/value :seon.config.store/value-out-of-line-bytes)]
                             (cond-> {:branching-factor (cfg/value :seon.config.store/branching-factor)}
                               (int? n) (assoc :value-out-of-line-bytes n)))}]
    (if-let [id (stored-id cfg)] (assoc-in cfg [:store :id] id) cfg)))

(defn made-cfg
  "cfg as a store is made with it: its `layout`, history kept as :seon.config.store/keep-history? says (its temporal
  indexes, and each transaction's tx-meta; v1's ruling 23) and its index nodes addressed by their content."
  {:malli/schema [:=> [:cat :seon.db/cfg] :seon.db/cfg]}
  [cfg] (merge cfg layout))

(defn sync!
  "Make every commit so far durable: one env sync for LMDB, which makes the newest commit the
  store's steady snapshot, the one an OS crash rolls back to (MDB_SAFENOSYNC,
  docs/storage.md#durability-is-continuous-group-commit); the filestore syncs per write itself. nil."
  {:malli/schema [:=> [:cat :seon.db/cfg] :nil]}
  [cfg]
  (when (= :lmdb (get-in cfg [:store :backend]))
    (lmdb-ffi/sync! (klmdb/open-env! (get-in cfg [:store :path]) nil)))
  nil)

(defn tx-meta
  "The tx-meta of every transaction Seon makes: the runtime that made it
  (:seon.tx/runtime, jolt.host/runtime-fingerprint) and the branch it is written on; and, given
  them, the eval whose code made it (:seon.tx/eval) and the branch a catch-up merged
  (:seon.tx/merged): provenance is a query on these (docs/ctx.md#provenance-who-made-each-fact). A runtime Jolt
  cannot identify (nil) is refused by this contract, never recorded as no runtime."
  {:malli/schema [:function [:=> [:cat :seon.db/branch] [:map [:seon.tx/runtime :string] [:seon.tx/branch :seon.db/branch]]]
                  [:=> [:cat :seon.db/branch [:map [:seon.tx/eval {:optional true} :seon.tx/eval] [:seon.tx/merged {:optional true} :seon.tx/merged]]]
                   [:map [:seon.tx/runtime :string] [:seon.tx/branch :seon.db/branch]]]]}
  ([branch] (tx-meta branch {}))
  ([branch more] (merge {:seon.tx/runtime (jolt.host/runtime-fingerprint) :seon.tx/branch branch} more)))

(defn main-branch
  "The branch the root holding cfg works on as its main: cfg's :branch, :db when none."
  {:malli/schema [:=> [:cat :seon.db/cfg] :seon.db/branch]}
  [cfg] (:branch cfg :db))

(defn conn-main
  "The main of the root holding conn (its connection to main): the branch conn is on."
  {:malli/schema [:=> [:cat :seon.host/conn] :seon.db/branch]}
  [conn] (main-branch (:config @conn)))

(def retired-names
  "Stored names a rename retired, {old new}: a stored keyword is renamed by erasing the store, never by a migration, so a store holding one is refused at open (install-missing!). See docs/storage.md#a-renamed-attribute."
  {:schema.file/path :file/path :schema.file/text :file/text})

(defn install-missing!
  "Transact the attributes of `schema` the db under conn lacks, or holds without their
  declared :db/contentId, and answer the idents it added: first
  :seon.tx/runtime alone when it is missing (the one transaction that cannot name its
  runtime, since it installs the attribute), then the rest in one transaction carrying
  tx-meta (an attribute it holds as another value type is refused, :seon.db/types-differ:
  a value type is fixed once installed; one it holds without its declared content id or index
  gains it): a kept store seeded before an attribute existed gains it when a root
  opens it, and a branch cut before one existed when its agent resumes it (seon.agent's
  resume: a branch keeps the schema it was cut with; Datahike accepts new attribute
  definitions at any time). A store holding a name a rename retired (retired-names) is refused, :seon.db/name-retired: it would gain the
  new name and hold nothing under it. Nothing missing writes nothing."
  {:malli/schema [:=> [:cat :seon.host/conn] [:set :keyword]]}
  [conn]
  (let [have (:schema @conn)
        differ (into {} (for [{k :db/ident t :db/valueType} schema
                              :let [stored (get-in have [k :db/valueType])]
                              :when (and stored (not= stored t))]
                          [k {:stored stored :declared t}]))
        _ (when (seq differ)
            (let [e (contract/error :seon.db/types-differ #'seon.db/install-missing!
                                    (str "the store holds " (count differ) " attributes as another value type than the schema declares (each in :seon.db/attributes)")
                                    {:seon.db/attributes differ
                                     :seon.error/fix "An attribute's value type is fixed once installed: open a store made with this schema (a fresh one), or keep running the build that made this store."})]
              (throw (ex-info (:seon.error/message e) e))))
        retired (into {} (filter (comp #(contains? have %) key)) retired-names)
        _ (when (seq retired)
            (let [e (contract/error :seon.db/name-retired #'seon.db/install-missing!
                                    (str "the store holds " (count retired) " attributes under names a rename retired (each in :seon.db/retired): it was made by an older build, and nothing in it was changed")
                                    {:seon.db/retired retired
                                     :seon.error/fix "A stored keyword is renamed by erasing the store, never by a migration: erase this store and start fresh, or keep running the build that made it."})]
              (throw (ex-info (:seon.error/message e) e))))
        ;; an identity a kept store holds without its content id gains it (Datahike admits
        ;; adding :db/contentId: its entities keep their ids, new ones mint at content ids), and an
        ;; attribute held without its declared index gains it (Datahike admits adding :db/index in
        ;; place: its datoms are in the AVET index at once)
        missing (vec (remove #(and (contains? have (:db/ident %))
                                   (= (:db/contentId %) (get-in have [(:db/ident %) :db/contentId]))
                                   (or (not (:db/index %)) (get-in have [(:db/ident %) :db/index])))
                             schema))]
    (let [[tx-attrs others] ((juxt filter remove) #(#{:seon.tx/runtime :seon.tx/branch} (:db/ident %)) missing)]
      (when (seq tx-attrs) (d/transact conn {:tx-data (vec tx-attrs)}))
      (when (seq others) (d/transact conn {:tx-data (vec others) :tx-meta (tx-meta (conn-main conn))})))
    (into #{} (map :db/ident) missing)))

(defn seed!
  "Seed the empty store under conn: the schema (install-missing!), then (seed-tx n)."
  {:malli/schema [:=> [:cat :seon.host/conn :int] :any]}
  [conn n]
  (install-missing! conn)
  (doseq [tx (seed-tx n)] (d/transact conn {:tx-data tx :tx-meta (tx-meta (conn-main conn))})))

;; ---- one store, many branches, as in production (DECISIONS, Branches as in production) --
;; A store holds one seed on :db. Every root works on a main of its own, a branch of :db
;; (production's root works on :db itself; each check step, each run, each test on a
;; branch), and its agents' and tasks' branches are named under that main, so roots
;; sharing a store never share a branch. A branch is one pointer write (~1 ms measured)
;; where a fresh seeded store was ~200 ms and ~2 MB.

(defn off-main
  "Branch b as the root whose main is `main` names it in its facts (an agent's :seon.agent/branch, a task's
  :seon.task/started-by): on-main undone, b with main's qualifier taken off (:smoke-x/agent-1 is :agent-1 on
  main :smoke-x); b itself on :db or when it carries no qualifier of main's. See docs/storage.md#a-roots-main-and-its-branch-names."
  {:malli/schema [:=> [:cat :seon.db/branch :seon.db/branch] :seon.db/branch]}
  [main b]
  (if (and (not= :db main) (= (name main) (namespace b))) (keyword (name b)) b))

(defn on-main
  "Branch b of the root whose main is `main`, as the store names it: :db names main itself;
  on :db (production) b is b; on any other main b is qualified by main's name (:smoke-x/agent-1).
  Idempotent."
  {:malli/schema [:=> [:cat :seon.db/branch :seon.db/branch] :seon.db/branch]}
  [main b]
  (cond (= :db b) main
        (or (= :db main) (= main b) (= (name main) (namespace b))) b
        :else (keyword (name main) (if (namespace b) (str (namespace b) "." (name b)) (name b)))))

(defn of-main
  "The branches of the store held under main, by the names its root gives them (on-main undone),
  main itself left out."
  {:malli/schema [:=> [:cat :seon.db/branch [:set :seon.db/branch]] [:vector :seon.db/branch]]}
  [main branches]
  (vec (sort (for [b branches
                   :when (if (= :db main) (and (nil? (namespace b)) (not= :db b)) (= (name main) (namespace b)))]
               (off-main main b)))))

(defn branch?
  "Does the store hold branch b? Its head key exists exactly when the branch does
  (datahike.roster), an O(1) read; d/branches folds the whole roster, O(branches)."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :boolean]}
  [conn b] (boolean (k/exists? (:store @conn) b {:sync? true})))

(defn open-main!
  "main on the store of cfg, whose :db exists: made from :db's head when the store has no
  such branch (one pointer write), on a connection that streams when root is the store's sole writer
  (:seon.config.store/sole-writer?). Answers [conn existed?]."
  {:malli/schema [:=> [:cat :seon.db/cfg :seon.db/branch] [:tuple :seon.host/conn :boolean]]}
  [cfg main]
  (let [;; root alone commits main when it is the store's sole writer: its connection streams (datahike's :writer :streaming?),
        ;; so a deref is the connection's own value, never the store's head read again (24 ticks against 58,739 and 259 KB). Only
        ;; this connection: the fork server's and the agents' read main as root commits it (the cfg answered keeps no :streaming?)
        own (cond-> cfg (cfg/value :seon.config.store/sole-writer?) (assoc-in [:writer :streaming?] true))
        seed-conn (connect (if (= :db main) own cfg))
        existed? (or (= :db main) (branch? seed-conn main))]
    (when-not existed? (d/branch! seed-conn :db main))
    (if (= :db main)
      [seed-conn existed?]
      (let [conn (connect (assoc own :branch main))] (d/release seed-conn) [conn existed?]))))

(defn agents-dir
  "Where the agents of the root holding cfg keep their stderr (seon.agent/stderr-path):
  <store path>-agents/<main>, or nil for a store with no path (in memory)."
  {:malli/schema [:=> [:cat :seon.db/cfg] [:maybe :string]]}
  [cfg] (when-let [p (get-in cfg [:store :path])] (str p "-agents/" (name (main-branch cfg)))))

(defn empty-db
  "An empty db value with Seon's schema (`schema`), in memory, keeping history when history?: a test's world
  (my.test/world) and a tree indexed with no store (seon.root/built-program). See docs/testing.md#testing-in-the-world."
  {:malli/schema [:=> [:cat :boolean] :seon.host/db]}
  [history?] (datahike.core/empty-db (into {} (map (juxt :db/ident #(dissoc % :db/ident))) schema)
                                     {:keep-history? history? :schema-flexibility :write}))

(defn delete-main!
  "Delete main and every branch held under it (of-main) from the store of conn, a connection
  to :db (datahike.api/delete-branch!; a branch's connections are released first): what a
  check step or a test made for itself, deleted by the run that made it. Real agent runs are
  never deleted this way (DECISIONS, Runs are kept, history and all). Answers the deleted branches;
  a main the store lacks deletes nothing."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] [:vector :seon.db/branch]]}
  [conn main]
  (let [under (mapv #(on-main main %) (of-main main (d/branches conn)))
        bs (cond-> under (branch? conn main) (conj main))]
    (doseq [b bs] (d/delete-branch! conn b))
    bs))

(defn tree-branch?
  "Whether b names a checkout's proving branch, tree-<its 40-hex tree id> (seon.root/tree-branch): the only branch the system
  drops by itself. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :seon.db/branch] :boolean]}
  [b] (boolean (re-matches #"tree-[0-9a-f]{40}" (name b))))

(defn drop-trees!
  "Delete every tree branch (tree-branch?) held under main from the store of conn, answering them by the names root gives them
  (of-main). Root's boot calls it before any agent exists: each found then is one whose last run an end of root cut short, its
  agent gone with root, which that run would have dropped. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] [:vector :seon.db/branch]]}
  [conn main]
  (let [bs (filterv tree-branch? (of-main main (d/branches conn)))]
    (doseq [b bs] (d/delete-branch! conn (on-main main b)))
    bs))

(defn preload!
  "Read every node of db's indexes into this process's node cache, so the children it forks
  (the fork server's agents) inherit them decoded and read the store less at a spawn. The fork
  server calls it once, through its own store, after its fork (docs/machine.md#roots-heap).
  Answers the datoms its three indexes hold, counted leaf by leaf: each index is one reduce over
  the sorted set's own slice, which restores every node on its way and makes nothing a datom
  (counting (d/datoms db index) walked each index through Datahike's lazy seq, a cell a datom: a small part of the 1.2 s
  a root's first fork spends here; the rest is decoding leaves)."
  {:malli/schema [:=> [:cat :seon.host/db] :int]}
  [db]
  (reduce (fn [n index] (reduce (fn [k _] (inc k)) n (pss/slice (get db index) nil nil)))
          0 [:aevt :avet :eavt]))

(lib/defc c-ids-open "seon_ids_open" [:string] :pointer {:capture-native-error true})
(lib/defc c-ids-take "seon_ids_take" [:pointer :int] :uint64)
(lib/defc c-ids-reach "seon_ids_reach" [:pointer :int :uint64] :void)
(lib/defc c-ids-trusted "seon_ids_trusted" [:pointer :string :string] :int)
(lib/defc c-ids-stamp "seon_ids_stamp" [:pointer :string :string] :void)

(defn ids!
  "Make the store of conn take its entity ids and transaction ids from one page every
  process writing it shares (native/ids.c): the file `ids` beside a durable store, or an
  anonymous page for a store in memory; a process forked from this one maps the same page.
  Each id is issued once across every branch and process, so a datom keeps its e and tx
  wherever it travels (docs/storage.md#ids: 28-34 ns an id, 200-240 ns with 10
  processes contending). Every branch head is read, and the page moved past their ids
  (datahike.writing/set-ids!, O(branches)), only when the page's stamp is not this boot's
  and this file's and the store's data file's (seon_ids_trusted, data-file): a new page, a copied
  one, one made again, one beside a data file replaced or copied in, one from before the OS last
  booted, or an anonymous one; the page is then stamped. A stamped page is already
  past every head, so an open reads none. An id issued but never committed is a gap, never
  reused. A page the OS refuses is :seon.db/ids-unopened (docs/failure-points.md). nil."
  {:malli/schema [:=> [:cat :seon.host/conn] :nil]}
  [conn]
  ;; one deref: each reads the branch head from the store (two reads, ~58k ticks)
  (let [db @conn
        {:keys [backend path id]} (get-in db [:config :store])
        file (if (= :memory backend) "" (str path "/ids"))
        data (if (= :memory backend) "" (data-file backend path))
        [page errno] (c-ids-open file)]
    (when (zero? (ffi/address page))
      (let [e (contract/error :seon.db/ids-unopened #'seon.db/ids!
                              (str "the store's ids page " file " was refused: " (ffi/errno-message errno))
                              {:path file :errno errno
                               :seon.error/fix "Make the store's directory writable (the ids page lives beside data.mdb), then open the store again."})]
        (throw (ex-info (:seon.error/message e) e))))
    (let [store (:store db)
          ids (reify datahike.ids/IIds
                (-take! [_ kind] (c-ids-take page (if (= :eid kind) 0 1)))
                (-reach! [_ kind n] (c-ids-reach page (if (= :eid kind) 0 1) n) nil))]
      (if (= 1 (c-ids-trusted page file data))
        (datahike.writing/set-ids! store id ids {:past-heads? true})
        ;; stamped only once moved past the heads: a process killed between the two leaves no stamp
        (do (datahike.writing/set-ids! store id ids) (c-ids-stamp page file data))))
    nil))

(defn store?
  "Does a durable LMDB store exist at path (its data file)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [path] (boolean (posix/exists? (data-file :lmdb path))))

(defn- make!
  "A store made whole at path, or none (task seon.db/a-store-is-made-whole-or-not-at-all): built
  in a sibling directory, path.making-<uuid> (created, seeded with about n datoms, synced, its
  connection released so its env closes), then renamed to path, which is atomic on one
  filesystem. A store is named by the id it records, never its path (store-cfg), so it opens
  where it lands. A create that fails or is killed leaves only its own directory, never a data
  file holding no database at path (which every later open read as \"Database does not exist\");
  a killed one's directory is garbage, nothing kept. A rename that finds a store at path lost a
  race to another creator: its directory goes and the winner's store is opened. One that finds
  anything else there is :seon.db/store-unplaced (docs/failure-points.md). Answers whether this
  call's store is at path."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string :int] :boolean]}
  [backend path n]
  (let [tmp (str path ".making-" (random-uuid))
        tcfg (store-cfg backend tmp)
        placed (volatile! false)]
    (try
      (d/create-database (made-cfg tcfg))
      (let [conn (connect tcfg)] (try (seed! conn n) (sync! tcfg) (finally (d/release conn))))
      (let [[rc errno] (posix/c-rename tmp path)]
        (vreset! placed (zero? rc))
        (when-not (or @placed (posix/exists? (data-file backend path)))
          (let [e (contract/error :seon.db/store-unplaced #'seon.db/make!
                                  (str "a store made at " tmp " could not be renamed to " path ": " (ffi/errno-message errno))
                                  {:path path :errno errno
                                   :seon.error/fix "Remove what stands at the path (it holds no store: no data file), then open the store again."})]
            (throw (ex-info (:seon.error/message e) e)))))
      @placed
      (finally
        ;; a create that threw midway may hold its env (Datahike releases the store only at its end)
        (when-not @placed
          (when (= backend :lmdb) (klmdb/close-env! tmp))
          (run! #(.delete ^java.io.File %) (reverse (file-seq (java.io.File. tmp)))))))))

(defn open!
  "Root's main on the durable store at path. A store that does not exist is made, whole or not
  at all (make!), seeded with about n datoms on :db; main (a branch name) is made from :db's
  head when the store has no such branch, or recovered when it has. Answers {:cfg (naming
  main) :conn :recovered? (main existed) :seeded? (this call made the store)}. A value of
  :conn is read before the connection's last release (d/release): that release closes the
  store's LMDB env, and a read after it answers :lmdb-env-closed (docs/storage.md)."
  {:malli/schema [:=> [:cat [:enum :lmdb :file] :string :int :seon.db/branch] :seon.db/open]}
  [backend path n main]
  (let [made? (and (not (posix/exists? (data-file backend path))) (make! backend path n))
        ;; a store this call did not make: found there, or made by a creator that won the rename
        exists? (not made?)
        ;; read once the store is there: it records the id it was made with (store-cfg)
        cfg (store-cfg backend path)]
    (let [[conn existed?] (open-main! cfg main)
          _ (ids! conn)
          added (install-missing! conn)
          cfg (cond-> cfg (not= :db main) (assoc :branch main))]
      ;; durable before root runs: the seed, the new branch or the added attributes (an
      ;; fsync, ~5-10 ms); a recovered main with its schema whole wrote nothing
      (when-not (and exists? existed? (empty? added)) (sync! cfg))
      {:cfg cfg :conn conn :recovered? (and exists? existed?) :seeded? (not exists?)})))

(defn kept-store!
  "The path of the kept store of regenerable output at base for a schema's digest (the whole schema's, `schema-digest`,
  unless given), base-<digest>; a store at base made with another digest (base itself, or base-<8 hex digits> but this
  one) is removed: it is output, made again from its inputs, never a record, so a changed schema or layout makes a new
  store, nothing migrated or refused (task seon.test-store/a-kept-store-is-keyed-by-its-schema). Nothing is made here;
  the caller opens it. The test runner's program store, the process tests' store and the build's index take it. See
  docs/source.md#the-binary-records-its-program."
  {:malli/schema [:function [:=> [:cat :string] :string] [:=> [:cat :string :string] :string]]}
  ([base] (kept-store! base schema-digest))
  ([base digest]
   (let [f (java.io.File. ^String base)
         dir (.getParentFile f)
         stem (.getName f)
         keyed (str stem "-" digest)
         old (re-pattern (str "\\Q" stem "\\E(-[0-9a-f]{8})?"))]
     (doseq [n (some-> dir .list seq) :when (and (re-matches old n) (not= n keyed))]
       (lib/remove-tree! (str dir "/" n)))
     (str dir "/" keyed))))

(defn- compact-marker
  "The file in the store at path that says a sweep freed pages since its last compaction (compact-if-due!)."
  {:malli/schema [:=> [:cat :string] :string]}
  [path] (str path "/compact-due"))

(defn- compact-due!
  "Leave the mark that the store under conn holds freed pages, for its next boot to compact (compact-if-due!). An LMDB store only; nil."
  {:malli/schema [:=> [:cat :seon.host/conn] :nil]}
  [conn]
  (let [{:keys [backend path]} (get-in @conn [:config :store])]
    (when (= :lmdb backend) (spit (compact-marker path) "")))
  nil)

(defn compact-if-due!
  "When a sweep has freed pages since the last compaction, replace the LMDB store at path by a copy holding only its pages in use, whole or not at all: an LMDB
  file never shrinks, as a sweep only frees pages for reuse. Root calls it at boot, before it opens the store, as the store's only process, and no other process
  may hold the store. Answers {:bytes-before :bytes-after :ms}, nil when none is due, or the typed error of a copy that failed, leaving the store as it was.
  See docs/storage.md#collection."
  {:malli/schema [:=> [:cat :string] [:maybe :map]]}
  [path]
  (when (posix/exists? (compact-marker path))
    (let [t0 (profile/now-ns)
          data (data-file :lmdb path)
          dir (str path ".compact")
          size #(.length (java.io.File. ^String %))
          before (size data)]
      (lib/remove-tree! dir)
      (.mkdirs (java.io.File. ^String dir))
      (let [e (try (lmdb-ffi/compact-copy! (klmdb/open-env! path nil) dir) nil
                   (catch Throwable e e))]
        (klmdb/close-env! path)
        (if e
          (do (lib/remove-tree! dir)
              (contract/error :seon.db/compact-failed #'seon.db/compact-if-due! (str "compacting the store at " path ": " (ex-message e)) {:path path}))
          (do (posix/c-rename (data-file :lmdb dir) data)
              (lib/remove-tree! dir)
              (.delete (java.io.File. ^String (compact-marker path)))
              {:bytes-before before :bytes-after (size data) :ms (profile/ms-since t0)}))))))

(defn collect!
  "Collect the store under conn (datahike.api/gc-storage: every key no branch reaches, nodes
  and values out of line, is swept) when a branch the last record saw is gone (main's
  :seon.root/collected): the record to write, {:swept :ms :branches :gone}. With no record yet
  (a new main) or none gone but branches made since, the record of what is here, nothing swept,
  so a branch seen at one quiescence and gone at a later one is reclaimed; {:skipped true} when
  the branches are the ones recorded. Only while no other process writes the store (owner:
  simplest first): root's writer runs it (:collect), root refuses it while an agent
  is live, and sole? (:seon.config.store/sole-writer?) says no other root writes it; when not,
  {:skipped true :shared true} (docs/storage.md#collection)."
  {:malli/schema [:=> [:cat :seon.host/conn :boolean] [:or [:map [:skipped :boolean] [:shared {:optional true} :boolean]] :seon.root/collected]]}
  [conn sole?]
  (let [last (:seon.root/collected (d/pull @conn [:seon.root/collected] [:seon.root/id :root]))
        now (set (d/branches conn))
        gone (count (remove now (:branches last)))]
    (cond
      (not sole?) {:skipped true :shared true}
      (and last (zero? gone) (= now (:branches last))) {:skipped true}
      ;; no record yet (a new main: every check step's) or nothing gone: record what is here,
      ;; sweep nothing; a full sweep on a store of hundreds of branches held a check step's
      ;; spawns behind root's writer for seconds (scale: 8.2 s to ready)
      (zero? gone) {:swept 0 :ms 0.0 :branches now :gone 0}
      :else
      (let [t0 (profile/now-ns)
            swept @(d/gc-storage conn (java.util.Date. 0))]
        (when (seq swept) (compact-due! conn))
        {:swept (count swept) :ms (profile/ms-since t0) :branches now :gone gone}))))

(defn store-of
  "The store db reads, of a db at a commit or of one as of a t (d/as-of keeps it on the db it wraps): what a reader of a commit by id opens it through. See docs/storage.md#collection."
  {:malli/schema [:=> [:cat :any] :any]}
  [db] (or (:store db) (:store (:origin-db db))))

(defn retain!
  "Sweep the commit snapshots of the store under conn older than main's newest keep commits, when that many have landed since the last sweep (main's :seon.root/retained),
  counted by the commit graph's generation, never a clock. Kept whole, whatever their age: each branch's head, its merge base with main, and each commit an agent's facts
  name (:seon.agent/base-commit, :seon.agent/main-commit, :seon.agent/main-program), which is what a merge and a page read by id. Every datom, a branch's history and as-of reads stay: only reading
  another old commit by its id goes. Root calls it at boot, before any other process exists, so nothing live holds a swept commit, and as its sole writer. Answers
  {:swept :ms :generation}, written on main, or nil when none is due. See docs/storage.md#collection."
  {:malli/schema [:=> [:cat :seon.host/conn pos-int?] [:maybe :map]]}
  [conn keep]
  (let [store (:store @conn)
        main (get-in @conn [:config :branch])
        record #(d/commit-graph-record store %)
        generation (:datahike/generation (record (d/commit-id @conn)))
        last-g (:generation (:seon.root/retained (d/pull @conn [:seon.root/retained] [:seon.root/id :root])) 0)]
    (when (and generation (> generation keep) (>= (- generation last-g) keep))
      (let [t0 (profile/now-ns)
            ;; a commit older than the graph has no record, and one is derived from the commit record the sweep deletes: every commit gets its record first
            _ (d/backfill-commit-graph! conn)
            ;; main's chain, newest first: at a merge, the parent of highest generation; the commit keep back names the cutoff
            oldest (loop [c (d/commit-id @conn) n keep]
                     (if (zero? n) c
                         (let [ps (:datahike/parents (record c))]
                           (if-let [p (when (seq ps) (apply max-key #(:datahike/generation (record %) 0) ps))] (recur p (dec n)) c))))
            cutoff (let [{:keys [datahike/updated-at datahike/created-at]} (:meta (k/get store oldest nil {:sync? true}))] (or updated-at created-at))
            bs (d/branches conn)
            ;; a branch with more than one best ancestor has each kept; its data says which (d/merge-base's refusal)
            bases (mapcat (fn [b] (try [(d/merge-base conn b main)]
                                       (catch clojure.lang.ExceptionInfo e (or (:merge-bases (ex-data e)) []))))
                          (remove #{main} bs))
            named (mapcat (fn [b] (let [h (d/branch-as-db store b)]
                                    (mapcat #(map :v (d/datoms h :aevt %)) [:seon.agent/base-commit :seon.agent/main-commit :seon.agent/main-program])))
                          bs)
            swept @(d/gc-storage conn cutoff {:datahike.gc/keep-commits (into #{} (remove nil?) (concat bases named))})
            r {:swept (count swept) :ms (profile/ms-since t0) :generation generation}]
        (when (seq swept) (compact-due! conn))
        (d/transact conn [{:seon.root/id :root :seon.root/retained r}])
        r))))

(defn after-fork!
  "Make this forked process (an agent, a clone) open the store itself: an LMDB env and the
  connections inherited from its parent must not be used across fork(), so they are
  forgotten (not closed: they are the parent's). The store's ids page stays: fork maps the
  parent's page (ids!). Physics, not a guard: fork shares the parent's handles. Datahike
  does the connections' side (datahike.connections/after-fork!): each node cache the parent's
  connections held stays, alone, and this process's first connect to that store adopts it and
  reads what the parent decoded instead of fetching and decoding it again."
  {:malli/schema [:=> [:cat :seon.db/cfg] :any]}
  [cfg]
  (swap! @#'konserve.jolt.lmdb/envs dissoc (get-in cfg [:store :path]))
  (datahike.connections/after-fork!))

(defn map-use
  "The LMDB store's use of its map: {:used-bytes (data.mdb's length: LMDB grows the file as it
  uses pages) :map-bytes}, or nil for another backend."
  {:malli/schema [:=> [:cat :seon.db/cfg] [:maybe [:map [:used-bytes :int] [:map-bytes :int]]]]}
  [cfg]
  (when (= :lmdb (get-in cfg [:store :backend]))
    {:used-bytes (.length (java.io.File. (data-file :lmdb (get-in cfg [:store :path]))))
     :map-bytes (get-in cfg [:store :lmdb :map-size])}))

(defn head
  "Branch b's committed value, read from the store (another process may have committed it)."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch] :seon.host/db]}
  [conn b] (d/branch-as-db (:store @conn) b))

(defn branch-base
  "The db at the merge base of branch and main (git's merge-base over Datahike's commit
  graph): the branch point, the branch head root last merged, or the main head last
  merged into the branch."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.db/branch :seon.db/branch] :seon.host/db]}
  [conn main branch]
  (d/commit-as-db conn (d/merge-base conn branch main)))

(defn point
  "Where db is, as data: [its commit's id, its max-tx]. What a process keeps of an older db it
  derived something from (a graph, a diff), in place of the db, which pins its own version of the
  index; the thing derived is a cache keyed by the point, and the db is made again with at-commit
  when it must be read. Two values of one commit differ by their t (a run-ahead branch is ahead of
  its last commit). A value never committed (d/db-with of a fresh db, a test's world) has no commit id, so [nil t] names
  no one value: a cache keyed by a point reuses only a point with a commit (seon.agent's graphs and diff). See
  docs/machine.md#roots-heap."
  {:malli/schema [:=> [:cat :seon.host/db] [:tuple [:maybe :uuid] :int]]}
  [db] [(d/commit-id db) (:max-tx db)])

(defn at-commit
  "The db of commit cid on conn's store: conn's own head when cid is the commit it holds (already
  in memory, no store read), else made from the store (datahike.api/commit-as-db). What a reader
  of main's commit notices (seon.writer/main-commit) calls when it takes one, so it keeps the
  commit and lets the db go; nil when the store holds no such commit (datahike.api/commit-as-db answers
  nil, as for an id it never made). See docs/machine.md#what-a-process-owns-in-memory."
  {:malli/schema [:=> [:cat :seon.host/conn :uuid] [:maybe :seon.host/db]]}
  [conn cid]
  (let [db @conn] (if (= cid (d/commit-id db)) db (d/commit-as-db db cid))))

(defn- no-history
  "The :seon.db/no-history error of operation op when db keeps no history, else nil: d/history and d/as-of
  throw on such a db, and an empty answer would read as an entity that never changed."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/var] [:maybe :seon.db/no-history]]}
  [db op]
  (when-not (:keep-history? (:config db))
    (contract/error :seon.db/no-history op "this store keeps no history (:seon.config.store/keep-history? was false when it was made): nothing was recorded to read")))

(defn- unresolved
  "The :seon.db/unresolved error of operation op: the kind of thing asked for (:entity :branch :commit :t) and what was asked."
  {:malli/schema [:=> [:cat :seon.host/var [:enum :entity :branch :commit :t] :any] :seon.db/unresolved]}
  [op kind value]
  (contract/error :seon.db/unresolved op (str "no such " (name kind) " in this store: :value is what was asked") {:kind kind :value value}))

(defn history
  "What entity e's attributes have been, oldest first, as plain data rows {:a :v :added? :t :at} (a retraction
  before the assertion of one transaction; :at the transaction's :db/txInstant), read from db's history and
  stored nowhere else. e is an entity id or a lookup ref of a unique attribute; a deleted entity's is still read.
  opts: :attrs, a set of attributes (default all); :pull, a pull pattern applied to a ref attribute's value as of
  its row's transaction (d/as-of), so a replaced form shows the text it had; :v is then the pulled map. An opt
  it does not know is refused. An e that names no entity answers :seon.db/unresolved, a store that keeps no
  history :seon.db/no-history, never []. See docs/storage.md#history."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.db/entity] [:or [:vector :seon.db/history-row] :seon.db/unresolved :seon.db/no-history]]
                  [:=> [:cat :seon.host/db :seon.db/entity :seon.db/history-opts] [:or [:vector :seon.db/history-row] :seon.db/unresolved :seon.db/no-history]]]}
  ([db e] (history db e {}))
  ([db e {:keys [attrs pull]}]
   (or (no-history db #'seon.db/history)
       (let [h (d/history db)
             id (if (int? e)
                  (when (seq (d/datoms h :eavt e)) e)
                  (let [[a v] e] (when (:db/unique (get (d/schema db) a)) (:e (first (d/datoms h :avet a v))))))]
         (if-not id
           (unresolved #'seon.db/history :entity e)
           (let [ds (sort-by (juxt :tx :added) (if attrs (mapcat #(d/datoms h :eavt id %) (sort attrs)) (d/datoms h :eavt id)))
                 at (into {} (map (fn [t] [t (:v (first (d/datoms db :eavt t :db/txInstant)))])) (distinct (map :tx ds)))
                 schema (:schema db)
                 ref? #(= :db.type/ref (get-in schema [% :db/valueType]))]
             (mapv (fn [{:keys [a v tx added]}]
                     {:a a :added? added :t tx :at (at tx)
                      :v (if (and pull (ref? a)) (d/pull (d/as-of db tx) pull v) v)})
                   ds)))))))

(defn at
  "The db at a named point of the store, or the error saying it is none: opts {} is db itself; {:t t}
  db as of t (d/as-of); {:commit cid} that commit (at-commit); {:branch b} branch b's last committed value
  (head: a write on b not yet committed is not seen); {:branch b :t t} b as of t. :commit with :branch or :t,
  and any other opt, is refused. A t that db never had is :seon.db/unresolved, never the nearest point: t ids
  are unique across the store, so a t is a transaction of this branch exactly when its transaction entity
  holds a :db/txInstant here (one seek), and another branch's is none. An unknown commit or branch is
  :seon.db/unresolved; a base keeping no history, with a :t, :seon.db/no-history. conn is any connection to
  the store, db the base of {} and {:t}. See docs/storage.md#history."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db :seon.db/at-opts] [:or :seon.host/db :seon.db/unresolved :seon.db/no-history]]}
  [conn db {:keys [t commit branch]}]
  (cond
    commit (or (at-commit conn commit) (unresolved #'seon.db/at :commit commit))
    (and branch (not (branch? conn branch))) (unresolved #'seon.db/at :branch branch)
    :else (let [base (if branch (head conn branch) db)]
            (if-not t
              base
              (or (no-history base #'seon.db/at)
                  (if (and (pos? t) (seq (d/datoms base :eavt t :db/txInstant))) (d/as-of base t) (unresolved #'seon.db/at :t t)))))))

(defn take-eid
  "A fresh entity id from the store db reads (`ids!`), issued once across every branch and process: what a record names itself by before it is written. Taken and never written, it is a gap. See docs/users.md#what-each-thing-is."
  {:malli/schema [:=> [:cat :seon.host/db] nat-int?]}
  [db] (datahike.ids/-take! (datahike.writing/ids db) :eid))

;; ---- tx-data with explicit ids ----------------------------------------------------
;; The store issues every entity id and transaction id once (ids!), so ids are unique
;; across branches: a branch's new entity keeps its id on main. The fork server's replica
;; of main reads each commit from the store (seon.fork-server, d/commit-as-db); these name
;; a commit's datoms for the program's loader and main's readers.

(defn report->tx
  "The writer's tx-report as explicit-id tx-data (the transaction entity's own datoms left
  out)."
  {:malli/schema [:=> [:cat :seon.db/tx-report] :seon.db/resolved-tx]}
  [rep]
  (vec (for [dt (:tx-data rep) :when (not= (:e dt) (:tx dt))]
         [(if (:added dt) :db/add :db/retract) (:e dt) (:a dt) (:v dt)])))

