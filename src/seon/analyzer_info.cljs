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
            [malli.core :as m]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

;; compile-state is an atom wrapping the analyzer's massive state
;; map; no useful Malli shape, and consumers pass it opaquely.
(schema/register! ::compile-state :any)

;; {ns-sym → {def-sym → digest-hash}}. The digest is a single int
;; produced by `var-digest` covering the semantically-load-bearing
;; subset of the var-map (`:fn-var`, `:arglists`, `:doc`, `:private`,
;; `:malli/schema`). Re-defs change the digest even when the keyset
;; doesn't, which is what `defs-since` needs to detect (B1).
(schema/register! ::defs-snapshot [:map-of :symbol [:map-of :symbol :int]])

;; Output of `defs-since` — one entry per newly-added OR re-defined def.
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
                   ;; `:spec` = `(pr-str (m/form <:malli/schema>))` when
                   ;; present and parseable; ABSENT = unspecced (or the
                   ;; schema failed to parse — caller stamps schema-error).
                   [:spec {:optional true} :string]])

;;; ---------------------------------------------------------------------------
;;; Internals
;;; ---------------------------------------------------------------------------

(defn- var-digest
  "Single-int hash of the semantically-load-bearing subset of an
   analyzer var-map. Used by snapshot-defs / defs-since to detect
   re-definitions (B1): two var-maps with the same keys but different
   `:doc` (or `:arglists`, `:private`, etc.) produce different digests.

   We deliberately exclude `:line` / `:column` / `:file` / `:env` —
   those churn without semantic effect (e.g. cljs.js re-emits
   source-location metadata on every eval) and would cause false
   redef detections."
  [var-map]
  (let [m (:meta var-map)]
    (hash [(:fn-var var-map)
           (:arglists var-map)
           (:doc m)
           (:private m)
           (:malli/schema m)
           ;; `:test` meta — cljs.test/deftest puts the test body fn
           ;; here. Each eval makes a fresh fn object → fresh identity-
           ;; hash. Including it means re-deftest tees a fresh
           ;; :seon.test row (Phase 4 mvp-completion-plan 2026-05-27).
           ;; For non-test defs `:test` is nil; doesn't affect digest.
           (:test m)])))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn snapshot-defs
  "Snapshot of `{ns-sym → {def-sym → digest-hash}}` in the analyzer's
   current `:cljs.analyzer/namespaces` map. The digest covers the
   semantically-meaningful fields of each var-map (see `var-digest`),
   so a re-def with changed doc/arglists/etc. produces a different
   digest — which is what `defs-since` keys off of (B1)."
  {:malli/schema [:=> [:cat ::compile-state] ::defs-snapshot]}
  [compile-state]
  {:pre [(some? compile-state)]}
  (into {}
        ;; The cljs self-host analyzer carries a `nil` ns-key holding only
        ;; the keyword-constants table (`:cljs.analyzer/constants`) — see
        ;; `cljs.analyzer/register-constant!`, which writes into
        ;; `[::namespaces (-> env :ns :name) ::constants]` and lands on a
        ;; `nil` name when a bare keyword constant is analyzed with no
        ;; enclosing ns (common under `cljs.js` eval-str). It holds NO
        ;; defs, so dropping it loses nothing — and keeps the output a
        ;; genuine `{symbol → …}` map (the schema's contract).
        (for [[ns-sym ns-info] (get @compile-state :cljs.analyzer/namespaces)
              :when (symbol? ns-sym)]
          [ns-sym (into {} (for [[sym var-map] (:defs ns-info)
                                 :when (simple-symbol? sym)]
                             [sym (var-digest var-map)]))])))

(defn defs-since
  "Seq of `{:ns :sym :var-map}` for every def present now whose digest
   differs from `before-snapshot` — i.e. brand-new defs AND re-defs
   whose load-bearing var-map fields changed (B1).

   Filters out `:declared true` entries (`(declare …)` produces a
   skeleton var-map with no body/arglists; tee'ing it would persist
   noise and overwrite a subsequent real defn's projection if the
   declare came after — A7)."
  {:malli/schema [:=> [:cat ::defs-snapshot ::compile-state] [:sequential ::new-def]]}
  [before-snapshot compile-state]
  {:pre [(map? before-snapshot) (some? compile-state)]}
  (let [ns-map (get @compile-state :cljs.analyzer/namespaces)]
    (for [[ns-sym ns-info] ns-map
          [sym var-map]    (:defs ns-info)
          :let [before-digest (get-in before-snapshot [ns-sym sym])
                now-digest    (var-digest var-map)]
          ;; - simple-symbol? filters out the multi-arity sub-records
          ;;   cljs.js writes into :defs keyed by the FULLY-QUALIFIED
          ;;   name (e.g. {ns-sym 'probe.q :defs {'probe.q/multi
          ;;   {:methods …}}}). They carry no :name / :arglists and
          ;;   would tee as junk.
          ;; - :declared filters (declare …) skeletons (A7).
          :when (and (simple-symbol? sym)
                     (not (true? (:declared var-map)))
                     (not= before-digest now-digest))]
      {:ns ns-sym :sym sym :var-map var-map})))

(defn ns-deps
  "Set of agent-ns syms `ns-sym` depends on, intersected with
   `known-ns-set` (so cljs.core / clojure.* / bootstrap nses drop
   out). Reads the analyzer's `:requires` + `:uses` + `:require-macros`
   maps directly. Excludes self. Used by Phase D bulk-load resume for
   topo-sort. `:require-macros` matters because macro-only deps
   (e.g. `(:require-macros [foo.macros :as fm])`) don't show up in
   `:requires` but DO need to load first on resume."
  {:malli/schema [:=> [:cat ::compile-state :symbol [:set :symbol]] [:set :symbol]]}
  [compile-state ns-sym known-ns-set]
  (let [ns-info (get-in @compile-state [:cljs.analyzer/namespaces ns-sym])
        deps   (set (concat (vals (:requires ns-info))
                            (vals (:uses ns-info))
                            (vals (:require-macros ns-info))))]
    (-> deps
        (disj ns-sym)
        (set/intersection known-ns-set))))

(defn var-projection
  "Persistable subset of an analyzer var-map for `:seon.fn` storage.
   Maps analyzer keys (`:fn-var`, `:arglists`, `:meta {:doc ...}`,
   `:meta {:private ...}`, `:meta {:malli/schema ...}`) to the v1.md
   §2.2 `:seon.fn/*` attr shapes (`:fn-var?`, `:arglists` pr-str'd,
   `:doc`, `:private?`, `:spec`).

   Arglists normalization (A6): single-arity defs land in the analyzer
   wrapped in a `(quote …)` form (e.g. `(quote ([x]))`); multi-arity
   defs land bare (e.g. `(([x]) ([x y]))`). We strip the leading
   `'quote` if present so the pr-str'd form is consistent across both
   shapes — callers that read-string the value back get a usable
   arglists structure either way."
  {:malli/schema [:=> [:cat [:map-of :any :any]] ::var-projection]}
  [{:keys [name fn-var arglists meta] :as var-map}]
  {:pre [(map? var-map)]}
  (let [al (if (and (seq? arglists) (= 'quote (first arglists)))
             (second arglists)
             arglists)
        ;; `:spec` = the fn's contract as `(pr-str (m/form schema))`.
        ;; Present (and the exact form in the corpus) when `:malli/schema`
        ;; metadata is present AND parses; absent when the metadata is
        ;; missing OR fails to parse (the tee caller stamps schema-error
        ;; in the latter case).
        schema-meta (:malli/schema meta)
        spec        (when (some? schema-meta)
                      (try (-> schema-meta m/schema m/form pr-str)
                           (catch :default _ nil)))]
    (cond-> {:sym       (str name)
             :fn-var?   (boolean fn-var)
             :arglists  (pr-str al)
             :doc       (or (:doc meta) "")
             :private?  (boolean (:private meta))}
      (some? spec) (assoc :spec spec))))
