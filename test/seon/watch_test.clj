(ns seon.watch-test
  "Root's file watcher (seon.watch, native/watch.c over FSEvents, my.root/watch!): a change
  under a watched root arrives as its path, a batch loads what its paths' loaders read, and a
  watcher that dies is a :platform error on main. Run: bin/test seon.watch-test"
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [clojure.java.io :as io]
            [seon.watch :as watch] [my.root :as mr] [seon.root :as root] [seon.problems :as problems] [seon.contract :as contract]
            [datahike.api :as d] [seon.task]))

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
