(ns seon.agent.debug
  "Agent self-introspection: 'what am I seeing right now?'

   Two functions:
     - `ctx-preview` — the FULL prompt the agent would receive on its
       next render: the database-value-pinned system block first, then the
       assembled AI context from the same compiled child result consumed by
       `seon.agent.turn/render-prompt`. The
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
       turn from its rendered transaction ref + prompt
       and reply blobs; diff two turns.
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
    [seon.agent.turn :as turn]
    [seon.db :as db]
    [seon.schema :as schema]))

;; Shared success/error envelope keys — registered ONCE up front; every
;; response schema in this ns references them (errors are values: an
;; unknown id / missing scope is `{::ok? false ::error <guiding>}`).
(schema/register! ::ok?   :boolean)
(schema/register! ::error :string)

(schema/register! :seon.agent.debug/request
  [:map
   [:seon.agent/id {:optional true} :seon.agent/id]
   [:seon.db/db {:optional true} :seon.db/db]
   [:seon.render/formats {:optional true}
    [:enum #{:ai} #{:ai :html}]]])

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

(schema/register! :seon.agent.debug/ctx-result
  [:or
   :seon.agent.debug/ctx-response
   [:map
    [:seon.error/message :string]
    [:seon.error/kind :keyword]
    [:seon.error/data {:optional true} :map]]])

(defn- ctx-preview*
  "Format one accepted compiled prompt result for operator inspection."
  [{text :seon.render/text
    system :seon.ai/system-prompt
    blocks :seon.agent.ctx/rendered-blocks}]
  (let [full-text (ai/debug-full-prompt {:seon.ai/ctx text
                                         :seon.ai/system-prompt system})]
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

(defn ^:async ctx-preview
  "Return the FULL prompt the agent would see on its next render.

   The exact bytes the LLM receives: the frozen system block first, then the
   assembled context. Both come from the same compiled child result the real
   turn consumes, so the debug adapter has no local prompt renderer or config
   fallback.
   `:seon.render/text` = system + boundary + context.
   `:seon.agent.ctx/rendered-blocks` leads with the `:system` block, then the
   exact ordered context blocks with whichever render formats they declare.
   `:seon.render/token-estimate` counts the WHOLE
   prompt (system included). An explicit immutable `:seon.db/db` is used
   as-is; omission uses the session's cached latest value. Errors are
   values: no id and no agent scope returns `::ok? false` plus a
   guiding `::error` — nothing throws."
  {:malli/schema [:=> [:cat :seon.agent.debug/request] :seon.agent.debug/ctx-result]}
  [{:seon.agent/keys [id]
    database :seon.db/db}]
  (if-let [id (or id (db/current-agent-id))]
    (let [database (or database (await (db/db)))
          rendered (await (turn/render-prompt id database))]
      (if (:seon.error/message rendered)
        rendered
        (ctx-preview* rendered)))
    {::ok?   false
     ::error (str "seon.agent.debug/ctx-preview: no agent-id — pass "
                  ":seon.agent/id or call inside (seon.db/with-agent id ...).")}))

;;; ============================================================
;;; Turn reconstruction — read one persisted turn from its rendered transaction,
;;; prompt blob, and reply blob. Arbitrary eval effects are never replayed.
;;; ============================================================

(schema/register! ::prompt        :string)
(schema/register! ::reply         :string)
(schema/register! ::prompt-tokens :int)
(schema/register! ::reply-tokens  :int)

(schema/register! ::turn-request
  [:map
   [:seon.agent.turn/id :seon.agent.turn/id]
   [:seon.db/db {:optional true} :seon.db/db]])

(schema/register! ::turn-response
  [:map
   [::ok? ::ok?]
   [:seon.agent.turn/id :seon.agent.turn/id]
   [::error                          {:optional true} ::error]
   [:seon.agent.turn/status         {:optional true} :seon.agent.turn/status]
   [:seon.agent.turn/at             {:optional true} :seon.agent.turn/at]
   [:seon.agent.turn/rendered-tx    {:optional true} :seon.agent.turn/rendered-tx]
   [:seon.agent.turn/error          {:optional true} :seon.agent.turn/error]
   [::prompt        {:optional true} ::prompt]
   [::prompt-tokens {:optional true} ::prompt-tokens]
   [::reply         {:optional true} ::reply]
   [::reply-tokens  {:optional true} ::reply-tokens]])

(defn- ^:async turn-eid
  "The entity id stored under a turn id, or nil — query, never a
   lookup-ref (safe on a store where no turn has landed yet)."
  [database turn-id]
  (await
   (db/query {:seon.db/db database
              :seon.db/query
              '[:find ?e . :in $ ?id :where [?e :seon.agent.turn/id ?id]]
              :seon.db/args [turn-id]})))

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

(defn ^:async turn
  "Reconstruct one persisted turn and its captured model bytes.

   Map-in/map-out — `{:seon.agent.turn/id id}` returns the turn's
   rendered transaction ref, the VERBATIM prompt and raw reply
   read back from their blobs (with token estimates), and the turn's stored
   error when present. The durable turn/eval graph is the record; this does not
   claim that arbitrary database or external effects can be replayed. An
   unknown id or unreadable blob returns `::ok? false` plus a guiding
   `::error`; nothing throws."
  {:malli/schema [:=> [:cat ::turn-request] ::turn-response]}
  [{turn-id :seon.agent.turn/id database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if-let [eid (await (turn-eid database turn-id))]
      (let [t (await (db/pull {:seon.db/db database
                      :seon.db/pull-pattern
                      [:seon.agent.turn/id :seon.agent.turn/at
                       :seon.agent.turn/status
                       :seon.agent.turn/rendered-tx
                       :seon.agent.turn/error
                       {:seon.agent.turn/prompt-blob [:my.blob/hash]}
                       {:seon.agent.turn/reply-blob  [:my.blob/hash]}]
                      :seon.db/ref eid}))
          p (blob-text t :seon.agent.turn/prompt-blob ::prompt ::prompt-tokens)
          r (blob-text t :seon.agent.turn/reply-blob  ::reply  ::reply-tokens)
          errs (vec (keep ::error [p r]))]
      (cond-> (merge (select-keys t [:seon.agent.turn/id :seon.agent.turn/at
                                     :seon.agent.turn/status
                                     :seon.agent.turn/rendered-tx
                                     :seon.agent.turn/error])
                     (dissoc p ::error)
                     (dissoc r ::error)
                     {::ok? (empty? errs)})
        (seq errs) (assoc ::error (str/join "; " errs))))
      {::ok? false
       :seon.agent.turn/id turn-id
       ::error (str "no turn stored under " (pr-str turn-id))})))

;;; turn-diff — what changed between two turns, as a summary an agent can
;;; budget on: a lineage-safe t delta when both cuts share one containing
;;; commit, plus a token/line summary of prompt drift + both replies.

(schema/register! ::from :seon.agent.turn/id)
(schema/register! ::to   :seon.agent.turn/id)

(schema/register! ::turn-diff-request
  [:map
   [::from ::from]
   [::to ::to]
   [:seon.db/db {:optional true} :seon.db/db]])

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

(defn ^:async turn-diff
  "What changed between two persisted turns — a budgetable summary.

   Map-in/map-out — `{::from id ::to id}` returns both [[turn]] bundles
   plus: `::basis-t-delta` only when both cuts share one immutable containing
   commit, `::prompt-token-delta` (tokens,
   the ONE estimator), and a multiset line summary of the prompt drift
   (`::prompt-lines-added` / `-removed` — cache-stability instrument:
   frozen bytes that moved show up here). `::ok? false` with a guiding
   `::error` when either turn is missing; partial capture degrades to
   the fields both sides carry."
  {:malli/schema [:=> [:cat ::turn-diff-request] ::turn-diff-response]}
  [{::keys [from to] database :seon.db/db}]
  (let [database (or database (await (db/db)))
        ft (await (turn {:seon.agent.turn/id from :seon.db/db database}))
        tt (await (turn {:seon.agent.turn/id to :seon.db/db database}))]
    (if (or (not (::ok? ft)) (not (::ok? tt)))
      {::ok? false
       ::error (str/join "; " (keep ::error [ft tt]))}
      (let [[added removed] (line-delta (::prompt ft) (::prompt tt))
            from-tx (:seon.agent.turn/rendered-tx ft)
            to-tx (:seon.agent.turn/rendered-tx tt)]
        (cond-> {::ok? (and (::ok? ft) (::ok? tt))
                 ::from-turn ft
                 ::to-turn   tt
                 ::prompt-lines-added   added
                 ::prompt-lines-removed removed}
          (and (int? from-tx) (int? to-tx))
          (assoc ::basis-t-delta (- to-tx from-tx))
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
   [:seon.db/db {:optional true} :seon.db/db]
   [::limit           {:optional true} ::limit]])

(schema/register! ::error-row
  [:map
   [::eid                ::eid]
   [:seon.error/fault    :seon.error/fault]
   [:seon.error/message  :seon.error/message]
   [:seon.error/store-id    {:optional true} :seon.error/store-id]
   [:seon.error/branch-name {:optional true} :seon.error/branch-name]
   [:seon.error/commit-id   {:optional true} :seon.error/commit-id]
   [:seon.error/basis-t     {:optional true} :seon.error/basis-t]
   [::top-frame          {:optional true} ::top-frame]
   [:seon.agent/id       {:optional true} :seon.agent/id]])

(schema/register! ::errors-response
  [:map [::errors [:vector ::error-row]]])

(def ^:private default-errors-limit
  "Row cap for [[errors]] when the caller names none — recent, compact."
  20)

(defn- ^:async tx-agent-id
  "The agent user whose transaction wrote error `eid`."
  [db eid]
  (await
   (db/query {:seon.db/db db
              :seon.db/query
              '[:find ?aid . :in $ ?e
                :where [?e :seon.error/fault _ ?tx]
                       [?tx :seon.db/user ?author]
                       [?author :seon.agent/id ?aid]]
              :seon.db/args [eid]})))

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
  [:seon.error/fault :seon.error/message
   :seon.error/store-id :seon.error/branch-name
   :seon.error/commit-id :seon.error/basis-t
   :seon.error/stack :seon.error/args-edn :seon.error/data-edn
   {:seon.error/frames [:seon.error.frame/index :seon.error.frame/fn
                        :seon.error.frame/file :seon.error.frame/line
                        :seon.error.frame/column]}])

(defn- ^:async pull-error
  "The persisted error entity under `eid`, or nil when it isn't one."
  [db eid]
  (let [e (await (db/pull {:seon.db/db db
                           :seon.db/pull-pattern error-pull-pattern
                           :seon.db/ref eid}))]
    (when (:seon.error/fault e) e)))

(defn ^:async errors
  "List recent persisted errors, newest first — compact triage rows.

   Map-in/map-out (0-arity = defaults): optional `:seon.error/fault`
   filter (`:agent` | `:core`) and `::limit` (default 20). Each row:
   the error's entity id (feed it to [[error]] / [[repro]]), fault,
   the complete catch-site Proximum branch head, the DEEPEST-cause short
   message, the top stack frame, and the recording agent's id when the
   tx carried one. Token-bounded by construction — stored messages are
   already clipped; rows clip further for the list."
  {:malli/schema [:function
                  [:=> [:cat] ::errors-response]
                  [:=> [:cat ::errors-request] ::errors-response]]}
  ([] (await (errors {})))
  ([{fault :seon.error/fault limit ::limit database :seon.db/db}]
   (let [database (or database (await (db/db)))
         rows (await (db/query {:seon.db/db database
                                :seon.db/query
                                '[:find ?e ?f
                                  :where [?e :seon.error/fault ?f]]}))
         eids (->> rows
                   (filter (fn [[_ f]] (or (nil? fault) (= fault f))))
                   (map first)
                   (sort >)                     ; eids are monotonic → newest first
                   (take (or limit default-errors-limit))
                   vec)
         persisted
         (vec (array-seq
               (await (js/Promise.all
                       (clj->js (mapv #(pull-error database %) eids))))))
         agent-ids
         (vec (array-seq
               (await (js/Promise.all
                       (clj->js (mapv #(tx-agent-id database %) eids))))))]
     {::errors
      (mapv (fn [eid persisted aid]
              (let [{:seon.error/keys [fault message frames]} persisted
                    top (top-frame-str frames)]
                (cond-> (merge
                          {::eid eid
                           :seon.error/fault fault
                           :seon.error/message (tokens/clip-str message 25)}
                          (select-keys persisted
                                       [:seon.error/store-id
                                        :seon.error/branch-name
                                        :seon.error/commit-id
                                        :seon.error/basis-t]))
                  top (assoc ::top-frame top)
                  aid (assoc :seon.agent/id aid))))
            eids persisted agent-ids)})))

(schema/register! ::error-request
  [:map [::eid ::eid] [:seon.db/db {:optional true} :seon.db/db]])

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
   [:seon.error/store-id    {:optional true} :seon.error/store-id]
   [:seon.error/branch-name {:optional true} :seon.error/branch-name]
   [:seon.error/commit-id   {:optional true} :seon.error/commit-id]
   [:seon.error/basis-t     {:optional true} :seon.error/basis-t]
   [:seon.error/stack    {:optional true} :seon.error/stack]
   [:seon.error/args-edn {:optional true} :seon.error/args-edn]
   [:seon.error/data-edn {:optional true} :seon.error/data-edn]
   [::frames             {:optional true} ::frames]
   [:seon.agent/id       {:optional true} :seon.agent/id]
   [::turn-eid           {:optional true} ::eid]
   [:seon.agent.turn/id  {:optional true} :seon.agent.turn/id]])

(defn- ^:async turn-active-at-basis-t
  "Latest turn for one agent whose rendered transaction precedes an error."
  [db aid error-t]
  (when (and aid (int? error-t))
    (->> (await
          (db/query
           {:seon.db/db db
            :seon.db/query
            '[:find ?turn ?turn-id ?rendered-tx
              :in $ ?aid ?error-t
              :where
              [?agent :seon.agent/id ?aid]
              [?run :seon.agent.run/agent ?agent]
              [?turn :seon.agent.turn/run ?run]
              [?turn :seon.agent.turn/id ?turn-id]
              [?turn :seon.agent.turn/rendered-tx ?rendered-tx]
              [(<= ?rendered-tx ?error-t)]]
            :seon.db/args [aid error-t]}))
         (sort-by #(nth % 2) >)
         first)))

(defn ^:async error
  "Full detail for one persisted error: envelope + turn/agent joins.

   Map-in/map-out — `{::eid eid}` (from [[errors]]) returns the whole
   persisted projection (message, fault, complete branch head, frames sorted by
   index, args-edn, data-edn, stack) plus the JOINS: the recording
   agent's id and the latest turn whose rendered transaction did not follow
   the error — `::turn-eid` plus
   `:seon.agent.turn/id` so [[turn]] composes.
   An unknown eid returns `::ok? false` with a guiding `::error`."
  {:malli/schema [:=> [:cat ::error-request] ::error-response]}
  [{eid ::eid database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if-let [e (await (pull-error database eid))]
      (let [aid (await (tx-agent-id database eid))
            [teid tid] (await (turn-active-at-basis-t database aid
                                                      (:seon.error/basis-t e)))]
        (cond-> (merge {::ok? true ::eid eid}
                       (select-keys e [:seon.error/fault :seon.error/message
                                       :seon.error/store-id
                                       :seon.error/branch-name
                                       :seon.error/commit-id
                                       :seon.error/basis-t :seon.error/stack
                                       :seon.error/args-edn :seon.error/data-edn]))
          (seq (:seon.error/frames e))
          (assoc ::frames (->> (:seon.error/frames e)
                               (sort-by :seon.error.frame/index)
                               (mapv #(dissoc % :db/id))))
          aid     (assoc :seon.agent/id aid)
          teid    (assoc ::turn-eid teid :seon.agent.turn/id tid)))
      {::ok? false ::eid eid
       ::error (str "no persisted error under eid " eid
                    " — list them: (seon.agent.debug/errors)")})))

(schema/register! ::fn-sym     :symbol)
(schema/register! ::repro-expr :string)
(schema/register! ::note       :string)

(schema/register! ::repro-request
  [:map [::eid ::eid] [:seon.db/db {:optional true} :seon.db/db]])

(schema/register! ::repro-response
  [:map
   [::ok?  ::ok?]
   [::eid  ::eid]
   [::error {:optional true} ::error]
   ;; The exact database value frozen at the transaction — REPL use only.
   ;; NEVER pr-str it into agent context (it prints the whole index);
   ;; render its t and abbreviated commit instead.
   [:seon.db/db          {:optional true} :seon.db/db]
   [:seon.error/store-id    {:optional true} :seon.error/store-id]
   [:seon.error/branch-name {:optional true} :seon.error/branch-name]
   [:seon.error/commit-id   {:optional true} :seon.error/commit-id]
   [:seon.error/basis-t     {:optional true} :seon.error/basis-t]
   [::fn-sym             {:optional true} ::fn-sym]
   [:seon.error/args-edn {:optional true} :seon.error/args-edn]
   [::turn-eid           {:optional true} ::eid]
   [:seon.agent.turn/id  {:optional true} :seon.agent.turn/id]
   [::repro-expr         {:optional true} ::repro-expr]
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
  [transaction-id fn-sym args-edn]
  (let [resolve-expr
        (str "(.then (seon.db/db) "
             "(fn [database] (seon.db/as-of database " transaction-id ")))")]
    (if (and fn-sym args-edn)
      (str "(.then " resolve-expr "\n"
           "  (fn [db] (apply (resolve '" fn-sym ") (cljs.reader/read-string "
           (pr-str args-edn) "))))")
      resolve-expr)))

(defn ^:async repro
  "The work-backwards bundle for one persisted error — freeze + re-run.

   Map-in/map-out — `{::eid eid}` returns `:seon.db/db` (the exact immutable
   database value frozen at the recorded transaction — REPL material; never print
   it), the failing `::fn-sym` + `:seon.error/args-edn`
   when the malli envelope captured them (a `::note` says so honestly
   when absent — nothing is fabricated), the linked turn
   (`::turn-eid` + rendered transaction ref, composes with [[turn]]), and
   `::repro-expr` — a ready-to-eval expression string built from what's
   actually stored. `::ok? false` + guiding `::error` is returned for an
   unknown eid or an old error without a transaction id."
  {:malli/schema [:=> [:cat ::repro-request] ::repro-response]}
  [{eid ::eid database :seon.db/db}]
  (let [database (or database (await (db/db)))]
    (if-let [e (await (pull-error database eid))]
      (let [{:seon.error/keys [args-edn data-edn]} e
            aid      (await (tx-agent-id database eid))
            fn-sym   (fn-sym-from-data-edn data-edn)
            args-edn (readable-args-edn args-edn)
            transaction-id (:seon.error/basis-t e)]
        (if-not (int? transaction-id)
          {::ok? false ::eid eid
           ::error (str "error " eid
                        " has no transaction id — no database value can be reconstructed")}
          (let [historical-db (db/as-of database transaction-id)
                [teid tid]
                (await (turn-active-at-basis-t database aid transaction-id))]
            (cond-> (merge
                     {::ok? true ::eid eid
                      :seon.db/db historical-db
                      ::repro-expr
                      (repro-expr-str transaction-id fn-sym args-edn)}
                     (select-keys e [:seon.error/store-id
                                     :seon.error/branch-name
                                     :seon.error/commit-id
                                     :seon.error/basis-t]))
              fn-sym (assoc ::fn-sym fn-sym)
              args-edn (assoc :seon.error/args-edn args-edn)
              teid (assoc ::turn-eid teid :seon.agent.turn/id tid)
              true (assoc ::note
                          (if (and fn-sym args-edn)
                            "exact read-only reproduction is ready"
                            (str "no captured fn/args on this error (non-malli "
                                 "path or clipped args); use the exact frozen db")))))))
      {::ok? false ::eid eid
       ::error (str "no persisted error under eid " eid
                    " — list them: (seon.agent.debug/errors)")})))
