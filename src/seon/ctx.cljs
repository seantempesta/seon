(ns seon.ctx
  "Context generation — the V3-C engine (one full-index query → one
   classifier → dumb renderers), moved out of seon.agent 2026-06-10.

   This namespace owns:
     - the `:seon.ctx/*` section schemas (`:seon.ctx/name`,
       `:seon.ctx/priority`, the `:seon.ctx/section` map shape).
       `:seon.ctx/fn` is DEAD — the one slot attr is `:seon.render/ai`
       (string = verbatim doctrine, symbol = late-bound section fn).
     - `assemble-context` — the ONE composer. Substrate default
       sections MERGED with the agent's own `:seon.agent/ctx` sections
       by one priority sort (override-by-name; replace-semantics died
       with the self-context spec, 2026-06-10). Render guard (a broken
       section renders an inline error line, never breaks assembly)
       and the per-agent section char budget live here.
     - `context-model` — the ONE classifier over the full index
       (`:seon.ns` rows + tx provenance). Rules, in precedence order:
         1. `*.internal` ns name → hidden, ALWAYS (the convention IS
            the filter).
         2. `my.*` → shown, ALWAYS (the human's world; provenance not
            consulted).
         3. ns whose corpus rows landed in an AGENT tx (tx carries
            `:seon.db/agent-id` and NOT `:seon.db/origin
            :substrate-seed`) → agent-authored → shown.
         4. `seon.*` → full-source relevant iff under a
            [[relevant-roots]] root; else a catalog count line.
       Every section consumes the model — none re-classifies.
     - the substrate default section fns (system, capabilities,
       exemplars, schema-catalog, functions-catalog,
       namespace-context, warnings, transcript, prompt) and the
       derived read API they share (messages / evals / current-ns /
       turns-since-inbound / …).
     - `seed-sections` — the `:purpose` launch-directive section (+ the
       tiny fn-shaped copyable) seeded onto every agent at create!.

   Section fns receive ONE map:
     {:seon.db/db       <db value>
      :seon.agent/id    <id string>          ; convenience, = entity id
      :seon.agent/entity <the agent's own entity, pulled ONCE>
      :seon.ctx/section  <this section's map>  ; per-section overrides
      :seon.ctx/model    <context-model>}      ; the one classifier pass
   and return a string; \"\" suppresses the section.

   seon.agent requires this ns and re-exports the agent-taught read
   API (seon.agent/messages …) as transitional aliases; the P6
   agent.cljs split finishes the relocation."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
    [seon.db :as db]
    [seon.error.instrument :as einstrument]
    [seon.eval :as seval]
    ;; Read-only fs capability — capabilities-section surfaces the LIVE
    ;; allowed-roots so the agent knows exactly what it may read.
    [seon.agent.fs :as sfs]
    [seon.handlers.fn :as h-fn]
    [seon.handlers.ns :as h-ns]
    [seon.handlers.schema :as h-schema]
    [seon.handlers.test :as h-test]
    [seon.log :as seon-log]
    [seon.render :as render]
    [seon.render.live-tile :as live-tile]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; ============================================================
;; Section schemas. A section is a plain map — the SAME shape whether
;; it lives in code (substrate defaults) or as a component entity on
;; the agent's :seon.agent/ctx vector.
;; ============================================================

(declare decode-section substrate-default-ctx)

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
   default by transacting its own value."
  [agent-id]
  (or (:seon.agent/turns-cap
        (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))
      default-turns-cap))

(defn home-ns
  "Return the deterministic home-ns symbol for an agent id.
   `(home-ns \"seon\") => 'my.agent.seon`."
  [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn current-session
  "Most-recent `:seon.agent.session` entity for `agent-id`. Returns nil if
   the agent has no sessions yet (fresh boot before `start-session!`)."
  [agent-id]
  (let [a (db/entity {:seon.db/ref [:seon.agent/id agent-id]})]
    (last (sort-by :seon.agent.session/at (:seon.agent/sessions a)))))

;; ============================================================
;; The classifier — ONE pass over the full index; every section
;; consumes the resulting model, none re-classifies. Replaces the
;; six scattered name filters (substrate-ns-name?, exemplar-ns?
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

(def relevant-roots
  "ROOT namespace names (strings) of the FULL-SOURCE set — the
   namespaces whose complete file source renders into every prompt as
   the :exemplars section (rule 4 of the classifier). An indexed ns is
   included iff its name equals a root, starts with `<root>.`
   (children ride along), or is the TEST SIBLING (`…-test`) of an
   included ns — see [[relevant-ns?]].

   Why these three: `seon.agent.search` is THE exemplar npm-package
   wrapper (wrapper doctrine, register! calls, map-in/map-out
   request/response schemas, error envelopes); `seon.agent.todo` is
   THE store/retrieve + resume arc; `my.kb` (+ child
   `my.kb.system`, the system-wide instruction singleton) is the
   knowledge-base scaffold agents copy when designing their own
   knowledge schemas.

   DELIBERATE FOLLOW-UP (not this unit): the post-split public faces
   (`seon.db` — already split —, `seon.schema`, `seon.repl`,
   `seon.agent`, `seon.agent.fs`, `seon.agent.inspect`) join this set
   as their `*.internal` splits land AND the added context size is
   re-measured against the gym (adding them today is ~+47k chars per
   prompt, unvalidated). Until then they render as catalog count
   lines, not full source."
  #{"seon.agent.search" "seon.agent.todo" "my.kb"})

(defn- relevant-base-name
  "The ns name with a trailing `-test` stripped — the SUBJECT ns a test
   sibling pairs with (`seon.agent.search-test` → `seon.agent.search`).
   Non-test names pass through unchanged."
  [ns-str]
  (if (str/ends-with? ns-str "-test")
    (subs ns-str 0 (- (count ns-str) 5))
    ns-str))

(defn relevant-ns?
  "True when `ns-name` (string, symbol, or ns-name keyword) is in the
   FULL-SOURCE set: equal to a root in [[relevant-roots]], a name-child
   of one (`<root>.<x>`), or the `-test` sibling of an included ns.
   Used by the boot indexer (`seon.client/ns-row`) to decide which
   `:seon.ns/source` rows carry the real full file text, and by
   [[exemplars-section]] to select the rows it renders — ONE rule, two
   sites, no drift. (Replaces seon.agent/exemplar-ns?, V3-C.)"
  {:malli/schema [:=> [:cat [:or :string :keyword :symbol]] :boolean]}
  [ns-name]
  (let [s    (if (keyword? ns-name) (name ns-name) (str ns-name))
        base (relevant-base-name s)]
    (boolean (some #(or (= base %) (str/starts-with? base (str % ".")))
                   relevant-roots))))

(defn- agent-authored-nses
  "Rule 3's evidence: the set of ns-name STRINGS whose `:seon.ns/name`
   row landed in an AGENT-scoped tx. The provenance predicate (verified
   against the live store, context-v3 unit 4): a tx is agent-scoped iff
   it carries `:seon.db/agent-id` AND NOT `:seon.db/origin
   :substrate-seed` (the boot seed runs inside the booting agent's
   `with-agent` scope, so seed txs carry BOTH attrs). Same clause
   family as `seon.warn/agent-registered-attrs` (the attr leg) —
   [[context-model]] is the single owner of the ns leg."
  [db]
  (let [agent-txs (into #{}
                        (map first)
                        (db/query
                          {:seon.db/db db
                           :seon.db/query
                           '[:find ?tx
                             :where
                             [?tx :seon.db/agent-id _]
                             (not [?tx :seon.db/origin :substrate-seed])]}))]
    (into #{}
          (keep (fn [[nm tx]]
                  (when (contains? agent-txs tx) (name nm))))
          (db/query {:seon.db/db db
                     :seon.db/query
                     '[:find ?nm ?tx
                       :where [?n :seon.ns/name ?nm ?tx]]}))))

(schema/register! :seon.ctx/relevant-nses [:vector :string])
(schema/register! :seon.ctx/hidden-nses   [:vector :string])
(schema/register! :seon.ctx/agent-nses    [:set :string])
(schema/register! :seon.ctx/agent-attrs   [:set :keyword])

(schema/register! :seon.ctx/model
  [:map
   [:seon.ctx/relevant-nses :seon.ctx/relevant-nses]
   [:seon.ctx/hidden-nses   :seon.ctx/hidden-nses]
   [:seon.ctx/agent-nses    :seon.ctx/agent-nses]
   [:seon.ctx/agent-attrs   :seon.ctx/agent-attrs]])

(schema/register! :seon.ctx/model-request
  [:map [:seon.db/db :seon.db/db]])

(defn context-model
  "ONE pass over the full index → the classification model every
   section consumes (none re-queries, none re-classifies):

     {:seon.ctx/relevant-nses [<ns-str> …]  ;; full source renders
      :seon.ctx/hidden-nses   [<ns-str> …]  ;; *.internal — never rendered
      :seon.ctx/agent-nses    #{<ns-str> …} ;; agent-authored (any agent)
      :seon.ctx/agent-attrs   #{<kw> …}}    ;; agent-registered attrs

   Precedence: hidden beats everything; `my.*` and agent-authored are
   shown (catalog depth); [[relevant-roots]] membership grants full
   source. The attr leg delegates to
   `seon.warn/agent-registered-attrs` — the validated provenance query
   (S-21) — so the two surfaces can never disagree."
  {:malli/schema [:=> [:cat :seon.ctx/model-request] :seon.ctx/model]}
  [{db :seon.db/db}]
  (let [ns-names (->> (db/query {:seon.db/db db
                                 :seon.db/query
                                 '[:find ?nm :where [_ :seon.ns/name ?nm]]})
                      (map (comp name first)))
        hidden   (filterv hidden-ns-name? (sort ns-names))
        agent    (agent-authored-nses db)]
    {:seon.ctx/relevant-nses (->> ns-names
                                  (remove hidden-ns-name?)
                                  (filter relevant-ns?)
                                  sort
                                  vec)
     :seon.ctx/hidden-nses   hidden
     :seon.ctx/agent-nses    (into #{} (remove hidden-ns-name?) agent)
     :seon.ctx/agent-attrs   (warn/agent-registered-attrs db)}))

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
   (let [s (pr-str v)]
     (if (> (count s) limit)
       (str (subs s 0 (max 0 (- limit 4))) " ...")
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

(defn cap-result
  "Truncate a rendered eval-result string to `eval-render-cap`,
   appending an elision marker reporting how many chars were dropped.
   Operates on the ALREADY-stringified result (`:seon.eval/result-edn`
   is a pr-str string), so no re-quoting. Nil-safe."
  ([s] (cap-result s eval-render-cap))
  ([s limit]
   (let [s (str s)
         n (count s)]
     (if (> n limit)
       (str (subs s 0 limit) " …⟨" (- n limit) " chars elided⟩")
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
              " …⟨" (- n limit) " chars clipped at " limit "⟩"
              "\n;; Narrow it: add a :find aggregate or limit, a tighter "
              ":where, or pull fewer attrs; " ref " holds the full value "
              "to drill with get-in/filter."))
       s))))

(defn- format-eval-row
  "Multi-line render for the recent-evals tile — narration, source,
   result/error, and the timing footer (`; # eval-id  Nms`).

   The rendered result/error body is capped at `eval-render-cap` chars
   (`cap-result`) so one huge eval result can't dominate the agent's
   context (context-SAFETY invariant).

   Error rendering branches: if `:seon.eval/error-data` decodes to a
   Malli instrumentation envelope, use `render-malli-error` (the
   structured ;; ERROR block with expected/got/reason/hint columns).
   Otherwise fall back to the legacy `(str \";; ERROR \" err)` plain
   path — covers timeouts, generic throws, anything pre-instrumentation."
  [{src      :seon.eval/source
    ok?      :seon.eval/ok?
    res      :seon.eval/result-edn
    out      :seon.eval/output
    err      :seon.eval/error
    err-data :seon.eval/error-data
    eid      :seon.eval/id
    dur      :seon.eval/duration-ms
    narr     :seon.eval/narration}]
  (let [envelope (read-error-envelope err-data)
        body (cond
               ok?
               (cap-result-body (or res "nil") eval-render-cap eid)

               (einstrument/instrument-error? envelope)
               (cap-result-body (einstrument/render-malli-error envelope)
                                eval-render-cap eid)

               (and (string? err) (not (str/blank? err)))
               ;; `:seon.eval/error` is now stored pre-rendered + legible
               ;; (deepest real message + structured `:seon.error/data`,
               ;; no opaque raw/stack) by `seon.eval/render-error-string`,
               ;; so it's already short — just prefix + plain-clip. NOT
               ;; `cap-result-body`, whose "narrow your query" guide is for
               ;; oversized RESULTS and is nonsensical on an error.
               (cap-result (str ";; ERROR " err))

               :else ";; <no result>")
        footer (str "  ; # " eid (when dur (str "  " dur "ms")))
        ;; Captured println/prn output (fix f) — shown above the result
        ;; like a real REPL prints before returning. Bounded by the same
        ;; per-eval render cap.
        out-ln (when (and (string? out) (not (str/blank? out)))
                 (str (cap-result (str/trimr out)) "\n"))]
    (str (when (and narr (not (str/blank? narr))) (str narr "\n"))
         "> " (cap-result src) "\n"
         out-ln
         body footer)))

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
   kind. Default {:seon.agent/n 50}."
  ([] (messages {}))
  ([{:seon.agent/keys [n id] :or {n 50}}]
   (let [id     (resolve-id id)
         my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
         rows   (when my-eid
                  (db/query
                    {:seon.db/query
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
  ([{:seon.agent/keys [id]}]
   (let [id      (resolve-id id)
         session (current-session id)]
     (last (sort-by :seon.agent.turn/at (:seon.agent.session/turns session))))))

(defn evals
  "Last N :seon.eval entries for the agent's current session,
   oldest-first. Walks :seon.agent.session/turns → :seon.agent.turn/evals (Platform
   migrated eval storage to this shape in commit 5786247).
   Default {:seon.agent/n 20}."
  ([] (evals {}))
  ([{:seon.agent/keys [n id] :or {n 20}}]
   (let [id      (resolve-id id)
         session (current-session id)
         es      (for [t (sort-by :seon.agent.turn/at (:seon.agent.session/turns session))
                       e (sort-by :seon.eval/at (:seon.agent.turn/evals t))]
                   e)]
     (vec (take-last n es)))))

(defn current-ns
  "The agent's current namespace — derived from the latest successful
   eval's :seon.eval/ns. Falls back to (home-ns id) when no successful
   eval has run yet. Reactive: the next successful eval that switches
   ns (via `(ns …)`) shows up here on the next call. See
   docs/seon/concepts/reactive-context."
  ([] (current-ns {}))
  ([{:seon.agent/keys [id]}]
   (let [id (resolve-id id)
         ;; All evals across all sessions, latest first.
         all-evals
         (for [s (:seon.agent/sessions (db/entity {:seon.db/ref [:seon.agent/id id]}))
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
  ([{:seon.agent/keys [id]}]
   (let [id      (resolve-id id)
         session (current-session id)
         turns   (:seon.agent.session/turns session)
         ;; lookup refs are NOT auto-resolved in query args — bind the
         ;; eid explicitly so the ref-valued ?me joins work.
         my-eid  (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
         latest-inbound-at
         (when my-eid
           (->> (db/query
                  {:seon.db/query
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

(defn system-section
  "REPL header: who-am-I, the strict response-format contract, the
   discovery cheat-sheet, and the four standing behavioral teachings
   (consult-before-research, store-proactively, reply-once,
   namespace-map — STAGED here by context-v4 V4-0 when the
   `<instructions>` section died; V4-1 rewrites this whole block into
   the §2.1 concept paragraphs).

   CACHE-PREFIX invariant: this section is the FIRST bytes of every
   turn's user message and must be BYTE-STABLE across turns. No
   timestamps, no current-ns, no counts — anything per-turn volatile
   lives in `prompt-section` (the always-changing tail). The old
   `Now: <ISO>` line here busted the provider prompt-cache at char 35
   every single turn (context-audit-2026-06-09 §4)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (str "<system agent=\"" id "\">\n"
       "  Your current namespace, the turn counts and the wall-clock time\n"
       "  are in the status block at the very END of this context; the\n"
       "  final line is a clean REPL prompt (<your-ns>=>) — your reply is\n"
       "  the next REPL input.\n"
       "\n"
       "  FORMAT IS STRICT. Everything you emit is either\n"
       "    (a) a Clojure form — (...), [...], {...}, @!atom\n"
       "    (b) a comment line starting with ;;\n"
       "  Anything else is a bug. Bare prose HAS eaten responses before\n"
       "  (\"Let me read the file\" once became four bogus eval entries).\n"
       "  If you write a sentence, put ;; in front of every line of it.\n"
       "\n"
       "  Correct shape:                 Wrong shape (don't do this):\n"
       "    ;; first, look around          Let me look around first.\n"
       "    (seon.db/query ...)            (seon.db/query ...)\n"
       "    ;; then, write a reply         Now I'll write the reply.\n"
       "    (seon.db/transact! ...)        (seon.db/transact! ...)\n"
       "\n"
       "  Walk your own state:\n"
       "    (seon.agent/messages)        ; current session's messages — default {:seon.agent/n 50}\n"
       "    (seon.agent/evals)           ; current session's evals — default {:seon.agent/n 20}\n"
       "    (seon.agent/current-ns)      ; your ns as data (the prompt line shows it too)\n"
       "    (result <eval-id>)           ; full live result of a prior eval (this session)\n"
       "\n"
       "  Namespaces are workspaces: (ns my.domain.thing) moves you there\n"
       "  and your CONTEXT FOLLOWS YOUR NAMESPACE — build where the work\n"
       "  lives. println/prn output is captured onto the eval's record\n"
       "  (shown above the result), but prefer returning values.\n"
       "\n"
       "  See your code in the current ns:\n"
       "    (seon.db/pull {:seon.db/pull-pattern\n"
       "                    '[:seon.ns/source\n"
       "                       {:seon.fn/_ns [*] :seon.schema/_ns [*]}]\n"
       "                    :seon.db/ref [:seon.ns/name (seon.agent/current-ns)]})\n"
       "\n"
       "  STANDING GUIDANCE:\n"
       "  - Consult stored knowledge FIRST: check the schema-catalog for\n"
       "    my.kb.* attrs and datalog those exact keywords before any\n"
       "    research. Prior agents already answered many questions —\n"
       "    re-deriving a stored answer is wasted turns. Search the repo\n"
       "    only when no stored knowledge covers the question.\n"
       "  - Store what you verify, without being asked: design (or reuse)\n"
       "    a my.kb.<domain> schema for the kind of knowledge at hand,\n"
       "    reference the shared :my.kb/* provenance attrs, and transact\n"
       "    the fact. Knowledge nobody stored is research the next agent\n"
       "    pays for again.\n"
       "  - A turn serving a question MUST end with (seon.agent/reply! …)\n"
       "    in the SAME response — your human sees NOTHING until reply!\n"
       "    lands. ONE reply per question: once reply! lands your wake is\n"
       "    complete and the loop stops. Do not emit verification forms or\n"
       "    follow-up replies after answering; a new message will wake you\n"
       "    if more is needed.\n"
       "  - Your code is my.*, your knowledge is my.kb.* (real schemas per\n"
       "    domain), and the substrate is seon.agent.* plus the other\n"
       "    seon.* namespaces — call substrate fns, never redefine them.\n"
       "</system>"))

;; ------------------------------------------------------------
;; capabilities-section — the "## What you can do" worked-examples
;; block. DERIVED, never hardcoded:
;; the core seon.db API fns are persisted as :seon.fn entities
;; (seeded by seon.client/index-substrate!), each carrying the real
;; :seon.fn/sym + :seon.fn/arglists + :seon.fn/doc. We render those
;; rows so the agent sees the exact MAP-IN call shape — the mistake
;; we observed (calling transact!/query positionally, hallucinating
;; seon.agent/current-agent-id) becomes impossible to make from
;; context. Bounded: the curated core API only (~5 fns), NOT every
;; registered :seon.fn — never the unbounded fn dump.
;; ------------------------------------------------------------

;; Render order + which core fns appear. These are exactly the syms
;; seon.client/index-substrate! persists; we pull them by identity so
;; the rendered shape is the SAME data the agent reads via
;; (seon.db/pull [:seon.fn/sym …]) — one source, no divergence.
(def ^:private capability-syms
  ["seon.schema/register!"
   "seon.db/transact!"
   "seon.db/query"
   "seon.db/pull"
   "seon.db/entity"
   "seon.db/listen!"
   "seon.db/current-agent-id"])

(defn- first-doc-line
  "First SENTENCE of a docstring (joined across the first few lines, cut
   at the first \". \") — the one-liner for the catalogs. The old
   first-LINE version dangled mid-sentence (\"Two call shapes:\") when a
   docstring's opening sentence wrapped. Full doc stays on the
   :seon.fn entity."
  [doc]
  (let [flat (->> (str/split-lines (or doc ""))
                  (map str/trim)
                  (remove str/blank?)
                  (take 3)
                  (str/join " "))
        idx  (str/index-of flat ". ")]
    (cond
      (str/blank? flat) nil
      (some? idx)       (subs flat 0 (inc idx))
      (> (count flat) 140) (str (subs flat 0 140) " …")
      :else             flat)))

(defn- arglist-vectors
  "Split a stored `:seon.fn/arglists` string — \"([k v])\",
   \"([req] [db selector eid])\" — into its top-level arg-vector strings
   ([\"[k v]\"] / [\"[req]\" \"[db selector eid]\"]). Returns [] for
   blank or \"()\" (unknown arity)."
  [arglists]
  (let [s (str/trim (or arglists ""))
        n (count s)]
    (loop [i 0 depth 0 start nil out []]
      (if (>= i n)
        out
        (let [c (nth s i)]
          (cond
            (= c \[) (recur (inc i) (inc depth)
                            (if (zero? depth) i start) out)
            (= c \]) (let [d (dec depth)]
                       (if (and (zero? d) (some? start))
                         (recur (inc i) d nil (conj out (subs s start (inc i))))
                         (recur (inc i) d start out)))
            :else    (recur (inc i) depth start out)))))))

(defn- callable-sigs
  "One CALLABLE shape per arity from a fn sym + stored arglists string:
   \"([k v])\" → [\"(sym k v)\"]; \"([req] [db eid])\" → [\"(sym req)\"
   \"(sym db eid)\"]. The old render glued the raw arglists string after
   the sym — `(seon.db/pull ())` / `(register! ([k v]))` — which taught
   an UNCALLABLE shape (context-audit 2026-06-09 §2). Unknown arity →
   [\"(sym …)\"]."
  [sym arglists]
  (let [vs (arglist-vectors arglists)]
    (if (seq vs)
      (mapv (fn [v]
              (let [inner (str/trim (subs v 1 (dec (count v))))]
                (if (str/blank? inner)
                  (str "(" sym ")")
                  (str "(" sym " " inner ")"))))
            vs)
      [(str "(" sym " …)")])))

(defn capabilities-section
  "Render the `## What you can do` worked-examples block.
   DERIVED from the persisted core `:seon.fn` entities —
   each fn's `:seon.fn/sym` + `:seon.fn/arglists` (the map-in shape) +
   a one-line `:seon.fn/doc`. Includes one fully-worked `transact!`
   example so the positional-call mistake is impossible to make from
   context. Bounded to the curated core API (`capability-syms`)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows  (->> capability-syms
                   (keep (fn [sym]
                           (let [e (db/entity {:seon.db/db db
                                               :seon.db/ref [:seon.fn/sym sym]})]
                             (when e
                               {:sym      (:seon.fn/sym e)
                                :arglists (:seon.fn/arglists e)
                                :doc      (first-doc-line (:seon.fn/doc e))})))))
        lines (for [{:keys [sym arglists doc]} rows]
                (str (str/join "\n"
                       (map #(str "  " %) (callable-sigs sym arglists)))
                     (when (seq doc) (str "\n      ; " doc))))
        roots (seq (:seon.agent.fs/allowed-roots @sfs/!config))]
    (if (seq rows)
      (str "<capabilities>\n"
           "## What you can do\n\n"
           "These are the core APIs. Map-in is the preferred shape: you pass\n"
           "ONE map with fully-namespaced keys (see the worked examples below).\n"
           "The db ops (query/pull/entity/transact!) ALSO accept a natural\n"
           "datahike-style positional form.\n\n"
           (str/join "\n" lines)
           "\n\n"
           "### Storing a NEW KIND of data: register the schema FIRST\n\n"
           "To store a NEW kind of fact you must REGISTER each attribute with\n"
           "`seon.schema/register!` BEFORE you transact it. Storing a schema's\n"
           "source as data is NOT registration — an unregistered attr is\n"
           "REJECTED by transact!. register! is the single source of truth:\n"
           "register the TYPE and the system derives datahike storage.\n\n"
           "But FIRST check the schema-catalog's `domain data attrs` block:\n"
           "if attrs for this kind of fact ALREADY exist, the kind is not\n"
           "new — transact with the existing keywords directly, zero\n"
           "register! calls needed. Registering a parallel shape beside an\n"
           "existing one (new namespace, new units) forks the data: the\n"
           "next query misses half the rows.\n\n"
           "Use DEEP, namespaced attrs — the keyword namespace must have at\n"
           "least TWO dot-separated segments, like a real code namespace:\n"
           "  :my.kb.codebase/claim   YES — multi-segment namespace\n"
           "  :finding/claim          NO  — single-segment namespace, same\n"
           "                                violation as a bare key\n"
           "  :title                  NO  — bare key\n"
           "Common shapes:\n"
           "  - plain values:                  :string, :keyword, :inst, :boolean\n"
           "  - a reference to another entity: :seon.db/ref\n"
           "  - many references:               [:vector :seon.db/ref]\n"
           "  - numbers: :int for counts/ids, :double for measures —\n"
           "    :number is NOT a type (the transact! gate will tell you).\n"
           "  - identity is OPTIONAL — [:string {:seon.db/identity true}]\n"
           "    only on a kind's ONE natural key, and only when rows must\n"
           "    upsert by that key (re-transacting the same key updates the\n"
           "    row instead of adding a duplicate). Most attrs, one-off\n"
           "    entities, and bulk rows need NO identity attr at all; never\n"
           "    re-register an existing attr just to add identity.\n\n"
           "  ;; 1. register the attrs (do this ONCE per attr)\n"
           "  (seon.schema/register! :my.kb.doc/path  [:string {:seon.db/identity true}])\n"
           "  ;;   ^ identity ONLY because docs re-index by path (upsert key)\n"
           "  (seon.schema/register! :my.kb.doc/title :string)\n"
           "  (seon.schema/register! :my.kb.doc/tags  [:vector :keyword])\n\n"
           "  ;; 2. NOW transact data using those attrs — upserts by :my.kb.doc/path\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:my.kb.doc/path  \"docs/seon/_dashboard.md\"\n"
           "       :my.kb.doc/title \"Dashboard\"\n"
           "       :my.kb.doc/tags  [:index :dashboard]}]})\n\n"
           "  ;; 3. read it back\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find ?path ?title\n"
           "                    :where [?e :my.kb.doc/path ?path]\n"
           "                           [?e :my.kb.doc/title ?title]]})\n\n"
           "Totals and aggregates: compute IN the query over the STORED data —\n"
           "(sum ?v), (count ?e), (max ?v) — never by hand from your own turn:\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find (sum ?secs)\n"
           "                    ;; :with ?e is REQUIRED for a row total —\n"
           "                    ;; datalog is set-semantics, so without it\n"
           "                    ;; two entities with the SAME value dedupe\n"
           "                    ;; to one and the sum comes out short.\n"
           "                    :with ?e\n"
           "                    :where [?e :my.domain/duration-seconds ?secs]]})\n\n"
           "When a query comes back EMPTY (#{}), suspect a misspelled attribute\n"
           "before you conclude there's no data. The usual cause is a shortened\n"
           "namespace: copy the attribute keyword EXACTLY as the schema-catalog\n"
           "shows it (if the catalog lists :my.kb.doc/path, query that — not\n"
           ":kb.doc/path). Fix the keyword and re-run.\n\n"
           "### Reading one entity: pull and entity\n\n"
           "The db ops are datahike-compatible — map-in (shown) and positional\n"
           "(db-first, e.g. (seon.db/pull <db> selector eid)) both work.\n\n"
           "  ;; pull — one entity as a plain map, by lookup-ref or eid\n"
           "  (seon.db/pull {:seon.db/pull-pattern '[:seon.fn/sym :seon.fn/doc]\n"
           "                 :seon.db/ref          [:seon.fn/sym \"seon.db/query\"]})\n\n"
           "  ;; entity — lazy map-like view; read attrs like a map\n"
           "  (:seon.fn/doc (seon.db/entity {:seon.db/ref [:seon.fn/sym \"seon.db/query\"]}))\n\n"
           "### Reacting to writes: listen!\n\n"
           "  (seon.db/listen!\n"
           "    {:seon.db/key     :my-ns/watch\n"
           "     :seon.db/handler (fn [{:seon.db/keys [db attr-index]}]\n"
           "                        ;; runs after EVERY transact; attr-index\n"
           "                        ;; groups the tx's datoms by attribute\n"
           "                        (when (:kb.doc/path attr-index)\n"
           "                          (js/console.log \"new doc stored\")))})\n"
           "  ;; same :seon.db/key replaces; remove with\n"
           "  ;; (seon.db/unlisten! {:seon.db/key :my-ns/watch})\n\n"
           "### Reading the repo (files on this machine)\n\n"
           (if roots
             (str "You can READ files under: " (str/join ", " roots) "\n"
                  "(read-only; everything outside these roots is denied)\n\n")
             (str "No filesystem roots are granted right now (default-deny) —\n"
                  "every seon.agent.fs call returns an error envelope that explains\n"
                  "how access is configured.\n\n"))
           "Paths are ABSOLUTE, real machine paths — there is no virtual\n"
           "root or chroot. When your human asks where something is,\n"
           "answer with the real path exactly as the substrate returns it.\n\n"
           "The recipe is CONSULT KNOWLEDGE → SEARCH → READ PRECISELY (never\n"
           "walk + guess). Step 0 is the consult-before-research instruction:\n"
           "the schema-catalog lists the my.kb.* (and other my.*) attrs that\n"
           "exist — datalog those EXACT keywords; there is no consult API:\n\n"
           "  ;; 0. e.g. the catalog shows :my.kb.codebase/claim et al:\n"
           "  (seon.db/query {:seon.db/query\n"
           "                  '[:find ?claim ?path ?line\n"
           "                    :where [?f :my.kb.codebase/claim ?claim]\n"
           "                           [?f :my.kb/source-path    ?path]\n"
           "                           [?f :my.kb/source-line    ?line]]})\n"
           "  ;; A hit IS the answer, with provenance — cite it (re-read the\n"
           "  ;; source line only if you must verify).\n"
           "  ;; NOTE: only built-in predicates work inside :where (=, <,\n"
           "  ;; get-else, missing?) — clojure.string/* fns DO NOT resolve\n"
           "  ;; in a query. Fetch the rows plain, filter in code after.\n\n"
           "  ;; 1. grep for a term (regex). Call it as the WHOLE form — the\n"
           "  ;;    result is auto-awaited; inside a let you'd get a Promise.\n"
           "  (seon.agent.search/grep {:seon.agent.search/pattern \"validate-entity-values!\"\n"
           "                     :seon.agent.search/glob    \"*.cljs\"})\n"
           "  ;; => {:seon.agent.search/ok? true, :seon.agent.search/matches\n"
           "  ;;     [{:seon.agent.search/path \"…/src/seon/db/internal.cljs\"\n"
           "  ;;       :seon.agent.search/line-number 499, …}], …}\n\n"
           "  ;; 2. read the exact hit (sync; match paths are absolute)\n"
           "  (seon.agent.fs/read-file {:seon.agent.fs/path \"<absolute path from the match>\"})\n\n"
           "A denial is a VALUE, not a crash — {:seon.agent.fs/ok? false\n"
           ":seon.agent.fs/error \"…\"} tells you whether the path is out of scope\n"
           "or the fs is read-only. Read the error; it says what to do.\n\n"
           "### Storing what you learn — design a my.kb.<domain> schema\n\n"
           "Knowledge is SCHEMA'D DATA in my.kb.<domain> namespaces, one REAL\n"
           "schema per knowledge kind (see the my.kb exemplar above; the\n"
           "store-proactively instruction says WHEN). Check the schema-catalog\n"
           "first: if attrs for this kind already exist, REUSE those exact\n"
           "keywords — NEVER invent a parallel shape. Reference the shared\n"
           ":my.kb/* provenance attrs (already registered) instead of\n"
           "re-inventing source-path/line/confidence per domain:\n\n"
           "  ;; e.g. verified facts about fns in a codebase:\n"
           "  (seon.schema/register! :my.kb.codebase.fn/name  [:string {:seon.db/identity true}])\n"
           "  ;;   ^ identity ONLY because claims upsert by fn name; most\n"
           "  ;;     attrs need no identity (see Common shapes)\n"
           "  (seon.schema/register! :my.kb.codebase.fn/claim :string)\n\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:my.kb.codebase.fn/name  \"seon.db/transact!\"\n"
           "       :my.kb.codebase.fn/claim \"Malli-validates every entity before the tx reaches datahike\"\n"
           "       :my.kb/source-path       \"src/seon/db/internal.cljs\"\n"
           "       :my.kb/source-line       499\n"
           "       :my.kb/confidence        :verified}]})  ; :verified = you read that line\n\n"
           "WHY schemas, not text blobs: the next agent discovers your attrs\n"
           "in the schema-catalog, datalogs your rows (recipe step 0) and\n"
           "reuses them — knowledge compounds only when it is queryable.\n\n"
           "### Replying — one line, the substrate knows who asked:\n\n"
           "  (seon.agent/reply! {:seon.agent.message/content \"on it — here's what I found\"})\n"
           "  ;; => {:seon.agent.message/ok? true, :seon.agent.message/id \"MSG…\",\n"
           "  ;;     :seon.agent.message/hops 1}   ; failure → {:seon.db/ok? false …}\n\n"
           "Messaging another agent (or an explicit target) — :seon.agent.message/to\n"
           "takes a ref or a vector of refs:\n\n"
           "  (seon.agent/message!\n"
           "    {:seon.agent.message/to      [:seon.agent/id \"<other-agent-id>\"]\n"
           "     :seon.agent.message/content \"can you check the workout totals?\"})\n\n"
           "### Your live tile (your one HTML surface in the inspector)\n\n"
           "You own ONE always-visible tile, rendered above the entity cards.\n"
           "Default renderer: seon.render.live-tile/welcome. Repoint it: define a fn\n"
           "returning {:seon.render/hiccup [...]}, then transact its symbol:\n\n"
           "  (defn my-tile [_input]\n"
           "    {:seon.render/hiccup [:div [:h2 \"status\"] [:p \"all green\"]]})\n\n"
           "  (seon.db/transact!\n"
           "    {:seon.db/tx-data\n"
           "     [{:seon.agent/id (seon.db/current-agent-id)\n"
           "       :seon.render.live-tile/content 'YOUR-CURRENT-NS/my-tile}]})  ; fully qualified\n"
           "</capabilities>")
      "")))

;; ============================================================
;; exemplars-section — FULL source of the chosen exemplar namespaces,
;; rendered from the program graph (context-focus-redesign 2026-06-10).
;; The user direction: fewer mechanisms at full depth beats 102
;; signatures at zero depth — give the agent COMPLETE, in-conventions
;; namespaces (schemas + fns + tests) to copy the SHAPE from.
;;
;; The section NEVER re-reads files at render time (code-as-data): the
;; boot indexer (`seon.client/index-substrate!` / `index-tests`) is the
;; ONE file-reader; it persists the real full file text on
;; `:seon.ns/source` for every ns matched by `relevant-ns?`. This
;; section just queries those datoms. Byte-stable for the life of a pod
;; process (source can only change with a build change, which restarts
;; the pod) — it belongs inside the provider-cacheable static prefix,
;; so it renders at priority 22, between :capabilities (20) and the
;; semi-static :schema-catalog (25).
;; ============================================================


(defn- relevant-sort-key
  "Deterministic render order for exemplar nses: alphabetical by the
   base (subject) name, test sibling AFTER its subject. For the current
   set that yields seon.agent.search → seon.agent.search-test → seon.agent.todo →
   seon.agent.todo-test — byte-stable across renders (LLM cache-prefix
   invariant: no timestamps, no map-order nondeterminism)."
  [ns-str]
  [(relevant-base-name ns-str) (if (str/ends-with? ns-str "-test") 1 0)])

(def ^:private exemplars-header
  ";; These complete namespaces are THE models for code you write: this is\n;; what a finished schema set, a specced map-in/map-out fn, an error\n;; envelope, and a test suite look like here. Copy the SHAPE — register!\n;; shapes, ::request/::response pairs, :malli/schema on every public fn,\n;; errors as values, deftest + fixture + envelope assertions.\n;; These fns already exist — call them; never re-define them.")

(defn exemplars-section
  "FULL source of the exemplar namespaces ([[relevant-roots]] + children
   + test siblings), each wrapped in `<exemplar ns=\"…\">…</exemplar>`,
   queried from the `:seon.ns/source` datoms the boot indexer persisted —
   never a render-time file read (code-as-data: the boot indexer is the
   ONE file-reader; everything downstream reads the graph).

   A matched ns whose source is missing or still the `(ns x)` stub
   renders NOTHING for that ns and logs fail-loud — never throws, never
   silently pads with the stub. Deterministically ordered
   ([[relevant-sort-key]]) so the section is byte-stable across renders."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?src
                         :where
                         [?n :seon.ns/name ?nm]
                         [?n :seon.ns/source ?src]]})
                    (map (fn [[nm src]] [(name nm) src]))
                    (filter (fn [[ns-str _]] (relevant-ns? ns-str)))
                    (sort-by (fn [[ns-str _]] (relevant-sort-key ns-str))))
        blocks (keep (fn [[ns-str src]]
                       (if (or (str/blank? src)
                               (= (str/trim src) (str "(ns " ns-str ")")))
                         (do (seon-log/error-console!
                               "seon.agent/exemplars-section"
                               (str "exemplar ns " ns-str " has no full "
                                    ":seon.ns/source (stub or blank) — "
                                    "omitted from the section; the boot "
                                    "indexer should have persisted the "
                                    "real file text"))
                             nil)
                         (str "<exemplar ns=\"" ns-str "\">\n"
                              (str/trim src)
                              "\n</exemplar>")))
                     rows)]
    (if (seq blocks)
      (str "<exemplars>\n" exemplars-header "\n\n"
           (str/join "\n\n" blocks)
           "\n</exemplars>")
      "")))

;; ============================================================
;; schema-catalog-section — the GLOBAL cross-namespace catalog of every
;; ENTITY kind stored in the system. Layered ON TOP of the per-ns
;; `namespace-context` (T5): namespace-context is the DEEP current-ns
;; view (this ns's fns/tests/source); the catalog is the BROAD view —
;; ALL the kinds of things that exist in the substrate, REGARDLESS of
;; the agent's current ns. This is HOW the agent knows what data the
;; system holds (user, 2026-06-08 night).
;;
;; DERIVED, never hardcoded: the catalog reads the `:seon.schema`
;; entities seeded at boot (`all-entity-schemas-tx-data`). An entity
;; KIND is a `:seon.schema` entity that carries a `:seon.schema/render-fn`
;; — i.e. a renderable `:map` entity-shape schema (`:seon.fn`, `:seon.ns`,
;; `:seon.eval`, `:seon.agent.message`, `:seon.schema`, `:seon.test`, …) — as
;; opposed to a request/response `:map` (which has no render symbol). The
;; `:seon.schema` entity stores the kind's `:seon.schema/id-attr`; the
;; per-attr SHAPE (type + which attrs are optional) is pulled from the
;; live registry via `seon.schema/schema-definition`, the source the
;; `:seon.schema` entity doesn't itself carry. Instance counts come from
;; one AEVT count on each kind's id-attr — defined-but-empty kinds still
;; list (count 0 is informative).
;;
;; Pure render of the DB + registry — stores nothing.
;; ============================================================

(defn- catalog-type-str
  "Render an attr's registered Malli form as a COMPACT type label for the
   catalog: a bare keyword as-is; `[:and {…} inner]` (the identity-wrap)
   as its inner ref; `[:vector/:set {…} elem]` as `vector<elem>`;
   `[:enum …]` as `enum (…)`; any other `[:type {props}]` as just `:type`.
   Keeps each attr line to one short token so a whole-system catalog of
   ~10 kinds stays a few KB."
  [t]
  (cond
    (keyword? t) (str t)
    (vector? t)
    (let [head (first t)
          rst  (rest t)]
      (case head
        :and (let [inner (remove map? rst)]
               (if (= 1 (count inner))
                 (catalog-type-str (first inner))
                 (str "(" (str/join " & " (map catalog-type-str inner)) ")")))
        :or  (str "(" (str/join " | " (map catalog-type-str (remove map? rst))) ")")
        (:vector :set) (str (name head) "<" (catalog-type-str (last (remove map? rst))) ">")
        :enum (str "enum " (pr-str (vec rst)))
        (str head)))
    :else (pr-str t)))

(defn- catalog-attr-rows
  "Attribute rows for an entity KIND, pulled from the live registry
   (`seon.schema/schema-definition`). Each row:
   `{:attr <kw> :type <compact-str> :optional <bool> :id? <bool>}`.
   Returns nil when `kind` isn't a registered `:map` schema. The id-attr
   is read from the schema's derived `:seon.entity/id-attr` prop."
  [kind]
  (let [form (schema/schema-definition kind)]
    (when (and (vector? form) (= :map (first form)))
      (let [props   (when (map? (second form)) (second form))
            id-attr (:seon.entity/id-attr props)
            body    (let [b (rest form)]
                      (if (and (seq b) (map? (first b))) (rest b) b))]
        (for [entry body :when (and (vector? entry) (keyword? (first entry)))]
          (let [k     (first entry)
                eprops (let [p (second entry)] (when (map? p) p))]
            {:attr     k
             :type     (catalog-type-str (schema/schema-definition k))
             :optional (boolean (:optional eprops))
             :id?      (= k id-attr)}))))))

(defn- catalog-kind-count
  "Count instances of `kind` by counting datoms on its `id-attr`
   (one AEVT scan). Bounded: one count query per kind."
  [db id-attr]
  (count (db/query {:seon.db/db db
                    :seon.db/query [:find '?e :where ['?e id-attr '_]]})))

(def ^:private uncounted-kind-id-attrs
  "Id-attrs of HIGH-CHURN substrate kinds whose live instance count
   changes EVERY turn (each eval + message is an instance) — rendering
   an exact count here would bust the prompt-cache prefix on every
   render (context-audit 2026-06-09 §4). The kind + attrs still list;
   the instances themselves are already in the transcript."
  #{:seon.eval/id :seon.agent.message/id})

(defn- fuzzy-count
  "Bucketed live-count label for catalog blocks: exact below 20, then
   rounded DOWN to a bucket (\"40+\", \"300+\", \"2000+\") so slow corpus
   growth doesn't bust the semi-static catalog prefix per increment."
  [n]
  (cond
    (< n 20)   (str n)
    (< n 200)  (str (* 10 (quot n 10)) "+")
    (< n 2000) (str (* 100 (quot n 100)) "+")
    :else      (str (* 1000 (quot n 1000)) "+")))

(defn- catalog-kind-block
  "Render one entity kind: a `[kind  N instances]` header then one line
   per attribute (`id`/`opt` flags + compact type). The id-attr line is
   marked `id`; optional attrs are marked `?`. High-churn substrate
   kinds (`uncounted-kind-id-attrs`) render without a count; other
   kinds use the bucketed `fuzzy-count` label — both are cache-prefix
   stability measures."
  [db {:keys [kind id-attr]}]
  (let [rows  (sort-by (fn [{:keys [id? attr]}] [(if id? 0 1) (str attr)])
                       (catalog-attr-rows kind))
        lines (for [{:keys [attr type optional id?]} rows]
                (str "  " (cond id? "id " optional "?  " :else "   ")
                     attr " : " type))
        label (if (contains? uncounted-kind-id-attrs id-attr)
                "(per-turn data — uncounted)"
                (let [n (fuzzy-count (catalog-kind-count db id-attr))]
                  (str n " instance" (when (not= "1" n) "s"))))]
    (str "[" kind "]  " label "\n"
         (str/join "\n" lines))))

(defn- db-schema
  "The datahike schema map of `db`, FilteredDB-safe. FilteredDB (the
   inspector's per-agent view) doesn't implement ILookup — `(:schema db)`
   THROWS. The schema is conn-level (the filter can't change it), so
   read through to the wrapped db. Same guard as
   `seon.warn/domain-attrs`; surfaced live at the flip (2.2e) because
   the cluster store carries attrs absent from the live Malli registry
   (other writers' attrs), which sent [[domain-attr-line]] down the
   installed-valueType fallback for the first time on a FilteredDB."
  [db]
  (try (:schema db)
       (catch :default _
         (:schema (.-unfiltered-db ^js db)))))

(defn- domain-attr-line
  "One catalog line for a DOMAIN attr: keyword, compact type (live
   registry when present, installed datahike valueType otherwise) and
   the live instance count — `duration-seconds (2 entities)` is what
   makes an existing attr hard to miss."
  [db attr]
  (let [t (if-let [form (schema/schema-definition attr)]
            (catalog-type-str form)
            (str (get-in (db-schema db) [attr :db/valueType])))
        n (fuzzy-count (catalog-kind-count db attr))]
    (str "  " attr " : " t " — " n " entit" (if (= "1" n) "y" "ies"))))

(defn- domain-attrs-block
  "The `domain data attrs` portion of the catalog: every agent-
   registered attr installed on the db (via [[seon.warn/domain-attrs]]
   — substrate internals excluded), grouped by keyword namespace, each
   with type + live instance count. Empty string when no domain attrs
   exist yet. This is the REUSE surface: run 4 proved an agent forks a
   parallel attr when the existing shape isn't in front of it."
  [db]
  (let [attrs  (warn/domain-attrs {:seon.db/db db})
        groups (->> attrs (group-by namespace) (sort-by first))]
    (if (seq attrs)
      (str "\n\n=== domain data attrs — REUSE these exact keywords ===\n"
           ";; Attrs already holding your human's data. Before you register!\n"
           ";; a new attr, check here: same kind of fact → use the EXISTING\n"
           ";; attr (exact keyword, exact unit). Extend with new attrs only\n"
           ";; for genuinely new facts; never fork the same quantity into\n"
           ";; different units.\n"
           (str/join "\n"
             (for [[ns-str ks] groups]
               (str "[" ns-str "]\n"
                    (str/join "\n" (map #(domain-attr-line db %) ks))))))
      "")))

(defn- squash-one-line
  "Whitespace-squash + cap a stored string for a one-line catalog row."
  [s]
  (let [flat (str/replace (str s) #"\s+" " ")]
    (if (> (count flat) 140) (str (subs flat 0 140) " …") flat)))

(defn- finding-claims-block
  "One-liner CONTENT of stored findings — the claim strings themselves,
   not just attr names (#26 finding-salience: run 7 proved attr names in
   the catalog are discoverable but not CONSULTED — agent #2 re-derived
   a stored answer). Renders the values of every domain attr NAMED
   `claim` (any namespace — the taught shape is a claim-carrying
   my.kb.<domain> attr like :my.kb.codebase.fn/claim, but earlier
   corpora used other namespaces), capped at 12 rows of ≤140
   chars. Empty string when no claims exist. Pure render of the db —
   stores nothing."
  [db]
  (let [claim-attrs (->> (warn/domain-attrs {:seon.db/db db})
                         (filter #(= "claim" (name %))))
        rows (->> claim-attrs
                  (mapcat (fn [a]
                            (->> (db/query {:seon.db/db db
                                            :seon.db/query
                                            [:find '?v :where ['_ a '?v]]})
                                 (map first)
                                 sort
                                 (map (fn [v] [a v])))))
                  (take 12))]
    (if (seq rows)
      (str "\n\n=== stored findings — CONSULT these before re-deriving ===\n"
           ";; Claims prior agents verified and stored. If one answers the\n"
           ";; question at hand, pull its full row (sibling attrs in the\n"
           ";; same namespace: question, source path, line, confidence)\n"
           ";; instead of re-searching the repo.\n"
           (str/join "\n"
             (for [[a v] rows]
               (str "  " a " — \"" (squash-one-line v) "\""))))
      "")))

(defn- schema-ns-summary-block
  "Compact index of EVERY registered schema in the system, as per-ns
   count lines (unit #23 fix b: all ~276 registered schemas are now
   `:seon.schema` rows; rendering each would blow the context budget, so
   the catalog shows the index and teaches the entity-read). Namespaced
   keys only — the un-namespaced entity KINDS already render as full
   blocks above. Counts are bucketed (`fuzzy-count`) for cache-prefix
   stability."
  [db]
  (let [ks     (->> (db/query {:seon.db/db db
                               :seon.db/query
                               '[:find ?k :where [?e :seon.schema/key ?k]]})
                    (map first)
                    (filter namespace))
        groups (->> ks (group-by namespace) (sort-by first))]
    (if (seq groups)
      (str "\n\n=== all registered schemas, by namespace ===\n"
           ";; Every registered schema is a :seon.schema row; read a shape:\n"
           ";; (:seon.schema/source (seon.db/entity\n"
           ";;    {:seon.db/ref [:seon.schema/key :seon.db/ref]}))\n"
           (str/join "\n"
             (for [[ns-str ns-ks] groups]
               (str "  " ns-str " — " (fuzzy-count (count ns-ks))
                    " schema" (when (not= 1 (count ns-ks)) "s")))))
      "")))

(defn schema-catalog-section
  "GLOBAL schema catalog — EVERY registered entity KIND in the system,
   grouped by owning namespace, REGARDLESS of the agent's current ns.
   This is how the agent knows what data the substrate holds: each kind's
   key, its attributes (name + compact type, identity attr flagged), and
   a live instance count.

   DERIVED from the `:seon.schema` entities (seeded at boot via
   `seon.schema/all-entity-schemas-tx-data`) — a kind is a `:seon.schema`
   entity carrying a `:seon.schema/render-fn` (a renderable `:map`
   entity-shape, not a request/response map). Per-attr shapes come from
   the live registry; counts from an AEVT scan on each id-attr. A
   trailing `domain data attrs` block lists every agent-registered attr
   installed on the db (with type + instance count) and states the
   reuse contract — the run-4 fix for forked parallel attrs; a trailing
   `stored findings` block surfaces finding CONTENT one-liners (the #26
   consult-before-research salience fix). Stores nothing; register a
   new entity kind and it appears here next render.

   The wrapper renders when ANY block has content — the kinds list does
   NOT gate the trailing blocks. (Gym iteration 1, 2026-06-10: on a
   store with no `:seon.schema/render-fn` rows the old `(seq kinds)`
   gate silently dropped the domain-attrs and finding-claims blocks,
   so S-21 forked a parallel workout namespace and S-32 re-derived a
   seeded finding — neither ever SAW the reuse/consult surface.)"
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [kinds (->> (db/query
                     {:seon.db/db db
                      :seon.db/query
                      '[:find ?k ?ida
                        :where
                        [?e :seon.schema/key ?k]
                        [?e :seon.schema/id-attr ?ida]
                        [?e :seon.schema/render-fn _]]})
                   (map (fn [[k ida]]
                          {:kind k :id-attr ida :owner-ns (namespace ida)})))
        groups (->> kinds
                    (group-by :owner-ns)
                    (sort-by first))
        kinds-block
        (when (seq kinds)
          (str/join "\n\n"
            (for [[ns ks] groups]
              (str "=== " ns " ===\n"
                   (str/join "\n\n"
                     (map #(catalog-kind-block db %)
                          (sort-by (comp str :kind) ks)))))))
        summary-block (schema-ns-summary-block db)
        domain-block  (domain-attrs-block db)
        claims-block  (finding-claims-block db)]
    (if (or kinds-block
            (seq summary-block) (seq domain-block) (seq claims-block))
      (str "<schema-catalog>\n"
           ";; Every kind of entity stored in the system, grouped by namespace.\n"
           ";; This is the WHOLE substrate — not just your current ns. These\n"
           ";; shapes EXIST: REUSE their exact attrs (copy keywords + units\n"
           ";; exactly); register! only what's missing. Query any kind by its\n"
           ";; id-attr, e.g. (seon.db/query {:seon.db/query\n"
           ";;   '[:find ?id :where [?e :seon.fn/sym ?id]]}).\n\n"
           kinds-block
           summary-block
           domain-block
           claims-block
           "\n</schema-catalog>")
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

(defn namespace-context-section
  "The agent's NAMESPACE context — `render-namespace` of the agent's
   current namespace at depth 1, so its direct `(:require …)`s render
   FIRST (prepended), then the ns itself. Drop a fresh agent into a
   near-empty home-ns that requires a parent agent ns and depth-1 brings
   the parent's fns/schemas/tests into view.

   Pure render of the DB: `render-namespace` reads the persisted
   `:seon.ns`/`:seon.fn`/`:seon.schema`/`:seon.test` corpus and stores
   nothing. Renders blank only when the ns has no recorded entities and
   no requires (a brand-new home-ns before any `(ns …)`/`(defn …)`)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns    (current-ns {:seon.agent/id id})
        ;; current-ns returns a SYMBOL (the home-ns / latest eval's ns);
        ;; render-namespace's input schema requires a keyword :seon.ns/name.
        ns-kw (if (keyword? ns) ns (keyword (str ns)))
        text  (-> (render-namespace {:seon.ns/name ns-kw
                                     :seon.render/depth 1
                                     :seon.render/format :ai
                                     :seon.db/db db})
                  :seon.render/text)
        ;; Empty-ns nudge (unit #23 fix c): when the CURRENT ns owns no
        ;; fns/schemas/tests, say so and teach the move — context follows
        ;; the namespace, so an agent sitting in an empty ns should either
        ;; define here or (ns …) to where the code is.
        data  (pull-ns-data db ns-kw)
        empty-ns? (and (empty? (:seon.fn/_ns data))
                       (empty? (:seon.schema/_ns data))
                       (empty? (:seon.test/_ns data)))
        ;; A fresh agent's own not-yet-in-db home-ns used to render as
        ;; ';; requires: <own-ns> (not in db)' — a mislabel (it's not a
        ;; require; context-audit item 11). Drop that lone line; the
        ;; empty-ns nudge below says it properly.
        text  (if (and (nil? data)
                       (= (str/trim text)
                          (str ";; requires: " (name ns-kw) " (not in db)")))
                ""
                text)
        nudge (when empty-ns?
                (str ";; Your current namespace (" (name ns-kw) ") is EMPTY —\n"
                     ";; no fns, schemas or tests yet. Define here, or switch\n"
                     ";; with (ns other.ns) to move where the code is: your\n"
                     ";; context follows your namespace."))]
    (cond
      (and (str/blank? text) (nil? nudge)) ""
      (str/blank? text)
      (str "<namespace-context>\n" nudge "\n</namespace-context>")
      :else
      (str "<namespace-context>\n" text
           (when nudge (str "\n\n" nudge))
           "\n</namespace-context>"))))

;; ============================================================
;; functions-catalog-section — the THIN cross-namespace INDEX of the fn
;; corpus. The sibling of `schema-catalog-section`: the catalog answers
;; "what KINDS of data exist"; this answers "what CODE already exists".
;; This is how a later agent (or a later turn) discovers and reuses an
;; earlier agent's work instead of re-deriving it (user, 2026-06-09 —
;; kill the over-orientation / re-implementation loop).
;;
;; Collapsed to a thin index (context-focus-redesign §2, unit E2/E3):
;;   - SUBSTRATE nses (compiled seon.* code) — ONE count line per ns;
;;     bodies are one `:seon.fn/source` pull away (the header teaches
;;     the query). Exemplar-root nses cross-reference the full source
;;     rendered in :exemplars above.
;;   - AGENT-AUTHORED nses — one callable line per fn for small groups,
;;     a count line for large ones. The agent's OWN ns renders its full
;;     source in :namespace-context (the deep current-ns view) — the
;;     old own-ns full-source duplicate here DIED with the redesign.
;;
;; DERIVED, never hardcoded: one datalog join over the `:seon.fn` corpus
;; (the same entities `index-substrate!` seeds and detect-and-tee
;; appends). Define a fn → it appears here next render; stores nothing.
;; ============================================================

(defn- fn-catalog-block-brief
  "One AGENT-authored fn for the catalog: ONE LINE — the first-arity
   callable signature only. Compact — the agent only needs to know it
   exists and how to call it; doc + body are one `:seon.fn` pull away
   (the header teaches the query)."
  [{:keys [sym arglists]}]
  (str "  " (first (callable-sigs sym arglists))))

(def ^:private fn-catalog-brief-max
  "Agent-authored ns groups with at most this many fns render one line
   per fn; larger groups collapse to a single count line. The DB carries
   everything either way — the catalog shows the index, the header
   teaches the query."
  8)

(defn- fn-catalog-summary-line
  "Single count line for a substrate or LARGE agent-ns group — the fns
   are all `:seon.fn` rows; the catalog header teaches how to list them,
   so the line is JUST `ns — N fns` (the old per-line 'query :seon.fn
   rows' boilerplate repeated ~30× and tripled the section)."
  [ns-name ns-fns]
  (str "  " ns-name " — " (count ns-fns)
       " fn" (when (not= 1 (count ns-fns)) "s")))

(defn functions-catalog-section
  "THIN index of every fn defined in the substrate, grouped by owning
   namespace — the sibling of `schema-catalog-section`. Substrate nses
   collapse to one count line each (exemplar nses cross-reference their
   full source in :exemplars above); agent-authored nses render one
   callable line per fn (count line when large). The agent's own ns
   source renders ONCE per prompt — in :namespace-context, not here.

   DERIVED from the `:seon.fn` corpus (one datalog join `:seon.fn` →
   `:seon.fn/ns` → `:seon.ns/name`); stores nothing. Define a fn and it
   appears here next render."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :as input}]
  (let [model  (or (:seon.ctx/model input) (context-model {:seon.db/db db}))
        agent-nses (:seon.ctx/agent-nses model)
        rows   (db/query
                 {:seon.db/db db
                  :seon.db/query
                  '[:find ?sym ?nm ?arglists
                    :where
                    [?f :seon.fn/sym ?sym]
                    [?f :seon.fn/ns ?ns]
                    [?ns :seon.ns/name ?nm]
                    [(get-else $ ?f :seon.fn/arglists "") ?arglists]]})
        fns    (map (fn [[sym nm arglists]]
                      {:sym sym :ns (name nm) :arglists arglists})
                    rows)
        groups (->> fns
                    (group-by :ns)
                    ;; Rule 1: *.internal is indexed, never rendered.
                    (remove (fn [[ns-name _]] (hidden-ns-name? ns-name)))
                    (sort-by first))]
    (if (seq fns)
      (str "<functions>\n"
           ";; Every fn defined across the WHOLE substrate is a :seon.fn row,\n"
           ";; indexed here by namespace. This is a COUNT INDEX — more exists\n"
           ";; than is shown. List any namespace's fns from the db, e.g.:\n"
           ";;   (seon.db/query {:seon.db/query\n"
           ";;     '[:find ?sym ?arglists :where [?f :seon.fn/ns ?n]\n"
           ";;       [?n :seon.ns/name :seon.db] [?f :seon.fn/sym ?sym]\n"
           ";;       [(get-else $ ?f :seon.fn/arglists \"\") ?arglists]]})\n"
           ";; and pull :seon.fn/source / :seon.fn/doc by [:seon.fn/sym \"…\"].\n"
           ";; Check here BEFORE writing a helper — it may already exist.\n\n"
           (str/join "\n"
             (for [[ns-name ns-fns] groups]
               (cond
                 ;; Full source already rendered in :exemplars — just
                 ;; the cross-reference.
                 (relevant-ns? ns-name)
                 (str (fn-catalog-summary-line ns-name ns-fns)
                      " (full source above)")

                 ;; Agent-authored / my.* code (the classifier's
                 ;; provenance + name rules): one callable line per fn
                 ;; while the group is small — this is the cross-agent
                 ;; reuse surface.
                 (and (or (contains? agent-nses ns-name)
                          (my-ns-name? ns-name))
                      (<= (count ns-fns) fn-catalog-brief-max))
                 (str "=== " ns-name " ===\n"
                      (str/join "\n"
                        (map fn-catalog-block-brief (sort-by :sym ns-fns))))

                 ;; Everything else (substrate plumbing, large agent
                 ;; groups): ONE count line — the body is one
                 ;; :seon.fn/source pull away (the header teaches it).
                 :else
                 (fn-catalog-summary-line ns-name ns-fns))))
           "\n</functions>")
      "")))

;; ============================================================
;; live-tile-section — "what your human currently sees" (live-tiles
;; U5). Kills the false belief a live T2 proof caught: a DeepSeek
;; agent replied "My tile is currently blank — I haven't set it up
;; yet" while its tile showed the substrate welcome. The agent sees
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
      ;; behind the same installed-schema gate `live-tile/user-name`
      ;; uses (load-bearing, not defensive fluff).
      (let [ent   (if (contains? (live-tile/installed-schema db)
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
   :seon.warn/all` is the whole-substrate overview. The RUNTIME checks
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

(defn- format-transcript-item
  "Render one transcript item — a `:seon.agent.message` as a REPL event
   (`user>`/`assistant>`/`agent-<id>>` line, labeled by from-ref kind)
   or a `:seon.eval` via `format-eval-row` (`> form\\n result`).
   Dispatch on which kind-keyed `:at` is present."
  [item own-id]
  (if (:seon.agent.message/at item)
    (format-message-row item own-id)
    (format-eval-row item)))

(defn transcript-section
  "The chronological TRANSCRIPT — the agent's messages and evals
   INTERLEAVED into a single oldest-first stream, so the agent reads one
   coherent REPL session (user input as `user>`/`assistant>` events,
   evals as `> form` + result) rather than two divorced blocks. Reads
   `:seon.agent/n` from the ctx-entity if present (caps EACH stream
   before the merge), else defaults to 50 messages + 50 evals.

   Pure render: messages via `seon.agent/messages` (direct agent-ref query,
   Change B 2026-06-09); evals via `seon.agent/evals` (turn-walk). Merges
   by `:at`, stores nothing. Each eval row is
   `cap-result`-bounded (`format-eval-row`) so one huge result can't
   dominate the transcript (context-SAFETY invariant).

   Budget eviction applies to EVAL rows ONLY — message rows are ALWAYS
   kept, in chronological position. Before this exemption a burst of
   ~16 worst-case eval rows after the user's last message pushed that
   message past the budget, and the agent correctly narrated 'the
   user's last message is missing from the visible transcript' (S-12
   KoQ turn Ckz-2606101827). The conversation is never sacrificed for
   eval bulk; each message is individually bounded by
   [[message-render-cap]] so the exemption can't blow the context."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] :as input}]
  (let [n     (or (:seon.agent/n (:seon.ctx/section input)) 50)
        msgs  (messages {:seon.agent/n n :seon.agent/id id})
        es    (evals    {:seon.agent/n n :seon.agent/id id})
        items (->> (concat msgs es)
                   (sort-by transcript-item-at))]
    (if (seq items)
      (let [rendered (mapv #(format-transcript-item % id) items)
            msg-at?  (mapv #(contains? % :seon.agent.message/at) items)
            ;; Chars the always-kept message rows consume up front.
            msg-chars (transduce
                        (keep-indexed
                          (fn [i s] (when (msg-at? i) (+ (count s) 2))))
                        + 0 rendered)
            ;; NEWEST-FIRST retention over the EVAL rows with whatever
            ;; budget the messages leave: walk from the end accumulating
            ;; rendered chars; keep the newest eval rows WHOLE (always
            ;; at least one), drop everything older.
            kept-eval (loop [i (dec (count rendered)) acc msg-chars
                             kept #{}]
                        (if (neg? i)
                          kept
                          (if (msg-at? i)
                            (recur (dec i) acc kept)
                            (let [acc' (+ acc (count (rendered i)) 2)]
                              (if (and (seq kept)
                                       (> acc' transcript-char-budget))
                                kept
                                (recur (dec i) acc' (conj kept i)))))))
            kept-idx (filterv #(or (msg-at? %) (kept-eval %))
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

(defn prompt-section
  "TERMINAL-style trailing prompt (unit #23 fix e, per the plan's
   REPL-PARITY CONTRACT prompt redesign): a per-turn STATUS BLOCK above,
   then a CLEAN REPL prompt as the very last line —

     ;; You are at a ClojureScript REPL — reply ONLY with forms + ;; comments.
     ;; ── turn 6 · 3 since-user (cap 20) · 2026-06-09T22:14:00.000Z ──
     my.domain.thing=>

   The status block carries the session turn count, the since-inbound
   count vs the agent's turns-cap, and the wall-clock timestamp (+ pod
   tz) — every per-turn-volatile byte lives HERE at the context tail so
   the static sections above stay a stable provider-cacheable prefix
   (context-audit 2026-06-09 §4). Turn-pressure nudges render inside
   this block when escalating (wrap up at halfway, FINAL WARNING three
   turns before `run-agentic-loop!` cuts the loop off). The final line
   is EXACTLY `<current-ns>=> ` — no trailing metadata; the agent
   completes the next REPL input. Always present (never blank)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (let [now      (.toISOString (js/Date.))
        ns       (current-ns {:seon.agent/id id})
        ;; current-ns returns a keyword (latest eval's :seon.eval/ns) or a
        ;; symbol (home-ns fallback) — render without the keyword colon,
        ;; like a real REPL prompt.
        ns-str   (if (keyword? ns) (name ns) (str ns))
        sess     (current-session id)
        n-turns  (count (:seon.agent.session/turns sess))
        since-u  (turns-since-inbound {:seon.agent/id id})
        cap      (turns-cap id)
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
    (str ";; You are at a ClojureScript REPL — reply ONLY with forms + ;; comments.\n"
         ";; ── turn " n-turns " · " since-u " since-user (cap " cap ") · "
         now " (pod tz: " (host-timezone) ") ──\n"
         pressure
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

(schema/register! :seon.render/assemble-response
  [:map
   [:seon.render/text            :string]
   [:seon.render/sections        :seon.render/sections]
   [:seon.render/section-texts   [:vector :seon.ctx/section-text]]
   [:seon.render/token-estimate  :int]])
(defn substrate-default-ctx
  "The default :seon.ctx section layout that ships with every fresh
   agent — ordered MOST-STATIC → MOST-DYNAMIC (prompt-cache friendly),
   per the context-render PRD (Phase 2) table:

     1. :system            — Seon identity + CLJS-in-Node + REPL contract
                             + the four standing behavioral teachings
                             (static; context-v4 V4-0 — the old
                             :instructions section DIED, system-wide
                             runtime instructions live in my.kb.system,
                             read by eval, never a section)
     2. :capabilities      — core API worked examples (static)
     3. :exemplars         — FULL source of the exemplar namespaces
                             (seon.agent.search, seon.agent.todo, my.kb +
                             children + test siblings), queried from
                             :seon.ns/source; byte-stable for the pod's
                             life (static — inside the cache prefix)
     4. :schema-catalog    — GLOBAL catalog of every entity KIND in the
                             system (cross-ns; what DATA exists), grouped by
                             namespace with attrs + instance counts;
                             semi-static (busts only on schema register)
     5. :functions-catalog — THIN per-ns count index of every fn defined in
                             the system (cross-ns; what CODE exists);
                             semi-static (busts when a fn is (re)defined)
     6. :live-tile         — what your human currently sees: the agent's
                             wired tile invoked against the turn's db, twin
                             text + wired identity (dynamic — present-tense
                             self-knowledge, like :warnings/:open-todos)
     7. :namespace-context — `render-namespace` of required nses + own ns
                             (mostly static; busts on ns edit)
     8. :warnings          — current cross-agent problems (failed/slow evals,
                             failing tests); reactive, vanishes when fixed (dynamic)
     9. :open-todos        — the CALLING agent's open work items
                             (seon.agent.todo/open-todos-section); derived from the
                             db, vanishes when the work is done (dynamic)
    10. :transcript        — messages + evals interleaved chronologically (dynamic)
    11. :prompt            — `my.agent.<id>=>  ; turn N` (always changing)

   :exemplars sits at 22, between :capabilities (20) and :schema-catalog
   (25): system + capabilities + exemplars are all fully byte-stable while
   the catalogs are only semi-static (fuzzy counts move on corpus growth) —
   static-before-semi-static maximizes the provider-cacheable prefix
   (context-focus-redesign §2). The two catalogs are the BROAD cross-ns
   view — schema-catalog is 'what kinds of data exist', functions-catalog
   is 'what code already exists' (so a later agent reuses an earlier one's
   work instead of re-deriving it). The per-ns `namespace-context` that
   follows is the DEEP current-ns view.

   Smallest priority first. `root-pull` is DELETED (was the
   `[*]`-everywhere amplifier that flooded context); `current-turn`/
   `current-session` fold into the prompt line."
  []
  [{:seon.ctx/name :system            :seon.ctx/priority 10
    :seon.render/ai 'seon.ctx/system-section}
   {:seon.ctx/name :capabilities      :seon.ctx/priority 20
    :seon.render/ai 'seon.ctx/capabilities-section}
   {:seon.ctx/name :exemplars         :seon.ctx/priority 22
    :seon.render/ai 'seon.ctx/exemplars-section}
   {:seon.ctx/name :schema-catalog    :seon.ctx/priority 25
    :seon.render/ai 'seon.ctx/schema-catalog-section}
   {:seon.ctx/name :functions-catalog :seon.ctx/priority 27
    :seon.render/ai 'seon.ctx/functions-catalog-section}
   {:seon.ctx/name :live-tile         :seon.ctx/priority 28
    :seon.render/ai 'seon.ctx/live-tile-section}
   {:seon.ctx/name :namespace-context :seon.ctx/priority 30
    :seon.render/ai 'seon.ctx/namespace-context-section}
   {:seon.ctx/name :warnings          :seon.ctx/priority 40
    :seon.render/ai 'seon.ctx/warnings-section}
   {:seon.ctx/name :open-todos        :seon.ctx/priority 45
    :seon.render/ai 'seon.agent.todo/open-todos-section}
   {:seon.ctx/name :transcript        :seon.ctx/priority 50
    :seon.render/ai 'seon.ctx/transcript-section}
   {:seon.ctx/name :prompt            :seon.ctx/priority 99
    :seon.render/ai 'seon.ctx/prompt-section}])

;; ============================================================
;; Composer — merge semantics + render guard + budget (the
;; agent-self-context spec, 2026-06-10):
;;
;;   sections = sort-by priority (substrate defaults ∪ agent's
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
;; sections truncate with a loud marker. Substrate sections are not
;; charged to it.
;; ============================================================

(def agent-section-char-budget
  "Total rendered-chars budget shared by the agent's OWN sections
   (everything in :seon.agent/ctx — strings and computed alike).
   Substrate default sections are not charged. Over budget, the
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
  "Substrate defaults ∪ the agent's own sections, ONE priority sort.
   Name collisions = override-by-name (the agent's entry wins — the
   deliberate escape hatch). Ties sort substrate-first, then by name,
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
   replacing the overflow with a loud marker; substrate sections pass
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

(defn assemble-context
  "Compose the LLM context — the ONE composer, called by BOTH the agent
   prompt path (`seon.agent/render-prompt`) and the inspector, so
   divergence is impossible.

   Sections = substrate defaults ([[substrate-default-ctx]]) MERGED
   with the agent's own `:seon.agent/ctx` section maps by one priority
   sort (override-by-name; merge-never-replace — substrate evolution
   always flows through, agent customization layers on top). The agent
   entity is pulled ONCE (sans the session log — the transcript section
   walks that separately; a bare `[*]` pull would inline every
   turn/eval component) and rides in the input map every section fn
   receives.

   Returns
     `{:seon.render/text \"…\"
       :seon.render/sections [<section-name> …]      ; render order
       :seon.render/section-texts [{:seon.ctx/name _
                                    :seon.render/text _} …]
       :seon.render/token-estimate <int>}`"
  {:malli/schema [:=> [:cat :seon.render/assemble-request]
                       :seon.render/assemble-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id] :as input}]
  (let [entity   (db/pull {:seon.db/db db
                           :seon.db/pull-pattern
                           '[:db/id :seon.agent/id :seon.agent/state
                             :seon.agent/turns-cap :seon.agent/completed-at
                             :seon.render/ai :seon.render/html
                             {:seon.agent/ctx [*]}]
                           :seon.db/ref [:seon.agent/id id]})
        model    (context-model {:seon.db/db db})
        sections (merge-sections (substrate-default-ctx)
                                 (agent-sections entity))
        base-in  (assoc input
                        :seon.agent/entity entity
                        :seon.ctx/model    model)
        rendered (->> sections
                      (map (fn [section]
                             (assoc section :seon.render/text
                                    (render-section base-in section))))
                      (remove (comp str/blank? :seon.render/text))
                      vec
                      apply-agent-budget)
        text     (str/join "\n\n" (map :seon.render/text rendered))]
    {:seon.render/text           text
     ;; :seon.render/sections is LAYOUT PROVENANCE — every merged
     ;; section name in render order, including ones whose fn rendered
     ;; blank this turn (a suppressed section is still part of the
     ;; layout). :seon.render/section-texts carries only the non-blank
     ;; contributions (what the inspector shows).
     :seon.render/sections       (mapv :seon.ctx/name sections)
     :seon.render/section-texts  (mapv #(select-keys % [:seon.ctx/name
                                                        :seon.render/text])
                                       rendered)
     :seon.render/token-estimate (quot (count text) 4)}))

;; ============================================================
;; Seeds — the :purpose launch directive + the tiny fn-shaped
;; copyable, transacted onto every agent at create! (NOT at resume —
;; existing agents keep theirs).
;; ============================================================

(def acquire-purpose-text
  "The :purpose seed when the agent is created WITHOUT a stated
   purpose — the placeholder teaches the mechanism by demanding its
   use."
  (str "Derive your purpose from your human's first messages, then "
       "update this section (add-section! :purpose) so you keep your "
       "direction."))

(defn purpose-text
  "The :purpose section text: the human's words when stated, else the
   acquire-your-purpose placeholder."
  [purpose]
  (if (and (string? purpose) (not (str/blank? purpose)))
    (str "Your human created you for: " purpose)
    acquire-purpose-text))

(defn own-sections-section
  "The tiny fn-shaped copyable seeded beside :purpose — renders the
   agent's OWN section list from its once-pulled entity. Its full
   source is small enough to read in the functions catalog; copy the
   shape for your own computed sections."
  {:malli/schema [:=> [:cat :map] :string]}
  [{entity :seon.agent/entity}]
  (let [secs (agent-sections entity)]
    (if (empty? secs)
      ""
      (str "Your own context sections (edit with seon.agent/add-section! / "
           "remove-section! / set-purpose!):\n"
           (str/join "\n"
                     (for [{nm :seon.ctx/name pri :seon.ctx/priority
                            slot :seon.render/ai} secs]
                       (str "  " pri " " nm
                            (if (string? slot) " (text)" (str " → " slot)))))))))

(defn seed-sections
  "The section maps seeded into `:seon.agent/ctx` at create!:
   the :purpose launch directive (priority 12 — after SOUL/system,
   BEFORE capabilities; purpose frames everything) and the
   :your-sections computed example (the fn-shaped copyable)."
  [purpose]
  [{:seon.ctx/name     :purpose
    :seon.ctx/priority 12
    :seon.render/ai    (purpose-text purpose)}
   {:seon.ctx/name     :your-sections
    :seon.ctx/priority 13
    :seon.render/ai    'seon.ctx/own-sections-section}])
