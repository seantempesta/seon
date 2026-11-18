(ns seon.flow-e2e-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.main :as main]))

(defonce shared-test-router
  (delay (main/make-fork-router (fork/start-fork-parent!))))

(defn rm-rf! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn await-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [v (pred)]
        v
        (if (> (System/currentTimeMillis) deadline)
          nil
          (do (Thread/sleep 25) (recur)))))))

(deftest branch-eval-reach-test-cache-and-merge-test
  (let [dir (str "/tmp/seon-e2e-1-" (System/nanoTime))
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. Evaluate real Clojure ns + defn + deftest on :branch/calc"
        (let [code (str "(ns calc.core)\n"
                        "(defn cube [x] (* x x x))\n"
                        "(ns calc.core-test (:require [clojure.test :refer [deftest is]] [calc.core :as c]))\n"
                        "(deftest cube-test (is (= 27 (c/cube 3))))")
              ev (main/call! sys {:op :eval :branch :branch/calc :code code})]
          (is (:ok? ev))))

      (testing "2. First :test run executes cube-test in forked COW worker; second run hits 100% Datahike digest cache!"
        (let [t1 (main/call! sys {:op :test :branch :branch/calc})]
          (is (:ok? t1))
          (is (= 1 (:ran t1)))
          (is (= 0 (:cached t1))))
        ;; Merge to :main so :test/digest is on :main, then re-test!
        (let [m (main/call! sys {:op :merge :branch :branch/calc})]
          (is (:ok? m))
          (is (= #{"src/calc/core.clj" "test/calc/core_test.clj"} (:touched-paths m))))
        (let [t2 (main/call! sys {:op :test :branch :main})]
          (is (:ok? t2))
          (is (= 0 (:ran t2)))
          (is (= 1 (:cached t2)))))

      (testing "3. Merged function is hot-loaded in :main and inherited by new branches"
        (let [ev2 (main/call! sys {:op :eval :branch :branch/next :code "(calc.core/cube 4)"})]
          (is (:ok? ev2))
          (is (= "64" (:value ev2)))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest autonomous-tree-and-cold-reboot-recovery-test
  (let [dir (str "/tmp/seon-e2e-tree-" (System/nanoTime))
        sys1 (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. Spawn coordinator goal -> spawns 2 worker subagents -> workers write code, pass tests, merge -> coordinator merges"
        (let [created (main/call! sys1 {:op :create-task
                                        :id :auto/tree1
                                        :title "Build parallel helpers"
                                        :spawn-agent? true
                                        :role :coordinator})]
          (is (:ok? created))
          (let [done-state
                (await-until
                 (fn []
                   (let [st (main/call! sys1 {:op :status})
                         ags (:agents st)]
                     (when (and (>= (count ags) 3)
                                (every? #(= :merged (:status %)) ags))
                       st)))
                 15000)]
            (is (some? done-state) "All 3 agents (coordinator + 2 workers) should reach :merged!")
            ;; Verify 0 live forked OS processes remain when idle!
            (is (every? #(nil? (:live-pid %)) (:agents done-state))))))

      (testing "2. Simulate Power Loss: write paused runnable agent, tear down Flow in RAM, unpause & cold-boot fresh Flow -> auto-resumes!"
        (main/call! sys1 {:op :spawn-agent
                          :id :recover/worker
                          :branch :branch/recover.worker
                          :role :worker
                          :goal ""})
        (main/call! sys1 {:op :toggle-pause-agent :agent-id :recover/worker})
        (main/call! sys1 {:op :send-message
                          :from :user
                          :to :recover/worker
                          :type :task
                          :body "Survive cold reboot!"})
        ;; Unpause directly in DB right as we kill sys1 before reconciler runs it, simulating power loss mid-queue!
        (d/transact (:conn sys1) {:tx-data [{:agent/id :recover/worker :agent/paused? false}]})
        (main/stop! sys1)

        ;; Boot brand-new Flow #2 on the EXACT SAME LMDB directory!
        (let [sys2 (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
          (try
            (let [recovered
                  (await-until
                   (fn []
                     (let [st (main/call! sys2 {:op :status})
                           ag (some #(when (= :recover/worker (:id %)) %) (:agents st))]
                       (when (= :merged (:status ag)) ag)))
                   12000)]
              (is (some? recovered) "Cold-booted Flow #2 must derive runnable state from LMDB and finish :recover/worker!"))
            (finally
              (main/stop! sys2)))))
      (finally
        (rm-rf! (io/file dir))))))

(deftest self-healing-red-test-and-git-sync-test
  (let [repo-dir (str "/tmp/seon-git-repo-" (System/nanoTime))
        store-dir (str "/tmp/seon-git-store-" (System/nanoTime))]
    (try
      (.mkdirs (io/file repo-dir "src/app"))
      (spit (io/file repo-dir "src/app/hello.clj") "(ns app.hello)\n\n(defn greet [x] (str \"hi \" x))\n")
      (git/git! repo-dir "init" "-b" "main")
      (git/git! repo-dir "config" "user.email" "test@seon.local")
      (git/git! repo-dir "config" "user.name" "Seon Test")
      (git/git! repo-dir "add" ".")
      (git/git! repo-dir "commit" "-m" "Initial commit")
      (let [sha0 (git/head-commit repo-dir)
            sys (main/start-flow-instance! @shared-test-router
                                           {:store-path store-dir
                                            :repo-dir repo-dir
                                            :commit-git? true
                                            :port 0})]
        (try
          (testing "1. Cold bootstrap imported src/app/hello.clj & recorded git HEAD"
            (let [st (main/call! sys {:op :status})]
              (is (= sha0 (:git st)))
              (is (pos? (:forms st)))))

          (testing "2. Self-healing agent: red deftest triggers :test-failure message -> next message fixes it -> merges to git main!"
            (main/call! sys {:op :spawn-agent
                             :id :fix/bot
                             :branch :branch/fix.bot
                             :role :worker
                             :goal ""})
            ;; Send intentionally failing test first!
            (main/call! sys {:op :send-message
                             :from :user
                             :to :fix/bot
                             :type :task
                             :body "(ns app.hello)\n(defn double-it [x] (+ x 1))\n(ns app.hello-test (:require [clojure.test :refer [deftest is]] [app.hello :as h]))\n(deftest double-test (is (= 10 (h/double-it 5))))\n(in-ns 'user)"})
            ;; Wait until `:test` catches the failure and sends `:test-failure` back, then send the fix!
            (let [saw-fail?
                  (await-until
                   (fn []
                     (let [db @(:conn sys)]
                       (some #(= :test-failure (:v %)) (d/datoms db :aevt :message/type))))
                   10000)]
              (is saw-fail? ":test proc must catch failing deftest and emit :test-failure message!"))

            ;; Send fix instruction!
            (main/call! sys {:op :send-message
                             :from :user
                             :to :fix/bot
                             :type :fix
                             :body "(ns app.hello)\n(defn double-it [x] (* x 2))\n(in-ns 'user)"})

            ;; Zero-worktree git ref diff works before or after merge, and git main commits cleanly on merge!
            (let [merged?
                  (await-until
                   (fn []
                     (let [st (main/call! sys {:op :status})
                           ag (some #(when (= :fix/bot (:id %)) %) (:agents st))]
                       (when (and (= :merged (:status ag))
                                  (not= sha0 (git/head-commit repo-dir)))
                         true)))
                   12000)]
              (is merged? "Fixed branch must pass :test, merge to Datahike :main, and commit to git main!")
              (is (str/includes? (slurp (io/file repo-dir "src/app/hello.clj")) "(defn double-it [x] (* x 2))"))
              (is (str/blank? (str/trim (git/git! repo-dir "status" "--porcelain")))
                  "Git working tree must remain 100% clean after write-back commit!")))
          (finally
            (main/stop! sys))))
      (finally
        (rm-rf! (io/file repo-dir))
        (rm-rf! (io/file store-dir))))))
