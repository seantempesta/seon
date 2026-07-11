(ns seon.agent.ctx.typeahead-steps
  "The `:typeahead-steps` ctx block family — the diffusion typeahead
   provider's observability twin (typeahead-design.md \"The live block\").

   ONE block, BOTH render slots, both reactive (pure fns of the db at
   render time — reactive-context, nothing stored):

     - `:seon.render/html` ([[steps-tile-html]]) — the agent-page live
       tile: the LAST provider call's step trace (transition, glyph,
       calibrated margin, EOS done-ness meter, forwards, wall per step),
       the call header (worker sha, render size in tokens, total locked
       forms) and the current draft/code-buffer preview (sized in
       TOKENS). Fed by the per-step `:seon.typeahead/*` projections the
       provider loop (`seon.ai.typeahead`) transacts each round, so a tx
       during a live call morphs the tile via the normal datastar SSE
       channel. nil when the agent has no step rows — the tile vanishes.
     - `:seon.render/ai` ([[steps-ai]]) — the provider protocol's
       special instructions, rendered ONLY when the agent's RESOLVED
       provider is `:typeahead` (`seon.ai/resolved-config` over the
       render db — per-agent `::agent-provider` overlay included). Any
       other provider → \"\" and the section vanishes at zero token
       cost. Glyph-selection teaching stays colocated with the menu
       sections (`seon.agent.ctx.menu` headers own it — one fact, one
       file); this slot adds only what the menu does not teach: how the
       step loop builds the reply and the live result grammar.

   NOT installed by default anywhere (owner constraint): no config seed,
   no self-install. Enabling is an explicit per-agent
   `(seon.agent.ctx/install! steps-block)` (or a manifest override a
   cluster opts into — documented in typeahead-design.md, never shipped
   in a default config). `(seon.agent.ctx/remove! :typeahead-steps)`
   reverts; both slots vanish on the next render."
  (:require
    [clojure.string :as str]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]))

;; ============================================================
;; The block — install explicitly, never seeded.
;; ============================================================

(def steps-block
  "The `:typeahead-steps` ctx block map — hand it to
   `seon.agent.ctx/install!` inside the target agent's scope."
  {:seon.agent.ctx/name     :typeahead-steps
   :seon.agent.ctx/priority 95
   :seon.render/ai          'seon.agent.ctx.typeahead-steps/steps-ai
   :seon.render/html        'seon.agent.ctx.typeahead-steps/steps-tile-html})

;; ============================================================
;; :seon.render/ai — the provider's special instructions, gated on the
;; agent's RESOLVED provider being :typeahead.
;; ============================================================

(def ^:private instructions
  ;; Only what the menu headers do NOT already teach (they own glyph
  ;; selection — strictly optional, forever): the step loop's reply
  ;; mechanics and the live result grammar.
  (str "; typeahead step loop — your reply is built form-by-form: each\n"
       "; parse-clean form LOCKS and carries forward; the unfinished tail\n"
       "; stays an editable draft. A selected menu glyph (the menu sections\n"
       "; teach selection; it stays strictly optional) EXPANDS to that\n"
       "; entry's call template `(fn …)` with a free hole for arguments.\n"
       "; Locked forms are eval'd by the pod AFTER the turn — results\n"
       "; arrive next turn as the transcript's bare `⟹ <value>` rows.\n"
       "; `⟹` is runtime output only; `;; =>` is not part of the grammar."))

(defn- typeahead-provider?
  "Whether agent `id`'s RESOLVED provider in db value `db` is
   `:typeahead` — the agent's own `::agent-provider` overlay over the
   global `:seon.ai/config` row over the shipped default. false on any
   read failure (a fresh db without the config attrs installed)."
  [db id]
  (boolean
    (try
      (when db
        (= :typeahead
           (get-in (ai/resolved-config
                     (cond-> {:seon.db/db db}
                       id (assoc :seon.agent/id id)))
                   [:seon.ai/resolved-config :seon.ai/provider])))
      (catch :default _ false))))

(defn steps-ai
  "The typeahead provider's protocol instructions, provider-gated.

   Renders [[instructions]] only when the agent's RESOLVED provider is
   `:typeahead` (`seon.ai/resolved-config` over the render db — the
   per-agent overlay applies, so one typeahead-routed agent sees it
   while its deepseek siblings don't). Any other provider → \"\" and
   the composer drops the section (reactive-context, zero token cost)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{db :seon.db/db id :seon.agent/id}]
  (let [db (or db (some-> db/*conn* deref))]
    (if (typeahead-provider? db id) instructions "")))

;; ============================================================
;; :seon.render/html — the step-trace tile, derived at render from the
;; provider loop's per-step :seon.typeahead/* projections.
;; ============================================================

(def ^:private transition-display
  "Transition keyword → dot glyph + Phosphor text class."
  {:done     {:dot "●" :class "text-signal"}
   :expand   {:dot "●" :class "text-signal"}
   :progress {:dot "●" :class "text-text-200"}
   :grow     {:dot "●" :class "text-text-400"}
   :repair   {:dot "⚠" :class "text-warning"}
   :stuck    {:dot "✗" :class "text-error"}})

(defn- last-call-steps
  "The agent's most recent call's step rows, step-idx-ordered. nil when
   the attr is uninstalled or the agent has no steps."
  [db id]
  (when (and db id (contains? (db/installed-schema db) :seon.typeahead/call))
    (let [rows (->> (db/query {:seon.db/db    db
                               :seon.db/query '[:find [?e ...]
                                                :in $ ?aid
                                                :where
                                                [?a :seon.agent/id ?aid]
                                                [?e :seon.typeahead/agent ?a]]
                               :seon.db/args  [id]})
                    (map #(db/pull db '[*] %))
                    (filter :seon.typeahead/at))]
      (when (seq rows)
        (let [call (->> rows
                        (sort-by #(.getTime ^js (:seon.typeahead/at %)))
                        last
                        :seon.typeahead/call)]
          (->> rows
               (filter #(= call (:seon.typeahead/call %)))
               (sort-by :seon.typeahead/step-idx)
               vec))))))

(defn- eos-meter
  "The done-ness meter — `eos-logprob` (measured −7 more-work … −2.8
   done) as a small horizontal bar."
  [lp]
  (let [pct (-> (* 100 (/ (+ lp 8) 8)) (max 2) (min 100) js/Math.round)]
    [:div {:class "flex items-center gap-1"}
     [:div {:class "w-16 h-1.5 bg-base-800 rounded overflow-hidden"}
      [:div {:class "h-full bg-signal"
             :style (str "width:" pct "%")}]]
     [:span {:class "text-text-500 text-2xs font-mono"} (str lp)]]))

(defn- expand-tag
  "An `:expand` row's outcome tag — the fired offer either locked in the
   step (`→⊢`) or expanded to nothing (`✗offer`). nil for other rows."
  [{:seon.typeahead/keys [transition locked-count]}]
  (when (= :expand transition)
    (if (pos? (or locked-count 0))
      [:span {:class "text-signal"} "→⊢"]
      [:span {:class "text-error"} "✗offer"])))

(defn- step-row
  "One rendered step line: idx, ● transition, glyph, margin, locks,
   wall, entropy, EOS meter."
  [{:seon.typeahead/keys [step-idx transition glyph margin eos-logprob
                          locked-count forwards gen-s entropy-worst]
    :as row}]
  (let [{:keys [dot class]} (get transition-display transition
                                 {:dot "●" :class "text-text-400"})]
    [:div {:class "flex items-center gap-3 text-xs font-mono py-0.5"}
     [:span {:class "text-text-500 w-6 text-right"} (str step-idx)]
     [:span {:class (str class " w-24")} (str dot " " (name transition))]
     [:span {:class "text-signal w-8"} (or glyph "·")]
     (or (expand-tag row)
         [:span {:class "text-text-400 w-20"}
          (if margin (str "Δ" margin) "Δ —")])
     [:span {:class "text-text-400 w-16"}
      (str "⊢ " locked-count (when forwards (str " ·" forwards "f")))]
     (when gen-s
       [:span {:class "text-text-500 w-12"} (str gen-s "s")])
     (when entropy-worst
       [:span {:class "text-text-500 w-12"} (str "H" entropy-worst)])
     (when eos-logprob (eos-meter eos-logprob))]))

(defn- call-header
  "The call meta line: step count, total locked forms, render size in
   tokens, worker sha (short) — whichever of those the rows carry."
  [steps]
  (let [locked (reduce + 0 (keep :seon.typeahead/locked-count steps))
        ptoks  (some :seon.typeahead/prompt-tokens steps)
        sha    (some :seon.typeahead/worker-sha steps)]
    [:div {:class "flex items-center justify-between"}
     [:span {:class "text-text-500 text-2xs uppercase tracking-wider"}
      "typeahead steps"]
     [:span {:class "text-text-500 text-2xs font-mono"}
      (str/join " · "
                (cond-> [(str (count steps) " step"
                              (when (not= 1 (count steps)) "s"))
                         (str "⊢ " locked)]
                  ptoks (conj (str "ctx ~" ptoks " tok"))
                  sha   (conj (subs sha 0 (min 8 (count sha))))))]]))

(defn- draft-line
  "The current draft/code-buffer preview line from the LAST step row —
   truncated text, sized in TOKENS. nil when the draft is empty."
  [steps]
  (let [{:seon.typeahead/keys [draft-preview draft-tokens]} (last steps)]
    (when (and draft-preview (not (str/blank? draft-preview)))
      [:div {:class "text-xs font-mono text-text-400 pt-0.5"}
       [:span {:class "text-text-500"}
        (str "draft ~" (or draft-tokens (tokens/estimate draft-preview))
             " tok ")]
       draft-preview])))

(defn steps-tile-html
  "The typeahead step-trace tile — the last provider call's FSM steps.

   Derived at render from the agent's `:seon.typeahead/*` step rows
   (latest call only): the call header (steps, total locked, render
   tokens, worker sha), per step the transition (dot+text), any emitted
   glyph, the expand outcome (`→⊢` locked / `✗offer` failed), the
   calibrated margin, locked-form count + decoder forwards, wall per
   step, worst free-region entropy, and the EOS done-ness meter — then
   the current draft preview sized in tokens. nil when the agent has no
   step rows — the tile body vanishes (reactive-context)."
  {:malli/schema [:=> [:cat :seon.render/section-request]
                  [:maybe :seon.render.live-tile/hiccup]]}
  [{db :seon.db/db id :seon.agent/id}]
  (let [db    (or db (some-> db/*conn* deref))
        steps (last-call-steps db id)]
    (when (seq steps)
      (into
        [:div {:class "flex flex-col gap-1"}
         (call-header steps)
         (into [:div {:class "flex flex-col"}]
               (map step-row steps))]
        (keep identity [(draft-line steps)])))))
