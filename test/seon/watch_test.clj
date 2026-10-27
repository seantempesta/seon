(ns seon.watch-test
  "Root's file watcher (seon.watch, native/watch.c over FSEvents, my.root/watch!): a change
  under a watched root arrives as its path, a batch loads what its paths' loaders read, and a
  watcher that dies is a :platform error on main. Run: bin/test seon.watch-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [clojure.java.io :as io]
            [seon.watch :as watch] [my.root :as mr] [seon.root :as root] [seon.problems :as problems] [seon.contract :as contract]
            [datahike.api :as d] [seon.task] [seon.docs] [clojure.java.shell] [seon.config] [clojure.string :as str] [seon.fx]))

(deftest a-record-is-its-kind-then-its-path
  (is (= [:path "/a/b\nc.md"] (watch/record (.getBytes "p/a/b\nc.md" "UTF-8"))) "a newline is part of the path: NUL ends a record")
  (is (= [:rescan "/a"] (watch/record (.getBytes "s/a" "UTF-8"))))
  (is (= [:root "/a"] (watch/record (.getBytes "r/a" "UTF-8"))))
  (is (= [:ready "/a"] (watch/record (.getBytes "w/a" "UTF-8")))))

(defn- watch-kinds "The kinds a batch loads (my.root/watch-loads' keys)." [co refs own batch] (set (keys (mr/watch-loads co refs own batch))))

(deftest a-batch-of-edits-reads-only-the-files-it-changed
  ;; task seon.source/an-import-reads-only-changed-files: the watcher's source batch names the files
  ;; (my.root/source-changes) and the :source read gives just those, with the tree's id for the digest
  (let [co (seon.config/checkout) refs "/g/refs" own #{"/g/refs/heads/jolt/health"} p #(str co "/" %)]
    (is (= #{"src/seon/watch.clj" "test/x.clj"} (mr/source-changes co refs own [[:path (p "src/seon/watch.clj")] [:path (p "src/seon/watch.clj")] [:path (p "test/x.clj")] [:path (p "docs/x.md")]])) "the files, once each; a page is the wiki's")
    (is (nil? (mr/source-changes co refs own [[:path (p "src/seon/watch.clj")] [:path "/g/refs/heads/jolt/health"]])) "the branch moved with them: the whole tree")
    (is (nil? (mr/source-changes co refs own [[:path (p "src/seon/watch.clj")] [:rescan (p "src")]])) "a rescan: the whole tree")
    (is (nil? (mr/source-changes co refs own [[:path (p "docs/x.md")]])) "no source file changed")
    (is (nil? (mr/source-changes co refs own [[:path (p "src/seon/watch.clj")] [:path (p "src/seon/gone")]])) "a directory made or removed: the whole tree"))
  (let [r (seon.fx/read-source {:req {:root (seon.config/checkout) :dirs ["src"] :paths #{"src/seon/watch.clj" "src/seon/gone.clj" "README.md"}}})]
    (is (= #{"src/seon/watch.clj" "src/seon/gone.clj"} (set (keys (:value r)))) "the named source files only")
    (is (nil? (get-in r [:value "src/seon/gone.clj"])) "a removed file is nil")
    (is (string? (get-in r [:value "src/seon/watch.clj"])))
    (is (string? (:tree r)) "the tree id, which stands for every file's content")))

(deftest a-batch-loads-what-its-paths-loaders-read
  (let [co (seon.config/checkout) refs "/g/refs" own #{"/g/refs/heads/jolt/health" "/g/refs/heads/jolt/merges"} k #(watch-kinds co refs own %)]
    (is (= #{:docs} (k [[:path (str co "/docs/health.md")]])))
    (is (= #{:source} (k [[:path (str co "/src/seon/watch.clj")]])))
    (is (= #{:tasks} (k [[:path (str co "/resources/seon/tasks/seon.watch.edn")]])))
    (is (= #{} (k [[:path (str co "/claude_scratch/x.md")] [:path (str co "/target/seon")] [:path "/elsewhere/docs/x.md"]])) "no loader reads them: nothing loads")
    (is (= #{:source} (k [[:path "/g/refs/heads/jolt/health"]])) "this checkout's branch moved: a commit or a merge on it")
    (is (= #{:source} (k [[:path "/g/refs/heads/jolt/merges"]])) "the write-back branch moved")
    (testing "the repository is every worktree's: another branch's commit is not this root's (root-test's 806 tasks, 2026-10-25)"
      (is (= #{} (k [[:path "/g/refs/heads/jolt/another"] [:path "/g/refs/heads/main"]])))
      (is (= #{} (watch-kinds co refs #{} [[:path "/g/refs/heads/jolt/health"]])) "a detached HEAD follows no ref"))
    (testing "a rescan (FSEvents coalesced or dropped events) loads the loaders reading under its path"
      (is (= #{:docs} (k [[:rescan (str co "/docs")]])))
      (is (= #{} (k [[:rescan (str co "/target")]])) "a build's writes under target/: nothing")
      (is (= #{:docs :tasks :source} (k [[:rescan co]]) (k [[:root co]])) "the checkout itself: every kind")
      (is (= #{:source} (k [[:rescan refs]])) "the refs: the source alone, never the task files"))))

(deftest another-worktrees-commit-loads-nothing
  ;; root-test's 806 tasks (2026-10-25): every worktree's commits move the one repository's refs;
  ;; a root loads on its own branch's alone. A scratch repository of two worktrees, its refs
  ;; watched as root watches them (seon.fx/git-refs, seon.fx/head-ref), the paths FSEvents reports
  (let [tmp (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-own-" (random-uuid))) (.mkdirs)))
        co (str tmp "/main") other (str tmp "/other")
        git (fn [dir & args] (let [r (apply clojure.java.shell/sh "git" "-C" dir args)] (is (zero? (:exit r)) (str args (:err r))) r))
        _ (.mkdirs (io/file co))
        _ (git co "init" "-q" "-b" "main")
        _ (git co "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "one")
        _ (git co "worktree" "add" "-q" "-b" "other" other)
        refs (seon.fx/git-refs co) own (seon.fx/head-ref co)
        seen (atom []) settled (promise) theirs (promise) ours (promise)
        w (watch/start! [refs] (fn [b] (let [all (swap! seen into b)]
                                         (when (some #(str/includes? (second %) "/tags/settled") all) (deliver settled true))
                                         (when (some #(and (= :path (first %)) (str/includes? (second %) "/heads/other")) all) (deliver theirs all))
                                         (when (some #(= [:path own] %) all) (deliver ours all))))
                        (fn [_]))]
    (try
      (is (= (str refs "/heads/main") own) "the checkout's own ref, as the watcher reports paths")
      ;; FSEvents may report the setup's writes (its first commit) after the stream starts: a tag
      ;; (a ref no loader reads) marks where they end, and what follows it is this test's
      (git co "tag" "settled")
      @settled
      (reset! seen [])
      (git other "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "theirs")
      (testing "the other worktree's commit moved its ref: that path loads nothing, and no ref loads the task files or the wiki"
        ;; (FSEvents may report heads/main again within a later batch, coalesced: at most a source
        ;; read that finds nothing new, never the task files)
        (let [b @theirs]
          (is (= #{} (watch-kinds co refs #{own} (filterv #(str/includes? (second %) "/heads/other") b))) (pr-str b))
          (is (not-any? #{:tasks :docs} (watch-kinds co refs #{own} b)) (pr-str b))))
      (git co "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "ours")
      (testing "this checkout's commit arrives as its own ref's path, as head-ref names it: its source loads"
        (let [b @ours]
          (is (some #{[:path own]} b) (pr-str b))
          (is (= #{:source} (watch-kinds co refs #{own} (filterv #{[:path own]} b))))))
      (finally (watch/stop! w) (clojure.java.shell/sh "rm" "-rf" tmp)))))

(defn- covers?
  "Does record [kind path] report a change to file f in dir: f's own path, or a rescan or a
  moved root at dir or above it (FSEvents coalesced or dropped what happened under it, and root
  reads the subtree again: my.root/batch-keys)?"
  [dir f [kind path]]
  (or (= [:path f] [kind path])
      (and (#{:rescan :root} kind) (or (= path dir) (str/starts-with? dir (str path "/"))))))

(deftest a-change-arrives-and-a-dead-watcher-is-a-platform-error
  (let [dir (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-watch-" (random-uuid) "/docs")) (.mkdirs)))
        f (str dir "/x.md")
        got (promise) dead (promise)
        w (watch/start! [dir] (fn [b] (when (some #(covers? dir f %) b) (deliver got b))) #(deliver dead %))]
    (is (not (contract/error? w)) (pr-str w))
    (spit f "# X\n")
    (testing "a file written under a watched root arrives as its path, or as a rescan over it"
      (is (some #(covers? dir f %) @got)))
    (testing "the watcher killed (not stopped): its death goes to on-exit as :seon.watch/died"
      (.destroy (:process w))
      (let [e @dead]
        (is (= :seon.watch/died (:seon.error/type e)))
        (is (contract/valid? :seon.watch/died e))
        (testing "recorded on main as a :platform error, what the banner shows"
          (let [db (d/db-with (ts/test-db) (root/platform-tx (ts/test-db) e 1000))]
            (is (= [:seon.watch/died] (mapv :type (get-in (problems/report db) [:classes :platform :rows]))))))))
    (testing "a watcher stopped is not a death"
      (let [dead2 (promise) w2 (watch/start! [dir] (fn [_]) #(deliver dead2 %))]
        (watch/stop! w2)
        (is (not (realized? dead2)))))))

(deftest a-change-made-at-ready-arrives
  ;; target/watch said ready once FSEventStreamStart returned, but fseventsd delivers to a stream
  ;; only once it is registered, later: a change made just after ready was lost (1 in 600 under
  ;; load, measured on the binary alone), and a-change-arrives-and-a-dead-watcher-is-a-platform-
  ;; error waited on it forever at 0% CPU. Ready is now fseventsd's HistoryDone sentinel, sent
  ;; once the stream is registered (native/watch.c), so a change made the moment start! answers
  ;; arrives, every time. 50
  ;; watchers in turn; there is no clock to end a wait, so the old watcher's red is a hang here,
  ;; and a rare one: at 1 in 600, 50 writes catch the old watcher about 8% of the time (the
  ;; before and after on the binary alone are in the change's commit)
  (let [base (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-watch-" (random-uuid))) (.mkdirs)))
        arrived (vec (for [i (range 50)]
                  (let [dir (.getCanonicalPath (doto (io/file (str base "/" i)) (.mkdirs)))
                        f (str dir "/x.md")
                        got (promise)
                        w (watch/start! [dir] (fn [b] (when (some #(covers? dir f %) b) (deliver got true))) (fn [_]))]
                    (spit f "# X\n")
                    (let [r @got] (watch/stop! w) r))))]
    (clojure.java.shell/sh "rm" "-rf" base)
    (is (= 50 (count (filter true? arrived))))))

(deftest a-root-made-after-the-watch-starts-is-watched
  ;; root watches its loaders' dirs (my.root/watch!), one of which may not exist yet (a test root's
  ;; task dir, a checkout's test/): FSEvents reports the dir's creation (:root) and what is written
  ;; in it after. A watch whose every root is missing says no ready (native/watch.c), which a root
  ;; never asks: its docs/ and src/ are there
  (let [base (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-watch-" (random-uuid))) (.mkdirs)))
        here (.getCanonicalPath (doto (io/file (str base "/here")) (.mkdirs)))
        later (str base "/later/tasks")
        f (str later "/t.edn")
        got (promise)
        w (watch/start! [here later] (fn [b] (when (some #(= [:path f] %) b) (deliver got b))) (fn [_]))]
    (is (not (contract/error? w)) (pr-str w))
    (.mkdirs (io/file later))
    (spit f "[]")
    (is (some #(= [:path f] %) @got) "a file in the root made after the start arrives")
    (watch/stop! w)
    (clojure.java.shell/sh "rm" "-rf" base)))

(deftest a-throw-in-the-batch-handler-is-a-recorded-death
  (let [dir (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-watch-" (random-uuid) "/docs")) (.mkdirs)))
        dead (promise)
        w (watch/start! [dir] (fn [_] (throw (ex-info "the handler broke" {}))) #(deliver dead %))]
    (is (not (contract/error? w)))
    (spit (str dir "/x.md") "# X\n")
    (let [e @dead]
      (is (= :seon.watch/died (:seon.error/type e)) "the thread does not end silently")
      (is (re-find #"handler threw: the handler broke" (:seon.error/message e)))
      (is (contract/valid? :seon.watch/died e)))))

(deftest a-refused-task-file-is-a-recorded-platform-error
  (let [bad (seon.task/file-declarations 't.x "{:not a vector")
        loaded {:docs {:ok true} :tasks bad}]
    (is (contract/error? bad) "a malformed task file is refused with a typed error")
    (is (= [bad] (mr/load-errors loaded)) "the loader's error is kept, the ok answer is not")
    (let [db (d/db-with (ts/test-db) (root/platform-tx (ts/test-db) bad 1000))]
      (is (= [(:seon.error/type bad)] (mapv :type (get-in (problems/report db) [:classes :platform :rows])))))))

(deftest a-dead-watchers-error-closes-when-a-new-watcher-writes-its-fact
  ;; found on the standing root 2026-10-25: a past boot's :seon.watch/died stayed open while this
  ;; boot's watcher ran (task seon.problems/a-platform-error-closes-when-its-cause-is-gone)
  (let [db0 (ts/test-db)
        e (contract/error :seon.watch/died 'seon.watch/start! "its batch handler threw: docs/references.md (No such file or directory): root no longer watches what its loaders read"
                          {:bin "/c/target/watch" :roots ["/c"] :exit 143 :stderr ""})
        fresh {:roots ["/c"] :boot "b2" :batches 0 :paths 0 :rescans 0}
        died #(d/db-with % (mr/watch-died-tx % (assoc fresh :boot "b1") e 1000))
        open? #(not (false? (:open? (first (get-in (problems/report %) [:classes :platform :rows])))))
        dead (died db0)]
    (is (= [:seon.watch/died] (mapv :type (get-in (problems/report dead) [:classes :platform :rows]))))
    (is (open? dead) "the death's own write of the watch fact is no later fact: open")
    (is (open? (d/db-with dead [{:seon.root/id :root :seon.root/boot "b2"}])) "a later write of root's other facts says nothing of the watcher")
    (let [alive (d/db-with dead [{:seon.root/id :root :seon.root/watch fresh}])]
      (is (not (open? alive)) "a new boot's watcher writes its fact: closed, nothing written to close it")
      (is (open? (died alive)) "it dies again: open again"))
    (testing "a death stored before its producer named a subject gains it when it happens again"
      (let [old (d/db-with db0 (root/platform-tx db0 e 900))]
        (is (open? (d/db-with old [{:seon.root/id :root :seon.root/watch fresh}])) "no subject: nothing can close it")
        (is (not (open? (-> (died old) (d/db-with [{:seon.root/id :root :seon.root/watch fresh}])))))))))

(deftest a-refused-task-files-error-closes-at-the-next-good-read
  ;; found on the standing root 2026-10-26: "task file my.task.edn: not readable EDN" stayed open ~21
  ;; hours after the file read again. Put back as it was, the good read changed no declaration, so
  ;; load-tasks! wrote nothing, and the refused read named no subject that anything could write
  (let [bad (mr/load-answer (seon.task/file-declarations 'my.task "[{:seon.task/name \"a\" :seon.task/plan}]"))
        good {:files 1}
        read #(d/db-with % (mr/tasks-read-tx good))
        refused #(d/db-with % (mr/load-error-tx % bad 1000))
        open? #(not (false? (:open? (first (get-in (problems/report %) [:classes :platform :rows])))))
        before (read (ts/test-db))
        db (refused before)]
    (is (= :seon.root/tasks (:seon.error/subject bad)) "the refused read names the read's outcome")
    (is (= [:seon.task/refused] (mapv :type (get-in (problems/report db) [:classes :platform :rows]))))
    (is (open? db) "refused, its outcome written with it: open")
    (is (open? (d/db-with db [{:seon.root/id :root :seon.root/boot "b2"}])) "a write of root's other facts says nothing of the task files")
    (let [again (refused db)]
      (is (open? again) "the same refused read again: open")
      (is (= (count (d/datoms db :aevt :seon.root/tasks)) (count (d/datoms again :aevt :seon.root/tasks))) "and its outcome, the same value, wrote no datom"))
    (let [fixed (read db)]
      (is (not (open? fixed)) "a good read of the same bytes as before the refused one: closed, nothing written to close it")
      (is (not (open? (read fixed))) "a good read again writes nothing and it stays closed")
      (is (open? (refused fixed)) "refused again: open again"))))

(deftest a-file-gone-when-its-batch-reads-it-is-a-change
  ;; task seon.watch/a-vanished-file-is-a-fact: a git merge or bin/update removes a file for a moment
  ;; while a batch reads it (the standing root's watcher died: "its batch handler threw:
  ;; .../docs/references.md (No such file or directory)"). A file listed and gone when read (here a
  ;; page replaced by a link to nothing, which lists and does not open) is left out of that read: the
  ;; watcher stays up, and the load records the page as gone, as it would a deletion
  (let [co (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-watch-" (random-uuid))) (.mkdirs)))
        docs (doto (io/file co "docs") (.mkdirs))
        page (io/file docs "a.md")
        _ (spit page "# A\n\nIt reads.\n")
        _ (spit (io/file docs "b.md") "# B\n\nIt stays.\n")
        db0 (ts/test-db)
        db (d/db-with db0 (seon.docs/load-dir-tx db0 co "docs" {}))
        _ (is (some? (d/entity db [:seon.section/address "docs/a.md#a"])) "the page was loaded")
        _ (.delete page)
        _ (java.nio.file.Files/createSymbolicLink (.toPath page) (.toPath (io/file docs "gone.md")) (make-array java.nio.file.attribute.FileAttribute 0))
        ;; whichever comes first: the batch's read, or the watcher's death (on the old read, the throw)
        outcome (promise)
        w (watch/start! [(.getCanonicalPath docs)]
                        (fn [_] (deliver outcome [:read (seon.docs/load-dir-tx db co "docs" {})]))
                        #(deliver outcome [:died %]))]
    (is (not (contract/error? w)) (pr-str w))
    ;; a watcher that never started answers as a death, so the wait below always ends
    (when (contract/error? w) (deliver outcome [:died w]))
    (try
    (spit (io/file docs "b.md") "# B\n\nIt changed.\n")
    (let [[kind tx] @outcome]
      (testing "the batch read what was there and the watcher is still up"
        (is (= :read kind) (str "no death: a vanished file never ends the watch; " (pr-str tx)))
        (is (vector? tx) (pr-str tx)))
      (testing "the page gone when read is recorded as gone: its section retracted, the other page's change loaded"
        (let [db' (d/db-with db tx)]
          (is (nil? (d/entity db' [:seon.section/address "docs/a.md#a"])))
          (is (= "It changed." (:seon.section/lead (d/pull db' [:seon.section/lead] [:seon.section/address "docs/b.md#b"])))))))
    (finally
      (when-not (contract/error? w) (watch/stop! w))
      (clojure.java.shell/sh "rm" "-rf" co)))))
