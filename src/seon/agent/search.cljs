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

(schema/register! :seon.agent.search/ok? :boolean)
(schema/register! :seon.agent.search/error :string)
(schema/register! :seon.agent.search/raw-error :string)

(schema/register! :seon.agent.search/path :string)
(schema/register! :seon.agent.search/line-number :int)
(schema/register! :seon.agent.search/line-text :string)

(schema/register! :seon.agent.search/match
  [:map
   [:seon.agent.search/path        :seon.agent.search/path]
   [:seon.agent.search/line-number :seon.agent.search/line-number]
   [:seon.agent.search/line-text   :seon.agent.search/line-text]])

(schema/register! :seon.agent.search/matches [:vector :seon.agent.search/match])
(schema/register! :seon.agent.search/match-count :int)
(schema/register! :seon.agent.search/truncated? :boolean)

(schema/register! :seon.agent.search/grep-request
  [:map
   [:seon.agent.search/pattern           :seon.agent.search/pattern]
   [:seon.agent.search/paths             {:optional true} :seon.agent.search/paths]
   [:seon.agent.search/glob              {:optional true} :seon.agent.search/glob]
   [:seon.agent.search/max-results       {:optional true} :seon.agent.search/max-results]
   [:seon.agent.search/case-insensitive? {:optional true} :seon.agent.search/case-insensitive?]])

(schema/register! :seon.agent.search/grep-response
  [:map
   [:seon.agent.search/ok?         :seon.agent.search/ok?]
   [:seon.agent.search/matches     {:optional true} :seon.agent.search/matches]
   [:seon.agent.search/match-count {:optional true} :seon.agent.search/match-count]
   [:seon.agent.search/truncated?  {:optional true} :seon.agent.search/truncated?]
   [:seon.agent.search/error       {:optional true} :seon.agent.search/error]
   [:seon.agent.search/raw-error   {:optional true} :seon.agent.search/raw-error]])

;; ============================================================
;; Public API
;; ============================================================

(defn ^:async grep
  "Search file CONTENTS under the seon.agent.fs allowed roots. `^:async` —
   returns a Promise that ALWAYS resolves to a :seon.agent.search/grep-response
   envelope (never rejects; errors are values).

   Request keys:
     :seon.agent.search/pattern           REQUIRED — a REGEX (rg syntax), not a
                                    literal: escape ( ) [ ] { } . with \\\\
     :seon.agent.search/paths             optional — files/dirs to search;
                                    DEFAULT = the seon.agent.fs allowed roots
     :seon.agent.search/glob              optional — filename filter, e.g. \"*.cljs\"
     :seon.agent.search/max-results       optional — clip (default 100);
                                    :seon.agent.search/truncated? true when hit
     :seon.agent.search/case-insensitive? optional

   No matches is SUCCESS: {:seon.agent.search/ok? true :seon.agent.search/matches []}.

   Worked example — search → read precisely (top-level call, no await:
   the REPL resolves the returned Promise for you):

     (seon.agent.search/grep {:seon.agent.search/pattern \"message/user\"})
     ;; => {:seon.agent.search/ok? true
     ;;     :seon.agent.search/matches
     ;;     [{:seon.agent.search/path        \"«abs path of the hit»\"
     ;;       :seon.agent.search/line-number «int»
     ;;       :seon.agent.search/line-text   \"«the matching line»\"} …]
     ;;     :seon.agent.search/match-count «int» :seon.agent.search/truncated? false}
     ;; the hits live under :seon.agent.search/matches (NOT :hits); the
     ;; count is :seon.agent.search/match-count.
     ;; pick a hit, then:
     (seon.agent.fs/read-file {:seon.agent.fs/path \"<:seon.agent.search/path of the hit>\"})
     ;; jump to its :seon.agent.search/line-number in the content.

   NOTE: ^:async means Malli validates the request; the response schema
   documents the RESOLVED value (the raw return is a js/Promise — same
   caveat as seon.db/transact!)."
  {:malli/schema [:=> [:cat :seon.agent.search/grep-request]
                  :seon.agent.search/grep-response]}
  [{:seon.agent.search/keys [pattern paths glob max-results case-insensitive?]
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
              (assoc (in/success-from stdout max-results)
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
              (in/success-from stdout max-results))))))
    (catch :default e
      (in/fail (str "unexpected error in seon.agent.search/grep: "
                    (or (some-> e .-message) (str e)))))))
