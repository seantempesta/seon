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

(defn- fn-signature
  "ONE public fn rendered as a SIGNATURE line — `(sym arglist)` with the
   body ELIDED, optionally a trailing `; first-docstring-line`. Reuses the
   conventional signature shape from `seon.ctx/fn-block-ai`: `:seon.fn/sym`
   + `:seon.fn/arglists` (a `pr-str`'d string like `\"([a b] [a b c])\"`)
   build the head; `:seon.fn/doc`'s first line is the one-line comment. NO
   source parse — the indexed projections already carry name+arglist+doc,
   so the API surface is assembled, never re-derived from the body."
  [{:seon.fn/keys [sym arglists doc]}]
  (let [a    (when arglists (str/trim arglists))
        head (cond
               (or (nil? a) (str/blank? a))
               (str "(" sym " …)")
               ;; `([a b] [a b c])` → multi-arity: keep the wrapping parens.
               (and (str/starts-with? a "(") (str/ends-with? a ")"))
               (str "(" sym " " (subs a 1 (dec (count a))) ")")
               :else
               (str "(" sym " " a ")"))
        d1   (when (and doc (not (str/blank? doc)))
               (str/trim (first (str/split-lines doc))))]
    (if d1
      (str head "  ; " d1)
      head)))

(defn- manifest-block
  "The ONE signature-manifest block for the `seon.*` framework bulk: a `;;`
   pointer header, then ONE `<namespace name=… kind=\"signatures\">` tag per
   framework ns whose body is its PUBLIC fns rendered as SIGNATURES
   ([[fn-signature]] — name + arglist + one-line docstring, BODIES ELIDED).
   Private (`defn-`) fns are skipped — they stay indexed/retrievable, but
   the API view is the public surface.

   `manifest-names` is the full sorted list of framework ns-name keywords in
   the manifest; `ns->fns` maps an ns-name keyword to its already-filtered
   PUBLIC fn maps (`:seon.fn/sym`/`/arglists`/`/doc`), in display order. An
   ns WITH public fns becomes a signatures tag; an ns with NONE (no indexed
   fns, or all private) is still NAMED in a trailing `;;` line so it stays
   discoverable + queryable. Returns nil when `manifest-names` is empty
   (nothing to manifest → no block)."
  [manifest-names ns->fns]
  (when (seq manifest-names)
    (let [pointer
          (str ";; other seon framework namespaces — PUBLIC fn signatures only\n"
               ";; (bodies elided). Query a fn's FULL source by name when you\n"
               ";; need it, e.g.:\n"
               ";;   (seon.db/query '[:find ?sym ?src :where\n"
               ";;                    [?n :seon.ns/name :seon.warn]\n"
               ";;                    [?f :seon.fn/ns ?n]\n"
               ";;                    [?f :seon.fn/sym ?sym]\n"
               ";;                    [?f :seon.fn/source ?src]])\n"
               ";; (swap :seon.fn/ns·sym·source for :seon.schema/ or :seon.test/\n"
               ";;  to read that ns's schemas or tests the same way; or call\n"
               ";;  (seon.ctx/render-namespace {:seon.ns/name :the.ns}) for a\n"
               ";;  whole-ns view incl. private helpers).")
          tags
          (keep (fn [nm]
                  (let [sigs (->> (get ns->fns nm)
                                  (map fn-signature)
                                  (remove str/blank?))]
                    (when (seq sigs)
                      (str "<namespace name=\"" (name nm) "\" kind=\"signatures\">\n"
                           (str/join "\n" sigs)
                           "\n</namespace>"))))
                manifest-names)
          ;; nses with no public fns to show — still NAMED so they stay
          ;; discoverable + queryable, just without a signature tag.
          bare   (remove (fn [nm]
                           (seq (->> (get ns->fns nm)
                                     (map fn-signature)
                                     (remove str/blank?))))
                         manifest-names)
          bare-line (when (seq bare)
                      (str ";; (no public fns indexed yet — query by name): "
                           (str/join ", " (map name bare))))
          blocks (cond-> (cons pointer tags)
                   bare-line (concat [bare-line]))]
      (str/join "\n\n" blocks))))

(def ^:private namespaces-header
  (str ";; Real loaded code. The few namespaces you USE or OWN are shown in\n"
       ";; FULL (your my.* code, third-party business code, your current\n"
       ";; namespace, and a curated seon.* tool set); the rest of the seon\n"
       ";; framework is shown as PUBLIC fn SIGNATURES (name + arglist +\n"
       ";; one-line doc, bodies elided) in a manifest at the end — query any\n"
       ";; fn's full source by name on demand. Full namespaces are ordered\n"
       ";; by RECENCY: most-recently-modified LAST."))

(defn namespaces-section
  "CURATED `<namespace>` body (curated-namespaces 2026-06-21). One
   `<namespace name=\"…\">` tag per FULL-rendered ns ([[render-full?]]:
   every `my.*` ns, every THIRD-PARTY `acme` ns, the agent's CURRENT ns,
   and the curated [[full-source-whitelist]] seon.* whitelist), each
   carrying its REAL FULL FILE SOURCE — NO clipping. Every OTHER `seon.*`
   framework ns ([[included-ns?]] minus the full set) collapses into ONE
   [[manifest-block]] at the end: per-ns PUBLIC fn SIGNATURES (name +
   arglist + one-line doc, BODIES ELIDED — see [[fn-signature]]), with a
   clear query-for-full-source pointer. Private fns are skipped (the API
   view is the public surface; private helpers stay retrievable on demand).

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
        ;; The manifest nses get PUBLIC fn signatures, not bare names. One
        ;; join (fn → its ns name) pulls every public fn's sym/arglists/doc;
        ;; private fns (`:seon.fn/private? true`) are excluded outright — the
        ;; manifest is the API view. `:seon.fn/arglists`/`/doc` are OPTIONAL
        ;; projections (absent for some rows), so they are looked up per-row
        ;; below rather than required in the :where (which would silently
        ;; drop arg-less/doc-less fns). Grouped ns-name → seq of fn maps.
        manifest-ns-set (into #{} (map first) manifest-rows)
        ns->fns (when (seq manifest-ns-set)
                  (->> (db/query
                         {:seon.db/db db
                          :seon.db/query
                          '[:find ?nm ?priv (pull ?f [:seon.fn/sym
                                                      :seon.fn/arglists
                                                      :seon.fn/doc])
                            :where
                            [?n :seon.ns/name ?nm]
                            [?f :seon.fn/ns ?n]
                            [?f :seon.fn/sym _]
                            [(get-else $ ?f :seon.fn/private? false) ?priv]]})
                       (filter (fn [[nm priv _m]]
                                 (and (contains? manifest-ns-set nm)
                                      (not priv))))
                       (group-by first)
                       (reduce-kv
                         (fn [acc nm rows*]
                           (assoc acc nm
                                  (->> rows*
                                       (map (fn [[_nm _priv m]] m))
                                       (sort-by :seon.fn/sym))))
                         {})))
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
        ;; The framework bulk → ONE signature manifest block (per-ns public
        ;; fn signatures, name-sorted; fn-less nses still named).
        manifest (manifest-block (sort manifest-ns-set) ns->fns)
        blocks (cond-> (vec tags)
                 manifest (conj manifest))]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
