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
  "The CURATED whitelist of `seon.*` FRAMEWORK namespaces shown to every
   agent IN FULL — the few seon.* tools an agent actually USES, worth
   their whole source. This is the clear EDITABLE def to extend.
     - `:seon.agent.todo` — the store/retrieve reference an agent calls
       directly: `register!` per attr, three map-in/map-out `:malli/schema`
       fn shapes, error-as-value envelopes, the todo tools the system
       prompt teaches by name.
     - `:seon.db` — the database API every agent uses for arbitrary reads
       and writes: the datalog cheat sheet, per-fn worked examples
       (query/pull/entity/transact!/store-inventory), the lookup-ref and
       ref-join idioms. Its real source IS the db manual.
     - `:seon.agent.search` — ripgrep wrapped as a capability; the
       search→read recipe an agent runs constantly. Signatures-only left
       agents guessing the call/response shape (e.g. expecting a `:hits`
       key); the real body carries the worked `grep` example, the
       `:seon.agent.search/matches` envelope, and the search→read idiom.
     - `:seon.agent.fs` — the read+write half of search→read; the agent's
       eyes and hands on disk behind a default-deny allowlist
       (`read-file`/`write-file`/`list-dir`/`walk-dir`/`stat`/`grants`).
       `grep` returns paths that feed `read-file`; the body teaches the
       default-deny-envelope-is-PASS vs thrown-error-is-FAIL distinction.
     - `:seon.agent.message` — the conversation verbs, the agent's only way
       to talk to its human or peers (`user`/`agent` over `message!`):
       fan-out (`to` = vector of refs), the hop guard, the `{:ok? …}`
       envelope, the loud self→self refusal.
     - `:seon.agent.lifecycle` — the terminal-transition verbs
       (`wait`/`complete`/`terminate`); how the agent parks, finishes, or
       is killed — each a small `:seon.agent/state` transact, errors-as-values.
   Deliberately NOT whitelisted (curated OUT — every shown line must earn
   its tokens): `:seon.schema` (its body is registry WIRING; the `register!`
   pattern is demonstrated by the kept nses' own `register!` calls + `my.kb`,
   and the modeling skill is taught there, not in the registry internals),
   `:seon.render` (the renderer ENGINE — agents consume rendered output, they
   don't author against it), `:seon.ai.tokens` (a `chars/4` one-liner). These
   stay indexed + grep-able via `seon.agent.search`, just not dumped in full.
   `my.*` nses (`my.kb`, agent-authored code) are ALREADY
   rendered full by the `my.*` rule in [[full-source-ns?]] — they do NOT
   belong here; this whitelist is ONLY for the seon.* framework tools.
   Shared by the boot indexer (which stores their real file source — see
   `seon.client/ns-row`) and [[namespaces-section]] (which renders them
   FULL while the rest of the framework is DROPPED from the rendered
   section — still indexed + searchable via seon.agent.search /
   render-namespace)."
  #{:seon.agent.todo :seon.db :seon.agent.search
    :seon.agent.fs :seon.agent.message :seon.agent.lifecycle})

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
  (str "; Real loaded code, CURATED: the few namespaces you USE or OWN are\n"
       "; shown in FULL (your my.* code, third-party business code, your\n"
       "; current namespace, and a curated seon.* tool set) — each its whole\n"
       "; file. The rest of the seon framework is NOT shown here; query any\n"
       "; ns or fn by name (it stays indexed + searchable). Full namespaces\n"
       "; are ordered by RECENCY: most-recently-modified LAST."))

(defn- render-one
  "Render ONE included ns through the SINGLE renderer
   ([[seon.ctx/render-namespace]]) at the chosen detail LEVEL, flat (depth
   0 — no require-recursion; the section renders each ns once). `:full`
   yields the whole-ns view (real file source + members); `:signature`
   yields the `(signatures)` API-surface block. Returns the rendered
   text, or nil when render-namespace produces nothing (empty-store edge:
   a full ns with blank source and no members)."
  [db nm detail]
  (let [txt (-> (ctx/render-namespace
                  {:seon.ns/name      nm
                   :seon.render/depth 0
                   :seon.render/detail detail
                   :seon.db/db        db})
                :seon.render/text
                str/trim)]
    ;; render-namespace emits a `; namespace x` label even for an
    ;; empty body (`;; (no recorded source/fns/schemas)`); a FULL ns with
    ;; nothing real to show is omitted from the section (the boot indexer
    ;; guarantees real text for every full row, so this is only the
    ;; empty-store edge).
    (when-not (or (str/blank? txt)
                  (and (= detail :full)
                       (str/includes? txt "(no recorded source/fns/schemas)")))
      txt)))

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
                 (try (ctx/current-ns {:seon.agent/id id :seon.db/db db})
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
        blocks    (keep (fn [[nm _tx]] (render-one db nm :full)) full-rows)]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
