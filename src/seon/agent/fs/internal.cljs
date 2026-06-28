(ns seon.agent.fs.internal
  "Filesystem-capability internals — the private data-manipulation +
   allowlist plumbing behind [[seon.agent.fs]]'s public verbs.

   Split out so the teaching namespace stays a clean list of map-in /
   map-out capability fns. Loaded + indexed + grep-able, but NOT
   whitelisted for full-source rendering: agents call the public verbs
   in [[seon.agent.fs]], not these.

   ## Capability config — default-deny allowlist

   [[!config]] holds the live grant:
     :seon.agent.fs/allowed-roots — vector of absolute paths. A path is
         in-scope iff its resolved absolute form lives under one of these
         roots. Empty = nothing allowed (default-deny).
     :seon.agent.fs/read-only?    — when true, write-file refuses.

   [[env-bootstrap]] seeds it from SEON_FS_ROOT / SEON_FS_READ_ONLY at ns
   load; [[seon.agent.fs/configure!]] replaces it (unless SEON_FS_LOCK —
   see [[fs-locked?]]).

   ## Why sync, not async

   The agent evals forms via cljs.js bootstrap; the only auto-await (in
   seon.eval/maybe-await-value) fires on a form's outermost value, not
   inside let-bindings. A Promise-returning fs op would bind `r` to a
   Promise, so `(:seon.agent.fs/ok? r)` returns nil → wrong branch. Sync
   ops hand the agent the resolved map. Local-file perf cost is
   irrelevant; WASI fd reads are sync too, so this survives convergence."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]
    [clojure.string :as str]
    [seon.config :as config]
    [seon.platform :as platform]))

;; ============================================================
;; Error / denial envelopes — errors are values, never a throw.
;; ============================================================

(defn ->err
  "Uniform error response for `path` from caught exception `e`."
  [path e]
  {:seon.agent.fs/ok?   false
   :seon.agent.fs/path  path
   :seon.agent.fs/error (or (some-> e .-message) (str e))})

(defn denied
  "Denial response for `path` with `reason`."
  [path reason]
  {:seon.agent.fs/ok?   false
   :seon.agent.fs/path  path
   :seon.agent.fs/error reason})

(defn wasi-pending
  "Stub response for the :wasi branch until wasi:filesystem/preopens
   lands."
  [path op]
  {:seon.agent.fs/ok?   false
   :seon.agent.fs/path  path
   :seon.agent.fs/error (str ":wasi backend not implemented — " op
                             " requires wasi:filesystem/preopens. "
                             "Run under :node.")})

;; ============================================================
;; Live config — seeded from env, replaced by configure!.
;; ============================================================

(defn env-bootstrap
  "Seed the grant from SEON_FS_ROOT / SEON_FS_READ_ONLY. SEON_FS_ROOT
   splits on the platform path-list delimiter (\":\" POSIX, \";\" Windows),
   same as $PATH — one root stays a one-element vector."
  []
  (let [host (platform/host)]
    {:seon.agent.fs/allowed-roots
     (case host
       :node (when-let [r (config/env-string "SEON_FS_ROOT")]
               (->> (str/split r (re-pattern (str "\\" np/delimiter)))
                    (remove str/blank?)
                    vec))
       :wasi nil)
     :seon.agent.fs/read-only?
     (case host
       :node (= "1" (config/env-string "SEON_FS_READ_ONLY"))
       :wasi true)}))

(defonce !config (atom (or (env-bootstrap) {})))

(defn fs-locked?
  "True when the HOST locked the grant via SEON_FS_LOCK. Read live from
   the env on every call — the host owns the knob; nothing inside the pod
   can flip it. Any non-blank value other than \"0\" locks."
  []
  (case (platform/host)
    :node (let [v (config/env-string "SEON_FS_LOCK")]
            (boolean (and v (not= "0" v))))
    :wasi false))

(defn read-only? []
  (boolean (:seon.agent.fs/read-only? @!config)))

(defn allowed-roots []
  (vec (:seon.agent.fs/allowed-roots @!config)))

;; ============================================================
;; Path scoping — resolve, then test against the allowlist.
;; ============================================================

(defn resolve-abs
  "Normalize `path` to an absolute, `..`-resolved string. nil on :wasi
   (paths there are pre-opened and don't normalize through node:path)."
  [path]
  (case (platform/host)
    :node (try (.resolve np path) (catch :default _ nil))
    :wasi nil))

(defn under-root?
  "True iff `abs-path` is `root` itself or a descendant. Uses the path
   separator boundary to avoid the /foo/bar vs /foobar false-positive."
  [abs-path root]
  (let [r (try (.resolve np root) (catch :default _ root))]
    (or (= abs-path r)
        (str/starts-with? (str abs-path)
                          (if (str/ends-with? r np/sep)
                            r
                            (str r np/sep))))))

(defn out-of-scope?
  "True iff `path` is denied by the current allowlist — always true when
   the allowlist is empty (default-deny) and on :wasi (defers to
   [[wasi-pending]])."
  [path]
  (case (platform/host)
    :node (let [roots (allowed-roots)]
            (or (empty? roots)
                (let [abs (resolve-abs path)]
                  (or (nil? abs)
                      (not (some #(under-root? abs %) roots))))))
    :wasi true))

(defn scope-denied [path]
  (let [roots (allowed-roots)]
    (denied path
            (if (empty? roots)
              "seon.agent.fs has no allowed-roots configured (default-deny). Call (seon.agent.fs/configure! {:seon.agent.fs/allowed-roots [...]}) or set SEON_FS_ROOT."
              (str "path outside allowed-roots " (pr-str roots))))))

;; ============================================================
;; read-file paging + recursive walk.
;; ============================================================

(defn page-lines
  "Slice `content` to the requested 1-based line window. Returns honest
   totals — what range came back of how many total lines — so a partial
   read never looks complete."
  [content from-line max-lines]
  (let [lines (str/split content #"\n" -1)
        ;; a trailing newline yields a final "" pseudo-line — drop it so
        ;; total-lines matches what an editor shows
        lines (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)
        total (count lines)
        from  (max 1 (or from-line 1))
        start (min (dec from) total)
        end   (if max-lines
                (min total (+ start (max 0 max-lines)))
                total)]
    {:seon.agent.fs/content        (str/join "\n" (subvec lines start end))
     :seon.agent.fs/from-line      from
     :seon.agent.fs/lines-returned (- end start)
     :seon.agent.fs/total-lines    total}))

(defn walk-dir-recursive!
  "Depth-first recursive walk (sync). Mutates `!out` (vector of matching
   absolute paths) and `!truncated?` (boolean) once `cap` is hit."
  [dir pred skip-hidden cap !out !truncated?]
  (when-not @!truncated?
    (let [listing (try (.readdirSync fs dir) (catch :default _ nil))]
      (when listing
        (doseq [name (sort (vec listing))
                :when (not @!truncated?)]
          (when-not (and skip-hidden (str/starts-with? name "."))
            (let [full (str dir "/" name)
                  s    (try (.statSync fs full) (catch :default _ nil))]
              (when s
                (cond
                  (.isDirectory s)
                  (walk-dir-recursive! full pred skip-hidden cap !out !truncated?)

                  (and (.isFile s) (pred full))
                  (do (swap! !out conj full)
                      (when (>= (count @!out) cap)
                        (reset! !truncated? true))))))))))))
