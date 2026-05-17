(ns seon.agent
  "Agent runtime — schemas + ctx-rendering, no magic verbs.

   After spec-03 H-1a, the agent operates as a real REPL: bootstrap-CLJS
   evaluates its forms, results land in a per-agent home namespace
   (`seon.agent.<id>`) as live atom entries, and durable records land
   as `:seon.eval` entities in the database. The agent calls the real
   `seon.db/*` APIs directly — no `say!`/`done!`/`scratch!` wrappers.

   What the agent uses to talk to the system:
     - `seon.db/transact!` — write
     - `seon.db/query` / `pull` / `entity` — read
     - `seon.db/listen!` / `seon.trigger/register!` (H-4) — react
     - its own home-ns atoms (`!session-id`, `!results`, `!current-ns`)
       set up at boot by `seon.eval/setup-agent-ns!`

   Everything above is shown to the agent as worked examples in
   `build-ctx`, formatted the same way the agent's own response is
   parsed. The agent learns by mimicking what it sees.

   This namespace owns:
     - `new-id!` — base62 10-char id generator (eval-ids, message-ids)
     - the `:seon.session/*`, `:seon.message/*`, `:seon.eval/*` schemas
     - `build-ctx` — the rendered prompt the LLM sees each turn"
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; ID generator — 10-char base62, time-prefixed so ids sort by
;; creation. Used for eval-ids, message-ids, trigger-ids, etc.
;; ============================================================

(def ^:private alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")

(defn- to-base62 [n width]
  (let [s (loop [n n out ()]
            (if (zero? n)
              (apply str out)
              (recur (quot n 62) (cons (nth alphabet (mod n 62)) out))))]
    (str (apply str (repeat (max 0 (- width (count s))) \A)) s)))

(defn new-id!
  "10-char base62 id. 4 chars time-prefix + 6 random. 62^6 random
   space per millisecond — collision-safe at any sane rate."
  []
  (let [now-mod    (mod (.now js/Date) (Math/pow 62 4))
        time-prefix (to-base62 (Math/floor now-mod) 4)
        rand-part   (apply str (repeatedly 6 #(rand-nth alphabet)))]
    (str time-prefix rand-part)))

;; ============================================================
;; Schemas — every shape the agent reads or writes.
;; ============================================================

;; Session — one entity per agent.
(schema/register! :seon.session/id              :string)
(schema/register! :seon.session/agent-ns        :symbol)
(schema/register! :seon.session/agent-loop-state [:enum :idle :running])
(schema/register! :seon.session/tick-count      :int)

;; Message — what the user and agent say to each other.
(schema/register! :seon.message/id      :string)
(schema/register! :seon.message/role    [:enum :user :assistant :system])
(schema/register! :seon.message/content :string)
(schema/register! :seon.message/session :seon.db/ref)
(schema/register! :seon.message/at      :inst)

;; Eval — every form the agent runs lands here. :ok? distinguishes
;; success from failure; :result-edn carries the pr-str of the value
;; on success; :error carries the pr-str of (seon.error/->map e) on
;; failure (so the agent reads back a map, not a string).
(schema/register! :seon.eval/id         :string)
(schema/register! :seon.eval/session    :seon.db/ref)
(schema/register! :seon.eval/at         :inst)
(schema/register! :seon.eval/turn       :int)
(schema/register! :seon.eval/narration  :string)
(schema/register! :seon.eval/source     :string)
(schema/register! :seon.eval/ok?        :boolean)
(schema/register! :seon.eval/result-edn :string)
(schema/register! :seon.eval/error      :string)

;; ============================================================
;; Context rendering — what the LLM sees per turn.
;;
;; H-1a ships a minimal build-ctx (recent conversation + recent evals
;; only). H-1b rewrites it richly with REPL-state header + worked
;; examples + live current-state queries. Keeping it minimal here so
;; H-1a's commit is focused on the eval-surface swap.
;; ============================================================

(defn- render-messages [session-id n]
  (->> (db/query
         {:seon.db/query '[:find ?at ?role ?content
                           :in $ ?sid
                           :where
                           [?m :seon.message/session ?sid]
                           [?m :seon.message/at ?at]
                           [?m :seon.message/role ?role]
                           [?m :seon.message/content ?content]]
          :seon.db/args [[:seon.session/id session-id]]})
       (sort-by first)
       (take-last n)
       (map (fn [[_ role content]] (str (name role) ": " content)))
       (str/join "\n")))

(defn- render-recent-evals [session-id n]
  (let [rows (->> (db/query
                    {:seon.db/query
                     '[:find ?id ?at ?src ?ok ?res ?err
                       :in $ ?sid
                       :where
                       [?e :seon.eval/session ?sid]
                       [?e :seon.eval/id ?id]
                       [?e :seon.eval/at ?at]
                       [?e :seon.eval/source ?src]
                       [(get-else $ ?e :seon.eval/ok? true) ?ok]
                       [(get-else $ ?e :seon.eval/result-edn "") ?res]
                       [(get-else $ ?e :seon.eval/error "") ?err]]
                     :seon.db/args [[:seon.session/id session-id]]})
                  (sort-by second #(compare %2 %1))
                  (take n)
                  reverse)]
    (->> rows
         (map (fn [[id _ src ok res err]]
                (str "[" id "] " src
                     "\n;; => " (cond
                                  ok                       res
                                  (not (str/blank? err))   (str "ERROR " err)
                                  :else                    "<no result>"))))
         (str/join "\n\n"))))

(defn build-ctx
  "Build the text blob the LLM sees this turn.

   H-1a minimum: identity header + recent conversation + recent evals.
   H-1b expands with worked examples, current-state queries, and REPL
   prompt-style header."
  [session-id agent-ns-sym]
  (str "# You are agent in namespace " agent-ns-sym "\n"
       ";; session-id: " (pr-str session-id) "\n\n"
       "# Recent messages (last 20)\n\n"
       (render-messages session-id 20)
       "\n\n# Recent evals (last 10)\n\n"
       (render-recent-evals session-id 10)))
