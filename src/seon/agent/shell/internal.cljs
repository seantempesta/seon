(ns seon.agent.shell.internal
  "Plumbing behind `seon.agent.shell` — the hard caps, the envelope
   helpers, the `SEON_SHELL` grant read, the seon.agent.fs cwd gate, and
   the `execFile` wrapper (cloned from `seon.agent.search.internal`, the
   proven exemplar, with the rg-specific parsing swapped for generic
   exit/out/err classification).

   This namespace is INTERNAL: it is never rendered into agent context
   (the `*.internal` ns name IS the filter). Agents call the public face
   in `seon.agent.shell`; nothing here is part of the taught surface.

   All map keys stay in the `:seon.agent.shell/*` namespace (via
   `:as-alias`): the keyword namespace tracks the OWNING DATA namespace
   (`seon.agent.shell`), not the file the code lives in — same rule as
   fs/search. (toolkit.md's §my.shell sketch spells the keys
   `:seon.shell/*`; the code ns is the truth, per the root convention.)"
  (:require
    ["node:child_process" :as cp]
    [clojure.string :as str]
    [seon.agent.fs :as fs]
    [seon.agent.shell :as-alias shell]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.platform :as platform]))

;; ============================================================
;; Hard caps.
;; ============================================================

(def default-timeout-ms
  "SIGTERM the child after this long when the request doesn't say
   (execFile :timeout)."
  30000)

(def max-output-bytes
  "execFile :maxBuffer — the FULL-capture ceiling for a foreground [[run]],
   mirroring web-fetch's 2MB body cap. Per-stream output up to this is
   captured whole (and, when it exceeds the preview cap, persisted to
   my.blob so nothing is discarded at the boundary); beyond it Node kills
   the child and ::shell/truncated? is set with the honest byte overflow.
   Long/high-volume output belongs in a background job (spawn-based, not
   maxBuffer-bounded)."
  2000000)

(def killed-exit
  "Deterministic exit sentinel for a SIGTERM-killed child (timeout or
   output-buffer overflow): 128 + SIGTERM(15), the POSIX-shell
   convention. The agent keys off ::shell/timed-out?, not the sentinel."
  143)

;; ============================================================
;; Envelope helpers — errors are values, never a throw.
;; ============================================================

(defn fail
  "ok?-false envelope on the shared :seon.error/* shape. `data`
   (optional map) carries structured detail."
  ([msg] (fail msg nil))
  ([msg data]
   (cond-> {::shell/ok?         false
            :seon.error/message msg}
     (seq data) (assoc :seon.error/data data))))

;; ============================================================
;; The SEON_SHELL grant — host-owned, read live, default-deny.
;; ============================================================

(defn granted?
  "True when the HOST granted shell access via SEON_SHELL. Read live
   from the env on every call — the host owns the knob; nothing inside
   the pod can flip it. Any non-blank value other than \"0\" grants."
  []
  (case (platform/host)
    :node (let [v (config/env-string "SEON_SHELL")]
            (boolean (and v (not= "0" v))))
    :wasi false))

(defn ungranted
  "The guiding default-deny envelope."
  []
  (fail (str "shell access is not granted (default-deny) — the host must "
             "set the SEON_SHELL env var (any value but \"0\") before the "
             "pod starts; nothing inside the pod can grant it. Inspect "
             "with (seon.agent.shell/grants).")))

;; ============================================================
;; cwd gate — delegate to seon.agent.fs, never reimplement.
;; ============================================================

(defn gate-cwd
  "nil when `cwd` is an allowlisted directory; otherwise the ok?-false
   envelope. Delegates normalization + allowlist + existence to
   seon.agent.fs/stat (the same gate read-file and search use), so shell
   and fs always agree on what is reachable."
  [cwd]
  (let [{ok?   :seon.agent.fs/ok?
         dir?  :seon.agent.fs/dir?
         error :seon.agent.fs/error} (fs/stat {:seon.agent.fs/path cwd})]
    (cond
      (not ok?)
      (fail (str ":seon.agent.shell/cwd " (pr-str cwd) " is not usable — ask "
                 "your human to grant access via (seon.agent.fs/configure! "
                 "{:seon.agent.fs/allowed-roots [...]}). seon.agent.fs "
                 "said: " error))

      (not dir?)
      (fail (str ":seon.agent.shell/cwd " (pr-str cwd)
                 " is not a directory.")))))

;; ============================================================
;; Output discipline — token-capped streams, honest totals.
;; ============================================================

(defn ran-envelope
  "The ok?-true envelope: the process RAN and exit/out/err is the answer.

   Returns the FULL stdout/stderr — no token cap at the verb. Display
   economy is the render layer's job (a large value stashes as result/<id>
   and renders as a bounded skeleton with an honest ⟨N tokens⟩ head + the
   result handle; aged transcript clips decay). ::shell/out-tokens /
   ::shell/err-tokens are the honest sizes. ::shell/truncated? is set ONLY
   when output overflowed the [[max-output-bytes]] capture ceiling — a RAM
   guard, not display economy: bytes beyond it were dropped and a
   ::shell/hint points at run-bg! for unbounded streams."
  [exit out err timed-out? buffer-truncated?]
  (let [out (str out)
        err (str err)]
    (cond-> {::shell/ok?        true
             ::shell/exit       exit
             ::shell/out        out
             ::shell/err        err
             ::shell/out-tokens (tokens/estimate out)
             ::shell/err-tokens (tokens/estimate err)
             ::shell/timed-out? (boolean timed-out?)
             ::shell/truncated? (boolean buffer-truncated?)}
      buffer-truncated?
      (assoc ::shell/hint
             (str "output overflowed the " max-output-bytes "-byte capture "
                  "ceiling — bytes beyond that were dropped (a RAM guard, not "
                  "display). For an unbounded or long-running stream use "
                  "(seon.agent.shell/run-bg! …) and page it with job-output.")))))

(defn exit-code
  "The child's exit code as data: 0 when execFile reported no error, the
   numeric error code when it exited non-zero, [[killed-exit]] when it
   was killed (no numeric code — timeout / overflow / external signal)."
  [err]
  (cond
    (nil? err)             0
    (number? (.-code err)) (.-code err)
    :else                  killed-exit))

;; ============================================================
;; The child_process boundary — execFile wrapper, always resolves.
;; ============================================================

(defn exec
  "Run `cmd` with `args` (vector of argv strings — NEVER a shell string;
   no `sh -c`, no injection surface). ALWAYS resolves, to a JS object
   {err stdout stderr} (err nil on exit 0). Timeout + output cap enforced
   by execFile options; `stdin` (a string or nil) is written to the
   child then the stream is ALWAYS closed so stdin-readers see EOF."
  [cmd args cwd stdin timeout-ms]
  (js/Promise.
    (fn [resolve _]
      (let [opts  #js {:timeout     timeout-ms
                       :maxBuffer   max-output-bytes
                       :windowsHide true}
            _     (when cwd (set! (.-cwd opts) cwd))
            child (.execFile cp cmd (into-array args) opts
                             (fn [err stdout stderr]
                               (resolve #js {:err err :stdout stdout :stderr stderr})))
            in    (.-stdin child)]
        (when in
          ;; A fast-exiting child EPIPEs the stdin write; an unhandled
          ;; stream error would crash the single-threaded pod. Swallow it
          ;; — the callback envelope carries the child's real outcome.
          (.on in "error" (fn [_] nil))
          (when (some? stdin) (.write in stdin))
          (.end in))))))

;; ============================================================
;; Background jobs — a VOLATILE process-lifetime table (globalThis tier,
;; NEVER datoms; no tmp-file tee). A long-running child spawns; its stdout /
;; stderr accumulate here (head-capped per stream at [[bg-max-stream-bytes]],
;; a RAM guard) and [[slice-since]] serves the full-so-far window on demand.
;; Lost on pod restart — honest, because the child process dies too; a job
;; is live runtime state, not a persisted fact.
;; ============================================================

(def bg-max-stream-bytes
  "Per-stream RAM ceiling for a background job (same ~2MB guard as run's
   capture ceiling). Output past this is dropped, keeping the HEAD so
   ::shell/since offsets stay stable, and the stream is flagged truncated."
  2000000)

(def max-exited-jobs
  "Cap on retained finished (:exited/:stopped) job records — the oldest are
   pruned when a job ends and the count exceeds this. Running jobs are never
   pruned."
  32)

;; Job id -> record. Each: {::shell/job-id ::shell/cmd ::shell/args
;; ::shell/cwd ::shell/started-at ::shell/child ::shell/out ::shell/err
;; ::shell/out-truncated? ::shell/err-truncated? ::shell/state ::shell/exit
;; ::shell/ended-at}. Volatile — process-lifetime only.
(defonce !jobs (atom {}))

(defn unknown-job
  "The ok?-false envelope for an id absent from the volatile job table."
  [id]
  (fail (str "no background job " (pr-str id) " — it never started, was "
             "pruned (oldest finished jobs are dropped past a cap), or the pod "
             "restarted (the table is volatile). Launch one with "
             "(seon.agent.shell/run-bg! …).")))

(defn runtime-ms
  "Wall-clock milliseconds a job has run — to `ended-at` if finished, else now."
  [j]
  (let [start (.getTime (::shell/started-at j))
        end   (if-let [e (::shell/ended-at j)] (.getTime e) (.now js/Date))]
    (max 0 (- end start))))

(defn- append-capped
  "Append `chunk` to job `id`'s `out-key`, HEAD-capped at bg-max-stream-bytes.

   Once the ceiling is hit `trunc-key` flips true and further output is
   dropped — the head is retained so a ::shell/since offset never shifts."
  [id out-key trunc-key chunk]
  (swap! !jobs update id
         (fn [j]
           (if (or (nil? j) (get j trunc-key))
             j
             (let [s' (str (get j out-key) (str chunk))]
               (if (> (count s') bg-max-stream-bytes)
                 (assoc j out-key (subs s' 0 bg-max-stream-bytes) trunc-key true)
                 (assoc j out-key s')))))))

(defn- prune-exited!
  "Drop the oldest finished job records beyond [[max-exited-jobs]]."
  []
  (swap! !jobs
         (fn [jobs]
           (let [finished (->> (vals jobs) (remove #(= :running (::shell/state %))))
                 over     (- (count finished) max-exited-jobs)]
             (if (pos? over)
               (let [drop-ids (->> finished
                                   ;; a just-stopped job may not have its
                                   ;; close event yet (ended-at nil) — sort it
                                   ;; newest so it is never the pruned one.
                                   (sort-by #(or (some-> (::shell/ended-at %) .getTime)
                                                 js/Number.MAX_SAFE_INTEGER))
                                   (take over)
                                   (map ::shell/job-id)
                                   set)]
                 (apply dissoc jobs drop-ids))
               jobs)))))

(defn start-job!
  "Spawn `cmd`/`args` in the background and register a job; return its id."
  [cmd args cwd stdin]
  (let [id    (str "job-" (subs (str (random-uuid)) 0 8))
        opts  #js {:windowsHide true}
        _     (when cwd (aset opts "cwd" cwd))
        child (.spawn cp cmd (into-array args) opts)]
    (swap! !jobs assoc id
           {::shell/job-id        id
            ::shell/cmd           cmd
            ::shell/args          (vec args)
            ::shell/cwd           cwd
            ::shell/started-at    (js/Date.)
            ::shell/child         child
            ::shell/out           ""
            ::shell/err           ""
            ::shell/out-truncated? false
            ::shell/err-truncated? false
            ::shell/state         :running
            ::shell/exit          nil
            ::shell/ended-at      nil})
    (.on (.-stdout child) "data"
         (fn [d] (append-capped id ::shell/out ::shell/out-truncated? d)))
    (.on (.-stderr child) "data"
         (fn [d] (append-capped id ::shell/err ::shell/err-truncated? d)))
    (.on child "error"
         (fn [e]
           (swap! !jobs update id
                  (fn [j] (when j
                            (assoc j
                                   ::shell/state    :exited
                                   ::shell/exit     killed-exit
                                   ::shell/ended-at (js/Date.)
                                   ::shell/err      (str (::shell/err j)
                                                         "\n[spawn error] "
                                                         (or (some-> e .-message) (str e)))))))
           (prune-exited!)))
    (.on child "close"
         (fn [code signal]
           (swap! !jobs update id
                  (fn [j] (when j
                            (assoc j
                                   ::shell/state    (if (= :stopped (::shell/state j)) :stopped :exited)
                                   ::shell/exit     (cond (number? code) code signal killed-exit :else 0)
                                   ::shell/ended-at (js/Date.)))))
           (prune-exited!)))
    (let [in (.-stdin child)]
      (when in
        (.on in "error" (fn [_] nil))
        (when (some? stdin) (.write in stdin))
        (.end in)))
    id))

(defn stop-job!
  "SIGTERM a running job; mark it :stopped. Idempotent on a finished job."
  [id]
  (let [j (get @!jobs id)]
    (when (and j (= :running (::shell/state j)))
      (swap! !jobs assoc-in [id ::shell/state] :stopped)
      (try (.kill ^js (::shell/child j) "SIGTERM") (catch :default _ nil)))))

(defn slice-since
  "The captured stream `s` from char offset `since` (default 0) to the end.

   Full-so-far minus what a prior poll already read — returns ::shell/content
   plus ::shell/since (clamped, echoed) and ::shell/next-since (the new end
   offset to pass as ::since next time to fetch only new output)."
  [s since]
  (let [s     (str s)
        total (count s)
        from  (min (max 0 (or since 0)) total)]
    {::shell/content    (subs s from)
     ::shell/since      from
     ::shell/next-since total}))
