(ns seon.code.reach
  "Incremental O(|Delta|) Merkle reach graph, reverse caller BFS, and doc-link indexing."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [datahike.api :as d]
            [edamame.core :as eda]
            [hasch.core :as hasch]
            [jolt.analyzer]
            [jolt.host]
            [jolt.ir :as ir]
            [jolt.scheme :as scm]
            [seon.code.files :as files]
            [seon.schema :as schema]
            [seon.store :as store]))

(def boot-root-keys
  #{"seon.main/-main"
    "seon.main/start-flow-instance!"
    "seon.fork/start-fork-parent!"
    "seon.fork/run-fork-parent-loop!"
    "seon.store/open!"
    "seon.schema.datahike/declarations"
    "seon.switchover/run-switch-over!"})

(defn boot-file-path? [^String path]
  (or (= path "deps.edn")
      (= path "bin/build")
      (= path "bin/env")
      (str/starts-with? path "native/")))

(defn clean-edn-for-hash [x]
  (cond
    (or (seq? x) (list? x) (vector? x)) (mapv clean-edn-for-hash x)
    (set? x) (vec (sort-by pr-str (map clean-edn-for-hash x)))
    (map? x) (vec (sort-by (comp pr-str first)
                           (map (fn [[k v]] [(clean-edn-for-hash k) (clean-edn-for-hash v)]) x)))
    (instance? java.util.regex.Pattern x) [:regex (str x)]
    :else x))

(defn sort-forms-topologically [forms]
  (let [by-ns (group-by :seon.code.form/ns forms)
        ns-set (set (keys by-ns))
        ns-deps (into {}
                      (map (fn [[nsym fs]]
                             [nsym (into #{}
                                         (keep (fn [d]
                                                 (let [dns (some-> (namespace d) symbol)]
                                                   (when (and dns (not= dns nsym) (contains? ns-set dns))
                                                     dns))))
                                         (mapcat :seon.code.form/deps fs))]))
                      by-ns)
        visited (volatile! #{})
        ordered-nss (volatile! [])
        visit-ns (fn visit-ns [n path]
                   (when-not (or (contains? @visited n) (contains? path n))
                     (doseq [dep (sort (get ns-deps n))]
                       (visit-ns dep (conj path n)))
                     (vswap! visited conj n)
                     (vswap! ordered-nss conj n)))]
    (doseq [n (sort-by (fn [s] [(if (str/ends-with? (name s) "-test") 1 0) (str s)]) ns-set)]
      (visit-ns n #{}))
    (vec
     (mapcat (fn [nsym]
               (sort-by (fn [f]
                          [(if (str/ends-with? (:seon.code.form/key f "") "#ns") 0 1)
                           (:seon.code.form/order f 0)
                           (:seon.code.form/key f "")])
                        (get by-ns nsym)))
             @ordered-nss))))

(defn changed-form-ops [conn from-cid to-cid]
  (if (= from-cid to-cid)
    {:upserts [] :retracted-keys #{} :schema-keys #{} :boot-affected? false}
    (let [ds (d/changes conn from-cid to-cid)
          db (or (try (d/commit-as-db conn to-cid) (catch Throwable _ nil)) @conn)
          key-aid (get-in db [:ident-ref-map :seon.code.form/key])
          src-aid (get-in db [:ident-ref-map :seon.code.form/src])
          sch-aid (get-in db [:ident-ref-map :seon.schema/key])
          schf-aid (get-in db [:ident-ref-map :seon.schema/form])
          fp-aid  (get-in db [:ident-ref-map :seon.code.file/path])
          relevant-form? (fn [a] (or (= a :seon.code.form/key) (= a :seon.code.form/src)
                                     (= a key-aid) (= a src-aid)))
          retracted-keys (into #{}
                               (keep (fn [dt]
                                       (when (and (not (:added dt))
                                                  (or (= (:a dt) :seon.code.form/key) (= (:a dt) key-aid)))
                                         (:v dt))))
                               ds)
          touched-eids (into #{}
                             (keep (fn [dt] (when (and (:added dt) (relevant-form? (:a dt))) (:e dt))))
                             ds)
          schema-eids (into #{}
                            (keep (fn [dt]
                                    (when (or (= (:a dt) :seon.schema/key) (= (:a dt) sch-aid)
                                              (= (:a dt) :seon.schema/form) (= (:a dt) schf-aid))
                                      (:e dt))))
                            ds)
          schema-keys (into #{}
                            (keep (fn [eid] (:seon.schema/key (d/pull db '[:seon.schema/key] eid))))
                            schema-eids)
          boot-files? (boolean
                       (some (fn [dt]
                               (when (or (= (:a dt) :seon.code.file/path) (= (:a dt) fp-aid))
                                 (boot-file-path? (str (:v dt)))))
                             ds))
          live-forms (->> touched-eids
                          (keep (fn [eid]
                                  (let [f (d/pull db '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                                                       :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                                                       :seon.code.form/test?] eid)]
                                    (when (:seon.code.form/src f) f))))
                          sort-forms-topologically)
          live-keys (into #{} (map :seon.code.form/key) live-forms)
          boot-forms? (boolean (some #(contains? boot-root-keys %)
                                     (concat live-keys retracted-keys)))]
      {:upserts live-forms
       :retracted-keys (set/difference retracted-keys live-keys)
       :schema-keys schema-keys
       :boot-affected? (or boot-files? boot-forms?)})))

(defn schema-kw->node-key [kw]
  (str "schema/" (namespace kw) "__" (name kw)))

(defn dep-sym->key [^clojure.lang.Symbol sym]
  (if (= "#ns" (name sym))
    (str (namespace sym) "#ns")
    (str (namespace sym) "/" (name sym))))

(defn compute-node-digest [nodes memo-vol k visiting]
  (if-let [d (get @memo-vol k)]
    d
    (if (contains? visiting k)
      (get-in nodes [k :hash] "cycle")
      (if-let [node (get nodes k)]
        (let [visiting' (conj visiting k)
              dep-digests (mapv #(compute-node-digest nodes memo-vol % visiting')
                                (sort (filter #(contains? nodes %) (:deps node))))
              d (-> (str (hasch/uuid [k (:hash node) dep-digests]))
                    (str/replace "-" "")
                    (subs 0 16))]
          (vswap! memo-vol assoc k d)
          d)
        "external"))))

(defn reverse-closure [nodes seed-keys]
  (loop [queue (vec seed-keys), seen (set seed-keys)]
    (if (empty? queue)
      seen
      (let [cur (peek queue)
            callers (get-in nodes [cur :rdeps] #{})
            fresh (remove seen callers)]
        (recur (into (pop queue) fresh)
               (into seen fresh))))))

(defn function-schema? [sch]
  (boolean (schema/generatable-function-schema? sch)))

(defn build-reach-graph [db]
  (let [raw-forms (->> (d/datoms db :aevt :seon.code.form/key)
                       (map #(d/pull db '[:seon.code.form/key :seon.code.form/hash
                                          :seon.code.form/deps :seon.code.form/test?] (:e %))))
        raw-schemas (->> (d/datoms db :aevt :seon.schema/key)
                         (map #(d/pull db '[:seon.schema/key :seon.schema/form] (:e %))))
        contracted (->> (d/datoms db :aevt :seon.code.var/schema)
                        (keep (fn [dt]
                                (let [v (d/pull db '[:seon.code.var/name :seon.code.var/form :seon.code.var/schema] (:e dt))]
                                  (when (and (:seon.code.var/name v) (function-schema? (:seon.code.var/schema v)))
                                    [(:seon.code.var/name v) (:seon.code.var/form v)]))))
                        (into {}))
        form-nodes (into {}
                         (map (fn [f]
                                [(:seon.code.form/key f)
                                 {:hash (:seon.code.form/hash f)
                                  :deps (into #{} (map dep-sym->key) (:seon.code.form/deps f))
                                  :rdeps #{}
                                  :test? (boolean (:seon.code.form/test? f))}]))
                         raw-forms)
        sch-nodes (into {}
                        (map (fn [{:keys [:seon.schema/key :seon.schema/form]}]
                               [(schema-kw->node-key key)
                                {:hash (-> (str (hasch/uuid [key (clean-edn-for-hash form)]))
                                           (str/replace "-" "")
                                           (subs 0 16))
                                 :deps #{}
                                 :rdeps #{}
                                 :test? false}]))
                        raw-schemas)
        base-nodes (merge sch-nodes form-nodes)
        nodes (reduce-kv
               (fn [acc k {:keys [deps]}]
                 (reduce (fn [a dep-k]
                           (if (contains? a dep-k)
                             (update-in a [dep-k :rdeps] conj k)
                             a))
                         acc deps))
               base-nodes base-nodes)
        memo (volatile! {})
        _ (doseq [k (keys nodes)] (compute-node-digest nodes memo k #{}))
        digests @memo
        tests (into {}
                    (keep (fn [[k n]] (when (:test? n) [(symbol k) (get digests k)])))
                    nodes)
        boot-closure (reverse-closure nodes (filter #(contains? nodes %) boot-root-keys))
        platform-tests (into #{}
                             (keep (fn [k] (when (get-in nodes [k :test?]) (symbol k))))
                             boot-closure)]
    {:commit (d/commit-id db)
     :nodes nodes
     :digests digests
     :tests tests
     :contracted-vars contracted
     :platform-tests platform-tests}))

(defn update-reach-graph [graph conn]
  (let [to-cid (d/commit-id @conn)
        from-cid (:commit graph)]
    (if (= from-cid to-cid)
      graph
      (try
        (let [db @conn
              {:keys [upserts retracted-keys schema-keys]} (changed-form-ops conn from-cid to-cid)
              sch-upserts (keep (fn [sk]
                                  (let [ent (d/pull db '[:seon.schema/key :seon.schema/form] [:seon.schema/key sk])]
                                    (when (some? (:seon.schema/form ent))
                                      {:key (schema-kw->node-key sk)
                                       :hash (-> (str (hasch/uuid [sk (clean-edn-for-hash (:seon.schema/form ent))]))
                                                 (str/replace "-" "")
                                                 (subs 0 16))})))
                                schema-keys)
              dirty-seeds (-> (set retracted-keys)
                              (into (map :seon.code.form/key) upserts)
                              (into (map :key) sch-upserts))
              affected-pre (reverse-closure (:nodes graph) dirty-seeds)
              nodes1 (reduce
                      (fn [ns k]
                        (if-let [old (get ns k)]
                          (let [ns' (reduce (fn [a dep-k]
                                              (if (contains? a dep-k)
                                                (update-in a [dep-k :rdeps] disj k)
                                                a))
                                            ns (:deps old))]
                            (if (contains? retracted-keys k) (dissoc ns' k) ns'))
                          ns))
                      (:nodes graph) dirty-seeds)
              nodes1b (reduce
                       (fn [ns {:keys [key hash]}]
                         (assoc ns key {:hash hash
                                        :deps #{}
                                        :rdeps (get-in ns [key :rdeps] #{})
                                        :test? false}))
                       nodes1 sch-upserts)
              nodes2 (reduce
                      (fn [ns f]
                        (let [k (:seon.code.form/key f)
                              deps (into #{} (map dep-sym->key) (:seon.code.form/deps f))
                              existing-rdeps (get-in ns [k :rdeps] #{})
                              ns' (assoc ns k {:hash (:seon.code.form/hash f)
                                               :deps deps
                                               :rdeps existing-rdeps
                                               :test? (boolean (:seon.code.form/test? f))})]
                          (reduce (fn [a dep-k]
                                    (if (contains? a dep-k)
                                      (update-in a [dep-k :rdeps] conj k)
                                      a))
                                  ns' deps)))
                      nodes1b upserts)
              affected (into affected-pre
                             (reverse-closure nodes2 (concat (map :seon.code.form/key upserts) (map :key sch-upserts))))
              kept-digests (apply dissoc (:digests graph) (concat affected retracted-keys))
              memo (volatile! kept-digests)
              _ (doseq [k affected :when (contains? nodes2 k)]
                  (compute-node-digest nodes2 memo k #{}))
              digests' @memo
              tests1 (apply dissoc (:tests graph) (map symbol retracted-keys))
              tests2 (reduce
                      (fn [ts k]
                        (if-let [n (get nodes2 k)]
                          (if (:test? n)
                            (assoc ts (symbol k) (get digests' k))
                            (dissoc ts (symbol k)))
                          ts))
                      tests1 affected)
              contracted' (reduce
                           (fn [cv f]
                             (let [fk (:seon.code.form/key f)
                                   v-eids (map :e (d/datoms db :avet :seon.code.var/form fk))
                                   vs (map #(d/pull db '[:seon.code.var/name :seon.code.var/schema] %) v-eids)
                                   cv1 (into {} (remove #(= fk (val %)) cv))]
                               (reduce (fn [a v]
                                         (if (and (:seon.code.var/name v) (function-schema? (:seon.code.var/schema v)))
                                           (assoc a (:seon.code.var/name v) fk)
                                           a))
                                       cv1 vs)))
                           (into {} (remove #(contains? retracted-keys (val %)) (:contracted-vars graph)))
                           upserts)
              boot-closure (reverse-closure nodes2 (filter #(contains? nodes2 %) boot-root-keys))
              platform-tests (into #{}
                                   (keep (fn [k] (when (get-in nodes2 [k :test?]) (symbol k))))
                                   boot-closure)]
          {:commit to-cid
           :nodes nodes2
           :digests digests'
           :tests tests2
           :contracted-vars contracted'
           :platform-tests platform-tests})
        (catch Throwable _
          (build-reach-graph @conn))))))

(defn reaching-tests
  "Reverse BFS over Datahike's indexed `:seon.code.var/refs` (up to 4 hops) from a var symbol
   (e.g. `'seon.render/hiccup->html`) or all vars of a namespace (`'seon.render`), returning
   sorted reaching `:seon.code.var/test?` var symbols."
  [db sym-or-ns]
  (if-not (and db sym-or-ns
               (contains? (:schema db) :seon.code.var/refs)
               (contains? (:schema db) :seon.code.var/ns))
    []
    (let [s (if (string? sym-or-ns) (symbol sym-or-ns) sym-or-ns)
          seed-eids (when-not (namespace s)
                      (mapv :e (d/datoms db :avet :seon.code.var/ns s)))
          seeds (if (namespace s)
                  [s]
                  (mapv #(:seon.code.var/name (d/pull db '[:seon.code.var/name] %)) seed-eids))]
      (loop [hop 0
             frontier (set (remove nil? seeds))
             visited-syms (set (remove nil? seeds))
             visited-eids (set (or seed-eids []))
             tests #{}]
        (if (or (empty? frontier) (>= hop 4))
          (vec (sort tests))
          (let [fresh-eids (volatile! #{})
                _ (doseq [sym frontier
                          dt (d/datoms db :avet :seon.code.var/refs sym)
                          :let [e (:e dt)]
                          :when (not (contains? visited-eids e))]
                    (vswap! fresh-eids conj e))
                eids @fresh-eids
                next-ents (into []
                                (comp (map #(d/pull db '[:seon.code.var/name :seon.code.var/test?] %))
                                      (filter :seon.code.var/name))
                                eids)
                new-tests (into tests (keep #(when (:seon.code.var/test? %) (:seon.code.var/name %))) next-ents)
                new-front (into #{}
                                (keep (fn [e]
                                        (let [vn (:seon.code.var/name e)]
                                          (when (and (not (:seon.code.var/test? e))
                                                     (not (contains? visited-syms vn)))
                                            vn))))
                                next-ents)]
            (recur (inc hop) new-front (into visited-syms new-front) (into visited-eids eids) new-tests)))))))

(defonce ^:seon.flow/edge ns-linked-docs-cache (store/lru-cache 64))

(defn ns-linked-docs
"Returns sorted `{:id :path :heading}` summaries for `:seon.code.doc/*` sections linked to `nsym`
 by path stem (e.g. `seon.render*` -> `docs/render.md`) or body mention of `nsym`.
 Bounded-cached by `:seon.code.doc/id` signature so repeated tile renders pay 0 doc-body deserialization."
([db nsym] (ns-linked-docs db nsym 8))
([db nsym max-n]
 (if-not (and db nsym (contains? (:schema db) :seon.code.doc/id))
   []
   (let [doc-sig (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))])
                         [0 0]
                         (d/datoms db :aevt :seon.code.doc/id))
         ck [doc-sig nsym max-n]]
     (store/lru-get! ns-linked-docs-cache 64 ck
       (fn []
       (let [ns-str (name nsym)
             seg2 (second (str/split ns-str #"\."))
             stem-path (when seg2 (str "docs/" seg2 ".md"))
             hit-eids (volatile! #{})
             _ (when (and stem-path (contains? (:schema db) :seon.code.doc/path))
                 (doseq [dt (d/datoms db :avet :seon.code.doc/path stem-path)]
                   (vswap! hit-eids conj (:e dt))))
             _ (when (contains? (:schema db) :seon.code.doc/body)
                 (doseq [dt (d/datoms db :aevt :seon.code.doc/body)
                         :when (str/includes? (str (:v dt)) ns-str)]
                   (vswap! hit-eids conj (:e dt))))
             _ (when (contains? (:schema db) :seon.code.doc/heading)
                 (doseq [dt (d/datoms db :aevt :seon.code.doc/heading)
                         :when (str/includes? (str (:v dt)) ns-str)]
                   (vswap! hit-eids conj (:e dt))))
             res (->> @hit-eids
                      (keep (fn [eid]
                              (let [e (d/pull db '[:seon.code.doc/id :seon.code.doc/path
                                                   :seon.code.doc/heading] eid)]
                                (when (:seon.code.doc/id e)
                                  {:id (:seon.code.doc/id e)
                                   :path (:seon.code.doc/path e)
                                   :heading (:seon.code.doc/heading e)}))))
                      (sort-by (juxt #(if (= (:path %) stem-path) 0 1) :id))
                      (take max-n)
                      vec)]
         res)))))))
