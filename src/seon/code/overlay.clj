(ns seon.code.overlay
  "Hot-reload code overlay application, alias-clearing namespace rebinds, and Malli var arming."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [jolt.analyzer]
            [jolt.host]
            [seon.schema :as schema]
            [seon.code.reach :as reach :refer [changed-form-ops sort-forms-topologically]]
            [seon.code.io :as cio :refer [ns-forms]]
            [seon.store :as store]))

(defn register-loaded-ns!
  {:malli/schema [:=> [:cat :symbol] :any]}
  [nsym]
  (when (and (symbol? nsym) (not= 'user nsym))
    (dosync (commute @#'clojure.core/*loaded-libs* conj nsym))))

(defn arm-defined-vars-in-ns!
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :symbol] :any]}
  [db nsym]
  (when-let [ns-obj (find-ns nsym)]
    (doseq [[_ v] (ns-interns ns-obj)]
      (let [m (meta v)]
        (when (and (not (:private m)) (not (:seon/instrumented? m)) (:malli/schema m))
          (schema/arm-var! db v))))))

(defn- spliced-caller-forms [db seed-syms upsert-keys retracted-keys]
(if-not (and db (seq seed-syms) (contains? (:schema db) :seon.code.var/calls))
  []
  (let [skip-nss #{'seon.main 'seon.fork 'seon.switchover}
        form-pull '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                    :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                    :seon.code.form/test?]
        direct-callers (fn [syms]
                         (into #{}
                               (for [s syms
                                     :let [veid (:e (first (d/datoms db :avet :seon.code.var/name s)))]
                                     :when veid
                                     dt (d/datoms db :avet :seon.code.var/calls veid)
                                     :let [csym (:v (first (d/datoms db :eavt (:e dt) :seon.code.var/name)))]
                                     :when csym]
                                 csym)))
        fetch-form (fn [csym]
                     (when-let [veid (:e (first (d/datoms db :avet :seon.code.var/name csym)))]
                       (when-let [fk (:v (first (d/datoms db :eavt veid :seon.code.var/form)))]
                         (when (and (not (contains? upsert-keys fk))
                                    (not (contains? retracted-keys fk)))
                           (let [f (dissoc (d/pull db form-pull [:seon.code.form/key fk]) :db/id)
                                 src (str (:seon.code.form/src f))]
                             (when (and (seq src)
                                        (not (contains? skip-nss (:seon.code.form/ns f)))
                                        (not (str/starts-with? (str/trim src) "(defonce ")))
                               f))))))
        hop1-syms (remove (set seed-syms) (direct-callers seed-syms))
        hop1-forms (into {} (keep (fn [s] (when-let [f (fetch-form s)] [(:seon.code.form/key f) [s f]]))) hop1-syms)
        small-hop1 (keep (fn [[_ [s f]]]
                           (when (and (not (:seon.code.form/test? f))
                                      (<= (count (str (:seon.code.form/src f))) 1800))
                             s))
                         hop1-forms)
        hop2-syms (remove (into (set seed-syms) hop1-syms) (direct-callers small-hop1))
        hop2-forms (into {} (keep (fn [s] (when-let [f (fetch-form s)] [(:seon.code.form/key f) [s f]]))) hop2-syms)
        ;; Also rebind anonymous #expr# forms (deftype/defrecord/extend-type) referencing seed-syms via :seon.code.form/deps
        ;; PLUS their same-ns constructors so AOT deftype classes never retain stale direct-linked targets!
        expr-forms (when (contains? (:schema db) :seon.code.form/deps)
                     (let [ef (into {}
                                    (for [s seed-syms
                                          dt (d/datoms db :avet :seon.code.form/deps s)
                                          :let [f (dissoc (d/pull db form-pull (:e dt)) :db/id)
                                                fk (:seon.code.form/key f)
                                                src (str (:seon.code.form/src f))]
                                          :when (and fk (str/includes? (str fk) "#expr#")
                                                     (not (contains? upsert-keys fk))
                                                     (not (contains? retracted-keys fk))
                                                     (not (contains? skip-nss (:seon.code.form/ns f)))
                                                     (not (str/starts-with? (str/trim src) "(defonce ")))]
                                      [fk f]))
                           ctor-forms (into {}
                                            (for [[_ f] ef
                                                  :let [m (re-find #"^\((?:deftype|defrecord)\s+([A-Za-z0-9_?!-]+)" (str/trim (str (:seon.code.form/src f))))
                                                        tname (second m)]
                                                  :when tname
                                                  nf (ns-forms db (:seon.code.form/ns f))
                                                  :let [nfk (:seon.code.form/key nf)
                                                        nsrc (str (:seon.code.form/src nf))]
                                                  :when (and nfk (not= nfk (:seon.code.form/key f))
                                                             (not (contains? upsert-keys nfk))
                                                             (not (contains? retracted-keys nfk))
                                                             (not (str/starts-with? (str/trim nsrc) "(defonce "))
                                                             (or (str/includes? nsrc (str tname "."))
                                                                 (str/includes? nsrc (str "->" tname))))]
                                              [nfk nf]))]
                       (merge ef ctor-forms)))]
    (vec (vals (merge (into {} (map (fn [[k [_ f]]] [k f])) (merge hop1-forms hop2-forms))
                      expr-forms))))))

(defn expand-overlay-rebinds
  "Expands an overlay `{:upserts [...] :retracted-keys #{...}}` using `db` so IPC recipients
   without a DB handle (`ForkParent` `:advance-image` and COW test workers) receive every
   caller form that must recompile when a var or namespace is retracted/renamed OR when an
   upserted var may have been AOT-spliced into compiled callers by Jolt."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] :map]}
  [db {:keys [upserts retracted-keys] :as overlay}]
  (if (or (nil? db) (and (empty? upserts) (empty? retracted-keys)))
    overlay
    (let [upsert-keys (into #{} (map :seon.code.form/key) upserts)
          upsert-syms (into #{} (keep (fn [k]
                                        (let [[ns-str var-str] (str/split (str k) #"/" 2)]
                                          (when (and ns-str var-str (not (str/includes? var-str "#")))
                                            (symbol ns-str var-str)))))
                            upsert-keys)
          retracted-by-ns (reduce (fn [acc k]
                                    (let [[ns-str var-str] (str/split (str k) #"/" 2)]
                                      (if (and ns-str var-str (not (str/includes? var-str "#")))
                                        (update acc (symbol ns-str) (fnil conj #{}) var-str)
                                        acc)))
                                  {}
                                  retracted-keys)
          retracted-nss (into #{}
                              (keep #(let [s (str %)]
                                       (when (str/ends-with? s "#ns")
                                         (symbol (subs s 0 (- (count s) 3))))))
                              retracted-keys)
          updated-ns-hdrs (into #{}
                                (keep #(when (str/ends-with? (str (:seon.code.form/key %)) "#ns")
                                         (:seon.code.form/ns %)))
                                upserts)
          target-nss (into (set (keys retracted-by-ns)) updated-ns-hdrs)
          rebind-forms (when (or (seq retracted-by-ns) (seq retracted-nss))
                         (for [nsym target-nss
                               f (ns-forms db nsym)
                               :let [fk (:seon.code.form/key f)
                                     src (str (:seon.code.form/src f))
                                     rebind-all? (and (seq retracted-nss) (contains? updated-ns-hdrs nsym))
                                     unmapped-names (get retracted-by-ns nsym)]
                               :when (and (not (contains? upsert-keys fk))
                                          (not (contains? retracted-keys fk))
                                          (or rebind-all?
                                              (some #(str/includes? src %) unmapped-names)))]
                           f))
          spliced-forms (spliced-caller-forms db upsert-syms (into upsert-keys (map :seon.code.form/key) rebind-forms) retracted-keys)
          extra (concat rebind-forms spliced-forms)]
      (if (seq extra)
        (assoc overlay :upserts (vec (vals (reduce (fn [m f] (assoc m (:seon.code.form/key f) f)) {} (concat upserts extra)))))
        overlay))))

(defn- merge-3way-map [base ours theirs]
  (let [after-del (reduce (fn [acc k]
                            (if (and (not (contains? theirs k)) (= (get ours k) (get base k)))
                              (dissoc acc k) acc))
                          (or ours {}) (keys base))]
    (reduce-kv (fn [acc k v] (if (not= v (get base k ::absent)) (assoc acc k v) acc))
               after-del (or theirs {}))))

(defn- candidate-ns-forms [base-db main-db to-db nsym]
  (let [to-m #(into {} (map (juxt :seon.code.form/key identity)) %)
        merged (merge-3way-map (to-m (ns-forms base-db nsym))
                               (to-m (ns-forms main-db nsym))
                               (to-m (ns-forms to-db nsym)))]
    (->> (vals merged)
         (sort-by (fn [f]
                    [(if (str/ends-with? (str (:seon.code.form/key f)) "#ns") 0 1)
                     (long (or (:seon.code.form/order f) 0))
                     (str (:seon.code.form/key f))]))
         vec)))

(defn- collect-overlay-upserts [db {:keys [upserts retracted-keys]} forms-for-ns]
  (let [get-forms (or forms-for-ns #(ns-forms db %))
        has-src? (boolean (or db forms-for-ns))
        retracted-by-ns
        (reduce (fn [acc k]
                  (let [[ns-str var-str] (str/split k #"/" 2)]
                    (if (and ns-str var-str (not (str/includes? var-str "#")))
                      (update acc (symbol ns-str) (fnil conj #{}) var-str)
                      acc)))
                {} retracted-keys)
        all-unmapped (into #{} (mapcat identity) (vals retracted-by-ns))
        upsert-keys (into #{} (map :seon.code.form/key) upserts)
        retracted-nss (into #{} (keep #(let [s (str %)] (when (str/ends-with? s "#ns") (symbol (subs s 0 (- (count s) 3)))))) retracted-keys)
        updated-ns-hdrs (into #{} (keep #(when (str/ends-with? (str (:seon.code.form/key %)) "#ns") (:seon.code.form/ns %))) upserts)
        
        rebind-forms
        (when (and has-src? (or (seq all-unmapped) (seq retracted-nss)))
          (for [nsym (into (set (keys retracted-by-ns)) updated-ns-hdrs)
                f (get-forms nsym)
                :let [fk (:seon.code.form/key f)
                      src (str (:seon.code.form/src f))
                      rebind-all? (and (seq retracted-nss) (contains? updated-ns-hdrs nsym))
                      names (if (contains? updated-ns-hdrs nsym) all-unmapped (get retracted-by-ns nsym))]
                :when (and (not (contains? upsert-keys fk)) (not (contains? retracted-keys fk))
                           (or rebind-all? (some #(str/includes? src %) names)))]
            f))
        unloaded-forms
        (when has-src?
          (let [unloaded (into #{} (comp (map :seon.code.form/ns) (filter #(and % (nil? (find-ns %))))) upserts)]
            (when (seq unloaded)
              (for [nsym unloaded f (get-forms nsym) :let [fk (:seon.code.form/key f)]
                    :when (and (not (contains? upsert-keys fk)) (not (contains? retracted-keys fk)))]
                f))))]
    {:retracted-nss retracted-nss
    :retracted-by-ns retracted-by-ns
    :all-unmapped all-unmapped
    :all-upserts (vec (vals (reduce (fn [m f] (assoc m (:seon.code.form/key f) f)) {} (concat unloaded-forms rebind-forms upserts))))}))

(defn- refresh-warm-user-helpers!
  {:seon.flow/edge true}
  [upserted-keys]
  (when (and (find-ns 'user) (ns-resolve (find-ns 'user) 'ctx))
    (when (contains? upserted-keys "seon.repl/install-repl-helpers!")
      (try (when-let [f (resolve 'seon.repl/install-repl-helpers!)] (f (find-ns 'user))) (catch Throwable _ nil)))
    (when (contains? upserted-keys "seon.code.refactor/install-user-helpers!")
      (try (when-let [f (resolve 'seon.code.refactor/install-user-helpers!)]
             (f #(some-> (resolve 'seon.fork.calls/*agent-ctx*) deref) (resolve 'seon.ctx/refresh-active!)))
           (catch Throwable _ nil)))))

(defn apply-overlay!
  "Applies a branch/merge delta `{:upserts [...] :retracted-keys #{...}}` live into the current
   Chez/Jolt runtime. Automatically unmaps retracted vars AND rebinds forms in both retracted
   namespaces and any namespace whose `#ns` form updated (preventing stale `:refer` closures)."
  {:malli/schema [:function
                  [:=> [:cat :map] [:vector :map]]
                  [:=> [:cat :any :map] [:vector :map]]
                  [:=> [:cat :any :map :boolean] [:vector :map]]
                  [:=> [:cat :any :map :boolean [:maybe :seon.host/fn]] [:vector :map]]]}
  ([overlay] (apply-overlay! nil overlay false nil))
  ([db delta] (apply-overlay! db delta false nil))
  ([db delta tolerant?] (apply-overlay! db delta tolerant? nil))
  ([db {:keys [schema-upserts retracted-schemas] :as delta} tolerant? forms-for-ns]
   (let [{:keys [retracted-nss retracted-by-ns all-unmapped all-upserts]} (collect-overlay-upserts db delta forms-for-ns)
         touched-nss (volatile! #{})
         errors (volatile! [])]
     (doseq [{:keys [:seon.code.form/key :seon.code.form/ns :seon.code.form/src]} (sort-forms-topologically all-upserts)]
       (let [load-one (fn []
                        (let [ns-obj (create-ns ns)]
                          (when (str/ends-with? (str key) "#ns")
                          (doseq [[al _] (ns-aliases ns-obj)] (ns-unalias ns-obj al))
                          (when (seq all-unmapped)
                            (doseq [vname all-unmapped
                                    :let [vsym (symbol vname)
                                          v (get (ns-map ns-obj) vsym)]
                                    :when (and (var? v)
                                               (let [def-ns (ns-name (:ns (meta v)))]
                                                 (and (contains? retracted-by-ns def-ns)
                                                      (or (not= def-ns ns)
                                                          (and (str/includes? src ":refer")
                                                               (str/includes? src vname))))))]
                              (ns-unmap ns-obj vsym))))
                          (binding [*ns* ns-obj]
                            (refer-clojure)
                            (let [rdr (java.io.PushbackReader. (java.io.StringReader. src))]
                              (loop []
                                (let [form (read {:eof ::eof} rdr)]
                                  (when-not (= ::eof form) (eval form) (recur))))))
                          (register-loaded-ns! ns)
                          (vswap! touched-nss conj ns)))]
         (if tolerant?
           (try (load-one) (catch Throwable ex (vswap! errors conj {:key key :error (or (.getMessage ex) (str ex))})))
           (load-one))))
     (let [upserted-keys (into #{} (map :seon.code.form/key) all-upserts)]
       (doseq [[nsym vnames] retracted-by-ns
               :let [ns-obj (find-ns nsym)]
               :when ns-obj
               vname vnames
               :when (not (contains? upserted-keys (str (name nsym) "/" vname)))
               :let [vsym (symbol vname)
                     v (get (ns-map ns-obj) vsym)]
               :when (and (var? v) (= nsym (ns-name (:ns (meta v)))))]
         (ns-unmap ns-obj vsym)))
     (doseq [old-ns retracted-nss]
       (when (and (not (contains? @touched-nss old-ns)) (find-ns old-ns)) (remove-ns old-ns)))
     (let [arm-db (when-not (seq schema/*base-forms-override*) db)
         upserted-keys (into #{} (map :seon.code.form/key) all-upserts)
         changed-schemas (into (set retracted-schemas) (keep :seon.schema/key) schema-upserts)
         arm-changed? (or (contains? upserted-keys "seon.schema/arm-var!")
                          (contains? upserted-keys "seon.schema/report-contract-violation!"))]
     (doseq [nsym @touched-nss :let [ns-obj (find-ns nsym)] :when ns-obj [_ v] (ns-interns ns-obj)
             :let [m (meta v)]
             :when (and (not (:private m)) (or (not (:seon/instrumented? m)) (seq changed-schemas) arm-changed?) (:malli/schema m))]
       (if tolerant?
         (try (schema/arm-var! arm-db v)
              (catch Throwable ex (vswap! errors conj {:key (symbol (name nsym) (str (:name m))) :error (or (.getMessage ex) (str ex))})))
         (schema/arm-var! arm-db v)))
     (when (or (seq changed-schemas) arm-changed?)
       (doseq [ns-obj (all-ns) :let [nsym (ns-name ns-obj)]
               :when (and (not (contains? @touched-nss nsym)) (str/starts-with? (name nsym) "seon."))
               [_ v] (ns-interns ns-obj)
               :let [m (meta v) sch (:malli/schema m)]
               :when (and (:seon/instrumented? m) (vector? sch)
                          (or arm-changed? (some changed-schemas (schema/schema-refs-in-form sch))))]
         (schema/arm-var! arm-db v))))
     (do (refresh-warm-user-helpers! (into #{} (map :seon.code.form/key) all-upserts)) @errors))))

(defn ^:seon.flow/edge rebind-spliced-callers!
  "Walks forward up to 3 hops from `root-sym` via `:seon.code.var/calls` and re-evaluates
   only those loaded AOT-compiled callers whose callee is an inline-eligible overlaid var."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db]] :int]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :symbol]] :int]]}
  ([db] (rebind-spliced-callers! db nil))
  ([db root-sym]
   (if-not (and db root-sym (contains? (:schema db) :seon.code.var/calls))
     0
     (let [skip-nss #{'seon.main 'seon.fork 'seon.switchover}
           var-meta-of (fn [s]
                         (when-let [no (and (qualified-symbol? s) (find-ns (symbol (namespace s))))]
                           (when-let [v (ns-resolve no (symbol (name s)))]
                             (when (var? v) (meta v)))))
           overlaid? (fn [m] (and (map? m) (nil? (:file m)) (= 1 (:line m))))
           single-arity? (fn [m]
                           (let [al (:arglists m)]
                             (and (map? m)
                                  (or (:macro m)
                                      (and (= 1 (count al)) (not (some #{'&} (first al))))))))
           edges (loop [frontier [root-sym] visited #{root-sym} depth 0 acc {}]
                   (if (or (empty? frontier) (>= depth 3))
                     acc
                     (let [[next-f acc']
                           (reduce (fn [[nf a] s]
                                     (if-let [veid (:e (first (d/datoms db :avet :seon.code.var/name s)))]
                                       (let [callees (vec (keep #(:v (first (d/datoms db :eavt (:v %) :seon.code.var/name)))
                                                                (d/datoms db :eavt veid :seon.code.var/calls)))]
                                         [(into nf (remove visited callees)) (assoc a s callees)])
                                       [nf a]))
                                   [[] acc] frontier)]
                       (recur next-f (into visited next-f) (inc depth) acc'))))
           all-nodes (into (set (keys edges)) (mapcat val edges))
           ;; Only propagate through inline-eligible (single-fixed-arity) callees!
           seed-overlaid (into #{} (filter #(let [m (var-meta-of %)] (and (overlaid? m) (single-arity? m)))) all-nodes)
           dirty (loop [to-rebind #{} propagators seed-overlaid i 0]
                   (if (or (empty? propagators) (>= i 3))
                     to-rebind
                     (let [newly (into #{} (for [[caller callees] edges
                                                 :when (and (not (contains? to-rebind caller))
                                                            (some propagators callees))]
                                             caller))
                           next-prop (into #{} (filter #(single-arity? (var-meta-of %))) newly)]
                       (recur (into to-rebind newly) next-prop (inc i)))))
           form-pull '[:seon.code.form/key :seon.code.form/ns :seon.code.form/order
                       :seon.code.form/src :seon.code.form/hash :seon.code.form/deps
                       :seon.code.form/test?]
           to-rebind-forms (vec (keep (fn [s]
                                        (let [m (var-meta-of s)]
                                          (when (and m (not (overlaid? m)))
                                            (when-let [veid (:e (first (d/datoms db :avet :seon.code.var/name s)))]
                                              (when-let [fk (:v (first (d/datoms db :eavt veid :seon.code.var/form)))]
                                                (let [f (dissoc (d/pull db form-pull [:seon.code.form/key fk]) :db/id)
                                                      src (str (:seon.code.form/src f))]
                                                  (when (and (seq src)
                                                             (not (contains? skip-nss (:seon.code.form/ns f)))
                                                             (not (str/starts-with? (str/trim src) "(defonce ")))
                                                    f)))))))
                                      dirty))]
       (when (seq to-rebind-forms)
         (apply-overlay! db {:upserts to-rebind-forms :retracted-keys #{}} true))
       (count to-rebind-forms)))))

(defn load-delta!
  {:malli/schema [:function [:=> [:cat :seon.host/conn :any :any] :any] [:=> [:cat :seon.host/conn :any :any :boolean] :any]], :seon.flow/edge true}
  ([conn from-cid to-cid] (load-delta! conn from-cid to-cid false))
  ([conn from-cid to-cid tolerant?]
   (let [delta (changed-form-ops conn from-cid to-cid)
         to-db (or (try (d/commit-as-db conn to-cid) (catch Throwable _ nil)) @conn)
         main-db (try (store/head conn :main) (catch Throwable _ nil))
         main-cid (when main-db (d/commit-id main-db))
         to-real-cid (try (d/commit-id to-db) (catch Throwable _ nil))
         base-cid (when (and main-cid to-real-cid (not= main-cid to-real-cid))
                    (try (d/merge-base conn main-cid to-real-cid) (catch Throwable _ nil)))
         stale-branch? (and base-cid main-cid (not= base-cid main-cid))
         base-db-d (when stale-branch? (delay (d/commit-as-db conn base-cid)))
         forms-fn (when stale-branch? #(candidate-ns-forms @base-db-d main-db to-db %))
         ov-db (if stale-branch? main-db to-db)
         exp-delta (if (or (seq (:upserts delta)) (seq (:retracted-keys delta)))
                     (expand-overlay-rebinds to-db delta)
                     delta)
         run-ov! (fn [] (apply-overlay! ov-db exp-delta (boolean tolerant?) forms-fn))
         errs (if (and stale-branch?
                       (or (seq (:upserts delta)) (seq (:retracted-keys delta)))
                       (not (seq schema/*base-forms-override*)))
                (let [sch (merge-3way-map (schema/db-schema-forms @base-db-d)
                                          (schema/db-schema-forms main-db)
                                          (schema/db-schema-forms to-db))]
                  (binding [schema/*base-forms-override* (when (seq sch) sch)]
                    (run-ov!)))
                (run-ov!))]
     {:loaded (mapv :seon.code.form/key (:upserts delta))
      :unmapped (vec (:retracted-keys delta))
      :errors errs
      :boot-affected? (:boot-affected? delta)})))