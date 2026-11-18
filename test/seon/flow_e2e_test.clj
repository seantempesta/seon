(ns seon.flow-e2e-test
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.main :as main]
            [seon.web :as web]))

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

(deftest branch-eval-generative-seed42-cost-overrun-and-cache-test
  (let [dir (str "/tmp/seon-e2e-1-" (System/nanoTime))
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. Register multi-segment Malli schema + contracted defn + deftest -> runs deftest & 100x seed=42 check!"
        (let [reg (main/call! sys {:op :register-schema
                                   :key :seon.calc/pos-int
                                   :form [:int {:min 1 :max 500}]})
              _ (is (:ok? reg))
              code (str "(ns seon.calc.core)\n"
                        "(defn cube {:malli/schema [:=> [:cat :seon.calc/pos-int] [:int {:min 1}]]} [x] (* x x x))\n"
                        "(ns seon.calc.core-test (:require [clojure.test :refer [deftest is]] [seon.calc.core :as c]))\n"
                        "(deftest cube-test (is (= 27 (c/cube 3))))")
              ev (main/call! sys {:op :eval :branch :branch/calc :code code})]
          (is (:ok? ev)))
        (let [t1 (main/call! sys {:op :test :branch :branch/calc})]
          (is (:ok? t1))
          (is (= 2 (:ran t1)))
          (is (= #{:deftest :generative} (into #{} (map :seon.test/kind) (:results t1)))))
        (let [m (main/call! sys {:op :merge :branch :branch/calc})
              _ (is (:ok? m))
              t2 (main/call! sys {:op :test :branch :main})]
          (is (:ok? t2))
          (is (= 0 (:ran t2)))
          (is (= 2 (:cached t2)))))

      (testing "2. Broken schema contract & unrealistic `:cost/ticks` estimate are caught automatically in `:test`!"
        (let [bad-gen (str "(ns seon.calc.broken)\n"
                           "(defn broken-abs {:malli/schema [:=> [:cat [:int {:min -50 :max 50}]] [:int {:min 1}]]}\n"
                           "  [x] (Math/abs (long x)))")
              _ (main/call! sys {:op :eval :branch :branch/bad-gen :code bad-gen})
              tg (main/call! sys {:op :test :branch :branch/bad-gen})]
          (is (false? (:ok? tg))))

        (let [bad-cost (str "(ns seon.calc.cost-test (:require [clojure.test :refer [deftest is]]))\n"
                            "(deftest ^{:cost/ticks 10} tiny-budget-test\n"
                            "  (is (= 499500 (reduce + (range 1000)))))")
              _ (main/call! sys {:op :eval :branch :branch/bad-cost :code bad-cost})
              tc (main/call! sys {:op :test :branch :branch/bad-cost})]
          (is (false? (:ok? tc)))
          (is (some #(str/includes? (:seon.test/report %) "COST OVERRUN") (:results tc)))))

      (testing "3. Reach graph automatically flags `:boot-affected?` when boot-root code or `deps.edn` changes!"
        (main/call! sys {:op :import-file :branch :branch/boot-change :path "deps.edn" :text "{:paths [\"src\"]}\n"})
        (let [tb (main/call! sys {:op :test :branch :branch/boot-change})]
          (is (true? (:boot-affected? tb)))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest persistent-agent-runtime-autonomous-tree-and-cold-reboot-test
  (let [dir (str "/tmp/seon-e2e-tree-" (System/nanoTime))
        sys1 (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
    (try
      (testing "1. Coordinator + 2 scoped workers run persistent warm OS runtimes across turns, then release to 0 PIDs once `:merged`!"
        (let [created (main/call! sys1 {:op :create-task
                                        :id :seon.auto/tree1
                                        :scope 'seon.app.tree
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
            (is (some? done-state))
            (is (every? #(nil? (:live-pid %)) (:agents done-state))))))

      (testing "2. Simulate Power Loss -> cold-booted Flow #2 resumes runnable agent seamlessly from LMDB!"
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
        (d/transact (:conn sys1) {:tx-data [{:seon.agent/id :recover/worker :seon.agent/paused? false}]})
        (main/stop! sys1)

        (let [sys2 (main/start-flow-instance! @shared-test-router {:store-path dir :port 0})]
          (try
            (let [recovered
                  (await-until
                   (fn []
                     (let [st (main/call! sys2 {:op :status})
                           ag (some #(when (= :recover/worker (:id %)) %) (:agents st))]
                       (when (= :merged (:status ag)) ag)))
                   12000)]
              (is (some? recovered)))
            (finally
              (main/stop! sys2)))))
      (finally
        (rm-rf! (io/file dir))))))

(deftest self-healing-red-test-and-two-parent-local-git-sync-test
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
          (main/call! sys {:op :spawn-agent
                           :id :fix/bot
                           :branch :branch/fix.bot
                           :role :worker
                           :goal ""})
          (main/call! sys {:op :send-message
                           :from :user
                           :to :fix/bot
                           :type :task
                           :body "(ns app.hello)\n(defn double-it [x] (+ x 1))\n(ns app.hello-test (:require [clojure.test :refer [deftest is]] [app.hello :as h]))\n(deftest double-test (is (= 10 (h/double-it 5))))\n(in-ns 'user)"})
          (let [saw-fail?
                (await-until
                 (fn []
                   (let [db @(:conn sys)]
                     (some #(= :test-failure (:v %)) (d/datoms db :aevt :seon.agent.message/type))))
                 10000)]
            (is saw-fail?)
            (is (some? (git/ref-commit repo-dir "refs/heads/seon/fix.bot"))))

          (main/call! sys {:op :send-message
                           :from :user
                           :to :fix/bot
                           :type :fix
                           :body "(ns app.hello)\n(defn double-it [x] (* x 2))\n(in-ns 'user)"})

          (let [merged?
                (await-until
                 (fn []
                   (let [st (main/call! sys {:op :status})
                         ag (some #(when (= :fix/bot (:id %)) %) (:agents st))]
                     (when (and (= :merged (:status ag))
                                (not= sha0 (git/head-commit repo-dir)))
                       true)))
                 12000)]
            (is merged?)
            (is (str/includes? (slurp (io/file repo-dir "src/app/hello.clj")) "(defn double-it [x] (* x 2))"))
            (is (str/blank? (str/trim (git/git! repo-dir "status" "--porcelain"))))
            (let [parents (-> (git/git! repo-dir "log" "-1" "--pretty=%P" "HEAD") str/trim (str/split #"\s+"))]
              (is (= 2 (count parents)))))
          (finally
            (main/stop! sys))))
      (finally
        (rm-rf! (io/file repo-dir))
        (rm-rf! (io/file store-dir))))))

(deftest http-datastar-listener-and-live-handover-test
  (let [dir (str "/tmp/seon-http-reboot-" (System/nanoTime))
        listener (web/start-http-listener! -1)]
    (try
      (let [active (volatile! nil)
            handed-over? (promise)
            reboot! (fn reboot! []
                      (let [old @active
                            n (inc (:boot-seq old 1))]
                        (main/stop! old {:close-fork-parent? false})
                        (vreset! active
                                 (assoc (main/start-flow-instance!
                                         @shared-test-router
                                         {:store-path dir :listener listener :boot-seq n :on-power-loss reboot!})
                                        :boot-seq n))))
            hand-over! (fn [reply-ch]
                         (when-let [cur @active]
                           (vreset! active nil)
                           (main/stop! cur {:close-fork-parent? false})
                           (a/>!! reply-ch {:ok? true :released true :port (:port listener)})
                           (a/close! reply-ch)
                           (deliver handed-over? true)))
            sys1 (main/start-flow-instance!
                  @shared-test-router
                  {:store-path dir :listener listener :boot-seq 1 :on-power-loss reboot! :on-hand-over hand-over!})]
        (vreset! active (assoc sys1 :boot-seq 1))
        (try
          (let [page-resp ((:handler listener) {:request-method :get :uri "/"})
                sse-resp ((:handler listener) {:request-method :get :uri "/events"})
                sse-ch (:body sse-resp)]
            (is (= 200 (:status page-resp)))
            (is (str/includes? (:body page-resp) "/datastar.js"))
            (reboot!)
            (reboot!)
            (is (= 3 (:boot-seq @active)))
            (let [saw-boot3? (await-until
                              (fn []
                                (when-let [frame (a/poll! sse-ch)]
                                  (and (str/includes? frame "event: datastar-patch-elements")
                                       (str/includes? frame "\"bootSeq\":3"))))
                              3000)]
              (is saw-boot3?)))

          (main/call! @active {:op :create-task :id :seon.handover/keep-me :title "Must survive binary swap-over"})
          (main/stop! @active {:close-fork-parent? false})
          (vreset! active (main/start-flow-instance!
                           @shared-test-router
                           {:store-path dir :listener listener :boot-seq 4 :on-hand-over hand-over!}))
          (let [ho-ans (main/call! @active {:op :hand-over})]
            (is (:released ho-ans))
            (is (true? (deref handed-over? 3000 false)))
            (let [successor (main/start-flow-instance!
                             @shared-test-router
                             {:store-path dir :listener listener :boot-seq 1})]
              (vreset! active successor)
              (let [st (main/call! successor {:op :status})]
                (is (some #(= :seon.handover/keep-me (:seon.task/id %)) (:tasks st))))))
          (finally
            (when-let [cur @active]
              (main/stop! cur {:close-fork-parent? false})))))
      (finally
        ((:stop! listener))
        (rm-rf! (io/file dir))))))
