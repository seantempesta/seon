(ns seon.ctx
  "Context generation — the v4 composer (context-v4-repl-realism
   2026-06-11): the prompt IS a REPL session. One static `<system>`
   header, ALL the loaded namespaces as the body (`<namespace>` tags,
   recency-ordered), the agent's own entity as a map, what the human
   currently sees, the reactive warnings/todos, ONE threaded
   chronological transcript of messages and evals, and a status line +
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
   EXCEPT `*.internal` ones (STRUCTURAL — applies to all prefixes). No
   lists, no budgets — a new namespace auto-includes the moment its
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
                   (some #(prefix-included? s %) prefixes))))))

(def full-source-roots
  "ROOT namespace names (strings) of the seon.* nses whose complete
   FILE source is inlined into every prompt (children + `-test`
   siblings ride along — see [[full-source-ns?]]); all `my.*` nses are
   full-source by RULE, not by membership here. Why these two:
   `seon.agent.search` is THE npm-package wrapper exemplar (register!
   calls, map-in/map-out request/response schemas, error envelopes);
   `seon.agent.todo` is THE store/retrieve + resume arc.

   WHY THIS LIST STILL EXISTS (context-v4 §2.3 deviation, measured
   2026-06-11): the decided rule — full source for ALL non-internal
   nses — is 873 KB ≈ 218k tokens over the current index (seon.ctx
   alone is 120 KB), beyond any provider window, because the big
   core nses have not had their `*.internal` splits yet. Until
   those splits land, non-full-source nses render as shallow
   `(ns …)`-only tags (existence + the store read, not the body);
   each split that lands shrinks a file toward inlinable size, and
   when they are all done this list dies by flipping
   [[full-source-ns?]] to [[included-ns?]]."
  #{"seon.agent.search" "seon.agent.todo"})

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
   human's world — always inlined), plus the [[full-source-roots]]
   set, children, and `-test` siblings of either. Used by the boot
   indexer (`seon.client/ns-row`) to decide which rows get the file
   read; [[namespaces-section]] renders whatever depth the row has —
   one rule, one writer, no drift."
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (base-ns-name s)]
    (boolean (and (not (hidden-ns-name? s))
                  (or (my-ns-name? base)
                      (some #(or (= base %)
                                 (str/starts-with? base (str % ".")))
                            full-source-roots))))))

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
  "Per-message rendered-content char cap for the transcript section.
   Messages are EXEMPT from the transcript's budget eviction (the
   conversation is never sacrificed for eval bulk — see
   [[transcript-section]]), so each one must be individually bounded
   or a single pasted blob could blow the context. 4000 (≈1k tokens)
   keeps any realistic chat turn whole; the full content stays in the
   db ((seon.agent/messages))."
  4000)

(defn- format-message-row
  "Render one message as a REPL event for the interleaved transcript:
   `user> …` / `assistant> …` / `agent-<id>> …`. The `<label>>` prefix
   lines it up visually with eval `> form` lines so the merged stream
   reads as one coherent REPL session. Content is capped at
   [[message-render-cap]] (context-SAFETY invariant — messages are
   never evicted from the transcript, so they must be bounded)."
  [{from :seon.agent.message/from content :seon.agent.message/content} own-id]
  (str (message-label from own-id) "> " (cap-result content message-render-cap)))

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

   The full value is always available via `(result <id>)` (the live
   globalThis stash); the clip is display-only."
  ([s] (cap-result-body s eval-render-cap nil))
  ([s limit] (cap-result-body s limit nil))
  ([s limit eid]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (let [ref (if eid (str "(result :" eid ")") "(result :<id>)")]
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
   Real result lines (`<value>  ; ⇒ (result :<id>) · <n>ms`) are
   appended by the composer itself AFTER this rewrite and never pass
   through it. Idempotent: the marker doesn't match the claim shape."
  {:malli/schema [:=> [:cat :string] :string]}
  [s]
  (str/replace s result-claim-re unverified-narration-marker))

(defn- format-eval-row
  "REPL-real render of one eval (context-v4 §2.8): the `<ns>=> <form>`
   prompt line (narration `;;` comments above it, as written — except
   result-claim comments, neutralized via
   [[neutralize-result-claims]]), the
   captured output, then the VALUE LINE carrying the eval's result-var
   id — the pinned glyph:

     my.agent.kXQ=> (+ 1 2)
     3  ; ⇒ (result :EVLabc-123) · 4ms

   The id is visible per eval and the suffix IS the retrieval call —
   show-don't-tell for the result-var system. PRIOR-SESSION evals
   (`prior?` true) render WITHOUT the result-var handle (their live
   values did not survive the restart; the resume boundary marker
   says so once). Errors render `;; ERROR …` with a plain `; # <id>`
   footer — there is no value to dereference.

   The rendered result/error body of an AGENT eval is capped at
   `eval-render-cap` chars so one huge eval result can't dominate the
   agent's context (context-SAFETY invariant — agent code can return
   literally anything). A CORE-AUTHORED row
   (`:seon.ctx/core-authored?` true, tagged by [[session-evals]]
   from its promptless owning turn) renders at
   [[core-eval-render-cap]] instead — our own scripted output is
   well-controlled and must arrive whole (the inventory-clip defect).
   Error rendering branches: a Malli instrumentation envelope renders
   via `render-malli-error`; otherwise the pre-rendered legible
   `:seon.eval/error` string.

   On SUCCESS, a reactive 'won't persist' note (#7) is DERIVED from the
   eval's source via [[seon.eval/scratch-def-note]] and appended as a
   trailing `;;` line — pure, no stored attr, recomputed each render so
   it FOLLOWS the form. Blank for everything but a bare `(def …)`."
  ([row] (format-eval-row row false))
  ([{src        :seon.eval/source
     ok?        :seon.eval/ok?
     res        :seon.eval/result-edn
     out        :seon.eval/output
     err        :seon.eval/error
     err-data   :seon.eval/error-data
     eid        :seon.eval/id
     dur        :seon.eval/duration-ms
     narr       :seon.eval/narration
     eval-ns    :seon.eval/ns
     core? :seon.ctx/core-authored?}
    prior?]
   (let [envelope (read-error-envelope err-data)
         limit    (if core? core-eval-render-cap eval-render-cap)
         body (cond
                ok?
                (cap-result-body (or res "nil") limit eid)

                (einstrument/instrument-error? envelope)
                (cap-result-body (einstrument/render-malli-error envelope)
                                 limit eid)

                (and (string? err) (not (str/blank? err)))
                ;; `:seon.eval/error` is stored pre-rendered + legible
                ;; (`seon.eval/render-error-string`) — prefix + plain-clip.
                ;; NOT `cap-result-body`, whose "narrow your query" guide
                ;; is for oversized RESULTS, nonsensical on an error.
                (cap-result (str ";; ERROR " err) limit)

                :else ";; <no result>")
         dur-str (when dur (str " · " dur "ms"))
         suffix  (cond
                   prior? ""
                   ok?    (str "  ; ⇒ (result :" eid ")" dur-str)
                   :else  (str "  ; # " eid dur-str))
         ;; Captured println/prn output — shown above the value like a
         ;; real REPL prints before returning. Bounded by the same cap.
         out-ln (when (and (string? out) (not (str/blank? out)))
                  (str (cap-result (str/trimr out) limit) "\n"))
         ;; Reactive 'won't persist' note (#7) — DERIVED from source, no
         ;; stored attr; recomputed each render so it follows the form.
         ;; Appended AFTER neutralize-result-claims and carries no `=>`/
         ;; `⇒`, so it never trips [[result-claim-re]].
         note   (when ok? (seval/scratch-def-note src))]
     (str (when (and narr (not (str/blank? narr)))
            (str (neutralize-result-claims narr) "\n"))
          (if eval-ns (name eval-ns) "?") "=> "
          (cap-result (neutralize-result-claims src) limit) "\n"
          out-ln
          body suffix
          (when (and note (not (str/blank? note))) (str "\n" note))))))

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
    "responses before.\n"
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
    "RESULT VARS. Every eval's value is saved under the id shown on its\n"
    "value line in the transcript; (result :<id>) returns the live\n"
    "value. NEVER re-run what is already computed. A clipped display is\n"
    "NOT a clipped value — dig into a big stored value with ordinary\n"
    "Clojure (get-in, filter, count) instead of re-querying.\n"
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
    "your context sections both ride this shape.\n"
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
    "loaded them correctly. Namespaces are workspaces: (ns\n"
    "my.domain.thing) moves you there and your context follows your\n"
    "namespace. Your code is my.*, your knowledge is my.kb.* (real\n"
    "schemas per domain); the core is seon.* — call its fns, never\n"
    "redefine them.\n"
    "\n"
    "STANDING TEACHINGS:\n"
    "- Consult stored knowledge FIRST: the <findings> section renders\n"
    "  stored user-domain rows IN FULL — read them before any research;\n"
    "  run (seon.db/store-inventory) — your creation turn already did —\n"
    "  and datalog the existing attrs for anything not shown. It lists\n"
    "  the data added AFTER bootstrap; the full system inventory — the\n"
    "  core's own fn/schema/test index included — is one call away:\n"
    "  (seon.db/store-inventory {:seon.db/system? true})\n"
    "  Prior agents already answered many questions; re-deriving a\n"
    "  stored answer is wasted turns.\n"
    "- Store what you verify, without being asked: design (or reuse) a\n"
    "  my.kb.<domain> schema, reference the shared :my.kb/* provenance\n"
    "  attrs, and transact the fact. Knowledge nobody stored is\n"
    "  research the next agent pays for again.\n"
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
;; a prior agent's register!/defines). Three depths, one render rule
;; per row:
;;   - real file text persisted (full-source-ns?)   → rendered whole;
;;   - stub/sourceless but the ns OWNS member rows  → reconstituted
;;     from the program graph (ns form + each :seon.fn/:seon.schema/
;;     :seon.test source, tx-ordered) — agent-authored nses render as
;;     the real code they are, exactly what bulk-load resume replays;
;;   - bare stub, no members (or seed provenance)   → the stub line
;;     with a SELF-DESCRIBING "source not indexed" marker (a bare
;;     stub has baited a fabricated quotation before).
;; ============================================================

(defn- ns-stub?
  "True when `src` is blank or the boot indexer's minimal `(ns x)`
   stub for `ns-str`."
  [ns-str src]
  (or (str/blank? src)
      (= (str/trim src) (str "(ns " ns-str ")"))))

(defn- reconstituted-ns-source
  "Rebuild an ns's source text from the program graph: the `(ns …)`
   form (the stored stub — SYNTHESIZED by the caller when the ns row
   is sourceless, the tee's nested `{:seon.ns/name kw}` upsert mints
   exactly such rows) followed by every owned `:seon.fn` /
   `:seon.schema` / `:seon.test` source in tx order — the same
   reconstruction bulk-load resume evals (code-as-data: one mechanism,
   two readers). A batch eval tees the SAME source string onto every
   member it defined, so member sources dedupe (first tx wins).
   Returns nil when the ns owns no member rows."
  [db ns-kw stub]
  (let [member-rows
        (fn [src-attr ns-attr]
          (db/query {:seon.db/db db
                     :seon.db/query
                     [:find '?src '?tx
                      :where
                      ['?n :seon.ns/name ns-kw]
                      ['?m ns-attr '?n]
                      ['?m src-attr '?src '?tx]]}))
        rows (concat (member-rows :seon.fn/source     :seon.fn/ns)
                     (member-rows :seon.schema/source :seon.schema/ns)
                     (member-rows :seon.test/source   :seon.test/ns))]
    (when (seq rows)
      (str/join "\n\n"
        (cons (str/trim stub)
              (->> rows
                   (sort-by second)
                   (map (comp str/trim first))
                   (distinct)))))))

(def ^:private namespaces-header
  (str ";; Real loaded code, most-recently-modified LAST. Namespaces showing\n"
       ";; only their (ns …) form are STUBS: their source is NOT in this\n"
       ";; prompt — never quote or summarize a stub's contents from memory.\n"
       ";; A stub's members are rows in the store; read them, e.g.:\n"
       ";;   (seon.db/query {:seon.db/query '[:find ?sym ?src :where\n"
       ";;                                    [?n :seon.ns/name :seon.warn]\n"
       ";;                                    [?f :seon.fn/ns ?n]\n"
       ";;                                    [?f :seon.fn/sym ?sym]\n"
       ";;                                    [?f :seon.fn/source ?src]]})\n"
       ";; (an empty result means the ns owns :seon.schema/:seon.test rows\n"
       ";;  instead — query :seon.schema/ns / :seon.test/ns the same way)"))

(defn namespaces-section
  "One `<namespace name=\"…\">` tag per included ns ([[included-ns?]] —
   ALL seon.* + my.* minus *.internal; ONE rule, no lists), ordered by
   RECENCY: most-recently-modified LAST (tx of the `:seon.ns/name`
   datom — bumped by the tee's nested upsert on every define), name as
   the tie-break, so the stable core set forms a stable cache
   prefix and the churning ns sits nearest the tail. Render depth per
   row is the ns-block comment's three-case rule; the stub branch
   discriminates by TX PROVENANCE — a stub row asserted by the
   `:core-seed` boot tx is compiled core (shallow tag: its
   members are the boot-indexed `:seon.fn` rows of the WHOLE compiled
   ns; inlining them re-creates the 200k+-char dump the depth rule
   exists to avoid), while a stub row from any other tx is
   agent-authored and reconstitutes. Never a render-time file read."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [;; seed-provenance tx set queried SEPARATELY — `get-else` over a
        ;; tx-eid that carries NO meta datoms silently DROPS the row in
        ;; datahike-cljs (probed 2026-06-11; the open-issues get-else
        ;; trap), so membership is checked in code instead.
        seed-txs (into #{}
                       (map first)
                       (db/query
                         {:seon.db/db db
                          :seon.db/query
                          '[:find ?tx
                            :where [?tx :seon.db/origin :core-seed]]}))
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
        blocks (for [[nm tx] rows]
                 (let [ns-str (name nm)
                       src    (get sources nm)
                       seed?  (contains? seed-txs tx)
                       stub   (str "(ns " ns-str ")")
                       body   (if (ns-stub? ns-str src)
                                (or (when-not seed?
                                      (reconstituted-ns-source
                                        db nm (if (str/blank? src) stub src)))
                                    ;; genuinely bare stub — SELF-DESCRIBES
                                    ;; so it can never pass for the ns's
                                    ;; real (empty-looking) source: an
                                    ;; agent has fabricated a quotation
                                    ;; from a bare stub before.
                                    (str stub
                                         " ;; ⚠ stub — source not indexed"
                                         " here; do NOT guess its contents;"
                                         " its members are store rows"
                                         " (see the query above)"))
                                (str/trim src))]
                   (str "<namespace name=\"" ns-str "\">\n"
                        body
                        "\n</namespace>")))]
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
   uses-maybe, no-return-spec, no-input-spec, missing-test) default to
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

(defn- transcript-item-at
  "Wall-clock `:at` of a transcript item (a message or an eval), as
   epoch-ms. Used to interleave the two streams chronologically."
  [item]
  (let [d (or (:seon.agent.message/at item) (:seon.eval/at item))]
    (if d (.getTime ^js d) 0)))

(def resume-marker-line
  "The session-resume boundary row (context-v4 §2.8): rendered ONCE per
   resume, between the last eval of a previous process and the first of
   the next. Everything above it ran in a process that no longer
   exists — its result vars are not dereferenceable."
  (str ";; ── session resumed — the evals above ran in a previous process; "
       "their result vars are gone (re-run a form to recompute a value) ──"))

(defn transcript-section
  "The chronological TRANSCRIPT — the agent's messages and evals
   INTERLEAVED into a single oldest-first stream (context-v4 §2.8), so
   the agent reads ONE coherent REPL session: `user>`/`assistant>`
   message events and `<ns>=> form` evals whose value lines carry the
   result-var id. Reads `:seon.agent/n` from the ctx-entity if present
   (caps EACH stream before the merge), else 50 messages + 50 evals.

   SESSION RESUME: evals from PREVIOUS sessions render too
   ([[session-evals]] walks all sessions), separated by ONE
   [[resume-marker-line]] per resume; prior-session evals render
   WITHOUT result-var handles (their live values died with the
   process — `(result <old-id>)` says so).

   Budget eviction applies to EVAL rows ONLY, oldest-first — message
   rows and resume markers are ALWAYS kept, in chronological position.
   Before this exemption a burst of worst-case eval rows pushed the
   user's last message past the budget (S-12 KoQ turn
   Ckz-2606101827). The conversation is never sacrificed for eval
   bulk; each message is individually bounded by
   [[message-render-cap]], each AGENT eval row by [[eval-render-cap]].
   CORE-AUTHORED eval rows (the creation-turn tutorial — tagged
   by [[session-evals]]) render at [[core-eval-render-cap]]
   instead: our own scripted output arrives whole."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db :as input}]
  (let [n        (or (:seon.agent/n (:seon.ctx/section input)) 50)
        msgs     (messages {:seon.agent/n n :seon.agent/id id :seon.db/db db})
        es       (vec (take-last n (session-evals id db)))
        cur-sess (:seon.agent.session/id (current-session id db))
        msg-items
        (map (fn [m] {:seon.ctx/kind :seon.ctx/msg
                      :seon.ctx/at   (transcript-item-at m)
                      :seon.render/text (format-message-row m id)})
             msgs)
        eval-items
        (loop [[e & more] es prev-sess ::none out []]
          (if (nil? e)
            out
            (let [sess   (:seon.agent.session/id-of-session e)
                  at     (transcript-item-at e)
                  prior? (and (some? cur-sess) (not= sess cur-sess))
                  marker (when (and (not= prev-sess ::none)
                                    (not= prev-sess sess))
                           {:seon.ctx/kind :seon.ctx/marker
                            ;; just before its session's first eval
                            :seon.ctx/at   (dec at)
                            :seon.render/text resume-marker-line})
                  row    {:seon.ctx/kind :seon.ctx/eval
                          :seon.ctx/at   at
                          :seon.render/text (format-eval-row e prior?)}]
              (recur more sess (into out (if marker [marker row] [row]))))))
        items (->> (concat msg-items eval-items)
                   (sort-by :seon.ctx/at)
                   vec)]
    (if (seq items)
      (let [rendered (mapv :seon.render/text items)
            exempt?  (mapv #(not= :seon.ctx/eval (:seon.ctx/kind %)) items)
            ;; Chars the always-kept rows consume up front.
            kept-chars (transduce
                         (keep-indexed
                           (fn [i s] (when (exempt? i) (+ (count s) 2))))
                         + 0 rendered)
            ;; NEWEST-FIRST retention over the EVAL rows with whatever
            ;; budget the exempt rows leave: walk from the end
            ;; accumulating rendered chars; keep the newest eval rows
            ;; WHOLE (always at least one), drop everything older —
            ;; eviction is OLDEST-FIRST by construction.
            kept-eval (loop [i (dec (count rendered)) acc kept-chars
                             kept #{}]
                        (if (neg? i)
                          kept
                          (if (exempt? i)
                            (recur (dec i) acc kept)
                            (let [acc' (+ acc (count (rendered i)) 2)]
                              (if (and (seq kept)
                                       (> acc' transcript-char-budget))
                                kept
                                (recur (dec i) acc' (conj kept i)))))))
            kept-idx (filterv #(or (exempt? %) (kept-eval %))
                              (range (count rendered)))
            elided   (- (count rendered) (count kept-idx))
            kept     (mapv rendered kept-idx)]
        (str "<transcript>\n"
             (when (pos? elided)
               (str ";; … " elided " older eval item" (when (not= 1 elided) "s")
                    " elided (transcript capped at " transcript-char-budget
                    " chars; messages are always kept; the full log is in "
                    "the db — (seon.agent/messages) / (seon.agent/evals))\n\n"))
             (str/join "\n\n" kept)
             "\n</transcript>"))
      "")))

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
   `<turns>`/`<findings-pointer>` on inbox-count made them
   first-turn-only — dead exactly where the countdown matters). The
   ONE gate both sections consume; nothing stored, nothing to clear
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

(defn latest-inbound-text
  "Content of the agent's MOST RECENT live inbound message, regardless
   of fold/reply state — the question text source for
   `seon.agent.findings/findings-pointer-block` (the pointer must name
   the live question on EVERY turn of a wake, not just before the
   first self-fold). \"\" when no live inbound exists."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [id     (resolve-id id)
        db     (or db @db/*conn*)
        my-eid (:db/id (db/entity {:seon.db/db db
                                   :seon.db/ref [:seon.agent/id id]}))]
    (or (second (when my-eid (latest-live-inbound db my-eid))) "")))

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

(schema/register! :seon.render/stable-text   :string)
(schema/register! :seon.render/volatile-text :string)

(schema/register! :seon.render/assemble-response
  [:map
   [:seon.render/text            :string]
   [:seon.render/stable-text     :seon.render/stable-text]
   [:seon.render/volatile-text   :seon.render/volatile-text]
   [:seon.render/sections        :seon.render/sections]
   [:seon.render/section-texts   [:vector :seon.ctx/section-text]]
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
   open-todos, findings, transcript, turns, findings-pointer, prompt).

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
     7. :findings    — stored user-domain rows IN FULL (the salience
                       rung — content, not just attr names); derived,
                       vanishes when the store holds none
     8. :transcript  — ONE threaded REPL stream: messages + evals
                       chronological, append-only within a session
     9. :turns       — the turn-budget countdown (one line, mid-task
                       only; derived, vanishes when idle) — just
                       above the prompt tail for salience
    10. :findings-pointer — the question-adjacent relevance pointer
                       (L12): 1–3 lines naming the stored kinds whose
                       terms overlap the open question, pointing back
                       at the full <findings> rows; derived, vanishes
                       when idle or when nothing overlaps
    11. :prompt      — the §2.9 status line + clean REPL prompt
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
   {:seon.ctx/name :findings     :seon.ctx/priority 48
    :seon.render/ai 'seon.agent.findings/findings-section}
   {:seon.ctx/name :transcript   :seon.ctx/priority 50
    :seon.render/ai 'seon.ctx/transcript-section}
   {:seon.ctx/name :turns        :seon.ctx/priority 90
    :seon.render/ai 'seon.agent.turns/turns-section}
   {:seon.ctx/name :findings-pointer :seon.ctx/priority 95
    :seon.render/ai 'seon.agent.findings/findings-pointer-section}
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
                              "\n\n" volatile-text))]
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
     :seon.render/token-estimate (quot (count text) 4)}))

