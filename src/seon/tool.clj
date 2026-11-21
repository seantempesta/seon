(ns seon.tool
  "Metadata-driven `^:seon/tool` capability discovery & `user/*` auto-installation:
   Any public `defn` anywhere in Seon carrying `{:seon/tool true}` (or `{:seon/tool 'alias}`)
   is automatically discovered via indexed `:seon.code.var/tool?` + live loaded vars,
   interned into `user/*` via live var delegation (preserving `:doc`, `:arglists`,
   `:malli/schema`, and `:seon.eval/budget-override`), and listed in `(user/tools)`."
  (:require [datahike.api :as d]))

(defn tool-spec-from-var
  "Extracts a canonical tool descriptor map from Clojure `Var` `v` when `(meta v)` has `:seon/tool`."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]
   :cost/ticks 20000}
  [v]
  (when (and (var? v) (bound? v))
    (let [m (meta v)
          tv (:seon/tool m)]
      (when tv
        (let [src-sym (symbol v)
              tool-sym (cond
                         (symbol? tv) (symbol (name tv))
                         (keyword? tv) (symbol (name tv))
                         :else (symbol (name src-sym)))]
          (cond-> {:name tool-sym
                   :source src-sym
                   :var v
                   :ctx? (boolean (:seon.tool/ctx? m))
                   :refresh? (boolean (:seon.tool/refresh? m))
                   :category (or (:seon.tool/category m) :general)}
            (string? (:doc m)) (assoc :doc (:doc m))
            (seq (:arglists m)) (assoc :arglists (:arglists m))
            (:malli/schema m) (assoc :malli/schema (:malli/schema m))
            (:seon.eval/budget-override m) (assoc :seon.eval/budget-override (:seon.eval/budget-override m))))))))

(defn discover-tool-vars
  "Discovers all `^:seon/tool` vars across Datahike `db` (via indexed `:seon.code.var/tool?`)
   and currently loaded JVM namespaces, sorted deterministically by tool name."
  {:malli/schema [:function
                  [:=> [:cat] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]]}
  ([] (discover-tool-vars nil))
  ([db]
   (let [db-syms (when (and db (contains? (:schema db) :seon.code.var/tool?))
                   (into #{}
                         (keep (fn [dt]
                                 (:seon.code.var/name
                                  (d/pull db '[:seon.code.var/name] (:e dt)))))
                         (d/datoms db :avet :seon.code.var/tool? true)))
         _ (doseq [sym db-syms]
             (try (requiring-resolve sym) (catch Throwable _ nil)))
         by-name (volatile! {})]
     (doseq [ns-obj (all-ns)
             [_ v] (ns-publics ns-obj)]
       (when-let [spec (tool-spec-from-var v)]
         (vswap! by-name assoc (:name spec) spec)))
     (doseq [sym db-syms]
       (when-let [v (try (resolve sym) (catch Throwable _ nil))]
         (when-let [spec (tool-spec-from-var v)]
           (vswap! by-name assoc (:name spec) spec))))
     (->> (vals @by-name)
          (sort-by (comp str :name))
          vec))))

(defn wrap-tool-fn
  "Builds the live-delegating wrapper function for a `^:seon/tool` descriptor."
  {:malli/schema [:=> [:cat :map :any :any] :seon.host/fn]}
  [{:keys [var ctx? refresh?]} get-agent-ctx-fn refresh-ctx-fn]
  (fn [& args]
    (let [ctx (when (and ctx? get-agent-ctx-fn) (get-agent-ctx-fn))
          res (if ctx?
                (apply @var ctx args)
                (apply @var args))]
      (when (and refresh? refresh-ctx-fn get-agent-ctx-fn)
        (when-let [{:keys [conn]} (get-agent-ctx-fn)]
          (refresh-ctx-fn @conn nil)))
      res)))

(defn install-discovered-tools!
  "Auto-interns every discovered `^:seon/tool` var into namespace `u` (`user`) with live
   delegation, metadata propagation, and a `(user/tools)` catalog helper."
  {:malli/schema [:=> [:cat :seon.host/ns [:maybe :seon.host/db] :any :any] [:vector :symbol]]}
  [u db get-agent-ctx-fn refresh-ctx-fn]
  (let [specs (discover-tool-vars db)
        installed (volatile! [])]
    (doseq [{:keys [name source doc arglists category] :as spec} specs]
      (let [v (intern u name (wrap-tool-fn spec get-agent-ctx-fn refresh-ctx-fn))]
        (alter-meta! v merge
                     (cond-> {:seon.tool/source source
                              :seon.tool/category category}
                       doc (assoc :doc doc)
                       arglists (assoc :arglists arglists)
                       (:malli/schema spec) (assoc :malli/schema (:malli/schema spec))
                       (:seon.eval/budget-override spec)
                       (assoc :seon.eval/budget-override (:seon.eval/budget-override spec))))
        (vswap! installed conj name)))
    (let [tv (intern u 'tools
                     (fn []
                       (let [cur-db (when-let [{:keys [conn]} (when get-agent-ctx-fn (get-agent-ctx-fn))] @conn)]
                         (mapv #(dissoc % :var) (discover-tool-vars cur-db)))))]
      (alter-meta! tv assoc :doc "Lists all metadata-discovered `^:seon/tool` helpers with category, arglists, and doc."))
    @installed))
