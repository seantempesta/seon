(ns seon.indexing
  "Compile-time enumeration of the CLJS build's program-graph surface.

   Self-host CLJS has no runtime `resolve` (it's a compile-time macro), so
   `seon.client/core-vars` must be a list of `#'`-literals. Hand-listing
   hundreds of vars is the dumbass move — these macros emit the var list
   FROM THE ANALYZER ENV at compile time instead (unit #23 fix b: the whole
   CLJS package surface, not a 7-fn curated sliver).

   Visibility rule: a macro sees only the namespaces compiled BEFORE its
   expansion site, so each macro restricts itself to the CALLING ns's
   transitive require closure. That is exactly the set that is (a) already
   analyzed, (b) loaded before the caller at runtime, and (c) warning-free
   to reference by fully-qualified symbol.

     - `public-fn-vars` in seon.client → every public fn the pod
       actually loads (specced or not).

   Freshness: shadow recompiles a ns when anything in its dependency
   closure changes, so the expansion stays current for code edits. The one
   stale case is a brand-new ns not yet required anywhere — which by
   definition isn't in the build either."
  (:require [cljs.env :as env]
            [cljs.util]
            [clojure.java.io :as io]))

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

(defn- first-party-roots
  "Roots a def's `:file` may live under to count as first-party: the
   repo root (the macroexpanding JVM's working dir) plus, when the
   `SEON_EXTRA_SRC` env var is set (task #36 — downstream consumers add
   their own CLJS source root via a `:local/root` dep injected by
   bin/seon), that extra root. Compile-time JVM-side, so `getenv` is
   trivially available; unset env = exactly today's single root."
  []
  (let [extra (System/getenv "SEON_EXTRA_SRC")]
    (cond-> [(System/getProperty "user.dir")]
      (and extra (not= extra "")) (conj extra))))

(defn- first-party-file?
  "STRUCTURAL first/third-party boundary (V3-C, 2026-06-10): a def is
   FIRST-PARTY iff its analyzer `:file` resolves to a file under one of
   [[first-party-roots]] — the repo root (`src/`, `test/`, etc.) or the
   `SEON_EXTRA_SRC` downstream root when that env var is set.
   Third-party code arrives from jars (`jar:` resource URLs) or
   gitlibs checkouts (file URLs OUTSIDE all roots) and is excluded.
   Replaces the `seon.*`/`my.*` name-prefix predicate (`indexed-ns?`):
   the indexer KNOWS the file path, so the name no longer has to carry
   the classification."
  [file]
  (boolean
    (when (and file (string? file))
      (let [roots (first-party-roots)]
        (if (.startsWith ^String file "/")
          (some #(.startsWith ^String file ^String %) roots)
          (when-let [url (io/resource file)]
            (and (= "file" (.getProtocol url))
                 (some #(.startsWith (.getPath url) ^String %) roots))))))))

(defmacro public-fn-vars
  "Expand to a vector of `#'`-literals: EVERY public fn var — specced or
   not — across every first-party namespace (by [[first-party-file?]]) in
   the calling ns's transitive require closure. Sorted by symbol for
   deterministic output.

   No `:malli/schema` gate: the program graph indexes the WHOLE public
   first-party surface (owner directive — 'just index everything'). Whether
   a fn carries a spec is recorded separately on its `:seon.fn/spec` row by
   the consumer ([[seon.client/var->fn-row]]), orthogonal to whether it is
   indexed — an unspecced fn is simply indexed without a spec."
  []
  (let [nss   (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        syms  (sort
                (for [n     reach
                      [_ d] (:defs (get nss n))
                      :let  [m (def-meta d)]
                      :when (and (:fn-var m)
                                 (not (:private m))
                                 (first-party-file? (:file m))
                                 (:line m))]
                  (:name d)))]
    `[~@(map (fn [s] (list 'var s)) syms)]))

(defmacro first-party-ns-strs
  "Expand to a sorted vector of ns NAME STRINGS: every FIRST-PARTY
   namespace in the calling ns's transitive require closure — the
   BUILD-DERIVED compiled-ns set. First-party by the same structural
   boundary as [[public-fn-vars]] ([[first-party-file?]]): a ns joins iff
   its source lives under a first-party root — checked via any def's
   analyzer `:file` (the common case) or, for a ns with NO defs at all
   (a register!-calls-only root), the classpath resource at the ns's own
   path (`cljs.util/ns->source`). Third-party nses (jar/gitlibs sources)
   are excluded by the same check.

   This is the computed replacement for hand-maintained compiled-root
   name sets (the `fn-less-compiled-roots #{\"my.kb\"}` class): a
   compiled first-party ns is in the set BY CONSTRUCTION, whether or not
   it owns a public fn var."
  []
  (let [nss   (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        fp?   (fn [n]
                (or (some #(first-party-file? (:file (def-meta (val %))))
                          (:defs (get nss n)))
                    (when-let [url (cljs.util/ns->source n)]
                      (and (= "file" (.getProtocol url))
                           (let [p (.getPath url)]
                             (boolean (some #(.startsWith p ^String %)
                                            (first-party-roots))))))))]
    `[~@(sort (map str (filter fp? reach)))]))
