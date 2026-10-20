(ns seon.program
  "The program is entities. A namespace and its definitions are facts, and each process runs
  the program its db holds:
  - an agent records what it evaluates as a REPL session leaves it (owner, 2026-10-20:
    \"We should be able to create new namespaces period. Just like a REPL.\"): an `ns` form,
    an `in-ns` or a first def makes its namespace program, unless the namespace is scratch
    (`user`, or declared ^:scratch). The `ns` form is kept as written (:ns/src), and each
    def as the exact text the agent wrote (:var/src) with its var's metadata (:var/meta);
  - root and the fork server load main's program at boot and every change main's commits
    make, namespaces in dependency order, so an agent forked after a merge inherits the
    merged code compiled and loads nothing;
  - an agent loads what reaches its branch from main (merge-main!).
  Source is evaluated natively in its namespace (Jolt compiles it); it is read for analysis
  (dependency order, reach) by seon.reader, never evaluated there."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-] :as contract] [datahike.api :as d] [seon.reader :as reader]))

(def ^:private def-heads '#{def defn defn- defmacro defonce deftest defmulti defrecord deftype defprotocol})

(defn def-form?
  "A top-level form that defines a var: (defn f ...), (deftest t ...), ..."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [form]
  (and (seq? form) (symbol? (first form)) (contains? def-heads (symbol (name (first form))))))

(defn ns-form?
  "An `ns` form: (ns name ...)."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [form]
  (and (seq? form) (= 'ns (first form)) (symbol? (second form))))

(defn in-ns-form?
  "An `in-ns` form: (in-ns 'name)."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [form]
  (and (seq? form) (= 'in-ns (first form))))

(defn scratch-ns?
  "Is namespace nsx scratch (never program): `user`, or declared ^:scratch."
  {:malli/schema [:=> [:cat :seon.host/namespace] :boolean]}
  [nsx]
  (or (= 'user (ns-name nsx)) (boolean (:scratch (meta nsx)))))

(defn edn-value?
  "Is v plain data that EDN prints and reads back equal: nil, booleans, numbers, strings,
  keywords, symbols, and lists, vectors, maps and sets of them."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v]
  (boolean (cond (record? v) false
                 (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)) true
                 (map? v) (every? (fn [[k x]] (and (edn-value? k) (edn-value? x))) v)
                 (coll? v) (every? edn-value? v)
                 :else false)))

(defn pr-edn
  "v printed as EDN that reads back (no print limits, no metadata)."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str v)))

(defn- tid
  "The tempid of namespace nsym's entity within one transaction."
  {:malli/schema [:=> [:cat :symbol] :string]}
  [nsym] (str "ns " nsym))

;; ---- recording: what an evaluation leaves that is program --------------------------

(defn var-kind
  "What a defined var holds, as recorded: :fn (a function, which needs a contract), :test,
  :macro or :value (G1's)."
  {:malli/schema [:=> [:cat :seon.host/var] [:enum :fn :test :macro :value]]}
  [v]
  (let [m (meta v)]
    (cond (:test m) :test (:macro m) :macro (contract/function-var? v) :fn :else :value)))

(def ^:private host-meta
  "Var metadata the runtime derives (where it was read, its name, its arglists) or that
  has its own fact (:doc, the contract): not part of :var/meta."
  [:ns :name :file :line :column :end-line :end-column :arglists :doc :malli/schema :test])

(defn var-meta
  "The metadata a definition declared on var v (^:dynamic, ^:private, ^{:k v} ...), the
  EDN values of it, as a sorted map."
  {:malli/schema [:=> [:cat :seon.host/var] :map]}
  [v]
  (into (sorted-map) (for [[k x] (apply dissoc (meta v) host-meta) :when (and (edn-value? k) (edn-value? x))] [k x])))

(defn record-tx
  "The facts of a def evaluated in a program namespace: its namespace, the exact text the
  agent wrote (src), kind, declared contract (as text), docstring and declared metadata
  (:var/meta, EDN), so main runs what the branch ran. An attribute the redefinition no
  longer has is retracted (db: the branch). G1's proto.program/record-tx."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/var :string] :seon.db/tx-data]}
  [db the-var src]
  (let [m (meta the-var) nsym (ns-name (:ns m)) vsym (symbol (str nsym) (str (:name m)))
        c (contract/contract the-var)
        um (var-meta the-var)
        now (cond-> {:var/name vsym :var/ns (tid nsym) :var/src src :var/kind (var-kind the-var)}
              c (assoc :var/contract (pr-edn c))
              (string? (:doc m)) (assoc :var/doc (:doc m))
              (seq um) (assoc :var/meta (pr-edn um)))
        e (d/q '[:find ?e . :in $ ?v :where [?e :var/name ?v]] db vsym)
        old (when e (d/pull db [:var/contract :var/doc :var/meta] e))]
    (into [{:db/id (tid nsym) :ns/name nsym} now]
          (for [a [:var/contract :var/doc :var/meta] :let [v (get old a)] :when (and (some? v) (not (contains? now a)))]
            [:db/retract e a v]))))

(defn record-ns-tx
  "The facts of an `ns` form evaluated on this branch: its namespace and the form as
  written (src). Its cost is the form's, never the program's."
  {:malli/schema [:=> [:cat :symbol :string] :seon.db/tx-data]}
  [nsym src]
  [{:db/id (tid nsym) :ns/name nsym :ns/src src}])

(defn recording
  "What evaluating form (written as src) left in this REPL that is program, as [what
  tx-data], or nil: an `ns` form's namespace and text; a namespace new to db that `in-ns`
  entered; a def's var. A scratch namespace leaves nothing. value: what the form evaluated
  to. Clojure's `ns` puts the name's metadata on the namespace; Jolt's drops it, so it is
  put here (jolt/ns-meta is the fix at its cause)."
  {:malli/schema [:=> [:cat :seon.host/db :any [:maybe :string] :any] [:maybe [:tuple :symbol :seon.db/tx-data]]]}
  [db form src value]
  (cond
    (and (ns-form? form) src (find-ns (second form)))
    (let [nsym (second form) nsx (find-ns nsym)]
      (when-let [m (not-empty (dissoc (meta nsym) :line :column))] (alter-meta! nsx merge m))
      (when-not (scratch-ns? nsx) [nsym (record-ns-tx nsym src)]))

    (in-ns-form? form)
    (let [nsym (ns-name value)]
      (when-not (or (scratch-ns? value) (d/q '[:find ?e . :in $ ?n :where [?e :ns/name ?n]] db nsym))
        [nsym [{:db/id (tid nsym) :ns/name nsym}]]))

    (and (var? value) (def-form? form) src)
    (when-not (scratch-ns? (:ns (meta value)))
      [(symbol value) (record-tx db value src)])))

;; ---- names: how a namespace's source resolves -----------------------------------------

(defn- expand-libspec
  "One :require/:use spec as libs: a symbol, a vector [lib & opts], or a prefix list
  (prefix lib-or-[lib & opts] ...)."
  {:malli/schema [:=> [:cat :any] [:sequential [:map [:ns :symbol] [:opts :map]]]]}
  [spec]
  (let [lib (fn [s opts] {:ns s :opts (apply hash-map opts)})]
    (cond (symbol? spec) [(lib spec nil)]
          (and (vector? spec) (or (= 1 (count spec)) (keyword? (second spec)))) [(lib (first spec) (rest spec))]
          (sequential? spec) (for [s (rest spec)
                                   :let [[sn & opts] (if (sequential? s) s [s])]]
                               (lib (symbol (str (first spec) "." sn)) opts))
          :else [])))

(defn libspecs
  "The libs an ns form names in :require and :use, as written: {:ns :as :refer} each, :refer
  a vector of symbols or :all (:use refers all unless :only)."
  {:malli/schema [:=> [:cat :any] [:vector [:map [:ns :symbol] [:as {:optional true} :symbol] [:refer {:optional true} :any]]]]}
  [form]
  (vec (for [clause (drop 2 form)
             :when (and (sequential? clause) (#{:require :use} (first clause)))
             spec (rest clause) :when (not (keyword? spec))
             {:keys [ns opts]} (expand-libspec spec)]
         (cond-> {:ns ns}
           (:as opts) (assoc :as (:as opts))
           (= :use (first clause)) (assoc :refer (or (:only opts) :all))
           (:refer opts) (assoc :refer (:refer opts))))))

(defn ns-env
  "How names resolve in namespace nsym: its ns form's aliases and refers, then its alias
  facts (\"[a target]\"). {:own :aliases {a target} :refers {s target} :all [target ...]}."
  {:malli/schema [:=> [:cat :symbol :any [:sequential :string]] :seon.program/env]}
  [nsym ns-form alias-strs]
  (let [libs (when (ns-form? ns-form) (libspecs ns-form))]
    {:own nsym
     :aliases (into (into {} (for [{:keys [ns as]} libs :when as] [as ns]))
                    (for [s alias-strs :let [[a target] (read-string s)]] [a target]))
     :refers (into {} (for [{:keys [ns refer]} libs :when (sequential? refer) s refer] [s ns]))
     :all (vec (for [{:keys [ns refer]} libs :when (= :all refer)] ns))
     :requires (vec (distinct (map :ns libs)))}))

(defn names-in
  "The definitions among names that form's symbols name, resolved in env (an unqualified
  symbol: its own namespace's var, else a refer; a qualified one: its namespace or alias).
  Static: a var reached only through eval, resolve or a macro's expansion is not seen."
  {:malli/schema [:=> [:cat :seon.program/env [:set :qualified-symbol] :any] [:set :qualified-symbol]]}
  [{:keys [own aliases refers all]} names form]
  (into #{} (keep (fn [s]
                    (let [q (if-let [sn (namespace s)]
                              (symbol (str (get aliases (symbol sn) sn)) (name s))
                              (let [mine (symbol (str own) (name s))]
                                (cond (names mine) mine
                                      (refers s) (symbol (str (refers s)) (name s))
                                      :else (some #(let [q (symbol (str %) (name s))] (when (names q) q)) all))))]
                      (when (and q (names q)) q))))
        (filter symbol? (tree-seq coll? seq form))))

(defn- ns-src-of
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:maybe :string]]}
  [db nsym] (d/q '[:find ?s . :in $ ?n :where [?e :ns/name ?n] [?e :ns/src ?s]] db nsym))

(defn env-of
  "Namespace nsym's name environment as db records it (one lookup)."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] :seon.program/env]}
  [db nsym]
  (ns-env nsym (some-> (ns-src-of db nsym) (reader/read-one nsym))
          (d/q '[:find [?a ...] :in $ ?n :where [?e :ns/name ?n] [?e :ns/alias ?a]] db nsym)))

(defn- test-form?
  {:malli/schema [:=> [:cat :any] :boolean]}
  [form] (boolean (and (seq? form) (symbol? (first form)) (= "deftest" (name (first form))))))

;; ---- loading ---------------------------------------------------------------------------

(defn ensure-ns!
  "The namespace, created with clojure.core and clojure.test's assertions referred when it
  is new (a definition seeded on main with no ns form)."
  {:malli/schema [:=> [:cat :symbol] :seon.host/namespace]}
  [ns-sym]
  (or (find-ns ns-sym)
      (let [n (create-ns ns-sym)]
        (binding [*ns* n]
          (refer-clojure)
          (refer 'clojure.test :only '[deftest is are testing]))
        n)))

(defn- err
  {:malli/schema [:=> [:cat :symbol :string :seon.host/throwable] :seon.program/error]}
  [nsym what e] {:ns nsym :message (str what (or (ex-message e) (str (type e))))})

(defn aliases!
  "Give each namespace of nsyms the aliases db records for it (:ns/alias \"[a target]\"),
  requiring the target first, so a definition that uses an alias loads (lane M1, core patch
  04). Answers the error maps of targets that could not be required."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :symbol]] [:vector :seon.program/error]]}
  [db nsyms]
  (vec (for [n (distinct nsyms)
             s (d/q '[:find [?s ...] :in $ ?n :where [?e :ns/name ?n] [?e :ns/alias ?s]] db n)
             :let [[a target] (read-string s)
                   e (try (when-not (find-ns target) (require target))
                          (binding [*ns* (ensure-ns! n)] (alias a target))
                          nil
                          (catch Throwable e (err n (str "alias " s ": ") e)))]
             :when e]
         e)))

(defn load-ns!
  "Declare namespace nsym as db holds it: created (ensure-ns!), its ns form evaluated as
  written (its requires, refers and aliases), then its alias facts. Answers the errors."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:vector :seon.program/error]]}
  [db nsym]
  (let [nsx (ensure-ns! nsym)
        e (when-let [src (ns-src-of db nsym)]
            (try (binding [*ns* nsx] (eval (read-string src))) nil
                 (catch Throwable e (err nsym "ns form: " e))))]
    (into (if e [e] []) (aliases! db [nsym]))))

(defn load-src!
  "Evaluate one var's source in its namespace and arm its contract (seon.contract/arm!):
  answers nil, or an error map (G1's proto.program/load-src!)."
  {:malli/schema [:=> [:cat :symbol :string] [:maybe :seon.program/error]]}
  [ns-sym src]
  (let [v (try (binding [*ns* (ensure-ns! ns-sym)] (eval (read-string src)))
               (catch Throwable e {:ns ns-sym :message (or (ex-message e) (str (type e)))}))]
    (cond
      (and (map? v) (:ns v) (contains? v :message)) v
      (var? v) (let [r (contract/arm! v)]
                 (when (contract/error? r) {:ns ns-sym :message (:seon.error/message r)}))
      :else nil)))

(defn- topo
  "nodes in dependency order: each after the nodes among them it needs (deps), ties and
  cycles broken by name. One visit per node and edge."
  {:malli/schema [:=> [:cat [:sequential :symbol] :seon.host/fn] [:vector :symbol]]}
  [nodes deps]
  (let [in? (set nodes)]
    (:order (reduce (fn visit [st n]
                      (if (or ((:seen st) n) (not (in? n)))
                        st
                        (update (reduce visit (update st :seen conj n) (sort-by str (deps n))) :order conj n)))
                    {:seen #{} :order []}
                    (sort-by str nodes)))))

(defn load-order
  "The order to load namespaces nss and definitions rows ([sym src]) of db in: namespaces in
  dependency order (after every namespace among them that their ns form requires or their
  definitions name), each declared and then its definitions, those in dependency order among
  themselves; tests after every other definition (a test may name its subject fully
  qualified). Answers [[:ns nsym] | [:var sym src] ...]. Cost: one read per row and one
  lookup per namespace, never the rest of the program."
  {:malli/schema [:=> [:cat :seon.host/db [:set :symbol] [:sequential [:tuple :qualified-symbol :string]]]
                  [:vector [:or [:tuple [:= :ns] :symbol] [:tuple [:= :var] :qualified-symbol :string]]]]}
  [db nss rows]
  (let [all-ns (into (set nss) (map (comp symbol namespace first)) rows)
        envs (into {} (for [n all-ns] [n (env-of db n)]))
        names (set (map first rows))
        defs (into {} (for [[v src] rows :let [n (symbol (namespace v))]]
                        [v {:src src :form (reader/read-one src n)}]))
        uses (into {} (for [[v {:keys [form]}] defs] [v (disj (names-in (envs (symbol (namespace v))) names form) v)]))
        named (reduce (fn [m [v us]] (update m (symbol (namespace v)) (fnil into #{}) (map (comp symbol namespace)) us)) {} uses)
        order (topo (vec all-ns) (fn [n] (into (set (:requires (envs n))) (named n))))
        by-ns (group-by (comp symbol namespace) (keys defs))
        vars (fn [n tests?] (for [v (topo (filterv #(= tests? (test-form? (:form (defs %)))) (by-ns n)) #(uses %))]
                              [:var v (:src (defs v))]))]
    (vec (concat (mapcat (fn [n] (concat (when (nss n) [[:ns n]]) (vars n false))) order)
                 (mapcat #(vars % true) order)))))

(defn- load-steps!
  "Carry out load-order's steps; answers the errors."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:vector :seon.program/error]]}
  [db steps]
  (reduce (fn [errs [k x src]]
            (case k
              :ns (into errs (load-ns! db x))
              :var (if-let [e (load-src! (symbol (namespace x)) src)] (conj errs e) errs)))
          [] steps))

(defn load-all!
  "Load db's program: every namespace declared (load-ns!), then every definition, in
  load-order. Answers {:loaded n :errors [...]}."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.program/load-report]}
  [db]
  (let [rows (vec (d/q '[:find ?v ?src :where [?e :var/name ?v] [?e :var/src ?src]] db))
        nss (set (d/q '[:find [?n ...] :where [_ :ns/name ?n]] db))]
    {:loaded (count rows) :errors (load-steps! db (load-order db nss rows))}))

(defn load-changes!
  "Load what resolved tx-data ops change, read against db (after them): unmap every var
  whose :var/name they retract, declare each namespace whose ns form or aliases they assert,
  and load each :var/src they assert, in load-order. Answers the error maps (empty when all
  loaded)."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :seon.db/op]] [:vector :seon.program/error]]}
  [db ops]
  (doseq [[op _ a v] ops
          :when (and (= op :db/retract) (= a :var/name) (symbol? v))
          :let [n (some-> (namespace v) symbol find-ns)]
          :when n]
    (ns-unmap n (symbol (name v))))
  (let [nss (set (for [[op e a _] ops :when (and (= op :db/add) (#{:ns/src :ns/alias} a))] (:ns/name (d/entity db e))))
        rows (vec (for [[op e a src] ops
                        :when (and (= op :db/add) (= a :var/src))
                        :let [v (d/q '[:find ?v . :in $ ?e :where [?e :var/name ?v]] db e)]
                        :when v]
                    [v src]))]
    (load-steps! db (load-order db nss rows))))

(defn load-diff!
  "Make this process run db-to's program where it holds db-from's: each namespace whose
  ns form differs is declared again, and each definition whose recorded content differs is
  loaded (or unmapped when db-to has none), in load-order. An agent resuming a kept branch
  was forked holding main's program. Answers the load errors."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :seon.program/error]]}
  [db-from db-to]
  (let [srcs (fn [db] (into {} (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/src ?s]] db)))
        nsrcs (fn [db] (into {} (d/q '[:find ?n ?s :where [?e :ns/name ?n] [?e :ns/src ?s]] db)))
        from (srcs db-from) to (srcs db-to)
        nfrom (nsrcs db-from) nto (nsrcs db-to)]
    (doseq [[v _] from :when (not (contains? to v))
            :let [n (find-ns (symbol (namespace v)))] :when n]
      (ns-unmap n (symbol (name v))))
    (load-steps! db-to (load-order db-to
                                   (set (for [[n s] nto :when (not= s (get nfrom n))] n))
                                   (vec (for [[v src] to :when (not= src (get from v))] [v src]))))))

;; ---- reach: which tests a change reaches ----------------------------------------------

(defn reaching-tests
  "The program's tests on db whose definition reaches one of syms (fully qualified var
  names): walked backwards once over the definitions each recorded :var/src names, resolved
  as its namespace resolves them (its own vars, its ns form's refers and aliases, its alias
  facts). Static (names-in). Cost: the program's definitions and references, read once."
  {:malli/schema [:=> [:cat :seon.host/db [:set :qualified-symbol]] [:set :qualified-symbol]]}
  [db syms]
  (let [rows (d/q '[:find ?v ?s :where [?e :var/name ?v] [?e :var/src ?s]] db)
        names (into #{} (map first) rows)
        envs (into {} (for [n (distinct (map (comp symbol namespace first) rows))] [n (env-of db n)]))
        forms (into {} (for [[v src] rows] [v (reader/read-one src (symbol (namespace v)))]))
        back (reduce (fn [m [v form]]
                       (reduce #(update %1 %2 (fnil conj #{}) v) m (disj (names-in (envs (symbol (namespace v))) names form) v)))
                     {} forms)
        reached (loop [front (vec (filter names syms)) seen (set (filter names syms))]
                  (if-let [[x & more] (seq front)]
                    (let [ns (remove seen (get back x))] (recur (into (vec more) ns) (into seen ns)))
                    seen))]
    (into #{} (filter #(test-form? (forms %))) reached)))
