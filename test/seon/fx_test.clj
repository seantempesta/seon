(ns seon.fx-test
  "The effect owner and worker step-fns as plain functions (no graph): admission by workers,
  priority order, budget refused, the queue bound, and a worker answering a defect."
  (:require [seon.lib] [clojure.test :refer [deftest is testing]] [jolt.deps] [seon.fx :as fx] [seon.config :as cfg] [seon.contract :as contract] [seon.proofs :as proofs]
            [clojure.core.async.flow :as flow] [clojure.core.async :as a] [seon.model :as model]))

(defn- owner [workers] (fx/owner-proc {:kind :t :workers workers :work nil :back nil}))
(defn- req [k & {:as more}] (merge {:req {:k k} :dest {:agent "boot/1" :op :fx-reply :k k}} more))

(deftest a-read-of-named-paths-reads-only-those-files
  ;; task seon.source/an-import-reads-only-changed-files: (my.root/import! sys paths) names the files
  ;; and the :source read gives just those, with the tree's id for the digest
  (let [r (seon.fx/read-source {:req {:root (seon.config/checkout) :dirs ["src"] :paths #{"src/seon/fx.clj" "src/seon/gone.clj" "README.md"}}})]
    (is (= #{"src/seon/fx.clj" "src/seon/gone.clj"} (set (keys (:value r)))) "the named source files only")
    (is (nil? (get-in r [:value "src/seon/gone.clj"])) "a removed file is nil")
    (is (string? (get-in r [:value "src/seon/fx.clj"])))
    (is (string? (:tree r)) "the tree id, which stands for every file's content")))

(deftest owner-admits-by-workers-and-priority
  (let [[st o] (fx/owner-proc (owner 1) :in (req 1))
        _ (is (= [1] (map #(get-in % [:req :k]) (:to-workers o))) "a free worker takes the first request")
        [st o] (fx/owner-proc st :in (req 2))
        _ (is (empty? (:to-workers o)) "no free worker: queued")
        [st _] (fx/owner-proc st :in (req 3 :prio 5))
        [st o] (fx/owner-proc st :done {:dest {:agent "boot/1" :op :fx-reply :k 1} :outcome {:value 1}})]
    (is (= [{:dest {:agent "boot/1" :op :fx-reply :k 1} :r {:value 1}}] (:out o)) "the outcome goes to its asker")
    (is (= [3] (map #(get-in % [:req :k]) (:to-workers o))) "the higher priority runs next")
    (is (= 1 (:busy st)))))

(deftest owner-refuses-a-spent-budget
  (let [[st _] (fx/owner-proc (owner 1) :in (req 1 :budget 10 :budget-key :task-a))
        [st _] (fx/owner-proc st :done {:dest {:agent "boot/1" :op :fx-reply :k 1} :outcome {:value 1} :budget-key :task-a :cost 10})
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
    (is (= {:agent "boot/1" :op :fx-reply :k 1} (:dest out)))))

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
    (let [o (done {:agent "boot/1" :op :fx-reply :k 1})]
      (is (= [:effect] (map :class (problems o))) "an agent asked: root's problem, of class :effect")
      (is (= [{:dest {:agent "boot/1" :op :fx-reply :k 1} :r failed}] (remove :op (:out o))) "and the agent is answered"))
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

;; ---- a kind that ends its host object when its queue empties (seon.vector's :embed helper) -------

(deftest an-emptied-queue-tells-the-worker-to-close
  (let [idle? (fn [o] (some #{{:idle true}} (:to-workers o)))
        st (fn [close?] (first (fx/owner-proc (fx/owner-proc {:kind :t :workers 1 :batch 3 :close? close? :work nil :back nil}) :in (req 1))))
        done {:batch [{:dest (:dest (req 1)) :outcome {:value 1}}]}
        [st2 _] (fx/owner-proc (st true) :in (req 2))]
    (is (idle? (second (fx/owner-proc (st true) :done done))) "nothing queued, nothing in flight: the worker is told")
    (is (not (idle? (second (fx/owner-proc (st false) :done done)))) "a kind without :close is told nothing")
    (is (not (idle? (second (fx/owner-proc st2 :done done)))) "a request queued behind: the helper stays")
    (is (not (idle? (second (fx/owner-proc (st true) :done {:batch [(assoc (first (:batch done)) :request (req 1 :req {:k 1 :more true}))]}))))
        "an answered request that said more were coming: the helper stays for the next round")))

(deftest a-worker-told-idle-closes-its-host-object-and-keeps-it
  (let [closed (atom [])
        st (fx/worker-proc {:kind :t :owner :fx/t :open (fn [_] {:id 1}) :close (fn [h] (swap! closed conj h) {:pid 7 :exit 0})
                            :perform (fn [{:keys [batch]}] (mapv (fn [_] {:value 1}) batch))})
        [st _] (fx/worker-proc st :work {:batch [(assoc (req 1) :in-ns 0 :taken-ns 0)]})
        [st o] (fx/worker-proc st :work {:idle true})]
    (is (= [{:id 1}] @closed) "close gets the host object")
    (is (= {:pid 7 :exit 0} (:closed st)) "its facts are in the worker's state, for the ping")
    (is (nil? o) "no outcome: nothing was asked")
    (is (= {:id 1} (:handle st)) "the handle stays; the next item uses it")
    (is (nil? (:closed (first (fx/worker-proc (fx/worker-proc {:kind :t :owner :fx/t :perform identity}) :work {:idle true})))) "no host object, nothing to close")))

;; ---- write-back answers what happened (audit GUARDS #21) ------------------------------

(defn- scratch-dir []
  (let [d (str (seon.config/checkout) "/target/tmp/fx-test-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(deftest a-file-the-program-loads-reads
  ;; docs/source.md#a-program-file: seen 2026-11-07, edamame refused three files Jolt loads (a #() inside a #(), a symbol with two slashes in EDN), so a write-back naming them was refused; the check reads as the loader does
  (is (nil? (fx/unread-text "src/x.clj" "(ns x)\n(def f #(do (g #(h %)) %))\n")) "a fn literal inside one reads, as Jolt's reader loads it")
  (is (nil? (fx/unread-text "src/x.clj" "(ns x (:require [clojure.string :as s]))\n(def k ::s/k)\n")) "an alias the ns form declares resolves")
  (is (nil? (fx/unread-text "x.edn" "{a/b/c.jolt {:test 1} :t #env X}\n")) "EDN reads as the EDN reader reads it")
  (is (some? (fx/unread-text "src/x.clj" "(ns x)\n(def f #(do (g %)")) "a form left open is still refused")
  (is (some? (fx/unread-text "x.edn" "{:a 1} ]")) "so is EDN with a stray close"))

(deftest a-file-is-read-as-its-reader-reads-it-before-a-commit
  ;; docs/source.md#a-program-file: measured 2026-11-07, one agent's spit of a schemaless key stopped a new process loading its config; what doesn't read is refused and nothing is committed
  (is (nil? (fx/unreadable "bin/x" "#!/bin/zsh\necho 1\n")))
  (is (some? (fx/unreadable "bin/x" "#!/bin/zsh\nif then\n")) "zsh -n")
  (is (some? (fx/unreadable "bin/x" "#!/usr/bin/perl\nprint (;\n")) "perl -c")
  (is (nil? (fx/unreadable "bin/env" "# sourced\nif then\n")) "no #! line: no reader to ask")
  (is (some? (fx/unreadable "src/x.clj" "(ns x)\n(defn f [] ::a/b)\n")) "an alias no ns form declares does not load, so it does not read")
  (is (some? (fx/unreadable "src/x.clj" "(ns x)\n(defn f [")))
  (is (nil? (fx/unreadable "resources/seon/schemas/x.edn" "{:a #env X}\n")) "a tag reads, as the config's reader reads it")
  (is (some? (fx/unreadable "resources/seon/schemas/x.edn" "{:a ")))
  (let [shipped (seon.config/shipped-text)]
    (is (nil? (fx/unreadable seon.config/shipped-file shipped)) "the shipped file reads")
    (is (re-find #"seon.config.nope/key unknown-key" (fx/unreadable seon.config/shipped-file (clojure.string/replace-first shipped "{" "{:seon.config.nope/key 1\n"))) "a key with no schema is refused, naming it"))
  (let [d (scratch-dir) git #(apply clojure.java.shell/sh "git" "-C" d "-c" "user.name=t" "-c" "user.email=t@t" %&)]
    (git "init" "-q") (spit (str d "/a") "a") (git "add" "a") (git "commit" "-q" "-m" "one")
    (let [r (fx/git-commit {:req {:dir d :branch "b" :files {"bin/x" "#!/bin/sh\nif then\n"} :message "m"}})]
      (is (= :seon.fx/io-error (:seon.error/type r)) (pr-str r))
      (is (clojure.string/includes? (:seon.error/message r) "bin/x") (pr-str r))
      (is (not (zero? (:exit (git "rev-parse" "--verify" "--quiet" "refs/heads/b")))) "the refused one committed nothing"))))

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

;; docs/source.md#indexing-a-tree: an import brings what the tree changed, so a merge the tree never held stays on main
(deftest a-source-read-since-its-last-tree-reads-only-what-moved
  (let [d (str (doto (java.io.File/createTempFile "seon-since" "") (.delete) (.mkdirs)))
        ;; each read-source is about ten git children (tree-id's own index): the test reads no tree it already has
        git (fn [& args] (:out (apply clojure.java.shell/sh "git" "-C" d "-c" "user.email=t@t" "-c" "user.name=t" args)))
        read (fn [since] (seon.fx/read-source {:req (cond-> {:root d :dirs ["src"]} since (assoc :since since))}))]
    (.mkdirs (java.io.File. (str d "/src")))
    (git "init" "-q")
    (spit (str d "/src/a.clj") "(ns a)") (spit (str d "/src/b.clj") "(ns b)") (spit (str d "/notes.clj") "(ns notes)")
    (git "add" ".") (git "commit" "-q" "-m" "one")
    (let [t (:tree (read nil))]
      (spit (str d "/src/b.clj") "(ns b) (def x 1)") (spit (str d "/notes.clj") "(ns notes) (def y 1)")
      (let [r (read t)]
        (is (= {"src/b.clj" "(ns b) (def x 1)"} (:value r)) "the file that moved, under dirs")
        (is (= ["src/b.clj"] (:paths r))))
      (.delete (java.io.File. (str d "/src/a.clj")))
      (is (= nil (get (:value (read t)) "src/a.clj" :absent)) "a removed file reads nil")
      (let [whole (read (apply str (repeat 40 "0")))]
        (is (contains? (:value whole) "src/b.clj") "a tree git can't find reads whole")
        (testing "a dir the last import didn't read is read whole, though nothing in it moved"
          (let [t2 (:tree whole)]
            (.mkdirs (java.io.File. (str d "/bin"))) (spit (str d "/bin/x.clj") "(ns x)") (git "add" ".") (git "commit" "-q" "-m" "two")
            (let [t3 (:tree (read nil))
                  r (seon.fx/read-source {:req {:root d :dirs ["src" "bin"] :since t3 :since-dirs ["src"]}})]
              (is (not= t2 t3))
              (is (= {"bin/x.clj" "(ns x)"} (:value r))))))))))

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
  ;; (the PKG plan, owner-approved; docs/source.md#dependencies):
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
  ;; red until Jolt's own resolver refuses it
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

(deftest a-checkout-says-what-no-merge-carries
  ;; repl-audit B F26: an edit of bin/ports was invisible: (my.branch/changes) read {} and nothing warned
  (let [dir (str (System/getProperty "java.io.tmpdir") "/uncommitted-" (random-uuid))]
    (.mkdirs (java.io.File. dir))
    (clojure.java.shell/sh "git" "init" "-q" dir)
    (is (= [] (fx/uncommitted dir)))
    (spit (str dir "/ports") "x")
    (is (= ["?? ports"] (fx/uncommitted dir)))
    (is (contract/error? (fx/uncommitted (str dir "/none"))) "git can't read it: said, never no files")
    (seon.lib/remove-tree! dir)))

(deftest a-gate-that-cant-analyze-says-so
  ;; deepseek-run6: every one of 209 tests read :error, the cause only in the log
  (is (= "the gate could analyze none of your tests: No such namespace: agent, at 278:13; your branch rewrote the ns form of seon.cost-test, which may have lost a require"
         (seon.agent/gate-plan-failure "prove: run\nerror[analyze/unknown-namespace]: No such namespace: agent\n  --> 278:13\nTEST FAILED (the plan)\n" ['seon.cost-test])))
  (is (nil? (seon.agent/gate-plan-failure "SHARD 3 tests, 2 green\n" []))))

(deftest a-failed-build-says-what-failed-and-which-form
  ;; deepseek-run3: a REPL form recorded as program stopped the gate's build 6 times, with only a log path to the agent, which called it unrelated
  (let [out "build: seon: jolt build\nUnhandled exception (FileNotFoundException): Could not locate seon/repl_test.jolt\n  at ./src/seon/repl.clj:3:1\n  trace:\n"
        files {"src/seon/repl.clj" "(ns seon.repl)\n\n(intern 'seon.repl-test\n  'let 1)\n\n(def after 1)\n"}]
    (is (= "the gate's build failed: FileNotFoundException: Could not locate seon/repl_test.jolt, at src/seon/repl.clj:3, where your change wrote: (intern 'seon.repl-test\n  'let 1)"
           (fx/build-failure out files)))
    (is (clojure.string/ends-with? (fx/build-failure out {}) "a place your change's files don't hold"))
    (is (= "the gate's build failed and named no exception" (fx/build-failure "nothing" files)))))

(deftest a-gates-answer-is-what-the-system-ran
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: the gate is proved by the system (seon.root/prove-tree!), which is
  ;; answered at its first red; a deferred test it never reached is in no result, so its branch keeps it deferred and asks
  ;; again, never a red it did not show (a :fail there would stand until that test's own digest moved)
  (let [a {:red ['seon.root-test/b]
           :reports [{:type :pass :var "#'seon.check-test/a"} {:type :pass :var "#'seon.root-test/b"}
                     {:type :fail :var "#'seon.root-test/b" :message "expected 1"}]
           :digests {'seon.check-test/a "da" 'seon.root-test/b "db" 'seon.agent-test/c "dc"}}
        v (fx/gate-answer a ['seon.check-test/a 'seon.root-test/b 'seon.agent-test/c])]
    (is (= {'seon.check-test/a :pass 'seon.root-test/b :fail} (:results v)))
    (is (= ["expected 1"] (map :message (get-in v [:failures 'seon.root-test/b]))))
    (is (= {'seon.check-test/a "da" 'seon.root-test/b "db"} (:digests v)))))

(deftest a-gate-is-proved-by-the-system-on-its-candidates-checkout
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: the :tests effect makes the candidate's checkout (the merges head,
  ;; the candidate's files over it) and asks the system to prove it (its :ctx's :prove-tree, seon.root/prove-tree! as root
  ;; binds it), named by exactly the deferred tests, in mode :judge, by the checkout's tree; the checkout is removed after.
  ;; Before, it ran bin/prove --tests in the checkout, a shell runner beside the test service
  (let [repo (.getCanonicalPath (doto (java.io.File. (str (seon.config/checkout) "/target/tmp/fx-gate-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" repo "-c" "user.name=t" "-c" "user.email=t@localhost" args)] (clojure.string/trim (str (:out r)))))
        asked (atom nil)
        ;; one red and one test the run never reached (it is answered at its first red): nothing green is held
        prove (fn [on] (reset! asked (assoc on :file (slurp (str (:checkout on) "/src/a.clj")) :tree-now (fx/tree-id (:checkout on))))
                {:red ['a/t] :reports [{:type :fail :var "#'a/t" :message "red"}] :digests {'a/t "d"}})]
    (try
      (git "init" "-q" "-b" "main")
      ;; target/ ignored, as the repository's is: the checkout's cloned products are no part of its tree
      (spit (str repo "/.gitignore") "/target/\n/runs/\n") (spit (str repo "/f") "1") (git "add" "-A") (git "commit" "-q" "-m" "one")
      (let [r (fx/run-tests {:req {:repo repo :branch "main" :files {"src/a.clj" (str "(ns a) ; " (random-uuid))} :tests ['a/t 'a/u]}
                             :ctx {:prove-tree prove}})]
        (is (= {'a/t :fail} (get-in r [:value :results])) (pr-str r))
        (is (= ["red"] (map :message (get-in r [:value :failures 'a/t]))))
        (is (= #{'a/t 'a/u} (:names @asked)) "named by exactly the deferred tests")
        (is (= :judge (:mode @asked)))
        (is (clojure.string/starts-with? (str (:file @asked)) "(ns a)") "the candidate's files are on the checkout it asks to prove")
        (is (= (:tree-now @asked) (:tree @asked)) "named by the checkout's tree")
        (is (not (.exists (java.io.File. (str (:checkout @asked))))) "and the checkout is removed after"))
      (finally (seon.lib/remove-tree! repo)))))

(deftest a-gate-runs-on-the-head-a-new-merges-branch-would-start-at
  ;; the restart test's first loop merge was held: "no commit of branch jolt/seon-merges-... in <clone>": the
  ;; merges branch does not exist until the first write-back, which git-commit starts at HEAD; the gate asked
  ;; for the branch itself. branch-head is the one rule
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (seon.config/checkout) "/target/tmp/fx-branch-head-" (random-uuid))) (.mkdirs)))
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
      (finally (seon.lib/remove-tree! dir (str dir "-empty"))))))

(deftest the-model-call-uses-the-provider-its-request-names
  ;; owner, 2026-10-28: the provider is resolved at each turn (seon.config/provider-at) and travels with the
  ;; request; root's boot provider (:ctx) answers only a request that names none
  (let [msgs [{:role "user" :content "hi"}]
        none (fx/model {:req {:messages msgs :task "a/b" :turn 0 :provider {:kind :none}} :ctx {:provider {:kind :scripted :script 'seon.fixtures/reply}}})
        boot (fx/model {:req {:messages msgs :task "a/b" :turn 0} :ctx {:provider {:kind :none}}})]
    (is (= :seon.model/no-provider (:seon.error/type none)) "the request's :none beats root's scripted boot provider")
    (is (= :seon.model/no-provider (:seon.error/type boot)) "no provider named: root's")))


(deftest a-walk-of-a-tree-with-a-link-cycle-counts-each-file-once
  ;; docs/failure-points.md, a directory walk: file-seq follows a link to a directory, so a link back
  ;; to an ancestor (a checkout's target/ holding a link to itself, measured 2026-10-29) made an
  ;; agent's cache count walk 13 minutes at full CPU. fx/files-under enters no linked directory:
  ;; its work is the entries the tree holds, each file once, wherever a link points. The cache
  ;; count (evict!) is the walk that hung: a cache with a loop in it is counted by its files.
  (let [d (scratch-dir)
        mk (fn [rel text] (let [f (java.io.File. d rel)] (.mkdirs (.getParentFile f)) (spit f text)))
        link (fn [rel to] (java.nio.file.Files/createSymbolicLink (.toPath (java.io.File. d rel)) (.toPath (java.io.File. to)) (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (mk "a/one.edn" "1") (mk "b/c/two.edn" "22") (mk "three.txt" "333")
      (link "a/loop" d)              ;; a link back to the root
      (link "b/up" (str d "/b"))     ;; a link to its own parent
      (link "b/c/out" "/tmp")        ;; a link out of the tree
      (link "a/file-link.edn" (str d "/three.txt"))
      (let [names (mapv #(.getName ^java.io.File %) (fx/files-under d))]
        (is (= ["file-link.edn" "one.edn" "three.txt" "two.edn"] (sort names)) "each file once, a link to a file as a file"))
      (testing "the cache count sees each verdict once"
        (let [r (fx/write-verdicts! d {:id "run" :tree "t"} {'a/t {:test 'a/t :digest "d1" :pass 1 :fail 0 :error 0}} {} 1)]
          (is (= {:recorded 1} r))
          ;; a bound of 1 byte evicts every verdict not just written: the walk ended and removed the three old .edn files
          (is (not (.exists (java.io.File. d "a/one.edn"))))
          (is (not (.exists (java.io.File. d "b/c/two.edn"))))))
      (finally (seon.lib/remove-tree! d)))))

;; docs/merging.md#a-merge-runs-what-the-change-reaches: 17 gates in a row built one candidate to the one refusal, about 40 s each, holding the worker
(deftest a-candidate-that-makes-no-plan-is-not-built-again
  (let [sha (str "test-" (random-uuid))
        calls (atom 0)
        refused (fn [_ _] (swap! calls inc) (seon.contract/error :seon.fx/io-error 'seon.fx/run-on-checkout "the test runner made no plan: No such namespace: print" {:path "/x" :no-plan true}))
        held @#'seon.fx/run-held
        req {:repo "/x" :files {"src/a.clj" "(ns a)"} :tests ['a/t]}
        first-answer (held refused sha req)
        again (held refused sha req)]
    (is (seon.contract/error? first-answer))
    (is (seon.contract/error? again) "the same typed answer")
    (is (= :seon.fx/io-error (:seon.error/type again)))
    (is (clojure.string/includes? (:seon.error/message again) "No such namespace: print") "saying the same why")
    (is (= 1 @calls) "and the second ask built nothing")
    (let [other (held refused sha (assoc-in req [:files "src/a.clj"] "(ns a) ;; changed"))]
      (is (seon.contract/error? other))
      (is (= 2 @calls) "changed files run again"))
    (let [other-error (fn [_ _] (swap! calls inc) (seon.contract/error :seon.fx/io-error 'seon.fx/run-on-checkout "bin/worktree --at exited 1" {:path "/x"}))
          sha2 (str "test-" (random-uuid))]
      (held other-error sha2 req)
      (held other-error sha2 req)
      (is (= 4 @calls) "another failure holds nothing: it may be the machine's"))))

;; a digest does not see the machine, so a gate holds greens only, as a test's plan reuses them
(deftest a-gates-red-runs-again-and-its-green-does-not
  (let [sha (str "test-" (random-uuid))
        calls (atom 0)
        status (atom :fail)
        run (fn [_ {:keys [tests]}] (swap! calls inc)
              {:value {:results (zipmap tests (repeat @status)) :failures (if (= :fail @status) {(first tests) [{:type :fail :message "red"}]} {}) :ms 1}})
        held @#'seon.fx/run-held
        req {:repo "/x" :files {"src/a.clj" "(ns a)"} :tests ['a/t]}]
    (is (= {'a/t :fail} (:results (:value (held run sha req)))))
    (is (= [{:type :fail :message "red"}] (get-in (held run sha req) [:value :failures 'a/t])) "a red runs again, and says why")
    (is (= 2 @calls))
    (reset! status :pass)
    (is (= {'a/t :pass} (:results (:value (held run sha req)))) "so one from outside the program does not stand")
    (is (= {'a/t :pass} (:results (:value (held run sha req)))))
    (is (= 3 @calls) "a green is held")))

;; a gate's refusal keeps what stopped it: its last 3 lines were where and two verdicts, and the error was cut
(deftest a-runners-end-keeps-its-error
  (let [end (@#'seon.fx/runner-end "prove: run\nerror[analyze/unknown-namespace]: No such namespace: print\n  --> 2566:28\nTEST FAILED (the plan)\nPROVE FAILED\n\n")]
    (is (clojure.string/includes? end "No such namespace: print"))
    (is (clojure.string/includes? end "2566:28"))))

(deftest a-build-the-analyzer-stopped-names-its-cause
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a gate on a tree that doesn't compile says what stopped it and the agent's form, not "named no exception"
  (let [out "error[analyze/unresolved-symbol]: Unable to resolve symbol: h in this context\n  --> /x/runs/merge-tests/abc/src/app/a.clj:2:3\n"
        said (seon.fx/build-failure out {"src/app/a.clj" "(ns app.a)\n(defn f [] (h))\n"})]
    (is (clojure.string/includes? said "Unable to resolve symbol: h") said)
    (is (clojure.string/includes? said "src/app/a.clj:2") said)
    (is (clojure.string/includes? said "(defn f [] (h))") said))
  (is (= "the gate's build failed and named no exception" (seon.fx/build-failure "nothing" {}))))


;; ---- a streaming perform (docs/flow.md#effects-one-owner-and-its-workers-per-kind) ----

(defn- streaming-perform
  "A perform that answers a stream over pull: the model's line step and final for call c1 of agent :a1."
  [pull]
  (fn [_] {:stream {:pull pull :init (model/sse-init "c1" "boot/1") :step model/sse-step :close model/sse-close
                    :final (fn [st] {:value (model/stream-reply st 1.0 2048)})}}))

(defn- data-line [m] (str "data: " (clojure.data.json/write-str m) "\n\n"))
(defn- delta [k s] (data-line {:choices [{:delta {k s}}]}))
(defn- piece [s] (.getBytes ^String s "UTF-8"))
(defn- drain
  "Steps the worker on its lines until it gives :done: [final-state stream-messages done-items]."
  [st]
  (loop [s st streams []]
    (let [[s' o] (fx/worker-proc s :lines (a/<!! (:lines s)))
          streams (into streams (get o [:web :streams]))]
      (if (get o [:fx/t :done]) [s' streams (get o [:fx/t :done])] (recur s' streams)))))

(deftest a-streaming-perform-sends-its-first-message-before-its-second-piece-is-read
  (let [gate (promise)
        n (atom 0)
        pull (fn [] (case (swap! n inc) 1 (piece (delta :reasoning_content "Think")) 2 (do @gate (piece (delta :content "Done"))) nil))
        st0 (fx/worker-proc {:kind :t :owner :fx/t :perform (streaming-perform pull)})
        [st o0] (fx/worker-proc st0 :work (assoc (req 1) :in-ns 0 :taken-ns 0))]
    (is (some? (:call st)) "the worker holds the call, its outcome not given")
    (is (nil? o0) "and gives nothing yet")
    (when (:call st)
      (let [[st1 o1] (fx/worker-proc st :lines (a/<!! (:lines st)))]
        (is (not (realized? gate)) "the first line was folded and sent while the second piece is still being read")
        (is (= [{:stream "c1/reasoning" :in "c1" :part "reasoning" :agent "boot/1" :text ["Think"]}] (get o1 [:web :streams])))
        (is (nil? (get o1 [:fx/t :done])))
        (deliver gate true)
        (let [[fin streams done] (drain st1)]
          (is (= [{:stream "c1/content" :in "c1" :part "content" :agent "boot/1" :text ["Done"]} {:stream "c1/reasoning" :in "c1" :part "reasoning" :agent "boot/1" :end true} {:stream "c1/content" :in "c1" :part "content" :agent "boot/1" :end true}]
                 streams))
          (is (= 1 (count done)) "the owner is given :done once, with every id still open ended")
          (is (= "Done" (get-in (first done) [:outcome :value :text])))
          (is (nil? (:call fin)))
          (is (true? ((::flow/input-filter fin) :work)) "and the worker takes work again"))))))

(deftest a-streaming-worker-keeps-its-slot-until-its-done
  (let [pull (let [ps (atom [(piece (delta :content "a")) nil])] (fn [] (let [p (first @ps)] (swap! ps rest) p)))
        st0 (fx/worker-proc {:kind :t :owner :fx/t :perform (streaming-perform pull)})
        [o0 _] (fx/owner-proc (owner 1) :in (req 1))
        [st _] (fx/worker-proc st0 :work (assoc (req 1) :in-ns 0 :taken-ns 0))]
    (is (= 1 (:busy o0)))
    (is (false? ((::flow/input-filter st) :work)) "a worker mid-call reads no work")
    (is (true? ((::flow/input-filter st) :lines)) "only its lines")
    (let [[fin _ done] (drain st)
          [o1 oo] (fx/owner-proc o0 :done (first done))]
      (is (= 1 (count done)))
      (is (= 0 (:busy o1)) "the owner counts the call in flight until its one :done, as for a call not streamed")
      (is (= "a" (get-in (first (:out oo)) [:r :value :text])) "and answers its asker the completion")
      (is (true? ((::flow/input-filter fin) :work))))))

(deftest a-socket-failure-mid-stream-is-a-transport-error-and-ends-every-id
  (let [ps (atom [(piece (delta :reasoning_content "Thin")) (contract/error :seon.model/transport 'seon.model/http-post "transport: reset" {})])
        pull (fn [] (let [p (first @ps)] (swap! ps rest) p))
        st0 (fx/worker-proc {:kind :t :owner :fx/t :perform (streaming-perform pull)})
        [st _] (fx/worker-proc st0 :work (assoc (req 1) :in-ns 0 :taken-ns 0))
        [fin streams done] (drain st)]
    (is (= :seon.model/transport (get-in (first done) [:outcome :seon.error/type])) "the asker is answered the transport error")
    (is (= [{:stream "c1/reasoning" :in "c1" :part "reasoning" :agent "boot/1" :text ["Thin"]} {:stream "c1/reasoning" :in "c1" :part "reasoning" :agent "boot/1" :end true}] streams))
    (is (true? ((::flow/input-filter fin) :work)))))

(deftest a-reader-whose-lines-are-closed-lets-go-of-its-connection
  ;; the worker stopped reading (its chan closed mid-stream): the reader ends and the connection is closed, not left to the OS
  (let [shut (promise)
        lines (a/chan)
        ps (atom [(piece (str (delta :content "a") (delta :content "b"))) (piece (delta :content "c")) (piece (delta :content "d"))])
        t (#'fx/read-lines! (fn [] (let [p (first @ps)] (swap! ps rest) p)) lines #(deliver shut :closed))]
    (is (:line (a/<!! lines)) "one line was taken")
    (a/close! lines)
    (a/<!! lines) ; a put pending when a chan closes is still taken (core.async): the one after it finds it closed
    (is (= :closed @shut) "the reader stopped and closed the connection")
    (.join ^Thread t)
    (is (< (count @ps) 3) "and the rest of the body was not all read" )))

(deftest a-worker-stopped-mid-call-closes-its-connection
  (let [shut (promise)
        pull (let [ps (atom [(piece (str (delta :content "a") (delta :content "b")))])]
               (fn [] (if-let [p (first @ps)] (do (swap! ps rest) p) (do @shut (contract/error :seon.model/transport 'x "closed" {})))))
        perform (fn [_] (assoc-in ((streaming-perform pull) nil) [:stream :shut] #(deliver shut :closed)))
        [st _] (fx/worker-proc (fx/worker-proc {:kind :t :owner :fx/t :perform perform}) :work (assoc (req 1) :in-ns 0 :taken-ns 0))]
    (is (:call st))
    (fx/worker-proc st ::flow/stop)
    (is (= :closed @shut) "stopping the worker closes the call's connection, which ends the reader")
    (let [idle (fx/worker-proc {:kind :t :owner :fx/t :perform identity})] (is (= idle (fx/worker-proc idle ::flow/stop)) "a worker with no call stops as before"))))

(deftest a-write-back-onto-a-checked-out-branch-leaves-that-tree-clean
  ;; docs/source.md#commit-the-merges-branch, 2026-11-11: the standing root's write-back commits onto
  ;; jolt/merges, the branch .worktrees/merges has checked out. git-commit moved the ref by plumbing, so that tree's HEAD
  ;; moved and its index and files stayed at the parent: each merged file a staged deletion, and bin/update's merge of main
  ;; then refused (or, unchecked, did nothing). A commit onto a branch a worktree has checked out goes through that tree:
  ;; its files written, its index, its HEAD, one commit. Red before: git status showed "D  src/demo/half.clj"
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (seon.config/checkout) "/target/tmp/fx-checked-out-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (clojure.string/trim (str (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@localhost" args)))))
        commit #(fx/git-commit {:req (merge {:dir dir :branch "jolt/merges" :message "Seon merge"} %)})]
    (try
      (git "init" "-q" "-b" "main")
      (spit (str dir "/a.txt") "a\n") (git "add" "-A") (git "commit" "-q" "-m" "one")
      (git "checkout" "-q" "-b" "jolt/merges")
      (let [parent (git "rev-parse" "HEAD")
            r (commit {:files {"src/demo/half.clj" "(ns demo.half)\n"}})]
        (is (some? (get-in r [:value :sha])) (pr-str r))
        (is (= (get-in r [:value :sha]) (git "rev-parse" "HEAD")) "the checked-out branch moved")
        (is (= parent (git "rev-parse" "HEAD^")) "by one commit on its old head")
        (is (= "" (git "status" "--short")) "and the tree is clean: index and files at the new HEAD")
        (is (= "(ns demo.half)\n" (slurp (str dir "/src/demo/half.clj"))) "the merged file is on disk"))
      (testing "a change of the tree's own is never overwritten: the write-back refuses and commits nothing"
        (spit (str dir "/src/demo/half.clj") "edited by hand\n")
        (let [head (git "rev-parse" "HEAD")
              r (commit {:files {"src/demo/half.clj" "(ns demo.half)\n(def x 1)\n"}})]
          (is (= :seon.fx/io-error (:seon.error/type r)) (pr-str r))
          (is (= head (git "rev-parse" "HEAD")) "nothing committed")
          (is (= "edited by hand\n" (slurp (str dir "/src/demo/half.clj"))) "the hand's edit kept")))
      (testing "a branch no tree has checked out is committed by plumbing, every tree's files as they are"
        (git "checkout" "-q" "--" "src/demo/half.clj")
        (let [r (fx/git-commit {:req {:dir dir :branch "jolt/sync-x" :message "sync" :files {"b.txt" "b\n"}}})]
          (is (some? (get-in r [:value :sha])) (pr-str r))
          (is (= "" (git "status" "--short")) "the checked-out tree untouched")
          (is (not (.exists (java.io.File. (str dir "/b.txt")))) "and no file written into it")))
      (finally (seon.lib/remove-tree! dir)))))

(deftest a-sessions-item-runs-on-its-checkout-once
  ;; docs/merging.md#a-session-proves-through-root (Flow step 4, with Tests): the :item effect runs one item seon.prove granted
  ;; on the session's checkout, and its verdict is the cache's by digest: an item runs (a test as a shard of one, bin/test --shard,
  ;; its green stored by the runner; one needing no binary, so this tree's own build is not the subject),
  ;; the same item asked again is answered from the cache with no process started, and a checkout whose files are no longer
  ;; the item's tree is refused. Red before: an asked-again item started its runner again only to reuse the green (2.3 s a test)
  (let [co (str (cfg/checkout)) tree (fx/tree-id co)
        ;; a digest of this run's own, so its first ask has no green to reuse
        dg (subs (str (random-uuid)) 0 16)
        item {:kind :process :tree tree :checkout co :name 'seon.fx-test/a-read-of-named-paths-reads-only-those-files :digest dg :binary? false}
        one (fx/run-item {:req item})
        two (fx/run-item {:req item})
        moved (fx/run-item {:req (assoc item :tree "0000000000000000000000000000000000000000")})]
    (is (= 1 (get-in one [:value :pass])) (pr-str one))
    (is (not (get-in one [:value :reused])) "the first ask ran the test")
    (is (= {:pass 1 :reused true} (select-keys (:value two) [:pass :reused])) (str "asked again, the cache answers: " (pr-str two)))
    (is (and (contract/error? moved) (:tree-moved moved)) (pr-str moved))))

;; docs/merging.md#a-session-proves-through-root: an item's command past its bound is ended whole (bin/bounded): its evidence kept,
;; every process of its group ended, an orphan reparented to pid 1 included, and the exit 124. Red before: bin/end-tree, which
;; bin/check and bin/stalls end an overran run with, walks by parent and spares its caller's group, so an orphan of the command
;; lived on (a forked shard runner held a prove 22 minutes, 2026-11-11). Under the bound the command's output and status are its own,
;; and what it left in its group is ended as it ends
(deftest a-bounded-command-is-ended-whole-past-its-bound
  (let [root (str (cfg/checkout)) dir (str (System/getProperty "java.io.tmpdir") "/seon-bounded-" (random-uuid))
        ;; a sleep of a length no other process has, so the check reads only this test's orphan
        n (+ 40000 (rand-int 20000))
        alive? (fn [m] (some #(clojure.string/ends-with? % (str "sleep " m)) (clojure.string/split-lines (:out (seon.lib/sh "ps" "-axo" "command")))))
        over (seon.lib/sh (str root "/bin/bounded") "1000" (str dir "/over") "zsh" "-c" (str "( sleep " n " & ) ; sleep " (inc n)))
        under (seon.lib/sh (str root "/bin/bounded") "60000" (str dir "/under") "zsh" "-c" (str "( sleep " (+ 2 n) " & ) ; echo kept; exit 3"))]
    (try
      (is (= 124 (:exit over)) (pr-str over))
      (is (.isFile (java.io.File. (str dir "/over/overran"))) "the bound it ran past is written")
      (is (.isFile (java.io.File. (str dir "/over/ps.txt"))) "its evidence was captured before it was ended")
      (is (not (alive? n)) "the orphan, ppid 1 in the command's group, is ended with it")
      (is (not (alive? (inc n))) "and the command itself")
      (is (= [3 "kept\n"] [(:exit under) (:out under)]) (pr-str under))
      (is (not (.exists (java.io.File. (str dir "/under/overran")))) "under its bound no overran")
      (is (not (alive? (+ 2 n))) "what it left in its group is ended as it ends")
      (finally (seon.lib/remove-tree! dir)))))

;; a test that hangs only when its fixture asks it to: it reads the fifo target/prove/hang.fifo of its checkout when one is there,
;; which with no writer never ends (a-process-item-past-its-bound-is-a-signal-and-nothing-of-it-runs-on makes it); with none it
;; passes at once, so a prove pays nothing for it
(deftest a-test-that-hangs-while-its-fifo-is-there
  (let [f (java.io.File. (str (cfg/checkout) "/target/prove/hang.fifo"))]
    (is (or (not (.exists f)) (string? (slurp f))))))

;; docs/merging.md#a-session-proves-through-root: a process item past its bound is ended whole and answers the signal a step past
;; its bound does, :overran with no verdict, never a red and never a worker held (f1 sets :bound, f2 honors it). The item is a test
;; that hangs (a-test-that-hangs-while-its-fifo-is-there, its fifo made here). Red before: the bound was not read, and the item held
;; its worker until the fifo went
(deftest a-process-item-past-its-bound-is-a-signal-and-nothing-of-it-runs-on
  (let [co (str (cfg/checkout)) tree (fx/tree-id co)
        dg (subs (str (random-uuid)) 0 16)
        fifo (str co "/target/prove/hang.fifo")
        t 'seon.fx-test/a-test-that-hangs-while-its-fifo-is-there]
    (.mkdirs (java.io.File. (str co "/target/prove")))
    (seon.lib/sh "mkfifo" fifo)
    (try
      (let [;; 3 s: past the shard's start, so the test is running (hung) when it is reached
            r (fx/run-item {:req {:kind :process :tree tree :checkout co :name t :digest dg :binary? false :bound 3000}})
            ev (str co "/target/prove/items/process-" dg "/evidence")]
        (is (= :overran (get-in r [:value :signal])) (pr-str r))
        (is (nil? (get-in r [:value :pass])) "no verdict")
        (is (.isFile (java.io.File. (str ev "/ps.txt"))) "its evidence was kept")
        (is (not (proofs/green? (get (fx/stored-results {t dg}) dg))) "nothing stored at its digest"))
      (finally (.delete (java.io.File. fifo))))))
