(ns seon.agent.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt: CURATED,
   not render-everything. Each rendered ns is a FULL-source comment-block
   delimited by per-ns `;;; ┌─ namespace x ─` / `;;; └─ end namespace x ─`
   brackets ([[seon.agent.ctx/ns-demarc]]). There is NO signature/compression
   path: every selected ns renders its REAL FULL FILE SOURCE, unclipped. The
   token budget is bound by CURATION (which nses render), never by compression
   (how each renders). The selected set ([[render-set]]):

     - the agent's CURRENT ns — its complete working code (unless the config
       policy sets `:seon.config/current-ns :off`);
     - every ns in the config `:seon.config/always` list
       ([[seon.config/namespaces-policy]]) — the `my.*` toolkit exemplars
       (`my.kb`/`my.data`/`my.ui`/`my.tile`) plus the core verb nses
       (`seon.agent.todo`/`seon.agent.message`/`seon.agent.lifecycle`);
     - the non-third-party nses the CURRENT ns `:require`s that carry stored
       full source ([[full-source-ns?]]) — so writing a real `(:require …)`
       pulls a helper into view, self-healing on the `:seon.ns/requires` edges
       (drop the require → it leaves the set);
     - every THIRD-PARTY ns — non-seon, non-my (the `SEON_EXTRA_SRC` `acme`
       business logic the agent needs whole);
     - the per-agent LIVE-DB override set ([[db-render-set]] —
       `:seon.agent.ctx/render-namespaces` datoms): transact a ns keyword onto
       the agent → it renders full next turn; retract → it vanishes.

   Every OTHER ns (the framework bulk and the agent's non-reachable `my.*`
   long tail) is DROPPED from the rendered section. It stays INDEXED (its
   `:seon.ns/name` + `:seon.fn` / `:seon.schema` / `:seon.test` rows) and
   SEARCHABLE — discoverable via `seon.agent.search` (ripgrep) or readable on
   demand via [[seon.agent.ctx/render-namespace]] (which defaults to `:full`).
   Passive name-listing is replaced by active grep/query, taught in the
   `<system>` prose.

   Symbol-wired into the composer layout (`seon.agent.ctx/default-seed-blocks`) as
   `'seon.agent.ctx.namespaces/namespaces-block`; loaded at boot so the
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
    [seon.agent.ctx :as ctx]
    [seon.config :as config]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.schema :as schema]))

;; ============================================================
;; Config interface — the namespaces-block render dials, as reactive
;; datoms on the namespaces block entity (config-driven-agent-init lane's
;; two-level model: a block ref off the agent record). The config loader
;; transacts these onto the block; `db/transact!` refuses unregistered
;; attrs, so they live HERE, colocated with the render fn that reads them.
;;
;; Attribute-PRESENCE is the config (decision 22/23): a ns present in a set
;; IS its config; compact is the ABSENCE. The two set attrs mirror the
;; proven cardinality-many pattern of `:seon.agent.ctx/render-namespaces`;
;; the element type is `:seon.ns/name` (a keyword, matching the identity
;; attr's shape) — the bridge derives the SAME cardinality-many keyword
;; column as a bare `:keyword` would, but names the shape. A keyword (not a
;; `:db.type/ref`) tolerates configuring a not-yet-indexed ns (a fresh
;; `my.agent.*` home ns): an unmatched name simply no-ops in the render.
;; Defaults ride each spec (malli-native, decision 4) — a fresh block is
;; compact-everywhere + full-current-ns purely from these defaults.
;; ============================================================

(schema/register! ::full-source   [:vector {:default []} :seon.ns/name]) ; ns present → render FULL (absent → compact)
(schema/register! ::with-tests    [:vector {:default []} :seon.ns/name]) ; ns present → also show its tests
(schema/register! ::current-full?  [:boolean {:default true}])           ; the agent's current ns renders full
(schema/register! ::current-tests? [:boolean {:default true}])           ; …and its current ns shows its tests

;; ============================================================
;; The namespace-display selection rules — the ONE home for which
;; indexed :seon.ns rows render, and which render in FULL. Shared by
;; the boot indexer (`seon.client/ns-row`, the one file reader) and
;; [[namespaces-block]] (the curated namespaces prompt body):
;; one rule, one writer, no drift. Pure string/keyword/symbol fns —
;; no dependency on anything in `seon.agent.ctx`.
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
   [[seon.agent.ctx/render-namespace]]. STRUCTURAL, like [[hidden-ns-name?]]: the
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

(defn- always-full?
  "True when `ns-name` (string/keyword/symbol) is in the resolved config
   policy's `:seon.config/always` set — the EXPLICIT list of nses rendered
   FULL (#42, [[seon.config/namespaces-policy]]). Replaces the retired
   hardcoded `full-source-whitelist`/`canonical-full-my-ns`: the boot indexer
   (`seon.client/ns-row`) and the renderer share this ONE decision. The policy
   read is memoized — no per-call file read."
  [ns-name]
  (contains? (:seon.config/always (config/namespaces-policy))
             (if (keyword? ns-name) ns-name (keyword (str ns-name)))))

(defn always-full-my-nses
  "The `my.*` namespaces the resolved config policy renders FULL — the toolkit
   exemplars (`my.kb`/`my.data`/`my.ui`/`my.tile` by default): the `my.*`
   members of [[seon.config/namespaces-policy]]'s `:seon.config/always` set.
   DERIVED from config, never hardcoded — replaces the retired
   `canonical-full-my-ns` const for the gym's toolkit-alias derivation."
  {:malli/schema [:=> [:cat] [:set :keyword]]}
  []
  (into #{} (filter my-ns-name?) (:seon.config/always (config/namespaces-policy))))

(defn full-source-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) carries its
   REAL FULL FILE TEXT as `:seon.ns/source`: every `my.*` ns (the
   human's world — always inlined), including `-test` siblings (the
   `-test` suffix is stripped to the subject ns first), AND every seon.* ns
   the config policy lists in `:seon.config/always` ([[always-full?]] — e.g.
   `:seon.agent.todo`, so its REAL body is stored). Used by the boot indexer
   (`seon.client/ns-row`) to decide which rows get the file read; the SAME
   rule decides which rows [[namespaces-block]] renders FULL — one rule, one
   writer, no drift. Third-party (`acme`) roots are full-source too, gated
   separately by `seon.client/extra-src-ns-strs` (the same file read). Every
   other ns gets the minimal `(ns x)` stub at boot and is DROPPED from the
   rendered section (still indexed + searchable)."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (and (not (hidden-ns-name? s))
                  (or (my-ns-name? base)
                      (always-full? base))))))

(defn- third-party-ns?
  "A render-time structural rule: an included ns that is NEITHER `seon.*`
   framework NOR `my.*` is THIRD-PARTY business logic (the `acme`
   `SEON_EXTRA_SRC` code) — rendered FULL, no clipping, like the human's own
   `my.*` world. String/keyword tolerant. (Genuine third-party only — `my.*`
   is selected via the curated full set / current ns / requires, NOT here, so
   a non-reachable `my.*` ns correctly falls to the navigable long tail.)"
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (and (not (str/starts-with? s "seon."))
         (not (my-ns-name? s)))))

(defn- seon-framework-ns?
  "True when `ns-name` (string/keyword/symbol) is a `seon.*` framework ns —
   used to route a STABLE always/required ns into the name-sorted cache PREFIX
   vs the recency BODY (the agent's churning my.* / current / db-override nses)."
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/starts-with? s "seon.")))

(defn- db-render-set
  "The per-agent LIVE-DB render override — the set of ns-name keywords the
   agent (or a human, or another agent) has transacted onto its own row via
   `:seon.agent.ctx/render-namespaces` (cardinality-many). Pure fn of the DB:
   add a datom → the ns joins the rendered set next turn; retract → it leaves.
   Derive-at-render, no stored projection. nil id (inspector path) → empty."
  [db id]
  (if-not id
    #{}
    (into #{}
          (map first)
          (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find ?ns
               :in $ ?id
               :where
               [?a :seon.agent/id ?id]
               [?a :seon.agent.ctx/render-namespaces ?ns]]
             :seon.db/args [id]}))))

(defn- required-full-set
  "The non-third-party nses the CURRENT ns `:require`s that carry stored FULL
   source ([[full-source-ns?]]) — so a real `(:require [my.helper …])` pulls
   that helper into the rendered set. GATED on `full-source-ns?` so a require
   of an infra ns with only a `(ns x)` stub (e.g. `seon.db`/`seon.schema`) is
   NOT dumped: infra is reached via grep / `render-namespace`, never inlined
   whole. Self-healing on the `:seon.ns/requires` edges — drop the require → it
   leaves the set. Empty when `cur-ns` is nil."
  [db cur-ns]
  (if-not cur-ns
    #{}
    (into #{}
          (comp (map first)
                (filter keyword?)
                (filter included-ns?)
                (filter full-source-ns?))
          (db/query
            {:seon.db/db db
             :seon.db/query
             '[:find ?r
               :in $ ?ns
               :where
               [?e :seon.ns/name ?ns]
               [?e :seon.ns/requires ?r]]
             :seon.db/args [cur-ns]}))))

(defn- render?
  "True when an included ns `nm` renders FULL in the namespaces BODY — the ONE
   full-or-drop decision (signatures retired; everything rendered is full).
   Membership in the curated `full-set` (config `:always` ∪ the current ns's
   required-full helpers ∪ the live-DB override), the THIRD-PARTY (`acme`) code,
   or the agent's CURRENT ns (unless `:seon.config/current-ns :off`). Every
   other ns is DROPPED — still indexed + grep-able, just never dumped here."
  [policy nm cur-ns full-set]
  (boolean
    (or (contains? full-set nm)
        (third-party-ns? nm)
        (and (= nm cur-ns) (not= :off (:seon.config/current-ns policy))))))

(def ^:private namespaces-header
  ;; Block-specific cue ONLY — the FULL-vs-queryable policy (what renders in
  ;; full, what stays indexed/searchable) lives once in
  ;; `seon.agent.ctx/system-text` (§"THE NAMESPACES BELOW"); don't re-teach it.
  (str "; The loaded namespaces below, ordered by recency"
       " (most-recently-modified last)."))

(defn- cur-ns-workspace-stub
  "The never-omit block for the agent's CURRENT ns when it has no members
   defined yet (GI-2). A fresh home ns (`my.agent.<id>`) carries a
   `:seon.ns/name` row but no stored `:seon.ns/source` and no fns/schemas, so
   [[seon.agent.ctx/render-namespace]] yields an empty body that would be omitted
   — breaking the system prompt's promise that YOUR OWN namespace renders in
   full. This stub keeps that promise: it shows the REAL `(ns … (:require …))`
   form [[seon.eval/setup-agent-ns!]] actually installed — `[seon.agent.message
   :as message]` / `[seon.agent.lifecycle :refer [wait complete …]]` / … WITH
   the aliases + refers — straight from the ONE canonical
   [[seon.eval/home-ns-form]], NOT a bare-name reconstruction from
   `:seon.ns/requires`. No hidden aliasing: the agent reads the form and knows
   `message/user`, `db/transact!`, `schema/register!`, `wait`, `complete`
   exist and how to call them. `nm` is a ns-name keyword whose `:seon.ns/name`
   row the caller already matched (an included, current-ns row)."
  [_db nm]
  (ctx/ns-demarc
    nm
    (str (seval/home-ns-form nm) "\n"
         "; (your workspace — nothing defined here yet; define schemas + fns and they appear here)")))

(defn- render-one
  "Render ONE included ns FULL through the SINGLE renderer
   ([[seon.agent.ctx/render-namespace]]), flat (depth 0 — no require-recursion;
   the section renders each ns once): the whole-ns view, real file source +
   members, unclipped.

   The agent's CURRENT ns (`cur-ns`) ALWAYS renders, even when empty: an
   empty current ns becomes a [[cur-ns-workspace-stub]] (GI-2) so the
   prompt's 'YOUR OWN namespace renders in full' promise holds. Every OTHER
   ns with nothing real to show is omitted (nil) — the empty-store edge; the
   boot indexer guarantees real text for every other selected row."
  [db nm cur-ns]
  (let [txt    (-> (ctx/render-namespace
                     {:seon.ns/name       nm
                      :seon.render/depth  0
                      :seon.render/detail :full
                      :seon.db/db         db})
                   :seon.render/text
                   str/trim)
        ;; render-namespace brackets even an empty ns, whose body is then
        ;; `; (no recorded source/fns/schemas)` (entity present, no
        ;; source/members) or `; requires: x (not in db)` (the home ns —
        ;; a :seon.ns/name row whose sparse pull returns nil). Both mean
        ;; "nothing real to show."
        empty? (or (str/blank? txt)
                   (str/includes? txt "(no recorded source/fns/schemas)")
                   (str/includes? txt "(not in db)"))]
    (cond
      (= nm cur-ns) (if empty? (cur-ns-workspace-stub db nm) txt)
      empty?        nil
      :else         txt)))

(defn namespaces-block
  "CURATED namespaces body. Routes EVERY selected ns through the SINGLE
   renderer [[seon.agent.ctx/render-namespace]] at `:full` detail — no
   parallel hand-rolled paths, no signature/compression path. The ONE choice
   the section makes is WHICH nses render ([[render?]] / [[render-set]]),
   driven by the explicit config policy ([[seon.config/namespaces-policy]]),
   the current ns + its required-full helpers, third-party code, and the
   per-agent live-DB override:

     - every selected ns renders FULL — a `;;; ┌─ namespace x ─` / `;;; └─ end
       namespace x ─` bracketed block carrying its REAL FULL FILE SOURCE,
       unclipped. Token budget is bound by CURATION, never compression.
     - Every OTHER ns is DROPPED. It stays INDEXED and SEARCHABLE (via
       `seon.agent.search`) and readable on demand via
       [[seon.agent.ctx/render-namespace]].

   ORDER: the STABLE always/required `seon.*` + third-party nses render FIRST,
   name-sorted, as a cache PREFIX; then the agent's churning BODY (my.* /
   current ns / live-DB override) ordered by RECENCY (tx of the `:seon.ns/name`
   datom, name tie-break) so the stable core forms a stable prefix and the
   churning ns sits nearest the tail.

   CACHE TRADE: the live-DB override set ([[db-render-set]]) sits in this
   CACHED-prefix block (priority ≤ 20). A DB-driven set that CHURNS busts the
   provider prompt cache whenever it changes — acceptable for the deliberate,
   rare navigation it serves (pin a ns, read it, unpin), not for per-turn flux.

   `*.internal` and `*-test` nses are excluded outright ([[included-ns?]]).
   A selected ns whose stored source/members are all empty renders nothing
   (omitted). NEVER a render-time file read — the boot indexer is the one
   reader; render-namespace reads only indexed rows."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [policy (config/namespaces-policy)
        ;; The agent's current ns (latest successful eval's ns) → rendered per
        ;; the policy's :current-ns even if it is a framework ns. nil id
        ;; (inspector path) → nil → no ns is forced current.
        cur-ns (when id
                 (try (when-let [c (ctx/current-ns {:seon.agent/id id :seon.db/db db})]
                        ;; current-ns yields a KEYWORD from a recorded eval but
                        ;; a SYMBOL from the (home-ns id) fallback (a fresh agent
                        ;; with no successful evals yet) — normalize to a keyword
                        ;; so the `(= nm cur-ns)` match against the keyword ns
                        ;; rows holds in BOTH cases (GI-2 fires even on turn 0).
                        (keyword (name c)))
                      (catch :default _ nil)))
        ;; The per-agent live-DB render override (queried once).
        db-set   (db-render-set db id)
        ;; The curated FULL set: config :always ∪ current-ns's required-full
        ;; helpers ∪ the per-agent live-DB override. (THIRD-PARTY + current ns
        ;; are decided per-row in [[render?]], not folded here.)
        full-set (into (into (:seon.config/always policy)
                             (required-full-set db cur-ns))
                       db-set)
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
        ;; Select every renderable row + its PHASE: :prefix for a STABLE
        ;; always/required seon.* ns (name-sorted cache prefix); :body for the
        ;; agent's churning nses (my.* / current / live-DB override / third
        ;; party), recency-ordered nearest the tail.
        selected (keep (fn [[nm _tx]]
                         (when (render? policy nm cur-ns full-set)
                           (let [prefix? (and (not= nm cur-ns)
                                              (seon-framework-ns? nm)
                                              (not (contains? db-set nm)))]
                             [nm (if prefix? :prefix :body)])))
                       rows)
        prefix-rows (->> selected
                         (filter (fn [[_ phase]] (= phase :prefix)))
                         (sort-by (fn [[nm _]] (name nm))))
        body-rows   (filterv (fn [[_ phase]] (= phase :body)) selected)
        prefix-blocks (keep (fn [[nm _]] (render-one db nm cur-ns)) prefix-rows)
        body-blocks   (keep (fn [[nm _]] (render-one db nm cur-ns)) body-rows)
        blocks        (concat prefix-blocks body-blocks)]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
