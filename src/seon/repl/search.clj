(ns seon.repl.search
  "Extracted child namespace of `seon.repl`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.store :as store]))

(def ^:private grep-src-cache (store/lru-cache 8))

(defn- cached-form-index [bdb]
  (let [ck [(get-in bdb [:config :store :id]) (get-in bdb [:config :branch] :main) (:max-tx bdb)]]
    (store/lru-get! grep-src-cache 8 ck
      (fn []
        (let [by-e (volatile! {}) out (volatile! [])]
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/key)] (vswap! by-e assoc (:e dt) {:key (str (:v dt))}))
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/ns)]
            (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :ns] (name (:v dt)))))
          (doseq [dt (d/datoms bdb :aevt :seon.code.form/src)
                  :let [info (get @by-e (:e dt))] :when info
                  :let [src (str (:v dt)) fk (:key info)]]
            (vswap! out conj {:key fk :sym (try (symbol fk) (catch Throwable _ nil))
                              :ns (or (:ns info) (first (str/split fk #"/|#"))) :src src :low (str/lower-case src)}))
          @out)))))

(defn- grep-schema-hits [bdb ns-pref compiled multi? match-low? rem-lim]
  (if-not (and (pos? rem-lim) (contains? (:schema bdb) :seon.schema/key)
               (some #(str/includes? (str/lower-case (:raw %)) "seon.") compiled))
    []
    (let [by-e (volatile! {}) sm (:schema bdb) out (volatile! [])]
      (doseq [dt (d/datoms bdb :aevt :seon.schema/key)] (vswap! by-e assoc (:e dt) {:key (:v dt)}))
      (when (:seon.schema/form sm)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/form)]
          (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :form] (pr-str (:v dt))))))
      (when (:seon.schema/doc sm)
        (doseq [dt (d/datoms bdb :aevt :seon.schema/doc)]
          (when (contains? @by-e (:e dt)) (vswap! by-e assoc-in [(:e dt) :doc] (str (:v dt))))))
      (doseq [{:keys [key form doc]} (sort-by #(str (:key %)) (vals @by-e))
              :while (< (count @out) rem-lim)
              :let [kns (when (qualified-keyword? key) (namespace key))]
              :when (or (nil? ns-pref) (= kns ns-pref) (and kns (str/starts-with? kns (str ns-pref "."))))
              :let [txt (str key " " (or form "") (when (and doc (not (str/blank? doc))) (str " — " doc)))
                    hit-p (some #(when (match-low? % txt (str/lower-case txt)) (:raw %)) compiled)]
              :when hit-p]
        (vswap! out conj (cond-> {:key (str key) :schema key :ns "seon.schema" :line 1
                                  :text (if (> (count txt) 140) (str (subs txt 0 140) "…") txt)}
                           multi? (assoc :pattern hit-p))))
      @out)))

(defn grep-src
  "Cached single- or multi-pattern grep across forms (falls back to `:seon.schema/*` when pattern mentions `seon.`). Accepts `{:ns 'ns :limit 30}` or a bare namespace symbol as 2nd arg."
  {:malli/schema [:function [:=> [:cat :any] [:maybe [:vector :map]]] [:=> [:cat :any :any] [:maybe [:vector :map]]]]}
  ([pattern] (grep-src pattern {}))
  ([pattern opts-or-ns]
   (let [{:keys [ns limit] :or {limit 30}} (if (map? opts-or-ns) opts-or-ns {:ns opts-or-ns})]
     (when-let [{:keys [conn]} *agent-ctx*]
       (let [bdb @conn ns-pref (when ns (if (string? ns) ns (name ns)))
             multi? (and (coll? pattern) (not (map? pattern)))
             compiled (mapv #(if (instance? java.util.regex.Pattern %) {:raw (str %) :re? true :re %} {:raw (str %) :re? false :low (str/lower-case (str %))})
                            (if multi? (vec pattern) [pattern]))
             mlow? (fn [{:keys [re? re low]} ^String s ^String ls] (if re? (boolean (re-find re s)) (str/includes? ls low)))
             mline? (fn [{:keys [re? re low]} ^String l] (if re? (boolean (re-find re l)) (str/includes? (str/lower-case l) low)))
             hits (volatile! [])]
         (doseq [{:keys [key sym src low] :as e} (cached-form-index bdb)
                 :while (< (count @hits) limit) :let [ens (:ns e)]
                 :when (or (nil? ns-pref) (= ens ns-pref) (str/starts-with? (str ens) (str ns-pref ".")))
                 :let [mp (filterv #(mlow? % src low) compiled)] :when (seq mp)]
           (doseq [[i line] (map-indexed vector (str/split-lines src))
                   :while (< (count @hits) limit)
                   :let [hp (some #(when (mline? % line) (:raw %)) mp)] :when hp
                   :let [t (str/trim line)]]
             (vswap! hits conj (cond-> {:key key :sym sym :ns ens :line (inc i)
                                        :text (if (> (count t) 120) (str (subs t 0 120) "…") t)}
                                 multi? (assoc :pattern hp)))))
         (into @hits (grep-schema-hits bdb ns-pref compiled multi? mlow? (- (long limit) (count @hits)))))))))

(defn- search-db-hits [db q]
  (let [sm (:schema db) vs (volatile! []) ss (volatile! []) ds (volatile! [])
        hit? #(str/includes? (str/lower-case (str %)) q)
        e-v (fn [e a] (:v (first (d/datoms db :eavt e a))))]
    (when (:seon.code.var/name sm) (doseq [dt (d/datoms db :aevt :seon.code.var/name)] (when (hit? (:v dt)) (vswap! vs conj (:v dt)))))
    (when (:seon.code.var/doc sm) (doseq [dt (d/datoms db :aevt :seon.code.var/doc)] (when (hit? (:v dt)) (when-let [v (e-v (:e dt) :seon.code.var/name)] (vswap! vs conj v)))))
    (when (:seon.schema/key sm) (doseq [dt (d/datoms db :aevt :seon.schema/key)] (when (hit? (:v dt)) (vswap! ss conj (:v dt)))))
    (when (:seon.schema/doc sm) (doseq [dt (d/datoms db :aevt :seon.schema/doc)] (when (hit? (:v dt)) (when-let [k (e-v (:e dt) :seon.schema/key)] (vswap! ss conj k)))))
    (when (:seon.schema/form sm) (doseq [dt (d/datoms db :aevt :seon.schema/form)] (when (hit? (pr-str (:v dt))) (when-let [k (e-v (:e dt) :seon.schema/key)] (vswap! ss conj k)))))
    (when (:seon.code.doc/id sm) (doseq [dt (d/datoms db :aevt :seon.code.doc/id)] (when (hit? (:v dt)) (vswap! ds conj (:v dt)))))
    {:vars @vs :schemas @ss :docs @ds}))

(defn search
  "Searches vars (name + doc), schemas (key + doc + form), and docs by case-insensitive substring. Optional 2nd arg filters to a namespace symbol (or `{:ns 'seon.foo}`)."
  {:malli/schema [:function [:=> [:cat :any] [:maybe :map]] [:=> [:cat :any :any] [:maybe :map]]]}
  ([substr] (search substr nil))
  ([substr scope-or-opts]
   (when-let [{:keys [conn branch]} *agent-ctx*]
     (let [bdb @conn
           mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))
           q (str/lower-case (str substr))
           raw-ns (if (map? scope-or-opts) (:ns scope-or-opts) scope-or-opts)
           ns-pref (when raw-ns (if (string? raw-ns) raw-ns (name raw-ns)))
           in-ns? (fn [x]
                    (if-not ns-pref
                      true
                      (let [xs (cond (qualified-symbol? x) (namespace x)
                                     (qualified-keyword? x) (namespace x)
                                     :else (str x))]
                        (and xs (or (= xs ns-pref) (str/starts-with? xs (str ns-pref ".")))))))
           h1 (search-db-hits mdb q)
           h2 (when (not= (d/commit-id bdb) (d/commit-id mdb)) (search-db-hits bdb q))]
       {:vars (->> (concat (:vars h1) (:vars h2)) (filter in-ns?) distinct sort (take 25) vec)
        :schemas (->> (concat (:schemas h1) (:schemas h2)) (filter in-ns?) distinct sort (take 20) vec)
        :docs (->> (concat (:docs h1) (:docs h2)) distinct sort (take 20) vec)}))))