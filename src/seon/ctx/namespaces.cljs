(ns seon.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt
   (context-v4 §2.3): one `<namespace name=\"…\">` tag per included ns,
   recency-ordered most-recently-modified LAST. Symbol-wired into the
   composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.namespaces/namespaces-section`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`.

   Owns the COMPACT ns-render machinery (elide-defn-body + the indexing
   reader helpers + compact-ns-source). The SELECTION rule
   (`included-ns?`) and the agent's current-ns
   derivation stay in the spine `seon.ctx`; the on-demand whole-namespace
   render (`seon.ctx/render-namespace`, the agent-callable capability the
   system prompt documents) also stays in the spine.

   The section NEVER re-reads files at render time (code-as-data): the
   boot indexer (`seon.client/ns-row`) is the ONE file-reader; this
   section renders EVERY `:seon.ns/name` row — including SOURCELESS rows
   (the tee's nested `{:seon.ns/name kw}` upsert mints those for a prior
   agent's register!/defines). Two render outcomes, one rule per row:
     - real file text persisted (full-source-ns?)   → rendered whole;
     - stub/sourceless but the ns OWNS member rows  → reconstituted
       from the program graph (ns form + each :seon.fn/:seon.schema/
       :seon.test source, tx-ordered) — agent-authored nses render as
       the real code they are, exactly what bulk-load resume replays;
     - bare stub, no members (or seed provenance)   → OMITTED ENTIRELY
       (no tag): a bare (ns x) line carries no source and has baited a
       fabricated quotation before. Such a ns's members stay reachable
       via the store query the header points at."
  (:require
    [clojure.string :as str]
    [cljs.tools.reader :as tools-reader]
    [cljs.tools.reader.reader-types :as reader-types]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.schema :as schema]))

(defn- ns-stub?
  "True when `src` is blank or the boot indexer's minimal `(ns x)`
   stub for `ns-str`."
  [ns-str src]
  (or (str/blank? src)
      (= (str/trim src) (str "(ns " ns-str ")"))))

;; ============================================================
;; elide-defn-body (B9) — render a fn as its REAL `defn` with the BODY
;; replaced by `…`. NOT a synthetic `[fn …]` header: standard Clojure,
;; verbatim from `:seon.fn/source` through the END of the arglist —
;; keeping the `(defn ^meta name`, the docstring, the `{:malli/schema …
;; :test …}` attr-map (so a `:test` USAGE EXAMPLE survives automatically),
;; and the arglist vector. We slice the ORIGINAL source string (NOT
;; pr-str of a read form, which drops `^:async`/`^:private` symbol
;; metadata and reformats) by reading the form with an INDEXING reader
;; and taking the arglist vector's `:end-line`/`:end-column`. Fail-soft:
;; any read/locate failure shows the WHOLE source (verbose > wrong).
;; ============================================================

(defn- line-col->index
  "Absolute 0-based string index of `line` (1-based)/`col` (1-based) in
   `src`. Returns nil when the position is out of range."
  [src line col]
  (let [lines (str/split-lines src)]
    (when (<= 1 line (count lines))
      (let [;; chars in all lines BEFORE the target line, +1 per newline.
            before (reduce + (map #(inc (count %)) (take (dec line) lines)))]
        (+ before (dec col))))))

(defn- read-defn-form
  "Read the single leading form from `src` with an INDEXING reader so
   collections carry `:line`/`:column`/`:end-line`/`:end-column` source
   metadata. `cljs.core/*ns*` is bound to a placeholder so auto-resolved
   `::keys`/`::foo-request` keywords (ubiquitous in seon fn sources)
   read instead of throwing 'Invalid token: ::'. Returns the form, or
   nil on any read failure."
  [src]
  (try
    (binding [cljs.core/*ns* 'seon.ctx.elide-placeholder]
      (let [rdr (reader-types/indexing-push-back-reader (str src))]
        (tools-reader/read {:eof ::eof :read-cond :allow} rdr)))
    (catch :default _ nil)))

(defn- arglist-end-index
  "0-based index in `src` of the char just AFTER the relevant arglist's
   closing `]`. For a single-arity defn that's the first top-level
   `vector?` element (the arglist). For a multi-arity defn (no top-level
   arglist vector — arities are `([a] …)` lists) it's the END of the
   LAST arity-list, so every arity's signature is preserved and only the
   trailing close-paren is appended by the caller. Returns nil when no
   arglist can be located (fail-soft → caller shows whole source)."
  [src form]
  (when (and (seq? form) (contains? '#{defn defn-} (first form)))
    (let [end-of (fn [meta-carrier]
                   (let [{:keys [end-line end-column]} (meta meta-carrier)]
                     (when (and end-line end-column)
                       ;; end-column is the col AFTER the closing char.
                       (line-col->index src end-line end-column))))
          top-vec (first (filter vector? form))
          arities (filter #(and (seq? %) (vector? (first %))) form)]
      (cond
        top-vec (end-of top-vec)
        (seq arities) (end-of (last arities))
        :else nil))))

(defn- string->source-token
  "The VERBATIM source-text form of string value `s` as it appears inside
   Clojure source: wrapped in `\"`, with only `\\` and `\"` escaped — NEWLINES
   STAY LITERAL (a multi-line docstring spans real newlines in the file, so
   `pr-str` — which escapes `\\n` — does NOT match the source). This is the
   token to find/replace in the original head slice."
  [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- clip-docstring-first-line
  "In compact-head text `head` (the slice from char 0 through the arglist),
   truncate a multi-line `defn` docstring to its FIRST LINE only. `form` is
   the already-read defn form; the docstring is `(nth form 2)` when a
   string. Finds the docstring's VERBATIM source token
   ([[string->source-token]] — newlines literal, so it matches the file)
   and replaces it with the first-line-only token. Fail-soft: any miss
   (single-line doc, no doc, token not found) leaves `head` unchanged —
   verbose > wrong. COMPACT-PATH ONLY; current-ns FULL and on-demand
   render-namespace keep the whole docstring."
  [head form]
  (let [doc (when (and (seq? form) (> (count form) 2)) (nth form 2))]
    (if (and (string? doc) (str/includes? doc "\n"))
      (str/replace-first head
                         (string->source-token doc)
                         (string->source-token (first (str/split-lines doc))))
      head)))

(defn elide-defn-body
  "Render `source` (a fn's `:seon.fn/source`) as its real `defn` with the
   BODY elided to ` …)`: the original text through the end of the arglist,
   then `\\n  …)`. Multi-arity keeps every arity SIGNATURE and appends a
   single `…)`. Any multi-line docstring is CLIPPED to its first line
   ([[clip-docstring-first-line]]). Fail-soft: returns `source` UNCHANGED
   when it isn't a locatable single `defn` (better verbose than wrong).
   Pure; no DB. COMPACT-PATH ONLY (the single call site is
   [[compact-ns-source]]); the current-ns FULL branch and on-demand
   render-namespace never call this, so they keep full bodies AND full
   docstrings."
  {:malli/schema [:=> [:cat :string] :string]}
  [source]
  (let [src  (str source)
        form (read-defn-form src)
        end  (arglist-end-index src form)]
    (if (and end (< end (count src)))
      (str (clip-docstring-first-line (str/trimr (subs src 0 end)) form) "\n  …)")
      src)))

;; ============================================================
;; compact-ns-source (B9) — the COMPACT body for an included ns that is
;; NOT the agent's current ns: the `(ns …)` form (its `:require` deps),
;; every `:seon.schema` in FULL (the schemas ARE the contract the fns'
;; `:malli/schema` metadata references — only fn BODIES elide), and each
;; `:seon.fn` as its elided `defn` (signature + attr-map incl. any `:test`
;; usage example, body → `…`). Standalone `:seon.test` (deftest) source
;; is DROPPED from the prompt BODY entirely (full tests live in the
;; on-demand `render-namespace` deep view). Keeps `<namespaces>` to a few
;; KB instead of the full-source dump.
;; ============================================================

(defn- extract-ns-form
  "The leading `(ns …)` form of `src` as a trimmed string (it carries the
   ns's `:require` deps the agent needs to see), or the synthesized
   `stub` when `src` has no readable `(ns …)` head. Slices the original
   text via the indexing reader's end position — verbatim, no reformat."
  [src stub]
  (let [s (str src)]
    (if (str/blank? s)
      stub
      (let [form (read-defn-form s)]
        (if (and (seq? form) (= 'ns (first form)))
          (let [{:keys [end-line end-column]} (meta form)
                end (when (and end-line end-column)
                      (line-col->index s end-line end-column))]
            (if (and end (<= end (count s)))
              (str/trim (subs s 0 end))
              stub))
          stub)))))

(defn- schema-full-source
  "The FULL definition text for one schema row: prefer the live registry
   shape (`(seon.schema/register! <key> <shape>)`), fall back to the
   persisted `:seon.schema/source`. Schemas render in full — they ARE the
   contract fn `:malli/schema` metadata references."
  [key source]
  (let [shape (when (keyword? key)
                (try (schema/schema-definition key) (catch :default _ nil)))]
    (cond
      shape (str "(seon.schema/register! " (pr-str key) "\n  " (pr-str shape) ")")
      (not (str/blank? source)) (str/trim source)
      :else (str "(seon.schema/register! " (pr-str key) " …)"))))

(defn- compact-ns-source
  "Compact body for `ns-kw`: ns form + every schema (full) + every fn
   (elided defn). Standalone tests dropped. `stub` is the synthesized
   `(ns x)` used when the ns row is sourceless. Returns nil when the ns
   owns no schema/fn rows (a bare stub renders nothing useful)."
  [db ns-kw src stub]
  (let [;; schema KEYS + tx only — NO source in the join. A `get-else`
        ;; clause over `:seon.schema/source` throws `Invalid entid` in
        ;; datahike-cljs whenever that attr is registered-but-uninstalled
        ;; (a store with only registry-shape, sourceless schema rows — the
        ;; same uninstalled-attr trap namespaces-section documents below).
        ;; Sources are joined SEPARATELY (a normal :where clause returns
        ;; empty, never throws, on an uninstalled attr) and looked up in
        ;; code, defaulting to "" so a sourceless row still renders.
        schema-keys (db/query
                      {:seon.db/db db
                       :seon.db/query
                       [:find '?key '?tx
                        :where
                        ['?n :seon.ns/name ns-kw]
                        ['?s :seon.schema/ns '?n]
                        ['?s :seon.schema/key '?key '?tx]]})
        schema-srcs (into {}
                      (db/query
                        {:seon.db/db db
                         :seon.db/query
                         [:find '?key '?ssrc
                          :where
                          ['?n :seon.ns/name ns-kw]
                          ['?s :seon.schema/ns '?n]
                          ['?s :seon.schema/key '?key]
                          ['?s :seon.schema/source '?ssrc]]}))
        fn-rows     (db/query
                      {:seon.db/db db
                       :seon.db/query
                       [:find '?sym '?fsrc '?tx
                        :where
                        ['?n :seon.ns/name ns-kw]
                        ['?f :seon.fn/ns '?n]
                        ['?f :seon.fn/sym '?sym]
                        ['?f :seon.fn/source '?fsrc '?tx]]})]
    (when (or (seq schema-keys) (seq fn-rows))
      (let [schemas (->> schema-keys
                         (sort-by second)
                         (map (fn [[k _tx]] (schema-full-source k (get schema-srcs k ""))))
                         distinct)
            fns     (->> fn-rows
                         (sort-by #(nth % 2))
                         (map (fn [[_ s _]] (elide-defn-body s)))
                         distinct)]
        (str/join "\n\n"
          (concat [(extract-ns-form src stub)] schemas fns))))))

(def ^:private namespaces-header
  (str ";; Real loaded code, most-recently-modified LAST. Bodies are elided\n"
       ";; here (API surface only) — read any fn's full source by name, e.g.:\n"
       ";;   (seon.db/query {:seon.db/query '[:find ?sym ?src :where\n"
       ";;                                    [?n :seon.ns/name :seon.warn]\n"
       ";;                                    [?f :seon.fn/ns ?n]\n"
       ";;                                    [?f :seon.fn/sym ?sym]\n"
       ";;                                    [?f :seon.fn/source ?src]]})\n"
       ";; (swap :seon.fn/ns·sym·source for :seon.schema/ or :seon.test/\n"
       ";;  to read that ns's schemas or tests the same way)"))

(defn namespaces-section
  "One `<namespace name=\"…\">` tag per included ns ([[seon.ctx/included-ns?]] —
   EVERY indexed :seon.ns row minus *.internal and *-test; ONE structural
   rule, no allow-list), ordered by
   RECENCY: most-recently-modified LAST (tx of the `:seon.ns/name`
   datom — bumped by the tee's nested upsert on every define), name as
   the tie-break, so the stable core set forms a stable cache
   prefix and the churning ns sits nearest the tail.

   Render depth per row (B9 body-size tiering):
     - the agent's OWN CURRENT ns (from `:seon.agent/id` via
       [[seon.ctx/current-ns]]) renders FULL source — the agent sees its
       complete working code. nil id (inspector / no-agent path) → no ns
       is current → everything compact.
     - every OTHER included ns renders COMPACT ([[compact-ns-source]]):
       the `(ns …)` form + each schema in FULL + each fn as its elided
       `defn` (signature + attr-map incl. any `:test` usage example, body
       → `…`); standalone deftests DROPPED. This holds the body to a few
       KB; full bodies + tests stay in the on-demand `render-namespace`.

   The stub branch renders COMPACT from member rows regardless of
   provenance — a stub row asserted by the `:core-seed` boot tx is
   compiled core whose members are the boot-indexed `:seon.fn`/
   `:seon.schema` rows of the WHOLE compiled ns; `compact-ns-source`
   ELIDES fn bodies, so this is the API surface (signatures + full
   schemas), NOT the 200k+-char dump that on-demand full bodies would
   be. An agent-authored stub renders the same way. A stub with no
   member rows yields a blank body and is OMITTED (nothing to show).
   Never a render-time file read."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [;; The agent's current ns (latest successful eval's ns) → the ONE
        ;; ns rendered FULL. nil id (inspector path) → nil → all compact.
        cur-ns (when id
                 (try (ctx/current-ns {:seon.agent/id id :seon.db/db db})
                      (catch :default _ nil)))
        ;; EVERY ns row, sourced or not — the tee's nested
        ;; `{:seon.ns/name kw}` upsert mints SOURCELESS rows (a prior
        ;; agent's register!/defines), and requiring `:seon.ns/source`
        ;; in the join silently dropped them from the prompt (the S-21
        ;; killer: the agent could not see :my.workout anywhere).
        ;; Sources joined separately and looked up in code.
        sources (into {}
                      (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?nm ?src
                           :where
                           [?n :seon.ns/name ?nm]
                           [?n :seon.ns/source ?src]]}))
        rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?tx
                         :where
                         [?n :seon.ns/name ?nm ?tx]]})
                    (filter (fn [[nm _]] (ctx/included-ns? nm)))
                    (sort-by (fn [[nm tx]] [tx (name nm)])))
        blocks (keep
                 (fn [[nm _tx]]
                   (let [ns-str   (name nm)
                         src      (get sources nm)
                         stub     (str "(ns " ns-str ")")
                         current? (= nm cur-ns)
                         ;; The agent's CURRENT ns renders FULL source (its
                         ;; complete working code); every OTHER ns renders
                         ;; COMPACT (ns form + schemas full + fns elided,
                         ;; tests dropped). A stub row — compiled core
                         ;; (`:core-seed` provenance, members are boot-indexed
                         ;; rows) OR agent-authored — compact-renders from its
                         ;; member rows: `compact-ns-source` elides fn bodies,
                         ;; so this is the API surface, not a dump. A
                         ;; sourceless/stub current ns has no full text to
                         ;; show, so it too renders compact. body is nil only
                         ;; when there are no member rows to render — such a ns
                         ;; is OMITTED ENTIRELY (nothing to show); its members
                         ;; remain discoverable via the store query the header
                         ;; points at.
                         body
                         (cond
                           (and current? (not (ns-stub? ns-str src)))
                           (str/trim src)

                           (ns-stub? ns-str src)
                           (compact-ns-source
                             db nm (if (str/blank? src) stub src) stub)

                           :else
                           (compact-ns-source db nm src stub))]
                     (when-not (str/blank? body)
                       (str "<namespace name=\"" ns-str "\">\n"
                            body
                            "\n</namespace>"))))
                 rows)]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
