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
  "execFile :maxBuffer — per-stream child output beyond this is dropped
   by Node and the child is killed; the partial output IS still returned
   with ::shell/truncated? true. Same cap as seon.agent.search.internal."
  (* 8 1024 1024))

(def default-max-output-tokens
  "DEFAULT per-stream cap, in TOKENS (seon.ai.tokens/estimate), on the
   stdout/stderr text placed in the envelope. Modest on purpose: the
   envelope renders into agent context and threads into other verbs — a
   chatty build log must not blow the turn. The honest full-size totals
   (::shell/out-tokens / ::shell/err-tokens) are always reported."
  2048)

(def hard-max-output-tokens
  "Ceiling a request's :seon.agent.shell/max-output-tokens is clamped to —
   the context-explosion guard. Beyond this, redirect the command's
   output to a file and page it with seon.agent.fs/read-file."
  16384)

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

(defn cap-stream
  "Cap `s` at `max-tokens` (TOKENS, not chars), marking a cut with an
   ellipsis. Returns the capped ::shell/text plus the HONEST full-size
   ::shell/tokens and a ::shell/clipped? flag — a partial stream never
   looks complete."
  [s max-tokens]
  (let [s     (str s)
        total (tokens/estimate s)]
    (if (> total max-tokens)
      {::shell/text     (str (subs s 0 (tokens/estimate-chars max-tokens)) "…")
       ::shell/tokens   total
       ::shell/clipped? true}
      {::shell/text s ::shell/tokens total ::shell/clipped? false})))

(defn ran-envelope
  "The ok?-true envelope: the process RAN and exit/out/err is the
   answer, whatever the exit code. Streams are token-capped at
   `max-tokens` with honest full-size totals; ::shell/truncated? is true
   when ANYTHING was dropped (token cap or the process-buffer cap), with
   a ::shell/hint naming how to get more."
  [exit out err timed-out? buffer-truncated? max-tokens]
  (let [o        (cap-stream out max-tokens)
        e        (cap-stream err max-tokens)
        clipped? (boolean (or buffer-truncated?
                              (::shell/clipped? o)
                              (::shell/clipped? e)))]
    (cond-> {::shell/ok?        true
             ::shell/exit       exit
             ::shell/out        (::shell/text o)
             ::shell/err        (::shell/text e)
             ::shell/out-tokens (::shell/tokens o)
             ::shell/err-tokens (::shell/tokens e)
             ::shell/timed-out? (boolean timed-out?)
             ::shell/truncated? clipped?}
      clipped?
      (assoc ::shell/hint
             (str "output clipped — full stdout ~" (::shell/tokens o)
                  " tok / stderr ~" (::shell/tokens e) " tok, shown up to "
                  max-tokens " tok each"
                  (when buffer-truncated?
                    (str ", and the child overflowed the " max-output-bytes
                         "-byte process buffer (earlier output is gone)"))
                  ". Raise :seon.agent.shell/max-output-tokens (hard cap "
                  hard-max-output-tokens "), or redirect the command's "
                  "output to a file under your fs roots and page it with "
                  "seon.agent.fs/read-file.")))))

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
