(ns seon.render.default
  "Default renderers + shared DB-read helpers for the render surface.

   What lives here:

   - `pretty-ai` / `pretty-html` — the two universal floors `seon.render`
     falls back to so the render mechanism never crashes (A-2 contract:
     missing renderer → pretty-print, never an exception).
   - `view` — the default `:seon.render/html` agent tile (status dot,
     turn count, error banner, recent messages). Agents repoint their
     tile by transacting a different symbol onto the slot.
   - Read helpers (`recent-messages`, `recent-errors`) used by `view`
     and the inspector.

   This namespace renders the tile and reads the message log; it does
   NOT compose the agent's prompt (that is `seon.ctx`'s job).

   ## Independent of seon.agent

   This namespace queries the DB directly via `seon.db` — it does NOT
   require `seon.agent`. That keeps the dependency graph acyclic:
   seon.agent → seon.render → seon.render.default is the one-way
   arrow; we do not close it."
  (:require
    [seon.db :as db]
    [seon.log :as log]
    [seon.ui.components :as comp]))

;; ============================================================
;; Pretty-print floors — universal fallbacks for both surfaces.
;; A-2 contract: render mechanism never crashes; missing → pretty-print.
;; ============================================================

(defn pretty-ai
  "Universal AI-side fallback. Emits the input map as edn."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [input]
  {:seon.render/ai (pr-str input)})

(defn pretty-html
  "Universal HTML-side fallback. Wraps an edn dump in a monospace
   container so the user at least sees the data structure."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [input]
  {:seon.render/hiccup
   [:pre {:class "p-3 text-xs font-mono bg-base-900 text-text-200 overflow-auto"}
    (pr-str input)]})

(defn pending-html
  "Calm IN-PROGRESS placeholder for a live tile whose content symbol
   names an agent-authored render fn that ISN'T loaded in the runtime
   right now (`seon.eval/lookup-value` returned nil). Mirrors the
   `seon.render.live-tile/welcome` tile shape (compact + expanded,
   muted text) so the human sees \"preparing this view…\", NOT an error
   dump of the render-context map. Self-heals: the moment the fn is
   (re)defined the symbol resolves and the real tile renders again."
  {:malli/schema [:=> [:cat :symbol] :seon.render/html-response]}
  [sym]
  {:seon.render/hiccup
   [:div {:class "seon-tile"}
    [:div {:class "seon-tile-compact flex flex-col gap-1 p-3"}
     [:div {:class "text-sm text-text-400 italic"} "Preparing this view…"]
     [:div {:class "text-[10px] font-mono text-text-500"} (str sym)]]
    [:div {:class "seon-tile-expanded flex flex-col gap-3 p-4"}
     [:div {:class "text-sm text-text-400 italic"} "Preparing this view…"]
     [:div {:class "text-xs text-text-500"}
      "This panel points at a render fn that isn't loaded yet."]
     [:div {:class "text-[10px] font-mono text-text-500"} (str sym)]]]
   :seon.render/ai
   (str "Your live tile points at " sym ", but that fn isn't loaded in "
        "the runtime right now — so the human sees a calm "
        "\"preparing this view…\" placeholder instead of your view. "
        "(Re)define the fn (eval its defn) so the symbol resolves, or "
        "point :seon.render.live-tile/content at a fn that exists "
        "(or at literal hiccup).")})

;; ============================================================
;; DB query helpers — used by `view` and the inspector.
;; All synchronous; reads resolve against the input map's `:seon.db/db`
;; when present, else fall back to `@seon.db/*conn*`.
;; ============================================================

(defn- pulled-agent
  "Pull the agent entity for `id`. Returns nil if missing."
  [db id]
  (let [entity (if db
                 (db/entity {:seon.db/db db
                             :seon.db/ref [:seon.agent/id id]})
                 (db/entity {:seon.db/ref [:seon.agent/id id]}))]
    (when (:seon.agent/id entity)
      entity)))

(defn ^:no-doc recent-messages
  "Return the most-recent `n` messages of agent `id`'s conversation
   (DERIVED: from = me OR to ∋ me — nothing stored per-agent),
   oldest-first. Each row is `[at label content]`; the label resolves
   by DIRECTION (from-ref kind × to-ref kinds):

   - `user`           — human → agent
   - `assistant`      — this agent → the user (a real reply: to ∋ user)
   - `agent-<id>`     — another agent → me (incoming peer)
   - `→ agent-<id>`   — this agent → a peer (outgoing peer)

   Rows from me whose `to` contains NEITHER the user NOR another agent
   (i.e. agent → self) are not conversation and are EXCLUDED here."
  ([db id] (recent-messages db id 20))
  ([db id n]
   (let [;; All reads via QUERY, not d/entity — the inspector hands
         ;; `view` a FilteredDB, and datahike-cljs FilteredDB doesn't
         ;; implement -lookup (entity-by-lookup-ref throws); queries
         ;; work fine.
         q      (fn [query & args]
                  (if db
                    (db/query {:seon.db/db db
                               :seon.db/query query
                               :seon.db/args (vec args)})
                    (db/query {:seon.db/query query
                               :seon.db/args (vec args)})))
         my-eid (ffirst (q '[:find ?e :in $ ?id
                             :where [?e :seon.agent/id ?id]]
                           id))
         ;; The from-ref's KIND resolves in Clojure against these two
         ;; eid→id maps, NOT via datalog `get-else` — on datahike-cljs
         ;; get-else's default branch never fires (rows whose ?f lacks
         ;; the attr are DROPPED, not defaulted), which would silently
         ;; filter every agent-from message out of the conversation.
         users  (into {} (q '[:find ?f ?uid :where [?f :seon.user/id ?uid]]))
         agents (into {} (q '[:find ?f ?aid :where [?f :seon.agent/id ?aid]]))
         rows   (when my-eid
                  (q '[:find ?m ?at ?f ?content
                       :in $ ?me
                       :where
                       (or-join [?m ?me]
                         [?m :seon.agent.message/from ?me]
                         [?m :seon.agent.message/to ?me])
                       [?m :seon.agent.message/at ?at]
                       [?m :seon.agent.message/from ?f]
                       [?m :seon.agent.message/content ?content]]
                     my-eid))
         ;; to-refs per message eid — `to` is cardinality-many, so this
         ;; query yields one row per (message, to-ref); fold into sets.
         ;; Direction needs it: from=me alone can't tell a real reply
         ;; (to ∋ user) from an agent → self row (to = [me]) from an
         ;; outgoing peer send (to ∋ other agent).
         tos    (when (seq rows)
                  (reduce (fn [acc [m t]] (update acc m (fnil conj #{}) t))
                          {}
                          (q '[:find ?m ?t
                               :in $ ?me
                               :where
                               (or-join [?m ?me]
                                 [?m :seon.agent.message/from ?me]
                                 [?m :seon.agent.message/to ?me])
                               [?m :seon.agent.message/to ?t]]
                             my-eid)))]
     (->> rows
          (keep (fn [[m at f content]]
                  (let [to    (get tos m #{})
                        label (cond
                                (contains? users f)
                                "user"

                                (= (get agents f) id)
                                (cond
                                  ;; a real reply — to ∋ the user
                                  (some #(contains? users %) to)
                                  "assistant"
                                  ;; outgoing peer send — to ∋ another agent
                                  (some #(and (contains? agents %) (not= % f)) to)
                                  (str "→ agent-"
                                       (some (fn [t]
                                               (when (and (contains? agents t)
                                                          (not= t f))
                                                 (get agents t)))
                                             to))
                                  ;; agent → self: transcript narration, not
                                  ;; conversation — exclude (nil → keep drops).
                                  :else nil)

                                (contains? agents f)
                                (str "agent-" (get agents f))

                                :else "unknown")]
                    (when label [at label content]))))
          (sort-by first)
          (take-last n)))))

(defn ^:no-doc recent-errors
  "Return the most-recent `n` `:seon.log/level :error` entries for
   agent `id`, newest-first. Reads the active `seon.log` file
   (NOT the DB — log entries are no longer persisted as datoms; see
   seon.log ns docstring). Returns `()` when none."
  ([_db id] (recent-errors _db id 10))
  ([_db id n]
   (log/tail {:seon.log/n     n
              :seon.log/level :error
              :seon.log/agent id})))

(defn ^:no-doc agent-turn-count
  "Derived turn count for an agent ENTITY: the number of
   `:seon.agent.session/turns` in its most-recent session (by
   `:seon.agent.session/at`). Derived here rather than requiring
   `seon.agent` (which would close the dependency cycle)."
  [ent]
  (let [session (last (sort-by :seon.agent.session/at (:seon.agent/sessions ent)))]
    (count (:seon.agent.session/turns session))))

;; `all-running-agents` deleted (agent-fsm redesign U1) — the inspector
;; pulls the one agent entity it needs directly; the armable-roster query
;; lives once in `seon.agent/armable-agent-ids` (state ≠ :terminated).

;; ============================================================
;; VIEW — the default :seon.render/html. Agent-tile dashboard:
;; status dot + agent id + turn count + error banner + recent msgs.
;; Phosphor Terminal palette via seon.ui.components.
;; ============================================================

(defn view
  "Default :seon.render/html renderer. System fn → takes system input
   shape (`:seon.db/db` + `:seon.agent/id`). Pulls the entity, renders
   a tile with status, turn count, recent-errors banner, last 5 messages.
   Returns `{:seon.render/hiccup [...]}`."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/html-response]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ent   (pulled-agent db id)
        state (or (:seon.agent/state ent) :unknown)
        turns (agent-turn-count ent)
        msgs  (recent-messages db id 5)
        errs  (recent-errors db id 5)]
    {:seon.render/hiccup
     [:div {:class "h-full flex flex-col p-3 gap-2 bg-base-900 rounded"
            :id (str "agent-" id)}
      [:header {:class "flex items-center gap-2"}
       (comp/status-dot state id)
       [:span {:class "text-xs text-text-400 ml-auto"} (str "turn " turns)]]
      ;; NOTE: children are built with `into` (vectors), NOT bare
      ;; `(for …)` lazy seqs — `seon.render.live-tile/valid-hiccup?`
      ;; (the render-boundary validator) accepts only
      ;; string/int/nil/vector children, so a lazy-seq child makes
      ;; instrumentation reject the whole tile.
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
