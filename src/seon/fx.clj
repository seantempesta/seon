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
            [seon.edn :as sedn] [clojure.edn :as edn] [seon.proofs :as proofs] [seon.reader :as reader]))

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
  bin/env and bin/root export names for this root's checkout, runs and home, and a child inheriting
  SEON_SOURCE_ROOT listed its test namespaces from that checkout, not the tree it ran in (a full run's plan
  named a test of the root's working tree that main does not hold, and its shard died at once). Used by build, run-tests
  and the health step's base (test-runner/base-program)."
  ["env" "-u" "SEON_CHECKOUT" "-u" "JOLT_LMDB_LIB" "-u" "JOLT_PWD" "-u" "HOME"
   "-u" "SEON_SOURCE_ROOT" "-u" "SEON_RUNS"])

(defn source-file?
  "Is the file at relative path p one a read of the source takes, under a source dir: Clojure or EDN, or a kind the
  allow-list names (seon.config/file-kind). See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string] :boolean]}
  [^String p] (boolean (or (.endsWith p ".clj") (.endsWith p ".edn") (cfg/file-kind p))))

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

(defn changed-since
  "The files of the tree at root as it is on disk that differ from tree (a tree id seon.fx/tree-id made): git's diff of tree
  against the files, submodules left out (a pin is a build input, which seon.root/binary-current judges), and each untracked
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
        ;; the wiki and the task files, each read whole by its own reader (seon.doc/pages, seon.root/task-files): a class, so a
        ;; wiki edit moves the tests that read the wiki and no other (a 4-line edit to docs/decisions.md ran 117 tests, 2026-11-11).
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
  value with the checkout's own path written as <checkout>, and the keys that say where a
  process runs left out (their schemas' :seon.config/place: its ports, main, store, runs,
  source tree and results directory, and the profile it runs under and what that picks, its provider, script and whether it is its store's sole writer: each checkout's and each root's own by design, as a test reads nothing of its runner, while
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
  "{seon.system/read-decl #{symbol}}: every qualified symbol the system declaration's text names
  (its procs' step-fns, its effects' performers), which root resolves by name at boot: reach
  edges of the function that reads the declaration (seon.program/graph-index' :data-refs),
  so a test booting root reaches its procs. None when there is no text (a tree with no system.edn)."
  {:malli/schema [:=> [:cat [:maybe :string]] [:map-of :qualified-symbol [:set :qualified-symbol]]]}
  [text]
  (let [decl (when text (edn/read-string {:default tagged-literal} text))]
    {'seon.system/read-decl (set (filter qualified-symbol? (tree-seq #(or (coll? %) (tagged-literal? %))
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
  (let [status (str/split-lines (git! {} dir ["status" "--porcelain=v2" "--branch" "-uall" "--ignore-submodules=all"] nil))
        head (second (re-find #"^# branch\.oid (\S+)" (first (filter #(str/starts-with? % "# branch.oid ") status))))
        _ (when (or (nil? head) (= "(initial)" head)) (throw (ex-info (str "pin " dir ": it has no commit") {:dir dir})))]
    (if (every? #(str/starts-with? % "# ") status)
      head
      (str head "+" (tree-id dir [])))))

(defn inputs
  "The one producer of the checkout at root's inputs that have no program facts, for bin/test
  and for the binary's build record (seon.root/built-program, which a boot hands its agents):
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
      :env (lib/digest (str/join "\n" (concat (map second (get by-class nil)) [(str "test-ticks " (:seon.config.agent/test-ticks cfg) " " (:seon.config.agent/test-ticks-floor cfg) " " (:seon.config.agent/test-ticks-factor cfg))])))
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
  {:malli/schema [:=> [:cat :string :string] [:maybe :string]]}
  [path text]
  (if (= path cfg/shipped-file) (cfg/text-refusal text) (unread-text path text)))

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

(defn- commit-in-tree!
  "Commit files on the branch worktree wt has checked out, through that tree: each file written there, added to its own
  index and committed as git commit does, so its HEAD, index and files move together. Refused, committing nothing, when
  its index differs from its HEAD (a change staged there) or a file to write differs there from HEAD (an edit of the
  tree's own): {:value {:sha :files n}} or :seon.fx/io-error. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string :map :map] :map]}
  [wt env {:keys [files message allow-empty? modes]}]
  (let [paths (vec (sort (keys files)))
        staged? (not (zero? (:exit (git-run {} wt ["diff-index" "--cached" "--quiet" "HEAD" "--"] nil))))
        edited (when (seq paths)
                 (not-empty (str/trim (git! {} wt (into ["status" "--porcelain" "--untracked-files=all" "--"] paths) nil))))]
    (cond
      staged? (error #'seon.fx/git-commit :seon.fx/io-error (str "refused, nothing committed: " wt " has changes staged that its HEAD does not hold") {:path wt})
      edited (error #'seon.fx/git-commit :seon.fx/io-error (str "refused, nothing committed: files the write-back writes differ in " wt " from its HEAD: " (str/replace edited "\n" "; ")) {:path wt})
      :else
      (do (doseq [[p text] files :let [f (java.io.File. ^String wt ^String p)]]
            (.mkdirs (.getParentFile f))
            (spit f text))
          (when (seq paths) (git! {} wt (into ["add" "--"] paths) nil))
          (doseq [[p m] modes :when (and (= "100755" m) (contains? files p))]
            (git! {} wt ["update-index" "--chmod=+x" "--" p] nil))
          (git! env wt (cond-> ["commit" "-q" "--no-verify" "-m" message] allow-empty? (conj "--allow-empty")) nil)
          {:value {:sha (git! {} wt ["rev-parse" "HEAD"] nil) :files (count files)}}))))

(defn git-commit
  "Commit files ({relative path text}) on branch of the repository at dir, with message,
  through git's plumbing: blobs, a tree from the branch's own with the files replaced, a
  commit whose parent is the branch head (a branch not there yet starts at :base, else at dir's HEAD, the
  commit the running generation's tree checks out), and the ref moved only from that head, so no
  tree's files are touched. A branch a worktree has checked out (the standing root's jolt/merges in .worktrees/merges) is
  committed through that tree instead (commit-in-tree!): its files, index and HEAD move together, and a change of that
  tree's own refuses the commit; moved by plumbing, its HEAD left its index behind. See docs/source.md#commit-the-merges-branch.
  With :allow-empty? and no files it commits the branch's own tree again (an empty commit: a review,
  seon.doc/review-message). Each file is first read as its reader reads it (unreadable), and one that doesn't refuses the commit. :modes ({path git mode}) gives a file's mode, 100644 otherwise. Answers {:value {:sha :files n}} ({:sha nil :files 0} when there is nothing
  to write), or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir branch files message allow-empty? modes base]} :req}]
  (if (and (empty? files) (not allow-empty?))
    {:value {:sha nil :files 0}}
    (try
      (if-let [bad (seq (for [[p t] (sort files) :let [why (unreadable p t)] :when why] (str p ": " why)))]
       (error #'seon.fx/git-commit :seon.fx/io-error (str "refused, nothing committed: " (str/join "; " bad)) {:path dir})
       (let [ref (str "refs/heads/" branch)
            who (cfg/value :seon.config.source/committer-name) mail (cfg/value :seon.config.source/committer-email)
            id-env {"GIT_AUTHOR_NAME" who "GIT_AUTHOR_EMAIL" mail "GIT_COMMITTER_NAME" who "GIT_COMMITTER_EMAIL" mail}]
        ;; a branch a worktree has checked out moves with that tree's index and files, through the tree (commit-in-tree!):
        ;; moved by plumbing, the tree's HEAD left its index behind, each merged file a staged deletion
        (if-let [wt (checked-out-at dir ref)]
          (commit-in-tree! wt id-env {:files files :message message :allow-empty? allow-empty? :modes modes})
       (let [have? (some? (git-try dir ["rev-parse" "--verify" "--quiet" ref]))
            parent (git! {} dir ["rev-parse" "--verify" (if have? ref (or base "HEAD"))] nil)
            gp (git! {} dir ["rev-parse" "--git-path" (str "seon-index-" (lib/c-getpid))] nil)
            index (if (str/starts-with? gp "/") gp (str dir "/" gp))]
        (try
          (git! {"GIT_INDEX_FILE" index} dir ["read-tree" parent] nil)
          (doseq [[path text] (sort files)]
            (git! {"GIT_INDEX_FILE" index} dir ["update-index" "--add" "--cacheinfo" (str (get modes path "100644") "," (git! {} dir ["hash-object" "-w" "--stdin"] text) "," path)] nil))
          ;; Seon is the committer and author of its merges (the config's identity): a
          ;; root's HOME is its checkout's target/home, which holds no git identity
          (let [sha (git! id-env dir ["commit-tree" (git! {"GIT_INDEX_FILE" index} dir ["write-tree"] nil) "-p" parent "-m" message] nil)]
            ;; only from the head read above; a new branch only if none appeared meanwhile
            (git! {} dir ["update-ref" ref sha (if have? parent "0000000000000000000000000000000000000000")] nil)
            {:value {:sha sha :files (count files)}})
          (finally (.delete (java.io.File. ^String index))))))))
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
               (let [r (apply lib/sh "zsh" "-c" "\"$@\" >> \"$0\" 2>&1" log (concat clean args))]
                 (when-not (zero? (:exit r))
                   ;; the log's text travels with the error: a test that removes its scratch keeps the cause
                   (error #'seon.fx/build :seon.fx/io-error (str what " exited " (:exit r) "; its log: " log) {:path dir :log (slurp log)}))))]
    (.mkdirs (java.io.File. (str (cfg/value :seon.config.source/generations-dir))))
    (or (when-not (.exists (java.io.File. dir))
          (step "bin/worktree --at" [(str from "/bin/worktree") "--at" dir commit repo]))
        (step "bin/build seon" ["nice" "-n" "15" (str dir "/bin/build") "seon"])
        {:value {:commit commit :checkout dir :binary (str dir "/target/seon") :ms (profile/ms-since t0)}})))
(defn full-run-command
  "The runner of a run on a checkout's own build, as argv, its run written under out (its plan at out/tests.edn): the daily
  full run's, every test green or not (bin/test --all: the plan ignores the results already recorded, runs the process tests,
  and measures each cost proof at the sizes it states); with names, those tests (the fallback route's named run,
  run-on-checkout's :built?)."
  {:malli/schema [:function [:=> [:cat :string] [:vector :string]] [:=> [:cat :string [:maybe [:sequential :string]]] [:vector :string]]]}
  ([out] (full-run-command out nil))
  ([out names] (into ["nice" "-n" "15" "bin/test" "--prove" "tests" out] (or (seq names) ["--all"]))))

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
                                                                      :failures (vec (:failures r)) :writer (:writer r)}
                                                               (:cost r) (assoc :cost (:cost r)))))]
                            (assoc acc dg v)
                            acc))))
          {} cand)))

(defn reds-at
  "Of digests ({test digest}), the tests whose cached verdict at that digest is red, whoever ran it (a shell's run, a test
  service's item, the daily full run), each with its first failure: {test failure}; a test with no verdict at its digest is
  not red. What the judge's done-input and the merge gate read to tell a red that is main's (red at that digest without the
  task's change) from the task's own. Answers the :seon.fx/io-error a file gives, never a partial answer. dir: the cache's
  (:seon.config.proofs/results-dir unless given). See docs/merging.md#verdicts-are-a-cache."
  {:malli/schema [:function [:=> [:cat [:map-of :qualified-symbol :string]] [:or [:map-of :qualified-symbol :map] :seon.error/base]]
                  [:=> [:cat :string [:map-of :qualified-symbol :string]] [:or [:map-of :qualified-symbol :map] :seon.error/base]]]}
  ([digests] (reds-at (cfg/value :seon.config.proofs/results-dir) digests))
  ([dir digests]
   (let [r (if (seq digests) (stored-results dir digests) {})]
     (if (contract/error? r)
       r
       (into {} (for [[t dg] digests :let [v (get r dg)] :when (and v (not (proofs/green? v)))]
                  [t (or (first (:failures v)) {:message (str "red at digest " dg ", with no failure kept")})]))))))

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
  touches a verdict it reads) go first, greens and reds alike, until the cache is :seon.config.proofs/results-evict-percent
  under the bound, so the writes after it count nothing, and the ledger is set to the true size. Other writers' additions and removals make the ledger a lower or
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
                  (-> (do (when (= :pass st) (history-add! dir k (some-> (:ms r) long) (:ticks r))) acc) (update :recorded inc) (update :bytes + (:bytes (:value w))) (update :kept conj (:path (:value w)))))))
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
                    :let [g (sort (keep second (history dir t)))
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

(defn build-failure
  "What a checkout's failed build says, for whoever asked: the exception it names, where, and when the candidate's files ({path text})
  hold that place, the form written there up to its first blank line, so an agent reads which form of its own stops the build. A build
  that names no exception says so. See docs/merging.md#a-merge-runs-what-the-change-reaches."
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
    "the gate's build failed and named no exception"))

(defn runner-end
  "The last lines of what a runner said, joined, enough to hold what stopped it: its error, where, and the verdict lines after them. Three lost the error, leaving
  only where and the two verdicts."
  {:malli/schema [:=> [:cat :string] :string]}
  [text] (str/join " | " (take-last 6 (remove str/blank? (str/split-lines text)))))

(defn uncommitted
  "The files git sees changed in the checkout at dir, `git status --porcelain`'s lines, which no merge of the program carries: [] when none, or
  the error when git can't read it. See docs/source.md#commit-the-merges-branch."
  {:malli/schema [:=> [:cat :string] [:or [:vector :string] :seon.error/base]]}
  [dir]
  (let [r (git-run {} dir ["status" "--porcelain" "--ignore-submodules"] nil)]
    (if (zero? (:exit r))
      (vec (remove str/blank? (str/split-lines (:out r))))
      (error #'seon.fx/uncommitted :seon.fx/io-error (str "git status in " dir ": " (:err r)) {:path dir}))))

(defn gate-answer
  "The system's answer a to the gate's run of tests (seon.root/prove-tree!'s, the run-tests-forked shape: :reports, :red,
  :digests) as run-on-checkout answers it: {:results {test :pass|:fail} :failures {test [failure ...]} :digests}. A test
  the run never reached (it is answered at its first red) is in none, so its branch keeps it deferred and asks again: never a
  red it did not show. See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat :map [:sequential :qualified-symbol]] :map]}
  [a tests]
  (let [red (set (:red a))
        fails (proofs/failures a)
        passed (into #{} (keep #(when (= :pass (:type %)) (proofs/test-symbol (:var %)))) (:reports a))
        results (into {} (for [t tests :let [s (cond (red t) :fail (passed t) :pass)] :when s] [t s]))]
    {:results results
     :failures (into {} (for [[t s] results :when (= :fail s) :let [fs (get fails t)] :when (seq fs)] [t (vec fs)]))
     :digests (select-keys (:digests a) (keys results))}))

(defn- run-on-checkout
  "One run of the :tests effect (run-tests) on a checkout of the merge candidate: a detached worktree of repo at
  branch's head (bin/worktree --at, as a generation's), files ({relative path text}, the candidate's changed files)
  written over it, root's binary and kept stores cloned into it; the worktree removed after. The gate (tests, qualified
  names) is proved by the system through prove (seon.root/prove-tree!, mode :judge, named by exactly those tests): its
  process tests run as items on the checkout (docs/merging.md#a-session-proves-through-root), and its answer is read by
  gate-answer: {:value {:results {test status} :failures {test [failure ...]} :digests {test digest} :ms :log}}, or the
  run's typed error. With :built? (the fallback route: a branch's program judged on a binary built from its own source, never
  root's) the tree's binary is built first and the named tests run on it by the checkout's runner (full-run-command's names).
  With :all? (the daily full run, seon.prove's :full mode) the tree's binary is built first
  (bin/build seon) and the runner is full-run-command, tests every test its plan names, each read from the verdict
  cache (test-results), a run not all green keeping its logs beside its log; repo defaults to this checkout and branch
  to git's main (:seon.config.source/main); its failure is :seon.fx/io-error naming the step and its log."
  {:malli/schema [:=> [:cat [:maybe :seon.host/fn] :string :map] :map]}
  [prove sha {:keys [repo files tests all? built?]}]
  (let [t0 (profile/now-ns)
        dir (str (cfg/value :seon.config.source/tests-dir) "/" sha "-" (random-uuid))
        log (str dir ".tests.log")
        clean child-env-scrub
        ;; the run's own logs (seed.log, the shard logs) live in the worktree removed below: a run
        ;; that is not all green keeps them beside its log, the evidence of why (run 29's gate
        ;; seed threw, and its seed.log went with the worktree)
        kept (str dir ".run")
        green? (volatile! false)
        ;; its built products (the runtime, the native libraries) are its root's checkout's, the tree whose pins the root runs on, so a
        ;; candidate is judged on its own program and its root's runtime, never the main tree's (a worktree root on another Jolt pin
        ;; judged on main's: docs/merging.md#a-merge-runs-what-the-change-reaches)
        at (lib/sh (str (cfg/checkout) "/bin/worktree") "--at" dir sha repo (cfg/checkout))]
    (cond
      (not (zero? (:exit at))) (error #'seon.fx/run-on-checkout :seon.fx/io-error (str "bin/worktree --at exited " (:exit at) ": " (:err at)) {:path dir})
      :else
      (try
        (doseq [[p text] files]
          (let [w (write-file {:req {:path (str dir "/" p) :text text}})]
            (when (contract/error? w) (throw (ex-info (:seon.error/message w) w)))))
        ;; process tests run this tree's binary, so it is built from this tree first, the gate's
        ;; as the full run's: the worktree's target/ is a clone of another tree's, and its binary
        ;; that tree's (measured 2026-10-27: the first daily run's process tests ran on a stale one,
        ;; red for that alone); bin/build skips it when the tree matches, else ~20 s
        (let [slot (str (cfg/value :seon.config.source/tests-dir) "/main-build")
              kept-build ["seon" "seon.stamp" "seon.tree"]
              ;; a run with no candidate is main's tree, and its build is kept by that tree (bin/build reads the stamp): a red's second run
              ;; or the next day's reuses it, measured at 45 s against 2 s. One slot, the last tree built
              _ (when (and (empty? files) (.isFile (java.io.File. (str slot "/seon"))))
                  (doseq [n kept-build] (lib/sh "cp" "-c" (str slot "/" n) (str dir "/target/" n))))
              ;; the binary root runs, with its build record, over the clone bin/worktree --at took of the main tree's: what a gate's
              ;; runner judges current for the candidate's tree (seon.root/binary-current) is the binary that is running
              _ (when-not all?
                  (doseq [n ["seon" "seon.stamp" "seon.tree" "seon.program"]
                          :let [f (java.io.File. (str (cfg/checkout) "/target/" n))] :when (.isFile f)]
                    (lib/sh "cp" "-cf" (.getPath f) (str dir "/target/" n)))
                  ;; and root's kept stores, so the candidate's plan and seed import only what it changed: the plan's program
                  ;; store (a branch per tree, keyed by the importer: seon.test-store/kept-program-store) and the process
                  ;; tests' store (its source fact keyed by the indexing code, seon.root/kept-index!: a clone whose code moved
                  ;; is indexed again from nothing, so the clone decides the cost, never the answer); a store's data file
                  ;; (as seon.test-store/clone-process-store! takes it) and the plan's kept reach graph, keyed by its builder
                  (doseq [^java.io.File d (concat (.listFiles (java.io.File. (str (cfg/checkout) "/target")))
                                                  (.listFiles (java.io.File. (str (cfg/checkout) "/target/runs"))))
                          :let [n (.getName d) rel (if (= "runs" (.getName (.getParentFile d))) (str "runs/" n) n)]
                          :when (and (.isDirectory d) (re-matches #"(program-store|test-store)-[0-9a-f]+" n)
                                     (.isFile (java.io.File. d "data.mdb")))]
                    (let [to (str dir "/target/" rel)]
                      (seon.lib/remove-tree! to) (lib/sh "mkdir" "-p" to)
                      (doseq [n ["data.mdb" "reach-graph.fasl"] :when (.isFile (java.io.File. d n))]
                        (lib/sh "cp" "-c" (str (.getPath d) "/" n) (str to "/" n))))))
              ;; a gate's run builds only through its runner, when the binary is not current for the candidate's tree
              ;; (bin/test's plan: seon.root/binary-current, rule (a)); the full run builds main's tree here, into its kept slot
              b (if (or all? built?) (apply lib/sh (concat clean ["nice" "-n" "15" "bin/build" "seon"] [:dir dir])) {:exit 0})
              _ (when (and all? (empty? files) (zero? (:exit b)))
                  (.mkdirs (java.io.File. ^String slot))
                  (doseq [n kept-build] (lib/sh "cp" "-cf" (str dir "/target/" n) (str slot "/" n))))]
          (if (not (zero? (:exit b)))
            (do (spit log (str (:out b) (:err b)))
                (error #'seon.fx/run-on-checkout :seon.fx/io-error (str (build-failure (str (:out b) (:err b)) files) "; its log: " log) {:path dir}))
            (if-not (or all? built?)
              ;; the gate: the system proves the candidate's checkout (prove, seon.root/prove-tree!, which root binds to this
              ;; effect's :ctx), named by exactly the deferred tests; its process tests run as items on dir, by the :item owner
              (let [a (if prove
                        (prove {:checkout dir :tree (tree-id dir) :names (set tests) :mode :judge})
                        (error #'seon.fx/run-on-checkout :seon.fx/io-error "the gate has no system to prove its candidate on (the :tests effect's :ctx names no :prove-tree)" {:path dir}))]
                (if (contract/error? a)
                  a
                  (let [v (gate-answer a tests)]
                    (vreset! green? (= (set tests) (set (keep (fn [[t s]] (when (= :pass s) t)) (:results v)))))
                    {:value (assoc v :ms (profile/ms-since t0) :log log)})))
            ;; the full run's shards cache their verdicts in this checkout's verdict cache, named to them,
            ;; so the verdicts read below are the ones its run wrote
            (let [out (str dir "/target/prove/run")
                  r (apply lib/sh (concat clean [(str "SEON_RESULTS_DIR=" (cfg/value :seon.config.proofs/results-dir))]
                                         (full-run-command out (when-not all? (map str tests)))
                                         [:dir dir]))
                  plan (let [f (java.io.File. ^String out "tests.edn")] (when (.isFile f) (edn/read-string (slurp f))))
                  _ (spit log (str (:out r) (:err r)))
                  tests (vec (sort-by str (keys (:digests plan))))
                  found (when plan (stored-results (:digests plan)))]
              (cond
                ;; no plan (out/tests.edn): the runner never got to its tests (its build, its plan or its start failed): that is the
                ;; answer, with the end of what it said, never a run whose tests all read :error with no reason
                (nil? plan)
                (error #'seon.fx/run-on-checkout :seon.fx/io-error
                       (str "the test runner made no plan in " dir " (exit " (:exit r) "); its log: " log
                            " ends: " (runner-end (str (:err r) "\n" (:out r))))
                       {:path dir :no-plan true})
                (contract/error? found)
                found
                :else
                (let [rs (test-results plan found tests)]
                  (vreset! green? (every? #{:pass} (vals rs)))
                  {:value (cond-> {:results rs :ms (profile/ms-since t0) :log log
                                   :digests (select-keys (:digests plan) tests)
                                   ;; why each red is red: the failures its result recorded
                                   :failures (into {} (for [[t s] rs :when (not= :pass s)
                                                            :let [fs (some->> (get-in plan [:digests t]) found :failures not-empty vec)]
                                                            :when fs]
                                                        [t fs]))}
                            (not @green?) (assoc :kept kept))}))))))
        ;; the worktree's removal is recorded in the run's log: a failed one names what it left
        (finally (let [run (java.io.File. (str dir "/target/prove/run"))
                       mv (when (and (not @green?) (.isDirectory run)) (lib/sh "mv" (.getPath run) kept))
                       rm (lib/sh (str (cfg/checkout) "/bin/worktree") "--remove-at" dir)]
                   (spit log (str (when mv (str "\nkept the run's logs (not all green): " kept ": exit " (:exit mv) (:err mv)))
                                  "\nremove-at " dir ": exit " (:exit rm) "\n" (:out rm) (:err rm)) :append true)))))))

(defn- run-held
  "A named run on a checkout (run: run-on-checkout) of the tests with no verdict at this candidate, sha and files, so one candidate's green test runs
  once whoever asks: the owner is the one :tests worker, and the verdict cache holds each green under the candidate's digest
  (seon.fx/write-verdicts!, evicted as any verdict is), never a red, which runs again as a test's plan runs it: a digest does not see the machine. Nothing to run: no worktree and no build. A candidate with files that can make no plan
  holds that failure the same way and answers it again: its next ask would build the same files to the same refusal, each turn; any other failed run holds nothing.
  Answers as run-on-checkout does, for every test, with each one's held digest under :digests."
  {:malli/schema [:=> [:cat :seon.host/fn :string :map] :map]}
  [run sha {:keys [files tests] :as req}]
  (let [candidate (lib/digest (sedn/encode [sha (into (sorted-map) files)]))
        key-of #(str "gate-" (str/replace (str %) "/" "-"))
        digest-of #(lib/digest (str candidate " " %))
        no-plan (lib/digest (str candidate " no-plan"))
        held (stored-results (into {"gate-no-plan" no-plan} (for [t tests] [(key-of t) (digest-of t)])))]
    (cond
      (contract/error? held) held
      (get held no-plan) (error #'seon.fx/run-held :seon.fx/io-error (str "held from this candidate's last run: " (:message (first (:failures (get held no-plan))))) {:path (:repo req) :no-plan true})
      :else
      (let [todo (vec (remove #(get held (digest-of %)) tests))
            r (when (seq todo) (run sha (assoc req :tests todo)))]
        (if (contract/error? r)
          (do (when (and (seq files) (:no-plan r))
                ;; derived data: a refused write leaves it to run again
                (write-verdicts! {:id (str "gate " candidate)} {"gate-no-plan" {:digest no-plan :pass 0 :fail 0 :error 1}}
                                 {"gate-no-plan" [{:type :error :message (:seon.error/message r)}]}))
              r)
          (let [{:keys [results failures] :as v} (:value r)
                w (write-verdicts! {:id (str "gate " candidate)}
                                   ;; greens only, as a test's plan reuses (seon.proofs/plan): a digest does not see the machine, so a red from outside the program would stand until one moved
                                   (into {} (for [[t s] results :when (= :pass s)]
                                              [(key-of t) {:digest (digest-of t) :pass 1 :fail 0 :error 0}]))
                                   {})
                was (fn [t] (get held (digest-of t)))]
            (if (contract/error? w)
              w
              {:value (assoc v
                             ;; a test the run never reached and none held is in no result: its branch keeps it deferred
                             :results (into {} (for [t tests :let [s (or (get results t) (when (was t) (if (proofs/green? (was t)) :pass :fail)))] :when s] [t s]))
                             :failures (into (or failures {}) (for [t tests :let [fs (:failures (was t))] :when fs] [t (vec fs)]))
                             :digests (into {} (for [t tests] [t (digest-of t)]))
                             :ms (:ms v 0))})))))))

(defn run-tests
  "The :tests effect: tests on a checkout of the merge candidate (run-on-checkout), proved by the system through its :ctx's
  :prove-tree (seon.root/prove-tree!, which root binds), its answer {:value {:results :ms :log :digests :failures :kept}} or
  its declared error; a named run reuses what this candidate already answered (run-held). With :base (each test's digest as
  main held it at the task's base, seon.task's :seon.task/deferred), the answer's :main-red names its reds that are main's,
  not the change's (seon.proofs/main-red: the change does not reach the test, or main's verdict at that digest is red,
  read by reds-at), which the gate never holds the task on. See docs/merging.md#a-merge-runs-what-the-change-reaches."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [repo branch all?] :as req} :req {:keys [prove-tree]} :ctx}]
  (let [repo (or repo (when all? (str (cfg/checkout))))
        branch (or branch (when all? (cfg/value :seon.config.source/main)))
        req (assoc req :repo repo :branch branch)
        ;; the branch's commit, or the error the asker gets: no such branch is never a run at an empty sha
        sha (try (or (branch-head repo branch)
                     (error #'seon.fx/run-tests :seon.fx/io-error (str "no commit of branch " branch " in " repo) {:path repo}))
                 (catch Throwable e (error #'seon.fx/run-tests :seon.fx/io-error (step/error-msg e) {:path repo})))]
    (cond
      (contract/error? sha) sha
      (or all? (:built? req)) (run-on-checkout nil sha req)
      :else
      (let [r (run-held (partial run-on-checkout prove-tree) sha req)
            {:keys [results digests]} (:value r)
            base (into {} (filter val) (:base req))
            ;; main's verdict at each red test's base digest: the one reader (reds-at), an io-error excusing nothing
            found (when (seq base)
                    (let [at (reds-at (into {} (for [[t s] results :let [b (get base t)] :when (and b (not= :pass s))] [t b])))]
                      (when-not (contract/error? at) (into {} (for [[t f] at] [(get base t) {:pass 0 :fail 1 :error 0 :failures [f]}])))))]
        (if (and (seq base) (map? results))
          (assoc-in r [:value :main-red] (proofs/main-red results (or digests {}) base (or found {})))
          r)))))

;; ---- a session's items (Flow step 4) -------------------------------------------------

(defn- item-files!
  "The files an item's run reads in dir, as bin/prove's plan writes them: a :process item's shard of one
  (shard-0.edn, as test_runner's plan deals one), a :step item's steps, steps.edn, steps-binary (empty: the step
  needs no target/seon) and step-bounds (its measured ms). See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :string :map] :nil]}
  [dir {:keys [kind tree name digest binary? bound]}]
  (case kind
    :process (spit (str dir "/shard-0.edn") (sedn/encode {:tests [name] :mg {} :digests {name digest} :tree tree :rerun #{}}))
    :step (do (spit (str dir "/steps") (str name "\n"))
              (spit (str dir "/steps.edn") (sedn/encode {:tree tree :digests {name digest}}))
              (when (false? binary?) (spit (str dir "/steps-binary") ""))
              (when bound (spit (str dir "/step-bounds") (str name " " bound "\n"))))
    (:build :seed) nil)
  nil)

(defn- clone-program-store!
  "The checkout's plan program store (its own :seon.config.proofs/program-store, each base-<digest> under it, as kept-store!
  keys them) cloned under base, an item's own (cp -c, APFS copy-on-write, as each check step clones the seeded store): items
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
            :when (and (.isDirectory d) (re-matches (re-pattern (str "\\Q" stem "\\E-[0-9a-f]{8}")) (.getName d)))
            :let [to (str base (subs (.getName d) (count stem)))]]
      (lib/sh "mkdir" "-p" to)
      (doseq [n ["data.mdb" "reach-graph.fasl"] :when (.isFile (java.io.File. d ^String n))]
        (lib/sh "cp" "-c" (str (.getPath d) "/" n) (str to "/" n))))
    base))

(defn run-item
  "The :item effect: one item of a session's prove that the test service (seon.prove) granted, run on the session's
  checkout at tree, niced: :build is bin/build seon there; :seed brings the checkout's program store to the tree and, :binary?,
  seeds its check store fresh, once a tree (each binary step takes a clone of it, as bin/check's steps do); :process is one test as a shard of one (bin/test --shard,
  whose runner stores its verdict; with a :bound, through bin/bounded, and past it the :signal :overran a step's is); :step is one check step (bin/check --steps, then bin/test --store-steps, which
  stores it green at its digest). Its answer {:value {:pass :fail :error :failures :ms}}, the verdict read back from the
  cache by its digest, so an item asked again is reused there; a step bin/check ended past its bound (its DIR/overran) is
  :signal :overran, never a verdict. A checkout whose files are no longer tree is refused (:seon.fx/io-error, :tree-moved): a verdict would be
  stored under a digest of files that are not there. A green already stored is answered at once, :reused, with no process
  started. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [kind tree checkout name digest bound] :as req} :req}]
  (let [t0 (profile/now-ns)
        here (tree-id checkout)
        ;; a green already stored at its digest is the answer: no process started (2.3 s a test's runner, only to reuse it)
        held (when-not (#{:build :seed} kind) (stored-results {name digest}))]
    (cond
      (not= here tree)
      (error #'seon.fx/run-item :seon.fx/io-error (str "the checkout " checkout " is tree " here ", not the item's " tree ": its files moved since the plan")
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
            pstore (when (#{:process :step} kind) (clone-program-store! checkout (str dir "/program-store")))
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
                                            "HOME=$PWD/target/home JOLT_PWD=$PWD target/jolt/jolt -A:test -e \"" prog "\"")))
                ;; with a :bound (seon.prove's plan-items, from its measured ms) it runs through bin/bounded: past the bound its
                ;; evidence is kept and its whole process group ended, never left to hold a worker
                :process (apply sh (concat (when bound ["bin/bounded" (str bound) (str dir "/evidence")])
                                           ["bin/test" "--shard" (str dir "/shard-0.edn") "--set" (str ":seon.config.proofs/program-store=" (sedn/encode pstore))]))
                :step (let [c (sh "bin/check" "--steps" dir)] (sh "bin/test" "--store-steps" dir) c))
            ms (profile/ms-since t0)
            stored (when-not (#{:build :seed} kind) (stored-results {name digest}))
            _ (when pstore (doseq [^java.io.File d (some-> (java.io.File. ^String dir) .listFiles seq)
                                   :when (str/starts-with? (.getName d) "program-store")]
                             (lib/remove-tree! (.getPath d))))
            v (when (map? stored) (get stored digest))
            ;; bin/check's own judgement of the step's bound, a signal it writes (DIR/overran): this measures nothing itself
            ;; and bin/bounded's, a process item's (DIR/evidence/overran)
            overran? (case kind
                       :step (let [f (java.io.File. (str dir "/overran"))]
                               (and (.isFile f) (contains? (set (str/split-lines (slurp f))) name)))
                       :process (.isFile (java.io.File. (str dir "/evidence/overran")))
                       false)]
        (cond
          (contract/error? stored) stored
          (= :build kind) (if (zero? (:exit r))
                            {:value {:pass 1 :fail 0 :error 0 :failures [] :ms ms}}
                            (error #'seon.fx/run-item :seon.fx/io-error (str (build-failure (str (:out r) (:err r)) nil) "; its log: " log) {:path checkout}))
          (= :seed kind) (if (and (zero? (:exit r)) (or (not (:binary? req)) (.isFile (java.io.File. (str store "/data.mdb")))))
                           {:value {:pass 1 :fail 0 :error 0 :failures [] :ms ms}}
                           (error #'seon.fx/run-item :seon.fx/io-error (str "the seed of " store " failed (exit " (:exit r) "): " (runner-end (str (:out r) (:err r))) "; its log: " log) {:path checkout}))
          ;; a step that ended red with no verdict stored, over its bound: the overrun is a signal, its verdict none
          (and overran? (not (proofs/green? v)))
          {:value {:signal :overran :ms ms}}
          v {:value (assoc (select-keys v [:pass :fail :error :failures]) :ms ms)}
          (= :step kind) {:value {:pass 0 :fail 1 :error 0 :failures [{:type :fail :message (str "bin/check --steps " name ": " (runner-end (str (:out r) (:err r))))}] :ms ms}}
          :else (error #'seon.fx/run-item :seon.fx/io-error (str "the shard stored no verdict for " name " (exit " (:exit r) "): " (runner-end (str (:out r) (:err r))) "; its log: " log)
                       {:path checkout}))))))

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
          :else (do (reply! o (:dest m) d)
                    (recur (update st :queue dissoc qk)))))
      st)))

(defn- bound
  "The owner's queue holds at most :seon.config.fx/buf requests of the agents; at the bound it stops reading :in (Flow's
  input filter), so agent procs park on :in: back-pressure to the asking agent. :ask (root's) and :done are always read."
  {:malli/schema [:=> [:cat :map] :map]}
  [st] (assoc st ::flow/input-filter (if (>= (count (:queue st)) (cfg/value :seon.config.fx/buf)) #(not= :in %) identity)))

(defn owner-proc
  "The owner of one effect kind. In :in (the agent procs' requests {:req :dest :prio
  :budget :budget-key :agent}, addressed by id; :seon.config.fx/buf), :ask (root's own asks, the api's: the same requests,
  never held back by the bound, since the api that puts them answers every asker and must never wait on one owner; they are
  bounded where they start: the test service's workers, the reconciler's flights), :done (the workers'
  outcomes). Out :to-workers (its performers' shared channel), :out (the api's :back:
  {:dest :r})."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:kind "the effect kind" :workers "performers, its concurrency"
                :rate "tokens per second the provider admits (nil: no bucket)"
                :batch "the most requests one worker step takes from the queue (nil: one, as itself)"
                :close? "the kind has a :close: when the queue is empty and nothing is in flight, its worker is told {:idle true}"
                :tick "its clock's channel (external in-port, with :rate): a :tick when the bucket refilled"}
       :ins {:in "requests, addressed [:fx/<kind> :in] by any proc" :ask "root's own requests, never held back by the bound" :done "its workers' outcomes"} :outs {}
       ;; admission and routing only: never blocks, never computes long
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:kind :workers :busy :done]) (assoc :queued (count (:queue st)) :spent (:spent st))))})
  ([args] (assoc (select-keys args [:kind :workers :batch :close?]) :busy 0 :done 0 :seq 0 :queue (sorted-map) :spent {}
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
         (:in :ask) (let [n (inc (:seq st))]
               [(bound (dispatch (-> st (assoc :seq n) (assoc-in [:queue [(- (:prio m 0)) n]] (assoc m :in-ns (profile/now-ns)))) o)) @o])
         :done (let [st (bound (dispatch (reduce #(outcome %1 o %2) (update st :busy dec) (or (:batch m) [m])) o))]
                 ;; the kind's one worker holds a host object that ends when the work does (:close): the queue
                 ;; empty and nothing in flight is that event, told to the worker as the next item, unless an
                 ;; answered request said more of its own were coming (:more in its :req: main's backlog, whose next
                 ;; round is asked once this one's vectors are written)
                 (when (and (:close? st) (zero? (:busy st)) (empty? (:queue st)) (not-any? #(get-in % [:request :req :more]) (or (:batch m) [m])))
                   (step/out! o :to-workers {:idle true}))
                 [st @o])
         :tick (do (step/fired! (:tick-timer st)) [(bound (dispatch st o)) @o]))))))

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
  connection is let go however the reading stops. See docs/flow.md#effects-one-owner-and-its-workers-per-kind."
  {:malli/schema [:=> [:cat :seon.host/fn :seon.host/chan [:maybe :seon.host/fn]] :seon.host/thread]}
  [pull lines shut]
  (doto (Thread. ^Runnable
                 (fn []
                   (let [tail (java.io.ByteArrayOutputStream.)]
                     (try
                       (loop []
                         (let [b (pull)]
                           (cond (nil? b) (when (or (zero? (.size tail)) (a/>!! lines {:line (String. (.toByteArray tail) "UTF-8")}))
                                            (a/>!! lines {:end true}))
                                 (contract/error? b) (a/>!! lines {:error b})
                                 (put-lines! b tail lines) (recur))))
                       (finally (when shut (shut))))))
                 "seon-fx-stream")
    (.setDaemon true) (.start)))

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
            done (done-of (get-in st [:call :m]) r (get-in st [:call :t0]) (profile/now-ns))]
        [(-> st (dissoc :call) (assoc ::flow/input-filter (constantly true)) (update :n inc))
         (merge {[(:owner st) :done] [done]} (stream-out (when (sequential? ends) ends)))]))))

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
     (= :lines in) (stream-step st m)
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
           done (mapv #(done-of %1 %2 t0 t1) ms rs)]
       (if-let [sm (and (not (:batch m)) (:stream (first rs)))]
         ;; a streaming perform: the call is read a line at a time on :lines until it ends, and the worker reads nothing else meanwhile
         (do (read-lines! (:pull sm) (:lines st) (:shut sm))
             [(assoc st :call (assoc (select-keys sm [:step :final :close :shut]) :m m :s (:init sm) :t0 t0)
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
                          :chan-opts {:in {:buf-or-n (cfg/value :seon.config.fx/buf)} :ask {:buf-or-n (cfg/value :seon.config.fx/buf)} :done {:buf-or-n (* 2 workers)}}
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
        _ (lib/sh "zsh" "-c" script "evidence" dir (str pid))
        _ (some->> (lib/tail-bytes stderr (cfg/value :seon.config.agent/stall-fibers-bytes)) (spit (str dir "/fibers.txt")))
        files (into {} (for [[k f] {:sample "sample.txt" :lsof "lsof.txt" :ps "ps.txt" :fibers "fibers.txt"}
                             :let [path (str dir "/" f) file (java.io.File. path)]
                             :when (pos? (.length file))]
                         [k path]))]
    (if (seq files)
      {:value {:dir dir :files files}}
      (error #'seon.fx/stall-evidence :seon.fx/io-error (str "no evidence of process " pid " was captured in " dir ": it is gone") {:path dir}))))

