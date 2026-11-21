(ns seon.git
  "Explicit Git & filesystem boundary (`Datahike :main === Git main` 1-to-1):
   - Cold bootstrap (`bootstrap-tx`) when a new store holds no `:seon.git/commit`.
   - O(|Delta|) boot catch-up (`catch-up-tx`) if git HEAD moved while Seon was stopped (0 file reads when equal).
   - Zero-worktree `refs/heads/seon/<branch>` export via `git fast-import`.
   - O(|touched-paths|) atomic write-back & true 2-parent Git merge commit on `main` (never pushes to GitHub)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.fibers :as fibers]
            [seon.code :as code]
            [seon.merge :as merge]))

(defn sh-fiber [& args]
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

(defn branch-git-ref [branch]
  (let [clean (if (namespace branch)
                (str (namespace branch) "/" (name branch))
                (name branch))
        clean (str/replace clean #"^branch/" "")]
    (str "refs/heads/seon/" clean)))

(defn tracked-path?
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String path]
  (or (= path "deps.edn")
      (= path "AGENTS.md")
      (str/starts-with? path "src/")
      (str/starts-with? path "test/")
      (str/starts-with? path "docs/")
      (str/starts-with? path "bin/")
      (str/starts-with? path "native/")
      (str/starts-with? path "resources/seon/")
      (str/starts-with? path "bench/")))

(defn ls-tracked-files [repo-dir]
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
  {:seon.flow/edge true, :malli/schema [:=> [:cat [:maybe :seon.host/db] :string] [:vector :any]]}
  [db repo-dir]
  (let [sha (head-commit repo-dir)
        br (current-branch repo-dir)
        files (ls-tracked-files repo-dir)
        ordered (sort-by (fn [{:keys [path]}]
                           [(cond (= path "resources/seon/config/default.edn") 0
                                  (code/schema-file-path? path) 1
                                  (code/rule-file-path? path) 2
                                  (code/task-file-path? path) 3
                                  (code/agent-file-path? path) 4
                                  :else 2)
                            path])
                         files)
        file-txs (mapcat (fn [{:keys [path exec?]}]
                           (let [f (io/file repo-dir path)]
                             (when (.isFile f)
                               (code/file-import-tx db path (slurp f) exec?))))
                         ordered)]
    (vec (concat file-txs
                 [{:seon.git/repo :main
                   :seon.git/commit sha
                   :seon.git/branch (if (str/blank? br) "HEAD" br)}]))))

(defn- retract-path-tx [db path]
  (cond
    (first (d/datoms db :avet :seon.code.ns/path path))
    (let [ns-dt (first (d/datoms db :avet :seon.code.ns/path path))
          nsym (:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e ns-dt)))]
      (vec (concat (map #(vector :db/retractEntity [:seon.code.form/key (:seon.code.form/key %)]) (code/ns-forms db nsym))
                   (map #(vector :db/retractEntity [:seon.code.var/name (:seon.code.var/name %)]) (code/ns-vars db nsym))
                   [[:db/retractEntity [:seon.code.ns/name nsym]]])))

    (code/md-path? path)
    (mapv #(vector :db/retractEntity [:seon.code.doc/id (:seon.code.doc/id %)]) (code/doc-sections db path))

    (code/rule-file-path? path)
    (if (contains? (:schema db) :seon.rule/id)
      (vec (for [dt (d/datoms db :aevt :seon.rule/id)]
             [:db/retractEntity [:seon.rule/id (:v dt)]]))
      [])

    (code/task-file-path? path)
    (if (= path "resources/seon/tasks.edn")
      (vec (for [dt (d/datoms db :aevt :seon.task/id)]
             [:db/retractEntity [:seon.task/id (:v dt)]]))
      (let [prefix (subs path (count "resources/seon/tasks/") (- (count path) 4))]
        (vec (for [dt (d/datoms db :aevt :seon.task/id)
                   :when (= prefix (namespace (:v dt)))]
               [:db/retractEntity [:seon.task/id (:v dt)]]))))

    (code/agent-file-path? path)
    (if (contains? (:schema db) :seon.agent/seed-path)
      (vec (for [dt (d/datoms db :aevt :seon.agent/seed-path)
                 :when (= path (:v dt))]
             [:db/retractEntity (:e dt)]))
      [])

    :else
    [[:db/retractEntity [:seon.code.file/path path]]]))

(defn catch-up-tx [db repo-dir]
  (let [stored (:seon.git/commit (d/pull db '[:seon.git/commit] [:seon.git/repo :main]))
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
        (vec (concat ops [{:seon.git/repo :main :seon.git/commit head}]))))))

(defn export-branch-ref! [ours-conn cfg repo-dir branch]
  (let [plan (merge/three-way ours-conn cfg branch)
        theirs-db (d/branch-as-db (:store @ours-conn) branch)
        base-git (or (:seon.git/commit (d/pull @ours-conn '[:seon.git/commit] [:seon.git/repo :main]))
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
    (let [deleted-paths (or (:deleted-paths plan) #{})]
    (doseq [path (sort (:touched-paths plan))]
      (if-let [txt (code/render-path theirs-db path)]
        (let [bs (.getBytes ^String txt "UTF-8")
              exec? (boolean (:seon.code.file/exec? (d/pull theirs-db '[:seon.code.file/exec?] [:seon.code.file/path path])))
              mode (if exec? "100755" "100644")]
          (.append sb (str "M " mode " inline " path "\n"))
          (.append sb (str "data " (alength bs) "\n"))
          (.append sb txt)
          (.append sb "\n"))
        (when (contains? deleted-paths path)
          (.append sb (str "D " path "\n"))))))
    (.append sb "done\n")
    (let [{:keys [exit err]} (sh-fiber "git" "-C" repo-dir "fast-import" "--done" "--quiet"
                                       :in (.toString sb))]
      (when-not (zero? exit)
        (throw (ex-info (str "git fast-import failed: " err) {:branch branch :err err})))
      {:ref ref-name
       :sha (ref-commit repo-dir ref-name)
       :touched-paths (:touched-paths plan)})))

(defn write-and-commit-main!
  ([db repo-dir expected-sha touched-paths message]
   (write-and-commit-main! db repo-dir expected-sha touched-paths message nil #{}))
  ([db repo-dir expected-sha touched-paths message merged-branch]
   (write-and-commit-main! db repo-dir expected-sha touched-paths message merged-branch #{}))
  ([db repo-dir expected-sha touched-paths message merged-branch deleted-paths]
   (if (empty? touched-paths)
     {:committed? false :commit expected-sha}
     (let [cur-sha (head-commit repo-dir)
           del-set (set deleted-paths)]
       (when (and expected-sha (not= cur-sha expected-sha))
         (throw (ex-info "Git HEAD moved outside Seon; refusing to overwrite working tree"
                         {:expected expected-sha :actual cur-sha})))
       (doseq [path (sort touched-paths)]
         (let [f (io/file repo-dir path)]
           (if-let [txt (code/render-path db path)]
             (do (.mkdirs (.getParentFile f))
                 (spit f txt)
                 (when (:seon.code.file/exec? (d/pull db '[:seon.code.file/exec?] [:seon.code.file/path path]))
                   (.setExecutable f true)))
             (if (contains? del-set path)
               (when (.exists f)
                 (.delete f))
               (throw (ex-info (str "Refusing blind deletion of " path ": render-path returned nil without an explicit entity deletion")
                               {:error :seon.git/refused-blind-delete :path path}))))))
       (apply git! repo-dir "add" "-A" "--" (vec (sort touched-paths)))
       (let [status (str/trim (git! repo-dir "status" "--porcelain" "--"))]
         (if (str/blank? status)
           {:committed? false :commit cur-sha}
           (let [tree-sha (str/trim (git! repo-dir "write-tree"))
                 branch-sha (when merged-branch (ref-commit repo-dir (branch-git-ref merged-branch)))
                 ct-args (cond-> ["-c" "user.name=Seon" "-c" "user.email=seon@local"
                                  "commit-tree" tree-sha "-p" cur-sha]
                           (and branch-sha (not= branch-sha cur-sha)) (conj "-p" branch-sha)
                           true (conj "-m" message))
                 new-sha (str/trim (apply git! repo-dir ct-args))]
             (git! repo-dir "-c" "user.name=Seon" "-c" "user.email=seon@local"
                   "update-ref" "-m" message "HEAD" new-sha)
             {:committed? true :commit new-sha :branch-parent branch-sha})))))))
