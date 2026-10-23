(ns seon.fx
  "Effects (session FX1, notes/archive/option3-FX1-effects.md). Every outside effect kind has ONE
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
            [seon.edn :as sedn] [seon.error :as err] [clojure.edn :as edn]))

(defn- error
  "An effect's declared failure, as the one error map (its variant registered in
  seon.problems.edn), raised by seon.fx/call."
  {:malli/schema [:=> [:cat :qualified-keyword :string :map] :seon.error/base]}
  [type message data]
  (contract/error type 'seon.fx/call message data))

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
                      (error :seon.fx/io-error (str "staging " tmp ": " (or (ex-message e) (str (type e)))) {:path path :tmp tmp})))]
    (or staged
        (let [[r errno] (lib/c-rename tmp path)]
          (if (zero? r)
            {:value {:bytes (.length (java.io.File. ^String path)) :path path}}
            (do (.delete (java.io.File. tmp))
                (error :seon.fx/io-error (str "rename " tmp " -> " path ": " (ffi/errno-message errno)) {:path path :tmp tmp :errno errno})))))))

;; Seon's own source: the program read from the tree a generation was built from, and
;; main's merged forms committed for the next one (seon.source). Never the working tree:
;; reading is read-only, and a commit is git's plumbing on a ref, so no checkout moves.

(declare inputs)

(defn read-source
  "The text of every .clj and .edn file under root's dirs (relative paths, e.g.
  src/seon/fx.clj, resources/seon/schemas/seon.facts.edn), sorted by path, the commit root's
  HEAD names and the revision it pins each reference-code submodule at (its gitlink), both
  absent when root is no git repository (git answers none).
  With :binary-tree (the file bin/build wrote beside the running binary), also :inputs (the
  tree's, inputs) and :binary (the tree id that file holds, or none), so root records the
  inputs only for a binary built from this very tree.
  Answers {:value {path text} :commit sha :pins {path sha} :inputs :binary} or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root dirs binary-tree]} :req}]
  (try
    (let [base (.getCanonicalPath (java.io.File. ^String root))
          head (sh/sh "git" "-C" base "rev-parse" "--verify" "--quiet" "HEAD")]
      (cond-> {:value (into (sorted-map)
                        (for [d dirs
                              f (file-seq (java.io.File. (str base "/" d)))
                              :let [p (.getCanonicalPath ^java.io.File f)]
                              :when (and (.isFile ^java.io.File f) (or (.endsWith p ".clj") (.endsWith p ".edn")))]
                          [(subs p (inc (count base))) (slurp f)]))}
        (zero? (:exit head)) (assoc :commit (str/trim (:out head))
                                    :pins (into {}
                                                (for [[_ sha p] (re-seq #"(?m)^160000 ([0-9a-f]{40}) \d+\t(\S+)$"
                                                                        (:out (sh/sh "git" "-C" base "ls-files" "-s" "--" "reference-code")))]
                                                  [p sha])))
        (and binary-tree (zero? (:exit head))) (assoc :inputs (inputs base)
                           :binary (let [f (java.io.File. ^String binary-tree)] (when (.isFile f) (str/trim (slurp f)))))))
    (catch Throwable e (error :seon.fx/io-error (or (ex-message e) (str (type e))) {:path root}))))

(defn- git!
  "git in dir with args, under the environment variables env adds ({name value}), its
  trimmed output; a failure throws its stderr (git-commit answers it as :seon.fx/io-error)."
  {:malli/schema [:=> [:cat [:map-of :string :string] :string [:sequential :string] [:maybe :string]] :string]}
  [env dir args in]
  (let [cmd (concat (when (seq env) (cons "env" (for [[k v] (sort env)] (str k "=" v)))) ["git" "-C" dir] args)
        r (if in (apply sh/sh (concat cmd [:in in])) (apply sh/sh cmd))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (first args) ": " (str/trim (str (:err r) (:out r)))) {})))
    (str/trim (:out r))))

(defn- env-input?
  "Is the file at relative path p an input of every test: everything git tracks or would
  track but the source program facts carry (.clj under src and test), task files (data
  root loads, no test's code) and prose (.md)?"
  {:malli/schema [:=> [:cat :string] :boolean]}
  [p]
  (not (or (str/ends-with? p ".md")
           (str/starts-with? p "resources/seon/tasks/")
           (and (str/ends-with? p ".clj") (or (str/starts-with? p "src/") (str/starts-with? p "test/"))))))

(defn config-input
  "The config in force as a test reads it, cfg (seon.config/config: the shipped file, this
  checkout's config/local.edn, the #env values, the profile), as text: the checkout's own path
  written as <checkout>, and the keys that say where a process runs left out (their schemas'
  :seon.config/place: its ports, main, store, runs, source tree and results directory: each
  checkout's and each root's own by design, while :tree covers the source's content). What a
  result depends on that git does not see, so an agent's root and a checkout on one tree key
  their results alike (docs/merging.md)."
  {:malli/schema [:=> [:cat :map :string] :string]}
  [cfg checkout]
  (str/replace (sedn/encode (into (sorted-map) (remove (fn [[k]] (get-in cfg/schema-forms [k 1 :seon.config/place]))) cfg))
               checkout "<checkout>"))

(defn declared-refs
  "{seon.system/read-decl #{symbol}}: every qualified symbol the system declaration at p names
  (its procs' step-fns, its effects' performers), which root resolves by name at boot: reach
  edges of the function that reads the declaration (seon.program/reach-digests' :data-refs),
  so a test booting root reaches its procs. None when there is no file at p."
  {:malli/schema [:=> [:cat :string] [:map-of :qualified-symbol [:set :qualified-symbol]]]}
  [p]
  (let [f (java.io.File. ^String p)
        decl (when (.isFile f) (edn/read-string {:default tagged-literal} (slurp f)))]
    {'seon.system/read-decl (set (filter qualified-symbol? (tree-seq #(or (coll? %) (tagged-literal? %))
                                                                     #(if (tagged-literal? %) [(:form %)] (seq %)) decl)))}))

(defn tree-id
  "git's id of the tree at root as it is on disk: HEAD's tree (none before a first commit)
  with every changed and untracked unignored file added, in an index of its own (the
  repository's index untouched). bin/build records the same id for the tree it builds
  (target/seon.tree), so a root can tell whether its binary is built from its source."
  {:malli/schema [:=> [:cat :string] :string]}
  [root]
  (let [idx (doto (java.io.File/createTempFile "seon-tree" ".index") (.delete))
        env {"GIT_INDEX_FILE" (.getPath idx)}]
    (try
      (when (zero? (:exit (sh/sh "git" "-C" root "rev-parse" "--verify" "--quiet" "HEAD"))) (git! env root ["read-tree" "HEAD"] nil))
      (git! env root ["add" "-A"] nil)
      (git! env root ["write-tree"] nil)
      (finally (.delete idx)))))

(defn pin
  "What a build or a test reads from the checkout at dir (a submodule), by content: its HEAD
  when its files are HEAD's, else HEAD+ the tree-id of its files as they are, so an
  uncommitted edit in a dependency is an input like a committed one (bin/build's pin computes
  the same, and builds it)."
  {:malli/schema [:=> [:cat :string] :string]}
  [dir]
  (let [head (git! {} dir ["rev-parse" "HEAD"] nil)]
    (if (str/blank? (git! {} dir ["status" "--porcelain" "-uall" "--ignore-submodules=all"] nil))
      head
      (str head "+" (tree-id dir)))))

(defn inputs
  "The one producer of the checkout at root's inputs that have no program facts, for bin/test
  and for root's :source read (read-source, which records them for its agents):
  {:tree (tree-id: every file and pin, for a test reading files or running the binary) :env
  (every test's: the tree's env-input? entries, every submodule's pin (its files as they are),
  Jolt's included, and
  the config in force, config-input) :data-refs (declared-refs of its system.edn)}, by
  relative paths and content, so the same content at another path is the same inputs."
  {:malli/schema [:function [:=> [:cat :string] :seon.program/inputs] [:=> [:cat :string :map :string] :seon.program/inputs]]}
  ([root] (inputs root cfg/config (cfg/checkout)))
  ([root cfg checkout]
   (let [tree (tree-id root)
         entries (for [l (str/split-lines (git! {} root ["ls-tree" "-r" tree] nil)) :let [[m p] (str/split l #"\t" 2)] :when (env-input? p)]
                   ;; a submodule by its pin: its files as they are, an uncommitted edit included
                   ;; (a clean one's line is its gitlink's, as before; one not checked out has no files to read)
                   (let [d (str root "/" p)
                         pn (when (and (str/starts-with? m "160000") (.exists (java.io.File. d ".git"))) (pin d))]
                     (if (and pn (str/includes? pn "+")) (str l " " pn) l)))]
     {:tree tree
      :env (err/digest (str/join "\n" (concat entries [(config-input cfg checkout)])))
      :data-refs (declared-refs (str root "/resources/seon/system.edn"))})))

(defn git-commit
  "Commit files ({relative path text}) on branch of the repository at dir, with message,
  through git's plumbing: blobs, a tree from the branch's own with the files replaced, a
  commit whose parent is the branch head (a branch not there yet starts at dir's HEAD, the
  commit the running generation's tree checks out), and the ref moved only from that head. No
  working tree or checkout is touched, so main and every worktree's files stay as they are.
  Answers {:value {:sha :files n}} ({:sha nil :files 0} when there is nothing to write),
  or :seon.fx/io-error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [dir branch files message]} :req}]
  (if (empty? files)
    {:value {:sha nil :files 0}}
    (try
      (let [ref (str "refs/heads/" branch)
            have? (zero? (:exit (sh/sh "git" "-C" dir "rev-parse" "--verify" "--quiet" ref)))
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
      (catch Throwable e (error :seon.fx/io-error (or (ex-message e) (str (type e))) {:path dir})))))

;; ---- the wiki's history: what each section was last written against -----------------------

(defn- blame
  "{final line number {:sha :time}} of the file at path in the repository at root, from git blame
  --porcelain (an uncommitted line is sha 000...)."
  {:malli/schema [:=> [:cat :string :string] [:map-of :int :map]]}
  [root path]
  (let [out (git! {} root ["blame" "--porcelain" "--" path] nil)]
    (loop [ls (str/split-lines out) cur nil times {} acc {}]
      (if-let [l (first ls)]
        (if-let [[_ sha line] (re-matches #"([0-9a-f]{40}) \d+ (\d+)(?: \d+)?" l)]
          (recur (rest ls) {:sha sha :line (parse-long line)} times (assoc acc (parse-long line) {:sha sha}))
          (if-let [[_ t] (re-matches #"committer-time (\d+)" l)]
            (recur (rest ls) cur (assoc times (:sha cur) (parse-long t)) acc)
            (recur (rest ls) cur times acc)))
        (update-vals acc (fn [{:keys [sha]}] {:sha sha :time (get times sha 0)}))))))

(defn wiki-history
  "For each requested wiki section ({:path :address :lines [from to] :files {name {:file :ns}}}) the
  commit that last changed its lines (git blame of its page, the newest committer time among its
  lines; nil when a line is not committed yet) and, for each (commit, file) the sections' cited
  vars live in, the file's text at that commit (nil: the file did not exist then), read from
  git once. Answers {:value {:sections {address {:commit sha}} :files {[sha file] text-or-nil}
  :errors {address message}}}: a page git cannot blame (not a git checkout, an untracked file)
  names its sections under :errors, never an exception; or :seon.fx/io-error. The wiki's loader
  records a section whose history is unread as of today and says so (seon.docs/baseline-reads)."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [root sections]} :req}]
  (try
    (let [by-path (group-by :path sections)
          blamed (into {} (for [p (keys by-path)]
                            [p (try (blame root p) (catch Throwable e {:error (or (ex-message e) (str (type e)))}))]))
          infos (into {} (for [{:keys [path address lines]} sections
                               :let [b (blamed path)]]
                           [address (if (:error b)
                                      {:error (:error b)}
                                      (let [ls (keep b (range (first lines) (inc (second lines))))
                                            newest (when (seq ls) (apply max-key :time ls))]
                                        {:commit (when (and newest (not (re-matches #"0+" (:sha newest)))) (:sha newest))}))]))
          needed (distinct (for [{:keys [address files]} sections :let [c (:commit (infos address))] :when c [_ {:keys [file]}] files] [c file]))
          texts (into {} (for [[c f] needed]
                           [[c f] (let [r (sh/sh "git" "-C" root "show" (str c ":" f))] (when (zero? (:exit r)) (:out r)))]))]
      {:value {:sections infos :files texts}})
    (catch Throwable e (error :seon.fx/io-error (or (ex-message e) (str (type e))) {:path root}))))

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
        (error :seon.fx/unresolved (or (ex-message e) (str (type e)))
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
        ;; a process of the tree's own: bin/env derives these from the tree it is in
        clean ["env" "-u" "SEON_CHECKOUT" "-u" "JOLT_LMDB_LIB" "-u" "JOLT_PWD" "-u" "HOME"]
        step (fn [what args]
               (let [r (apply sh/sh "zsh" "-c" "\"$@\" >> \"$0\" 2>&1" log (concat clean args))]
                 (when-not (zero? (:exit r))
                   (error :seon.fx/io-error (str what " exited " (:exit r) "; its log: " log) {:path dir}))))]
    (.mkdirs (java.io.File. (str (cfg/value :seon.config.source/generations-dir))))
    (or (when-not (.exists (java.io.File. dir))
          (step "bin/worktree --at" [(str from "/bin/worktree") "--at" dir commit repo]))
        (step "bin/build seon" ["nice" "-n" "15" (str dir "/bin/build") "seon"])
        {:value {:commit commit :checkout dir :binary (str dir "/target/seon") :ms (profile/ms-since t0)}})))
(defn test-command
  "The runner the :tests effect calls in a checkout, as argv, for namespaces nss: prove's one
  entry (land12, root 2026-10-22), each named namespace's tests with no green at their reach
  digest run, the rest reused."
  {:malli/schema [:=> [:cat [:sequential :symbol]] [:vector :string]]}
  [nss] (into ["nice" "-n" "15" "bin/prove" "--tests"] (map str nss)))

(defn test-results
  "Each of tests' status from prove's output out and its exit status: :fail or :error where a
  RED line names it (its counts say which), :pass only when the run reached its tests, exited
  0 and named it in no RED line (it ran green, or was green at its digest), and :error for
  every other one: a run that never reached its tests (its plan or its build failed), or that
  exited non-zero without naming it (a check step, a crash after the shard), says nothing of it
  passing."
  {:malli/schema [:=> [:cat :string :int [:sequential :qualified-symbol]] [:map-of :qualified-symbol [:enum :pass :fail :error]]]}
  [out exit tests]
  (let [red (into {} (for [[_ t counts] (re-seq #"(?m)^RED (\S+) (\{[^\n]*\})" out)]
                       [(symbol t) (if (pos? (or (:error (edn/read-string counts)) 0)) :error :fail)]))
        reached? (boolean (re-find #"(?m)^(SHARD |PROVE OK|prove: .* 0 to run)" out))]
    (into {} (for [t tests] [t (or (red t) (if (and reached? (zero? exit)) :pass :error))]))))

(defn run-tests
  "The :tests effect: tests (qualified names) run on a checkout of the merge candidate: a
  detached worktree of repo at branch's head (bin/worktree --at, as a generation's), files
  ({relative path text}, the candidate's changed files) written over it, then the runner
  (test-command) over the tests' namespaces there, niced, in that tree's own environment;
  the worktree removed after. Answers {:value {:results {test status} :ms :log}}, or
  :seon.fx/io-error naming the step and its log."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [repo branch files tests]} :req}]
  (let [t0 (profile/now-ns)
        sha (str/trim (:out (sh/sh "git" "-C" repo "rev-parse" branch)))
        dir (str (cfg/value :seon.config.source/tests-dir) "/" sha "-" (random-uuid))
        log (str dir ".tests.log")
        clean ["env" "-u" "SEON_CHECKOUT" "-u" "JOLT_LMDB_LIB" "-u" "JOLT_PWD" "-u" "HOME"]
        at (sh/sh (str (cfg/checkout) "/bin/worktree") "--at" dir sha repo)]
    (if-not (zero? (:exit at))
      (error :seon.fx/io-error (str "bin/worktree --at exited " (:exit at) ": " (:err at)) {:path dir})
      (try
        (doseq [[p text] files] (let [f (java.io.File. dir p)] (.mkdirs (.getParentFile f)) (spit f text)))
        (let [r (apply sh/sh (concat clean (test-command (sort (distinct (map (comp symbol namespace) tests)))) [:dir dir]))
              out (str (:out r) (:err r))]
          (spit log out)
          {:value {:results (test-results out (:exit r) tests) :ms (profile/ms-since t0) :log log}})
        ;; the worktree's removal is recorded in the run's log: a failed one names what it left
        (finally (let [rm (sh/sh (str (cfg/checkout) "/bin/worktree") "--remove-at" dir)]
                   (spit log (str "\nremove-at " dir ": exit " (:exit rm) "\n" (:out rm) (:err rm)) :append true)))))))

;; ---- the owner -----------------------------------------------------------------------

(defn refill
  "The owner's token bucket at now-ms (session R3): tokens grow at :per-ms up to :cap since :at.
  A bucket is the provider's rate as data: tokens per second, set from its config key and halved by
  a rate-limit outcome."
  {:malli/schema [:=> [:cat :seon.fx/bucket :int] :seon.fx/bucket]}
  [{:keys [tokens cap per-ms at] :as b} now-ms]
  (assoc b :tokens (min cap (+ tokens (* per-ms (max 0 (- now-ms at))))) :at (max at now-ms)))

(defn- admit
  "What the owner does with a queued request at now-ms: :run, :wait (no free worker, or its
  bucket holds fewer tokens than the request's estimate :est: a :tick or a worker's :done
  dispatches again), or the error (the task spent its :budget; spend is this proc's live
  counter, keyed by :budget-key)."
  {:malli/schema [:=> [:cat :map :map :int] [:or [:enum :run :wait] :map]]}
  [st {:keys [budget budget-key est]} now-ms]
  (cond
    (and budget (>= (get-in st [:spent budget-key] 0) budget))
    (error :seon.fx/budget-spent "the request's budget is spent" {:budget budget :spent (get-in st [:spent budget-key])})
    (>= (:busy st) (:workers st)) :wait
    (and (:bucket st) (< (:tokens (refill (:bucket st) now-ms)) (or est 0))) :wait
    :else :run))

(defn- settle
  "The owner at a worker's outcome m: the worker is free; the task's spend grows by the actual
  cost; the bucket gets back (or pays) the difference from the estimate; a rate-limit outcome
  (the provider's 429, the named failure point of this edge) halves the bucket's rate, and each
  other outcome raises it by a twentieth back toward the configured rate (AIMD, TCP's congestion rule)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [st {:keys [budget-key cost est outcome]}]
  (let [limited? (= :seon.model/rate-limited (:seon.error/type outcome))]
    (cond-> (-> st (update :busy dec) (update :done inc))
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

(defn- wake-later
  "The clock (session R3's :tick): when the head request waits on the bucket alone (a worker is
  free), one :tick is due on the owner's :tick port once the bucket refills to its estimate.
  Answers st, marked :tick-due until that tick arrives."
  {:malli/schema [:=> [:cat :map :map :int] :map]}
  [st m now]
  (if (and (:bucket st) (:tick st) (not (:tick-due st)) (< (:busy st) (:workers st)))
    (let [b (refill (:bucket st) now)
          ms (long (Math/ceil (/ (max 1 (- (or (:est m) 0) (:tokens b))) (:per-ms b))))]
      (a/take! (a/timeout ms) (fn [_] (a/put! (:tick st) {})))
      (assoc st :tick-due true))
    st))

(defn- dispatch
  "Take queued requests in priority order while workers are free; a refused one is answered
  at once. Answers st."
  {:malli/schema [:=> [:cat :map :seon.host/volatile] :map]}
  [st o]
  (loop [st st]
    (if-let [[qk m] (first (:queue st))]
      (let [now (quot (profile/now-ns) 1000000)
            d (admit st m now)]
        (cond
          (= :wait d) (wake-later st m now)
          (= :run d) (do (step/out! o :to-workers (assoc m :taken-ns (profile/now-ns) ::qk qk))
                         (recur (cond-> (-> st (update :queue dissoc qk) (update :busy inc))
                                  (:bucket st) (update :bucket #(update (refill % now) :tokens - (or (:est m) 0))))))
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
                :tick "its clock's channel (external in-port, with :rate): a :tick when the bucket refilled"}
       :ins {:in "requests, addressed [:fx/<kind> :in] by any proc" :done "its workers' outcomes"} :outs {}
       ;; admission and routing only: never blocks, never computes long
       :workload :io
       :ping-map-fn (fn [st] (-> (select-keys st [:kind :workers :busy :done]) (assoc :queued (count (:queue st)) :spent (:spent st))))})
  ([args] (assoc (select-keys args [:kind :workers]) :busy 0 :done 0 :seq 0 :queue (sorted-map) :spent {}
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
         :done (let [limited? (= :seon.model/rate-limited (:seon.error/type (:outcome m)))]
                 (when-not limited? (reply! o (:dest m) (:outcome m)))
                   ;; root counts every failed effect across agents (seon.error, class :effect);
                   ;; the asker records its own outcome on its branch
                   (when (contract/error? (:outcome m))
                     (step/out! o :out {:op :problem :class :effect :error (dissoc (:outcome m) :seon.fx/us :seon.fx/queue-us)
                                        :agent (get-in m [:dest :agent])}))
                   (let [st (settle st m)
                         st (if (and limited? (:request m))
                              (let [r (:request m)
                                    qk (or (::qk r) [(- (:prio r 0)) (inc (:seq st))])]
                                (cond-> (-> st (assoc :seq (max (:seq st) (if (vector? qk) (second qk) 0)))
                                            (assoc-in [:queue qk] (dissoc r :taken-ns ::qk)))
                                  (:bucket st) (update :bucket assoc :tokens 0.0)))
                              st)]
                     [(bound (dispatch st o)) @o]))
         :tick [(bound (dispatch (dissoc st :tick-due) o)) @o])))))

(defn worker-proc
  "One performer of an effect kind: takes a request from its owner's shared channel,
  performs it with the kind's Var, and gives the owner the outcome (with its timing).
  The entrant boundary for a defect in the perform Var: the defect is the outcome, as an
  error map, so its asker is always answered."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:perform "the kind's perform Var"} :ins {} :outs {}
       ;; the effect blocks in native IO (an HTTP read, a file write). Jolt runs :io procs
       ;; on fibers bound to their carrier thread for life, and a blocking FFI call pins the
       ;; carrier, stranding every other fiber placed on it (inv2: replies 4-15 s late).
       ;; :mixed gives each worker its own thread: the declared workload is true, and the
       ;; worker count already bounds the threads
       :workload :mixed
       :ping-map-fn (fn [st] (select-keys st [:n]))})
  ([args] {:perform (:perform args) :kind (:kind args) :ctx (:ctx args) :n 0
           :owner (:owner args) ::flow/in-ports {:work (:work args)}})
  ([st _] st)
  ([st _ m]
   (if (nil? m)
     [st nil]
     (let [t0 (profile/now-ns)
           r (try ((:perform st) (assoc m :ctx (:ctx st)))
                  (catch Throwable e (error :seon.fx/defect (or (ex-message e) (str (type e))) {})))
           t1 (profile/now-ns)
           outcome (assoc r :seon.fx/kind (:kind st) :seon.fx/queue-us (quot (- (:taken-ns m) (:in-ns m)) 1000)
                          :seon.fx/us (quot (- t1 t0) 1000))]
       [(update st :n inc) {[(:owner st) :done] [(cond-> {:dest (:dest m) :request m :outcome outcome :budget-key (:budget-key m) :est (:est m 0)}
                                     (:cost r) (assoc :cost (:cost r)))]}]))))

(defn model
  "The model call (seon.model/complete) under root's provider (:ctx, which names the API
  key's environment variable; root alone reads it); a request's :model overrides the
  provider's model. Answers {:value completion :cost tokens} or the provider error."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [req ctx]}]
  (let [p (cond-> (:provider ctx) (and (:model req) (= :openai-compat (:kind (:provider ctx)))) (assoc :model (:model req)))
        c (model/complete p (dissoc req :model))]
    (cond (contract/error? c) c
          (and (:prompt-tokens c) (:completion-tokens c)) {:value c :cost (+ (:prompt-tokens c) (:completion-tokens c))}
          :else {:value c})))

(defn procs
  "The owner and worker procs of every kind, for the root graph. Any proc reaches an owner
  by its id, [:fx/<kind> :in]; back is the api's :back channel. Answers {pid def}."
  {:malli/schema [:=> [:cat :map :seon.host/chan] :map]}
  [kinds back]
  (reduce-kv
    (fn [acc kind {:keys [perform workers ctx rate]}]
      (let [work (a/chan workers) owner (keyword "fx" (name kind))]
        (-> acc
            (assoc owner {:proc (flow/process #'owner-proc)
                          :chan-opts {:in {:buf-or-n (cfg/value :seon.config.fx/buf)} :done {:buf-or-n (* 2 workers)}}
                          :args (cond-> {:kind kind :workers workers :work work :back back}
                                  rate (assoc :rate rate :tick (a/chan 1)))})
            (into (for [i (range workers)]
                    [(keyword (str "fx." (name kind)) (str i))
                     {:proc (flow/process #'worker-proc) :args {:kind kind :perform perform :ctx ctx :work work :owner owner}}])))))
    {} kinds))
