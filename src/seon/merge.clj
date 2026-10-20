(ns seon.merge
  "The program merge: git's three-way merge over a branch's PROGRAM, as data. From lane
  M1's proto.merge (proto/agents/core-patches/01-proto-merge.patch).

  A db's program is a map from program keys to content (`program`):
  - `[:var sym]` -> {:src :kind :contract :doc} of a recorded definition. Its identity is
    its source text (the canonical print seon.program/record-tx stores), so identical
    definitions on two branches are equal whatever their entity ids;
  - `[:alias ns a]` -> the namespace an alias of program namespace ns names (`:ns/alias`);
  - `[:datum e a]` (cardinality one) / `[:datum e a v]` (many) -> a value of a data
    attribute the caller declares (`data-attrs`: a set, or :all for every attribute that
    is neither program, observation nor merge bookkeeping). Entity ids are store-wide
    unique, so a data entity keeps its id across branches.
  A flagged definition (a plain def whose value is no EDN: `:var/flagged`) is an unstaged
  local change: it maps to its base entry, so it never reaches main and never conflicts.

  `three-way` compares base, ours and theirs: the one derivation behind merging main into
  a branch, merging a branch into main, the done rule's unmerged entries and the
  dashboard. A conflict is kept as an unmerged entry on the branch (`unmerged-tx`), base,
  ours and theirs as data, until the branch's content for its key changes or it is
  resolved (`resolve-tx`). Every function here is pure over db values."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [clojure.edn :as edn] [seon.db :as pdb]))

(def var-attrs
  "The attributes of a recorded definition that are its content."
  [:var/src :var/kind :var/contract :var/doc])

(defn- pr-edn
  "v printed as EDN that reads back (no print limits, no metadata)."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str v)))

(def ^:private program-ns-attrs #{"var" "ns" "seon.merge" "db" "db.install" "db.type" "db.cardinality" "db.unique"})

(defn- data-attr-set
  "The data attributes of db that data-attrs (a set, or :all) names."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] [:set :keyword]]}
  [db data-attrs]
  (if (= :all data-attrs)
    (into #{} (for [[k v] (d/schema db)
                    :when (and (keyword? k) (map? v) (:db/valueType v)
                               (not (program-ns-attrs (namespace k))) (not (pdb/observation-attrs k)))]
                k))
    data-attrs))

(defn program
  "The program of db as {key content}: definitions, aliases, and the declared data. One
  :aevt scan per attribute, joined by entity (cost: the program's datoms, read once)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/data-attrs] :seon.merge/program]}
  [db data-attrs]
  (let [schema (d/schema db)
        col (fn [a] (persistent! (reduce (fn [m dt] (assoc! m (:e dt) (:v dt))) (transient {}) (d/datoms db :aevt a))))
        cols (into {} (for [a (conj var-attrs :var/flagged)] [a (col a)]))
        vars (for [dt (d/datoms db :aevt :var/name)
                   :let [e (:e dt)]]
               [[:var (:v dt)]
                (cond-> (into {} (for [a var-attrs :let [v (get-in cols [a e])] :when (some? v)] [(keyword (name a)) v]))
                  (get-in cols [:var/flagged e]) (assoc :flagged true))])
        ns-names (col :ns/name)
        aliases (for [dt (d/datoms db :aevt :ns/alias)
                      :let [[a target] (edn/read-string (:v dt))]]
                  [[:alias (ns-names (:e dt)) a] target])
        data (for [a (sort (data-attr-set db data-attrs))
                   dt (d/datoms db :aevt a)]
               (if (= :db.cardinality/many (get-in schema [a :db/cardinality]))
                 [[:datum (:e dt) a (:v dt)] true]
                 [[:datum (:e dt) a] (:v dt)]))]
    (into {} (concat vars aliases data))))

(defn changes
  "{key [from-content to-content]} for every key whose content differs (nil: absent)."
  {:malli/schema [:=> [:cat :seon.merge/program :seon.merge/program] :seon.merge/changes]}
  [from to]
  (into {} (for [k (into (set (keys from)) (keys to))
                 :let [a (get from k) b (get to k)]
                 :when (not= a b)]
             [k [a b]])))

(defn- unflag
  "ours with each flagged entry replaced by base's (absent when base has none)."
  {:malli/schema [:=> [:cat :seon.merge/program :seon.merge/program] :seon.merge/program]}
  [base ours]
  (reduce-kv (fn [m k v] (if (:flagged v) (if-let [b (get base k)] (assoc m k b) (dissoc m k)) m)) ours ours))

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
  (let [ours (unflag base ours)
        theirs (unflag base theirs)
        oc (changes base ours)
        tc (changes base theirs)
        ks (sort-by pr-edn (keys tc))]
    {:ours oc
     :theirs tc
     :apply (vec (for [k ks :let [b (get base k) o (get ours k) t (get theirs k)] :when (and (not= o t) (= o b))] k))
     :conflicts (vec (for [k ks
                           :let [b (get base k) o (get ours k) t (get theirs k)]
                           :when (and (not= o t) (not= o b))]
                       (cond-> {:key k :kind (conflict-kind b o t)}
                         b (assoc :base b) o (assoc :ours o) t (assoc :theirs t))))}))

;; ---- tx-data: setting keys on a db ---------------------------------------------------

(defn- set-var-tx
  {:malli/schema [:=> [:cat :seon.host/db :qualified-symbol [:maybe :seon.merge/entry]] :seon.db/tx-data]}
  [db sym entry]
  (let [e (d/q '[:find ?e . :in $ ?v :where [?e :var/name ?v]] db sym)
        ent (when e (d/entity db e))
        nsym (symbol (namespace sym))
        tid (str "ns " nsym)]
    (if (nil? entry)
      (if e [[:db/retractEntity e]] [])
      (into [{:db/id tid :ns/name nsym}
             (into {:var/name sym :var/ns tid}
                   (for [a var-attrs :let [v (get entry (keyword (name a)))] :when (some? v)] [a v]))]
            (concat (for [a (conj var-attrs :var/flagged)
                          :let [old (get ent a)]
                          :when (and (some? old) (nil? (get entry (keyword (name a)))))]
                      [:db/retract e a old]))))))

(defn- set-alias-tx
  {:malli/schema [:=> [:cat :seon.host/db :symbol :symbol [:maybe :symbol]] :seon.db/tx-data]}
  [db nsym a target]
  (let [n (d/q '[:find ?e . :in $ ?n :where [?e :ns/name ?n]] db nsym)
        old (when n (some #(let [[a' t] (edn/read-string %)] (when (= a a') [% t])) (:ns/alias (d/entity db n))))
        tid (str "ns " nsym)]
    (cond-> []
      (and old (not= target (second old))) (conj [:db/retract n :ns/alias (first old)])
      (and target (not= target (second old))) (into [{:db/id tid :ns/name nsym} [:db/add tid :ns/alias (pr-edn [a target])]]))))

(defn- set-datum-tx
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key :any] :seon.db/tx-data]}
  [db [_ e a v :as k] content]
  (if (= 4 (count k))
    (if content [[:db/add e a v]] (if (seq (d/datoms db :eavt e a v)) [[:db/retract e a v]] []))
    (let [old (:v (first (d/datoms db :eavt e a)))]
      (cond (some? content) [[:db/add e a content]]
            (some? old) [[:db/retract e a old]]
            :else []))))

(defn apply-tx
  "tx-data making each key of m ({key content}, nil content: absent) hold its content on
  db: definitions upserted by :var/name (entity ids never matter), aliases on their
  namespace, data by entity id."
  {:malli/schema [:=> [:cat :seon.host/db [:map-of :seon.merge/program-key :any]] :seon.db/tx-data]}
  [db m]
  (vec (distinct
         (mapcat (fn [[k content]]
                   (case (first k)
                     :var (set-var-tx db (second k) content)
                     :alias (set-alias-tx db (second k) (nth k 2) content)
                     :datum (set-datum-tx db k content)))
                 (sort-by (comp pr-edn key) m)))))

;; ---- unmerged entries (git's index stages 1, 2, 3), on the branch ------------------------

(defn- entry-eids
  "The branch's merge entries: entities with a key and the main t they were merged at
  (an attempt's conflict evidence on main shares the :seon.merge/* members, not :main-t)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :int]]}
  [db] (vec (d/q '[:find [?e ...] :where [?e :seon.merge/key] [?e :seon.merge/main-t]] db)))

(defn entries
  "Every merge entry on db, as conflict values with :main-t and :resolved?."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :seon.merge/entry-value]]}
  [db]
  (vec (sort-by (comp pr-edn :key)
                (for [e (entry-eids db)
                      :let [m (d/entity db e)]]
                  (cond-> {:key (edn/read-string (:seon.merge/key m)) :kind (:seon.merge/kind m)
                           :main-t (:seon.merge/main-t m) :resolved? (boolean (:seon.merge/resolved? m))}
                    (:seon.merge/base m) (assoc :base (edn/read-string (:seon.merge/base m)))
                    (:seon.merge/ours m) (assoc :ours (edn/read-string (:seon.merge/ours m)))
                    (:seon.merge/theirs m) (assoc :theirs (edn/read-string (:seon.merge/theirs m))))))))

(defn conflict-tx
  "A conflict as the members of one merge entity (no :db/id): for the branch's entries
  and an attempt's evidence alike."
  {:malli/schema [:=> [:cat :seon.merge/conflict] :map]}
  [{:keys [key kind base ours theirs]}]
  (cond-> {:seon.merge/key (pr-edn key) :seon.merge/kind kind}
    base (assoc :seon.merge/base (pr-edn base))
    ours (assoc :seon.merge/ours (pr-edn ours))
    theirs (assoc :seon.merge/theirs (pr-edn theirs))))

(defn unmerged-tx
  "The branch's unmerged entries for conflicts, against main at t main-t: an earlier entry
  for the same key is replaced."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :seon.merge/conflict] :int] :seon.db/tx-data]}
  [db conflicts main-t]
  (let [ks (set (map (comp pr-edn :key) conflicts))]
    (into (vec (for [e (entry-eids db)
                     :when (ks (:seon.merge/key (d/entity db e)))]
                 [:db/retractEntity e]))
          (for [c conflicts] (assoc (conflict-tx c) :seon.merge/main-t main-t :seon.merge/resolved? false)))))

(defn unmerged
  "The entries of db still unmerged, given db's program prog: not resolved, and the
  branch's content for the key still equal to ours (a re-defn resolves by itself)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program] [:vector :seon.merge/entry-value]]}
  [db prog]
  (filterv #(and (not (:resolved? %)) (= (:ours %) (get (unflag {} prog) (:key %)))) (entries db)))

(defn resolve-tx
  "Mark the entry for key resolved (git add): the branch keeps what it holds now."
  {:malli/schema [:=> [:cat :seon.host/db :seon.merge/program-key] :seon.db/tx-data]}
  [db k]
  (vec (for [e (entry-eids db)
             :when (= (pr-edn k) (:seon.merge/key (d/entity db e)))]
         [:db/add e :seon.merge/resolved? true])))