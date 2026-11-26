(ns seon.code.persist
  "Extracted child namespace of `seon.code`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [jolt.analyzer]
            [jolt.host]
            [seon.store :as store]
            [seon.code.parse :as parse :refer [boot-root-keys ns->default-path ns-parent-chain-tx read-clj]]
            [seon.code.io :as cio :refer [ns-forms ns-vars retract-stale-many-ops]]
            [seon.code.analyze :as analyze]
            [seon.code.cst.ns :as cst-ns]))

(defn- bare-ns-form?
  "True when `s` is a bare `(ns ...)` header form (no docstring/requires)."
  [s]
  (when (str/starts-with? (str/triml s) "(ns ")
    (let [v (try (read-string s) (catch clojure.lang.ExceptionInfo _ nil))]
      (and (seq? v) (= 'ns (first v)) (<= (count v) 2)))))

(defn- enrich-reparsed-vars
  "Builds enriched Datahike var tx-maps for `reparsed` vars, resolving local refs,
   ns eid, and registry context so `analyze/enrich-var-with-account` can run."
  [db target-ns reparsed ir-extra-refs]
  (let [rvars (:vars reparsed)
        local-var-refs (into {}
                             (map (fn [v]
                                    (let [vs (:seon.code.var/name v)]
                                      [vs (or (when (contains? (:schema db) :seon.code.var/name)
                                                (:e (first (d/datoms db :avet :seon.code.var/name vs))))
                                              (analyze/var-tempid vs))])))
                             rvars)
        ns-eid (when (contains? (:schema db) :seon.code.ns/name)
                 (:e (first (d/datoms db :avet :seon.code.ns/name target-ns))))
        reg-info (when (some :seon.code.var/schema rvars)
                   (store/fast-registry-for-db db))
        bctx (cond-> {:var->ref local-var-refs
                      :assign-tempid? true
                      :var-lookup-cache (volatile! {})
                      :schema-lookup-cache (volatile! {})}
               ns-eid (assoc :ns-eid ns-eid)
               reg-info (assoc :registry (:registry reg-info)
                               :known-schemas (set (keys (:forms reg-info)))))]
    (mapv (fn [v]
            (let [v-extra (get ir-extra-refs (:seon.code.var/name v))
                  acct (if (map? v-extra) v-extra (when (seq v-extra) {:refs v-extra}))]
              (analyze/enrich-var-with-account db v acct boot-root-keys bctx)))
          rvars)))

(defn- build-ns-entry
  "Merges the existing ns entity with the reparsed `:ns` header decl (doc/requires),
   resolving requires-to-refs when the schema is present."
  [db target-ns ns-chain reparsed has-ns-form?]
  (let [existing-ns (let [p (d/pull db '[:seon.code.ns/name :seon.code.ns/path :seon.code.ns/tail]
                                      [:seon.code.ns/name target-ns])]
                      (when (:seon.code.ns/name p) p))
        base (if existing-ns
               (merge (peek ns-chain) existing-ns)
               (assoc (peek ns-chain)
                      :seon.code.ns/path (ns->default-path target-ns)
                      :seon.code.ns/tail "\n"))]
    (cond-> base
      (and (or has-ns-form? (nil? existing-ns)) (:doc reparsed))
      (assoc :seon.code.ns/doc (:doc reparsed))
      (and (or has-ns-form? (nil? existing-ns)) (seq (:requires reparsed)))
      ((fn [m]
         (let [rr (when (contains? (:schema db) :seon.code.ns/requires-ns)
                    (analyze/resolve-ns-requires-refs db (:requires reparsed)))]
           (cond-> (assoc m :seon.code.ns/requires (:requires reparsed))
             (seq rr) (assoc :seon.code.ns/requires-ns rr))))))))

(defn- assign-form-orders
  "Assigns `:seon.code.form/{order,lead}` to `kept-forms`, reusing existing order/lead
   for unchanged keys and slotting new forms after `max-ord`."
  [existing by-key max-ord kept-forms]
  (let [[_ txs]
        (reduce
         (fn [[next-ord acc] f]
           (let [is-ns-hdr? (str/ends-with? (:seon.code.form/key f "") "#ns")]
             (if-let [prev (get by-key (:seon.code.form/key f))]
               [next-ord (conj acc (assoc f
                                          :seon.code.form/order (:seon.code.form/order prev)
                                          :seon.code.form/lead (:seon.code.form/lead prev)))]
               (if (and is-ns-hdr? (seq existing))
                 (let [min-ord (reduce min 0 (map :seon.code.form/order existing))]
                   [next-ord (conj acc (assoc f
                                              :seon.code.form/order (dec min-ord)
                                              :seon.code.form/lead ""))])
                 [(inc next-ord) (conj acc (assoc f
                                                  :seon.code.form/order next-ord
                                                  :seon.code.form/lead (if (zero? next-ord) "" "\n\n")))]))))
         [(inc max-ord) []]
         kept-forms)]
    txs))

(defn- reorder-form-txs
  "Returns `raw-form-txs` topologically ordered via unified `cst-ns/topo-order-ns-forms` so same-ns
   callees always precede callers (including multi-hop chains and newly resolved forward refs),
   emitting `:seon.code.form/order` fixes for moved existing forms."
  [by-key raw-form-txs]
  (let [tx-by-k (into {} (map (juxt :seon.code.form/key identity)) raw-form-txs)
        new-syms (into [] (keep (fn [k]
                                  (when (and (not (contains? by-key k)) (string? k) (str/includes? k "/") (not (str/includes? k "#")))
                                    (symbol k))))
                       (keys tx-by-k))
        enriched (mapv (fn [f]
                         (if (or (empty? new-syms) (contains? tx-by-k (:seon.code.form/key f)))
                           f
                           (let [src (str (:seon.code.form/src f))
                                 cur-deps (set (:seon.code.form/deps f))
                                 extra (keep (fn [s]
                                               (let [nm (name s)]
                                                 (when (and (not (contains? cur-deps s))
                                                            (str/includes? src nm)
                                                            (re-find (re-pattern (str "(?<![A-Za-z0-9_?!*/.-])" (java.util.regex.Pattern/quote nm) "(?![A-Za-z0-9_?!*/.-])")) src))
                                                   s)))
                                             new-syms)]
                             (if (seq extra) (update f :seon.code.form/deps #(vec (distinct (concat % extra)))) f))))
                       (vals (merge by-key tx-by-k)))
        ord-updates (cst-ns/topo-order-ns-forms enriched)]
    (if (empty? ord-updates)
      raw-form-txs
      (let [ord-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/order)) ord-updates)
            updated-txs (mapv (fn [f]
                                (if-let [no (get ord-by-k (:seon.code.form/key f))]
                                  (assoc f :seon.code.form/order no)
                                  f))
                              raw-form-txs)
            moved-existing (filterv #(not (contains? tx-by-k (:seon.code.form/key %))) ord-updates)]
        (into updated-txs moved-existing)))))

(defn persist-forms-by-ns
  "Persists newly defined or updated top-level Clojure forms `{nsym [src ...]}` into Datahike tx-data with topo reordering, in-place named-expr replacement, and ns-header sync."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :any]]}
  [db ns-entries ir-refs-by-var]
  (vec
   (mapcat
    (fn [[target-ns srcs]]
      (let [existing (ns-forms db target-ns)
            existing-ns-src (some #(when (= (:seon.code.form/key %) (str target-ns "#ns")) (:seon.code.form/src %)) existing)
            srcs (vec (mapcat #(if-let [[nh nb] (cst-ns/split-leading-ns-src %)] [nh nb] [%])
                              (if existing-ns-src (remove bare-ns-form? srcs) srcs)))
            raw-ns-idx (first (keep-indexed #(when (str/starts-with? (str/triml %2) "(ns ") %1) srcs))
            raw-ns? (some? raw-ns-idx)
            body-srcs (if raw-ns? (vec (remove #(str/starts-with? (str/triml %) "(ns ") srcs)) srcs)
            auto-ns (if raw-ns?
                      (when (seq body-srcs)
                        (cst-ns/sync-ns-requires-for-live-ns target-ns (nth srcs raw-ns-idx) body-srcs))
                      (cst-ns/sync-ns-requires-for-live-ns target-ns existing-ns-src srcs))
            srcs (cond
                   (and raw-ns? auto-ns) (assoc srcs raw-ns-idx auto-ns)
                   auto-ns (into [auto-ns] srcs)
                   :else srcs)
            has-ns-form? (or raw-ns? (some? auto-ns))
            existing-local-defs (into #{} (map #(symbol (name (:seon.code.var/name %)))) (ns-vars db target-ns))
            header (cond has-ns-form? "" existing-ns-src (str existing-ns-src "\n") :else (str "(ns " target-ns ")\n"))
            reparsed (read-clj (ns->default-path target-ns) (str header (str/join "\n" srcs)) existing-local-defs)
            kept-forms (if has-ns-form? (:forms reparsed) (vec (rest (:forms reparsed))))
            kept-vars (enrich-reparsed-vars db target-ns reparsed ir-refs-by-var)
            {:keys [by-key retracts]} (cio/reconcile-named-exprs existing kept-forms)
            max-ord (reduce max -1 (map :seon.code.form/order existing))
            ns-chain (ns-parent-chain-tx target-ns)
            ns-ent (build-ns-entry db target-ns ns-chain reparsed has-ns-form?)
            stale-ns-reqs (when has-ns-form? (retract-stale-many-ops db :seon.code.ns/name [:seon.code.ns/requires :seon.code.ns/requires-ns] [ns-ent]))
            form-txs (reorder-form-txs by-key (assign-form-orders existing by-key max-ord kept-forms))
            stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] (filter :seon.code.form/src form-txs))
            stale-var-refs (analyze/stale-var-edge-retracts db kept-vars)]
        (vec (concat retracts stale-ns-reqs stale-form-deps stale-var-refs (pop ns-chain) [ns-ent] form-txs kept-vars))))
    ns-entries)))
