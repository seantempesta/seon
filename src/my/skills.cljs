(ns my.skills
  "Loadable skills — knowledge an agent dials INTO its own context only while
   it needs it, then drops so it stops paying for what it isn't using. A skill
   is NOT a new subsystem: a loaded skill IS a `:seon.agent.ctx/block`
   ([[seon.agent.ctx/install!]] puts it on the agent's own `:seon.agent/ctx`,
   [[seon.agent.ctx/remove!]] takes it off), its body rides the existing
   file-block read+quote path, and its cost is DERIVED at render — nothing
   stored that needs clearing.

   THE MODEL — a skill is its attributes, never a `:kind` stamp. A row 'is a
   skill' because it carries `:my.skills/name`; it is 'file-backed' because it
   carries `:seon.agent.ctx/file-path` (body stays in the SKILL.md, read fresh
   every render) and 'inline/agent-authored' because it carries
   `:my.skills/body` instead. Where it came from is `:seon.db/origin` on the
   tx, not a field.

   THREE SURFACES, three levels of disclosure (this slice ships L0 ⇄ L2):
     - L0 catalog — one always-on `;`-line per skill (name + description), the
       cheap discovery layer ([[catalog-block]], a default seed block). Costs
       a line each; the body costs nothing until loaded.
     - L2 body    — the whole SKILL.md, loaded only while `(load :name)`d
       ([[skill-block]]), with a DERIVED token-cost footer + the explicit
       unload hint so the trade stays visible. `(unload :name)` drops it.

   THE CORPUS — `(load :name)` resolves a `:my.skills/*` row seeded at boot by
   scanning [[seon.config/skills-dir]] (manifest `:seon.config/dirs` else env
   `SEON_SKILLS_DIR`, default `.claude/skills`), the SAME directory humans edit. Drop a standard `<name>/SKILL.md` in there
   and it appears; edit a skill file and the agent gets the edit. The pod
   stores only the path + the frontmatter `name`/`description` — no YAML or
   markdown parser, the body is the file.

   Async: `load`/`unload` AWAIT the underlying `install!`/`remove!` transact
   (they are `^:async`); the eval path auto-awaits, so an agent calling
   `(my.skills/load :datahike)` gets the result MAP, not a Promise. `list` is
   a synchronous derived query."
  (:refer-clojure :exclude [load list])
  (:require
    [clojure.string :as str]
    [seon.agent.ctx :as ctx]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
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

;; Verb/render value shapes — map-out results + the derived catalog entry.
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

(schema/register! ::result
  [:map
   [::ok?           ::ok?]
   [:my.skills/name {:optional true} :my.skills/name]
   [::message       ::message]])

(def ^:private load-priority
  "Loaded skill bodies sit in the VOLATILE band (> seon.agent.ctx
   `stable-priority-max` = 20), so load/unload never busts the cacheable
   static prefix (soul → :namespaces) — only the volatile tail re-renders."
  30)

(def ^:private catalog-priority
  "The always-on catalog sits in the CACHED prefix (≤ stable-priority-max =
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
   is absent/unreadable. Node `fs` (the pod is Node).

   Entry type is resolved with `statSync` (which FOLLOWS symlinks), not the
   `readdirSync` Dirent flags — a `<dir>/<name>` that is a SYMLINK to a skill
   directory reports `.isDirectory? = false` on its Dirent and would be
   silently dropped. `.claude/skills` symlinks the shared `seon-skills/*`
   dirs in exactly this shape, so following links is mandatory."
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

   Scans [[seon.config/skills-dir]] (default arg) — each row carries `:my.skills/name` (the
   frontmatter name as a keyword), `:my.skills/description` (verbatim), and
   `:seon.agent.ctx/file-path` (the body stays in the file, read fresh at
   render). Identity-upsert on `:my.skills/name`, so a re-scan at every boot
   is idempotent. [] when the dir is absent or holds no readable SKILL.md.
   Pure (file reads only); the boot path transacts it under
   `:seon.db/origin :core-seed`."
  {:malli/schema [:function
                  [:=> [:cat] [:vector ::skill-row]]
                  [:=> [:catn [::dir :string]] [:vector ::skill-row]]]}
  ([] (seed-skills-tx-data (config/skills-dir)))
  ([dir]
   (->> (list-skill-files dir)
        (keep (fn [path]
                (when-let [fm (parse-frontmatter (ctx/read-file-text path))]
                  (assoc fm :seon.agent.ctx/file-path path))))
        vec)))

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
  [db agent-id]
  (if (nil? agent-id)
    #{}
    (->> (db/query '[:find [?n ...]
                     :in $ ?aid
                     :where
                     [?a :seon.agent/id ?aid]
                     [?a :seon.agent/ctx ?b]
                     [?b :seon.agent.ctx/name ?n]]
                   db agent-id)
         (filter #(= "skill" (namespace %)))
         (map #(keyword (name %)))
         set)))

(defn- catalog-entries
  "The derived skill catalog over `db`: one entry per `:my.skills/*` row
   (name + description), each marked `::loaded?` against `agent-id`'s own
   loaded `:skill/*` blocks. Sorted by name. Pure derivation."
  [db agent-id]
  (let [loaded (loaded-skill-names db agent-id)]
    (->> (db/query '[:find ?n ?d
                     :where
                     [?e :my.skills/name ?n]
                     [?e :my.skills/description ?d]]
                   db)
         (sort-by first)
         (mapv (fn [[n d]]
                 {:my.skills/name        n
                  :my.skills/description  d
                  ::loaded?              (contains? loaded n)})))))

;;; VERBS — thin wrappers over install!/remove! + a derived row query. The
;;; agent gets DATA (the eval path auto-awaits the ^:async ones).

(defn ^:async load
  "Load skill `skill-name`'s body INTO your context.

   Install ONE `:skill/<name>` context block whose body is the skill's SKILL.md, rendered
   with its token cost. Idempotent (re-loading replaces in place). Returns
   {::ok? true :my.skills/name …} on success, {::ok? false ::message …} when
   no such skill exists or the install fails (errors are values).

     (my.skills/load :datahike)   ; its body now rides your prompt; unload when done"
  {:malli/schema [:=> [:catn [::skill-name :my.skills/name]] ::result]}
  [skill-name]
  (let [exists? (some? (db/query '[:find ?e . :in $ ?n
                                   :where [?e :my.skills/name ?n]]
                                 @db/*conn* skill-name))]
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

(defn ^:async unload
  "Unload skill `skill-name` — remove its `:skill/<name>` block.

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

(defn list
  "The skill catalog — every available skill and whether YOU loaded it.

   Each entry carries its description and `::loaded?` — derived from your
   own `:skill/*` blocks. Read it to discover what you can `(load …)`.

     (my.skills/list)
     ;; => [{:my.skills/name :datahike :my.skills/description \"…\" :my.skills/loaded? false} …]"
  {:malli/schema [:function
                  [:=> [:cat] [:vector ::catalog-entry]]
                  [:=> [:catn [::db :seon.db/db]] [:vector ::catalog-entry]]]}
  ([] (list @db/*conn*))
  ([db] (catalog-entries db (db/current-agent-id))))

;;; RENDER FNS — the always-on catalog (L0) and the loaded body+footer (L2).
;;; Both `;`-comment their text so the whole prompt stays eval-valid Clojure.

(def ^:private catalog-header
  (str "; SKILLS — loadable knowledge. Each costs nothing here until you load\n"
       "; its body. Load one with (my.skills/load :name); its full body then\n"
       "; rides your context showing its token cost — (my.skills/unload :name)\n"
       "; to drop what you're done with. ● loaded · ○ available."))

(defn- catalog-line
  [{nm :my.skills/name desc :my.skills/description loaded? ::loaded?}]
  (str "; - :" (name nm) "  " (if loaded? "● loaded" "○") " — " desc))

(defn catalog-block
  "The L0 `:skills-catalog` context block — one `;`-line per skill.

   Each line is cheap: name + description + a DERIVED ●/○ loaded marker.
   A symbol-slot section wired into `seon.agent.ctx/default-seed-blocks` at
   priority 12 (cached prefix). REACTIVE: \"\" when no skill rows exist, so
   the section drops."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [entries (catalog-entries db id)]
    (if (empty? entries)
      ""
      (str catalog-header "\n"
           (str/join "\n" (map catalog-line entries))))))

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

(defn skill-block
  "The L2 loaded-body block — the skill's full SKILL.md, `;`-commented.

   Eval-safe via [[seon.agent.ctx/quote-lines]], with a DERIVED token-cost
   footer. The skill name comes from the block's own `:skill/<name>` name;
   the row is pulled FRESH each render (REACTIVE: if the row is retracted,
   or the file vanished, the body resolves blank → \"\" → the block drops)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] node :seon.render/node}]
  (let [skill-name (keyword (name (:seon.agent.ctx/name node)))
        ;; Resolve the eid via a GUARDED query (returns nil — never throws —
        ;; when the row is gone), THEN pull by eid: a non-resolving lookup-ref
        ;; `[:my.skills/name …]` throws `:entity-id/missing`, but the block
        ;; must DROP (\"\") when its skill row is retracted, not error.
        eid        (db/query '[:find ?e . :in $ ?n
                               :where [?e :my.skills/name ?n]]
                             db skill-name)
        row        (when eid
                     (db/pull {:seon.db/db db
                               :seon.db/pull-pattern
                               '[:my.skills/body :seon.agent.ctx/file-path]
                               :seon.db/ref eid}))
        body       (skill-body row)]
    (if (str/blank? body)
      ""
      (str (ctx/quote-lines body) "\n"
           (footer-line skill-name body nil)))))
