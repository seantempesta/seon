(ns seon.git.forks
  "Fork submodules under `reference-code/` as first-class Seon code (`:seon.code.ns/origin`):
   - Pure path partitioning (`path->fork-root`, `partition-paths-by-repo`) for submodule-aware write-back.
   - Explicit fork indexing (`index-fork-tx`), never part of cold `bootstrap-tx`.
   - Mechanical fork commits on `seon/<topic>` branches (`write-and-commit-fork!`); the main merge commit stages
     the returned submodule root as its new gitlink.
   - The explicit never-force `push-all!` boundary: forks first, gitlink verification, then Seon's branch."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [jolt.fibers :as fibers]
            [seon.code.io :as cio]))

(def fork-repos
  "The Clojure library forks Seon indexes and edits in place: `{origin submodule-root}`."
  {:fork/datahike "reference-code/datahike"
   :fork/konserve "reference-code/konserve"
   :fork/malli "reference-code/malli"
   :fork/persistent-sorted-set "reference-code/persistent-sorted-set"
   :fork/jolt-overlay "reference-code/jolt-overlay"
   :fork/crypto "reference-code/crypto"})

(defn sh-fiber
  "Runs `clojure.java.shell/sh` with `args` inside a Jolt fiber (spawning one when the caller is not in a fiber)."
  {:seon.flow/edge true
   :malli/schema [:function [:=> [:cat [:* :any]] :map]]
   :cost/ticks 1000000}
  [& args]
  (if (fibers/in-fiber?)
    (apply sh/sh args)
    (fibers/join (fibers/spawn #(apply sh/sh args)))))

(defn git!
  "Runs `git -C repo-dir args...`, returning stdout or throwing `ex-info` carrying `:exit` and `:err`."
  {:seon.flow/edge true
   :malli/schema [:function [:=> [:cat :string [:* :string]] :string]]
   :cost/ticks 1000000}
  [repo-dir & args]
  (let [{:keys [exit out err]} (apply sh-fiber "git" "-C" repo-dir args)]
    (if (zero? exit)
      out
      (throw (ex-info (str "git " (str/join " " args) " failed (" exit "): " (str/trim err))
                      {:repo repo-dir :args (vec args) :exit exit :err err})))))

(defn- git-ok? [dir & args]
  (zero? (:exit (apply sh-fiber "git" "-C" dir args))))

(defn path->fork-root
  "Splits a repo-relative `reference-code/<lib>/<rel>` path into `{:origin :fork/<lib> :root :rel-path}`; nil otherwise."
  {:malli/schema [:=> [:cat :string] [:maybe [:map [:origin :qualified-keyword] [:root :string] [:rel-path :string]]]]
   :cost/ticks 200000}
  [path]
  (when-let [[_ lib rel] (re-matches #"reference-code/([^/]+)/(.+)" path)]
    {:origin (keyword "fork" lib) :root (str "reference-code/" lib) :rel-path rel}))

(defn partition-paths-by-repo
  "Splits merge `:touched-paths` into main-repo paths and `{fork-root [rel-paths]}` (both sorted, distinct)."
  {:malli/schema [:=> [:cat [:or [:sequential :string] [:set :string]]]
                  [:map [:main [:vector :string]] [:forks [:map-of :string [:vector :string]]]]]
   :cost/ticks 2000000}
  [paths]
  (reduce (fn [acc p]
            (if-let [{:keys [root rel-path]} (path->fork-root p)]
              (update-in acc [:forks root] (fnil conj []) rel-path)
              (update acc :main conj p)))
          {:main [] :forks {}}
          (sort (distinct paths))))

(defn topic-branch
  "Mechanical fork branch `seon/<topic>` for merged Datahike `branch` (ZERO `jolt/` prefix; `seon/merge` when nil)."
  {:malli/schema [:=> [:cat [:maybe :keyword]] :string]
   :cost/ticks 200000}
  [branch]
  (let [raw (when branch
              (str (when-let [n (namespace branch)] (when (not= n "branch") (str n "-"))) (name branch)))
        t (-> (str raw) (str/replace #"[^A-Za-z0-9_]+" "-") (str/replace #"^-+|-+$" ""))]
    (str "seon/" (if (str/blank? t) "merge" t))))

(defn write-and-commit-fork!
  "Renders fork `root`'s `rel-paths` byte-for-byte from `db` into `repo-dir/root` and makes one mechanical commit
   (`Seon <seon@local>`, first line of `message`, no trailers) on `(topic-branch merged-branch)` inside the
   submodule, refusing to clobber an existing branch that is not an ancestor of the submodule HEAD.
   Returns `{:root :branch :commit :committed?}`; the caller stages `root` (the gitlink) in its own commit."
  {:seon.flow/edge true
   :malli/schema [:=> [:cat :seon.host/db :string :string [:sequential :string] [:maybe :keyword] :string [:set :string]] :map]
   :cost/ticks 50000000}
  [db repo-dir root rel-paths merged-branch message deleted-paths]
  (let [dir (str (io/file repo-dir root))
        br (topic-branch merged-branch)
        subj (or (first (str/split-lines (str message))) "")
        subj (if (str/blank? subj) (str "Seon merge " br) subj)]
    (when (not= br (str/trim (git! dir "branch" "--show-current")))
      (when (and (git-ok? dir "rev-parse" "--verify" "--quiet" (str "refs/heads/" br))
                 (not (git-ok? dir "merge-base" "--is-ancestor" br "HEAD")))
        (throw (ex-info (str "Fork branch " br " in " root " diverged from the submodule HEAD; refusing to reset it")
                        {:error :seon.git/fork-branch-diverged :root root :branch br})))
      (git! dir "checkout" "-q" "-B" br))
    (doseq [rel rel-paths]
      (let [full (str root "/" rel)
            f (io/file dir rel)]
        (if-let [txt (cio/render-path db full)]
          (do (.mkdirs (.getParentFile f)) (spit f txt))
          (if (contains? deleted-paths full)
            (when (.exists f) (.delete f))
            (throw (ex-info (str "Refusing blind deletion of " full)
                            {:error :seon.git/refused-blind-delete :path full}))))))
    (apply git! dir "add" "-A" "--" (vec rel-paths))
    (let [staged? (not (git-ok? dir "diff" "--cached" "--quiet"))]
      (when staged?
        (git! dir "-c" "user.name=Seon" "-c" "user.email=seon@local" "commit" "-q" "--no-verify" "-m" subj))
      {:root root :branch br :commit (str/trim (git! dir "rev-parse" "HEAD")) :committed? staged?})))

(defn fork-source-paths
  "Sorted repo-relative `.clj`/`.cljc`/`.jolt` source paths under fork `origin`'s `src/` tree in `repo-dir`."
  {:seon.flow/edge true
   :malli/schema [:function [:=> [:cat :string :qualified-keyword] [:vector :string]]]
   :cost/ticks 50000000}
  [repo-dir origin]
  (let [root (or (get fork-repos origin)
                 (throw (ex-info (str "Unknown fork " origin)
                                 {:error :seon.git/unknown-fork :origin origin :known (vec (keys fork-repos))})))
        prefix-len (inc (count (.getPath (io/file repo-dir))))]
    (->> (file-seq (io/file repo-dir root "src"))
         (filter #(.isFile ^java.io.File %))
         (map #(subs (.getPath ^java.io.File %) prefix-len))
         (filter cio/fork-src-path?)
         sort
         vec)))

(defn index-fork-tx
  "Explicit fork import (never part of cold `bootstrap-tx`): `cio/file-import-tx` tx-data for every source of fork
   `origin` with cross-file refs resolved in one batch (`cio/batch-ref-ctx`, no compiler analysis), tagging each
   namespace `:seon.code.ns/origin origin`."
  {:seon.flow/edge true
   :malli/schema [:=> [:cat [:maybe :seon.host/db] :string :qualified-keyword] [:vector :any]]
   :cost/ticks 5000000000}
  [db repo-dir origin]
  (let [texts (into (sorted-map)
                    (map (fn [p] [p (slurp (io/file repo-dir p))]))
                    (fork-source-paths repo-dir origin))
        ctx (assoc (cio/batch-ref-ctx db texts) :skip-compiler-analyze? true)]
    (into []
          (comp (mapcat (fn [[p t]] (cio/file-import-tx db p t false ctx)))
                (map #(if (and (map? %) (:seon.code.ns/name %) (:seon.code.ns/path %)) (assoc % :seon.code.ns/origin origin) %)))
          texts)))

(defn- submodule-report [repo-dir path gitlink dry-run?]
  (let [dir (str (io/file repo-dir path))
        on-remote? #(not (str/blank? (git! dir "branch" "-r" "--contains" gitlink)))
        base {:path path :gitlink gitlink}]
    (cond
      (not (.exists (io/file dir ".git"))) (assoc base :status :uninitialized)
      (not (str/blank? (git! dir "status" "--porcelain" "--untracked-files=no"))) (assoc base :status :dirty :error? true)
      (not (git-ok? dir "cat-file" "-e" (str gitlink "^{commit}"))) (assoc base :status :missing-gitlink :error? true)
      (on-remote?) (assoc base :status :on-remote)
      :else
      (let [br (str/trim (git! dir "branch" "--show-current"))]
        (cond
          (or (str/blank? br) (contains? #{"main" "master"} br) (not (git-ok? dir "merge-base" "--is-ancestor" gitlink "HEAD")))
          (assoc base :status :unpushable :branch br :error? true)
          dry-run? (assoc base :status :would-push :branch br)
          :else (do (git! dir "push" "origin" br)
                    (assoc base :status (if (on-remote?) :pushed :push-unverified) :branch br
                           :error? (not (on-remote?)))))))))

(defn push-all!
  "Explicit never-force push boundary: (1) every initialized `reference-code/` submodule must be clean, and any
   whose gitlink commit is not on a remote branch is pushed from its current non-`main` branch (`git push origin
   <branch>`, never `--force`); (2) every gitlink must then be contained in a remote branch; (3) only then is Seon's
   current branch pushed to `origin`. `{:dry-run? true}` plans the same steps with local reads only (no network)."
  {:seon.flow/edge true
   :malli/schema [:function [:=> [:cat :string [:maybe :map]] :map]]
   :cost/ticks 500000000}
  [repo-dir {:keys [dry-run?]}]
  (let [links (keep (fn [line]
                      (let [[meta-part path] (str/split line #"\t" 2)
                            [mode sha] (str/split (str meta-part) #"\s+")]
                        (when (and path (= mode "160000") (str/starts-with? path "reference-code/"))
                          [path sha])))
                    (str/split-lines (git! repo-dir "ls-files" "-s" "--" "reference-code")))
        subs-r (mapv (fn [[path sha]] (submodule-report repo-dir path sha dry-run?)) links)
        errors (filterv :error? subs-r)
        br (str/trim (git! repo-dir "branch" "--show-current"))
        main-st (cond
                  (seq errors) :blocked
                  (str/blank? br) :detached
                  dry-run? :would-push
                  :else (do (git! repo-dir "push" "origin" br) :pushed))]
    {:ok? (and (empty? errors) (contains? #{:would-push :pushed} main-st))
     :dry-run? (boolean dry-run?)
     :submodules subs-r
     :errors errors
     :main {:branch br :status main-st}}))
