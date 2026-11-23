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
            [seon.merge :as merge]
            [clojure.edn :as edn]
            [seon.code.analyze :as analyze]
            [seon.code.parse :as parse]
            [seon.schema :as schema]
            [seon.git.forks :as forks]
            [seon.code.io :as cio]))

(defn sh-fiber [& args] (apply forks/sh-fiber args))

(defn git! [& args] (apply forks/git! args))

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
        existing-ns  (if (and db (contains? dh-sch :seon.code.ns/name))
                       (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.ns/name)) {})
        existing-var (if (and db (contains? dh-sch :seon.code.var/name))
                       (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.code.var/name)) {})
        slurp-file (fn [path]
                     (let [f (io/file repo-dir path)]
                       (when (.isFile f) (slurp f))))
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
        {:keys [ns->ref var->ref]}
        (reduce (fn [acc [path txt]]
                  (let [{:keys [ns vars]} (parse/read-clj path txt)
                        chain (parse/ns-parent-chain-tx ns)
                        acc1 (reduce (fn [m ne]
                                       (let [n (:seon.code.ns/name ne)]
                                         (assoc-in m [:ns->ref n] (or (get existing-ns n) (analyze/ns-tempid n)))))
                                     acc chain)]
                    (reduce (fn [m v]
                              (let [vs (:seon.code.var/name v)]
                                (assoc-in m [:var->ref vs] (or (get existing-var vs) (analyze/var-tempid vs)))))
                            acc1 vars)))
                {:ns->ref existing-ns :var->ref existing-var}
                p1-texts)
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
                             (when (.isFile f)
                               (code/file-import-tx db path (slurp f) (.canExecute f)))))))))
                 diff-lines)]
        (vec (concat ops [{:seon.git/repo :main :seon.git/commit head}]))))))

(defn- clean-one-line [s]
  (when (string? s)
    (let [t (str/trim (str/replace s #"\s+" " "))]
      (when-not (str/blank? t) t))))

(defn- safe-bucket [b]
  (let [v (fn [k] (let [x (when (map? b) (get b k))]
                    (if (and (coll? x) (not (map? x))) (vec x) [])))]
    {:added (v :added) :modified (v :modified) :deleted (v :deleted)}))

(defn- format-bucket-counts [{:keys [added modified deleted]} labels?]
  (let [na (count added) nm (count modified) nd (count deleted)]
    (when (pos? (+ na nm nd))
      (if labels?
        (str/join ", " (remove nil? [(when (pos? na) (str "+" na " added"))
                                      (when (pos? nm) (str "~" nm " modified"))
                                      (when (pos? nd) (str "-" nd " removed"))]))
        (str/join " / " (remove nil? [(when (pos? na) (str "+" na))
                                      (when (pos? nm) (str "~" nm))
                                      (when (pos? nd) (str "-" nd))]))))))

(defn- infer-commit-scope [nss paths schemas rules routes docs branch]
  (let [strip-seon (fn [s] (str/replace s #"^seon\." ""))
        clean-sc (fn [s]
                   (when (string? s)
                     (let [t (-> s str/trim (str/replace #"[^a-zA-Z0-9._/-]" "") strip-seon)]
                       (when-not (str/blank? t)
                         (if (> (count t) 18) (subs t 0 18) t)))))
        non-test-nss (remove #(str/ends-with? (name %) "-test") nss)
        eff-nss (if (seq non-test-nss) non-test-nss nss)]
    (or (when (seq eff-nss)
          (let [uniq (vec (distinct eff-nss))]
            (if (= 1 (count uniq))
              (clean-sc (str/replace (name (first uniq)) #"-test$" ""))
              (let [top-seg (fn [n] (first (str/split (strip-seon (name n)) #"\.")))
                    [best _] (apply max-key val (frequencies (map top-seg eff-nss)))]
                (clean-sc best)))))
        (when (pos? (+ (count (:added schemas)) (count (:modified schemas)) (count (:deleted schemas)))) "schemas")
        (when (pos? (+ (count (:added rules)) (count (:modified rules)) (count (:deleted rules)))) "rules")
        (when (pos? (+ (count (:added routes)) (count (:modified routes)) (count (:deleted routes)))) "routes")
        (when (seq docs)
          (if (= 1 (count docs))
            (clean-sc (str/replace (last (str/split (first docs) #"/")) #"\.md$" ""))
            "docs"))
        (when (seq paths)
          (let [p (first (sort paths))]
            (cond
              (str/starts-with? p "test/") "test"
              (str/starts-with? p "bench/") "bench"
              (str/starts-with? p "bin/") "bin"
              (str/starts-with? p "resources/seon/") (clean-sc (str/replace (last (str/split p #"/")) #"\.edn$" ""))
              :else (clean-sc (first (str/split p #"/"))))))
        (when (keyword? branch) (clean-sc (name branch))))))

(defn render-merge-commit-message
  "Pure Conventional-Commit formatter over a structured merge summary map.
   Guarantees a single-line subject `<= 72` chars plus structured non-empty body sections."
  {:malli/schema [:=> [:cat :map] :string]
   :cost/ticks 2000000}
  [summary]
  (let [branch (:branch summary)
        max-tx (when (int? (:max-tx summary)) (:max-tx summary))
        agent-id (when (keyword? (:agent-id summary)) (:agent-id summary))
        task (when (map? (:task summary)) (:task summary))
        fixes (if (and (coll? (:fixes summary)) (not (map? (:fixes summary))))
                (filterv map? (:fixes summary))
                [])
        schemas (safe-bucket (:schemas summary))
        rules (safe-bucket (:rules summary))
        routes (safe-bucket (:routes summary))
        vars (safe-bucket (:vars summary))
        var-nss (let [raw (when (map? (:vars summary)) (:namespaces (:vars summary)))]
                  (if (and (coll? raw) (not (map? raw))) (filterv symbol? raw) []))
        all-vars (concat (:added vars) (:modified vars) (:deleted vars))
        inferred-nss (if (seq var-nss)
                       var-nss
                       (vec (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %))) all-vars)))
        docs (if (and (coll? (:docs summary)) (not (map? (:docs summary))))
               (vec (distinct (filter string? (:docs summary))))
               [])
        paths (if (and (coll? (:touched-paths summary)) (not (map? (:touched-paths summary))))
                (vec (distinct (sort (filter string? (:touched-paths summary)))))
                [])
        doc-path? #(or (= % "AGENTS.md") (str/starts-with? % "docs/"))
        test-path? #(str/starts-with? % "test/")
        ctype (cond
                (seq fixes) "fix"
                (or (and (seq paths) (every? doc-path? paths))
                    (and (seq docs) (empty? paths) (empty? all-vars)
                         (nil? (format-bucket-counts schemas false))
                         (nil? (format-bucket-counts rules false))
                         (nil? (format-bucket-counts routes false)))) "docs"
                (or (and (seq paths) (every? test-path? paths))
                    (and (seq all-vars)
                         (every? #(and (symbol? %) (namespace %) (str/ends-with? (namespace %) "-test")) all-vars)
                         (nil? (format-bucket-counts schemas false))
                         (nil? (format-bucket-counts rules false))
                         (nil? (format-bucket-counts routes false)))) "test"
                (or (seq (:added vars)) (seq (:added schemas)) (seq (:added rules)) (seq (:added routes))) "feat"
                (or (seq (:modified vars)) (seq (:deleted vars)) (some #(str/starts-with? % "src/") paths)) "refactor"
                :else "chore")
        scope (infer-commit-scope inferred-nss paths schemas rules routes docs branch)
        mech-parts (remove nil?
                           [(when (seq (:added schemas)) (str "add " (count (:added schemas)) " schema" (when (> (count (:added schemas)) 1) "s")))
                            (when (seq (:modified schemas)) (str "update " (count (:modified schemas)) " schema" (when (> (count (:modified schemas)) 1) "s")))
                            (when (seq (:added rules)) (str "add " (count (:added rules)) " rule" (when (> (count (:added rules)) 1) "s")))
                            (when (seq (:modified rules)) (str "update " (count (:modified rules)) " rule" (when (> (count (:modified rules)) 1) "s")))
                            (when (seq (:added routes)) (str "add " (count (:added routes)) " route" (when (> (count (:added routes)) 1) "s")))
                            (when (seq (:added vars)) (str "add " (count (:added vars)) " var" (when (> (count (:added vars)) 1) "s")))
                            (when (seq (:modified vars)) (str "update " (count (:modified vars)) " var" (when (> (count (:modified vars)) 1) "s")))
                            (when (seq (:deleted vars)) (str "remove " (count (:deleted vars)) " var" (when (> (count (:deleted vars)) 1) "s")))
                            (when (seq docs) (str "update " (count docs) " doc" (when (> (count docs) 1) "s")))])
        mech-sum (or (when (seq mech-parts) (str/join ", " (take 3 mech-parts)))
                     (when (seq paths) (str "update " (count paths) " file" (when (> (count paths) 1) "s")))
                     (str "merge " (if (keyword? branch) (name branch) "branch")))
        sum-text (or (some #(clean-one-line (:change-summary %)) fixes)
                     (clean-one-line (:title task))
                     mech-sum)
        prefix (if scope (str ctype "(" scope "): ") (str ctype ": "))
        raw-subj (str prefix sum-text)
        subject (if (<= (count raw-subj) 72)
                  raw-subj
                  (str (subs raw-subj 0 (max 1 (- 72 3))) "..."))
        br-meta (str/join ", " (remove nil? [(when max-tx (str "tx " max-tx))
                                            (when agent-id (str "agent " agent-id))]))
        sch-line (when-let [c (format-bucket-counts schemas false)]
                   (let [ks (take 5 (concat (:added schemas) (:modified schemas) (:deleted schemas)))]
                     (str "Schemas: " c (when (seq ks) (str " (" (str/join ", " ks) ")")))))
        rule-line (when-let [c (format-bucket-counts rules false)] (str "Rules: " c))
        route-line (when-let [c (format-bucket-counts routes false)] (str "Routes: " c))
        var-line (when-let [c (format-bucket-counts vars true)]
                   (let [top-ns (mapv key (take 3 (sort-by (comp - val) (frequencies inferred-nss))))]
                     (str "Vars: " c (when (seq top-ns) (str " (" (str/join ", " top-ns) ")")))))
        doc-line (when (seq docs) (str "Docs: " (str/join ", " (take 4 docs))))
        task-line (when (or (:id task) (clean-one-line (:title task)))
                    (str "Task: " (or (:id task) "")
                         (when-let [t (clean-one-line (:title task))]
                           (str (when (:id task) " — ") t))))
        task-sum-line (when-let [ts (clean-one-line (:summary task))] (str "Summary: " ts))
        fix-lines (keep (fn [fx]
                          (let [ec (:error-class fx)
                                ba (or (clean-one-line (:before-after fx)) (clean-one-line (:change-summary fx)))]
                            (when (or ec ba)
                              (str "Fix: " (or ec :fix) (when ba (str " — " ba))))))
                        fixes)
        body (vec (concat
                   (when branch [(str "Branch: " branch (when-not (str/blank? br-meta) (str " (" br-meta ")")))])
                   (when task-line [task-line])
                   (when task-sum-line [task-sum-line])
                   fix-lines
                   (remove nil? [sch-line rule-line route-line var-line doc-line
                                 (when (seq paths) (str "Files: " (count paths) " touched"))])))]
    (if (seq body)
      (str subject "\n\n" (str/join "\n" body))
      subject)))

(defn format-merge-commit-message
  "Synthesizes a Conventional-Commit Git message mechanically from Datahike `db`, `branch`, `plan`,
   and optional `opts` (`:agent-id`, `:task-id`, `:task`, `:max-tx`) with zero LLM calls."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :keyword :map] :string]
                  [:=> [:cat [:maybe :seon.host/db] :keyword :map [:maybe :map]] :string]]
   :cost/ticks 5000000}
  ([db branch plan] (format-merge-commit-message db branch plan nil))
  ([db branch plan opts]
   (let [sm (when (map? db) (:schema db))
         diffs (or (:entity-diffs plan) [])
         tx-maps (filterv map? (:tx-data plan))
         by-kind (fn [attr]
                   (reduce (fn [acc {:keys [entity status]}]
                             (if (and (vector? entity) (= attr (first entity)))
                               (let [k (case status :added :added :deleted :deleted :modified)]
                                 (update acc k conj (second entity)))
                               acc))
                           {:added [] :modified [] :deleted []}
                           diffs))
         ag (when (and db (contains? sm :seon.agent/branch))
              (or (when-let [aid (:agent-id opts)]
                    (when (first (d/datoms db :avet :seon.agent/id aid))
                      (d/pull db '[:seon.agent/id :seon.agent/task :seon.agent/archived?] [:seon.agent/id aid])))
                  (let [dts (if (get-in sm [:seon.agent/branch :db/index])
                              (d/datoms db :avet :seon.agent/branch branch)
                              (filter #(= branch (:v %)) (d/datoms db :aevt :seon.agent/branch)))]
                    (->> dts
                         (map #(d/pull db '[:seon.agent/id :seon.agent/task :seon.agent/archived?] (:e %)))
                         (remove :seon.agent/archived?)
                         first))))
         agent-id (or (:agent-id opts) (:seon.agent/id ag))
         task-id (or (:task-id opts)
                     (:seon.agent/task ag)
                     (some (fn [{:keys [entity status]}]
                             (when (and (vector? entity) (= :seon.task/id (first entity)) (not= status :deleted))
                               (second entity)))
                           diffs))
         task-ent (or (:task opts)
                      (when task-id
                        (let [from-db (when (and db (contains? sm :seon.task/id)
                                                 (first (d/datoms db :avet :seon.task/id task-id)))
                                        (d/pull db '[:seon.task/id :seon.task/title :seon.task/summary]
                                                [:seon.task/id task-id]))
                              from-tx (some #(when (= task-id (:seon.task/id %)) %) tx-maps)
                              m (merge from-db from-tx)]
                          (when (seq m)
                            {:id task-id
                             :title (:seon.task/title m)
                             :summary (:seon.task/summary m)}))))
         fix-ids (keep (fn [{:keys [entity status]}]
                         (when (and (vector? entity) (= :seon.fix/id (first entity)) (not= status :deleted))
                           (second entity)))
                       diffs)
         fixes (mapv (fn [fid]
                       (let [from-db (when (and db (contains? sm :seon.fix/id)
                                                (first (d/datoms db :avet :seon.fix/id fid)))
                                       (d/pull db '[:seon.fix/error-class :seon.fix/change-summary :seon.fix/before-after]
                                               [:seon.fix/id fid]))
                             from-tx (some #(when (= fid (:seon.fix/id %)) %) tx-maps)
                             m (merge from-db from-tx)]
                         {:id fid
                          :error-class (:seon.fix/error-class m)
                          :change-summary (:seon.fix/change-summary m)
                          :before-after (:seon.fix/before-after m)}))
                     fix-ids)
         vars-b (by-kind :seon.code.var/name)
         extra-nss (keep (fn [{:keys [entity]}]
                           (when (vector? entity)
                             (case (first entity)
                               :seon.code.ns/name (second entity)
                               :seon.code.form/key (let [s (str (second entity))
                                                         p (first (str/split s #"[#/]" 2))]
                                                     (when-not (str/blank? p) (symbol p)))
                               nil)))
                         diffs)
         var-nss (vec (concat (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))
                                    (concat (:added vars-b) (:modified vars-b) (:deleted vars-b)))
                              extra-nss))
         paths (vec (sort (or (:touched-paths plan) [])))
         docs (vec (distinct (concat (filter #(or (= % "AGENTS.md") (str/starts-with? % "docs/")) paths)
                                     (keep #(when (and (vector? (:entity %)) (= :seon.code.doc/id (first (:entity %))) (:path %))
                                              (:path %))
                                           diffs))))]
     (render-merge-commit-message
      {:branch branch
       :max-tx (or (:max-tx opts) (when (map? db) (:max-tx db)))
       :agent-id agent-id
       :task task-ent
       :fixes fixes
       :schemas (by-kind :seon.schema/key)
       :rules (by-kind :seon.rule/id)
       :routes (by-kind :seon.route/name)
       :vars (assoc vars-b :namespaces var-nss)
       :docs docs
       :touched-paths paths}))))

(defn export-branch-ref!
"Exports Datahike `branch` onto zero-worktree `refs/heads/seon/<branch>` via `git fast-import`.
 Accepts an optional precomputed `merge/three-way` `plan` to avoid recomputing the merge plan."
{:seon.flow/edge true
 :malli/schema [:function
                [:=> [:cat :seon.host/conn :map :string :seon.store/branch] :map]
                [:=> [:cat :seon.host/conn :map :string :seon.store/branch [:maybe :map]] :map]]}
([ours-conn cfg repo-dir branch]
 (export-branch-ref! ours-conn cfg repo-dir branch nil))
([ours-conn cfg repo-dir branch plan]
 (let [plan (or plan (merge/three-way ours-conn cfg branch))
       theirs-db (d/branch-as-db (:store @ours-conn) branch)
       base-git (or (:seon.git/commit (d/pull @ours-conn '[:seon.git/commit] [:seon.git/repo :main]))
                    (head-commit repo-dir))
       prev-ref-sha (ref-commit repo-dir (branch-git-ref branch))
       ref-name (branch-git-ref branch)
       now-sec (quot (System/currentTimeMillis) 1000)
       subj (first (str/split-lines (format-merge-commit-message theirs-db branch plan)))
       sb (StringBuilder.)]
   (.append sb (str "commit " ref-name "\n"))
   (.append sb (str "committer Seon <seon@local> " now-sec " +0000\n"))
   (let [msg (str subj "\n")]
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
      :touched-paths (:touched-paths plan)}))))