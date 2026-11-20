(ns seon.health.contracts
  "Contract & reference integrity gates for System Health — broken docstring/qualified-symbol refs, impure generatable fns, and newly added/edited public `defn`s lacking a `:malli/schema` (hard merge gate)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.health.efficiency :as eff]
            [seon.store :as store]
            [seon.health.static :as static]))

(defn broken-symbol-refs
  "Referential integrity gate: flags any qualified symbol referenced by `:seon.schema/render`,
   `:seon.render/hiccup` inside `:seon.schema/form`, `:seon.agent/render-fn`, or `:seon.agent/tail-renders`
   that does not exist in `:seon.code.var/name` (catching renderer typos or deleted fns at merge time)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db (contains? (:schema db) :seon.code.var/name))
    []
    (let [br (get-in db [:config :branch] :main)
          live-vars (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))]
      (if (empty? live-vars)
        []
        (let [errs (volatile! [])
              check! (fn [src-kind owner sym]
                       (when (and (qualified-symbol? sym)
                                  (not (contains? live-vars sym))
                                  (nil? (try (resolve sym) (catch Throwable _ nil))))
                         (vswap! errs conj
                                 {:class :broken-symbol-ref
                                  :severity :error
                                  :kind :static
                                  :ns (symbol (namespace sym))
                                  :var sym
                                  :subject (str src-kind " " owner " -> " sym)
                                  :branch br
                                  :message (str "Broken symbol reference in " src-kind " on " owner
                                                ": var " sym " does not exist. Define it or update the reference.")})))]
          (when (contains? (:schema db) :seon.schema/render)
            (doseq [dt (d/datoms db :aevt :seon.schema/render)]
              (let [k (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt)))]
                (check! :seon.schema/render k (:v dt)))))
          (when (contains? (:schema db) :seon.schema/form)
            (doseq [dt (d/datoms db :aevt :seon.schema/form)]
              (let [f (:v dt)
                    r (when (and (vector? f) (map? (second f))) (:seon.render/hiccup (second f)))]
                (when r
                  (let [k (:seon.schema/key (d/pull db '[:seon.schema/key] (:e dt)))]
                    (check! :seon.render/hiccup k r))))))
          (when (contains? (:schema db) :seon.agent/render-fn)
            (doseq [dt (d/datoms db :aevt :seon.agent/render-fn)]
              (let [a (:seon.agent/id (d/pull db '[:seon.agent/id] (:e dt)))]
                (check! :seon.agent/render-fn a (:v dt)))))
          (when (contains? (:schema db) :seon.agent/tail-renders)
            (doseq [dt (d/datoms db :aevt :seon.agent/tail-renders)]
              (let [a (:seon.agent/id (d/pull db '[:seon.agent/id] (:e dt)))]
                (doseq [sym (:v dt)]
                  (check! :seon.agent/tail-renders a sym)))))
          (vec (sort-by :subject (distinct @errs))))))))

(defn broken-doc-refs
  "Detects backticked qualified symbol references (e.g. `` `seon.health/report` ``) inside var or namespace
  docstrings that do not exist in `:seon.code.var/name` on `db`, preventing docstring rot."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db
               (contains? (:schema db) :seon.code.var/name)
               (contains? (:schema db) :seon.code.var/doc))
    []
    (let [br (get-in db [:config :branch] :main)
          all-vars (into #{} (map :v) (d/datoms db :aevt :seon.code.var/name))
          pat #"`(seon\.[a-z0-9._-]+/[a-zA-Z0-9*+!_?<>=.-]+)`"
          var-probs
          (for [dt (d/datoms db :aevt :seon.code.var/doc)
                :let [v (d/pull db '[:seon.code.var/name :seon.code.var/ns :seon.code.var/doc] (:e dt))
                      vsym (:seon.code.var/name v)
                      nsym (:seon.code.var/ns v)
                      doc (str (:seon.code.var/doc v))]
                :when (and vsym nsym (not (str/ends-with? (name nsym) "-test")))
                [_ sym-s] (re-seq pat doc)
                :let [sym (try (symbol sym-s) (catch Throwable _ nil))]
                :when (and sym (not (contains? all-vars sym)))]
            {:class :broken-doc-ref
             :severity :error
             :kind :static
             :ns nsym
             :var vsym
             :subject (str vsym "->" sym)
             :branch br
             :message (str "Docstring of " vsym " references missing var `" sym "`. Update the docstring or restore the var.")})
          ns-probs
          (if-not (contains? (:schema db) :seon.code.ns/doc)
            []
            (for [dt (d/datoms db :aevt :seon.code.ns/doc)
                  :let [n (d/pull db '[:seon.code.ns/name :seon.code.ns/doc] (:e dt))
                        nsym (:seon.code.ns/name n)
                        doc (str (:seon.code.ns/doc n))]
                  :when (and nsym (not (str/ends-with? (name nsym) "-test")))
                  [_ sym-s] (re-seq pat doc)
                  :let [sym (try (symbol sym-s) (catch Throwable _ nil))]
                  :when (and sym (not (contains? all-vars sym)))]
              {:class :broken-doc-ref
               :severity :error
               :kind :static
               :ns nsym
               :subject (str nsym "#ns->" sym)
               :branch br
               :message (str "Namespace docstring of " nsym " references missing var `" sym "`. Update the docstring.")}))]
      (vec (sort-by :subject (concat var-probs ns-probs))))))

(defn impure-generatable-fns
  "Detects non-edge functions carrying a generatable `[:=> ...]` `:malli/schema` whose static/IR
   references call a `^:seon.flow/edge` side-effecting var (which would fire 100x during `stress-var`)."
  {:malli/schema [:=> [:cat :seon.host/db] [:vector :map]]}
  [db]
  (if-not (and db
               (contains? (:schema db) :seon.code.var/schema)
               (contains? (:schema db) :seon.code.var/flow-edge?)
               (contains? (:schema db) :seon.code.var/refs))
    []
    (let [br (get-in db [:config :branch] :main)
          edge-vars (into #{}
                          (keep (fn [dt]
                                  (when (:v dt)
                                    (:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e dt))))))
                          (d/datoms db :aevt :seon.code.var/flow-edge?))]
      (if (empty? edge-vars)
        []
        (->> (d/datoms db :aevt :seon.code.var/schema)
             (keep (fn [dt]
                     (let [v (d/pull db '[:seon.code.var/name :seon.code.var/ns :seon.code.var/schema
                                          :seon.code.var/refs :seon.code.var/flow-edge?] (:e dt))
                           vsym (:seon.code.var/name v)
                           nsym (:seon.code.var/ns v)
                           sch (:seon.code.var/schema v)]
                       (when (and vsym nsym
                                  (not (str/ends-with? (name nsym) "-test"))
                                  (not (:seon.code.var/flow-edge? v))
                                  (schema/generatable-function-schema? sch))
                         (let [bad-refs (vec (sort (filter #(contains? edge-vars %) (:seon.code.var/refs v))))]
                           (when (seq bad-refs)
                             {:class :impure-generatable-fn
                              :severity :error
                              :kind :static
                              :ns nsym
                              :var vsym
                              :subject (str vsym)
                              :branch br
                              :message (str "Generatable pure schema on " vsym " calls ^:seon.flow/edge var(s) "
                                            (str/join ", " bad-refs)
                                            ". Mark ^:seon.flow/edge, use :seon.host/* args, or keep pure logic separate from IO.")}))))))
             (sort-by :subject)
             vec)))))

(defn missing-contract-violations
  "Hard merge gate: flags any newly added or edited non-test public `defn` on `theirs-db`
   (relative to `ours-db`) that lacks a `:malli/schema` contract (skipping thin `[& args] (apply ...)`
   forwarding stubs). Returns actionable `:error` violations with copy-pasteable `user/set-meta!` hints."
  {:malli/schema [:function
                  [:=> [:cat :any :any] [:vector :map]]
                  [:=> [:cat :any :any [:maybe [:sequential :symbol]]] [:vector :map]]]}
  ([ours-db theirs-db] (missing-contract-violations ours-db theirs-db nil))
  ([ours-db theirs-db touched-nss]
   (if-not (and theirs-db
                (contains? (:schema theirs-db) :seon.code.var/name)
                (contains? (:schema theirs-db) :seon.code.form/key))
     []
     (let [br (get-in theirs-db [:config :branch] :main)
         ;; Fast O(|changed-forms|) filter: find only form keys whose hash differs from ours-db!
         changed-fkeys
         (if (seq touched-nss)
           (into #{}
                 (for [n (distinct touched-nss)
                       :when (and n (not (str/ends-with? (name n) "-test")))
                       dt (d/datoms theirs-db :avet :seon.code.form/ns n)
                       :let [e (:e dt)
                             fk (:v (first (d/datoms theirs-db :eavt e :seon.code.form/key)))
                             th (:v (first (d/datoms theirs-db :eavt e :seon.code.form/hash)))
                             oh (when (and ours-db fk (contains? (:schema ours-db) :seon.code.form/key))
                                  (when-let [oe (:e (first (d/datoms ours-db :avet :seon.code.form/key fk)))]
                                    (:v (first (d/datoms ours-db :eavt oe :seon.code.form/hash)))))]
                       :when (and fk th (not= th oh))]
                   fk))
           (let [fmap (fn [db]
                        (if-not (and db (contains? (:schema db) :seon.code.form/key))
                          {}
                          (let [ks (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
                            (reduce (fn [a dt]
                                      (if-let [k (get ks (:e dt))] (assoc a k (:v dt)) a))
                                    {}
                                    (d/datoms db :aevt :seon.code.form/hash)))))
                 ohs (fmap ours-db)
                 ths (fmap theirs-db)]
             (into #{}
                   (keep (fn [[fk th]] (when (not= th (get ohs fk)) fk)))
                   ths)))]
     (if (empty? changed-fkeys)
       []
       (let [proj '[:seon.code.var/name :seon.code.var/ns :seon.code.var/form
                    :seon.code.var/private? :seon.code.var/macro? :seon.code.var/test?
                    :seon.code.var/arglists :seon.code.var/schema]]
         (->> changed-fkeys
              (mapcat #(map :e (d/datoms theirs-db :avet :seon.code.var/form %)))
              (keep (fn [e]
                      (let [v (d/pull theirs-db proj e)
                            vsym (:seon.code.var/name v)
                            nsym (:seon.code.var/ns v)
                            fk (:seon.code.var/form v)]
                        (when (and vsym nsym fk
                                   (not (str/ends-with? (name nsym) "-test"))
                                   (not (:seon.code.var/test? v))
                                   (not (:seon.code.var/private? v))
                                   (not (:seon.code.var/macro? v))
                                   (seq (:seon.code.var/arglists v))
                                   (nil? (:seon.code.var/schema v)))
                          (let [fe (:e (first (d/datoms theirs-db :avet :seon.code.form/key fk)))
                                src (str (when fe (:v (first (d/datoms theirs-db :eavt fe :seon.code.form/src)))))
                                delegate-stub? (boolean (re-find #"\[&\s+args\]\s*\(apply\s+" src))]
                            (when-not delegate-stub?
                              {:class :missing-contract
                               :severity :error
                               :kind :static
                               :ns nsym
                               :var vsym
                               :subject (str vsym)
                               :branch br
                               :message (str "Public defn " vsym " was added/edited without a :malli/schema contract. "
                                             "Add one via (user/set-meta! '" vsym " {:malli/schema [:=> [:cat ...] :any]}) — "
                                             "for host values use :seon.host/db, :seon.host/conn, :seon.host/chan, or :any; "
                                             "for pure fns include :cost/ticks & :cost/bytes for seed=42 generative proofs.")}))))))
              (sort-by :subject)
              vec)))))))
