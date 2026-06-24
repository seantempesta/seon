(ns my.soul
  "The agent's API-level SYSTEM-PROMPT IDENTITY, read LIVE from disk.

   The identity is the text of the user's identity files — SOUL.md and
   AGENTS.md — read FRESH on every LLM call ([[system-prompt-text]],
   which seon.ai/effective-system-prompt joins into the `system`
   message). NO store, NO seed, NO restart: a user's edit to SOUL.md or
   AGENTS.md lands on the NEXT turn for every agent. The files are the
   single source of truth and are freely user-editable.

   This is identity ONLY. The universal your-output-is-a-REPL MECHANICS
   are hardcoded in the system section (`seon.ctx/system-text`), not
   here — so the user can edit or even empty SOUL.md/AGENTS.md without
   breaking the core: the load-bearing teaching lives in the core, the
   files only add who-this-agent-is on top. When no identity file
   exists, [[system-prompt-text]] is \"\" and the caller falls back to a
   minimal boot-edge prompt (see seon.ai/fallback-system-prompt).

   FILE RESOLUTION ([[soul-files]]): the primary identity file
   (`SEON_SOUL_FILE` override, else `SOUL.md`) followed by `AGENTS.md`,
   each read when it exists, joined with a blank line. AGENTS.md is the
   cross-tool standard for repo/work instructions; SOUL.md is seon's
   identity file — both ride into the prompt."
  (:require
    [clojure.string :as str]))

(def default-soul-md-path
  "Default repo-relative path of the primary identity file. The pod runs
   with cwd = repo root (same convention as seon.client's exemplar file
   reads). Overridable via SEON_SOUL_FILE (see [[soul-files]])."
  "SOUL.md")

(def ^:private agents-md-path
  "The cross-tool standard repo/work-instructions file, always read
   alongside the primary identity file when it exists."
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

(defn- read-file-text
  "Live text of identity file `path` (resolved against cwd), or nil when
   unreadable (missing file — e.g. a downstream deploy without one).
   Never throws."
  [path]
  (try
    (let [fs (js/require "fs")]
      (.readFileSync fs (str (.cwd js/process) "/" path) "utf8"))
    (catch :default _ nil)))

(defn soul-files
  "The identity files read LIVE into the system prompt, in order: the
   primary identity file (`SEON_SOUL_FILE` override, else SOUL.md) then
   AGENTS.md — only those that currently exist, deduped (so an
   SEON_SOUL_FILE pointing at AGENTS.md is not read twice). A user
   adding, editing, or removing either file is reflected on the next
   call — nothing is cached."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  []
  (->> [(or (env-val "SEON_SOUL_FILE") default-soul-md-path)
        agents-md-path]
       (filter file-exists?)
       distinct
       vec))

(defn system-prompt-text
  "The agent's IDENTITY system message: the live text of every
   [[soul-files]] entry, read FRESH on each call and joined with a blank
   line. \"\" when none exist (the caller falls back — see
   seon.ai/effective-system-prompt). Read every turn so a user's edit to
   SOUL.md / AGENTS.md lands next turn for ALL agents — no seed, no
   restart. The universal REPL mechanics are NOT here; they are
   hardcoded in seon.ctx/system-text."
  {:malli/schema [:=> [:cat] :string]}
  []
  (->> (soul-files)
       (keep read-file-text)
       (str/join "\n\n")))
