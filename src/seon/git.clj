(ns seon.git
  "Explicit Git & filesystem boundary (`Datahike :main === Git main` 1-to-1):
   - Cold bootstrap (`bootstrap-tx`) when a new store holds no `:seon.git/commit`.
   - O(|Delta|) boot catch-up (`catch-up-tx`) if git HEAD moved while Seon was stopped (0 file reads when equal).
   - Zero-worktree `refs/heads/seon/<branch>` export via `git fast-import`.
   - O(|touched-paths|) atomic write-back & true 2-parent Git merge commit on `main` (never pushes to GitHub)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.merge :as merge]
            [clojure.edn :as edn]
            [seon.code.parse :as parse]
            [seon.schema :as schema]
            [seon.git.forks :as forks]
            [seon.code.io :as cio]
            [jolt.fs :as fs]
            [seon.git.message :as gmsg]))

(defn sh-fiber
  "Forwards to `seon.git.forks/sh-fiber`."
  {:malli/schema [:function [:=> [:cat [:* :any]] :map]]}
  [& args]
  (apply forks/sh-fiber args))

(defn git!
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat :string [:* :string]] :string]]}
  [& args] (apply forks/git! args))

(defn head-commit
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat :string] :string]]}
  [repo-dir]
  (str/trim (git! repo-dir "rev-parse" "HEAD")))

(defn ref-commit
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat :string :string] [:maybe :string]]]}
  [repo-dir ref-name]
  (let [{:keys [exit out]} (sh-fiber "git" "-C" repo-dir "rev-parse" "--verify" "--quiet" ref-name)]
    (when (zero? exit)
      (let [s (str/trim out)]
        (when-not (str/blank? s) s)))))

(defn current-branch
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat :string] :string]]}
  [repo-dir]
  (str/trim (git! repo-dir "branch" "--show-current")))

(defn branch-git-ref
  {:malli/schema [:=> [:cat :keyword] :string], :cost/ticks 500000}
  [branch]
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

(defn ls-tracked-files
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat :string] [:vector :map]]]}
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

(defn write-and-commit-main!
  {:seon.flow/edge true, :malli/schema [:function [:=> [:cat :seon.host/db :string [:maybe :string] [:or [:sequential :string] [:set :string]] :string] :map] [:=> [:cat :seon.host/db :string [:maybe :string] [:or [:sequential :string] [:set :string]] :string [:maybe :keyword]] :map] [:=> [:cat :seon.host/db :string [:maybe :string] [:or [:sequential :string] [:set :string]] :string [:maybe :keyword] [:set :string]] :map]]}
  ([db repo-dir expected-sha touched-paths message]
   (write-and-commit-main! db repo-dir expected-sha touched-paths message nil #{}))
  ([db repo-dir expected-sha touched-paths message merged-branch]
   (write-and-commit-main! db repo-dir expected-sha touched-paths message merged-branch #{}))
  ([db repo-dir expected-sha touched-paths message merged-branch deleted-paths]
   (if (empty? touched-paths)
     {:committed? false :commit expected-sha}
     (let [cur-sha (head-commit repo-dir)
           del-set (set deleted-paths)
           {main-paths :main fork-map :forks} (forks/partition-paths-by-repo touched-paths)]
       (when (and expected-sha (not= cur-sha expected-sha))
         (throw (ex-info "Git HEAD moved outside Seon; refusing to overwrite working tree"
                         {:expected expected-sha :actual cur-sha})))
       (doseq [path main-paths]
         (let [f (io/file repo-dir path)]
           (if-let [txt (code/render-path db path)]
             (do (fs/create-dirs (fs/parent f))
                 (spit f txt)
                 (when (:seon.code.file/exec? (d/pull db '[:seon.code.file/exec?] [:seon.code.file/path path]))
                   (.setExecutable f true)))
             (if (contains? del-set path)
               (when (.exists f)
                 (fs/delete-if-exists f))
               (throw (ex-info (str "Refusing blind deletion of " path ": render-path returned nil without an explicit entity deletion")
                               {:error :seon.git/refused-blind-delete :path path}))))))
       (let [fork-res (mapv (fn [[root rels]] (forks/write-and-commit-fork! db repo-dir root rels merged-branch message del-set)) fork-map)
            dirty-synced (keep (fn [p]
                                 (let [p (str/trim p)
                                       f (when (and (not (str/blank? p)) (tracked-path? p)) (io/file repo-dir p))]
                                   (when (and f (fs/regular-file? f) (= (slurp f) (code/render-path db p))) p)))
                               (str/split-lines (or (:out (sh-fiber "git" "-C" repo-dir "diff" "--name-only")) "")))
            stage-paths (vec (distinct (concat main-paths dirty-synced (map :root fork-res))))]
       (apply git! repo-dir "add" "-A" "--" stage-paths))
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

(defn file-priority
  "Canonical cold-boot import priority: 0 = schemas, 1 = clj src/test, 2 = docs/AGENTS, 3 = rules/tasks/agents/config."
  {:malli/schema [:=> [:cat :string] :int]
   :cost/ticks 50000}
  [^String path]
  (cond
    (code/schema-file-path? path) 0
    (parse/clj-path? path) 1
    (code/md-path? path) 2
    :else 3))

(defn- sub-priority [^String path]
  (cond
    (= path "resources/seon/config/default.edn") 0
    (code/rule-file-path? path) 1
    (code/task-file-path? path) 2
    (code/agent-file-path? path) 3
    :else 4))

(defn bootstrap-tx
  "Builds a single atomic cold-boot transaction for `repo-dir` in priority order
   (0=schemas, 1=src/test clj, 2=docs/AGENTS, 3=rules/tasks/agents/config), resolving cross-file
   `:calls`, `:input`/`:optional-input`/`:output`, `:requires-ns`, `:references`, and `:rule/{schema,fn}` refs."
  {:seon.flow/edge true
   :malli/schema [:=> [:cat [:maybe :seon.host/db] :string] [:vector :any]]}
  [db repo-dir]
  (let [sha (head-commit repo-dir)
        br (current-branch repo-dir)
        files (sort-by (fn [{:keys [path]}] [(file-priority path) (sub-priority path) path])
                       (ls-tracked-files repo-dir))
        dh-sch (when db (:schema db))
        existing-sch (if (and db (contains? dh-sch :seon.schema/key))
                       (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key)) {})
        slurp-file (fn [path]
                     (let [f (io/file repo-dir path)]
                       (when (fs/regular-file? f) (slurp f))))
        by-prio (group-by #(file-priority (:path %)) files)
        p0 (get by-prio 0 [])
        p1 (get by-prio 1 [])
        p2 (get by-prio 2 [])
        p3 (get by-prio 3 [])
        p0-texts (into {} (keep (fn [{:keys [path]}] (when-let [t (slurp-file path)] [path t]))) p0)
        p0-forms (reduce (fn [acc [_ txt]]
                           (let [m (edn/read-string txt)]
                             (if (map? m) (merge acc m) acc)))
                         {} p0-texts)
        schema->ref (merge (into {} (map (fn [k] [k (or (get existing-sch k) (schema/schema-tempid k))])) (keys p0-forms))
                           existing-sch)
        reg-info (schema/registry-for-db db p0-forms)
        p1-texts (into {} (keep (fn [{:keys [path]}] (when-let [t (slurp-file path)] [path t]))) p1)
        {:keys [ns->ref var->ref]} (cio/batch-ref-ctx db p1-texts)
        batch-ctx {:schema->ref schema->ref
                   :known-schemas (set (keys schema->ref))
                   :registry (:registry reg-info)
                   :ns->ref ns->ref
                   :var->ref var->ref
                   :skip-compiler-analyze? true}
        p0-txs (mapcat (fn [{:keys [path exec?]}]
                         (when-let [txt (get p0-texts path)]
                           (code/file-import-tx db path txt exec?)))
                       p0)
        p1-txs (mapcat (fn [{:keys [path exec?]}]
                         (when-let [txt (get p1-texts path)]
                           (code/file-import-tx db path txt exec? batch-ctx)))
                       p1)
        p23-txs (mapcat (fn [{:keys [path exec?]}]
                          (when-let [txt (slurp-file path)]
                            (code/file-import-tx db path txt exec? batch-ctx)))
                        (concat p2 p3))]
    (vec (concat p0-txs p1-txs p23-txs
                 [{:seon.git/repo :main
                   :seon.git/commit sha
                   :seon.git/branch (if (str/blank? br) "HEAD" br)}]))))

(defn catch-up-tx
  "Boot catch-up: runs `bootstrap-tx` on empty store, 0 file reads when HEAD matches, or imports diff files in priority order."
  {:seon.flow/edge true
   :malli/schema [:=> [:cat [:maybe :seon.host/db] :string] [:vector :any]]}
  [db repo-dir]
  (let [stored (:seon.git/commit (d/pull db '[:seon.git/commit] [:seon.git/repo :main]))
        head (head-commit repo-dir)]
    (cond
      (nil? stored)
      (bootstrap-tx db repo-dir)

      (= stored head)
      []

      :else
      (let [diff-lines (sort-by (fn [line]
                                  (let [[_ p] (str/split line #"\t" 2)]
                                    [(if p (file-priority p) 9) (or p "")]))
                                (str/split-lines (git! repo-dir "diff" "--name-status" stored head)))
            ops (mapcat
                 (fn [line]
                   (when-not (str/blank? line)
                     (let [[status path] (str/split line #"\t" 2)]
                       (when (and path (tracked-path? path))
                         (if (str/starts-with? status "D")
                           (retract-path-tx db path)
                           (let [f (io/file repo-dir path)]
                             (when (fs/regular-file? f)
                             (code/file-import-tx db path (slurp f) (fs/executable? f)))))))))
                 diff-lines)]
        (vec (concat ops [{:seon.git/repo :main :seon.git/commit head}]))))))

(defn ^{:seon.code/reexport 'seon.git.message/render-merge-commit-message} render-merge-commit-message [& args] (apply gmsg/render-merge-commit-message args))

(defn ^{:seon.code/reexport 'seon.git.message/format-merge-commit-message} format-merge-commit-message [& args] (apply gmsg/format-merge-commit-message args))

(defn export-branch-ref!
"Exports non-`:main` Datahike `branch` onto zero-worktree `refs/heads/seon/<branch>` via `git fast-import --force`.
 Accepts an optional precomputed `merge/three-way` `plan` to avoid recomputing the merge plan."
{:seon.flow/edge true
 :malli/schema [:function
                [:=> [:cat :seon.host/conn :map :string :seon.store/branch] :map]
                [:=> [:cat :seon.host/conn :map :string :seon.store/branch [:maybe :map]] :map]]}
([ours-conn cfg repo-dir branch]
 (export-branch-ref! ours-conn cfg repo-dir branch nil))
([ours-conn cfg repo-dir branch plan]
 (when (= branch :main)
   (throw (ex-info "Cannot export :main as a feature branch ref" {:branch branch})))
 (let [ref-name (branch-git-ref branch)
       plan (or plan (merge/three-way ours-conn cfg branch))
       theirs-db (d/branch-as-db (:store @ours-conn) branch)
       base-git (or (:seon.git/commit (d/pull @ours-conn '[:seon.git/commit] [:seon.git/repo :main]))
                    (head-commit repo-dir))
       now-sec (quot (System/currentTimeMillis) 1000)
       subj (first (str/split-lines (format-merge-commit-message theirs-db branch plan)))
       sb (StringBuilder.)]
   (.append sb (str "commit " ref-name "\n"))
   (.append sb (str "committer Seon <seon@local> " now-sec " +0000\n"))
   (let [msg (str subj "\n")]
     (.append sb (str "data " (alength (.getBytes msg "UTF-8")) "\n" msg)))
   (when base-git
     (.append sb (str "from " base-git "\n")))
   (let [deleted-paths (or (:deleted-paths plan) #{})]
     (doseq [path (:main (forks/partition-paths-by-repo (:touched-paths plan)))]
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
   (let [{:keys [exit err]} (sh-fiber "git" "-C" repo-dir "fast-import" "--force" "--done" "--quiet"
                                      :in (.toString sb))]
     (when-not (zero? exit)
       (throw (ex-info (str "git fast-import failed: " err) {:branch branch :err err})))
     {:ref ref-name
      :sha (ref-commit repo-dir ref-name)
      :touched-paths (:touched-paths plan)}))))

(defn delete-branch-ref!
  "Deletes `refs/heads/seon/<clean>` in `repo-dir` via `git update-ref -d` (no-op for `:main` or absent ref)."
  {:seon.flow/edge true, :malli/schema [:=> [:cat :string :keyword] :map], :doc "Deletes `refs/heads/seon/<clean>` in `repo-dir` via `git update-ref -d` (no-op for `:main` or absent ref). (idempotent ref cleanup)."}
  [repo-dir branch]
  (if (= branch :main)
    {:deleted? false :ref nil :branch branch :reason :protected-main}
    (let [ref-name (branch-git-ref branch)
          prev-sha (ref-commit repo-dir ref-name)]
      (if-not prev-sha
        {:deleted? false :ref ref-name :branch branch :reason :not-found}
        (let [{:keys [exit err]} (sh-fiber "git" "-C" repo-dir "update-ref" "-d" ref-name)]
          (when-not (zero? exit)
            (throw (ex-info (str "git update-ref -d failed for " ref-name ": " err)
                            {:branch branch :ref ref-name :exit exit :err err})))
          {:deleted? true :ref ref-name :branch branch :prev-sha prev-sha})))))