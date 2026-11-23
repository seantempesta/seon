(ns seon.code.reach
  "Incremental O(|Delta|) Merkle reach graph, reverse caller BFS, and doc-link indexing."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [jolt.analyzer]
            [jolt.host]
            [seon.schema :as schema]
            [seon.store :as store]
            [hasch.benc :as hbenc]
            [hasch.platform :as hplat]))

(def boot-root-keys
  #{"seon.main#ns"
    "seon.main/-main"
    "seon.main/start-flow-instance!"
    "seon.fork/start-fork-parent!"
    "seon.fork/run-fork-parent-loop!"
    "seon.store/open!"
    "seon.schema.datahike/declarations"
    "seon.switchover/run-switch-over!"})

(defn boot-file-path?
  {:malli/schema [:=> [:cat :string] :boolean], :cost/ticks 500000, :cost/bytes 524288}
  [^String path]
  (or (= path "deps.edn")
      (= path "src/seon/main.clj")
      (= path "bin/build")
      (= path "bin/env")
      (str/starts-with? path "native/")))

(defn clean-edn-for-hash
  {:malli/schema [:=> [:cat :any] :any], :cost/ticks 5000000}
  [x]
  (cond
    (or (seq? x) (list? x) (vector? x)) (mapv clean-edn-for-hash x)
    (set? x) (vec (sort-by pr-str (map clean-edn-for-hash x)))
    (map? x) (vec (sort-by (comp pr-str first)
                           (map (fn [[k v]] [(clean-edn-for-hash k) (clean-edn-for-hash v)]) x)))
    (instance? java.util.regex.Pattern x) [:regex (str x)]
    :else x))

(defn sort-forms-topologically
  {:malli/schema [:=> [:cat [:sequential [:map [:seon.code.form/key :string] [:seon.code.form/ns :symbol] [:seon.code.form/order {:optional true} :int] [:seon.code.form/deps {:optional true} [:or [:set :qualified-symbol] [:sequential :qualified-symbol]]]]]] [:vector :map]], :cost/ticks 10000000}
  [forms]
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

(defn changed-form-ops
  {:malli/schema [:=> [:cat :seon.host/conn :any :any] :map]}
  [conn from-cid to-cid]
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

(defn schema-kw->node-key
  {:malli/schema [:=> [:cat :keyword] :string], :cost/ticks 500000}
  [kw]
  (str "schema/" (namespace kw) "__" (name kw)))

(defn dep-sym->key
  {:malli/schema [:=> [:cat :symbol] :string], :cost/ticks 500000}
  [^clojure.lang.Symbol sym]
  (if (= "#ns" (name sym))
    (str (namespace sym) "#ns")
    (str (namespace sym) "/" (name sym))))

(defn compute-node-digest
  {:malli/schema [:function [:=> [:cat :map :any :string [:set :string]] :string]]}
  [nodes memo-vol k visiting]
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

(defn reverse-closure
  {:malli/schema [:=> [:cat [:map-of :string [:map [:rdeps {:optional true} [:set :string]]]] [:sequential :string]] [:set :string]], :cost/ticks 5000000}
  [nodes seed-keys]
  (loop [queue (vec seed-keys), seen (set seed-keys)]
    (if (empty? queue)
      seen
      (let [cur (peek queue)
            callers (get-in nodes [cur :rdeps] #{})
            fresh (remove seen callers)]
        (recur (into (pop queue) fresh)
               (into seen fresh))))))

(defn function-schema?
  {:malli/schema [:=> [:cat :any] :boolean], :cost/ticks 500000}
  [sch]
  (boolean (schema/generatable-function-schema? sch)))

(defonce ^:seon.flow/edge ns-linked-docs-cache (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge reaching-tests-cache
  (store/lru-cache 128))

(defn- compute-reaching-tests
  [db s]
  (let [seed-eids (when-not (namespace s)
                    (mapv :e (d/datoms db :avet :seon.code.var/ns s)))
        seeds (if (namespace s)
                [s]
                (keep #(:v (first (d/datoms db :eavt % :seon.code.var/name))) seed-eids))]
    (loop [hop 0
           frontier (set seeds)
           visited-syms (set seeds)
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
              new-tests (volatile! tests)
              new-front (volatile! #{})]
          (doseq [e eids
                  :let [vn (:v (first (d/datoms db :eavt e :seon.code.var/name)))]
                  :when vn]
            (if (true? (:v (first (d/datoms db :eavt e :seon.code.var/test?))))
              (vswap! new-tests conj vn)
              (when-not (contains? visited-syms vn)
                (vswap! new-front conj vn))))
          (recur (inc hop) @new-front (into visited-syms @new-front) (into visited-eids eids) @new-tests))))))

(defn reaching-tests
"Reverse BFS over Datahike's indexed `:seon.code.var/refs` (up to 4 hops) from a var symbol
 (e.g. `'seon.render/hiccup->html`) or all vars of a namespace (`'seon.render`), returning
 sorted reaching `:seon.code.var/test?` var symbols."
  {:malli/schema [:=> [:cat :seon.host/db :symbol] [:vector :symbol]]}
[db sym-or-ns]
(if-not (and db sym-or-ns
             (contains? (:schema db) :seon.code.var/refs)
             (contains? (:schema db) :seon.code.var/ns))
  []
  (let [s (if (string? sym-or-ns) (symbol sym-or-ns) sym-or-ns)
        cid (when (and (not (instance? datahike.db.AsOfDB db))
                       (not (instance? datahike.db.HistoricalDB db)))
              (try (d/commit-id db) (catch Throwable _ nil)))
        ck (when cid [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) cid (:max-tx db) s])]
    (if ck
      (store/lru-get! reaching-tests-cache 128 ck #(compute-reaching-tests db s))
      (compute-reaching-tests db s)))))

(defonce ^:private ^:seon.flow/edge ns-doc-index-cache
  (store/lru-cache 16))

(defn- cached-doc-index
  [db doc-sig]
  (store/lru-get! ns-doc-index-cache 16 doc-sig
    (fn []
      (let [ids (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.doc/id))
            paths (if (contains? (:schema db) :seon.code.doc/path)
                    (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.doc/path))
                    {})
            heads (if (contains? (:schema db) :seon.code.doc/heading)
                    (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.doc/heading))
                    {})
            bodies (if (contains? (:schema db) :seon.code.doc/body)
                     (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.doc/body))
                     {})]
        (reduce-kv (fn [acc e id]
                     (conj acc {:id id
                                :path (get paths e)
                                :heading (get heads e)
                                :body (get bodies e)}))
                   [] ids)))))

(defn ns-linked-docs
"Returns sorted `{:id :path :heading}` summaries for `:seon.code.doc/*` sections linked to `nsym`
 by path stem (e.g. `seon.render*` -> `docs/render.md`) or body mention of `nsym`.
 Bounded-cached by `:seon.code.doc/id` signature so repeated tile renders pay 0 doc-body deserialization."
  {:malli/schema [:function [:=> [:cat :seon.host/db :symbol] [:vector :map]] [:=> [:cat :seon.host/db :symbol :int] [:vector :map]]]}
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
               stem-path (when seg2 (str "docs/" seg2 ".md"))]
           (->> (cached-doc-index db doc-sig)
                (keep (fn [{:keys [id path heading body]}]
                        (when (or (and stem-path (= path stem-path))
                                  (and body (str/includes? (str body) ns-str))
                                  (and heading (str/includes? (str heading) ns-str)))
                          {:id id :path path :heading heading})))
                (sort-by (juxt #(if (= (:path %) stem-path) 0 1) :id))
                (take max-n)
                vec))))))))

(defn- ^:seon.flow/edge safe-commit-changes [conn from-cid to-cid]
  (try
    (d/changes conn from-cid to-cid)
    (catch Throwable _
      (try
        (when-let [base (d/merge-base conn from-cid to-cid)]
          (if (= base to-cid) [] (d/changes conn base to-cid)))
        (catch Throwable _ [])))))

(defn- coerce-str-bytes
  [md-fn ^bytes nil-bytes ^long split-sz str-magic s]
  (if (nil? s)
    nil-bytes
    (let [^bytes a (.getBytes ^String s "UTF-8")
          len (alength a)]
      (if (< len split-sz)
        (let [out (byte-array (inc (* 2 len)))]
          (aset out 0 (byte str-magic))
          (System/arraycopy a 0 out 1 len)
          (let [esc-off (inc len)]
            (loop [i 0]
              (when (< i len)
                (let [b (aget a i)]
                  (when (and (> b 0) (< b 30))
                    (aset out (+ esc-off i) (byte 1))))
                (recur (inc i)))))
          out)
        (hplat/encode (byte str-magic) (hbenc/digest a md-fn))))))

(defn- sha512->uuid5-hex16
  [^bytes dg]
  (let [high (loop [i 0 acc 0]
               (if (< i 8)
                 (let [raw (bit-and (long (aget dg i)) 0xff)
                       b (case i 0 (bit-and raw 0x3f) 6 (bit-or (bit-and raw 0x0f) 0x50) raw)]
                   (recur (inc i) (bit-or (bit-shift-left acc 8) b)))
                 acc))
        s (Long/toHexString high)
        n (.length s)]
    (if (== n 16) s (str (subs "0000000000000000" 0 (- 16 n)) s))))

(defn- digest-node-hex
  [^java.security.MessageDigest md md-fn ^bytes nil-bytes ^bytes fin-in ^long split-sz str-magic vec-magic k h dep-cbs]
  (let [^bytes deps-in (if (empty? dep-cbs)
                         nil-bytes
                         (let [tot (reduce (fn [n ^bytes b] (+ n (alength b))) 0 dep-cbs)
                               buf (byte-array tot)]
                           (reduce (fn [off ^bytes b]
                                     (let [bl (alength b)]
                                       (System/arraycopy b 0 buf off bl)
                                       (+ off bl)))
                                   0 dep-cbs)
                           buf))
        ^bytes deps-dg (.digest md deps-in)
        ^bytes kb (coerce-str-bytes md-fn nil-bytes split-sz str-magic k)
        ^bytes hb (coerce-str-bytes md-fn nil-bytes split-sz str-magic h)
        kl (alength kb)
        hl (alength hb)
        dl (alength deps-dg)
        outer-in (byte-array (+ kl hl 1 dl))]
    (System/arraycopy kb 0 outer-in 0 kl)
    (System/arraycopy hb 0 outer-in kl hl)
    (aset outer-in (+ kl hl) (byte vec-magic))
    (System/arraycopy deps-dg 0 outer-in (+ kl hl 1) dl)
    (let [^bytes body (.digest md outer-in)]
      (System/arraycopy body 0 fin-in 1 (alength body))
      (sha512->uuid5-hex16 (.digest md fin-in)))))

(defn- fast-hasch-hex
  [md-fn x]
  (sha512->uuid5-hex16 (hbenc/digest (hbenc/-coerce x md-fn nil) md-fn)))

(defn apply-overlay-to-reach-graph
  "Applies a branch overlay {:upserts :retracted-keys :schema-upserts :retracted-schemas}
   onto an existing reach graph, recomputing digests and contracted vars for the candidate branch."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map :map] :map]
   :cost/ticks 50000000}
  [theirs-db graph {:keys [upserts retracted-keys schema-upserts retracted-schemas]}]
  (if (and (empty? upserts) (empty? retracted-keys) (empty? schema-upserts) (empty? retracted-schemas))
    graph
    (let [retracted-node-keys (into (set retracted-keys) (map schema-kw->node-key) retracted-schemas)
          nodes1 (apply dissoc (:nodes graph) retracted-node-keys)
          nodes1b (reduce
                   (fn [ns {:keys [:seon.schema/key :seon.schema/form]}]
                     (assoc ns (schema-kw->node-key key)
                            {:hash (-> (str (hasch/uuid [key (clean-edn-for-hash form)]))
                                       (str/replace "-" "")
                                       (subs 0 16))
                             :deps #{} :rdeps #{} :test? false}))
                   nodes1 schema-upserts)
          nodes2 (reduce
                  (fn [ns f]
                    (assoc ns (:seon.code.form/key f)
                           {:hash (:seon.code.form/hash f)
                            :deps (into #{} (map dep-sym->key) (:seon.code.form/deps f))
                            :rdeps #{}
                            :test? (boolean (:seon.code.form/test? f))}))
                  nodes1b upserts)
          nodes3 (reduce-kv
                  (fn [acc k {:keys [deps]}]
                    (reduce (fn [a dep-k]
                              (if (contains? a dep-k) (update-in a [dep-k :rdeps] conj k) a))
                            acc deps))
                  nodes2 nodes2)
          memo (volatile! {})
          _ (doseq [k (keys nodes3)] (compute-node-digest nodes3 memo k #{}))
          digests @memo
          contracted' (reduce
                       (fn [cv f]
                         (let [fk (:seon.code.form/key f)
                               v-eids (when theirs-db (map :e (d/datoms theirs-db :avet :seon.code.var/form fk)))
                               vs (map #(d/pull theirs-db '[:seon.code.var/name :seon.code.var/schema] %) v-eids)
                               cv1 (into {} (remove #(= fk (val %)) cv))]
                           (reduce (fn [a v]
                                     (if (and (:seon.code.var/name v) (function-schema? (:seon.code.var/schema v)))
                                       (assoc a (:seon.code.var/name v) fk)
                                       a))
                                   cv1 vs)))
                       (into {} (remove #(contains? retracted-keys (val %)) (:contracted-vars graph)))
                       upserts)]
      {:nodes nodes3
       :digests digests
       :tests (into {} (keep (fn [[k n]] (when (:test? n) [(symbol k) (get digests k)]))) nodes3)
       :contracted-vars contracted'
       :platform-tests (:platform-tests graph #{})})))

(defn- aevt-map
  ^java.util.HashMap [db sch attr ^long cap]
  (let [^java.util.HashMap m (java.util.HashMap. (int cap))]
    (when (contains? sch attr)
      (reduce (fn [_ ^datahike.datom.Datom dt] (.put m (.-e dt) (.-v dt)))
              nil (d/datoms db :aevt attr)))
    m))

(defn- each-entry!
  [^java.util.HashMap hm f]
  (let [^java.util.Iterator it (.iterator (.entrySet hm))]
    (loop []
      (when (.hasNext it)
        (let [^java.util.Map en (.next it)]
          (f (.getKey en) (.getValue en))
          (recur))))))

(defn- aevt-filter-map
  ^java.util.HashMap [db sch attr ^java.util.HashSet keep-eids]
  (let [^java.util.HashMap m (java.util.HashMap. (.size keep-eids))]
    (when (contains? sch attr)
      (reduce (fn [_ ^datahike.datom.Datom dt]
                (let [e (.-e dt)] (when (.contains keep-eids e) (.put m e (.-v dt)))))
              nil (d/datoms db :aevt attr)))
    m))

(defn- build-reach-nodes
  [db md-fn]
  (let [sch (:schema db)
        ^java.util.HashMap eid->fkey (aevt-map db sch :seon.code.form/key 2400)
        ^java.util.HashMap eid->fhash (aevt-map db sch :seon.code.form/hash 2400)
        ^java.util.HashMap eid->fdeps (java.util.HashMap. 2400)
        ^java.util.HashMap sym->key (java.util.HashMap. 2400)
        ^java.util.HashSet test-eids (java.util.HashSet. 256)
        _ (when (contains? sch :seon.code.form/deps)
            (reduce (fn [_ ^datahike.datom.Datom dt]
                      (let [e (.-e dt) sym (.-v dt)
                            dk (or (.get sym->key sym)
                                   (let [k (dep-sym->key sym)] (.put sym->key sym k) k))]
                        (.put eid->fdeps e (conj! (or (.get eid->fdeps e) (transient #{})) dk))))
                    nil (d/datoms db :aevt :seon.code.form/deps)))
        _ (when (contains? sch :seon.code.form/test?)
            (reduce (fn [_ ^datahike.datom.Datom dt] (when (.-v dt) (.add test-eids (.-e dt))))
                    nil (d/datoms db :aevt :seon.code.form/test?)))
        ^java.util.HashMap eid->skey (aevt-map db sch :seon.schema/key 512)
        ^java.util.HashMap eid->sform (aevt-map db sch :seon.schema/form 512)
        ^java.util.HashSet c-eids (java.util.HashSet. 512)
        _ (when (contains? sch :seon.code.var/schema)
            (reduce (fn [_ ^datahike.datom.Datom dt]
                      (when (function-schema? (.-v dt)) (.add c-eids (.-e dt))))
                    nil (d/datoms db :aevt :seon.code.var/schema)))
        contracted
        (if (.isEmpty c-eids)
          {}
          (let [^java.util.HashMap vn (aevt-filter-map db sch :seon.code.var/name c-eids)
              ^java.util.HashMap vf (aevt-filter-map db sch :seon.code.var/form c-eids)
              out (transient {})]
          (each-entry! vn (fn [e nm] (assoc! out nm (.get vf e))))
          (persistent! out)))
        ^java.util.HashMap base (java.util.HashMap. (+ (.size eid->fkey) (.size eid->skey) 16))
        _ (each-entry! eid->skey
            (fn [e sk]
              (.put base (schema-kw->node-key sk)
                    {:hash (fast-hasch-hex md-fn [sk (clean-edn-for-hash (.get eid->sform e))])
                     :deps #{} :rdeps #{} :test? false})))
        _ (each-entry! eid->fkey
            (fn [e fk]
              (let [td (.get eid->fdeps e)]
                (.put base fk {:hash (.get eid->fhash e) :deps (if td (persistent! td) #{})
                               :rdeps #{} :test? (.contains test-eids e)}))))
        ^java.util.HashMap rdeps (java.util.HashMap. (.size base))
        _ (each-entry! base
            (fn [k n]
              (reduce (fn [_ dk]
                        (when (.containsKey base dk)
                          (.put rdeps dk (conj! (or (.get rdeps dk) (transient #{})) k))))
                      nil (:deps n))))
        out (transient {})
        _ (each-entry! base
            (fn [k n]
              (let [rd (.get rdeps k)]
                (assoc! out k (if rd (assoc n :rdeps (persistent! rd)) n)))))]
    {:nodes (persistent! out) :contracted contracted}))

(defn build-reach-graph
  "Builds the Merkle reach graph {:commit :nodes :digests :tests :contracted-vars :platform-tests}
   from db using single-pass flat AEVT index scans joined via Java hash maps (zero d/pulls)."
  {:malli/schema [:=> [:cat :seon.host/db] :map]
   :cost/ticks 200000000}
  [db]
  (let [^java.security.MessageDigest md (java.security.MessageDigest/getInstance "SHA-512")
        md-fn (fn [] (.reset md) md)
        {:keys [nodes contracted]} (build-reach-nodes db md-fn)
        str-magic (byte (:string hbenc/magics))
        vec-magic (byte (:vector hbenc/magics))
        nil-bytes (doto (byte-array 1) (aset 0 (byte (:nil hbenc/magics))))
        fin-in (doto (byte-array 65) (aset 0 vec-magic))
        split-sz (long hbenc/split-size)
        ^java.util.HashMap memo (java.util.HashMap. (max 16 (count nodes)))
        ^java.util.HashMap memo-bytes (java.util.HashMap. (max 16 (count nodes)))
        compute (fn compute [k visiting]
                  (if-let [cb (.get memo-bytes k)]
                    cb
                    (if (contains? visiting k)
                      (coerce-str-bytes md-fn nil-bytes split-sz str-magic (get-in nodes [k :hash] "cycle"))
                      (if-let [node (get nodes k)]
                        (let [deps (:deps node)
                              dep-cbs (if (empty? deps)
                                        []
                                        (let [in-deps (persistent! (reduce (fn [acc dk] (if (contains? nodes dk) (conj! acc dk) acc))
                                                                           (transient []) deps))
                                              n (count in-deps)]
                                          (if (zero? n)
                                            []
                                            (let [v' (conj visiting k)]
                                              (mapv #(compute % v') (if (== n 1) in-deps (sort in-deps)))))))
                              d (digest-node-hex md md-fn nil-bytes fin-in split-sz str-magic vec-magic k (:hash node) dep-cbs)
                              cb (coerce-str-bytes md-fn nil-bytes split-sz str-magic d)]
                          (.put memo k d)
                          (.put memo-bytes k cb)
                          cb)
                        (coerce-str-bytes md-fn nil-bytes split-sz str-magic "external")))))
        _ (reduce-kv (fn [_ k _] (compute k #{})) nil nodes)
        dig-tr (transient {})
        _ (each-entry! memo (fn [k v] (assoc! dig-tr k v)))
        digests (persistent! dig-tr)
        tests (reduce-kv (fn [acc k n] (if (:test? n) (assoc acc (symbol k) (get digests k)) acc)) {} nodes)
        boot-closure (reverse-closure nodes (filter #(contains? nodes %) boot-root-keys))
        platform-tests (into #{} (keep (fn [k] (when (get-in nodes [k :test?]) (symbol k)))) boot-closure)]
    {:commit (d/commit-id db)
     :nodes nodes
     :digests digests
     :tests tests
     :contracted-vars contracted
     :platform-tests platform-tests}))

(defn update-reach-graph
  {:malli/schema [:=> [:cat :map :seon.host/conn] :map]}
  [graph conn]
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
