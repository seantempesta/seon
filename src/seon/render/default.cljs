(ns seon.render.default
  "Default renderers + shared DB-read helpers for the render surface.

   What lives here:

   - `pretty-ai` / `pretty-html` — the two universal floors `seon.render`
     falls back to so the render mechanism never crashes (A-2 contract:
     missing renderer → pretty-print, never an exception).
   - `view` — the default `:seon.render/html` agent surface (status dot,
     turn count, error banner, recent messages). Agents repoint their
     surface by transacting a different symbol onto the slot.
   - Read helpers (`recent-messages`, `recent-errors`) used by `view`
     and the web UI.

   This namespace renders the surface and reads the message log; it does
   NOT compose the agent's prompt (that is `seon.agent.ctx`'s job).

   ## Independent of seon.agent

   This namespace queries the DB directly via `seon.db` — it does NOT
   require `seon.agent`. That keeps the dependency graph acyclic:
   seon.agent → seon.render → seon.render.default is the one-way
   arrow; we do not close it."
  (:require
    [seon.agent.message :as message]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.log :as log]
    [seon.ui.components :as comp]))

;; ============================================================
;; Pretty-print floors — universal fallbacks for both surfaces.
;; A-2 contract: render mechanism never crashes; missing → pretty-print.
;; ============================================================

(defn pretty-ai
  "Universal AI-side fallback. Emits the input map as edn."
  {:malli/schema [:=> [:cat :seon.render/section-request] :seon.render/ai-response]}
  [input]
  {:seon.render/ai (pr-str input)})

(defn pretty-html
  "Universal HTML-side fallback — wraps an edn dump in monospace.

   The container ensures the user at least sees the data structure."
  {:malli/schema [:=> [:cat :seon.render/section-request] :seon.render/html-response]}
  [input]
  {:seon.render/hiccup
   [:pre {:class "p-3 text-xs font-mono bg-base-900 text-text-200 overflow-auto"}
    (pr-str input)]})

(defn pending-html
  "Calm IN-PROGRESS placeholder for a canvas still loading.

   Its content symbol
   names an agent-authored render fn that ISN'T loaded in the runtime
   right now (`seon.eval/lookup-value` returned nil). Mirrors the
   `seon.render.canvas/welcome` card shape (compact + expanded,
   muted text) so the human sees \"preparing this view…\", NOT an error
   dump of the render-context map. Self-heals: the moment the fn is
   (re)defined the symbol resolves and the real surface renders again."
  {:malli/schema [:=> [:cat :symbol] :seon.render/html-response]}
  [sym]
  {:seon.render/hiccup
   [:div {:class "seon-card"}
    [:div {:class "seon-card-compact flex flex-col gap-1 p-3"}
     [:div {:class "text-sm text-text-400 italic"} "Preparing this view…"]
     [:div {:class "text-[10px] font-mono text-text-500"} (str sym)]]
    [:div {:class "seon-card-expanded flex flex-col gap-3 p-4"}
     [:div {:class "text-sm text-text-400 italic"} "Preparing this view…"]
     [:div {:class "text-xs text-text-500"}
      "This panel points at a render fn that isn't loaded yet."]
     [:div {:class "text-[10px] font-mono text-text-500"} (str sym)]]]
   :seon.render/ai
   (str "Your canvas points at " sym ", but that fn isn't loaded in "
        "the runtime right now — so the human sees a calm "
        "\"preparing this view…\" placeholder instead of your view. "
        "(Re)define the fn (eval its defn) so the symbol resolves, or "
        "point :seon.render.canvas/content at a fn that exists "
        "(or at literal hiccup).")})

;; ============================================================
;; DB query helpers — used by `view` and the web UI.
;; All synchronous; reads resolve against the input map's `:seon.db/db`
;; when present, else fall back to `@seon.db/*conn*`.
;; ============================================================


(defn ^:no-doc recent-messages
  "Return the most-recent `n` messages of agent `id`'s conversation.

   DERIVED (from = me OR to ∋ me — nothing stored per-agent),
   oldest-first. Each row is `[at label content]`; the label resolves
   by DIRECTION (from-ref kind × to-ref kinds):

   - `user`           — human → agent
   - `assistant`      — this agent → the user (a real reply: to ∋ user)
   - `agent-<id>`     — another agent → me (incoming peer)
   - `→ agent-<id>`   — this agent → a peer (outgoing peer)

   Rows from me whose `to` contains NEITHER the user NOR another agent
   (i.e. agent → self) are not conversation and are EXCLUDED here."
  {:malli/schema [:function
                  [:=> [:cat :any :string] :any]
                  [:=> [:cat :any :string :int] :any]]}
  ([db id] (recent-messages db id 20))
  ([db id n]
   (let [db (or db @db/*conn*)]
     (if (pos? n)
       (->> (message/recent
              {:seon.db/db db
               :seon.agent/id id
               :seon.agent.message/recent-limit (min 200 n)})
            (keep (fn [{at :seon.agent.message/at
                      f :seon.agent.message/from
                      to :seon.agent.message/to
                      content :seon.agent.message/content}]
                  (let [from-user? (some? (:seon.user/id f))
                        from-agent-id (:seon.agent/id f)
                        label (cond
                                from-user?
                                "user"

                                (= from-agent-id id)
                                (cond
                                  ;; a real reply — to ∋ the user
                                  (some :seon.user/id to)
                                  "assistant"
                                  ;; outgoing peer send — to ∋ another agent
                                  (some #(and (:seon.agent/id %)
                                              (not= id (:seon.agent/id %))) to)
                                  (str "→ agent-"
                                       (some (fn [t]
                                               (when (and (:seon.agent/id t)
                                                          (not= id (:seon.agent/id t)))
                                                 (:seon.agent/id t)))
                                             to))
                                  ;; agent → self: transcript narration, not
                                  ;; conversation — exclude (nil → keep drops).
                                  :else nil)

                                from-agent-id
                                (str "agent-" from-agent-id)

                                :else "unknown")]
                    (when label [at label content]))))
            vec)
       []))))

(defn ^:no-doc recent-errors
  "Return the most-recent `n` `:error` log entries for agent `id`.

   Newest-first, reading the active `seon.log` file
   (NOT the DB — log entries are no longer persisted as datoms; see
   seon.log ns docstring). Returns `()` when none."
  {:malli/schema [:function
                  [:=> [:cat :any :string] :any]
                  [:=> [:cat :any :string :int] :any]]}
  ([_db id] (recent-errors _db id 10))
  ([_db id n]
   (log/tail {:seon.log/n     n
              :seon.log/level :error
              :seon.log/agent id})))

;; Turn-count + derived-state are the [[seon.derive]] leaf — `view` (and the
;; web UI) call `seon.derive/agent-turn-count` / `seon.derive/derive-state`
;; with the db value they hold. They were duplicated here only to dodge the
;; seon.agent require cycle; the armable-agent query lives once in
;; `seon.derive/armable-agent-ids` (state = :idle).

;; ============================================================
;; View — the default :seon.render/html agent dashboard:
;; status dot + agent id + turn count + error banner + recent msgs.
;; Phosphor Terminal palette via seon.ui.components.
;; ============================================================

(defn view
  "Default `:seon.render/html` renderer for an agent surface.

   System fn → takes system input
   shape (`:seon.db/db` + `:seon.agent/id`). Pulls the entity, renders
   a surface with status, turn count, recent-errors banner, last 5 messages.
   Returns `{:seon.render/hiccup [...]}`."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [state (derive/derive-state db id)
        turns (derive/agent-turn-count db id)
        msgs  (recent-messages db id 5)
        errs  (recent-errors db id 5)]
    {:seon.render/hiccup
     [:div {:class "h-full flex flex-col p-3 gap-2 bg-base-900 rounded"
            :id (str "agent-" id)}
      [:header {:class "flex items-center gap-2"}
       (comp/status-dot state id)
       [:span {:class "text-xs text-text-400 ml-auto"} (str "turn " turns)]]
      ;; NOTE: children are built with `into` (vectors), NOT bare
      ;; `(for …)` lazy seqs — `seon.render.canvas/valid-hiccup?`
      ;; (the render-boundary validator) accepts only
      ;; string/int/nil/vector children, so a lazy-seq child makes
      ;; instrumentation reject the whole surface.
      (when (seq errs)
        (into [:section {:class "flex flex-col gap-1 border border-error/40 bg-error/10 rounded p-2"}]
              (map (fn [e]
                     [:div {:class "flex items-start gap-2 text-xs"}
                      [:span {:class "text-error font-bold"} "⚠"]
                      [:span {:class "flex-1 text-error font-mono"}
                       (str (:seon.log/message e))]]))
              errs))
      (if (seq msgs)
        (into [:section {:class "flex-1 overflow-auto text-xs font-mono"}]
              (map (fn [[_at label content]]
                     [:div {:class "py-0.5"}
                      [:span {:class "text-text-400"} (str label ": ")]
                      [:span {:class "text-text-100"} content]]))
              msgs)
        [:section {:class "flex-1 overflow-auto text-xs font-mono"}
         [:div {:class "text-text-500 italic"} "no messages yet"]])]}))
