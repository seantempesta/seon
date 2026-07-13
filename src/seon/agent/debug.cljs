(ns seon.agent.debug
  "Agent self-introspection: 'what am I seeing right now?'

   Two functions:
     - `ctx-preview` — the FULL prompt the agent would receive on its
       next render: the HARDCODED system block FIRST (read via the SAME
       fn the adapters call, `seon.ai/effective-system-prompt` — the
       system-specific mechanics, NOT the soul/any file), then the
       assembled AI-context via `seon.agent.ctx/render-context` — the SINGLE
       producer the loop's prompt path (`seon.agent.turn/render-prompt`)
       also routes through, over the SAME unfiltered `@*conn*`. The
       `:seon.render/text` is byte-identical to what the LLM receives
       (system message + context), with an explicit boundary between
       them. Per-block texts (left pane) lead with the system block;
       the per-block html twins (right pane) mirror the context
       blocks only (which now include the SOUL.md / AGENTS.md
       file-blocks). System block + context derive from the same
       sources the real call uses, so divergence is impossible.
     - `handlers` — the live handler registry visible to the agent
       (core + per-agent).
     - `turn` / `turn-diff` — turn replay: reconstruct any persisted
       turn from its `:seon.agent.turn/rendered-as-of` basis-t + prompt
       and reply blobs; diff two turns (tokens + basis-t delta).
     - `errors` / `error` / `repro` — error triage over the persisted
       `seon.error/record!` datoms: compact recent list → one full
       envelope with the turn/agent joins → the work-backwards bundle
       (as-of db frozen at the failure + a ready-to-eval repro
       expression).

   All map-in, map-out. Defaults `:seon.agent/id` to
   `(seon.db/current-agent-id)` so REPL calls from inside an agent
   scope work with no argument."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [my.blob :as blob]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.agent.turn]
    [seon.db :as db]
    [seon.error]
    [seon.schema :as schema]
    [seon.store.wire :as store.wire]))

;; Shared success/error envelope keys — registered ONCE up front; every
;; response schema in this ns references them (errors are values: an
;; unknown id / missing scope is `{::ok? false ::error <guiding>}`).
(schema/register! ::ok?   :boolean)
(schema/register! ::error :string)

(schema/register! :seon.agent.debug/request
  [:map [:seon.agent/id {:optional true} :seon.agent/id]])

;; One resolved context block, carrying either or both rendered formats.
(schema/register! :seon.agent.debug/rendered-context-block
  [:map
   [:seon.agent.ctx/name :seon.agent.ctx/name]
   [:seon.agent.ctx/priority :seon.agent.ctx/priority]
   [:seon.render/text {:optional true} :string]
   [:seon.render/hiccup {:optional true} :seon.render.canvas/hiccup]
   [:seon.render/token-estimate {:optional true} :int]])

;; `::ok?` required, render keys optional — the same envelope shape as
;; `::turn-response` below: `::ok? false` + a guiding `::error` when no
;; agent scope resolves (errors are values, never a throw).
(schema/register! :seon.agent.debug/ctx-response
  [:map
   [::ok? ::ok?]
   [::error {:optional true} ::error]
   [:seon.render/text {:optional true} :string]
   [:seon.agent.ctx/rendered-blocks {:optional true}
    [:vector :seon.agent.debug/rendered-context-block]]
   [:seon.render/token-estimate {:optional true} :int]])

(defn- ctx-preview*
  "The resolved-id body of [[ctx-preview]] — renders the full prompt."
  [id]
  (let [;; THE SAME db the prompt path renders against — the live cluster
        ;; conn, UNFILTERED. The loop renders the prompt over `@*conn*`
        ;; ([[seon.agent.ctx/render-context]] / `render-prompt`); the web UI
        ;; must use the SAME db value or it would not be byte-identical (and
        ;; the old per-agent `d/filter` actively DROPPED inbound peer-message
        ;; content whose datom lived in the peer's tx — the web UI lied).
        db  @db/*conn*
        ctx {:seon.agent/id id :seon.db/db db}
        ;; Render the ordered AI blocks ONCE, assemble the context from those
        ;; same strings, and add HTML twins only for the current eager debug
        ;; consumer. The lazy unit cutover will request the twins separately;
        ;; either way no AI renderer is invoked twice for token accounting.
        {:seon.render/keys [text]
         blocks :seon.agent.ctx/rendered-blocks}
        (ctx/rendered-context ctx #{:ai :html})
        ;; Block 1 — the resolved system message, via the EXACT fn the
        ;; adapters call (no re-implementation, no drift). No override is
        ;; passed, so this returns the cluster's `:seon.config/system-text`
        ;; datom when seeded, else the shipped default — the normal call's
        ;; system message.
        system        (ai/effective-system-prompt {})
        ;; The FULL prompt = system + boundary + context, via the SAME fn
        ;; the adapters call so the two debug surfaces can't drift.
        full-text     (ai/debug-full-prompt {:seon.ai/ctx text})]
    {::ok?                        true
     :seon.render/text            full-text
     :seon.agent.ctx/rendered-blocks
     (into [{:seon.agent.ctx/name :system
             :seon.agent.ctx/priority 0
             :seon.render/text system
             :seon.render/token-estimate (tokens/estimate system)}]
           blocks)
     ;; Estimate over the WHOLE prompt — same units as the composer
     ;; (~4 chars/token, via seon.ai.tokens), so the count grows by the
     ;; system-block length.
     :seon.render/token-estimate  (tokens/estimate full-text)}))

(defn ctx-preview
  "Return the FULL prompt the agent would see on its next render.

   The EXACT bytes the LLM receives: the HARDCODED system block FIRST, then
   the assembled context. The system block is read via the SAME fn the
   adapters call (`seon.ai/effective-system-prompt` — the system-specific
   seon mechanics, NOT the soul/any file; explicit-override logic), so
   the debug text is byte-identical to the real system message; the
   context comes from `seon.agent.ctx/context-root` → render (and now CARRIES
   the SOUL.md / AGENTS.md file-blocks). Divergence is impossible — both
   surfaces derive from the same sources the real call uses.
   `:seon.render/text` = system + boundary + context.
   `:seon.agent.ctx/rendered-blocks` leads with the `:system` block, then the
   exact ordered context blocks with whichever render formats they declare.
   `:seon.render/token-estimate` counts the WHOLE
   prompt (system included). Renders against the live `@*conn*` — the SAME
   unfiltered db value the loop renders the prompt over — so the two are
   byte-identical (no per-agent `d/filter` divergence). Errors are
   values: no id and no agent scope returns `::ok? false` plus a
   guiding `::error` — nothing throws."
  {:malli/schema [:=> [:cat :seon.agent.debug/request] :seon.agent.debug/ctx-response]}
  [{:seon.agent/keys [id]}]
  (if-let [id (or id (db/current-agent-id))]
    (ctx-preview* id)
    {::ok?   false
     ::error (str "seon.agent.debug/ctx-preview: no agent-id — pass "
                  ":seon.agent/id or call inside (seon.db/with-agent id ...).")}))

;;; ============================================================
;;; Turn reconstruction — read one persisted turn from its frozen coordinate,
;;; prompt blob, and reply blob. Arbitrary eval effects are never replayed.
;;; ============================================================

(schema/register! ::prompt        :string)
(schema/register! ::reply         :string)
(schema/register! ::prompt-tokens :int)
(schema/register! ::reply-tokens  :int)

(schema/register! ::turn-request
  [:map [:seon.agent.turn/id :seon.agent.turn/id]])

(schema/register! ::turn-response
  [:map
   [::ok? ::ok?]
   [:seon.agent.turn/id :seon.agent.turn/id]
   [::error                          {:optional true} ::error]
   [:seon.agent.turn/status         {:optional true} :seon.agent.turn/status]
   [:seon.agent.turn/at             {:optional true} :seon.agent.turn/at]
   [:seon.agent.turn/rendered-as-of {:optional true} :seon.agent.turn/rendered-as-of]
   [:seon.agent.turn/error          {:optional true} :seon.agent.turn/error]
   [::prompt        {:optional true} ::prompt]
   [::prompt-tokens {:optional true} ::prompt-tokens]
   [::reply         {:optional true} ::reply]
   [::reply-tokens  {:optional true} ::reply-tokens]])

(defn- turn-eid
  "The entity id stored under a turn id, or nil — query, never a
   lookup-ref (safe on a store where no turn has landed yet)."
  [turn-id]
  (db/query '[:find ?e . :in $ ?id :where [?e :seon.agent.turn/id ?id]]
            turn-id))

(defn- blob-text
  "Read a captured blob ref's content back — `{::k text ::k-tokens n}`
   under the given keys, `{}` when the turn has no such ref, or an
   `::error` note naming the blob when the ref exists but won't read."
  [pulled ref-attr text-key tokens-key]
  (if-let [hash (get-in pulled [ref-attr :my.blob/hash])]
    (let [{:my.blob/keys [ok? content tokens error]}
          (blob/get {:my.blob/hash hash})]
      (if ok?
        {text-key content tokens-key tokens}
        {::error (str (name ref-attr) " blob " hash " unreadable: " error)}))
    {}))

(defn turn
  "Reconstruct one persisted turn: basis-t, verbatim prompt, raw reply.

   Map-in/map-out — `{:seon.agent.turn/id id}` returns the turn's
   `:seon.agent.turn/rendered-as-of` (re-derive its whole structured
   context with `(db/as-of conn t)`), the VERBATIM prompt and raw reply
   read back from their blobs (with token estimates), and the turn's stored
   error when present. The durable turn/eval graph is the record; this does not
   claim that arbitrary database or external effects can be replayed. An
   unknown id or unreadable blob returns `::ok? false` plus a guiding
   `::error`; nothing throws."
  {:malli/schema [:=> [:cat ::turn-request] ::turn-response]}
  [{turn-id :seon.agent.turn/id}]
  (if-let [eid (turn-eid turn-id)]
    (let [t (db/pull {:seon.db/pull-pattern
                      [:seon.agent.turn/id :seon.agent.turn/at
                       :seon.agent.turn/status :seon.agent.turn/rendered-as-of
                       :seon.agent.turn/error
                       {:seon.agent.turn/prompt-blob [:my.blob/hash]}
                       {:seon.agent.turn/reply-blob  [:my.blob/hash]}]
                      :seon.db/ref eid})
          p (blob-text t :seon.agent.turn/prompt-blob ::prompt ::prompt-tokens)
          r (blob-text t :seon.agent.turn/reply-blob  ::reply  ::reply-tokens)
          errs (keep ::error [p r])]
      (cond-> (merge (select-keys t [:seon.agent.turn/id :seon.agent.turn/at
                                     :seon.agent.turn/status
                                     :seon.agent.turn/rendered-as-of
                                     :seon.agent.turn/error])
                     (dissoc p ::error)
                     (dissoc r ::error)
                     {::ok? (empty? errs)})
        (seq errs) (assoc ::error (str/join "; " errs))))
    {::ok? false
     :seon.agent.turn/id turn-id
     ::error (str "no turn stored under " (pr-str turn-id))}))

;;; turn-diff — what changed between two turns, as a summary an agent can
;;; budget on: basis-t delta (how many txs advanced the db between the
;;; two renders) + a token/line summary of the prompt drift + both replies.

(schema/register! ::from :seon.agent.turn/id)
(schema/register! ::to   :seon.agent.turn/id)

(schema/register! ::turn-diff-request
  [:map [::from ::from] [::to ::to]])

(schema/register! ::basis-t-delta        :int)
(schema/register! ::prompt-token-delta   :int)
(schema/register! ::prompt-lines-added   :int)
(schema/register! ::prompt-lines-removed :int)

(schema/register! ::turn-diff-response
  [:map
   [::ok? ::ok?]
   [::error {:optional true} ::error]
   [::from-turn {:optional true} ::turn-response]
   [::to-turn   {:optional true} ::turn-response]
   [::basis-t-delta        {:optional true} ::basis-t-delta]
   [::prompt-token-delta   {:optional true} ::prompt-token-delta]
   [::prompt-lines-added   {:optional true} ::prompt-lines-added]
   [::prompt-lines-removed {:optional true} ::prompt-lines-removed]])

(defn- line-delta
  "Multiset line diff `from` → `to`: `[added removed]` line counts."
  [from to]
  (let [f (frequencies (str/split-lines (or from "")))
        t (frequencies (str/split-lines (or to "")))
        ks (into (set (keys f)) (keys t))]
    (reduce (fn [[a r] k]
              (let [d (- (get t k 0) (get f k 0))]
                [(+ a (max 0 d)) (+ r (max 0 (- d)))]))
            [0 0] ks)))

(defn turn-diff
  "What changed between two persisted turns — a budgetable summary.

   Map-in/map-out — `{::from id ::to id}` returns both [[turn]] bundles
   plus: `::basis-t-delta` (txs the db advanced between the two
   renders — `rendered-as-of` distance), `::prompt-token-delta` (tokens,
   the ONE estimator), and a multiset line summary of the prompt drift
   (`::prompt-lines-added` / `-removed` — cache-stability instrument:
   frozen bytes that moved show up here). `::ok? false` with a guiding
   `::error` when either turn is missing; partial capture degrades to
   the fields both sides carry."
  {:malli/schema [:=> [:cat ::turn-diff-request] ::turn-diff-response]}
  [{::keys [from to]}]
  (let [ft (turn {:seon.agent.turn/id from})
        tt (turn {:seon.agent.turn/id to})]
    (if (or (nil? (turn-eid from)) (nil? (turn-eid to)))
      {::ok? false
       ::error (str/join "; " (keep ::error [ft tt]))}
      (let [[added removed] (line-delta (::prompt ft) (::prompt tt))
            f-as-of (:seon.agent.turn/rendered-as-of ft)
            t-as-of (:seon.agent.turn/rendered-as-of tt)]
        (cond-> {::ok? (and (::ok? ft) (::ok? tt))
                 ::from-turn ft
                 ::to-turn   tt
                 ::prompt-lines-added   added
                 ::prompt-lines-removed removed}
          (and f-as-of t-as-of)
          (assoc ::basis-t-delta (- t-as-of f-as-of))
          (and (::prompt-tokens ft) (::prompt-tokens tt))
          (assoc ::prompt-token-delta (- (::prompt-tokens tt)
                                         (::prompt-tokens ft)))
          (seq (keep ::error [ft tt]))
          (assoc ::error (str/join "; " (keep ::error [ft tt]))))))))

;;; ============================================================
;;; Error triage — the persisted `seon.error/record!` datoms, three
;;; altitudes: `errors` (compact recent list) → `error` (one full
;;; envelope + the turn/agent joins) → `repro` (the work-backwards
;;; bundle: the as-of db value frozen at the failure + a ready-to-eval
;;; reproduction expression). Errors are values throughout — an unknown
;;; eid is a guiding map, nothing throws.
;;; ============================================================

(schema/register! ::eid   :int)
(schema/register! ::limit [:int {:min 1 :max 200}])
;; "fn (file:line)" of the failure's TOP stack frame — compact display.
(schema/register! ::top-frame :string)

(schema/register! ::errors-request
  [:map
   [:seon.error/fault {:optional true} :seon.error/fault]
   [::limit           {:optional true} ::limit]])

(schema/register! ::error-row
  [:map
   [::eid                ::eid]
   [:seon.error/fault    :seon.error/fault]
   [:seon.error/message  :seon.error/message]
   [:seon.error/at       {:optional true} :seon.error/at]
   [::top-frame          {:optional true} ::top-frame]
   [:seon.agent/id       {:optional true} :seon.agent/id]])

(schema/register! ::errors-response
  [:map [::errors [:vector ::error-row]]])

(def ^:private default-errors-limit
  "Row cap for [[errors]] when the caller names none — recent, compact."
  20)

(defn- tx-agent-id
  "The agent user whose transaction wrote error `eid`."
  [db eid]
  (db/query '[:find ?aid . :in $ ?e
              :where [?e :seon.error/fault _ ?tx]
                     [?tx :seon.db/user ?author]
                     [?author :seon.agent/id ?aid]]
            db eid))

(defn- top-frame-str
  "\"fn (file:line)\" for the index-0 frame of pulled `frames`, or nil."
  [frames]
  (when-let [{f :seon.error.frame/fn file :seon.error.frame/file
              line :seon.error.frame/line}
             (first (sort-by :seon.error.frame/index frames))]
    (let [base (when file (last (str/split file #"/")))]
      (when (or f base)
        (str (or f "?")
             (when base (str " (" base (when line (str ":" line)) ")")))))))

(def ^:private error-pull-pattern
  [:seon.error/fault :seon.error/message :seon.error/at
   :seon.error/stack :seon.error/args-edn :seon.error/data-edn
   {:seon.error/frames [:seon.error.frame/index :seon.error.frame/fn
                        :seon.error.frame/file :seon.error.frame/line
                        :seon.error.frame/column]}])

(defn- pull-error
  "The persisted error entity under `eid`, or nil when it isn't one."
  [db eid]
  (let [e (db/pull {:seon.db/db db
                    :seon.db/pull-pattern error-pull-pattern
                    :seon.db/ref eid})]
    (when (:seon.error/fault e) e)))

(defn errors
  "List recent persisted errors, newest first — compact triage rows.

   Map-in/map-out (0-arity = defaults): optional `:seon.error/fault`
   filter (`:agent` | `:core`) and `::limit` (default 20). Each row:
   the error's entity id (feed it to [[error]] / [[repro]]), fault,
   `:seon.error/at` (basis-t at failure), the DEEPEST-cause short
   message, the top stack frame, and the recording agent's id when the
   tx carried one. Token-bounded by construction — stored messages are
   already clipped; rows clip further for the list."
  {:malli/schema [:function
                  [:=> [:cat] ::errors-response]
                  [:=> [:cat ::errors-request] ::errors-response]]}
  ([] (errors {}))
  ([{fault :seon.error/fault limit ::limit}]
   (let [db   @db/*conn*
         eids (->> (db/query '[:find ?e ?f
                               :where [?e :seon.error/fault ?f]]
                             db)
                   (filter (fn [[_ f]] (or (nil? fault) (= fault f))))
                   (map first)
                   (sort >)                     ; eids are monotonic → newest first
                   (take (or limit default-errors-limit)))]
     {::errors
      (mapv (fn [eid]
              (let [{:seon.error/keys [fault message at frames]} (pull-error db eid)
                    top (top-frame-str frames)
                    aid (tx-agent-id db eid)]
                (cond-> {::eid eid
                         :seon.error/fault fault
                         :seon.error/message (tokens/clip-str message 25)}
                  at  (assoc :seon.error/at at)
                  top (assoc ::top-frame top)
                  aid (assoc :seon.agent/id aid))))
            eids)})))

(schema/register! ::error-request [:map [::eid ::eid]])

;; Pulled frame rows (sorted by index) — same leaf shape as the stored
;; component entities, re-used from seon.error's registration.
(schema/register! ::frames [:vector :seon.error/frame])

(schema/register! ::error-response
  [:map
   [::ok?  ::ok?]
   [::eid  ::eid]
   [::error {:optional true} ::error]
   [:seon.error/fault    {:optional true} :seon.error/fault]
   [:seon.error/message  {:optional true} :seon.error/message]
   [:seon.error/at       {:optional true} :seon.error/at]
   [:seon.error/stack    {:optional true} :seon.error/stack]
   [:seon.error/args-edn {:optional true} :seon.error/args-edn]
   [:seon.error/data-edn {:optional true} :seon.error/data-edn]
   [::frames             {:optional true} ::frames]
   [:seon.agent/id       {:optional true} :seon.agent/id]
   [::turn-eid           {:optional true} ::eid]
   [:seon.agent.turn/id  {:optional true} :seon.agent.turn/id]
   [:seon.agent.turn/rendered-as-of
    {:optional true} :seon.agent.turn/rendered-as-of]])

(defn- turn-by-id
  "`[turn-eid turn-id rendered-as-of|nil]` for a turn id, or nil."
  [db tid]
  (when tid
    (when-let [te (db/query '[:find ?t . :in $ ?tid
                              :where [?t :seon.agent.turn/id ?tid]]
                            db tid)]
      [te tid (:seon.agent.turn/rendered-as-of
                (db/pull {:seon.db/db db
                          :seon.db/pull-pattern [:seon.agent.turn/rendered-as-of]
                          :seon.db/ref te}))])))

(defn- turn-active-at
  "The agent's turn ACTIVE at basis-t `at` — greatest rendered-as-of ≤ at.

   Returns `[turn-eid turn-id rendered-as-of]` or nil. The join is the
   turns' `rendered-as-of` window (agent → runs → turns); this covers
   errors recorded outside a turn-scoped tx (listeners, wrappers)."
  [db aid at]
  (when (and aid at)
    (->> (db/query '[:find ?t ?tid ?as
                     :in $ ?aid ?at
                     :where
                     [?a :seon.agent/id ?aid]
                     [?r :seon.agent.run/agent ?a]
                     [?t :seon.agent.turn/run ?r]
                     [?t :seon.agent.turn/id ?tid]
                     [?t :seon.agent.turn/rendered-as-of ?as]
                     [(<= ?as ?at)]]
                   db aid at)
         (sort-by #(nth % 2) >)
         first)))

(defn- error-turn
  "The turn active for agent `aid` at the error's persisted coordinate."
  [db aid at]
  (turn-active-at db aid at))

(defn error
  "Full detail for one persisted error: envelope + turn/agent joins.

   Map-in/map-out — `{::eid eid}` (from [[errors]]) returns the whole
   persisted projection (message, fault, `at`, frames table sorted by
   index, args-edn, data-edn, stack) plus the JOINS: the recording
   agent's id and the turn active at that basis-t — `::turn-eid` plus
   `:seon.agent.turn/id` so [[turn]] composes.
   An unknown eid returns `::ok? false` with a guiding `::error`."
  {:malli/schema [:=> [:cat ::error-request] ::error-response]}
  [{eid ::eid}]
  (let [db @db/*conn*]
    (if-let [e (pull-error db eid)]
      (let [aid (tx-agent-id db eid)
            [teid tid t-as-of] (error-turn db aid (:seon.error/at e))]
        (cond-> (merge {::ok? true ::eid eid}
                       (select-keys e [:seon.error/fault :seon.error/message
                                       :seon.error/at :seon.error/stack
                                       :seon.error/args-edn :seon.error/data-edn]))
          (seq (:seon.error/frames e))
          (assoc ::frames (->> (:seon.error/frames e)
                               (sort-by :seon.error.frame/index)
                               (mapv #(dissoc % :db/id))))
          aid     (assoc :seon.agent/id aid)
          teid    (assoc ::turn-eid teid :seon.agent.turn/id tid)
          t-as-of (assoc :seon.agent.turn/rendered-as-of t-as-of)))
      {::ok? false ::eid eid
       ::error (str "no persisted error under eid " eid
                    " — list them: (seon.agent.debug/errors)")})))

(schema/register! ::fn-sym     :symbol)
(schema/register! ::repro-expr :string)
(schema/register! ::note       :string)
;; The exact supervisor command that boots this error's database snapshot as a live,
;; writable, disposable cluster: `bin/seon cluster fork <cluster> <at>`.
;; `:seon.error/at` is the basis-t at the CATCH site — the db the failing
;; code SAW — so the fork holds everything up to the failure but NOT the
;; error datom itself (recorded in a later tx).
(schema/register! ::fork-hint  :string)

(schema/register! ::repro-request [:map [::eid ::eid]])

(schema/register! ::repro-response
  [:map
   [::ok?  ::ok?]
   [::eid  ::eid]
   [::error {:optional true} ::error]
   ;; The LIVE as-of db VALUE frozen at :seon.error/at — REPL use only.
   ;; NEVER pr-str it into agent context (it prints the whole index);
   ;; render its basis-t (:seon.error/at) instead.
   [:seon.db/db          {:optional true} :seon.db/db]
   [:seon.error/at       {:optional true} :seon.error/at]
   [::fn-sym             {:optional true} ::fn-sym]
   [:seon.error/args-edn {:optional true} :seon.error/args-edn]
   [::turn-eid           {:optional true} ::eid]
   [:seon.agent.turn/id  {:optional true} :seon.agent.turn/id]
   [:seon.agent.turn/rendered-as-of
    {:optional true} :seon.agent.turn/rendered-as-of]
   [::repro-expr         {:optional true} ::repro-expr]
   [::fork-hint          {:optional true} ::fork-hint]
   [::note               {:optional true} ::note]])

(defn- fn-sym-from-data-edn
  "The `:seon.error.malli/fn-sym` embedded in a stored data-edn string."
  [data-edn]
  (when (string? data-edn)
    (when-let [[_ s] (re-find #":seon\.error\.malli/fn-sym\s+([^\s,}\]\)\"]+)"
                              data-edn)]
      (symbol s))))

(defn- readable-args-edn
  "`args-edn` when it read-strings back to the args VECTOR, else nil.

   A stored args-edn can be token-clipped mid-form; a bundle must never
   hand back an expression built on unreadable args."
  [args-edn]
  (when (string? args-edn)
    (try (when (vector? (reader/read-string args-edn)) args-edn)
         (catch :default _ nil))))

(defn- repro-expr-str
  "The ready-to-eval reproduction expression for what's ACTUALLY stored."
  [at fn-sym args-edn]
  (if (and fn-sym args-edn)
    (str "(let [db (seon.db/as-of " at ")]\n"
         "  (apply (resolve '" fn-sym ") (cljs.reader/read-string "
         (pr-str args-edn) ")))")
    (str "(seon.db/as-of " at ")")))

(defn repro
  "The work-backwards bundle for one persisted error — freeze + re-run.

   Map-in/map-out — `{::eid eid}` returns `:seon.db/db` (the LIVE as-of
   db VALUE frozen at `:seon.error/at` — REPL material; never print it,
   render the basis-t), the failing `::fn-sym` + `:seon.error/args-edn`
   when the malli envelope captured them (a `::note` says so honestly
   when absent — nothing is fabricated), the linked turn
   (`::turn-eid` + `rendered-as-of`, composes with [[turn]]), and
   `::repro-expr` — a ready-to-eval expression string built from what's
   actually stored. `::fork-hint` is the supervisor command that boots
   this cluster as a live writable cluster (`bin/seon cluster fork
   <cluster> <at>`) — `at` is the basis-t the failing code SAW, so the
   fork holds everything up to the failure but not this error datom
   itself. `::ok? false` + guiding `::error` for an unknown
   eid or an error persisted before a conn was live (no `at`)."
  {:malli/schema [:=> [:cat ::repro-request] ::repro-response]}
  [{eid ::eid}]
  (let [db @db/*conn*]
    (if-let [e (pull-error db eid)]
      (let [{:seon.error/keys [at args-edn data-edn]} e
            aid      (tx-agent-id db eid)
            fn-sym   (fn-sym-from-data-edn data-edn)
            args-edn (readable-args-edn args-edn)
            [teid tid t-as-of] (error-turn db aid at)]
        (if-not at
          {::ok? false ::eid eid
           ::error (str "error " eid " has no :seon.error/at (recorded before "
                        "a conn was live) — no db value to freeze; read the "
                        "envelope via (seon.agent.debug/error {::eid " eid "})")}
          (cond-> {::ok? true ::eid eid
                   :seon.db/db (db/as-of db at)
                   :seon.error/at at
                   ::repro-expr (repro-expr-str at fn-sym args-edn)
                   ::fork-hint (str "bin/seon cluster fork "
                                    store.wire/cluster-name " " at)}
            fn-sym   (assoc ::fn-sym fn-sym)
            args-edn (assoc :seon.error/args-edn args-edn)
            teid     (assoc ::turn-eid teid :seon.agent.turn/id tid)
            t-as-of  (assoc :seon.agent.turn/rendered-as-of t-as-of)
            (not (and fn-sym args-edn))
            (assoc ::note (str "no captured fn/args on this error (non-malli "
                               "path or clipped args) — re-invocation is not "
                               "possible from the datom; work from the frozen "
                               "db + the linked turn's eval forms "
                               "(seon.agent.debug/turn)")))))
      {::ok? false ::eid eid
       ::error (str "no persisted error under eid " eid
                    " — list them: (seon.agent.debug/errors)")})))
