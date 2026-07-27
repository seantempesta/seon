(ns seon.client.indexing
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
            [seon.dev.program-inventory :as program-inventory]))

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

(defmacro public-fn-vars
  "Expand to a vector of `#'`-literals: EVERY public fn var — specced or
   not — across every first-party namespace (by
   [[seon.dev.program-inventory/first-party-file?]]) in the calling ns's
   transitive require closure. Sorted by symbol for deterministic output.

   No `:malli/schema` gate: the program graph indexes the WHOLE public
   first-party surface (owner directive — 'just index everything'). Whether
   a fn carries a spec is recorded separately on its `:seon.fn/spec` row by
   the consumer ([[seon.client/var->fn-row]]), orthogonal to whether it is
   indexed — an unspecced fn is simply indexed without a spec."
  []
  (let [nss   (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        syms  (map symbol
                   (:seon.dev.program-inventory/public-exports
                    (program-inventory/analyzer-fn-inventory nss reach)))]
    `[~@(map (fn [s] (list 'var s)) syms)]))

(defmacro first-party-fn-vars
  "Expand to every first-party function var, preserving private helpers.

   R39 publishes private helpers as presence-marked corpus rows. Dependency
   functions remain build terminals and never enter the public corpus."
  []
  (let [nss (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        inventory (program-inventory/analyzer-fn-inventory nss reach)
        syms (map symbol
                  (concat
                   (:seon.dev.program-inventory/public-exports inventory)
                   (:seon.dev.program-inventory/first-party-private inventory)))]
    `[~@(map (fn [s] (list 'var s)) syms)]))

(defmacro first-party-ns-strs
  "Expand to a sorted vector of ns NAME STRINGS: every FIRST-PARTY
   namespace in the calling ns's transitive require closure — the
   BUILD-DERIVED compiled-ns set. First-party by the same structural
   boundary as [[public-fn-vars]]
   ([[seon.dev.program-inventory/first-party-file?]]): a ns joins iff its
   source lives under a first-party root — checked via any def's analyzer
   `:file` (the common case) or, for a ns with NO defs at all (a
   register!-calls-only root), the classpath resource at the ns's own path
   (`cljs.util/ns->source`). Third-party nses (jar/gitlibs sources) are
   excluded by the same check.

   This is the computed replacement for hand-maintained compiled-root
   name sets (the `fn-less-compiled-roots #{\"my.kb\"}` class): a
   compiled first-party ns is in the set BY CONSTRUCTION, whether or not
   it owns a public fn var."
  []
  (let [nss   (analyzer-namespaces)
        reach (transitive-requires nss (-> &env :ns :name))
        fp?   (fn [n]
                (or (some #(program-inventory/first-party-definition?
                            (val %))
                          (:defs (get nss n)))
                    (when-let [url (cljs.util/ns->source n)]
                      (and (= "file" (.getProtocol url))
                           (let [p (.getPath url)]
                             (boolean (some #(.startsWith p ^String %)
                                            (program-inventory/first-party-roots))))))))]
    `[~@(sort (map str (filter fp? reach)))]))
