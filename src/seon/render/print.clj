(ns seon.render.print
  "Seon's Unified 4-Stage Zero-Clipping Schema Self-Printer, Smart Previews, and `(user/expand! ...)`."
  (:require [seon.render.print.emit :as pemit]
            [seon.render.print.expand :as pexp]
            [seon.render.print.policy :as ppol]
            [seon.render.print.select :as psel]))

(defn preview-rules
  "Declarative `:seon.rule/*` rule definitions governing print policy."
  {:malli/schema [:=> [:cat] [:vector :map]]
   :cost/ticks 500000
   :cost/bytes 131072}
  []
  (ppol/preview-rules))

(defn derive-print-policy
  "Stage 2: Derives O(1) print policy once per top-level print call via `seon.rules/step-rules`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map] [:=> [:cat [:maybe :seon.host/db] [:maybe [:vector :map]]] :map]]
   :cost/ticks 5000000
   :cost/bytes 2097152}
  ([db] (ppol/derive-print-policy db))
  ([db explicit-rules] (ppol/derive-print-policy db explicit-rules)))

(defn select-with-index
  "Pure Stage 3 Selection Tree builder over a precompiled schema index."
  {:malli/schema [:=> [:cat :map :any :map] :map]
   :cost/ticks 10000000
   :cost/bytes 8388608}
  [ix val opts]
  (psel/select-with-index ix val opts))

(defn select-tree
  "Runs Stages 1-3 (recursive sub-schema dispatch + rule policy + breadth-first budgeted tree)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :map] [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  ([db val] (psel/select-tree db val))
  ([db val opts] (psel/select-tree db val opts)))

(defn tree->md
  "Stage 4 Markdown Emitter from a Selection Tree."
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map :map] :string]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  ([tree] (pemit/tree->md tree))
  ([tree opts] (pemit/tree->md tree opts)))

(defn tree->hiccup
  "Stage 4 Hiccup Emitter from a Selection Tree."
  {:malli/schema [:function [:=> [:cat :map] :any] [:=> [:cat :map :map] :any]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  ([tree] (pemit/tree->hiccup tree))
  ([tree opts] (pemit/tree->hiccup tree opts)))

(defn- collect-elisions [node]
  (when (map? node)
    (let [self (when-let [el (:elision node)] [el])
          kids (concat
                (when (sequential? (:entries node)) (mapcat (fn [e] (when (sequential? e) (collect-elisions (second e)))) (:entries node)))
                (when (sequential? (:items node)) (mapcat collect-elisions (:items node))))]
      (vec (concat self kids)))))

(defn- tree->descriptor [tree]
  (let [els (collect-elisions tree)
        eh (or (:expand-hint tree) (:expand-hint (first els)))]
    (cond-> (select-keys tree [:kind :schema :facets :facet-details :element-schema :tabular? :columns
                               :total :exact? :offset :limit :preview :more? :remaining :ambiguous :layout])
      true (assoc :elisions els :tree tree)
      (not (contains? tree :facets)) (assoc :facets [])
      (not (contains? tree :more?)) (assoc :more? false)
      eh (assoc :expand-hint eh))))

(defn describe-with-index
  "Pure structural descriptor over `ix` without requiring a DB handle."
  {:malli/schema [:=> [:cat :map :any :map] :map]
   :cost/ticks 10000000
   :cost/bytes 8388608}
  [ix val opts]
  (tree->descriptor (psel/select-with-index ix val opts)))

(defn describe-value
  "Builds structural descriptor `{:kind :schema :facets :total :preview :more? :expand-hint :elisions :tree}`."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :map] [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  ([db val] (describe-value db val {}))
  ([db val opts] (tree->descriptor (psel/select-tree db val opts))))

(defn format-value-md
  "Renders any value into clean, zero-clipping Markdown/text using the 4-stage printer."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :string] [:=> [:cat [:maybe :seon.host/db] :any :map] :string]]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  ([db val] (format-value-md db val {}))
  ([db val opts]
   (let [nopts (pexp/norm-opts (assoc (if (map? opts) opts {}) :mode :model))]
     (pemit/tree->md (psel/select-tree db val nopts) nopts))))

(defn format-value-hiccup
  "Renders any value into clean Hiccup using the 4-stage printer (delegating nested `:seon.render/hiccup` renderers)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :any] [:=> [:cat [:maybe :seon.host/db] :any :map] :any]]
   :cost/ticks 15000000
   :cost/bytes 8388608}
  ([db val] (format-value-hiccup db val {}))
  ([db val opts]
   (let [nopts (pexp/norm-opts (assoc (if (map? opts) opts {}) :mode :human))]
     (pemit/tree->hiccup (psel/select-tree db val nopts) nopts))))

(defn slice-value
  "Pure path-drill, filter, reverse, and offset/limit slicer over any Clojure value."
  {:malli/schema [:=> [:cat :any :map] :map]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [val opts]
  (pexp/slice-value val opts))

(defn- resolve-result-handle [sym]
  (let [rns (find-ns 'result)
        nm (symbol (name sym))
        vr (when rns (ns-resolve rns nm))]
    (if (and vr (bound? vr)) {:found? true :value @vr :handle sym} {:found? false :handle sym})))

(defn- apply-path-filter-reverse [val {:keys [path key filter reverse?] :as nopts}]
  (let [epath (cond-> path (contains? nopts :key) (conj key))
        sub (if (seq epath) (pexp/drill-path val epath) val)
        filtered (if (or (some? filter) reverse?)
                   (let [sl (pexp/slice-value sub {:filter filter :reverse? reverse? :offset 0 :limit 10000})]
                     (if (contains? sl :preview) (:preview sl) (:value sl)))
                   sub)]
    [epath filtered]))

(defn expand-result
  "Expands a `result/e<n>` handle, a Datahike pattern `[:attr val]`/`[e a v]`, or a direct map/collection."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] :any] :map] [:=> [:cat [:maybe :seon.host/db] :any :map] :map]]
   :cost/ticks 15000000
   :cost/bytes 16777216}
  ([db target] (expand-result db target {}))
  ([db target opts]
   (let [nopts (pexp/norm-opts opts)]
     (cond
       (pexp/db-query-pattern? db target)
       (let [res (pexp/expand-db-pattern db target nopts)
             tree (psel/select-tree db (:preview res) (assoc nopts :offset 0 :limit (count (:preview res))))]
         (assoc res :ok? true :md (pemit/tree->md tree (assoc nopts :max-chars 7500))))

       (and (symbol? target) (= "result" (namespace target)))
       (let [{:keys [found? value]} (resolve-result-handle target)]
         (if-not found?
           {:ok? false :error (str "Result handle " target " not found in `result` namespace.")}
           (let [[epath sub] (apply-path-filter-reverse value nopts)
                 eopts (assoc nopts :handle target :path epath :budget (max 4000 (long (:budget nopts 2000))))
                 desc (describe-value db sub eopts)
                 md (pemit/tree->md (:tree desc) (assoc eopts :max-chars 7500))]
             (assoc desc :ok? true :handle target :path epath :md md))))

       :else
       (let [[epath sub] (apply-path-filter-reverse target nopts)
             eopts (assoc nopts :path epath :budget (max 4000 (long (:budget nopts 2000))))
             desc (describe-value db sub eopts)
             md (pemit/tree->md (:tree desc) (assoc eopts :max-chars 7500))]
         (assoc desc :ok? true :path epath :md md))))))

(defn install-user-helpers!
  "Installs `(user/expand! target)` and `(user/expand! target opts)` into namespace `u`."
  {:malli/schema [:=> [:cat :seon.host/ns fn?] :map]
   :cost/ticks 200000
   :cost/bytes 131072}
  [u get-agent-ctx-fn]
  (let [expand-fn (fn expand!
                    ([target] (expand! target {}))
                    ([target opts]
                     (let [{:keys [db branch-db]} (get-agent-ctx-fn)
                           adb (or branch-db db)
                           res (expand-result adb target (if (map? opts) opts {}))]
                       (if (or (not (:ok? res)) (:raw? opts))
                         res
                         (:md res)))))]
    (intern u (with-meta 'expand!
                {:doc "Expands a `result/e<n>` handle, Datahike pattern `[:attr val]`/`[e a v]`, or collection with `{:offset :limit :key :path :filter :reverse? :raw?}`."
                 :arglists '([target] [target opts])
                 :seon.eval/budget-override {:ticks 20000000 :bytes 33554432}})
            expand-fn)
    {:ok? true :installed ['user/expand!]}))
