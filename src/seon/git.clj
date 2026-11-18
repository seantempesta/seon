(ns seon.git
  "Explicit Git & filesystem boundary (`Datahike :main === Git main` 1-to-1):
   - Cold bootstrap (`bootstrap-tx`) when a new store holds no `:git/commit`.
   - O(|Delta|) boot catch-up (`catch-up-tx`) if git HEAD moved while Seon was stopped (0 file reads when equal).
   - Zero-worktree `refs/heads/seon/<branch>` export via `git fast-import` so `git branch`, `git log`,
     and `git diff main..seon/<branch>` work naturally with standard Git/GitHub tooling.
   - O(|touched-paths|) atomic write-back & true 2-parent Git merge commit on `main` (never pushes to GitHub)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.fibers :as fibers]
            [seon.code :as code]
            [seon.merge :as merge]))

(defn sh-fiber
  "Runs subprocess `args` on a Jolt fiber so pipes park on kqueue readiness without spawning OS threads."
  [& args]
  (if (fibers/in-fiber?)
    (apply sh/sh args)
    (fibers/join (fibers/spawn #(apply sh/sh args)))))

(defn git! [repo-dir & args]
  (let [{:keys [exit out err]} (apply sh-fiber "git" "-C" repo-dir args)]
    (if (zero? exit)
      out
      (throw (ex-info (str "git " (str/join " " args) " failed (" exit "): " (str/trim err))
                      {:repo repo-dir :args (vec args) :exit exit :err err})))))

(defn head-commit [repo-dir]
  (str/trim (git! repo-dir "rev-parse" "HEAD")))

(defn ref-commit [repo-dir ref-name]
  (let [{:keys [exit out]} (sh-fiber "git" "-C" repo-dir "rev-parse" "--verify" "--quiet" ref-name)]
    (when (zero? exit)
      (let [s (str/trim out)]
        (when-not (str/blank? s) s)))))

(defn current-branch [repo-dir]
  (str/trim (git! repo-dir "branch" "--show-current")))

(defn branch-git-ref
  "Standard local git branch ref (`refs/heads/seon/<name>`) so `git branch` and `git diff` see every DB branch!"
  [branch]
  (let [clean (if (namespace branch)
                (str (namespace branch) "/" (name branch))
                (name branch))
        clean (str/replace clean #"^branch/" "")]
    (str "refs/heads/seon/" clean)))

(defn tracked-path? [^String path]
  (or (= path "deps.edn")
      (= path "AGENTS.md")
      (str/starts-with? path "src/")
      (str/starts-with? path "test/")
      (str/starts-with? path "docs/")
      (str/starts-with? path "bin/")
      (str/starts-with? path "native/")
      (str/starts-with? path "resources/seon/")))

(defn ls-tracked-files
  "Single `git ls-files -s` call (~5 ms) returning `[{:path :exec?}]` for all tracked Seon files."
  [repo-dir]
  (->> (str/split-lines (git! repo-dir "ls-files" "-s"))
       (keep (fn [line]
               (when-not (str/blank? line)
                 (let [[meta-part path] (str/split line #"\t" 2)
                       mode (first (str/split meta-part #"\s+"))]
                   (when (and path (not= mode "160000") (tracked-path? path))
                     {:path path
                      :exec? (= mode "100755")})))))
       vec))

(defn bootstrap-tx
  "Cold-boot seed ONLY when `db` holds no `:git/commit`: imports tracked files from `repo-dir`."
  [db repo-dir]
  (let [sha (head-commit repo-dir)
        br (current-branch repo-dir)
        files (ls-tracked-files repo-dir)
        ;; Import schemas & config first so code validation sees them
        ordered (sort-by (fn [{:keys [path]}]
                           [(cond (= path "resources/seon/config/default.edn") 0
                                  (= path "resources/seon/schemas.edn") 1
                                  :else 2)
                            path])
                         files)
        file-txs (mapcat (fn [{:keys [path exec?]}]
                           (let [f (io/file repo-dir path)]
                             (when (.isFile f)
                               (code/file-import-tx db path (slurp f) exec?))))
                         ordered)]
    (vec (concat file-txs
                 [{:git/repo :main
                   :git/commit sha
                   :git/branch (if (str/blank? br) "HEAD" br)}]))))

(defn- retract-path-tx [db path]
  (cond
    (first (d/datoms db :avet :ns/path path))
    (let [ns-dt (first (d/datoms db :avet :ns/path path))
          nsym (:ns/name (d/pull db '[:ns/name] (:e ns-dt)))]
      (vec (concat (map #(vector :db/retractEntity [:form/key (:form/key %)]) (code/ns-forms db nsym))
                   (map #(vector :db/retractEntity [:var/name (:var/name %)]) (code/ns-vars db nsym))
                   [[:db/retractEntity [:ns/name nsym]]])))

    (code/md-path? path)
    (mapv #(vector :db/retractEntity [:doc/id (:doc/id %)]) (code/doc-sections db path))

    :else
    [[:db/retractEntity [:file/path path]]]))

(defn catch-up-tx
  "O(|Delta_git|) boot check: if `:git/commit` on `db` equals `HEAD`, reads ZERO files and returns `[]`!
   If `HEAD` moved while Seon was stopped, imports only `git diff --name-status <stored>..HEAD`."
  [db repo-dir]
  (let [stored (:git/commit (d/pull db '[:git/commit] [:git/repo :main]))
        head (head-commit repo-dir)]
    (cond
      (nil? stored)
      (bootstrap-tx db repo-dir)

      (= stored head)
      []

      :else
      (let [diff-lines (str/split-lines (git! repo-dir "diff" "--name-status" stored head))
            ops (mapcat
                 (fn [line]
                   (when-not (str/blank? line)
                     (let [[status path] (str/split line #"\t" 2)]
                       (when (and path (tracked-path? path))
                         (if (str/starts-with? status "D")
                           (retract-path-tx db path)
                           (let [f (io/file repo-dir path)]
                             (when (.isFile f)
                               (code/file-import-tx db path (slurp f) (.canExecute f)))))))))
                 diff-lines)]
        (vec (concat ops [{:git/repo :main :git/commit head}]))))))

(defn export-branch-ref!
  "Exports Datahike `branch` onto local git branch `refs/heads/seon/<branch>` via `git fast-import`
   in O(|touched-paths|) with ZERO working-tree modifications and ZERO worktrees!"
  [ours-conn cfg repo-dir branch]
  (let [plan (merge/three-way ours-conn cfg branch)
        theirs-db (d/branch-as-db (:store @ours-conn) branch)
        base-git (or (:git/commit (d/pull @ours-conn '[:git/commit] [:git/repo :main]))
                     (head-commit repo-dir))
        prev-ref-sha (ref-commit repo-dir (branch-git-ref branch))
        ref-name (branch-git-ref branch)
        now-sec (quot (System/currentTimeMillis) 1000)
        sb (StringBuilder.)]
    (.append sb (str "commit " ref-name "\n"))
    (.append sb (str "committer Seon <seon@local> " now-sec " +0000\n"))
    (let [msg (str "seon " branch " @ tx " (:max-tx theirs-db) "\n")]
      (.append sb (str "data " (alength (.getBytes msg "UTF-8")) "\n" msg)))
    (when base-git
      (.append sb (str "from " base-git "\n")))
    (when (and prev-ref-sha (not= prev-ref-sha base-git))
      (.append sb (str "merge " prev-ref-sha "\n")))
    (doseq [path (sort (:touched-paths plan))]
      (if-let [txt (code/render-path theirs-db path)]
        (let [bs (.getBytes ^String txt "UTF-8")
              exec? (boolean (:file/exec? (d/pull theirs-db '[:file/exec?] [:file/path path])))
              mode (if exec? "100755" "100644")]
          (.append sb (str "M " mode " inline " path "\n"))
          (.append sb (str "data " (alength bs) "\n"))
          (.append sb txt)
          (.append sb "\n"))
        (.append sb (str "D " path "\n"))))
    (.append sb "done\n")
    (let [{:keys [exit err]} (sh-fiber "git" "-C" repo-dir "fast-import" "--done" "--quiet"
                                       :in (.toString sb))]
      (when-not (zero? exit)
        (throw (ex-info (str "git fast-import failed: " err) {:branch branch :err err})))
      {:ref ref-name
       :sha (ref-commit repo-dir ref-name)
       :touched-paths (:touched-paths plan)})))

(defn write-and-commit-main!
  "Writes `touched-paths` rendered from `db` to `repo-dir` and creates an atomic local Git commit
   (a true 2-parent Git merge commit when `merged-branch` has a `refs/heads/seon/*` commit!).
   Never pushes to GitHub."
  ([db repo-dir expected-sha touched-paths message]
   (write-and-commit-main! db repo-dir expected-sha touched-paths message nil))
  ([db repo-dir expected-sha touched-paths message merged-branch]
   (if (empty? touched-paths)
     {:committed? false :commit expected-sha}
     (let [cur-sha (head-commit repo-dir)]
       (when (and expected-sha (not= cur-sha expected-sha))
         (throw (ex-info "Git HEAD moved outside Seon; refusing to overwrite working tree"
                         {:expected expected-sha :actual cur-sha})))
       (doseq [path (sort touched-paths)]
         (let [f (io/file repo-dir path)]
           (if-let [txt (code/render-path db path)]
             (do (.mkdirs (.getParentFile f))
                 (spit f txt)
                 (when (:file/exec? (d/pull db '[:file/exec?] [:file/path path]))
                   (.setExecutable f true)))
             (when (.exists f)
               (.delete f)))))
       (apply git! repo-dir "add" "-A" "--" (vec (sort touched-paths)))
       (let [status (str/trim (git! repo-dir "status" "--porcelain" "--"))]
         (if (str/blank? status)
           {:committed? false :commit cur-sha}
           (let [tree-sha (str/trim (git! repo-dir "write-tree"))
                 branch-sha (when merged-branch (ref-commit repo-dir (branch-git-ref merged-branch)))
                 ct-args (cond-> ["commit-tree" tree-sha "-p" cur-sha]
                           (and branch-sha (not= branch-sha cur-sha)) (conj "-p" branch-sha)
                           true (conj "-m" message))
                 new-sha (str/trim (apply git! repo-dir ct-args))]
             (git! repo-dir "update-ref" "-m" message "HEAD" new-sha)
             {:committed? true :commit new-sha :branch-parent branch-sha})))))))
