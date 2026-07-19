(ns seon.eval.bootstrap-cache
  "Load analysis caches for self-hosted CLJS evaluation.

   Loads every `<bootstrap>/ana/*.transit.json` emitted by the shadow
   `:bootstrap` build into a cljs.js compile-state, so every namespace in
   `shadow-cljs.edn :bootstrap :entries` is resolvable by the self-host
   analyzer without a hand-maintained load-list.

   Why unconditional loading is needed: shadow's `boot/init` only
   auto-loads the analyzer cache for entries whose
   `[:cljs.analyzer/namespaces ns :name]` is nil
   (`bootstrap/node.cljs:104`). `(cljs/empty-state)` calls `(dump-core)`
   which leaves stubs with `:name` set for many nses, so the filter
   short-circuits; loading unconditionally here is the robust answer.

   DELIBERATELY LEAN: requires only cljs.js + the shadow bootstrap
   reader + Node builtins — no seon.db, no seon.schema, no pod state —
   so the standalone worker bundle stays free of the pod cage."
  (:require
    [clojure.string :as str]
    [cljs.js :as cljs]
    [shadow.cljs.bootstrap.node :as boot]
    ["fs" :as fs]
    ["path" :as path]))

(defn cache-files
  "`[ns-sym path]` pairs for `<bootstrap>/ana/*.transit.json` files.

   cljs.core + cljs.core$macros sort first so they land in the analyzer
   state before anything that references them — order doesn't strictly
   matter (load-analysis-cache! is just a swap), but cosmetic ordering
   helps when debugging the compile-state map."
  {:malli/schema [:=> [:catn [::bootstrap-path :string]]
                  [:sequential [:tuple :symbol :string]]]}
  [bootstrap-path]
  (let [ana-dir (.resolve path bootstrap-path "ana")
        names   (.readdirSync fs ana-dir)
        suffix  ".transit.json"]
    (->> (array-seq names)
         (filter #(str/ends-with? % suffix))
         (map (fn [filename]
                (let [ns-name (subs filename 0 (- (count filename) (count suffix)))]
                  [(symbol ns-name) (.resolve path ana-dir filename)])))
         (sort-by (fn [[ns-sym _]]
                    (case (str ns-sym)
                      "cljs.core"        0
                      "cljs.core$macros" 1
                      2))))))

(defn load-all!
  "Load every bootstrap analysis cache into compile-state `state`.

   Reads each [[cache-files]] entry and `cljs.js/load-analysis-cache!`s
   it. Returns the number of caches loaded. `state` is the cljs.js
   compile-state atom (third-party boundary)."
  {:malli/schema [:=> [:catn [::state :any] [::bootstrap-path :string]] :int]}
  [state bootstrap-path]
  (let [pairs (cache-files bootstrap-path)]
    (doseq [[ns-sym file] pairs]
      (let [txt  (.readFileSync fs file "utf8")
            data (boot/transit-read txt)]
        (cljs/load-analysis-cache! state ns-sym data)))
    (count pairs)))
