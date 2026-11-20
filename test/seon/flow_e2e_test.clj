(ns seon.flow-e2e-test
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.main :as main]
            [seon.web :as web]
            [seon.store :refer [rm-rf!]]))

(defonce shared-test-router
  (delay (main/make-fork-router (fork/start-fork-parent!))))

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
                           :body "(ns app.hello)\n(defn double-it {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))\n(ns app.hello-test (:require [clojure.test :refer [deftest is]] [app.hello :as h]))\n(deftest double-test (is (= 10 (h/double-it 5))))\n(in-ns 'user)"})
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
                           :body "(ns app.hello)\n(defn double-it {:malli/schema [:=> [:cat :int] :int]} [x] (* x 2))\n(in-ns 'user)"})

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
            (is (str/includes? (slurp (io/file repo-dir "src/app/hello.clj")) "(defn double-it {:malli/schema [:=> [:cat :int] :int]} [x] (* x 2))"))
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

(deftest ^{:cost/ticks 400000000} flow-native-calls-await-cycles-schema-and-cancellation-test
  (testing "Flow-native :seon.agent.call/* supports parallel ask!/await!, cycle rejection, Malli answer validation, paused-callee auto-fail, and cascading cancel"
    (let [dir (str "/tmp/seon-e2e-calls-" (System/nanoTime))
          sys (main/start-flow-instance!
               @shared-test-router
               {:store-path dir
                :port 0
                :model-provider {:type :built-in}
                :latency-ms 5000})]
      (try
        ;; 1. Spawn coordinator + 2 active workers + 1 paused worker
        (is (:ok? (main/call! sys {:op :spawn-agent :id :ag/coord :role :coordinator :scope 'seon :config {:seon.agent/paused? true}})))
        (is (:ok? (main/call! sys {:op :spawn-agent :id :ag/a :parent :ag/coord :role :worker :scope 'seon.code})))
        (is (:ok? (main/call! sys {:op :spawn-agent :id :ag/b :parent :ag/coord :role :worker :scope 'seon.web})))
        (is (:ok? (main/call! sys {:op :spawn-agent :id :ag/paused :parent :ag/coord :role :worker :scope 'seon.git :config {:seon.agent/paused? true}})))

        (let [c1 (main/call! sys {:op :create-call
                                  :caller :ag/coord
                                  :callee :ag/a
                                  :prompt "Return a health report"
                                  :result-schema :seon.health/report})
              c2 (main/call! sys {:op :create-call
                                  :caller :ag/coord
                                  :callee :ag/b
                                  :prompt "Check web unit"})
              cid1 (:call-id c1)
              cid2 (:call-id c2)]
          (is (:ok? c1))
          (is (:ok? c2))

          ;; 2. Deadlock cycle rejection at dispatch time (< 1 ms, zero waiting!)
          (let [cyc (main/call! sys {:op :create-call
                                     :caller :ag/a
                                     :callee :ag/coord
                                     :parent-call cid1
                                     :prompt "Cycle back to caller"})]
            (is (not (:ok? cyc)))
            (is (= :cycle (get-in cyc [:error :class]))))

          ;; 3. Malli schema validation on :answer-call rejects malformed answer without settling!
          (let [bad (main/call! sys {:op :answer-call :call-id cid1 :callee :ag/a :result {:wrong true}})]
            (is (not (:ok? bad)))
            (is (= :schema-mismatch (get-in bad [:error :class]))))

          ;; 4. Await both calls -> :ag/coord suspends cleanly (:status :awaiting, 0 blocked threads!)
          (let [aw (main/call! sys {:op :await-calls :caller :ag/coord :call-ids [cid1 cid2]})]
            (is (:ok? aw))
            (is (false? (:settled? aw))))

          ;; 5. Answer c1 with valid :seon.health/report, fail c2 explicitly -> reconciler delivers BOTH in 1 :call-result!
          (let [ok1 (main/call! sys {:op :answer-call
                                     :call-id cid1
                                     :callee :ag/a
                                     :result {:ok? true :branch :main :counts {} :problems []}})
                f2  (main/call! sys {:op :fail-call
                                     :call-id cid2
                                     :callee :ag/b
                                     :error {:class :out-of-scope :suggest :ns/seon.render}})]
            (is (:ok? ok1))
            (is (:ok? f2)))

          ;; Verify reconciler cleared :seon.agent/awaiting and delivered :type :call-result to :ag/coord!
          (is (await-until
               (fn []
                 (let [db @(:conn sys)
                       aw (:seon.agent/awaiting (d/pull db '[:seon.agent/awaiting] [:seon.agent/id :ag/coord]))
                       msgs (map #(d/pull db '[:seon.agent.message/type :seon.agent.message/body] (:e %))
                                 (d/datoms db :avet :seon.agent.message/to :ag/coord))]
                   (and (empty? aw)
                        (some #(= :call-result (:seon.agent.message/type %)) msgs))))
               5000))

          ;; 6. Zero-timer auto-failure when calling a paused callee!
          (let [pc (main/call! sys {:op :create-call :caller :ag/coord :callee :ag/paused :prompt "Should auto-fail"})]
            (is (:ok? pc))
            (is (await-until
                 (fn []
                   (let [c (d/pull @(:conn sys) '[:seon.agent.call/status :seon.agent.call/error]
                                   [:seon.agent.call/id (:call-id pc)])]
                     (and (= :failed (:seon.agent.call/status c))
                          (= :callee-paused (get-in c [:seon.agent.call/error :class])))))
                 5000)))

          ;; 7. Cascading cancellation down :parent-call tree
          (let [pcall (main/call! sys {:op :create-call :caller :ag/coord :callee :ag/a :prompt "Parent work"})
                child (main/call! sys {:op :create-call :caller :ag/a :callee :ag/b :parent-call (:call-id pcall) :prompt "Sub work"})
                canc  (main/call! sys {:op :cancel-call :call-id (:call-id pcall) :reason "user cancelled"})]
            (is (:ok? canc))
            (is (= #{(:call-id pcall) (:call-id child)} (set (:cancelled canc))))
            (is (= :cancelled (:seon.agent.call/status (d/pull @(:conn sys) '[:seon.agent.call/status] [:seon.agent.call/id (:call-id child)]))))))
      (finally
        (main/stop! sys {:close-fork-parent? false})
        (rm-rf! (io/file dir)))))))

(deftest data-driven-ui-lazy-sync-and-recursive-delegation-test
  (let [dir (str "/tmp/seon-e2e-dd-" (System/nanoTime))
        sys (main/start-flow-instance! @shared-test-router {:store-path dir :port 0 :model-provider {:type :built-in}})]
    (try
      (let [conn (:conn sys)
            extra {:seon.agent/entry? [:boolean {:seon.db/index true}]
                   :seon.agent/label :string
                   :seon.agent/hero-title :string
                   :seon.agent/read-branch :keyword
                   :seon.agent/router? :boolean
                   :seon.agent/seed-path :string
                   :seon.agent/entity-extra
                   [:map {:seon.db/attributes true}
                    [:seon.agent/entry? {:optional true} :seon.agent/entry?]
                    [:seon.agent/label {:optional true} :seon.agent/label]
                    [:seon.agent/hero-title {:optional true} :seon.agent/hero-title]
                    [:seon.agent/read-branch {:optional true} :seon.agent/read-branch]
                    [:seon.agent/router? {:optional true} :seon.agent/router?]
                    [:seon.agent/seed-path {:optional true} :seon.agent/seed-path]]}
            reg (seon.schema/registry-for-db @conn extra)
            missing (remove #(contains? (:schema @conn) (:db/ident %)) (:declarations reg))]
        (when (seq missing)
          (d/transact conn {:tx-data (vec missing)})))

      (testing "1. Data-driven UI works for arbitrary custom entry agent with ZERO :agent/root entity"
        (is (:ok? (main/call! sys {:op :transact
                                   :tx-data [{:db/id "ns/demo" :seon.code.ns/name 'demo :seon.code.ns/doc "Demo root"}
                                             {:db/id "ns/demo.a" :seon.code.ns/name 'demo.a :seon.code.ns/parent "ns/demo" :seon.code.ns/doc "Sub A"}
                                             {:seon.agent/id :agent/atlas
                                              :seon.agent/entry? true
                                              :seon.agent/label "Atlas"
                                              :seon.agent/hero-title "Ask Atlas anything"
                                              :seon.agent/branch :branch/atlas
                                              :seon.agent/read-branch :main
                                              :seon.agent/router? true
                                              :seon.agent/role :coordinator
                                              :seon.agent/scope 'demo
                                              :seon.agent/focus "ns/demo"
                                              :seon.agent/system-prompt "Custom intermediary instructions."
                                              :seon.agent/render-fn 'seon.render.ui/agent-canvas
                                              :seon.agent/paused? true
                                              :seon.agent/waiting? true}]})))
        (let [db @(:conn sys)
              vs (seon.render.ui/user-view-state db)
              hdr (seon.render/hiccup->html (seon.render.ui/header-unit db {:boot-seq 1 :agent-states {}}))
              cvs (seon.render/hiccup->html (seon.render.ui/view-unit db {:conn (:conn sys)}))
              wire (seon.render.ui/build-live-wire-request db db :agent/atlas {})]
          (is (= :agent/atlas (:viewing-id vs)))
          (is (str/includes? hdr "Atlas"))
          (is (str/includes? cvs "Ask Atlas anything"))
          (is (= "Custom intermediary instructions." (:content (first (:messages wire)))))))

      (testing "2. Warm sleeping agent lazily loads newly merged :main code only when it wakes to eval"
        (let [ev1 (main/call! sys {:op :eval :branch :branch/lazy-w :agent-id :agent/lazy-w :keep-warm? true
                                   :code "(ns seon.lazy.local) (defn mine [] :local-v1) (mine)"})]
          (is (:ok? ev1))
          (is (= ":local-v1" (:value ev1)))
          (let [ev2 (main/call! sys {:op :eval :branch :branch/pub-main
                                     :code "(ns seon.lazy.shared) (defn greet {:malli/schema [:=> [:cat :string] :string]} [x] (str \"hello-\" x))"})
                m (main/call! sys {:op :merge :branch :branch/pub-main})]
            (is (:ok? ev2))
            (is (:ok? m))
            (let [ev3 (main/call! sys {:op :eval :branch :branch/lazy-w :agent-id :agent/lazy-w :keep-warm? true
                                       :code "[(seon.lazy.shared/greet \"world\") (seon.lazy.local/mine)]"})]
              (is (:ok? ev3))
              (is (= "[\"hello-world\" :local-v1]" (:value ev3)))))))

      (testing "3. 3-level recursive delegation (coord -> [mid-a, mid-b] -> [leaf-1..4]) flows results back up cleanly"
        ;; Coordinator paused; mid/leaf active (so reconciler won't auto-fail them with :callee-paused!)
        (is (:ok? (main/call! sys {:op :spawn-agent :id :rec/coord :role :coordinator :scope 'demo :config {:seon.agent/paused? true}})))
        (doseq [[id parent] [[:rec/mid-a :rec/coord] [:rec/mid-b :rec/coord]
                             [:rec/leaf-1 :rec/mid-a] [:rec/leaf-2 :rec/mid-a]
                             [:rec/leaf-3 :rec/mid-b] [:rec/leaf-4 :rec/mid-b]]]
          (is (:ok? (main/call! sys {:op :spawn-agent :id id :parent parent :role :worker :scope 'demo}))))
        (let [c-a (main/call! sys {:op :create-call :caller :rec/coord :callee :rec/mid-a :prompt "Part A"})
              c-b (main/call! sys {:op :create-call :caller :rec/coord :callee :rec/mid-b :prompt "Part B"})
              _ (is (:ok? (main/call! sys {:op :await-calls :caller :rec/coord :call-ids [(:call-id c-a) (:call-id c-b)]})))
              c-1 (main/call! sys {:op :create-call :caller :rec/mid-a :callee :rec/leaf-1 :parent-call (:call-id c-a) :prompt "L1"})
              c-2 (main/call! sys {:op :create-call :caller :rec/mid-a :callee :rec/leaf-2 :parent-call (:call-id c-a) :prompt "L2"})
              _ (is (:ok? (main/call! sys {:op :await-calls :caller :rec/mid-a :call-ids [(:call-id c-1) (:call-id c-2)]})))
              c-3 (main/call! sys {:op :create-call :caller :rec/mid-b :callee :rec/leaf-3 :parent-call (:call-id c-b) :prompt "L3"})
              c-4 (main/call! sys {:op :create-call :caller :rec/mid-b :callee :rec/leaf-4 :parent-call (:call-id c-b) :prompt "L4"})
              _ (is (:ok? (main/call! sys {:op :await-calls :caller :rec/mid-b :call-ids [(:call-id c-3) (:call-id c-4)]})))]
          (let [cvs-html (seon.render/hiccup->html (seon.render.ui/agent-canvas @(:conn sys) {:conn (:conn sys) :viewing-id :rec/coord}))]
            (is (str/includes? cvs-html ":rec/mid-a"))
            (is (str/includes? cvs-html ":rec/leaf-1"))
            (is (str/includes? cvs-html ":rec/leaf-4")))
          (doseq [[cid val] [[(:call-id c-1) {:v 10}] [(:call-id c-2) {:v 20}]
                             [(:call-id c-3) {:v 30}] [(:call-id c-4) {:v 40}]]]
            (is (:ok? (main/call! sys {:op :answer-call :call-id cid :result val}))))
          (is (await-until (fn []
                             (let [db @(:conn sys)
                                   ma (d/pull db '[:seon.agent/awaiting] [:seon.agent/id :rec/mid-a])
                                   mb (d/pull db '[:seon.agent/awaiting] [:seon.agent/id :rec/mid-b])]
                               (and (empty? (:seon.agent/awaiting ma))
                                    (empty? (:seon.agent/awaiting mb)))))
                           4000))
          (is (:ok? (main/call! sys {:op :answer-call :call-id (:call-id c-a) :result {:sum 30}})))
          (is (:ok? (main/call! sys {:op :answer-call :call-id (:call-id c-b) :result {:sum 70}})))
          (is (await-until (fn []
                             (let [db @(:conn sys)
                                   co (d/pull db '[:seon.agent/awaiting] [:seon.agent/id :rec/coord])
                                   msgs (map #(d/pull db '[:seon.agent.message/type :seon.agent.message/body] (:e %))
                                             (d/datoms db :avet :seon.agent.message/to :rec/coord))]
                               (and (empty? (:seon.agent/awaiting co))
                                    (some #(and (= :call-result (:seon.agent.message/type %))
                                                (str/includes? (:seon.agent.message/body %) ":sum 30")
                                                (str/includes? (:seon.agent.message/body %) ":sum 70"))
                                          msgs))))
                           4000))))
      (finally
        (main/stop! sys)
        (rm-rf! (io/file dir))))))

(deftest fresh-install-vs-resume-and-switchover-failure-rollback-test
  (let [root-tmp (str "/tmp/seon-fresh-test-" (System/nanoTime))
        repo-dir (str root-tmp "/repo")
        store-dir (str root-tmp "/store")
        src-root seon.store/checkout-root]
    (try
      ;; 1. Prepare a real clean Git repo with declarative config, schemas, agents, and tasks
      (.mkdirs (io/file repo-dir "resources/seon/config"))
      (.mkdirs (io/file repo-dir "resources/seon/schemas"))
      (.mkdirs (io/file repo-dir "resources/seon/agents"))
      (.mkdirs (io/file repo-dir "resources/seon/tasks"))
      (doseq [rel ["resources/seon/config/default.edn"
                   "resources/seon/agents/root.edn"
                   "resources/seon/agents/examples.edn"
                   "resources/seon/tasks.edn"
                   "resources/seon/tasks/seon.web.edn"
                   "resources/seon/schemas.edn"]]
        (let [src-f (io/file src-root rel)
              dst-f (io/file repo-dir rel)]
          (when (.isFile src-f)
            (.mkdirs (.getParentFile dst-f))
            (spit dst-f (slurp src-f)))))
      (let [sdir (io/file src-root "resources/seon/schemas")]
        (when (.isDirectory sdir)
          (doseq [^java.io.File sf (.listFiles sdir)
                  :when (str/ends-with? (.getName sf) ".edn")]
            (spit (io/file repo-dir "resources/seon/schemas" (.getName sf)) (slurp sf)))))
      (git/git! repo-dir "init" "-b" "main")
      (git/git! repo-dir "config" "user.name" "SeonTest")
      (git/git! repo-dir "config" "user.email" "test@local")
      (git/git! repo-dir "add" "-A")
      (git/git! repo-dir "commit" "-m" "Fresh declarative seed")

      (testing "1. Fresh install bootstraps config, schemas, tasks, examples & data-driven :agent/root from Git"
        (let [sys (main/start-flow-instance! @shared-test-router
                                             {:store-path store-dir :repo-dir repo-dir :commit-git? false :port 0})]
          (try
            (main/ensure-boot-state! sys repo-dir)
            (let [db @(:conn sys)
                  root-ag (d/pull db '[*] [:seon.agent/id :agent/root])
                  user-ent (d/pull db '[:seon.user/id {:seon.user/viewing [:seon.agent/id]}] [:seon.user/id :user/default])
                  task-ent (d/pull db '[:seon.task/id :seon.task/title] [:seon.task/id :seon.web/split-oversized-namespace])]
              (is (= :agent/root (:seon.agent/id root-ag)))
              (is (true? (:seon.agent/entry? root-ag)))
              (is (= "Root" (:seon.agent/label root-ag)))
              (is (true? (:seon.agent/router? root-ag)))
              (is (= :main (:seon.agent/read-branch root-ag)))
              (is (not (str/blank? (str (:seon.agent/system-prompt root-ag)))))
              (is (= :agent/root (get-in user-ent [:seon.user/viewing :seon.agent/id])))
              (is (= :seon.web/split-oversized-namespace (:seon.task/id task-ent)))
              (is (true? (main/drain-for-handover! sys 1000))))
            ;; Mutate runtime state (memory + canvas) before shutting down to test resume preservation
            (is (:ok? (main/call! sys {:op :transact
                                       :tx-data [{:seon.agent/id :agent/root
                                                  :seon.agent/memory ["Survives resume!"]
                                                  :seon.agent/canvas {:title "Persisted View"}}]})))
            (finally
              (main/stop! sys)))))

      (testing "2. Resuming existing store at same git HEAD reads 0 files and preserves all runtime state"
        (let [sys2 (main/start-flow-instance! @shared-test-router
                                              {:store-path store-dir :repo-dir repo-dir :commit-git? false :port 0})]
          (try
            (main/ensure-boot-state! sys2 repo-dir)
            (let [db @(:conn sys2)
                  catchup (git/catch-up-tx db repo-dir)
                  root-ag (d/pull db '[:seon.agent/memory :seon.agent/canvas :seon.agent/label] [:seon.agent/id :agent/root])]
              (is (empty? catchup))
              (is (= ["Survives resume!"] (:seon.agent/memory root-ag)))
              (is (= {:title "Persisted View"} (:seon.agent/canvas root-ag)))
              (is (= "Root" (:seon.agent/label root-ag))))
            (finally
              (main/stop! sys2)))))

      (testing "3. Atomic switch-over refuses to spawn successor when build/--self-test fails (zero downtime on failure)"
        (let [spawned (volatile! nil)
              fail-res (seon.switchover/run-switch-over!
                        {:repo-dir repo-dir
                         :store-path store-dir
                         :port 9998
                         :build-fn (fn [_] {:ok? false :stage :build :error "simulated compile failure"})
                         :spawn-fn (fn [opts] (vreset! spawned opts) {:ok? true})})]
          (is (false? (:ok? fail-res)))
          (is (= :build (:stage fail-res)))
          (is (nil? @spawned)))
        (let [spawned (volatile! nil)
              ok-res (seon.switchover/run-switch-over!
                      {:repo-dir repo-dir
                       :store-path store-dir
                       :port 9998
                       :build-fn (fn [_] {:ok? true :stage :build})
                       :spawn-fn (fn [opts] (vreset! spawned opts) {:ok? true :stage :spawn :port (:port opts)})})]
          (is (true? (:ok? ok-res)))
          (is (true? (:swapping? ok-res)))
          (is (= {:repo-dir repo-dir :store-path store-dir :port 9998} @spawned))))
      (finally
        (rm-rf! (io/file root-tmp))))))
