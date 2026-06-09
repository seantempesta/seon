(ns seon.indexing
  "Compile-time enumeration of the CLJS build's program-graph surface.

   Self-host CLJS has no runtime `resolve` (it's a compile-time macro), so
   `seon.client/substrate-vars` must be a list of `#'`-literals. Hand-listing
   hundreds of vars is the dumbass move — these macros emit the var list
   FROM THE ANALYZER ENV at compile time instead (unit #23 fix b: the whole
   CLJS package surface, not a 7-fn curated sliver).

   Visibility rule: a macro sees only the namespaces compiled BEFORE its
   expansion site, so each macro restricts itself to the CALLING ns's
   transitive require closure. That is exactly the set that is (a) already
   analyzed, (b) loaded before the caller at runtime, and (c) warning-free
   to reference by fully-qualified symbol.

     - `specced-fn-vars` in seon.client → every public specced fn the pod
       actually loads.
     - `deftest-vars` in seon.dev.test-preload → every deftest var the pod
       build pulls in (the preload IS the test-ns roster).

   Freshness: shadow recompiles a ns when anything in its dependency
   closure changes, so the expansion stays current for code edits. The one
   stale case is a brand-new ns not yet required anywhere — which by
   definition isn't in the build either."
  (:require [cljs.env :as env]))

(defn- analyzer-namespaces
  "The CLJS analyzer's namespaces map ({ns-sym ns-map}), or nil outside a
   CLJS compile."
  []
  (some-> env/*compiler* deref :cljs.analyzer/namespaces))

(defn- transitive-requires
  "Set of ns-syms reachable from `root` through `:requires` edges in the
   analyzer namespaces map `nss` (root included)."
  [nss root]
  (loop [seen #{} todo [root]]
    (if (empty? todo)
      seen
      (let [n (peek todo)]
        (if (contains? seen n)
          (recur seen (pop todo))
          (recur (conj seen n)
                 (into (pop todo) (vals (:requires (get nss n))))))))))

(defn- def-meta
  "Merged view of an analyzer def map and its user `:meta` — user metadata
   (`:malli/schema`, `:test`, …) lives under `:meta`; analyzer projections
   (`:private`, `:fn-var`, `:file`, `:line`) sit at the top level."
  [d]
  (merge (:meta d) (select-keys d [:private :fn-var :file :line :test])))

(defn- seon-ns? [ns-sym]
  (let [s (str ns-sym)]
    (or (= s "seon") (.startsWith s "seon."))))

(defmacro specced-fn-vars
  "Expand to a vector of `#'`-literals: every PUBLIC fn var carrying
   `:malli/schema` metadata, across every `seon.*` namespace in the calling
   ns's transitive require closure. Sorted by symbol for deterministic
   output."
  []
  (let [nss   (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        syms  (sort
                (for [n     reach
                      :when (seon-ns? n)
                      [_ d] (:defs (get nss n))
                      :let  [m (def-meta d)]
                      :when (and (:fn-var m)
                                 (not (:private m))
                                 (some? (:malli/schema m))
                                 (:file m)
                                 (:line m))]
                  (:name d)))]
    `[~@(map (fn [s] (list 'var s)) syms)]))

(defmacro deftest-vars
  "Expand to a vector of `#'`-literals: every deftest var (a def carrying
   `:test` metadata) across every `seon.*` namespace in the calling ns's
   transitive require closure. Invoke from `seon.dev.test-preload`, whose
   requires ARE the pod's test-ns roster."
  []
  (let [nss   (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        syms  (sort
                (for [n     reach
                      :when (seon-ns? n)
                      [_ d] (:defs (get nss n))
                      :let  [m (def-meta d)]
                      :when (and (some? (:test m))
                                 (:file m)
                                 (:line m))]
                  (:name d)))]
    `[~@(map (fn [s] (list 'var s)) syms)]))
