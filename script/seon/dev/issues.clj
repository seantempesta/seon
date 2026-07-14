(ns seon.dev.issues
  "Validate Seon's issue-note authority and derive its index."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private legal-severities #{"blocker" "friction" "cleanup"})
(def ^:private legal-closed-statuses #{"resolved" "superseded"})
(def ^:private severity-order ["blocker" "friction" "cleanup"])
(def ^:private non-note-files #{"AGENTS.md" "README.md" "index.md"})

(def ^:private lane-rules
  [["web" "UI"]
   ["agent" "agent"]
   ["flow" "Core"]
   ["database" "Core"]
   ["schema" "Core"]
   ["reference" "docs"]
   ["component" "docs"]])

(defn- frontmatter [content]
  (let [lines (str/split-lines content)]
    (when (= "---" (first lines))
      (when-let [end (->> (rest lines)
                          (map-indexed vector)
                          (some (fn [[index line]]
                                  (when (= "---" line) index))))]
        (->> (take end (rest lines))
             (keep (fn [line]
                     (when-let [[_ key value]
                                (re-matches #"([^:]+):\s*(.*)" line)]
                       [(str/trim key) (str/trim value)])))
             (into {}))))))

(defn- parse-tags [value]
  (-> (or value "")
      (str/replace #"[\[\]]" "")
      (str/split #",")
      (->> (map str/trim) (remove str/blank?) set)))

(defn- headings [content]
  (->> (str/split-lines content)
       (keep (fn [line]
               (when (str/starts-with? line "# ")
                 (str/trim (subs line 2)))))
       vec))

(defn- note [issues-dir location path]
  (let [content (slurp (str path))
        metadata (frontmatter content)
        titles (headings content)]
    {::path (str path)
     ::relative-path (str (fs/relativize issues-dir path))
     ::file (str (fs/file-name path))
     ::location location
     ::metadata metadata
     ::title (first titles)
     ::titles titles
     ::tags (parse-tags (get metadata "tags"))}))

(defn notes
  "Read open and archived issue notes under one repository root."
  [root]
  (let [issues-dir (fs/path root "docs/seon/issues")
        open (->> (fs/glob issues-dir "*.md")
                  (remove #(contains? non-note-files (str (fs/file-name %))))
                  (map #(note issues-dir :open %)))
        archived (->> (fs/glob (fs/path issues-dir "archive") "*.md")
                      (map #(note issues-dir :archive %)))]
    (vec (concat open archived))))

(defn- row-errors [{::keys [relative-path location metadata titles tags]}]
  (let [status (get metadata "status")
        expected-status? (if (= :open location)
                           (= "open" status)
                           (contains? legal-closed-statuses status))
        severity (get metadata "severity")]
    (cond-> []
      (nil? metadata)
      (conj {::path relative-path ::problem :missing-frontmatter})

      (not= "issue" (get metadata "type"))
      (conj {::path relative-path ::problem :invalid-type
             ::actual (get metadata "type")})

      (not expected-status?)
      (conj {::path relative-path ::problem :invalid-status
             ::actual status ::location location})

      (not (contains? legal-severities severity))
      (conj {::path relative-path ::problem :invalid-severity
             ::actual severity})

      (not (contains? tags "issue"))
      (conj {::path relative-path ::problem :missing-issue-tag})

      (not= 1 (count titles))
      (conj {::path relative-path ::problem :invalid-h1-count
             ::actual (count titles)}))))

(defn validation-errors
  "Return every lifecycle, metadata, filename, and title violation."
  [issue-notes]
  (let [duplicate-files (->> issue-notes
                             (group-by ::file)
                             (keep (fn [[file rows]]
                                     (when (< 1 (count rows)) file)))
                             set)
        duplicate-titles (->> issue-notes
                              (group-by ::title)
                              (keep (fn [[title rows]]
                                      (when (and title (< 1 (count rows))) title)))
                              set)]
    (into []
          (concat
            (mapcat row-errors issue-notes)
            (for [file (sort duplicate-files)]
              {::problem :duplicate-file ::actual file})
            (for [title (sort duplicate-titles)]
              {::problem :duplicate-title ::actual title})))))

(defn- lane [{::keys [tags]}]
  (or (some (fn [[tag label]] (when (contains? tags tag) label)) lane-rules)
      "general"))

(defn render-index
  "Render the deterministic open-issue projection."
  [issue-notes]
  (let [open-notes (->> issue-notes
                        (filter #(= :open (::location %)))
                        (sort-by ::title))
        by-severity (group-by #(get-in % [::metadata "severity"]) open-notes)]
    (str
      "---\n"
      "type: orchestrator\n"
      "status: active\n"
      "tags: [orchestrator, issue, index]\n"
      "---\n\n"
      "# Open Issues — Index\n\n"
      "GENERATED FILE — do not hand-edit. Regenerate with `bin/issues-index`.\n"
      "Lifecycle `open → resolved | superseded`; closed issues live in `archive/`.\n"
      "See `README.md` for the convention.\n\n"
      (str/join
        "\n"
        (for [severity severity-order
              :let [rows (get by-severity severity)]
              :when (seq rows)]
          (str "## " (str/capitalize severity) " (" (count rows) ")\n\n"
               "| Issue | Severity | Lane |\n"
               "|-------|----------|------|\n"
               (str/join
                 "\n"
                 (for [row rows]
                   (str "| [" (::title row) "](" (::file row) ") | "
                        severity " | " (lane row) " |")))
               "\n"))))))

(defn- issue-state [root]
  (let [issue-notes (notes root)
        errors (validation-errors issue-notes)]
    (when (seq errors)
      (throw (ex-info "Issue authority is invalid."
                      {::errors errors})))
    {::notes issue-notes
     ::index (render-index issue-notes)
     ::path (fs/path root "docs/seon/issues/index.md")}))

(defn check!
  "Validate issue notes and require the checked-in index to match."
  [root]
  (let [{::keys [notes index path]} (issue-state root)
        actual (when (fs/regular-file? path) (slurp (str path)))]
    (when-not (= index actual)
      (throw (ex-info "Issue index is stale; run bin/issues-index."
                      {::path (str path)})))
    {::clean? true
     ::open-count (count (filter #(= :open (::location %)) notes))
     ::archive-count (count (filter #(= :archive (::location %)) notes))}))

(defn write!
  "Validate issue notes and atomically replace the derived index."
  [root]
  (let [{::keys [notes index path]} (issue-state root)
        temporary (fs/path (str path "." (random-uuid) ".tmp"))]
    (spit (str temporary) index)
    (fs/move temporary path {:replace-existing true :atomic-move true})
    {::path (str path)
     ::open-count (count (filter #(= :open (::location %)) notes))
     ::archive-count (count (filter #(= :archive (::location %)) notes))}))

(defn run!
  "Run the issue-index command for one repository root."
  [root arguments]
  (try
    (let [result (case (vec arguments)
                   [] (write! root)
                   ["--check"] (check! root)
                   (throw (ex-info "Usage: bin/issues-index [--check]" {})))]
      (println (pr-str result))
      result)
    (catch Exception error
      (binding [*out* *err*]
        (println (.getMessage error))
        (when-let [data (ex-data error)]
          (println (pr-str data))))
      (System/exit 1))))
