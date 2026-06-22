(ns seon.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt
   (context-v4 §2.3): CURATED, not render-everything (curated-namespaces
   2026-06-21). The old render dumped EVERY seon.* framework ns as a
   compact `(ns …)` + `register!` + elided-defn surface — 70+ tags of
   mostly-noise the agent never calls. That render is GONE. Now:

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
     - NAME-MANIFEST for every OTHER `seon.*` framework ns: one block
       listing just the names + a pointer to fetch any one's source on
       demand. No bodies, no `register!` dump.

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
;; [[namespaces-section]] (the curated `<namespaces>` prompt body):
;; one rule, one writer, no drift. Pure string/keyword/symbol fns —
;; no dependency on anything in `seon.ctx`.
;; ============================================================

(defn hidden-ns-name?
  "Rule 1: a `*.internal` namespace (or any of its children) is
   indexed but NEVER rendered — the V3-A naming convention IS the
   filter. String/keyword/symbol tolerant."
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
   agent IN FULL (curated-namespaces 2026-06-21) — the few seon.* tools an
   agent actually USES, worth their whole source. Start it here; this is
   the clear EDITABLE def to extend.
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

(defn- manifest-block
  "The ONE name-only block for the `seon.*` framework bulk: a `;;` comment
   listing every non-rendered framework ns NAME, with a clear pointer to
   query any one's source on demand. Returns nil when `names` is empty
   (nothing to manifest → no block)."
  [names]
  (when (seq names)
    (str ";; other seon framework namespaces (not shown full — query a fn's\n"
         ";; source by name when you need it, e.g.:\n"
         ";;   (seon.db/query '[:find ?sym ?src :where\n"
         ";;                    [?n :seon.ns/name :seon.warn]\n"
         ";;                    [?f :seon.fn/ns ?n]\n"
         ";;                    [?f :seon.fn/sym ?sym]\n"
         ";;                    [?f :seon.fn/source ?src]])\n"
         ";; (swap :seon.fn/ns·sym·source for :seon.schema/ or :seon.test/\n"
         ";;  to read that ns's schemas or tests the same way; or call\n"
         ";;  (seon.ctx/render-namespace {:seon.ns/name :the.ns}) for a\n"
         ";;  whole-ns view):\n"
         ";; "
         (str/join ", " (map name names)))))

(def ^:private namespaces-header
  (str ";; Real loaded code. The few namespaces you USE or OWN are shown in\n"
       ";; FULL (your my.* code, third-party business code, your current\n"
       ";; namespace, and a curated seon.* tool set); the rest of the seon\n"
       ";; framework is named in a manifest at the end — query any of those\n"
       ";; by name on demand. Full namespaces are ordered by RECENCY:\n"
       ";; most-recently-modified LAST."))

(defn namespaces-section
  "CURATED `<namespace>` body (curated-namespaces 2026-06-21). One
   `<namespace name=\"…\">` tag per FULL-rendered ns ([[render-full?]]:
   every `my.*` ns, every THIRD-PARTY `acme` ns, the agent's CURRENT ns,
   and the curated [[full-source-whitelist]] seon.* whitelist), each
   carrying its REAL FULL FILE SOURCE — NO clipping. Every OTHER `seon.*`
   framework ns ([[included-ns?]] minus the full set) collapses
   into ONE name-only [[manifest-block]] at the end, with a clear
   query-for-source pointer.

   The full tags are ordered by RECENCY (tx of the `:seon.ns/name` datom —
   bumped by the tee's nested upsert on every define), name as the
   tie-break, so the stable core forms a stable cache prefix and the
   churning ns sits nearest the tail. The manifest is name-sorted and
   sits LAST (it changes only when the framework roster changes).

   `*.internal` and `*-test` nses are excluded outright
   ([[included-ns?]]). A full-source ns whose stored source is
   blank renders nothing (omitted); the boot indexer guarantees real text
   for every full row, so this is only the empty-store edge. NEVER a
   render-time file read — the boot indexer is the one reader."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [;; The agent's current ns (latest successful eval's ns) → rendered
        ;; FULL even if it is a framework ns. nil id (inspector path) →
        ;; nil → no ns is forced current.
        cur-ns (when id
                 (try (ctx/current-ns {:seon.agent/id id :seon.db/db db})
                      (catch :default _ nil)))
        ;; Sources joined SEPARATELY from the name rows (requiring
        ;; :seon.ns/source in the join silently drops sourceless rows; a
        ;; plain :where on a registered-but-uninstalled attr returns empty,
        ;; never throws). Looked up in code below.
        sources (into {}
                      (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?nm ?src
                           :where
                           [?n :seon.ns/name ?nm]
                           [?n :seon.ns/source ?src]]}))
        ;; EVERY included ns row, recency-ordered, partitioned into the
        ;; FULL set (rendered as tags) and the framework bulk (manifest).
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
        ;; FULL tags — real file source, trimmed, NO clipping. A blank
        ;; source (empty-store edge) yields no tag.
        tags   (keep
                 (fn [[nm _tx]]
                   (let [src (str/trim (str (get sources nm)))]
                     (when-not (str/blank? src)
                       (str "<namespace name=\"" (name nm) "\">\n"
                            src
                            "\n</namespace>"))))
                 full-rows)
        ;; The framework bulk → ONE name-only manifest block, name-sorted.
        manifest (manifest-block
                   (sort (map (comp name first) manifest-rows)))
        blocks (cond-> (vec tags)
                 manifest (conj manifest))]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
