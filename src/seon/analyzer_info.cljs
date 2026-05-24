(ns seon.analyzer-info
  "Read-side wrapper over the bootstrap-CLJS analyzer state in
   `@compile-state`. One module so 'how we read the analyzer' lives
   in one place. Three consumers:

   - Phase B item 10: detect-and-tee in `seon.eval/eval-batch!` —
     diffs `snapshot-defs` before/after each form, calls
     `defs-since` + `var-projection` to build `:seon.fn` entities.
   - Phase C item 11: `seed-from-source!` — walks the analyzer's
     known seon.* nses at boot.
   - Phase D item 15: bulk-load resume — uses `ns-deps` for
     topo-sort.

   `compile-state` is the CLJS-bootstrap inner atom (the one cljs.js
   threads through `eval-str`). The seon process holds it boxed in
   `seon.repl/!compile-state` (atom-of-atom); callers deref the
   outer atom once and pass the inner here.

   Implementation note (REPL-verified 2026-05-24): self-host CLJS
   does NOT expose `cljs.analyzer.api/find-ns` or `ns-resolve` —
   both throw `TypeError: undefined`. We read
   `(:cljs.analyzer/namespaces @compile-state)` directly. The
   research note's reference impl is wrong on that point."
  (:require [clojure.set :as set]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

;; compile-state is an atom wrapping the analyzer's massive state
;; map; no useful Malli shape, and consumers pass it opaquely.
(schema/register! ::compile-state :any)

;; {ns-sym → #{def-sym ...}}
(schema/register! ::defs-snapshot [:map-of :symbol [:set :symbol]])

;; Output of `defs-since` — one entry per newly-added def.
(schema/register! ::new-def
                  [:map
                   [:ns :symbol]
                   [:sym :symbol]
                   [:var-map [:map-of :any :any]]])

;; Output of `var-projection`. Matches `:seon.fn/*` attrs per v1.md §2.2.
(schema/register! ::var-projection
                  [:map
                   [:sym :string]
                   [:fn-var? :boolean]
                   [:arglists :string]
                   [:doc :string]
                   [:private? :boolean]
                   [:specced? :boolean]])

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn snapshot-defs
  "Snapshot of `{ns-sym → #{def-syms}}` in the analyzer's current
   `:cljs.analyzer/namespaces` map. Pure read of `@compile-state`.
   Cheap — no walking of var-maps, just keyset extraction."
  {:malli/schema [:=> [:cat ::compile-state] ::defs-snapshot]}
  [compile-state]
  (into {}
        (for [[ns-sym ns-info] (get @compile-state :cljs.analyzer/namespaces)]
          [ns-sym (set (keys (:defs ns-info)))])))

(defn defs-since
  "Seq of `{:ns :sym :var-map}` for every def present now that wasn't
   in `before-snapshot`. Used by detect-and-tee to identify newly-
   defined vars after a form evals. `var-map` is the raw analyzer
   entry — pass it to `var-projection` to get the persistable shape."
  {:malli/schema [:=> [:cat ::defs-snapshot ::compile-state] [:sequential ::new-def]]}
  [before-snapshot compile-state]
  (let [ns-map (get @compile-state :cljs.analyzer/namespaces)]
    (for [[ns-sym ns-info] ns-map
          [sym var-map]    (:defs ns-info)
          :when (not (contains? (get before-snapshot ns-sym #{}) sym))]
      {:ns ns-sym :sym sym :var-map var-map})))

(defn ns-deps
  "Set of agent-ns syms `ns-sym` depends on, intersected with
   `known-ns-set` (so cljs.core / clojure.* / bootstrap nses drop
   out). Reads the analyzer's `:requires` + `:uses` maps directly.
   Excludes self. Used by Phase D bulk-load resume for topo-sort."
  {:malli/schema [:=> [:cat ::compile-state :symbol [:set :symbol]] [:set :symbol]]}
  [compile-state ns-sym known-ns-set]
  (let [ns-info (get-in @compile-state [:cljs.analyzer/namespaces ns-sym])
        deps   (set (concat (vals (:requires ns-info))
                            (vals (:uses ns-info))))]
    (-> deps
        (disj ns-sym)
        (set/intersection known-ns-set))))

(defn var-projection
  "Persistable subset of an analyzer var-map for `:seon.fn` storage.
   Maps analyzer keys (`:fn-var`, `:arglists`, `:meta {:doc ...}`,
   `:meta {:private ...}`, `:meta {:malli/schema ...}`) to the v1.md
   §2.2 `:seon.fn/*` attr shapes (`:fn-var?`, `:arglists` pr-str'd,
   `:doc`, `:private?`, `:specced?`).

   Analyzer note: `:arglists` arrives wrapped in a `(quote ...)`
   form (e.g. `(quote ([k v]))`). `pr-str` preserves that exactly —
   resume parses it back with `read-string` if needed."
  {:malli/schema [:=> [:cat [:map-of :any :any]] ::var-projection]}
  [{:keys [name fn-var arglists meta] :as _var-map}]
  {:sym       (str name)
   :fn-var?   (boolean fn-var)
   :arglists  (pr-str arglists)
   :doc       (or (:doc meta) "")
   :private?  (boolean (:private meta))
   :specced?  (some? (:malli/schema meta))})
