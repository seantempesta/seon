(ns seon.agent.ctx.jobs
  "The `:jobs` context section — running + recently-finished background
   shell jobs as a `BACKGROUND JOBS` comment-block.

   The reactive-context pattern: this is a pure fn of the LIVE job table
   (`seon.agent.shell/list-jobs`, the globalThis volatile tier — never
   datoms) read AT RENDER TIME. It renders NOTHING when the table is empty
   and each line carries the `job-output` handle to read more — so nothing
   is stored, no acknowledgement state, and the section VANISHES the moment
   the last job is pruned (self-healing). Scoped to the CURRENT agent:
   `list-jobs` filters by `:seon.agent/id`, so this section shows ONLY the
   jobs THIS agent launched — never another agent's (OBS-1). Symbol-wired
   into the composer (`seon.config/default-ctx-blocks`) as
   `'seon.agent.ctx.jobs/jobs-block`."
  (:require
    [clojure.string :as str]
    [seon.agent.shell :as shell]))

(defn- state-dot
  "A dot glyph for a job state — running / stopped / exited."
  [state]
  (case state :running "●" :stopped "◼" "○"))

(defn- humanize-ms
  "Coarse human duration for `ms` — s / m / h."
  [ms]
  (let [s (quot (max 0 ms) 1000)]
    (cond
      (< s 60)   (str s "s")
      (< s 3600) (str (quot s 60) "m")
      :else      (str (quot s 3600) "h"))))

(defn- job-line
  "One `; job-… (cmd) — ● running, 2m, ~41k tok output  (…job-output…)` row."
  [{:seon.agent.shell/keys [job-id cmd state exit runtime-ms out-tokens err-tokens]}]
  (str "; " job-id " (" cmd ") — " (state-dot state) " " (name state)
       (when (some? exit) (str " " exit))
       ", " (humanize-ms runtime-ms)
       ", ~" (+ (or out-tokens 0) (or err-tokens 0)) " tok output"
       "  (seon.agent.shell/job-output {:seon.agent.shell/job-id \"" job-id "\"})"))

(defn jobs-block
  "DEPRECATED — shelf idea (jobs); reference only, see context-rebuild.

   Running + recent background shell jobs as a block, or empty when none.

   Derived from the live volatile job table at render time — no stored
   state, self-healing (vanishes when the table empties). Each row names
   the `job-output` handle to read that job's stream."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [_]
  (let [jobs (:seon.agent.shell/jobs (shell/list-jobs))]
    (if (empty? jobs)
      ""
      (str ";;; BACKGROUND JOBS — " (count jobs)
           " (volatile: lost on pod restart, oldest finished pruned)\n"
           (str/join "\n" (map job-line jobs))))))
