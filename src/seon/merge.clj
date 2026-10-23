(ns seon.merge
  "The program merge: git's three-way merge over a branch's PROGRAM, as data. From session
  M1's proto.merge (proto/agents/core-patches/01-proto-merge.patch), over the program as
  the compiler knows it (seon.program).

  A db's program is a map from program keys to content (`program`):
  - `[:form id]` -> a live form: its namespace, exact text, place and what the compiler said
    it defines, declares, references and requires. Its id is its namespace and text, so an
    identical form on two branches is one key, and its place does not make two equal forms
    differ;
  - `[:var sym]`, `[:method multi dispatch]`, `[:impl protocol type]`, `[:ns nsym]` -> a
    definition: the form that last made it ({:form id}), and a var's kind, contract and doc.
    Conflicts are per definition: two branches that redefine one var differently conflict
    there, whatever else their forms carry;
  - `[:schema k]` -> a schema entry of a resource ({:form text :file path}; seon.source
    imports them, my.schema/register! changes them); an entry with no resource (a scratch
    key) is no program;
  - `[:datum e a]` (cardinality one) / `[:datum e a v]` (many) -> a value of a data
    attribute the caller declares (`data-attrs`: a set, or :all for every attribute that
    is neither program, observation nor merge bookkeeping). Entity ids are store-wide
    unique, so a data entity keeps its id across branches.

  `three-way` compares base, ours and theirs: the one derivation behind merging main into
  a branch, merging a branch into main, the done rule's unmerged entries and the
  dashboard. A conflict is kept as an unmerged entry on the branch (`unmerged-tx`), base,
  ours and theirs as data, until the branch's content for its key changes or it is
  resolved (`resolve-tx`). Every function here is pure over db values."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [datahike.api :as d] [clojure.string :as str] [seon.db :as pdb] [seon.program :as program] [seon.edn :as sedn]))

(def ^:private program-ns-attrs #{"form" "var" "ns" "method" "impl" "schema" "schema.file" "db" "db.install" "db.type" "db.cardinality" "db.unique"})

(def carried-platform-attrs
  "The platform attributes (seon.*) a branch carries: the task content an agent may improve
  (my.task/update!), carried onto main at its attempt's end (seon.task/carry-tx). Every other seon.* attribute is a platform record (root's boot and
  quiescence, recorded errors, attempts, observations) written where it happened and never
  carried, so main writing one never puts a branch behind."
  #{:seon.task/plan :seon.task/instructions})

(defn- platform-record? {:malli/schema [:=> [:cat :keyword] :boolean]} [k]
  (let [n (namespace k)]
    (and (some? n) (or (= "seon" n) (str/starts-with? n "seon.")) (not (carried-platform-attrs k)))))

(defn- data-attr-set
  "The data attributes of db that data-attrs (a set, or :all) names."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] [:set :keyword]]}
  [db data-attrs]
  (if (= :all data-attrs)
    (into #{} (for [[k v] (d/schema db)
                    :when (and (keyword? k) (map? v) (:db/valueType v)
                               (not (program-ns-attrs (namespace k))) (not (platform-record? k)))]
                k))
    data-attrs))

(defn- def-entity
  "The entity of definition key k on db, or nil."
  {:malli/schema [:=> [:cat :seon.host/db :any] [:maybe :int]]}
  [db [kind a b]]
  (let [[attr v] (case kind :var [:var/name a] :method [:method/key (program/method-key a b)]
                       :impl [:impl/key (program/impl-key a b)] :ns [:ns/name a])]
    (pdb/eid db attr v)))

(defn- def-content
  "Definition k's content on db, when it points to form row f: the form's id, and its text
  (so a conflict shows each side's code as written)."
  {:malli/schema [:=> [:cat :seon.host/db :any :int :map] :map]}
  [db k e f]
  (let [x (d/entity db e)
        c {:form (:id f) :src (:src f)}]
    (case (first k)
      :var (cond-> (assoc c :kind (:var/kind x))
             (:var/contract x) (assoc :contract (:var/contract x))
             (:var/doc x) (assoc :doc (:var/doc x)))
      :ns (cond-> c (:ns/file x) (assoc :file (:ns/file x)))
      c)))

(defn program
  "The program of db as {key content}: its live forms, the definitions pointing to them,
  and the declared data. Cost: the program's forms and definitions, read once."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] :seon.merge/program]}
  [db data-attrs]
  (let [schema (d/schema db)
        forms (program/live-forms db)
        defs (for [f forms k (:defines f)
                   :let [e (def-entity db k)]
                   :when (and e (some #(= (:e f) (:db/id (get (d/entity db e) %))) [:var/form :method/form :impl/form :ns/form]))]
               [k (def-content db k e f)])
        data (for [a (sort (data-attr-set db data-attrs))
                   dt (d/datoms db :aevt a)]
               (if (= :db.cardinality/many (get-in schema [a :db/cardinality]))
                 [[:datum (:e dt) a (:v dt)] true]
                 [[:datum (:e dt) a] (:v dt)]))
        schemas (for [dt (d/datoms db :aevt :schema/file) :let [x (d/entity db (:e dt))]]
                  [[:schema (:schema/key x)] {:form (:schema/form x) :file (:v dt)}])]
    (into {} (concat (for [f forms] [[:form (:id f)] (dissoc f :e :id)]) defs schemas data))))

(defn- same?
  "Is content a the same as b for key k: a form's place does not make it another form."
  {:malli/schema [:=> [:cat :seon.merge/program-key :any :any] :boolean]}
  [k a b]
  (if (and (= :form (first k)) (map? a) (map? b)) (= (dissoc a :order :lead) (dissoc b :order :lead)) (= a b)))

(defn changes
  "{key [from-content to-content]} for every key whose content differs (nil: absent)."
  {:malli/schema [:=> [:cat :seon.merge/program :seon.merge/program] :seon.merge/changes]}
  [from to]
  (into {} (for [k (into (set (keys from)) (keys to))
                 :let [a (get from k) b (get to k)]
                 :when (not (same? k a b))]
             [k [a b]])))

(defn uncontracted
  "The functions a branch's changes ours (a three-way's :ours) define without a contract,
  sorted; none while contracts are off (seon.contract/on?). The writer's merge gate and
  root's judge both ask this."
  {:malli/schema [:=> [:cat :seon.merge/changes] [:vector :qualified-symbol]]}
  [ours]
  (if contract/on?
    (vec (sort-by str (for [[[k sym] [_ ov]] ours :when (and (= :var k) (= :fn (:kind ov)) (nil? (:contract ov)))] sym)))
    []))

(defn uncontracted-since
  "The functions branch db adds or redefines without a contract since its merge base base:
  uncontracted's answer for the branch's own changes, read from what changed
  (seon.program/divergence's vars and their facts), never the whole program. Measured
  2026-10-23 on Seon's program: divergence ~4 ms, against two program reads ~1 s. None while
  contracts are off (seon.contract/on?)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :qualified-symbol]]}
  [base db]
  (if contract/on?
    (let [{:keys [added changed]} (program/divergence base db)]
      (vec (sort-by str (for [sym (concat added changed)
                              :let [x (some->> (pdb/eid db :var/name sym) (d/entity db))]
                              :when (and (= :fn (:var/kind x)) (nil? (:var/contract x)))]
                          sym))))
    []))

(defn- conflict-kind
  {:malli/schema [:=> [:cat :any :any :any] :seon.merge/conflict-kind]}
  [b o t]
  (cond (nil? b) :both-added (nil? o) :deleted-by-us (nil? t) :deleted-by-them :else :both-modified))

(defn three-way
  "git's three-way merge of program maps: :ours and :theirs (each side's changes since
  base), :apply (keys to set to theirs on ours) and :conflicts (both sides changed a key
  differently). Keys are sorted, so the answer is a value."
  {:malli/schema [:=> [:cat :seon.merge/program :seon.merge/program :seon.merge/program] :seon.merge/result]}
  [base ours theirs]
  (let [oc (changes base ours)
        tc (changes base theirs)
        ks (sort-by sedn/encode (keys tc))]
    {:ours oc
     :theirs tc
     :apply (vec (for [k ks :let [b (get base k) o (get ours k) t (get theirs k)] :when (and (not (same? k o t)) (same? k o b))] k))
     :conflicts (vec (for [k ks
                           :let [b (get base k) o (get ours k) t (get theirs k)]
                           :when (and (not (same? k o t)) (not (same? k o b)))]
                       (cond-> {:key k :kind (conflict-kind b o t)}
                         b (assoc :base b) o (assoc :ours o) t (assoc :theirs t))))}))

;; ---- what program code does not do (owner, 2026-10-21) ------------------------------

(defn mutations
  "What the forms a branch adds (ours: its changes since base) do that program code does
  not: call clojure.core/intern, alter-var-root, eval or load-string, or define inside a
  function body; from the compiler's resolved references, never text. One finding per
  form and call, naming the definitions the form makes. A namespace imported from the
  platform's source (it has an :ns/file on db) is not judged."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/changes] [:vector [:map [:form :string] [:ns :symbol] [:defines [:vector :any]]
                                                                     [:call {:optional true} :qualified-symbol]
                                                                     [:inline-def {:optional true} :qualified-symbol]]]]}
  [db ours]
  (vec (for [[[k id] [_ f]] (sort-by (comp sedn/encode key) ours)
             :when (and (= :form k) f (not (:ns/file (d/entity db [:ns/name (:ns f)]))))
             finding (concat (for [c (sort-by str (:banned f))] {:call c})
                             (for [v (sort-by str (:inline-defs f))] {:inline-def v}))]
         (merge {:form id :ns (:ns f) :defines (vec (sort-by sedn/encode (:defines f)))} finding))))

;; ---- tx-data: setting keys on a db ---------------------------------------------------


(defn- def-ref
  "The entity map naming definition k on db: its entity when db has it, else an upsert by
  its identity with its tempid (Datahike allocates a tempid first seen as a ref, in
  :form/defines, before the map that upserts it, and a form defining two existing
  definitions, a protocol or a record, then conflicts)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/def-key] :map]}
  [db [kind a b :as k]]
  (cond-> (case kind
            :var {:db/id (str "var " a) :var/name a :var/ns (program/ns-tid (symbol (namespace a)))}
            :method (let [k (program/method-key a b)] {:db/id (str "method " k) :method/key k :method/multi a :method/dispatch b})
            :impl (let [k (program/impl-key a b)] {:db/id (str "impl " k) :impl/key k :impl/protocol a :impl/type b})
            :ns {:db/id (program/ns-tid a) :ns/name a})
    (def-entity db k) (assoc :db/id (def-entity db k))))

(def ^:private pointer {:var :var/form :method :method/form :impl :impl/form :ns :ns/form})

(defn- set-form-tx
  {:malli/schema [:=> [:cat :seon.host/db :string [:maybe :map]] :seon.db/tx-data]}
  [db id content]
  (let [e (pdb/eid db :form/id id)]
    (if (nil? content)
      (if e [[:db/retractEntity e]] [])
      (let [{:keys [ns src order lead defines declares refs requires banned inline-defs timeouts catches]} content]
        (into [{:db/id (program/ns-tid ns) :ns/name ns}
               (cond-> {:db/id (program/form-tid id) :form/id id :form/src src :form/ns (program/ns-tid ns) :form/order order}
                 lead (assoc :form/lead lead)
                 (seq defines) (assoc :form/defines (mapv #(:db/id (def-ref db %)) defines))
                 (seq declares) (assoc :form/declares (mapv #(:db/id (def-ref db [:var %])) declares))
                 (seq refs) (assoc :form/refs (vec refs))
                 (seq requires) (assoc :form/requires (vec requires))
                 (seq banned) (assoc :form/banned (vec banned))
                 (seq inline-defs) (assoc :form/inline-defs (vec inline-defs))
                 (seq timeouts) (assoc :form/timeouts (vec timeouts))
                 (seq catches) (assoc :form/catches (vec catches)))]
              (concat (map #(def-ref db %) defines)
                      (for [s declares] (def-ref db [:var s]))))))))

(defn- set-def-tx
  {:malli/schema [:=> [:cat :seon.host/db :any [:maybe :map]] :seon.db/tx-data]}
  [db k content]
  (let [e (def-entity db k)
        x (when e (d/entity db e))
        attrs (case (first k) :var [:var/form :var/kind :var/contract :var/doc] :ns [:ns/form :ns/file] [(pointer (first k))])
        now (when content
              (cond-> {(pointer (first k)) (program/form-tid (:form content))}
                (:kind content) (assoc :var/kind (:kind content))
                (:contract content) (assoc :var/contract (:contract content))
                (:doc content) (assoc :var/doc (:doc content))
                (:file content) (assoc :ns/file (:file content))))]
    (into (if content [(merge (def-ref db k) now) {:db/id (program/form-tid (:form content)) :form/id (:form content)}] [])
          (for [a attrs :let [old (get x a)] :when (and (some? old) (not (contains? now a)))]
            [:db/retract e a (if (#{:var/form :ns/form :method/form :impl/form} a) (:db/id old) old)]))))

(defn- set-datum-tx
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key :any] :seon.db/tx-data]}
  [db [_ e a v :as k] content]
  (if (= 4 (count k))
    (if content [[:db/add e a v]] (if (seq (d/datoms db :eavt e a v)) [[:db/retract e a v]] []))
    (let [old (:v (first (d/datoms db :eavt e a)))]
      (cond (some? content) [[:db/add e a content]]
            (some? old) [[:db/retract e a old]]
            :else []))))

(defn- set-schema-tx
  "tx-data making schema entry k hold content on db: upserted by its key, no longer the
  binary's (:schema/source retracted), or retracted."
  {:malli/schema [:=> [:cat :seon.host/db :qualified-keyword [:maybe :seon.merge/schema-entry]] :seon.db/tx-data]}
  [db k content]
  (let [e (pdb/eid db :schema/key k)]
    (cond (nil? content) (if e [[:db/retractEntity e]] [])
          :else (cond-> [{:schema/key k :schema/form (:form content) :schema/file (:file content)}]
                  (and e (:schema/source (d/entity db e))) (conj [:db/retract e :schema/source true])))))

(defn apply-tx
  "tx-data making each key of m ({key content}, nil content: absent) hold its content on
  db: forms upserted by id (or retracted), definitions pointed at their forms by identity
  (entity ids never matter), schema entries by key with the Datahike declarations their
  population needs, data by entity id."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :seon.merge/program-key :any]] :seon.db/tx-data]}
  [db m]
  (let [tx (vec (distinct
                  (mapcat (fn [[k content]]
                            (case (first k)
                              :form (set-form-tx db (second k) content)
                              (:var :method :impl :ns) (set-def-tx db k content)
                              :schema (set-schema-tx db (second k) content)
                              :datum (set-datum-tx db k content)))
                          (sort-by (comp sedn/encode key) m))))]
    (if (some #(= :schema (ffirst %)) m)
      (let [db' (d/db-with db tx)] (into tx (program/schema-declarations db (program/schema-registry (program/schema-changes db')))))
      tx)))

;; ---- unmerged entries (git's index stages 1, 2, 3), on the branch ------------------------

(defn- entry-eids
  "The branch's merge entries: entities with a key and the main t they were merged at
  (an attempt's conflict evidence on main shares the :seon.merge/* members, not :main-t)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :int]]}
  [db] (vec (d/q '[:find [?e ...] :where [?e :seon.merge/key] [?e :seon.merge/main-t]] db)))

(defn conflict-value
  "The conflict the :seon.merge/* members of m state, m a pulled entry (the inverse of
  `conflict-tx`): one decoder for a branch's entries and an attempt's evidence."
  {:malli/schema [:=> [:cat :seon.merge/entry-members] :seon.merge/conflict]}
  [m]
  (cond-> {:key (:seon.merge/key m) :kind (:seon.merge/kind m)}
    (:seon.merge/base m) (assoc :base (:seon.merge/base m))
    (:seon.merge/ours m) (assoc :ours (:seon.merge/ours m))
    (:seon.merge/theirs m) (assoc :theirs (:seon.merge/theirs m))))

(defn entries
  "Every merge entry on db, as conflict values with :main-t and :resolved?."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.merge/entry-value]]}
  [db]
  (vec (sort-by (comp sedn/encode :key)
                (for [e (entry-eids db)
                      :let [m (d/pull db '[*] e)]]
                  (assoc (conflict-value m) :main-t (:seon.merge/main-t m) :resolved? (boolean (:seon.merge/resolved? m)))))))

(defn conflict-tx
  "A conflict as the members of one merge entity (no :db/id): for the branch's entries
  and an attempt's evidence alike."
  {:malli/schema [:=> [:cat :seon.merge/conflict] :map]}
  [{:keys [key kind base ours theirs]}]
  (cond-> {:seon.merge/key key :seon.merge/kind kind}
    base (assoc :seon.merge/base base)
    ours (assoc :seon.merge/ours ours)
    theirs (assoc :seon.merge/theirs theirs)))

(defn unmerged-tx
  "The branch's unmerged entries for conflicts, against main at t main-t: an earlier entry
  for the same key is replaced."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.merge/conflict] :int] :seon.db/tx-data]}
  [db conflicts main-t]
  (let [ks (set (map :key conflicts))]
    (into (vec (for [e (entry-eids db)
                     :when (ks (:seon.merge/key (d/entity db e)))]
                 [:db/retractEntity e]))
          (for [c conflicts] (assoc (conflict-tx c) :seon.merge/main-t main-t :seon.merge/resolved? false)))))

(defn unmerged
  "The entries of db still unmerged, given db's program prog: not resolved, and the
  branch's content for the key still equal to ours (a re-defn resolves by itself). Without
  prog, the program is read only when an entry is still open (a branch with none, the usual
  case, reads its entries alone: the whole program costs ~0.5 s on Seon's own)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:vector :seon.merge/entry-value]]
                  [:=> [:cat :seon.host/db :seon.merge/program] [:vector :seon.merge/entry-value]]]}
  ([db] (let [open (filterv (complement :resolved?) (entries db))
              prog (delay (program db #{}))]
          (filterv #(same? (:key %) (:ours %) (get @prog (:key %))) open)))
  ([db prog]
   (filterv #(and (not (:resolved? %)) (same? (:key %) (:ours %) (get prog (:key %)))) (entries db))))

(defn resolve-tx
  "Mark the entry for key resolved (git add): the branch keeps what it holds now."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key] :seon.db/tx-data]}
  [db k]
  (vec (for [e (entry-eids db)
             :when (= k (:seon.merge/key (d/entity db e)))]
         [:db/add e :seon.merge/resolved? true])))