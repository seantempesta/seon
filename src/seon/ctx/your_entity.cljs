(ns seon.ctx.your-entity
  "The `:your-entity` context section — the agent's OWN entity rendered
   as a pretty-printed map (purpose, tile wiring, sections, self-notes).
   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.your-entity/your-entity-section`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`. Uses the spine's section
   decoder (`seon.ctx/decode-section`) and the db edn-value bridge."
  (:require
    [clojure.string :as str]
    [cljs.pprint :as pprint]
    [seon.ctx :as ctx]
    [seon.db :as db]))

(defn your-entity-section
  "The agent's OWN entity as a pretty-printed MAP: purpose, tile wiring,
   registered sections, lifecycle attrs, and any self-instructions the
   agent has written to itself. Identity is data you look at, and editing
   it is a transact to the map you are looking at (the startup evals
   demonstrate the lookup-ref move).

   Renders the agent's own entity (the composer injects it as
   `:seon.agent/entity`; this section also resolves it from the db by
   `:seon.agent/id` when absent, so it never silently vanishes from the
   prompt) with the render slots and ctx sections bridge-decoded — what
   you see is what a `seon.db/pull` returns. Show-don't-tell applied to
   identity."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id] ctx-entity :seon.agent/entity}]
  (let [entity
        (or ctx-entity
            ;; DEFENSIVE: the composer's PROMPT path does not always inject
            ;; :seon.agent/entity (the inspector path does), so resolve it
            ;; from the db by id here too — a nil entity would silently
            ;; return "" and drop this section from the agent's prompt, yet
            ;; the agent must always see its own entity ("this map IS you").
            ;; Same pull shape `context-root` uses; the pull guard filters
            ;; registered-but-uninstalled attrs.
            (when (and db id)
              (db/pull {:seon.db/db db
                        :seon.db/pull-pattern
                        '[:db/id :seon.agent/id
                          :seon.agent/purpose
                          :seon.agent/default-turn-limit
                          :seon.agent/default-deadline-ms
                          :seon.render/ai :seon.render/html
                          :seon.render.live-tile/content
                          {:seon.agent/sections [*]}]
                        :seon.db/ref [:seon.agent/id id]})))]
    (if (nil? entity)
      ""
      (let [decoded (cond-> (ctx/decode-section (into {} entity))
                      (seq (:seon.agent/sections entity))
                      (assoc :seon.agent/sections
                             (mapv ctx/decode-section (:seon.agent/sections entity)))
                      (contains? entity :seon.render.live-tile/content)
                      (update :seon.render.live-tile/content
                              #(db/decode-edn-value
                                 :seon.render.live-tile/content %)))]
        (str "; YOUR OWN ENTITY in the shared store, re-pulled every turn —\n"
             "; the value of:\n"
             ";   (seon.db/pull '[*] [:seon.agent/id \"" id "\"])\n"
             "; Transact to it by lookup ref — e.g.\n"
             ";   (seon.db/transact! [{:seon.db/ref [:seon.agent/id \"" id "\"]\n"
             ";                        :seon.agent/purpose \"...\"}])\n"
             "; — and the change appears here next turn. Write notes and\n"
             "; standing instructions to yourself here; this map IS you.\n"
             ;; Derive-your-purpose teaching — CONTEXT, not stored data:
             ;; the welcome tile renders :seon.agent/purpose verbatim to
             ;; the human, so the instruction lives here and only while the
             ;; attr is absent (self-healing: the agent's own transact makes
             ;; this line vanish).
             (when (nil? (:seon.agent/purpose entity))
               (str "; Your :seon.agent/purpose is UNSET. Derive it from your\n"
                    "; human's first messages, then transact it onto your own\n"
                    "; entity (the lookup-ref move above) so you keep your\n"
                    "; direction — your human sees it as your tile's headline.\n"))
             ;; The pulled map rides as `;` comment lines (via
             ;; [[seon.ctx/quote-lines]]) so the whole section reads as
             ;; eval'able Clojure (the context IS one live REPL). It is a
             ;; VALUE you read, not a form to run.
             (ctx/quote-lines
               (str/trimr (with-out-str (pprint/pprint decoded)))))))))
