(ns seon.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt: CURATED,
   not render-everything. Each rendered ns is a `;; ── namespace x ──`
   (full) or `;; ── namespace x (signatures) ──` (manifest) comment-block:

     - FULL source (whole file, NO clipping) for the few nses an agent
       actually USES or OWNS:
         (a) every `my.*` ns           — the human's/agent's own code;
         (b) every THIRD-PARTY ns      — non-seon, non-my (the
             `SEON_EXTRA_SRC` `acme` business logic the agent needs whole);
         (c) the agent's CURRENT ns    — its complete working code;
         (d) a small curated whitelist of `seon.*` framework tools
             ([[full-source-whitelist]] — `:seon.agent.todo`).
       The FULL-source decision is one shared rule:
       [[full-source-ns?]] already covers (a) + (d); (b) is the
       not-`seon.` structural fall-through; (c) is the current-ns check.
     - SIGNATURE-MANIFEST for every OTHER `seon.*` framework ns: one block
       per ns listing its PUBLIC fns as SIGNATURES —
       `(fn-name [arglist] \"first docstring line\")`, BODIES ELIDED —
       plus a pointer to fetch any one's full source on demand. No bodies,
       no `register!` dump, no private helpers (they stay indexed +
       retrievable, but the API view is the public surface).

   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.namespaces/namespaces-section`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`.

   The section NEVER re-reads files at render time (code-as-data): the
   boot indexer (`seon.client/ns-row`) is the ONE file-reader, and it
   stores the REAL full file text for exactly the nses rendered full
   (the same [[full-source-ns?]] rule + the extra-src roots),
   leaving the framework bulk a `(ns x)` stub — which this section never
   renders as a body, only as a NAME in the manifest. So the full rows
   here are always real file source, never a reconstructed stub."
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
  "The ONE selection rule for the `<namespace>` tags: EVERY indexed
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
   `my.*` nses (`my.kb`, `my.soul`, agent-authored code) are ALREADY
   rendered full by the `my.*` rule in [[full-source-ns?]] — they do NOT
   belong here; this whitelist is ONLY for the seon.* framework tools.
   Shared by the boot indexer (which stores their real file source — see
   `seon.client/ns-row`) and [[namespaces-section]] (which renders them
   FULL while the rest of the framework is a name manifest)."
  #{:seon.agent.todo})

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
   gets the minimal `(ns x)` stub at boot and is named in the manifest."
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
   Everything else is a `seon.*` framework ns → NAME-MANIFEST only."
  [nm cur-ns]
  (boolean
    (or (= nm cur-ns)
        (full-source-ns? nm)
        (third-party-ns? nm))))

(def ^:private manifest-pointer
  "The `;;` header that precedes the signature-manifest tags: tells the
   agent the framework bulk shows PUBLIC fn signatures only and how to
   fetch any fn's FULL source — the same `render-namespace` the section
   itself delegates to (one renderer, named in the pointer)."
  (str ";; other seon framework namespaces — PUBLIC fn signatures only\n"
       ";; (bodies elided). Read a fn's FULL source (or a whole ns incl.\n"
       ";; private helpers) on demand with render-namespace, e.g.:\n"
       ";;   (seon.ctx/render-namespace {:seon.ns/name :seon.db})\n"
       ";; or query a single fn's source by name:\n"
       ";;   (seon.db/query '[:find ?sym ?src :where\n"
       ";;                    [?n :seon.ns/name :seon.warn]\n"
       ";;                    [?f :seon.fn/ns ?n]\n"
       ";;                    [?f :seon.fn/sym ?sym]\n"
       ";;                    [?f :seon.fn/source ?src]])"))

(def ^:private namespaces-header
  (str ";; Real loaded code. The few namespaces you USE or OWN are shown in\n"
       ";; FULL (your my.* code, third-party business code, your current\n"
       ";; namespace, and a curated seon.* tool set); the rest of the seon\n"
       ";; framework is shown as PUBLIC fn SIGNATURES (name + arglist +\n"
       ";; one-line doc, bodies elided) in a manifest at the end — query any\n"
       ";; fn's full source by name on demand. Full namespaces are ordered\n"
       ";; by RECENCY: most-recently-modified LAST."))

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
    ;; render-namespace emits a `;; ── namespace x ──` header even for an
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
       [[full-source-whitelist]] seon.* tools — each a `;; ── namespace x ──`
       block carrying its REAL FULL FILE SOURCE (+ any member rows), unclipped.
     - SIGNATURE (`:seon.render/detail :signature`) for every OTHER `seon.*`
       framework ns — a `;; ── namespace x (signatures) ──` block of PUBLIC
       fn signatures (name + arglist + one-line doc, BODIES ELIDED).
       Private fns are skipped; they stay retrievable via the same renderer.

   The full blocks are ordered by RECENCY (tx of the `:seon.ns/name` datom —
   bumped by the tee's nested upsert on every define), name as the
   tie-break, so the stable core forms a stable cache prefix and the
   churning ns sits nearest the tail. The signature blocks are name-sorted
   and sit LAST after a [[manifest-pointer]] (they change only when the
   framework roster changes).

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
        ;; EVERY included ns row, recency-ordered, partitioned into the
        ;; FULL set (rendered as full tags) and the framework bulk
        ;; (signature tags). One :seon.ns/name datom per ns carries its tx.
        rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?tx
                         :where
                         [?n :seon.ns/name ?nm ?tx]]})
                    (filter (fn [[nm _]] (included-ns? nm)))
                    (sort-by (fn [[nm tx]] [tx (name nm)])))
        {full-rows true manifest-rows false}
        (group-by (fn [[nm _tx]] (render-full? nm cur-ns)) rows)
        ;; FULL tags — whole-ns view via the ONE renderer, recency-ordered.
        full-tags (keep (fn [[nm _tx]] (render-one db nm :full)) full-rows)
        ;; SIGNATURE tags — public-API view via the SAME renderer,
        ;; name-sorted, behind the query-for-source pointer.
        sig-tags  (keep (fn [nm] (render-one db nm :signature))
                        (sort (map first manifest-rows)))
        manifest  (when (seq sig-tags)
                    (str/join "\n\n" (cons manifest-pointer sig-tags)))
        blocks    (cond-> (vec full-tags)
                    manifest (conj manifest))]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
