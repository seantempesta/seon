(ns seon.render.default
  "The renderers a fresh agent uses when no slot override is set.

   Two universal floors (`pretty-ai`, `pretty-html`) plus the rich
   defaults — `ctx` (the agent's prompt) and `view` (the agent's
   HTML tile) — composed from public helpers an agent can call back
   into when writing its own.

   Per spec-05 §15.3 + §15.4b these renderers all follow seon's
   map-in / map-out convention with `:malli/schema` metadata on every
   public fn. The helpers are public so an agent override (typically
   `seon.agent.seon/my-ctx`) can pick which fragments to keep + add
   its own pieces.

   ## Independent of seon.agent

   This namespace queries the DB directly via `seon.db` — it does NOT
   require `seon.agent`. That keeps the dependency graph acyclic when
   A-7 wires `seon.agent/run-turn-once!` to call `seon.render/ai-dispatch`
   (which calls into this namespace). seon.agent → seon.render →
   seon.render.default is the one-way arrow; we do not close it."
  (:require
    [cljs.reader :as edn]
    [clojure.string :as str]
    [seon.db :as db]
    [seon.fs :as fs]
    [seon.schema :as schema]
    [seon.ui.components :as comp]))

;; ============================================================
;; Pretty-print floors — universal fallbacks for both surfaces.
;; A-2 contract: render mechanism never crashes; missing → pretty-print.
;; ============================================================

(defn- pod-cwd []
  (try (.cwd js/process) (catch :default _ ".")))

(defn- read-source-file
  "Read a `.cljs` source file relative to the pod's cwd. Used to
   inject the live toolkit source into the agent's prompt so its
   API surface is always exactly what the code says it is."
  [rel-path]
  (let [abs (str (pod-cwd) "/" rel-path)
        r   (fs/read-file {:seon.fs/path abs})]
    (when (:seon.fs/ok? r) (:seon.fs/content r))))

(defn fs-root
  "The folder the user has pointed the agent at. Reads `SEON_FS_ROOT` from
   process env; returns nil if not set. Used by the welcome tile and
   the agent prompt to ground every answer in the user's own files."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (some-> js/process .-env .-SEON_FS_ROOT))

(defn read-only?
  "True iff the pod was launched with `SEON_FS_READ_ONLY=1`. The agent
   sees this in its prompt so it knows write-file will be denied."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (= "1" (some-> js/process .-env .-SEON_FS_READ_ONLY)))

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
;; DB query helpers — used by both the ctx fragments and the view.
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
  "Return the most-recent `n` messages for `id`, oldest-first. Each
   row is `[at role content]`."
  ([db id] (recent-messages db id 20))
  ([db id n]
   (let [args  [[:seon.agent/id id]]
         query '[:find ?at ?role ?content
                 :in $ ?aid
                 :where
                 [?m :seon.message/agent ?aid]
                 [?m :seon.message/at ?at]
                 [?m :seon.message/role ?role]
                 [?m :seon.message/content ?content]]
         rows  (if db
                 (db/query {:seon.db/db db
                            :seon.db/query query
                            :seon.db/args args})
                 (db/query {:seon.db/query query
                            :seon.db/args args}))]
     (->> rows (sort-by first) (take-last n)))))

(defn ^:no-doc recent-evals
  "Return the most-recent `n` :seon.eval entries for `id`, oldest-first.
   Each row is `[id at src ok result-edn error narration]`."
  ([db id] (recent-evals db id 10))
  ([db id n]
   (let [args  [[:seon.agent/id id]]
         query '[:find ?id ?at ?src ?ok ?res ?err ?narr
                 :in $ ?aid
                 :where
                 [?e :seon.eval/agent ?aid]
                 [?e :seon.eval/id ?id]
                 [?e :seon.eval/at ?at]
                 [?e :seon.eval/source ?src]
                 [(get-else $ ?e :seon.eval/ok? true) ?ok]
                 [(get-else $ ?e :seon.eval/result-edn "") ?res]
                 [(get-else $ ?e :seon.eval/error "") ?err]
                 [(get-else $ ?e :seon.eval/narration "") ?narr]]
         rows  (if db
                 (db/query {:seon.db/db db
                            :seon.db/query query
                            :seon.db/args args})
                 (db/query {:seon.db/query query
                            :seon.db/args args}))]
     (->> rows (sort-by second #(compare %2 %1)) (take n) reverse))))

(defn ^:no-doc notes-helper
  "Return all `:seon.note/*` entities for `id`, newest-first. Each
   row is `{:seon.note/id :seon.note/topic :seon.note/content
   :seon.note/at}`."
  [db id]
  (let [args  [[:seon.agent/id id]]
        query '[:find ?nid ?topic ?content ?at
                :in $ ?aid
                :where
                [?n :seon.note/agent ?aid]
                [?n :seon.note/id ?nid]
                [?n :seon.note/topic ?topic]
                [?n :seon.note/content ?content]
                [?n :seon.note/at ?at]]
        rows  (if db
                (db/query {:seon.db/db db :seon.db/query query :seon.db/args args})
                (db/query {:seon.db/query query :seon.db/args args}))]
    (->> rows
         (sort-by #(nth % 3) #(compare %2 %1))
         (map (fn [[nid topic content at]]
                {:seon.note/id nid
                 :seon.note/topic topic
                 :seon.note/content content
                 :seon.note/at at})))))

(defn ^:no-doc recent-errors
  "Return the most-recent `n` undismissed `:seon.log/level :error`
   entries for `id`, newest-first. Populated by `seon.log/error!`
   transactions (A-6). Returns `()` when none."
  ([db id] (recent-errors db id 10))
  ([db id n]
   (let [args  [id]
         query '[:find ?eid ?at ?msg
                 :in $ ?aid
                 :where
                 [?e :seon.log/level :error]
                 [?e :seon.log/agent ?aid]
                 [?e :seon.log/at ?at]
                 [?e :seon.log/message ?msg]
                 (not [?e :seon.log/dismissed-at _])
                 [(identity ?e) ?eid]]
         rows  (if db
                 (db/query {:seon.db/db db :seon.db/query query :seon.db/args args})
                 (db/query {:seon.db/query query :seon.db/args args}))]
     (->> rows
          (sort-by second #(compare %2 %1))
          (take n)
          (map (fn [[eid _at msg]]
                 {:db/id eid :seon.log/message msg}))))))

(defn ^:no-doc all-entities
  "Return the user-data entities of the DB as a vector of maps,
   sorted by eid. Each entity carries `:db/id` plus every observed
   `?a ?v` pair. Schema entities (those with `:db/ident`) are
   filtered out — they're metadata, not data the user cares about
   when watching memory grow."
  [db]
  (let [query '[:find ?e ?a ?v
                :where [?e ?a ?v]]
        rows  (if db
                (db/query {:seon.db/db db :seon.db/query query})
                (db/query {:seon.db/query query}))]
    (->> rows
         (group-by first)
         (remove (fn [[_ triples]]
                   (some (fn [[_ a _]] (= a :db/ident)) triples)))
         (sort-by key)
         (map (fn [[eid triples]]
                (into {:db/id eid}
                      (map (fn [[_ a v]] [a v]) triples))))
         vec)))

(defn ^:no-doc all-running-agents
  "Return every agent entity whose `:seon.agent/state` is `:idle` or
   `:running`. Used by `seon.web.broadcast` (A-6) to iterate live
   agents for per-tx re-render. Pure read; safe from any thread."
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
;; CTX fragments — each emits a markdown block. The default `ctx`
;; concatenates them in order; agent overrides pick which to keep.
;; ============================================================

(defn repl-state-header
  "REPL prompt header — who you are, where you are, what turn this is.
   Also surfaces turn-pressure when the multi-turn loop is running
   long, to nudge the agent toward composing its final `:assistant`
   reply before hitting the cap."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ent     (pulled-agent db id)
        ns-sym  (symbol (str "seon.agent." id))
        since-u (or (:seon.agent/turns-since-user ent) 0)
        cap     20
        pressure
        (cond
          (>= since-u 17)
          (str "\n;; ⚠⚠⚠ FINAL WARNING — you are on turn " since-u "/" cap ".\n"
               ";; You WILL hit the cap on your next turn or two.\n"
               ";; STOP researching. TRANSACT THE :assistant MESSAGE NOW\n"
               ";; with whatever you have — even partial. The user gets\n"
               ";; NOTHING if you don't reply.\n")
          (>= since-u 10)
          (str "\n;; ⚠ Turn " since-u "/" cap " — past halfway. Wrap up.\n"
               ";; You probably have enough info. Stop reading new files;\n"
               ";; compose the :assistant reply with what you've found.\n")
          (>= since-u 5)
          (str "\n;; Turn " since-u "/" cap " — most questions need 2–4\n"
               ";; turns. If you have the answer, reply now.\n")
          :else
          "")]
    (str "## REPL state\n"
         ";; current-ns:  " ns-sym "\n"
         ";; agent home:  " ns-sym
         "  (auto-loaded with !session-id, !current-ns atoms"
         " + session-id, result accessor fns)\n"
         ";; agent-id:    " (pr-str id) "\n"
         ";; turn:        " (or (:seon.agent/turn-count ent) 0) "\n"
         ";; since-user:  " since-u "/" cap " turns this conversation\n"
         ";; agent-state: " (pr-str (:seon.agent/state ent)) "\n"
         pressure)))

(defn your-mission
  "Top-of-prompt framing: what the user pointed the agent at, the answering
   posture, and the build-tools-then-use-them work style. Emitted only
   when `SEON_FS_ROOT` is set."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  (when-let [root (fs-root)]
    (str "## Your mission\n\n"
         "TWO goals each conversation:\n\n"
         "  1. **Reply.** Transact ONE `:seon.message/role :assistant`\n"
         "     message answering the user, grounded in files you read\n"
         "     under " root (when (read-only?) " (read-only)") ".\n"
         "     Until you transact it, the user sees nothing useful\n"
         "     and the multi-turn loop keeps firing.\n\n"
         "  2. **Remember.** Whenever you discover a durable fact\n"
         "     (who someone is, what a project does, where something\n"
         "     lives), call `(seon.toolkit/note! {...})`. Notes\n"
         "     survive even when the user clears the chat. Next time\n"
         "     they ask about the same thing, you'll have it already.\n\n"
         "Most questions should take 2–4 turns. Don't burn turns\n"
         "inspecting; reply as soon as you have something defensible.\n\n"

         "## ⚠ Common traps (read carefully)\n\n"
         "  - **`println` is INVISIBLE to the user.** It writes to the\n"
         "    Node stdout, not to the chat. It also returns `nil`, so\n"
         "    you can't see what you printed. NEVER use println.\n"
         "    To inspect a value, just write the expression by itself —\n"
         "    its return value appears in `recent evals` next turn.\n"
         "      WRONG:  (println \"count:\" (count xs))   ;; you see nil\n"
         "      RIGHT:  (count xs)                       ;; you see the count\n"
         "  - **The reply must be a `seon.db/transact!`.** Pretty-printing\n"
         "    a result, doseq-printing rows, returning a map — none of\n"
         "    these reach the user. Only an `:assistant` message in the\n"
         "    DB shows up in the chat.\n"
         "  - **If `slurp*` returns nil, the file doesn't exist** at that\n"
         "    path. Do NOT call `(subs nil ...)` next — that crashes.\n"
         "    Re-run `search` with a different `:name-pattern` instead.\n"
         "  - **Case-insensitive grep**: most names in the wiki are\n"
         "    capitalized (Maya, Daniel). Always use `(?i)` in your\n"
         "    pattern: `#\"(?i)maya\"`, not `#\"maya\"`.\n\n"

         "## How to work — build tools, then use them\n\n"
         "You have up to ~20 agentic turns per user message. You do\n"
         "NOT have to fit your reply in one turn. The expected work\n"
         "style is:\n\n"
         "  1. write helper functions into your home ns\n"
         "  2. compose them to narrow → read → answer\n"
         "  3. transact a single :assistant message with the result\n\n"
         "This is a REPL. `(def x ...)` persists across turns —\n"
         "your home namespace is long-lived. Don't read every file\n"
         "in the folder; there are thousands. Use the toolkit's\n"
         "filename + content grep to narrow first.\n\n"

         "## Pre-loaded toolkit — `seon.toolkit/*`\n\n"
         "All pre-loaded — call directly, no `require` needed.\n\n"
         "### High-leverage first moves\n\n"
         "  (seon.toolkit/about \"Seon\")\n"
         "  ;; ⇒ {:notes [...] :paths [...] :index-hits [{:line :line-num}]}\n"
         "  ;; The SMARTEST first call for any 'tell me about X' question.\n"
         "  ;; Combines: recall existing notes + find filenames matching\n"
         "  ;; X + grep the root CLAUDE.md for X. No file reads beyond\n"
         "  ;; the cached index. Decide what to slurp from what comes back.\n\n"
         "  (seon.toolkit/find-by-name #\"(?i)<topic>\")\n"
         "  ;; ⇒ [\"<abs-path>\" …]  — paths only, very fast (cached).\n"
         "  ;; Use to learn WHERE something lives before deciding what\n"
         "  ;; to read. NEVER guess at paths — `list-dir` on a non-\n"
         "  ;; existent dir wastes a turn.\n\n"
         "  (seon.toolkit/index)\n"
         "  ;; ⇒ body of " root "/CLAUDE.md\n"
         "  ;; The user's own hand-written index of everything. Best\n"
         "  ;; starting point when you have no idea where to look.\n\n"
         "### Read + extract\n\n"
         "  (seon.toolkit/slurp* \"<abs-path>\")    ;; body or nil\n"
         "  (seon.toolkit/head body 800)           ;; first N chars\n"
         "  (seon.toolkit/section body \"Heading\") ;; pull a markdown section\n\n"
         "### Lower-level grep\n\n"
         "  (seon.toolkit/search\n"
         "    {:root         \"" root "\"\n"
         "     :pattern      #\"(?i)<topic>\"\n"
         "     :name-pattern #\"<scope-regex>\"\n"
         "     :limit 12})\n"
         "  (seon.toolkit/grep-files opts)         ;; raw hit list\n\n"
         "### Memory\n\n"
         "  (seon.toolkit/recall #\"(?i)<topic>\")\n"
         "  (seon.toolkit/note! {:topic \"…\" :content \"…\" :sources [\"…\"]})\n\n"

         "## ;; narration is the user's primary view\n\n"
         "The chat tile shows your `;; narration` comments as the\n"
         "MAIN text and tucks the code into a collapsible block\n"
         "below. Write your thinking out loud — sparse code, rich\n"
         "`;;` narration:\n\n"
         "  ;; Looking for files about the build service.\n"
         "  ;; Starting with CLAUDE.md and docs/timeline — those\n"
         "  ;; carry the deployment URLs and status updates.\n"
         "  (seon.toolkit/search\n"
         "    {:root \"" root "\"\n"
         "     :pattern #\"(?i)build.*service\"\n"
         "     :name-pattern #\"(?i)CLAUDE\\.md|docs/timeline\"})\n\n"

         "## Code style — NO scattered atoms\n\n"
         "Use `let` bindings within a turn. Use `def` to hold a\n"
         "single named value across turns. Don't wrap every\n"
         "intermediate in its own atom — that's noise. If you do\n"
         "need cross-turn mutable state, ONE atom holding a map.\n\n"
         "BAD:\n"
         "  (def !hits  (atom (search ...)))\n"
         "  (def !top   (atom (->> @!hits :by-path (take 2))))\n"
         "  (def !body1 (atom (slurp* (-> @!top first :path))))\n"
         "  (def !body2 (atom (slurp* (-> @!top second :path))))\n\n"
         "GOOD:\n"
         "  (def hits (seon.toolkit/search ...))\n"
         "  hits   ;; result visible in next turn's recent-evals\n\n"
         "## Two-turn template (the demo target)\n\n"
         "  ;; turn 1 — probe everything cheap at once with `about`\n"
         "  (def probe (seon.toolkit/about \"<topic>\"))\n"
         "  probe\n\n"
         "  ;; turn 2 — slurp 1–2 of probe's :paths, compose ONE reply,\n"
         "  ;;          and `note!` what you learned in the same form.\n"
         "  (let [paths  (->> probe :paths (take 2))\n"
         "        bodies (zipmap paths (map seon.toolkit/slurp* paths))\n"
         "        answer (str \"## <topic>\\n\\n<your synthesis here, citing paths>\\n\\n\"\n"
         "                    \"### Sources\\n\"\n"
         "                    (clojure.string/join \"\\n\"\n"
         "                      (map #(str \"- \" %) paths)))]\n"
         "    (seon.toolkit/note!\n"
         "      {:topic   \"<topic>\"\n"
         "       :content \"<one-paragraph durable summary>\"\n"
         "       :sources (vec paths)})\n"
         "    (seon.db/transact!\n"
         "      {:seon.db/tx-data\n"
         "       [{:seon.message/id      (seon.agent/new-id!)\n"
         "         :seon.message/role    :assistant\n"
         "         :seon.message/content answer\n"
         "         :seon.message/agent   [:seon.agent/id (session-id)]\n"
         "         :seon.message/at      (js/Date.)}]}))\n\n"
         "## Picking `:name-pattern`\n\n"
         "The user's wiki is well-organized:\n"
         "  - people / who-is:           #\"docs/(people|timeline)\"\n"
         "  - project status:            #\"CLAUDE\\.md|docs/timeline\"\n"
         "  - meetings / decisions:      #\"meetings/|/decisions\\.md\"\n"
         "  - when unsure:               #\"CLAUDE\\.md$\"   — the index\n\n"

         "## Rules\n\n"
         "  - Don't slurp the same path twice — `(def x ...)` once.\n"
         "  - Read at most ~3 files in full per question. If grep\n"
         "    snippets answer it, skip the slurp.\n"
         "  - Show your work in `;;` narration — that IS the reply\n"
         "    visible to the user. Don't lie about sources; saying\n"
         "    \"couldn't find it\" is fine.\n"
         "  - End with exactly ONE `:assistant` transact!. Until\n"
         "    you do, the loop keeps firing turns.\n")))

(defn how-you-respond
  "Tell the LLM the response shape it should emit — `;; narration`
   lines paired with s-exprs, partial-failure semantics, and the
   strict 'prose must be commented' rule."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  (str "## How you respond — FORMAT IS STRICT\n\n"
       "Your response is read by a Clojure REPL. Everything you emit\n"
       "is either:\n\n"
       "  (a) a Clojure form — `(...)`, `[...]`, `{...}`, `@!atom`, etc.\n"
       "  (b) a comment line starting with `;;`\n\n"
       "Anything else is a bug. The reader will silently drop bare\n"
       "prose words now — but it WAS eating responses last week\n"
       "(\"Let me read the file\" became four bogus eval entries).\n"
       "Don't make me prove it again. If you write a sentence, put\n"
       "`;; ` in front of every line of it.\n\n"
       "Forms run in order. If form N fails, form N+1 still runs\n"
       "(REPL semantics). Each form's result is captured under a\n"
       "10-char eval-id, visible next turn under `recent evals`.\n"
       "Refer back via `(result :<eval-id>)`.\n\n"
       "Correct shape:\n\n"
       ";; first, look at what's here\n"
       "(seon.db/query ...)\n\n"
       ";; then, write a reply\n"
       "(seon.db/transact! ...)\n\n"
       "Wrong shape (don't do this):\n\n"
       "Let me look at what's here first.\n"
       "(seon.db/query ...)\n"
       "Now I'll write the reply.\n"
       "(seon.db/transact! ...)\n"))

(defn what-you-can-do
  "Worked examples for each primitive the agent uses. Real forms with
   the real agent-id substituted in — the agent can copy them, change
   the strings, and the patterns work as-is."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (str
    "## What you can do\n\n"

    ";; read your own agent entity\n"
    "(seon.db/entity {:seon.db/ref [:seon.agent/id " (pr-str id) "]})\n\n"

    ";; query for recent user messages\n"
    "(seon.db/query\n"
    "  {:seon.db/query '[:find ?at ?content\n"
    "                    :in $ ?aid\n"
    "                    :where\n"
    "                    [?m :seon.message/agent ?aid]\n"
    "                    [?m :seon.message/role :user]\n"
    "                    [?m :seon.message/at ?at]\n"
    "                    [?m :seon.message/content ?content]]\n"
    "   :seon.db/args  [[:seon.agent/id " (pr-str id) "]]})\n\n"

    ";; reply by transacting an :assistant message\n"
    ";; (session-id) reads your own id from the home-ns atom\n"
    "(seon.db/transact!\n"
    "  {:seon.db/tx-data\n"
    "   [{:seon.message/id      (seon.agent/new-id!)\n"
    "     :seon.message/role    :assistant\n"
    "     :seon.message/content \"your text here\"\n"
    "     :seon.message/agent   [:seon.agent/id (session-id)]\n"
    "     :seon.message/at      (js/Date.)}]})\n\n"

    ";; pull a specific entity by lookup-ref\n"
    "(seon.db/pull {:seon.db/pull-pattern '[*]\n"
    "               :seon.db/ref [:seon.message/id \"some-msg-id\"]})\n\n"

    ";; reach back to a prior eval's value by id\n"
    "(result :<eval-id-from-recent-evals>)\n\n"

    ";; define a function for later turns — vars persist in your home ns\n"
    "(defn double-it [n] (* n 2))\n\n"

    ";; later (this turn or any future turn): just call it\n"
    "(double-it 21)\n\n"

    ";; ── filesystem (your local-machine surface; spec-05 A-9) ──────\n"
    ";; check what runtime you're on (`:node` in V0.5 dev; `:wasi` in V1+ Tauri)\n"
    "(seon.platform/host)\n\n"

    ";; read a file from disk — returns {:seon.fs/ok? :seon.fs/content ...}\n"
    "(seon.fs/read-file {:seon.fs/path \"/Users/sean/.zshrc\"})\n\n"

    ";; list a directory — returns {:seon.fs/ok? :seon.fs/entries [...]}\n"
    "(seon.fs/list-dir  {:seon.fs/path (seon.fs/home-dir)})\n\n"

    ";; stat a file — size, mtime, dir?/file?\n"
    "(seon.fs/stat      {:seon.fs/path \"/Users/sean/.zshrc\"})\n\n"

    ";; write a file — overwrites; returns {:seon.fs/ok? :seon.fs/path}\n"
    "(seon.fs/write-file {:seon.fs/path \"/tmp/seon-note.txt\"\n"
    "                     :seon.fs/content \"hi from \" (str (session-id))})\n\n"

    ";; recursively walk a tree, filter by extension — returns absolute paths\n"
    "(seon.fs/walk-dir {:seon.fs/path \"/Users/you/src/your-project\"\n"
    "                   :seon.fs/match-ext \".md\"})\n\n"

    ";; pattern for digesting a knowledge base — walk, then read selectively\n"
    ";; based on the user's question (don't read everything; pick relevant files)\n"
    "(let [r (seon.fs/walk-dir {:seon.fs/path \"/Users/you/src/your-project\"\n"
    "                           :seon.fs/match-ext \".md\"})]\n"
    "  (when (:seon.fs/ok? r)\n"
    "    (count (:seon.fs/entries r))))\n"))

(defn conventions
  "Hard rules + gotchas the agent should know up front."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id]}]
  (let [ns-sym (symbol (str "seon.agent." id))]
    (str "## Conventions + gotchas\n\n"
         "- Stay in your home namespace `" ns-sym "`. `(ns other)` is\n"
         "  a hassle and unnecessary.\n"
         "- `(def x ...)` persists across turns — your home ns is\n"
         "  long-lived. So just `(def hits (search ...))` in turn 1\n"
         "  and reference `hits` in turn 2. No need to wrap values\n"
         "  in atoms for cross-turn access.\n"
         "- Atoms are only for genuinely-mutable state (rare here).\n"
         "  If you ever need one, prefer ONE atom holding a map.\n"
         "- Errors from your forms are values, not exceptions. A\n"
         "  failed form lands in `recent evals` as `:ok? false`. The\n"
         "  loop keeps going — your next form still runs.\n")))

(defn recent-conversation
  "Block: '## Recent conversation (last 20)' followed by the chronological
   message log."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [msgs (recent-messages db id 20)]
    (str "## Recent conversation (last 20)\n\n"
         (if (seq msgs)
           (str/join "\n" (map (fn [[_at role content]]
                                 (str (name role) ": " content))
                               msgs))
           "  (no messages yet)"))))

(defn available-code
  "Inject the live source of `seon.toolkit` and `seon.fs` into the
   prompt. The agent doesn't have to guess at signatures — it reads
   the code. Source is fetched at render time, so any update to the
   files appears on the next turn without a pod restart."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  (let [tk (read-source-file "src/seon/toolkit.cljs")
        fs (read-source-file "src/seon/fs.cljs")]
    (str "## Available code — read this; don't guess signatures\n\n"
         (when tk
           (str "### `seon.toolkit` (pre-loaded; call directly)\n\n"
                "```clojure\n" tk "\n```\n\n"))
         (when fs
           (str "### `seon.fs` (lower-level filesystem primitives)\n\n"
                "```clojure\n" fs "\n```\n\n"))
         "Cite functions by their fully-qualified name when calling:\n"
         "`(seon.toolkit/search ...)`, `(seon.fs/read-file ...)`, etc.\n")))

(defn what-you-already-know
  "Surface the agent's durable `:seon.note/*` entries at the top of the
   prompt. This is THE memory that persists across conversations —
   the user can clear the chat, refresh the page, and these notes
   still apply. The agent should query them BEFORE walking the filesystem."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [ns (notes-helper db id)]
    (if (empty? ns)
      (str "## What you already know\n\n"
           "  (no notes yet — write some with `seon.toolkit/note!` when\n"
           "   you find facts worth remembering. They persist across\n"
           "   conversations.)\n")
      (str "## What you already know  (your durable notes)\n\n"
           ";; You wrote these in past turns. They persist even after\n"
           ";; the user clears the chat. Check them BEFORE walking the\n"
           ";; filesystem; you may already have the answer.\n\n"
           (str/join "\n\n"
             (map (fn [{:seon.note/keys [id topic content]}]
                    (str "[" id "]  **" topic "**\n"
                         "  " content))
                  ns))
           "\n\nQuery these directly:  `(seon.toolkit/recall #\"(?i)<topic>\")`\n"))))

(defn- try-read-edn
  "Read an EDN string and pretty-print it. Falls back to the raw
   string truncated when the reader can't parse (CLJS-side tagged
   literals like #datahike/DB aren't reader-registered)."
  [s]
  (when-not (str/blank? s)
    (let [trimmed (if (> (count s) 400)
                    (str (subs s 0 400) " …")
                    s)]
      (try (pr-str (edn/read-string s))
           (catch :default _ trimmed)))))

(defn recent-evals-block
  "Block: '## Recent evals (last 10, oldest-first)' with each row's
   eval-id, source, and :ok / :error payload."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [rows (recent-evals db id 10)]
    (str "## Recent evals (last 10, oldest-first)\n\n"
         (if (seq rows)
           (str/join "\n\n"
                     (map (fn [[eid _at src ok res err]]
                            (str "[" eid "] " src "\n"
                                 ";; " (if ok ":ok " ":error ")
                                 (cond
                                   ok                       (or (try-read-edn res) res)
                                   (not (str/blank? err))   (pr-str (try-read-edn err))
                                   :else                    "<no result>")))
                          rows))
           "  (none yet)"))))

(defn recent-errors-block
  "Block: '## Recent errors' pulled from `:seon.log/level :error`
   entities. Stubbed empty until seon.log/error! lands in A-6."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [errs (recent-errors db id 10)]
    (if (seq errs)
      (str "## Recent errors\n\n"
           (str/join "\n" (map :seon.log/message errs)))
      "")))

(defn schema-reference
  "Bottom of the ctx — full schema dump for the agent's domain. Filters
   to schemas in the seon.agent / seon.message / seon.eval namespaces so
   the agent sees ONLY what's relevant to its transaction surface."
  {:malli/schema [:=> [:cat :map] :string]}
  [_input]
  (let [filtered (->> (schema/registered-schemas)
                      (filter (fn [[k _]]
                                (#{"seon.agent" "seon.message" "seon.eval"}
                                  (namespace k))))
                      sort)]
    (str "## Schema reference\n\n"
         (str/join "\n"
                   (map (fn [[k v]] (str "  " k "  " (pr-str v)))
                        filtered)))))

;; ============================================================
;; CTX — the default :seon.render/ai. Composes the helpers above.
;; Agents override by transacting `:seon.render/ai 'seon.agent.seon/my-ctx`
;; with their own fn that calls whichever helpers they want.
;; ============================================================

(defn ctx
  "Default :seon.render/ai renderer. System fn → takes system input
   shape (`:seon.db/db` + `:seon.agent/id`). Concatenates the helper
   sections in order; recent-errors stays inline so the agent sees
   its own thrown exceptions next turn (per spec-05 §15.4a)."
  {:malli/schema [:=> [:cat :seon.render/system-input] :seon.render/ai-response]}
  [input]
  {:seon.render/text
   (str/join "\n\n"
     (remove str/blank?
       [(repl-state-header      input)
        (your-mission           input)
        (what-you-already-know  input)
        (available-code         input)
        (how-you-respond        input)
        (what-you-can-do        input)
        (conventions            input)
        (recent-conversation    input)
        (recent-evals-block     input)
        (recent-errors-block    input)
        (schema-reference       input)]))})

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
        turns (or (:seon.agent/turn-count ent) 0)
        msgs  (recent-messages db id 5)
        errs  (recent-errors db id 5)]
    {:seon.render/hiccup
     [:div {:class "h-full flex flex-col p-3 gap-2 bg-base-900 rounded"
            :id (str "agent-" id)}
      [:header {:class "flex items-center gap-2"}
       (comp/status-dot state id)
       [:span {:class "text-xs text-text-400 ml-auto"} (str "turn " turns)]]
      (when (seq errs)
        [:section {:class "flex flex-col gap-1 border border-error/40 bg-error/10 rounded p-2"}
         (for [e errs]
           [:div {:class "flex items-start gap-2 text-xs"}
            [:span {:class "text-error font-bold"} "⚠"]
            [:span {:class "flex-1 text-error font-mono"}
             (str (:seon.log/message e))]
            [:button {:class "text-text-400 hover:text-text-100"
                      :data-on-click__post (str "/log/dismiss?id=" (:db/id e))} "×"]])])
      [:section {:class "flex-1 overflow-auto text-xs font-mono"}
       (if (seq msgs)
         (for [[_at role content] msgs]
           [:div {:class "py-0.5"}
            [:span {:class "text-text-400"} (str (name role) ": ")]
            [:span {:class "text-text-100"} content]])
         [:div {:class "text-text-500 italic"} "no messages yet"])]]}))
