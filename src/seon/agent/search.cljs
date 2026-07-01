(ns seon.agent.search
  "Content search over allowed files — ripgrep (`@vscode/ripgrep`) wrapped
   as a core capability. THE EXEMPLAR npm-package wrapper: copy this shape
   when wrapping the next package.

   The wrapper contract:
     - Map-in / map-out: registered `::request`/`::response` schemas, full
       `:malli/schema`, ALL keys namespaced.
     - ERRORS ARE VALUES: every public fn RESOLVES to an envelope —
       `{::ok? true …}` on success, `{::ok? false ::error <guiding message>
       ::raw-error <npm-side detail>}` on failure. Never throws, never
       rejects (same contract as seon.db/transact!). `grep` is `^:async`,
       so instrumentation skips its throwing validator (it would break the
       errors-as-values contract); the `:malli/schema` stays as the
       discoverable contract.
     - CAPABILITY-GATED: search roots are gated through seon.agent.fs's
       allowlist (never reimplemented), so search and read agree on reach.

   gitignore semantics: ignore rules apply relative to the SEARCH ROOT —
   searching the repo root skips node_modules/out/tmp, but a directory you
   were explicitly granted is fully searchable when passed as a root.

   The search→read recipe (the core move) — see [[grep]] for the worked
   example; a hit's `::path` is absolute + allowlisted, so it feeds
   `seon.agent.fs/read-file` directly with no guessing.

   Plumbing (hard caps, envelope helpers, npm boundary, allowlist gate, rg
   --json parser) lives in [[seon.agent.search.internal]]."
  (:require
    [clojure.string :as str]
    [seon.agent.search.internal :as in]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every key registered, request/response named.
;; ============================================================

(schema/register! :seon.agent.search/pattern [:string {:min 1}])
(schema/register! :seon.agent.search/paths [:vector :string])
(schema/register! :seon.agent.search/glob :string)
(schema/register! :seon.agent.search/max-results :int)
(schema/register! :seon.agent.search/case-insensitive? :boolean)
(schema/register! :seon.agent.search/full? :boolean)

(schema/register! :seon.agent.search/ok? :boolean)
(schema/register! :seon.agent.search/error :string)
(schema/register! :seon.agent.search/raw-error :string)
(schema/register! :seon.agent.search/hint :string)

(schema/register! :seon.agent.search/path :string)
(schema/register! :seon.agent.search/line-number :int)
(schema/register! :seon.agent.search/line-text :string)
(schema/register! :seon.agent.search/count :int)

;; A flat match — one hit, returned only under :full? true.
(schema/register! :seon.agent.search/match
  [:map
   [:seon.agent.search/path        :seon.agent.search/path]
   [:seon.agent.search/line-number :seon.agent.search/line-number]
   [:seon.agent.search/line-text   :seon.agent.search/line-text]])

;; A file roll-up — the CONCISE default unit: where the pattern hits, how
;; many times, and the first matching line (preview-capped) as a sample.
(schema/register! :seon.agent.search/file-row
  [:map
   [:seon.agent.search/path        :seon.agent.search/path]
   [:seon.agent.search/count       :seon.agent.search/count]
   [:seon.agent.search/line-number :seon.agent.search/line-number]
   [:seon.agent.search/line-text   :seon.agent.search/line-text]])

(schema/register! :seon.agent.search/matches [:vector :seon.agent.search/match])
(schema/register! :seon.agent.search/by-file [:vector :seon.agent.search/file-row])
(schema/register! :seon.agent.search/match-count :int)
(schema/register! :seon.agent.search/file-count :int)
(schema/register! :seon.agent.search/returned :int)
(schema/register! :seon.agent.search/truncated? :boolean)

;; --- grep-graph: the program-graph counterpart of grep. Same envelope
;; shape (capped rows grouped by a container, honest totals + hint +
;; :full?), but the CONTAINER is the NAMESPACE and a row's sample is a
;; matching MEMBER (fn/schema/ns), not a file line. Shared scalars above
;; (::match-count, ::returned, ::truncated?, ::hint, ::count, ::line-text,
;; ::ok?, ::pattern, ::full?, ::case-insensitive?) are REFERENCED here, not
;; re-registered — the "register shared shapes once" rule.
(schema/register! :seon.agent.search/ns       :string)      ; container — owning namespace
(schema/register! :seon.agent.search/member   :string)      ; the matched fn sym / schema key / ns name (printed)
(schema/register! :seon.agent.search/target   :keyword)     ; which graph attr matched: :seon.fn/:seon.schema/:seon.ns/:seon.eval
(schema/register! :seon.agent.search/ns-count :int)
(schema/register! :seon.agent.search/targets
  [:vector [:enum :seon.fn :seon.schema :seon.ns :seon.eval]])

;; A flat graph match — one matching member, returned only under :full? true.
(schema/register! :seon.agent.search/graph-match
  [:map
   [:seon.agent.search/ns        :seon.agent.search/ns]
   [:seon.agent.search/member    :seon.agent.search/member]
   [:seon.agent.search/target    :seon.agent.search/target]
   [:seon.agent.search/line-text :seon.agent.search/line-text]])

;; A namespace roll-up — the CONCISE default unit: which namespace, how many
;; members matched, and the first matching member (+ a preview line) as a sample.
(schema/register! :seon.agent.search/ns-row
  [:map
   [:seon.agent.search/ns        :seon.agent.search/ns]
   [:seon.agent.search/count     :seon.agent.search/count]
   [:seon.agent.search/member    :seon.agent.search/member]
   [:seon.agent.search/target    :seon.agent.search/target]
   [:seon.agent.search/line-text :seon.agent.search/line-text]])

(schema/register! :seon.agent.search/graph-matches [:vector :seon.agent.search/graph-match])
(schema/register! :seon.agent.search/by-ns [:vector :seon.agent.search/ns-row])

(schema/register! :seon.agent.search/grep-graph-request
  [:map
   [:seon.agent.search/pattern           :seon.agent.search/pattern]
   [:seon.agent.search/targets           {:optional true} :seon.agent.search/targets]
   [:seon.agent.search/max-results       {:optional true} :seon.agent.search/max-results]
   [:seon.agent.search/full?             {:optional true} :seon.agent.search/full?]
   [:seon.agent.search/case-insensitive? {:optional true} :seon.agent.search/case-insensitive?]])

(schema/register! :seon.agent.search/grep-graph-response
  [:map
   [:seon.agent.search/ok?         :seon.agent.search/ok?]
   [:seon.agent.search/match-count {:optional true} :seon.agent.search/match-count]
   [:seon.agent.search/ns-count    {:optional true} :seon.agent.search/ns-count]
   [:seon.agent.search/returned    {:optional true} :seon.agent.search/returned]
   [:seon.agent.search/by-ns       {:optional true} :seon.agent.search/by-ns]
   [:seon.agent.search/matches     {:optional true} :seon.agent.search/graph-matches]
   [:seon.agent.search/truncated?  {:optional true} :seon.agent.search/truncated?]
   [:seon.agent.search/hint        {:optional true} :seon.agent.search/hint]
   [:seon.agent.search/error       {:optional true} :seon.agent.search/error]
   [:seon.agent.search/raw-error   {:optional true} :seon.agent.search/raw-error]])

(schema/register! :seon.agent.search/grep-request
  [:map
   [:seon.agent.search/pattern           :seon.agent.search/pattern]
   [:seon.agent.search/paths             {:optional true} :seon.agent.search/paths]
   [:seon.agent.search/glob              {:optional true} :seon.agent.search/glob]
   [:seon.agent.search/max-results       {:optional true} :seon.agent.search/max-results]
   [:seon.agent.search/full?             {:optional true} :seon.agent.search/full?]
   [:seon.agent.search/case-insensitive? {:optional true} :seon.agent.search/case-insensitive?]])

(schema/register! :seon.agent.search/grep-response
  [:map
   [:seon.agent.search/ok?         :seon.agent.search/ok?]
   [:seon.agent.search/match-count {:optional true} :seon.agent.search/match-count]
   [:seon.agent.search/file-count  {:optional true} :seon.agent.search/file-count]
   [:seon.agent.search/returned    {:optional true} :seon.agent.search/returned]
   [:seon.agent.search/by-file     {:optional true} :seon.agent.search/by-file]
   [:seon.agent.search/matches     {:optional true} :seon.agent.search/matches]
   [:seon.agent.search/truncated?  {:optional true} :seon.agent.search/truncated?]
   [:seon.agent.search/hint        {:optional true} :seon.agent.search/hint]
   [:seon.agent.search/error       {:optional true} :seon.agent.search/error]
   [:seon.agent.search/raw-error   {:optional true} :seon.agent.search/raw-error]])

;; ============================================================
;; Public API
;; ============================================================

(defn ^:async grep
  "Search file CONTENTS under the seon.agent.fs allowed roots.

   `^:async` — returns a Promise that ALWAYS resolves to a
   :seon.agent.search/grep-response envelope (never rejects; errors are values).

   CONCISE by default: hits are GROUPED BY FILE, ranked by hit-count, and
   the top :seon.agent.search/max-results (default 20) file rows are
   returned under :seon.agent.search/by-file — each a {path, count, the
   first matching line-number + a preview-capped line-text}. The HONEST
   totals (:match-count = all hits, :file-count = all files) are always
   reported; when rows were clipped, :seon.agent.search/hint tells you how
   to narrow. This keeps a broad pattern from dumping hundreds of lines —
   you see WHERE the matches cluster, then drill.

   Request keys:
     :seon.agent.search/pattern           REQUIRED — a REGEX (rg syntax), not a
                                    literal: escape ( ) [ ] { } . with \\\\
     :seon.agent.search/paths             optional — files/dirs to search;
                                    DEFAULT = the seon.agent.fs allowed roots
     :seon.agent.search/glob              optional — filename filter, e.g. \"*.cljs\"
     :seon.agent.search/max-results       optional — max FILE ROWS returned
                                    (default 20); :truncated? true when clipped
     :seon.agent.search/full?             optional — when true, return the FLAT
                                    :seon.agent.search/matches list (every line,
                                    capped at max-results) instead of by-file
     :seon.agent.search/case-insensitive? optional

   No matches is SUCCESS: {:seon.agent.search/ok? true
                           :seon.agent.search/by-file [] …count 0}.

   Worked example — find then drill (top-level call, no await: the REPL
   resolves the returned Promise for you):

     (seon.agent.search/grep {:seon.agent.search/pattern \"message/user\"})
     ;; => {:seon.agent.search/ok? true
     ;;     :seon.agent.search/match-count «total hits»
     ;;     :seon.agent.search/file-count  «total files»
     ;;     :seon.agent.search/by-file
     ;;     [{:seon.agent.search/path        \"«abs path of the hit»\"
     ;;       :seon.agent.search/count       «hits in this file»
     ;;       :seon.agent.search/line-number «first hit's line»
     ;;       :seon.agent.search/line-text   \"«preview of that line»\"} …]
     ;;     :seon.agent.search/truncated? false}
     ;; pick a file row, then read it (the path is absolute + allowlisted):
     (seon.agent.fs/read-file {:seon.agent.fs/path \"<:seon.agent.search/path>\"})
     ;; jump to its :seon.agent.search/line-number; or re-grep with
     ;; :seon.agent.search/paths [that-file] to see every hit in it.

   NOTE: ^:async means Malli validates the request; the response schema
   documents the RESOLVED value (the raw return is a js/Promise — same
   caveat as seon.db/transact!)."
  {:malli/schema [:=> [:cat :seon.agent.search/grep-request]
                  :seon.agent.search/grep-response]}
  [{:seon.agent.search/keys [pattern paths glob max-results full? case-insensitive?]
    :or {max-results in/default-max-results}}]
  (try
    (let [roots (if (seq paths) (vec paths) (in/default-roots))
          bin   (in/rg-path)]
      (cond
        (or (nil? pattern) (str/blank? pattern))
        (in/fail (str ":seon.agent.search/pattern is required and must be non-blank "
                      "— it is a regex over file contents."))

        (empty? roots)
        (in/fail (str "nothing is searchable: no :seon.agent.search/paths given and "
                      "seon.agent.fs has no allowed-roots configured (default-deny) "
                      "— ask your human to grant access via "
                      "(seon.agent.fs/configure! {:seon.agent.fs/allowed-roots [...]})."))

        (nil? bin)
        (in/fail (str "ripgrep binary not found — the @vscode/ripgrep npm "
                      "package is missing or its platform binary did not "
                      "install. Run `npm install` in the repo root."))

        :else
        (if-let [denied (some in/gate-path roots)]
          denied
          (let [args (-> ["--json" "--no-config"]
                         (cond-> case-insensitive? (conj "-i")
                                 glob              (conj "--glob" glob))
                         (conj "--regexp" pattern "--")
                         (into roots))
                ^js r  (await (in/exec-rg bin args))
                ^js err (.-err r)
                stdout (.-stdout r)
                stderr (.-stderr r)]
            (cond
              ;; Timeout — execFile killed the child.
              (and err (.-killed err))
              (in/fail (str "search timed out after " in/timeout-ms "ms — "
                            "narrow :seon.agent.search/paths, add a "
                            ":seon.agent.search/glob, or use a more specific "
                            "pattern.")
                       stderr)

              ;; Binary vanished between rg-path check and spawn.
              (and err (= "ENOENT" (.-code err)))
              (in/fail (str "ripgrep binary failed to spawn (" bin ") — "
                            "run `npm install` in the repo root.")
                       (.-message err))

              ;; Output cap — partial stdout is still parseable.
              (and err (= "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" (.-code err)))
              (assoc (in/success-from stdout paths glob max-results full?)
                     :seon.agent.search/truncated? true)

              ;; rg exit 1 = searched fine, found nothing. NOT an error.
              (and err (= 1 (.-code err)))
              (in/ok-empty)

              ;; rg exit 2 (or anything else) — bad regex is the common case.
              err
              (in/fail (str "ripgrep rejected the search — most often an "
                            "invalid regex in :seon.agent.search/pattern (it is a "
                            "REGEX, not a literal: escape ( ) [ ] { } . with "
                            "\\\\). Detail: "
                            (or (first (str/split-lines (str stderr)))
                                (.-message err)))
                       (if (str/blank? (str stderr)) (.-message err) stderr))

              :else
              (in/success-from stdout paths glob max-results full?))))))
    (catch :default e
      (in/fail (str "unexpected error in seon.agent.search/grep: "
                    (or (some-> e .-message) (str e)))))))

(defn grep-graph
  "Text-search the LIVE PROGRAM GRAPH — the literal counterpart of `grep`.

   The literal sibling of SEON_EMBED semantic recall. Where `grep`
   searches file CONTENTS, this searches the CODE stored in seon.db:
   :seon.fn (source + name + docstring), :seon.schema (source), and
   :seon.ns (source) — fns/schemas/namespaces that may exist in NO source
   file (agent-authored + seeded code-as-data), which file-grep can't reach.
   Synchronous (graph reads are local): returns the
   :seon.agent.search/grep-graph-response envelope directly. Errors are
   values — never throws.

   This is NOT for arbitrary entity data (todos, kb rows, agent state) —
   that is Datalog (`seon.db/query`) / `my.kb`. grep-graph is literal text
   search over CODE only.

   SAME concise contract as `grep`: matching members are GROUPED BY
   NAMESPACE, ranked by hit-count, top :seon.agent.search/max-results
   (default 12) namespace rows under :seon.agent.search/by-ns — each a {ns,
   count, the first matching member + its target + a preview line}. Honest
   totals (:match-count = matching members, :ns-count = namespaces) always
   reported; :seon.agent.search/hint when rows were clipped.

   Request keys:
     :seon.agent.search/pattern           REQUIRED — a REGEX (JS syntax),
                                    not a literal: escape ( ) [ ] { } . with \\\\
     :seon.agent.search/targets           optional — which graph kinds to
                                    search; DEFAULT [:seon.fn :seon.schema
                                    :seon.ns]. Add :seon.eval to include the
                                    high-volume eval LOG (off by default).
     :seon.agent.search/max-results       optional — max NS ROWS (default 12)
     :seon.agent.search/full?             optional — when true, return the FLAT
                                    :seon.agent.search/matches list (every
                                    matching member, capped) instead of by-ns
     :seon.agent.search/case-insensitive? optional

   No matches is SUCCESS: {:seon.agent.search/ok? true
                           :seon.agent.search/by-ns [] …count 0}.

   Worked example:

     (seon.agent.search/grep-graph {:seon.agent.search/pattern \"transact\"})
     ;; => {:seon.agent.search/ok? true
     ;;     :seon.agent.search/match-count «matching members»
     ;;     :seon.agent.search/ns-count    «namespaces»
     ;;     :seon.agent.search/by-ns
     ;;     [{:seon.agent.search/ns        \"seon.db\"
     ;;       :seon.agent.search/count     «hits in this ns»
     ;;       :seon.agent.search/member    \"seon.db/transact!\"
     ;;       :seon.agent.search/target    :seon.fn
     ;;       :seon.agent.search/line-text \"«preview of the matching line»\"} …]
     ;;     :seon.agent.search/truncated? false}"
  {:malli/schema [:=> [:cat :seon.agent.search/grep-graph-request]
                  :seon.agent.search/grep-graph-response]}
  [{:seon.agent.search/keys [pattern targets max-results full? case-insensitive?]
    :or {max-results in/default-max-results
         targets     in/default-graph-targets}}]
  (if (or (nil? pattern) (str/blank? pattern))
    (in/fail (str ":seon.agent.search/pattern is required and must be non-blank "
                  "— it is a regex over the program graph (fn/schema/ns code)."))
    (in/graph-search pattern targets max-results full? case-insensitive?)))
