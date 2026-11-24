(ns seon.fork-sandbox-test
  "Unit, seed=42 generative, live kernel Seatbelt sandbox, test-worker catch-up,
   ForkParent router fault surfacing, and Git ref-hygiene tests."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [seon.code :as code]
            [seon.error :as serr]
            [seon.fork :as fork]
            [seon.fork.sandbox :as sandbox]
            [seon.git :as git]
            [seon.git-proc :as git-proc]
            [seon.main :as main]
            [seon.schema :as schema]
            [seon.store :as store :refer [rm-rf!]]
            [seon.test-fixture :refer [with-test-store]]
            [seon.transactor :as transactor]))

(defn- fork-and-run!
  "Forks a single COW child, runs `(child-fn)`, returns the EDN result map, and reaps the child."
  [child-fn]
  (let [[end fd] (remote/remote-pair {:pump :fiber :buf 4})
        pid (jolt.host/fork-alone 1000)]
    (cond
      (zero? pid)
      (do (remote/close-streams-but! #{fd})
          (let [mine (remote/open fd {:pump :fiber :buf 4})]
            (try
              (a/>!! (:out mine) (child-fn))
              (catch Throwable ex
                (a/>!! (:out mine) {:error (or (.getMessage ex) (str ex))
                                    :ex-data (ex-data ex)})))
            (a/close! (:out mine))
            (a/<!! (:sent mine))
            (System/exit 0)))

      (neg? pid)
      (do (remote/close! end)
          (remote/close-fd! fd)
          {:error :fork-refused :code pid})

      :else
      (do (remote/close-fd! fd)
          (let [[res _] (a/alts!! [(:in end) (a/timeout 8000)])]
            (remote/close! end)
            (ffi/with-alloc [st-buf 4]
              (sandbox/c-waitpid pid st-buf 0))
            (or res {:error :fork-timeout}))))))

(deftest seatbelt-profile-canonicalization-escaping-and-seed42-test
  (testing "build-seatbelt-profile canonicalizes realpaths, escapes SBPL quotes/backslashes, orders deny before allow, and handles nil/equal paths"
    (let [c-tmp (.getCanonicalPath (io/file "/tmp"))
          prof (sandbox/build-seatbelt-profile "/tmp/seon-r/" "/tmp/seon-r/runs/store/")
          deny-idx (str/index-of prof "(deny file-write*")
          allow-idx (str/index-of prof "(allow file-write*")]
      (is (str/starts-with? prof "(version 1)\n(allow default)\n"))
      (is (str/includes? prof (str "(deny file-write* (subpath \"" c-tmp "/seon-r\"))")))
      (is (str/includes? prof (str "(allow file-write* (subpath \"" c-tmp "/seon-r/runs/store\"))")))
      (is (and deny-idx allow-idx (< deny-idx allow-idx))
          "Seatbelt last-match-wins requires allow store AFTER deny repo"))
    (let [esc (sandbox/escape-sbpl-string "/tmp/a\"b\\c\nd\re\u0000f")]
      (is (= "/tmp/a\\\"b\\\\c\\nd\\ref" esc))
      (is (not (str/includes? esc "\u0000"))))
    (let [q-prof (sandbox/build-seatbelt-profile "/tmp/dir\"quote\\bs" "/tmp/dir\"quote\\bs/store")]
      (is (str/includes? q-prof "dir\\\"quote\\\\bs"))
      (is (str/includes? q-prof "dir\\\"quote\\\\bs/store")))
    (testing "nil/blank repo-dir or repo-dir == store-path skips repo deny cleanly"
      (is (= "(version 1)\n(allow default)\n" (sandbox/build-seatbelt-profile nil "/tmp/store")))
      (is (= "(version 1)\n(allow default)\n" (sandbox/build-seatbelt-profile "   " "/tmp/store")))
      (is (= "(version 1)\n(allow default)\n" (sandbox/build-seatbelt-profile "/tmp/same" "/tmp/same/"))))
    (testing "ancestor store-path does not override repo deny"
      (let [p (sandbox/build-seatbelt-profile "/tmp/parent/repo" "/tmp/parent")]
        (is (str/includes? p "(deny file-write*"))
        (is (not (str/includes? p "(allow file-write*")))))
    (testing "resolve-sandbox-plan gates on sandbox?, macos?, repo-dir, and repo != store"
      (is (= {:apply? false :reason :disabled}
             (select-keys (sandbox/resolve-sandbox-plan {:sandbox? false :macos? true :repo-dir "/tmp/a" :store-path "/tmp/b"})
                          [:apply? :reason])))
      (is (= {:apply? false :reason :unsupported-os}
             (select-keys (sandbox/resolve-sandbox-plan {:sandbox? true :macos? false :repo-dir "/tmp/a" :store-path "/tmp/b"})
                          [:apply? :reason])))
      (is (= {:apply? false :reason :no-repo-dir}
             (select-keys (sandbox/resolve-sandbox-plan {:sandbox? true :macos? true :repo-dir nil :store-path "/tmp/b"})
                          [:apply? :reason])))
      (is (= {:apply? false :reason :repo-equals-store}
             (select-keys (sandbox/resolve-sandbox-plan {:sandbox? true :macos? true :repo-dir "/tmp/same" :store-path "/tmp/same/"})
                          [:apply? :reason])))
      (let [plan (sandbox/resolve-sandbox-plan {:macos? true :repo-dir "/tmp/repo-x" :store-path "/tmp/repo-x/runs/store"})]
        (is (true? (:apply? plan)))
        (is (= :macos-seatbelt (:reason plan)))
        (is (string? (:profile plan))))))
  (testing "seed=42 Malli generative checks pass for all pure sandbox & fault-builder functions"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [vsym '[seon.fork.sandbox/macos?
                     seon.fork.sandbox/escape-sbpl-string
                     seon.fork.sandbox/canonical-path
                     seon.fork.sandbox/build-seatbelt-profile
                     seon.fork.sandbox/resolve-sandbox-plan
                     seon.fork/image-advance-fault-req]]
          (let [r (schema/stress-var db vsym {:samples 100 :seed 42 :measure-fn code/measure-call})]
            (is (:ok? r) (str "seed=42 failed for " vsym ": " (pr-str r)))))))))

(deftest live-kernel-worker-sandbox-blocks-repo-and-git-allows-store-test
  (when (sandbox/macos?)
    (if-let [inherited (sandbox/current-sandbox)]
      (testing "Inside an already-sandboxed :fork-test worker, inherited OS sandbox protects host repo and nested apply-worker-sandbox! stays non-fatal"
        (let [repo (:repo-dir inherited)
              probe-path (io/file repo (str "src/.seon-sandbox-probe-" (System/nanoTime)))]
          (is (string? (slurp (io/file repo "AGENTS.md"))))
          (is (thrown? Throwable (spit probe-path "should-fail")))
          (is (not (.exists probe-path)))
          (let [nested (sandbox/apply-worker-sandbox! {:repo-dir "/tmp/nested-r" :store-path "/tmp/nested-s" :sandbox? true})]
            (is (false? (:applied? nested)))
            (is (true? (:already-sandboxed? nested))))
          (is (:ok? (sandbox/self-test-sandbox!)))))
      (testing "Forked child under Seatbelt can read AGENTS.md & write runs/store, cannot write src/evil.clj or run git commit, and fails closed on invalid SBPL"
        (let [base (str "/tmp/seon-sandbox-test-" (System/nanoTime))
              store-dir (str base "/runs/store")]
          (try
            (.mkdirs (io/file base "src"))
            (.mkdirs (io/file store-dir))
            (spit (io/file base "AGENTS.md") "seon-sandbox-agents\n")
            (git/git! base "init" "-b" "main")
            (git/git! base "config" "user.email" "test@seon.local")
            (git/git! base "config" "user.name" "Seon Test")
            (git/git! base "add" ".")
            (git/git! base "commit" "-m" "Initial")
            (let [sha0 (git/head-commit base)
                  res (fork-and-run!
                       (fn []
                         (let [sb (sandbox/apply-worker-sandbox! {:repo-dir base :store-path store-dir :sandbox? true})
                               read-ok? (= "seon-sandbox-agents\n" (slurp (io/file base "AGENTS.md")))
                               store-ok? (do (spit (io/file store-dir "probe.txt") "lmdb-ok")
                                             (= "lmdb-ok" (slurp (io/file store-dir "probe.txt"))))
                               evil (try
                                      (spit (io/file base "src/evil.clj") "(ns evil)")
                                      {:blocked? false}
                                      (catch Throwable e
                                        {:blocked? true :msg (or (.getMessage e) (str e))}))
                               git-res (git/sh-fiber "git" "-C" base "commit" "--allow-empty" "-m" "rogue")]
                           {:applied? (:applied? sb)
                            :read-ok? read-ok?
                            :store-ok? store-ok?
                            :evil evil
                            :git-exit (:exit git-res)
                            :git-err (:err git-res)})))
                  bad-sb (fork-and-run!
                          (fn []
                            (try
                              (sandbox/apply-seatbelt-profile! "(version 1) (definitely-invalid-sbpl-op")
                              {:threw? false}
                              (catch clojure.lang.ExceptionInfo ex
                                {:threw? true
                                 :error (:error (ex-data ex))
                                 :rc (:rc (ex-data ex))
                                 :has-msg? (boolean (not-empty (:sandbox-error (ex-data ex))))}))))
                  st-res (sandbox/self-test-sandbox!)]
              (is (true? (:applied? res)))
              (is (true? (:read-ok? res)))
              (is (true? (:store-ok? res)))
              (is (true? (get-in res [:evil :blocked?])))
              (is (str/includes? (str/lower-case (str (get-in res [:evil :msg]))) "operation not permitted"))
              (is (not (.exists (io/file base "src/evil.clj"))))
              (is (not= 0 (:git-exit res)))
              (is (= sha0 (git/head-commit base)) "Git HEAD must remain unchanged after blocked rogue commit")
              (is (true? (:threw? bad-sb)) "Invalid SBPL must fail closed")
              (is (= :seon.sandbox/init-failed (:error bad-sb)))
              (is (not= 0 (:rc bad-sb)))
              (is (true? (:has-msg? bad-sb)))
              (is (true? (:ok? st-res)))
              (is (nil? (sandbox/current-sandbox)) "Parent/coordinator must remain unsandboxed"))
            (finally
              (rm-rf! (io/file base))
              (is (not (.exists (io/file base))) "Temp sandbox dir must be cleaned up"))))))))

(deftest test-worker-warm-commit-catchup-and-wrap-fork-out-test
  (testing "run-test-worker! catches up warm-commit -> main-cid delta before running tests"
    (with-test-store [{:keys [conn cfg]}]
      (let [src-v1 "(ns seon.demo.warmcatch)\n(defn answer [] 1)\n"
            src-test "(ns seon.demo.warmcatch-test (:require [clojure.test :refer [deftest is]] [seon.demo.warmcatch :as w]))\n(deftest answer-v2-test (is (= 2 (w/answer))))\n"
            src-v2 "(ns seon.demo.warmcatch)\n(defn answer [] 2)\n"]
        (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/warmcatch.clj" src-v1 false)})
        (d/transact conn {:tx-data (code/file-import-tx @conn "test/seon/demo/warmcatch_test.clj" src-test false)})
        (let [cid0 (d/commit-id @conn)]
          ;; Load v1 into memory (simulating ForkParent frozen at warm-commit = cid0), then advance :main in LMDB to v2!
          (code/apply-overlay! @conn {:upserts (concat (code/ns-forms @conn 'seon.demo.warmcatch)
                                                       (code/ns-forms @conn 'seon.demo.warmcatch-test))})
          (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/warmcatch.clj" src-v2 false)})
          (store/sync! cfg)
          (let [res (fork-and-run!
                     (fn []
                       (let [out (a/chan 4)]
                         (#'fork/run-test-worker! {:out out}
                           {:batch-id "catchup-1"
                            :cfg cfg
                            :branch :main
                            :warm-commit cid0
                            :overlay nil
                            :test-items [{:var 'seon.demo.warmcatch-test/answer-v2-test :digest "dg-catchup"}]
                            :gen-items []})
                         (a/<!! out))))]
            (is (= :test-batch-done (:ev res)))
            (is (= 1 (count (:results res))))
            (is (true? (:seon.test/pass? (first (:results res))))
                (str "Expected test worker to catch up cid0 -> main-cid: " (pr-str res))))))))
  (testing "wrap-fork-out attaches :repo-dir and :sandbox? to fork ops without altering control ops"
    (let [raw-out (a/chan 8)
          wrapped (sandbox/wrap-fork-out raw-out nil "/tmp/demo-repo" nil)]
      (try
        (a/>!! wrapped {:op :fork-agent :agent-id :ag/a :branch :branch/a})
        (a/>!! wrapped {:op :fork-test :batch-id "b1" :sandbox? false})
        (a/>!! wrapped {:op :advance-image :commit "c1"})
        (let [m1 (a/<!! raw-out)
              m2 (a/<!! raw-out)
              m3 (a/<!! raw-out)]
          (is (= "/tmp/demo-repo" (:repo-dir m1)))
          (is (true? (:sandbox? m1)))
          (is (= "/tmp/demo-repo" (:repo-dir m2)))
          (is (false? (:sandbox? m2)))
          (is (nil? (:repo-dir m3)))
          (is (nil? (:sandbox? m3))))
        (finally
          (a/close! wrapped)
          (a/close! raw-out))))))

(deftest fork-router-surfaces-image-advance-error-and-clears-on-recovery-test
  (testing "make-fork-router routes :image-advance-error to typed :seon.error/* via server-in-ch & error-chan, and retracts it on :image-advanced"
    (with-test-store [{:keys [conn cfg]}]
      (let [fp-in (a/chan 8)
            fp-out (a/chan 8)
            srv-in (a/chan 8)
            rep-ch (a/chan 8)
            err-ch (a/chan 8)
            seen (atom [])
            router (main/make-fork-router {:end {:in fp-in :out fp-out}})
            tx-st0 (transactor/transactor-proc {:conn conn :cfg cfg :repo-dir nil :commit-git? false :fork-out nil})]
        (try
          ((:set-targets! router) {:agent-ch nil :test-ch nil :kill-ack nil
                                   :server-in-ch srv-in :report-chan rep-ch :error-chan err-ch
                                   :on-image-event #(swap! seen conj %)})
          (a/>!! fp-in {:ev :image-advance-error :commit "cid-bad" :error "Unable to resolve symbol: boom"})
          (let [[req1 _] (a/alts!! [srv-in (a/timeout 2000)])
                [err1 _] (a/alts!! [err-ch (a/timeout 2000)])
                [rep1 _] (a/alts!! [rep-ch (a/timeout 2000)])
                [tx-st1 _] (transactor/transactor-proc tx-st0 :tx (:req req1))
                open1 (serr/open-errors @conn {:kind :image-advance-error})]
            (is (= :record-fault (get-in req1 [:req :op])))
            (is (= :image-advance-error (get-in req1 [:req :class])))
            (is (= :fork (:proc err1)))
            (is (str/includes? (str rep1) ":image-advance-error"))
            (is (= 1 (count open1)))
            (is (= :fork (:seon.error/source (first open1))))
            (is (str/includes? (:seon.error/message (first open1)) "cid-bad"))
            ;; Subsequent :image-advanced clears the open fault via :retract-fault!
            (a/>!! fp-in {:ev :image-advanced :commit "cid-good"})
            (let [[req2 _] (a/alts!! [srv-in (a/timeout 2000)])
                  [rep2 _] (a/alts!! [rep-ch (a/timeout 2000)])
                  _ (transactor/transactor-proc tx-st1 :tx (:req req2))
                  open2 (serr/open-errors @conn {:kind :image-advance-error})]
              (is (= :retract-fault (get-in req2 [:req :op])))
              (is (= (get-in req1 [:req :id]) (get-in req2 [:req :id])))
              (is (str/includes? (str rep2) "cid-good"))
              (is (empty? open2))
              (is (= [:image-advance-error :image-advanced] (mapv :ev @seen)))))
          (finally
            (a/close! fp-in)
            (a/close! fp-out)
            (a/close! srv-in)
            (a/close! rep-ch)
            (a/close! err-ch)))))))

(deftest git-proc-commit-git-gate-and-delete-branch-ref-test
  (testing "git-proc skips :sync-branch/:write-back/:delete-branch-ref when :commit-git? false, and delete-branch-ref! + batch :delete-branch-ref remove refs cleanly"
    (let [repo (str "/tmp/seon-git-hygiene-" (System/nanoTime))]
      (try
        (.mkdirs (io/file repo "src/demo"))
        (spit (io/file repo "src/demo/a.clj") "(ns demo.a)\n(defn f [] 1)\n")
        (git/git! repo "init" "-b" "main")
        (git/git! repo "config" "user.email" "test@seon.local")
        (git/git! repo "config" "user.name" "Seon Test")
        (git/git! repo "add" ".")
        (git/git! repo "commit" "-m" "Init")
        (let [sha0 (git/head-commit repo)]
          (with-test-store [{:keys [conn cfg]}]
            (d/transact conn {:tx-data (git/bootstrap-tx @conn repo)})
            (store/branch! conn :main :branch/trial-a)
            (store/branch! conn :main :branch/trial-b)
            (let [bc-a (store/connect cfg :branch/trial-a)
                  bc-b (store/connect cfg :branch/trial-b)]
              (try
                (d/transact bc-a {:tx-data (code/file-import-tx @bc-a "src/demo/a.clj" "(ns demo.a)\n(defn f [] 2)\n" false)})
                (d/transact bc-b {:tx-data (code/file-import-tx @bc-b "src/demo/a.clj" "(ns demo.a)\n(defn f [] 3)\n" false)})
                (finally
                  (d/release bc-a)
                  (d/release bc-b))))
            ;; 1. With :commit-git? false, :sync-branch & :write-back are no-ops and leak ZERO refs into .git!
            (let [st-off (git-proc/git-proc {:conn conn :cfg cfg :repo-dir repo :commit-git? false :port 0})
                  rch1 (a/chan 1)
                  [st1 out1] (git-proc/git-proc st-off :in {:op :sync-branch :branch :branch/trial-a :reply-ch rch1})
                  ans1 (a/<!! rch1)
                  rch2 (a/chan 1)
                  [st2 out2] (git-proc/git-proc st1 :in {:op :write-back :branch :branch/trial-a :commit (d/commit-id @conn)
                                                         :touched-paths #{"src/demo/a.clj"} :message "skip" :reply-ch rch2})
                  ans2 (a/<!! rch2)]
              (is (= :skipped-commit-git-false (:skipped ans1)))
              (is (some #(str/includes? % ":skipped-commit-git-false") (:clojure.core.async.flow/report out1)))
              (is (nil? (git/ref-commit repo "refs/heads/seon/trial-a")))
              (is (= :skipped-commit-git-false (:skipped ans2)))
              (is (false? (:committed? ans2)))
              (is (some #(str/includes? % ":skipped-commit-git-false") (:clojure.core.async.flow/report out2)))
              (is (= sha0 (git/head-commit repo)))
              (is (= 0 (:commits st2))))
            ;; 2. With :commit-git? true, sync creates refs, delete-branch-ref! removes one (protecting :main), and batch :delete-branch-ref cleans multiple!
            (let [st-on (git-proc/git-proc {:conn conn :cfg cfg :repo-dir repo :commit-git? true :port 0})
                  [st-a _] (git-proc/git-proc st-on :in {:op :sync-branch :branch :branch/trial-a})
                  [st-ab _] (git-proc/git-proc st-a :in {:op :sync-branch :branch :branch/trial-b})]
              (is (some? (git/ref-commit repo "refs/heads/seon/trial-a")))
              (is (some? (git/ref-commit repo "refs/heads/seon/trial-b")))
              (is (= {:deleted? false :ref nil :branch :main :reason :protected-main}
                     (git/delete-branch-ref! repo :main)))
              (let [d1 (git/delete-branch-ref! repo :branch/trial-a)
                    d2 (git/delete-branch-ref! repo :branch/trial-a)]
                (is (true? (:deleted? d1)))
                (is (= "refs/heads/seon/trial-a" (:ref d1)))
                (is (nil? (git/ref-commit repo "refs/heads/seon/trial-a")))
                (is (false? (:deleted? d2)))
                (is (= :not-found (:reason d2))))
              ;; Re-sync trial-a then batch-delete both via :op :delete-branch-ref
              (let [[st-both _] (git-proc/git-proc st-ab :in {:op :sync-branch :branch :branch/trial-a})
                    rch-del (a/chan 1)
                    [st-after out-del] (git-proc/git-proc st-both :in {:op :delete-branch-ref
                                                                       :branches [:branch/trial-a :branch/trial-b]
                                                                       :reply-ch rch-del})
                    ans-del (a/<!! rch-del)]
                (is (true? (:ok? ans-del)))
                (is (= [:branch/trial-a :branch/trial-b] (:deleted ans-del)))
                (is (nil? (git/ref-commit repo "refs/heads/seon/trial-a")))
                (is (nil? (git/ref-commit repo "refs/heads/seon/trial-b")))
                (is (not (contains? (:branch-refs st-after) :branch/trial-a)))
                (is (not (contains? (:branch-refs st-after) :branch/trial-b)))
                (do (is (some #(str/includes? % "deleted 2 ref(s)") (:clojure.core.async.flow/report out-del))) (let [legacy-st (dissoc st-on :commit-git?) [_ outs] (git-proc/git-proc legacy-st :in {:op :delete-branch-ref :branch :branch/nonexistent})] (is (not (some #(str/includes? % "skipped-commit-git-false") (:clojure.core.async.flow/report outs))))))))))
        (finally
          (rm-rf! (io/file repo))
          (is (not (.exists (io/file repo))) "Temp git repo must be cleaned up"))))))
