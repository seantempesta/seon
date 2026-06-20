(ns my.soul
  "The agent's API-level SYSTEM PROMPT as DATA — store-seeded,
   runtime-editable, never compiled-in (open-issues-prd-2026-06-11
   Tier 2 \"SOUL/system-prompt hardcoded\").

   Rows follow the `my.kb.instruction` pattern (id/text/priority,
   identity upsert on `::id`) but feed a DIFFERENT injection point:
   `seon.ai.openai-compat` joins them priority-ordered into the `system`
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

   ONE shipped row:

     \"identity\"       — SOUL.md, read from the repo at seed time.
                          SOUL.md stays the seed source of truth.

   The your-output-is-a-REPL MECHANICS are no longer a soul row — they
   are universal for every downstream consumer, so they live HARDCODED
   in the `<system>` block (`seon.ctx/system-text`), not here. The soul
   carries only the agent's IDENTITY; the mechanics belong to the core.

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

(schema/register! ::id [:string {:seon.db/identity true}])  ; "identity", …
(schema/register! ::text [:string {:min 1}])
(schema/register! ::priority :int)                          ; join order, smallest first

(schema/register! ::section
  [:map {:seon.db/entity true}
   [::id       ::id]
   [::text     ::text]
   [::priority ::priority]
   [:my.kb/source-path     {:optional true} :my.kb/source-path]
   [:my.kb/source-line     {:optional true} :my.kb/source-line]
   [:my.kb/source-line-end {:optional true} :my.kb/source-line-end]
   [:my.kb/confidence      {:optional true} :my.kb/confidence]])

;; --- Seed sources.

(def default-soul-md-path
  "Default repo-relative path of the identity seed. The pod runs with
   cwd = repo root (same convention as seon.client's exemplar file
   reads). Overridable via SEON_SOUL_FILE; falls back to AGENTS.md when
   SOUL.md is absent (see [[resolved-soul-path]])."
  "SOUL.md")

(def ^:private fallback-soul-md-path
  "Seed path tried when SEON_SOUL_FILE is unset and the default
   SOUL.md does not exist — the agent's identity file lives in AGENTS.md."
  "AGENTS.md")

(defn- env-val
  "process.env value for `var-name`, or nil when unset/blank (or when
   there is no Node process env at all). Same access pattern as
   seon.web.brand/env-val and seon.platform/runtime-root."
  [var-name]
  (let [v (some-> (.. js/globalThis -process) (.-env) (aget var-name))]
    (when (and (string? v) (not (str/blank? v))) v)))

(defn- file-exists?
  "True when `path` (resolved against cwd) is a readable file. Never
   throws — a missing fs/file just answers false."
  [path]
  (try
    (let [fs (js/require "fs")]
      (.existsSync fs (str (.cwd js/process) "/" path)))
    (catch :default _ false)))

(defn resolved-soul-path
  "The repo-relative identity-seed path actually used this boot.
   Resolution order: (1) SEON_SOUL_FILE when set & non-blank; (2)
   SOUL.md when it exists; (3) AGENTS.md fallback. The returned path is
   what [[seed-tx-data]] stamps onto `:my.kb/source-path` so the row
   reflects the file actually read."
  {:malli/schema [:=> [:cat] :string]}
  []
  (or (env-val "SEON_SOUL_FILE")
      (when (file-exists? default-soul-md-path) default-soul-md-path)
      fallback-soul-md-path))

(defn- read-soul-md
  "Identity-seed text for `path` (default [[resolved-soul-path]]), or
   nil when unreadable (missing file — e.g. a downstream deploy without
   one). nil = the identity row is simply not seeded this boot; it
   seeds on a later boot once the file exists. Never throws."
  ([] (read-soul-md (resolved-soul-path)))
  ([path]
   (try
     (let [fs (js/require "fs")]
       (.readFileSync fs (str (.cwd js/process) "/" path) "utf8"))
     (catch :default _ nil))))

;; --- Boot seed — SEED-ONLY-IF-ABSENT (see ns doc for why this
;; --- differs from my.kb.instruction's re-assert semantics).

(defn seed-tx-data
  "Tx-data for the soul rows MISSING from db value `db` — rows whose
   `::id` already exists are never re-emitted, so a user's runtime edit
   survives every reboot. The \"identity\" row reads SOUL.md fresh at
   seed time (omitted when the file is unreadable). Caller
   (`seon.client` boot) transacts under
   `:seon.db/origin :core-seed`; empty result = nothing to seed."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val]] [:vector ::section]]}
  [db]
  (let [have (into #{} (map first)
                   (db/query {:seon.db/query '[:find ?id :where [?e ::id ?id]]
                              :seon.db/db    db}))
        path (resolved-soul-path)
        soul (when-not (contains? have "identity") (read-soul-md path))]
    (cond-> []
      soul
      ;; Full provenance shape, line range included — agents imitate
      ;; what is SHOWN, so the ambient seed rows carry the same
      ;; source-line/-end attrs the my.kb teaching tells them to store
      ;; (told-vs-shown, blind-spot #9). The range is honest: the row's
      ;; text IS SOUL.md lines 1..N, counted from the text just read.
      (conj {::id       "identity"
             ::priority 10
             ::text     soul
             :my.kb/source-path     path
             :my.kb/source-line     1
             :my.kb/source-line-end (count (str/split-lines soul))
             :my.kb/confidence      :verified}))))

;; --- Derived read — the LLM call's system message.

(defn system-prompt-text
  "The system prompt: every `:my.soul` row's text, priority-ordered
   (smallest first), joined with a blank line. \"\" when no rows exist
   (the caller falls back — see seon.ai.openai-compat). 0-arity reads the
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
