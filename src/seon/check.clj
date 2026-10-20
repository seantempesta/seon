(ns seon.check
  "End-to-end checks of seon.root through the same API the dashboard uses (proto/flow
  f2-final proto.smoke). bin/check runs them; target/seon <check> [args] runs one.
  usage: target/seon smoke        3 agents: define, test, transact; merge one; kill one mid-eval
         target/seon scale N      N agents spawned at once, each eval + transact + test"
  (:require [seon.root :as c] [seon.lib :as lib] [datahike.api :as d] [clojure.pprint :refer [pprint]] [clojure.java.shell] [clojure.string] [clojure.core.async.flow :as flow] [seon.db] [seon.reconcile]))

(defn- out! [& xs] (println (apply str xs)) (flush))
(defn- await-status [sys ids pred ms]
  (let [deadline (+ (lib/now-ns) (* ms 1000000))]
    (loop []
      (let [ags (:agents (c/state sys))]
        (if (or (every? #(pred (get-in ags [% :status])) ids) (> (lib/now-ns) deadline))
          (every? #(pred (get-in ags [% :status])) ids)
          (do (Thread/sleep 2) (recur)))))))

(defn- main-q [sys query & inputs] (apply d/q query @(:conn sys) inputs))

(defn- short-items [items] (mapv #(select-keys % [:tag :printed :ms]) items))

(defn smoke []
  (let [t0 (lib/now-ns)
        sys (c/start! {:datoms 2000})
        _ (out! "booted in " (lib/r2 (lib/ms-since t0)) " ms: " (:main (c/state sys {:datoms true})))
        tasks {:a1 "square numbers" :a2 "count the program's vars" :a3 "write a note about app.m0/f1"}
        ids (mapv (fn [[_ task]] (c/spawn! sys {:task task})) (sort tasks))
        _ (out! "spawned " ids ", ready: " (await-status sys ids #{:idle} 5000))
        programs {(ids 0) "(defn sq [x] (* x x))
                           (deftest sq-test (is (= 144 (sq 12))))
                           (transact! [{:note/text \"sq is defined\" :note/agent :a1}])
                           (sq 12)"
                  (ids 1) "(defn n-vars [] (count (q '[:find ?v :where [?v :var/name]])))
                           (deftest n-vars-test (is (= 360 (n-vars))) (is (pos? (n-vars))))
                           (transact! [{:note/text (str \"the program has \" (n-vars) \" vars\") :note/agent :a2}])
                           (n-vars)"
                  (ids 2) "(defn doc-of [v] (q '[:find ?d . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?d]] v))
                           (deftest doc-test (is (= \"f1 adds 1 to x\" (doc-of 'app.m0/f1)))
                             (transact! [{:var/name 'app.m0/f1 :var/doc \"changed inside a test\"}])
                             (is (= \"changed inside a test\" (doc-of 'app.m0/f1))))
                           (transact! [{:note/text \"f1 adds one\" :note/agent :a3 :note/about [:var/name 'app.m0/f1]}
                                       {:var/name 'app.m0/f1 :var/doc \"f1 adds 1 to x\"}])
                           (doc-of 'app.m0/f1)"}
        replies (into {} (pmap (fn [id] [id @(c/submit! sys id (programs id))]) ids))
        _ (doseq [id ids] (out! id " " (short-items (replies id))))
        tests (into {} (pmap (fn [id] [id @(c/run-tests! sys id)]) ids))
        _ (doseq [id ids] (out! id " tests " (select-keys (tests id) [:pass :fail :error :ms])
                                (vec (for [r (:reports (tests id)) :when (not= :pass (:type r))] r))))
        ;; the test's write vanished with the test's process
        _ (out! (ids 2) " after tests, its branch still says: " (:printed (first @(c/submit! sys (ids 2) "(doc-of 'app.m0/f1)"))))
        _ (out! (ids 2) " branch diff before its merge: " @(c/branch-diff sys (ids 2)) "; stats now: " @(c/agent-stats! sys (ids 2)))
        m1 @(c/merge! sys (ids 0))
        m3 @(c/merge! sys (ids 2))
        _ (out! "merge " (ids 0) ": " m1 "\nmerge " (ids 2) ": " m3)
        _ (out! "main notes: " (main-q sys '[:find ?a ?t :where [?n :note/agent ?a] [?n :note/text ?t]]))
        _ (out! "main f1 doc: " (main-q sys '[:find ?d . :where [?e :var/name app.m0/f1] [?e :var/doc ?d]])
                "; note about: " (main-q sys '[:find ?v . :where [?n :note/about ?e] [?e :var/name ?v]]))
        ;; a second merge of the same branch: the note a3 created is the same entity on main
        _ @(c/submit! sys (ids 2) "(transact! [[:db/add (q '[:find ?n . :where [?n :note/agent :a3]]) :note/text \"f1 adds one (edited)\"]])")
        m3b @(c/merge! sys (ids 2))
        _ (out! "merge " (ids 2) " again: " m3b " -> main a3 notes: "
                (main-q sys '[:find [?t ...] :where [?n :note/agent :a3] [?n :note/text ?t]]))
        ;; a conflict: a2 changes f1's doc on its branch too, after main already took a3's change
        _ @(c/submit! sys (ids 1) "(transact! [{:var/name 'app.m0/f1 :var/doc \"a2's doc\"}])")
        m2 @(c/merge! sys (ids 1))
        _ (out! "merge " (ids 1) " (both changed f1's doc): " (dissoc m2 :tx))
        ;; clone: a3 forks itself, live values and all, onto a branch of its branch
        _ @(c/submit! sys (ids 2) "(def memo (atom {:seen 1}))")
        t-clone (lib/now-ns)
        cl @(c/clone! sys (ids 2) {:task "a3's clone"})
        _ (out! "clone " (ids 2) " -> " cl " in " (lib/r2 (lib/ms-since t-clone)) " ms; the clone says: "
                (short-items @(c/submit! sys (:id cl) "(whoami) (swap! memo update :seen inc) (doc-of 'app.m0/f1)"))
                "; the parent still has " (:printed (first @(c/submit! sys (ids 2) "@memo"))))
        ;; merge main forward into a1's branch (git merge main): it now sees a3's notes too
        mm @(c/merge-main! sys (ids 0))
        _ (out! "merge main into " (ids 0) ": " mm " -> it sees "
                (:printed (first @(c/submit! sys (ids 0) "(sort (q '[:find [?t ...] :where [?n :note/text ?t]]))"))))
        ;; robustness: a1 runs forever; kill it; the others keep working
        hung (c/submit! sys (ids 0) "(loop [] (recur))")
        _ (Thread/sleep 200)
        _ (out! (ids 0) " running: " (get-in (c/state sys) [:agents (ids 0) :running]))
        _ (c/kill! sys (ids 0))
        _ (out! "killed " (ids 0) " mid-eval; its pending reply: " (short-items (deref hung 3000 :timeout)))
        _ (out! (ids 2) " still answers: " (short-items @(c/submit! sys (ids 2) "(+ 40 2)")))
        a4 (c/spawn! sys {:task "after the kill"})
        _ (await-status sys [a4] #{:idle} 3000)
        _ (out! a4 " spawned after the kill: " (short-items @(c/submit! sys a4 "(count (q '[:find ?n :where [?n :note/text]]))")))
        st (c/state sys)]
    (out! "\nstate:")
    (pprint (-> st (update :agents (fn [ags] (into (sorted-map) (for [[k v] ags] [k (-> v (dissoc :evals) (update :tests #(some-> % (dissoc :reports))))]))))))
    (c/stop! sys)
    (lib/c-exit 0)))

;; ---- the program is entities -------------------------------------------------------

(defn program []
  (let [sys (c/start! {:datoms 2000})
        _ (out! "root loaded main's program: " (get-in (c/state sys) [:boot :program]))
        [a b] (mapv #(c/spawn! sys {:task %}) ["make f1 multiply" "bystander"])
        _ (await-status sys [a b] #{:idle} 5000)
        p1 (fn [id forms] (mapv (juxt :printed :recorded) @(c/submit! sys id forms)))
        _ (out! a " changes app.m0/f1 in REPL terms: "
                (p1 a "(app.m0/f1 2) (in-ns 'app.m0) (defn f1 [x] (* x 10)) (deftest f1-test (is (= 20 (f1 2)))) (f1 2)"))
        _ (out! b " (its own branch) still runs the old f1: " (p1 b "(app.m0/f1 2)"))
        m @(c/merge! sys a)
        _ (out! "merge " a " (gated by its tests): " m)
        n (c/spawn! sys {:task "born after the merge"})
        _ (await-status sys [n] #{:idle} 5000)
        _ (out! n ", forked after the merge, inherits the new f1 compiled: " (p1 n "(app.m0/f1 2)")
                "; its tests include the merged proof: " (select-keys @(c/run-tests! sys n) [:pass :fail :error]))
        mm @(c/merge-main! sys b)
        _ (out! b " merges main forward " (select-keys mm [:updated? :datoms]) " and now runs: " (p1 b "(app.m0/f1 2)"))
        x (c/spawn! sys {:task "breaks f1"})
        _ (await-status sys [x] #{:idle} 5000)
        _ (out! x " breaks f1: " (p1 x "(in-ns 'app.m0) (defn f1 [x] (- x)) (f1 2)"))
        mx @(c/merge! sys x)
        _ (out! "merge " x ": " (dissoc mx :tx))
        y (c/spawn! sys {:task "after the refused merge"})
        _ (await-status sys [y] #{:idle} 5000)
        _ (out! y ": main still runs " (p1 y "(app.m0/f1 2)"))
        ok (and (:merged? m) (not (:merged? mx)) (= "20" (ffirst (p1 y "(app.m0/f1 2)"))))]
    (out! (if ok "PROGRAM OK" "PROGRAM FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

;; ---- robustness ---------------------------------------------------------------------

(defn robust []
  (let [sys (c/start! {:datoms 2000})
        [a b x] (mapv #(c/spawn! sys {:task %}) ["exits" "misbehaves" "tests misbehave"])
        _ (await-status sys [a b x] #{:idle} 5000)
        check (fn [label ok? v] (out! (if ok? "PASS " "FAIL ") label ": " v) (boolean ok?))
        r1 @(c/submit! sys a "(+ 1 1) (System/exit 3) (+ 2 2)")
        _ (await-status sys [a] #{:dead} 3000)
        _ (Thread/sleep 100)
        c1 (check "agent calling System/exit mid-request dies alone" (and (= "agent died" (:printed (last r1)))
                                                                          (= :dead (get-in (c/state sys) [:agents a :status])))
                  [(short-items r1) (select-keys (get-in (c/state sys) [:agents a]) [:status :exit])])
        r2 @(c/submit! sys b "(transact! [{:no/such-attr 1}])")
        c2 (check "a refused transaction is an :err item" (and (= :err (:tag (first r2))) (re-find #"refused" (:printed (first r2)))) (short-items r2))
        r3 @(c/submit! sys b "(apply str (repeat 100000 \"x\")) (range)")
        c3 (check "huge and infinite values print within the printer's budget" (and (< (count (:printed (first r3))) 25000) (< (count (:printed (second r3))) 25000))
                  (mapv #(count (:printed %)) r3))
        r4 @(c/submit! sys b "(+ 1")
        c4 (check "a read error is one :err item" (= :read (get-in (first r4) [:err :phase])) (short-items r4))
        _ @(c/submit! sys x "(deftest spins (loop [] (recur)))")
        t5 @(c/run-tests! sys x 1000)
        c5 (check "a test that never ends is killed at its bound; the agent lives"
                  (and (= 1 (:error t5)) (= "2" (:printed (first @(c/submit! sys x "(+ 1 1)")))))
                  (select-keys t5 [:error :reports :ms]))
        _ @(c/submit! sys x "(ns-unmap *ns* 'spins) (deftest exits (System/exit 7))")
        t6 @(c/run-tests! sys x)
        c6 (check "a test that kills its process is an error; the agent lives"
                  (and (= 1 (:error t6)) (= "3" (:printed (first @(c/submit! sys x "(+ 1 2)")))))
                  (select-keys t6 [:error :reports :exit]))
        r9 @(c/submit! sys b "(count (vec (range 300000000))) (+ 5 5)")
        c8 (check "an agent past its heap bound gets OutOfMemoryError as an :err item and lives"
                  (and (= :err (:tag (first r9))) (= "10" (:printed (second r9)))) (short-items r9))
        r11 @(c/submit! sys b "(def kept 7) (loop [] (recur)) (+ kept 1)" {:timeout-ms 300})
        p12 (c/submit! sys b "(reduce + (range)) :next")
        _ (Thread/sleep 200)
        _ (c/interrupt! sys b)
        r12 (deref p12 3000 :hang)
        r12b @(c/submit! sys b ":next")
        ;; nREPL's model (owner's choice): an interrupt stops the request; the agent goes on
        c11 (check "root's interrupt! stops the running request; the agent's next request runs"
                   (and (vector? r12) (= "interrupted by root" (:printed (first r12))) (= 1 (count r12)) (= ":next" (:printed (first r12b))))
                   (if (vector? r12) (short-items r12) r12))
        c10 (check "a form past its timeout is interrupted; the agent keeps its state and goes on"
                   (and (re-find #"timed out" (str (:printed (second r11)))) (= "8" (:printed (nth r11 2)))) (short-items r11))
        r10 @(c/submit! sys x "(println \"hello from\" (:id (whoami))) (def busy (future (loop [] (recur))))")
        t10 @(c/run-tests! sys x)
        c9 (check "printed output comes back as :out; a busy thread blocks the test fork instead of breaking it"
                  (and (= "hello from :a3\n" (:out (first r10))) (= 1 (:error t10))
                       (re-find #"another thread" (str (:message (first (:reports t10))))))
                  [(:out (first r10)) (:reports t10)])
        fs-pid (get-in (c/state sys) [:server :pid])
        _ (lib/c-kill fs-pid 9)
        _ (Thread/sleep 300)
        r7 @(c/submit! sys b "(+ 3 4)")
        y (c/spawn! sys {:task "after the fork server died"})
        ready? (await-status sys [y] #{:idle} 5000)
        r8 @(c/submit! sys y "(count (q '[:find ?v :where [?v :var/name]])) (loop [] (recur)) :after" {:timeout-ms 200})
        c7 (check "the fork server dies: agents keep working, a new server forks the next agent (whose time bound works)"
                  (and (= "7" (:printed (first r7))) ready? (= "360" (:printed (first r8)))
                       (re-find #"timed out" (str (:printed (second r8)))) (= ":after" (:printed (nth r8 2))))
                  [(:server (c/state sys)) (short-items r7) (short-items r8)])
        all [c1 c2 c3 c4 c5 c6 c8 c9 c10 c11 c7]]
    (out! (count (filter true? all)) "/" (count all) " robustness checks pass")
    (c/stop! sys)
    (lib/c-exit (if (every? true? all) 0 1))))

;; ---- scale ------------------------------------------------------------------------

(defn scale [n]
  (let [sys (c/start! {:datoms 2000})
        mem0 (lib/sysmem)
        th-boot (lib/nthreads)
        t0 (lib/now-ns)
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "scale " i)})) (range n)))
        ready? (await-status sys ids #{:idle} 20000)
        t-ready (lib/ms-since t0)
        th-ready (lib/nthreads)
        _ (out! "ready " ready? " in " (lib/r2 t-ready) " ms: " (frequencies (map #(get-in (c/state sys) [:agents % :status]) ids)))
        work (fn [id]
               (let [k (subs (name id) 1)
                     t1 (lib/now-ns)
                     r @(c/submit! sys id (str "(defn f [x] (* x " k "))
                                                (deftest f-test (is (= (* 2 " k ") (f 2))))
                                                (transact! [{:note/text \"hi from " k "\" :note/agent " id "}])
                                                (f 21)"))
                     t2 (lib/now-ns)
                     tr @(c/run-tests! sys id)
                     t3 (lib/now-ns)]
                 {:id id :ok (and (every? #(= :ret (:tag %)) r) (= 1 (:pass tr)) (zero? (+ (:fail tr) (:error tr))))
                  :eval-ms (/ (- t2 t1) 1e6) :tests-ms (/ (- t3 t2) 1e6)}))
        results (vec (pmap work ids))
        t-done (lib/ms-since t0)
        th-work (lib/nthreads)
        st (c/state sys)
        mem1 (lib/sysmem)
        agent-mb (keep #(get-in st [:agents % :mem-mb]) ids)
        merged (mapv #(deref (c/merge! sys %)) (take 10 ids))
        notes (count (main-q sys '[:find ?n :where [?n :note/text]]))
        fs (c/fs-call! sys {:op :stats})
        _ (doseq [id ids] (c/kill! sys id))
        killed? (await-status sys ids #{:killed} 10000)
        ;; kill! marks :killed at once; the process is gone when root has retired it
        _ (loop [k 0] (when (and (not-every? #(get-in (c/state sys) [:agents % :ended-ms]) ids) (< k 1000)) (Thread/sleep 10) (recur (inc k))))
        mem2 (do (Thread/sleep 500) (lib/sysmem))
        th-end (lib/nthreads)]
    (pprint {:agents n :all-ready ready? :all-ok (count (filter :ok results))
             :spawn->all-ready-ms (lib/r2 t-ready) :end-to-end-ms (lib/r2 t-done)
             :start-ms (lib/summ (keep #(get-in st [:agents % :forked-ms]) ids))
             :server-fork-ms-p50 (get-in st [:server :fork-ms-p50])
             :eval+transact-ms (lib/summ (map :eval-ms results)) :tests-ms (lib/summ (map :tests-ms results))
             :agent-real-mb (lib/summ agent-mb) :agent-real-mb-sum (lib/r2 (reduce + agent-mb))
             :system-free-drop-mb-per-agent (lib/r2 (/ (- (:free-mb mem0) (:free-mb mem1)) n))
             :system-free-returned-mb-per-agent (lib/r2 (/ (- (:free-mb mem2) (:free-mb mem1)) n))
             :control-real-mb (lib/r2 (lib/real-mb)) :fork-server (select-keys fs [:mem-mb :os-threads :active-threads :mismatch :datoms])
             :writer (:writer st) :merged (count (filter :merged? merged)) :main-notes notes
             :all-killed killed? :all-ended (every? #(get-in (c/state sys) [:agents % :ended-ms]) ids)
             :root-os-threads {:boot th-boot :all-ready th-ready :after-work th-work :after-kill th-end}})
    (c/stop! sys)
    (lib/c-exit 0)))

(defn mem
  "Real memory per agent, per method.md: the system-wide free-page drop across n agents
  (minus root's own growth), against the agents' own page counts (snp + private)."
  [n rounds]
  (let [sys (c/start! {:datoms 2000})]
    (doseq [r (range rounds)]
      (Thread/sleep 500)
      (let [m0 (lib/sysmem) c0 (lib/real-mb)
            ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "mem " i)})) (range n)))
            _ (await-status sys ids #{:idle} 30000)
            _ (Thread/sleep 500)
            m1 (lib/sysmem) c1 (lib/real-mb)
            idle-mb (vec (keep #(get-in (c/state sys) [:agents % :mem-mb]) ids))
            _ (doall (pmap #(deref (c/submit! sys % "(defn f [x] (* x 2)) (deftest t (is (= 4 (f 2)))) (transact! [{:note/text \"m\"}]) (count (q '[:find ?v :where [?v :var/name]]))")) ids))
            _ (doall (pmap #(deref (c/run-tests! sys %)) ids))
            _ (Thread/sleep 1100)
            _ (doall (pmap #(deref (c/submit! sys % "(+ 1 1)")) ids))
            m2 (lib/sysmem) c2 (lib/real-mb)
            work-mb (vec (keep #(get-in (c/state sys) [:agents % :mem-mb]) ids))
            _ (doseq [id ids] (c/kill! sys id))
            _ (await-status sys ids #{:killed} 10000)
            _ (Thread/sleep 1000)
            m3 (lib/sysmem)
            per (fn [a b ca cb] (lib/r2 (/ (- (- (:free-mb a) (:free-mb b)) (- cb ca)) n)))]
        (pprint {:round r :agents n :load (clojure.string/trim (:out (clojure.java.shell/sh "uptime")))
                 :idle {:system-mb-per-agent (per m0 m1 c0 c1) :agent-own-mb (lib/summ idle-mb) :root-growth-mb (lib/r2 (- c1 c0))}
                 :after-work {:system-mb-per-agent (per m0 m2 c0 c2) :agent-own-mb (lib/summ work-mb) :root-growth-mb (lib/r2 (- c2 c0))}
                 :returned-on-kill-mb-per-agent (lib/r2 (/ (- (:free-mb m3) (:free-mb m2)) n))})))
    (c/stop! sys)
    (lib/c-exit 0)))

(defn soak
  "n agents under a random mix of everything for secs: evals with transact!, tests,
  gated merges, merge-main!, clones, interrupts, kills (and respawns), and one fork
  server kill. Checks: no request hangs, no unexpected error, replicas agree with the
  writer, and root's memory over time."
  [n secs]
  (let [sys (c/start! {:datoms 2000})
        _ (some-> (System/getenv "SOAK_PIDFILE") (spit (str (lib/c-getpid))))
        t-end (+ (lib/now-ns) (* secs 1000000000))
        live (atom (vec (pmap (fn [i] (c/spawn! sys {:task (str "soak " i)})) (range n))))
        _ (await-status sys @live #{:idle} 20000)
        counts (atom {})
        bump! (fn [k] (swap! counts update k (fnil inc 0)))
        hangs (atom []) surprises (atom [])
        wait (fn [label p ms] (let [v (deref p ms ::hang)] (when (= v ::hang) (swap! hangs conj label)) v))
        pick (fn [] (let [v @live] (when (seq v) (rand-nth v))))
        live? (fn [id] (#{:idle :busy} (get-in (c/state sys) [:agents id :status])))
        mem-trace (atom [])
        actions
        {:eval (fn [id] (let [r (wait [:eval id] (c/submit! sys id (str "(defn g [x] (* x " (rand-int 100) ")) (g 3) (transact! [{:note/text \"soak\" :note/agent " id "}])")) 30000)]
                          (when (and (vector? r) (live? id) (some #(and (= :err (:tag %)) (not= "agent died" (:printed %))) r))
                            (swap! surprises conj [:eval id (mapv :printed r)]))))
         :test (fn [id] (wait [:deftest id] (c/submit! sys id "(deftest g-test (is (number? (g 2))))") 30000)
                 (let [r (wait [:tests id] (c/run-tests! sys id) 30000)]
                   (when (and (map? r) (live? id) (pos? (+ (:fail r 0) (:error r 0))) (not (re-find #"died" (str (:reports r)))))
                     (swap! surprises conj [:tests id r]))))
         :merge (fn [id] (wait [:merge id] (c/merge! sys id) 30000))
         :merge-main (fn [id] (wait [:merge-main id] (c/merge-main! sys id) 30000))
         :clone (fn [id] (when (< (count @live) (* 2 n))
                           (let [r (wait [:clone id] (c/clone! sys id) 30000)]
                             (when (:ok r) (swap! live conj (:id r))))))
         :interrupt (fn [id] (let [r (wait [:interrupt id] (c/submit! sys id "(loop [] (recur))" {:timeout-ms 50}) 30000)]
                               (when (and (vector? r) (live? id) (not (re-find #"timed out|agent died" (str (:printed (first r))))))
                                 (swap! surprises conj [:interrupt id (mapv :printed r)]))))
         :kill (fn [id] (when (> (count @live) (quot n 2))
                          (c/kill! sys id) (swap! live (fn [v] (vec (remove #{id} v))))
                          (swap! live conj (c/spawn! sys {:task "respawned"}))))}
        weights [:eval :eval :eval :eval :test :test :merge :merge-main :clone :interrupt :kill]
        worker (fn [] (loop [] (when (< (lib/now-ns) t-end)
                                 (when-let [id (pick)]
                                   (let [a (rand-nth weights)]
                                     (if (live? id) (do ((actions a) id) (bump! a)) (Thread/sleep 5))))
                                 (recur))))
        workers (vec (for [_ (range (min 16 n))] (future (worker))))
        tracer (future (loop [k 0] (when (< (lib/now-ns) t-end)
                                     (when (if-let [e (System/getenv "SOAK_FS_KILL_EVERY")]
                                             (and (pos? k) (zero? (mod k (Long/parseLong e))))
                                             (and (= k (quot secs 2)) (not (System/getenv "SOAK_NO_FS_KILL"))))
                                       (lib/c-kill (get-in (c/state sys) [:server :pid]) 9) (bump! :fork-server-kill)
                                       ;; each restart must give a ready agent within 5 s
                                       (when (System/getenv "SOAK_FS_KILL_EVERY")
                                         (Thread/sleep 50)
                                         (let [pid (try (c/spawn! sys {:task "after fs kill"}) (catch Throwable e e))]
                                           (if (and (keyword? pid) (await-status sys [pid] #{:idle} 5000))
                                             (do (bump! :fs-restart-ready) (c/kill! sys pid))
                                             (swap! hangs conj [:fs-restart k (get-in (c/state sys) [:server])])))))
                                     (swap! mem-trace conj [k (lib/r2 (lib/real-mb)) (count @live) (get-in (c/state sys) [:writer :tx-count]) (lib/nthreads)])
                                     (Thread/sleep 1000) (recur (inc k)))))
        _ (doseq [w workers] @w)
        _ @tracer
        _ (Thread/sleep 1000)
        st (c/state sys)
        ids (vec (filter live? @live))
        agree (vec (for [id (take 20 ids)
                         :let [local (some-> (first @(c/submit! sys id "(count (d/datoms (db) :eavt))")) :printed Long/parseLong)
                               w @(c/branch-info sys id)]]
                     [id local (:datoms w) (get-in (c/state sys) [:agents id :mismatch])]))
        fs (c/fs-call! sys {:op :stats})
        main-datoms (count (d/datoms @(:conn sys) :eavt))
        statuses (frequencies (map :status (vals (:agents st))))
        ok (and (empty? @hangs) (empty? @surprises) (every? (fn [[_ l w]] (= l w)) agree)
                (= (:datoms fs) main-datoms) (zero? (:mismatch fs)))]
    (pprint {:agents-at-start n :secs secs :actions @counts :statuses statuses
             :hangs @hangs :surprises (take 10 @surprises)
             :dead-why (frequencies (for [[_ a] (:agents st) :when (= :dead (:status a))] [(:error a) (:exit a)]))
             :replicas-agree (every? (fn [[_ l w]] (= l w)) agree) :agree-sample (take 5 agree)
             :fork-server {:datoms (:datoms fs) :main-datoms main-datoms :mismatch (:mismatch fs) :mem-mb (:mem-mb fs)}
             :server (:server st) :writer (:writer st)
             :root-mem-trace (vec (take-nth (max 1 (quot secs 12)) @mem-trace))
             :result (if ok :SOAK-OK :SOAK-FAILED)})
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

(defn swarm
  "n agents work main's tasks at once. Agent i takes module k = i mod modules: it rewrites
  app.mK/f1 in REPL terms (recorded on its branch), adds a proof, marks the task done,
  and asks root to merge (gated by its tests and every merged proof). Two agents on one
  module conflict; the loser is retired and a fresh agent, forked after the winner's
  merge, carries the task: it starts from main's new code and its proofs."
  [n]
  (let [sys (c/start! {:datoms 2000})
        modules (count (main-q sys '[:find ?t :where [?t :task/name]]))
        t0 (lib/now-ns)
        log (atom [])
        attempt (fn attempt [k generation]
                  (let [id (c/spawn! sys {:task (str "tidy app.m" k " (try " generation ")")})]
                    (await-status sys [id] #{:idle} 20000)
                    (let [m (str "app.m" k)
                          r @(c/submit! sys id (str "(in-ns '" m ")"
                                                    ;; a tidy rewrite: same behaviour, new text (each agent its own)
                                                    " (defn f1 [x] (+ " (rand-int 1000) " (- x " (rand-int 1000) ") (- 1 " (rand-int 1000) ") (- 0 (- " (rand-int 1000) "))))"
                                                    " (defn f1 [x] (inc x))"
                                                    " (deftest f1-proof-" (name id) " (is (= 3 (f1 2))) (is (= 1 (f1 0))))"
                                                    " (seon.agent/transact! [{:task/name :" m "/tidy :task/status :done :task/agent " id "}])"))
                          ok-eval (every? #(= :ret (:tag %)) r)
                          mr @(c/merge! sys id)]
                      (swap! log conj {:k k :agent id :generation generation :eval ok-eval
                                       :merged (:merged? mr) :conflicts (count (:conflicts mr))
                                       :red (when (:tests mr) (pos? (+ (:fail (:tests mr) 0) (:error (:tests mr) 0))))
                                       :reports (when (System/getenv "SWARM_DEBUG") (take 2 (:reports mr)))})
                      (c/kill! sys id)
                      (when (and (not (:merged? mr)) (< generation 4)) (attempt k (inc generation))))))
        _ (doall (pmap (fn [i] (attempt (mod i modules) 0)) (range n)))
        wall (lib/ms-since t0)
        done (main-q sys '[:find (count ?t) . :where [?t :task/status :done]])
        fresh (c/spawn! sys {:task "check main"})
        _ (await-status sys [fresh] #{:idle} 5000)
        proofs @(c/run-tests! sys fresh)
        l @log
        _ (when (System/getenv "SWARM_DEBUG") (doseq [e (sort-by (juxt :k :generation) l)] (prn e)))]
    (pprint {:agents-started (count l) :modules modules :wall-ms (lib/r2 wall)
             :merged (count (filter :merged l)) :conflicted (count (filter #(pos? (:conflicts %)) l))
             :refused-red (count (filter :red l))
             :by-generation (frequencies (map (juxt :generation :merged) l))
             :tasks-done-on-main done
             :fresh-agent-runs-all-merged-proofs (select-keys proofs [:test :pass :fail :error])
             :writer (:writer (c/state sys)) :main (:main (c/state sys))})
    (c/stop! sys)
    (lib/c-exit 0)))

(defn rtt
  "Round trips through the whole path (submit! -> :agents proc -> ring -> agent eval ->
  ring -> reader -> proc -> promise), sequential, one live agent: eval, eval + q,
  eval + transact!, and a fresh clone."
  []
  (let [sys (c/start! {:datoms 2000})
        a (c/spawn! sys {:task "rtt"})
        _ (await-status sys [a] #{:idle} 5000)
        time-it (fn [n f] (dotimes [_ 20] (f)) (lib/summ (vec (for [_ (range n)] (let [t0 (lib/now-ns)] (f) (/ (- (lib/now-ns) t0) 1e3))))))
        r {:eval-us (time-it 500 #(deref (c/submit! sys a "(+ 1 2)")))
           :eval+q-us (time-it 300 #(deref (c/submit! sys a "(q '[:find ?d . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?d]] 'app.m0/f1)")))
           :eval+transact-us (time-it 300 #(deref (c/submit! sys a "(transact! [{:note/text \"r\"}])")))
           :tests-us (time-it 100 #(deref (c/run-tests! sys a)))
           :clone-us (time-it 50 #(let [r @(c/clone! sys a)] (c/kill! sys (:id r))))}]
    (pprint (assoc r :load (clojure.string/trim (:out (clojure.java.shell/sh "uptime")))))
    (c/stop! sys)
    (lib/c-exit 0)))

(defn orphan
  "Root dies while its agents are busy (one in a loop, one in a forever test, one clone):
  prints their pids and exits; smoke.sh checks none of them outlives root."
  []
  (let [sys (c/start! {:datoms 500})
        [a b] (mapv #(c/spawn! sys {:task %}) ["loops" "tests forever"])
        _ (await-status sys [a b] #{:idle} 5000)
        cl @(c/clone! sys a)
        _ (await-status sys [(:id cl)] #{:idle} 5000)
        _ (c/submit! sys a "(loop [] (recur))")
        _ (c/submit! sys (:id cl) "(loop [] (recur))")
        _ @(c/submit! sys b "(deftest forever (loop [] (recur)))")
        _ (c/run-tests! sys b 600000)
        _ (Thread/sleep 300)
        pids (keep #(get-in (c/state sys) [:agents % :pid]) [a b (:id cl)])
        test-child (:out (clojure.java.shell/sh "pgrep" "-P" (str (get-in (c/state sys) [:agents b :pid]))))]
    (out! "PIDS " (clojure.string/join " " (concat pids [(get-in (c/state sys) [:server :pid])] (clojure.string/split-lines test-child))))
    (lib/c-exit 0)))

;; ---- durability: the whole system restarts and recovers ------------------------------

(defn- dir-bytes [path] (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (java.io.File. path))))))

(defn durable-write
  "Phase 1 (a process of its own): boot on a fresh store at path, then
  a merges a new f1 (gated), b commits unmerged facts and a recorded defn on its branch,
  c's clone commits on a branch of a branch. Root then exits WITHOUT stop! (SIGKILL of
  itself): nothing but the store survives."
  [path]
  (let [t0 (lib/now-ns)
        sys (c/start! {:datoms 2000 :path path})
        _ (out! "fresh store booted in " (lib/r2 (lib/ms-since t0)) " ms: " (:store (c/state sys)))
        [a b k] (mapv #(c/spawn! sys {:task % :branch %2}) ["make f1 multiply" "unmerged work" "parent"] [:dur-a :dur-b :dur-c])
        _ (await-status sys [a b k] #{:idle} 5000)
        p1 (fn [id forms] (mapv (juxt :printed :recorded) @(c/submit! sys id forms)))
        _ (out! a ": " (p1 a "(in-ns 'app.m0) (defn f1 [x] (* x 10)) (deftest f1-test (is (= 20 (f1 2)))) (f1 2)"))
        m @(c/merge! sys a)
        _ (out! "merge " a ": " (dissoc m :ops))
        _ (out! b ": " (p1 b "(in-ns 'app.m1) (defn f2 [x] (str \"b:\" x)) (deftest f2-test (is (= \"b:1\" (f2 1))))
                             (in-ns 'agent.a2) (transact! [{:note/text \"b's unmerged note\" :note/agent :b}]) (app.m1/f2 1)"))
        cl @(c/clone! sys k {:branch :dur-clone})
        _ (await-status sys [(:id cl)] #{:idle} 5000)
        _ (out! "clone " (:id cl) ": " (p1 (:id cl) "(transact! [{:note/text \"the clone's note\" :note/agent :clone}]) (count (q '[:find ?n :where [?n :note/text]]))"))
        st (c/state sys {:datoms true})
        expect {:main-datoms (get-in st [:main :datoms]) :main-t (get-in st [:main :t])}]
    (spit (str path ".expect.edn") (pr-str expect))
    (out! "phase 1 done: " expect "; store " (dir-bytes path) " bytes; killing root (no stop!)")
    (lib/c-kill (lib/c-getpid) 9)))

(defn durable-read
  "Phase 2 (a new process): boot on the same store and check main, the program, the
  branches and agents' committed work came back; resume b and merge it now."
  [path]
  (let [expect (read-string (slurp (str path ".expect.edn")))
        t0 (lib/now-ns)
        sys (c/start! {:datoms 2000 :path path})
        boot-ms (lib/r2 (lib/ms-since t0))
        st (c/state sys {:datoms true})
        _ (out! "recovered in " boot-ms " ms: " (:store st) " main " (:main st))
        main-ok (= (:main-datoms expect) (get-in st [:main :datoms]))
        n (c/spawn! sys {:task "born after the restart"})
        _ (await-status sys [n] #{:idle} 5000)
        p1 (fn [id forms] (mapv (juxt :printed :recorded) @(c/submit! sys id forms)))
        r-n (p1 n "(app.m0/f1 2) (count (q '[:find ?n :where [?n :note/text]]))")
        _ (out! n " (main after restart): " r-n)
        rb (c/spawn! sys {:task "resume b" :branch :dur-b})
        _ (await-status sys [rb] #{:idle} 5000)
        r-b (p1 rb "(app.m1/f2 1) (q '[:find [?t ...] :where [?n :note/text ?t]]) (app.m0/f1 2)")
        _ (out! rb " resumes :dur-b: " r-b)
        t-b @(c/run-tests! sys rb)
        _ (out! rb " tests (its recorded f2-test; main's later f1-test is not on its branch): " (select-keys t-b [:pass :fail :error]))
        rc (c/spawn! sys {:task "resume the clone" :branch :dur-clone})
        _ (await-status sys [rc] #{:idle} 5000)
        r-c (p1 rc "(sort (q '[:find [?t ...] :where [?n :note/text ?t]]))")
        _ (out! rc " resumes :dur-clone: " r-c)
        mb @(c/merge! sys rb)
        _ (out! "merge the resumed :dur-b now: " (dissoc mb :ops))
        n2 (c/spawn! sys {:task "after merging b"})
        _ (await-status sys [n2] #{:idle} 5000)
        r-n2 (p1 n2 "(app.m1/f2 1) (q '[:find [?t ...] :where [?n :note/agent :b] [?n :note/text ?t]]) (app.m0/f1 2)")
        _ (out! n2 ": " r-n2)
        ok (and main-ok (:recovered? (:store st))
                (= #{:dur-a :dur-b :dur-c :dur-clone} (set (get-in st [:store :recovered-branches])))
                (= "20" (ffirst r-n)) (= "\"b:1\"" (ffirst r-b)) (re-find #"unmerged" (str (second r-b)))
                (zero? (+ (:fail t-b) (:error t-b))) (pos? (:pass t-b))
                (re-find #"clone's note" (str r-c)) (:merged? mb) (= "\"b:1\"" (ffirst r-n2)) (= "20" (first (nth r-n2 2))))]
    (out! (if ok "DURABLE OK" "DURABLE FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

;; ---- durability numbers ----------------------------------------------------------------

(defn dur-bench-write
  "A fresh store at path seeded with about n datoms; then one agent's sequential
  eval + transact! round trips (the commit latency), and the store's bytes."
  [path n]
  (let [t0 (lib/now-ns)
        sys (c/start! {:datoms n :path path})
        boot-ms (lib/ms-since t0)
        a (c/spawn! sys {:task "commit latency"})
        _ (await-status sys [a] #{:idle} 10000)
        time-it (fn [k f] (dotimes [_ 20] (f)) (lib/summ (vec (for [_ (range k)] (let [t0 (lib/now-ns)] (f) (/ (- (lib/now-ns) t0) 1e3))))))
        r {:n n :datoms (get-in (c/state sys {:datoms true}) [:main :datoms]) :create+seed-ms (lib/r2 boot-ms)
           :store-bytes-after-seed (dir-bytes path)
           :eval+transact-us (time-it 200 #(deref (c/submit! sys a "(transact! [{:note/text \"r\"}])")))
           :eval-us (time-it 200 #(deref (c/submit! sys a "(+ 1 2)")))
           :store-bytes (dir-bytes path)
           :store (:store (c/state sys {:datoms true}))}]
    (pprint r)
    (c/stop! sys)
    (lib/c-exit 0)))

(defn dur-bench-read
  "Restart on the store at path: how long recovery takes, by stage."
  [path]
  (let [t0 (lib/now-ns)
        sys (c/start! {:datoms 0 :path path})
        st (c/state sys)]
    (pprint {:boot-ms (lib/r2 (lib/ms-since t0)) :main (:main st) :store (:store st) :program (get-in st [:boot :program :ms])
             :store-bytes (dir-bytes path)})
    (c/stop! sys)
    (lib/c-exit 0)))

;; ---- lifecycle (lane F1): strict start, pause, stop ---------------------------------

(defn- alive? [pid] (zero? (lib/c-kill pid 0)))

(defn lifecycle
  "n cycles of start! -> 3 agents work -> stop! in ONE process. Each stop! must answer
  with every proc exited (:graph true) and no agent or fork-server process left, and root's
  thread count must come back to where it was after the first cycle. Then pause/resume:
  a paused agent's request is held (no answer), and answered after resume, in order."
  [n]
  (let [rows (vec (for [i (range n)]
                    (let [t0 (lib/now-ns)
                          sys (c/start! {:datoms 2000})
                          boot-ms (lib/ms-since t0)
                          ids (mapv #(c/spawn! sys {:task (str "lc " %)}) (range 3))
                          ready? (await-status sys ids #{:idle} 5000)
                          ok? (every? #(= "4" (:printed (first @(c/submit! sys % "(+ 2 2)")))) ids)
                          health (c/health sys)
                          pids (cons (get-in (c/state sys) [:server :pid]) (keep #(get-in (c/state sys) [:agents % :pid]) ids))
                          st (c/stop! sys)
                          left (filter alive? pids)]
                      {:cycle i :ready ready? :ok ok? :boot-ms (lib/r2 boot-ms) :stop st :left (vec left)
                       :health (set (map #(if (map? %) (:status %) %) (vals health))) :threads (lib/nthreads)})))
        th (map :threads rows)
        sys (c/start! {:datoms 2000})
        a (c/spawn! sys {:task "paused"})
        _ (await-status sys [a] #{:idle} 5000)
        _ (c/pause-agent! sys a)
        _ (Thread/sleep 50)
        paused-health (c/agent-health sys a)
        p1 (c/submit! sys a "(def x 1) x")
        p2 (c/submit! sys a "(inc x)")
        held (deref p1 300 :held)
        held-state (= :paused (:status (c/agent-health sys a)))
        _ (c/resume-agent! sys a)
        r1 (deref p1 3000 :hang) r2 (deref p2 3000 :hang)
        st (c/stop! sys)
        checks {:every-stop-exited (every? #(and (true? (get-in % [:stop :graph])) (zero? (get-in % [:stop :agents-left])) (empty? (:left %))) rows)
                :every-cycle-worked (every? #(and (:ready %) (:ok %) (= #{:running} (:health %))) rows)
                :threads-flat (<= (- (apply max (rest th)) (second th)) 2)
                :pause-holds (and (= :held held) (true? held-state) (= :paused (:status paused-health)))
                :resume-releases-in-order (and (= "1" (:printed (second r1))) (= "2" (:printed (first r2))))
                :final-stop (true? (:graph st))}]
    (pprint {:cycles (mapv #(select-keys % [:cycle :boot-ms :threads :left :health]) rows)
             :stop-ms (lib/summ (map #(get-in % [:stop :ms]) rows))
             :checks checks})
    (out! (if (every? true? (vals checks)) "LIFECYCLE OK" "LIFECYCLE FAIL"))
    (lib/c-exit (if (every? true? (vals checks)) 0 1))))

;; ---- delivery (lane F2): main's commits to the reconciler, messages to agents ------

(defn deliver-check
  "k commits to main, one at a time: each reaches the :reconciler (commit -> seen, one
  way, e11's shared clock). Then one message to all n agents, k times (encoded once,
  written to every ring): message -> agent has it, p50/p95. And the writer's throughput
  (m main commits in flight) with no reconciler work."
  [n]
  (let [k 200 m 2000
        sys (c/start! {:datoms 2000})
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "deliver " i)})) (range n)))
        _ (await-status sys ids #{:idle} 20000)
        thru (fn [label]
               (let [t0 (lib/now-ns)
                     ps (mapv #(c/transact-main! sys [{:note/text (str label %) :note/agent :thru}]) (range m))]
                 (run! deref ps)
                 (lib/r2 (/ m (/ (- (lib/now-ns) t0) 1e9)))))
        tx-per-s (thru "t")
        seen0 (count (:main-seen-us (c/stats sys)))
        _ (dotimes [i k] @(c/transact-main! sys [{:note/text (str "w" i) :note/agent :deliver}]) (Thread/sleep 1))
        _ (Thread/sleep 50)
        seen (drop seen0 (:main-seen-us (c/stats sys)))
        lat (vec (for [i (range k)]
                   (let [before (count (:msg-us (c/stats sys)))]
                     (c/send-message! sys ids {:i i})
                     (loop [j 0] (when (and (< (count (:msg-us (c/stats sys))) (+ before n)) (< j 4000))
                                   (Thread/sleep 0 200000) (recur (inc j))))
                     (count (:msg-us (c/stats sys))))))
        st (c/stats sys)
        took (first @(c/submit! sys (first ids) "(count (messages))"))]
    (pprint {:agents n :commits k :commit->reconciler-us (lib/summ (vec seen))
             :messages k :all-delivered (= (* k n) (count (:msg-us st))) :message->agent-us (lib/summ (:msg-us st))
             :first-agent-inbox (:printed took) :writer-tx-per-s tx-per-s
             :load (clojure.string/trim (:out (clojure.java.shell/sh "uptime")))})
    (c/stop! sys)
    (lib/c-exit 0)))

;; ---- drain (lane F2): shutdown under load loses nothing accepted -------------------

(defn drain
  "n agents each transact in a loop, and root's own writes stream in, when stop! is
  called. Every request the entrance accepted is answered; every :ok write is in the
  store afterwards (durable: reopened in a fresh connection), and nothing is left running."
  [n]
  (let [dir (str "/tmp/seon-drain-" (random-uuid))
        sys (c/start! {:datoms 2000 :path dir})
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "drain " i)})) (range n)))
        _ (await-status sys ids #{:idle} 20000)
        pids (keep #(get-in (c/state sys) [:agents % :pid]) ids)
        fs-pid (get-in (c/state sys) [:server :pid])
        ;; each agent: one eval writing 50 notes, one transact! at a time
        evals (mapv #(c/submit! sys % (str "(dotimes [i 50] (transact! [{:note/text (str \"d\" i) :note/agent " % "}])) :done")) ids)
        mains (vec (for [i (range 300)] (do (when (= i 150) (Thread/sleep 20)) (c/transact-main! sys [{:note/text (str "m" i) :note/agent :root}]))))
        st (c/stop! sys)
        evr (mapv #(deref % 1000 :hang) evals)
        mr (mapv #(deref % 1000 :hang) mains)
        ok-main (count (filter :ok mr))
        refused-main (count (filter #(= "root stopped" (:error %)) mr))
        eval-done (count (filter #(= ":done" (:printed (second %))) evr))
        eval-other (frequencies (map #(if (vector? %) (:printed (last %)) %) evr))
        cfg (seon.db/store-cfg :lmdb dir)
        conn (d/connect cfg)
        main-notes (d/q '[:find (count ?e) . :where [?e :note/agent :root]] @conn)
        left (filter #(zero? (lib/c-kill % 0)) (cons fs-pid pids))
        checks {:every-request-answered (and (not-any? #{:hang} evr) (not-any? #{:hang} mr))
                :main-ok-all-stored (= ok-main (or main-notes 0))
                :main-answers-account (= 300 (+ ok-main refused-main))
                :nothing-left-running (empty? left)
                :graph-stopped (true? (:graph st))}]
    (d/release conn)
    (clojure.java.shell/sh "rm" "-rf" dir)
    (pprint {:agents n :stop st :main {:ok ok-main :refused-after-close refused-main :stored main-notes}
             :evals {:done eval-done :other eval-other} :left (vec left) :checks checks})
    (out! (if (every? true? (vals checks)) "DRAIN OK" "DRAIN FAIL"))
    (lib/c-exit (if (every? true? (vals checks)) 0 1))))

;; ---- writers (lane F2): group commit at 1/8/32/256 concurrent writers --------------

(defn writers
  "Writer throughput and per-write latency with w agents each running k transact! in a
  loop at once (one write in flight per agent), w in ws. store: mem or lmdb."
  [store ws]
  (let [sys (c/start! (cond-> {:datoms 2000} (= store "lmdb") (assoc :path (str "/tmp/seon-writers-" (random-uuid)))))
        maxw (apply max ws)
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "w" i)})) (range maxw)))
        _ (await-status sys ids #{:idle} 30000)
        _ (run! deref (mapv #(c/submit! sys % "(transact! [{:note/text \"warm\"}])") ids))
        rows (vec (for [w ws]
                    (let [k (max 20 (quot 4000 w))
                          s0 (c/stats sys)
                          t0 (lib/now-ns)
                          rs (mapv #(c/submit! sys % (str "(let [xs (vec (for [i (range " k ")] (let [t0 (System/nanoTime)] (transact! [{:note/text (str \"n\" i)}]) (/ (- (System/nanoTime) t0) 1000.0))))] (let [v (vec (sort xs)) c (count v)] [(v (quot c 2)) (v (int (* 0.95 c)))])))"))
                                   (take w ids))
                          lats (mapv #(read-string (:printed (first @%))) rs)
                          secs (/ (- (lib/now-ns) t0) 1e9)
                          s1 (c/stats sys)
                          b (- (:batches s1 0) (:batches s0 0)) tx (- (:batched-tx s1 0) (:batched-tx s0 0))]
                      {:writers w :tx (* w k) :tx-per-s (lib/r2 (/ (* w k) secs)) :write-us-p50 (lib/r2 (lib/percentile (map first lats) 0.5)) :write-us-p95 (lib/r2 (lib/percentile (map second lats) 0.5))
                       :tx-per-batch (lib/r2 (/ (double tx) (max 1 b))) :commits (- (:commits s1 0) (:commits s0 0))})))]
    (pprint {:store store :rows rows :load (clojure.string/trim (:out (clojure.java.shell/sh "uptime")))})
    (c/stop! sys)
    (lib/c-exit 0)))

;; ---- back-pressure (lane F1) ------------------------------------------------------

(defn backpressure
  "1. Across the ring: an agent floods root with 20,000 messages while its proc is paused.
     Its channel fills, the poller leaves the frames in the ring, the ring fills, and the
     agent's own send waits: few messages arrive while paused, the agent is still busy,
     and after resume all 20,000 arrive, in order, then its reply.
  2. A saturated writer: the writer proc is paused while n agents transact. The writer's
     in-port (SEON_WRITER_BUF) fills, then the agent procs' puts park; resume completes
     every transaction. Root's memory before/after is reported (a parked put is a parked
     fiber)."
  [n]
  (let [sys (c/start! {:datoms 2000})
        a (c/spawn! sys {:task "flood"})
        _ (await-status sys [a] #{:idle} 5000)
        p (c/submit! sys a "(dotimes [i 20000] (#'seon.agent/send! {:op :noise :i i})) :flooded")
        _ (Thread/sleep 5)
        _ (c/pause-agent! sys a)
        _ (Thread/sleep 400)
        while-paused (:noise (c/stats sys) 0)
        busy (get-in (c/state sys) [:agents a :status])
        t0 (lib/now-ns)
        _ (c/resume-agent! sys a)
        r (deref p 20000 :hang)
        drain-ms (lib/ms-since t0)
        st (c/stats sys)
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "bp " i)})) (range n)))
        _ (await-status sys ids #{:idle} 20000)
        mb0 (lib/r2 (lib/real-mb))
        _ (flow/pause-proc (:graph sys) :writer)
        ps (mapv #(c/submit! sys % "(transact! [{:note/text \"bp\" :note/agent :bp}]) :ok") ids)
        _ (Thread/sleep 500)
        answered-while-paused (count (filter realized? ps))
        mb1 (lib/r2 (lib/real-mb))
        t1 (lib/now-ns)
        _ (flow/resume-proc (:graph sys) :writer)
        rs (mapv #(deref % 20000 :hang) ps)
        resume-ms (lib/ms-since t1)
        ok (count (filter #(= ":ok" (:printed (second %))) rs))
        checks {:ring-pushes-back (and (< while-paused 2000) (= :busy busy))
                :flood-all-arrive-in-order (and (= 20000 (:noise st)) (true? (:noise-ordered st)) (= ":flooded" (:printed (second r))))
                :writer-paused-holds-all (zero? answered-while-paused)
                :writer-resumed-completes-all (= n ok)}]
    (pprint {:flood {:arrived (:noise st) :ordered (:noise-ordered st) :reply (:printed (second r)) :arrived-while-paused while-paused :status-while-paused busy :drain-ms (lib/r2 drain-ms)}
             :writer {:agents n :writer-buf (System/getenv "SEON_WRITER_BUF") :answered-while-paused answered-while-paused
                      :root-real-mb {:before mb0 :writer-saturated mb1} :resume->all-done-ms (lib/r2 resume-ms) :ok ok
                      :not-ok (frequencies (map #(if (vector? %) (mapv :printed %) %) (remove #(= ":ok" (:printed (second %))) rs)))}
             :checks checks})
    (out! (if (every? true? (vals checks)) "BACKPRESSURE OK" "BACKPRESSURE FAIL"))
    (c/stop! sys)
    (lib/c-exit (if (every? true? (vals checks)) 0 1))))


;; ---- hot reload (lane F2): a proc's step-fn var redefined in the built binary -----------

(defn hotreload
  "Redefine the :reconciler proc's step-fn Var while root runs (alter-var-root, as a defn
  at the REPL would): its next step runs the new code, with no update! and no restart."
  []
  (let [sys (c/start! {:datoms 2000})
        hits (atom 0)
        old @#'seon.reconcile/reconciler-proc
        _ @(c/transact-main! sys [{:note/text "before"}])
        _ (alter-var-root #'seon.reconcile/reconciler-proc
                          (fn [f] (fn ([] (f)) ([a] (f a)) ([st tr] (f st tr)) ([st in m] (swap! hits inc) (f st in m)))))
        _ (dotimes [i 3] @(c/transact-main! sys [{:note/text (str "after" i)}]))
        _ (loop [k 0] (when (and (zero? @hits) (< k 200)) (Thread/sleep 5) (recur (inc k))))
        ok (pos? @hits)]
    (alter-var-root #'seon.reconcile/reconciler-proc (constantly old))
    (println {:redefined-step-ran @hits :health (:reconciler (c/health sys))})
    (out! (if ok "HOTRELOAD OK" "HOTRELOAD FAIL"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

(defn -main [& [mode n & more]]
  (case mode
    "dur-bench-write" (dur-bench-write n (Long/parseLong (first more)))
    "dur-bench-read" (dur-bench-read n)
    "durable-write" (durable-write n)
    "durable-read" (durable-read n)
    "scale" (scale (Long/parseLong (or n "100")))
    "robust" (robust)
    "lifecycle" (lifecycle (Long/parseLong (or n "10")))
    "deliver" (deliver-check (Long/parseLong (or n "32")))
    "drain" (drain (Long/parseLong (or n "32")))
    "writers" (writers (or n "mem") (mapv #(Long/parseLong %) (or (seq more) ["1" "8" "32" "256"])))
    "backpressure" (backpressure (Long/parseLong (or n "200")))
    "program" (program)
    "orphan" (orphan)
    "rtt" (rtt)
    "hotreload" (hotreload)
    "swarm" (swarm (Long/parseLong (or n "100")))
    "soak" (soak (Long/parseLong (or n "50")) (Long/parseLong (or (first more) "60")))
    "mem" (mem (Long/parseLong (or n "100")) 3)
    (smoke)))
