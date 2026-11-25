(ns seon.fork-sandbox-test
  "Tests for :fork-refused batch isolation, transactor/merge :delete-branch-ref wiring,
   git-proc :commit-git? gating, test-worker warm-commit catch-up, canonical store helpers,
   and ForkParent router fault surfacing."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.reads :as reads]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [konserve.core :as k]
            [seon.code :as code]
            [seon.error :as serr]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.git-proc :as git-proc]
            [seon.main :as main]
            [seon.merge :as merge]
            [seon.schema :as schema]
            [seon.store :as store :refer [rm-rf!]]
            [seon.test :as stest]
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
              (fork/c-waitpid pid st-buf 0))
            (or res {:error :fork-timeout}))))))

(deftest seatbelt-profile-canonicalization-escaping-and-seed42-test
  (testing ":fork-refused carries :agent-id/:batch-id and isolates the refused test batch without nuking concurrent batches"
    (let [rc1 (a/chan 1)
          rc2 (a/chan 1)
          st0 {:conn nil
               :cfg nil
               :fork-out (a/chan 8)
               :max-workers 4
               :parents {"batch-1" {:reply-ch rc1 :agent-id :agent/a :branch :branch/a :results []}
                         "batch-2" {:reply-ch rc2 :agent-id :agent/b :branch :branch/b :results []}}
               :active-subs {"batch-1-fast-0" "batch-1"
                             "batch-1-fast-1" "batch-1"
                             "batch-2-fast-0" "batch-2"}
               :sub-queue [{:parent-id "batch-1" :sub-id "batch-1-slow-0"}
                           {:parent-id "batch-2" :sub-id "batch-2-slow-0"}]}
          [st1 out1] (stest/test-proc st0 :fork-ev
                       {:ev :fork-refused :type :test :batch-id "batch-1-fast-0" :code -1})
          rep1 (a/poll! rc1)]
      (is (false? (:ok? rep1)))
      (is (str/includes? (str (:error rep1)) "-1"))
      (is (not (contains? (:parents st1) "batch-1")))
      (is (contains? (:parents st1) "batch-2") "Concurrent batch-2 must remain untouched")
      (is (= #{"batch-2-fast-0" "batch-2-slow-0"} (set (keys (:active-subs st1)))))
      (is (nil? (a/poll! rc2)) "Concurrent batch-2 reply-ch must remain open and untouched")
      (is (= :agent/a (:to (first (:tx out1)))))
      ;; Duplicate/stale :fork-refused for already-purged sibling sub-id must NOT nuke batch-2!
      (let [[st2 out2] (stest/test-proc st1 :fork-ev
                         {:ev :fork-refused :type :test :batch-id "batch-1-fast-1" :code -1})]
        (is (contains? (:parents st2) "batch-2") "Stale sibling :fork-refused must not nuke concurrent batch-2")
        (is (= #{"batch-2-fast-0" "batch-2-slow-0"} (set (keys (:active-subs st2)))))
        (is (nil? (a/poll! rc2)))
        (is (= "batch-1-fast-1" (:batch-id (first (::flow/error out2)))))
        ;; Unknown :batch-id also leaves batch-2 untouched
        (let [[st3 _] (stest/test-proc st2 :fork-ev
                        {:ev :fork-refused :type :test :batch-id "batch-unknown" :code -2})]
          (is (contains? (:parents st3) "batch-2"))
          (is (nil? (a/poll! rc2)))
          ;; Unattributed nil :batch-id releases remaining active batches
          (let [[st4 _] (stest/test-proc st3 :fork-ev
                          {:ev :fork-refused :type :test :code -3})
                rep2 (a/poll! rc2)]
            (is (empty? (:parents st4)))
            (is (empty? (:active-subs st4)))
            (is (false? (:ok? rep2))))))))
  (testing "ForkParent emits :agent-id and :batch-id on :ev :fork-refused and seed=42 passes for image-advance-fault-req"
    (with-test-store [{:keys [conn]}]
      (let [db @conn
            r (schema/stress-var db 'seon.fork/image-advance-fault-req
                                 {:samples 100 :seed 42 :measure-fn code/measure-call})]
        (is (:ok? r) (str "seed=42 failed for seon.fork/image-advance-fault-req: " (pr-str r)))))))

(deftest live-kernel-worker-sandbox-blocks-repo-and-git-allows-store-test
  (testing "transactor :gc-store and :merge emit :delete-branch-ref on :git for pruned branches"
    (with-test-store [{:keys [conn cfg]}]
      (store/branch! conn :main :branch/old-gc-1)
      (store/branch! conn :main :branch/old-gc-2)
      (d/transact conn {:tx-data [{:seon.agent/id :agent/old-gc-1 :seon.agent/branch :branch/old-gc-1
                                   :seon.agent/merged-tx 10 :seon.agent/archived? true}
                                  {:seon.agent/id :agent/old-gc-2 :seon.agent/branch :branch/old-gc-2
                                   :seon.agent/merged-tx 11 :seon.agent/archived? true}]})
      (let [tx-st0 (transactor/transactor-proc {:conn conn :cfg cfg :repo-dir nil :commit-git? false :fork-out nil})
            rch-gc (a/chan 1)
            [_ out-gc] (transactor/transactor-proc tx-st0 :tx {:op :gc-store :keep 0 :reply-ch rch-gc})
            ans-gc (a/<!! rch-gc)
            git-gc (first (:git out-gc))]
        (is (true? (:ok? ans-gc)))
        (is (= #{:branch/old-gc-1 :branch/old-gc-2} (set (:pruned-branches ans-gc))))
        (is (= :delete-branch-ref (:op git-gc)))
        (is (= #{:branch/old-gc-1 :branch/old-gc-2} (set (:branches git-gc)))))
      ;; Merge path also emits :delete-branch-ref when prune-merged-branches! removes stale branches
      (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/gc-keep-merged-branches 0}]})
      (store/branch! conn :main :branch/stale-merge-1)
      (store/branch! conn :main :branch/fresh-merge-1)
      (d/transact conn {:tx-data [{:seon.agent/id :agent/stale-m :seon.agent/branch :branch/stale-merge-1
                                   :seon.agent/merged-tx 12 :seon.agent/archived? true}]})
      (let [bconn (store/connect cfg :branch/fresh-merge-1)]
        (try
          (d/transact bconn {:tx-data [{:seon.task/id :task/fresh-m :seon.task/title "Fresh merge task"}]})
          (finally (d/release bconn))))
      (let [tx-st1 (transactor/transactor-proc {:conn conn :cfg cfg :repo-dir nil :commit-git? false :fork-out nil})
            rch-m (a/chan 1)
            [_ out-m] (transactor/transactor-proc tx-st1 :tx {:op :merge :branch :branch/fresh-merge-1 :reply-ch rch-m})
            ans-m (a/<!! rch-m)
            del-op (some #(when (= :delete-branch-ref (:op %)) %) (:git out-m))]
        (is (true? (:ok? ans-m)))
        (is (some? del-op) (str "Expected :delete-branch-ref on :git out, got: " (pr-str out-m)))
        (is (contains? (set (:branches del-op)) :branch/stale-merge-1)))))
  (testing "git-proc :diff-branch honors :commit-git? false and leaks zero refs into .git"
    (let [repo (str "/tmp/seon-diff-gate-" (System/nanoTime))]
      (try
        (.mkdirs (io/file repo "src/demo"))
        (spit (io/file repo "src/demo/d.clj") "(ns demo.d)\n(defn v [] 1)\n")
        (git/git! repo "init" "-b" "main")
        (git/git! repo "config" "user.email" "test@seon.local")
        (git/git! repo "config" "user.name" "Seon Test")
        (git/git! repo "add" ".")
        (git/git! repo "commit" "-m" "Init")
        (with-test-store [{:keys [conn cfg]}]
          (d/transact conn {:tx-data (git/bootstrap-tx @conn repo)})
          (store/branch! conn :main :branch/diff-noleak)
          (let [bc (store/connect cfg :branch/diff-noleak)]
            (try
              (d/transact bc {:tx-data (code/file-import-tx @bc "src/demo/d.clj" "(ns demo.d)\n(defn v [] 2)\n" false)})
              (finally (d/release bc))))
          (let [plan (merge/three-way conn cfg :branch/diff-noleak {:skip-health? true})
                st-off (git-proc/git-proc {:conn conn :cfg cfg :repo-dir repo :commit-git? false :port 0})
                rch (a/chan 1)
                [_ out] (git-proc/git-proc st-off :in {:op :diff-branch :branch :branch/diff-noleak
                                                       :plan plan :reply-ch rch})
                ans (a/<!! rch)]
            (is (= :skipped-commit-git-false (:skipped ans)))
            (is (nil? (:git-ref ans)))
            (is (nil? (:diff ans)))
            (is (some #(str/includes? % ":diff-branch :skipped-commit-git-false") (::flow/report out)))
            (is (nil? (git/ref-commit repo "refs/heads/seon/diff-noleak")))))
        (finally
          (rm-rf! (io/file repo))
          (is (not (.exists (io/file repo))) "Temp git repo must be cleaned up"))))))

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
  (testing "canonical seon.store helpers (lru-peek, lru-put!, resolve-lazy-str, tx-instant-ms)"
    (with-test-store [{:keys [conn]}]
      (let [db @conn
            ca (store/lru-cache 2)]
        (is (= ::miss (store/lru-peek ca :k1 ::miss)))
        (is (nil? (store/lru-peek ca :k1)))
        (is (= :v1 (store/lru-put! ca 2 :k1 :v1)))
        (is (= nil (store/lru-put! ca 2 :k-nil nil)))
        (is (= :v1 (store/lru-peek ca :k1 ::miss)))
        (is (nil? (store/lru-peek ca :k-nil ::miss)) "Cached nil must not return miss sentinel")
        (store/lru-put! ca 2 :k3 :v3)
        (is (= 2 (count @ca)))
        ;; lru-peek unwraps :seon.store/lru-val and replays datahike.reads
        (let [ca2 (store/lru-cache 4)
              _ (reads/with-reads #(store/lru-get! ca2 4 :cnt (fn [] (count (d/datoms db :aevt :seon.schema/key)))))
              [v rs] (reads/with-reads #(store/lru-peek ca2 :cnt ::miss))]
          (is (pos? v))
          (is (seq rs) "lru-peek must replay recorded datahike.reads"))
        ;; resolve-lazy-str handles nil, inline strings, and Datahike LazyValues
        (let [lk [:test/lazy-str (random-uuid)]
              _ (k/assoc-in (:store db) [lk] "lazy-resolved-body" {:sync? true})
              lv (dd/lazy-value lk (store/store-id db))]
          (is (nil? (store/resolve-lazy-str db nil)))
          (is (= "inline" (store/resolve-lazy-str db "inline")))
          (is (dd/lazy-value? lv))
          (is (= "lazy-resolved-body" (store/resolve-lazy-str db lv))))
        ;; tx-instant-ms returns positive epoch ms for valid tx and nil for nil/non-pos/missing
        (let [ms (store/tx-instant-ms db (:max-tx db))]
          (is (and (int? ms) (pos? ms)))
          (is (nil? (store/tx-instant-ms db nil)))
          (is (nil? (store/tx-instant-ms db 0)))
          (is (nil? (store/tx-instant-ms nil (:max-tx db)))))))))

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
              (is (some #(str/includes? % ":skipped-commit-git-false") (::flow/report out1)))
              (is (nil? (git/ref-commit repo "refs/heads/seon/trial-a")))
              (is (= :skipped-commit-git-false (:skipped ans2)))
              (is (false? (:committed? ans2)))
              (is (some #(str/includes? % ":skipped-commit-git-false") (::flow/report out2)))
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
                (is (some #(str/includes? % "deleted 2 ref(s)") (::flow/report out-del)))
                (let [legacy-st (dissoc st-on :commit-git?)
                      [_ outs] (git-proc/git-proc legacy-st :in {:op :delete-branch-ref :branch :branch/nonexistent})]
                  (is (not (some #(str/includes? % "skipped-commit-git-false") (::flow/report outs)))))))))
        (finally
          (rm-rf! (io/file repo))
          (is (not (.exists (io/file repo))) "Temp git repo must be cleaned up"))))))