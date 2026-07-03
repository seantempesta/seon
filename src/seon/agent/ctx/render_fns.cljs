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
    [seon.error :as err]
    [seon.eval :as seval]
    [seon.render.live-tile :as live-tile]
    [seon.render.sci :as render-sci]
    [seon.schema :as schema]))

(def auto-run-priority
  "The derived blocks' `:seon.agent.ctx/priority` — context.md group 3:
   right after the stable code (`:namespaces` = 20), before the volatile
   tail (`:live-tile` = 35)."
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
(schema/register! ::derived-blocks-request
  [:map
   [:seon.db/db    :seon.db/db]
   [:seon.agent/id {:optional true} :string]
   [::current-ns   {:optional true} ::current-ns]
   [::pinned-syms  {:optional true} ::pinned-syms]])

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
      (render-sci/agent-authored-sym? sym)
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
  {:malli/schema [:=> [:cat :map] :string]}
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
   place ([[seon.render.live-tile/error-tile]]) — the render pass survives."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.render.live-tile/hiccup]]}
  [in]
  (let [sym (::fn-sym (:seon.render/node in))
        r   (run-render-fn in :seon.render/html)]
    (cond
      (and (map? r) (:seon.render.sci/interrupt r))
      (live-tile/error-tile
        {:seon.error/message (str sym " did not terminate within its budget")
         :seon.error/where   :auto-run
         :seon.error/hint    "render fns must be fast, terminating db→view derivations"})
      (and (map? r) (:seon.render.sci/error r))
      (live-tile/error-tile
        (assoc (:seon.render.sci/error r) :seon.error/where :auto-run
               :seon.error/symbol sym))
      :else
      (let [h (if (map? r) (:seon.render/hiccup r) r)]
        (cond
          (nil? h)    nil
          (vector? h) h
          :else (live-tile/error-tile
                  {:seon.error/message (str sym " returned a non-hiccup "
                                            ":seon.render/hiccup — fix its output")
                   :seon.error/where   :auto-run}))))))
