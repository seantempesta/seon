(ns seon.render.default
  "Default renderers + shared DB-read helpers for the render surface.

   What lives here:

   - `pretty-ai` / `pretty-html` — the two universal floors `seon.render`
     falls back to so the render mechanism never crashes (A-2 contract:
     missing renderer → pretty-print, never an exception).
   - `view` — the default `:seon.render/html` agent tile (status dot,
     turn count, error banner, recent messages). Agents repoint their
     tile by transacting a different symbol onto the slot.
   - Read helpers (`recent-messages`, `recent-errors`,
     `all-running-agents`) used by `view` and the inspector.

   The agent's PROMPT is NOT composed here: the live default
   `:seon.render/ai` path is `seon.agent/assemble-context`, whose
   section layout is `seon.agent/substrate-default-ctx`. The old `ctx`
   composer and its fragment helpers were deleted 2026-06-09 (unit 1.3)
   after their teaching content was folded into the live sections
   (`seon.agent/system-section`, `seon.agent/prompt-section`).

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
  {:seon.render/text (pr-str input)})

(defn pretty-html
  "Universal HTML-side fallback. Wraps an edn dump in a monospace
   container so the user at least sees the data structure."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [input]
  {:seon.render/hiccup
   [:pre {:class "p-3 text-xs font-mono bg-base-900 text-text-200 overflow-auto"}
    (pr-str input)]})

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
   (i.e. agent → self) are the per-turn transcript SELF-messages — raw
   LLM output logged for the turn record, not conversation — and are
   EXCLUDED here (observed live 2026-06-11, kXQ root tile: a self
   row of raw eval source rendered as the agent's \"last reply\"
   because the pre-fix query ignored `to` entirely)."
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
         ;; the attr are DROPPED, not defaulted), which silently
         ;; filtered every agent-from message out of the conversation
         ;; (observed live 2026-06-11, live-tiles U2: assistant replies
         ;; existed in the store but never rendered).
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
         ;; (to ∋ user) from transcript self-narration (to = [me]) from
         ;; an outgoing peer send (to ∋ other agent).
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
   `:seon.agent.session/at`). The stored `:seon.agent/turn-count` attr was
   retired 2026-05-22 — this mirrors how `seon.agent/prompt-section`
   derives `turn N` (count of current-session turns), without
   requiring `seon.agent` (would close the dependency cycle)."
  [ent]
  (let [session (last (sort-by :seon.agent.session/at (:seon.agent/sessions ent)))]
    (count (:seon.agent.session/turns session))))

(defn ^:no-doc all-running-agents
  "Return every agent entity whose `:seon.agent/state` is `:idle` or
   `:running`. Used by the inspector to iterate live agents. Pure
   read; safe from any thread."
  [db]
  (let [query '[:find ?aid
                :in $
                :where
                [?a :seon.agent/id ?aid]
                [?a :seon.agent/state ?state]
                [(contains? #{:idle :running} ?state)]]
        rows  (if db
                (db/query {:seon.db/db db :seon.db/query query})
                (db/query {:seon.db/query query}))]
    (for [[aid] rows]
      (pulled-agent db aid))))

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
      ;; `(for …)` lazy seqs — `seon.render/valid-hiccup?` (the
      ;; `:seon.render/html-response` validator) accepts only
      ;; string/int/nil/vector children, so a lazy-seq child makes
      ;; instrumentation reject the whole tile (2026-06-09 inspector
      ;; tile-missing bug).
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
