(ns seon.dev.skills
  "Canonical runtime skills and deterministic development-tool adapters."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]))

(def ^:private adapter-roots
  [".agents/skills" ".claude/skills"])

(def ^:private codex-skills-root ".agents/skills")
(def ^:private claude-skills-root ".claude/skills")

(defn- regular-files [root]
  (if (fs/directory? root)
    (->> (file-seq (fs/file root))
         (filter #(.isFile ^java.io.File %))
         (sort-by str))
    []))

(defn- tree-content [root]
  (let [root (fs/path root)]
    (into (sorted-map)
          (map (fn [file]
                 [(str (fs/relativize root (fs/path file))) (slurp file)]))
          (regular-files root))))

(defn- skill-names-under [root path]
  (let [directory (fs/path root path)]
    (->> (fs/list-dir directory)
         (filter fs/directory?)
         (map (comp str fs/file-name))
         sort
         vec)))

(defn- runtime-skill-names [root]
  (skill-names-under root "seon-skills"))

(defn- development-skill-names [root]
  (let [runtime (set (runtime-skill-names root))]
    (into [] (remove runtime) (skill-names-under root codex-skills-root))))

(defn- drift-row [root source-root adapter-root skill-name]
  (let [source (fs/path root source-root skill-name)
        adapter (fs/path root adapter-root skill-name)
        source-content (tree-content source)
        adapter-content (tree-content adapter)
        paths (sort (set/union (set (keys source-content))
                               (set (keys adapter-content))))
        changed (filterv #(not= (get source-content %)
                                (get adapter-content %))
                         paths)]
    (when (seq changed)
      {:seon.dev.skills/source source-root
       :seon.dev.skills/adapter adapter-root
       :seon.dev.skills/name skill-name
       :seon.dev.skills/changed-paths changed})))

(defn adapter-drift
  "Return content drift between canonical `seon-skills` and shared adapters.

   Runtime-importable skills originate in `seon-skills` and generate both
   adapters. Codex-only development skills originate in `.agents/skills` and
   generate the Claude adapter; Claude-only skills remain outside the graph."
  [root]
  (let [runtime (runtime-skill-names root)
        development (development-skill-names root)]
    (into []
          (keep identity)
          (concat
            (for [adapter-root adapter-roots
                  skill-name runtime]
              (drift-row root "seon-skills" adapter-root skill-name))
            (for [skill-name development]
              (drift-row root codex-skills-root claude-skills-root skill-name))))))

(defn- replace-tree! [source target]
  (when (fs/exists? target)
    (fs/delete-tree target {:force true}))
  (fs/create-dirs (fs/parent target))
  (fs/copy-tree source target {:replace-existing true}))

(defn sync!
  "Replace shared adapter skill trees from canonical `seon-skills` content.

   Claude-only skills remain untouched. Returns the exact runtime and
   development names copied."
  [root]
  (let [runtime (runtime-skill-names root)
        development (development-skill-names root)]
    (doseq [adapter-root adapter-roots
            skill-name runtime
            :let [source (fs/path root "seon-skills" skill-name)
                  target (fs/path root adapter-root skill-name)]]
      (replace-tree! source target))
    (doseq [skill-name development
            :let [source (fs/path root codex-skills-root skill-name)
                  target (fs/path root claude-skills-root skill-name)]]
      (replace-tree! source target))
    {:seon.dev.skills/synced (vec (concat runtime development))
     :seon.dev.skills/runtime runtime
     :seon.dev.skills/development development
     :seon.dev.skills/adapters adapter-roots}))

(defn check!
  "Return a clean adapter result or throw with every drifted path."
  [root]
  (let [drift (adapter-drift root)]
    (when (seq drift)
      (throw (ex-info "Generated skill adapters differ from seon-skills."
                      {:seon.dev.skills/drift drift})))
    {:seon.dev.skills/clean? true}))
