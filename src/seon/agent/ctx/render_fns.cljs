(ns seon.agent.ctx.render-fns
  "Auto-run — the current ns's render fns become context (context.md
   §\"Auto-run\").

   The render pass QUERIES THE PROGRAM GRAPH for fns in the agent's CURRENT
   namespace whose OUTPUT schema is a render type — a `:map` declaring
   `:seon.render/ai` (a block: its string joins the prompt) and/or
   `:seon.render/hiccup` (a tile: its own surface on the agent's page; the
   `:seon.render/html-response` envelope declares both — the twins). Each
   such fn becomes ONE DERIVED context block ([[derived-blocks]]) that
   [[seon.agent.ctx/context-root]] merges into the agent's stored block set
   at [[auto-run-priority]] — right after the stable code (`:namespaces`,
   20) and before the volatile tail. ONE ordered render list; never a
   second rendering system. Writing a specced `defn` IS authoring context.

   Detection is STRUCTURAL — the output schema, resolved via malli
   ([[output-twin-keys]]); no name conventions, no hand lists. DERIVED,
   never stored: blocks are computed per render from the db + current-ns;
   drop the fn (or leave the ns) and its block vanishes. `install!` stays
   the explicit override — a fn already pinned by a stored block's slot is
   SKIPPED here (the stored block's priority wins).

   Each run goes through the bounded, errors-as-values path: an
   agent-authored fn is SCI-interpreted with the wall-clock interrupt
   ([[seon.render.sci/invoke-bounded]] — fail-loud, no unbounded
   fallback); a core fn calls compiled through the ONE injecting
   instrumentation wrapper. Dependencies arrive EXPLICITLY (explicit-wins
   injection): the runner passes the render's frozen `:seon.db/db`, the
   `:seon.agent/id`, and `:seon.render/at` (basis-t) in the input map. A
   throw / interrupt / wrong shape becomes a `;; ⚠` line in the block and
   a `:seon/error` tile — the render pass always survives. The ai output
   is clipped at `seon.config/render-fn-token-cap` TOKENS."
  (:require
    [cljs.reader :as reader]
    [malli.core :as m]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.error :as err]
    [seon.eval :as seval]
    [seon.render.canvas :as canvas]
    [seon.render.sci :as render-sci]
    [seon.schema :as schema]))

(def auto-run-priority
  "The derived blocks' `:seon.agent.ctx/priority` — context.md group 3:
   right after the stable code (`:namespaces` = 20), before the volatile
   tail (`:canvas` = 35)."
  30)

(def ^:private twin-keys
  "The output-map keys that make a fn a renderer — the block/tile twins
   (`:seon.render/html-response` is the established envelope; a map
   declaring either key alone is a single-sided renderer)."
  #{:seon.render/ai :seon.render/hiccup})

(defn output-twin-keys
  "The render twin-keys a fn's persisted `spec-str` DECLARES on its output.

   Reads the spec (a pr-str'd `:=>` form) via `m/-function-info` →
   `m/deref` (resolves a registered ref like `:seon.render/html-response`
   or passes an inline `:map` through) → `m/entries`, and intersects the
   output map's keys with [[twin-keys]]. `#{}` for a non-`:=>` /
   multi-arity / non-map-output / unreadable spec — errors-as-values, the
   fn is simply not a renderer."
  {:malli/schema [:=> [:catn [::spec-str :string]] [:set :keyword]]}
  [spec-str]
  (try
    (let [form (reader/read-string spec-str)
          info (m/-function-info (m/schema form))
          out  (some-> (:output info) m/deref)]
      (if (and out (= :map (m/type out)))
        (into #{} (comp (map first) (filter twin-keys)) (m/entries out))
        #{}))
    (catch :default _ #{})))

(defn- render-fn-rows
  "The current ns's public, specced fns whose output schema declares a
   render twin — `[{::sym ::twins}]`, name-sorted (deterministic block
   order within the group). Pure program-graph query; `[]` when `cur-ns`
   is nil or nothing matches."
  [db cur-ns]
  (if-not cur-ns
    []
    (->> (db/query
           {:seon.db/db db
            :seon.db/query
            '[:find ?sym ?spec ?priv
              :in $ ?ns
              :where
              [?n :seon.ns/name ?ns]
              [?f :seon.fn/ns ?n]
              [?f :seon.fn/sym ?sym]
              [?f :seon.fn/spec ?spec]
              [(get-else $ ?f :seon.fn/private? false) ?priv]]
            :seon.db/args [cur-ns]})
         (keep (fn [[sym spec priv]]
                 (when-not priv
                   (let [twins (output-twin-keys spec)]
                     (when (seq twins)
                       {::sym (symbol sym) ::twins twins})))))
         (sort-by (comp str ::sym))
         vec)))

;; :seon.ns/name lives HERE (not seon.agent.ctx) because THIS ns loads
;; first — seon.agent.ctx requires us, and ::current-ns below references
;; it at load time (a cold boot with the registration upstream dies with
;; ":seon.ns/name is not a valid Malli schema").
(schema/register! :seon.ns/name [:keyword {:seon.db/identity true}])

(schema/register! ::fn-sym :symbol)
(schema/register! ::current-ns :seon.ns/name)
(schema/register! ::pinned-syms [:set :symbol])

;; `:seon.agent/id` — registered HERE (not in its owning ns `seon.agent`)
;; because this is the FIRST-loading ns whose load-time schema references
;; it (`::derived-blocks-request` below; seon.render + seon.agent load
;; after this ns, and register!'s compilability guard rejects forward
;; references — same precedent as `:seon.ns/name` above). Moved
;; seon.agent → seon.render in C54, → here in C58. The value schema admits the
;; reserved orchestrator `root`, preserved legacy ids, and newly generated
;; readable ids; only the readable branch is generated now.
;; The `:or` bridges to the SAME datahike schema: the CLJS bridge
;; (seon.db.internal/form->datahike-value-type) walks `:and` → base, then
;; the `:or` (one mappable type :db.type/string via :seon.db/id + the
;; unmappable `[:= "root"]`) → :db.type/string; the
;; `{:seon.db/identity true}` prop still yields :db.unique/identity.
;; Because the resolved head is `:and` (not `:or`), `edn-encoded-attr?`
;; is FALSE — "root" stores as a plain string, NOT pr-str'd EDN.
(schema/register!
  :seon.agent/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/human-readable}
   ::db.id/agent-value])

(schema/register! ::derived-blocks-request
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id {:optional true} :seon.agent/id]
   [::current-ns   {:optional true} ::current-ns]
   [::pinned-syms  {:optional true} ::pinned-syms]])

;; ============================================================
;; The canvas default — the agent's LAST-UPDATED tile (context.md /
;; ui.md §canvas: "derived by default, pinnable to override"). Pure
;; f(db value): nothing is stored, no touched-at stamp — the touch
;; coordinate is read off the datoms' tx column (+ the history view,
;; so a retraction counts as a change too).
;; ============================================================

(def ^:private attr-literal-re
  "Qualified keyword LITERALS in a stored fn source — the LEGACY-fallback
   regex behind [[declared-read-attrs]]. Requires a `/` (an unqualified
   `:keys` never matches); an `::alias/k` form matches only its bare
   `alias/k` tail, which the installed-attr intersection then discards."
  #":([A-Za-z][A-Za-z0-9_.$-]*/[A-Za-z0-9_.*+!?<>='-]+)")

(def ^:private dependency-exclusions
  "Transaction/delivery plumbing is not domain view data.

   These attrs appear as qualified literals in generic database helpers and
   provenance queries, but their presence on every commit must not invalidate
   every renderer or steal surface focus. A renderer that intentionally shows
   provenance should derive it behind a domain-facing function/read model."
  #{:db/txInstant
    :seon.db/user
    :seon.db/process
    :seon.store.wire/id})

(defn- source-attrs
  "LEGACY fallback: regex-scan `src` for qualified keyword literals.

   Kept ONLY for PRE-STRUCTURAL `:seon.fn` rows (no stored
   `:seon.fn/read-attrs` — old stores; they self-backfill on the next
   replay/re-eval). New rows take the stored path in
   [[declared-read-attrs]]; never widen this scan."
  [installed src]
  (->> (re-seq attr-literal-re src)
       (map (comp keyword second))
       (filter installed)
       (remove twin-keys)
       distinct
       vec))

(defn- declared-read-attrs
  "The fn's DECLARED read-set: the attrs its author's forms name as
   qualified keyword literals (agent `my.*` code must fully qualify
   (#73), so the literals are the queries). Reads the STORED
   `:seon.fn/read-attrs` (the tee extracts the set from the
   already-read defn form — C28 structural store); a row without the
   attr (pre-structural) falls back to the [[source-attrs]] regex over
   `src`. Intersected with the INSTALLED schema at read time (an attr
   registered after the tee still joins the watch set) and the render
   twin keys (the fn's OUTPUT, never data) removed. Conservative by
   construction: an attr reached only dynamically is not watched."
  [installed stored-kws src]
  (if (some? stored-kws)
    (->> stored-kws
         (filter installed)
         (remove twin-keys)
         (remove dependency-exclusions)
         distinct
         vec)
    (->> (source-attrs installed src)
         (remove dependency-exclusions)
         vec)))

(schema/register! ::last-updated-request
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id :string]])
(schema/register! ::tile-sym :symbol)
(schema/register! ::touch :int)
(schema/register! ::last-updated-response
  [:map
   [::tile-sym {:optional true} ::tile-sym]
   [::touch    {:optional true} ::touch]])

(defn- fn-row
  "One stored fn's source transaction and declared database read-set."
  [db sym]
  (let [installed (db/installed-schema db)]
    (when (every? installed [:seon.fn/sym :seon.fn/source])
      (when-let [[src src-tx]
                 (first (db/query
                          {:seon.db/db db
                           :seon.db/query
                           '[:find ?src ?tx
                             :in $ ?sym
                             :where
                             [?f :seon.fn/sym ?sym]
                             [?f :seon.fn/source ?src ?tx]]
                           :seon.db/args [(str sym)]}))]
        (let [stored (when (installed :seon.fn/read-attrs)
                       (not-empty
                         (set (:seon.fn/read-attrs
                                (db/pull db [:seon.fn/read-attrs]
                                         [:seon.fn/sym (str sym)])))))]
          {::src-tx src-tx
           ::attrs (declared-read-attrs installed stored src)})))))

(schema/register! ::renderer-read-attrs-request
  [:map
   [:seon.db/db :seon.db/db]
   [:seon.render/html :symbol]])
(schema/register! ::renderer-read-attrs-response
  [:map [::attrs [:vector :qualified-keyword]]])

(defn renderer-read-attrs
  "A symbolic renderer's declared database read-set.

   This is the dependency projection used by both recency and live UI
   invalidation. It reads the stored analyzer-produced `:seon.fn/read-attrs`
   and retains the existing legacy source fallback through [[fn-row]]."
  {:malli/schema [:=> [:cat ::renderer-read-attrs-request]
                  ::renderer-read-attrs-response]}
  [{db :seon.db/db renderer :seon.render/html}]
  {::attrs (vec (or (::attrs (fn-row db renderer)) []))})

(schema/register! ::renderer-touch-request
  [:map
   [:seon.db/db :seon.db/db]
   [:seon.agent/id :string]
   [:seon.render/html :symbol]])
(schema/register! ::renderer-touch-response
  [:map [::touch {:optional true} ::touch]])

(defn renderer-touch
  "Latest transaction by `agent-id` touching a renderer's declared read-set.

   The renderer identity and read-set come from the stored program graph. The
   history query joins each data transaction to the standard agent provenance
   metadata, so another agent changing the same attribute cannot steal focus.
   The renderer's own source transaction also counts when that agent authored
   it. Pure over `db`; `{}` means no deliberate update by this agent."
  {:malli/schema [:=> [:cat ::renderer-touch-request]
                  ::renderer-touch-response]}
  [{db :seon.db/db id :seon.agent/id sym :seon.render/html}]
  (if-let [{::keys [src-tx attrs]} (fn-row db sym)]
    (let [history (db/history db)
          attr-tx (when (seq attrs)
                    (ffirst
                      (db/query
                        {:seon.db/db history
                         :seon.db/query
                         '[:find (max ?tx)
                           :in $ ?aid [?a ...]
                           :where
                           [?e ?a _ ?tx]
                           [?tx :seon.db/user ?author]
                           [?author :seon.agent/id ?aid]]
                         :seon.db/args [id (vec attrs)]})))
          source-by-agent?
          (boolean
            (seq (db/query
                   {:seon.db/db history
                    :seon.db/query
                    '[:find ?tx
                      :in $ ?tx ?aid
                      :where
                      [?tx :seon.db/user ?author]
                      [?author :seon.agent/id ?aid]]
                    :seon.db/args [src-tx id]})))
          touches (cond-> []
                    source-by-agent? (conj src-tx)
                    attr-tx (conj attr-tx))]
      (if (seq touches) {::touch (apply max touches)} {}))
    {}))

(defn last-updated-tile
  "The agent's last-updated tile fn — the derived canvas default.

   Candidates are THIS agent's authored tile fns: `:seon.fn` rows whose
   `:seon.fn/source` datom's tx carries the agent's provenance
   (`:seon.db/user` ref — the one who-wrote-what mechanism) and
   whose registered spec's output declares `:seon.render/hiccup`
   ([[output-twin-keys]] — the same structural detection as auto-run).
   Each candidate's TOUCH coordinate is the max tx over (a) its own
   source datom — redefining the tile touches it — and (b) every datom
   of the attrs it declares ([[declared-read-attrs]] — the stored
   `:seon.fn/read-attrs`, regex fallback for pre-structural rows), read
   on the HISTORY view so retractions count. The candidate with the max touch
   is the last-updated tile; `{}` when the agent has authored none (the
   caller falls back to the welcome). Ties break on the fn name.

   Pure over the frozen db value — derive-don't-store: no touched-at
   stamp exists anywhere. Honest bound: a tile reading attrs it never
   names literally (dynamic attr construction) follows only its own
   redefinitions."
  {:malli/schema [:=> [:cat ::last-updated-request] ::last-updated-response]}
  [{db :seon.db/db id :seon.agent/id}]
  (let [installed (db/installed-schema db)]
    (if-not (every? installed [:seon.fn/sym :seon.fn/source :seon.fn/spec
                               :seon.db/user])
      {}
      (let [;; `get-else` on a NEVER-INSTALLED attr yields NO rows at all
            ;; (not its default), so the privacy column joins the query
            ;; only when `:seon.fn/private?` is installed; the filter
            ;; itself runs in Clojure, mirroring [[render-fn-rows]].
            q    (if (installed :seon.fn/private?)
                   '[:find ?sym ?src ?spec ?srctx ?priv
                     :in $ ?aid
                     :where
                     [?f :seon.fn/source ?src ?srctx]
                     [?srctx :seon.db/user ?author]
                     [?author :seon.agent/id ?aid]
                     [?f :seon.fn/sym ?sym]
                     [?f :seon.fn/spec ?spec]
                     [(get-else $ ?f :seon.fn/private? false) ?priv]]
                   '[:find ?sym ?src ?spec ?srctx
                     :in $ ?aid
                     :where
                     [?f :seon.fn/source ?src ?srctx]
                     [?srctx :seon.db/user ?author]
                     [?author :seon.agent/id ?aid]
                     [?f :seon.fn/sym ?sym]
                     [?f :seon.fn/spec ?spec]])
            ;; stored read-set (C28) — nil for a pre-structural row (or
            ;; a store predating the attr), which falls back to the
            ;; legacy regex in [[declared-read-attrs]].
            stored-kws (fn [sym]
                         (when (installed :seon.fn/read-attrs)
                           (not-empty
                             (set (:seon.fn/read-attrs
                                    (db/pull db [:seon.fn/read-attrs]
                                             [:seon.fn/sym (str sym)]))))))
            rows (->> (db/query {:seon.db/db db :seon.db/query q
                                 :seon.db/args [id]})
                      (keep (fn [[sym src spec srctx priv]]
                              (when (and (not priv)
                                         (contains? (output-twin-keys spec)
                                                    :seon.render/hiccup))
                                {::tile-sym (symbol sym)
                                 ::src-tx   srctx
                                 ::attrs    (declared-read-attrs
                                              installed (stored-kws sym) src)}))))
            ;; ONE aggregate over the union of watched attrs (history view —
            ;; a retraction is a change), then per-candidate max lookup.
            attrs   (into #{} (mapcat ::attrs) rows)
            attr-tx (if (seq attrs)
                      (into {} (db/query
                                 {:seon.db/db (db/history db)
                                  :seon.db/query
                                  '[:find ?a (max ?tx)
                                    :in $ [?a ...]
                                    :where [?e ?a _ ?tx]]
                                  :seon.db/args [(vec attrs)]}))
                      {})
            best    (->> rows
                         (map (fn [{::keys [tile-sym src-tx attrs]}]
                                {::tile-sym tile-sym
                                 ::touch (reduce max src-tx
                                                 (keep attr-tx attrs))}))
                         (sort-by (juxt ::touch (comp str ::tile-sym)))
                         last)]
        (if (some? best)
          {::tile-sym (::tile-sym best)}
          {})))))

(defn derived-blocks
  "The DERIVED auto-run context blocks for the agent's current ns.

   One block per discovered render fn ([[render-fn-rows]]), each carrying
   `::fn-sym` (the fn to run) and the runner symbols in the slots its
   output declares — `:seon.render/ai` → [[render-fn-block-ai]],
   `:seon.render/hiccup` → [[render-fn-block-html]] — at
   [[auto-run-priority]]. A fn in `::pinned-syms` (already the slot of a
   STORED block — the `install!` override) is skipped. Computed per render,
   never persisted."
  {:malli/schema [:=> [:cat ::derived-blocks-request] [:vector :map]]}
  [{db :seon.db/db cur-ns ::current-ns pinned ::pinned-syms}]
  (->> (render-fn-rows db cur-ns)
       (remove #(contains? (or pinned #{}) (::sym %)))
       (mapv (fn [{sym ::sym twins ::twins}]
               (cond-> {:seon.agent.ctx/name     (keyword "render-fn" (name sym))
                        :seon.agent.ctx/priority auto-run-priority
                        ::fn-sym                 sym}
                 (contains? twins :seon.render/ai)
                 (assoc :seon.render/ai 'seon.agent.ctx.render-fns/render-fn-block-ai)
                 (contains? twins :seon.render/hiccup)
                 (assoc :seon.render/html 'seon.agent.ctx.render-fns/render-fn-block-html))))))

;; ============================================================
;; The runner — ONE bounded, errors-as-values invocation shared by both
;; twins. Returns the fn's value (the html-response envelope or a bare
;; view value) OR the `:seon.render.sci/interrupt` / `:seon.render.sci/error`
;; envelope — never throws.
;; ============================================================

(defn- run-render-fn
  "Run the derived block's `::fn-sym` for `view`, bounded + errors-as-values.

   Builds the fn's input EXPLICITLY from the render context — the frozen
   `:seon.db/db`, the `:seon.agent/id`, and `:seon.render/at` (the frozen
   db's basis-t) — so a fn declaring those keys reads the render's frozen
   snapshot (explicit args win over boundary injection). An agent-authored
   sym runs SCI-bounded ([[seon.render.sci/invoke-bounded]]); a core sym
   calls compiled (its instrumented wrapper validates). Never throws."
  [in view]
  (let [node (:seon.render/node in)
        sym  (::fn-sym node)
        db*  (:seon.db/db in)
        input (cond-> {:seon.db/db db*}
                (:seon.agent/id in) (assoc :seon.agent/id (:seon.agent/id in))
                db* (assoc :seon.render/at
                           (try (db/basis-t db*) (catch :default _ 0))))]
    (cond
      (not (qualified-symbol? sym))
      {:seon.render.sci/error
       {:seon.error/message (str "auto-run block carries no runnable ::fn-sym ("
                                 (pr-str sym) ")")}}
      (err/agent-authored-sym? sym)
      (render-sci/invoke-bounded sym input view)
      :else
      (if-let [f (seval/lookup-value sym)]
        (try (f input)
             (catch :default e {:seon.render.sci/error (err/->map e)}))
        {:seon.render.sci/error
         {:seon.error/message (str sym " does not resolve — define it and this "
                                   "block self-heals next render")}}))))

(defn- clip-marker
  "The LOUD token-denominated truncation notice appended at an auto-run
   clip cut."
  [budget total]
  (str " …⟨⚠ clipped at " budget " of " total
       " tokens — narrow this render fn's output⟩"))

(defn render-fn-block-ai
  "The derived auto-run block's `:seon.render/ai` slot — run the fn, keep
   its ai twin.

   Runs the node's `::fn-sym` through [[run-render-fn]] and returns its
   `:seon.render/ai` string (envelope or bare), clipped at
   `seon.config/render-fn-token-cap` tokens. An interrupt / error / wrong
   shape becomes a `;; ⚠` line naming the fn — the agent sees exactly what
   to fix, in place, and the render pass survives (errors-as-values)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [in]
  (let [sym (::fn-sym (:seon.render/node in))
        r   (run-render-fn in :seon.render/ai)]
    (cond
      (and (map? r) (:seon.render.sci/interrupt r))
      (str ";; ⚠ render fn " sym " did not terminate within its budget and was "
           "skipped — render fns must be fast, terminating db→view derivations")
      (and (map? r) (:seon.render.sci/error r))
      (str ";; ⚠ render fn " sym " failed: "
           (get-in r [:seon.render.sci/error :seon.error/message]))
      :else
      (let [s (if (map? r) (:seon.render/ai r) r)]
        (cond
          (nil? s)    ""
          (string? s) (tokens/clip-str s (config/render-fn-token-cap) clip-marker)
          :else (str ";; ⚠ render fn " sym " returned a non-string "
                     ":seon.render/ai — fix its output"))))))

(defn render-fn-block-html
  "The derived auto-run block's `:seon.render/html` slot — the tile twin.

   Runs the node's `::fn-sym` through [[run-render-fn]] and returns its
   `:seon.render/hiccup` (envelope or bare vector); nil renders nothing. An
   interrupt / error / wrong shape becomes the ONE `:seon/error` tile in
   place ([[seon.render.canvas/error-tile]]) — the render pass survives."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.canvas/hiccup]]}
  [in]
  (let [sym (::fn-sym (:seon.render/node in))
        r   (run-render-fn in :seon.render/html)]
    (cond
      (and (map? r) (:seon.render.sci/interrupt r))
      (canvas/error-tile
        {:seon.error/message (str sym " did not terminate within its budget")
         :seon.error/where   :auto-run
         :seon.error/hint    "render fns must be fast, terminating db→view derivations"})
      (and (map? r) (:seon.render.sci/error r))
      (canvas/error-tile
        (assoc (:seon.render.sci/error r) :seon.error/where :auto-run
               :seon.error/symbol sym))
      :else
      (let [h (if (map? r) (:seon.render/hiccup r) r)]
        (cond
          (nil? h)    nil
          (vector? h) h
          :else (canvas/error-tile
                  {:seon.error/message (str sym " returned a non-hiccup "
                                            ":seon.render/hiccup — fix its output")
                   :seon.error/where   :auto-run}))))))
