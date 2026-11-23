(ns seon.fork-index-and-miners-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.io :as cio]
            [seon.code.mine :as mine]
            [seon.code.reach :as reach]
            [seon.git :as git]
            [seon.git.forks :as forks]
            [seon.health :as health]
            [seon.health.contracts :as contracts]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]))

(defn- ensure-wave-d2-schemas! [conn]
  (let [reg! (fn [k form opts]
               (let [{:keys [entity dh-decls retract-ops]}
                     (schema/validate-schema-registration! @conn (assoc opts :key k :form form))]
                 (when (seq dh-decls) (d/transact conn {:tx-data dh-decls}))
                 (d/transact conn {:tx-data (vec (concat retract-ops [entity]))})))]
    (reg! :seon.schema/examples
          [:vector {:max 4 :description "Explicit valid example values."} :any]
          {})
    (when-not (contains? (:schema @conn) :seon.code.ns/origin)
      (reg! :seon.code.ns/origin
            [:keyword {:seon.db/index true}]
            {:doc "Provenance of a namespace: `:seon` or `:fork/<lib>`."
             :examples [:seon :fork/datahike]})
      (let [ef (:seon.schema/form (d/pull @conn '[:seon.schema/form] [:seon.schema/key :seon.code.ns/entity]))]
        (when (and (vector? ef) (not (some #(and (vector? %) (= :seon.code.ns/origin (first %))) ef)))
          (reg! :seon.code.ns/entity
                (conj ef [:seon.code.ns/origin {:optional true} :seon.code.ns/origin])
                {}))))))

(deftest path-partitioning-and-seed42-gen-test
  (testing "path->fork-root, partition-paths-by-repo, topic-branch behavior and seed=42 generative checks"
    (is (= {:origin :fork/datahike :root "reference-code/datahike" :rel-path "src/datahike/api.cljc"}
           (forks/path->fork-root "reference-code/datahike/src/datahike/api.cljc")))
    (is (true? (cio/fork-src-path? "reference-code/datahike/src/datahike/api.cljc")))
    (is (true? (cio/fork-src-path? "reference-code/konserve/src/konserve/jolt/lmdb.jolt")))
    (is (false? (cio/fork-src-path? "src/seon/git.clj")))
    (is (nil? (forks/path->fork-root "src/seon/git.clj")))
    (is (nil? (forks/path->fork-root "reference-code/datahike")))
    (is (= {:main ["bin/seon" "src/seon/git.clj"]
            :forks {"reference-code/datahike" ["src/a.cljc" "src/b.cljc"]
                    "reference-code/malli" ["src/malli/core.cljc"]}}
           (forks/partition-paths-by-repo
            ["src/seon/git.clj"
             "reference-code/datahike/src/b.cljc"
             "bin/seon"
             "reference-code/malli/src/malli/core.cljc"
             "reference-code/datahike/src/a.cljc"
             "src/seon/git.clj"])))
    (is (= "seon/fix-pull-read" (forks/topic-branch :branch/fix-pull-read)))
    (is (= "seon/merge" (forks/topic-branch nil)))
    (is (not (str/starts-with? (forks/topic-branch :branch/jolt-fix) "jolt/")))
    (is (= 6 (count forks/fork-repos)))
    (doseq [vsym '[seon.git.forks/path->fork-root
                   seon.git.forks/partition-paths-by-repo
                   seon.git.forks/topic-branch
                   seon.code.io/fork-src-path?
                   seon.code.mine/doc-mentions]]
      (let [res (schema/stress-var nil vsym {:samples 100 :seed 42})]
        (is (:ok? res) (str "seed=42 generative failed on " vsym ": " (:reason res)))))))

(deftest ^{:cost/ticks 250000000} submodule-write-back-and-push-dry-run-test
  (testing "Submodule-aware write-and-commit-main! commits forks on seon/<topic>, stages gitlinks, and push-all! plans/guards cleanly"
    (let [tmp (io/file (str "/tmp/seon-submod-test-" (System/nanoTime)))]
      (try
        (.mkdirs tmp)
        (let [sub-bare (io/file tmp "sub-remote.git")
              main-bare (io/file tmp "main-remote.git")
              sub-work (io/file tmp "sub-work")
              main-work (io/file tmp "main-work")
              sub-path (.getPath sub-work)
              main-path (.getPath main-work)
              git-c (fn [dir & args]
                      (apply forks/git! dir "-c" "user.name=Test" "-c" "user.email=test@local" args))]
          (.mkdirs sub-bare) (.mkdirs main-bare) (.mkdirs sub-work) (.mkdirs main-work)
          (forks/git! (.getPath sub-bare) "init" "--bare" "-q" "-b" "main")
          (forks/git! (.getPath main-bare) "init" "--bare" "-q" "-b" "main")
          (forks/git! sub-path "init" "-q" "-b" "main")
          (let [f (io/file sub-work "src/mylib/core.clj")]
            (.mkdirs (.getParentFile f))
            (spit f "(ns mylib.core)\n(defn v [] 1)\n"))
          (forks/git! sub-path "add" "-A")
          (git-c sub-path "commit" "-q" "-m" "initial fork commit")
          (forks/git! sub-path "remote" "add" "origin" (.getPath sub-bare))
          (forks/git! sub-path "push" "-q" "-u" "origin" "main")
          (forks/git! main-path "init" "-q" "-b" "main")
          (spit (io/file main-work "README.md") "hello\n")
          (forks/git! main-path "add" "README.md")
          (git-c main-path "commit" "-q" "-m" "initial main")
          (forks/git! main-path "-c" "protocol.file.allow=always"
                      "submodule" "add" "-q" (.getPath sub-bare) "reference-code/datahike")
          (git-c main-path "commit" "-q" "-m" "add datahike submodule")
          (forks/git! main-path "remote" "add" "origin" (.getPath main-bare))
          (forks/git! main-path "push" "-q" "-u" "origin" "main")
          (let [sha0 (git/head-commit main-path)
                sub-in-main (.getPath (io/file main-work "reference-code/datahike"))
                sub-sha0 (git/head-commit sub-in-main)]
            (with-test-store [{:keys [conn]}]
              (ensure-wave-d2-schemas! conn)
              (let [main-src "(ns seon.demo.sub)\n(defn ok {:malli/schema [:=> [:cat] :boolean]} [] true)\n"
                    fork-src "(ns mylib.core)\n(defn v [] 2)\n"
                    tx1 (code/file-import-tx @conn "src/seon/demo/sub.clj" main-src false)
                    tx2 (mapv #(if (and (map? %) (:seon.code.ns/name %) (:seon.code.ns/path %))
                                 (assoc % :seon.code.ns/origin :fork/datahike)
                                 %)
                              (code/file-import-tx @conn "reference-code/datahike/src/mylib/core.clj" fork-src false))]
                (d/transact conn {:tx-data (vec (concat tx1 tx2))})
                (let [res (git/write-and-commit-main!
                           @conn main-path sha0
                           ["src/seon/demo/sub.clj" "reference-code/datahike/src/mylib/core.clj"]
                           "fix(datahike): bump v to 2\n\nDetailed body line"
                           :branch/fix-pull-read #{})
                      sub-sha1 (git/head-commit sub-in-main)
                      sub-br (str/trim (forks/git! sub-in-main "branch" "--show-current"))
                      sub-msg (str/trim (forks/git! sub-in-main "log" "-1" "--format=%B"))
                      gitlink-sha (str/trim (forks/git! main-path "rev-parse" "HEAD:reference-code/datahike"))]
                  (is (:committed? res))
                  (is (not= sha0 (:commit res)))
                  (is (not= sub-sha0 sub-sha1))
                  (is (= "seon/fix-pull-read" sub-br))
                  (is (not (str/starts-with? sub-br "jolt/")))
                  (is (= "fix(datahike): bump v to 2" sub-msg))
                  (is (not (re-find #"(?i)co-authored-by|claude|anthropic" sub-msg)))
                  (is (= sub-sha1 gitlink-sha))
                  (is (= fork-src (slurp (io/file main-work "reference-code/datahike/src/mylib/core.clj"))))
                  (is (= main-src (slurp (io/file main-work "src/seon/demo/sub.clj"))))
                  (is (str/blank? (str/trim (forks/git! main-path "status" "--porcelain"))))
                  (let [plan1 (forks/push-all! main-path {:dry-run? true})]
                    (is (:ok? plan1))
                    (is (:dry-run? plan1))
                    (is (= :would-push (get-in plan1 [:submodules 0 :status])))
                    (is (= "seon/fix-pull-read" (get-in plan1 [:submodules 0 :branch])))
                    (is (= :would-push (get-in plan1 [:main :status]))))
                  (spit (io/file sub-in-main "src/mylib/core.clj") "(ns mylib.core)\n(defn v [] 99)\n")
                  (let [plan-dirty (forks/push-all! main-path {:dry-run? true})]
                    (is (not (:ok? plan-dirty)))
                    (is (= :dirty (get-in plan-dirty [:submodules 0 :status])))
                    (is (= :blocked (get-in plan-dirty [:main :status]))))
                  (forks/git! sub-in-main "checkout" "--" "src/mylib/core.clj")
                  (forks/git! sub-in-main "push" "-q" "origin" "seon/fix-pull-read")
                  (let [plan2 (forks/push-all! main-path {:dry-run? true})]
                    (is (:ok? plan2))
                    (is (= :on-remote (get-in plan2 [:submodules 0 :status])))
                    (is (= :would-push (get-in plan2 [:main :status])))))))))
        (finally
          (store/rm-rf! tmp))))))

(deftest ^{:cost/ticks 200000000} fork-indexing-health-gates-and-boot-reach-test
  (testing ":seon.code.ns/origin :fork/* skips Seon-only health gates, keeps :broken-symbol-ref, and triggers :boot-affected?"
    (is (true? (reach/boot-file-path? "reference-code/datahike/src/datahike/api.cljc")))
    (is (true? (reach/boot-file-path? "reference-code/konserve/src/konserve/jolt/lmdb.jolt")))
    (is (false? (reach/boot-file-path? "src/seon/git/forks.clj")))
    (with-test-store [{:keys [conn]}]
      (ensure-wave-d2-schemas! conn)
      (let [base-db @conn
            pad-lines (str/join "\n" (repeat 130 "  (inc x)"))
            big-pad (apply str (repeat 34000 " "))
            fork-src (str "(ns datahike.fork-demo (:require [clojure.core.async :as a]))\n"
                          "(defn huge-untyped-proc [x ch]\n  (a/<!! ch)\n  (try\n" pad-lines "\n    (catch Throwable _ nil)))\n"
                          ";; " big-pad "\n")
            tx (mapv (fn [e]
                       (cond-> e
                         (and (map? e) (:seon.code.ns/name e) (:seon.code.ns/path e))
                         (assoc :seon.code.ns/origin :fork/datahike)
                         (and (map? e) (= 'datahike.fork-demo/huge-untyped-proc (:seon.code.var/name e)))
                         (assoc :seon.code.var/catches [{:class "Throwable" :answers :constant}])))
                     (code/file-import-tx @conn "reference-code/datahike/src/datahike/fork_demo.clj" fork-src false))]
        (d/transact conn {:tx-data tx})
        (let [db1 @conn
              mc (contracts/missing-contract-violations base-db db1)
              rep (health/report db1)
              fork-probs (filterv #(= 'datahike.fork-demo (:ns %)) (:problems rep))]
          (is (empty? (filter #(= 'datahike.fork-demo (:ns %)) mc)))
          (is (empty? fork-probs) (str "Expected 0 Seon-only gates on fork ns, got: " (pr-str fork-probs))))
        ;; Broken symbol ref must remain active even when foreign namespaces are present
        (d/transact conn {:tx-data [{:seon.schema/key :seon.demo/broken-ref-check
                                     :seon.schema/form [:map {:seon.render/hiccup 'seon.missing/no-such-renderer} [:a :int]]}]})
        (let [bsr (contracts/broken-symbol-refs @conn)]
          (is (some #(= 'seon.missing/no-such-renderer (:var %)) bsr)))
        ;; Verify explicit index-fork-tx on real checkout's small :fork/crypto (read-only)
        (let [crypto-tx (forks/index-fork-tx @conn store/checkout-root :fork/crypto)
              ns-ents (filter #(and (map? %) (:seon.code.ns/origin %)) crypto-tx)]
          (is (pos? (count crypto-tx)))
          (is (seq ns-ents))
          (is (every? #(= :fork/crypto (:seon.code.ns/origin %)) ns-ents))
          (d/transact conn {:tx-data crypto-tx})
          (is (= :fork/crypto (:seon.code.ns/origin (d/pull @conn '[:seon.code.ns/origin] [:seon.code.ns/name 'jolt.crypto])))))))))

(deftest ^{:cost/ticks 250000000} mechanical-miners-and-reports-test
  (testing "mine-schema-examples-tx, mine-docstring-refs-tx, graph-density-report, and turn0-recall-precision-report"
    (with-test-store [{:keys [conn]}]
      (ensure-wave-d2-schemas! conn)
      (let [src-a (str "(ns seon.mine-demo.a)\n"
                       "(defn helper\n  \"Small pure helper.\"\n  {:malli/schema [:=> [:cat :int] :int] :cost/ticks 1000}\n  [x] (inc x))\n"
                       "(defn caller\n  \"Uses `seon.mine-demo.a/helper` in `seon.mine-demo.a` with `:seon.task/title` and self `seon.mine-demo.a/caller`.\"\n"
                       "  {:malli/schema [:=> [:cat :map] :any] :cost/ticks 1000}\n  [m] m)\n")
            fork-src "(ns datahike.excluded)\n(defn f [x] x)\n"
            tx-a (code/file-import-tx @conn "src/seon/mine_demo/a.clj" src-a false)
            tx-f (mapv #(if (and (map? %) (:seon.code.ns/name %) (:seon.code.ns/path %))
                          (assoc % :seon.code.ns/origin :fork/datahike)
                          %)
                       (code/file-import-tx @conn "reference-code/datahike/src/datahike/excluded.clj" fork-src false))]
        (d/transact conn {:tx-data (vec (concat tx-a tx-f))})
        ;; Seed a task with normal values + one huge/base64 string + a modern agent + evals
        (let [helper-eid (:e (first (d/datoms @conn :avet :seon.code.var/name 'seon.mine-demo.a/helper)))
              ns-eid (:e (first (d/datoms @conn :avet :seon.code.ns/name 'seon.mine-demo.a)))]
          (d/transact conn {:tx-data [{:seon.task/id :seon.mine-demo/t1
                                       :seon.task/title "Short valid title"
                                       :seon.task/done? false
                                       :seon.task/scope 'seon.mine-demo.a
                                       :seon.task/summary "data:image/png;base64,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
                                      {:seon.agent/id :seon.mine-demo/worker
                                       :seon.agent/role :worker
                                       :seon.agent/scope 'seon.mine-demo.a
                                       :seon.agent/task :seon.mine-demo/t1}
                                      {:seon.agent.eval/id (random-uuid)
                                       :seon.agent.eval/agent :seon.mine-demo/worker
                                       :seon.agent.eval/turn 1
                                       :seon.agent.eval/code "(seon.mine-demo.a/helper 1)"
                                       :seon.agent.eval/touched [helper-eid ns-eid]}
                                      {:seon.agent.eval/id (random-uuid)
                                       :seon.agent.eval/agent :seon.mine-demo/worker
                                       :seon.agent.eval/turn 2
                                       :seon.agent.eval/code ":seon.task/title"
                                       :seon.agent.eval/touched [helper-eid]}
                                      {:seon.agent.eval/id (random-uuid)
                                       :seon.agent.eval/agent :seon.mine-demo/worker
                                       :seon.agent.eval/turn 3
                                       :seon.agent.eval/code "(seon.mine-demo.a/caller {})"
                                       :seon.agent.eval/touched [helper-eid]}]})
          (let [db @conn
                ;; 1. mine-schema-examples-tx
                ex-tx (mine/mine-schema-examples-tx db)
                by-k (into {} (map (juxt :seon.schema/key :seon.schema/examples)) ex-tx)]
            (is (pos? (count ex-tx)))
            (is (= ["Short valid title"] (get by-k :seon.task/title)))
            (is (nil? (get by-k :seon.task/summary)) "Base64 data URI must be skipped")
            (is (every? (fn [{:seon.schema/keys [examples]}]
                          (= examples (edn/read-string (pr-str examples))))
                        ex-tx))
            (is (nil? (try (store/validate-tx-data! db ex-tx) nil (catch Throwable t (.getMessage t)))))
            ;; 2. mine-docstring-refs-tx
            (let [doc-tx (mine/mine-docstring-refs-tx db)
                  caller-op (some #(when (= 'seon.mine-demo.a/caller (:seon.code.var/name %)) %) doc-tx)]
              (is (some? caller-op))
              (is (= #{'seon.mine-demo.a/helper} (:seon.code.var/refs caller-op)))
              (is (= #{helper-eid} (:seon.code.var/calls caller-op)))
              (is (= #{:seon.task/title} (:seon.code.var/keywords caller-op)))
              (is (nil? (try (store/validate-tx-data! db doc-tx) nil (catch Throwable t (.getMessage t))))))
            ;; 3. graph-density-report
            (let [gdr (mine/graph-density-report db)]
              (is (= 2 (:public-vars gdr)) "Fork ns var excluded from Seon public-vars")
              (is (= 2 (:with-schema gdr)))
              (is (= 2 (:with-doc gdr)))
              (is (= 1 (:pure-opaque-map gdr)))
              (is (= 1 (:pure-opaque-any gdr)))
              (is (pos? (:schemas-total gdr)))
              (is (< (:ms gdr) 150.0)))
            ;; 4. turn0-recall-precision-report
            (let [t0 (mine/turn0-recall-precision-report db {:agents [:seon.mine-demo/worker]})]
              (is (= 1 (count (:agents t0))))
              (is (<= 0.0 (:precision t0) 1.0))
              (is (<= 0.0 (:recall t0) 1.0))
              (is (vector? (:recall-gap-items t0)))
              (is (pos? (:touched (first (:agents t0))))))))))))
