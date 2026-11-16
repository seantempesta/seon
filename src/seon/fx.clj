(ns seon.fx
  "Effects (session FX1, the session design papers in git history). Every outside effect kind has ONE
  owner proc in root behind a bounded channel, and `workers` performer procs:

    agent --socket--> agent proc --:fx/<kind>--> owner --:work--> worker (performs)
    agent <--socket-- agent proc <--api :back--- owner <--:done-- worker

  The owner admits (concurrency = its workers; priority and budget are request attributes,
  rate and budget counters its live state), queues by priority, and replies {:dest :r}
  on the api's :back, the route the writer's answers already take. A worker performs one
  request with the kind's perform Var (the interop edge: it returns its declared error as
  data) and is the entrant boundary for a defect in it. Nothing here writes a branch: the
  asker records the outcome in its own recording transaction."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.lib :as lib] [seon.step :as step] [seon.config :as cfg] [seon.model :as model]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [clojure.string :as str] [jolt.ffi :as ffi] [jolt.deps :as deps]
            [seon.edn :as sedn] [clojure.edn :as edn] [clojure.java.io :as io] [seon.proofs :as proofs] [seon.reader :as reader] [seon.program :as program] [seon.intr :as intr] [seon.db :as pdb] [seon.task :as task]))

(defn- error
  "An effect's declared failure, as the one error map (its variant registered in
  seon.problems.edn), raised by op: the performer that failed, by its var (so a rename breaks
  the compile, never the name an error carries)."
  {:malli/schema [:=> [:cat :seon.host/var :qualified-keyword :string :map] :seon.error/base]}
  [op type message data]
  (contract/error type op message data))

;; ---- performers: the interop edges, one Var per kind ------------------------------------

(defn echo "The test effect: answers the request as its value." {:malli/schema [:=> [:cat :map] :map]}
  [req] {:value (:req req)})

(defn write-file
  "Write-back: the text to path (its directory made when missing), staged beside it and
  renamed over it (rename(2)), so a reader sees the old or the new file, and a file already there keeps its mode. Answers {:value {:bytes n :path path}}, n the path's
  size on disk after the rename, or :seon.fx/io-error {:path :tmp :errno :message} (errno
  absent when the stage itself failed) with the staged file removed."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [path text]} :req}]
  (let [tmp (str path ".fx-" (lib/c-getpid))
        staged (try (.mkdirs (.getParentFile (java.io.File. ^String tmp))) (spit tmp text)
                    (when (.exists (java.io.File. ^String path))
                      (java.nio.file.Files/setPosixFilePermissions (.toPath (java.io.File. ^String tmp))
                                                                   (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. ^String path)) (into-array java.nio.file.LinkOption []))))
                    nil
                    (catch Throwable e
                      (.delete (java.io.File. tmp))
                      (error #'seon.fx/write-file :seon.fx/io-error (str "staging " tmp ": " (step/error-msg e)) {:path path :tmp tmp})))]
    (or staged
        (let [[r errno] (lib/c-rename tmp path)]
          (if (zero? r)
            {:value {:bytes (.length (java.io.File. ^String path)) :path path}}
            (do (.delete (java.io.File. tmp))
                (error #'seon.fx/write-file :seon.fx/io-error (str "rename " tmp " -> " path ": " (ffi/errno-message errno)) {:path path :tmp tmp :errno errno})))))))

;; Seon's own source: the program read from the tree a generation was built from, and
;; main's merged forms committed for the next one (seon.source). Never the working tree:
;; reading is read-only, and a commit is git's plumbing on a ref, so no checkout moves.

(declare tree-id)

(defn- git-run
  "git in dir with args, under the environment variables env adds ({name value}), fed in when given:
  its sh result. The one place src runs git (seon.main-test/git-is-read-through-fx); git! and
  git-try are its two readings. git reads the repository's own config and no other (GIT_CONFIG_GLOBAL /dev/null,
  GIT_CONFIG_NOSYSTEM): what a tree id names, an ignored file among it, is a function of the tree and the repository,
  never of whose HOME runs it (one tree had two ids when the owner's global ignore file was read by bin/tree in a session
  and not by a root, whose HOME is its target/home). See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat [:map-of :string :string] :string [:sequential :string] [:maybe :string]] :map]}
  [env dir args in]
  (let [env (merge {"GIT_CONFIG_GLOBAL" "/dev/null" "GIT_CONFIG_NOSYSTEM" "1"} env)
        cmd (concat (cons "env" (for [[k v] (sort env)] (str k "=" v))) ["git" "-C" dir] args)]
    (if in (apply lib/sh (concat cmd [:in in])) (apply lib/sh cmd))))

(defn- git-failed
  "git's failure of args in result r, as the exception git! and git-try throw: git's words."
  {:malli/schema [:=> [:cat [:sequential :string] :map] :any]}
  [args r]
  (ex-info (str "git " (str/join " " args) ": " (str/trim (str (:err r) (:out r)))) {:exit (:exit r)}))

(defn- git!
  "git in dir with args (git-run), its trimmed output; a failure throws git's words (git-commit
  answers it as :seon.fx/io-error)."
  {:malli/schema [:=> [:cat [:map-of :string :string] :string [:sequential :string] [:maybe :string]] :string]}
  [env dir args in]
  (let [r (git-run env dir args in)]
    (when-not (zero? (:exit r)) (throw (git-failed args r)))
    (str/trim (:out r))))

(defn- git-try
  "git in dir with args (git-run), for a read whose no is an answer (no commit yet, no such branch
  or ref, a detached HEAD: git exits 1): its trimmed output when git exits 0, nil when it exits 1;
  any other exit (not a repository, git's own failure) throws git's words, as git! does."
  {:malli/schema [:=> [:cat :string [:sequential :string]] [:maybe :string]]}
  [dir args]
  (let [r (git-run {} dir args nil)]
    (case (int (:exit r))
      0 (str/trim (:out r))
      1 nil
      (throw (git-failed args r)))))

(defn- checked-out?
  "Is directory d a git checkout of its own (a submodule checked out: its .git), not only a
  directory inside its parent's work tree (where git rev-parse answers for the parent)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [d] (.exists (java.io.File. ^String d ".git")))

(defn files-under
  "Every regular file under dir (the directory the caller names, followed if it is itself a link),
  each once, depth first in name order: a directory reached through a symbolic link is not entered,
  so a link back to an ancestor (a cycle) or out of the tree adds nothing and the walk's work is
  the entries the tree holds. A link to a file is a file. Never `file-seq`, which follows links
  (a checkout's target/ holding a link to itself hung an agent for 13 minutes: docs/failure-points.md,
  a directory walk). Answers a vector of java.io.File: dir's one file when it is a file, none when it is neither."
  {:malli/schema [:=> [:cat :string] [:vector :any]]}
  [dir]
  (let [root (java.io.File. ^String dir)]
    (if (.isDirectory root)
      (loop [todo [root] out []]
        (if (empty? todo)
          out
          (let [^java.io.File d (peek todo)
                kids (sort-by #(.getName ^java.io.File %) (or (.listFiles d) []))
                subdirs (filterv #(and (.isDirectory ^java.io.File %) (not (java.nio.file.Files/isSymbolicLink (.toPath ^java.io.File %)))) kids)
                files (filterv #(.isFile ^java.io.File %) kids)]
            (recur (into (pop todo) (rseq subdirs)) (into out files)))))
      (if (.isFile root) [root] []))))

(defn run-dir
  "The directory of one test child's own, named by its pid: under the agents' working directory
  (:seon.config.agent/cwd), where the child works so that whatever a test writes relative to its
  working directory (`target/...`, the temporary files of a fixture) is the run's, removed with
  it (seon.lib/remove-tree!) and never left in the directory every agent shares. Answers the path; it is
  made by the child (made-run-dir!). See docs/failure-points.md, a directory walk."
  {:malli/schema [:=> [:cat :int] :string]}
  [pid] (str (cfg/value :seon.config.agent/cwd) "/tests/" pid))

(def child-env-scrub
  "The `env -u` argv that gives a child process the tree's own environment (docs/source.md#the-next-generation):
  bin/env and bin/system export names for this root's checkout, runs and home, and a child inheriting
  SEON_SOURCE_ROOT listed its test namespaces from that checkout, not the tree it ran in (a full run's plan
  named a test of the root's working tree that main does not hold, and its shard died at once), and its ports (a check
  step the :prove service ran took the standing system's SEON_WEB_PORT 7777 and its root failed on the port in use). Used
  by build, a test tree's item, a check step and the health step's base (test-runner/base-program)."
  ["env" "-u" "SEON_CHECKOUT" "-u" "JOLT_LMDB_LIB" "-u" "JOLT_PWD" "-u" "HOME"
   "-u" "SEON_SOURCE_ROOT" "-u" "SEON_RUNS" "-u" "SEON_WEB_PORT" "-u" "SEON_SERVER_PORT"])

(def child-env-variables
  "The variables child-env-scrub removes: the ones that name a checkout's root, runs, home, libraries and ports. See
  docs/source.md#the-next-generation."
  (mapv second (filter #(= "-u" (first %)) (partition 2 1 child-env-scrub))))

(def root-variables
  "The variables only a root's own process has, bin/system's for the standing system: its source root, runs and ports. A
  process forked from root that is not root drops them (seon.agent/hold-env!), so its config never reads root's source tree
  (:seon.config.source/root is #env SEON_SOURCE_ROOT). See docs/source.md#the-next-generation."
  ["SEON_SOURCE_ROOT" "SEON_RUNS" "SEON_WEB_PORT" "SEON_SERVER_PORT"])

(defn checkout-env!
  "This process's environment made checkout's own, as bin/test starts a shard of it: child-env-variables removed, then
  SEON_CHECKOUT, HOME, JOLT_PWD and JOLT_LMDB_LIB set from checkout as bin/env and bin/test set them. For a process forked from another checkout's (a test child of the
  service, forked from root's agent), so what it starts (a test's root, bin/system) reads checkout's tree, never root's.
  See docs/source.md#the-next-generation."
  {:malli/schema [:=> [:cat :string] :nil]}
  [checkout]
  (doseq [v child-env-variables] (lib/c-unsetenv v))
  ;; what bin/env and bin/test give a shard of checkout, each from checkout as they derive it
  (doseq [[v x] {"SEON_CHECKOUT" checkout "HOME" (str checkout "/target/home") "JOLT_PWD" checkout
                 "JOLT_LMDB_LIB" (str checkout "/target/lib/liblmdb.dylib")}]
    (lib/c-setenv v x 1))
  nil)

(defn source-file?
  "Is the file at relative path p one a read of the source takes, under a source dir: Clojure or EDN, or a kind the
  allow-list names (seon.config/file-kind). See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String p] (boolean (or (.endsWith p ".clj") (.endsWith p ".edn") (cfg/file-kind p))))

(defn library-source
  "The :library effect: the source of every root the program depends on (classpath roots, and the binary's embedded archive) whose
  identity differs from since, {:value {:roots {root identity} :files {path {:text :root :rel}}}}. See docs/vector.md#search."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root pins since]} :req}]
  (try
    (let [base (.getCanonicalPath (java.io.File. ^String root))
          rel-of (fn [p] (if (str/starts-with? p (str base "/")) (subs p (inc (count base))) p))
          pinned (fn [rr] (some (fn [[pp c]] (when (or (= rr pp) (str/starts-with? rr (str pp "/"))) (str pp "@" c))) pins))
          src? #(re-find #"\.(clj|cljc|cljs|jolt)$" %)
          deps (edn/read-string (slurp (str base "/deps.edn")))
          roots (->> (concat (keep #(when (pinned %) (str base "/" %)) (:paths deps)) (:roots (deps/resolve-deps (:deps deps) base)))
                     (filter #(.exists (java.io.File. ^String %))) distinct)
          ;; the running binary's identity (its build record, held since its boot): what its embedded jar holds
          digest (or (:digest (program/binary-program)) "unbuilt")
          ids (into {"jolt-embedded-resources.jar" digest} (for [r roots :let [rr (rel-of r)]] [rr (or (pinned rr) rr)]))
          due (into #{} (remove #(= (ids %) (get since %))) (keys ids))
          entries (fn [^java.util.zip.ZipFile z rr]
                    (for [e (enumeration-seq (.entries z)) :when (src? (.getName e))]
                      [(str rr "!/" (.getName e)) {:text (slurp (.getInputStream z e)) :root rr :rel (.getName e)}]))
          files (into (sorted-map)
                      (concat
                        (for [r roots :let [rr (rel-of r)] :when (due rr) kv (if (.isDirectory (java.io.File. ^String r))
                                                                             (for [f (file-seq (java.io.File. ^String r)) :when (and (.isFile ^java.io.File f) (src? (.getName ^java.io.File f)))
                                                                                   :let [rel (subs (.getPath ^java.io.File f) (inc (count r)))]]
                                                                               [(str rr "/" rel) {:text (slurp f) :root rr :rel rel}])
                                                                             (entries (java.util.zip.ZipFile. ^String r) rr))]
                          kv)
                        (when (due "jolt-embedded-resources.jar")
                          (entries (.getJarFile ^java.net.JarURLConnection (.openConnection (io/resource "jolt/ir.clj"))) "jolt-embedded-resources.jar"))))]
      {:value {:roots ids :files files}})
    (catch Throwable e (error #'seon.fx/library-source :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn read-source
  "The text of every .clj and .edn file under root's dirs (relative paths, e.g.
  src/seon/fx.clj, resources/seon/schemas/seon.facts.edn), sorted by path, the commit root's
  HEAD names and the revision it pins each reference-code submodule at (its gitlink), both
  absent when root is no git repository (git answers none): what a seed or a re-index reads,
  never a boot (docs/source.md#indexing-a-tree).
  With :paths (relative paths, the files my.root/import! names) only those files, each nil when
  it is gone, and none of the others read: the cost of what changed. In a git repository also
  :tree, the id of the whole tree as it is on disk (tree-id: git's own index makes it the cost of
  what changed), which stands for every file's content.
  With :since (the tree id of the last import, and no :paths; :since-dirs, the dirs that import read), only the files under dirs that differ from it, and every file of a dir it didn't read, are read, so what main merged and the tree never held stays main's. A tree git can no longer find reads whole.
  Answers {:value {path text} :modes {path git mode, of each file the allow-list names} :commit sha :branch name (nil when detached) :tree id :paths paths-when-read-by-them :pins {path sha}} or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root dirs paths since since-dirs]} :req}]
  (try
    (let [base (.getCanonicalPath (java.io.File. ^String root))
          head (when (checked-out? base) (git-try base ["rev-parse" "--verify" "--quiet" "HEAD"]))
          now (when head (tree-id base))
          paths (or paths
                    (when (and since now)
                      (let [d (git-run {} base (into ["diff" "--name-only" since now "--"] dirs) nil)]
                        (when (zero? (:exit d))
                          ;; a dir added since that import has never been read, though nothing in it moved
                          (into (vec (remove str/blank? (str/split-lines (:out d))))
                                (for [nd dirs :when (and since-dirs (not (some #{nd} since-dirs))) f (files-under (str base "/" nd))]
                                  (subs (.getCanonicalPath ^java.io.File f) (inc (count base)))))))))
          source? source-file?
          src-read (cond-> {:value (if paths
                                     (into (sorted-map)
                                           (for [p paths :when (source? p) :let [f (java.io.File. (str base "/" p))]]
                                             [p (when (.isFile f) (slurp f))]))
                                     (into (sorted-map)
                                           (for [d dirs
                                                 f (files-under (str base "/" d))
                                                 :let [p (subs (.getCanonicalPath ^java.io.File f) (inc (count base)))]
                                                 :when (source? p)]
                                             [p (slurp f)])))}
                     head (assoc :commit head
                                 ;; the branch checked out, none when HEAD is detached: which system a page is (seon.render.ui/source-line)
                                 :branch (git-try base ["symbolic-ref" "--short" "-q" "HEAD"])
                                 :tree now
                                 :pins (into {}
                                             (for [[_ sha p] (re-seq #"(?m)^160000 ([0-9a-f]{40}) \d+\t(\S+)$"
                                                                     (git! {} base ["ls-files" "-s" "--" "reference-code"] nil))]
                                               [p sha])))
                     paths (assoc :paths paths))]
      ;; each file the allow-list names, as git records its mode
      (assoc src-read :modes (into {} (for [[p t] (:value src-read) :when (and t (cfg/file-kind p))]
                                        [p (if (.canExecute (java.io.File. (str base "/" p))) "100755" "100644")]))))
    (catch Throwable e (error #'seon.fx/read-source :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn messages-since
  "The messages of the commits git at root holds from commit from (excluded) to commit to: what a landing brought, read for the
  tasks it says it completes (seon.task/completes). \"\" when from is to; :seon.fx/io-error when git cannot read either. See
  docs/source.md#a-landing-enters-main-as-data."
  {:malli/schema [:=> [:cat :string :string :string] [:or :string :seon.error/base]]}
  [root from to]
  (try (if (= from to) "" (git! {} root ["log" "--format=%B" (str from ".." to)] nil))
       (catch Throwable e (error #'seon.fx/messages-since :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn changed-since
  "The files of the tree at root as it is on disk that differ from tree (a tree id seon.fx/tree-id made): git's diff of tree
  against the files, submodules left out (a pin is a build input, which seon.fx/runtime-for judges), and each untracked
  file git does not ignore whose content tree does not hold at its path, less :seon.config.source/tree-excludes' top-level paths. Each with its text (nil when gone), the mode
  of each the allow-list names, and, when any differs, the tree id now and HEAD's commit. About 40 ms on this checkout when
  nothing differs, where tree-id is ~0.4 s (git checking each submodule's files). Answers {:value {path text} :modes {path mode}
  :tree :commit}, or :seon.fx/io-error (git cannot find tree). See docs/source.md#a-boot-catches-up-with-its-checkout."
  {:malli/schema [:=> [:cat :string :string] :map]}
  [root tree]
  (try
    (let [lines #(remove str/blank? (str/split-lines %))
          out? (fn [p] (some #(or (= p %) (str/starts-with? p (str % "/"))) (cfg/value :seon.config.source/tree-excludes)))
          untracked (vec (remove out? (lines (git! {} root ["ls-files" "-o" "--exclude-standard"] nil))))
          ;; an untracked file differs when tree holds no blob of its content at its path (one ls-tree and one hash-object for all)
          held (when (seq untracked)
                 (into {} (for [l (lines (git! {} root (into ["ls-tree" tree "--"] untracked) nil)) :let [[m p] (str/split l #"\t" 2)]]
                            [p (nth (str/split m #" ") 2)])))
          hashes (when (seq untracked) (zipmap untracked (lines (git! {} root (into ["hash-object" "--"] untracked) nil))))
          ;; git's diff of a tree against the files names an untracked file the tree holds as changed whatever its content: those are
          ;; judged by content above; a file the tree holds that is gone from disk is no untracked file, so it stays a removal
          ps (sort (distinct (concat (remove (some-fn out? (set untracked)) (lines (git! {} root ["diff" "--name-only" "--no-renames" "--ignore-submodules=all" tree "--"] nil)))
                                     (filter #(not= (held %) (hashes %)) untracked))))
          texts (into (sorted-map) (for [p ps :let [f (java.io.File. (str root "/" p))]] [p (when (.isFile f) (slurp f))]))]
      (cond-> {:value texts
               :modes (into {} (for [[p t] texts :when (and t (cfg/file-kind p))]
                                 [p (if (.canExecute (java.io.File. (str root "/" p))) "100755" "100644")]))}
        (seq ps) (assoc :tree (tree-id root) :commit (git-try root ["rev-parse" "--verify" "--quiet" "HEAD"]))))
    (catch Throwable e (error #'seon.fx/changed-since :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn files-at
  "The files paths name (relative to root) as they are on disk, read as changed-since reads the files that changed: {:value {path
  text} :modes {path mode}}, a text nil when the file is gone, a mode for each the allow-list names. What a session's import
  reads (my.branch/import!): the files it names, never the tree's diff. See docs/source.md#a-boot-catches-up-with-its-checkout."
  {:malli/schema [:=> [:cat :string [:sequential :string]] :map]}
  [root paths]
  (try
    (let [texts (into (sorted-map) (for [p paths :let [f (java.io.File. (str root "/" p))]] [p (when (.isFile f) (slurp f))]))]
      {:value texts
       :modes (into {} (for [[p t] texts :when (and t (cfg/file-kind p))]
                         [p (if (.canExecute (java.io.File. (str root "/" p))) "100755" "100644")]))})
    (catch Throwable e (error #'seon.fx/files-at :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn- env-input?
  "Is the file at relative path p an input of every test: everything git tracks or would
  track but the source program facts carry (.clj under src and test), task files (data
  root loads, no test's code), text (.md) and the shipped config (resources/seon/config/: its
  values are :config's, which a test's digest takes by the keys it reads)? The schema resources
  are in: their entries are program facts, which a test's digest takes by the entries its reach
  names, and their class (env-class) for a reach that reads a db's whole schema.
  (claude_scratch/ is already out of the tree, tree-id's excludes.)"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [p]
  (not (or (str/ends-with? p ".md")
           (str/starts-with? p "resources/seon/tasks/")
           (str/starts-with? p "resources/seon/config/")
           (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))))))

(defn env-class
  "The class of the file at relative path p: :seon.env/system, /web, /deps, /bin (bin/ and the root's other files), /schemas, /docs (the
  wiki: docs/ and the .md files at the checkout's root), /tasks (the task files) or /src (the .clj
  files, which env-input? leaves to the program facts: a class only for a reach that reads the program's text), each a part of the checkout one kind of test reads (seon.program/env-class-readers),
  or nil (every test's: native/, the pins, the fixtures, everything else)."
  {:malli/schema [:=> [:cat :string] [:maybe :keyword]]}
  [p]
  (cond (= p "resources/seon/system.edn") :seon.env/system
        (or (str/starts-with? p "resources/public/") (str/starts-with? p "web/")) :seon.env/web
        (= p "deps.edn") :seon.env/deps
        ;; the wiki and the task files, each read whole by its own reader (seon.doc/pages, seon.system/task-files): a class, so a
        ;; wiki edit moves the tests that read the wiki and no other (a 4-line edit to docs/decisions.md ran 117 tests).
        ;; A .md file at the checkout's root (AGENTS.md, README.md) is the wiki's too, its top page (seon.doc): as the bin class,
        ;; which every process test and step takes, batch 77's AGENTS.md edit reran 463 of 1,785 tests and 36 of 40 steps
        (or (str/starts-with? p "docs/") (and (not (str/includes? p "/")) (str/ends-with? p ".md"))) :seon.env/docs
        ;; a file at the checkout's root (.gitmodules, .gitignore, .mcp.json): git's and the tools', read by git and the
        ;; bin/ scripts. In :env, which every test takes, a new submodule moved every test's digest, and a .gitignore
        ;; edit reran 1,600 of 1,891 keys (each pin is its own input, pin-class)
        (or (str/starts-with? p "bin/") (not (str/includes? p "/"))) :seon.env/bin
        (str/starts-with? p "resources/seon/schemas/") :seon.env/schemas
        (str/starts-with? p "resources/seon/tasks/") :seon.env/tasks
        ;; the program's text, which a test reading the whole program (a docstring, a form's source) reads
        (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))) :seon.env/src))

(defn config-input
  "The config in force as a test reads it, cfg (seon.config/config: the shipped file, the
  --config layers its command line names, the #env values, the profile), as {key text}: each
  value naming a file by its path written as <file>/ and its name (what the file is, never where the machine keeps it: the
  embed model's name is its id), any other value with the checkout's own path written as <checkout>, and the keys that say where a
  process runs left out (their schemas' :seon.config/place: its ports, main, store, runs,
  source tree and results directory, and the profile it runs under and what that picks, its provider, script and whether it is its store's sole writer: each checkout's and each root's own by design, as a test reads nothing of its runner, while
  :tree covers the source's content). What a result depends on that git does not see: a
  test's digest takes the keys its reach reads (seon.program/config-reads), so an agent's
  root and a checkout on one tree key their results alike, and a key no test reads moves
  none (docs/merging.md)."
  {:malli/schema [:=> [:cat :map :string] [:map-of :qualified-keyword :string]]}
  [cfg checkout]
  ;; a plain map: the store encodes no sorted one, and the digest sorts its keys (seon.program/graph-index)
  ;; a value that names a file enters as what the file is, its name, never where this machine keeps it (what the file holds
  ;; reaches a digest by the tree, the pins or its own name): the embed model, in the main tree's shared cache, read
  ;; "<checkout>/..." from the main tree and its absolute path from any other checkout, and 103 of 181 process digests
  ;; differed between the standing system and a worktree
  (let [outside? #(and (string? %) (str/starts-with? % "/"))]
    (into {} (for [[k v] cfg :when (not (get-in cfg/schema-forms [k 1 :seon.config/place]))]
               [k (if (outside? v)
                    (str "<file>/" (last (str/split v #"/")))
                    (str/replace (sedn/encode v) checkout "<checkout>"))]))))

(defn declared-refs
  "{seon.graph/read-decl #{symbol}}: every qualified symbol the system declaration's text names
  (its procs' step-fns, its effects' performers), which root resolves by name at boot: reach
  edges of the function that reads the declaration (seon.program/graph-index' :data-refs),
  so a test booting root reaches its procs. None when there is no text (a tree with no system.edn)."
  {:malli/schema [:=> [:cat [:maybe :string]] [:map-of :qualified-symbol [:set :qualified-symbol]]]}
  [text]
  (let [decl (when text (edn/read-string {:default tagged-literal} text))]
    {'seon.graph/read-decl (set (filter qualified-symbol? (tree-seq #(or (coll? %) (tagged-literal? %))
                                                                     #(if (tagged-literal? %) [(:form %)] (seq %)) decl)))}))

(defn tree-diff
  "What changed between git trees from and to of the repository at root: [[path old-mode new-mode] ...] (git diff --raw, no
  renames: a mode 160000 is a pin), or :seon.fx/io-error with git's words when git cannot diff them (a tree it does not
  hold). Two tree ids' diff never changes, so it is kept beside the checkout's builds (target/tree-diffs/FROM-TO.edn, derived
  data, read before git is asked): the plan that judged a binary current computed it, and root's boot reads it as a file,
  running no git (git's cost; every shell is on a fiber, docs/machine.md#processes). See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :string :string :string] [:or [:vector [:tuple :string :string :string]] :seon.error/base]]}
  [root from to]
  (let [f (java.io.File. (str root "/target/tree-diffs/" from "-" to ".edn"))]
    (if (.isFile f)
      (sedn/decode (slurp f))
      (let [r (git-run {} root ["diff" "--raw" "--no-renames" from to] nil)]
        (if (zero? (:exit r))
          (let [d (vec (for [l (str/split-lines (:out r)) :when (not (str/blank? l))
                             :let [[meta p] (str/split l #"\t" 2) [om nm] (str/split (subs meta 1) #" ")]]
                         [p om nm]))]
            (write-file {:req {:path (.getPath f) :text (sedn/encode d)}})
            d)
          (error #'seon.fx/tree-diff :seon.fx/io-error (str "git diff " from " " to ": " (str/trim (str (:err r)))) {:path root}))))))

(defn tree-file
  "The text of path in git tree tree of the repository at root (git show TREE:PATH), or nil when the tree holds no such
  file. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :string :string :string] [:maybe :string]]}
  [root tree path]
  (let [r (git-run {} root ["show" (str tree ":" path)] nil)]
    (when (zero? (:exit r)) (:out r))))

(defn- identity-lines
  "The lines a binary's identity is the digest of, for git tree tree in the repository at root, as a sorted set: tree's build
  inputs' and pins' git ls-tree lines (:seon.config.build/inputs; with no shipped config, this checkout's) and, for each config
  key a schema of tree's marks :seon.config/build, :seon.config/at-load or :seon.config/place, [key value] as default.edn states
  it over its schema entry's :default, read as EDN data. binary-digest digests them; build-differs names the ones two trees do not share. Four git calls; throws
  git's or the reader's words."
  {:malli/schema [:=> [:cat :string :string] [:set :string]]}
  [root tree]
  (let [lines (fn [args] (remove str/blank? (str/split-lines (git! {} root args nil))))
        ;; every EDN form a text holds, its tags kept as data (aero's #profile, #env): git show of several blobs is their texts end to end
        forms (fn [text] (let [r (java.io.PushbackReader. (java.io.StringReader. text)) end (Object.)]
                           (loop [acc []] (let [f (edn/read {:default tagged-literal :eof end} r)] (if (identical? end f) acc (recur (conj acc f)))))))
        ;; a tree with no shipped config (a test's scratch tree) is digested with this checkout's build inputs and no config lines
        shown (git-run {} root ["show" (str tree ":resources/seon/config/default.edn")] nil)
        cfg (if (zero? (:exit shown)) (first (forms (:out shown))) {})
        schemas (filter #(re-find #"/seon\.config[^/]*\.edn$" %) (lines ["ls-tree" "--name-only" tree "resources/seon/schemas/"]))
        entries (for [m (when (seq schemas) (forms (git! {} root (into ["show"] (map #(str tree ":" %)) schemas) nil))) [k f] m
                      :when (and (vector? f) (map? (second f)))]
                  [k (second f)])
        marked (for [[k p] entries :when (some p [:seon.config/build :seon.config/at-load :seon.config/place])] k)
        ;; a key the file does not state takes its schema's :default (as malli's default-value-transformer reads it in seon.config), so a value moved between them is one line
        cfg (merge (into {} (for [[k p] entries :when (contains? p :default)] [k (:default p)])) cfg)]
    (into (sorted-set)
          (concat (lines (into ["ls-tree" "-r" tree "--"] (or (:seon.config.build/inputs cfg) (cfg/value :seon.config.build/inputs))))
                  (filter #(str/starts-with? % "160000 ") (lines ["ls-tree" "-r" tree]))
                  (for [k marked :when (contains? cfg k)] (sedn/encode [k (get cfg k)]))))))

(defn binary-digest
  "The binary identity of git tree tree in the repository at root: the digest (seon.lib/digest) of its identity-lines, what only
  a new binary or process acts on: tree's build inputs, its pins and the config keys a schema marks :seon.config/build,
  :seon.config/at-load or :seon.config/place. What seon.system/built-program records with the binary and seon.fx/runtime-for
  compares; the program (src/'s forms) is not in it. See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat :string :string] [:or :seon.lib/digest :seon.error/base]]}
  [root tree]
  (try
    (lib/digest (str/join "\n" (identity-lines root tree)))
    (catch Throwable e (error #'binary-digest :seon.fx/io-error (str "the binary identity of tree " tree ": " (step/error-msg e)) {:path root}))))

(defn- tree-digest
  "The binary identity of git tree tree, read in the repository at repo (binary-digest): a tree is immutable, so its digest is
  computed once for the code that computes it and kept beside the builds of the checkout at root,
  target/runtime-for/TREE.CODE.digest, CODE the digest of (seon.program/running-form 'seon.fx/binary-digest): a change of
  binary-digest misses by construction. {:digest d} or binary-digest's error."
  {:malli/schema [:=> [:cat :string :string :string] [:or [:map [:digest :string]] :seon.error/base]]}
  [root repo tree]
  (let [f (java.io.File. (str root "/target/runtime-for/" tree "." (lib/digest (program/running-form 'seon.fx/binary-digest)) ".digest"))]
    (if (.isFile f)
      {:digest (str/trim (slurp f))}
      (let [d (binary-digest repo tree)]
        (if (contract/error? d)
          d
          (do (write-file {:req {:path (.getPath f) :text d}}) {:digest d}))))))

(defn- build-differs
  "What differs between the identity of the binary's tree (in the checkout at root's repository) and tree's (in repo's), named: each identity line the two do not share, by its path
  (a build input or a pin) or its config key; the same lines binary-digest digests, so a mismatch always names its cause. Every
  build input when the binary recorded no tree. The :seon.fx/io-error of a tree whose lines can't be read; an interrupt passes.
  Asked only on a mismatch."
  {:malli/schema [:=> [:cat :string :string [:maybe :string] :string] [:or [:vector :string] :seon.error/base]]}
  [root repo built-tree tree]
  (if-not built-tree
    (vec (cfg/value :seon.config.build/inputs))
    (try
      ;; each tree read where it was made: the binary's in this checkout's repository, the branch's in the source root's
      (let [a (identity-lines root built-tree) b (identity-lines repo tree)
            named (fn [l] (if (str/starts-with? l "[") (first (str/split (subs l 1) #"\s" 2)) (second (str/split l #"\t" 2))))]
        (vec (distinct (map named (sort (concat (remove b a) (remove a b)))))))
      (catch Throwable e
        (if (intr/interrupt? e)
          (throw e)
          (error #'build-differs :seon.fx/io-error (str "what differs between trees " built-tree " and " tree ": " (step/error-msg e)) {:path root}))))))

(defn runtime-for
  "The binary a process on tree's branch runs: {:binary its path :digest d} when the running binary's identity (its build record's
  :digest, seon.program/binary-program) is tree's (seon.fx/binary-digest, read in the source root's repository, where a tree is
  made: :seon.config.source/root, else the checkout), else :seon.process/env-mismatch
  naming both digests and :seon.process/differs (the build inputs and pins that differ), for its caller to build once. The one
  check of which runtime a branch needs, made at every start (root's boot, an agent's, a test child's fork) and by the restart
  predicate; the program (src/'s forms) is not in it: a process loads its branch's forms as a diff. With {:scope :all}, for a
  process whose store holds no Seon program and so runs the binary's baked one, the src tree must be the binary's too, and the
  answer adds :program, tree's src id. :record is the binary's build record to judge (default this process's,
  seon.program/binary-program). tree's digest is kept by tree id (tree-digest); a later ask costs one file read. See
  docs/source.md#the-binary-records-its-program."
  {:malli/schema [:function [:=> [:cat :string] [:or [:map [:binary :string] [:digest :string]] :seon.error/base]]
                  [:=> [:cat :string [:map [:scope {:optional true} [:enum :root :all]] [:record {:optional true} [:maybe :map]]]]
                   [:or [:map [:binary :string] [:digest :string] [:program {:optional true} :string]] :seon.error/base]]]}
  ([tree] (runtime-for tree {}))
  ([tree {:keys [scope record] :or {scope :root record (program/binary-program)}}]
   (let [root (cfg/checkout)
         ;; optional in the config's schema: absent, the checkout is the repository
         repo (or (:source-root (cfg/process)) root)
         read (fn [p] (let [f (java.io.File. (str root p))] (when (.isFile f) (str/trim (slurp f)))))
         ;; the RUNNING binary's identity, held since its boot (seon.program/binary-program): never target/seon's file, which a
         ;; later build replaces while this process still runs the old one (bin/update asked after its build, 2026-11-15)
         built (:digest record)
         built-tree (read "/target/seon.tree")
         w (tree-digest root repo tree)
         src (fn [t] (when t (let [r (git-run {} repo ["rev-parse" "--verify" "--quiet" (str t ":src")] nil)]
                               (when (zero? (:exit r)) (str/trim (:out r))))))
         mismatch (fn [want why]
                    (let [differs (build-differs root repo built-tree tree)]
                      (if (contract/error? differs)
                        differs
                        (error #'runtime-for :seon.process/env-mismatch
                               (str "target/seon's identity " (or built "(none recorded)") " is not tree " tree "'s " want ": " why)
                               {:seon.process/binary-digest built :seon.process/branch-digest want :seon.process/tree tree
                                :seon.process/differs differs}))))]
     (cond (contract/error? w) w
           (not= built (:digest w)) (mismatch (:digest w) "build it")
           (= :all scope) (let [p (src tree)]
                            (if (and p (= p (src built-tree)))
                              {:binary (str root "/target/seon") :digest (:digest w) :program p}
                              (mismatch (:digest w) "its program (src) is not the binary's: build it")))
           :else {:binary (str root "/target/seon") :digest (:digest w)}))))

(defn- drop-untracked-gitlinks!
  "Drop from the index env names every gitlink the checkout at root's own index does not hold: a pin is a gitlink the checkout
  tracks, and a nested repository only add -A takes in (an untracked clone) is no part of the tree, so it moves no tree and no
  binary identity (bin/tree, the same rule). See docs/source.md#the-binary-records-its-program."
  {:malli/schema [:=> [:cat [:map-of :string :string] :string] :any]}
  [env root]
  (let [links (fn [e] (set (for [l (str/split-lines (git! e root ["ls-files" "-s"] nil))
                                 :let [[m _ _ p] (str/split l #"\s+" 4)]
                                 :when (= "160000" m)]
                             p)))
        held (links {})
        extra (for [p (sort (links env)) :when (not (held p))] (str ":(top)" p))]
    (when (seq extra) (git! env root (into ["rm" "--cached" "-f" "-q" "--"] extra) nil))))

(defn tree-id
  "git's id of the tree at root as it is on disk: HEAD's tree (none before a first commit) with every
  changed and untracked unignored file added, in an index of its own (the repository's untouched), less
  the top-level paths excludes names (default
  :seon.config.source/tree-excludes: claude_scratch/, which no read and no build reads, so a commit to
  it moves nothing). bin/tree computes the same, which bin/build records for the tree it
  builds (target/seon.tree), so a root can tell whether its binary is built from its source."
  {:malli/schema [:function [:=> [:cat :string] :string] [:=> [:cat :string [:vector :string]] :string]]}
  ([root] (tree-id root (cfg/value :seon.config.source/tree-excludes)))
  ([root excludes]
   (let [idx (doto (java.io.File/createTempFile "seon-tree" ".index") (.delete))
         env {"GIT_INDEX_FILE" (.getPath idx)}
         top (map #(str ":(top)" %) excludes)]
     (try
       ;; that index starts as a copy of the checkout's own (with its mtime, which git's racy-clean check
       ;; compares) when that is HEAD's tree with no skipped entry: its stat cache then spares reading every
       ;; file that did not move. A staged change, an assume-unchanged or skip-worktree entry, or a forced
       ;; ignored file would survive `add -A`, so those read HEAD's tree afresh
       (let [real (java.io.File. ^String (git! {} root ["rev-parse" "--path-format=absolute" "--git-path" "index"] nil))
             head? (some? (git-try root ["rev-parse" "--verify" "--quiet" "HEAD"]))
             plain? (and head? (.isFile real)
                         (zero? (:exit (git-run {} root ["diff-index" "--cached" "--quiet" "HEAD"] nil)))
                         (every? #(str/starts-with? % "H ") (str/split-lines (git! {} root ["ls-files" "-v"] nil))))]
         (cond plain? (java.nio.file.Files/copy (.toPath real) (.toPath idx) (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/COPY_ATTRIBUTES]))
               head? (git! env root ["read-tree" "HEAD"] nil)))
       (git! env root ["add" "-A"] nil)
       (when (seq excludes) (git! env root (into ["rm" "-r" "--cached" "-q" "--ignore-unmatch" "--"] top) nil))
       (drop-untracked-gitlinks! env root)
       (git! env root ["write-tree"] nil)
       (finally (.delete idx))))))

(defn base-commit
  "The commit the checkout at root's health is read against (health is what a branch caused,
  docs/health.md#checks-are-callers-of-seon). With a
  branch other than git's main checked out (:seon.config.source/main; a detached HEAD too), its
  merge base with main: a branch with no commits of its own, or one main has passed, has HEAD
  itself or an older commit, so it caused only what it changes. With main checked out, its first
  parent (the previous main, which a merge is held to) when the tree is clean, else HEAD (what the
  uncommitted change caused). nil when there is no base: main's first commit (no parent), or a
  repository with no commit or no branch named main (the health step then says which, and its
  digest is missing); a merge base git cannot find throws git's words (git!)."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [root]
  (let [main (cfg/value :seon.config.source/main)
        on (git-try root ["symbolic-ref" "--short" "-q" "HEAD"])
        named? (fn [rev] (some? (git-try root ["rev-parse" "--verify" "--quiet" (str rev "^{commit}")])))]
    (cond (not (and (named? "HEAD") (named? main))) nil
          (not= main on) (git! {} root ["merge-base" "HEAD" main] nil)
          (not (str/blank? (git! {} root ["status" "--porcelain" "--ignore-submodules=all"] nil))) (git! {} root ["rev-parse" "HEAD"] nil)
          :else (git-try root ["rev-parse" "--verify" "--quiet" "HEAD^1"]))))

(defn landing-message
  "A session's landing message, read from its checkout at root: {:message :head}, the messages of its own commits (merges of main left
  out) from after since (the head the last landing read, when it is an ancestor of HEAD) or else from the checkout's merge base with
  main, to HEAD, and HEAD; nil with no base or no own commit since; :seon.fx/io-error when git cannot read them. A session's commits
  never reach git's main themselves (seon_done merges its files), so its merge base stays behind them: since is what keeps a landing
  from carrying the ones before it. Three or four git processes. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string [:maybe :string]] [:or [:maybe [:map [:message :string] [:head :string]]] :seon.error/base]]}
  [root since]
  (try
    (let [head (git! {} root ["rev-parse" "HEAD"] nil)
          from (or (when (and since (zero? (:exit (git-run {} root ["merge-base" "--is-ancestor" since head] nil)))) since)
                   (base-commit root))]
      (when (and from (not= from head))
        (let [m (str/trim (git! {} root ["log" "--no-merges" "--format=%B" (str from ".." head)] nil))]
          (when-not (str/blank? m) {:message m :head head}))))
    (catch Throwable e (error #'seon.fx/landing-message :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn behind
  "How many commits git's main (:seon.config.source/main) holds that the checkout at root's HEAD does not: what a session merges
  before a plan, never what its own change reached. 0 for main itself; nil when either has no commit. See docs/merging.md#a-branch-lands-with-binland."
  {:malli/schema [:=> [:cat :string] [:maybe nat-int?]]}
  [root]
  (some-> (git-try root ["rev-list" "--count" (str "HEAD.." (cfg/value :seon.config.source/main))]) str/trim parse-long))

(defn tree-of
  "The id of commit rev's tree in the repository at root."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [root rev] (git! {} root ["rev-parse" (str rev "^{tree}")] nil))

(defn checkout-at
  "Commit rev's files under paths, as git holds them, extracted into dir (made empty first), for a
  reader that reads a checkout's files (the :source read, the wiki's and the tasks' loaders).
  Answers dir; a failure throws git's or tar's stderr, as git! does."
  {:malli/schema [:=> [:cat :string :string [:sequential :string] :string] :string]}
  [root rev paths dir]
  (let [tar (java.io.File/createTempFile "seon-checkout-at" ".tar")]
    (try
      (git! {} root (concat ["archive" "-o" (.getPath tar) rev "--"] paths) nil)
      (let [r (lib/sh "zsh" "-c" "rm -rf \"$1\" && mkdir -p \"$1\" && tar -x -C \"$1\" -f \"$2\"" "checkout-at" dir (.getPath tar))]
        (when-not (zero? (:exit r))
          (throw (ex-info (str "checkout-at " rev ": " (str/trim (str (:err r) (:out r)))) {})))
        dir)
      (finally (.delete tar)))))

(defn pin
  "What a build or a test reads from the checkout at dir (a submodule), by content: its HEAD
  when its files are HEAD's, else HEAD+ the tree-id of its files as they are, so an
  uncommitted edit in a dependency is an input like a committed one (bin/build's pin computes
  the same, and builds it)."
  {:malli/schema [:=> [:cat :string] :string]}
  [dir]
  (let [out (git! {} dir ["status" "--porcelain=v2" "--branch" "-uall" "--ignore-submodules=all"] nil)
        status (str/split-lines out)
        ;; git's header line, when it printed one: a status that exits 0 with none (an empty output, seen once under load in a
        ;; seed, 2026-11-12) is said with what git printed, never a NullPointerException in the parse
        head (some->> (first (filter #(str/starts-with? % "# branch.oid ") status)) (re-find #"^# branch\.oid (\S+)") second)
        _ (when (nil? head) (throw (ex-info (str "pin " dir ": git status printed no branch header, only: " (if (str/blank? out) "nothing" (str/trim out))) {:dir dir})))
        _ (when (= "(initial)" head) (throw (ex-info (str "pin " dir ": it has no commit") {:dir dir})))]
    (if (every? #(str/starts-with? % "# ") status)
      head
      (str head "+" (tree-id dir [])))))

(defn blob-ids
  "{path blob} of every file of tree in the repository at root (git ls-tree -r: a submodule by its commit), the content id a
  recorded file read is keyed by (seon.program/reads-index). One git call."
  {:malli/schema [:=> [:cat :string :string] [:map-of :string :string]]}
  [root tree]
  (into {} (for [l (str/split-lines (git! {} root ["ls-tree" "-r" tree] nil)) :let [[m p] (str/split l #"\t" 2)] :when p]
             [p (nth (str/split m #" ") 2)])))

(defn inputs
  "The one producer of the checkout at root's inputs that have no program facts, for bin/test
  and for the binary's build record (seon.system/built-program, which a boot hands its agents):
  {:tree (tree-id: every file and pin, for a test reading files) :code (the digest of the program's source and the shipped config, for a check step, which runs them whatever its reach names; the wiki, the tasks and the tests are not in it) :env
  (every test's: the tree's env-input? entries of no env-class, among them the runtime's pins
  (:seon.config.source/runtime-pins: Jolt and Chez, a bump re-runs every test), and the per-test
  tick bound every verdict is a function of, :seon.config.agent/test-ticks) :config (the config in
  force, config-input, and a digest of each env-class of files and of each other submodule's pin
  (its files as they are), seon.config/pin-class: each test's digest takes the keys and classes it
  reads, so a library's pin moves the tests whose reach names a namespace it provides) :data-refs (declared-refs of its system.edn)}, by
  relative paths and content, so the same content at another path is the same inputs."
  {:malli/schema [:function [:=> [:cat :string] :seon.program/inputs] [:=> [:cat :string :map :string] :seon.program/inputs]
                  [:=> [:cat :string :map :string :string] :seon.program/inputs]]}
  ([root] (inputs root cfg/config (cfg/checkout)))
  ([root cfg checkout] (inputs root cfg checkout (tree-id root)))
  ;; tree: the checkout's tree-id the caller already holds (one per run: bin/test's plan computes it once)
  ([root cfg checkout tree]
   (let [runtime-pins (set (:seon.config.source/runtime-pins cfg))
         lines (str/split-lines (git! {} root ["ls-tree" "-r" tree] nil))
         entries (for [l lines :let [[m p] (str/split l #"\t" 2)] :when (or (env-input? p) (env-class p))]
                   ;; a submodule by its pin: its files as they are, an uncommitted edit included
                   ;; (a clean one's line is its gitlink's, as before; one not checked out has no files to read)
                   (let [d (str root "/" p)
                         pn (when (and (str/starts-with? m "160000") (checked-out? d)) (pin d))]
                     ;; the runtime's pins (Jolt, Chez) are no class: every test's :env takes them
                     [(or (env-class p) (when (and (str/starts-with? m "160000") (not (runtime-pins p))) (cfg/pin-class p)))
                      (if (and pn (str/includes? pn "+")) (str l " " pn) l)]))
         by-class (group-by first entries)]
     {:tree tree
      :code (lib/digest (str/join "\n" (filter #(let [p (second (str/split % #"\t" 2))] (or (str/starts-with? p "src/") (str/starts-with? p "resources/seon/config/"))) lines)))
      :env (lib/digest (str/join "\n" (concat (map second (get by-class nil)) [(str "test-ticks " (:seon.config.agent/test-ticks cfg))])))
      ;; the config in force, and each class of files a test may read (env-class): named inputs
      ;; a test's digest takes by what its reach reads
      :config (into (config-input cfg checkout)
                    (for [[c es] by-class :when c] [c (lib/digest (str/join "\n" (map second es)))]))
      :data-refs (declared-refs (let [f (java.io.File. (str root "/resources/seon/system.edn"))] (when (.isFile f) (slurp f))))})))

(defn unread-text
  "Why text does not read as the file at relative path is read, or nil, its meaning left to its owner: EDN reads as the EDN reader reads it (a tag stays a tagged literal), Clojure as Jolt's own reader loads it (no #=, every form of the text, each `::alias` of its ns form's requires resolving, in a namespace made for the read and removed after), a script passes its interpreter's own syntax check (zsh, sh and bash -n, perl -c, its text on stdin; any other file, or a script with no #! line, has no reader here to ask). See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [path text]
  (let [interp (when (str/starts-with? text "#!") (last (str/split (first (str/split (subs text 2) #"\s" 2)) #"/")))
        flag ({"zsh" "-n" "sh" "-n" "bash" "-n" "perl" "-c"} interp)]
    (cond (str/ends-with? path ".edn")
          (let [r (java.io.PushbackReader. (java.io.StringReader. text))]
            (try (loop [] (when-not (= ::end (edn/read {:eof ::end :default tagged-literal} r)) (recur)))
                 (catch Throwable e (ex-message e))))
          (str/ends-with? path ".clj")
          (let [scratch (create-ns (gensym "seon.fx.unread-")) made (atom [scratch])
                r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
                declare-aliases! (fn [form]
                                   (doseq [[ns-sym as a] (filter #(and (vector? %) (= 3 (count %)) (symbol? (first %)) (= :as (second %)) (symbol? (nth % 2))) (tree-seq coll? seq form))
                                           :when (not (get (ns-aliases scratch) a))]
                                     (when-not (find-ns ns-sym) (swap! made conj (create-ns ns-sym)))
                                     (binding [*ns* scratch] (alias a ns-sym))))]
            (try (binding [*ns* scratch *read-eval* false]
                   (loop [] (let [form (read {:eof ::end} r)] (when-not (= ::end form) (declare-aliases! form) (recur)))))
                 (catch Throwable e (ex-message e))
                 (finally (run! remove-ns (map ns-name @made)))))
          flag (let [r (lib/sh interp flag :in text)]
                 (when-not (zero? (:exit r)) (str/trim (str (:err r) (:out r))))))))

(defn unreadable
  "Why text can't be read as the file at relative path is read, or nil: the shipped config validates (its refusal names each key), and any other file reads (unread-text). See docs/source.md#a-program-file."
  {:malli/schema [:function [:=> [:cat :string :string] [:maybe :string]] [:=> [:cat :string :string [:maybe :map]] [:maybe :string]]]}
  ([path text] (unreadable path text nil))
  ;; schema-changes: the schema entries the same change states (seon.config/text-refusal's changes)
  ([path text schema-changes]
   (if (= path cfg/shipped-file) (cfg/text-refusal text schema-changes) (unread-text path text))))

(declare git-commit)

(defn- checked-out-at
  "The worktree of the repository at dir that has ref (refs/heads/...) checked out, or nil (git worktree list --porcelain:
  each tree's path and its branch). See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [dir ref]
  (some (fn [block] (let [ls (str/split-lines block)
                          path (some #(when (str/starts-with? % "worktree ") (subs % 9)) ls)]
                      (when (some #{(str "branch " ref)} ls) path)))
        (str/split (git! {} dir ["worktree" "list" "--porcelain"] nil) #"\n\n")))

(defn- fast-commit
  "The commit of files ({path text}, modes {path mode}) on parent's tree with message, and ref moved to it only from old (a sha, or
  git's zero id for a branch not there yet): its sha, or nil when ref is not at old, nothing written to ref then. Two git processes:
  git fast-import makes the blobs, the tree from parent's with the files replaced and the commit (stamped now by git) on a scratch
  ref of this process's own, then one git update-ref --stdin transaction moves ref from old to it and deletes the scratch ref, all or
  nothing (git's own compare-and-swap). The committer is env's (GIT_COMMITTER_NAME and _EMAIL, the config's identity). Was seven
  git processes and two a file (read-tree, hash-object and update-index each file, write-tree, commit-tree, update-ref), ~5-10 ms
  each. links ({path commit}) are pins, written as gitlinks. No tree's files or index are touched. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:function [:=> [:cat :string [:map-of :string :string] :string :string :string [:map-of :string :string] [:maybe [:map-of :string :string]] :string] [:maybe :string]]
                  [:=> [:cat :string [:map-of :string :string] :string :string :string [:map-of :string :string] [:maybe [:map-of :string :string]] :string [:map-of :string :string]] [:maybe :string]]]}
  ([dir env ref old parent files modes message] (fast-commit dir env ref old parent files modes message {}))
  ([dir env ref old parent files modes message links]
  (let [scratch (str "refs/seon/write-" (lib/c-getpid))
        marks (doto (java.io.File/createTempFile "seon-marks" ".txt") (.delete))
        data (fn [^String t] (str "data " (alength (.getBytes t "UTF-8")) "\n" t "\n"))
        stream (str "commit " scratch "\nmark :1\ncommitter " (get env "GIT_COMMITTER_NAME") " <" (get env "GIT_COMMITTER_EMAIL") "> now\n"
                    (data message) "from " parent "\n"
                    (apply str (for [[path text] (sort files)] (str "M " (get modes path "100644") " inline " path "\n" (data text))))
                    ;; a pin: the submodule's commit as its gitlink
                    (apply str (for [[path sha] (sort links)] (str "M 160000 " sha " " path "\n")))
                    "\ndone\n")]
    (try
      (git! env dir ["fast-import" "--quiet" "--force" "--date-format=now" "--done" (str "--export-marks=" (.getPath marks))] stream)
      (let [sha (some #(when (str/starts-with? % ":1 ") (subs % 3)) (str/split-lines (slurp marks)))
            r (git-run {} dir ["update-ref" "--stdin"] (str "start\nupdate " ref " " sha " " old "\ndelete " scratch "\ncommit\n"))]
        (if (zero? (:exit r))
          sha
          (do (git-run {} dir ["update-ref" "-d" scratch] nil) nil)))
      (finally (.delete marks))))))

(defn switch-over
  "The :switch-over effect (task seon.system/a-successor-takes-over-the-running-root): after a merge whose judge built and booted a
  candidate (:seon.task/build, seon.fx/build's checkout), this root's successor started from that build's binary, never a second
  build. Only when the checkout's binary is main's: its recorded identity (target/seon.program's :digest) is the binary digest of the source
  tree of main's branch ref (binary-digest; never the repository's HEAD, a generation checkout's fixed commit), else refused, naming both. Then seon.lib/spawn-detached! starts <checkout>/target/seon root --detached
  --take-over <this root's port> with the arguments that resume this store and main (seon.config/resume-args over this process's own
  facts), in <checkout>/target, its environment this root's with the checkout's own (SEON_CHECKOUT, HOME, JOLT_LMDB_LIB, as bin/system
  run sets), its output appended to this root's log. The successor loads, catches up ahead, and asks this root to hand over.
  Answers {:value {:pid :checkout}} or :seon.fx/io-error. See docs/source.md#the-next-generation."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [checkout]} :req}]
  (let [{:keys [store main source-root runs ports]} (cfg/process)
        repo (or source-root (cfg/checkout))
        binary (str checkout "/target/seon")
        ;; the build's own record (bin/build's target/seon.program, what seon.program/binary-program reads in the binary it describes)
        have (let [f (io/file checkout "target/seon.program")] (when (.isFile f) (:digest (sedn/decode (slurp f)))))
        ;; main's tree by its branch ref (:main), never the repository's HEAD: the standing root's source root is its own generation
        ;; checkout (runs/root-gen-<sha>), whose HEAD stays at the commit it was started from, so HEAD refused every later build
        ;; (2026-11-17, session/runtime's merge 98369c234e: build 94cd5ff9a82002e7 is main's, HEAD f86ec371f6's is ad7fb1663f8e92ab)
        tree (git-try repo ["rev-parse" (str main "^{tree}")])
        want (when tree (binary-digest repo tree))
        port (:server ports)]
    (cond
      (not (.isFile (io/file binary))) (error #'seon.fx/switch-over :seon.fx/io-error (str "no binary at " binary) {:path checkout})
      (or (nil? want) (contract/error? want)) (error #'seon.fx/switch-over :seon.fx/io-error (str "main's binary digest is not readable in " repo) {:path repo})
      ;; typed, with main's head: the reconciler builds main there and switches to that (seon.reconcile/switch-answered)
      (not= have want) (error #'seon.fx/switch-over :seon.fx/not-mains-binary
                              (str "the build at " checkout " is binary " have ", main's tree " tree " is " want ": no switch-over to another binary")
                              {:checkout checkout :have (str have) :want want :head (git-try repo ["rev-parse" "HEAD"]) :repo repo})
      :else
      (let [log (str runs "/root.log")
            r (lib/spawn-detached! {:argv (into [binary "root"] (concat (cfg/resume-args {:store store :main main :source-root source-root})
                                                                         ["--detached" "--take-over" (str port)]))
                                    :dir (str checkout "/target") :out log :err log
                                    :env {"SEON_CHECKOUT" checkout "HOME" (str checkout "/target/home")
                                          "JOLT_LMDB_LIB" (str checkout "/target/lib/liblmdb.dylib")}})]
        (if (:pid r)
          {:value {:pid (:pid r) :checkout checkout}}
          (error #'seon.fx/switch-over :seon.fx/io-error (str "the successor did not start: " (:error r) " (errno " (:errno r) ")") {:path checkout}))))))

(defn drop-generations
  "The :drop-generations effect: build generations under <runs>/generations (seon.fx/build's worktrees, ~2-9 GB each) removed with
  their build logs: each one's git worktree removed from the source repository (git worktree remove --force), then whatever is left of
  the directory. :drop names the ones to remove (a judge's own build at its end when it did not merge: the reconciler's), each taken
  by its directory's name under <runs>/generations, so nothing outside it is ever named; :keep instead removes every one it does not
  name (root's boot, keeping the one it runs from: no judge is in flight at a boot). Disk stays bounded by what can still run (AGENTS.md
  section 8). Answers {:value {:dropped [{:path :git?} ...]}}. See docs/source.md#the-next-generation."
  {:malli/schema [:=> [:cat [:map [:req [:or [:map [:drop [:sequential :string]]] [:map [:keep [:sequential :string]]]]]]] :map]}
  [{{:keys [keep drop]} :req}]
  (let [{:keys [runs source-root]} (cfg/process)
        repo (or source-root (cfg/checkout))
        dir (io/file runs "generations")
        ;; both by a generation's name under dir: a path spelled another way (a doubled slash, a link) names the same generation
        kept (into #{} (map #(.getName (io/file %))) keep)
        gone (if drop
               (vec (for [p drop :let [f (io/file dir (.getName (io/file p)))] :when (.isDirectory f)] (.getPath f)))
               (vec (for [f (or (.listFiles dir) []) :when (and (.isDirectory f) (not (kept (.getName f))))] (.getPath f))))]
    ;; a generation is a worktree of the source repository (bin/worktree --at): removed from git first, so no worktree entry dangles;
    ;; git's refusal (a directory git never registered) is named on the answer beside the path, never dropped
    {:value {:dropped (vec (for [path gone]
                             (let [r (git-run {} repo ["worktree" "remove" "--force" path] nil)
                                   refused (when-not (zero? (:exit r)) (str/trim (str (:err r) (:out r))))]
                               (when (.exists (io/file path)) (lib/remove-tree! path))
                               (io/delete-file (str path ".build.log") true)
                               (cond-> {:path path} refused (assoc :git refused)))))}}))

(defn write-out
  "A branch's files written out as a commit to build from (seon.writer/write-out-req's request): files ({path text}, with modes) and links
  (pins, {path commit}) on base (the commit main's files were imported from), in one fast-commit, ref (refs/seon/candidate/<branch>,
  never a branch: git branch lists the sessions' alone) moved to it from whatever it held (a branch written out again starts from base again: its files are the branch's now, never a history of
  write-outs). No tree's files are touched; seon.fx/build of the commit makes the tree a candidate builds and boots from. Answers
  {:value {:sha :files n :links n}} or :seon.fx/io-error. See task seon.writer/a-branchs-files-are-written-out-to-a-tree."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir ref base files modes links message]} :req}]
  (try
    (let [who (cfg/value :seon.config.source/committer-name) mail (cfg/value :seon.config.source/committer-email)
          old (or (git-try dir ["rev-parse" "--verify" "--quiet" ref]) "0000000000000000000000000000000000000000")
          sha (fast-commit dir {"GIT_COMMITTER_NAME" who "GIT_COMMITTER_EMAIL" mail} ref old base files modes message links)]
      (if sha
        {:value {:sha sha :files (count files) :links (count links)}}
        (error #'seon.fx/write-out :seon.fx/io-error (str ref " moved while it was written out: nothing written") {:path dir})))
    (catch Throwable e (if (intr/interrupt? e) (throw e) (error #'seon.fx/write-out :seon.fx/io-error (step/error-msg e) {:path dir})))))

(defn drop-write-out
  "A branch's write-out deleted when its candidate is stopped: ref (seon.writer/write-out-req's :ref) removed from the repository at
  dir, so no candidate commit outlives its candidate. Answers {:value {:dropped ref}}, the same when it is gone already, or
  :seon.fx/io-error. See task seon.writer/a-branchs-files-are-written-out-to-a-tree."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir ref]} :req}]
  (let [r (git-run {} dir ["update-ref" "-d" ref] nil)]
    (if (or (zero? (:exit r)) (nil? (git-try dir ["rev-parse" "--verify" "--quiet" ref])))
      {:value {:dropped ref}}
      (error #'seon.fx/drop-write-out :seon.fx/io-error (str "deleting " ref ": " (str/trim (str (:err r)))) {:path dir}))))

(defn- tree-refusal
  "Why the worktree wt can't take a commit of paths, or nil: a change staged there that its HEAD does not hold, or a file to
  write that differs there from HEAD (an edit of the tree's own, never overwritten). See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string [:vector :string]] [:maybe :seon.error/base]]}
  [wt paths]
  (let [staged? (not (zero? (:exit (git-run {} wt ["diff-index" "--cached" "--quiet" "HEAD" "--"] nil))))
        edited (when (seq paths)
                 (not-empty (str/trim (git! {} wt (into ["status" "--porcelain" "--untracked-files=all" "--"] paths) nil))))]
    (cond
      staged? (error #'seon.fx/git-commit :seon.fx/io-error (str "refused, nothing committed: " wt " has changes staged that its HEAD does not hold") {:path wt})
      edited (error #'seon.fx/git-commit :seon.fx/io-error (str "refused, nothing committed: files the write-back writes differ in " wt " from its HEAD: " (str/replace edited "\n" "; ")) {:path wt}))))

(defn commit-tree-id
  "The id tree-id gives a clean checkout of commit sha in the repository at dir, read from the commit and never the disk: its tree
  less the top-level paths excludes names (:seon.config.source/tree-excludes), so what bin/tree records for a build. Two git processes
  over the top-level listing (ls-tree, mktree), O(top-level entries), where tree-id walks every file. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:function [:=> [:cat :string :string] :string] [:=> [:cat :string :string [:vector :string]] :string]]}
  ([dir sha] (commit-tree-id dir sha (cfg/value :seon.config.source/tree-excludes)))
  ([dir sha excludes]
   (let [lines (str/split-lines (git! {} dir ["ls-tree" sha] nil))
         kept (remove #(contains? (set excludes) (second (str/split % #"\t" 2))) lines)]
     (if (= (count kept) (count lines))
       (git! {} dir ["rev-parse" (str sha "^{tree}")] nil)
       (git! {} dir ["mktree"] (str (str/join "\n" kept) "\n"))))))

(defn- expected-commit!
  "Commit files on branch ref of the repository at dir as one compare-and-swap from expect, the commit its caller read (the store's
  main was imported from): the commit is made on expect's tree and the ref moved by git update-ref from EXPECT (fast-commit), which
  refuses atomically when the ref is anywhere else: :seon.fx/ref-moved {:expected :found}, nothing written. A branch worktree wt
  has checked out (git's main in the main tree) takes the tree's refusals first (tree-refusal), and its index and files follow
  the ref (read-tree -m -u), and the answer names its tree id. Never a retry: the caller reads what moved it and asks again. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string :string [:maybe :string] [:map-of :string :string] :string :map] :map]}
  [dir ref wt env expect {:keys [files modes message]}]
  (or (when wt (tree-refusal wt (vec (sort (keys files)))))
      (let [sha (fast-commit dir env ref expect expect files modes message)]
        (if-not sha
          (let [found (git-try dir ["rev-parse" "--verify" "--quiet" ref])]
            (error #'seon.fx/git-commit :seon.fx/ref-moved (str ref " is at " found ", not " expect ", the commit this write was read from: nothing written")
                   {:path dir :expected expect :found found}))
          ;; the ref moved: the commit is made whatever follows, so it is answered as one; a tree that could not follow is named
          ;; beside it (:tree-unmoved, a tree to bring in by hand), never an error that leaves the commit unrecorded
          (let [moved (when wt (try (git! {} wt ["read-tree" "-m" "-u" expect sha] nil) nil (catch Throwable e (step/error-msg e))))]
            ;; the commit it was made from, and main's files' tree now, the commit's own (git rev-parse, one process): what main's
            ;; source fact moves to and seon.system/restart-for compares with a landing's (commit-tree-id). The tree id of the checkout on
            ;; disk (tree-id, git add -A over the whole checkout: 360-465 ms on the main tree, result/c1b3d45c) is the same tree for the
            ;; clean main tree the write-back commits through (tree-refusal refused staged changes and edits of its files)
            {:value (cond-> {:sha sha :files (count files) :expected expect}
                      (and wt (nil? moved)) (assoc :tree (commit-tree-id dir sha))
                      moved (assoc :tree-unmoved (str wt ": " moved)))})))))

(defn- commit-in-tree!
  "Commit files on the branch worktree wt has checked out, through that tree: each file written there, added to its own
  index and committed as git commit does, so its HEAD, index and files move together. Refused, committing nothing, when
  its index differs from its HEAD (a change staged there) or a file to write differs there from HEAD (an edit of the
  tree's own): {:value {:sha :files n}} or :seon.fx/io-error. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string :map :map] :map]}
  [wt env {:keys [files message allow-empty? modes]}]
  (let [paths (vec (sort (keys files)))]
    (or (tree-refusal wt paths)
      (do (doseq [[p text] files :let [f (java.io.File. ^String wt ^String p)]]
            (.mkdirs (.getParentFile f))
            (spit f text))
          (when (seq paths) (git! {} wt (into ["add" "--"] paths) nil))
          (doseq [[p m] modes :when (and (= "100755" m) (contains? files p))]
            (git! {} wt ["update-index" "--chmod=+x" "--" p] nil))
          (git! env wt (cond-> ["commit" "-q" "--no-verify" "-m" message] allow-empty? (conj "--allow-empty")) nil)
          {:value {:sha (git! {} wt ["rev-parse" "HEAD"] nil) :files (count files)}}))))

(defn- merge-in-tree!
  "Merge ref into the branch worktree wt has checked out, through that tree, as the one writer of it (the :git owner's one worker,
  task seon.system/a-landing-restarts-root-only-for-a-binary-change, slice 2a): refused, merging nothing, when the tree holds an
  edit or a staged change of its own; a merge that conflicts is aborted, the tree left as it was found, and answered typed naming
  the conflicting paths, so no later commit is made in a MERGING tree. {:value {:sha :merged ref :expected :tree}} (:expected ref's
  commit, :tree the tree's id after) or :seon.fx/io-error. See
  docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string :map :string] :map]}
  [wt env ref]
  (let [run (fn [args] (git-run env wt args nil))
        _ (run ["update-index" "-q" "--refresh" "--ignore-submodules"])
        dirty (concat (str/split-lines (:out (run ["diff" "--name-only" "--ignore-submodules"])))
                      (str/split-lines (:out (run ["diff" "--cached" "--name-only" "--ignore-submodules"]))))
        dirty (vec (distinct (remove str/blank? dirty)))]
    (if (seq dirty)
      (error #'seon.fx/git-commit :seon.fx/io-error (str "refused, nothing merged: " wt " holds changes of its own: " (str/join ", " dirty)) {:path wt})
      (let [r (run ["merge" "-q" "--no-edit" ref])]
        (cond
          ;; the merge said done, and HEAD contains ref: what bin/update's ancestry check did (a merge that did nothing once gave
          ;; eight false UPDATE OKs), now the effect's own answer
          ;; :expected, the commit merged in, and :tree, the tree's id after: what main's source fact moves to when it named that commit
          ;; (seon.reconcile/source-moved-tx, as a write-back's answer), so the next write-back's compare-and-swap reads from this merge
          (and (zero? (:exit r)) (zero? (:exit (run ["merge-base" "--is-ancestor" ref "HEAD"]))))
          {:value {:sha (str/trim (:out (run ["rev-parse" "HEAD"]))) :merged ref
                   :expected (str/trim (:out (run ["rev-parse" "--verify" (str ref "^{commit}")]))) :tree (tree-id wt)}}
          (zero? (:exit r))
          (error #'seon.fx/git-commit :seon.fx/io-error (str "merging " ref " into " wt " answered done, but HEAD does not contain " ref) {:path wt})
          :else
          (let [conflicts (vec (remove str/blank? (str/split-lines (:out (run ["diff" "--name-only" "--diff-filter=U"])))))]
            (run ["merge" "--abort"])
            (error #'seon.fx/git-commit :seon.fx/io-error
                   (str "merging " ref " into " wt " conflicts, aborted (the tree as it was): " (if (seq conflicts) (str/join ", " conflicts) (str/trim (:err r))))
                   {:path wt})))))))

(defn git-commit
  "Commit files ({relative path text}) on branch of the repository at dir, with message,
  through git's plumbing: blobs, a tree from the branch's own with the files replaced, a
  commit whose parent is the branch head (a branch not there yet starts at :base, else at dir's HEAD, the
  commit the running generation's tree checks out), and the ref moved only from that head, so no
  tree's files are touched. A branch a worktree has checked out (the standing root's jolt/merges in .worktrees/merges) is
  committed through that tree instead (commit-in-tree!): its files, index and HEAD move together, and a change of that
  tree's own refuses the commit; moved by plumbing, its HEAD left its index behind. With :expect (a sha), the commit is made on
  that commit and the ref moves only from it, one compare-and-swap (expected-commit!: :seon.fx/ref-moved when it moved). See docs/source.md#commit-the-merges-branch.
  With :allow-empty? and no files it commits the branch's own tree again (an empty commit: a review,
  seon.doc/review-message). The files are committed as given, never read again: main's drawn text was read form by form when written. :modes ({path git mode}) gives a file's mode, 100644 otherwise. Answers {:value {:sha :files n}} ({:sha nil :files 0} when there is nothing
  to write), or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir branch files message allow-empty? modes base expect merge]} :req}]
  (cond
    ;; {:merge ref}: ref merged into the branch's checked-out tree, by this effect's one worker (merge-in-tree!)
    merge (let [ref (str "refs/heads/" branch) wt (checked-out-at dir ref)]
            (if wt
              (try (merge-in-tree! wt (let [who (cfg/value :seon.config.source/committer-name) mail (cfg/value :seon.config.source/committer-email)]
                                        {"GIT_AUTHOR_NAME" who "GIT_AUTHOR_EMAIL" mail "GIT_COMMITTER_NAME" who "GIT_COMMITTER_EMAIL" mail})
                                   merge) (catch Throwable e (if (intr/interrupt? e) (throw e) (error #'seon.fx/git-commit :seon.fx/io-error (step/error-msg e) {:path dir}))))
              (error #'seon.fx/git-commit :seon.fx/io-error (str "no worktree has " branch " checked out: nothing to merge into") {:path dir})))
    :else
  (if (and (empty? files) (not allow-empty?))
    {:value {:sha nil :files 0}}
    (try
      ;; the files are main's own drawn text, each form read when it was written: not read again here (a draw bug is the write-back's
      ;; byte-for-byte proofs' to catch, and a catch-up leaves out a file it cannot read, naming it)
      (do
       (let [ref (str "refs/heads/" branch)
            who (cfg/value :seon.config.source/committer-name) mail (cfg/value :seon.config.source/committer-email)
            id-env {"GIT_AUTHOR_NAME" who "GIT_AUTHOR_EMAIL" mail "GIT_COMMITTER_NAME" who "GIT_COMMITTER_EMAIL" mail}]
        ;; a branch a worktree has checked out moves with that tree's index and files, through the tree (commit-in-tree!):
        ;; moved by plumbing, the tree's HEAD left its index behind, each merged file a staged deletion
        (cond
          ;; the ref moves only from the commit the caller read: one compare-and-swap (expected-commit!)
          expect (expected-commit! dir ref (checked-out-at dir ref) id-env expect {:files files :modes modes :message message})
          (checked-out-at dir ref)
          (commit-in-tree! (checked-out-at dir ref) id-env {:files files :message message :allow-empty? allow-empty? :modes modes})
          :else
          (let [have? (some? (git-try dir ["rev-parse" "--verify" "--quiet" ref]))
                parent (git! {} dir ["rev-parse" "--verify" (if have? ref (or base "HEAD"))] nil)
                ;; Seon is the committer and author of its merges (the config's identity): a
                ;; root's HOME is its checkout's target/home, which holds no git identity
                ;; only from the head read above; a new branch only if none appeared meanwhile
                sha (fast-commit dir id-env ref (if have? parent "0000000000000000000000000000000000000000") parent files modes message)]
            (if sha
              {:value {:sha sha :files (count files)}}
              (error #'seon.fx/git-commit :seon.fx/io-error (str ref " moved from " parent " while this commit was made: nothing written") {:path dir}))))))
      ;; an interrupt or a budget's end is no git error: it goes on to the step (seon.intr/interrupt?)
      (catch Throwable e (if (intr/interrupt? e) (throw e) (error #'seon.fx/git-commit :seon.fx/io-error (step/error-msg e) {:path dir})))))))

;; ---- the wiki's history: what each section was last written against -----------------------

(defn- blame
  "{final line number {:sha :time}} of the file at path in the repository at root, from git blame
  --porcelain (an uncommitted line is sha 000...). A commit with no committer time is a failure
  to read git's answer: it throws, never a time of 0."
  {:malli/schema [:=> [:cat :string :string] [:map-of :int :map]]}
  [root path]
  (let [out (git! {} root ["blame" "--porcelain" "--" path] nil)
        lines (loop [ls (str/split-lines out) cur nil times {} acc {}]
                (if-let [l (first ls)]
                  (if-let [[_ sha line] (re-matches #"([0-9a-f]{40}) \d+ (\d+)(?: \d+)?" l)]
                    (recur (rest ls) {:sha sha} times (assoc acc (parse-long line) sha))
                    (if-let [[_ t] (re-matches #"committer-time (\d+)" l)]
                      (recur (rest ls) cur (assoc times (:sha cur) (parse-long t)) acc)
                      (recur (rest ls) cur times acc)))
                  [acc times]))
        [acc times] lines]
    (update-vals acc (fn [sha] (if-let [t (times sha)]
                                 {:sha sha :time t}
                                 (throw (ex-info (str "git blame gave no committer time for " sha) {})))))))

(defn- reviews
  "{section address {:sha :time}}: the newest commit of the repository at root (reachable from HEAD) whose message
  carries the trailer `Reviewed: <address>` for it, in the checkout's own history (HEAD: a review that
  never reached it is not a review here), from ONE git log (an empty review commit touches
  no file, so no per-file log finds it). Each address is one trailer line."
  {:malli/schema [:=> [:cat :string] [:map-of :string :map]]}
  [root]
  (let [out (git! {} root ["log" "HEAD" "--grep=^Reviewed: " "--format=%x01%H%x00%ct%x00%(trailers:key=Reviewed,valueonly)"] nil)]
    (reduce (fn [m rec]
              (let [[sha ct trailers] (str/split rec #"\x00" 3)
                    t (parse-long (str/trim ct))]
                (reduce (fn [m a] (if (> t (:time (m a) -1)) (assoc m a {:sha (str/trim sha) :time t}) m))
                        m (remove str/blank? (map str/trim (str/split-lines (or trailers ""))))))) 
            {} (remove str/blank? (str/split out #"\x01")))))

(defn wiki-history
  "For each requested wiki section ({:path :address :lines [from to] :files {name {:file :ns}}}) the
  commit that last changed its lines (git blame of its page, the newest committer time among its
  lines; nil when a line is not committed yet) or, when it is newer or as new, the last commit whose
  `Reviewed:` trailer names the section (one git log for all of them, `reviews`): a review is a
  commit, so it survives an erased store and a fresh clone. And, for each (commit, file) the sections' cited
  vars live in, the file's text at that commit: nil when the commit has no such path (git ls-tree
  says so), else `git show`'s text. Every git call goes through `git!`. Answers {:value {:sections
  {address {:commit sha}} :files {[sha file] text-or-nil} :errors {[sha file] message}}}: a page git
  cannot blame (not a git checkout, an untracked file) answers {:error message} for its sections, and
  a (commit, file) git cannot show is under :errors, never nil; or :seon.fx/io-error. The wiki's
  loader records a section whose history is unread as of today and says so (seon.doc/baseline-reads)."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root sections]} :req}]
  (try
    (let [by-path (group-by :path sections)
          reviewed (reviews root)
          blamed (into {} (for [p (keys by-path)]
                            [p (try (blame root p) (catch Throwable e {:error (step/error-msg e)}))]))
          infos (into {} (for [{:keys [path address lines]} sections
                               :let [b (blamed path)]]
                           [address (if (:error b)
                                      {:error (:error b)}
                                      (let [ls (keep b (range (first lines) (inc (second lines))))
                                            newest (when (seq ls) (apply max-key :time ls))
                                            rv (get reviewed address)]
                                        {:commit (when (and newest (not (re-matches #"0+" (:sha newest))))
                                                   (if (and rv (>= (:time rv) (:time newest))) (:sha rv) (:sha newest)))}))]))
          needed (distinct (for [{:keys [address files]} sections :let [c (:commit (infos address))] :when c [_ {:keys [file]}] files] [c file]))
          shown (into {} (for [[c f] needed]
                           [[c f] (try (if (str/blank? (git! {} root ["ls-tree" c "--" f] nil))
                                         {:text nil}
                                         {:text (git! {} root ["show" (str c ":" f)] nil)})
                                       (catch Throwable e {:error (step/error-msg e)}))]))]
      {:value {:sections infos
               :files (into {} (keep (fn [[k r]] (when (contains? r :text) [k (:text r)]))) shown)
               :errors (into {} (keep (fn [[k r]] (when (:error r) [k (:error r)]))) shown)}})
    (catch Throwable e (error #'seon.fx/wiki-history :seon.fx/io-error (step/error-msg e) {:path root}))))

;; Dependencies: fetching is an outside-world effect with one owner (the PKG plan), and
;; the resolver is Jolt's own: no resolver of ours.

(defn resolve-deps
  "Resolve a deps map ({lib coordinate}: :mvn/version, :git/sha or :local/root) with
  `jolt.deps/resolve-deps`, which fetches into the checkout's shared caches. Answers
  {:value {:roots [absolute source roots]}} or :seon.fx/unresolved {:deps [the libs that
  did not resolve] :unresolved Jolt's {:coord :version :detail}}."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [deps]} :req}]
  (try
    {:value {:roots (:roots (deps/resolve-deps deps "."))}}
    (catch Throwable e
      (let [u (:unresolved (ex-data e))]
        (error #'seon.fx/resolve-deps :seon.fx/unresolved (step/error-msg e)
               {:deps (vec (or (seq (map :coord u)) (keys deps))) :unresolved (vec u)})))))

(defn build
  "The next generation (docs/source.md#the-next-generation): commit of the repository at
  repo built as <runs>/generations/<commit>, a worktree of it with this
  checkout's submodule repos at the commit's pins and its target/ cloned (bin/worktree --at),
  then bin/build seon there, niced. The build runs in that tree's own environment (bin/env),
  never this root's checkout. A tree already there is built again (Jolt's caches make an
  unchanged build cheap). Only what cannot load in place needs it (a Jolt, Chez or dependency
  pin, deps.edn, native code): a merge loads in place (seon.program/load-diff!). Answers {:value {:commit :checkout :binary
  :ms}}, or :seon.fx/io-error naming the step and its log (<checkout>.build.log)."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [repo commit]} :req}]
  (let [t0 (profile/now-ns)
        from (cfg/checkout)
        dir (str (:runs (cfg/process)) "/generations/" commit)
        log (str dir ".build.log")
        clean child-env-scrub
        step (fn [what args]
               (let [r (apply lib/sh "zsh" "-c" "\"$@\" >> \"$0\" 2>&1" log (concat clean args))]
                 (when-not (zero? (:exit r))
                   ;; the log's text travels with the error: a test that removes its scratch keeps the cause
                   (error #'seon.fx/build :seon.fx/io-error (str what " exited " (:exit r) "; its log: " log) {:path dir :log (slurp log)}))))]
    (.mkdirs (java.io.File. (str (:runs (cfg/process)) "/generations")))
    (or (when-not (.exists (java.io.File. dir))
          (step "bin/worktree --at" [(str from "/bin/worktree") "--at" dir commit repo]))
        (step "bin/build seon" ["nice" "-n" "15" (str dir "/bin/build") "seon"])
        {:value {:commit commit :checkout dir :binary (str dir "/target/seon") :ms (profile/ms-since t0)}})))

;; ---- verdicts: a cache by digest, outside the store (docs/merging.md#verdicts-are-a-cache) ----

(defn- key-dir
  "Key k's directory in the verdict cache at dir (:seon.config.proofs/results-dir): a test's
  <ns>/<name>, a bin/check step's steps/<step>."
  {:malli/schema [:=> [:cat :string [:or :qualified-symbol :string]] :string]}
  [dir k] (str dir "/" (if (string? k) (str "steps/" k) (str (namespace k) "/" (name k)))))

(defn- verdict-path
  "The file of key k's verdict at digest dg: <digest>.pass.edn for a green, <digest>.red.edn for a
  red (failed or errored). One file a (digest, status), each written once: green and red both is
  flaky, so two writers of one digest never lose either."
  {:malli/schema [:=> [:cat :string [:or :qualified-symbol :string] :string [:enum :pass :red]] :string]}
  [dir k dg st] (str (key-dir dir k) "/" dg "." (name st) ".edn"))

(defn read-verdict
  "The verdict file at path as data ({:key :digest :status ...}, write-verdicts!'s), nil when there
  is none (never written, or evicted, even between a listing and this read), or :seon.fx/io-error
  naming it: a file there that cannot be read or is not EDN (corrupt) is that error, never a miss."
  {:malli/schema [:=> [:cat :string] [:or :nil :seon.error/base :map]]}
  [path]
  (try (edn/read-string {:default tagged-literal} (slurp path))
       (catch java.io.FileNotFoundException _ nil)
       (catch Throwable e (error #'seon.fx/read-verdict :seon.fx/io-error (str "reading " path ": " (step/error-msg e)) {:path path}))))

(defn held-reads
  "{test [{:digest :reads}]} of tests: each one's newest green whose run recorded what it read (write-verdicts!'s reads.edn
  beside its verdicts; seon.proofs/read-digests reuses it while every read still has its digest); a test with none is
  absent. One small file a test. dir: the cache's (:seon.config.proofs/results-dir unless given)."
  {:malli/schema [:function [:=> [:cat [:sequential :any]] [:or :map :seon.error/base]] [:=> [:cat :string [:sequential :any]] [:or :map :seon.error/base]]]}
  ([tests] (held-reads (cfg/value :seon.config.proofs/results-dir) tests))
  ([dir tests]
   (reduce (fn [acc t] (let [r (read-verdict (str (key-dir dir t) "/reads.edn"))]
                         (cond (contract/error? r) (reduced r) (map? r) (assoc acc t [r]) :else acc)))
           {} tests)))

(defn stored-results
  "{digest result} of the verdicts cached for cand ({key digest}): two file checks a key, and a red's file read. A green is
  {:digest :pass 1 :fail 0 :error 0}; a red its status counted once, with its failures and, for a cost
  proof, the verdict it was judged by (:cost {:rows :verdict}); a digest cached both green and red is
  flaky (seon.proofs/verdict), judged red, with both runs' ticks (a green's file is read only there: measured on one scripted turn, 2,258 reads of 41 KB each were the turn's largest per-test cost). A test's verdict is a function of its digest, so a red is
  reused as a green is. Answers the first :seon.fx/io-error a file gives, never a partial answer. dir:
  the cache's (:seon.config.proofs/results-dir unless given)."
  {:malli/schema [:function [:=> [:cat [:map-of [:or :qualified-symbol :string] :string]] [:or [:map-of :string :map] :seon.error/base]]
                  [:=> [:cat :string [:map-of [:or :qualified-symbol :string] :string]] [:or [:map-of :string :map] :seon.error/base]]]}
  ([cand] (stored-results (cfg/value :seon.config.proofs/results-dir) cand))
  ([dir cand]
  (reduce (fn [acc [k dg]]
            (let [gp (verdict-path dir k dg :pass) rp (verdict-path dir k dg :red)
                  ;; a green's file is its verdict (its content is never read: written once, whole, by write-verdicts!'s
                  ;; rename), so a test is one file check; only a red's content (its failures) is read
                  g (.exists (java.io.File. ^String gp))
                  r (when (.exists (java.io.File. ^String rp)) (read-verdict rp))
                  ;; green and red at one digest (flaky, rare): the green's file is read too, for the ticks it spent (seon.proofs/flaky)
                  gt (when (and g (map? r)) (let [v (read-verdict gp)] (if (contract/error? v) v (:ticks v))))]
              (when g (.setLastModified (java.io.File. ^String gp) (System/currentTimeMillis)))
              (when (map? r) (.setLastModified (java.io.File. ^String rp) (System/currentTimeMillis)))
              (cond (contract/error? r) (reduced r)
                    ;; gt is nil but at a flaky digest: no check, so a green costs what it did
                    (and gt (contract/error? gt)) (reduced gt)
                    :else (if-let [v (proofs/verdict dg (when g (cond-> {:digest dg :pass 1 :fail 0 :error 0} gt (assoc :ticks gt)))
                                                     (when r (cond-> {:digest dg :pass 0 :fail (if (= :fail (:status r)) 1 0) :error (if (= :error (:status r)) 1 0)
                                                                      :failures (vec (:failures r)) :writer (:writer r)}
                                                               (:cost r) (assoc :cost (:cost r))
                                                               (and g (:ticks r)) (assoc :ticks (:ticks r)))))]
                            (assoc acc dg v)
                            acc))))
          {} cand)))

(defn- verdict-files
  "Key k's cached verdict files, each [digest status file]."
  {:malli/schema [:=> [:cat :string [:or :qualified-symbol :string]] [:vector [:tuple :string :keyword :any]]]}
  [dir k]
  (vec (for [^java.io.File f (or (seq (.listFiles (java.io.File. ^String (key-dir dir k)))) [])
             :let [[_ dg st] (re-matches #"([^.]+)\.(pass|red)\.edn" (.getName f))] :when dg]
         [dg (keyword st) f])))

(defn- recent-verdict-files
  "The newest of verdict files vs (verdict-files'), at most :seon.config.proofs/recent-verdicts: what an order of work reads of a test's
  history, so its cost is the test's and not the digests it has had. See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat [:vector [:tuple :string :keyword :any]]] [:sequential [:tuple :string :keyword :any]]]}
  [vs]
  (take (cfg/value :seon.config.proofs/recent-verdicts) (sort-by (fn [[_ _ ^java.io.File f]] (- (.lastModified f))) vs)))

(defn- verdict-head-long
  "The number re finds in verdict file f's first bytes (a verdict writes :ms and :ticks before its failures, cost and per-function rows, which
  are most of its size: seon.fx/write-verdicts!), nil when it has none or cannot be read. An order of work, never a verdict (docs/merging.md#verdicts-are-a-cache)."
  {:malli/schema [:=> [:cat :any :any] [:maybe :int]]}
  [f re]
  (try (with-open [r (java.io.FileReader. ^java.io.File f)]
         (let [buf (char-array 768) n (.read r buf)]
           (when (pos? n) (some-> (re-find re (String. buf 0 (int n))) second parse-long))))
       (catch java.io.IOException _ nil)))

(defn- history-path "Key k's history file in the cache at dir (history)." {:malli/schema [:=> [:cat :string [:or :qualified-symbol :string]] :string]}
  [dir k] (str (key-dir dir k) "/.recent"))

(defn- history
  "Key k's newest greens, [[ms ticks] ...] newest first, at most :seon.config.proofs/recent-verdicts: one small file, written with each green
  (write-verdicts!), so what an order of work reads costs one read however many digests the key has had. Derived data: when the file is gone or
  unreadable it is made again from the verdict files. See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:=> [:cat :string [:or :qualified-symbol :string]] [:sequential :any]]}
  [dir k]
  (let [p (history-path dir k) v (read-verdict p)]
    (if (and (map? v) (:recent v))
      (:recent v)
      (let [h (vec (map (fn [[_ _ f]] [(verdict-head-long f #":ms (\d+)") (verdict-head-long f #":ticks (\d+)")])
                         (recent-verdict-files (filterv (fn [[_ st]] (= :pass st)) (verdict-files dir k)))))]
        (when (seq h) (write-file {:req {:path p :text (sedn/encode {:recent h})}}))
        h))))

(defn- history-add!
  "Green at ms and ticks added to key k's history (history), its oldest dropped past the bound; the write-file's answer."
  {:malli/schema [:=> [:cat :string [:or :qualified-symbol :string] [:maybe :int] [:maybe :int]] :any]}
  [dir k ms ticks]
  (let [h (history dir k)]
    (write-file {:req {:path (history-path dir k)
                       :text (sedn/encode {:recent (vec (take (cfg/value :seon.config.proofs/recent-verdicts) (cons [ms ticks] h)))})}})))

(defn- ledger-bytes
  "The size the cache at dir last wrote down for itself (its .size file), nil when it has none or
  cannot read it (then the next write counts the cache)."
  {:malli/schema [:=> [:cat :string] [:or :nil :int]]}
  [dir]
  (let [f (java.io.File. ^String dir ".size")]
    (when (.isFile f) (parse-long (str/trim (slurp f))))))

(defn- verdict-listing
  "Every cached file under dir as [file bytes mtime-ms]: the one count of the cache, by metadata alone: a test's green or
  red (<ns>/<name>/<digest>.pass.edn or .red.edn) and a green's recorded reads (reads.edn), evicted like a verdict (a missing
  one only makes its test run); nothing else is (a staged file, the .size ledger, the last full run's record full-run.edn)."
  {:malli/schema [:=> [:cat :string] [:vector :any]]}
  [dir]
  (vec (for [^java.io.File f (files-under dir)
             :let [n (.getName f)]
             :when (or (str/ends-with? n ".red.edn") (str/ends-with? n ".pass.edn") (= n "reads.edn"))]
         [f (.length f) (.lastModified f)])))

(defn- evict!
  "Keep the cache at dir within max-bytes after a write of added bytes (the files at kept, never
  removed). The size is kept in the cache's .size ledger, so a write that leaves the cache under the
  bound reads and rewrites one small file and counts nothing; past it (or with no ledger) the
  verdict files are counted once, and the least recently used (the oldest mtime: seon.fx/stored-results touches a verdict
  it reads) go first, greens and reds alike (an LRU cache, the owner's ruling), until the cache is
  :seon.config.proofs/results-evict-percent under the bound, so the writes after it count nothing, and the ledger is set to
  the true size. A file that is no verdict (full-run.edn) is never counted or deleted. Other writers' additions and removals make the ledger a lower or
  higher guess, corrected by the next count. A file that would not delete stays (a cache a little
  over, never an error). Answers nil."
  {:malli/schema [:=> [:cat :string :int [:set :string] :int] :nil]}
  [dir added kept max-bytes]
  (let [known (ledger-bytes dir)
        total (some-> known (+ added))
        ledger! (fn [n] (write-file {:req {:path (str dir "/.size") :text (str n)}}) nil)]
    (if (and total (<= total max-bytes))
      (ledger! total)
      (let [files (verdict-listing dir)
            size (reduce + (map second files))
            low (- max-bytes (quot (* max-bytes (cfg/value :seon.config.proofs/results-evict-percent)) 100))]
        (ledger! (loop [[[^java.io.File f n] & more] (if (> size max-bytes) (sort-by #(nth % 2) (remove #(kept (str (first %))) files)) []) size size]
                   (if (and f (> size low))
                     (recur more (if (.delete f) (- size n) size))
                     size)))))))

(defn forget-greens!
  "Take back the greens of written ({key digest}) from the cache at dir (:seon.config.proofs/results-dir unless given):
  what a run stored as each test ended, when the tree changed under it (test-runner's shard), so no green names code it
  may not have run. A green is a cache (docs/merging.md#verdicts-are-a-cache); a red is never removed. Answers nil."
  {:malli/schema [:function [:=> [:cat [:map-of [:or :qualified-symbol :string] :string]] :nil]
                  [:=> [:cat :string [:map-of [:or :qualified-symbol :string] :string]] :nil]]}
  ([written] (forget-greens! (cfg/value :seon.config.proofs/results-dir) written))
  ([dir written] (doseq [[k dg] written] (.delete (java.io.File. ^String (verdict-path dir k dg :pass)))) nil))

(defn write-verdicts!
  "Cache what ran ({key result}, a test's {:test :digest :pass :fail :error :ms :ticks :bytes :fns :cost} or a
  step's {:step :digest ...}) of run ({:id, and :tree or :branch :t}, the writer every file names), with
  failures ({key [assertion]}, seon.proofs/failures): one file a key at its digest and status, written by
  write-file (staged, then renamed over), then the cache held within :seon.config.proofs/results-max-bytes
  (the least recently used verdicts evicted, never one just written). A flaky result (judged, not run),
  an :unreported one (no verdict of its own) and one with no :digest write nothing. Answers {:recorded n},
  or the first write's :seon.fx/io-error: those verdicts are not cached, so they run again. Nothing is
  written to the store: a verdict is derived data (docs/merging.md#verdicts-are-a-cache). dir: the cache's
  (:seon.config.proofs/results-dir unless given), max-bytes its bound (:seon.config.proofs/results-max-bytes)."
  {:malli/schema [:function [:=> [:cat :map [:map-of :any :map] [:map-of :any [:sequential :map]]] [:or [:map [:recorded nat-int?]] :seon.error/base]]
                  [:=> [:cat :string :map [:map-of :any :map] [:map-of :any [:sequential :map]]] [:or [:map [:recorded nat-int?]] :seon.error/base]]
                  [:=> [:cat :string :map [:map-of :any :map] [:map-of :any [:sequential :map]] :int] [:or [:map [:recorded nat-int?]] :seon.error/base]]]}
  ([run ran failures] (write-verdicts! (cfg/value :seon.config.proofs/results-dir) run ran failures))
  ([dir run ran failures] (write-verdicts! dir run ran failures (cfg/value :seon.config.proofs/results-max-bytes)))
  ([dir run ran failures max-bytes]
  (let [writer (select-keys run [:id :tree :branch :t :agent :key])
        done (reduce (fn [acc [k r]]
              (let [st (proofs/status-of r)
                    v (cond-> {:key k :digest (:digest r) :status st :writer writer}
                        (:ms r) (assoc :ms (long (:ms r)))
                        (:ticks r) (assoc :ticks (:ticks r))
                        (:bytes r) (assoc :bytes (:bytes r))
                        (:budget r) (assoc :budget (:budget r))
                        (seq (failures k)) (assoc :failures (vec (failures k)))
                        (:cost r) (assoc :cost (:cost r))
                        (seq (:fns r)) (assoc :fns (into {} (for [{:keys [var calls ticks self-ticks bytes self-bytes]} (:fns r)] [var [calls ticks self-ticks bytes self-bytes]]))))
                    w (write-file {:req {:path (verdict-path dir k (:digest r) (if (= :pass st) :pass :red))
                                         :text (sedn/encode v)}})]
                (if (contract/error? w)
                  (reduced w)
                  (let [;; a green whose run recorded what it read (seon.proofs/reads-key): the test's newest, beside its verdicts
                        rw (when (and (= :pass st) (seq (:reads r)))
                             (write-file {:req {:path (str (key-dir dir k) "/reads.edn") :text (sedn/encode {:digest (:digest r) :reads (:reads r)})}}))]
                    (if (contract/error? rw)
                      (reduced rw)
                      (-> (do (when (= :pass st) (history-add! dir k (some-> (:ms r) long) (:ticks r))) acc) (update :recorded inc) (update :bytes + (:bytes (:value w))) (update :kept conj (:path (:value w)))))))))
            {:recorded 0 :bytes 0 :kept #{}}
            (filter (fn [[_ r]] (and (:digest r) (not (:flaky r)) (not (:unreported r)))) ran))]
    (when-not (contract/error? done) (when (pos? (:recorded done)) (evict! dir (:bytes done) (:kept done) max-bytes)))
    (if (contract/error? done) done (select-keys done [:recorded])))))

(defn typical-ms
  "{test ms} of tests (or of check steps' and generative-check keys, strings): the median of the ms their newest greens measured (history), whatever the digest: what
  orders a run's deal of tests into shards, never what a test proves. The median, not the largest: one
  run under load or a hang (a 17 s test once measured 625 s) must not weigh a test as that run did, which
  made one shard of a merge's run end at 62 s beside three at 640-746 (docs/merging.md#a-merge-runs-what-the-change-reaches).
  A test with none is left out; an unreadable file is passed over (an order, never a verdict)."
  {:malli/schema [:function [:=> [:cat [:sequential [:or :qualified-symbol :string]]] [:map-of [:or :qualified-symbol :string] :int]]
                  [:=> [:cat :string [:sequential [:or :qualified-symbol :string]]] [:map-of [:or :qualified-symbol :string] :int]]]}
  ([tests] (typical-ms (cfg/value :seon.config.proofs/results-dir) tests))
  ([dir tests]
  (into {} (for [t (distinct tests)
                 :let [ms (sort (keep first (history dir t)))]
                 :when (seq ms)]
             [t (nth ms (quot (count ms) 2))]))))

(defn typical-ticks
  "{test ticks} of tests: the median of the engine ticks their newest greens spent (history), whatever the digest: what a test
  stating no estimate is seen to cost (health's list of them, test-runner/unestimated), never its budget (the owner: a budget
  is worked from what a test must do, never the last run). A test with none is left out. docs/merging.md#a-tests-tick-budget."
  {:malli/schema [:function [:=> [:cat [:sequential :qualified-symbol]] [:map-of :qualified-symbol :int]]
                  [:=> [:cat :string [:sequential :qualified-symbol]] [:map-of :qualified-symbol :int]]]}
  ([tests] (typical-ticks (cfg/value :seon.config.proofs/results-dir) tests))
  ([dir tests]
   (into {} (for [t (distinct tests)
                  :let [ts (sort (keep second (history dir t)))]
                  :when (seq ts)]
              [t (nth ts (quot (count ts) 2))]))))

(defn slow-tests
  "{test ms} of the tests of digests ({test digest}) that run past bound (:seon.config.proofs/slow-test-ms): by the fastest of
  its newest greens (recent-verdict-files) and its green at its own digest, so a test made fast comes back as soon as it is
  proved and load, which only ever slows a run, leaves no test out: a test is slow when it is slow every time. A test with no
  green is not slow. Cost: two small reads a test (history). docs/merging.md#a-slow-test-runs-last."
  {:malli/schema [:function [:=> [:cat [:map-of :qualified-symbol [:maybe :string]]] [:map-of :qualified-symbol :int]]
                  [:=> [:cat :string [:map-of :qualified-symbol [:maybe :string]] pos-int?] [:map-of :qualified-symbol :int]]]}
  ([digests] (slow-tests (cfg/value :seon.config.proofs/results-dir) digests (cfg/value :seon.config.proofs/slow-test-ms)))
  ([dir digests bound]
   (into {} (for [[t dg] digests
                  :let [own (when dg (let [f (java.io.File. ^String (verdict-path dir t dg :pass))] (when (.isFile f) (verdict-head-long f #":ms (\d+)"))))
                        ms (cond-> (keep first (history dir t)) own (conj own))]
                  :when (and (seq ms) (>= (apply min ms) bound))]
              [t (apply min ms)]))))

(defn details
  "What each test of cand ({test digest}, a tree's) spent in each contracted function, as its cached
  verdict at that digest measured it: {test {var {:calls :ticks :self-ticks :bytes :self-bytes}}}, the shape the store keeps (seon.proofs/fn-costs)."
  {:malli/schema [:function [:=> [:cat [:map-of :qualified-symbol :string]] [:map-of :qualified-symbol [:map-of :qualified-symbol [:map [:calls :int] [:ticks :int] [:self-ticks :int] [:bytes :int] [:self-bytes :int]]]]]
                  [:=> [:cat :string [:map-of :qualified-symbol :string]] [:map-of :qualified-symbol [:map-of :qualified-symbol [:map [:calls :int] [:ticks :int] [:self-ticks :int] [:bytes :int] [:self-bytes :int]]]]]]}
  ([cand] (details (cfg/value :seon.config.proofs/results-dir) cand))
  ([dir cand]
  (into {} (for [[t dg] cand
                 :let [v (some #(let [v (read-verdict (verdict-path dir t dg %))] (when (and (map? v) (not (contract/error? v))) v)) [:pass :red])]
                 :when (seq (:fns v))]
             [t (into {} (for [[f [calls ticks self-ticks bytes self-bytes]] (:fns v)]
                           [f {:calls calls :ticks ticks :self-ticks self-ticks :bytes bytes :self-bytes self-bytes}]))]))))

(defn checkout-branch
  "The branch the checkout at dir is on (jolt/<area> for a session's worktree), or nil on a detached HEAD: what names the session
  whose agent a worktree's edits are imported onto (my.root/branch-agent). See docs/server.md#working-a-task-from-a-worktree."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [dir] (git-try dir ["symbolic-ref" "--short" "HEAD"]))

(defn bound-port
  "The port the root whose runs directory is runs bound, as bin/system recorded it from its ROOT
  LISTENING line (runs/root.port), or nil when no root there has listened: a root on a port the
  kernel assigns (:seon.config.root/server-port -1) has it nowhere else. See docs/server.md."
  {:malli/schema [:=> [:cat :string] [:maybe :int]]}
  [runs]
  (let [f (java.io.File. (str runs "/root.port"))]
    (when (.isFile f) (parse-long (str/trim (slurp f))))))

(defn runner-end
  "What stopped a runner, joined: the error's own head wherever it falls (an exception's \"Unhandled exception\" line or the
  analyzer's \"error[\" line, up to its trace, at most 4 lines), then its last 6 lines (where it ended and the verdicts). A
  count of last lines alone lost the error twice: three left where and two verdicts, six left a long trace's frames alone (a
  base's runner that threw: \"made no plan\" with no why)."
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  (let [ls (vec (remove str/blank? (str/split-lines text)))
        i (first (keep-indexed (fn [i l] (when (re-find #"^\s*(Unhandled exception|error\[)" l) i)) ls))
        head (when i (take 4 (take-while #(not (re-find #"^\s*trace:" %)) (subvec ls i))))]
    (str/join " | " (distinct (concat head (take-last 6 ls))))))

(defn build-failure
  "What a checkout's failed build says, for whoever asked: the exception it names, where, and when the candidate's files ({path text})
  hold that place, the form written there up to its first blank line, so an agent reads which form of its own stops the build. A build
  that names no exception says so, with how it ended (runner-end): its log is in the checkout the run removes, so the error is
  the only place its words survive. See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat :string [:maybe :map]] :string]}
  [out files]
  (if-let [[_ ex msg path line] (or (re-find #"Unhandled exception \(([^)]*)\): (.*)\n\s*at \./([^:\n]+):(\d+)" out)
                                    ;; Jolt's analyzer names a place by its absolute path in the gate's own worktree
                                    (when-let [[m k msg abs line] (re-find #"error\[([^\]]+)\]: (.*)\n\s*--> ([^:\n]+):(\d+)" out)]
                                      [m k msg (or (some #(when (str/ends-with? abs (str "/" %)) %) (keys files)) abs) line]))]
    (let [text (get files path)
          form (when text (->> (str/split-lines text) (drop (dec (parse-long line))) (take-while (complement str/blank?)) (str/join "\n")))]
      (str "the gate's build failed: " ex ": " msg ", at " path ":" line
           (if (seq form) (str ", where your change wrote: " form) ", a place your change's files don't hold")))
    (str "the gate's build failed and named no exception; it ended: " (runner-end out))))

(defn uncommitted
  "The files git sees changed in the checkout at dir, `git status --porcelain`'s lines, which no merge of the program carries: [] when none, or
  the error when git can't read it. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string] [:or [:vector :string] :seon.error/base]]}
  [dir]
  (let [r (git-run {} dir ["status" "--porcelain" "--ignore-submodules"] nil)]
    (if (zero? (:exit r))
      (vec (remove str/blank? (str/split-lines (:out r))))
      (error #'seon.fx/uncommitted :seon.fx/io-error (str "git status in " dir ": " (:err r)) {:path dir}))))

(defn- kept-files
  "The files of a program store d a copy of it carries: its data, and the reach graphs and reads indexes kept for its trees
  (test-runner/tree-file: one a tree, so the copy's tests read their own tree's, never rebuild it)."
  {:malli/schema [:=> [:cat :any] [:sequential :string]]}
  [^java.io.File d]
  (into [] (comp (filter #(.isFile ^java.io.File %)) (map #(.getName ^java.io.File %))
                 (filter #(or (= % "data.mdb") (str/starts-with? % "reach-graph") (str/starts-with? % "reads-index"))))
        (some-> (.listFiles d) seq)))

;; ---- a session's items (Flow step 4) -------------------------------------------------

(defn- item-files!
  "The files an item's run reads in dir, as bin/prove's plan writes them: a :step item's steps, steps.edn, steps-binary (empty: the step
  needs no target/seon) and step-bounds (its measured ms). See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :string :map] :nil]}
  [dir {:keys [kind tree name digest binary? bound]}]
  (case kind
    :step (do (spit (str dir "/steps") (str name "\n"))
              (spit (str dir "/steps.edn") (sedn/encode {:tree tree :digests {name digest}}))
              (when (false? binary?) (spit (str dir "/steps-binary") ""))
              (spit (str dir "/step-bounds") (str name " " bound "\n")))
    (:build :seed :candidate) nil)
  nil)

(defn- clone-program-store!
  "The checkout's plan program store (its own :seon.config.proofs/program-store, each base-<digest> under it, as kept-store!
  keys them, and each generation of one, base-<digest>-<n>: seon.test-store/generations) cloned under base, an item's own (cp -c, APFS copy-on-write, as each check step clones the seeded store): items
  running at once share no mutable store, and a clone's writes are cache, dropped with it. Answers base. See
  docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :string :string] :string]}
  [checkout base]
  (let [v (cfg/value :seon.config.proofs/program-store)
        root (str (cfg/checkout) "/")
        ;; the key resolves against this root's checkout; the item's store is the same path in the item's checkout
        src (if (str/starts-with? v root) (str checkout "/" (subs v (count root))) v)
        from (java.io.File. ^String src)
        stem (.getName from)]
    (doseq [^java.io.File d (some-> (.getParentFile from) .listFiles seq)
            :when (and (.isDirectory d) (re-matches (re-pattern (str "\\Q" stem "\\E-[0-9a-f]{8}(?:-\\d+)?")) (.getName d)))
            :let [to (str base (subs (.getName d) (count stem)))]]
      (lib/sh "mkdir" "-p" to)
      (doseq [n (kept-files d)]
        (lib/sh "cp" "-c" (str (.getPath d) "/" n) (str to "/" n))))
    base))

(defn- run-tree-item
  "A build, a seed or a check step of run-item, on its checkout at its tree. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [kind tree checkout name digest bound] :as req}]
  (let [t0 (profile/now-ns)
        here (tree-id checkout)
        ;; a green already stored at its digest is the answer: no process started (2.3 s a test's runner, only to reuse it)
        held (when-not (#{:build :seed :candidate} kind) (stored-results {name digest}))]
    (cond
      (not= here tree)
      (error #'seon.fx/run-tree-item :seon.fx/io-error (str "the checkout " checkout " is tree " here ", not the item's " tree ": its files moved since the plan")
             {:path checkout :tree-moved true})
      (contract/error? held) held
      (proofs/green? (get held digest)) {:value (assoc (select-keys (get held digest) [:pass :fail :error]) :ms (profile/ms-since t0) :reused true)}
      :else
      (let [dir (str checkout "/target/prove/items/" (clojure.core/name kind) "-" digest)
            log (str dir ".log")
            _ (lib/remove-tree! dir)
            _ (.mkdirs (java.io.File. ^String dir))
            _ (item-files! dir req)
            ;; a store of its own for each item that runs tests (clone-program-store!): a step reads it through DIR/config.edn
            ;; (bin/check --steps passes it to every ./seon it runs), a shard through --set
            pstore (when (= :step kind) (clone-program-store! checkout (str dir "/program-store")))
            _ (when (= :step kind) (spit (str dir "/config.edn") (sedn/encode {:seon.config.proofs/program-store pstore})))
            ;; the children write the cache this reads: the one this process names (a test's run names its own)
            ;; and the steps' one seeded store is the checkout's own, never a store this process was handed
            store (str checkout "/target/check-store")
            sh (fn [& argv] (let [r (apply lib/sh (concat child-env-scrub [(str "SEON_RESULTS_DIR=" (cfg/value :seon.config.proofs/results-dir))
                                                                           (str "SEON_STORE=" store)]
                                                          ["nice" "-n" "15"] argv [:dir checkout]))]
                              (spit log (str "$ " (str/join " " argv) " (exit " (:exit r) ")\n" (:out r) (:err r)) :append true)
                              r))
            r (case kind
                :build (sh "bin/build" "seon")
                ;; the store every binary step of this tree branches from (each step takes a clone of it, bin/check's unit),
                ;; seeded once, fresh, as bin/check's seed step seeds it: so steps at once never race to make it
                ;; and the process tests' store, made by this checkout's own runtime (its path is its schema's digest), as
                ;; bin/test's plan makes it before any shard: a process item on a fresh checkout found none
                ;; first of all the checkout's plan program store, brought to the tree by the plan's own path (program-db with its
                ;; max-bytes, the one caller that may remove it), so the items that clone it import nothing
                :seed (let [prog (str "(require 'seon.test-store 'test-runner) (test-runner/program-db \\\"$PWD\\\" (seon.test-store/kept-program-store)"
                                      " (seon.fx/tree-id \\\"$PWD\\\") (seon.config/value :seon.config.proofs/program-store-max-bytes))"
                                      (when (:binary? req) " (seon.test-store/make-process-store! \\\"$PWD\\\")"))]
                        (when (:binary? req) (lib/remove-tree! store))
                        (sh "zsh" "-c" (str "ROOT=$PWD; . bin/env; "
                                            (when (:binary? req) "(cd target && ../bin/held ./seon seed 2000 --profile scripted 7>&- 3>&-) && ")
                                            "HOME=$PWD/target/home JOLT_PWD=$PWD target/jolt/jolt -A:test -e \"" prog "\""
                                            ;; and the process tests' store gets this tree's program on :db, as bin/test seeds it before its
                                            ;; shards (bin/system seed): a process item's root clones it, and one seeded at an earlier tree was a
                                            ;; binary built from this tree over a store of that one, every spawn refused (Tests' trial, step 2b)
                                            (when (:binary? req) " && bin/system seed \"$(<target/process-store.path)\""))))
                :step (let [c (sh "bin/check" "--steps" dir)] (sh "bin/test" "--store-steps" dir) c)
                ;; the tree's one booted candidate: its build on a clone of the process tests' store (seon.test-store/boot-candidate!,
                ;; deps'), booted once in root's process and held by the prove proc for every process test of the tree
                :candidate (let [c ((requiring-resolve 'seon.test-store/boot-candidate!) checkout)]
                             (spit log (str "candidate " (sedn/encode (select-keys c [:port :writer-pid :system-pid :dir :runs :store :main])) "\n") :append true)
                             (if (:port c) {:exit 0 :candidate (contract/message :seon.prove/held-candidate c)} {:exit 1 :out (str "the candidate did not boot: exit " (:exit c) "\n" (:log c))})))
            ms (profile/ms-since t0)
            stored (when-not (#{:build :seed :candidate} kind) (stored-results {name digest}))
            _ (when pstore (doseq [^java.io.File d (some-> (java.io.File. ^String dir) .listFiles seq)
                                   :when (str/starts-with? (.getName d) "program-store")]
                             (lib/remove-tree! (.getPath d))))
            v (when (map? stored) (get stored digest))]
        (cond
          (contract/error? stored) stored
          (= :build kind) (if (zero? (:exit r))
                            {:value {:pass 1 :fail 0 :error 0 :failures [] :ms ms}}
                            (error #'seon.fx/run-tree-item :seon.fx/io-error (str (build-failure (str (:out r) (:err r)) nil) "; its log: " log) {:path checkout}))
          (= :candidate kind) (if (zero? (:exit r))
                                {:value {:pass 1 :fail 0 :error 0 :failures [] :ms ms :candidate (:candidate r)}}
                                (error #'seon.fx/run-tree-item :seon.fx/io-error (str "the candidate of tree " tree " did not boot: " (:out r) "; its log: " log) {:path checkout}))
          (= :seed kind) (if (and (zero? (:exit r)) (or (not (:binary? req)) (.isFile (java.io.File. (str store "/data.mdb")))))
                           {:value {:pass 1 :fail 0 :error 0 :failures [] :ms ms}}
                           (error #'seon.fx/run-tree-item :seon.fx/io-error (str "the seed of " store " failed (exit " (:exit r) "): " (runner-end (str (:out r) (:err r))) "; its log: " log) {:path checkout}))
          v {:value (assoc (select-keys v [:pass :fail :error :failures]) :ms ms)}
          (= :step kind) {:value {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message (str "bin/check --steps " name ": " (runner-end (str (:out r) (:err r))))}] :ms ms}}
          :else (error #'seon.fx/run-tree-item :seon.fx/io-error (str "the shard stored no verdict for " name " (exit " (:exit r) "): " (runner-end (str (:out r) (:err r))) "; its log: " log)
                       {:path checkout}))))))

(defn run-item
  "The :process effect's worker perform: one item of a prove that the test service (seon.prove) asked and this kind's owner
  admitted by the processes its class holds (admits?): a build, a seed or a check step on the session's checkout
  (run-tree-item). A test batch is never a worker's: the owner asks the api for it (:seon.fx/ask). See
  docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :map] :map]}
  [{req :req}]
  (cond
    ;; a candidate no item needs any more: stopped, its store and runs removed (seon.test-store/stop-candidate!, deps')
    ;; with its branch's write-out, the commit it was built from, dropped too (drop-write-out), so none outlives its candidate
    (= :stop-candidate (:kind req)) (let [c (:candidate req) v ((requiring-resolve 'seon.test-store/stop-candidate!) c)]
                                      (some-> (:write-out c) (as-> w (drop-write-out {:req w})))
                                      {:value v})
    ;; an agent's branch's candidate (no checkout): the standing binary on a clone of the standing store, on a branch of the agent's
    ;; branch with every running task held (seon.test-store/boot-candidate!'s one boot)
    (and (= :candidate (:kind req)) (:branch req) (not (:checkout req)))
    ;; one whose change moved a build input (its :write-out): its files written out as a commit (write-out), that commit built
    ;; (build), and that build booted, so its bake holds what the branch changed; else the standing binary, this checkout's
    (let [t0 (profile/now-ns)
          wo (:write-out req)
          w (when wo (write-out {:req wo}))
          b (when (and w (not (contract/error? w))) (build {:req {:repo (:dir wo) :commit (get-in w [:value :sha])}}))
          failed (some #(when (contract/error? %) %) [w b])
          c (when-not failed ((requiring-resolve 'seon.test-store/boot-candidate!) (or (get-in b [:value :checkout]) (seon.config/checkout)) (:branch req)))
          dropped (when wo (select-keys wo [:dir :ref]))]
      (cond
        failed (do (some-> dropped (as-> x (drop-write-out {:req x}))) failed)
        (:port c)
        {:value {:pass 1 :fail 0 :error 0 :failures [] :ms (profile/ms-since t0)
                 ;; :build, the build it booted when the branch moved a build input (seon.fx/build's answer): what a merge of this branch
                 ;; switches the standing root over to (:seon.task/build, seon.fx/switch-over), never built again
                 :candidate (contract/message :seon.prove/held-candidate (cond-> c
                                                                          dropped (assoc :write-out dropped)
                                                                          b (assoc :build (contract/message :seon.task/build (:value b)))))}}
        :else (error #'seon.fx/run-item :seon.fx/io-error (str "the candidate of branch " (:branch req) " did not boot: its :log and :exit are this error's") (assoc (select-keys c [:log :exit]) :path (str (:dir c))))))
    :else (run-tree-item req)))

;; ---- the owner -----------------------------------------------------------------------

(defn refill
  "The owner's token bucket at now-ms (session R3): tokens grow at :per-ms up to :cap since :at.
  A bucket is the provider's rate as data: tokens per second, set from its config key and halved by
  a rate-limit outcome."
  {:malli/schema [:=> [:cat :seon.fx/bucket :int] :seon.fx/bucket]}
  [{:keys [tokens cap per-ms at] :as b} now-ms]
  (assoc b :tokens (min cap (+ tokens (* per-ms (max 0 (- now-ms at))))) :at (max at now-ms)))

(defn- spent?
  "Whether request m's task has spent its :budget (spend is the owner's live counter, keyed by
  :budget-key)."
  {:malli/schema [:=> [:cat :map :map] :boolean]}
  [st {:keys [budget budget-key]}] (boolean (and budget (>= (get-in st [:spent budget-key] 0) budget))))

(defn limits
  "The admission dials from the config's optional keys, any combination: :max-workers (the least count set, else the cores), every
  item's; :process-roots, the process rail's (decisions.md, two rails: items that boot a root), its memory ceiling over a booted root's
  (:process-rail-bytes / :root-bytes, rounded down, at least 1; nil when unset); :max-load; and :budget, the cores and two: the size
  of the owner's :answers port. See docs/config.md#the-test-services-three-limits."
  {:malli/schema [:=> [:cat [:map [:cores pos-int?] [:max-workers {:optional true} [:maybe pos-int?]]
                             [:max-cores {:optional true} [:maybe number?]] [:max-load {:optional true} [:maybe number?]]
                             [:process-rail-bytes {:optional true} [:maybe pos-int?]] [:root-bytes {:optional true} [:maybe pos-int?]]]]
                  [:map [:max-workers pos-int?] [:budget pos-int?] [:max-load [:maybe number?]] [:process-roots [:maybe pos-int?]]]]}
  [{:keys [cores max-workers max-cores max-load process-rail-bytes root-bytes]}]
  (let [by-cores (when max-cores (max 1 (long (Math/floor (* max-cores cores)))))
        counts (filterv some? [max-workers by-cores])]
    {:max-workers (if (seq counts) (reduce min counts) cores) :budget (+ cores 2) :max-load max-load
     :process-roots (when (and process-rail-bytes root-bytes) (max 1 (quot process-rail-bytes root-bytes)))}))

(defn config-limits
  "limits of the config this process holds: what limits-held derives once a config change, so a dial the owner turns (when he
  games, say) admits by it at the next admission. See docs/config.md#the-test-services-three-limits."
  {:malli/schema [:=> [:cat] [:map [:max-workers pos-int?] [:budget pos-int?] [:max-load [:maybe number?]] [:process-roots [:maybe pos-int?]]]]}
  []
  (let [c (:config (cfg/in-force))]
    (limits {:cores (:cores (cfg/process)) :max-cores (:seon.config.proofs/max-cores c)
             :max-workers (:seon.config.proofs/max-workers c) :max-load (:seon.config.proofs/max-load c)
             :process-rail-bytes (:seon.config.proofs/process-rail-bytes c) :root-bytes (:seon.config.proofs/root-bytes c)})))

(defn limits-held
  "[st limits]: config-limits of the config this process holds, kept in the reader's own state st (under ::limits-of, with the
  config map it was derived from) and derived again only when the config in force is another value (seon.config/held is replaced
  only when a transaction touches an entry): a dial the owner turns reaches the next admission or grant, and every other one costs
  one identical? compare, never a resolver read (~1k ticks a call, measured 953). The :process owner's dispatch and seon.prove's grant read it.
  See docs/config.md#the-test-services-three-limits."
  {:malli/schema [:=> [:cat :map] [:tuple :map [:map [:max-workers pos-int?] [:budget pos-int?] [:max-load [:maybe number?]]]]]}
  [st]
  (let [c (:config (cfg/in-force)) h (::limits-of st)]
    (if (and h (identical? c (:config h)))
      [st (:limits h)]
      (let [l (config-limits)] [(assoc st ::limits-of {:config c :limits l}) l]))))

(defn batch-tag
  "The tag of batch bid of the test service: the service's process and the batch, the key the :process owner keeps it in flight
  under and its test children's plan names. See docs/merging.md#the-test-service."
  {:malli/schema [:=> [:cat :int] :string]}
  [bid] (str (lib/c-getpid) "-" bid))

(defn hold-reason
  "Why request m waits under limits lim beside st's :flight and :load: :workers, :roots (a process-rail item, one that boots a root,
  past :process-roots of its rail in flight) or :load; nil when admitted. A request of no class is never held, and nothing in flight
  admits one. No class is weighed: a fork's ceilings end a runaway, and the process rail's memory ceiling holds its roots
  (decisions.md, two rails). See docs/config.md#the-test-services-three-limits."
  {:malli/schema [:=> [:cat :map :map :map] [:maybe [:enum :workers :roots :load]]]}
  [{:keys [max-workers max-load process-roots]} st m]
  (let [flight (vals (:flight st))]
    (cond
      (or (nil? (:seon.fx/class m)) (empty? flight)) nil
      (>= (count flight) max-workers) :workers
      (and process-roots (= :process (:seon.fx/rail m)) (>= (count (filter #(= :process (:rail %)) flight)) process-roots)) :roots
      (and max-load (:load st) (>= (:load st) max-load)) :load)))

(defn admits?
  "Whether request m is admitted under limits lim beside what st has in flight (hold-reason is nil). See
  docs/config.md#the-test-services-three-limits."
  {:malli/schema [:=> [:cat :map :map :map] :boolean]}
  [lim st m] (nil? (hold-reason lim st m)))

(defn- admit
  "What the owner does with a queued request at now-ms: :run, :wait (no free worker, or its
  bucket holds fewer tokens than the request's estimate :est: a :tick or a worker's :done
  dispatches again), or the error (the task spent its :budget; spend is this proc's live
  counter, keyed by :budget-key)."
  {:malli/schema [:=> [:cat :map :map :int] [:or [:enum :run :wait] :map]]}
  [st {:keys [budget budget-key est] :as m} now-ms]
  (cond
    (spent? st m)
    (error #'seon.fx/admit :seon.fx/budget-spent "the request's budget is spent" {:budget budget :spent (get-in st [:spent budget-key])})
    (and (>= (:busy st) (:workers st)) (not (get-in m [:req :seon.fx/ask]))) :wait
    (not (admits? (:lim st) st m)) :wait
    (and (:bucket st) (< (:tokens (refill (:bucket st) now-ms)) (or est 0))) :wait
    :else :run))

(defn- charge
  "The owner at one request's outcome: the task's spend grows by the actual cost; the bucket gets
  back (or pays) the difference from the estimate; a rate-limit outcome (the provider's 429, the
  named failure point of this edge) halves the bucket's rate, and each other outcome raises it by
  a twentieth back toward the configured rate (AIMD, TCP's congestion rule)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st {:keys [budget-key cost est outcome]}]
  (let [limited? (= :seon.model/rate-limited (:seon.error/type outcome))]
    (cond-> (update st :done inc)
      ;; a call that states no cost is not charged: the spend grows only by what was
      ;; measured, and the bucket keeps the estimate it reserved
      (and budget-key cost) (update-in [:spent budget-key] (fnil + 0) cost)
      (:bucket st) (update :bucket (fn [b] (-> (cond-> b cost (update :tokens + (- (or est 0) cost)))
                                               (assoc :per-ms (if limited?
                                                                (max (/ (:rate-ms b) 64) (/ (:per-ms b) 2))
                                                                (min (:rate-ms b) (+ (:per-ms b) (/ (:rate-ms b) 20)))))))))))

(defn- reply!
  "The outcome to its asker, through the api's :back (the writer's answer route)."
  {:malli/schema [:=> [:cat :seon.host/volatile :map :map] :nil]}
  [o dest r]
  (step/out! o :out {:dest dest :r r}))

(defn- running-told
  "The owner's running set ({:client :id :since-ns} of each request in flight its asker may stop, from :stoppable), told to the api's
  view (seon.system's :server-ops) when it moved, so root's debug page lists each running server op; nil when none
  moved. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :map :map :seon.host/volatile] :nil]}
  [before st o]
  (when-not (identical? (:stoppable before) (:stoppable st))
    (step/out! o :out {:op :server-ops :kind (:kind st)
                       :running (vec (for [[_ r] (:stoppable st)] (assoc (get-in r [:req :seon.fx/stop-key]) :since-ns (:in-ns r))))}))
  nil)

;; an agent's failed effect is root's to count (seon.error, class :effect): the agent's facts of it
;; are on its branch, which health does not read; root code records its own (seon.error/asked-tx)
(defn- outcome
  "The owner at one request's outcome r (a worker frees once per work item, before this): its
  asker is answered, an agent's failure is root's problem (root code records its own), and its
  request is charged. A rate-limited
  request is not answered: it goes back on the queue, and the bucket is emptied."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o r]
  (let [limited? (= :seon.model/rate-limited (:seon.error/type (:outcome r)))]
    (when-not limited? (reply! o (:dest r) (:outcome r)))
    (when (and (contract/error? (:outcome r)) (get-in r [:dest :agent]))
      (step/out! o :out {:op :problem :class :effect :error (dissoc (:outcome r) :seon.fx/us :seon.fx/queue-us :seon.fx/at-ms)
                         :agent (get-in r [:dest :agent])}))
    (let [st (cond-> (charge st r)
               (:fkey (:request r)) (update :flight dissoc (:fkey (:request r)))
               (::qk (:request r)) (as-> s (let [s' (update s :stoppable dissoc (::qk (:request r)))] (running-told s s' o) s')))]
      (if (and limited? (:request r))
        (let [q (:request r)
              qk (or (::qk q) [(- (:prio q 0)) (inc (:seq st))])]
          (cond-> (-> st (assoc :seq (max (:seq st) (if (vector? qk) (second qk) 0)))
                      (assoc-in [:queue qk] (dissoc q :taken-ns ::qk)))
            (:bucket st) (update :bucket assoc :tokens 0.0)))
        st))))

(defn- wake-later
  "The clock (session R3's :tick): when the head request waits on the bucket alone (a worker is
  free), one :tick is due on the owner's :tick port once the bucket refills to its estimate.
  Answers st; the tick's timer (seon.step/timer) is armed until that tick arrives, whatever state a step that threw left."
  {:malli/schema [:=> [:cat :map :map :int] :map]}
  [st m now]
  (when (and (:bucket st) (:tick st) (< (:busy st) (:workers st)))
    (let [b (refill (:bucket st) now)
          ms (long (Math/ceil (/ (max 1 (- (or (:est m) 0) (:tokens b))) (:per-ms b))))]
      (step/arm! (:tick-timer st) (:tick st) ms {})))
  st)

(defn- batch
  "With the head request m admitted, the requests queued behind it that ride in its worker's
  step, up to the kind's :batch (one when it has none): each in priority order, its budget
  unspent. Answers [st' [m ...]] with those taken out of the queue."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st m]
  (let [more (->> (:queue st) (remove (fn [[_ r]] (spent? st r))) (take (dec (:batch st 1))))]
    [(update st :queue #(apply dissoc % (map key more))) (into [m] (map val) more)]))

(defn- read-machine
  "st with the load read once a pass, only with max-load set, when m has a class and something is in flight. See
  docs/clocks.md#the-machines-load-admits-work."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [st lim m]
  (if (or (:read? st) (nil? (:seon.fx/class m)) (empty? (:flight st)) (nil? (:max-load lim)))
    st
    (assoc st :read? true :load ((:load-fn st)))))

(defn- held-told
  "st and o with the askers of the queued requests told once that they wait on the machine's load ({:op :prove-held :key :held
  true :load :max-load :queued}), so a hold never reads as a hang; the askers are a request's :seon.prove/askers."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o lim]
  (if (:holding st)
    st
    (let [qs (vals (:queue st))
          askers (into (sorted-set) (mapcat :seon.prove/askers) qs)]
      (doseq [k askers] (step/out! o :out {:op :prove-held :key k :held true :load (:load st) :max-load (:max-load lim) :queued (count qs)}))
      (assoc st :holding askers))))

(defn- resumed-told
  "st with the askers told they were held for the load told it ended ({:op :prove-held :key :held false}), at the next admission."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (doseq [k (:holding st)] (step/out! o :out {:op :prove-held :key k :held false}))
  (dissoc st :holding))

(defn- stoppable
  "Requests ms as dispatched: each whose asker may stop it (:seon.fx/stop-key) gets the token its run goes under, made here and
  kept in st's :stoppable until its outcome, so a stop finds it whichever worker took it (stop). Answers [st ms]."
  {:malli/schema [:=> [:cat :map [:sequential :map]] [:tuple :map [:vector :map]]]}
  [st ms]
  (let [ms (mapv #(cond-> % (get-in % [:req :seon.fx/stop-key]) (assoc-in [:req :seon.fx/token] (jolt.host/make-interrupt))) ms)]
    [(reduce (fn [st r] (cond-> st (and (::qk r) (get-in r [:req :seon.fx/token])) (assoc-in [:stoppable (::qk r)] r))) st ms) ms]))

(defn- dispatch
  "Take queued requests in priority order while each is admitted (a free worker, the bucket, the dials of config-limits read this
  pass); a refused one is answered at once; a request of a class joins :flight until its outcome. Answers st. See
  docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (let [[st lim] (if (:limits st) [st (:limits st)] (limits-held st))]
    ;; passed: the queue keys this pass went past, held by their rail's roots (:roots); a runtime item behind a held process item
    ;; still runs, so a rail's ceiling never holds the other rail (decisions.md, two rails)
    (loop [st (assoc st :lim lim :read? false) passed #{}]
      (if-let [[qk m] (first (remove (comp passed key) (:queue st)))]
        (let [now (quot (profile/now-ns) 1000000)
              st (read-machine st lim m)
              d (admit st m now)
              why (when (= :wait d) (hold-reason lim st m))]
          (cond
            (= :roots why) (recur st (conj passed qk))
            (= :wait d) (let [st (if (= :load why) (held-told st o lim) st)]
                          (wake-later (dissoc st :lim :read?) m now))
            (= :run d) (let [[st ms] (batch (update st :queue dissoc qk) (assoc m ::qk qk))
                             ms (mapv #(cond-> % (:seon.fx/class %) (assoc :fkey (or (get-in % [:req :seon.prove/batch]) [:seq (:seon.fx/qk %)]))) ms)
                             [st' ms] (stoppable st ms)
                             _ (running-told st st' o)
                             st st'
                             st (reduce (fn [st r] (if-let [k (:fkey r)]
                                                     (assoc-in st [:flight k] {:class (:seon.fx/class r) :rail (:seon.fx/rail r)})
                                                     st))
                                        st ms)
                             st (cond-> st (and (:holding st) (some :fkey ms)) (resumed-told o))
                             t (profile/now-ns)]
                         (if-let [ask (get-in (first ms) [:req :seon.fx/ask])]
                           ;; a run another process does for it (a test batch, the agent's): asked of the api as an output and
                           ;; answered on :answers, so no step waits inside (docs/flow.md, the five rules); it holds no worker
                           (let [[st msg] (step/ask st [(keyword "fx" (name (:kind st))) :answers] (assoc (first ms) :taken-ns t) ask)]
                             (step/out! o :out msg)
                             (recur st passed))
                           (do
                         (step/out! o :to-workers (if (:batch st) {:batch (mapv #(assoc % :taken-ns t) ms)} (assoc (first ms) :taken-ns t)))
                         (recur (cond-> (update st :busy inc)
                                  (:bucket st) (update :bucket #(update (refill % now) :tokens - (transduce (map (fn [r] (or (:est r) 0))) + ms))))
                                passed))))
            :else (do (reply! o (:dest m) d)
                      (recur (update st :queue dissoc qk) passed))))
        (dissoc st :lim :read?)))))

(defn- stop
  "The owner at a stop {:req {:seon.fx/stop k}} on :ask: each request whose :seon.fx/stop-key holds k is stopped (queued:
  answered :seon.fx/stopped; running: seon.intr/stop! on its token). Answers {:value {:stopped n :dropped n}}. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (let [k (get-in m [:req :seon.fx/stop])
        hit? (fn [[_ r]] (when-let [sk (get-in r [:req :seon.fx/stop-key])] (and (seq k) (= k (select-keys sk (keys k))))))
        queued (filter hit? (:queue st))
        live (filter hit? (:stoppable st))]
    (doseq [[_ r] queued]
      (reply! o (:dest r) (error #'seon.fx/stop :seon.fx/stopped "stopped by its asker before it ran" {:seon.fx/stop k})))
    (run! (fn [[_ r]] (intr/stop! (get-in r [:req :seon.fx/token]))) live)
    (reply! o (:dest m) {:value {:stopped (count live) :dropped (count queued)}})
    (update st :queue #(apply dissoc % (map key queued)))))

(defn- bound
  "The owner's queue holds at most :seon.config.fx/buf requests of the agents; at the bound it stops reading :in (Flow's
  input filter), so agent procs park on :in: back-pressure to the asking agent. :ask (root's) and :done are always read."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] (assoc st ::flow/input-filter (if (>= (count (:queue st)) (cfg/value :seon.config.fx/buf)) #(not= :in %) identity)))

(defn- done-of
  "The owner's :done item for request m answered r, taken at t0 and answered at t1 (ns): the outcome with its
  timing, a failure with the time it failed (seon.error/asked-tx), and its cost. See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :map :map :int :int] :map]}
  [m r t0 t1]
  (cond-> {:dest (:dest m) :request m :budget-key (:budget-key m) :est (:est m 0)
           :outcome (cond-> (assoc r :seon.fx/queue-us (quot (- (:taken-ns m) (:in-ns m)) 1000)
                                   :seon.fx/us (quot (- t1 t0) 1000))
                      ;; when it failed, as its asker records it (seon.error/asked-tx)
                      (contract/error? r) (assoc :seon.fx/at-ms (System/currentTimeMillis)))}
    (:cost r) (assoc :cost (:cost r))))


(defn owner-proc
  "The owner of one effect kind. In :in (the agent procs' requests {:req :dest :prio
  :budget :budget-key :agent}, addressed by id; :seon.config.fx/buf), :ask (root's own asks, the api's: the same requests,
  never held back by the bound, since the api that puts them answers every asker and must never wait on one owner; they are
  bounded where they start: the test service's workers, the reconciler's flights), :done (the workers'
  outcomes), :answers (the api's answers to what it asked: a request whose :req names :seon.fx/ask, a run another process does,
  is asked of the api, never performed by a worker; the port holds every request admission lets be in flight). Out :to-workers
  (its performers' shared channel), :out (the api's :back: {:dest :r}, and the asks)."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:kind "the effect kind" :workers "performers, its concurrency"
                :rate "tokens per second the provider admits (nil: no bucket)"
                :batch "the most requests one worker step takes from the queue (nil: one, as itself)"
                :close? "the kind has a :close: when the queue is empty and nothing is in flight, its worker is told {:idle true}"
                :tick "its clock's channel (external in-port, with :rate): a :tick when the bucket refilled"
                :limits "its admission dials (limits' map; nil: config-limits, read at each admission)"
                :load-fn "reads the machine's load (lib/load-average)"}
       :ins {:in "requests, addressed [:fx/<kind> :in] by any proc" :ask "root's own requests, never held back by the bound" :done "its workers' outcomes"
              :answers "the api's answers to the requests it asked (:seon.fx/ask), one each"} :outs {}
       ;; admission and routing only: never blocks, never computes long
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:kind :workers :busy :load :done]) (assoc :in-flight (count (:flight st))) (assoc :queued (count (:queue st)) :spent (:spent st))))
       ;; docs/health.md#a-proc-that-does-not-drain
       :seon.flow/drain "its queue is finished as when running, each request answered by its worker; its tick re-arms only while a request waits for a worker, budget or bucket, so it ends with the queue"})
  ([args] (assoc (select-keys args [:kind :workers :batch :close? :limits]) :busy 0 :flight {} :stoppable {} :done 0 :rid 0 :pending {}
                 :load-fn (or (:load-fn args) lib/load-average) :seq 0 :queue (sorted-map) :spent {}
                 :bucket (when-let [r (:rate args)]
                           {:tokens (double r) :cap (double r) :per-ms (/ r 1000.0) :rate-ms (/ r 1000.0) :at (quot (profile/now-ns) 1000000)})
                 :tick (:tick args)
                 :tick-timer (step/timer)
                 ::flow/in-ports (if (:tick args) {:tick (:tick args)} {})
                 ::flow/out-ports {:to-workers (:work args) :out (:back args)}))
  ([st _] st)
  ([st in m]
   (let [o (volatile! {})]
     (if (nil? m)
       [st nil]
       (case in
         (:in :ask) (if (get-in m [:req :seon.fx/stop])
                      [(stop st o m) @o]
                      (let [n (inc (:seq st))]
               [(bound (dispatch (-> st (assoc :seq n) (assoc-in [:queue [(- (:prio m 0)) n]] (assoc m :in-ns (profile/now-ns)))) o)) @o]))
         :done (let [st (bound (dispatch (reduce #(outcome %1 o %2) (update st :busy dec) (or (:batch m) [m])) o))]
                 ;; the kind's one worker holds a host object that ends when the work does (:close): the queue
                 ;; empty and nothing in flight is that event, told to the worker as the next item, unless an
                 ;; answered request said more of its own were coming (:more in its :req: main's backlog, whose next
                 ;; round is asked once this one's vectors are written)
                 (when (and (:close? st) (zero? (:busy st)) (empty? (:queue st)) (not-any? #(get-in % [:request :req :more]) (or (:batch m) [m])))
                   (step/out! o :to-workers {:idle true}))
                 [st @o])
         :tick (do (step/fired! (:tick-timer st)) [(bound (dispatch st o)) @o])
         ;; the api's one answer to a request asked of it (step/ask); a second, or a late :ended, finds nothing (step/answered)
         :answers (let [[st m0] (step/answered st (:tag m))]
                    (if m0
                      [(bound (dispatch (outcome st o (done-of m0 (:r m) (:taken-ns m0) (profile/now-ns))) o)) @o]
                      [st @o])))))))

(defn- put-lines!
  "The lines of the bytes b, a piece cut anywhere: every newline byte (never inside a multi-byte character) ends the line
  tail holds so far, decoded whole and put on lines as {:line s}; the rest stays in tail. False when lines is closed."
  {:malli/schema [:=> [:cat :any :any :seon.host/chan] :boolean]}
  [^bytes b ^java.io.ByteArrayOutputStream tail lines]
  (let [n (alength b)]
    (loop [from 0 i 0]
      (cond (= i n) (do (.write tail b from (- n from)) true)
            (= 10 (aget b i)) (do (.write tail b from (- i from))
                                  (let [line (String. (.toByteArray tail) "UTF-8")]
                                    (.reset tail)
                                    (if (a/>!! lines {:line line}) (recur (inc i) (inc i)) false)))
            :else (recur from (inc i))))))

(defn- read-lines!
  "The edge reader of a streaming perform (the one thread docs/flow.md#the-step-fn-contract-every-proc-follows allows outside procs, for a blocking read, as
  seon.fork-server's socket reader): pulls the body's pieces (pull answers the next byte array, nil at the end, or an error
  map when the socket failed) and puts each whole line on lines, then {:end true}, or {:error e} for the failure. Ends
  at either, or when lines is closed; shut (the stream's :shut, nil: none) is called whenever it ends, so the
  connection is let go however the reading stops. With frames? each value pull answers is one line, whole (a helper's framed
  answers: seon.vector/embed). See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:seon.flow/edge "an edge reader for a blocking read: a streaming call's connection, read by one thread that puts each line on its worker's :lines in-port"
   :malli/schema [:function [:=> [:cat :seon.host/fn :seon.host/chan [:maybe :seon.host/fn]] :seon.host/thread]
                  [:=> [:cat :seon.host/fn :seon.host/chan [:maybe :seon.host/fn] :boolean] :seon.host/thread]]}
  ([pull lines shut] (read-lines! pull lines shut false))
  ([pull lines shut frames?]
  (doto (Thread. ^Runnable
                 (fn []
                   (let [tail (java.io.ByteArrayOutputStream.)]
                     (try
                       (loop []
                         (let [b (pull)]
                           (cond (nil? b) (when (or (zero? (.size tail)) (a/>!! lines {:line (String. (.toByteArray tail) "UTF-8")}))
                                            (a/>!! lines {:end true}))
                                 (contract/error? b) (a/>!! lines {:error b})
                                 frames? (when (a/>!! lines {:line b}) (recur))
                                 (put-lines! b tail lines) (recur))))
                       (finally (when shut (shut))))))
                 "seon-fx-stream")
    (.setDaemon true) (.start))))

(defn- stream-out
  "The step's outputs for stream messages ms: each to web's :streams in-port (Flow's dynamic route [pid in]; the web proc
  declares the port, and a graph without it drops them, reported as undeliverable), none when ms is empty."
  {:malli/schema [:=> [:cat [:sequential :map]] [:maybe :map]]}
  [ms]
  (when (seq ms) {[:web :streams] (vec ms)}))

(defn stream-step
  "A streaming worker's step on one item of :lines. {:line s}: the kind's step folds it into the call's state, and the
  messages it answers leave at once. {:end true} or {:error e}: the call's outcome (its final of the state, or the error),
  the messages that end every id still open, and the owner's :done, once, as a perform's outcome is given. A defect in the
  step or the final is that outcome (the entrant boundary of `worker-proc`), and the lines after it are read and dropped
  until the end, so the reader never waits on a worker that stopped reading. See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:maybe :map]]]}
  [st m]
  (let [{:keys [s step final close dead]} (:call st)
        defect (fn [e] (error #'seon.fx/stream-step :seon.fx/defect (step/error-msg e) {}))]
    (if (:line m)
      (if dead
        [st nil]
        (try (let [[s' ms] (step s (:line m))] [(assoc-in st [:call :s] s') (stream-out ms)])
             (catch Throwable e [(assoc-in st [:call :dead] (defect e)) nil])))
      (let [r (cond dead dead
                    (:error m) (:error m)
                    :else (try (final s) (catch Throwable e (defect e))))
            ends (try (close s) (catch Throwable e (defect e)))
            m (get-in st [:call :m]) t0 (get-in st [:call :t0]) t1 (profile/now-ns)
            ;; a batch's final answers one outcome per request, in order; an error, or a count that differs, is every request's
            done (if-let [ms (:batch m)]
                   {:batch (mapv #(done-of %1 %2 t0 t1) ms
                                 (if (and (sequential? r) (= (count ms) (count r)))
                                   r
                                   (repeat (if (contract/error? r) r (defect (ex-info (str "the batch's final answered " (count ms) " requests no outcome each") {}))))))}
                   (done-of m r t0 t1))]
        [(-> st (dissoc :call) (assoc ::flow/input-filter (constantly true)) (update :n + (count (:batch m [m]))))
         (merge {[(:owner st) :done] [done]} (stream-out (when (sequential? ends) ends)))]))))

(defn- stream-due
  "A streaming worker's step on its bound's due {:due t0}: a call quiet past its kind's :seon.bound/ms is shut and its
  outcome made :seon.bound/reached; one that streamed since is re-armed. See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:maybe :map]]]}
  [st m]
  (let [{:keys [ms t0 last-ns shut dead]} (:call st)]
    (if (or (nil? (:call st)) dead (not= t0 (:due m)))
      [st nil]
      (let [quiet (quot (- (profile/now-ns) last-ns) 1000000)]
        (if (< quiet ms)
          (do (step/put-after! (:lines st) (- ms quiet) {:due t0}) [st nil])
          (do (when shut (shut))
              [(assoc-in st [:call :dead] (error #'seon.fx/stream-due :seon.bound/reached
                                                 (str "the " (name (:kind st)) " stream sent nothing for " quiet " ms, past its bound of " ms " ms")
                                                 {:seon.bound/bound {:seon.bound/ms ms} :seon.bound/spent {:seon.bound/ms quiet}
                                                  :seon.bound/at (keyword "seon.fx" (name (:kind st)))}))
               nil]))))))

(defn worker-proc
  "One performer of an effect kind: takes a work item from its owner's shared channel,
  performs it with the kind's Var, and gives the owner the outcome (with its timing). A batch
  item {:batch [m ...]} is performed in one call, {:batch [m ...] :ctx :handle}, whose answer
  is one outcome per request, in order. A kind with :open holds a host object (a model): the
  worker makes it on its first item, (open ctx), keeps it in its own state, and gives it to
  perform as :handle; an open that answers an error is every request's outcome, and the next
  item opens again. A kind with :close is told when its owner's queue has emptied ({:idle true}, no
  outcome): (close handle) lets go of what the handle holds outside the process and answers its
  facts (docs/vector.md#the-model), kept in the ping as :closed; the handle stays and a later item
  uses it again. A perform may answer {:stream {:pull :init :step :final :close}} in place of its
  outcome (a model call that streams): see `read-lines!` and `stream-step`; the worker keeps its slot until the stream ends (a worker stopped mid-call closes its connection). The entrant boundary for a defect in the perform Var: the defect is the
  outcome, as an error map, so its asker is always answered."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:perform "the kind's perform Var" :open "the Var that makes the kind's host object from :ctx (nil: none)"
                :close "the Var that lets go of what the host object holds when the owner's queue is empty (nil: none)"} :ins {} :outs {}
       ;; the effect blocks in native IO (an HTTP read, a file write). Jolt runs :io procs
       ;; on fibers bound to their carrier thread for life, and a blocking FFI call pins the
       ;; carrier, stranding every other fiber placed on it (inv2: replies 4-15 s late).
       ;; :mixed gives each worker its own thread: the declared workload is true, and the
       ;; worker count already bounds the threads
       :workload :mixed
       ;; :open?: it holds its kind's host object (a model's foreign threads run while it does)
       ;; docs/health.md#a-proc-that-does-not-drain
       :seon.flow/drain "its call in flight ends by its kind's declared bound (put-after!), which answers it typed, never by a drain"
       :ping-map-fn (fn [st] (-> (select-keys st [:n :closed]) (assoc :open? (some? (:handle st)))
                                   (cond-> (:facts (:handle st)) (assoc :host @(:facts (:handle st))))))})
  ([args] (let [lines (a/chan)]
            {:perform (:perform args) :open (:open args) :close (:close args) :kind (:kind args) :ctx (:ctx args) :n 0
             :owner (:owner args) :lines lines ::flow/in-ports {:work (:work args) :lines lines}}))
  ;; stopped mid-call: the connection is closed, which ends the reader's pull, and the line it may be holding is taken
  ([st t] (when-let [shut (and (= ::flow/stop t) (get-in st [:call :shut]))]
            (shut)
            (loop [] (when (a/poll! (:lines st)) (recur))))
          st)
  ([st in m]
   (cond
     (nil? m) [st nil]
     (:due m) (stream-due st m)
     ;; a line is the call's progress: its quiet is counted from it (stream-due)
     (= :lines in) (if (:call st) (stream-step (cond-> st (:line m) (assoc-in [:call :last-ns] (profile/now-ns))) m) [st nil])
     (:idle m) [(cond-> st (and (:close st) (:handle st)) (assoc :closed ((:close st) (:handle st)))) nil]
     :else
     (let [ms (or (:batch m) [m])
           t0 (profile/now-ns)
           ;; the host object, kept once made even when perform then throws
           hv (volatile! (:handle st))
           rs (try (let [h (or @hv (when-let [open (:open st)] (open (:ctx st))))]
                     (when-not (contract/error? h) (vreset! hv h))
                     (cond (contract/error? h) (repeat (count ms) h)
                           (:batch m) ((:perform st) {:batch ms :ctx (:ctx st) :handle h})
                           :else [((:perform st) (cond-> (assoc m :ctx (:ctx st)) h (assoc :handle h)))]))
                   (catch Throwable e (repeat (count ms) (error #'seon.fx/worker-proc :seon.fx/defect (step/error-msg e) {}))))
           ;; a perform that answers {:stream ...} (a batch's as a map, one request's as its outcome) is read a line at a time
           sm (if (:batch m) (when (map? rs) (:stream rs)) (:stream (first rs)))
           ;; one outcome per request taken, or each asker would wait for ever
           rs (if (or sm (= (count ms) (count rs))) rs
                  (repeat (count ms) (error #'seon.fx/worker-proc :seon.fx/defect (str "the batch perform answered " (count rs) " outcomes for " (count ms) " requests") {})))
           st (cond-> st @hv (assoc :handle @hv))
           t1 (profile/now-ns)
           done (when-not sm (mapv #(done-of %1 %2 t0 t1) ms rs))]
       (if sm
         ;; a streaming perform: the call is read a line at a time on :lines until it ends, and the worker reads nothing else meanwhile
         (let [bound-ms (get-in (cfg/value :seon.config.fx/bounds) [(:kind st) :seon.bound/ms])]
           (read-lines! (:pull sm) (:lines st) (:shut sm) (boolean (:frames sm)))
           ;; the kind's declared bound, of quiet: re-armed by each line, so a reply that keeps streaming is never cut
           (when bound-ms (step/put-after! (:lines st) bound-ms {:due t0}))
             [(assoc st :call (cond-> (assoc (select-keys sm [:step :final :close :shut]) :m m :s (:init sm) :t0 t0 :last-ns t0) bound-ms (assoc :ms bound-ms))
                     ::flow/input-filter #(= % :lines))
              nil])
         [(update st :n + (count ms)) {[(:owner st) :done] [(if (:batch m) {:batch done} (first done))]}])))))

(defn model
  "The model call (seon.model/complete) under the request's provider (the agent resolves it at its
  turn, seon.config/provider-at: it names the API key's environment variable, never the key; root
  alone reads it, with the run's model), else root's boot provider (:ctx). Answers
  {:value completion :cost tokens} or the provider error; for a request naming its model call (:call), {:stream ...} (seon.model/complete), whose final answers that."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [req ctx]}]
  (let [p (or (:provider req) (:provider ctx))
        c (model/complete p (dissoc req :provider))
        outcome (fn [c] (cond (contract/error? c) c
                              (and (:prompt-tokens c) (:completion-tokens c)) {:value c :cost (+ (:prompt-tokens c) (:completion-tokens c))}
                              :else {:value c}))]
    ;; a stream's completion is shaped when it ends, as a whole reply's is
    (if (:stream c) (update-in c [:stream :final] #(comp outcome %)) (outcome c))))

(defn procs
  "The owner and worker procs of every kind, for the root graph. Any proc reaches an owner
  by its id, [:fx/<kind> :in]; back is the api's :back channel. Answers {pid def}."
  {:malli/schema [:=> [:cat :map :seon.host/chan] :map]}
  [kinds back]
  (reduce-kv
    (fn [acc kind {:keys [perform open close workers ctx rate batch]}]
      (let [work (a/chan workers) owner (keyword "fx" (name kind))]
        (-> acc
            (assoc owner {:proc (flow/process #'owner-proc)
                          :chan-opts {:in {:buf-or-n (cfg/value :seon.config.fx/buf)} :ask {:buf-or-n (cfg/value :seon.config.fx/buf)} :done {:buf-or-n (* 2 workers)}
                                      ;; credit: every request admission lets be in flight at once (each weighs a process or more, within
                                      ;; limits' :budget, the one bound it is taken from), so an answer never parks (docs/flow.md, request and answer)
                                      :answers {:buf-or-n (:budget (config-limits))}}
                          :args (cond-> {:kind kind :workers workers :work work :back back}
                                  rate (assoc :rate rate :tick (a/chan 1))
                                  batch (assoc :batch batch)
                                  close (assoc :close? true))})
            (into (for [i (range workers)]
                    [(keyword (str "fx." (name kind)) (str i))
                     {:proc (flow/process #'worker-proc) :args {:kind kind :perform perform :open open :close close :ctx ctx :work work :owner owner}}])))))
    {} kinds))

(defn collect-store
  "The :collect effect: seon.db/retain! of root's store, asked at quiescence, never at boot. Answers {:value record} (nil when
  none is due or the store is not LMDB), or retain!'s typed refusal with its record under :retained. See docs/storage.md#collection."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [keep]} :req {:keys [conn]} :ctx}]
  ;; an LMDB store only, as the boot's sweep was (a :file store's test root sweeps nothing)
  (if (= :lmdb (get-in @conn [:config :store :backend]))
    (let [r (pdb/retain! conn keep (task/settled-branches @conn))] (if (contract/error? r) r {:value r}))
    {:value nil}))
