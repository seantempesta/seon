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
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [clojure.java.shell :as sh] [clojure.string :as str] [jolt.ffi :as ffi] [jolt.deps :as deps]
            [seon.edn :as sedn] [clojure.edn :as edn] [seon.proofs :as proofs]))

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
  renamed over it (rename(2)), so a reader sees the old or the new file. Answers {:value {:bytes n :path path}}, n the path's
  size on disk after the rename, or :seon.fx/io-error {:path :tmp :errno :message} (errno
  absent when the stage itself failed) with the staged file removed."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [path text]} :req}]
  (let [tmp (str path ".fx-" (lib/c-getpid))
        staged (try (.mkdirs (.getParentFile (java.io.File. ^String tmp))) (spit tmp text) nil
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

(declare inputs tree-id)

(defn- git-run
  "git in dir with args, under the environment variables env adds ({name value}), fed in when given:
  its sh result. The one place src runs git (seon.main-test/git-is-read-through-fx); git! and
  git-try are its two readings."
  {:malli/schema [:=> [:cat [:map-of :string :string] :string [:sequential :string] [:maybe :string]] :map]}
  [env dir args in]
  (let [cmd (concat (when (seq env) (cons "env" (for [[k v] (sort env)] (str k "=" v)))) ["git" "-C" dir] args)]
    (if in (apply sh/sh (concat cmd [:in in])) (apply sh/sh cmd))))

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

(defn git-refs
  "The refs directory of the git repository the checkout at co belongs to (its common dir, so a
  worktree's commits and merges move refs there too), canonical as the watcher reports paths; nil
  when co is no checkout of its own (checked-out?). git's failure throws its stderr (git!)."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [co]
  (when (checked-out? co)
    (str (.getCanonicalPath (java.io.File. ^String (git! {} co ["rev-parse" "--path-format=absolute" "--git-common-dir"] nil))) "/refs")))

(defn head-ref
  "The ref file the checkout at co's HEAD names (its branch's: the common dir's refs/heads/...),
  canonical as the watcher reports paths: the one ref under git-refs whose move is this
  checkout's commit or merge; nil on a detached HEAD or when co is no checkout of its own."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [co]
  (when-let [refs (git-refs co)]
    (when-let [ref (git-try co ["symbolic-ref" "-q" "HEAD"])]
      (str refs (subs ref (count "refs"))))))

(defn- same-repository?
  "Are the checkouts a and b of one git repository (the same common dir: a worktree and its main
  tree are, a clone or a test's own repository is not)?"
  {:malli/schema [:=> [:cat :string :string] :boolean]}
  [a b]
  (let [common #(some-> (git-try % ["rev-parse" "--path-format=absolute" "--git-common-dir"]) java.io.File. .getCanonicalPath)]
    (= (common a) (common b))))

(defn- follow-main
  "Bring the write-back checkout at base (Seon's own source tree, on a branch of its own) to the
  ref of the branch main in co's repository (of the same repository: another one's commits are none
  of its history), never co's checked-out HEAD: fast-forwarded (git merge --ff-only in base) when
  its HEAD is an ancestor of it, so the source follows main with no step of the owner's, whatever
  branch the main tree has checked out (docs/source.md#mains-program-follows-the-tree). nil when
  base is co, either is no checkout of its own, co's repository has no branch main, or base is at main already; the gap
  {:main sha :source sha :ahead n :behind m} (commits base holds that main lacks, and the reverse)
  when base holds commits main lacks, or {:main :source :refused git's words} when the fast-forward
  is refused (a dirty tree): read-source records either and imports the tree as it is."
  {:malli/schema [:=> [:cat :string :string] [:maybe :map]]}
  [base co]
  (let [branch (when (and (not= base (.getCanonicalPath (java.io.File. ^String co))) (checked-out? co) (checked-out? base) (same-repository? base co))
                 "main")
        main (when branch (git-try co ["rev-parse" "--verify" "--quiet" "refs/heads/main"]))
        source (when main (git! {} base ["rev-parse" "--verify" "HEAD"] nil))]
    (when (and main (not= main source))
      (if (zero? (:exit (git-run {} base ["merge-base" "--is-ancestor" "HEAD" main] nil)))
        (let [r (git-run {} base ["merge" "--ff-only" "-q" main] nil)]
          (when-not (zero? (:exit r)) {:main main :source source :refused (str/trim (str (:err r)))}))
        {:main main :source source
         :ahead (parse-long (git! {} base ["rev-list" "--count" (str main "..HEAD")] nil))
         :behind (parse-long (git! {} base ["rev-list" "--count" (str "HEAD.." main)] nil))}))))

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
  it (remove-tree!) and never left in the directory every agent shares. Answers the path; it is
  made by the child (made-run-dir!). See docs/failure-points.md, a directory walk."
  {:malli/schema [:=> [:cat :int] :string]}
  [pid] (str (cfg/value :seon.config.agent/cwd) "/tests/" pid))

(def ^:private child-env-scrub
  "The `env -u` argv that gives a child process the tree's own environment (docs/source.md#the-next-generation):
  bin/env and bin/root export names for this root's checkout, runs and home, and a child inheriting
  SEON_SOURCE_ROOT listed its test namespaces from that checkout, not the tree it ran in (a full run's plan
  named a test of the root's working tree that main does not hold, and its shard died at once). Used by build and run-tests."
  ["env" "-u" "SEON_CHECKOUT" "-u" "JOLT_LMDB_LIB" "-u" "JOLT_PWD" "-u" "HOME"
   "-u" "SEON_SOURCE_ROOT" "-u" "SEON_RUNS"])

(defn remove-tree!
  "Remove the directory dir and all it holds (`rm -rf`: a symbolic link is removed, never entered).
  Answers nil; a directory that is not there is already removed."
  {:malli/schema [:=> [:cat :string] :nil]}
  [dir] (sh/sh "rm" "-rf" dir) nil)

(defn read-source
  "The text of every .clj and .edn file under root's dirs (relative paths, e.g.
  src/seon/fx.clj, resources/seon/schemas/seon.facts.edn), sorted by path, the commit root's
  HEAD names and the revision it pins each reference-code submodule at (its gitlink), both
  absent when root is no git repository (git answers none).
  With :paths (relative paths, the files a watcher saw change) only those files, each nil when
  it is gone, and none of the others read: the cost of what changed.
  With :binary-tree (the file bin/build wrote beside the running binary), also :inputs (the
  tree's, inputs) and :binary (the tree id that file holds, or none), so root records the
  inputs only for a binary built from this very tree. In a git repository also :tree, the id
  of the whole tree as it is on disk (tree-id: git's own index makes it the cost of what
  changed), which stands for every file's content.
  With :follow (the main checkout), root's tree is first brought to the branch main of that checkout's repository (never its checked-out HEAD)
  (follow-main): fast-forwarded when it is behind (the files it moved are read whole, :paths
  set aside, and the answer then holds no :paths). When it holds commits main lacks, or git
  refuses the fast-forward, the answer is a :seon.fx/io-error naming why that still carries the
  read (:value and the rest), so the import goes on and the gap is a recorded problem.
  Answers {:value {path text} :commit sha :tree id :paths paths-when-read-by-them :pins {path sha} :inputs :binary} or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root dirs binary-tree paths follow]} :req}]
  (try
    (let [base (.getCanonicalPath (java.io.File. ^String root))
          head0 (when (and follow (checked-out? base)) (git-try base ["rev-parse" "--verify" "--quiet" "HEAD"]))
          gap (when follow (follow-main base follow))
          head (when (checked-out? base) (git-try base ["rev-parse" "--verify" "--quiet" "HEAD"]))
          paths (when-not (and follow (not= head head0)) paths)
          source? (fn [^String p] (or (.endsWith p ".clj") (.endsWith p ".edn")))
          inputs (when (and binary-tree head) (inputs base))]
      (let [src-read (cond-> {:value (if paths
                        (into (sorted-map)
                              (for [p paths :when (source? p) :let [f (java.io.File. (str base "/" p))]]
                                [p (when (.isFile f) (slurp f))]))
                        (into (sorted-map)
                              (for [d dirs
                                    f (files-under (str base "/" d))
                                    :let [p (.getCanonicalPath ^java.io.File f)]
                                    :when (source? p)]
                                [(subs p (inc (count base))) (slurp f)])))}
        head (assoc :commit head
                    :tree (or (:tree inputs) (tree-id base))
                    :pins (into {}
                                (for [[_ sha p] (re-seq #"(?m)^160000 ([0-9a-f]{40}) \d+\t(\S+)$"
                                                        (git! {} base ["ls-files" "-s" "--" "reference-code"] nil))]
                                  [p sha])))
        inputs (assoc :inputs inputs
                      :binary (let [f (java.io.File. ^String binary-tree)] (when (.isFile f) (str/trim (slurp f)))))
                       paths (assoc :paths paths))]
        (if gap
          (merge (error #'seon.fx/read-source :seon.fx/io-error
                        (if (:refused gap)
                          (str "the source tree " root " (at " (:source gap) ") could not follow main (at " (:main gap) "): "
                               (:refused gap) ": imported as it is")
                          (str "the source tree " root " holds " (:ahead gap) " commits main lacks (it is at " (:source gap)
                               ", main at " (:main gap) ", which holds " (:behind gap) " it lacks): imported as it is"))
                        (assoc gap :path root))
                 src-read)
          src-read)))
    (catch Throwable e (error #'seon.fx/read-source :seon.fx/io-error (step/error-msg e) {:path root}))))

(defn- env-input?
  "Is the file at relative path p an input of every test: everything git tracks or would
  track but the source program facts carry (.clj under src and test), task files (data
  root loads, no test's code), prose (.md) and the shipped config (resources/seon/config/: its
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

(defn- env-class
  "The class of the file at relative path p: :seon.env/system, /web, /deps, /bin, /schemas or /src (the .clj
  files, which env-input? leaves to the program facts: a class only for a reach that reads the program's text), each a part of the checkout one kind of test reads (seon.program/env-class-readers),
  or nil (every test's: native/, the pins, the fixtures, everything else)."
  {:malli/schema [:=> [:cat :string] [:maybe :keyword]]}
  [p]
  (cond (= p "resources/seon/system.edn") :seon.env/system
        (or (str/starts-with? p "resources/public/") (str/starts-with? p "web/")) :seon.env/web
        (= p "deps.edn") :seon.env/deps
        (str/starts-with? p "bin/") :seon.env/bin
        (str/starts-with? p "resources/seon/schemas/") :seon.env/schemas
        ;; the program's text, which a test reading the whole program (a docstring, a form's source) reads
        (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))) :seon.env/src))

(defn config-input
  "The config in force as a test reads it, cfg (seon.config/config: the shipped file, the
  --config layers its command line names, the #env values, the profile), as {key text}: each
  value with the checkout's own path written as <checkout>, and the keys that say where a
  process runs left out (their schemas' :seon.config/place: its ports, main, store, runs,
  source tree and results directory: each checkout's and each root's own by design, while
  :tree covers the source's content). What a result depends on that git does not see: a
  test's digest takes the keys its reach reads (seon.program/config-reads), so an agent's
  root and a checkout on one tree key their results alike, and a key no test reads moves
  none (docs/merging.md)."
  {:malli/schema [:=> [:cat :map :string] [:map-of :qualified-keyword :string]]}
  [cfg checkout]
  ;; a plain map: the store encodes no sorted one, and the digest sorts its keys (seon.program/graph-index)
  (into {} (for [[k v] cfg :when (not (get-in cfg/schema-forms [k 1 :seon.config/place]))]
                       [k (str/replace (sedn/encode v) checkout "<checkout>")])))

(defn declared-refs
  "{seon.system/read-decl #{symbol}}: every qualified symbol the system declaration at p names
  (its procs' step-fns, its effects' performers), which root resolves by name at boot: reach
  edges of the function that reads the declaration (seon.program/graph-index' :data-refs),
  so a test booting root reaches its procs. None when there is no file at p."
  {:malli/schema [:=> [:cat :string] [:map-of :qualified-symbol [:set :qualified-symbol]]]}
  [p]
  (let [f (java.io.File. ^String p)
        decl (when (.isFile f) (edn/read-string {:default tagged-literal} (slurp f)))]
    {'seon.system/read-decl (set (filter qualified-symbol? (tree-seq #(or (coll? %) (tagged-literal? %))
                                                                     #(if (tagged-literal? %) [(:form %)] (seq %)) decl)))}))

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
      (let [r (sh/sh "zsh" "-c" "rm -rf \"$1\" && mkdir -p \"$1\" && tar -x -C \"$1\" -f \"$2\"" "checkout-at" dir (.getPath tar))]
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
  (let [status (str/split-lines (git! {} dir ["status" "--porcelain=v2" "--branch" "-uall" "--ignore-submodules=all"] nil))
        head (second (re-find #"^# branch\.oid (\S+)" (first (filter #(str/starts-with? % "# branch.oid ") status))))
        _ (when (or (nil? head) (= "(initial)" head)) (throw (ex-info (str "pin " dir ": it has no commit") {:dir dir})))]
    (if (every? #(str/starts-with? % "# ") status)
      head
      (str head "+" (tree-id dir [])))))

(defn inputs
  "The one producer of the checkout at root's inputs that have no program facts, for bin/test
  and for root's :source read (read-source, which records them for its agents):
  {:tree (tree-id: every file and pin, for a test reading files or running the binary) :env
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
         entries (for [l (str/split-lines (git! {} root ["ls-tree" "-r" tree] nil)) :let [[m p] (str/split l #"\t" 2)] :when (or (env-input? p) (env-class p))]
                   ;; a submodule by its pin: its files as they are, an uncommitted edit included
                   ;; (a clean one's line is its gitlink's, as before; one not checked out has no files to read)
                   (let [d (str root "/" p)
                         pn (when (and (str/starts-with? m "160000") (checked-out? d)) (pin d))]
                     ;; the runtime's pins (Jolt, Chez) are no class: every test's :env takes them
                     [(or (env-class p) (when (and (str/starts-with? m "160000") (not (runtime-pins p))) (cfg/pin-class p)))
                      (if (and pn (str/includes? pn "+")) (str l " " pn) l)]))
         by-class (group-by first entries)]
     {:tree tree
      :env (lib/digest (str/join "\n" (concat (map second (get by-class nil)) [(str "test-ticks " (:seon.config.agent/test-ticks cfg) " " (:seon.config.agent/test-ticks-floor cfg) " " (:seon.config.agent/test-ticks-factor cfg))])))
      ;; the config in force, and each class of files a test may read (env-class): named inputs
      ;; a test's digest takes by what its reach reads
      :config (into (config-input cfg checkout)
                    (for [[c es] by-class :when c] [c (lib/digest (str/join "\n" (map second es)))]))
      :data-refs (declared-refs (str root "/resources/seon/system.edn"))})))

(defn git-commit
  "Commit files ({relative path text}) on branch of the repository at dir, with message,
  through git's plumbing: blobs, a tree from the branch's own with the files replaced, a
  commit whose parent is the branch head (a branch not there yet starts at dir's HEAD, the
  commit the running generation's tree checks out), and the ref moved only from that head. No
  working tree or checkout is touched, so main and every worktree's files stay as they are.
  With :allow-empty? and no files it commits the branch's own tree again (an empty commit: a review,
  seon.doc/review-message). Answers {:value {:sha :files n}} ({:sha nil :files 0} when there is nothing
  to write), or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir branch files message allow-empty?]} :req}]
  (if (and (empty? files) (not allow-empty?))
    {:value {:sha nil :files 0}}
    (try
      (let [ref (str "refs/heads/" branch)
            have? (some? (git-try dir ["rev-parse" "--verify" "--quiet" ref]))
            parent (git! {} dir ["rev-parse" "--verify" (if have? ref "HEAD")] nil)
            gp (git! {} dir ["rev-parse" "--git-path" (str "seon-index-" (lib/c-getpid))] nil)
            index (if (str/starts-with? gp "/") gp (str dir "/" gp))]
        (try
          (git! {"GIT_INDEX_FILE" index} dir ["read-tree" parent] nil)
          (doseq [[path text] (sort files)]
            (git! {"GIT_INDEX_FILE" index} dir ["update-index" "--add" "--cacheinfo" (str "100644," (git! {} dir ["hash-object" "-w" "--stdin"] text) "," path)] nil))
          ;; Seon is the committer and author of its merges (the config's identity): a
          ;; root's HOME is its checkout's target/home, which holds no git identity
          (let [who (cfg/value :seon.config.source/committer-name) mail (cfg/value :seon.config.source/committer-email)
                sha (git! {"GIT_AUTHOR_NAME" who "GIT_AUTHOR_EMAIL" mail "GIT_COMMITTER_NAME" who "GIT_COMMITTER_EMAIL" mail}
                          dir ["commit-tree" (git! {"GIT_INDEX_FILE" index} dir ["write-tree"] nil) "-p" parent "-m" message] nil)]
            ;; only from the head read above; a new branch only if none appeared meanwhile
            (git! {} dir ["update-ref" ref sha (if have? parent "0000000000000000000000000000000000000000")] nil)
            {:value {:sha sha :files (count files)}})
          (finally (.delete (java.io.File. ^String index)))))
      (catch Throwable e (error #'seon.fx/git-commit :seon.fx/io-error (step/error-msg e) {:path dir})))))

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
  repo built as <:seon.config.source/generations-dir>/<commit>, a worktree of it with this
  checkout's submodule repos at the commit's pins and its target/ cloned (bin/worktree --at),
  then bin/build seon there, niced. The build runs in that tree's own environment (bin/env),
  never this root's checkout. A tree already there is built again (Jolt's caches make an
  unchanged build cheap). Only what cannot load in place needs it (a Jolt, Chez or dependency
  pin, deps.edn, native code): a merge loads in place (seon.program/load-changes!). Answers {:value {:commit :checkout :binary
  :ms}}, or :seon.fx/io-error naming the step and its log (<checkout>.build.log)."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [repo commit]} :req}]
  (let [t0 (profile/now-ns)
        from (cfg/checkout)
        dir (str (cfg/value :seon.config.source/generations-dir) "/" commit)
        log (str dir ".build.log")
        clean child-env-scrub
        step (fn [what args]
               (let [r (apply sh/sh "zsh" "-c" "\"$@\" >> \"$0\" 2>&1" log (concat clean args))]
                 (when-not (zero? (:exit r))
                   ;; the log's text travels with the error: a test that removes its scratch keeps the cause
                   (error #'seon.fx/build :seon.fx/io-error (str what " exited " (:exit r) "; its log: " log) {:path dir :log (slurp log)}))))]
    (.mkdirs (java.io.File. (str (cfg/value :seon.config.source/generations-dir))))
    (or (when-not (.exists (java.io.File. dir))
          (step "bin/worktree --at" [(str from "/bin/worktree") "--at" dir commit repo]))
        (step "bin/build seon" ["nice" "-n" "15" (str dir "/bin/build") "seon"])
        {:value {:commit commit :checkout dir :binary (str dir "/target/seon") :ms (profile/ms-since t0)}})))
(defn test-command
  "The runner the :tests effect calls in a checkout, as argv, for tests (qualified names, or a
  namespace's bare name for all its tests): prove's one entry, each named test with no green at
  its reach digest run, the rest reused, its run written under out (bin/prove --out: the effect
  reads the plan there, never from a path the run did not name). A gate names exactly the
  tests the change reached (:seon.task/deferred), never their namespaces' other tests
  (docs/merging.md#a-merge-runs-what-the-change-reaches)."
  {:malli/schema [:=> [:cat [:sequential :symbol] :string] [:vector :string]]}
  [tests out] (into ["nice" "-n" "15" "bin/prove" "--out" out "--tests"] (map str tests)))

(defn full-run-command
  "The runner of the daily full run, as argv: every test, green or not (bin/test --all: the
  plan ignores the results already recorded, runs the process tests, and measures each cost proof
  at the sizes it states), its run written under out (its plan at out/tests.edn)."
  {:malli/schema [:=> [:cat :string] [:vector :string]]}
  [out] ["nice" "-n" "15" "bin/test" "--prove" "tests" out "--all"])

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

(defn stored-results
  "{digest result} of the verdicts cached for cand ({key digest}): two file checks a key, and a red's file read. A green is
  {:digest :pass 1 :fail 0 :error 0}; a red its status counted once, with its failures and, for a cost
  proof, the verdict it was judged by (:cost {:rows :verdict}); a digest cached both green and red is
  flaky (seon.proofs/verdict), judged red (a green's file is not read: measured on one scripted turn, 2,258 reads of 41 KB each were the turn's largest per-test cost). A test's verdict is a function of its digest, so a red is
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
                  r (when (.exists (java.io.File. ^String rp)) (read-verdict rp))]
              (when g (.setLastModified (java.io.File. ^String gp) (System/currentTimeMillis)))
              (when (map? r) (.setLastModified (java.io.File. ^String rp) (System/currentTimeMillis)))
              (cond (contract/error? r) (reduced r)
                    :else (if-let [v (proofs/verdict dg (when g {:digest dg :pass 1 :fail 0 :error 0})
                                                     (when r (cond-> {:digest dg :pass 0 :fail (if (= :fail (:status r)) 1 0) :error (if (= :error (:status r)) 1 0)
                                                                      :failures (vec (:failures r))}
                                                               (:cost r) (assoc :cost (:cost r)))))]
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

(defn- ledger-bytes
  "The size the cache at dir last wrote down for itself (its .size file), nil when it has none or
  cannot read it (then the next write counts the cache)."
  {:malli/schema [:=> [:cat :string] [:or :nil :int]]}
  [dir]
  (let [f (java.io.File. ^String dir ".size")]
    (when (.isFile f) (parse-long (str/trim (slurp f))))))

(defn- verdict-listing
  "Every verdict file under dir as [file bytes mtime-ms]: the one count of the cache, by metadata
  alone (a staged file or the .size ledger is not a verdict)."
  {:malli/schema [:=> [:cat :string] [:vector :any]]}
  [dir]
  (vec (for [^java.io.File f (files-under dir) :when (str/ends-with? (.getName f) ".edn")]
         [f (.length f) (.lastModified f)])))

(defn- evict!
  "Keep the cache at dir within max-bytes after a write of added bytes (the files at kept, never
  removed). The size is kept in the cache's .size ledger, so a write that leaves the cache under the
  bound reads and rewrites one small file and counts nothing; past it (or with no ledger) the
  verdict files are counted once, and the least recently used (the oldest mtime: seon.fx/stored-results
  touches a verdict it reads) go first, greens and reds alike, until the cache is under the bound, and
  the ledger is set to the true size. Other writers' additions and removals make the ledger a lower or
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
            size (reduce + (map second files))]
        (ledger! (loop [[[^java.io.File f n] & more] (sort-by #(nth % 2) (remove #(kept (str (first %))) files)) size size]
                   (if (and f (> size max-bytes))
                     (recur more (if (.delete f) (- size n) size))
                     size)))))))

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
  (let [writer (select-keys run [:id :tree :branch :t :agent])
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
                  (-> acc (update :recorded inc) (update :bytes + (:bytes (:value w))) (update :kept conj (:path (:value w)))))))
            {:recorded 0 :bytes 0 :kept #{}}
            (filter (fn [[_ r]] (and (:digest r) (not (:flaky r)) (not (:unreported r)))) ran))]
    (when-not (contract/error? done) (when (pos? (:recorded done)) (evict! dir (:bytes done) (:kept done) max-bytes)))
    (if (contract/error? done) done (select-keys done [:recorded])))))

(defn typical-ms
  "{test ms} of tests (or of check steps' and generative-check keys, strings): the median of the ms their cached verdicts measured, whatever the digest: what
  orders a run's deal of tests into shards, never what a test proves. The median, not the largest: one
  run under load or a hang (a 17 s test once measured 625 s) must not weigh a test as that run did, which
  made one shard of a merge's run end at 62 s beside three at 640-746 (docs/merging.md#a-merge-runs-what-the-change-reaches).
  A test with none is left out; an unreadable file is passed over (an order, never a verdict)."
  {:malli/schema [:function [:=> [:cat [:sequential [:or :qualified-symbol :string]]] [:map-of [:or :qualified-symbol :string] :int]]
                  [:=> [:cat :string [:sequential [:or :qualified-symbol :string]]] [:map-of [:or :qualified-symbol :string] :int]]]}
  ([tests] (typical-ms (cfg/value :seon.config.proofs/results-dir) tests))
  ([dir tests]
  (into {} (for [t (distinct tests)
                 :let [ms (sort (keep (fn [[_ _ f]] (let [v (read-verdict (str f))] (when-not (contract/error? v) (:ms v)))) (verdict-files dir t)))]
                 :when (seq ms)]
             [t (nth ms (quot (count ms) 2))]))))

(defn- green-ticks
  "The ticks the green verdict file f measured, read from its first bytes (a verdict writes :ticks
  before its failures, cost and per-function rows, which are most of its size: seon.fx/write-verdicts!),
  nil when it has none or cannot be read. An order of work, never a verdict (docs/merging.md#a-tests-tick-budget)."
  {:malli/schema [:=> [:cat :any] [:maybe :int]]}
  [f]
  (try (with-open [r (java.io.FileReader. ^java.io.File f)]
         (let [buf (char-array 768) n (.read r buf)]
           (when (pos? n) (some-> (re-find #":ticks (\d+)" (String. buf 0 (int n))) second parse-long))))
       (catch java.io.IOException _ nil)))

(defn test-budgets
  "{test ticks} of tests: the engine ticks each may run, min(cap, max(floor, factor x the median of the
  ticks its green verdicts measured, at any digest)); a test with no green gets the floor. cap
  (:seon.config.agent/test-ticks), floor (:seon.config.agent/test-ticks-floor) and factor
  (:seon.config.agent/test-ticks-factor) are config; 0 cap: none (nil). The history is read once, here, by a run before it
  runs anything. Work done, never time; derived data, so a missing or evicted history gives the floor.
  docs/merging.md#a-tests-tick-budget."
  {:malli/schema [:function [:=> [:cat [:sequential :qualified-symbol]] [:maybe [:map-of :qualified-symbol pos-int?]]]
                  [:=> [:cat :string [:sequential :qualified-symbol] nat-int? pos-int? pos-int?] [:maybe [:map-of :qualified-symbol pos-int?]]]]}
  ([tests] (test-budgets (cfg/value :seon.config.proofs/results-dir) tests (cfg/value :seon.config.agent/test-ticks)
                         (cfg/value :seon.config.agent/test-ticks-floor) (cfg/value :seon.config.agent/test-ticks-factor)))
  ([dir tests cap floor factor]
   (when (pos? cap)
     (into {} (for [t (distinct tests)
                    :let [g (sort (keep (fn [[_ st f]] (when (= :pass st) (green-ticks f))) (verdict-files dir t)))
                          med (if (seq g) (nth g (quot (count g) 2)) 0)]]
                [t (min cap (max floor (* factor med)))])))))

(defn details
  "What each test of cand ({test digest}, a tree's) spent in each contracted function, as its cached
  verdict at that digest measured it: {test {var [calls ticks self-ticks bytes self-bytes]}}."
  {:malli/schema [:function [:=> [:cat [:map-of :qualified-symbol :string]] [:map-of :qualified-symbol [:map-of :qualified-symbol [:tuple :int :int :int :int :int]]]]
                  [:=> [:cat :string [:map-of :qualified-symbol :string]] [:map-of :qualified-symbol [:map-of :qualified-symbol [:tuple :int :int :int :int :int]]]]]}
  ([cand] (details (cfg/value :seon.config.proofs/results-dir) cand))
  ([dir cand]
  (into {} (for [[t dg] cand
                 :let [v (some #(let [v (read-verdict (verdict-path dir t dg %))] (when (and (map? v) (not (contract/error? v))) v)) [:pass :red])]
                 :when (seq (:fns v))]
             [t (:fns v)]))))

(defn test-results
  "Each of tests' status from its result at its digest: plan is what prove's plan wrote in the
  tree it ran in (out/tests.edn: {:digests {test digest}}; nil when the plan never ran), found the
  verdicts cached at those digests ({digest result}, stored-results, which the run's shards wrote).
  :pass when a green is cached (it ran green, or was green at its
  digest), :fail when a red is (flaky, or a red), :error with none: a run that never reached it
  (its plan or its build failed, it died) says nothing of it passing. The record read, never
  prove's printed output."
  {:malli/schema [:=> [:cat [:maybe [:map [:digests [:map-of :qualified-symbol :string]]]] [:map-of :string :map] [:sequential :qualified-symbol]]
                  [:map-of :qualified-symbol [:enum :pass :fail :error]]]}
  [plan found tests]
  (into {} (for [t tests :let [r (some-> (get-in plan [:digests t]) found)]]
             [t (cond (nil? r) :error (proofs/green? r) :pass :else :fail)])))

(defn branch-head
  "The commit branch names in the repository at repo, or the commit HEAD names when the branch is not
  there yet: the rule git-commit starts a new branch by (it starts at dir's HEAD), so a merge gated
  before its first write-back runs on the tree that write-back would branch from. nil when neither
  names a commit (no commit at all)."
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [repo branch]
  (or (git-try repo ["rev-parse" "--verify" "--quiet" (str "refs/heads/" branch "^{commit}")])
      (git-try repo ["rev-parse" "--verify" "--quiet" "HEAD^{commit}"])))

(defn bound-port
  "The port the root whose runs directory is runs bound, as bin/root recorded it from its ROOT
  LISTENING line (runs/root.port), or nil when no root there has listened: a root on a port the
  kernel assigns (:seon.config.root/server-port -1) has it nowhere else. See docs/server.md."
  {:malli/schema [:=> [:cat :string] [:maybe :int]]}
  [runs]
  (let [f (java.io.File. (str runs "/root.port"))]
    (when (.isFile f) (parse-long (str/trim (slurp f))))))

(defn run-tests
  "The :tests effect: tests (qualified names) run on a checkout of the merge candidate: a
  detached worktree of repo at branch's head (bin/worktree --at, as a generation's), files
  ({relative path text}, the candidate's changed files) written over it, then the runner
  (test-command) over exactly the tests named there, niced, in that tree's own environment;
  the worktree removed after (its run's logs kept beside its log when it is not all green). The tree's
  binary is built first (bin/build seon), so its process tests run this candidate. Answers {:value {:results {test status} :ms :log}}, or
  :seon.fx/io-error naming the step and its log. With :all? (the daily full run, seon.prove's
  :full mode) the runner is full-run-command, tests are every test the plan names, and the answer
  also holds :digests {test digest} and :failures {test [failure ...]} of its reds (the
  failures their results recorded, which the run's shard logs, removed with the worktree, also
  held); repo defaults to this checkout and branch to git's main (:seon.config.source/main)."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [repo branch files tests all?]} :req}]
  (let [t0 (profile/now-ns)
        repo (or repo (when all? (str (cfg/checkout))))
        branch (or branch (when all? (cfg/value :seon.config.source/main)))
        ;; the branch's commit, or the error the asker gets: no such branch is never a run at an empty sha
        sha (try (or (branch-head repo branch)
                     (error #'seon.fx/run-tests :seon.fx/io-error (str "no commit of branch " branch " in " repo) {:path repo}))
                 (catch Throwable e (error #'seon.fx/run-tests :seon.fx/io-error (step/error-msg e) {:path repo})))
        dir (str (cfg/value :seon.config.source/tests-dir) "/" sha "-" (random-uuid))
        log (str dir ".tests.log")
        clean child-env-scrub
        ;; the run's own logs (seed.log, the shard logs) live in the worktree removed below: a run
        ;; that is not all green keeps them beside its log, the evidence of why (run 29's gate
        ;; seed threw, and its seed.log went with the worktree)
        kept (str dir ".run")
        green? (volatile! false)
        at (when-not (contract/error? sha) (sh/sh (str (cfg/checkout) "/bin/worktree") "--at" dir sha repo))]
    (cond
      (contract/error? sha) sha
      (not (zero? (:exit at))) (error #'seon.fx/run-tests :seon.fx/io-error (str "bin/worktree --at exited " (:exit at) ": " (:err at)) {:path dir})
      :else
      (try
        (doseq [[p text] files] (let [f (java.io.File. dir p)] (.mkdirs (.getParentFile f)) (spit f text)))
        ;; process tests run this tree's binary, so it is built from this tree first, the gate's
        ;; as the full run's: the worktree's target/ is a clone of another tree's, and its binary
        ;; that tree's (measured 2026-10-27: the first daily run's process tests ran on a stale one,
        ;; red for that alone); bin/build skips it when the tree matches, else ~20 s
        (let [b (apply sh/sh (concat clean ["nice" "-n" "15" "bin/build" "seon"] [:dir dir]))]
          (if (not (zero? (:exit b)))
            (do (spit log (str (:out b) (:err b)))
                (error #'seon.fx/run-tests :seon.fx/io-error (str "bin/build seon exited " (:exit b) "; its log: " log) {:path dir}))
            ;; the tree's shards cache their verdicts in this checkout's verdict cache, named to them,
            ;; so the verdicts read below are the ones its run wrote
            (let [out (str dir "/target/prove/run")
                  r (apply sh/sh (concat clean [(str "SEON_RESULTS_DIR=" (cfg/value :seon.config.proofs/results-dir))]
                                         (if all? (full-run-command out) (test-command (sort-by str (distinct tests)) out))
                                         [:dir dir]))
                  plan (let [f (java.io.File. ^String out "tests.edn")] (when (.isFile f) (edn/read-string (slurp f))))
                  _ (spit log (str (:out r) (:err r)))
                  tests (if all? (vec (sort-by str (keys (:digests plan)))) tests)
                  found (when plan (stored-results (:digests plan)))]
              (cond
                ;; no plan (out/tests.edn): the runner never got to its tests (its build, its plan or its start failed): that is the
                ;; answer, with the end of what it said, never a run whose tests all read :error with no reason
                (nil? plan)
                (error #'seon.fx/run-tests :seon.fx/io-error
                       (str "the test runner made no plan in " dir " (exit " (:exit r) "); its log: " log
                            " ends: " (str/join " | " (take-last 3 (remove str/blank? (str/split-lines (str (:err r) "\n" (:out r)))))))
                       {:path dir})
                (contract/error? found)
                found
                :else
                (let [rs (test-results plan found tests)]
                  (vreset! green? (every? #{:pass} (vals rs)))
                  {:value (cond-> {:results rs :ms (profile/ms-since t0) :log log}
                            (not @green?) (assoc :kept kept)
                            all? (assoc :digests (select-keys (:digests plan) tests)
                                        ;; why each red is red: the failures its result recorded
                                        :failures (into {} (for [[t s] rs :when (not= :pass s)
                                                                 :let [fs (some->> (get-in plan [:digests t]) found :failures not-empty vec)]
                                                                 :when fs]
                                                             [t fs]))))})))))
        ;; the worktree's removal is recorded in the run's log: a failed one names what it left
        (finally (let [run (java.io.File. (str dir "/target/prove/run"))
                       mv (when (and (not @green?) (.isDirectory run)) (sh/sh "mv" (.getPath run) kept))
                       rm (sh/sh (str (cfg/checkout) "/bin/worktree") "--remove-at" dir)]
                   (spit log (str (when mv (str "\nkept the run's logs (not all green): " kept ": exit " (:exit mv) (:err mv)))
                                  "\nremove-at " dir ": exit " (:exit rm) "\n" (:out rm) (:err rm)) :append true)))))))

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
    (>= (:busy st) (:workers st)) :wait
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
    (let [st (charge st r)]
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
  Answers st, marked :tick-due until that tick arrives."
  {:malli/schema [:=> [:cat :map :map :int] :map]}
  [st m now]
  (if (and (:bucket st) (:tick st) (not (:tick-due st)) (< (:busy st) (:workers st)))
    (let [b (refill (:bucket st) now)
          ms (long (Math/ceil (/ (max 1 (- (or (:est m) 0) (:tokens b))) (:per-ms b))))]
      (step/put-after! (:tick st) ms {})
      (assoc st :tick-due true))
    st))

(defn- batch
  "With the head request m admitted, the requests queued behind it that ride in its worker's
  step, up to the kind's :batch (one when it has none): each in priority order, its budget
  unspent. Answers [st' [m ...]] with those taken out of the queue."
  {:malli/schema [:=> [:cat :map :map] [:tuple :map [:vector :map]]]}
  [st m]
  (let [more (->> (:queue st) (remove (fn [[_ r]] (spent? st r))) (take (dec (:batch st 1))))]
    [(update st :queue #(apply dissoc % (map key more))) (into [m] (map val) more)]))

(defn- dispatch
  "Take queued requests in priority order while workers are free; a refused one is answered
  at once. A kind with :batch gives a free worker its head request and those queued behind it,
  as one work item {:batch [m ...]}. Answers st."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (loop [st st]
    (if-let [[qk m] (first (:queue st))]
      (let [now (quot (profile/now-ns) 1000000)
            d (admit st m now)]
        (cond
          (= :wait d) (wake-later st m now)
          (= :run d) (let [[st ms] (batch (update st :queue dissoc qk) (assoc m ::qk qk))
                           t (profile/now-ns)]
                       (step/out! o :to-workers (if (:batch st) {:batch (mapv #(assoc % :taken-ns t) ms)} (assoc (first ms) :taken-ns t)))
                       (recur (cond-> (update st :busy inc)
                                (:bucket st) (update :bucket #(update (refill % now) :tokens - (transduce (map (fn [r] (or (:est r) 0))) + ms))))))
          :else (do (reply! o (:dest m) (assoc d :seon.fx/kind (:kind st)))
                    (recur (update st :queue dissoc qk)))))
      st)))

(defn- bound
  "The owner's queue holds at most :seon.config.fx/buf requests; at the bound it reads only :done
  (Flow's input filter), so agent procs park on :in: back-pressure to the asking agent."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] (assoc st ::flow/input-filter (if (>= (count (:queue st)) (cfg/value :seon.config.fx/buf)) #(not= :in %) identity)))

(defn owner-proc
  "The owner of one effect kind. In :in (the agent procs' requests {:req :dest :prio
  :budget :budget-key :agent}, addressed by id; :seon.config.fx/buf), :done (the workers'
  outcomes). Out :to-workers (its performers' shared channel), :out (the api's :back:
  {:dest :r})."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:kind "the effect kind" :workers "performers, its concurrency"
                :rate "tokens per second the provider admits (nil: no bucket)"
                :batch "the most requests one worker step takes from the queue (nil: one, as itself)"
                :close? "the kind has a :close: when the queue is empty and nothing is in flight, its worker is told {:idle true}"
                :tick "its clock's channel (external in-port, with :rate): a :tick when the bucket refilled"}
       :ins {:in "requests, addressed [:fx/<kind> :in] by any proc" :done "its workers' outcomes"} :outs {}
       ;; admission and routing only: never blocks, never computes long
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:kind :workers :busy :done]) (assoc :queued (count (:queue st)) :spent (:spent st))))})
  ([args] (assoc (select-keys args [:kind :workers :batch :close?]) :busy 0 :done 0 :seq 0 :queue (sorted-map) :spent {}
                 :bucket (when-let [r (:rate args)]
                           {:tokens (double r) :cap (double r) :per-ms (/ r 1000.0) :rate-ms (/ r 1000.0) :at (quot (profile/now-ns) 1000000)})
                 :tick (:tick args)
                 ::flow/in-ports (if (:tick args) {:tick (:tick args)} {})
                 ::flow/out-ports {:to-workers (:work args) :out (:back args)}))
  ([st _] st)
  ([st in m]
   (let [o (volatile! {})]
     (if (nil? m)
       [st nil]
       (case in
         :in (let [n (inc (:seq st))]
               [(bound (dispatch (-> st (assoc :seq n) (assoc-in [:queue [(- (:prio m 0)) n]] (assoc m :in-ns (profile/now-ns)))) o)) @o])
         :done (let [st (bound (dispatch (reduce #(outcome %1 o %2) (update st :busy dec) (or (:batch m) [m])) o))]
                 ;; the kind's one worker holds a host object that ends when the work does (:close): the queue
                 ;; empty and nothing in flight is that event, told to the worker as the next item, unless an
                 ;; answered request said more of its own were coming (:more in its :req: main's backlog, whose next
                 ;; round is asked once this one's vectors are written)
                 (when (and (:close? st) (zero? (:busy st)) (empty? (:queue st)) (not-any? #(get-in % [:request :req :more]) (or (:batch m) [m])))
                   (step/out! o :to-workers {:idle true}))
                 [st @o])
         :tick [(bound (dispatch (dissoc st :tick-due) o)) @o])))))

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
  uses it again. The entrant boundary for a defect in the perform Var: the defect is the
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
       :ping-map-fn (fn [st] (-> (select-keys st [:n :closed]) (assoc :open? (some? (:handle st)))
                                   (cond-> (:facts (:handle st)) (assoc :host @(:facts (:handle st))))))})
  ([args] {:perform (:perform args) :open (:open args) :close (:close args) :kind (:kind args) :ctx (:ctx args) :n 0
           :owner (:owner args) ::flow/in-ports {:work (:work args)}})
  ([st _] st)
  ([st _ m]
   (cond
     (nil? m) [st nil]
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
           ;; one outcome per request taken, or each asker would wait for ever
           rs (if (= (count ms) (count rs)) rs
                  (repeat (count ms) (error #'seon.fx/worker-proc :seon.fx/defect (str "the batch perform answered " (count rs) " outcomes for " (count ms) " requests") {})))
           st (cond-> st @hv (assoc :handle @hv))
           t1 (profile/now-ns)
           done (mapv (fn [m r]
                        (cond-> {:dest (:dest m) :request m :budget-key (:budget-key m) :est (:est m 0)
                                 :outcome (cond-> (assoc r :seon.fx/kind (:kind st) :seon.fx/queue-us (quot (- (:taken-ns m) (:in-ns m)) 1000)
                                                         :seon.fx/us (quot (- t1 t0) 1000))
                                            ;; when it failed, as its asker records it (seon.error/asked-tx)
                                            (contract/error? r) (assoc :seon.fx/at-ms (System/currentTimeMillis)))}
                          (:cost r) (assoc :cost (:cost r))))
                      ms rs)]
       [(update st :n + (count ms)) {[(:owner st) :done] [(if (:batch m) {:batch done} (first done))]}]))))

(defn model
  "The model call (seon.model/complete) under the request's provider (the agent resolves it at its
  turn, seon.config/provider-at: it names the API key's environment variable, never the key; root
  alone reads it, with the run's model), else root's boot provider (:ctx). Answers
  {:value completion :cost tokens} or the provider error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [req ctx]}]
  (let [p (or (:provider req) (:provider ctx))
        c (model/complete p (dissoc req :provider))]
    (cond (contract/error? c) c
          (and (:prompt-tokens c) (:completion-tokens c)) {:value c :cost (+ (:prompt-tokens c) (:completion-tokens c))}
          :else {:value c})))

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
                          :chan-opts {:in {:buf-or-n (cfg/value :seon.config.fx/buf)} :done {:buf-or-n (* 2 workers)}}
                          :args (cond-> {:kind kind :workers workers :work work :back back}
                                  rate (assoc :rate rate :tick (a/chan 1))
                                  batch (assoc :batch batch)
                                  close (assoc :close? true))})
            (into (for [i (range workers)]
                    [(keyword (str "fx." (name kind)) (str i))
                     {:proc (flow/process #'worker-proc) :args {:kind kind :perform perform :open open :close close :ctx ctx :work work :owner owner}}])))))
    {} kinds))

(defn stall-evidence
  "A stalled agent's evidence, captured while it is stuck (seon.root's stall task asks it at the
  stall's first write): its fiber dump (SIGINFO: Jolt writes every fiber's stack to the process's
  stderr, read from its stderr file after the sample), `sample` of pid for 5 s, `lsof` of pid, and
  `ps` of its process group, each a file in dir. A hang is evidence, kept by the system rather than
  remembered (a root that restarts loses the process). Answers {:value {:dir :files {kind path}}}
  naming each file written with something in it, or :seon.fx/io-error when none was (the process
  already gone)."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [pid dir stderr]} :req}]
  (let [script (str "mkdir -p \"$1\" && cd \"$1\" || exit 1; p=$2\n"
                    "kill -INFO $p 2>/dev/null\n"
                    "sample $p 5 -file sample.txt > /dev/null 2>&1\n"
                    "lsof -p $p > lsof.txt 2>&1\n"
                    "g=$(ps -o pgid= -p $p | tr -d ' '); [[ -n $g ]] && ps -o pid,ppid,pgid,stat,etime,%cpu,rss,command -g $g > ps.txt 2>&1\n")
        _ (sh/sh "zsh" "-c" script "evidence" dir (str pid))
        _ (some->> (lib/tail-bytes stderr (cfg/value :seon.config.agent/stall-fibers-bytes)) (spit (str dir "/fibers.txt")))
        files (into {} (for [[k f] {:sample "sample.txt" :lsof "lsof.txt" :ps "ps.txt" :fibers "fibers.txt"}
                             :let [path (str dir "/" f) file (java.io.File. path)]
                             :when (pos? (.length file))]
                         [k path]))]
    (if (seq files)
      {:value {:dir dir :files files}}
      (error #'seon.fx/stall-evidence :seon.fx/io-error (str "no evidence of process " pid " was captured in " dir ": it is gone") {:path dir}))))

