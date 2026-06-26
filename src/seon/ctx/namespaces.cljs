(ns seon.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt: CURATED,
   not render-everything. Each rendered ns is a `; namespace x`
   full-source comment-block. ONLY the curated set renders:

     - FULL source (whole file, NO clipping) for the few nses an agent
       actually USES or OWNS:
         (a) every `my.*` ns           — the human's/agent's own code;
         (b) every THIRD-PARTY ns      — non-seon, non-my (the
             `SEON_EXTRA_SRC` `acme` business logic the agent needs whole);
         (c) the agent's CURRENT ns    — its complete working code;
         (d) a small curated whitelist of `seon.*` framework tools
             ([[full-source-whitelist]]).
       The FULL-source decision is one shared rule:
       [[full-source-ns?]] already covers (a) + (d); (b) is the
       not-`seon.` structural fall-through; (c) is the current-ns check.
     - Every OTHER `seon.*` framework ns is DROPPED from the rendered
       section — it is NOT shown here at all. It stays INDEXED (its
       `:seon.ns/name` + `:seon.fn` / `:seon.schema` / `:seon.test` rows)
       and SEARCHABLE — discoverable via `seon.agent.search` (ripgrep) or
       readable on demand via [[seon.ctx/render-namespace]]. There is no
       signature manifest: passive name-listing is replaced by active
       grep/query, taught in the `<system>` prose.

   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.namespaces/namespaces-section`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`.

   The section NEVER re-reads files at render time (code-as-data): the
   boot indexer (`seon.client/ns-row`) is the ONE file-reader, and it
   stores the REAL full file text for exactly the nses rendered full
   (the same [[full-source-ns?]] rule + the extra-src roots),
   leaving the framework bulk a `(ns x)` stub — which this section never
   renders (those nses are dropped from the body; the stub still feeds the
   on-demand `render-namespace` path). So the full rows here are always
   real file source, never a reconstructed stub."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]))

;; ============================================================
;; The namespace-display selection rules — the ONE home for which
;; indexed :seon.ns rows render, and which render in FULL. Shared by
;; the boot indexer (`seon.client/ns-row`, the one file reader) and
;; [[namespaces-section]] (the curated namespaces prompt body):
;; one rule, one writer, no drift. Pure string/keyword/symbol fns —
;; no dependency on anything in `seon.ctx`.
;; ============================================================

(defn hidden-ns-name?
  "Rule 1: a `*.internal` namespace (or any of its children) is
   indexed but NEVER rendered — the naming convention IS the filter.
   String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (str/ends-with? s ".internal")
                 (str/includes? s ".internal.")))))

(defn my-ns-name?
  "Rule 2: `my.*` is the human's world — always shown, provenance not
   consulted (one name rule, no special cases)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (or (= s "my") (str/starts-with? s "my.")))))

(defn test-ns-name?
  "Rule 1b: a `*-test` namespace is indexed but NEVER rendered into the
   agent prompt — its `deftest`s are noise to the working agent, and the
   per-fn `:test` usage example already rides the regular fn's attr-map in
   the compact head. Full tests stay reachable on demand via
   [[seon.ctx/render-namespace]]. STRUCTURAL, like [[hidden-ns-name?]]: the
   suffix IS the filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/ends-with? s "-test")))

(defn included-ns?
  "The ONE selection rule for the namespace sections: EVERY indexed
   :seon.ns row renders EXCEPT *.internal (hidden-ns-name?) and *-test
   (test-ns-name?) ones — both STRUCTURAL naming conventions that apply
   to seon, my.*, and downstream code alike. No prefix allow-list: the
   library gate lives on the INDEX side (only first-party + SEON_EXTRA_SRC
   code ever gets a :seon.ns row — seon.indexing/first-party-file?)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (boolean (and (not (hidden-ns-name? s))
                  (not (test-ns-name? s))))))

(defn- base-ns-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.agent.search-test` → `seon.agent.search`).
   Non-test names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(def full-source-whitelist
  "The LEAN whitelist of `seon.*` FRAMEWORK namespaces shown to every agent
   IN FULL — kept deliberately tiny so the agent is not buried in framework
   code. Just two set-up tools, shown as EXAMPLES (both already aliased in
   the agent's home ns), not an exhaustive API dump. This is the clear
   EDITABLE def to extend.
     - `:seon.db` — the database API every agent uses for arbitrary reads
       and writes: the datalog cheat sheet, per-fn worked examples
       (query/pull/entity/transact!/store-inventory), the lookup-ref and
       ref-join idioms. Its real source IS the db manual.
     - `:seon.agent.todo` — the work-list tool an agent calls directly:
       `register!` per attr, map-in/map-out `:malli/schema` fn shapes,
       error-as-value envelopes, the todo verbs the system prompt teaches
       by name.
   EVERYTHING ELSE in the framework is curated OUT — the rest of `seon.*`
   (search, fs, message, lifecycle, schema, render, …) is NOT dumped here.
   It stays INDEXED and grep-able via `seon.agent.search` and readable on
   demand via [[seon.ctx/render-namespace]] — one search away, never a wall
   of code the agent must wade through. `my.*` nses (`my.kb`, agent-authored
   code) are ALREADY rendered full by the `my.*` rule in [[full-source-ns?]]
   — they do NOT belong here; this whitelist is ONLY for the seon.* example
   tools. Shared by the boot indexer (which stores their real file source —
   see `seon.client/ns-row`) and [[namespaces-section]] (which renders them
   FULL while the rest of the framework is DROPPED from the rendered
   section — still indexed + searchable)."
  #{:seon.db :seon.agent.todo})

(defn in-full-source-whitelist?
  "True when `ns-name` (string, keyword, or symbol) is one of the curated
   seon.* framework [[full-source-whitelist]] nses. String/keyword/symbol
   tolerant — the indexer hands a string, the renderer a keyword."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (contains? full-source-whitelist
             (if (keyword? ns-name) ns-name (keyword (str ns-name)))))

(defn full-source-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) carries its
   REAL FULL FILE TEXT as `:seon.ns/source`: every `my.*` ns (the
   human's world — always inlined), including `-test` siblings (the
   `-test` suffix is stripped to the subject ns first), AND every curated
   [[in-full-source-whitelist?]] seon.* tool (so a framework tool like
   `:seon.agent.todo` gets its REAL body stored — private helpers and
   comments included). Used by the boot indexer (`seon.client/ns-row`) to
   decide which rows get the file read; the SAME rule decides which rows
   [[namespaces-section]] renders FULL — one rule, one writer, no drift.
   Third-party (`acme`) roots are full-source too, gated separately by
   `seon.client/extra-src-ns-strs` (the same file read). Every other ns
   gets the minimal `(ns x)` stub at boot and is DROPPED from the rendered
   section (still indexed + searchable)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (and (not (hidden-ns-name? s))
                  (or (my-ns-name? base)
                      (in-full-source-whitelist? base))))))

(defn- third-party-ns?
  "A render-time structural rule: an included ns that is NEITHER `seon.*`
   framework NOR `my.*` is THIRD-PARTY business logic (the `acme`
   `SEON_EXTRA_SRC` code) — rendered FULL, no clipping. `my.*` is full via
   [[full-source-ns?]] already; this catches the remaining
   non-seon roots. String/keyword tolerant."
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (not (str/starts-with? s "seon."))))

(defn- render-full?
  "True when ns `nm` (a keyword) renders its FULL source in the body:
     (a) it is the agent's CURRENT ns (`cur-ns`); OR
     (b) [[full-source-ns?]] — every `my.*` ns + the curated
         seon.* whitelist ([[full-source-whitelist]]); OR
     (c) [[third-party-ns?]] — a non-seon, non-my root (the `acme`
         business logic).
   Everything else is a `seon.*` framework ns → DROPPED from the rendered
   section (still indexed + searchable)."
  [nm cur-ns]
  (boolean
    (or (= nm cur-ns)
        (full-source-ns? nm)
        (third-party-ns? nm))))

(def ^:private namespaces-header
  (str "; Real loaded code. Shown in FULL: YOUR OWN namespace (your live\n"
       "; workspace — the most important), and a couple of set-up tools as\n"
       "; EXAMPLES — db (the database) and todo (your work list), both\n"
       "; already aliased in your namespace. The rest of the framework is\n"
       "; NOT dumped here on purpose — it stays indexed and one search away,\n"
       "; so you are not buried in code you don't need. Full namespaces are\n"
       "; ordered by recency (most-recently-modified last)."))

(defn- cur-ns-workspace-stub
  "The never-omit block for the agent's CURRENT ns when it has no members
   defined yet (GI-2). A fresh home ns (`my.agent.<id>`) carries a
   `:seon.ns/name` + `:seon.ns/requires` row but no stored `:seon.ns/source`
   and no fns/schemas, so [[seon.ctx/render-namespace]] yields an empty body
   that would be omitted — breaking the system prompt's promise that YOUR
   OWN namespace renders in full. This stub keeps that promise: it shows the
   reconstructed `(ns …)` require form (from the stored `:seon.ns/requires`,
   cardinality-many → pulled as a vector) so the agent sees what's wired into
   its workspace, plus a one-line note that the workspace is empty. `nm` is a
   ns-name keyword that already has a `:seon.ns/name` row (the caller only
   reaches this for an included, current-ns row)."
  [db nm]
  (let [reqs    (-> (db/pull {:seon.db/db db
                              :seon.db/ref [:seon.ns/name nm]
                              :seon.db/pull-pattern '[:seon.ns/requires]})
                    :seon.ns/requires
                    sort)
        ns-form (if (seq reqs)
                  (str "(ns " (name nm) "\n  (:require "
                       (str/join " " (map name reqs)) "))")
                  (str "(ns " (name nm) ")"))]
    (str "; namespace " (name nm) "\n"
         ns-form "\n"
         "; (your workspace — nothing defined here yet; define schemas + fns and they appear here)")))

(defn- render-one
  "Render ONE included ns through the SINGLE renderer
   ([[seon.ctx/render-namespace]]) at the chosen detail LEVEL, flat (depth
   0 — no require-recursion; the section renders each ns once). `:full`
   yields the whole-ns view (real file source + members); `:signature`
   yields the `(signatures)` API-surface block.

   The agent's CURRENT ns (`cur-ns`) ALWAYS renders, even when empty: an
   empty current ns becomes a [[cur-ns-workspace-stub]] (GI-2) so the
   prompt's 'YOUR OWN namespace renders in full' promise holds. Every OTHER
   full ns with nothing real to show is omitted (nil) — the empty-store
   edge; the boot indexer guarantees real text for every other full row."
  [db nm detail cur-ns]
  (let [txt    (-> (ctx/render-namespace
                     {:seon.ns/name      nm
                      :seon.render/depth 0
                      :seon.render/detail detail
                      :seon.db/db        db})
                   :seon.render/text
                   str/trim)
        ;; render-namespace emits a `; namespace x` label even for an empty
        ;; body: `; (no recorded source/fns/schemas)` (entity present, no
        ;; source/members) or `; requires: x (not in db)` (the home ns —
        ;; a :seon.ns/name row whose sparse pull returns nil). Both mean
        ;; "nothing real to show."
        empty? (or (str/blank? txt)
                   (and (= detail :full)
                        (or (str/includes? txt "(no recorded source/fns/schemas)")
                            (str/includes? txt "(not in db)"))))]
    (cond
      (= nm cur-ns) (if (and (= detail :full) empty?)
                      (cur-ns-workspace-stub db nm)
                      txt)
      empty?        nil
      :else         txt)))

(defn namespaces-section
  "CURATED namespaces body. Routes EVERY included ns through the SINGLE
   renderer [[seon.ctx/render-namespace]] — no parallel hand-rolled paths.
   The per-ns DETAIL LEVEL is the only choice the section makes
   ([[render-full?]]):

     - FULL (`:seon.render/detail :full`) for every `my.*` ns, every
       THIRD-PARTY `acme` ns, the agent's CURRENT ns, and the curated
       [[full-source-whitelist]] seon.* tools — each a `; namespace x`
       block carrying its REAL FULL FILE SOURCE (+ any member rows), unclipped.
     - Every OTHER `seon.*` framework ns is DROPPED from the rendered
       section — not shown here at all. It stays INDEXED and SEARCHABLE
       (via `seon.agent.search`) and readable on demand via
       [[seon.ctx/render-namespace]].

   The full blocks are ordered by RECENCY (tx of the `:seon.ns/name` datom —
   bumped by the tee's nested upsert on every define), name as the
   tie-break, so the stable core forms a stable cache prefix and the
   churning ns sits nearest the tail.

   `*.internal` and `*-test` nses are excluded outright ([[included-ns?]]).
   A full ns whose stored source/members are all empty renders nothing
   (omitted). NEVER a render-time file read — the boot indexer is the one
   reader; render-namespace reads only indexed rows."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [;; The agent's current ns (latest successful eval's ns) → rendered
        ;; FULL even if it is a framework ns. nil id (inspector path) →
        ;; nil → no ns is forced current.
        cur-ns (when id
                 (try (when-let [c (ctx/current-ns {:seon.agent/id id :seon.db/db db})]
                        ;; current-ns yields a KEYWORD from a recorded eval but
                        ;; a SYMBOL from the (home-ns id) fallback (a fresh agent
                        ;; with no successful evals yet) — normalize to a keyword
                        ;; so the `(= nm cur-ns)` match against the keyword ns
                        ;; rows holds in BOTH cases (GI-2 fires even on turn 0).
                        (keyword (name c)))
                      (catch :default _ nil)))
        ;; EVERY included ns row, recency-ordered. One :seon.ns/name datom
        ;; per ns carries its tx.
        rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?tx
                         :where
                         [?n :seon.ns/name ?nm ?tx]]})
                    (filter (fn [[nm _]] (included-ns? nm)))
                    (sort-by (fn [[nm tx]] [tx (name nm)])))
        ;; ONLY curated-full nses render: current ns, full-source-ns?
        ;; (my.* + the seon.* full-source-whitelist), and third-party
        ;; (acme) roots. Every OTHER seon.* framework ns is DROPPED from
        ;; the rendered section — it stays indexed + grep-able via
        ;; seon.agent.search, just not dumped here.
        full-rows (filter (fn [[nm _tx]] (render-full? nm cur-ns)) rows)
        blocks    (keep (fn [[nm _tx]] (render-one db nm :full cur-ns)) full-rows)]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
