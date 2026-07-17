(ns seon.agent.ctx.canvas
  "The `:canvas` context section — \"what your human currently sees\",
   rendered as a `;; ── canvas ──` comment-block. Symbol-wired into the
   composer layout (`config manifest`) as
   `'seon.agent.ctx.canvas/canvas-block`; loaded at boot so the symbol
   resolves for `seon.eval/lookup-value`.

   The agent sees the SAME wired value the human's surfaces render —
   derived every turn, nothing stored (reactive-context doctrine), so the
   agent can never believe its canvas is blank when the human sees content.
   Self-contained: no spine read API, just the canvas renderer +
   wired-content provenance."
  (:require
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.render-fns :as render-fns]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.error :as err]
    [seon.render.canvas :as canvas]))

(def ^:private candidate-query
  '[:find ?sym ?spec ?source-tx ?private
    (pull ?fn [:seon.fn/read-attrs])
    :in $ ?agent-id
    :where
    [?fn :seon.fn/source _ ?source-tx]
    [?source-tx :seon.db/user ?author]
    [?author :seon.agent/id ?agent-id]
    [?source-tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]
    [?fn :seon.fn/sym ?sym]
    [?fn :seon.fn/spec ?spec]
    [(get-else $ ?fn :seon.fn/private? false) ?private]])

(def ^:private history-query
  '[:find ?attr (max ?tx)
    :in $ ?agent-id [?attr ...]
    :where
    [?entity ?attr _ ?tx]
    [?tx :seon.db/user ?author]
    [?author :seon.agent/id ?agent-id]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(def ^:private unwatched-attrs
  "Render outputs and transaction plumbing do not select a canvas."
  #{:seon.render/ai
    :seon.render/hiccup
    :db/txInstant
    :seon.db/user
    :seon.db/process
    :seon.db.protocol/request-id})

(defn- candidate-member
  [id]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form candidate-query
   ::protocol/arguments [id]
   :datahike.resource/max-work 2000000
   :datahike.resource/max-results 32768
   :datahike.resource/max-result-weight 1048576})

(defn- history-member
  [id attrs]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form history-query
   ::protocol/arguments [id (vec attrs)]
   ::protocol/history? true
   :datahike.resource/max-work 4000000
   :datahike.resource/max-results 65536
   :datahike.resource/max-result-weight 1048576})

(defn- member-result
  [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- acquisition-error
  [stage value]
  {:seon.error/message (str "Canvas " stage " failed.")
   :seon.error/data value
   :seon.error/kind :core-bug})

(defn- discovery-state
  "Canvas resolution facts already carried by prompt discovery."
  [agent]
  (let [configured
        (some (fn [block]
                (when (= :canvas (:seon.agent.ctx/name block))
                  (let [value (some->>
                                (:seon.render.canvas/content block)
                                (db/decode-edn-value ::canvas/content))]
                    (when (and (some? value) (not= :none value)) value))))
              (:seon.agent/ctx agent))]
    (cond-> {:seon.render/entity (dissoc agent :seon.agent/ctx)}
      (some? configured) (assoc ::canvas/configured configured))))

(defn- candidate-rows
  [rows]
  (->> rows
       (keep
         (fn [[sym spec source-tx private? pulled]]
           (when (and (not private?)
                      (contains? (render-fns/output-twin-keys spec)
                                 :seon.render/hiccup))
             {::surface-sym (symbol sym)
              ::source-tx source-tx
              ::attrs (->> (:seon.fn/read-attrs pulled)
                           (remove unwatched-attrs)
                           distinct
                           vec)})))
       vec))

(defn- selected-surface
  [rows attr-txs]
  (->> rows
       (map (fn [{::keys [surface-sym source-tx attrs]}]
              {::surface-sym surface-sym
               ::touch (reduce max source-tx (keep attr-txs attrs))}))
       (sort-by (juxt ::touch (comp str ::surface-sym)))
       last
       ::surface-sym))

(defn ^:async ^:private acquire-canvas!
  "Acquire the canvas identity from ordinary discovery data at one database value."
  [id agent database]
  (let [{entity :seon.render/entity
         :as state} (discovery-state agent)
        base-wired (canvas/wired-content state)]
    (cond
      (nil? (:seon.agent/id entity))
      {:seon.render/entity entity}

      (not= ::canvas/welcome (::canvas/source base-wired))
      {:seon.render/entity entity ::canvas/wired base-wired}

      :else
      (let [candidates-response
            (await (db/execute-many
                     {::db/db database
                      ::db/members [(candidate-member id)]
                      ::db/max-result-weight 1179648}))]
        (if (:seon.error/message candidates-response)
          (acquisition-error "candidate acquisition" candidates-response)
          (let [member (first (::db/results candidates-response))]
            (if-not (true? (::protocol/success? member))
              (acquisition-error "candidate member" member)
              (let [rows (candidate-rows (member-result member))
                    attrs (into #{} (mapcat ::attrs) rows)
                    history-response
                    (when (seq attrs)
                      (await (db/execute-many
                               {::db/db database
                                ::db/members [(history-member id attrs)]
                                ::db/max-result-weight 1179648})))
                    attr-txs
                    (cond
                      (empty? attrs) {}
                      (:seon.error/message history-response) nil
                      :else
                      (let [history-member-result
                            (first (::db/results history-response))]
                        (when (true? (::protocol/success?
                                      history-member-result))
                          (into {} (member-result history-member-result)))))]
                (if (nil? attr-txs)
                  (acquisition-error "history acquisition" history-response)
                  (let [derived (selected-surface rows attr-txs)
                        wired (canvas/wired-content
                                (cond-> state
                                  derived (assoc ::canvas/derived derived)))]
                    {:seon.render/entity entity
                     ::canvas/wired wired}))))))))))

(defn- clip-marker
  "A loud, token-denominated cut marker for one canvas-context value."
  [what budget total]
  (str "\n…⟨" what " clipped at " budget " of " total
       " tokens — narrow the canvas render⟩"))

(defn- rendered-canvas-text
  "Format one acquired canvas response from ordinary values only."
  [{:seon.render/keys [hiccup ai error]
    wired :seon.render.canvas/wired}
   source cap]
  (let [body-kind (cond
                    (some? error)  :error
                    (some? ai)     :ai
                    (some? hiccup) :hiccup)
        body (cond
               (some? error)
               (str "Render failed; your human sees the fallback card.\n"
                    "Fix the renderer or pin a working canvas. Cause: "
                    (:seon.error/message error) "\n"
                    (pr-str (select-keys error [:seon.error/data
                                                :seon.error/ex-data]))
                    (when (some? ai) (str "\n" ai)))

               (some? ai)     ai
               (some? hiccup) (pr-str hiccup))
        body (when (some? body)
               (tokens/clip-str body cap (partial clip-marker "canvas twin")))]
    (if (nil? body)
      ""
      (let [body-comment (ctx/quote-lines body)
            wired-value (:seon.render.canvas/value wired)
            fn-src (when (and (err/agent-authored-sym? wired-value)
                              (some? source))
                     (tokens/clip-str
                       source cap (partial clip-marker "canvas source")))
            body-label (case body-kind
                         :error "Render status:"
                         :ai "Rendered meaning (:seon.render/ai; paired HTML is on screen):"
                         :hiccup "Rendered Hiccup (exact human view):"
                         "Rendered output:")]
        (str "; CANVAS — current human-facing view\n"
             "; Renderer: " (canvas/wired-label wired) "\n"
             "; Snapshot: this prompt; the browser refreshes after relevant transactions.\n"
             "; " body-label "\n"
             body-comment "\n"
             (when (some? fn-src)
               (str ";\n"
                    "; Agent-authored renderer source:\n"
                    (ctx/quote-lines fn-src) "\n"))
             ";\n"
             "; Change: (my.canvas/show! {:my.canvas/content <hiccup-or-qualified-fn>})\n"
             "; Live fn: ordinary :seon.render/system-input → my.canvas/view; use seon.db functions for reads.\n"
             "; Actions: my.canvas controls call schema'd home-ns handlers; writes redraw.\n"
             "; Inspect/auto: (my.canvas/pinned {}) / (my.canvas/clear! {}).")))))

(defn- selected-canvas-response
  [wired result]
  (let [value (::canvas/value wired)
        response (:seon.execution/value result)]
    (cond
      (not (:seon.execution/ok? result))
      (assoc (canvas/error-response
               (assoc (:seon.execution/error result) ::canvas/content value))
             ::canvas/wired wired)

      (nil? response)
      {::canvas/wired wired}

      (map? response)
      (assoc response ::canvas/wired wired)

      :else
      (assoc (canvas/error-response
               {:seon.error/message
                "A canvas function must return a render response map."
                :seon.error/kind :agent
                :seon.error/data {:seon.render.canvas/content value}
                ::canvas/content value})
             ::canvas/wired wired))))

(defn ^:async canvas-block
  "Show and explain the agent's current live canvas.

   The `:canvas` section — what your human currently sees and the compact
   operational contract for changing it. Agent-authored twins and source are
   independently bounded by the one render-fn token cap; a canvas cannot
   consume an unbounded share of every later turn.

   Acquires the wired canvas at this turn's exact database value and
   invokes a selected renderer inside the execution child. Renderer input is
   ordinary data; database work uses the asynchronous `seon.db` API rather
   than receiving a Datahike value.

     header — the wired identity (`seon.render.canvas/wired-label`:
              fn name, or \"literal hiccup on your entity\") so the
              agent always sees HOW to change the display;
     body   — the `:seon.render/ai` twin for fns; the literal hiccup
              VERBATIM for static values (\"you see exactly what's
              wired\" — a fn that omits the twin gets its hiccup
              verbatim too, which is itself the nudge to add one);
              the `:seon.error/*` envelope when the renderer THROWS
              (a broken canvas must never silently vanish — vanish is
              indistinguishable from unwired, banned).

   PER-TURN SEMANTICS (correct BY DESIGN — do not \"fix\" with stored
   presentation state or mid-turn refreshes): the body is as-of the
   db value this prompt was assembled from. The human's canvas
   live-updates per relevant tx, so between turns the human may
   briefly see FRESHER data than this twin; the next turn's section
   re-derives from the then-current db.

   Renders nothing only when no canvas resolves at all (agent entity
   missing) — every created agent is welcome-wired, so in practice
   the section is always present; the unwired branch is the
   correctness floor."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [{:seon.agent/keys [id entity] :as input} invoke-selected!]
  (try
    (let [database (or (::db/db input)
                       (::db/db (db/current-tx-context))
                       (await (db/db)))
          _ (when (:seon.error/message database)
              (throw (ex-info (:seon.error/message database) database)))
          {wired ::canvas/wired :as acquired}
          (await (acquire-canvas! id entity database))
          value (::canvas/value wired)
          result (when (symbol? value)
                   (first
                     (await
                       (invoke-selected!
                         [{:seon.execution/function-symbol value
                           :seon.execution/arguments
                           [{:seon.agent/id id
                             :seon.render/entity
                             (:seon.render/entity acquired)}]}]))))
          response (cond
                     (nil? wired) {:seon.render/hiccup nil}
                     (symbol? value) (selected-canvas-response wired result)
                     :else {:seon.render/hiccup value
                            ::canvas/wired wired})
          source (when (and result (err/agent-authored-sym? value))
                   (:seon.execution/source result))]
      {:seon.render/ai (rendered-canvas-text response source 2000)
       ::render-fns/pinned-syms
       (cond-> #{} (symbol? value) (conj value))})
    ;; CONTRACT: this section NEVER vanishes and NEVER surfaces a bare
    ;; ⚠/malli code. `render-agent-canvas` is already throw-safe, so this
    ;; backstop only fires on an UNEXPECTED failure (e.g. a db read) —
    ;; and even then the agent reads a clear, actionable safe-state, not
    ;; a swallowed error keyword. Self-heals on the next clean render.
    (catch :default e
      (str "; Your canvas — loading (safe-state placeholder this turn).\n"
           "; The per-turn canvas derivation hit an unexpected error and\n"
           "; degraded gracefully; your human sees the calm core welcome\n"
           "; card, never a broken panel. This is a transient render\n"
           "; hiccup that self-heals next turn.\n"
           ";\n"
           "; Diagnostic: " (ex-message e) "\n"
           ";\n"
           "; To (re)wire your canvas, transact a qualified fn symbol or\n"
           "; literal hiccup onto :seon.render.canvas/content on your\n"
           "; agent entity."))))
