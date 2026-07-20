(ns my.skills
  "Discover and manage skills in an agent's active context.

   This namespace covers the database-backed skill catalog, file-backed and
   inline skill bodies, explicit loading and unloading, and their derived
   context renders. Skills reuse the ordinary context-block mechanism; corpus
   discovery and source files remain startup configuration concerns."
  (:refer-clojure :exclude [load list])
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.schema :as schema]))

;;; SCHEMA — register the attrs before any entity schema references them. A
;;; skill is identified by `:my.skills/name` (the catalog key AND the
;;; load/unload handle); the body source is the ATTRIBUTE PRESENCE of a
;;; file-path (file-backed) vs an inline body (agent-authored) — no `:kind`.

(schema/register! :my.skills/name        [:keyword {:seon.db/identity true}])
(schema/register! :my.skills/description [:string {:min 1}])   ; the catalog line; "Use when…" trigger
(schema/register! :my.skills/body        [:string {:min 1}])   ; inline body, ONLY for agent-authored skills

;; Config-driven agent-init CP-1 — WHICH skill bodies are always-on
;; (agent-level presence-set, decision 22a). The boot loader will transact
;; a :skill/<name> block per named skill; nothing reads this yet (purely
;; additive). Value type = the existing `:my.skills/name` handle.
(schema/register! ::load [:vector {:default [:repl]} :my.skills/name])

;; Function/render value shapes — map-out results + the derived catalog entry.
(schema/register! ::loaded? :boolean)
(schema/register! ::ok?     :boolean)
(schema/register! ::message :string)

(schema/register! ::skill-row
  [:map
   [:my.skills/name          :my.skills/name]
   [:my.skills/description    :my.skills/description]
   [:seon.agent.ctx/file-path :seon.agent.ctx/file-path]])

(schema/register! ::catalog-entry
  [:map
   [:my.skills/name        :my.skills/name]
   [:my.skills/description  :my.skills/description]
   [::loaded?              ::loaded?]])

(schema/register! ::list-response
  [:or [:vector ::catalog-entry]
   [:map [:seon.error/message :string]]])

(schema/register! ::result
  [:map
   [::ok?           ::ok?]
   [:my.skills/name {:optional true} :my.skills/name]
   [::message       ::message]])

(def ^:private load-priority
  "Loaded skill bodies sit in the VOLATILE band (> seon.agent.ctx
   `cache-breakpoint` = 20), so load/unload never busts the cacheable
   static prefix (soul → :namespaces) — only the volatile tail re-renders."
  30)

(def ^:private catalog-priority
  "The always-on catalog sits in the CACHED prefix (≤ cache-breakpoint =
   20): the same name+description text every render, so loading/unloading a
   body (a volatile-band change) never busts the catalog's cache slot."
  12)

;;; CORPUS SCAN — at boot, scan the skills dir for standard `SKILL.md` files
;;; and emit one row per file. No YAML/markdown parser: the body stays in the
;;; file (read fresh at render via `:seon.agent.ctx/file-path`); only the two
;;; frontmatter scalars `name`/`description` are pulled.

(defn- parse-frontmatter
  "Pull ONLY `name` + `description` from a SKILL.md's YAML frontmatter (the
   block between the first two `---` lines) — a ~10-line scanner, NOT a YAML
   parser: every other frontmatter key is tolerated by being ignored. Returns
   {:my.skills/name <keyword> :my.skills/description <string>}, or nil when
   `name` or a non-blank `description` is absent (the row is then skipped)."
  [text]
  (let [lines (str/split-lines (or text ""))
        fm    (when (= "---" (str/trim (first lines)))
                (->> (rest lines) (take-while #(not= "---" (str/trim %)))))
        field (fn [k]
                (some (fn [line]
                        (when-let [[_ v] (re-find (re-pattern (str "^" k ":\\s*(.+)$"))
                                                  line)]
                          (-> v str/trim (str/replace #"^[\"']|[\"']$" "") str/trim)))
                      fm))
        nm    (field "name")
        desc  (field "description")]
    (when (and (not (str/blank? nm)) (not (str/blank? desc)))
      {:my.skills/name        (keyword nm)
       :my.skills/description desc})))

(defn- list-skill-files
  "Repo-relative paths of every `SKILL.md` under `dir`: the directory form
   `<dir>/<name>/SKILL.md` plus any top-level `<dir>/<name>.md`. [] when `dir`
   is absent/unreadable. Bun's compatible `node:fs` implementation supplies
   the filesystem operations.

   Entry type is resolved with `statSync` (which FOLLOWS symlinks), not the
   `readdirSync` Dirent flags: the shipped corpus (`seon-skills/`) holds REAL
   directories, but statting THROUGH links keeps this robust if a corpus dir is
   ever a symlink to a skill directory — a `<dir>/<name>` symlink reports
   `.isDirectory? = false` on its Dirent and would otherwise be silently
   dropped."
  [dir]
  (let [fs (js/require "fs")
        stat (fn [p] (try (.statSync fs p) (catch :default _ nil)))]
    (if-not (try (.existsSync fs dir) (catch :default _ false))
      []
      (into []
            (mapcat (fn [nm]
                      (let [p  (str dir "/" nm)
                            st (stat p)]
                        (cond
                          (nil? st) nil
                          (.isDirectory st)
                          (let [sp (str p "/SKILL.md")]
                            (when (.existsSync fs sp) [sp]))
                          (and (.isFile st) (str/ends-with? nm ".md"))
                          [p]))))
            (.readdirSync fs dir)))))

(defn seed-skills-tx-data
  "Tx-data seeding one `:my.skills` row per `SKILL.md` found.

   Scans explicit corpus directory `dir`. Each row carries `:my.skills/name`
   (the frontmatter name as a keyword), `:my.skills/description` (verbatim),
   and `:seon.agent.ctx/file-path` (the body stays in the file, read fresh at
   render). Identity-upsert on `:my.skills/name`, so a re-scan at every boot is
   idempotent. [] when the dir is absent or holds no readable SKILL.md. Pure
   (file reads only); the boot path selects the directory before the database session
   and transacts the resulting ordinary data as root/boot."
  {:malli/schema [:=> [:catn [::dir :string]] [:vector ::skill-row]]}
  [dir]
  (->> (list-skill-files dir)
       (keep (fn [path]
               (when-let [fm (parse-frontmatter (ctx/read-file-text path))]
                 (assoc fm :seon.agent.ctx/file-path path))))
       vec))

;;; DERIVATION — loaded? is a pure projection of the agent's OWN ctx blocks
;;; (the `:skill/<name>` ones), never a stored flag.

(defn- block-name
  "The ctx-block name for a loaded skill — `:skill/<name>`. The `:skill`
   namespace IS the loaded marker the catalog derives from, and the name part
   IS the skill identity the render fn re-reads — so the block carries no
   `:my.skills/name` attr (that is a unique identity; storing it on the block
   would collide-merge with the skill row)."
  [skill-name]
  (keyword "skill" (name skill-name)))

(defn- loaded-skill-names
  "The set of skill names currently loaded in `agent-id`'s context — derived
   from its `:skill/<name>` block names, no stored flag. #{} when no agent or
   none loaded."
  [rows]
  (->> rows
       (filter #(= "skill" (namespace %)))
       (map #(keyword (name %)))
       set))

(defn- catalog-entries
  "The derived skill catalog over ordinary rows: one entry per `:my.skills/*` row
   (name + description), each marked `::loaded?` against `agent-id`'s own
   loaded `:skill/*` blocks. Sorted by name. Pure derivation."
  [catalog-rows loaded-rows]
  (let [loaded (loaded-skill-names loaded-rows)]
    (->> catalog-rows
         (sort-by first)
         (mapv (fn [[n d]]
                 {:my.skills/name        n
                  :my.skills/description  d
                  ::loaded?              (contains? loaded n)})))))

;;; FUNCTIONS — thin wrappers over install!/remove! + a derived row query. The
;;; agent gets DATA (the eval path auto-awaits the ^:async ones).

(defn ^{:async true :seon.fn/agent-facing? true} load
  "Load skill `skill-name`'s body INTO your context.

   Install ONE `:skill/<name>` context block whose body is the skill's SKILL.md, rendered
   with its token cost. Idempotent (re-loading replaces in place). Returns
   {::ok? true :my.skills/name …} on success, {::ok? false ::message …} when
   no such skill exists or the install fails (errors are values).

     (my.skills/load :datahike)   ; its body now rides your prompt; unload when done"
  {:malli/schema [:=> [:catn [::skill-name :my.skills/name]] ::result]}
  [skill-name]
  (let [exists? (some? (await (db/query
                               {:seon.db/query '[:find ?e . :in $ ?n
                                                 :where [?e :my.skills/name ?n]]
                                :seon.db/args [skill-name]})))]
    (if-not exists?
      {::ok? false
       ::message (str "no skill " skill-name " — (my.skills/list) to see what's available")}
      (let [res (await (ctx/install!
                         {:seon.agent.ctx/name     (block-name skill-name)
                          :seon.agent.ctx/priority load-priority
                          :seon.render/ai          'my.skills/skill-block}))]
        (if (:seon.agent.ctx/ok? res)
          {::ok? true :my.skills/name skill-name
           ::message (str "loaded " skill-name " — its body is in your context below;"
                          " (my.skills/unload " skill-name ") when done")}
          {::ok? false
           ::message (str "load failed: " (:seon.agent.ctx/error res))})))))

(defn ^{:async true :seon.fn/agent-facing? true} unload
  "Unload skill `skill-name`, removing its `:skill/<name>` block.

   Its body (and token cost) is gone next render. No-op success if it
   wasn't loaded.

     (my.skills/unload :datahike)"
  {:malli/schema [:=> [:catn [::skill-name :my.skills/name]] ::result]}
  [skill-name]
  (let [res (await (ctx/remove! (block-name skill-name)))]
    (if (:seon.agent.ctx/ok? res)
      {::ok? true :my.skills/name skill-name
       ::message (str "unloaded " skill-name)}
      {::ok? false
       ::message (str "unload failed: " (:seon.agent.ctx/error res))})))

(defn ^{:async true :seon.fn/agent-facing? true} list
  "The skill catalog: every available skill and whether YOU loaded it.

   Each entry carries its description and `::loaded?` — derived from your
   own `:skill/*` blocks. Read it to discover what you can `(load …)`.

     (my.skills/list)
     ; returns «vector: [{:my.skills/name :datahike, :my.skills/description \"…\", :my.skills/loaded? false} …]»"
  {:malli/schema [:=> [:cat] ::list-response]}
  []
  (let [database (await (db/db))]
    (if (:seon.error/message database)
      database
      (let [agent-id (db/current-agent-id)
            catalog (await
                     (db/query
                      {:seon.db/db database
                       :seon.db/query '[:find ?n ?d
                                        :where
                                        [?e :my.skills/name ?n]
                                        [?e :my.skills/description ?d]]}))]
        (if (:seon.error/message catalog)
          catalog
          (let [loaded (if agent-id
                         (await
                          (db/query
                           {:seon.db/db database
                            :seon.db/query '[:find [?n ...]
                                             :in $ ?aid
                                             :where
                                             [?a :seon.agent/id ?aid]
                                             [?a :seon.agent/ctx ?b]
                                             [?b :seon.agent.ctx/name ?n]]
                            :seon.db/args [agent-id]}))
                         [])]
            (if (:seon.error/message loaded)
              loaded
              (catalog-entries catalog loaded))))))))

;;; RENDER FNS — the always-on catalog (L0) and the loaded body+footer (L2).
;;; Both `;`-comment their text so the whole prompt stays eval-valid Clojure.

(defn- strip-frontmatter
  "Drop a leading YAML frontmatter block (`---` … `---`) from `text`,
   returning the markdown body (a file-backed SKILL.md leads with frontmatter
   that is already in the catalog). Returns `text` unchanged when it has no
   leading frontmatter (inline bodies)."
  [text]
  (let [lines (str/split-lines (or text ""))]
    (if (= "---" (str/trim (first lines)))
      (->> (rest lines)
           (drop-while #(not= "---" (str/trim %)))
           rest                                   ; drop the closing ---
           (str/join "\n")
           str/triml)
      (or text ""))))

(defn- skill-body
  "The body text of pulled skill `row`: the file read FRESH (frontmatter
   stripped) when file-backed (`:seon.agent.ctx/file-path` present), else the
   inline `:my.skills/body`. nil when neither resolves → the block drops."
  [row]
  (if-let [path (:seon.agent.ctx/file-path row)]
    (some-> (ctx/read-file-text path) strip-frontmatter)
    (:my.skills/body row)))

(defn- footer-line
  "The DERIVED cost footer for a loaded skill `body`: its own token cost
   (`tokens/estimate`, chars/4 — the ONE estimator) + the explicit unload
   hint. With a `total-tok` prompt size it also shows ≈% of context; omitted
   otherwise (the per-turn prompt-total stash is the follow-on). Never stored
   — recomputed every render, so unloading erases the cost."
  [skill-name body total-tok]
  (let [own (tokens/estimate body)]
    (str "; ── " (name skill-name) " skill · ~" own " tok"
         (when (and total-tok (pos? total-tok))
           (str " (≈" (max 1 (js/Math.round (/ (* 100 own) total-tok)))
                "% of your ~" total-tok "-tok context)"))
         "\n;    done? (my.skills/unload :" (name skill-name) ") ──")))

(defn ^:async skill-block
  "The loaded-skill body block: the full SKILL.md, `;`-commented.

   The `:seon.render/ai` slot [[load]] installs as the agent's
   `:skill/<name>` context block.
   Eval-safe via [[seon.agent.ctx/quote-lines]], with a DERIVED token-cost
   footer. The skill name comes from the block's own `:skill/<name>` name;
   the row is pulled FRESH each render (REACTIVE: if the row is retracted,
   or the file vanished, the body resolves blank → \"\" → the block drops).
   A database ERROR is a value — the block renders a legible one-line
   failure comment instead of silently dropping."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] node :seon.render/node}]
  (let [skill-name (keyword (name (:seon.agent.ctx/name node)))
        ;; Resolve the eid via a GUARDED query (an absent row yields nil,
        ;; never a throw), THEN pull by eid: a non-resolving lookup-ref
        ;; `[:my.skills/name …]` throws `:entity-id/missing`, but the block
        ;; must DROP (\"\") when its skill row is retracted, not error.
        eid        (await (db/query '[:find ?e . :in $ ?n
                                      :where [?e :my.skills/name ?n]]
                                    db skill-name))
        row        (when (and eid (not (:seon.error/message eid)))
                     (await (db/pull {:seon.db/db db
                                      :seon.db/pull-pattern
                                      '[:my.skills/body :seon.agent.ctx/file-path]
                                      :seon.db/ref eid})))
        error      (or (:seon.error/message eid) (:seon.error/message row))
        body       (when-not error (skill-body row))]
    (cond
      error
      (str "; skill " (name skill-name) " block read failed: " error)

      (str/blank? body)
      ""

      :else
      (str (ctx/quote-lines body) "\n"
           (footer-line skill-name body nil)))))
