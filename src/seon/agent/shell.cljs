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

     (seon.agent.shell/grants)   ;; the SEON_SHELL grant — call this first
     (seon.agent.shell/run {:seon.agent.shell/cmd  \"git\"
                            :seon.agent.shell/args [\"status\" \"--porcelain\"]
                            :seon.agent.shell/cwd  \"/Users/me/work-folder\"})
     ;; => {:seon.agent.shell/ok? true :seon.agent.shell/exit 0
     ;;     :seon.agent.shell/out \"…\" :seon.agent.shell/err \"\"
     ;;     :seon.agent.shell/out-tokens 42 :seon.agent.shell/err-tokens 0
     ;;     :seon.agent.shell/timed-out? false :seon.agent.shell/truncated? false}
     (seon.agent.shell/py-run {:seon.agent.shell/source \"import sys\\nprint(sys.version)\"})

   Plumbing (caps, grant read, cwd gate, execFile wrapper) lives in
   [[seon.agent.shell.internal]]."
  (:require
    [clojure.string :as str]
    [seon.agent.shell.internal :as in]
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
(schema/register! :seon.agent.shell/max-output-tokens :int)        ; per-stream envelope cap
(schema/register! :seon.agent.shell/source     [:string {:min 1}]) ; python source (py-run)

(schema/register! :seon.agent.shell/ok?        :boolean)           ; "the process RAN", NOT "exit 0"
(schema/register! :seon.agent.shell/exit       :int)               ; the real exit code (may be non-zero)
(schema/register! :seon.agent.shell/out        :string)            ; stdout, token-capped
(schema/register! :seon.agent.shell/err        :string)            ; stderr, token-capped
(schema/register! :seon.agent.shell/out-tokens :int)               ; honest FULL stdout size (tokens)
(schema/register! :seon.agent.shell/err-tokens :int)               ; honest FULL stderr size (tokens)
(schema/register! :seon.agent.shell/timed-out? :boolean)
(schema/register! :seon.agent.shell/truncated? :boolean)           ; anything dropped (token or byte cap)
(schema/register! :seon.agent.shell/hint       :string)
(schema/register! :seon.agent.shell/granted?   :boolean)

(schema/register! :seon.agent.shell/run-request
  [:map
   [:seon.agent.shell/cmd               :seon.agent.shell/cmd]
   [:seon.agent.shell/args              {:optional true} :seon.agent.shell/args]
   [:seon.agent.shell/cwd               {:optional true} :seon.agent.shell/cwd]
   [:seon.agent.shell/stdin             {:optional true} :seon.agent.shell/stdin]
   [:seon.agent.shell/timeout-ms        {:optional true} :seon.agent.shell/timeout-ms]
   [:seon.agent.shell/max-output-tokens {:optional true} :seon.agent.shell/max-output-tokens]])

(schema/register! :seon.agent.shell/py-run-request
  [:map
   [:seon.agent.shell/source            :seon.agent.shell/source]
   [:seon.agent.shell/cmd               {:optional true} :seon.agent.shell/cmd]
   [:seon.agent.shell/args              {:optional true} :seon.agent.shell/args]
   [:seon.agent.shell/cwd               {:optional true} :seon.agent.shell/cwd]
   [:seon.agent.shell/timeout-ms        {:optional true} :seon.agent.shell/timeout-ms]
   [:seon.agent.shell/max-output-tokens {:optional true} :seon.agent.shell/max-output-tokens]])

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
    [:seon.agent.shell/hint       {:optional true} :seon.agent.shell/hint]]
   ;; COULD-NOT-RUN — gate/spawn failure. Shared error map, never a bare
   ;; string.
   [:map
    [:seon.agent.shell/ok?     [:= false]]
    [:seon.error/message :string]
    [:seon.error/data    {:optional true} :map]]])

(schema/register! :seon.agent.shell/grants-response
  [:map [:seon.agent.shell/granted? :seon.agent.shell/granted?]])

;; ============================================================
;; Public API
;; ============================================================

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
   exit sentinel 143, partial output still delivered). stdout/stderr are
   token-capped (default 2048/stream, :seon.agent.shell/max-output-tokens to
   raise, hard cap 16384) with honest full-size totals + a hint when
   clipped. :seon.agent.shell/cwd (optional) must sit under the seon.agent.fs
   allowlist; the whole verb is default-deny until the host grants
   SEON_SHELL.

   Worked example — run, then thread the output onward:

     (seon.agent.shell/run {:seon.agent.shell/cmd  \"git\"
                            :seon.agent.shell/args [\"status\" \"--porcelain\"]
                            :seon.agent.shell/cwd  \"/Users/me/work-folder\"})
     ;; => {:seon.agent.shell/ok? true :seon.agent.shell/exit 0 :seon.agent.shell/out \"…\" …}
     ;; (zero? (:seon.agent.shell/exit r)) → clean tree; split :seon.agent.shell/out
     ;; into lines, transform, db/transact!."
  {:malli/schema [:=> [:cat :seon.agent.shell/run-request] :seon.agent.shell/run-response]}
  [{:seon.agent.shell/keys [cmd args cwd stdin timeout-ms max-output-tokens]
    :or {timeout-ms        in/default-timeout-ms
         max-output-tokens in/default-max-output-tokens}}]
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
        (let [max-tok (min (max 1 max-output-tokens) in/hard-max-output-tokens)
              ^js r   (await (in/exec cmd (vec (or args [])) cwd stdin timeout-ms))
              ^js err (.-err r)
              stdout  (str (.-stdout r))
              stderr  (str (.-stderr r))]
          (cond
            ;; Binary not found — could not run at all.
            (and err (= "ENOENT" (.-code err)))
            (in/fail (str "command not found: " (pr-str cmd) " — argv[0] is "
                          "PATH-resolved; check the name or use an absolute "
                          "path.")
                     {:seon.agent.shell/cmd cmd})

            ;; Output-buffer overflow — the child was killed, but the
            ;; partial output IS the (truncated) answer.
            (and err (= "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" (.-code err)))
            (in/ran-envelope (in/exit-code err) stdout stderr false true max-tok)

            ;; Timeout — execFile SIGTERM'd the child; deliver the honest
            ;; partial output with the authoritative timed-out? flag.
            (and err (.-killed err))
            (in/ran-envelope in/killed-exit stdout stderr true false max-tok)

            ;; Ran (exit 0 or non-zero) — exit/out/err is the answer.
            :else
            (in/ran-envelope (in/exit-code err) stdout stderr false false max-tok)))))
    (catch :default e
      (in/fail (str "unexpected error in seon.agent.shell/run: "
                    (or (some-> e .-message) (str e)))))))

(defn ^:async py-run
  "Run Python source via stdin (`python3 -`); result is data.

   The thin Python specialization of [[run]] — same gate (SEON_SHELL),
   same :seon.agent.shell/run-response envelope, same caps. The load-bearing
   rule: :seon.agent.shell/source is shipped to the interpreter AS STDIN DATA,
   never string-concatenated into a shell line — write any Python, no
   quoting or escaping games. Optional :seon.agent.shell/args become the
   script's sys.argv[1:]; :seon.agent.shell/cmd overrides the interpreter
   (default \"python3\" — pass a venv's absolute python to select it).

   Worked example (Clojure strings take literal newlines — multi-line
   source is just a string):

     (seon.agent.shell/py-run {:seon.agent.shell/source \"import sys\\nprint(21 * 2)\"})
     ;; => {:seon.agent.shell/ok? true :seon.agent.shell/exit 0
     ;;     :seon.agent.shell/out \"42\\n\" :seon.agent.shell/err \"\" …}"
  {:malli/schema [:=> [:cat :seon.agent.shell/py-run-request] :seon.agent.shell/run-response]}
  [{:seon.agent.shell/keys [source cmd args cwd timeout-ms max-output-tokens]
    :or {cmd "python3"}}]
  (if (or (nil? source) (str/blank? source))
    (in/fail ":seon.agent.shell/source is required and must be non-blank — the Python source text (shipped to the interpreter as stdin).")
    (await (run (cond-> {:seon.agent.shell/cmd   cmd
                         :seon.agent.shell/args  (into ["-"] (or args []))
                         :seon.agent.shell/stdin source}
                  cwd               (assoc :seon.agent.shell/cwd cwd)
                  timeout-ms        (assoc :seon.agent.shell/timeout-ms timeout-ms)
                  max-output-tokens (assoc :seon.agent.shell/max-output-tokens max-output-tokens))))))
