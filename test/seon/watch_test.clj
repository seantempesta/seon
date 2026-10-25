(ns seon.watch-test
  "Root's file watcher (seon.watch, native/watch.c over FSEvents, my.root/watch!): a change
  under a watched root arrives as its path, a batch loads what its paths' loaders read, and a
  watcher that dies is a :platform error on main. Run: bin/test seon.watch-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [clojure.java.io :as io]
            [seon.watch :as watch] [my.root :as mr] [seon.root :as root] [seon.problems :as problems] [seon.contract :as contract]
            [datahike.api :as d] [seon.task] [seon.docs] [clojure.java.shell]))

(deftest a-record-is-its-kind-then-its-path
  (is (= [:path "/a/b\nc.md"] (watch/record (.getBytes "p/a/b\nc.md" "UTF-8"))) "a newline is part of the path: NUL ends a record")
  (is (= [:rescan "/a"] (watch/record (.getBytes "s/a" "UTF-8"))))
  (is (= [:root "/a"] (watch/record (.getBytes "r/a" "UTF-8"))))
  (is (= [:ready "/a"] (watch/record (.getBytes "w/a" "UTF-8")))))

(deftest a-batch-loads-what-its-paths-loaders-read
  (let [co "/c" refs "/g/refs" k #(mr/watch-kinds co refs %)]
    (is (= #{:docs} (k [[:path "/c/docs/health.md"]])))
    (is (= #{:source} (k [[:path "/c/src/seon/watch.clj"]])))
    (is (= #{} (k [[:path "/c/notes/x.md"] [:path "/c/target/seon"] [:path "/elsewhere/docs/x.md"]])) "no loader reads them: nothing loads")
    (is (= #{:source} (k [[:path "/g/refs/heads/jolt/health"]])) "a ref moved: a commit or a merge")
    (is (= #{:docs :tasks :source} (k [[:rescan "/c/docs"]])) "FSEvents coalesced or dropped events: every kind again")
    (is (= #{:docs :tasks :source} (k [[:root "/c"]])))))

(deftest a-change-arrives-and-a-dead-watcher-is-a-platform-error
  (let [dir (.getCanonicalPath (doto (io/file (str (System/getProperty "java.io.tmpdir") "/seon-watch-" (random-uuid) "/docs")) (.mkdirs)))
        got (promise) dead (promise)
        w (watch/start! [dir] (fn [b] (when (some #(= [:path (str dir "/x.md")] %) b) (deliver got b))) #(deliver dead %))]
    (is (not (contract/error? w)) (pr-str w))
    (spit (str dir "/x.md") "# X\n")
    (testing "a file written under a watched root arrives as its path"
      (is (some #(= [:path (str dir "/x.md")] %) @got)))
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
        e (contract/error :seon.watch/died 'seon.watch/start! "its batch handler threw: docs/references.md (No such file or directory): root no longer watches the checkout"
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
      (is (open? (died alive)) "it dies again: open again"))))

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
