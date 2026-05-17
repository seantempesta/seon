(ns seon.agent
  "## You are an agent

   **DRAFT — 2026-05-17.** Read this cover-to-cover. Everything you
   can do, every shape of data you'll encounter, and the loop that
   runs you is right here. This file is rendered as part of your
   context every turn, alongside your own personal namespace.

   ## Your scope

   The runtime has bound two dynamics before your code ever runs:

     *session-id*  — your identity, a 10-char string. Lookup-ref via
                     [:seon.session/id *session-id*].
     *agent-ns*    — your personal namespace, e.g. seon.agent.4f8c0bd7.
                     When you define a name (V0-B-8 forward), it
                     interns there. You own it; no other agent reads.

   ## How a turn happens

   When a :user message lands, the user-message-kick trigger calls
   into seon.session/run-turn!. One turn is:

     1. build-ctx → renders this namespace + your playground + recent
                    messages + recent evals into one text blob
     2. LLM → sends that text to deepseek, gets text back
     3. parse-forms (in seon.repl) → walks the response like a Clojure
                    REPL: ;; lines are narration, the next form is
                    code; pairs them up
     4. for each pair: eval the form, transact a :seon.eval entity
        carrying source + result + narration
     5. read your :seon.session/agent-loop-state from the DB. If
        :idle, halt. Otherwise tick again. The only way to set :idle
        is to call (done!).

   You don't 'reply'. You eval. Forms do whatever you want done —
   query, transact, message the user. Like a bash session: ;;
   comments explain your thinking, real commands do work.

   ## Talking to the user

     (say! \"text\")

   Transacts an :assistant message. The user is listening to the same
   :seon.message table you are; their UI updates as you call this.
   Call say! many times in one turn — partial results, status,
   final answer all flow through here.

   ## Comments are rendered to the user too

   `;;` narration lines aren't just yours. The user-facing renderer
   formats them as markdown and shows the user 'agent said X, did Y,
   result Z'. Write comments worth reading — they're prose to the
   user, not just notes to yourself.

   ## Ending your turn

     (done!)         — you're satisfied; halt; wait for next user msg.

   That's the only halt verb. If you don't call (done!), the loop
   ticks again (capped per session to prevent runaway). Default
   behavior is keep-going; halting is explicit.

   ## The user might message you mid-turn

   They can transact :user messages while you're running. We won't
   interrupt you. After you (done!), the runtime sees any
   unprocessed user messages and kicks another turn for you.

   ## Hashes are handles

   Every form you eval gets a 10-char id. The value is in
   :seon.eval/result-edn. Use these from later turns:

     (eval-lookup \"a4f81bd7c2\")    — read a prior eval's value
     (recent-evals)                  — last 10 evals in this session

   ## Your scratchpad

     (scratch! :foo 17)   — write working memory
     (scratch :foo)       — read it back, possibly across turns

   ## Verbs index (everything callable in this namespace)

     (say! text)          — message the user
     (done!)              — halt the loop
     (scratch! k v)       — write working memory
     (scratch k)          — read working memory
     (eval-lookup id)     — get a prior eval's value
     (recent-evals)       — list of recent evals

   Plus seon.db/* for the database (transact!, query, pull, entity).
   Plus standard cljs.core. That's it for V0.

   ## What's NOT here yet (V0 scaffolding limitations)

   - You can't (defn ...) yet. The V0 eval is curated to the verb
     table only — see *verb-table*. Bootstrap-CLJS eval that allows
     arbitrary forms lands in V0-B-8.
   - You can't see other sessions' data.
   - You can't call out to the network except through verbs.
   - You can't interrupt yourself; (done!) is the only exit.

   Iterate carefully within these limits; we'll lift them as you
   demonstrate the loop works."
  (:require
    [cljs.core.async :as a :refer [chan close! put!]]
    [cljs.reader :as edn]
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema])
  (:require-macros
    [cljs.core.async :refer [go]]))

;; ============================================================
;; ID generator — one 10-char base62 generator used everywhere.
;; Globally unique at our scale, time-prefixed so they sort by
;; creation. Per-attribute idents (:seon.X/id) so the namespace
;; on the attribute self-documents the entity type.
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
  (let [now-mod (mod (.now js/Date) (Math/pow 62 4))
        time-prefix (to-base62 (Math/floor now-mod) 4)
        rand-part (apply str (repeatedly 6 #(rand-nth alphabet)))]
    (str time-prefix rand-part)))

;; ============================================================
;; Schemas — every shape you'll write or see in the database.
;; ============================================================

;; Session — you. One entity per agent.
(schema/register! :seon.session/id              :string)
(schema/register! :seon.session/agent-ns        :symbol)
(schema/register! :seon.session/agent-loop-state [:enum :idle :running])
;; Scratchpad is an EDN string (pr-str at write, read-string at read).
;; Datahike-cljs doesn't have a map value-type; we serialize. The
;; scratch / scratch! verbs hide this detail.
(schema/register! :seon.session/scratchpad      :string)
(schema/register! :seon.session/tick-count      :int)

;; Message — what you and the user say to each other.
(schema/register! :seon.message/id      :string)
(schema/register! :seon.message/role    [:enum :user :assistant :system])
(schema/register! :seon.message/content :string)
(schema/register! :seon.message/session :seon.db/ref)
(schema/register! :seon.message/at      :inst)

;; Eval — every form you run.
(schema/register! :seon.eval/id         :string)
(schema/register! :seon.eval/session    :seon.db/ref)
(schema/register! :seon.eval/at         :inst)
(schema/register! :seon.eval/turn       :int)
(schema/register! :seon.eval/narration  :string)
(schema/register! :seon.eval/source     :string)
(schema/register! :seon.eval/result-edn :string)
(schema/register! :seon.eval/error      :string)

;; ============================================================
;; The bound dynamics — your identity. The session lifecycle binds
;; these for you. Anything you call from your turn sees them.
;; ============================================================

(def ^:dynamic *session-id* nil)
(def ^:dynamic *agent-ns*   nil)

(defn- self-ref [] [:seon.session/id *session-id*])

;; ============================================================
;; Talking to the user.
;;
;; The user's UI listens to :seon.message entities. As you say!,
;; messages appear in chat in :seon.message/at order. The user can
;; interleave their own :user messages at any time.
;; ============================================================

(defn say!
  "Transact an :assistant message. Returns the message-id. Call as
   many times as you want in one turn — each call is a separate
   message; the user sees them as they're transacted."
  [text]
  (let [mid (new-id!)]
    (db/transact!
      {:seon.db/tx-data [{:seon.message/id      mid
                          :seon.message/role    :assistant
                          :seon.message/content text
                          :seon.message/session (self-ref)
                          :seon.message/at      (js/Date.)}]})
    mid))

;; ============================================================
;; Ending your turn.
;;
;; The only way to set :idle is here. The loop driver in seon.session
;; reads :seon.session/agent-loop-state from the DB after each turn:
;;   :idle    → halt; wait for next user message
;;   :running → tick again (capped at :seon.session/tick-count limit)
;;
;; The default is keep going. You must explicitly call (done!) to
;; halt. This is intentional: a busy agent doesn't have to remember
;; to say 'continue', but they DO have to remember to say 'stop'.
;; ============================================================

(defn done!
  "Halt this turn. The next :user message kicks a fresh turn. If user
   messages arrived during this turn, the runtime sees them after
   you set :idle and kicks again automatically."
  []
  (db/transact!
    {:seon.db/tx-data [{:seon.session/id *session-id*
                        :seon.session/agent-loop-state :idle}]}))

;; ============================================================
;; Scratchpad — small free-form working memory across turns.
;; ============================================================

(defn- read-scratchpad []
  (let [s (:seon.session/scratchpad
            (db/entity {:seon.db/ref (self-ref)}))]
    (if (and s (not (str/blank? s)))
      (try (edn/read-string s) (catch :default _ {}))
      {})))

(defn scratch
  "Read a key from your scratchpad. nil if unset."
  [k]
  (get (read-scratchpad) k))

(defn scratch!
  "Set a key on your scratchpad. Returns the value."
  [k v]
  (let [pad (read-scratchpad)]
    (db/transact!
      {:seon.db/tx-data [{:seon.session/id *session-id*
                          :seon.session/scratchpad (pr-str (assoc pad k v))}]})
    v))

;; ============================================================
;; Hashes as handles.
;; ============================================================

(defn eval-lookup
  "Retrieve the result of a prior eval by id. Returns the read value,
   or the raw string if pr-str→read-string round-trip fails, or nil."
  [eval-id]
  (when-let [e (db/entity {:seon.db/ref [:seon.eval/id eval-id]})]
    (when-let [s (:seon.eval/result-edn e)]
      (try (edn/read-string s)
           (catch :default _ s)))))

(defn recent-evals
  "Last N evals in this session, newest first. Returns vectors of
   [id source result-or-error].

   `get-else` rejects nil defaults by design (matches Datomic; see
   datahike's `query/logical.cljc`: \"legacy get-else semantics: a
   nil default is rejected at runtime\"). We use empty strings as
   sentinels and filter at the seq layer."
  ([] (recent-evals 10))
  ([n]
   (->> (db/query
          {:seon.db/query '[:find ?id ?at ?src ?res ?err
                            :in $ ?sid
                            :where
                            [?e :seon.eval/session ?sid]
                            [?e :seon.eval/id ?id]
                            [?e :seon.eval/at ?at]
                            [?e :seon.eval/source ?src]
                            [(get-else $ ?e :seon.eval/result-edn "") ?res]
                            [(get-else $ ?e :seon.eval/error "") ?err]]
           :seon.db/args [(self-ref)]})
        (sort-by second #(compare %2 %1))
        (take n)
        (mapv (fn [[id _ src res err]]
                [id src (cond
                          (not (str/blank? res)) res
                          (not (str/blank? err)) (str "ERROR: " err)
                          :else nil)])))))

;; ============================================================
;; The verb table — what the runtime eval allows.
;;
;; V0 scaffolding: the agent can only call fns listed here. seon.session
;; consults *verb-table* when evaluating each form. When V0-B-8 brings
;; bootstrap-CLJS eval online, this table goes away and the agent can
;; (defn ...) freely. For now, this is the curated vocabulary.
;;
;; Adding a verb above? Add an entry here AND in `verbs-doc` (used by
;; the renderer).
;; ============================================================

(def verb-table
  {'say!         say!
   'done!        done!
   'scratch!     scratch!
   'scratch      scratch
   'eval-lookup  eval-lookup
   'recent-evals recent-evals})

(def verbs-doc
  "One-line docs per verb, for the agent-facing render."
  {'say!         "Transact an :assistant message. Multi-call = streaming."
   'done!        "Halt this turn; wait for next user message."
   'scratch      "Read a key from your scratchpad."
   'scratch!     "Set a key on your scratchpad."
   'eval-lookup  "Retrieve a prior eval's value by 10-char id."
   'recent-evals "Last N evals in this session, newest first."})

;; ============================================================
;; Context rendering — what the agent sees per turn.
;;
;; build-ctx is called by seon.session/run-turn! before each LLM call.
;; It concatenates the runtime surface + the agent's playground +
;; recent chat + recent evals. Pure of DB; rebuilt every turn.
;; ============================================================

(defn- one-line-doc [s]
  (when s (-> s (str/split #"\n") first str/trim)))

(defn- format-schema [[k v]]
  (str "  " k "  " (pr-str v)))

(defn- render-seon-agent
  "Render the agent runtime's surface: schemas + verbs. Static across
   turns (unless seon.agent itself changes)."
  []
  (let [schemas (->> (schema/registered-schemas)
                     (filter (fn [[k _]]
                               (#{"seon.session" "seon.message" "seon.eval"}
                                (namespace k))))
                     sort)]
    (str "### namespace seon.agent\n\n"
         "#### schemas\n"
         (str/join "\n" (map format-schema schemas))
         "\n\n#### verbs\n"
         (str/join "\n"
                   (for [[sym doc] (sort verbs-doc)]
                     (str "  (" sym ")  ; " doc)))
         "\n\n")))

(defn- render-agent-playground
  "Render *agent-ns* from the :seon.eval log. CLJS has no runtime ns
   introspection, so we surface defn-like forms from the agent's eval
   history. (Until V0-B-8 the agent can't actually defn, so this
   section will mostly be empty.)"
  []
  (let [evals (db/query
                {:seon.db/query '[:find ?id ?at ?src
                                  :in $ ?sid
                                  :where
                                  [?e :seon.eval/session ?sid]
                                  [?e :seon.eval/id ?id]
                                  [?e :seon.eval/at ?at]
                                  [?e :seon.eval/source ?src]]
                 :seon.db/args [(self-ref)]})
        defs (->> evals
                  (sort-by second #(compare %2 %1))
                  (filter (fn [[_ _ src]] (re-find #"\(def" src)))
                  (take 20))]
    (str "### namespace " *agent-ns* "\n\n"
         (if (seq defs)
           (str "#### your defs (most recent 20)\n"
                (str/join "\n" (map (fn [[id _ src]]
                                      (str "  [" id "] " src))
                                    defs))
                "\n\n")
           "  (empty — you haven't defined anything yet)\n\n"))))

(defn- render-messages [n]
  (->> (db/query
         {:seon.db/query '[:find ?at ?role ?content
                           :in $ ?sid
                           :where
                           [?m :seon.message/session ?sid]
                           [?m :seon.message/at ?at]
                           [?m :seon.message/role ?role]
                           [?m :seon.message/content ?content]]
          :seon.db/args [(self-ref)]})
       (sort-by first)
       (take-last n)
       (map (fn [[_ role content]] (str (name role) ": " content)))
       (str/join "\n")))

(defn- render-recent-evals [n]
  (->> (recent-evals n)
       (map (fn [[id src res]]
              (str "[" id "] " src "\n;; => " res)))
       reverse
       (str/join "\n\n")))

(defn build-ctx
  "Build the text blob the LLM sees this turn. The runtime + your
   playground + recent chat + recent evals. Everything visible to
   you per turn is here."
  []
  (str "# seon.agent — the runtime\n\n"
       (render-seon-agent)
       "\n\n# Your playground\n\n"
       (render-agent-playground)
       "\n\n# Recent messages (last 20)\n\n"
       (render-messages 20)
       "\n\n# Recent evals (last 10)\n\n"
       (render-recent-evals 10)))
