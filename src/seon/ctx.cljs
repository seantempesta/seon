(ns seon.ctx
  "Context generation — the v4 composer (context-v4-repl-realism
   2026-06-11): the prompt IS a REPL session. One static `<system>`
   header, ALL the loaded namespaces as the body (`<namespace>` tags,
   recency-ordered), the agent's own entity as a map, what the human
   currently sees, the reactive warnings/todos, the threaded
   transcript of PAST turns (each `<turn>` carries its woken-by `<user>`
   + REPL-faithful evals), and a status line +
   clean REPL prompt. Layout is ordered top→bottom = static→volatile:
   everything above `<warnings>` is the provider-cacheable prefix.

   This namespace owns:
     - the `:seon.ctx/*` section schemas (`:seon.ctx/name`,
       `:seon.ctx/priority`, the `:seon.ctx/section` map shape). The
       one slot attr is `:seon.render/ai` (string = verbatim doctrine,
       symbol = late-bound section fn); `:seon.render/html` is the
       optional debug-view twin (§2.8b).
     - `assemble-context` — the ONE composer. Core default
       sections MERGED with the agent's own `:seon.agent/ctx` sections
       by one priority sort (override-by-name). Render guard (a broken
       section renders an inline error line, never breaks assembly)
       and the per-agent section char budget live here.
     - the selection rules: [[included-ns?]] (ALL nses under the
       store-configured [[included-prefixes]] — defaults seon.* + my.*
       — minus *.internal; the ONE rule, no lists; downstreams extend
       by one transact onto [[config-ref]]) and [[full-source-ns?]]
       (which rows the boot indexer inlines real file text for).
     - the core default section fns (system, namespaces,
       your-entity, live-tile, warnings, transcript, prompt) and the
       derived read API they share (messages / evals / session-evals /
       current-ns / turns-since-inbound / …) — every read takes the
       composer's `:seon.db/db` snapshot so one render is one db view.
     - `render-namespace` — the standalone whole-namespace render
       (ns + fns + schemas + tests, :ai or :html), an agent-callable
       core capability.

   Section fns receive ONE map:
     {:seon.db/db        <db value>
      :seon.agent/id     <id string>          ; convenience, = entity id
      :seon.agent/entity <the agent's own entity, pulled ONCE>
      :seon.ctx/section  <this section's map>} ; per-section overrides
   and return a string; \"\" suppresses the section.

   seon.agent requires this ns and re-exports the agent-taught read
   API (seon.agent/messages …) as transitional aliases; the P6
   agent.cljs split finishes the relocation."
  (:require
    [clojure.string :as str]
    [cljs.pprint :as pprint]
    [cljs.reader :as edn]
    [cljs.tools.reader :as tools-reader]
    [cljs.tools.reader.reader-types :as reader-types]
    [seon.db :as db]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    [seon.handlers.fn :as h-fn]
    [seon.handlers.ns :as h-ns]
    [seon.handlers.schema :as h-schema]
    [seon.handlers.test :as h-test]
    [seon.render :as render]
    [seon.render.live-tile :as live-tile]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; ============================================================
;; Section schemas. A section is a plain map — the SAME shape whether
;; it lives in code (core defaults) or as a component entity on
;; the agent's :seon.agent/ctx vector.
;; ============================================================

(declare decode-section core-default-ctx)

;; Program-graph ns rows — registered HERE (not seon.agent) because
;; this ns loads first and its render-namespace schemas reference
;; :seon.ns/name. The rest of the :seon.fn/:seon.schema attr family
;; stays in seon.agent until the P6 split finds them a real home.
(schema/register! :seon.ns/name    [:keyword {:seon.db/identity true}])
(schema/register! :seon.ns/source  :string)
;; The dependency-edge SET for a namespace: the required ns-NAMES as
;; keywords (aliases dropped — those are reconstituted from
;; :seon.ns/source's ns form in Phase 2). Captured + diff-upserted at
;; the tee from the analyzer (seon.analyzer-info/ns-requires). A
;; `[:vector :keyword]` maps to :db.cardinality/many keyword via the
;; bridge (db/internal form->cardinality) — INTENDED: a queryable set
;; of dep edges, so the load can topo-sort by requires (the one fix
;; that unblocks DB-layer load — see db-is-the-running-system PRD).
;; Cardinality-many means a plain upsert ACCUMULATES; the tee writes a
;; diff (additions + explicit retractions) so the stored set EXACTLY
;; matches the analyzer's current requires.
(schema/register! :seon.ns/requires [:vector :keyword])

(schema/register! :seon.ctx/name     :keyword)
(schema/register! :seon.ctx/priority :int)

;; The section map contract (validated at seon.agent/add-section! AND
;; at transact! like everything else). :seon.render/ai is the ONE slot:
;; a string renders verbatim (doctrine — content as source); a
;; qualified symbol resolves LATE via seon.eval/lookup-value at every
;; render. Optional :seon.render/html twin (symbol or hiccup literal).
(schema/register! :seon.ctx/section
  [:map
   [:seon.ctx/name     :seon.ctx/name]
   [:seon.ctx/priority :seon.ctx/priority]
   [:seon.render/ai    :seon.render/ai]
   [:seon.render/html  {:optional true} :seon.render/html]])

(def default-turns-cap 20)

(defn turns-cap
  "Read `:seon.agent/turns-cap` from the agent entity. Returns the
   configured cap or `default-turns-cap` when the attr is absent.
   Use this at every cap-check site so the agent can override the
   default by transacting its own value. Optional `db` value (the
   composer's snapshot); defaults to the live conn."
  ([agent-id] (turns-cap agent-id nil))
  ([agent-id db]
   (or (:seon.agent/turns-cap
         (db/entity (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                      db (assoc :seon.db/db db))))
       default-turns-cap)))

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'my.agent.seon`."
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn current-session
  "Most-recent `:seon.agent.session` entity for `agent-id`. Returns nil if
   the agent has no sessions yet (fresh boot before `start-session!`).
   Optional `db` value (the composer's snapshot) — the run-3 bug class
   fix: section fns must read the SAME db value the composer renders
   from, never reach back to the live conn mid-render."
  ([agent-id] (current-session agent-id nil))
  ([agent-id db]
   (let [a (db/entity (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                        db (assoc :seon.db/db db)))]
     (last (sort-by :seon.agent.session/at (:seon.agent/sessions a))))))

;; ============================================================
;; The classifier — ONE pass over the full index; every section
;; consumes the resulting model, none re-classifies. Replaces the
;; six scattered name filters (core-ns-name?, exemplar-ns?
;; duplication, the warn internal-attr-ns? regex, …).
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
   [[render-namespace]]. STRUCTURAL, like [[hidden-ns-name?]]: the suffix
   IS the filter. String/keyword/symbol tolerant."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (str/ends-with? s "-test")))

;; ------------------------------------------------------------
;; Included-prefix config — the customize-with-data row behind the
;; ONE selection rule. A downstream consumer (an unmodified-core
;; product) extends the prefix set by ONE transact:
;;
;;   (seon.db/transact!
;;     {:seon.db/tx-data [{:seon.ctx/config-id "core"
;;                         :seon.ctx/included-prefixes ["acme."]}]})
;;
;; and its `acme.*` namespaces render as `<namespace>` tags for every
;; agent on the next render; `[:db/retract <eid>
;; :seon.ctx/included-prefixes "acme."]` removes it again
;; (cardinality-many — adds accumulate, never clobber the defaults).
;; The `*.internal` exclusion stays STRUCTURAL: it applies to every
;; prefix, configured or default.
;; ------------------------------------------------------------

(schema/register! :seon.ctx/config-id [:string {:seon.db/identity true}])
(schema/register! :seon.ctx/included-prefixes [:vector :string])

(def config-ref
  "Lookup ref of THE core ctx-config entity — the carrier of
   `:seon.ctx/included-prefixes` (and any future composer config rows).
   Seeded if absent by the composer ([[assemble-context]])."
  [:seon.ctx/config-id "core"])

(def default-included-prefixes
  "The built-in prefix set: the core (`seon.*`) and the human's
   world (`my.*`). Seeded onto the config entity at first render;
   downstreams ADD to the row, they never need to restate these."
  ["seon." "my."])

(defn included-prefixes
  "The CURRENT included-prefix set — the config entity's
   `:seon.ctx/included-prefixes` rows when present (sorted vector;
   cardinality-many reads back as a set), else
   [[default-included-prefixes]]. Optional `db` snapshot (the composer
   threads its render db); the no-arg arity reads the live conn and
   falls back to the defaults when no conn is bound (pure-name tests).
   The `installed-schema` gate is load-bearing: datahike throws on a
   lookup ref whose attr the conn never installed (installs are lazy,
   at first transact)."
  {:malli/schema [:function
                  [:=> [:cat] [:vector :string]]
                  [:=> [:cat :seon.db/db-val] [:vector :string]]]}
  ([] (if-let [db (some-> db/*conn* deref)]
        (included-prefixes db)
        (vec (sort default-included-prefixes))))
  ([db]
   (->> (or (when (contains? (db/installed-schema db) :seon.ctx/config-id)
              (seq (:seon.ctx/included-prefixes
                     (db/entity {:seon.db/db db :seon.db/ref config-ref}))))
            default-included-prefixes)
        sort
        vec)))

(defn- prefix-included?
  "True when ns string `s` falls under config prefix `p`: `\"acme.\"`
   matches the bare root `acme` and every `acme.*` child (the same
   root-or-child rule the hardwired seon/my checks used)."
  [s p]
  (let [root (if (str/ends-with? p ".") (subs p 0 (dec (count p))) p)]
    (boolean (or (= s root) (str/starts-with? s (str root "."))))))

(defn included-ns?
  "The ONE selection rule for the `<namespace>` tags (context-v4 §2.3,
   r2): every namespace under an included prefix (store-configured —
   [[included-prefixes]]; defaults `seon.*` + `my.*`) is included
   EXCEPT `*.internal` ([[hidden-ns-name?]]) and `*-test`
   ([[test-ns-name?]]) ones (both STRUCTURAL — apply to all prefixes).
   No lists, no budgets — a new namespace auto-includes the moment its
   `:seon.ns` row exists; a downstream prefix auto-includes the moment
   its config row is transacted. Pass the per-render `prefixes` (the
   composer computes them ONCE per render from its db snapshot); the
   1-arity reads the live conn."
  {:malli/schema [:function
                  [:=> [:cat [:or :string :keyword :symbol]] :boolean]
                  [:=> [:cat [:or :string :keyword :symbol]
                        [:vector :string]] :boolean]]}
  ([ns-name] (included-ns? ns-name (included-prefixes)))
  ([ns-name prefixes]
   (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
     (boolean (and (not (hidden-ns-name? s))
                   (not (test-ns-name? s))
                   (some #(prefix-included? s %) prefixes))))))

(defn- base-ns-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.agent.search-test` → `seon.agent.search`).
   Non-test names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(defn full-source-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) carries its
   REAL FULL FILE TEXT as `:seon.ns/source`: every `my.*` ns (the
   human's world — always inlined), including `-test` siblings (the
   `-test` suffix is stripped to the subject ns first). Used by the
   boot indexer (`seon.client/ns-row`) to decide which rows get the
   file read; [[namespaces-section]] renders whatever depth the row
   has — one rule, one writer, no drift. Every other (non-`my.*`) ns
   gets the minimal `(ns x)` stub at boot and compact-renders from its
   indexed member rows."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (and (not (hidden-ns-name? s))
                  (my-ns-name? base)))))

;; ------------------------------------------------------------
;; Pretty-print + truncation helpers.
;; ------------------------------------------------------------

(defn host-timezone
  "IANA tz string for the running pod, or 'UTC' if Intl is unavailable."
  []
  (try
    (or (some-> (js/Intl.DateTimeFormat.) .resolvedOptions .-timeZone) "UTC")
    (catch :default _ "UTC")))

(defn truncate-edn
  "pr-str a value, truncate to ~2 KB for display in the eval log
   (v1.md §1's three-tier storage rule: DB datoms hold projections,
   not full content)."
  ([v] (truncate-edn v 2048))
  ([v limit]
   (let [s (pr-str v)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit)
            " …⟨⚠ TRUNCATED at " limit " of " n " chars — display clip, "
            "the underlying value is complete⟩")
       s))))

(defn message-label
  "Transcript label for a message's `:seon.agent.message/from` ref (a pulled
   map carrying `:seon.user/id` / `:seon.agent/id`), resolved by REF
   KIND: the user → `user`, this agent itself → `assistant`, any other
   agent → `agent-<id>`."
  [from own-id]
  (cond
    (:seon.user/id from)             "user"
    (= own-id (:seon.agent/id from)) "assistant"
    (:seon.agent/id from)            (str "agent-" (:seon.agent/id from))
    :else                            "unknown"))

(defn- read-error-envelope
  "Best-effort EDN decode of a `:seon.eval/error-data` instrument-envelope
   string. Returns the envelope map, or nil when blank/unreadable. Never
   throws. (The plain `:seon.eval/error` string is now stored pre-rendered
   and legible by `seon.eval/render-error-string`, so it is NOT decoded
   here — `format-eval-row` surfaces it as-is.)"
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (edn/read-string s)
         (catch :default _ nil))))

(def eval-render-cap
  "Per-eval rendered-result char cap for the transcript context section.
   Context-SAFETY invariant: no single eval's result may dominate the
   agent's whole context. One 9.7M-char `pull` result used to blow
   render-prompt to ~9.8M chars; capping each rendered result here keeps
   `transcript-section` bounded regardless of how large any individual
   `:seon.eval/result-edn` blob is."
  1500)

(def core-eval-render-cap
  "Render cap for CORE-AUTHORED eval rows (the creation-turn
   tutorial evals — tile wiring, store-inventory, the my.kb.system
   read — and any future core-scripted eval). These are OUR
   output: well-controlled, written by compiled core code, never
   by an LLM — so they render IN FULL (user directive 2026-06-11:
   'our own context should be well controlled… don't be cheap'). The
   1500-char agent cap defeated the inventory's own purpose: the
   per-attr result clipped BEFORE the user-domain rows rendered, so
   agents never saw e.g. :my.workout. 50,000 is a runaway BACKSTOP
   against a pathological store, not a working limit — core eval
   results are expected to fit whole far below it. (Stored
   `:seon.eval/result-edn` is itself bounded upstream at
   `seon.eval/store-edn-cap`, 16,384, so in practice this cap never
   bites.) AGENT evals keep [[eval-render-cap]] + the loud ⚠ clip —
   they can return literally anything."
  50000)

(defn cap-result
  "Truncate a rendered eval-result string to `eval-render-cap`,
   appending a LOUD truncation marker (shown of full chars) so a
   clipped display can never pass for complete content — the observed
   failure mode is an agent summarizing INVENTED content from a
   silently-clipped render. Operates on the ALREADY-stringified result
   (`:seon.eval/result-edn` is a pr-str string), so no re-quoting.
   Nil-safe."
  ([s] (cap-result s eval-render-cap))
  ([s limit]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit)
            " …⟨⚠ TRUNCATED at " limit " of " n " chars — the DISPLAY is "
            "clipped, the underlying data is complete; do not summarize "
            "or quote beyond what is shown⟩")
       s))))

(def message-render-cap
  "Per-message rendered-content char cap for a `<user>`/`<from>` line in
   the threaded transcript: each woken-by message must be individually
   bounded or a single pasted blob could blow the context. 4000 (≈1k
   tokens) keeps any realistic chat turn whole; the full content stays in
   the db ((seon.agent/messages))."
  4000)

(defn cap-result-body
  "Like `cap-result`, but for an eval RESULT body specifically: when the
   value is clipped by size, append a GUIDING clip message that teaches
   the agent how to get less/narrower output, instead of a bare elision
   marker. A clip is feedback, not a failure (errors are values the agent
   reads).

   Only the SIZE clip (a huge scalar/string that overflows the display
   cap) gets this guide. Large COLLECTIONS are already bounded upstream
   with their own row-count guide in `:seon.eval/result-edn`
   (`seon.eval/render-result-edn`), so their preview fits under the cap
   and no second guide fires here — no double-noising.

   The full value is always available as the live var `result/<id>`
   (transcript-redesign-2026-06-18 — the agent references the var
   directly, no `(result …)` call); the clip is display-only."
  ([s] (cap-result-body s eval-render-cap nil))
  ([s limit] (cap-result-body s limit nil))
  ([s limit eid]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (let [ref (if eid (str "result/" eid) "result/<id>")]
         (str (subs s 0 limit)
              " …⟨⚠ TRUNCATED at " limit " of " n " chars — the DISPLAY "
              "is clipped, the live value is COMPLETE⟩"
              "\n;; Never summarize or quote beyond the shown " limit
              " chars — bind and process the value with code: " ref
              " holds it whole; (count " ref "), subs, get-in/filter, or "
              "paged take/drop. To get less next time: a :find aggregate, "
              "a tighter :where, or pull fewer attrs."))
       s))))

(def unverified-narration-marker
  "What a model-authored result-claim comment is rewritten to in the
   transcript. Deliberately does NOT match [[result-claim-re]] itself
   (no `=>`/`⇒` after the semicolons), so the rewrite is idempotent."
  ";; [unverified narration — not a real result]")

(def ^:private result-claim-re
  "A comment posing as a REPL result read: one-or-more `;`, optional
   whitespace, then `=>` or `⇒` — `;; =>`, `;; ⇒`, `; =>`, `;⇒`,
   `;;=>` all match — through to end of line. Shape-match, line-
   position preserving: applies anywhere in a line, so inline claims
   (`(+ 1 2) ;; => 3`) lose only the comment, never the code.
   `[^\\n]*` instead of `(?m)…$` because CLJS `str/replace` rebuilds
   the RegExp with only the `g` flag, dropping `m`."
  #";+[ \t]*(?:=>|⇒)[^\n]*")

(defn neutralize-result-claims
  "Rewrite every model-authored result-claim comment in `s` to
   [[unverified-narration-marker]], dropping the claimed value
   entirely (the claimed value is the poison: a later turn reads
   `;; => {...}` as a real result and trusts data that was never
   computed — two live fabrication incidents, F13/F14).

   PROVENANCE GATE, not regex luck: this runs ONLY on the
   model-authored transcript channels — `:seon.eval/narration` and
   `:seon.eval/source` — BEFORE [[format-eval-row]] composes the row.
   Real result lines (`=> <value> ;; result/<id>`) are appended by the
   composer itself AFTER this rewrite and never pass through it.
   Idempotent: the marker doesn't match the claim shape."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (str/replace s result-claim-re unverified-narration-marker))

(defn- strip-comment-prefix
  "Strip any leading `;`/`⚠`/`↻`/`=>` comment markers + whitespace from
   one line, returning the bare text. Lets the renderer re-prefix a line
   idempotently whether the stored text already carried a `;;` (a real
   comment, the scratch-def note, the repair `↻` breadcrumb, a
   `render-malli-error` `;;` line) or not (raw bare prose). The `↻`/`⚠`
   glyphs and the `=>`/`⇒` arrows are re-emitted by the caller as the
   line's role demands."
  [line]
  (-> (str line)
      (str/replace #"^[\s;]*(?:[↻⚠]\s*)?" "")
      str/trimr))

(defn- comment-lines
  "Render multi-line comment-preamble text `s` as literal `;;` lines —
   one `;; <text>` per non-blank source line, leading comment markers
   normalized away first (idempotent re-prefix). Returns a seq of
   `;;`-prefixed strings (empty when `s` is blank). The repair `↻`
   breadcrumb keeps its glyph: a line whose stripped text starts with a
   non-`;;` marker is handled by the caller; here we re-add only `;;`."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (->> (str/split-lines s)
         (map str/trim)
         (remove str/blank?)
         (map (fn [line]
                ;; The repair breadcrumb is stored with a leading `↻`;
                ;; keep the glyph so a wrong-but-valid repair stays
                ;; visible. Everything else becomes a plain `;;` line.
                (if (str/starts-with? line "↻")
                  (str ";; " line)
                  (str ";; " (strip-comment-prefix line))))))))

(defn- error-lines
  "Render an error/guidance body `s` as the REPL FAILURE shape: the FIRST
   non-blank line becomes the `=> ✗ <headline>` output line (visually
   DISTINCT from `;;` comments — it is REPL output, not narration), every
   CONTINUATION line a plain `;;` comment (so a read-error's source slice
   + `^` caret stay ALIGNED — only a leading `;`/`⚠`/`ERROR`/`✗` marker is
   stripped, never the interior indentation the caret depends on). One
   crystal-clear guidance block, never a stack trace. Returns a single
   newline-joined string (\"\" when blank)."
  [s]
  (let [strip-marker
        (fn [line]
          ;; Drop ONLY a leading comment marker / legacy `ERROR` keyword /
          ;; prior `⚠`/`✗`, plus the single following space — KEEP any
          ;; deeper indentation (the caret + source-slice lines rely on it).
          (-> (str line)
              (str/replace #"^\s*;+[ \t]?" "")
              (str/replace #"(?i)^ERROR[ \t]?" "")
              (str/replace #"^[⚠✗][ \t]?" "")))
        lines (->> (str/split-lines (str s))
                   (drop-while str/blank?))]
    (if (empty? lines)
      ""
      (str/join "\n"
        (cons (str "=> ✗ " (strip-marker (first lines)))
              (map #(str ";; " (strip-marker %)) (rest lines)))))))

(defn- format-eval-row
  "REPL-faithful render of one eval (transcript-redesign-2026-06-18):
   the form's comment-preamble as literal `;;` lines, the form verbatim
   (or the parinfer-repaired source), captured print output, then the
   value as a `=> <value>` output line trailing ` ;; result/<id>` (or
   the error as a `=> ✗ <guidance>` line). NO `<eval>` tag and NO
   `<ns>=>` history prompt prefix — the live `<your-ns>=>` cursor lives
   once at the very END of the context; each row reads as plain
   comments + form + REPL output, the exact shape the system prompt
   teaches.

     ;; add 1 and 2
     (+ 1 2)
     => 3 ;; result/EVLabc-123

   The `=>` line is visually DISTINCT from the `;;` comments (it starts
   with `=>`, not semicolons) — fixing the agents-confusing-output-for-
   comments bug (ari-2606180804). The trailing ` ;; result/<id>` is the
   LIVE VAR HANDLE: the agent references `result/<id>` directly to reuse
   the value. PRIOR-SESSION evals (`prior?` true) render the value
   WITHOUT the handle (their vars died with the restart; the resume
   boundary marker says so once). A clipped value appends `(N of M)` to
   the handle so the agent knows the display is a partial view.

   FAILURES render `=> ✗ <crystal-clear guidance>` (never a stack trace,
   never a `;; result/<id>` — there is no value to reuse): the
   pre-rendered legible `:seon.eval/error` string (read/compile/runtime —
   crystal-clear at the source) or a Malli instrumentation envelope via
   `render-malli-error`. A COMMENT-ONLY row (blank source — trailing
   `;;` lines / bare prose the agent typed with no following form)
   renders just its `;;` preamble, no form, no output.

   The rendered result/error body of an AGENT eval is capped at
   `eval-render-cap` chars (context-SAFETY invariant — agent code can
   return literally anything); a CORE-AUTHORED row
   (`:seon.ctx/core-authored?` true) renders at [[core-eval-render-cap]].

   On SUCCESS a reactive 'won't persist' note (#7) is DERIVED from the
   eval's source via [[seon.eval/scratch-def-note]] and appended as a
   trailing `;;` line — pure, no stored attr, recomputed each render so
   it FOLLOWS the form. The repair `↻ auto-balanced …` breadcrumb (when
   a span was parinfer-repaired) rides in the preamble, keeping a
   wrong-but-valid repair catchable."
  ([row] (format-eval-row row false))
  ([{src        :seon.eval/source
     ok?        :seon.eval/ok?
     res        :seon.eval/result-edn
     out        :seon.eval/output
     err        :seon.eval/error
     err-data   :seon.eval/error-data
     eid        :seon.eval/id
     narr       :seon.eval/narration
     core? :seon.ctx/core-authored?}
    prior?]
   (let [envelope    (read-error-envelope err-data)
         limit       (if core? core-eval-render-cap eval-render-cap)
         comment-only? (str/blank? (str src))
         ;; Comment-preamble — the agent's `;;`/prose thinking, neutralized
         ;; against fabricated result-claims BEFORE we re-prefix.
         preamble    (comment-lines
                       (when (and narr (not (str/blank? narr)))
                         (neutralize-result-claims narr)))
         ;; The form, verbatim (or repaired) — neutralized for any inline
         ;; result-claim, capped. Omitted for a comment-only row.
         form-ln     (when-not comment-only?
                       (cap-result (neutralize-result-claims src) limit))
         ;; Captured println/prn output — shown above the value like a
         ;; real REPL prints before returning. Bounded by the same cap.
         out-ln      (when (and (string? out) (not (str/blank? out)))
                       (cap-result (str/trimr out) limit))
         ;; The result / error body, rendered as REPL output.
         result-ln
         (cond
           comment-only? nil

           ok?
           (let [raw     (str (or res "nil"))
                 full    (count raw)
                 clipped? (> full limit)
                 ;; The value body — clipped (with the size guide) when
                 ;; huge. The guide carries its own `\n;;` lines (a
                 ;; result/<id> dig hint), shown below the `=>` line.
                 v       (cap-result-body raw limit eid)
                 ;; The live VAR HANDLE rides the `=>` line as a trailing
                 ;; `;; result/<id>`: the agent references `result/<id>`
                 ;; directly. Prior-session rows carry NO handle (their
                 ;; vars died with the process). A clip appends `(N of M)`
                 ;; so the agent knows the shown value is partial.
                 handle  (when-not prior?
                           (str " ;; result/" eid
                                (when clipped? (str " (" limit " of " full ")"))))
                 lines   (str/split-lines v)]
             ;; Prefix ONLY the first line with `=>` + handle; continuation
             ;; lines (a clip's own `;;` guide) stay as the body wrote them.
             (str "=> " (first lines) handle
                  (when (next lines)
                    (str "\n" (str/join "\n" (rest lines))))))

           (einstrument/instrument-error? envelope)
           (cap-result-body
             (error-lines (einstrument/render-malli-error envelope)) limit eid)

           (and (string? err) (not (str/blank? err)))
           ;; `:seon.eval/error` is stored pre-rendered + crystal-clear
           ;; (`seon.eval/render-error-string` / `read-error-message` /
           ;; the undeclared-var message) — render as a `=> ✗` failure
           ;; line, plain-clip (NOT the "narrow your query" result guide).
           (cap-result (error-lines err) limit)

           :else "=> ✗ <no result>")
         ;; Reactive 'won't persist' note (#7) — DERIVED from source, no
         ;; stored attr; recomputed each render so it follows the form.
         note   (when (and ok? (not comment-only?))
                  (seval/scratch-def-note src))]
     (->> [(when (seq preamble) (str/join "\n" preamble))
           form-ln
           out-ln
           result-ln
           (when (and note (not (str/blank? note)))
             (str ";; " (strip-comment-prefix note)))]
          (remove nil?)
          (str/join "\n")))))

;; ------------------------------------------------------------
;; Read API — what the agent calls from its REPL to walk its own
;; state. All sync, all pulling from the live conn. Match v1.md §5's
;; map-arg convention with smart defaults.
;;
;; Agent-id resolution: callers pass `:seon.agent/id` explicitly OR
;; run inside a `(seon.db/with-agent id …)` scope (the normal boot/
;; run-loop path). `resolve-id` throws a clear ex-info when neither
;; is available — we don't guess, we don't fall back to a hardcoded
;; process-global default (audit P1).
;; ------------------------------------------------------------

(defn- resolve-id
  "Return the explicit id when supplied, else `(db/current-agent-id)`,
   else throw with a clear message. Centralized so every read API
   surfaces the same instruction when called outside any agent scope."
  [id]
  (or id
      (db/current-agent-id)
      (throw (ex-info
               (str "seon.agent: no agent-id in scope — pass "
                    ":seon.agent/id explicitly or call inside "
                    "(seon.db/with-agent id …).")
               {::error :seon.agent/no-agent-id}))))

(defn messages
  "Last N messages of MY conversation, oldest-first. The conversation
   is DERIVED — `from = me OR to ∋ me` — never stored as a membership
   attr (the retired per-message agent back-ref). Queries the
   message log DIRECTLY, not via :seon.agent.session/turns → :seon.agent.turn/
   messages (the turn-walk was the run-3 demo killer: standalone
   inbound messages never attach to a turn). The from/to refs are
   pulled with their id attrs so transcript labeling resolves by ref
   kind. Default {:seon.agent/n 50}. Optional `:seon.db/db` — the
   composer threads its render snapshot here so every section reads
   the SAME db value (the run-3 bug class fix)."
  ([] (messages {}))
  ([{:seon.agent/keys [n id] db :seon.db/db :or {n 50}}]
   (let [id     (resolve-id id)
         db     (or db @db/*conn*)
         my-eid (:db/id (db/entity {:seon.db/db db
                                    :seon.db/ref [:seon.agent/id id]}))
         rows   (when my-eid
                  (db/query
                    {:seon.db/db db
                     :seon.db/query
                     '[:find (pull ?m [* {:seon.agent.message/from
                                          [:db/id :seon.user/id :seon.agent/id]
                                          :seon.agent.message/to
                                          [:db/id :seon.user/id :seon.agent/id]}])
                       :in $ ?me
                       :where
                       (or-join [?m ?me]
                         [?m :seon.agent.message/from ?me]
                         [?m :seon.agent.message/to ?me])
                       [?m :seon.agent.message/at _]]
                     :seon.db/args [my-eid]}))
         msgs   (->> rows
                     (map first)
                     (sort-by #(.getTime ^js (:seon.agent.message/at %))))]
     (vec (take-last n msgs)))))

(defn current-turn
  "Most-recent :seon.agent.turn on the agent's current session — the one
   that's :running, or the last :done if no turn is open."
  ([] (current-turn {}))
  ([{:seon.agent/keys [id] db :seon.db/db}]
   (let [id      (resolve-id id)
         session (current-session id db)]
     (last (sort-by :seon.agent.turn/at (:seon.agent.session/turns session))))))

(schema/register! ::core-authored? :boolean)

(defn core-authored-turn?
  "True when the turn was SCRIPTED BY THE CORE rather than
   authored by the agent's LLM: it carries no prompt
   (`:seon.agent.turn/prompt-chars` 0 or absent). An LLM turn is
   CONSTITUTED by its prompt — `run-turn!` always renders one and
   records its char count on the turn-open tx — so a promptless turn
   means no model was consulted and every eval on it is
   core-written tutorial code (`seon.client/creation-evals!`
   passes prompt-text \"\"). Chosen over the other candidate markers
   because it is structural and load-bearing: the eval txs' origin is
   overwritten to `:agent` by `eval-batch!`'s per-form tx-context (and
   the turn-open tx is `:system` for BOTH paths), and
   `:seon.agent.turn/woken-by` is also absent on harness-driven LLM
   turns. No name-lists, no flags to keep in sync.

   `turn` is a pulled turn map OR a datahike Entity (the
   [[session-evals]] walk hands entities) — `:any` is the third-party
   boundary exception: Entity is a datahike deftype, not `map?`."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [turn]
  (zero? (or (:seon.agent.turn/prompt-chars turn) 0)))

(defn session-evals
  "ALL :seon.eval entries for `agent-id`, oldest-first across ALL
   sessions, each tagged with its owning `:seon.agent.session/id` —
   the transcript's cross-restart read (context-v4 §2.8: prior-session
   evals render too, behind a resume boundary marker) — and with
   `:seon.ctx/core-authored?` ([[core-authored-turn?]] on the
   owning turn), which [[format-eval-row]] reads to render core
   tutorial rows IN FULL while agent rows keep the eval cap. Walks
   agent → sessions → turns → evals. Optional `db` snapshot."
  [agent-id db]
  (let [a (db/entity (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                       db (assoc :seon.db/db db)))]
    (vec
      (for [s (sort-by :seon.agent.session/at (:seon.agent/sessions a))
            t (sort-by :seon.agent.turn/at (:seon.agent.session/turns s))
            e (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
        (assoc (into {} e)
               :seon.agent.session/id-of-session
               (:seon.agent.session/id s)
               ::core-authored?
               (core-authored-turn? t))))))

(defn evals
  "Last N :seon.eval entries for the agent's current session,
   oldest-first. Walks :seon.agent.session/turns → :seon.agent.turn/evals (Platform
   migrated eval storage to this shape in commit 5786247).
   Default {:seon.agent/n 20}. Optional `:seon.db/db` snapshot."
  ([] (evals {}))
  ([{:seon.agent/keys [n id] db :seon.db/db :or {n 20}}]
   (let [id      (resolve-id id)
         session (current-session id db)
         es      (for [t (sort-by :seon.agent.turn/at (:seon.agent.session/turns session))
                       e (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
                   e)]
     (vec (take-last n es)))))

(defn current-ns
  "The agent's current namespace — derived from the latest successful
   eval's :seon.eval/ns. Falls back to (home-ns id) when no successful
   eval has run yet. Reactive: the next successful eval that switches
   ns (via `(ns …)`) shows up here on the next call. See
   docs/seon/concepts/reactive-context. Optional `:seon.db/db`
   snapshot (the composer threads its render db here)."
  ([] (current-ns {}))
  ([{:seon.agent/keys [id] db :seon.db/db}]
   (let [id (resolve-id id)
         ;; All evals across all sessions, latest first.
         all-evals
         (for [s (:seon.agent/sessions
                   (db/entity (cond-> {:seon.db/ref [:seon.agent/id id]}
                                db (assoc :seon.db/db db))))
               t (:seon.agent.session/turns s)
               e (:seon.agent.turn/evals t)
               :when (true? (:seon.eval/ok? e))]
           e)
         latest (last (sort-by :seon.eval/at all-evals))]
     (or (:seon.eval/ns latest) (home-ns id)))))

(defn turns-since-inbound
  "Count of :seon.agent.turn entities in the agent's current session whose
   :seon.agent.turn/at is strictly after the latest INBOUND message's :at —
   a message with to ∋ me AND from ≠ me (sender-agnostic: the user and
   other agents both reset the window). Drives `run-agentic-loop!`'s
   cap policy. Derived from the message + turn log; nothing stored.
   See docs/seon/concepts/reactive-context."
  ([] (turns-since-inbound {}))
  ([{:seon.agent/keys [id] db :seon.db/db}]
   (let [id      (resolve-id id)
         db      (or db @db/*conn*)
         session (current-session id db)
         turns   (:seon.agent.session/turns session)
         ;; lookup refs are NOT auto-resolved in query args — bind the
         ;; eid explicitly so the ref-valued ?me joins work.
         my-eid  (:db/id (db/entity {:seon.db/db db
                                     :seon.db/ref [:seon.agent/id id]}))
         latest-inbound-at
         (when my-eid
           (->> (db/query
                  {:seon.db/db db
                   :seon.db/query
                   '[:find (max ?at)
                     :in $ ?me ?cap
                     :where
                     [?m :seon.agent.message/to ?me]
                     [?m :seon.agent.message/from ?f]
                     [(not= ?f ?me)]
                     ;; hop-exhausted messages must NOT extend the loop:
                     ;; without this filter two live agent loops reset
                     ;; each other's window forever (the wake guard only
                     ;; gates loop STARTS, not in-flight loops).
                     [(get-else $ ?m :seon.agent.message/hops 0) ?h]
                     [(< ?h ?cap)]
                     [?m :seon.agent.message/at ?at]]
                   :seon.db/args [my-eid warn/hop-cap]})
                ffirst))]
     (count
       (if latest-inbound-at
         (filter #(> (.getTime ^js (:seon.agent.turn/at %))
                     (.getTime ^js latest-inbound-at))
                 turns)
         turns)))))

(defn ctx-entities
  "Pull the agent's :seon.agent/ctx vector with each :seon.ctx entity
   inlined. Sorted by :seon.ctx/priority. Useful for inspection
   and for the agent's layout-editing flow."
  ([] (ctx-entities {}))
  ([{:seon.agent/keys [id]}]
   (let [id (resolve-id id)]
     (->> (db/pull {:seon.db/pull-pattern '[{:seon.agent/ctx [*]}]
                    :seon.db/ref [:seon.agent/id id]})
          :seon.agent/ctx
          (map decode-section)
          (sort-by :seon.ctx/priority)
          vec))))

;; ------------------------------------------------------------
;; Section fns (v1.md §5.2). Each takes :seon.render/system-input
;; {:seon.db/db :seon.agent/id} optionally with :seon.ctx/section
;; (the :seon.ctx entity that named this section, so the fn can read
;; per-section overrides like :seon.agent/n). Returns a string;
;; empty string = section suppressed by the composer.
;; ------------------------------------------------------------

(def system-text
  "The ONE universal `<system>` block (context-v4 §2.1) — the concept
   paragraphs (a–h) plus the four standing behavioral teachings, and
   nothing else. Usage teaching lives in the rendered namespace
   sources (docstrings + `;;` comments) and the startup tutorial
   evals, never here.

   BYTE-IDENTICAL for every agent and every turn — a `def`, not a fn
   of the agent: the agent id lives in the status line at the very
   END of the prompt (§2.9), so this block is one shared cacheable
   artifact across the whole cluster. CACHE-PREFIX invariant: no
   timestamps, no ids, no counts — anything volatile lives in
   `prompt-section`. PROVIDER-NEUTRAL: no model or vendor words, ever."
  (str
    "<system>\n"
    "You are at a live Clojure REPL on one human's runtime. The REPL is\n"
    "your only tool: everything you do — read, compute, store, reply,\n"
    "render — is a Clojure form evaluated here.\n"
    "\n"
    "THE LIVE CONTEXT SYSTEM. This whole prompt re-derives from the\n"
    "shared database every turn: every section is a view of NOW, not an\n"
    "accumulating log. Fix a problem and its warning vanishes; store\n"
    "data and the next render shows it; other agents' writes appear on\n"
    "your next turn. The status line and a clean REPL prompt\n"
    "(<your-ns>=>) are the very END of this context — your reply is the\n"
    "next REPL input.\n"
    "\n"
    "EVAL MECHANICS. Your reply is one or more Clojure forms, each\n"
    "preceded by ;; comment lines. There are no tool calls. Anything\n"
    "that is not a form or a ;; comment is a bug — bare prose HAS eaten\n"
    "responses before. The <transcript> below is READ-ONLY history: the\n"
    "runtime adds each form's `=> result` line and the `;; result/<id>`\n"
    "after it — never write a `=>`, `;; result/`, or any <tag> yourself.\n"
    "Your reply is ONLY ;; comments and forms.\n"
    "\n"
    "  Correct shape:                 Wrong shape (don't do this):\n"
    "    ;; first, look around          Let me look around first.\n"
    "    (seon.db/query ...)            (seon.db/query ...)\n"
    "    ;; then, write a reply         Now I'll write the reply.\n"
    "    (seon.db/transact! ...)        (seon.db/transact! ...)\n"
    "\n"
    "THINK IN COMMENTS. The ;; lines BEFORE each form are where your\n"
    "reasoning lives — what you are about to do and why. If you write a\n"
    "sentence, put ;; in front of every line of it.\n"
    "\n"
    "RESULT VARS. Every eval's value is a live var result/<id>, the id\n"
    "shown after its `=>` in the transcript (e.g. `=> 42 ;; result/auC`).\n"
    "Reference result/<id> directly to reuse it — never re-run a form you\n"
    "already computed. A clipped display is NOT a clipped value: dig into\n"
    "a big result with ordinary Clojure (get-in, filter, count) on its\n"
    "result/<id> var instead of re-querying.\n"
    "\n"
    "ERRORS ARE VALUES. Core calls never throw at you — a failure\n"
    "comes back as data, e.g. {:seon.db/ok? false :seon.db/error …}.\n"
    "Read the error map; it names the defect and the fix. Telling your\n"
    "human something \"threw an exception\" when you were handed an error\n"
    "envelope is wrong — nothing was thrown; the failure is a value you\n"
    "read.\n"
    "\n"
    "THE RENDERING SYSTEM. You show your human things with render\n"
    "twins: :seon.render/ai (text for you) + :seon.render/html (hiccup\n"
    "for their screen) — one render, two surfaces. Your live tile and\n"
    "your context sections both ride this shape. A *section* (not just\n"
    "the tile) can carry an :seon.render/html twin — that is where rich\n"
    "panels (tables, images, SVG) go: the agent reads the :ai text, the\n"
    "human sees the :html panel, one section row serving both.\n"
    "\n"
    "THE SHARED STORE. All agents are wired to ONE shared datahike\n"
    "(datomic-style) database; *conn* is ambient — your universe binds\n"
    "it, never thread it. seon.db is rendered below; its docstrings are\n"
    "the API reference. Two laws worth stating once: register an\n"
    "attribute (seon.schema/register!) BEFORE the first transact that\n"
    "uses it, and give every attribute keyword a namespace of at least\n"
    "two dot-separated segments (:my.kb.doc/title — never :doc/title,\n"
    "never :title).\n"
    "\n"
    "THE NAMESPACES BELOW are real loaded code, ordered by RECENCY —\n"
    "most-recently-modified LAST, not dependency order; the runtime\n"
    "loaded them correctly. Each renders COMPACT: the (ns …) form, every\n"
    "schema in FULL (the schemas ARE the contract), and each fn as its\n"
    "real (defn …) with the BODY elided to `…` — signature + docstring +\n"
    "attr-map, enough to CALL it without reading its guts. The ONE\n"
    "exception is YOUR OWN current namespace, shown in FULL so you see\n"
    "your complete working code. To read another ns's full bodies + tests\n"
    "on demand, call\n"
    "(seon.ctx/render-namespace {:seon.ns/name :the.ns}). Namespaces are\n"
    "workspaces: (ns my.domain.thing) moves you there and your context\n"
    "follows your namespace. Your code is my.*, your knowledge is my.kb.*\n"
    "(real schemas per domain); the core is seon.* — call its fns, never\n"
    "redefine them.\n"
    "\n"
    "STANDING TEACHINGS:\n"
    "- Consult stored knowledge FIRST — it is DISCOVERABLE, not dumped:\n"
    "  <inventory> lists every stored KIND + its attrs (run\n"
    "  (seon.db/store-inventory) — your creation turn already did), so\n"
    "  you READ the rows by QUERYING rather than from a wall of text.\n"
    "  Datalog the existing attrs for\n"
    "  anything you need. The inventory lists the data added AFTER\n"
    "  bootstrap; the full system inventory — the core's own\n"
    "  fn/schema/test index included — is one call away:\n"
    "  (seon.db/store-inventory {:seon.db/system? true})\n"
    "  Prior agents already answered many questions; re-deriving a\n"
    "  stored answer is wasted turns.\n"
    "- Store what you verify, without being asked: design (or reuse) a\n"
    "  my.kb.<domain> schema, reference the shared :my.kb/* provenance\n"
    "  attrs, and transact the fact. Knowledge nobody stored is\n"
    "  research the next agent pays for again.\n"
    "- Keep fns small and tight. Add ONE concise :test usage example to\n"
    "  a new defn ONLY when its :malli/schema doesn't already make the\n"
    "  call obvious (generic shapes like [:int]->:boolean):\n"
    "  (defn f {:malli/schema … :test (fn [] (assert (= 4 (f 2 2))))}\n"
    "    [a b] …). It is what the NEXT agent sees INSTEAD of your body, so\n"
    "  it must be self-explanatory AND pass (a failing example surfaces).\n"
    "- A task with 2+ steps: mint one todo per step with\n"
    "  seon.agent.todo/add! BEFORE you start, and complete! each id as\n"
    "  the step lands. Your open todos render every turn with their\n"
    "  ids; an empty <open-todos> section is your done-signal.\n"
    "- Your replies render as markdown on your human's screen — use\n"
    "  structure when it helps (short headings, lists, code fences for\n"
    "  code or data); plain prose otherwise.\n"
    "- A turn serving a question MUST end with (seon.agent/reply! …) in\n"
    "  the SAME response — your human sees NOTHING until reply! lands.\n"
    "  ONE reply per question: once it lands your wake is complete and\n"
    "  the loop stops; a new message will wake you if more is needed.\n"
    "- Building tile or panel hiccup from a sequence: splice children\n"
    "  with (into [:div …] children), never nest a bare seq as one\n"
    "  child. Eval your render fn once at the REPL to eyeball the hiccup\n"
    "  before you wire it onto a surface.\n"
    "- Never write a result you have not evaluated. Your reply is REPL\n"
    "  input, not a transcript — a `;; => 42` you typed yourself is\n"
    "  fiction the next agent may trust. Eval it; the runtime writes the\n"
    "  real value line.\n"
    "- When you store a my.kb.* fact, grade it: record HOW you know it\n"
    "  (a :my.kb/source) and HOW SURE you are (a :my.kb/confidence). A\n"
    "  guess stored as fact is worse than no fact — the next agent\n"
    "  cannot read your certainty from your phrasing.\n"
    "</system>"))

(defn system-section
  "The universal `<system>` header — returns [[system-text]] verbatim.
   A fn only because sections render through the symbol slot; it takes
   the standard section input and ignores it (byte-identical for every
   agent and turn — the whole point)."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  system-text)


;; ============================================================
;; namespaces-section — THE BODY of the prompt (context-v4 §2.3): one
;; `<namespace name="…">` tag per included ns, recency-ordered
;; most-recently-modified LAST. Dissolves `<exemplars>` (exemplars
;; were never a different kind of thing — they are just namespaces)
;; AND `<namespace-context>` (the agent's own ns is just a tag).
;;
;; The section NEVER re-reads files at render time (code-as-data): the
;; boot indexer (`seon.client/ns-row`) is the ONE file-reader; this
;; section renders EVERY `:seon.ns/name` row — including SOURCELESS
;; rows (the tee's nested `{:seon.ns/name kw}` upsert mints those for
;; a prior agent's register!/defines). Two render outcomes, one rule
;; per row:
;;   - real file text persisted (full-source-ns?)   → rendered whole;
;;   - stub/sourceless but the ns OWNS member rows  → reconstituted
;;     from the program graph (ns form + each :seon.fn/:seon.schema/
;;     :seon.test source, tx-ordered) — agent-authored nses render as
;;     the real code they are, exactly what bulk-load resume replays;
;;   - bare stub, no members (or seed provenance)   → OMITTED ENTIRELY
;;     (no tag): a bare (ns x) line carries no source and has baited a
;;     fabricated quotation before. Such a ns's members stay reachable
;;     via the store query the header points at.
;; ============================================================

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
  "One `<namespace name=\"…\">` tag per included ns ([[included-ns?]] —
   ALL seon.* + my.* minus *.internal; ONE rule, no lists), ordered by
   RECENCY: most-recently-modified LAST (tx of the `:seon.ns/name`
   datom — bumped by the tee's nested upsert on every define), name as
   the tie-break, so the stable core set forms a stable cache
   prefix and the churning ns sits nearest the tail.

   Render depth per row (B9 body-size tiering):
     - the agent's OWN CURRENT ns (from `:seon.agent/id` via
       [[current-ns]]) renders FULL source — the agent sees its complete
       working code. nil id (inspector / no-agent path) → no ns is
       current → everything compact.
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
                 (try (current-ns {:seon.agent/id id :seon.db/db db})
                      (catch :default _ nil)))
        ;; The configured prefix set, computed ONCE per render from the
        ;; SAME db snapshot every row is filtered against.
        prefixes (included-prefixes db)
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
                    (filter (fn [[nm _]] (included-ns? (name nm) prefixes)))
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

;; ============================================================
;; render-namespace — the foundational whole-namespace render.
;;
;; Renders ONE namespace (ns source + its fns + schemas + tests) in
;; either :ai text or :html hiccup, recursing into the namespaces it
;; `(:require …)`s. Required nses render FIRST (prepended) so that, read
;; top-to-bottom, a reference resolves before its use. The default
;; context an agent receives is built from this: drop an agent into a
;; near-empty ns that requires a parent agent ns, and depth-1 brings the
;; parent's fns/schemas into view.
;;
;; Pure function of the DB — stores nothing. Per-member output is bounded
;; here (signature + doc by default, full source only for small fns); the
;; clip guardrail is a later backstop, not a crutch.
;; ============================================================

(def ^:private fn-source-inline-threshold
  "Fns whose `:seon.fn/source` is at or under this many chars render
   their full source in the :ai form; larger fns show signature + doc
   only. Keeps a whole-ns render bounded to a few KB."
  240)

(def ^:private member-doc-clip
  "Max chars of a fn docstring surfaced per member in the :ai form."
  280)

(defn- clip
  "Clip `s` to `n` chars with an ellipsis marker. nil-safe."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- parse-require-syms
  "Parse an `(ns … (:require …))` source string and return the vector of
   required namespace symbols (in declaration order, deduped). Handles
   bare-symbol specs (`a.b`) and vector specs (`[a.b :as c :refer […]]`).
   Returns [] on any parse failure or when there's no `(ns …)` form —
   recursion simply stops rather than erroring."
  [src]
  (if (or (nil? src) (str/blank? src))
    []
    (try
      (let [form (edn/read-string src)]
        (if (and (seq? form) (= 'ns (first form)))
          (->> (rest form)
               (filter #(and (seq? %) (= :require (first %))))
               (mapcat rest)
               (keep (fn [spec]
                       (cond
                         (symbol? spec)     spec
                         (sequential? spec) (first spec)
                         :else              nil)))
               (filter symbol?)
               distinct
               vec)
          []))
      (catch :default _ []))))

(defn- pull-ns-data
  "Reverse-ref pull of everything one `:seon.ns` owns: its source plus
   every `:seon.fn` / `:seon.schema` / `:seon.test` whose `:ns` points at
   it. Returns nil when no `:seon.ns` entity exists for `ns-kw` (the
   caller renders a one-line 'not in db' note instead). `:seon.test` is a
   real entity kind (Step 3); its rows are pulled and rendered under the
   ns alongside fns and schemas.

   Guarded by an `entity` existence check first: `db/pull` throws on an
   unresolved lookup-ref, so we confirm presence before pulling."
  [db ns-kw]
  (when (db/entity {:seon.db/db db :seon.db/ref [:seon.ns/name ns-kw]})
    (let [core (db/pull {:seon.db/db db
                         :seon.db/ref [:seon.ns/name ns-kw]
                         :seon.db/pull-pattern
                         '[:seon.ns/source
                           {:seon.fn/_ns     [:seon.fn/sym :seon.fn/arglists
                                              :seon.fn/doc :seon.fn/source
                                              :seon.fn/private? :seon.fn/spec
                                              :seon.fn/schema-error]
                            :seon.schema/_ns [:seon.schema/key :seon.schema/source]}]})
          ;; :seon.test is now a real entity kind (Step 3): `:seon.test/ns`
          ;; IS registered, so this reverse-ref pull resolves. Kept as a
          ;; SEPARATE guarded call (vs. inlining into the `core` pull) for
          ;; cleanliness: a conn that has no `:seon.test` rows for this ns
          ;; yields nil and the merge below is a no-op.
          tests (try
                  (-> (db/pull {:seon.db/db db
                                :seon.db/ref [:seon.ns/name ns-kw]
                                :seon.db/pull-pattern
                                '[{:seon.test/_ns
                                   [:seon.test/sym :seon.test/source
                                    :seon.test/last-passed-at
                                    :seon.test/last-failed-at
                                    :seon.test/last-failure-summary]}]})
                      :seon.test/_ns)
                  (catch :default _ nil))]
      (cond-> core
        (seq tests) (assoc :seon.test/_ns tests)))))

(defn- fn-block-ai
  "One fn rendered for the :ai form: `(sym arglists)` header, clipped
   doc, and full source only when small. Reuses the conventional
   signature shape via `seon.handlers.fn/render-ai` is overkill here
   (that fn also runs test-status queries); we render flat + bounded."
  [{:seon.fn/keys [sym arglists doc source private? spec schema-error]}]
  (let [sig    (when (and arglists (not (str/blank? arglists)))
                 (let [a (str/trim arglists)]
                   (if (and (str/starts-with? a "(") (str/ends-with? a ")"))
                     (str "(" sym " " (subs a 1 (dec (count a))) ")")
                     (str "(" sym " " a ")"))))
        flags  (cond-> []
                 private?      (conj ":private")
                 (some? spec)  (conj (str ":spec " (clip spec 80)))
                 (nil? spec)   (conj ":unspecced")
                 schema-error  (conj (str ":schema-error " (clip schema-error 80))))
        header (str "[fn " sym "]"
                    (when sig (str "  " sig))
                    (when (seq flags) (str "  " (str/join " " flags))))
        small? (and source (<= (count source) fn-source-inline-threshold))
        lines  (cond-> [header]
                 (and doc (not (str/blank? doc)))
                 (conj (str ";; " (clip (first (str/split-lines doc)) member-doc-clip)))
                 small?
                 (conj (str/trim source)))]
    (str/join "\n" lines)))

(defn- schema-block-ai
  "One schema rendered for the :ai form: `[schema :ns/key]  <malli form>`.
   Pulls the live shape from the registry; falls back to the persisted
   `:seon.schema/source` when the registry has no entry."
  [{:seon.schema/keys [key source]}]
  (let [shape (when (keyword? key)
                (try (schema/schema-definition key) (catch :default _ nil)))
        form  (cond
                shape                       (clip (pr-str shape) 200)
                (not (str/blank? source))   (clip (str/trim source) 200)
                :else                       "<not registered>")]
    (str "[schema " (pr-str key) "]  " form)))

(defn- test-block-ai
  "One test rendered for the :ai form — `[test sym]` header, the
   pass/fail status line (✓/✗/•), and clipped source. The status glyph
   is derived via the shared `seon.handlers.test/status-line` — the
   SINGLE source of the ✓/✗/• logic — so this whole-ns block and the
   per-kind `seon.handlers.test/render-ai` never diverge."
  [{:seon.test/keys [sym source] :as test}]
  (str "[test " sym "]"
       "\n" (h-test/status-line test)
       (when (and source (not (str/blank? source)))
         (str "\n" (clip (str/trim source) fn-source-inline-threshold)))))

(defn- render-one-ns-ai
  "Render a single namespace block to text. `ns-kw` is the namespace
   keyword; `data` is the `pull-ns-data` result (or nil = not in db)."
  [ns-kw data]
  (if (nil? data)
    (str ";; requires: " (name ns-kw) " (not in db)")
    (let [src     (:seon.ns/source data)
          fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))
          body    (cond-> []
                    (and src (not (str/blank? src)))
                    (conj (str/trim src))
                    (seq fns)
                    (into (map fn-block-ai fns))
                    (seq schemas)
                    (into (map schema-block-ai schemas))
                    (seq tests)
                    (into (map test-block-ai tests)))]
      (str "<namespace name=\"" (name ns-kw) "\">\n"
           (if (seq body) (str/join "\n\n" body) ";; (no recorded source/fns/schemas)")
           "\n</namespace>"))))

(defn- render-one-ns-html
  "Render a single namespace block to hiccup. Reuses the per-kind
   `seon.handlers.{ns,fn,schema}/render-html` for each member so the
   webview card styling stays consistent with the inspector panes."
  [db ns-kw data]
  (if (nil? data)
    [:div {:class "py-1 text-xs font-mono text-text-500 italic"}
     (str "requires: " (name ns-kw) " (not in db)")]
    (let [fns     (->> (:seon.fn/_ns data)     (sort-by :seon.fn/sym))
          schemas (->> (:seon.schema/_ns data) (sort-by (comp str :seon.schema/key)))
          tests   (->> (:seon.test/_ns data)   (sort-by :seon.test/sym))
          ns-ent  {:seon.ns/name ns-kw}]
      (into
        [:section {:class "py-1 border-l-2 border-base-700 pl-2"}
         (:seon.render/hiccup (h-ns/render-html {:seon.db/db db :seon.render/entity ns-ent}))]
        (concat
          (for [f fns]
            (:seon.render/hiccup
              (h-fn/render-html {:seon.db/db db :seon.render/entity f})))
          (for [s schemas]
            (:seon.render/hiccup
              (h-schema/render-html {:seon.db/db db :seon.render/entity s})))
          ;; Tests rendered via the per-kind handler — same `test-status`
          ;; source as the AI path, so the pass/fail pill never diverges.
          (for [t tests]
            (:seon.render/hiccup
              (h-test/render-html {:seon.db/db db :seon.render/entity t}))))))))

(defn- collect-ns-order
  "Compute the ordered, deduped list of namespace keywords to render —
   required nses FIRST (prepended), then the ns itself, recursing to
   `depth`. Cycle- and revisit-safe: a ns already in the accumulator is
   never expanded or re-added. depth 0 = just `ns-kw` (no requires).

   Returns `[ordered-kws data-by-kw]` where `data-by-kw` caches each
   ns's `pull-ns-data` result (possibly nil for not-in-db requires)."
  [db ns-kw depth]
  (let [data-by-kw (atom {})
        seen       (atom #{})
        order      (atom [])
        ;; memoized pull
        data-for   (fn [k]
                     (if (contains? @data-by-kw k)
                       (@data-by-kw k)
                       (let [d (pull-ns-data db k)]
                         (swap! data-by-kw assoc k d)
                         d)))
        walk       (fn walk [k d]
                     (when-not (contains? @seen k)
                       (swap! seen conj k)
                       (let [data (data-for k)
                             reqs (when (and data (pos? d))
                                    (->> (parse-require-syms (:seon.ns/source data))
                                         (map keyword)))]
                         ;; required nses first (prepended), then self
                         (doseq [r reqs] (walk r (dec d)))
                         (swap! order conj k))))]
    (walk ns-kw depth)
    [@order @data-by-kw]))

(schema/register! :seon.render/depth :int)
(schema/register! :seon.render/format [:enum :ai :html])

(schema/register! ::render-namespace-request
  [:map
   [:seon.ns/name        :seon.ns/name]
   [:seon.render/depth   {:optional true} :seon.render/depth]
   [:seon.render/format  {:optional true} :seon.render/format]
   [:seon.db/db          {:optional true} :seon.db/db]])

(schema/register! ::render-namespace-response
  [:map
   [:seon.render/text   {:optional true} :string]
   ;; Pure-data shallow hiccup bound — registered forms must not embed
   ;; fns (platform law; see seon.render.live-tile). Deep validation
   ;; stays at the render boundary.
   [:seon.render/hiccup {:optional true} :seon.render.live-tile/hiccup]])

(defn render-namespace
  "Render a WHOLE namespace — its `(ns …)` source plus every `:seon.fn`,
   `:seon.schema`, and (when the kind exists) `:seon.test` it owns — in
   either `:ai` text or `:html` hiccup, recursing into the namespaces it
   `(:require …)`s.

   Required namespaces render FIRST (prepended), then the namespace
   itself, to `:seon.render/depth` (default 1 = the ns + its direct
   requires). Recursion is deduped (each ns rendered once) and cycle-safe.
   A required ns with no `:seon.ns` entity is noted on a single line
   (`requires: x.y (not in db)`), never errored.

   Map-in / map-out:

     {:seon.ns/name <keyword>
      :seon.render/depth  <int, default 1>
      :seon.render/format <:ai | :html, default :ai>
      :seon.db/db <db value, optional — defaults to @*conn*>}

   → {:seon.render/text <string>}     for :ai
   → {:seon.render/hiccup <hiccup>}   for :html

   This is the foundation of every agent's default context; the section
   that surfaces the agent's namespaces resolves to it (T5)."
  {:malli/schema [:=> [:cat ::render-namespace-request] ::render-namespace-response]}
  [{ns-name :seon.ns/name
    :seon.render/keys [depth format]
    :seon.db/keys [db]
    :or {depth 1 format :ai}}]
  (let [db    (or db @db/*conn*)
        ns-kw (if (keyword? ns-name) ns-name (keyword (str ns-name)))
        [order data-by-kw] (collect-ns-order db ns-kw (max 0 depth))]
    (if (= format :html)
      {:seon.render/hiccup
       (into [:div {:class "flex flex-col gap-2"}]
             (for [k order]
               (render-one-ns-html db k (data-by-kw k))))}
      {:seon.render/text
       (str/join "\n\n" (for [k order]
                          (render-one-ns-ai k (data-by-kw k))))})))

;; ============================================================
;; live-tile-section — "what your human currently sees" (live-tiles
;; U5). Kills the false belief a live T2 proof caught: a DeepSeek
;; agent replied "My tile is currently blank — I haven't set it up
;; yet" while its tile showed the core welcome. The agent sees
;; the SAME wired value the human surfaces render — derived every
;; turn, nothing stored (reactive-context doctrine).
;; ============================================================

(defn live-tile-section
  "The `:live-tile` awareness section — what your human currently
   sees. Invokes the agent's wired tile value against THIS TURN's db
   value through `seon.render/render-agent-tile` (the ONE tile entry
   point — same resolution, same render the human surfaces use) and
   renders:

     header — the wired identity (`seon.render.live-tile/wired-label`:
              fn name, or \"literal hiccup on your entity\") so the
              agent always sees HOW to change the display;
     body   — the `:seon.render/ai` twin for fns; the literal hiccup
              VERBATIM for static values (\"you see exactly what's
              wired\" — a fn that omits the twin gets its hiccup
              verbatim too, which is itself the nudge to add one);
              the `:seon.error/*` envelope when the renderer THROWS
              (a broken tile must never silently vanish — vanish is
              indistinguishable from unwired, banned).

   PER-TURN SEMANTICS (correct BY DESIGN — do not \"fix\" with stored
   presentation state or mid-turn refreshes): the body is as-of the
   db value this prompt was assembled from. The human's tile
   live-updates per relevant tx, so between turns the human may
   briefly see FRESHER data than this twin; the next turn's section
   re-derives from the then-current db.

   Renders nothing only when no tile resolves at all (agent entity
   missing) — every created agent is welcome-wired, so in practice
   the section is always present; the unwired branch is the
   correctness floor."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id] entity :seon.agent/entity}]
  (let [{:seon.render/keys [hiccup ai error]}
        (render/render-agent-tile {:seon.agent/id id :seon.db/db db})
        body (cond
               ;; Renderer threw: the twin says it's broken; the
               ;; envelope (sans the raw js error / 4KB stack — the
               ;; message + flattened ex-data are the agent-actionable
               ;; parts) says what the exception said.
               (some? error)
               (str ai "\n"
                    (pr-str (select-keys error [:seon.error/message
                                                :seon.error/data
                                                :seon.error/ex-data])))

               (some? ai)     ai
               (some? hiccup) (pr-str hiccup))]
    (if (nil? body)
      ""
      ;; Provenance for the header. The composer's entity pull cannot
      ;; name :seon.render.live-tile/content explicitly — datahike
      ;; THROWS on pulling an attr the conn never installed (installs
      ;; are lazy, at first transact), and a store predating the tile
      ;; key must still assemble context — so the slot is read here
      ;; behind the same `seon.db/installed-schema` gate
      ;; `live-tile/user-name` uses (load-bearing, not defensive fluff).
      (let [ent   (if (contains? (db/installed-schema db)
                                 :seon.render.live-tile/content)
                    (merge entity
                           (db/pull {:seon.db/db db
                                     :seon.db/pull-pattern
                                     '[:seon.render.live-tile/content]
                                     :seon.db/ref [:seon.agent/id id]}))
                    entity)
            wired (live-tile/wired-content {:seon.render/entity ent})]
        (str "<live-tile>\n"
             ";; Your live tile — what your human currently sees (as-of this\n"
             ";; turn's render; the human's view live-updates between turns).\n"
             "Wired: " (live-tile/wired-label wired) "\n\n"
             body "\n\n"
             "To change it: redefine the wired fn, or transact a new value\n"
             "(a qualified fn symbol or literal hiccup) onto\n"
             ":seon.render.live-tile/content on your agent entity.\n"
             "</live-tile>")))))

(defn your-entity-section
  "The agent's OWN entity as a pretty-printed MAP (context-v4 §2.5):
   purpose, tile wiring, registered sections, lifecycle attrs, and any
   self-instructions the agent has written to itself. Replaces the
   `:purpose` and `:your-sections` seed sections — identity is data
   you look at, and editing it is a transact to the map you are
   looking at (the startup evals demonstrate the lookup-ref move).

   Renders the ONCE-pulled composer entity (`:seon.agent/entity` in
   the section input) with the render slots and ctx sections
   bridge-decoded — what you see is what a `seon.db/pull` returns.
   Show-don't-tell applied to identity."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] entity :seon.agent/entity}]
  (if (nil? entity)
    ""
    (let [decoded (cond-> (decode-section (into {} entity))
                    (seq (:seon.agent/ctx entity))
                    (assoc :seon.agent/ctx
                           (mapv decode-section (:seon.agent/ctx entity)))
                    (contains? entity :seon.render.live-tile/content)
                    (update :seon.render.live-tile/content
                            #(db/decode-edn-value
                               :seon.render.live-tile/content %)))]
      (str "<your-entity>\n"
           ";; YOUR OWN ENTITY in the shared store, re-pulled every turn.\n"
           ";; Transact to it by lookup ref — e.g.\n"
           ";;   (seon.db/transact! [{:seon.db/ref [:seon.agent/id \"" id "\"]\n"
           ";;                        :seon.agent/purpose \"…\"}])\n"
           ";; — and the change appears here next turn. Write notes and\n"
           ";; standing instructions to yourself here; this map IS you.\n"
           ;; Derive-your-purpose teaching — CONTEXT, not stored data:
           ;; the welcome tile renders :seon.agent/purpose verbatim to
           ;; the customer, so the instruction lives here and only
           ;; while the attr is absent (self-healing: the agent's own
           ;; transact makes this line vanish). Chat-surface #29, a23.
           (when (nil? (:seon.agent/purpose entity))
             (str ";; Your :seon.agent/purpose is UNSET. Derive it from your\n"
                  ";; human's first messages, then transact it onto your own\n"
                  ";; entity (the lookup-ref move above) so you keep your\n"
                  ";; direction — your human sees it as your tile's headline.\n"))
           (str/trimr (with-out-str (pprint/pprint decoded)))
           "\n</your-entity>"))))

(defn warnings-section
  "Render current problems as ONE clustered `<warnings>` block via the
   `seon.warn` check registry: one complete explanation + one targeted
   fix example per kind, then the affected list with specific locations.
   Empty string when everything is clean; warnings vanish the moment the
   underlying state goes away (derived, never stored — see
   docs/seon/concepts/reactive-context).

   The CORPUS checks (no-malli-schema, return-is-any, arg-is-any,
   uses-maybe, no-return-spec, no-input-spec) default to
   the agent's CURRENT ns so an agent isn't confused by other
   namespaces' defects. Override per-section via the `:seon.ctx` entity:
   `:seon.warn/ns <ns-kw>` scopes to that ns; `:seon.warn/ns
   :seon.warn/all` is the whole-core overview. The RUNTIME checks
   (failed-evals, bad-ref, slow-evals, failing-tests) are always global
   — cross-agent visibility is their point.

   To add a warning kind, add a check fn to `seon.warn/checks`."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [override (:seon.warn/ns (:seon.ctx/section input))
        scope    (cond
                   (= override :seon.warn/all) nil
                   (some? override)            override
                   :else
                   (let [ns (current-ns {:seon.agent/id id})]
                     (if (keyword? ns) ns (keyword (str ns)))))]
    (warn/render-warnings
      (cond-> {:seon.db/db db}
        (some? scope) (assoc :seon.warn/ns scope)))))

(def ^:private inventory-header
  (str ";; stored data — what this cluster holds RIGHT NOW, one line per\n"
       ";; KIND (attr namespace), then each attr NAME with its live row\n"
       ";; count. Consult this BEFORE researching or registering: a kind\n"
       ";; that already exists means prior agents stored rows you can\n"
       ";; query. Read any kind's rows with the LISTED attrs, e.g.:\n"
       ";;   (seon.db/query {:seon.db/query\n"
       ";;     '[:find ?v :where [?e :my.kb.codebase/answer ?v]]})\n"
       ";; (post-bootstrap data only; the full system index is one call\n"
       ";;  away — (seon.db/store-inventory {:seon.db/system? true}))"))

(defn inventory-section
  "The `<data-inventory>` discovery surface (always-changing volatile
   tail): a CHEAP map of what the shared store holds RIGHT NOW, derived
   from [[seon.db/store-inventory]] (user-domain kinds first). ONE line
   per kind — the kind (attr namespace) is the line label, then
   space-separated `attr-name count` pairs with the namespace stripped
   off each attr name (the line label already carries it). Pure fn of
   the db; stores nothing; recomputed each render so a newly-stored
   kind appears next turn and a fully-retracted one vanishes (see
   docs/seon/concepts/reactive-context).

   REACTIVE: returns \"\" (composer drops the section) when the store
   holds no post-bootstrap data — no empty shell. The whole section for
   a typical store is only a few hundred chars (~300 tokens), so it
   stays out of the cacheable prefix and rides near the prompt tail."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows (db/store-inventory {:seon.db/db db})]
    (if (seq rows)
      (let [lines (map (fn [{kind :seon.db/kind attrs :seon.db/attrs}]
                         (str (name kind) ": "
                              (str/join " "
                                (map (fn [[a c]] (str (name a) " " c))
                                     attrs))))
                       rows)]
        (str "<data-inventory>\n"
             inventory-header "\n\n"
             (str/join "\n" lines)
             "\n</data-inventory>"))
      "")))

(def transcript-char-budget
  "Total rendered-chars cap for the transcript section (~6k tokens at
   chars/4). Why 24,000: the audit measured an UNBOUNDED transcript at
   90,468 chars by turn 58 — 83% of a 27k-token context, dominating
   both spend and the model's attention. 24k keeps the newest ~15
   worst-case eval rows (≤1.6KB each via `eval-render-cap`) or several
   dozen typical items whole — comfortably more than the 2–4 turns most
   questions need — while bounding context ≈ static sections + 6k tok.
   Retention is NEWEST-FIRST: oldest items drop beyond the budget and
   an elision note replaces them at the top."
  24000)

(def resume-marker-line
  "The session-resume boundary row (context-v4 §2.8): rendered ONCE per
   resume, between the last turn of a previous process and the first of
   the next. Everything above it ran in a process that no longer
   exists — its `result/<id>` vars are not dereferenceable."
  (str ";; ── session resumed — the turns above ran in a previous process; "
       "their result/<id> vars are gone (re-run a form to recompute a value) ──"))

(def transcript-note
  "The `note=` attribute on the `<transcript>` envelope: an in-band
   disclaimer of the runtime-owned annotations (the `=> result` line +
   the `;; result/<id>` handle). It tells the agent (a) these tags +
   annotations are PAST history the runtime wrote, not its own output
   shape, and (b) how to reuse a value — reference `result/<id>` as a
   live var. Mimicry safety: the agent's own forms render with NO tags,
   so there is nothing in its output shape to copy."
  (str "These are your PAST turns. The runtime adds each form's "
       "`=> result` line and the `;; result/<id>` after it — never write "
       "`=>` or `;; result/` lines yourself. To reuse any result, "
       "reference result/<id> directly; it is a live var."))

(defn- session-turns
  "ALL :seon.agent.turn entities for `agent-id`, oldest-first across ALL
   sessions, each tagged with its owning `:seon.agent.session/id` and
   `:seon.ctx/core-authored?` ([[core-authored-turn?]]) — the
   turn-grouped transcript walk (context-v4 §2.8: prior-session turns
   render too, behind a resume boundary). Walks agent → sessions →
   turns. Each turn's `:seon.agent.turn/evals` ride along as the (lazy
   datahike) ref vector. Optional `db` snapshot (the composer threads
   its render db)."
  [agent-id db]
  (let [a (db/entity (cond-> {:seon.db/ref [:seon.agent/id agent-id]}
                       db (assoc :seon.db/db db)))]
    (vec
      (for [s (sort-by :seon.agent.session/at (:seon.agent/sessions a))
            t (sort-by :seon.agent.turn/at (:seon.agent.session/turns s))]
        {::turn                            t
         :seon.agent.session/id-of-session (:seon.agent.session/id s)
         ::core-authored?                  (core-authored-turn? t)}))))

(defn- sender-line
  "The `<user>…</user>` (human) or `<from agent=<id>>…</from>` (agent) line
   for a message's `from` ref (a pulled map carrying `:seon.user/id` /
   `:seon.agent/id`) and `content` string — the ONE place a triggering
   message becomes a transcript line, shared by [[woken-by-line]] (a
   turn's wake) and the pending-inbound render (the not-yet-threaded
   question). Content is bounded by [[message-render-cap]]."
  [from content]
  (let [body (cap-result content message-render-cap)]
    (if (:seon.user/id from)
      (str "<user>" body "</user>")
      (str "<from agent=" (:seon.agent/id from) ">" body "</from>"))))

(defn- woken-by-line
  "The `<user>` (or `<from agent=…>`) line for a turn — the message whose
   wake opened it: `:seon.agent.turn/woken-by` → `:seon.agent.message/content`,
   with the tag chosen by the sender's ref kind (a `:seon.user/id` ref =
   the human → `<user>`; an agent ref → `<from agent=…>`).
   Returns nil when the turn has no woken-by (boot/manual turns get no
   `<user>` line). The human → `<user>…</user>`; this agent itself or
   another agent → `<from agent=<id>>…</from>` so a human vs agent
   trigger is unambiguous. Content is bounded by [[message-render-cap]]."
  [turn own-id]
  (when-let [wb (:seon.agent.turn/woken-by turn)]
    (when-let [content (:seon.agent.message/content wb)]
      (sender-line (:seon.agent.message/from wb) content))))

(defn- render-turn
  "Render ONE turn as a `<turn id=… evals=N/M>` block: the woken-by
   `<user>`/`<from>` line (omitted when absent), then each eval rendered
   REPL-faithful by [[format-eval-row]] (a `;; in <ns>` marker injected
   only where the eval ns changes, so ns switches stay visible without a
   per-row prompt prefix). `evals=N/M` is ok-count / total. PRIOR-SESSION
   turns (`prior?` true) render their evals WITHOUT `result/<id>` handles
   (the vars died with the process). CORE-AUTHORED turns render their
   eval bodies at [[core-eval-render-cap]] (tagged via `core?`)."
  [{turn ::turn core? ::core-authored?} own-id prior?]
  (let [evals  (->> (:seon.agent.turn/evals turn)
                    (sort-by :seon.eval/at)
                    (mapv #(assoc (into {} %) :seon.ctx/core-authored? core?)))
        n-tot  (count evals)
        n-ok   (count (filter :seon.eval/ok? evals))
        tid    (:seon.agent.turn/id turn)
        user-ln (woken-by-line turn own-id)
        eval-rows
        (loop [[e & more] evals prev-ns ::none out []]
          (if (nil? e)
            out
            (let [ns-kw    (:seon.eval/ns e)
                  ns-marker (when (and (some? ns-kw) (not= prev-ns ns-kw))
                              (str ";; in " (name ns-kw)))
                  r         (format-eval-row e prior?)
                  row-text  (if ns-marker (str ns-marker "\n" r) r)]
              (recur more (or ns-kw prev-ns) (conj out row-text)))))]
    (->> [(str "<turn id=" tid " evals=" n-ok "/" n-tot ">")
          user-ln
          (when (seq eval-rows) (str/join "\n" eval-rows))
          "</turn>"]
         (remove nil?)
         (str/join "\n"))))

(defn- pending-inbound-line
  "The PENDING inbound — the message the agent is being woken to answer
   RIGHT NOW, rendered as a trailing `<user>`/`<from>` line so the agent
   sees the question it must respond to. Load-bearing: a turn's prompt is
   rendered BEFORE that turn opens (run-turn! does render-prompt → then
   with-turn!), so the incoming message is NOT yet any turn's woken-by at
   render time — without this line the agent would never see the question
   it is answering (the old message-interleave surfaced every message;
   the turn-grouped render only shows woken-by). Returns the line only
   when the latest LIVE inbound (to ∋ me, from ≠ me, hops < `warn/hop-cap`)
   is NOT already some turn's woken-by; nil otherwise (already threaded,
   or no live inbound). One reactive query, nothing stored."
  [db my-eid turns]
  (when my-eid
    (let [latest (->> (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?m ?at ?content ?f
                           :in $ ?me ?cap
                           :where
                           [?m :seon.agent.message/to ?me]
                           [?m :seon.agent.message/from ?f]
                           [(not= ?f ?me)]
                           [(get-else $ ?m :seon.agent.message/hops 0) ?h]
                           [(< ?h ?cap)]
                           [?m :seon.agent.message/at ?at]
                           [?m :seon.agent.message/content ?content]]
                         :seon.db/args [my-eid warn/hop-cap]})
                      (sort-by #(.getTime ^js (second %)))
                      last)]
      (when latest
        (let [[m-eid _ content f-eid] latest
              woken-eids (into #{}
                               (keep #(:db/id (:seon.agent.turn/woken-by (::turn %))))
                               turns)]
          ;; Render only when this inbound has NOT yet opened a turn — i.e.
          ;; it is the question the CURRENT (about-to-open) turn answers.
          (when-not (contains? woken-eids m-eid)
            (let [from (db/entity {:seon.db/db db :seon.db/ref f-eid})]
              (sender-line {:seon.user/id (:seon.user/id from)
                            :seon.agent/id (:seon.agent/id from)}
                           content))))))))

(defn transcript-section
  "The THREADED TRANSCRIPT (transcript-redesign-2026-06-18) — the agent's
   PAST turns, oldest-first, grouped by turn: one `<turn id=… evals=N/M>`
   block per turn carrying the woken-by `<user>`/`<from>` line and the
   turn's evals rendered REPL-faithful ([[format-eval-row]] — `;;`
   preamble, the form, a `=> value ;; result/<id>` output line, or a
   `=> ✗` failure line). The whole thing wraps in `<transcript note=…>`
   ([[transcript-note]] disclaims the runtime annotations in-band).

   Only ENVELOPE tags (`<transcript>`/`<turn>`/`<user>`/`<from>`) are
   XML — the agent's own output renders as plain comments + forms +
   REPL output, with NO `<eval>` tag, so there is nothing in its output
   shape to mimic (the live `<your-ns>=>` cursor at the very END of the
   context is the strongest 'type here' signal).

   SESSION RESUME: turns from PREVIOUS sessions render too
   ([[session-turns]] walks all sessions), separated by ONE
   [[resume-marker-line]] per resume; prior-session turns render their
   evals WITHOUT `result/<id>` handles (the vars died with the process).

   Budget eviction is OLDEST-TURN-FIRST: the newest turns are kept whole
   (always at least one); older turns drop beyond
   [[transcript-char-budget]] and an elision note replaces them at the
   top. Each AGENT eval row is bounded by [[eval-render-cap]];
   CORE-AUTHORED turns (the creation-turn tutorial — tagged by
   [[session-turns]]) render their evals at [[core-eval-render-cap]]."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [db       (or db @db/*conn*)
        cur-sess (:seon.agent.session/id (current-session id db))
        turns    (session-turns id db)
        my-eid   (:db/id (db/entity {:seon.db/db db
                                     :seon.db/ref [:seon.agent/id id]}))
        ;; The question being answered NOW — not yet any turn's woken-by
        ;; (the prompt renders BEFORE its turn opens). ALWAYS kept.
        pending  (pending-inbound-line db my-eid turns)
        ;; Build the rendered rows oldest-first; a resume marker rides as
        ;; its own row just before a turn whose session differs from the
        ;; previous turn's.
        turn-items
        (loop [[t & more] turns prev-sess ::none out []]
          (if (nil? t)
            out
            (let [sess   (:seon.agent.session/id-of-session t)
                  prior? (and (some? cur-sess) (not= sess cur-sess))
                  marker (when (and (not= prev-sess ::none)
                                    (not= prev-sess sess))
                           {:seon.ctx/kind :seon.ctx/marker
                            :seon.render/text resume-marker-line})
                  row    {:seon.ctx/kind :seon.ctx/turn
                          :seon.render/text (render-turn t id prior?)}]
              (recur more sess (into out (if marker [marker row] [row]))))))
        ;; The pending-inbound line rides as an always-kept item at the END.
        items (cond-> turn-items
                pending (conj {:seon.ctx/kind :seon.ctx/pending
                               :seon.render/text pending}))]
    (if (seq items)
      (let [rendered (mapv :seon.render/text items)
            ;; Resume markers are ALWAYS kept (they orient the agent); only
            ;; TURN blocks are evictable.
            exempt?  (mapv #(not= :seon.ctx/turn (:seon.ctx/kind %)) items)
            kept-chars (transduce
                         (keep-indexed
                           (fn [i s] (when (exempt? i) (+ (count s) 2))))
                         + 0 rendered)
            ;; NEWEST-FIRST retention over the TURN blocks: walk from the
            ;; end accumulating rendered chars; keep the newest turns WHOLE
            ;; (always at least one), drop everything older — eviction is
            ;; OLDEST-FIRST by construction.
            kept-turn (loop [i (dec (count rendered)) acc kept-chars kept #{}]
                        (if (neg? i)
                          kept
                          (if (exempt? i)
                            (recur (dec i) acc kept)
                            (let [acc' (+ acc (count (rendered i)) 2)]
                              (if (and (seq kept)
                                       (> acc' transcript-char-budget))
                                kept
                                (recur (dec i) acc' (conj kept i)))))))
            kept-idx (filterv #(or (exempt? %) (kept-turn %))
                              (range (count rendered)))
            elided   (- (count rendered) (count kept-idx))
            kept     (mapv rendered kept-idx)]
        (str "<transcript note=\"" transcript-note "\">\n"
             (when (pos? elided)
               (str ";; … " elided " older turn" (when (not= 1 elided) "s")
                    " elided (transcript capped at " transcript-char-budget
                    " chars; the full log is in the db — "
                    "(seon.agent/messages) / (seon.agent/evals))\n\n"))
             (str/join "\n" kept)
             "\n</transcript>"))
      "")))

(defn- turn-card-hiccup
  "ONE turn rendered as a card (debug-view-section-twins-2026-06-18): the
   `turn N` header + woken-by `<user>`/`<from>` line, then the turn's
   evals as the REPL-faithful text [[render-turn]] already produces,
   dropped into a `[:pre]`. Lifts the card framing from the inspector's
   `card-block` (border-l rail, mono text). `prior?` strips result/<id>
   handles for prior-session turns."
  [{turn ::turn :as t} own-id prior? n]
  (let [tid   (:seon.agent.turn/id turn)
        evals (:seon.agent.turn/evals turn)
        body  (render-turn t own-id prior?)]
    [:div {:class "border-l-2 border-amber-700/40 pl-2 py-1 mb-1"}
     [:div {:class "text-xs font-mono text-text-400"}
      [:span {:class "font-semibold text-text-300"} (str "turn " n)]
      [:span {:class "text-text-500"} (str "  id=" tid
                                           "  evals=" (count evals))]]
     [:pre {:class (str "mt-0.5 text-xs font-mono whitespace-pre-wrap "
                        "break-all text-text-100")}
      body]]))

(defn transcript-section-html
  "The HTML TWIN of [[transcript-section]] (debug-view-section-twins-
   2026-06-18): the agent's OWN turns/evals/messages rendered as cards,
   oldest-first (the same [[session-turns]] walk the text twin uses, so
   it is STRUCTURALLY agent-scoped — core `:seon.test` index entities are
   never in the agent's session turns and so never appear here). Render
   order = the transcript's turn order. Returns the standard
   `:seon.render/html-response` map (`{:seon.render/hiccup …}`); an empty
   transcript renders a friendly placeholder card rather than vanishing.

   This replaces the old per-entity last-64-by-tx-time right-pane window
   (`render/visible-entities`), which flooded with the core's own test
   captures and no longer mirrored the prompt."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [{:seon.agent/keys [id] db :seon.db/db :seon.ctx/keys [section]}]
  (let [db       (or db @db/*conn*)
        cur-sess (:seon.agent.session/id (current-session id db))
        turns    (session-turns id db)
        cards
        (->> turns
             (map-indexed
               (fn [i t]
                 (let [sess   (:seon.agent.session/id-of-session t)
                       prior? (and (some? cur-sess) (not= sess cur-sess))]
                   (turn-card-hiccup t id prior? (inc i)))))
             vec)]
    {:seon.render/hiccup
     (if (seq cards)
       (into [:div {:class "flex flex-col"}] cards)
       [:div {:class "text-text-500 italic p-2 text-xs font-mono"}
        "no turns yet — every turn this agent takes appears here live"])}))

(defn inbox-count
  "Count of UNANSWERED inbound messages in `msgs` (the agent's derived
   conversation, oldest-first): inbound items (from ≠ me) strictly
   after my own latest outbound message — every inbound when I have
   never replied. The `inbox K` slot of the status line (§2.9). NOTE:
   ANY outbound from me counts here, INCLUDING the per-turn self-fold
   (from = to = me) — so this window closes after turn 1 of a wake.
   The MID-TASK gate is [[task-in-progress?]], which mirrors the
   loop's reply semantics instead (opus-live-tests 2026-06-12
   finding 1: sections gated on inbox-count were first-turn-only)."
  {:malli/schema [:=> [:catn [::msgs [:vector :map]] [::own-id :string]]
                  :int]}
  [msgs own-id]
  (let [outbound? #(= own-id (:seon.agent/id (:seon.agent.message/from %)))
        after-out (->> msgs
                       reverse
                       (take-while (complement outbound?)))]
    (count (remove outbound? after-out))))

(defn- latest-live-inbound
  "The latest LIVE inbound message for `my-eid` in db value `db` as
   [at content] — to ∋ me, from ≠ me, hops < `warn/hop-cap` (the same
   window [[turns-since-inbound]] and the loop's cap policy count
   against). nil when none."
  [db my-eid]
  (->> (db/query
         {:seon.db/db db
          :seon.db/query
          '[:find ?at ?content
            :in $ ?me ?cap
            :where
            [?m :seon.agent.message/to ?me]
            [?m :seon.agent.message/from ?f]
            [(not= ?f ?me)]
            [(get-else $ ?m :seon.agent.message/hops 0) ?h]
            [(< ?h ?cap)]
            [?m :seon.agent.message/at ?at]
            [?m :seon.agent.message/content ?content]]
          :seon.db/args [my-eid warn/hop-cap]})
       (sort-by #(.getTime ^js (first %)))
       last))

(defn task-in-progress?
  "MID-TASK derivation — TRUE from a live inbound message until the
   agent REPLIES to it: the latest live inbound (to ∋ me, from ≠ me,
   hops < `warn/hop-cap`) has no LATER outbound to a NON-SELF
   recipient. Mirrors the loop's own stop semantics
   (`seon.agent/replied-since-inbound?`, halt `:replied`) read-only
   from the message log at render time — the per-turn self-fold
   (from = to = me) never closes the window, unlike [[inbox-count]]'s
   any-outbound window (opus-live-tests 2026-06-12 finding 1: gating
   `<turns>` on inbox-count made it
   first-turn-only — dead exactly where the countdown matters). The
   gate the turns section consumes; nothing stored, nothing to clear
   (docs/seon/concepts/reactive-context)."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [id     (resolve-id id)
        db     (or db @db/*conn*)
        my-eid (:db/id (db/entity {:seon.db/db db
                                   :seon.db/ref [:seon.agent/id id]}))
        [inbound-at _] (when my-eid (latest-live-inbound db my-eid))
        reply-at
        (when my-eid
          (ffirst
            (db/query
              {:seon.db/db db
               :seon.db/query
               '[:find (max ?at)
                 :in $ ?me
                 :where
                 [?m :seon.agent.message/from ?me]
                 [?m :seon.agent.message/to ?t]
                 [(not= ?t ?me)]
                 [?m :seon.agent.message/at ?at]]
               :seon.db/args [my-eid]})))]
    (boolean (and inbound-at
                  (or (nil? reply-at)
                      (<= (.getTime ^js reply-at)
                          (.getTime ^js inbound-at)))))))

(defn localized-now
  "The current wall-clock time rendered in the HUMAN'S timezone (the
   pod host's IANA tz) — `2026-06-11 14:23:08 Europe/Madrid`. The
   sv-SE locale gives the ISO-like `YYYY-MM-DD HH:mm:ss` shape."
  []
  (let [tz (host-timezone)]
    (str (try (.toLocaleString (js/Date.) "sv-SE" #js {:timeZone tz})
              (catch :default _ (.toISOString (js/Date.))))
         " " tz)))

(defn prompt-section
  "The final two lines of every prompt (context-v4 §2.9): one status
   line, then a CLEAN REPL prompt —

     ;; ── my.agent.kXQ · turn 6 · 3 since-user (cap 20) · 2026-06-11 14:23:08 Europe/Madrid · inbox 1 · agent kXQ-2606101814 ──
     my.agent.kXQ=>

   Every per-turn-volatile byte lives HERE at the context tail so the
   sections above stay a stable provider-cacheable prefix
   (context-audit 2026-06-09 §4). The agent id lands here (moved OUT
   of `<system>`, §2.1 — the system block is one shared cacheable
   artifact across the cluster). `inbox K` = unanswered inbound
   messages. Turn-pressure nudges render ABOVE the status line when
   escalating (wrap up at halfway, FINAL WARNING before
   `run-agentic-loop!` cuts the loop off) — normally the section is
   exactly the two lines. The final line is EXACTLY `<current-ns>=> `
   — no trailing metadata; the agent completes the next REPL input.
   Always present (never blank)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [ns       (current-ns {:seon.agent/id id :seon.db/db db})
        ;; current-ns returns a keyword (latest eval's :seon.eval/ns) or a
        ;; symbol (home-ns fallback) — render without the keyword colon,
        ;; like a real REPL prompt.
        ns-str   (if (keyword? ns) (name ns) (str ns))
        sess     (current-session id db)
        n-turns  (count (:seon.agent.session/turns sess))
        since-u  (turns-since-inbound {:seon.agent/id id :seon.db/db db})
        cap      (turns-cap id db)
        inbox    (inbox-count (messages {:seon.agent/id id :seon.db/db db})
                              id)
        pressure
        (cond
          (>= since-u (max 1 (- cap 3)))
          (str ";; ⚠⚠⚠ FINAL WARNING — turn " since-u "/" cap " since your\n"
               ";; human last spoke. You WILL hit the cap in a turn or two.\n"
               ";; STOP researching. TRANSACT THE :assistant MESSAGE NOW with\n"
               ";; whatever you have — even partial. Your human gets NOTHING\n"
               ";; if you don't reply.\n")
          (>= since-u (quot cap 2))
          (str ";; ⚠ Turn " since-u "/" cap " since your human last spoke —\n"
               ";; past halfway. You probably have enough. Stop reading new\n"
               ";; things; compose the :assistant reply with what you found.\n")
          (>= since-u 5)
          (str ";; Turn " since-u "/" cap " since your human last spoke —\n"
               ";; most questions need 2–4 turns. If you have the answer,\n"
               ";; reply now.\n")
          :else "")]
    (str pressure
         ";; ── " ns-str " · turn " n-turns " · " since-u
         " since-user (cap " cap ") · " (localized-now)
         " · inbox " inbox " · agent " id " ──\n"
         ns-str "=> ")))
(schema/register! :seon.render/sections [:vector :seon.ctx/name])

(schema/register! :seon.render/assemble-request
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id :string]])

(schema/register! :seon.ctx/section-text
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/text :string]])

;; The HTML TWIN of a rendered section (debug-view-section-twins-2026-06-18):
;; the dormant `:seon.render/html` slot, resolved through
;; `seon.render/html-render` + the throw-to-banner guard, paired with its
;; section name. Hiccup is genuinely arbitrary agent-authored data at this
;; boundary — `:seon.render.live-tile/hiccup` is the registered shallow
;; bound (vector with keyword head); the deep walk happens at the render
;; boundary, same as every `:seon.render/html-response`.
(schema/register! :seon.ctx/section-html
  [:map
   [:seon.ctx/name :seon.ctx/name]
   [:seon.render/hiccup :seon.render.live-tile/hiccup]])

(schema/register! :seon.render/stable-text   :string)
(schema/register! :seon.render/volatile-text :string)

(schema/register! :seon.render/assemble-response
  [:map
   [:seon.render/text            :string]
   [:seon.render/stable-text     :seon.render/stable-text]
   [:seon.render/volatile-text   :seon.render/volatile-text]
   [:seon.render/sections        :seon.render/sections]
   [:seon.render/section-texts   [:vector :seon.ctx/section-text]]
   [:seon.render/section-html    [:vector :seon.ctx/section-html]]
   [:seon.render/token-estimate  :int]])

(schema/register! :seon.render/split-response
  [:map
   [:seon.render/stable-text   :seon.render/stable-text]
   [:seon.render/volatile-text :seon.render/volatile-text]])

(def stable-boundary
  "The in-band cache-boundary line the composer joins between the
   STABLE prefix (every section through :namespaces — byte-stable
   within a session given the deterministic rendering) and the
   VOLATILE tail (everything after: your-entity, live-tile, warnings,
   open-todos, transcript, turns, inventory, prompt).

   In-band because the agent loop hands providers ONE assembled
   string (`llm-fn` is fn-of-ctx-string): [[split-context]] recovers
   the two halves on the provider side, so the Anthropic adapter can
   put the stable prefix in a cache_control'd system block and send
   only the volatile tail as the user message (task #34 — cache
   coverage was 5.4k of ~38k input tokens). Built by concatenation so
   the marker can never appear verbatim in rendered source text."
  (str ";; ──── ctx cache boundary — everything above this line is the "
       "byte-stable" " prefix; everything below changes per turn ────"))

(def ^:private stable-boundary-delim
  (str "\n\n" stable-boundary "\n\n"))

(defn split-context
  "Split an assembled ctx string at [[stable-boundary]] into the
   stable prefix and the volatile tail. A string WITHOUT the boundary
   (hand-rolled test ctx, stub prompts) is all volatile —
   `:seon.render/stable-text` is \"\" and the input rides through
   unchanged as the tail, so providers degrade to pre-split behavior."
  {:malli/schema [:=> [:catn [:seon.render/text :string]]
                  :seon.render/split-response]}
  [text]
  (let [i (.indexOf text stable-boundary-delim)]
    (if (neg? i)
      {:seon.render/stable-text   ""
       :seon.render/volatile-text text}
      {:seon.render/stable-text   (subs text 0 i)
       :seon.render/volatile-text (subs text (+ i (count stable-boundary-delim)))})))
(defn core-default-ctx
  "The default :seon.ctx section layout that ships with every fresh
   agent — the context-v4 layout (§2), ordered top→bottom =
   static→volatile (the provider-cache contract, §1):

     1. :system      — the universal concept paragraphs + standing
                       teachings; byte-identical for every agent and
                       turn (the agent id lives in the status line)
     2. :namespaces  — THE BODY: one <namespace> tag per included ns
                       (ALL seon.* + my.* minus *.internal),
                       recency-ordered most-recently-modified LAST
     3. :your-entity — the agent's own entity as a pretty-printed map
                       (purpose, tile wiring, sections, self-notes)
     4. :live-tile   — what your human currently sees (tiles-PRD U5;
                       this layout only fixes its slot: after
                       :your-entity, before :warnings)
     5. :warnings    — current problems; reactive, vanishes when fixed
     6. :open-todos  — the agent's open work items; derived, vanishes
     7. :transcript  — PAST turns, grouped <turn id=… evals=N/M>: the
                       woken-by <user> + the turn's evals, REPL-faithful
     8. :turns       — the turn-budget countdown (one line, mid-task
                       only; derived, vanishes when idle) — just
                       above the prompt tail for salience
     9. :inventory   — the cheap <data-inventory> map: one line per
                       stored KIND with each attr's live row count
                       (user-domain first); derived from
                       seon.db/store-inventory, vanishes when the store
                       holds no post-bootstrap data
    10. :prompt      — the §2.9 status line + clean REPL prompt
                       (always changing — the volatile tail's end)

   The dead sections (capabilities, exemplars, schema-catalog,
   functions-catalog, namespace-context, the :purpose/:your-sections
   seeds) dissolved into code (docstrings, the rendered namespace
   sources), data (the agent's own entity), or the startup tutorial
   evals (store-inventory, my.kb.system/instructions) — context-v4
   §§2.2–2.4, 3b. Smallest priority renders first."
  []
  [{:seon.ctx/name :system       :seon.ctx/priority 10
    :seon.render/ai 'seon.ctx/system-section}
   {:seon.ctx/name :namespaces   :seon.ctx/priority 20
    :seon.render/ai 'seon.ctx/namespaces-section}
   {:seon.ctx/name :your-entity  :seon.ctx/priority 30
    :seon.render/ai 'seon.ctx/your-entity-section}
   {:seon.ctx/name :live-tile    :seon.ctx/priority 35
    :seon.render/ai 'seon.ctx/live-tile-section}
   {:seon.ctx/name :warnings     :seon.ctx/priority 40
    :seon.render/ai 'seon.ctx/warnings-section}
   {:seon.ctx/name :open-todos   :seon.ctx/priority 45
    :seon.render/ai 'seon.agent.todo/open-todos-section}
   {:seon.ctx/name :transcript   :seon.ctx/priority 50
    :seon.render/ai 'seon.ctx/transcript-section
    :seon.render/html 'seon.ctx/transcript-section-html}
   {:seon.ctx/name :turns        :seon.ctx/priority 90
    :seon.render/ai 'seon.agent.turns/turns-section}
   {:seon.ctx/name :inventory    :seon.ctx/priority 97
    :seon.render/ai 'seon.ctx/inventory-section}
   {:seon.ctx/name :prompt       :seon.ctx/priority 99
    :seon.render/ai 'seon.ctx/prompt-section}])

;; ============================================================
;; Composer — merge semantics + render guard + budget (the
;; agent-self-context spec, 2026-06-10):
;;
;;   sections = sort-by priority (core defaults ∪ agent's
;;              :seon.agent/ctx)   — MERGE, never replace; a name
;;              collision means override-by-name (deliberate, visible
;;              as data).
;;   input    = {db, id, entity (pulled ONCE), section, model}
;;   render   = string slot → verbatim | symbol slot → (fn input)
;;
;; Guard: a section whose fn is missing/throws renders a one-line
;; error string inside the section — never breaks assembly, surfaces
;; loudly, self-heals when fixed.
;;
;; Budget: agent-authored sections share a per-agent char budget
;; (agent-section-char-budget). Over budget → lowest-priority agent
;; sections truncate with a loud marker. Core sections are not
;; charged to it.
;; ============================================================

(def agent-section-char-budget
  "Total rendered-chars budget shared by the agent's OWN sections
   (everything in :seon.agent/ctx — strings and computed alike).
   Core default sections are not charged. Over budget, the
   LOWEST-priority (largest number, renders last) agent sections
   truncate first, each with a loud marker line."
  8000)

(defn- decode-section
  "Decode the mixed-:or render slots of a PULLED section entity back to
   their value shapes (`seon.db/decode-edn-value` — the inverse of the
   bridge's EDN-string storage encoding). Code-default sections pass
   through unchanged."
  [section]
  (cond-> section
    (contains? section :seon.render/ai)
    (update :seon.render/ai #(db/decode-edn-value :seon.render/ai %))
    (contains? section :seon.render/html)
    (update :seon.render/html #(db/decode-edn-value :seon.render/html %))))

(defn agent-sections
  "The agent's OWN section maps from its pulled entity — slot-decoded,
   sorted by priority. `entity` is the once-pulled agent entity map."
  [entity]
  (->> (:seon.agent/ctx entity)
       (map decode-section)
       (sort-by :seon.ctx/priority)
       vec))

(defn- merge-sections
  "Core defaults ∪ the agent's own sections, ONE priority sort.
   Name collisions = override-by-name (the agent's entry wins — the
   deliberate escape hatch). Ties sort core-first, then by name,
   for byte-stable output."
  [defaults agent-sects]
  (let [agent-names (into #{} (map :seon.ctx/name) agent-sects)
        kept        (remove #(contains? agent-names (:seon.ctx/name %))
                            defaults)
        tagged      (concat (map #(assoc % :seon.ctx/agent? false) kept)
                            (map #(assoc % :seon.ctx/agent? true) agent-sects))]
    (vec (sort-by (juxt :seon.ctx/priority
                        :seon.ctx/agent?
                        (comp str :seon.ctx/name))
                  tagged))))

(defn- render-error-line
  "The guard's inline one-liner for a broken section."
  [section-name detail]
  (str "[" (name section-name) "] render failed: " detail))

(defn- render-section
  "Render ONE section map against `input`. String slot → verbatim;
   qualified symbol → resolve via seon.eval/lookup-value and call with
   `(assoc input :seon.ctx/section section)`. Missing fn or throw →
   the one-line error string (guard — assembly never breaks)."
  [input section]
  (let [slot (:seon.render/ai section)
        nm   (:seon.ctx/name section :unnamed)]
    (try
      (cond
        (string? slot)
        slot

        (qualified-symbol? slot)
        (if-let [f (seval/lookup-value slot)]
          (str (f (assoc input :seon.ctx/section section)))
          (render-error-line nm (str "fn " slot " does not resolve — "
                                     "define it (or fix the symbol) and "
                                     "this section self-heals next render")))

        :else
        (render-error-line nm (str ":seon.render/ai must be a string or a "
                                   "qualified symbol, got " (pr-str slot))))
      (catch :default e
        (render-error-line nm (or (.-message e) (str e)))))))

(defn- render-section-html
  "Resolve a section's `:seon.render/html` twin to hiccup via the EXISTING
   `seon.render/html-render` (symbol | literal-hiccup | else), wrapped in
   the SAME throw-to-banner guard `seon.render/render-entity-html` uses: a
   throwing twin degrades to a legible banner naming the slot + message,
   NEVER nil, NEVER vanish (vanish = banned). `base-in` is the composer's
   shared render input ({:seon.db/db :seon.agent/id …}); the section is
   assoc'd in as every section fn expects."
  [base-in section]
  (let [slot (:seon.render/html section)
        nm   (:seon.ctx/name section :unnamed)]
    (try
      (:seon.render/hiccup
        (render/html-render slot (assoc base-in :seon.ctx/section section)))
      (catch :default e
        [:div {:class (str "flex flex-col gap-1 p-3 border "
                           "border-error/40 bg-error/10 rounded")}
         [:div {:class "text-xs text-error font-mono font-bold"}
          "⚠ render error"]
         [:div {:class "text-xs font-mono text-text-300 break-all"}
          (str (name nm) " (" (pr-str slot) ") threw: "
               (or (.-message e) (str e)))]]))))

(defn- apply-agent-budget
  "Enforce [[agent-section-char-budget]] over the rendered agent
   sections. `rendered` is [{:seon.ctx/name _ :seon.ctx/agent? _
   :seon.ctx/priority _ :seon.render/text _} …] in render order.
   Truncates the lowest-priority (largest number) agent sections first,
   replacing the overflow with a loud marker; core sections pass
   through untouched."
  [rendered]
  (let [agent-total (transduce (comp (filter :seon.ctx/agent?)
                                     (map (comp count :seon.render/text)))
                               + 0 rendered)]
    (if (<= agent-total agent-section-char-budget)
      rendered
      ;; Walk agent sections lowest-priority-first, truncating until
      ;; the total fits. Each truncated section keeps a head slice +
      ;; the loud marker (a fully-dropped section would hide that it
      ;; exists — the marker teaches the agent to trim its own ctx).
      (let [order (->> rendered
                       (filter :seon.ctx/agent?)
                       (sort-by (juxt (comp - :seon.ctx/priority)
                                      (comp str :seon.ctx/name))))
            cuts  (loop [over (- agent-total agent-section-char-budget)
                         [s & more] order
                         acc {}]
                    (if (or (<= over 0) (nil? s))
                      acc
                      (let [n    (count (:seon.render/text s))
                            keep (max 0 (- n over))]
                        (recur (- over (- n keep))
                               more
                               (assoc acc (:seon.ctx/name s) keep)))))]
        (mapv (fn [{nm :seon.ctx/name txt :seon.render/text :as s}]
                (if-let [keep (and (:seon.ctx/agent? s) (get cuts nm))]
                  (assoc s :seon.render/text
                         (str (subs txt 0 keep)
                              "\n;; ⚠ [" (name nm) "] TRUNCATED — your agent "
                              "sections exceed the " agent-section-char-budget
                              "-char budget (this section was "
                              (count txt) " chars). Trim it with "
                              "(seon.agent/add-section! …) or remove it."))
                  s))
              rendered)))))

(defonce ^:private !config-seed-attempted?
  ;; Once-per-process latch for the lazy config seed below — the row is
  ;; identity-upserted, so a lost race is harmless; the latch just stops
  ;; every render from re-checking after the first.
  (atom false))

(defn- ensure-ctx-config!
  "Seed THE ctx-config entity ([[config-ref]]) with
   [[default-included-prefixes]] IF ABSENT — fire-and-forget (transact!
   is safe-by-default), at most one attempt per process. Reads presence
   from the render snapshot `db`; readers fall back to the defaults
   until the row lands, so a lost write costs nothing but the
   downstream's read-modify convenience."
  [db]
  (let [present? (and (contains? (db/installed-schema db)
                                 :seon.ctx/config-id)
                      (some? (db/entity {:seon.db/db db
                                         :seon.db/ref config-ref})))]
    (when (and (not present?)
               (compare-and-set! !config-seed-attempted? false true))
      (db/transact!
        {:seon.db/tx-data
         [{:seon.ctx/config-id "core"
           :seon.ctx/included-prefixes default-included-prefixes}]}))
    nil))

(defn assemble-context
  "Compose the LLM context — the ONE composer, called by BOTH the agent
   prompt path (`seon.agent/render-prompt`) and the inspector, so
   divergence is impossible.

   Sections = core defaults ([[core-default-ctx]]) MERGED
   with the agent's own `:seon.agent/ctx` section maps by one priority
   sort (override-by-name; merge-never-replace — core evolution
   always flows through, agent customization layers on top). The agent
   entity is pulled ONCE (sans the session log — the transcript section
   walks that separately; a bare `[*]` pull would inline every
   turn/eval component) and rides in the input map every section fn
   receives.

   The render splits at [[stable-boundary]]: every section through
   :namespaces (by merged render order) is the STABLE prefix —
   byte-stable within a session given the deterministic rendering —
   and everything after is the VOLATILE tail. `:seon.render/text` is
   the full prompt with the boundary line joined in-band between the
   halves; `:seon.render/stable-text` / `:seon.render/volatile-text`
   carry the halves separately (and [[split-context]] recovers them
   from the joined string — the provider-cache contract, task #34).
   An agent section with priority below :namespaces' lands in the
   stable half — it must render byte-stably (verbatim strings do).

   Returns
     `{:seon.render/text \"…\"
       :seon.render/stable-text \"…\"
       :seon.render/volatile-text \"…\"
       :seon.render/sections [<section-name> …]      ; render order
       :seon.render/section-texts [{:seon.ctx/name _
                                    :seon.render/text _} …]
       :seon.render/token-estimate <int>}`"
  {:malli/schema [:=> [:cat :seon.render/assemble-request]
                       :seon.render/assemble-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (ensure-ctx-config! db)
  (let [entity   (db/pull {:seon.db/db db
                           :seon.db/pull-pattern
                           ;; Registered-but-uninstalled attrs (e.g. the
                           ;; tile slot on a store predating it) are
                           ;; silently filtered by the pull guard — safe.
                           '[:db/id :seon.agent/id :seon.agent/state
                             :seon.agent/purpose
                             :seon.agent/turns-cap :seon.agent/completed-at
                             :seon.render/ai :seon.render/html
                             :seon.render.live-tile/content
                             {:seon.agent/ctx [*]}]
                           :seon.db/ref [:seon.agent/id id]})
        sections (merge-sections (core-default-ctx)
                                 (agent-sections entity))
        base-in  (assoc input :seon.agent/entity entity)
        rendered (->> sections
                      (map (fn [section]
                             (assoc section :seon.render/text
                                    (render-section base-in section))))
                      (remove (comp str/blank? :seon.render/text))
                      vec
                      apply-agent-budget)
        ;; Stable/volatile boundary: everything through :namespaces in
        ;; merged render order is the stable prefix (see docstring).
        ;; Names are unique post-merge, so the stable set is a prefix
        ;; of `rendered` (which preserves merged order).
        names    (mapv :seon.ctx/name sections)
        stable?  (set (take (inc (.indexOf names :namespaces)) names))
        stable-text   (->> rendered
                           (filter (comp stable? :seon.ctx/name))
                           (map :seon.render/text)
                           (str/join "\n\n"))
        volatile-text (->> rendered
                           (remove (comp stable? :seon.ctx/name))
                           (map :seon.render/text)
                           (str/join "\n\n"))
        text     (cond
                   (str/blank? stable-text)   volatile-text
                   (str/blank? volatile-text) stable-text
                   :else (str stable-text "\n\n" stable-boundary
                              "\n\n" volatile-text))
        ;; HTML TWINS (debug-view-section-twins-2026-06-18): for each
        ;; NON-BLANK rendered section (post-budget, in render order) that
        ;; carries a :seon.render/html slot, resolve it through
        ;; html-render + the banner guard. Sections without an html slot
        ;; contribute no item — the right pane simply has no card for
        ;; them. Not char-budgeted (html isn't counted against the
        ;; agent's char budget); derived from the same post-budget vec so
        ;; the twin mirrors whatever text the agent sees.
        section-html
        (->> rendered
             (filter :seon.render/html)
             (mapv (fn [section]
                     {:seon.ctx/name       (:seon.ctx/name section)
                      :seon.render/hiccup  (render-section-html base-in
                                                                section)})))]
    {:seon.render/text           text
     :seon.render/stable-text    stable-text
     :seon.render/volatile-text  volatile-text
     ;; :seon.render/sections is LAYOUT PROVENANCE — every merged
     ;; section name in render order, including ones whose fn rendered
     ;; blank this turn (a suppressed section is still part of the
     ;; layout). :seon.render/section-texts carries only the non-blank
     ;; contributions (what the inspector shows).
     :seon.render/sections       names
     :seon.render/section-texts  (mapv #(select-keys % [:seon.ctx/name
                                                        :seon.render/text])
                                       rendered)
     :seon.render/section-html   section-html
     :seon.render/token-estimate (quot (count text) 4)}))

