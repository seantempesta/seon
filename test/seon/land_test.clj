(ns seon.land-test
  "bin/land proves a throwaway checkout of the exact commit it lands (docs/merging.md#a-branch-lands-with-binland), run on a
  scratch repository whose bin/ holds stand-ins for the collaborators (worktree, build, check, laws, prove, update): the script's
  own steps are the real ones; what the stand-ins do is not covered (bin/worktree --at is a-gates-checkout-takes-its-roots-built-products,
  the real prove and build are theirs)."
  (:require [seon.lib] [clojure.test :refer [deftest is]] [clojure.java.shell :as sh] [clojure.string :as str] [seon.config :as config]))

(def ^:private stubs
  "The scratch repo's bin/ files. worktree is --at/--remove-at/--sync over git worktrees (the real one's calls and output shape);
  prove writes what it saw into $SEON_LAND_OUT, then does what $SEON_LAND_PROVE says: `edit` changes the session's worktree
  before it looks, `stop` sends the land a TERM and never ends."
  {"worktree" "#!/bin/zsh
case $1 in
  --at) git -C \"$4\" worktree add -q --detach \"$2\" \"$3\" && mkdir -p \"$2/target\" && print \"worktree $2\" ;;
  --remove-at) git -C \"$2\" worktree remove --force \"$2\" && print removed ;;
  --sync) ;;
esac
"
   "build" "#!/bin/zsh\nexit 0\n"
   "check" "#!/bin/zsh\nexit 0\n"
   "laws" "#!/bin/zsh\nexit 0\n"
   "ports" "#!/bin/zsh\nprint 1\n"
   "tree" "#!/bin/zsh\nprint x\n"
   "ask" "#!/bin/zsh\nprint :landed\n"
   "update" "#!/bin/zsh\nprint 'UPDATE OK'\n"
   "full-run" "#!/bin/zsh\nexit 0\n"
   "prove" "#!/bin/zsh
case $SEON_LAND_PROVE in
  edit) print edited-meanwhile > $SEON_LAND_HERE/marker ;;
esac
print -r -- $PWD > $SEON_LAND_OUT/prove-pwd
print -r -- $(cat marker) > $SEON_LAND_OUT/proved-marker
git rev-parse 'HEAD^{tree}' > $SEON_LAND_OUT/proved-tree
case $SEON_LAND_PROVE in
  stop) kill -TERM $PPID; exec sleep 30 ;;
esac
print 'MERGE OK'
"})

(defn- scratch!
  "A repo (main) holding the stand-ins and a marker file, its worktree `w` on branch jolt/w with one more commit and main moved
  once more by a file of its own (so the land merges). Returns the paths and a git fn."
  [tmp]
  (let [repo (str tmp "/repo") w (str tmp "/w") out (str tmp "/out")
        git (fn [dir & args] (let [r (apply sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t" args)]
                               (is (zero? (:exit r)) (str args (:err r))) (str/trim (:out r))))]
    (.mkdirs (java.io.File. (str repo "/bin"))) (.mkdirs (java.io.File. out))
    (git repo "init" "-q" "-b" "main")
    (doseq [[n text] stubs] (spit (str repo "/bin/" n) text) (sh/sh "chmod" "+x" (str repo "/bin/" n)))
    (spit (str repo "/marker") "committed") (spit (str repo "/.gitignore") "target/\n")
    (git repo "add" ".") (git repo "commit" "-q" "-m" "base")
    (git repo "worktree" "add" "-q" "-b" "jolt/w" w)
    (.mkdirs (java.io.File. (str w "/target")))
    (spit (str w "/marker") "branch change") (git w "commit" "-q" "-am" "branch")
    (spit (str repo "/other") "main change") (git repo "add" "other") (git repo "commit" "-q" "-m" "main moves")
    {:repo repo :w w :out out :git git}))

(defn- land!
  "bin/land in the scratch worktree with the prove stand-in told to `how`; the exit, output and what the prove saw."
  [{:keys [w out]} how]
  (let [r (sh/sh "env" "GIT_AUTHOR_NAME=t" "GIT_AUTHOR_EMAIL=t@t" "GIT_COMMITTER_NAME=t" "GIT_COMMITTER_EMAIL=t@t"
                 (str "SEON_LAND_OUT=" out) (str "SEON_LAND_HERE=" w) (str "SEON_LAND_PROVE=" how)
                 "zsh" (str (config/checkout) "/bin/land") :dir w)
        seen #(let [f (java.io.File. (str out "/" %))] (when (.exists f) (str/trim (slurp f))))]
    (assoc r :pwd (seen "prove-pwd") :marker (seen "proved-marker") :tree (seen "proved-tree"))))

(defn- checkouts [{:keys [repo]}] (count (filter #(re-find #"-chk" %) (str/split-lines (:out (sh/sh "git" "-C" repo "worktree" "list"))))))

;; Estimate: ~25 child calls (git, zsh stand-ins) x 60k = 1500000 ticks
(deftest ^{:seon.test/estimate {:ticks 1500000} :seon.test/execs ["git" "bin/land"]} an-edit-made-during-a-land-is-not-in-what-it-proves-or-lands
  ;; docs/merging.md#a-branch-lands-with-binland: the proof runs in a checkout of the commit that lands. Red before: bin/land
  ;; proved the worktree's files as they stood, so an edit made while the proof ran was proved and never landed (runtime-2's
  ;; two red lands, 2026-11-15)
  (let [tmp (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-land-" (random-uuid))) (.mkdirs)))
        {:keys [repo w git] :as s} (scratch! tmp)]
    (try
      (let [r (land! s "edit")
            landed (git repo "rev-parse" "main")]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (= "LANDED" (last (str/split-lines (:out r)))) (:out r))
        (is (not= w (:pwd r)) "the proof ran in a checkout, not the worktree")
        (is (= "edited-meanwhile" (str/trim (slurp (str w "/marker")))) "the edit was made, in the worktree, during the proof")
        (is (= "branch change" (:marker r)) "the proof saw the committed file, not the edit")
        (is (= (:tree r) (git repo "rev-parse" (str landed "^{tree}"))) "the proved tree is the landed commit's tree")
        (is (= "branch change" (git repo "show" (str landed ":marker"))) "and what landed holds no edit")
        (is (= "main change" (git repo "show" (str landed ":other"))) "main's own change was merged in before the proof")
        (is (zero? (checkouts s)) "the checkout is gone after the land"))
      (finally (sh/sh "git" "-C" repo "worktree" "prune") (seon.lib/remove-tree! tmp)))))

;; Estimate: ~15 child calls x 60k = 900000 ticks
(deftest ^{:seon.test/estimate {:ticks 900000} :seon.test/execs ["git" "bin/land"]} a-stopped-land-leaves-no-checkout-and-moves-nothing
  ;; the checkout goes on every exit, a stop included (a TERM during the proof). Red before: no checkout was made, so this fails at
  ;; the first assertion (the proof ran in the worktree); without the EXIT trap it fails at the last
  (let [tmp (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-land-" (random-uuid))) (.mkdirs)))
        {:keys [repo w git] :as s} (scratch! tmp)
        before (git repo "rev-parse" "main")]
    (try
      (let [r (land! s "stop")]
        (is (not= w (:pwd r)) "the proof ran in a checkout")
        (is (pos? (:exit r)) (str (:out r) (:err r)))
        (is (= "LAND FAILED" (last (str/split-lines (:out r)))) (:out r))
        (is (= before (git repo "rev-parse" "main")) "main did not move")
        (is (zero? (checkouts s)) "no checkout is left behind")
        (is (empty? (filter #(re-find #"-chk" (.getName %)) (.listFiles (java.io.File. (str repo "/target/land"))))) "nor its directory"))
      (finally (sh/sh "git" "-C" repo "worktree" "prune") (seon.lib/remove-tree! tmp)))))
