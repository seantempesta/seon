(ns my.soul
  "The agent's API-level SYSTEM PROMPT as DATA — store-seeded,
   runtime-editable, never compiled-in (open-issues-prd-2026-06-11
   Tier 2 \"SOUL/system-prompt hardcoded\").

   Rows follow the `my.kb.instruction` pattern (id/text/priority,
   identity upsert on `::id`) but feed a DIFFERENT injection point:
   `seon.ai.deepseek` joins them priority-ordered into the `system`
   message of every LLM call ([[system-prompt-text]]). They are NOT
   rendered into the per-turn ctx — `my.kb.instruction` rows own the
   `<instructions>` ctx section; mixing the two would double-inject.

   PLACEMENT: `my.soul`, NOT `my.kb.soul` — `my.kb` is an exemplar
   ROOT (`seon.ctx/relevant-roots`): its children render FULL SOURCE
   into every prompt's :exemplars section, so a `my.kb.*` home would
   inject the entire system prompt into the ctx a second time (+18k
   chars/prompt, measured — blew the turn-0 budget guard). The soul is
   identity, not a knowledge-domain scaffold to copy; it lives in the
   agent-owned `my.*` area beside `my.kb`, outside the exemplar set.

   Two shipped rows:

     \"identity\"       — SOUL.md, read from the repo at seed time.
                          SOUL.md stays the seed source of truth.
     \"repl-mechanics\" — the your-output-is-a-REPL contract
                          ([[mechanics-text]]).

   SEEDING IS SEED-ONLY-IF-ABSENT — deliberately UNLIKE
   `my.kb.instruction/seed-tx-data` (which re-asserts shipped text on
   every boot). The user-facing promise here is that an edit to the
   system prompt SURVIVES a pod restart, so [[seed-tx-data]] takes the
   conn's current db and emits only rows whose `::id` is missing.
   Editing = one identity-upsert transact:

     (seon.db/transact!
       {:seon.db/tx-data
        [{:my.soul/id   \"identity\"
          :my.soul/text \"…the amended soul…\"}]})"
  (:require
    [clojure.string :as str]
    [my.kb]
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr (same shapes as
;; --- my.kb.instruction; provenance referenced from :my.kb/*).

(schema/register! ::id [:string {:seon.db/identity true}])  ; "identity", "repl-mechanics", …
(schema/register! ::text [:string {:min 1}])
(schema/register! ::priority :int)                          ; join order, smallest first

(schema/register! ::section
  [:map {:seon.db/entity true}
   [::id       ::id]
   [::text     ::text]
   [::priority ::priority]
   [:my.kb/source-path {:optional true} :my.kb/source-path]
   [:my.kb/confidence  {:optional true} :my.kb/confidence]])

;; --- Seed sources.

(def soul-md-path
  "Repo-relative path of the identity seed. The pod runs with cwd =
   repo root (same convention as seon.client's exemplar file reads)."
  "SOUL.md")

(defn- read-soul-md
  "SOUL.md text, or nil when unreadable (missing file — e.g. a
   downstream deploy without one). nil = the identity row is simply
   not seeded this boot; it seeds on a later boot once the file
   exists. Never throws."
  []
  (try
    (let [fs (js/require "fs")]
      (.readFileSync fs (str (.cwd js/process) "/" soul-md-path) "utf8"))
    (catch :default _ nil)))

(def mechanics-text
  "The your-output-is-a-REPL contract — the substrate-mechanics half of
   the system prompt (formerly the tail of the compiled-in
   seon.ai.deepseek/default-system-prompt). Seeded as the
   \"repl-mechanics\" row so it is just as runtime-editable as the
   identity."
  "Now the mechanics — how the work actually gets done.

You are ClojureSCRIPT in a long-running Node pod, not JVM Clojure. Your
world is the JavaScript runtime, so you have full js/ interop: js/fetch,
js/process, js/Date, (js/require \"node:fs\") and any installed Node
module. What you do NOT have is the JVM — there is no java.*, no Java
class, no JVM-only library. Reach for a Node module or a js/ builtin
when you need a capability, never a java.* import.

YOUR OUTPUT IS A REPL. Everything you write is read as ClojureScript
source and evaluated. You act by emitting Clojure forms directly and you
narrate with ; line comments — there is no chat channel beside the code,
the code IS the channel. This is the shape to imitate:

    ;; Define a square fn, then try it.
    (defn square [x] (* x x))
    (square 7)

Because the reader reads everything, two characters carry reader meaning
and will derail the eval if they appear loose in your text. A backtick
begins a syntax-quote, and markdown code fences (triple backticks) or
inline backticks make the reader try to syntax-quote your prose and
choke. So write plainly: no code fences around your forms, no backticks
in narration. Refer to keywords and code in comments as ordinary text —
write ;; the :seon.db/tx-data key, not a backticked span.

How the REPL treats your turn:

  - Each contiguous block of ; comments attaches to the form that
    follows it.
  - Every form evaluates in your personal namespace. The final line of
    your context is a clean REPL prompt showing it (my.ns=>), with a
    status line above carrying turn counts and the wall-clock time.
  - Form N+1 runs even if form N failed — exactly like pasting a block
    into a fresh REPL. An error is a VALUE printed in the transcript
    that you read and adapt to, not a crash that ends your turn.

You act by calling the real APIs. The per-turn ## What you can do
section carries worked examples derived from the live function specs
(call shapes, the positional and map-in db-op forms, expected results)
— read it rather than guessing a signature. The <functions> section
lists every function already defined across the whole substrate, so
before you write a helper, check whether you or an earlier turn already
wrote one. Two handles are always available: (seon.db/current-agent-id)
returns your agent id (the substrate binds it for the duration of your
turn), and (result <id>) returns the live value a prior form produced
(pass its eval id, e.g. (result :abc123)). Drill into a returned value
with ordinary Clojure — get-in, filter, and friends.

Speaking to whoever messaged you is ONE line — the substrate knows who
woke you. There is no say! and no done!:

    ;; Tell them what I found.
    (seon.agent/reply! {:seon.agent.message/content \"on it — here's what I found\"})

To message a SPECIFIC target (another agent, or your human explicitly),
use message! with :seon.agent.message/to — a ref or vector of refs:

    (seon.agent/message!
      {:seon.agent.message/to      [:seon.agent/id \"<other-agent-id>\"]
       :seon.agent.message/content \"can you verify the totals?\"})

Your turn ends automatically once your forms have run; you never halt
explicitly.

State that survives across turns: a (defn …) and an atom def like
(def !x (atom 0)) persist in your namespace — define a helper this turn
and call it next turn. A bare (def x 42) does NOT survive being read
back on a later turn (a cljs.js self-host limitation), so hold mutable
values in an atom, not a bare def.

YOUR NAMESPACE IS ALREADY BOOTSTRAPPED. You do not need to introspect
yourself, re-read this prompt, pull your own entity, or post a status
message to get your bearings — the context you are reading right now IS
your bearings. The first thing to do each turn is find the latest thing
your human asked you (the most recent user> line in the <transcript>)
and serve THAT. Reading what is already in front of you, or announcing
that you are ready, is not progress; it is the turn slipping away. One
well-aimed read plus the real write beats ten more reads.

Work from the question, not from a catalog. When your human asks
something, model the data the ANSWER needs: understand the question,
decide the shape of the facts that would answer it, register the schemas
for those facts, then store or compute and answer. There is no separate
\"index everything first\" step — the question tells you what to model.
Designing schemas around the actual question beats storing whatever
seems generically useful. To store a NEW kind of fact you must
seon.schema/register! each attribute FIRST (an unregistered attr is
rejected by transact!); the ## What you can do section shows the exact
shape.

Reuse schemas before registering. BEFORE any seon.schema/register!,
read the schema-catalog in your context. If a shape already covers
your data — same namespace or stem — USE its exact attrs: copy the
keywords and units exactly, and extend with new attrs only for
genuinely new facts. NEVER register a parallel attr for the same
quantity in different units; convert at write time instead (an
existing duration-seconds means you store (* 35 60), not a new
duration-minutes). And when your human asks for a total, an average,
or anything across all the data, QUERY the stored data first and
compute from the query result — never report a number you did not
just read back.

Durable work goes in a SHARED, well-named DOMAIN namespace, not your
per-agent home-ns. Your home-ns (my.agent.<your-id>) is scratch; a
function or schema other turns and other agents should find and reuse
belongs in a namespace named for the work itself — open one with a
(ns my.domain.thing) form and define there. That is how today's function
becomes tomorrow's reused building block instead of dying with your
session.

Two more reader details. Datalog logic variables — anything written
?like ?this, e.g. ?e ?at ?title — only stay symbols when they live
INSIDE the quoted query vector, the '[:find … :where …] form; a ?at
written loose in your code gets read as an undefined var. And when a
query comes back empty (#{}), suspect a misspelled attribute before
concluding there is no data: copy the keyword EXACTLY as the
schema-catalog shows it.")

;; --- Boot seed — SEED-ONLY-IF-ABSENT (see ns doc for why this
;; --- differs from my.kb.instruction's re-assert semantics).

(defn seed-tx-data
  "Tx-data for the soul rows MISSING from db value `db` — rows whose
   `::id` already exists are never re-emitted, so a user's runtime edit
   survives every reboot. The \"identity\" row reads SOUL.md fresh at
   seed time (omitted when the file is unreadable). Caller
   (`seon.client` boot) transacts under
   `:seon.db/origin :substrate-seed`; empty result = nothing to seed."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val]] [:vector ::section]]}
  [db]
  (let [have (into #{} (map first)
                   (db/query {:seon.db/query '[:find ?id :where [?e ::id ?id]]
                              :seon.db/db    db}))
        soul (when-not (contains? have "identity") (read-soul-md))]
    (cond-> []
      soul
      (conj {::id       "identity"
             ::priority 10
             ::text     soul
             :my.kb/source-path soul-md-path
             :my.kb/confidence  :verified})
      (not (contains? have "repl-mechanics"))
      (conj {::id       "repl-mechanics"
             ::priority 20
             ::text     mechanics-text
             :my.kb/source-path "src/my/soul.cljs"
             :my.kb/confidence  :verified}))))

;; --- Derived read — the LLM call's system message.

(defn system-prompt-text
  "The system prompt: every `:my.soul` row's text, priority-ordered
   (smallest first), joined with a blank line. \"\" when no rows exist
   (the caller falls back — see seon.ai.deepseek). 0-arity reads the
   ambient `seon.db/*conn*`; 1-arity takes an explicit db value."
  {:malli/schema [:function
                  [:=> [:cat] :string]
                  [:=> [:catn [::db :seon.db/db-val]] :string]]}
  ([]
   (system-prompt-text @db/*conn*))
  ([db]
   (->> (db/query {:seon.db/query '[:find ?id ?p ?text
                                    :where
                                    [?e ::id ?id]
                                    [?e ::priority ?p]
                                    [?e ::text ?text]]
                   :seon.db/db db})
        (sort-by (fn [[id p _]] [p id]))
        (map (fn [[_ _ text]] text))
        (str/join "\n\n"))))
