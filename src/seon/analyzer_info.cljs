(ns seon.analyzer-info
  "Read-side wrapper over the bootstrap-CLJS analyzer state in
   `@compile-state`. One module so 'how we read the analyzer' lives
   in one place. Three consumers:

   - Phase B item 10: detect-and-tee in `seon.eval/eval-batch!` —
     diffs `snapshot-defs` before/after each form, calls
     `defs-since` + `var-projection` to build `:seon.fn` entities.
   - Phase C item 11: `seed-from-source!` — walks the analyzer's
     known seon.* nses at boot.
   - Phase D item 15: bulk-load resume — topo-sorts over the stored
     `:seon.ns/require-edges` the tee writes from [[ns-require-edges]].

   `compile-state` is the CLJS-bootstrap inner atom (the one cljs.js
   threads through `eval-str`). The seon process holds it boxed in
   `seon.repl/!compile-state` (atom-of-atom); callers deref the
   outer atom once and pass the inner here.

   Implementation note (REPL-verified 2026-05-24): self-host CLJS
   does NOT expose `cljs.analyzer.api/find-ns` or `ns-resolve` —
   both throw `TypeError: undefined`. We read
   `(:cljs.analyzer/namespaces @compile-state)` directly. The
   research note's reference impl is wrong on that point."
  (:require [cljs.reader :as reader]
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
;; `:malli/schema`, `:seon.fn/agent-facing?`). Re-defs change the digest even when the keyset
;; doesn't, which is what `defs-since` needs to detect (B1).
(schema/register! ::defs-snapshot [:map-of :symbol [:map-of :symbol :int]])

;; Output of `defs-since` — one entry per newly-added OR re-defined def.
(schema/register! ::new-def
                  [:map
                   [::ns :symbol]
                   [::sym :symbol]
                   [::var-map [:map-of :any :any]]])

;; Output of `var-projection`. Matches `:seon.fn/*` attrs per v1.md §2.2.
;; Owner-ns keys (C34, C33-residue) — this ns produces the map, so the
;; keys speak `:seon.analyzer-info/*` like every internal envelope.
(schema/register! ::var-projection
                  [:map
                   [::sym :string]
                   [::fn-var? :boolean]
                   [::arglists :string]
                   [::doc :string]
                   [::private? :boolean]
                   ;; Positive capability declaration. ABSENT means the
                   ;; function remains program data but is not an agent tool.
                   [::agent-facing? {:optional true} [:= true]]
                   ;; `::spec` = `(pr-str (m/form <:malli/schema>))` when
                   ;; present and parseable; ABSENT = unspecced (or the
                   ;; schema failed to parse — caller stamps schema-error).
                   [::spec {:optional true} :string]])

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
           (:seon.fn/agent-facing? m)
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
  "Snapshot of `{ns-sym → {def-sym → digest-hash}}` for the analyzer.

   From the current `:cljs.analyzer/namespaces` map. The digest covers the
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
  "Seq of `{::ns ::sym ::var-map}` maps for defs changed since a snapshot.

   Every def present now whose digest
   differs from `before-snapshot` — i.e. brand-new defs AND re-defs
   whose load-bearing var-map fields changed (B1).

   Filters out `:declared true` entries (`(declare …)` produces a
   skeleton var-map with no body/arglists; tee'ing it would persist
   noise and overwrite a subsequent real defn's projection if the
   declare came after — A7). Also filters out the synthetic result vars
   (`:seon.eval/result-var? true`): `seon.eval/bind-result-var!`
   registers each eval's value under the reserved `result` ns, and
   without this guard those would tee as bogus `:seon.fn` rows + a
   sourceless `{:seon.ns/name :result}` row (the prefix allow-list was
   the only thing hiding them — once it is gone they leak into the program
   graph)."
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
          ;; - :seon.eval/result-var? filters the synthetic eval-result
          ;;   vars (bound under the reserved `result` ns by
          ;;   seon.eval/bind-result-var!) — they are not defs the agent
          ;;   wrote and must never tee into the program graph.
          :when (and (simple-symbol? sym)
                     (not (true? (:declared var-map)))
                     (not (true? (:seon.eval/result-var? var-map)))
                     (not= before-digest now-digest))]
      {::ns ns-sym ::sym sym ::var-map var-map})))

(defn remove-phantom-defs!
  "Failure-path counterpart to [[defs-since]].

   Drops the PHANTOM def
   registrations a FAILED eval left in `ns-sym`'s `:defs`, and return the
   removed simple-symbol seq.

   Why phantoms exist: under `:def-emits-var true`, `cljs.analyzer`'s
   `parse 'def` writes the var-map into `[::namespaces ns-sym :defs sym]`
   BEFORE it analyzes the body (analyzer.cljc ~2112) — and it does NOT
   roll the swap! back when the eval then fails. A body that analyzes
   cleanly but is FAILED post-eval (a warning-promoted `:undeclared-var`,
   or a runtime/emit throw) leaves the FULL var-map (incl. `:fn-var`),
   whose [[var-digest]] equals what a SUCCESSFUL same-signature retry
   would produce. That collision makes the retry's `defs-before` already
   hold the digest, so [[defs-since]] sees no change and the detect-and-tee
   SILENTLY SKIPS the `:seon.fn` row — the fn works in-session but never
   persists (vanishes on the next restart).

   Removing the syms present in `ns-sym`'s CURRENT `:defs` but ABSENT from
   `before-snapshot[ns-sym]` (this form's pre-eval keyset) restores the
   REPL invariant that a failed defn defines nothing: the retry is then
   genuinely-new and tees. Scoped to `ns-sym` and to NEWLY-added simple
   symbols so it can never touch a pre-existing def (its sym is in
   `before-snapshot`), a redef of an existing fn whose body failed (its
   PRIOR good entry is in `before-snapshot`), the fully-qualified
   multi-arity sub-records (non-simple-symbol keys, never in the
   snapshot), or any other namespace."
  {:malli/schema [:=> [:cat ::compile-state ::defs-snapshot :symbol]
                  [:sequential :symbol]]}
  [compile-state before-snapshot ns-sym]
  {:pre [(some? compile-state) (map? before-snapshot)]}
  (let [before-syms (get before-snapshot ns-sym)
        cur-defs    (get-in @compile-state
                            [:cljs.analyzer/namespaces ns-sym :defs])
        phantoms    (vec (for [[sym _var-map] cur-defs
                               :when (and (simple-symbol? sym)
                                          (not (contains? before-syms sym)))]
                           sym))]
    (when (seq phantoms)
      (swap! compile-state update-in
             [:cljs.analyzer/namespaces ns-sym :defs]
             (fn [defs] (apply dissoc defs phantoms))))
    phantoms))

;; ---------------------------------------------------------------------------
;; Reified require edges (M4/C28 structural store — code-as-data: the
;; analyzer already produced the alias/refer facts at eval time; store
;; them on the `:seon.ns` row instead of re-parsing `:seon.ns/source`
;; text at render time). One edge per required ns, carrying the `:as`
;; alias and `:refer` set when present. The attr registrations live HERE
;; (this ns loads before seon.eval → seon.client, so
;; every reader/writer sees them registered on a cold boot).
;; ---------------------------------------------------------------------------

(schema/register! :seon.ns.require/target :keyword)
(schema/register! :seon.ns.require/alias  :symbol)
(schema/register! :seon.ns.require/refers [:set :symbol])
;; `:refer :all` — never produced by the analyzer (CLJS has no
;; `:refer :all`); only the SOURCE-parse path
;; ([[require-edges-from-source]], over legacy/handwritten ns text) can yield
;; it. Registered so a parsed edge round-trips.
(schema/register! :seon.ns.require/refer-all? :boolean)
;; `[x :as-alias y]` — a READER alias for qualified keywords with NO
;; load of the target (Clojure 1.11 semantics; the analyzer stores it
;; in the ns entry's `:as-aliases`, never `:requires`). The flag keeps
;; the edge distinguishable so [[seon.eval/synthesized-ns-head]] emits
;; `:as-alias` back (a plain `:as` would LOAD the target on resume) and
;; the SCI env never treats the target as a loaded ns.
(schema/register! :seon.ns.require/as-alias? :boolean)

(schema/register! ::require-edge
                  [:map {:seon.db/entity true}
                   [:seon.ns.require/target :seon.ns.require/target]
                   [:seon.ns.require/alias {:optional true} :seon.ns.require/alias]
                   [:seon.ns.require/refers {:optional true} :seon.ns.require/refers]
                   [:seon.ns.require/refer-all? {:optional true} :seon.ns.require/refer-all?]
                   [:seon.ns.require/as-alias? {:optional true} :seon.ns.require/as-alias?]])
(schema/register! ::require-edges [:set ::require-edge])

(defn require-edges-from-source
  "Parse an `(ns …)` `source` string into the reified require-edge set.

   The SOURCE-side counterpart of
   `seon.analyzer-info/ns-require-edges` — same `::analyzer-info/
   require-edge` maps, derived by reading the form's `:require` clause.
   Used where no analyzer state exists: the boot indexer's full-source
   ns rows and the SCI cage's legacy fallback for pre-structural rows.
   Also carries `:seon.ns.require/refer-all? true` for a
   `:refer :all` clause (legacy text only — CLJS can't compile one).
   Fail-soft → `#{}` on any read error or a non-`(ns …)` form."
  {:malli/schema [:=> [:cat :string] :seon.analyzer-info/require-edges]}
  [source]
  (try
    (let [form (reader/read-string source)]
      (if (and (seq? form) (= 'ns (first form)))
        (let [reqs (->> form
                        (filter seq?)
                        (some #(when (= :require (first %)) (rest %))))]
          (into #{}
                (keep (fn [r]
                        (cond
                          (symbol? r)
                          {:seon.ns.require/target (keyword (str r))}

                          (and (vector? r) (symbol? (first r)))
                          (let [tns  (first r)
                                opts (try (apply hash-map (rest r))
                                          ;; probe: a malformed require clause
                                          ;; (odd-count opts) yields no opts —
                                          ;; expected for agent-authored source,
                                          ;; not a defect.
                                          (catch :default _ {}))
                                as   (:as opts)
                                asa  (:as-alias opts)
                                refr (:refer opts)]
                            (cond-> {:seon.ns.require/target (keyword (str tns))}
                              (symbol? as)       (assoc :seon.ns.require/alias as)
                              ;; `:as-alias` — reader alias, no load; when
                              ;; both `:as` and `:as-alias` appear the real
                              ;; alias wins (the ns IS loaded).
                              (and (symbol? asa) (not (symbol? as)))
                              (assoc :seon.ns.require/alias asa
                                     :seon.ns.require/as-alias? true)
                              (sequential? refr) (assoc :seon.ns.require/refers
                                                        (set refr))
                              (= :all refr)      (assoc :seon.ns.require/refer-all?
                                                        true)))
                          :else nil)))
                (or reqs [])))
        #{}))
    ;; probe: fail-soft over agent-authored source — an unreadable /
    ;; non-(ns …) form has no require edges; #{} is the expected answer.
    (catch :default _ #{})))

(defn ns-require-edges
  "The reified require-edge set for `ns-sym`, read from the analyzer.

   One `::require-edge` map per RUNTIME-required ns (the analyzer's
   `:requires` vals ∪ `:uses` vals, self excluded — macro-only
   `:require-macros` deps are NOT edges; resume's load-fn satisfies transitive requires
   on demand regardless of topo edges). The `:as` alias is the `:requires` KEY mapping to the
   target where key ≠ target; the `:refer` set is the `:uses` keys
   grouped by target. `#{}` for an unknown / never-eval'd ns. The ns
   entry's `:as-aliases` (the `:as-alias` reader aliases — no load)
   each yield an edge flagged `:seon.ns.require/as-alias? true`, unless
   the same target is also genuinely required. This is
   what the tee stores as `:seon.ns/require-edges` (component rows) so
   runtime consumers use datoms, never a reader over `:seon.ns/source`
   text (M4)."
  {:malli/schema [:=> [:cat ::compile-state :symbol] ::require-edges]}
  [compile-state ns-sym]
  (let [ns-info    (get-in @compile-state [:cljs.analyzer/namespaces ns-sym])
        reqs       (:requires ns-info)
        uses       (:uses ns-info)
        as-aliases (:as-aliases ns-info)
        targets    (-> (set (concat (vals reqs) (vals uses)))
                       (disj ns-sym))
        alias-of   (into {}
                         (keep (fn [[k v]] (when (not= k v) [v k])))
                         reqs)
        refers-of  (reduce-kv (fn [m sym target]
                                (update m target (fnil conj #{}) sym))
                              {} uses)]
    (into (into #{}
                (map (fn [t]
                       (cond-> {:seon.ns.require/target (keyword (str t))}
                         (alias-of t)  (assoc :seon.ns.require/alias (alias-of t))
                         (refers-of t) (assoc :seon.ns.require/refers (refers-of t)))))
                targets)
          ;; `:as-alias` reader aliases — targets NOT also required stay
          ;; load-free edges; a target that IS required already carries
          ;; its real edge above (the reader alias adds nothing).
          (keep (fn [[a t]]
                  (when-not (contains? targets t)
                    {:seon.ns.require/target    (keyword (str t))
                     :seon.ns.require/alias     a
                     :seon.ns.require/as-alias? true})))
          (or as-aliases {}))))

(defn var-projection
  "Persistable subset of an analyzer var-map for `:seon.fn` storage.
   Maps analyzer keys (`:fn-var`, `:arglists`, `:meta {:doc ...}`,
   `:meta {:private ...}`, `:meta {:malli/schema ...}` and positive
   `:meta {:seon.fn/agent-facing? true}`) to the v1.md
   §2.2 `:seon.fn/*` attr shapes (`:fn-var?`, `:arglists` pr-str'd,
   `:doc`, `:private?`, `:spec`).

   Arglists normalization (A6): single-arity defs land in the analyzer
   wrapped in a `(quote …)` form (e.g. `(quote ([x]))`); multi-arity
   defs land bare (e.g. `(([x]) ([x y]))`). We strip the leading
   `'quote` if present so the pr-str'd form is consistent across both
   shapes — callers that read-string the value back get a usable
   arglists structure either way."
  {:malli/schema [:=> [:cat [:map-of :any :any]] ::var-projection]}
  [{:keys [name fn-var arglists meta]}]
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
    (cond-> {::sym       (str name)
             ::fn-var?   (boolean fn-var)
             ::arglists  (pr-str al)
             ::doc       (or (:doc meta) "")
             ::private?  (boolean (:private meta))}
      (true? (:seon.fn/agent-facing? meta)) (assoc ::agent-facing? true)
      (some? spec) (assoc ::spec spec))))
