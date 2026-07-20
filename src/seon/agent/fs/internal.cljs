(ns seon.agent.fs.internal
  "Implement filesystem capability policy and I/O plumbing.

   This internal namespace resolves and enforces host allowlists, performs the
   synchronous file operations, and builds the public result envelopes. Agents
   use `seon.agent.fs`; none of these helpers form a taught capability surface."
  (:require
    ["node:crypto" :as crypto]
    ["node:fs" :as fs]
    ["node:path" :as np]
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]))

;; ============================================================
;; Content addressing — the sha guard's currency.
;; ============================================================

(defn file-sha
  "SHA-256 hex digest of `content` (utf-8) — the file's content address.

   Same primitive my.blob names a blob by; here it fences an anchored
   edit against a stale read: read → sha → the agent echoes it back on
   the next replace!, a mismatch means the file moved under them."
  [content]
  (-> (.createHash crypto "sha256")
      (.update content "utf8")
      (.digest "hex")))

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

;; ============================================================
;; Live config — seeded from env, replaced by configure!.
;; ============================================================

(defn env-bootstrap
  "Seed the grant from SEON_FS_ROOT / SEON_FS_READ_ONLY. SEON_FS_ROOT
   splits on the platform path-list delimiter (\":\" POSIX, \";\" Windows),
   same as $PATH — one root stays a one-element vector."
  []
  {:seon.agent.fs/allowed-roots
   (when-let [r (config/env-string "SEON_FS_ROOT")]
     (->> (str/split r (re-pattern (str "\\" np/delimiter)))
          (remove str/blank?)
          vec))
   :seon.agent.fs/read-only?
   (= "1" (config/env-string "SEON_FS_READ_ONLY"))})

(defonce !config (atom (or (env-bootstrap) {})))

(defn fs-locked?
  "True when the HOST locked the grant via SEON_FS_LOCK. Read live from
   the env on every call — the host owns the knob; nothing inside the pod
   can flip it. Any non-blank value other than \"0\" locks."
  []
  (let [v (config/env-string "SEON_FS_LOCK")]
    (boolean (and v (not= "0" v)))))

(defn read-only? []
  (boolean (:seon.agent.fs/read-only? @!config)))

(defn allowed-roots []
  (vec (:seon.agent.fs/allowed-roots @!config)))

;; ============================================================
;; Path scoping — resolve, then test against the allowlist.
;; ============================================================

(defn resolve-abs
  "Normalize `path` to an absolute, `..`-resolved string."
  [path]
  (try (.resolve np path) (catch :default _ nil)))

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
   the allowlist is empty (default-deny)."
  [path]
  (let [roots (allowed-roots)]
    (or (empty? roots)
        (let [abs (resolve-abs path)]
          (or (nil? abs)
              (not (some #(under-root? abs %) roots)))))))

(defn scope-denied [path]
  (let [roots (allowed-roots)]
    (assoc (denied path
                   (if (empty? roots)
                     "seon.agent.fs has no allowed-roots configured (default-deny). Call (seon.agent.fs/configure! {:seon.agent.fs/allowed-roots [...]}) or set SEON_FS_ROOT."
                     (str "path outside allowed-roots " (pr-str roots))))
           :seon.agent.fs/denial :allowlist)))

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

;; ============================================================
;; edit-file plumbing — pure line/string surgery over file content.
;; ============================================================

(def edit-context-lines
  "Lines of surrounding context returned around an in-place edit."
  3)

(def edit-context-max-tokens
  "Token cap on the edit-result context window (seon.ai.tokens/estimate)."
  200)

(defn content->lines
  "Split file `content` into [lines trailing-newline?] — the trailing
   \"\" pseudo-line a final newline produces is dropped, so `lines`
   counts what an editor shows (same convention as [[page-lines]])."
  [content]
  (let [raw       (str/split content #"\n" -1)
        trailing? (and (seq raw) (= "" (peek raw)) (pos? (count content)))]
    [(if trailing? (pop raw) raw) trailing?]))

(defn replacement->lines
  "Replacement text as a vector of lines — empty string means DELETE
   (zero lines); a trailing newline doesn't add a phantom blank line."
  [replacement]
  (if (= "" replacement)
    []
    (let [ls (str/split replacement #"\n" -1)]
      (if (and (> (count ls) 1) (= "" (peek ls))) (pop ls) ls))))

(defn edit-context-window
  "A capped window of `lines` around the changed 1-based inclusive
   [from to] range — enough for the agent to SEE the edit landed
   without a full re-read. Token-capped via [[edit-context-max-tokens]]."
  [lines from to]
  (let [total (count lines)]
    (if (zero? total)
      {:seon.agent.fs/context           ""
       :seon.agent.fs/context-from-line 1
       :seon.agent.fs/truncated?        false}
      (let [from  (min (max 1 from) total)
            to    (min (max from to) total)
            start (max 1 (- from edit-context-lines))
            end   (min total (+ to edit-context-lines))
            s     (str/join "\n" (subvec lines (dec start) end))
            over? (> (tokens/estimate s) edit-context-max-tokens)]
        {:seon.agent.fs/context           (if over?
                                            (str (subs s 0 (tokens/estimate-chars edit-context-max-tokens)) "…")
                                            s)
         :seon.agent.fs/context-from-line start
         :seon.agent.fs/truncated?        over?}))))

(defn line-range-edit
  "Replace 1-based inclusive lines [from to] of `content` with
   `replacement`. Returns {:seon.agent.fs/error <s>} when the range is
   out of bounds, else the new content + the changed-range facts."
  [content from to replacement]
  (let [[lines trailing?] (content->lines content)
        total             (count lines)]
    (cond
      (< from 1)
      {:seon.agent.fs/error (str "from-line must be >= 1 (got " from ")")}

      (< to from)
      {:seon.agent.fs/error (str "to-line " to " is before from-line " from
                                 " (range is 1-based INCLUSIVE)")}

      (> to total)
      {:seon.agent.fs/error (str "to-line " to " is past the end — the file has "
                                 total " lines; re-read it and retry")}

      :else
      (let [new-lines (replacement->lines replacement)
            spliced   (-> (subvec lines 0 (dec from))
                          (into new-lines)
                          (into (subvec lines to)))]
        {:seon.agent.fs/new-content    (cond-> (str/join "\n" spliced)
                                         trailing? (str "\n"))
         :seon.agent.fs/new-lines      spliced
         :seon.agent.fs/from-line      from
         :seon.agent.fs/lines-replaced (inc (- to from))
         :seon.agent.fs/lines-inserted (count new-lines)}))))

(defn count-matches
  "Occurrences of `s` in `content` plus the first match index —
   [n first-idx]. Non-overlapping, exact."
  [content s]
  (loop [idx 0 n 0 first-idx nil]
    (if-let [i (str/index-of content s idx)]
      (recur (+ i (count s)) (inc n) (or first-idx i))
      [n first-idx])))

(defn match-edit
  "Replace the UNIQUE exact occurrence of `old-string` in `content`
   with `new-string`. 0 or >1 matches → {:seon.agent.fs/error <s>}
   (the safe editor primitive: never guess which match was meant)."
  [content old-string new-string]
  (if (= "" old-string)
    {:seon.agent.fs/error "old-string must be non-empty"}
    (let [[n idx] (count-matches content old-string)]
      (case n
        0 {:seon.agent.fs/error
           (str "old-string not found (0 matches) — read the file and copy "
                "the EXACT text, including whitespace")}
        1 (let [new-content (str (subs content 0 idx)
                                 new-string
                                 (subs content (+ idx (count old-string))))
                from        (inc (count (re-seq #"\n" (subs content 0 idx))))
                [new-lines _] (content->lines new-content)]
            {:seon.agent.fs/new-content    new-content
             :seon.agent.fs/new-lines      new-lines
             :seon.agent.fs/from-line      from
             :seon.agent.fs/lines-replaced (inc (count (re-seq #"\n" old-string)))
             :seon.agent.fs/lines-inserted (inc (count (re-seq #"\n" new-string)))})
        {:seon.agent.fs/error
         (str "old-string is AMBIGUOUS (" n " matches) — include more "
              "surrounding context to make it unique, or use the "
              "from-line/to-line range mode")}))))

(defn glob->re
  "Compile a shell glob to an anchored regex.

   `**/` → zero or more path segments (globstar), `**` → any run (incl.
   `/`), `*` → any run of non-`/`, `?` → one non-`/`. Every other regex
   metacharacter is escaped so a literal `.` or `(` matches itself. One
   ordered token pass — no placeholder round-trip."
  [glob]
  (let [pat (str/replace
              glob
              #"\*\*/|\*\*|\*|\?|[.+^${}()|\[\]\\]|[^*?]"
              (fn [m]
                (case m
                  "**/" "(?:.*/)?"
                  "**"  ".*"
                  "*"   "[^/]*"
                  "?"   "[^/]"
                  (if (re-matches #"[.+^${}()|\[\]\\]" m) (str "\\" m) m))))]
    (re-pattern (str "^" pat "$"))))


(defn walk-pred
  "A file-path predicate combining an optional `match-ext` suffix and an
   optional `glob`. The glob matches the root-RELATIVE path (forward
   slashes); a slash-free glob (e.g. `*.md`) also matches the basename, so
   it selects those files at any depth. Absent filters pass everything."
  [root match-ext glob]
  (let [re        (when glob (glob->re glob))
        basename? (and glob (not (str/includes? glob "/")))]
    (fn [full]
      (and (or (nil? match-ext) (str/ends-with? full match-ext))
           (or (nil? re)
               (let [rel (.relative np root full)]
                 (boolean (or (re-matches re rel)
                              (and basename? (re-matches re (.basename np full)))))))))))

(defn sort-by-mtime
  "Order absolute `paths` newest-first by mtime; an unstatable path sinks
   to the end (mtime 0). Statting is bounded — the caller already capped
   `paths` at max-results."
  [paths]
  (->> paths
       (map (fn [p] [p (try (.getTime (.-mtime (.statSync fs p)))
                            (catch :default _ 0))]))
       (sort-by (comp - second))
       (mapv first)))

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
