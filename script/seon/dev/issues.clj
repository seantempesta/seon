(ns seon.dev.issues
  "Validate Seon's issue-note authority and derive its index."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private legal-severities #{"blocker" "friction" "cleanup"})
(def ^:private legal-closed-statuses #{"resolved" "superseded"})
(def ^:private severity-order ["blocker" "friction" "cleanup"])
(def ^:private non-note-files #{"AGENTS.md" "README.md" "index.md"})

(defn- frontmatter [content]
  (let [lines (str/split-lines content)]
    (when (= "---" (first lines))
      (when-let [end (->> (rest lines)
                          (map-indexed vector)
                          (some (fn [[index line]]
                                  (when (= "---" line) index))))]
        (->> (take end (rest lines))
             (keep (fn [line]
                     (when-let [[_ field value]
                                (re-matches #"([^:]+):\s*(.*)" line)]
                       [(str/trim field) (str/trim value)])))
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
      (conj {::path relative-path ::problem :missing-frontmatter
             ::expected "delimited YAML frontmatter"})

      (not= "issue" (get metadata "type"))
      (conj {::path relative-path ::problem :invalid-type
             ::expected "issue" ::actual (get metadata "type")})

      (not expected-status?)
      (conj {::path relative-path ::problem :invalid-status
             ::expected (if (= :open location)
                          ["open"]
                          (sort legal-closed-statuses))
             ::actual status ::location location})

      (not (contains? legal-severities severity))
      (conj {::path relative-path ::problem :invalid-severity
             ::expected severity-order ::actual severity})

      (not (contains? tags "issue"))
      (conj {::path relative-path ::problem :missing-issue-tag
             ::expected "issue" ::actual (sort tags)})

      (not= 1 (count titles))
      (conj {::path relative-path ::problem :invalid-h1-count
             ::expected 1 ::actual (count titles)}))))

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

(def ^:private schedule-row-pattern
  #"^\|\s+\[[^\]]+\]\(([^)]+\.md)\)\s+\|\s+([^|]+?)\s+\|\s+([^|]+?)\s+\|\s*$")

(defn- schedule-rows [content]
  (into []
        (keep (fn [line]
                (when-let [[_ file severity destination]
                           (re-matches schedule-row-pattern line)]
                  {::file file
                   ::severity (str/trim severity)
                   ::destination (str/trim destination)})))
        (str/split-lines content)))

(defn- schedule-errors [open-notes index-content]
  (let [rows (schedule-rows index-content)
        open-by-file (into {} (map (juxt ::file identity)) open-notes)
        counts (frequencies (map ::file rows))]
    (into []
          (concat
           (for [{::keys [file]} open-notes
                 :when (zero? (get counts file 0))]
             {::path file ::problem :missing-schedule-row})
           (for [[file occurrence-count] (sort-by key counts)
                 :when (> occurrence-count 1)]
             {::path file ::problem :duplicate-schedule-row
              ::actual occurrence-count})
           (for [{::keys [file]} rows
                 :when (nil? (get open-by-file file))]
             {::path file ::problem :scheduled-note-is-not-open})
           (for [{::keys [file severity]} rows
                 :let [issue-note (get open-by-file file)]
                 :when (and issue-note
                            (not= severity
                                  (get-in issue-note [::metadata "severity"])))]
             {::path file ::problem :schedule-severity-mismatch
              ::expected (get-in issue-note [::metadata "severity"])
              ::actual severity})
           (for [{::keys [file destination]} rows
                 :when (str/blank? destination)]
             {::path file ::problem :missing-schedule-destination})))))

(defn- issue-state [root]
  (let [issue-notes (notes root)
        errors (validation-errors issue-notes)
        indexable-notes (filterv #(empty? (row-errors %)) issue-notes)
        path (fs/path root "docs/seon/issues/index.md")
        index-content (when (fs/regular-file? path) (slurp (str path)))
        open-notes (filterv #(= :open (::location %)) indexable-notes)]
    {::notes issue-notes
     ::indexable-notes indexable-notes
     ::errors (into errors
                    (if index-content
                      (schedule-errors open-notes index-content)
                      [{::path (str path) ::problem :missing-index}]))
     ::path path}))

(defn- require-valid! [result errors]
  (when (seq errors)
    (throw (ex-info "Issue authority is invalid; every valid note was indexed."
                    {::errors errors
                     ::result result})))
  result)

(defn check!
  "Validate issue notes and require one scheduled row per open note."
  [root]
  (let [{::keys [indexable-notes errors path]} (issue-state root)]
    (require-valid!
      {::clean? true
       ::path (str path)
       ::open-count (count (filter #(= :open (::location %)) indexable-notes))
       ::archive-count (count (filter #(= :archive (::location %))
                                     indexable-notes))}
      errors)))

(defn- print-errors! [errors]
  (doseq [{::keys [path problem] :as error} errors]
    (println (str path ": " (name problem) " "
                  (pr-str (dissoc error ::path ::problem))))))

(defn run!
  "Run the issue-index command for one repository root."
  [root arguments]
  (try
    (let [result (case (vec arguments)
                   [] (check! root)
                   ["--check"] (check! root)
                   (throw (ex-info "Usage: bin/issues-index [--check]" {})))]
      (println (pr-str result))
      result)
    (catch Exception error
      (binding [*out* *err*]
        (println (.getMessage error))
        (if-let [errors (::errors (ex-data error))]
          (print-errors! errors)
          (when-let [data (ex-data error)]
            (println (pr-str data)))))
      (System/exit 1))))
