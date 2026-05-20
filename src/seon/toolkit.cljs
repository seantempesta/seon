(ns seon.toolkit
  "Pre-loaded helper functions for the agent. The agent can call any
   of these as `seon.toolkit/<fn>` without re-defining them each
   conversation. Verified against the real wiki tree before shipping.

   Public API:
     (search opts)          → {:total-hits N :by-path [...]}
     (grep-files opts)      → [{:path :line-num :line :context}]
     (slurp* path)          → body string or nil
     (section body heading) → markdown section under a `## Heading`
     (head body n)          → first n chars of a string

   Common `opts` for search/grep-files:
     :root         absolute path to walk
     :pattern      regex applied to each line  (use `(?i)` for case-insens)
     :name-pattern regex applied to each file path (default: any .md)
     :limit        max hits returned (default 30)
     :max-files    max files scanned (default 80)"
  (:require
    [clojure.string :as str]
    [seon.agent :as agent]
    [seon.db :as db]
    [seon.fs :as fs]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas for the agent's durable notes. `seon.db/transact!` enforces
;; Malli-registered attribute keys at the boundary; without these
;; registrations, `note!` would throw at the pre-transact validation
;; step (before datahike even sees the data). The datahike side of
;; the schema is registered in `seon.client/agent-bootstrap-schema`.
;; ============================================================

(schema/register! :seon.note/id      :string)
(schema/register! :seon.note/topic   :string)
(schema/register! :seon.note/content :string)
(schema/register! :seon.note/sources [:vector :string])
(schema/register! :seon.note/at      :inst)
(schema/register! :seon.note/agent   :seon.db/ref)

(declare recall notes)

(defn fs-root
  "The directory the agent was pointed at, read from `SEON_FS_ROOT`. Most
   toolkit fns accept an explicit `:root` — but when omitted, default
   to this so the agent rarely has to spell it out."
  []
  (some-> js/process .-env .-SEON_FS_ROOT))

(defn slurp*
  "Read `path` and return its body string, or nil on any failure.
   Wraps `seon.fs/read-file` for the common 'I just want the text'
   case. Returns nil — not a `:seon.fs/ok? false` map — so callers
   can use `(when-let [body (slurp* p)] ...)`."
  [path]
  (let [r (fs/read-file {:seon.fs/path path})]
    (when (:seon.fs/ok? r) (:seon.fs/content r))))

(defn head
  "First `n` chars of `s`, safe on nil. Convenience for truncating
   large slurped bodies before logging."
  [s n]
  (when s (subs s 0 (min n (count s)))))

(defn section
  "Pull a single markdown section from `body` under `## Heading`.
   `heading` matches case-insensitively. Returns the heading line
   plus everything until the next `#` heading at the same-or-higher
   level, or nil if not found."
  [body heading]
  (when body
    (let [pat (re-pattern (str "(?ims)^#+\\s*" heading ".*?(?=^#|\\z)"))]
      (some-> (re-find pat body) str/trim))))

;; ============================================================
;; Fast path-discovery — cached, no file reads. This is the FIRST
;; call you should make for any 'where does X live?' question. It's
;; orders of magnitude cheaper than `search` (which reads every file
;; that passes the name filter) and usually answers the question
;; outright. Cache is per-process: invalidate manually if files
;; change underneath the agent (rare during a conversation).
;; ============================================================

(defonce ^:private !file-index (atom nil))

(defn- ensure-index! []
  (or @!file-index
      (let [r (fs/walk-dir {:seon.fs/path (fs-root)
                            :seon.fs/match-ext ".md"})]
        (when (:seon.fs/ok? r)
          (reset! !file-index (:seon.fs/entries r))))))

(defn refresh-index!
  "Drop the cached file-index. Next call rebuilds. Use if files
   have changed underneath you mid-conversation."
  []
  (reset! !file-index nil)
  (ensure-index!)
  :ok)

(defn find-by-name
  "Walk the wiki ONCE (cached) and return paths whose filename or
   path matches `pattern`. No file reads — purely path lookup.

   Use this BEFORE `search` to learn the directory layout. It's
   the cheapest way to answer 'where do things about X live?' and
   it'll never return a path that doesn't exist (unlike guessing).

     (seon.toolkit/find-by-name #\"(?i)readme\")
     ;; ⇒ [\"…/project/README.md\"
     ;;    \"…/project/docs/getting-started.md\"
     ;;    …]

   Returns at most `limit` paths (default 40)."
  ([pattern] (find-by-name pattern 40))
  ([pattern limit]
   (->> (or (ensure-index!) [])
        (filter #(re-find pattern %))
        (take limit)
        vec)))

(defn index
  "Read the wiki's root `CLAUDE.md` — the user's hand-written index.
   This is the BEST starting point for any general question about the
   user's work. The index has links to every section + the active
   work-items table. Returns the body string."
  []
  (slurp* (str (fs-root) "/CLAUDE.md")))

(defn about
  "Composite high-leverage probe. Given a `topic` string, returns
   a single map combining everything cheap-to-collect:

     :notes         existing notes whose topic/content matches
     :paths         file paths whose name matches
     :index-hits    lines of the root CLAUDE.md mentioning the topic

   This is the smart first call for ANY 'tell me about X' question.
   It answers structure (where is this?) and prior-art (what do I
   already know?) without reading any large files. Decide what to
   slurp next based on what comes back."
  [topic]
  (let [pat   (re-pattern (str "(?i)" topic))
        idx   (or (index) "")
        lines (str/split-lines idx)]
    {:notes      (recall pat)
     :paths      (find-by-name pat 20)
     :index-hits (->> lines
                      (map-indexed vector)
                      (filter (fn [[_ ln]] (re-find pat ln)))
                      (take 8)
                      (map (fn [[i ln]] {:line-num (inc i) :line ln}))
                      vec)}))

(defn grep-files
  "Walk `:root`, narrow files by `:name-pattern` regex, then content-
   grep each remaining file with `:pattern`. Returns a vector of hit
   maps `{:path :line-num :line :context}` where :context includes
   the matching line plus ±1 surrounding line for orientation.

   Stops at `:limit` hits (default 30). Scans up to `:max-files`
   files (default 80) — increase if your name-pattern is broad and
   you need to widen the search."
  [{:keys [root pattern name-pattern limit max-files]
    :or   {limit 30 max-files 80}}]
  (let [files (->> (:seon.fs/entries
                     (fs/walk-dir
                       {:seon.fs/path root
                        :seon.fs/match-ext ".md"}))
                   (filter (fn [p]
                             (if name-pattern
                               (re-find name-pattern p) true)))
                   (take max-files))]
    (->> (for [p files
               :let  [body (slurp* p)
                      lines (when body (str/split-lines body))]
               :when lines
               [i ln] (map-indexed vector lines)
               :when (re-find pattern ln)]
           {:path p
            :line-num (inc i)
            :line ln
            :context (str/join "\n"
                       (subvec lines
                               (max 0 (- i 1))
                               (min (count lines) (+ i 2))))})
         (take limit)
         vec)))

(defn search
  "Higher-level wrapper over `grep-files`. Same opts. Returns a
   path-grouped summary so you can decide which 1–2 files to read
   in full before composing your reply.

   Returns:
     {:total-hits N
      :by-path [{:path :n :first-line} ...]}   ;; sorted by hit count desc"
  [opts]
  (let [hits (grep-files opts)]
    {:total-hits (count hits)
     :by-path (->> hits
                   (group-by :path)
                   (sort-by (comp - count val))
                   (map (fn [[p hs]]
                          {:path p
                           :n (count hs)
                           :first-line (-> hs first :line)}))
                   vec)}))

;; ============================================================
;; Durable notes — the agent's growing memory.
;;
;; The point of this whole project. When the agent learns something
;; worth remembering (who someone is, the gist of a project, where
;; a recurring fact lives), she writes a note. Next conversation
;; the prompt surfaces matching notes BEFORE she re-walks the
;; filesystem. Notes persist across pod restarts (the DB is durable
;; in V1; in V0.5 dev it's :memory and resets on restart, but the
;; *protocol* is real).
;; ============================================================

(defn note!
  "Record a durable note. Returns the new note's id.

     (seon.toolkit/note!
       {:topic   \"meeting-notes\"
        :content \"Example note showing the call shape.\"
        :sources [\"/path/to/some/file.md\"]
        :agent-id \"seon\"})

   `:agent-id` defaults to the V0 default agent id when omitted."
  [{:keys [topic content sources agent-id]
    :or   {agent-id agent/default-id sources []}}]
  (let [id (agent/new-id!)]
    (db/transact!
      {:seon.db/tx-data
       [{:seon.note/id      id
         :seon.note/topic   (or topic "")
         :seon.note/content (or content "")
         :seon.note/sources (vec sources)
         :seon.note/at      (js/Date.)
         :seon.note/agent   [:seon.agent/id agent-id]}]})
    id))

(defn notes
  "Return all notes for `agent-id` (default: the V0 default), newest-
   first. Each row is a plain map of the note's attrs."
  ([] (notes agent/default-id))
  ([agent-id]
   (let [rows (db/query
                {:seon.db/query
                 '[:find ?id ?topic ?content ?at
                   :in $ ?aid
                   :where
                   [?n :seon.note/agent ?aid]
                   [?n :seon.note/id ?id]
                   [?n :seon.note/topic ?topic]
                   [?n :seon.note/content ?content]
                   [?n :seon.note/at ?at]]
                 :seon.db/args [[:seon.agent/id agent-id]]})]
     (->> rows
          (sort-by #(nth % 3) #(compare %2 %1))
          (map (fn [[id topic content at]]
                 {:seon.note/id id
                  :seon.note/topic topic
                  :seon.note/content content
                  :seon.note/at at}))
          vec))))

(defn recall
  "Filter `(notes)` by regex match against topic OR content. Use to
   check what you already know about a subject before re-reading
   files. Returns a vector of note maps."
  ([pattern] (recall pattern agent/default-id))
  ([pattern agent-id]
   (->> (notes agent-id)
        (filter (fn [{:seon.note/keys [topic content]}]
                  (or (re-find pattern (str topic))
                      (re-find pattern (str content)))))
        vec)))
