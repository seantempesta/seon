(ns seon.agent.ctx.typeahead-steps
  "The `:typeahead-steps` ctx block family — the diffusion typeahead
   provider's observability twin (typeahead-design.md \"The live block\").

   ONE block, BOTH render slots, both reactive (pure fns of the db at
   render time — reactive-context, nothing stored):

     - `:seon.render/html` ([[steps-surface-html]]) — the agent-page live
       surface: a state banner (FSM state now, provider, step k/N, rounds,
       wall, ctx tokens, server sha), THE CODE-BUFFER PANE (the last
       step's `buffer_text` painted by `buffer_spans` status — locked /
       clamped / settled / resolving / repaired / frontier — with a
       legend), the offers panel (per-offer fired/suppressed/
       below-margin + a calibrated-margin bar against the threshold),
       the holes panel (per-hole entropy, CAL-chosen length, accepted),
       the done-ness strip (EOS logprob meter + harvest totals) and the
       compact step history. Fed by the per-step `:seon.typeahead/*`
       projections the provider loop (`seon.ai.typeahead`) transacts
       each round, so a tx during a live call morphs the surface via the
       normal datastar SSE channel. nil when the agent has no step
       rows — the surface vanishes; every panel vanishes when its rows
       lack the data (reactive-context).
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
    [cljs.reader :as reader]
    [clojure.string :as str]
    [seon.ai :as ai]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

;; ============================================================
;; The block — install explicitly, never seeded.
;; ============================================================

(def steps-block
  "The `:typeahead-steps` ctx block map — hand it to
   `seon.agent.ctx/install!` inside the target agent's scope."
  {:seon.agent.ctx/name     :typeahead-steps
   :seon.agent.ctx/priority 95
   :seon.render/ai          'seon.agent.ctx.typeahead-steps/steps-ai
   :seon.render/html        'seon.agent.ctx.typeahead-steps/steps-surface-html})

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
       "; entry's call template with a free hole for arguments.\n"
       "; Locked forms are eval'd by the pod AFTER the turn — results\n"
       "; arrive next turn as the transcript's bare `⟹ <value>` rows.\n"
       "; `⟹` is runtime output only; `;; =>` is not part of the grammar."))

(defn- resolved-provider
  "Agent `id`'s RESOLVED provider keyword in db value `db`, nil on any
   read failure (a fresh db without the config attrs installed)."
  [db id]
  (try
    (when db
      (get-in (ai/resolved-config
                (cond-> {:seon.db/db db}
                  id (assoc :seon.agent/id id)))
              [:seon.ai/resolved-config :seon.ai/provider]))
    (catch :default _ nil)))

(defn- prompt-provider
  "Resolve the prompt provider from already-acquired ordinary rows."
  [agent-row config-row]
  (let [override (some-> (:seon.ai/agent-provider agent-row)
                         (db/decode-edn-value :seon.ai/agent-provider))]
    (if (and override (not= :inherit override))
      override
      (or (:seon.ai/provider config-row) :deepseek))))

(defn ^:async ^:private acquire-prompt-provider
  [{agent-id :seon.agent/id :as input}]
  (let [coordinate (or (::db/coordinate input)
                       (::db/coordinate (db/current-tx-context)))
        acquired (when coordinate
                   (await
                     (db/execute-many
                       {::db/coordinate coordinate
                        ::db/members
                        [{::protocol/operation protocol/pull-operation
                          ::protocol/selector [:seon.ai/agent-provider]
                          ::protocol/entity-id [:seon.agent/id agent-id]
                          :datahike.resource/max-work 10000
                          :datahike.resource/max-results 8
                          :datahike.resource/max-result-weight 1024}
                         {::protocol/operation protocol/pull-operation
                          ::protocol/selector [:seon.ai/provider]
                          ::protocol/entity-id [:seon.ai/id "config"]
                          :datahike.resource/max-work 10000
                          :datahike.resource/max-results 8
                          :datahike.resource/max-result-weight 1024}]
                        ::db/max-result-weight 4096})))]
    (if-not (and coordinate (= coordinate (::db/coordinate acquired))
                 (every? #(true? (::protocol/success? %))
                         (::db/results acquired)))
      {:seon.error/message "Typeahead provider acquisition failed."
       :seon.error/kind :core-bug
       :seon.error/data acquired}
      (let [[agent-member config-member] (::db/results acquired)]
        (prompt-provider (::protocol/result agent-member)
                         (::protocol/result config-member))))))

(defn ^:async steps-ai
  "The typeahead provider's protocol instructions, provider-gated.

   Renders [[instructions]] only when the agent's RESOLVED provider is
   `:typeahead` (`seon.ai/resolved-config` over the render db — the
   per-agent overlay applies, so one typeahead-routed agent sees it
   while its deepseek siblings don't). Any other provider → \"\" and
   the composer drops the section (reactive-context, zero token cost)."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (let [provider (await (acquire-prompt-provider input))]
    (cond
      (:seon.error/message provider)
      (str "[typeahead-steps] render failed: "
           (:seon.error/message provider))

      (= :typeahead provider) instructions
      :else "")))

;; ============================================================
;; :seon.render/html — the step-trace surface, derived at render from the
;; provider loop's per-step :seon.typeahead/* projections.
;; ============================================================

(def ^:private transition-display
  "Transition keyword → dot glyph + Phosphor text class."
  {:done     {:dot "●" :class "text-success"}
   :expand   {:dot "●" :class "text-signal"}
   :progress {:dot "●" :class "text-text-200"}
   :grow     {:dot "●" :class "text-text-400"}
   :repair   {:dot "⚠" :class "text-warning"}
   :stuck    {:dot "✗" :class "text-error"}})

(defn- read-edn
  "Parse a persisted `*-edn` projection string; nil on any failure —
   a malformed row degrades to a missing panel, never a throw."
  [s]
  (when (string? s)
    (try (reader/read-string s) (catch :default _ nil))))

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
                    (map #(db/pull {:seon.db/db db
                                    :seon.db/pull-pattern '[*]
                                    :seon.db/ref %}))
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

;; ------------------------------------------------------------
;; 1. State banner — the FSM state NOW + the call vitals.
;; ------------------------------------------------------------

(defn- fsm-state
  "The banner's dot+text state from the LAST step row: `done`, `expand`,
   `repair`, `stuck`, `locked` (progress that harvested) or `denoise`."
  [{:seon.typeahead/keys [transition locked-count]}]
  (case transition
    :done   {:dot "●" :class "text-success" :label "done"}
    :expand {:dot "●" :class "text-signal"  :label "expand"}
    :repair {:dot "⚠" :class "text-warning" :label "repair"}
    :stuck  {:dot "✗" :class "text-error"   :label "stuck"}
    (if (pos? (or locked-count 0))
      {:dot "●" :class "text-success" :label "locked"}
      {:dot "●" :class "text-signal"  :label "denoise"})))

(defn- state-banner
  "The surface masthead: `● <state>` + provider · N steps · step k/N ·
   rounds j/b · wall so far · ctx tokens · server sha (short) —
   whichever of those the rows carry."
  [db id steps]
  (let [row    (last steps)
        {:keys [dot class label]} (fsm-state row)
        prov   (some-> (resolved-provider db id) name)
        n      (count steps)
        kmax   (:seon.typeahead/max-rounds row)
        ru     (:seon.typeahead/rounds-used row)
        rb     (:seon.typeahead/round-budget row)
        wall   (reduce + 0.0 (keep :seon.typeahead/gen-s steps))
        ptoks  (some :seon.typeahead/prompt-tokens steps)
        sha    (some :seon.typeahead/worker-sha steps)
        locked (reduce + 0 (keep :seon.typeahead/locked-count steps))]
    [:div {:class "flex items-center justify-between gap-2 flex-wrap"}
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-text-500 text-2xs uppercase tracking-wider"}
       "typeahead steps"]
      [:span {:class (str class " text-xs font-mono")} (str dot " " label)]]
     [:span {:class "text-text-500 text-2xs font-mono"}
      (str/join " · "
                (cond-> []
                  prov         (conj prov)
                  true         (conj (str n " step" (when (not= 1 n) "s")))
                  kmax         (conj (str "step "
                                          (inc (or (:seon.typeahead/step-idx row) 0))
                                          "/" kmax))
                  (and ru rb)  (conj (str "rounds " ru "/" rb))
                  (pos? wall)  (conj (str (.toFixed wall 1) "s"))
                  true         (conj (str "⊢ " locked))
                  ptoks        (conj (str "ctx ~" ptoks " tok"))
                  sha          (conj (subs sha 0 (min 8 (count sha))))))]]))

;; ------------------------------------------------------------
;; 2. THE CODE-BUFFER PANE — the centerpiece: buffer text painted by
;;    span status, with a legend so the encoding never needs recall.
;; ------------------------------------------------------------

(def ^:private span-style
  "Buffer span status → inline style (Phosphor palette). locked = paid
   and immutable (dim); clamped = hard guarantee (amber underline);
   settled = fresh clean text (bright cream); resolving = still noise
   (dotted); repaired = oracle-rejected (warm red); frontier = the
   cursor (amber block)."
  {:locked    "color:#6b6459"
   :clamped   "color:#d4d0c8;border-bottom:1px solid #f0b429"
   :settled   "color:#faf9f7"
   :resolving "color:#8c8578;border-bottom:1px dotted #6b6459"
   :repaired  "color:#f87171;background:rgba(248,113,113,.08)"
   :frontier  "color:#f0b429"})

(defn- buffer-legend
  "One line decoding the span colors — rendered under the pane so the
   owner never has to remember the encoding."
  []
  (into [:div {:class "flex items-center gap-3 text-2xs font-mono text-text-500 flex-wrap"}]
        (map (fn [[st label]]
               [:span
                [:span {:style (span-style st)}
                 (if (= st :frontier) "▌" "▪▪")]
                (str " " label)])
             [[:locked "locked"] [:clamped "clamped"] [:settled "settled"]
              [:resolving "resolving"] [:repaired "repaired"]
              [:frontier "frontier"]])))

(defn- buffer-pane
  "The current code buffer, monospace, span-status colored. Renders from
   the LAST step row's `buffer-preview`/`buffer-spans`; nil when the row
   carries no buffer picture (pre-upgrade rows, empty buffers)."
  [row]
  (let [text  (:seon.typeahead/buffer-preview row)
        spans (read-edn (:seon.typeahead/buffer-spans row))]
    (when (and (string? text) (not (str/blank? text)) (seq spans))
      [:div {:class "flex flex-col gap-1"}
       [:div {:class "text-text-500 text-2xs uppercase tracking-wider"}
        (str "code buffer ~" (tokens/estimate text) " tok"
             (when-let [ct (:seon.typeahead/committed-tokens row)]
               (str " · committed ~" ct " tok")))]
       (into [:div {:class (str "text-xs font-mono whitespace-pre-wrap "
                                "break-words bg-base-900 border "
                                "border-base-700 rounded p-2 leading-tight")}]
             (keep (fn [[a b st]]
                     (if (= st :frontier)
                       [:span {:style (span-style :frontier)} "▌"]
                       (let [n   (count text)
                             seg (subs text (min a n) (min b n))]
                         (when (seq seg)
                           [:span {:style (span-style st)} seg]))))
                   spans))
       (buffer-legend)])))

;; ------------------------------------------------------------
;; 3. Offers panel — per-offer state + calibrated margin vs threshold.
;; ------------------------------------------------------------

(defn- margin-pct
  "A calibrated-nats value mapped onto the mini-bar's 0–100% (the
   measured range: off-menu collapse ≈ −12 … strong fire ≈ +12)."
  [v]
  (-> (* 100 (/ (+ v 12) 24)) (max 0) (min 100) js/Math.round))

(defn- margin-bar
  "The offer's calibrated-lift mini-bar with the auto-offer threshold
   tick. nil when the row carries no calibrated value."
  [cal thr]
  (when (number? cal)
    [:div {:class "relative w-20 h-1.5 bg-base-800 rounded overflow-hidden shrink-0"}
     [:div {:class "h-full"
            :style (str "width:" (margin-pct cal) "%;background:#60a5fa")}]
     (when (number? thr)
       [:div {:class "absolute top-0 h-full"
              :style (str "left:" (margin-pct thr) "%;width:1px;background:#f0b429")}])]))

(defn- offer-state
  "The offer's dot+text outcome cell."
  [{:seon.typeahead/keys [state reason]}]
  (case state
    :fired      [:span {:class "text-success"} "● fired"]
    :suppressed [:span {:class "text-warning"}
                 (str "● suppressed" (when reason (str " (" (name reason) ")")))]
    [:span {:class "text-text-500"} "○ below-margin"]))

(defn- offers-panel
  "Per-offer status rows from the LAST step row's offers EDN — glyph,
   fn label, calibrated-margin bar against the threshold tick, outcome.
   nil when the row carries no offer picture."
  [row]
  (let [offers (read-edn (:seon.typeahead/offers-edn row))
        thr    (:seon.typeahead/auto-offer-margin row)]
    (when (seq offers)
      [:div {:class "flex flex-col gap-0.5"}
       [:div {:class "text-text-500 text-2xs uppercase tracking-wider"}
        (str "offers" (when (number? thr) (str " · fire ≥ " thr)))]
       (into [:div {:class "flex flex-col"}]
             (map (fn [{:seon.typeahead/keys [glyph label cal] :as o}]
                    [:div {:class "flex items-center gap-3 text-xs font-mono py-0.5"}
                     [:span {:class "text-signal w-5"} glyph]
                     [:span {:class "text-text-200 flex-1 truncate"} (or label "—")]
                     (or (margin-bar cal thr)
                         [:span {:class "w-20 shrink-0"}])
                     [:span {:class "text-text-400 w-14 text-right"}
                      (if (number? cal) (str "Δ" cal) "")]
                     (offer-state o)])
                  offers))])))

;; ------------------------------------------------------------
;; 4. Holes panel — the expansion's per-hole convergence picture.
;; ------------------------------------------------------------

(defn- holes-panel
  "Per-hole rows from the latest step row carrying a holes EDN (the
   last EXPAND): worst/mean entropy, CAL-chosen length, accepted?,
   snapped-to-candidate?. nil outside expansions."
  [steps]
  (let [row   (last (filter :seon.typeahead/holes-edn steps))
        holes (read-edn (:seon.typeahead/holes-edn row))]
    (when (seq holes)
      [:div {:class "flex flex-col gap-0.5"}
       [:div {:class "text-text-500 text-2xs uppercase tracking-wider"}
        (str "expand holes"
             (when-let [ru (:seon.typeahead/rounds-used row)]
               (str " · rounds " ru
                    (when-let [rb (:seon.typeahead/round-budget row)]
                      (str "/" rb)))))]
       (into [:div {:class "flex flex-col"}]
             (map-indexed
               (fn [i {:seon.typeahead/keys [worst mean accepted round
                                             chosen-length snapped]}]
                 [:div {:class "flex items-center gap-3 text-xs font-mono py-0.5"}
                  [:span {:class "text-text-500 w-6 text-right"} (str "#" (inc i))]
                  [:span {:class "text-text-400 w-20"}
                   (if (number? worst) (str "H " worst) "H —")]
                  [:span {:class "text-text-500 w-20"}
                   (if (number? mean) (str "mean " mean) "")]
                  [:span {:class "text-text-400 w-16"}
                   (if chosen-length (str "len " chosen-length) "")]
                  (if accepted
                    [:span {:class "text-success w-24"}
                     (str "✓ settled" (when (number? round) (str " r" round)))]
                    [:span {:class "text-warning w-24"} "○ unsettled"])
                  (when snapped
                    [:span {:class "text-info"} "snap→candidate"])])
               holes))])))

;; ------------------------------------------------------------
;; 5. Done-ness strip — the honest EOS signal + harvest totals.
;; ------------------------------------------------------------

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

(defn- done-strip
  "EOS-logprob labeled meter + locked-forms / harvested-token totals
   for the call. nil when the last row has no EOS readout."
  [steps]
  (let [{:seon.typeahead/keys [eos-logprob committed-tokens]} (last steps)
        locked (reduce + 0 (keep :seon.typeahead/locked-count steps))]
    (when (number? eos-logprob)
      [:div {:class "flex items-center gap-3 text-xs font-mono"}
       [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "eos"]
       (eos-meter eos-logprob)
       [:span {:class "text-text-400"}
        (str "⊢ " locked " form" (when (not= 1 locked) "s")
             (when committed-tokens
               (str " · ~" committed-tokens " tok harvested")))]])))

;; ------------------------------------------------------------
;; 6. Step history — the compact per-step rows (capped).
;; ------------------------------------------------------------

(def ^:private history-cap
  "Most recent step rows shown in the history strip."
  12)

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

(defn- step-history
  "The capped compact step rows — the last [[history-cap]] steps."
  [steps]
  (into [:div {:class "flex flex-col"}]
        (map step-row (take-last history-cap steps))))

(defn- draft-line
  "Fallback draft preview from the LAST step row — only when the row has
   no buffer picture (pre-upgrade rows); sized in TOKENS."
  [steps]
  (let [{:seon.typeahead/keys [draft-preview draft-tokens
                               buffer-preview]} (last steps)]
    (when (and draft-preview (not (str/blank? draft-preview))
               (str/blank? buffer-preview))
      [:div {:class "text-xs font-mono text-text-400 pt-0.5"}
       [:span {:class "text-text-500"}
        (str "draft ~" (or draft-tokens (tokens/estimate draft-preview))
             " tok ")]
       draft-preview])))

(defn steps-surface-html
  "The typeahead canvas — the last provider call, fully legible.

   Composed top to bottom, every panel reactive (vanishes when its rows
   lack the data): the state banner (FSM state now, provider, step k/N,
   rounds j/budget, wall, ctx tokens, server sha), THE CODE-BUFFER PANE
   (span-status-painted buffer text + legend), the offers panel
   (fired/suppressed/below-margin + margin bars vs the threshold), the
   holes panel (per-hole entropy/length/accepted after an EXPAND), the
   done-ness strip (EOS meter + harvest totals) and the compact step
   history. nil when the agent has no step rows (reactive-context)."
  {:malli/schema [:=> [:cat :seon.render/section-request]
                  [:maybe :seon.render.canvas/hiccup]]}
  [{db :seon.db/db id :seon.agent/id}]
  (let [db    (or db (some-> db/*conn* deref))
        steps (last-call-steps db id)]
    (when (seq steps)
      (let [row (last steps)]
        (into [:div {:class "flex flex-col gap-1.5"}]
              (keep identity
                    [(state-banner db id steps)
                     (buffer-pane row)
                     (offers-panel row)
                     (holes-panel steps)
                     (done-strip steps)
                     (step-history steps)
                     (draft-line steps)]))))))
