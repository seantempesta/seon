(ns seon.fx-test
  "The effect owner and worker step-fns as plain functions (no graph): admission by workers,
  priority order, budget refused, the queue bound, and a worker answering a defect."
  (:require [clojure.test :refer [deftest is testing]] [jolt.deps] [seon.fx :as fx] [seon.config :as cfg] [seon.contract :as contract] [seon.proofs :as proofs]
            [clojure.core.async.flow :as flow]))

(defn- owner [workers] (fx/owner-proc {:kind :t :workers workers :work nil :back nil}))
(defn- req [k & {:as more}] (merge {:req {:k k} :dest {:agent :a1 :op :fx-reply :k k}} more))

(deftest owner-admits-by-workers-and-priority
  (let [[st o] (fx/owner-proc (owner 1) :in (req 1))
        _ (is (= [1] (map #(get-in % [:req :k]) (:to-workers o))) "a free worker takes the first request")
        [st o] (fx/owner-proc st :in (req 2))
        _ (is (empty? (:to-workers o)) "no free worker: queued")
        [st _] (fx/owner-proc st :in (req 3 :prio 5))
        [st o] (fx/owner-proc st :done {:dest {:agent :a1 :op :fx-reply :k 1} :outcome {:value 1}})]
    (is (= [{:dest {:agent :a1 :op :fx-reply :k 1} :r {:value 1}}] (:out o)) "the outcome goes to its asker")
    (is (= [3] (map #(get-in % [:req :k]) (:to-workers o))) "the higher priority runs next")
    (is (= 1 (:busy st)))))

(deftest owner-refuses-a-spent-budget
  (let [[st _] (fx/owner-proc (owner 1) :in (req 1 :budget 10 :budget-key :task-a))
        [st _] (fx/owner-proc st :done {:dest {:agent :a1 :op :fx-reply :k 1} :outcome {:value 1} :budget-key :task-a :cost 10})
        [_ o] (fx/owner-proc st :in (req 2 :budget 10 :budget-key :task-a))]
    (is (= :seon.fx/budget-spent (get-in (first (:out o)) [:r :seon.error/type])))))

(deftest owner-stops-reading-at-its-bound
  (let [st (reduce (fn [st k] (first (fx/owner-proc st :in (req k)))) (owner 0) (range (cfg/value :seon.config.fx/buf)))]
    (is (false? ((::flow/input-filter st) :in)))
    (is (true? ((::flow/input-filter st) :done)))))

(deftest a-worker-answers-a-defect-as-data
  (let [st (fx/worker-proc {:kind :t :perform (fn [_] (throw (ex-info "boom" {}))) :owner :fx/t})
        [_ o] (fx/worker-proc st :work (assoc (req 1) :in-ns 0 :taken-ns 0))
        out (first (get o [:fx/t :done]))]
    (is (= :seon.fx/defect (get-in out [:outcome :seon.error/type])))
    (is (= {:agent :a1 :op :fx-reply :k 1} (:dest out)))))

(deftest a-failure-is-roots-problem-only-when-an-agent-asked
  ;; the asker records its outcome once (root, 2026-10-26): root code records its own failed effect
  ;; with its fact (seon.error/asked-tx), so root's count takes an agent's alone, whose facts are on
  ;; its branch
  (let [st (fx/worker-proc {:kind :t :perform (fn [_] (contract/error :seon.fx/io-error 'seon.fx/t "no such file" {})) :owner :fx/t})
        [_ o] (fx/worker-proc st :work (assoc (req 1) :in-ns 0 :taken-ns 0))
        failed (:outcome (first (get o [:fx/t :done])))
        [_ ok] (fx/worker-proc (fx/worker-proc {:kind :t :perform (fn [_] {:value 1}) :owner :fx/t}) :work (assoc (req 2) :in-ns 0 :taken-ns 0))
        done (fn [dest] (second (fx/owner-proc (first (fx/owner-proc (owner 1) :in (assoc (req 1) :dest dest))) :done {:dest dest :outcome failed})))
        problems #(filter :op (:out %))]
    (is (int? (:seon.fx/at-ms failed)) "a failed outcome carries when it failed, as its asker records it")
    (is (nil? (:seon.fx/at-ms (:outcome (first (get ok [:fx/t :done]))))) "a good one carries no such time")
    (let [o (done {:agent :a1 :op :fx-reply :k 1})]
      (is (= [:effect] (map :class (problems o))) "an agent asked: root's problem, of class :effect")
      (is (= [{:dest {:agent :a1 :op :fx-reply :k 1} :r failed}] (remove :op (:out o))) "and the agent is answered"))
    (let [o (done {:rid 7})]
      (is (empty? (problems o)) "root code asked: not root's count, the asker records it")
      (is (= [{:dest {:rid 7} :r failed}] (:out o)) "it is answered the error"))))

;; ---- a batch kind and a held host object (seon.vector's :embed) ------------------------

(deftest a-batch-kind-takes-what-is-queued
  (let [ks (fn [o] (map #(mapv (fn [m] (get-in m [:req :k])) (:batch %)) (:to-workers o)))
        [st o] (fx/owner-proc (fx/owner-proc {:kind :t :workers 1 :batch 3 :work nil :back nil}) :in (req 1))
        _ (is (= [[1]] (ks o)) "with nothing queued behind it, the head goes alone")
        st (reduce (fn [st k] (first (fx/owner-proc st :in (req k)))) st [2 3 4 5])
        [st o] (fx/owner-proc st :done {:batch [{:dest (:dest (req 1)) :outcome {:value 1}}]})]
    (is (= [{:dest (:dest (req 1)) :r {:value 1}}] (:out o)) "each request of a batch is answered")
    (is (= [[2 3 4]] (ks o)) "the free worker takes the head and those queued behind it, up to :batch")
    (is (= [5] (map (comp :k :req) (vals (:queue st)))) "the rest waits")
    (is (= 1 (:busy st)) "a batch holds one worker")))

(deftest a-worker-performs-a-batch-in-one-call-and-opens-once
  (let [st (fx/worker-proc {:kind :t :owner :fx/t :ctx {:path "m"}
                            :open (fn [ctx] {:model (:path ctx) :id (random-uuid)})
                            :perform (fn [{:keys [batch handle]}] (mapv (fn [m] {:value [(get-in m [:req :k]) (count batch) handle]}) batch))})
        item (fn [& ks] {:batch (mapv #(assoc (req %) :in-ns 0 :taken-ns 0) ks)})
        [st o] (fx/worker-proc st :work (item 1 2))
        [_ o2] (fx/worker-proc st :work (item 3))
        vals-of (fn [o] (mapv #(get-in % [:outcome :value]) (:batch (first (get o [:fx/t :done])))))
        [[k1 n1 h1] [k2 n2 h2]] (vals-of o)
        [[k3 n3 h3]] (vals-of o2)]
    (is (= [1 2 2 2] [k1 n1 k2 n2]) "one call performs the batch, one outcome per request in order")
    (is (= "m" (:model h1)) "the host object is made from the kind's ctx")
    (is (= h1 h2 h3) "and made once: the next item reuses it")
    (is (= [3 1] [k3 n3]))))

(deftest an-open-that-fails-answers-every-request-and-opens-again
  (let [st (fx/worker-proc {:kind :t :owner :fx/t :open (fn [_] (contract/error :seon.vector/model-unopened 'seon.vector/open "no model" {:path "m"}))
                            :perform (fn [_] (throw (ex-info "never performed" {})))})
        [st o] (fx/worker-proc st :work {:batch [(assoc (req 1) :in-ns 0 :taken-ns 0) (assoc (req 2) :in-ns 0 :taken-ns 0)]})]
    (is (= [:seon.vector/model-unopened :seon.vector/model-unopened]
           (mapv #(get-in % [:outcome :seon.error/type]) (:batch (first (get o [:fx/t :done]))))))
    (is (nil? (:handle st)) "nothing is held, so the next item opens again")))

;; ---- write-back answers what happened (audit GUARDS #21) ------------------------------

(defn- scratch-dir []
  (let [d (str (System/getProperty "user.dir") "/target/tmp/fx-test-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(deftest a-failed-rename-is-an-io-error-and-leaves-no-tmp
  (let [d (scratch-dir)
        target (str d "/occupied")
        _ (.mkdirs (java.io.File. (str target "/inside")))
        r (fx/write-file {:req {:path target :text "text"}})]
    (is (= :seon.fx/io-error (:seon.error/type r)) "renaming over a non-empty directory fails, and says so")
    (is (pos? (:errno r)) "with rename(2)'s errno")
    (is (= target (:path r)))
    (is (not (.exists (java.io.File. ^String (:tmp r)))) "the staged file is removed")
    (is (= ["occupied"] (vec (.list (java.io.File. d)))) "nothing else is left beside it"))
  (let [r (fx/write-file {:req {:path "/nonexistent-dir/x.clj" :text "text"}})]
    (is (= :seon.fx/io-error (:seon.error/type r)) "a missing directory: the stage itself fails")))

(deftest a-write-back-answers-the-bytes-on-disk
  (let [d (scratch-dir)
        p (str d "/f.clj")
        text "(def s \"héllo — world\")\n"
        r (fx/write-file {:req {:path p :text text}})]
    (is (= {:value {:bytes (.length (java.io.File. p)) :path p}} r))
    (is (= (+ (count text) 1 2) (get-in r [:value :bytes])) "bytes on disk, not characters: e-acute 2, em dash 3")
    (is (= text (slurp p)))
    (is (= ["f.clj"] (vec (.list (java.io.File. d)))) "no staged file left")))

(deftest a-dependency-is-resolved-through-its-owner
  ;; task seon.fx/a-dependency-is-fetched-through-its-owner (the PKG plan, owner-approved):
  ;; the :deps performer resolves a deps map with Jolt's own jolt.deps and answers the roots
  ;; to load from, or its one typed error naming what did not resolve; no network here (a
  ;; :local/root library made in this test's scratch dir)
  (let [d (scratch-dir)
        lib (str d "/tinylib")
        _ (.mkdirs (java.io.File. (str lib "/src/tiny")))
        _ (spit (str lib "/deps.edn") "{:paths [\"src\"]}")
        _ (spit (str lib "/src/tiny/core.clj") "(ns tiny.core)\n(defn hello [] :hi)\n")
        r (fx/resolve-deps {:req {:deps {'tiny/tiny {:local/root lib}}}})]
    (is (some #(= (str lib "/src") %) (get-in r [:value :roots])) "the library's absolute source root")
    (let [bad (fx/resolve-deps {:req {:deps {'nowhere/nothing {:local/root (str d "/missing")}}}})]
      (is (= :seon.fx/unresolved (:seon.error/type bad)) "a dependency that cannot be resolved is its typed error")
      (is (contains? (set (:deps bad)) 'nowhere/nothing) "naming it"))))

(deftest a-missing-local-root-fails-in-jolts-resolver
  ;; task jolt.deps/a-missing-local-root-is-unresolved: red until Jolt's own resolver refuses it
  (let [d (scratch-dir)
        r (try (jolt.deps/resolve-deps {'nowhere/nothing {:local/root (str d "/missing")}} ".") nil
               (catch Throwable e (ex-data e)))]
    (is (contains? (set (map :coord (:unresolved r))) 'nowhere/nothing))))

(deftest a-merges-deferred-tests-read-from-their-results
  ;; the merge gate (root's ruling, 2026-10-22) runs them through prove's one entry, and reads
  ;; each one's result at the digest prove's plan gave it (out/tests.edn) on the results root's main
  ;; its shards record into, never its printed output (seon.prove is the one runner,
  ;; 2026-10-27: the regex over RED and SHARD lines went): a green there passed (it ran green, or
  ;; was green at its digest), a red is red, and a test with no result, or no digest (the plan
  ;; or the build failed before it), is :error, never a pass (review, 2026-10-23)
  (let [tests ['seon.root-test/boots 'seon.root-test/threads 'seon.check-test/clean 'seon.check-test/never]
        plan {:digests {'seon.root-test/boots "dg-boots" 'seon.root-test/threads "dg-threads" 'seon.check-test/clean "dg-clean"}}
        found {"dg-boots" {:digest "dg-boots" :pass 1 :fail 0 :error 0} "dg-threads" {:digest "dg-threads" :pass 0 :fail 1 :error 0}}]
    (is (= {'seon.root-test/boots :pass 'seon.root-test/threads :fail 'seon.check-test/clean :error 'seon.check-test/never :error}
           (fx/test-results plan found tests))
        "green at its digest passed; a red is red; a digest with no result, or a test the plan never named, is no pass")
    (is (= {'seon.root-test/boots :error} (fx/test-results nil found ['seon.root-test/boots]))
        "a run whose plan never ran says nothing of any test")))

(deftest a-merges-deferred-tests-command
    (is (= ["nice" "-n" "15" "bin/prove" "--out" "/o" "--tests" "seon.check-test" "seon.root-test"] (fx/test-command ['seon.check-test 'seon.root-test] "/o"))))

(deftest a-gate-runs-on-the-head-a-new-merges-branch-would-start-at
  ;; the restart test's first loop merge was held: "no commit of branch jolt/seon-merges-... in <clone>": the
  ;; merges branch does not exist until the first write-back, which git-commit starts at HEAD; the gate asked
  ;; for the branch itself. branch-head is the one rule
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "user.dir") "/target/tmp/fx-branch-head-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@localhost" args)] (clojure.string/trim (str (:out r)))))]
    (try
      (git "init" "-q")
      (spit (str dir "/a") "1") (git "add" "-A") (git "commit" "-q" "-m" "one")
      (let [head (git "rev-parse" "HEAD")]
        (is (= head (fx/branch-head dir "jolt/seon-merges-new")) "a branch not there yet: HEAD")
        (spit (str dir "/a") "2") (git "add" "-A") (git "commit" "-q" "-m" "two" )
        (git "branch" "jolt/merges" head)
        (is (= head (fx/branch-head dir "jolt/merges")) "a branch there: its own head, not HEAD")
        (is (not= head (fx/branch-head dir "jolt/seon-merges-new")) "and HEAD has moved on"))
      (let [empty-dir (.getCanonicalPath (doto (java.io.File. (str dir "-empty")) (.mkdirs)))]
        (clojure.java.shell/sh "git" "-C" empty-dir "init" "-q")
        (is (nil? (fx/branch-head empty-dir "x")) "a repository with no commit: nil"))
      (finally (clojure.java.shell/sh "rm" "-rf" dir (str dir "-empty"))))))

(deftest the-model-call-uses-the-provider-its-request-names
  ;; owner, 2026-10-28: the provider is resolved at each turn (seon.config/provider-at) and travels with the
  ;; request; root's boot provider (:ctx) answers only a request that names none
  (let [msgs [{:role "user" :content "hi"}]
        none (fx/model {:req {:messages msgs :provider {:kind :none}} :ctx {:provider {:kind :scripted :script 'seon.fixtures/reply}}})
        boot (fx/model {:req {:messages msgs} :ctx {:provider {:kind :none}}})]
    (is (= :seon.model/no-provider (:seon.error/type none)) "the request's :none beats root's scripted boot provider")
    (is (= :seon.model/no-provider (:seon.error/type boot)) "no provider named: root's")))

