(ns seon.agent
  "Agent runtime — schemas + ctx-rendering, no magic verbs.

   The agent operates as a real REPL: bootstrap-CLJS evaluates its
   forms, results land in a per-agent home namespace (`seon.agent.<id>`)
   as live values keyed by eval-id (on globalThis, via [[seon.eval]]),
   and durable records land as `:seon.eval` entities in the database.
   The agent calls the real `seon.db/*` APIs directly — no
   `say!`/`done!`/`scratch!` wrappers.

   This namespace owns:
     - `new-id!` — base62 10-char id generator (eval-ids, message-ids)
     - the `:seon.session/*`, `:seon.message/*`, `:seon.eval/*` schemas
     - `build-ctx` — the rendered prompt the LLM sees each turn

   ## build-ctx shape

   What the LLM sees is structured as a REPL session header (current
   ns, session-id, turn count) followed by capability examples in the
   SAME shape the agent is expected to respond in (`;; narration` +
   `(form)`). Live DB state, recent conversation, and recent evals
   land below. The agent learns by mimicking what it sees — there's
   no schema-as-docs to interpret, only worked patterns."
  (:require
    [clojure.string :as str]
    [cljs.reader :as edn]
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

(schema/register! :seon.session/id              :string)
(schema/register! :seon.session/agent-ns        :symbol)
(schema/register! :seon.session/agent-loop-state [:enum :idle :running])
(schema/register! :seon.session/tick-count      :int)

(schema/register! :seon.message/id      :string)
(schema/register! :seon.message/role    [:enum :user :assistant :system])
(schema/register! :seon.message/content :string)
(schema/register! :seon.message/session :seon.db/ref)
(schema/register! :seon.message/at      :inst)

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
;; Live state probes — pure DB queries used to render ctx.
;; ============================================================

(defn- session-entity [session-id]
  (db/entity {:seon.db/ref [:seon.session/id session-id]}))

(defn- recent-messages [session-id n]
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
       (take-last n)))

(defn- recent-evals [session-id n]
  (->> (db/query
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
       reverse))

(defn- try-read-edn
  "Read an EDN string and render it for the agent. If it round-trips
   cleanly, return the pretty-printed value. Otherwise return the raw
   string truncated — the agent reads whatever's there (it's still
   informative even when CLJS-side tagged literals like #datahike/DB
   aren't reader-registered)."
  [s]
  (when-not (str/blank? s)
    (let [trimmed (if (> (count s) 400)
                    (str (subs s 0 400) " …")
                    s)]
      (try (pr-str (edn/read-string s))
           (catch :default _ trimmed)))))

;; ============================================================
;; Render sections — each emits a markdown block. build-ctx
;; concatenates them in order.
;;
;; Examples are formatted in the SAME shape the agent's expected
;; response is parsed (`;; narration\n(form)`) — see seon.repl/
;; parse-forms. The agent learns by mimicking.
;; ============================================================

(defn- render-repl-state
  "REPL prompt header — who you are, where you are, what turn this is.
   `current-ns` defaults to the agent's home ns; updates on `(ns …)`."
  [{:keys [session-id agent-ns-sym current-ns turn-n]}]
  (let [se (session-entity session-id)]
    (str "## REPL state\n"
         ";; current-ns:  " current-ns "\n"
         ";; agent home:  " agent-ns-sym
         "  (auto-loaded with !session-id, !current-ns atoms"
         " + session-id, result accessor fns)\n"
         ";; session-id:  " (pr-str session-id) "\n"
         ";; turn:        " (or turn-n (:seon.session/tick-count se) 0) "\n"
         ";; agent-state: " (pr-str (:seon.session/agent-loop-state se)) "\n")))

(defn- render-how-you-respond
  "Tell the LLM the response shape it should emit. Mimics what
   `seon.repl/parse-forms` reads back out: `;; narration` lines paired
   with s-exprs. Each form runs independently — form N+1 still runs
   if N fails (real-REPL copy-paste semantics)."
  [_]
  (str "## How you respond\n\n"
       "Write a sequence of Clojure forms. You may precede each form\n"
       "with one or more `;; narration` lines explaining what you're\n"
       "about to do. Forms run in order. If form N fails, form N+1\n"
       "still runs (just like pasting a block into a REPL). The result\n"
       "of every form is captured under a 10-char eval-id; you'll see\n"
       "those in the next turn's `recent evals` and can refer back to\n"
       "any of them via `(result :<eval-id>)`.\n\n"
       "Example response shape:\n\n"
       ";; first, look at what's here\n"
       "(seon.db/query ...)\n\n"
       ";; then, write a reply\n"
       "(seon.db/transact! ...)\n"))

(defn- render-what-you-can-do
  "Worked examples for each primitive the agent uses. Real forms with
   real session-id — the agent can copy them, change the strings, and
   the patterns work."
  [{:keys [session-id]}]
  (str
    "## What you can do\n\n"

    ";; read your own session entity\n"
    "(seon.db/entity {:seon.db/ref [:seon.session/id " (pr-str session-id) "]})\n\n"

    ";; query for recent user messages\n"
    "(seon.db/query\n"
    "  {:seon.db/query '[:find ?at ?content\n"
    "                    :in $ ?sid\n"
    "                    :where\n"
    "                    [?m :seon.message/session ?sid]\n"
    "                    [?m :seon.message/role :user]\n"
    "                    [?m :seon.message/at ?at]\n"
    "                    [?m :seon.message/content ?content]]\n"
    "   :seon.db/args  [[:seon.session/id " (pr-str session-id) "]]})\n\n"

    ";; reply by transacting an :assistant message\n"
    ";; (session-id) reads your own id from the home-ns atom\n"
    "(seon.db/transact!\n"
    "  {:seon.db/tx-data\n"
    "   [{:seon.message/id      (seon.agent/new-id!)\n"
    "     :seon.message/role    :assistant\n"
    "     :seon.message/content \"your text here\"\n"
    "     :seon.message/session [:seon.session/id (session-id)]\n"
    "     :seon.message/at      (js/Date.)}]})\n\n"

    ";; pull a specific entity by lookup-ref\n"
    "(seon.db/pull {:seon.db/pull-pattern '[*]\n"
    "               :seon.db/ref [:seon.message/id \"some-msg-id\"]})\n\n"

    ";; reach back to a prior eval's value by id\n"
    "(result :<eval-id-from-recent-evals>)\n\n"

    ";; define a function for later turns — vars persist in your home ns\n"
    "(defn double-it [n] (* n 2))\n\n"

    ";; later (this turn or any future turn): just call it\n"
    "(double-it 21)\n"))

(defn- render-conventions
  "Hard rules + gotchas the agent should know up front."
  [{:keys [agent-ns-sym]}]
  (str "## Conventions + gotchas\n\n"
       "- Stay in your home namespace `" agent-ns-sym "` unless you have\n"
       "  a reason to switch. (`(ns other)` works but cross-ns bare\n"
       "  value reads return nil — that's a cljs.js limitation. Atoms\n"
       "  cross-ns fine; fns cross-ns fine.)\n"
       "- Use atoms for state you want to read back: `(def !x (atom 0))`\n"
       "  + `@!x` works. `(def x 42)` then later `x` returns nil — use\n"
       "  `(def x (atom 42))` instead.\n"
       "- Your turn ends automatically after your forms run; the\n"
       "  session flips to :idle. The user's next message kicks a new\n"
       "  turn.\n"
       "- Errors from your forms are values, not exceptions. A failed\n"
       "  form lands in `recent evals` as `:ok? false` with the full\n"
       "  error map readable from `:error`. The session keeps going.\n"))

(defn- render-current-state
  "Live snapshot — output of the example queries against the real DB.
   This is what the example queries above would actually return RIGHT
   NOW. The agent sees these as a feedback loop."
  [{:keys [session-id]}]
  (let [se          (session-entity session-id)
        recent-user (->> (db/query
                           {:seon.db/query
                            '[:find ?at ?content
                              :in $ ?sid
                              :where
                              [?m :seon.message/session ?sid]
                              [?m :seon.message/role :user]
                              [?m :seon.message/at ?at]
                              [?m :seon.message/content ?content]]
                            :seon.db/args [[:seon.session/id session-id]]})
                         (sort-by first)
                         (take-last 5))]
    (str "## Current state (live)\n\n"
         ";; your session entity\n"
         (pr-str {:seon.session/id              (:seon.session/id se)
                  :seon.session/agent-ns         (:seon.session/agent-ns se)
                  :seon.session/agent-loop-state (:seon.session/agent-loop-state se)
                  :seon.session/tick-count       (:seon.session/tick-count se)}) "\n\n"
         ";; last 5 user messages\n"
         (if (seq recent-user)
           (str/join "\n" (map (fn [[at content]]
                                 (str "  " (pr-str at) "  " (pr-str content)))
                               recent-user))
           "  (none)") "\n")))

(defn- render-recent-conversation
  [{:keys [session-id]}]
  (let [msgs (recent-messages session-id 20)]
    (str "## Recent conversation (last 20)\n\n"
         (if (seq msgs)
           (str/join "\n" (map (fn [[_ role content]]
                                 (str (name role) ": " content))
                               msgs))
           "  (no messages yet)"))))

(defn- render-recent-evals
  [{:keys [session-id]}]
  (let [rows (recent-evals session-id 10)]
    (str "## Recent evals (last 10, oldest-first)\n\n"
         (if (seq rows)
           (str/join "\n\n"
                     (map (fn [[id _ src ok res err]]
                            (str "[" id "] " src "\n"
                                 ";; " (if ok ":ok " ":error ")
                                 (cond
                                   ok                       (or (try-read-edn res) res)
                                   (not (str/blank? err))   (pr-str (try-read-edn err))
                                   :else                    "<no result>")))
                          rows))
           "  (none yet)"))))

(defn- render-schema-reference
  "Bottom — schema reference. Reference material, not example, so it
   lives at the end."
  [_]
  (let [filtered (->> (schema/registered-schemas)
                      (filter (fn [[k _]]
                                (#{"seon.session" "seon.message" "seon.eval"}
                                  (namespace k))))
                      sort)]
    (str "## Schema reference\n\n"
         (str/join "\n"
                   (map (fn [[k v]] (str "  " k "  " (pr-str v)))
                        filtered)))))

;; ============================================================
;; build-ctx — concatenate the sections in order. Pure of state
;; once the DB queries land; safe to call from anywhere.
;;
;; Args:
;;   session-id    — session string (e.g. \"seon\")
;;   agent-ns-sym  — agent's home ns symbol (e.g. 'seon.agent.seon)
;;   current-ns    — the agent's tracked current ns (from @!current-ns;
;;                   defaults to agent-ns-sym if not provided)
;;   turn-n        — current turn counter (optional; reads from session
;;                   entity if not provided)
;; ============================================================

(defn build-ctx
  "Build the text blob the LLM sees this turn. Pure DB queries; no
   bootstrap-eval round-trip required."
  ([session-id agent-ns-sym]
   (build-ctx session-id agent-ns-sym nil nil))
  ([session-id agent-ns-sym current-ns turn-n]
   (let [m {:session-id   session-id
            :agent-ns-sym agent-ns-sym
            :current-ns   (or current-ns agent-ns-sym)
            :turn-n       turn-n}]
     (str (render-repl-state m)
          "\n"
          (render-how-you-respond m)
          "\n"
          (render-what-you-can-do m)
          "\n"
          (render-conventions m)
          "\n"
          (render-current-state m)
          "\n"
          (render-recent-conversation m)
          "\n\n"
          (render-recent-evals m)
          "\n\n"
          (render-schema-reference m)))))
