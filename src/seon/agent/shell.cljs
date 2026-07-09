(ns seon.agent.shell
  "Run real commands — argv in, `{exit out err}` out, as data.

   A formatter, a one-off `node`/`python` script, a `git` query: `run`
   executes it and hands back the universal shell contract. ARGV ONLY —
   there is no shell, no string interpolation, no injection surface.

   ## The ok? refinement (read this)

   `:seon.agent.shell/ok? true` means **the process RAN** and exit/out/err is
   the answer — a NON-ZERO exit is a legitimate result (a formatter
   found issues, a test failed, `git diff` found changes): read
   `:seon.agent.shell/exit` yourself. `ok? false` is reserved for COULD NOT
   RUN AT ALL (SEON_SHELL ungranted, cwd denied, binary not found,
   internal error).

   ## Security model

   **Default-deny.** The whole capability is gated by the host-owned
   `SEON_SHELL` env var (inspect with [[grants]]); a `:seon.agent.shell/cwd`
   is additionally gated through the seon.agent.fs allowlist. A soft
   boundary against LLM accidents, not a security boundary.

   ## Output discipline

   stdout/stderr in the envelope are TOKEN-CAPPED (default 2048/stream)
   with honest metadata: `:seon.agent.shell/out-tokens` / `err-tokens` always
   carry the FULL captured size, `:seon.agent.shell/truncated?` flags any
   drop, and `:seon.agent.shell/hint` names how to get more.

   ## Worked examples

     (seon.agent.shell/grants)   ; the SEON_SHELL grant — call this first
     (seon.agent.shell/run {:seon.agent.shell/cmd  \"git\"
                            :seon.agent.shell/args [\"status\" \"--porcelain\"]
                            :seon.agent.shell/cwd  \"/Users/me/work-folder\"})
     ; ⟹ «map: ::ok? true, ::exit 0, ::out/::err strings, ::out-tokens/::err-tokens ints, ::timed-out?/::truncated? bools»
     (seon.agent.shell/py-run {:seon.agent.shell/source \"import sys\\nprint(sys.version)\"})

   Plumbing (caps, grant read, cwd gate, execFile wrapper) lives in
   [[seon.agent.shell.internal]]."
  (:require
    [clojure.string :as str]
    [seon.agent.shell.internal :as in]
    [seon.agent.testrun :as testrun]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every key registered, request/response named.
;; ============================================================

(schema/register! :seon.agent.shell/cmd        [:string {:min 1}]) ; argv[0], PATH-resolved
(schema/register! :seon.agent.shell/args       [:vector :string])  ; argv[1..] — never a shell string
(schema/register! :seon.agent.shell/cwd        [:string {:min 1}]) ; absolute; gated by seon.agent.fs
(schema/register! :seon.agent.shell/stdin      :string)            ; written to the child's stdin
(schema/register! :seon.agent.shell/timeout-ms :int)               ; default 30000, then SIGTERM
(schema/register! :seon.agent.shell/source     [:string {:min 1}]) ; python source (py-run)

(schema/register! :seon.agent.shell/ok?        :boolean)           ; "the process RAN", NOT "exit 0"
(schema/register! :seon.agent.shell/exit       :int)               ; the real exit code (may be non-zero)
(schema/register! :seon.agent.shell/out        :string)            ; stdout, token-capped
(schema/register! :seon.agent.shell/err        :string)            ; stderr, token-capped
(schema/register! :seon.agent.shell/out-tokens :int)               ; honest FULL stdout size (tokens)
(schema/register! :seon.agent.shell/err-tokens :int)               ; honest FULL stderr size (tokens)
(schema/register! :seon.agent.shell/timed-out? :boolean)
(schema/register! :seon.agent.shell/truncated? :boolean)           ; output overflowed the byte-capture ceiling (RAM guard)
(schema/register! :seon.agent.shell/hint       :string)
(schema/register! :seon.agent.shell/granted?   :boolean)

(schema/register! :seon.agent.shell/run-request
  [:map
   [:seon.agent.shell/cmd               :seon.agent.shell/cmd]
   [:seon.agent.shell/args              {:optional true} :seon.agent.shell/args]
   [:seon.agent.shell/cwd               {:optional true} :seon.agent.shell/cwd]
   [:seon.agent.shell/stdin             {:optional true} :seon.agent.shell/stdin]
   [:seon.agent.shell/timeout-ms        {:optional true} :seon.agent.shell/timeout-ms]])

(schema/register! :seon.agent.shell/py-run-request
  [:map
   [:seon.agent.shell/source            :seon.agent.shell/source]
   [:seon.agent.shell/cmd               {:optional true} :seon.agent.shell/cmd]
   [:seon.agent.shell/args              {:optional true} :seon.agent.shell/args]
   [:seon.agent.shell/cwd               {:optional true} :seon.agent.shell/cwd]
   [:seon.agent.shell/timeout-ms        {:optional true} :seon.agent.shell/timeout-ms]])

;; The ONE envelope — py-run returns it too (no parallel shape).
(schema/register! :seon.agent.shell/run-response
  [:or
   ;; RAN — exit/out/err is the answer, whatever the exit code.
   [:map
    [:seon.agent.shell/ok?        [:= true]]
    [:seon.agent.shell/exit       :seon.agent.shell/exit]
    [:seon.agent.shell/out        :seon.agent.shell/out]
    [:seon.agent.shell/err        :seon.agent.shell/err]
    [:seon.agent.shell/out-tokens :seon.agent.shell/out-tokens]
    [:seon.agent.shell/err-tokens :seon.agent.shell/err-tokens]
    [:seon.agent.shell/timed-out? :seon.agent.shell/timed-out?]
    [:seon.agent.shell/truncated? :seon.agent.shell/truncated?]
    [:seon.agent.shell/hint       {:optional true} :seon.agent.shell/hint]
    ;; Parsed test results — present only when the argv was pytest-shaped AND
    ;; the output was recognized (seon.agent.testrun/parse). Persisted too, so
    ;; the :test-failures context section renders the latest failing set.
    [:seon.agent.testrun/result   {:optional true} :seon.agent.testrun/result]]
   ;; COULD-NOT-RUN — gate/spawn failure. Shared error map, never a bare
   ;; string.
   [:map
    [:seon.agent.shell/ok?     [:= false]]
    [:seon.error/message :string]
    [:seon.error/data    {:optional true} :map]]])

;; ============================================================
;; Background jobs — spawn long-running work, poll its status, page its
;; FULL output. The job table is volatile (globalThis tier, NEVER datoms);
;; job-output pages the whole captured stream, so nothing is discarded.
;; ============================================================

(schema/register! :seon.agent.shell/job-id     [:string {:min 1}])
(schema/register! :seon.agent.shell/state      [:enum :running :exited :stopped])
(schema/register! :seon.agent.shell/runtime-ms :int)
(schema/register! :seon.agent.shell/stream      [:enum :out :err])
(schema/register! :seon.agent.shell/since       [:int {:min 0}]) ; char cursor into the captured stream
(schema/register! :seon.agent.shell/next-since  :int)            ; pass as ::since next poll for only-new output
(schema/register! :seon.agent.shell/content     :string)
(schema/register! :seon.agent.shell/tokens      :int)

(schema/register! :seon.agent.shell/run-bg-request
  [:map
   [:seon.agent.shell/cmd   :seon.agent.shell/cmd]
   [:seon.agent.shell/args  {:optional true} :seon.agent.shell/args]
   [:seon.agent.shell/cwd   {:optional true} :seon.agent.shell/cwd]
   [:seon.agent.shell/stdin {:optional true} :seon.agent.shell/stdin]])

;; the ok?-false half — shared across every background verb (gate/unknown-job).
(schema/register! :seon.agent.shell/job-fail
  [:map
   [:seon.agent.shell/ok? [:= false]]
   [:seon.error/message :string]
   [:seon.error/data    {:optional true} :map]])

(schema/register! :seon.agent.shell/run-bg-response
  [:or
   [:map
    [:seon.agent.shell/ok?     [:= true]]
    [:seon.agent.shell/job-id  :seon.agent.shell/job-id]
    [:seon.agent.shell/state   :seon.agent.shell/state]
    [:seon.agent.shell/cmd     :seon.agent.shell/cmd]]
   :seon.agent.shell/job-fail])

(schema/register! :seon.agent.shell/job-ref-request
  [:map [:seon.agent.shell/job-id :seon.agent.shell/job-id]])

(schema/register! :seon.agent.shell/job-status-response
  [:or
   [:map
    [:seon.agent.shell/ok?         [:= true]]
    [:seon.agent.shell/job-id      :seon.agent.shell/job-id]
    [:seon.agent.shell/state       :seon.agent.shell/state]
    [:seon.agent.shell/cmd         :seon.agent.shell/cmd]
    [:seon.agent.shell/runtime-ms  :seon.agent.shell/runtime-ms]
    [:seon.agent.shell/out-tokens  :seon.agent.shell/out-tokens]
    [:seon.agent.shell/err-tokens  :seon.agent.shell/err-tokens]
    [:seon.agent.shell/exit        {:optional true} :seon.agent.shell/exit]
    ;; Derived (read-time, not persisted): a FINISHED pytest job's parsed
    ;; result via the same seon.agent.testrun/parse. Background runs are not
    ;; projected into the section (that needs the foreground persist path).
    [:seon.agent.testrun/result    {:optional true} :seon.agent.testrun/result]]
   :seon.agent.shell/job-fail])

(schema/register! :seon.agent.shell/job-output-request
  [:map
   [:seon.agent.shell/job-id :seon.agent.shell/job-id]
   [:seon.agent.shell/stream {:optional true} :seon.agent.shell/stream]
   [:seon.agent.shell/since  {:optional true} :seon.agent.shell/since]])

(schema/register! :seon.agent.shell/job-output-response
  [:or
   [:map
    [:seon.agent.shell/ok?         [:= true]]
    [:seon.agent.shell/job-id      :seon.agent.shell/job-id]
    [:seon.agent.shell/state       :seon.agent.shell/state]
    [:seon.agent.shell/stream      :seon.agent.shell/stream]
    [:seon.agent.shell/content     :seon.agent.shell/content]
    [:seon.agent.shell/since       :seon.agent.shell/since]
    [:seon.agent.shell/next-since  :seon.agent.shell/next-since]
    [:seon.agent.shell/tokens      :seon.agent.shell/tokens]
    [:seon.agent.shell/truncated?  :seon.agent.shell/truncated?]
    [:seon.agent.shell/runtime-ms  :seon.agent.shell/runtime-ms]
    [:seon.agent.shell/exit        {:optional true} :seon.agent.shell/exit]]
   :seon.agent.shell/job-fail])

(schema/register! :seon.agent.shell/job-stop-response
  [:or
   [:map
    [:seon.agent.shell/ok?    [:= true]]
    [:seon.agent.shell/job-id :seon.agent.shell/job-id]
    [:seon.agent.shell/state  :seon.agent.shell/state]]
   :seon.agent.shell/job-fail])

(schema/register! :seon.agent.shell/job-summary
  [:map
   [:seon.agent.shell/job-id     :seon.agent.shell/job-id]
   [:seon.agent.shell/cmd        :seon.agent.shell/cmd]
   [:seon.agent.shell/state      :seon.agent.shell/state]
   [:seon.agent.shell/runtime-ms :seon.agent.shell/runtime-ms]
   [:seon.agent.shell/out-tokens :seon.agent.shell/out-tokens]
   [:seon.agent.shell/err-tokens :seon.agent.shell/err-tokens]
   [:seon.agent.shell/exit       {:optional true} :seon.agent.shell/exit]])
(schema/register! :seon.agent.shell/jobs [:vector :seon.agent.shell/job-summary])
(schema/register! :seon.agent.shell/list-response
  [:map
   [:seon.agent.shell/ok?   [:= true]]
   [:seon.agent.shell/jobs  :seon.agent.shell/jobs]])

(schema/register! :seon.agent.shell/grants-response
  [:map [:seon.agent.shell/granted? :seon.agent.shell/granted?]])

;; ============================================================
;; Public API
;; ============================================================

(defn- ^:async attach-testrun!
  "When `env` is a pytest run that parsed, attach + persist the result.

   Pure pass-through otherwise. The ONE integration of the shared parser on
   the foreground path: parse the full out/err, and on a recognized run
   persist it (scoped to the running agent) so the derived section updates,
   returning `env` with `:seon.agent.testrun/result` assoc'd."
  [cmd args env]
  (if (and (:seon.agent.shell/ok? env)
           (testrun/pytest-argv? cmd (vec (or args []))))
    (let [parsed (testrun/parse {:seon.agent.testrun/stdout (:seon.agent.shell/out env)
                                 :seon.agent.testrun/stderr (:seon.agent.shell/err env)})]
      (if (:seon.agent.testrun/ok? parsed)
        (do (await (testrun/record! {:seon.agent.testrun/result parsed}))
            (assoc env :seon.agent.testrun/result parsed))
        env))
    env))

(defn grants
  "Report whether the host granted shell access (`SEON_SHELL`).

   The grant is host-owned and read live from the env — nothing inside
   the pod can flip it. `false` = default-deny: every [[run]] returns
   the guiding ok?-false envelope until the host sets SEON_SHELL."
  {:malli/schema [:=> [:cat] :seon.agent.shell/grants-response]}
  []
  {:seon.agent.shell/granted? (in/granted?)})

(defn ^:async run
  "Run a command as argv (never a shell string); result is data.

   `^:async` — returns a Promise that ALWAYS resolves to a
   :seon.agent.shell/run-response envelope (never rejects; errors are values).

   ok? = the process RAN: read :seon.agent.shell/exit for success/failure — a
   non-zero exit is a legitimate answer, not an ok?-false. SIGTERM at
   :seon.agent.shell/timeout-ms (default 30s → :seon.agent.shell/timed-out? true,
   exit sentinel 143, partial output still delivered; no low ceiling — pass a
   large timeout for a slow build/test). :seon.agent.shell/cwd (optional) must
   sit under the seon.agent.fs allowlist; the whole verb is default-deny until
   the host grants SEON_SHELL.

   Output is DATA, uncapped: :seon.agent.shell/out / err carry the FULL
   streams (with honest :seon.agent.shell/out-tokens / err-tokens sizes) —
   display economy is the render layer's, not the verb's: a big value stashes
   as result/<id> and renders as a bounded skeleton you re-reference to read
   in full. The only bound is a ~2MB/stream RAM ceiling: past it Node kills
   the child and :seon.agent.shell/truncated? is set with a guiding hint (use
   [[run-bg!]] for an unbounded stream). To PERSIST output durably, that is
   your explicit choice — (my.blob/put! {:my.blob/content
   (:seon.agent.shell/out r)}) the stashed value; the verb never blobs behind
   your back.

   Worked example — run, then thread the output onward:

     (seon.agent.shell/run {:seon.agent.shell/cmd  \"git\"
                            :seon.agent.shell/args [\"status\" \"--porcelain\"]
                            :seon.agent.shell/cwd  \"/Users/me/work-folder\"})
     ; ⟹ «map: ::ok? true, ::exit 0, ::out \"…\", ::err \"\", …»
     ; (zero? (:seon.agent.shell/exit r)) → clean tree; split :seon.agent.shell/out
     ; into lines, transform, db/transact!."
  {:malli/schema [:=> [:cat :seon.agent.shell/run-request] :seon.agent.shell/run-response]}
  [{:seon.agent.shell/keys [cmd args cwd stdin timeout-ms]
    :or {timeout-ms in/default-timeout-ms}}]
  (try
    (cond
      (not= :node (platform/host))
      (in/fail "seon.agent.shell requires the :node host (no :wasi child processes).")

      (not (in/granted?))
      (in/ungranted)

      (or (nil? cmd) (str/blank? cmd))
      (in/fail ":seon.agent.shell/cmd is required and must be non-blank — argv[0], PATH-resolved (e.g. \"git\").")

      :else
      (if-let [denied (when cwd (in/gate-cwd cwd))]
        denied
        (let [^js r   (await (in/exec cmd (vec (or args [])) cwd stdin timeout-ms))
              ^js err (.-err r)
              stdout  (str (.-stdout r))
              stderr  (str (.-stderr r))]
          (await
            (attach-testrun! cmd args
              (cond
                ;; Binary not found — could not run at all.
                (and err (= "ENOENT" (.-code err)))
                (in/fail (str "command not found: " (pr-str cmd) " — argv[0] is "
                              "PATH-resolved; check the name or use an absolute "
                              "path.")
                         {:seon.agent.shell/cmd cmd})

                ;; RAM-ceiling overflow — the child was killed, but the captured
                ;; partial output IS the (truncated) answer.
                (and err (= "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" (.-code err)))
                (in/ran-envelope (in/exit-code err) stdout stderr false true)

                ;; Timeout — execFile SIGTERM'd the child; deliver the honest
                ;; partial output with the authoritative timed-out? flag.
                (and err (.-killed err))
                (in/ran-envelope in/killed-exit stdout stderr true false)

                ;; Ran (exit 0 or non-zero) — exit/out/err is the answer.
                :else
                (in/ran-envelope (in/exit-code err) stdout stderr false false)))))))
    (catch :default e
      (in/fail (str "unexpected error in seon.agent.shell/run: "
                    (or (some-> e .-message) (str e)))))))

(defn ^:async py-run
  "Run Python source via stdin (`python3 -`); result is data.

   The thin Python specialization of [[run]] — same gate (SEON_SHELL),
   same :seon.agent.shell/run-response envelope, same full-output rule. The
   load-bearing rule: :seon.agent.shell/source is shipped to the interpreter AS STDIN DATA,
   never string-concatenated into a shell line — write any Python, no
   quoting or escaping games. Optional :seon.agent.shell/args become the
   script's sys.argv[1:]; :seon.agent.shell/cmd overrides the interpreter
   (default \"python3\" — pass a venv's absolute python to select it).

   Worked example (Clojure strings take literal newlines — multi-line
   source is just a string):

     (seon.agent.shell/py-run {:seon.agent.shell/source \"import sys\\nprint(21 * 2)\"})
     ; ⟹ «map: ::ok? true, ::exit 0, ::out \"42\\n\", ::err \"\", …»"
  {:malli/schema [:=> [:cat :seon.agent.shell/py-run-request] :seon.agent.shell/run-response]}
  [{:seon.agent.shell/keys [source cmd args cwd timeout-ms]
    :or {cmd "python3"}}]
  (if (or (nil? source) (str/blank? source))
    (in/fail ":seon.agent.shell/source is required and must be non-blank — the Python source text (shipped to the interpreter as stdin).")
    (await (run (cond-> {:seon.agent.shell/cmd   cmd
                         :seon.agent.shell/args  (into ["-"] (or args []))
                         :seon.agent.shell/stdin source}
                  cwd        (assoc :seon.agent.shell/cwd cwd)
                  timeout-ms (assoc :seon.agent.shell/timeout-ms timeout-ms))))))

;; ============================================================
;; Background jobs — spawn, poll, page, stop. Same SEON_SHELL gate + cwd
;; allowlist as run; the job table is volatile (lost on pod restart).
;; ============================================================

(defn run-bg!
  "Spawn a command in the BACKGROUND; return its :seon.agent.shell/job-id.

   For long or high-volume work (a bench test run, a build) that would
   outlast [[run]]'s timeout or overflow its capture ceiling. Same gate as
   run — default-deny until SEON_SHELL, a :seon.agent.shell/cwd under the
   seon.agent.fs allowlist. Returns immediately with ok? true + the job-id
   + :seon.agent.shell/state :running; the child's stdout/stderr accumulate
   in a volatile table (NOT datoms), head-capped per stream at ~2MB (a RAM
   guard). Poll it with [[job-status]], read its output with [[job-output]]
   (full-so-far or incremental via ::since), and SIGTERM it with
   [[job-stop!]]. The table is lost on a pod restart (and the oldest finished
   jobs are pruned past a cap) — a job is live runtime state, not a persisted
   fact; my.blob/put! its output if you need it durable.

     (seon.agent.shell/run-bg! {:seon.agent.shell/cmd  \"pytest\"
                                :seon.agent.shell/args [\"-q\"]
                                :seon.agent.shell/cwd  \"/Users/me/work-repo\"})
     ; ⟹ «map: ::ok? true, ::job-id \"job-1a2b3c4d\", ::state :running, ::cmd \"pytest\"»"
  {:malli/schema [:=> [:cat :seon.agent.shell/run-bg-request] :seon.agent.shell/run-bg-response]}
  [{:seon.agent.shell/keys [cmd args cwd stdin]}]
  (try
    (cond
      (not= :node (platform/host))
      (in/fail "seon.agent.shell requires the :node host (no :wasi child processes).")

      (not (in/granted?))
      (in/ungranted)

      (or (nil? cmd) (str/blank? cmd))
      (in/fail ":seon.agent.shell/cmd is required and must be non-blank — argv[0], PATH-resolved (e.g. \"pytest\").")

      :else
      (if-let [denied (when cwd (in/gate-cwd cwd))]
        denied
        (let [id (in/start-job! cmd (vec (or args [])) cwd stdin)]
          {:seon.agent.shell/ok?     true
           :seon.agent.shell/job-id  id
           :seon.agent.shell/state   :running
           :seon.agent.shell/cmd     cmd})))
    (catch :default e
      (in/fail (str "unexpected error in seon.agent.shell/run-bg!: "
                    (or (some-> e .-message) (str e)))))))

(defn- job-summary
  "A background job record → its compact data summary (sizes in tokens)."
  [j]
  (cond-> {:seon.agent.shell/job-id     (:seon.agent.shell/job-id j)
           :seon.agent.shell/cmd        (:seon.agent.shell/cmd j)
           :seon.agent.shell/state      (:seon.agent.shell/state j)
           :seon.agent.shell/runtime-ms (in/runtime-ms j)
           :seon.agent.shell/out-tokens (tokens/estimate (:seon.agent.shell/out j))
           :seon.agent.shell/err-tokens (tokens/estimate (:seon.agent.shell/err j))}
    (some? (:seon.agent.shell/exit j))
    (assoc :seon.agent.shell/exit (:seon.agent.shell/exit j))))

(defn- mine?
  "True when job `j` belongs to the CURRENT caller's scope — the per-agent
   filter. Nil `j` (an unknown/pruned id) is never mine. Otherwise scope
   equality: a job whose :seon.agent.shell/agent-id is nil
   (spawned outside any agent scope, e.g. a dev REPL or a test) is visible ONLY
   to an equally-unscoped caller (nil = nil), and NEVER appears in any scoped
   agent's list (a real agent-id never equals nil). A scoped agent sees only
   its own jobs; another agent's job is invisible."
  [j]
  (and (some? j)
       (= (:seon.agent.shell/agent-id j) (db/current-agent-id))))

(defn list-jobs
  "This agent's background jobs in the volatile table, newest-first.

   Scoped to the CURRENT agent (the reactive :seon.agent/id filter): background
   jobs are volatile per-agent runtime artifacts, so an agent sees only the
   jobs IT launched — never another agent's. The reactive :jobs context section
   renders from this. Sizes are TOKENS. The table is process-volatile (running
   + recently-finished jobs; oldest finished pruned past a cap)."
  {:malli/schema [:=> [:cat] :seon.agent.shell/list-response]}
  []
  {:seon.agent.shell/ok?  true
   :seon.agent.shell/jobs (->> (vals @in/!jobs)
                               (filter mine?)
                               (sort-by #(- (.getTime (:seon.agent.shell/started-at %))))
                               (mapv job-summary))})

(defn job-status
  "Report a background job's state, runtime, and pending-output sizes.

   :seon.agent.shell/state is :running / :exited / :stopped;
   :seon.agent.shell/exit is present once finished;
   :seon.agent.shell/runtime-ms is wall-clock so far (or total);
   :seon.agent.shell/out-tokens / err-tokens are the HONEST full captured
   sizes so you know how much [[job-output]] has to show. An unknown id
   (never started, or the pod restarted) is a guiding ok?-false value."
  {:malli/schema [:=> [:cat :seon.agent.shell/job-ref-request] :seon.agent.shell/job-status-response]}
  [{:seon.agent.shell/keys [job-id]}]
  (if-let [j (let [j (get @in/!jobs job-id)] (when (mine? j) j))]
    (let [parsed (when (and (not= :running (:seon.agent.shell/state j))
                            (testrun/pytest-argv? (:seon.agent.shell/cmd j)
                                                  (:seon.agent.shell/args j)))
                   (let [p (testrun/parse {:seon.agent.testrun/stdout (:seon.agent.shell/out j)
                                           :seon.agent.testrun/stderr (:seon.agent.shell/err j)})]
                     (when (:seon.agent.testrun/ok? p) p)))]
      (cond-> {:seon.agent.shell/ok?         true
               :seon.agent.shell/job-id      job-id
               :seon.agent.shell/state       (:seon.agent.shell/state j)
               :seon.agent.shell/cmd         (:seon.agent.shell/cmd j)
               :seon.agent.shell/runtime-ms  (in/runtime-ms j)
               :seon.agent.shell/out-tokens  (tokens/estimate (:seon.agent.shell/out j))
               :seon.agent.shell/err-tokens  (tokens/estimate (:seon.agent.shell/err j))}
        (some? (:seon.agent.shell/exit j))
        (assoc :seon.agent.shell/exit (:seon.agent.shell/exit j))
        (some? parsed)
        (assoc :seon.agent.testrun/result parsed)))
    (in/unknown-job job-id)))

(defn job-output
  "A background job's captured output — full-so-far, or only-new via ::since.

   Reads the chosen :seon.agent.shell/stream (:out default / :err) as an
   ORDINARY eval value (no token cap — display economy is the render layer's;
   a big result stashes as result/<id>). Default returns everything captured
   so far; pass :seon.agent.shell/since (a char offset — the previous call's
   :seon.agent.shell/next-since) to get ONLY output since then, so polling a
   live job streams incrementally. :seon.agent.shell/tokens is the honest full
   size; :seon.agent.shell/truncated? true means the stream hit its ~2MB RAM
   ceiling and later bytes were dropped (head kept). Unknown id → a guiding
   ok?-false value."
  {:malli/schema [:=> [:cat :seon.agent.shell/job-output-request] :seon.agent.shell/job-output-response]}
  [{:seon.agent.shell/keys [job-id stream since]}]
  (if-let [j (let [j (get @in/!jobs job-id)] (when (mine? j) j))]
    (let [stream (or stream :out)
          s      (case stream :err (:seon.agent.shell/err j) (:seon.agent.shell/out j))
          trunc? (case stream
                   :err (:seon.agent.shell/err-truncated? j)
                   (:seon.agent.shell/out-truncated? j))]
      (cond-> (merge {:seon.agent.shell/ok?         true
                      :seon.agent.shell/job-id      job-id
                      :seon.agent.shell/state       (:seon.agent.shell/state j)
                      :seon.agent.shell/stream      stream
                      :seon.agent.shell/tokens      (tokens/estimate (str s))
                      :seon.agent.shell/truncated?  (boolean trunc?)
                      :seon.agent.shell/runtime-ms  (in/runtime-ms j)}
                     (in/slice-since s since))
        (some? (:seon.agent.shell/exit j))
        (assoc :seon.agent.shell/exit (:seon.agent.shell/exit j))))
    (in/unknown-job job-id)))

(defn job-stop!
  "SIGTERM a running background job; return its new state.

   Idempotent — a job already :exited / :stopped is left as-is. The
   captured output stays retrievable via [[job-output]] after stopping.
   Unknown id → a guiding ok?-false value."
  {:malli/schema [:=> [:cat :seon.agent.shell/job-ref-request] :seon.agent.shell/job-stop-response]}
  [{:seon.agent.shell/keys [job-id]}]
  (if (mine? (get @in/!jobs job-id))
    (do (in/stop-job! job-id)
        {:seon.agent.shell/ok?    true
         :seon.agent.shell/job-id job-id
         :seon.agent.shell/state  (:seon.agent.shell/state (get @in/!jobs job-id))})
    (in/unknown-job job-id)))
