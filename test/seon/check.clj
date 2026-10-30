(ns seon.check
  "End-to-end checks of seon.root through the same API the dashboard uses (proto/flow
  f2-final proto.smoke). bin/check runs them; target/seon <check> [args] runs one. A driver,
  outside src (task seon.main/drivers-leave-src): the binary's -main (seon.main/-main) serves
  root and ports and loads this driver from the checkout's test/ for every other mode.
  usage: target/seon smoke        3 agents: define, test, transact; merge one; kill one mid-eval
         target/seon scale N      N agents spawned at once, each eval + transact + test
         target/seon scale-work N N agents (10) that work: five evals and a prove of ~60 tests each
  A mode is its body: run-check boots root, runs it, prints its report and checks, stops
  root and ends on the one verdict line, NAME OK or NAME FAILED, and its exit status. Waits
  are root's events (await-agents, on a tap taken before what causes them) or a watch on a
  reference (await-ref, seon.root/await-db), never a poll or a deadline."
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.root :as c] [seon.lib :as lib] [datahike.api :as d] [seon.print :as print] [clojure.java.shell] [clojure.string] [clojure.core.async :as a] [clojure.core.async.flow :as flow] [seon.db] [seon.reconcile] [seon.contract :as contract] [malli.core :as m] [malli.generator :as mg] [seon.e2e] [seon.await] [seon.system] [seon.web] [seon.render.proc] [seon.fixtures] [my.root] [seon.task] [seon.config] [clojure.edn] [seon.server] [jolt.scheme] [clojure.core.async.flow.spi] [clojure.data.json] [seon.cost] [seon.problems] [seon.proofs] [seon.vector] [seon.main :refer [out! shown]] [seon.fx] [seon.source] [seon.docs] [seon.test-store :as ts] [seon.sock :as sock]))


;; ---- the harness: one frame, one verdict, one way to wait ------------------------------

(defn- verdict!
  "The one verdict: NAME OK or NAME FAILED as the last line, and the exit status, 0 only
  when every check holds."
  [nm checks]
  (let [ok (every? identity (vals checks))]
    (out! nm (if ok " OK" " FAILED"))
    (lib/c-exit (if ok 0 1))))

(defn- run-check
  "One check mode's frame: root booted with opts (seon.root/start!; nil boots none), (body sys)
  answering {:checks {k truthy} :report m :then :stop|:kill}, the report printed with its
  checks, root stopped, then the verdict (verdict!). With :then :kill, phase 1 of a
  kill/reopen pair: the verdict line, then root SIGKILLs itself so nothing but the store
  survives (exit 137), and phase 2 reopens the store in a process of its own."
  [nm opts body]
  (let [sys (when opts (c/start! opts))
        {:keys [checks report then]} (body sys)
        checks (into (sorted-map) (for [[k v] checks] [k (boolean v)]))]
    (out! (shown (assoc report :checks checks)))
    (if (= :kill then)
      (do (out! nm (if (every? val checks) " OK" " FAILED")) (lib/c-kill (lib/c-getpid) 9))
      (do (some-> sys c/stop!) (verdict! nm checks)))))

(defn- await-event
  "The first event on ev (a tap of root's events, seon.root/events, taken before what causes
  it) for which (pred e) holds: root's events are the dependency, never a poll or a
  deadline. An error when the events end (root stopped) first."
  [ev pred]
  (loop [] (let [e (a/<!! ev)] (cond (nil? e) (throw (ex-info "root's events ended" {})) (pred e) e :else (recur)))))

(defn- await-each
  "The first event on ev (await-event) for each of preds, in preds' order, whatever order
  they arrive in (one at a time would drop another's event while it waited)."
  [ev preds]
  (let [met (fn [got e] (first (filter #(and (not (got %)) ((preds %) e)) (range (count preds)))))]
    (loop [got {}]
      (if (= (count got) (count preds))
        (mapv got (range (count preds)))
        (let [e (await-event ev #(met got %))] (recur (assoc got (met got e) e)))))))

(defn- started
  "Agent id's :started event for an eval of form: it began, so a kill, an interrupt or a pause
  arrives on a running eval; the form tells it from an earlier one's."
  [id form] #(and (= :started (:type %)) (= id (:agent %)) (= form (:form %))))

(defn- ping
  "Flow's ping of proc pid: {:status :count :state}, or {:blocked pid :ping-ms ms} when it took
  no control message within :seon.config.check/ping-ms."
  [sys pid]
  (let [ms (seon.config/value :seon.config.check/ping-ms)]
    (if-let [m (flow/ping-proc (:graph sys) pid :timeout-ms ms)]
      {:status (::flow/status m) :count (::flow/count m) :state (::flow/state m)}
      {:blocked pid :ping-ms ms})))

(defn- await-agents
  "ids, once each has sent an event whose :type is in types on ev (await-event); an agent
  that ends first (:dead, :killed, :ended) is an error naming it."
  [ev ids types]
  (loop [left (set ids)]
    (if (empty? left)
      ids
      (let [e (await-event ev #(left (:agent %)))]
        (cond (types (:type e)) (recur (disj left (:agent e)))
              (c/gone-statuses (:type e)) (throw (ex-info (str "agent " (:agent e) " " (name (:type e)) " first") e))
              :else (recur left))))))

(defn- ready!
  "What f starts (spawn! or clone!: f answers an id, a clone's {:id}, or ids), once each agent
  is ready: its :ready event, on a tap of root's events taken before f runs, so none is
  missed. A refused start is an error naming its answer (no agent comes to wait for)."
  [sys f]
  (let [ev (c/events sys)
        r (f)]
    (when-let [e (first (filter contract/error? (if (sequential? r) r [r])))]
      (a/untap (:events-mult sys) ev) (throw (ex-info (str "refused: " (:seon.error/message e)) e)))
    (await-agents ev (cond (keyword? r) [r] (map? r) [(:id r)] :else r) #{:ready})
    (a/untap (:events-mult sys) ev)
    r))

(defn- spawn-ready!
  "Agents spawned for tasks (a task string, or spawn! opts), once each is ready (ready!)."
  [sys & tasks]
  (ready! sys #(mapv (fn [t] (c/spawn! sys (if (map? t) t {:task t}))) tasks)))

(defn- await-ref
  "true once (pred @r) holds: a watch on reference r runs pred at each change (seon.root/await-db's
  mechanism, on any reference). No deadline, no polling."
  [r pred]
  (let [p (promise) k (gensym "await-")]
    (add-watch r k (fn [_ _ _ v] (when (pred v) (deliver p true))))
    (when (pred @r) (deliver p true))
    @p
    (remove-watch r k)
    true))

(defn- merge-in!
  "Agent id's branch into main as a merge task's is: the writer's octopus merge of one
  member (what seon.reconcile's :octopus-merge asks), answered as that member's result. The
  agent commits its branch first (seon.agent/commit!; it commits when idle, which a request
  straight to the writer does not wait for), as a run's end is committed before its merge."
  [sys id]
  (let [_ @(c/submit! sys id "(seon.agent/commit!)")
        r @(c/call! sys {:op :write :req {:op :octopus-merge :branches [(get-in (c/state sys) [:agents id :branch])] :data-attrs :all}})]
    (if (contract/error? r) r (first (:results r)))))

(defn- main-q [sys query & inputs] (apply d/q query @(:conn sys) inputs))

(defn- short-items "A response as a report shows it: its text, its error, its ms." [r]
  (if (contract/error? r) r (select-keys r [:text :ex :ms])))

(defn- printed
  "The value response r showed, printed as Clojure prints it (its :text read back,
  seon.test-store/value-of), or nil for an error or a value that does not read back."
  [r] (when (and (map? r) (contains? r :text)) (some-> (ts/value-of r) pr-str)))

(defn- p1 "The response of forms evaluated in agent id, as [printed recorded]." [sys id forms]
  ((juxt printed :recorded) @(c/submit! sys id forms)))

;; ---- the modes --------------------------------------------------------------------------

(defn smoke []
  (run-check "SMOKE" {:datoms 2000}
   (fn [sys]
    (let [_ (out! "booted: " (:main (c/state sys {:datoms true})))
        tasks {:a1 "square numbers" :a2 "count the program's vars" :a3 "write a note about app.m0/f1"}
        ids (apply spawn-ready! sys (vals (sort tasks)))
        _ (out! "spawned " ids ", ready")
        ;; each agent works in a program namespace of its own: scratch (agent.*) never
        ;; merges, so its tests never judge
        programs {(ids 0) "(ns app.sq (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))
                           (deftest sq-test (is (= 144 (sq 12))))
                           (transact! [{:note/text \"sq is defined\" :note/agent :a1}])
                           (sq 12)"
                  (ids 1) "(ns app.count (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn n-vars {:malli/schema [:=> [:cat] :int]} [] (count (q '[:find ?v :where [?v :var/name]])))
                           (deftest n-vars-test (is (< 360 (n-vars))) (is (some #{'app.count/n-vars} (q '[:find [?v ...] :where [_ :var/name ?v]]))))
                           (transact! [{:note/text (str \"the program has \" (n-vars) \" vars\") :note/agent :a2}])
                           (n-vars)"
                  (ids 2) "(ns app.docs (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn doc-of {:malli/schema [:=> [:cat :symbol] [:maybe :string]]} [v] (q '[:find ?d . :in $ ?v :where [?e :var/name ?v] [?e :var/doc ?d]] v))
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
        _ (out! (ids 2) " after tests, its branch still says: " (printed (deref (c/submit! sys (ids 2) "(doc-of 'app.m0/f1)"))))
        _ (out! (ids 2) " branch diff before its merge: " @(c/branch-diff sys (ids 2)) "; stats now: " @(c/call! sys {:op :stats :id (ids 2)}))
        m1 (merge-in! sys (ids 0))
        ;; git: a branch merges main first (main took a1's work), then it merges into main
        mm3 @(c/merge-main! sys (ids 2))
        m3 (merge-in! sys (ids 2))
        _ (out! "merge main into " (ids 2) " first: " (select-keys mm3 [:ok :applied :conflicts]))
        _ (out! "merge " (ids 0) ": " m1 "\nmerge " (ids 2) ": " m3)
        _ (out! "main notes: " (main-q sys '[:find ?a ?t :where [?n :note/agent ?a] [?n :note/text ?t]]))
        _ (out! "main f1 doc: " (main-q sys '[:find ?d . :where [?e :var/name app.m0/f1] [?e :var/doc ?d]])
                "; note about: " (main-q sys '[:find ?v . :where [?n :note/about ?e] [?e :var/name ?v]]))
        ;; a second merge of the same branch: the note a3 created is the same entity on main
        _ @(c/submit! sys (ids 2) "(transact! [[:db/add (q '[:find ?n . :where [?n :note/agent :a3]]) :note/text \"f1 adds one (edited)\"]])")
        m3b (merge-in! sys (ids 2))
        _ (out! "merge " (ids 2) " again: " m3b " -> main a3 notes: "
                (main-q sys '[:find [?t ...] :where [?n :note/agent :a3] [?n :note/text ?t]]))
        ;; a conflict: a2 changes f1's doc on its branch too, after main already took a3's change
        _ @(c/submit! sys (ids 1) "(transact! [{:var/name 'app.m0/f1 :var/doc \"a2's doc\"}])")
        mm2 @(c/merge-main! sys (ids 1))
        _ (out! "merge main into " (ids 1) " (both changed f1's doc): " (select-keys mm2 [:ok :applied :conflicts]))
        m2 (merge-in! sys (ids 1))
        _ (out! "merge " (ids 1) " with its conflict unmerged: " (dissoc m2 :tx))
        ;; clone: a3 forks itself, live values and all, onto a branch of its branch
        _ @(c/submit! sys (ids 2) "(def memo (atom {:seen 1}))")
        t-clone (profile/now-ns)
        cl (ready! sys #(deref (c/clone! sys (ids 2) {:task "a3's clone"})))
        _ (out! "clone " (ids 2) " -> " cl " in " (lib/r2 (profile/ms-since t-clone)) " ms; the clone says: "
                (short-items @(c/submit! sys (:id cl) "(my.agent/whoami) (swap! memo update :seen inc) (doc-of 'app.m0/f1)"))
                "; the parent still has " (printed (deref (c/submit! sys (ids 2) "@memo"))))
        ;; merge main forward into a1's branch (git merge main): it now sees a3's notes too
        mm @(c/merge-main! sys (ids 0))
        _ (out! "merge main into " (ids 0) ": " mm " -> it sees "
                (printed (deref (c/submit! sys (ids 0) "(sort (q '[:find [?t ...] :where [?n :note/text ?t]]))"))))
        ;; robustness: a1 runs forever; kill it; the others keep working
        ev (c/events sys)
        hung (c/submit! sys (ids 0) "(loop [] (recur))")
        ;; the kill arrives mid-eval: the agent said it began
        _ (await-event ev (started (ids 0) "(loop [] (recur))"))
        _ (out! (ids 0) " running: " (get-in (c/state sys) [:agents (ids 0) :running]))
        _ (c/kill! sys (ids 0))
        _ (out! "killed " (ids 0) " mid-eval; its pending reply: " (short-items @hung))
        _ (out! (ids 2) " still answers: " (short-items @(c/submit! sys (ids 2) "(+ 40 2)")))
        [a4] (spawn-ready! sys "after the kill")
        _ (out! a4 " spawned after the kill: " (short-items @(c/submit! sys a4 "(count (q '[:find ?n :where [?n :note/text]]))")))
        st (c/state sys)]
      {:report {:state (-> st (update :agents (fn [ags] (into (sorted-map) (for [[k v] ags] [k (-> v (dissoc :evals) (update :tests #(some-> % (dissoc :reports))))])))))}
       ;; each agent's tests ran and passed; a1 and a3 merged; a2 is refused (its conflict unmerged)
       :checks {:every-agent's-tests-pass (every? (fn [id] (let [t (tests id)] (and (not (contract/error? t)) (pos? (:pass t)) (zero? (+ (:fail t) (:error t)))))) ids)
                :a1-merged (:merged? m1)
                :a3-merged-twice (and (:merged? m3) (:merged? m3b))
                :a2-refused (not (:merged? m2))}}))))

;; ---- contracts (session G1, proto/core 9f5100a proto.smoke/contracts) --------------------

(seon.contract/defn probe-callee
  "A contracted function, for the direct-link check below."
  {:malli/schema [:=> [:cat :int] :int]}
  [x] (inc x))
(defn probe-caller "An uncontracted caller compiled against probe-callee." [x] (probe-callee x))

(defn- platform-nss "Every loaded platform namespace (seon.*, my.*), this check driver left out." []
  (sort (remove #{'seon.check 'seon.e2e} (filter #(re-find #"^(seon|my)\." (str %)) (map ns-name (all-ns))))))

;; ---- test inputs: a test reads no input its digest misses (pure-tests 2026-10-23) --------

(def hidden-inputs
  "Reads whose answer no reach digest holds: a clock, an unseeded random, the inherited
  environment, a sleep or a deadline. A test form that references one is named in
  named-test-inputs with why its verdict does not depend on it."
  '#{seon.profile/now-ns seon.profile/ms-since seon.profile/cpu-ns System/currentTimeMillis System/nanoTime
     System/getenv clojure.core/rand clojure.core/rand-int clojure.core/rand-nth clojure.core/shuffle
     Thread/sleep clojure.core.async/timeout})

(def named-test-inputs
  "{[test-namespace var, the read] why its verdict is a function of its digest anyway}."
  '{[seon.db-test/a-commit-costs-what-it-writes seon.profile/now-ns] "printed ms per turn; the verdict is on bytes"
    [seon.db-test/a-commit-costs-what-it-writes seon.profile/ms-since] "printed ms per turn; the verdict is on bytes"
    [seon.model-test/a-streamed-reply-ends-when-the-server-ends-it Thread/sleep] "the stream's pacing is the subject: a deadline can only turn it red (FINDINGS #13)"
    [seon.model-test/a-streamed-reply-ends-when-the-server-ends-it clojure.core/rand-int] "a port: a collision can only turn it red (FINDINGS #14)"
    [seon.problems-test/an-agent-death-is-recorded-with-its-cause seon.profile/now-ns] "a fresh directory's name"
    [seon.procs-test/notice seon.profile/now-ns] "a message's :ns stamp, data the verdict does not read (the one helper the procs and reconcile tests build their notices with)"
    [seon.step-test/timer-threads clojure.core.async/timeout] "the timers' threads are the subject; the verdict counts threads, never time"
    [seon.profile-test/a-threads-allocation-is-its-own Thread/sleep] "a window in which bytes are counted; the verdict is on bytes"
    [seon.profile-test/reading-the-clock-allocates-nothing seon.profile/now-ns] "the clock read is the subject; the verdict is on bytes"
    [seon.profile-test/the-clock-is-the-one-every-process-reads seon.profile/now-ns] "the clock's identity is the subject"
    [test-runner/prove! seon.profile/now-ns] "the runner's printed selection time"
    [test-runner/shard! seon.profile/now-ns] "the runner's printed shard time"})

(defn test-inputs
  "The compiler's facts of the checkout's test/ (each defining form's :form/refs): every read
  of a hidden input (hidden-inputs) is named in named-test-inputs with why its test's verdict
  is a function of its digest anyway, and every named one is still read. A new clock read,
  random, environment read, sleep or deadline in a test is red, naming it. A check step, not
  a deftest: it reads the checkout's files, which an agent's gate does not have (as a deftest
  it went red in an agent, a stray red that blocked a merge)."
  []
  (run-check "TEST-INPUTS" {:datoms 2000}
   (fn [sys]
    ;; the tests' files: this driver lives in test/ too (outside src) but is no test, and its
    ;; waits are a check's measurement, named in docs/clocks.md
    (let [files (into {} (filter #(and (clojure.string/ends-with? (key %) ".clj") (not (clojure.string/ends-with? (key %) "seon/check.clj"))))
                      (:value (seon.fx/read-source {:req {:root (seon.config/checkout) :dirs ["test"]}})))
          ;; test/'s forms analyzed onto main's value (d/db-with: nothing transacted)
          db (let [db (d/db (:conn sys))] (d/db-with db (seon.source/import-tx db files)))
          found (set (d/q '[:find ?v ?r :in $ [?r ...] :where [?f :form/refs ?r] [?f :form/defines ?d] [?d :var/name ?v]] db (vec hidden-inputs)))
          unnamed (vec (sort-by str (remove named-test-inputs found)))
          gone (vec (sort-by str (remove found (keys named-test-inputs))))]
      {:report {:test-files (count files) :reads (count found) :unnamed unnamed :named-not-read gone}
       :checks {:every-hidden-input-named (empty? unnamed) :every-named-one-read (empty? gone)}}))))

(defn contracts
  "In this binary (a release build direct-links and inlines app defs): every function var
  of the platform carries a contract; every contracted var holds its checked function
  from its definition on (or is armed at boot), and that function object (what a
  direct-linked caller holds) refuses a call with a wrong arity as the one error map; a
  plain caller compiled against a contracted function reaches its check."
  []
  (run-check "CONTRACTS" nil
   (fn [_]
    (let [boot (contract/instrument! contract/boot-nss)
        nss (platform-nss)
        vs (for [n nss v (vals (ns-interns n)) :when (contract/contract v)] v)
        at-def (filter #(= :definition (:seon.contract/checked-at (meta %))) vs)
        late (remove #(= :definition (:seon.contract/checked-at (meta %))) vs)
        uncontracted (contract/uncontracted nss)
        bad-arity (fn [v] (let [s (m/deref (contract/schema (contract/contract v)))
                                infos (map m/-function-info (m/-function-schema-arities s))
                                hi (reduce max -1 (map #(or (:max %) -1) infos))
                                lo (reduce min 1000 (map :min infos))]
                            (if (some #(nil? (:max %)) infos) (when (pos? lo) (dec lo)) (inc hi))))
        refuses (fn [v] (if-let [n (bad-arity v)]
                          (try (apply @v (repeat n nil)) :not-refused
                               (catch Throwable e (if (= :seon.instrument/arity-error (:seon.error/type (ex-data e))) :refused :other)))
                          :no-bad-arity))
        results (group-by refuses vs)
        direct (try (probe-caller "x") :not-refused
                    (catch Throwable e (if (= 'seon.check/probe-callee (:seon.error/operation (ex-data e))) :refused :other)))]
      {:report {:namespaces (count nss) :contracted (count vs) :checked-at-definition (count at-def)
                :armed-at-boot (vec (sort (map symbol late))) :uncontracted uncontracted
                :wrong-arity {:refused (count (results :refused)) :not-refused (mapv symbol (results :not-refused))
                              :other (mapv symbol (results :other)) :no-bad-arity (mapv symbol (results :no-bad-arity))}
                :direct-linked-caller direct :boot (dissoc boot :uncontracted)
                :instrument (if contract/on? :on :off)}
       :checks {:direct-linked-caller-refused (= direct :refused) :every-fn-contracted (empty? uncontracted)
                :late-ones-armed (every? contract/armed? late)
                :every-wrong-arity-refused (and (empty? (results :not-refused)) (empty? (results :other)))}}))))

(defn instrument
  "An agent's defn with :malli/schema is armed (a bad call refuses as the error map, the
  response's :ex); one without stays uninstrumented and joins the agent's uncontracted set."
  []
  (run-check "INSTRUMENT" {:datoms 500}
   (fn [sys]
    (let [[a] (spawn-ready! sys "instrument")
          bad @(c/submit! sys a "(defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)) (sq \"x\")")
          ;; the agent's uncontracted set, as the done rule reads it (seon.repl/instrument)
          u1 (printed @(c/submit! sys a "(do (defn plain [x] x) (count (:uncontracted @seon.agent/state)))"))
          u2 (printed @(c/submit! sys a "(do (defn plain2 [x] x) (count (:uncontracted @seon.agent/state)))"))]
      {:report {:refused (short-items bad) :uncontracted [u1 u2]}
       :checks {:armed-and-refused (re-find #"invalid-input" (str (get-in bad [:ex :seon.error/message])))
                :uncontracted-grows (= ["1" "2"] [u1 u2])}}))))

(defn record
  "Each eval request is recorded in one transaction on the agent's branch (its one eval on
  its agent entity, its defs in program namespaces), and stops at its first error; the agent's
  next request sees its own recording."
  []
  (run-check "RECORD" {:datoms 500}
   (fn [sys]
    (let [[a] (spawn-ready! sys "record")
          r1 @(c/submit! sys a "(+ 1 2) (in-ns 'app.m0) (defn f3 [x] (* 3 x)) (/ 1 0)")
          n @(c/submit! sys a "(count (my.db/q '[:find ?e :where [?e :seon.eval/agent ?a] [?a :seon.agent/id :a1]]))")
          src @(c/submit! sys a "(my.db/q '[:find ?s . :where [?e :var/name app.m0/f3] [?e :var/form ?f] [?f :form/src ?s]])")]
      {:report {:first (select-keys r1 [:text :record :recorded]) :second [(printed n) (printed src)]}
       :checks {:one-response-its-error (some? (:ex r1))
                ;; one eval per request: r1's four forms are one record; n's own is recorded
                ;; after it answers (seon.repl/record!), so n counts r1's alone
                :its-evals-recorded (= "1" (printed n))
                :its-defn-recorded (re-find #"f3" (str (printed src)))}}))))

;; ---- problems (session D2): each class provoked, then read back from main ----------------

(def ^:private problem-specs
  "Two tasks the scripted model cannot make green in one turn: p1's proof wants 99 (the
  reply adds 1), and p2's proof is a cost proof whose estimate allows half a call."
  [{:var "p2-input" :ns 'app.par :stub "(defn p2-input [n] [n])"}
   {:name "p1" :ns 'app.par :stub "(defn p1 {:malli/schema [:=> [:cat :int] :int]} [x] 0)"
    :proof "(deftest p1-test (is (= 99 (app.par/p1 10))))"}
   {:name "p2" :ns 'app.par :stub "(defn p2 {:malli/schema [:=> [:cat :int] :int]} [x] 0)"
    :proof (str "(deftest p2-test (seon.cost/proof {:of 'app.par/p2 :input 'app.par/p2-input :n \"x\" :work \"one call\""
                " :growth :constant :fixed {:calls 0.5} :sizes [1 10 100]}))")}])

(defn problems-check
  "Provoke every class of problem through the paths agents and root use (:stall-ms 1000
  for the stall), then read my.root/problems: an eval error and a contract's error, an
  effect's io error, a platform defect in the api, a stall, an agent's death with its exit
  status and stderr, a red proof's assertions and a cost proof's verdict. It reads them
  once main holds each (seon.root/await-db over seon.problems/report)."
  []
  (run-check "PROBLEMS" {:datoms 300 :max-running 2 :stall-ms 1000}
   (fn [sys]
    (let [t0 (System/currentTimeMillis)
        [a b] (spawn-ready! sys "problems" "dies")
        ;; one error each: a request stops at its first
        r1 [@(c/submit! sys a "(/ 1 0)") @(c/submit! sys a "(defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)) (sq \"x\")")
            @(c/submit! sys a "(my.fx/call :file {:path \"/nonexistent-d2/x\" :text \"x\"})")]
        ;; a stall (1,000 ms bound): an open :stalled problem while it sleeps, closed once it answers
        stalled-rows #(get-in (seon.problems/health %) [:classes :stalled :rows])
        r2p (c/submit! sys a "(Thread/sleep 2500) :awake")
        during (seon.root/await-db sys #(seq (stalled-rows %)))
        stall (first (some-> during stalled-rows))
        r2 @r2p
        after (seon.root/await-db sys #(empty? (stalled-rows %)))
        _ (a/>!! (:back sys) {:op :writer :tx-count 1 :batch-ms 1 :n-tx "not a number" :commits 1})
        r3 @(c/submit! sys b "(spit \"/dev/stderr\" \"D2 boom: the agent writes its last words\\n\" :append true) (seon.lib/c-exit 3)")
        _ (doseq [tx (seon.fixtures/program-txs @(:conn sys) problem-specs)] @(c/transact-main! sys tx))
        ds (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 1})
                 (remove #(= :merge (:seon.task/kind %)) (seon.fixtures/decls problem-specs 'problems nil)))
        _ (my.root/create! sys ds)
        _ (my.root/run! sys ["problems/p1" "problems/p2"])
        ;; named: the report is made of what the reconciler's runs write (the judge's red and cost
        ;; rows), so the wait ends when root goes quiet with a class missing (:seon.root/quiet-before-true)
        ;; and the :checks below name which one, never a wait for a row that cannot come
        _ (seon.root/await-db sys "the provoked problems (a red proof, a cost verdict, a platform defect, a death) are on main"
                              #(let [p (seon.problems/report %)]
                                    (and (some (fn [r] (= 'app.proofs/p1-test (:test r))) (get-in p [:classes :red :rows]))
                                         (pos? (get-in p [:classes :cost :n])) (pos? (get-in p [:classes :platform :n]))
                                         (some (fn [r] (= :seon.agent/died (:type r))) (get-in p [:classes :agent :rows])))))
        p (my.root/problems sys)
        rows (fn [k] (get-in p [:classes k :rows]))
        types (fn [k] (set (map :type (rows k))))
        died (first (filter #(= :seon.agent/died (:type %)) (rows :agent)))
        red (first (filter #(= 'app.proofs/p1-test (:test %)) (rows :red)))
        cost-row (first (rows :cost))]
      {:report {:replies (mapv short-items (conj r1 r2 r3))
                :counts (into (sorted-map) (for [[k v] (:classes p)] [k (:n v)]))
                :died (select-keys (:example died) [:exit :stderr :last-op])
                :red (select-keys red [:run :test :assertions :evidence])
                :cost (select-keys cost-row [:run :test :verdict])
                :stalled (select-keys stall [:agent :message])
                :ms (- (System/currentTimeMillis) t0)}
       :checks {:eval (every? (types :eval) [:seon.agent/eval-error :seon.instrument/contract-error])
                :effect (contains? (types :effect) :seon.fx/io-error)
                :platform (contains? (types :platform) :seon.root/proc-defect)
                :stalled (and (= a (:agent stall)) (re-find #"Thread/sleep 2500" (str (:message stall))) (not (contract/error? after)))
                :died (and died (= {:exit 3} (get-in died [:example :exit])) (re-find #"D2 boom" (str (get-in died [:example :stderr]))))
                :red (and red (seq (:assertions red)) (= ['app.proofs/p1-test] (:proofs red)))
                :cost (and cost-row (false? (get-in cost-row [:verdict :ok?])) (seq (:rows cost-row)))}}))))

;; ---- the mg/check gate (v1's survey of ~/src/seon, 2026-10-20) ------------------

(defn mg-check
  "The mg/check gate by the one check bin/test's shards run (seon.proofs/mg-run), in the release binary: no
  [:fn pred] schema without :gen in the registry (seon.proofs/mg-registry, in shard 0), and every
  pure contract (seon.proofs/pure-nss, pure-fns) passes malli.generator/check (100 runs each). Prints the
  counts and the contracts it checked. selected, {fn digest}, limits it to those functions (nil: every
  one), each drawing from its own seed, (seon.proofs/seed fn digest); a selected function that is not
  contracted fails the gate (a renamed one never reads as green). Without a digest the seed is of base,
  bin/check's tree. With shard \"I/K\" (0 <= I < K) it checks only the contracted vars at positions I,
  I+K, ... of the platform's, sorted by symbol, so K shards in K processes check exactly what one run
  does. By var, not namespace: seon.reconcile alone holds 36 of the 82 contracts, so a namespace split
  cannot go below its share. A failure names its seed and the form that reproduces it. See
  docs/checks.md#the-generative-check-is-per-function."
  [& [shard base selected]]
  (run-check "MG-CHECK" nil
   (fn [_]
    (let [[i k] (if shard (mapv #(Long/parseLong %) (clojure.string/split shard #"/")) [0 1])
        contracted (sort-by symbol (for [n (platform-nss) v (vals (ns-interns n)) :when (contract/contract v)] v))
        unknown (vec (sort (remove (set (map symbol contracted)) (keys selected))))
        vs (keep-indexed #(when (= i (mod %1 k)) %2)
                         (cond->> contracted selected (filter #(contains? selected (symbol %)))))
        pure? #(or (seon.proofs/pure-nss (ns-name (:ns (meta %)))) (seon.proofs/pure-fns (symbol %)))
        runs (concat (when (zero? i) [(seon.proofs/mg-run seon.proofs/mg-registry nil)])
                     (for [v vs :when (pure? v)] (seon.proofs/mg-run (str "mg/" (symbol v)) (or (get selected (symbol v)) base))))
        by (group-by :status runs)
        failed (vec (for [r (:failed by)] [(:key r) (:failure r)]))]
      {:report {:shard [i k] :contracts (count vs) :unknown-selected unknown
                :generated-and-checked (count (:ok by)) :failed failed
                :checked (vec (sort (map :key (:ok by)))) :ms (into (sorted-map) (map (juxt :key :ms)) (:ok by))
                :not-generated (count (:skipped by))}
       :checks {:registry-and-contracts-hold (empty? failed) :every-selected-fn-is-contracted (empty? unknown)}}))))

(defn- mg-check-mode
  "mg-check's command line (bin/check): \"I/K [BASE [FILE]]\", a shard of K, BASE the seed's base and FILE the
  selected functions, a line \"<fn> <digest>\" each (bin/prove's out/mg-fns); or \"FN ...\", just those
  functions (bin/check mg-check seon.merge/f), each drawing from the tree's seed."
  [n more]
  (if (and n (re-matches #"\d+/\d+" n))
    (mg-check n (first more)
              (when-let [f (second more)]
                (into {} (for [l (clojure.string/split-lines (slurp f)) :when (seq l)] (let [[s d] (clojure.string/split l #" ")] [(symbol s) d])))))
    (mg-check nil nil (not-empty (into {} (for [s (remove nil? (cons n more))] [(symbol s) nil]))))))

;; ---- robustness ---------------------------------------------------------------------

(defn robust []
  (run-check "ROBUST" {:datoms 2000}
   (fn [sys]
    (let [[a b x] (spawn-ready! sys "exits" "misbehaves" "tests misbehave")
        ev (c/events sys)
        r1 @(c/submit! sys a "(+ 1 1) (System/exit 3) (+ 2 2)")
        _ (await-agents ev [a] #{:dead})
        r2 @(c/submit! sys b "(transact! [{:no/such-attr 1}])")
        r3 [@(c/submit! sys b "(apply str (repeat 100000 \"x\"))") @(c/submit! sys b "(range)")]
        r4 @(c/submit! sys b "(+ 1")
        _ @(c/submit! sys x "(ns app.spin (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest spins (loop [] (recur)))")
        ;; the runner's tick bound, given here as root's run-tests! (the test service) does not take one
        t5 (ts/value-of @(c/submit! sys x "(select-keys (seon.agent/run-tests-forked 100000000 #{} :judge) [:error :reports :ms])"))
        r5 @(c/submit! sys x "(+ 1 1)")
        _ @(c/submit! sys x "(in-ns 'app.spin) (ns-unmap 'app.spin 'spins) (deftest exits (System/exit 7))")
        t6 @(c/run-tests! sys x)
        r6 @(c/submit! sys x "(+ 1 2)")
        r9 [@(c/submit! sys b "(count (vec (range 300000000)))") @(c/submit! sys b "(+ 5 5)")]
        r11 [@(c/submit! sys b "(def kept 7) (loop [] (recur))" {:ticks 100000000}) @(c/submit! sys b "(+ kept 1)")]
        p12 (c/submit! sys b "(reduce + (range)) :next")
        ;; the interrupt arrives on the running eval: the agent said it began
        _ (await-event ev (started b "(reduce + (range)) :next"))
        _ (c/interrupt! sys b)
        r12 @p12
        r12b @(c/submit! sys b ":next")
        r10 @(c/submit! sys x "(println \"hello from\" (:id (my.agent/whoami))) (def busy (future (loop [] (recur))))")
        t10 @(c/run-tests! sys x)
        _ (lib/c-kill (get-in (c/state sys) [:fork-server :pid]) 9)
        _ (await-event ev #(#{:fork-server-up :fork-server-lost} (:type %)))
        r7 @(c/submit! sys b "(+ 3 4)")
        [y] (spawn-ready! sys "after the fork server died")
        r8 [@(c/submit! sys y "(count (q '[:find ?v :where [?v :var/name]]))") @(c/submit! sys y "(loop [] (recur))" {:ticks 100000000})
            @(c/submit! sys y ":after")]]
      {:report {:exit [r1 (select-keys (get-in (c/state sys) [:agents a]) [:status :exit])]
                :refused (short-items r2) :printed-chars (mapv #(count (:text %)) r3) :read-error (short-items r4)
                :spin (select-keys t5 [:error :reports :ms]) :test-exits (select-keys t6 [:error :reports :exit])
                :oom (mapv short-items r9) :interrupt (short-items r12) :ticks (mapv short-items r11)
                :out [(:out r10) t10] :fork-server [(:fork-server (c/state sys)) (short-items r7) (mapv short-items r8)]}
       :checks {"agent calling System/exit mid-request dies alone, named by its exit status"
                (and (= :seon.agent/died (:seon.error/type r1)) (= {:exit 3} (:exit r1))
                     (= :dead (get-in (c/state sys) [:agents a :status])))
                "a refused transaction is the response's :ex" (and (:ex r2) (re-find #"refused" (str (:text r2))))
                "huge and infinite values print within the printer's budget" (let [chars (seon.print.select/estimate-chars (seon.config/value :seon.config.render.agent/token-budget))]
                                                                 (every? #(and (string? (:text %)) (<= (count (:text %)) chars)) r3))
                "a read error is the response's :ex" (= :read (get-in r4 [:ex :phase]))
                "a test that never ends stops at its tick budget, naming the test; the agent lives"
                (and (= 1 (:error t5)) (= "2" (printed r5))
                     (= [:seon.test/ticks-spent "#'app.spin/spins"] ((juxt :seon.error/type :var) (first (:reports t5)))))
                "a test that kills its process is an error; the agent lives" (and (= 1 (:error t6)) (= "3" (printed r6)))
                "an agent past its heap bound gets OutOfMemoryError as its response's :ex and lives" (and (:ex (first r9)) (= "10" (printed (second r9))))
                ;; nREPL's model (owner's choice): an interrupt stops the request; the agent goes on
                "root's interrupt! stops the running request; the agent's next request runs"
                (and (clojure.string/starts-with? (str (get-in r12 [:ex :seon.error/message])) "interrupted by root") (not (contains? r12 :value)) (= ":next" (printed r12b)))
                "a form past its tick budget is stopped, a typed error naming its ticks; the agent keeps its state and goes on"
                (and (= :seon.repl/ticks-spent (get-in (first r11) [:ex :seon.error/type]))
                     (<= 100000000 (get-in (first r11) [:ex :ticks])) (= "8" (printed (second r11))))
                "printed output comes back as :out; a busy thread blocks the test fork instead of breaking it"
                (and (= "hello from :a3\n" (:seon.print/code (:out r10))) (= :seon.test/fork-refused (:seon.error/type t10)) (re-find #"Chez threads active" (:seon.error/message t10)))
                "the fork server dies: agents keep working, a new server forks the next agent (whose tick budget works)"
                (and (= "7" (printed r7)) (= "360" (printed (first r8)))
                     (= :seon.repl/ticks-spent (get-in (second r8) [:ex :seon.error/type])) (= ":after" (printed (nth r8 2))))}}))))

;; ---- root's threads: a declared number (session THREADS) --------------------------------

(defn- fiber-carriers
  "The fiber carrier threads Jolt runs (or will run, before the first fiber): its pool size."
  [] (jolt.scheme/eval-string "(if jolt-fiber-carriers (vector-length jolt-fiber-carriers) (jolt-fiber-carrier-count))"))

(defn- thread-roster
  "This process's Jolt threads by name, a numbered name folded to its stem
  (\"jolt-carrier-3\" -> \"jolt-carrier\"): {stem count}. A thread nobody named shows as
  \"Thread\". The GC's sweepers are C threads, not here: (- (nthreads) this sum)."
  []
  (frequencies (map #(clojure.string/replace (.getName ^Thread %) #"-\d+$" "") (keys (Thread/getAllStackTraces)))))

(defn- declared-threads
  "The threads root runs, as {:roster {name-stem n} :gc-sweepers n} (stems as thread-roster
  folds them). Without a system, the process's own: main, Jolt's shutdown watcher, timer, io
  poller, all-asleep detector (started with the carriers when boot's main carrier returns) and
  fiber carriers, the GC sweepers fixed at boot, and :native, the C thread that ends it with its
  starter (seon.lib/end-with!). The timer starts at the
  first timeout and the io poller at the first socket wait (an agent's), so a root has both
  once its first agent runs. With a running sys, also one thread per :mixed proc of its
  declaration (Flow runs :io procs, agent procs included, on the carriers), its two edge
  readers (Flow's error channel, the fork server's socket) and, while the web proc listens,
  the web server's acceptor and sweeper (ring-chez-adapter's, each a future of Jolt's agent
  pool; task seon.web/web-server-threads-are-joined-at-stop). The embedding model's threads are the
  helper's process's, none root's (docs/vector.md#the-model)."
  ([] {:roster {"main" 1 "jolt-shutdown" 1 "jolt-timer" 1 "jolt-io-poller" 1 "jolt-asleep" 1 "jolt-carrier" (fiber-carriers)}
       :gc-sweepers (seon.config/value :seon.config.root/gc-sweepers)
       ;; the C thread that ends this process with its starter (seon.lib/end-with!), outside Jolt's roster
       :native (if (pos? (lib/end-watched)) 1 0)})
  ([sys]
   (let [pm (:procs @(get-in sys [:bindings :system]))
         procs (vals pm)
         mixed (count (filter #(= :mixed (or (:workload (clojure.core.async.flow.spi/describe (:proc %))) :mixed)) procs))
         web? (true? (get-in (c/state sys) [:web :listening]))]
     (update (declared-threads) :roster merge
             (cond-> {"async-mixed" mixed "seon-fs-events" 1}
               web? (assoc "clojure-agent-pool" 2))))))

(defn- threads
  "The threads this process runs now, measured as declared-threads declares them, and :os,
  the OS's count (seon.lib/nthreads): the roster's sum plus the sweepers when nothing else runs."
  []
  {:roster (thread-roster) :gc-sweepers (lib/gc-sweepers! -1) :os (lib/nthreads)})

(defn thread-diff
  "Where measured threads (threads) differ from declared ones
  (declared-threads): {} when they are exactly what is declared, else each
  differing name with [declared measured], :gc-sweepers likewise, and :os when the OS
  counts threads beyond the roster, the sweepers and the declared :native one."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [decl got]
  (let [names (into (set (keys (:roster decl))) (keys (:roster got)))
        roster (into {} (for [n names :let [d (get-in decl [:roster n] 0) g (get-in got [:roster n] 0)] :when (not= d g)] [n [d g]]))
        expect-os (+ (reduce + 0 (vals (:roster got))) (:gc-sweepers got) (:native decl 0))]
    (cond-> roster
      (not= (:gc-sweepers decl) (:gc-sweepers got)) (assoc :gc-sweepers [(:gc-sweepers decl) (:gc-sweepers got)])
      (not= expect-os (:os got)) (assoc :os [expect-os (:os got)]))))

;; ---- scale ------------------------------------------------------------------------

(defn scale [n]
  (run-check "SCALE" {:datoms 2000}
   (fn [sys]
    (let [;; with embedding on, main's texts embedded first (seon.root/await-db: nothing due), so
          ;; the threads counted are the open model's steady ones, not its load's or an encode's
          _ (when (pos? (seon.config/value :seon.config.vector/texts))
              (seon.root/await-db sys #(empty? (seon.vector/due % (seon.vector/model-id (seon.config/value :seon.config.vector/model))))))
          mem0 (lib/sysmem)
        th-boot (thread-diff (declared-threads sys) (threads))
        t0 (profile/now-ns)
        ids (ready! sys #(vec (pmap (fn [i] (c/spawn! sys {:task (str "scale " i)})) (range n))))
        t-ready (profile/ms-since t0)
        th-ready (thread-diff (declared-threads sys) (threads))
        _ (out! "ready in " (lib/r2 t-ready) " ms: " (frequencies (map #(get-in (c/state sys) [:agents % :status]) ids)))
        work (fn [id]
               (let [k (subs (name id) 1)
                     t1 (profile/now-ns)
                     r @(c/submit! sys id (str "(ns app.s" k " (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* x " k "))
                                                (deftest f-test (is (= (* 2 " k ") (f 2))))
                                                (transact! [{:note/text \"hi from " k "\" :note/agent " id "}])
                                                (f 21)"))
                     t2 (profile/now-ns)
                     tr @(c/run-tests! sys id)
                     t3 (profile/now-ns)]
                 (cond-> {:id id :ok (and (map? r) (not (:ex r)) (= 1 (:pass tr)) (zero? (+ (:fail tr) (:error tr))))
                          :eval-ms (/ (- t2 t1) 1e6) :tests-ms (/ (- t3 t2) 1e6)}
                   ;; a failed agent names itself: its eval's answer and its test run's counts
                   (not (and (map? r) (not (:ex r)) (= 1 (:pass tr)) (zero? (+ (:fail tr) (:error tr)))))
                   (assoc :eval r :tests (select-keys tr [:pass :fail :error :ex])))))
        results (vec (pmap work ids))
        t-done (profile/ms-since t0)
        _ (doseq [r results :when (not (:ok r))] (out! "NOT OK: " (pr-str r)))
        th-work (thread-diff (declared-threads sys) (threads))
        st (c/state sys)
        mem1 (lib/sysmem)
        agent-mb (keep #(get-in st [:agents % :mem-mb]) ids)
        merged (mapv (fn [id] @(c/merge-main! sys id) (merge-in! sys id)) (take 10 ids)) ; as a merge task does: main first
        notes (count (main-q sys '[:find ?n :where [?n :note/text]]))
        fs @(c/call! sys {:op :fs :req {:op :stats}})
        ;; kill! marks :killed at once; the :killed event says root has retired the process
        ev (c/events sys)
        _ (doseq [id ids] (c/kill! sys id))
        _ (await-agents ev ids #{:killed})
        mem2 (do (Thread/sleep (seon.config/value :seon.config.check/pages-ms)) (lib/sysmem))
        th-end (thread-diff (declared-threads sys) (threads))
        ;; the target machine (a 16 GB M1, owner 2026-10-22): root, the fork server and the default
        ;; number of agents at once fit :seon.config.machine/memory-mb, each agent charged what the
        ;; machine's free plus inactive memory dropped per agent from before the agents started to their
        ;; peak (its own written pages, its page tables, the kernel's share): never the sum of their
        ;; footprints, which charges every inherited page an agent only reads (docs/cost.md#how-we-measure)
        footprint-sum (reduce + agent-mb)
        per-agent (/ (- (:available-mb mem0) (:available-mb mem1)) n)
        need (+ (lib/real-mb) (:mem-mb fs) (* (seon.config/value :seon.config.agent/max-running) per-agent))
        budget (seon.config/value :seon.config.machine/memory-mb)]
      {:report {:agents n :all-ok (count (filter :ok results))
                :spawn->all-ready-ms (lib/r2 t-ready) :end-to-end-ms (lib/r2 t-done)
                :start-ms (lib/summ (keep #(get-in st [:agents % :forked-ms]) ids))
                :server-fork-ms-p50 (get-in st [:fork-server :fork-ms-p50])
                :eval+transact-ms (lib/summ (map :eval-ms results)) :tests-ms (lib/summ (map :tests-ms results))
                :agent-real-mb (lib/summ agent-mb) :agent-real-mb-sum (lib/r2 footprint-sum)
                :system-available-drop-mb-per-agent (lib/r2 per-agent)
                :system-available-returned-mb-per-agent (lib/r2 (/ (- (:available-mb mem2) (:available-mb mem1)) n))
                :footprint-need-mb (lib/r2 (+ (lib/real-mb) (:mem-mb fs) (* (seon.config/value :seon.config.agent/max-running) (/ footprint-sum n))))
                :control-real-mb (lib/r2 (lib/real-mb)) :fork-server (select-keys fs [:mem-mb :os-threads :active-threads :mismatch :datoms])
                :writer (:writer st) :merged (count (filter :merged? merged)) :main-notes notes
                :all-ended (every? #(get-in (c/state sys) [:agents % :ended-ms]) ids)
                :gc (lib/gc-stats) :gc-sweepers (lib/gc-sweepers! -1)
                :memory-at-max-running {:agents (seon.config/value :seon.config.agent/max-running) :need-mb (lib/r2 need) :budget-mb budget}
                :root-threads {:declared (declared-threads sys) :os (lib/nthreads)
                               :diff {:boot th-boot :all-ready th-ready :after-work th-work :after-kill th-end}}}
       ;; root's threads exactly as declared once agents run (at boot Jolt's io poller has not
       ;; started yet: it starts at the first socket wait, an agent's). The model's threads (Metal's
       ;; GCD workers) are the embedding helper's, not root's, so the OS count is judged at every sample
       :checks {:every-eval-and-test-passed (every? :ok results) :ten-merges-went-in (every? :merged? merged)
                :root-threads-as-declared (every? empty? [th-ready th-work th-end])
                :fits-the-machine (<= need budget)}}))))

;; ---- scale-work: agents that work --------------------------------------------------

(defn- ps-tree
  "{pid [ppid cpu-s]} of every process on the machine (ps): its parent and the CPU seconds it has used."
  []
  (into {} (for [l (clojure.string/split-lines (:out (clojure.java.shell/sh "ps" "-axo" "pid=,ppid=,time=")))
                 :let [[p pp t] (re-seq #"[\d:.]+" l)] :when t]
             [(parse-long p) [(parse-long pp) (reduce (fn [acc x] (+ (* 60 acc) (parse-double x))) 0 (clojure.string/split t #":"))]])))

(defn- footprints
  "{pid {:fp :peak}} in bytes for pids, as macOS's footprint tool reads the kernel's ledger
  (phys_footprint, and the peak it has been): the one reading of a process that is not this one."
  [pids]
  (if (empty? pids)
    {}
    (loop [ls (clojure.string/split-lines (:out (apply clojure.java.shell/sh "footprint" "--noCategories" "-f" "bytes"
                                                       (mapcat #(vector "-p" (str %)) pids))))
           cur nil acc {}]
      (if (empty? ls)
        acc
        (let [l (first ls)
              [_ p f] (re-find #"\[(\d+)\]: 64-bit\s+Footprint: (\d+) B" l)
              [_ pk] (re-find #"phys_footprint_peak: (\d+) B" l)]
          (cond p (recur (rest ls) (parse-long p) (assoc acc (parse-long p) {:fp (parse-long f)}))
                pk (recur (rest ls) cur (assoc-in acc [cur :peak] (parse-long pk)))
                :else (recur (rest ls) cur acc)))))))

(defn- categories
  "Where pid's dirty memory is, by VM category: [[category dirty-mb]] largest first, of those at
  least 5 MB (the rest, summed, last as :others)."
  [pid]
  (let [rows (for [l (clojure.string/split-lines (:out (clojure.java.shell/sh "footprint" "-f" "bytes" "-p" (str pid))))
                   :let [[_ d _ _ _ c] (re-find #"^\s*(\d+) B\s+(\d+) B\s+(\d+) B\s+(\d+)\s+(.+?)\s*$" l)]
                   :when c]
               [c (/ (parse-long d) 1048576.0)])
        big (filter #(>= (second %) 5) rows)]
    (vec (concat (map (fn [[c d]] [c (lib/r2 d)]) (sort-by (comp - second) big))
                 [[:others (lib/r2 (reduce + 0 (map second (remove #(>= (second %) 5) rows))))]]))))

(defn- anon-mb
  "The machine's anonymous memory in MB (vm_stat's anonymous pages): what processes hold privately, with the
  file cache (the store's maps, the binary's pages) that free plus inactive counts left out."
  []
  (let [o (:out (clojure.java.shell/sh "vm_stat"))
        [_ ps] (re-find #"page size of (\d+) bytes" o) [_ n] (re-find #"Anonymous pages:\s+(\d+)" o)]
    (lib/r2 (/ (* (parse-long ps) (parse-long n)) 1048576.0))))

(defn- faults
  "{pid [cow faults]} of every process, cumulative since it began (top): its copy-on-write faults and all its
  page faults. A forked process's private copies are at least cow pages and at most faults pages (a read fault
  on an inherited page maps it shared and copies nothing), a bracket of what it added to the machine's memory
  that no other process on the machine moves."
  []
  (into {} (for [l (clojure.string/split-lines (:out (clojure.java.shell/sh "top" "-l" "1" "-n" "100000" "-stats" "pid,cow,faults")))
                 :let [[_ p c f] (re-find #"^\s*(\d+)\s+(\d+)\s+(\d+)\s*$" l)] :when f]
             [(parse-long p) [(parse-long c) (parse-long f)]])))

(defn- sample
  "One reading, as a map: the machine's available MB and the footprints (MB) of root (this
  process), root's fork server (fs, a pid), its direct children (the agents, :agents, of the pids
  agents, and the standby) and theirs (:children: the agents' test children), each by pid;
  :cpu the CPU seconds of each of them; :ms since t0 and :cost-ms, what the reading took."
  [fs agents phase t0]
  (let [t (profile/now-ns) tree (ps-tree) kids (fn [p] (map key (filter #(= p (first (val %))) tree)))
        d1 (kids fs) d2 (mapcat kids (filter agents d1))
        me (lib/c-getpid) ps (concat [me fs] d1 d2)
        fp (footprints ps)
        mb (fn [p] (lib/r2 (/ (get-in fp [p :fp] 0) 1048576.0)))]
    {:ms (profile/ms-since t0) :phase @phase :avail-mb (:available-mb (lib/sysmem))
     :root (mb me) :fork-server (mb fs)
     :agents (into {} (for [p d1 :when (agents p)] [p (mb p)]))
     :standby (reduce + 0 (for [p d1 :when (not (agents p))] (mb p)))
     :children (into {} (for [p d2] [p (mb p)]))
     :cpu (into {} (for [p ps] [p (second (tree p))]))
     :peaks (into {} (for [[p v] fp] [p (lib/r2 (/ (:peak v) 1048576.0))]))
     :cost-ms (lib/r2 (/ (- (profile/now-ns) t) 1e6))}))

(defn- phase-row
  "What the samples taken in one phase read: the lowest available memory, the highest footprint
  of root, the fork server and the agents together, of the test children together and of one,
  and how many test children ran at once."
  [ss]
  (let [sum #(reduce + 0 (vals %))]
    {:samples (count ss) :avail-min-mb (lib/r2 (apply min (map :avail-mb ss)))
     :root-max (apply max (map :root ss)) :fork-server-max (apply max (map :fork-server ss))
     :agents-sum-max (lib/r2 (apply max (map (comp sum :agents) ss)))
     :children-at-once-max (apply max (map (comp count :children) ss))
     :children-sum-max (lib/r2 (apply max (map (comp sum :children) ss)))
     :child-max (apply max 0 (mapcat (comp vals :children) ss))
     :footprint-sum-max (lib/r2 (apply max (map #(+ (:root %) (:fork-server %) (:standby %) (sum (:agents %)) (sum (:children %))) ss)))}))

(defn scale-work
  "n agents (default 10; with search? false, the third eval, my.agent/tests-reaching, is left out:
  what it alone costs an agent) that work, where scale's only evaluate and test one new test each: every
  agent does five evals (a namespace with a function and its test, a read of main's vars, a
  search of what reaches a function, five transactions, and an edit of seon.error/dirty?, a
  function some 60 tests reach, each agent's edit its own) and then proves (c/run-tests!, the judge's run: the
  tests its edit reaches, dealt across test children, none reused since each digest is its own).
  Evals run in one pass, proves in a second at once, so each is a phase of its own. A sampler
  reads the machine's available memory and every process under root's fork server every
  :seon.config.check/sample-ms (sample); the cost is the machine's drop (as scale charges it)
  from before the agents to its lowest reading, plus root's and the fork server's footprints
  before it, against :seon.config.machine/memory-mb (docs/machine.md#ten-agents-at-once)."
  [n search?]
  (run-check "SCALE-WORK" {:datoms 0 :source {:root (seon.config/checkout) :dirs (seon.config/value :seon.config.source/dirs)}}
   (fn [sys]
    (let [_ (when (pos? (seon.config/value :seon.config.vector/texts))
              (seon.root/await-db sys #(empty? (seon.vector/due % (seon.vector/model-id (seon.config/value :seon.config.vector/model))))))
          t0 (profile/now-ns)
          phase (atom :ready) samples (atom []) stop (atom false)
          ids (ready! sys #(vec (pmap (fn [i] (c/spawn! sys {:task (str "scale-work " i)})) (range n))))
          ready-ms (lib/r2 (profile/ms-since t0))
          ;; the baseline: every agent ready (its standby parent forked it, so it is charged), none yet working
          st0 (c/state sys)
          fs-pid (get-in st0 [:fork-server :pid]) agent-pids (set (keep #(get-in st0 [:agents % :pid]) ids))
          base (sample fs-pid agent-pids phase t0)
          mem0 (assoc (lib/sysmem) :anon-mb (anon-mb))
          faults0 (faults)
          ;; two readers: the machine's available memory alone is one native call (the drop that is
          ;; judged), the processes' footprints a ps and a footprint (the attribution)
          avails (atom [])
          avail-reader (future (loop [] (swap! avails conj (let [tree (ps-tree)] {:ms (profile/ms-since t0) :phase @phase :avail-mb (:available-mb (lib/sysmem)) :anon-mb (anon-mb)
                                                                  :children-n (count (filter #(agent-pids (first (val %))) tree))}))
                                 (when-not @stop (Thread/sleep (seon.config/value :seon.config.check/sample-ms)) (recur))))
          sampler (future (loop [] (swap! samples conj (sample fs-pid agent-pids phase t0))
                            (when-not @stop (Thread/sleep (seon.config/value :seon.config.check/sample-ms)) (recur))))
          probe "{:fp (seon.lib/real-mb) :gen-mb (/ (seon.lib/generation-bytes) 1048576.0) :heap-mb (/ (seon.lib/bytes-in-heap) 1048576.0)}"
          probed (fn [id] (let [r @(c/submit! sys id probe)] (or (ts/value-of r) r)))
          p0 (into {} (pmap (fn [id] [id (probed id)]) ids))
          timed (fn [id form] (let [t (profile/now-ns) r @(c/submit! sys id form)] [r (/ (- (profile/now-ns) t) 1e6)]))
          _ (reset! phase :eval)
          evals (vec (pmap (fn [id]
                             (let [k (subs (name id) 1)
                                   forms [(str "(ns app.w" k " (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* x " k "))
                                                (deftest f-test (is (= (* 2 " k ") (f 2)))) (f 21)")
                                        "(count (my.db/q '[:find ?e :where [?e :var/name]]))"
                                        (if search? "(count (my.agent/tests-reaching 'seon.error/dirty?))" "(+ 1 1)")
                                        (str "(my.db/transact! (vec (for [i (range 5)] {:note/text (str \"note \" i \" from " k "\") :note/agent " id "})))")
                                        (str "(in-ns 'seon.error) (defn dirty? \"Has f a signature to write (one not yet written)? Edited by " k ".\" {:malli/schema [:=> [:cat :map] :boolean]} [f] (boolean (some :dirty? (vals f))))")]
                                   rp (mapv (fn [f] (let [r (timed id f)] [r (probed id)])) forms)
                                   rs (mapv first rp)]
                               {:id id :probes (mapv second rp) :ok (every? #(and (map? (first %)) (not (:ex (first %)))) rs) :evals-ms (mapv (comp lib/r2 second) rs)
                                :reaching (when search? (ts/value-of (first (nth rs 2))))
                                :failed (vec (for [[r _] rs :when (or (not (map? r)) (:ex r))] (select-keys r [:ex :text])))}))
                           ids))
          p1 (into {} (pmap (fn [id] [id (probed id)]) ids))
          mem-evals (assoc (lib/sysmem) :anon-mb (anon-mb))
          _ (reset! phase :prove)
          t-prove (profile/now-ns)
          proves (vec (pmap (fn [id] (let [[r ms] (let [t (profile/now-ns) r @(c/run-tests! sys id)] [r (/ (- (profile/now-ns) t) 1e6)])]
                                       {:id id :tests-ms (lib/r2 ms) :ran (:ran r) :pass (:pass r) :fail (:fail r) :error (:error r) :reused (:reused r)
                                        :deferred (count (:deferred r)) :ex (:ex r)}))
                            ids))
          prove-ms (profile/ms-since t-prove)
          _ (reset! phase :after)
          p2 (into {} (pmap (fn [id] [id (probed id)]) ids))
          mem-after (assoc (lib/sysmem) :anon-mb (anon-mb))
          _ (reset! stop true)
          _ @sampler
          _ @avail-reader
          st (c/state sys)
          fin (sample fs-pid agent-pids (atom :end) t0)
          faults1 (faults)
          page-mb (/ 16384 1048576.0)
          added (fn [i p] (* page-mb (- (get-in faults1 [p i] 0) (get-in faults0 [p i] 0))))
          all-samples (conj @samples fin)
          by-phase (into (sorted-map) (for [[p ss] (group-by :phase all-samples)] [p (phase-row ss)]))
          pid-peaks (apply merge-with max (map :peaks all-samples))
          children-pids (set (mapcat (comp keys :children) all-samples))
          root0 (:root base) fs0 (:fork-server base)
          drop-of (fn [m] (lib/r2 (- (:available-mb mem0) (:available-mb m))))
          drop-peak (lib/r2 (- (:available-mb mem0) (apply min (map :avail-mb (concat all-samples @avails)))))
          anon-peak (lib/r2 (- (apply max (map :anon-mb @avails)) (:anon-mb mem0)))
          need #(+ root0 fs0 %)
          budget (seon.config/value :seon.config.machine/memory-mb)
          mean (fn [xs] (lib/r2 (/ (reduce + 0 xs) (max 1 (count xs)))))
          gen (fn [p k] (mean (map #(get-in (p %) [k]) ids)))
          ok-prove? (fn [r] (and (nil? (:ex r)) (zero? (+ (:fail r 0) (:error r 0))) (pos? (:pass r 0)) (<= 50 (:ran r 0) 100)))]
      {:report {:agents n :load (clojure.string/trim (:out (clojure.java.shell/sh "sysctl" "-n" "vm.loadavg")))
                :wall-ms {:ready ready-ms :end-to-end (lib/r2 (profile/ms-since t0)) :prove-all-ten (lib/r2 prove-ms)}
                :evals-ms-per-form (vec (for [i (range 5)] (lib/summ (map #(nth (:evals-ms %) i) evals))))
                :prove {:tests-ms (lib/summ (map :tests-ms proves)) :ran (lib/summ (keep :ran proves)) :reused (lib/summ (keep :reused proves))
                        :pass (lib/summ (keep :pass proves)) :deferred (lib/summ (map :deferred proves))}
                :per-eval-growth-mb-mean {:footprint (vec (for [i (range 5)] (mean (map #(- (get-in (nth (:probes %) i) [:fp]) (get-in (p0 (:id %)) [:fp] 0)) evals))))
                                          :non-static-gen (vec (for [i (range 5)] (mean (map #(- (get-in (nth (:probes %) i) [:gen-mb]) (get-in (p0 (:id %)) [:gen-mb] 0)) evals))))}
                :tests-reaching-dirty? (lib/summ (keep :reaching evals))
                :baseline-before-work-mb {:available (:available-mb mem0) :root root0 :fork-server fs0 :standby (:standby base)
                                          :agents-sum (lib/r2 (reduce + 0 (vals (:agents base))))}
                :machine-drop-mb {:after-evals (drop-of mem-evals) :peak drop-peak :after-prove (drop-of mem-after)
                                  :per-agent-at-peak (lib/r2 (/ drop-peak n)) :per-agent-after-evals (lib/r2 (/ (drop-of mem-evals) n))
                                  :per-agent-after-prove (lib/r2 (/ (drop-of mem-after) n))}
                :anonymous-memory-drop-mb {:after-evals (lib/r2 (- (:anon-mb mem-evals) (:anon-mb mem0))) :peak anon-peak :after-prove (lib/r2 (- (:anon-mb mem-after) (:anon-mb mem0)))
                                           :per-agent-at-peak (lib/r2 (/ anon-peak n))}
                :need-mb {:peak (lib/r2 (need drop-peak)) :after-evals (lib/r2 (need (drop-of mem-evals))) :after-prove (lib/r2 (need (drop-of mem-after)))
                          :budget budget}
                :by-phase by-phase
                :machine-drop-by-phase-mb (into (sorted-map) (for [[p as] (group-by :phase @avails)]
                                                              [p {:max (lib/r2 (- (:available-mb mem0) (apply min (map :avail-mb as))))
                                                                  :last (lib/r2 (- (:available-mb mem0) (:avail-mb (last as))))
                                                                  :test-children-at-once-max (apply max (map :children-n as))}]))
                :peak-footprints-mb {:root (get pid-peaks (lib/c-getpid)) :fork-server (get pid-peaks fs-pid)
                                     :agent-summ (lib/summ (keep pid-peaks agent-pids))
                                     :test-children-seen (count children-pids) :test-child-summ (lib/summ (keep pid-peaks children-pids))}
                :per-agent-before-evals-after-evals-after-prove
                {:footprint-mb [(gen p0 :fp) (gen p1 :fp) (gen p2 :fp)]
                 :non-static-gen-mb [(gen p0 :gen-mb) (gen p1 :gen-mb) (gen p2 :gen-mb)]
                 :heap-incl-static-mb [(gen p0 :heap-mb) (gen p1 :heap-mb) (gen p2 :heap-mb)]}
                :dirty-memory-by-category-mb {:root (categories (lib/c-getpid)) :fork-server (categories fs-pid)
                                              :agent (categories (first agent-pids))}
                :private-pages-added-mb {:note "per agent since all were ready, bracketed by top's copy-on-write faults (at least) and all faults (at most)"
                                         :agents-cow (lib/summ (map #(added 0 %) agent-pids)) :agents-faults (lib/summ (map #(added 1 %) agent-pids))
                                         :root-cow (lib/r2 (added 0 (lib/c-getpid))) :root-faults (lib/r2 (added 1 (lib/c-getpid)))
                                         :fork-server-cow (lib/r2 (added 0 fs-pid)) :fork-server-faults (lib/r2 (added 1 fs-pid))}
                :readings {:footprint (count all-samples) :cost-ms (lib/summ (map :cost-ms all-samples)) :available (count @avails) :test-children-at-once-max (apply max 0 (map :children-n @avails))
                           :test-children-at-once-p50 (lib/r2 (lib/percentile (map :children-n (filter #(= :prove (:phase %)) @avails)) 0.5))}
                :cpu-s {:root (lib/r2 (- (get-in fin [:cpu (lib/c-getpid)]) (get-in base [:cpu (lib/c-getpid)])))
                        :fork-server (lib/r2 (- (get-in fin [:cpu fs-pid]) (get-in base [:cpu fs-pid])))
                        :agents-sum (lib/r2 (reduce + 0 (for [p agent-pids] (- (get-in fin [:cpu p] 0) (get-in base [:cpu p] 0)))))
                        :test-children-last-seen-sum (lib/r2 (reduce + 0 (for [c children-pids]
                                                                           (apply max 0 (keep #(get-in % [:cpu c]) all-samples)))))}
                :failed (vec (for [e evals :when (not (:ok e))] (select-keys e [:id :failed])))}
       :checks {:every-eval-passed (every? :ok evals)
                :every-prove-ran-50-to-100-tests-green (every? ok-prove? proves)
                :fits-the-machine (<= (need drop-peak) budget)}}))))

(defn spawn-phases
  "n agents spawned at once on a scratch main holding Seon's own program, twice (wave 1: main as imported;
  wave 2: nothing moved since): each agent's phase table (seon.agent/spawn-phases, on its :ready event),
  the fork server's :applied and :forked events, and the wall from the request to each :ready.
  What a spawn costs, per phase (docs/processes.md)."
  [n]
  (run-check "SPAWN-PHASES" {:datoms 0 :source {:root (seon.config/checkout) :dirs (seon.config/value :seon.config.source/dirs)}}
   (fn [sys]
    (let [_ (when (pos? (seon.config/value :seon.config.vector/texts))
              (seon.root/await-db sys #(empty? (seon.vector/due % (seon.vector/model-id (seon.config/value :seon.config.vector/model))))))
          wave (fn [w]
                 (let [ev (c/events sys) t0 (profile/now-ns)
                       ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "spawn " w "." i)})) (range n)))
                       ids-set (set ids)
                       evs (loop [left ids-set acc []]
                             (if (empty? left) acc
                                 (let [e (await-event ev #(or (#{:applied :forked} (:ev %)) (ids-set (:agent %))))]
                                   (recur (if (= :ready (:type e)) (disj left (:agent e)) left)
                                          (conj acc (assoc e :at-ms (lib/r2 (profile/ms-since t0))))))))
                       _ (a/untap (:events-mult sys) ev)
                       ready (filter #(= :ready (:type %)) evs)]
                   {:wall-ms (lib/r2 (profile/ms-since t0))
                    :ready (vec (sort-by :at-ms (for [e ready] (select-keys e [:agent :at-ms :start-ms :phases]))))
                    :applied (vec (for [e evs :when (= :applied (:ev e))] (select-keys e [:t :apply-ms :lag-ms :at-ms])))
                    :forked (vec (for [e evs :when (= :forked (:ev e))] (select-keys e [:agent :fork-ms :static-ms :at-ms])))}))
          w1 (wave 1) w2 (wave 2)]
      {:report {:agents n :wave-1 w1 :wave-2 w2}
       :checks {:all-ready (= n (count (:ready w1)) (count (:ready w2)))}}))))

(defn orphan
  "Root dies while its agents are busy (one in a loop, one in a forever test, one clone):
  prints their pids and exits; bin/check checks none of them outlives root."
  []
  (let [sys (c/start! {:datoms 500})
        [a b] (spawn-ready! sys "loops" "tests forever")
        cl (ready! sys #(deref (c/clone! sys a)))
        ev (c/events sys)
        _ (doseq [id [a (:id cl)]] (c/submit! sys id "(loop [] (recur))"))
        _ @(c/submit! sys b "(ns app.forever (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest forever (loop [] (recur)))")
        ;; bounded by :seon.config.agent/test-ticks: root dies long before the bound
        _ (c/run-tests! sys b)
        ;; busy when root dies: each loop began, and the test child is forked (its pid on
        ;; the event), or the run answered first (no child)
        [_ _ forked] (await-each ev [(started a "(loop [] (recur))") (started (:id cl) "(loop [] (recur))")
                                     #(and (= b (:agent %)) (#{:tests-started :tests} (:type %)))])
        test-child (when (= :tests-started (:type forked)) (:pid forked))
        pids (keep #(get-in (c/state sys) [:agents % :pid]) [a b (:id cl)])]
    (out! "PIDS " (clojure.string/join " " (concat pids [(get-in (c/state sys) [:fork-server :pid])] (when test-child [test-child]))))
    ;; without a running test child there is nothing to prove about it
    (when-not test-child (out! "NO TEST CHILD " (shown forked)))
    (lib/c-exit (if test-child 0 1))))

;; ---- durability: the whole system restarts and recovers ------------------------------

(defn- dir-bytes [path] (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (java.io.File. path))))))

(defn- main-data-datoms
  "Main's datoms (a seq) but root's own facts (:seon.root/*: its boot, its quiescence report, its
  collection record), each boot's user (an agent of the boot with no process, seon.task/boot-tx:
  a new boot writes its own), root's link to the agents it started (:seon.agent/root: a new boot
  retracts an earlier boot's, seon.task/earlier-agents-tx, one per agent) and every transaction
  entity's own datoms (:db/txInstant, e = tx): a restarted root writes its facts anew in
  transactions of its own (boot, quiescent, collected: three :db/txInstant datoms more), so what
  a restart must give back exactly is the rest."
  [sys]
  (let [db @(:conn sys) users (set (d/q '[:find [?e ...] :where [?e :seon.agent/id :user]] db))]
    (remove #(or (= "seon.root" (namespace (:a %))) (= :seon.agent/root (:a %)) (= (:e %) (:tx %)) (users (:e %)))
            (d/datoms db :eavt))))

(defn- by-attr
  "The datoms main-data-datoms counts, per attribute: {attr n}, so a recovery that differs names
  the attributes it differs in."
  [datoms] (frequencies (map :a datoms)))

(defn durable-write
  "Phase 1 (a process of its own): boot on main branch nm of the one store (made from its
  seed), then
  a merges a new f1, b commits unmerged facts and a recorded defn on its branch,
  c's clone commits on a branch of a branch. Root then exits WITHOUT stop! (SIGKILL of
  itself): nothing but the store survives."
  [nm]
  (run-check "DURABLE-WRITE" {:main (keyword nm)}
   (fn [sys]
    (let [path (str (seon.db/agents-dir (:cfg sys)) "/durable")
        [a b k] (spawn-ready! sys {:task "make f1 multiply" :branch :dur-a} {:task "unmerged work" :branch :dur-b} {:task "parent" :branch :dur-c})
        ra (p1 sys a "(in-ns 'app.m0) (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (* x 10)) (deftest f1-test (is (= 20 (f1 2)))) (f1 2)")
        m (merge-in! sys a)
        rb (p1 sys b "(in-ns 'app.m1) (defn f2 {:malli/schema [:=> [:cat :int] :string]} [x] (str \"b:\" x)) (deftest f2-test (is (= \"b:1\" (f2 1))))
                             (in-ns 'agent.a2) (transact! [{:note/text \"b's unmerged note\" :note/agent :b}]) (app.m1/f2 1)")
        cl (ready! sys #(deref (c/clone! sys k {:branch :dur-clone})))
        ev (c/events sys)
        rc (p1 sys (:id cl) "(transact! [{:note/text \"the clone's note\" :note/agent :clone}]) (count (q '[:find ?n :where [?n :note/text]]))")
        ;; an agent's write is answered when applied, committed when the agent is idle (its
        ;; run-ahead writer) and durable at root's next flush: the kill waits for the
        ;; clone's commit event, then for a flush after it (a :flush? write's answer comes after
        ;; its flush, which covers the commit the writer was told of first), rather than
        ;; racing them (a kill in between loses the write by design)
        _ (await-event ev #(and (= :commit (:type %)) (= (:id cl) (:agent %))))
        _ @(c/transact-main! sys [] {:flush? true})
        st (c/state sys {:datoms true})
        md (main-data-datoms sys)
        expect {:main-datoms (count md) :by-attr (by-attr md) :main-t (get-in st [:main :t]) :user (seon.task/agent-key (:boot sys) :user)}]
      (spit (str path ".expect.edn") (sedn/encode expect))
      {:report {:store (:store st) :a ra :merge (dissoc m :ops) :b rb :clone [(:id cl) rc] :expect expect :store-bytes (dir-bytes (:store-path sys))}
       :checks {:merged (:merged? m)} :then :kill}))))

(defn durable-read
  "Phase 2 (a new process): reopen the same store on the same main and check main, the
  program, the branches and agents' committed work came back; resume b and merge it now."
  [nm]
  (run-check "DURABLE" {:main (keyword nm)}
   (fn [sys]
    (let [expect-file (str (seon.db/agents-dir (:cfg sys)) "/durable.expect.edn")
          ;; phase 2 reads what phase 1 wrote before it killed itself: run alone, it says so
          _ (when-not (.exists (java.io.File. expect-file))
              (out! "DURABLE FAILED (phase 1 left no " expect-file ": durable-read reopens what durable-write killed; run bin/check durable)")
              (lib/c-exit 1))
          expect (read-string (slurp expect-file))
          ;; read once: phase 2 takes what phase 1 left (its report carries it), so a later run
          ;; alone finds none rather than an earlier run's
          _ (.delete (java.io.File. expect-file))
        st (c/state sys {:datoms true})
        data (main-data-datoms sys)
        md (count data)
        ba (by-attr data)
        ;; this boot has started no agent yet: every earlier boot's link to its agents is
        ;; retracted, and the one agent linked is this boot's user (seon.task/boot-tx)
        linked (vec (for [dt (d/datoms @(:conn sys) :aevt :seon.agent/root)] (:seon.agent/key (d/pull @(:conn sys) [:seon.agent/key] (:e dt)))))
        [n rb rc] (spawn-ready! sys "born after the restart" {:task "resume b" :branch :dur-b} {:task "resume the clone" :branch :dur-clone})
        ;; each request's response is its last form's value: one vector of what each check reads
        r-n (ts/value-of @(c/submit! sys n "[(app.m0/f1 2) (count (q '[:find ?n :where [?n :note/text]]))]"))
        r-b (ts/value-of @(c/submit! sys rb "[(app.m1/f2 1) (q '[:find [?t ...] :where [?n :note/text ?t]]) (app.m0/f1 2)]"))
        ;; its recorded f2-test; main's later f1-test is not on its branch
        t-b @(c/run-tests! sys rb)
        r-c (p1 sys rc "(sort (q '[:find [?t ...] :where [?n :note/text ?t]]))")
        mmb @(c/merge-main! sys rb)
        mb (merge-in! sys rb)
        [n2] (spawn-ready! sys "after merging b")
        r-n2 (ts/value-of @(c/submit! sys n2 "[(app.m1/f2 1) (q '[:find [?t ...] :where [?n :note/agent :b] [?n :note/text ?t]]) (app.m0/f1 2)]"))]
      {:report {:store (:store st) :main (:main st) :main-data-datoms {:phase-1 (:main-datoms expect) :recovered md}
                :attrs-that-differ (into {} (for [a (set (concat (keys (:by-attr expect)) (keys ba)))
                                                  :let [x (get (:by-attr expect) a 0) y (get ba a 0)] :when (not= x y)]
                                              [a [x y]]))
                :agents-linked-at-boot linked
                :born-after r-n :resumed-b r-b :b-tests (select-keys t-b [:pass :fail :error]) :resumed-clone r-c
                :merge-b [(select-keys mmb [:ok :applied]) (dissoc mb :ops)] :after-merging-b r-n2}
       :checks {:main-recovered (and (= (:main-datoms expect) md) (:recovered? (:store st)))
                ;; what main-data-datoms leaves out, asserted: the boot retracted phase 1's links
                :earlier-agents-are-not-this-roots (= [(seon.task/agent-key (:boot sys) :user)] linked)
                ;; phase 1's root was SIGKILLed: it left no stop fact, so this boot found its end :unclean
                ;; main-data-datoms leaves each boot's user out: phase 1's is still main's
                :phase-1s-user-recovered (= :user (:seon.agent/id (d/pull @(:conn sys) [:seon.agent/id] [:seon.agent/key (:user expect)])))
                :the-killed-root-ended-unclean (= :unclean (:cause (:seon.root/previous-end (d/pull @(:conn sys) [:seon.root/previous-end] [:seon.root/id :root]))))
                :branches-recovered (= #{:dur-a :dur-b :dur-c :dur-clone} (set (get-in st [:store :recovered-branches])))
                :main-runs-the-merged-f1 (= 20 (first r-n))
                :b-resumes-its-work (and (= "b:1" (first r-b)) (re-find #"unmerged" (str (second r-b))))
                :b's-tests-pass (and (not (contract/error? t-b)) (zero? (+ (:fail t-b) (:error t-b))) (pos? (:pass t-b)))
                :the-clone-resumes (re-find #"clone's note" (str r-c))
                :b-merges-now (and (:merged? mb) (= "b:1" (first r-n2)) (= 20 (nth r-n2 2)))}}))))

(defn window-write
  "Phase 1 of the group-commit check (continuous, no window): main branch nm of the one
  store. A is written with :flush? (an outside effect follows): synced before its answer,
  the store clean. With the flusher paused, B is answered committed and unflushed (the
  writer dirty, its flush count unmoved), and C, with :flush?, is answered after its own
  sync (the count moved, clean). The flusher resumed, D is answered, and root SIGKILLs
  itself at once."
  [nm]
  (run-check "WINDOW-WRITE" {:main (keyword nm)}
   (fn [sys]
    (let [writer #(let [p (ping sys :writer)] (if (:blocked p) p (select-keys (:state p) [:flushes :dirty])))
          t (fn [p] (let [t0 (profile/now-ns) r @p] [(:ok r) (lib/r2 (profile/ms-since t0))]))
          a (t (c/transact-main! sys [{:note/text "A" :note/agent :window}] {:flush? true}))
          w0 (writer)
          _ (flow/pause-proc (:graph sys) :flusher)
          b (t (c/transact-main! sys [{:note/text "B" :note/agent :window}]))
          w1 (writer)
          cc (t (c/transact-main! sys [{:note/text "C" :note/agent :window}] {:flush? true}))
          w2 (writer)
          _ (flow/resume-proc (:graph sys) :flusher)
          d (t (c/transact-main! sys [{:note/text "D" :note/agent :window}]))]
      {:report {:answers {:A-flush? a :B b :C-flush? cc :D d} :writer [w0 w1 w2]}
       :checks {:a-answered-after-its-flush (false? (:dirty w0))
                :b-answered-unflushed (and (:dirty w1) (= (:flushes w0) (:flushes w1)))
                :c-answered-after-its-flush (and (false? (:dirty w2)) (= (inc (:flushes w1)) (:flushes w2)))
                :all-ok (every? first [a b cc d])}
       :then :kill}))))

(defn window-read
  "Phase 2: the store reopened on main nm after root's crash holds A, B and C. (D is there too: a process crash
  keeps what LMDB handed the kernel; only a machine crash loses the open window.)"
  [nm]
  (run-check "WINDOW" {:main (keyword nm)}
   (fn [sys]
    (let [notes (set (main-q sys '[:find [?t ...] :where [?n :note/agent :window] [?n :note/text ?t]]))]
      {:report {:after-the-crash (sort notes)} :checks {:a-b-c-survive (every? notes ["A" "B" "C"])}}))))

;; ---- lifecycle ---------------------------------------------------------------------------

(defn- alive? [pid] (zero? (lib/c-kill pid 0)))

(defn- cycle-row
  "Cycle i, start! -> 3 agents evaluate -> stop!, in this process: root's threads against what
  it declares (thread-diff, {} when exact) while running and after its stop!, the stop's answer
  and any process it left."
  [i]
  (let [t0 (profile/now-ns)
        sys (c/start! {:datoms 2000})
        boot-ms (profile/ms-since t0)
        ids (apply spawn-ready! sys (map #(str "lc " %) (range 3)))
        ok? (every? #(= "4" (printed (deref (c/submit! sys % "(+ 2 2)")))) ids)
        health (mapv #(let [p (ping sys %)] (:status p p)) [:api :writer :fork-server :flusher :reconciler])
        running (thread-diff (declared-threads sys) (threads))
        pids (vec (cons (get-in (c/state sys) [:fork-server :pid]) (keep #(get-in (c/state sys) [:agents % :pid]) ids)))
        st (c/stop! sys)]
    ;; after stop!, the named roster and the sweepers are what stop! answers for; the OS's
    ;; count is recorded, not compared: a thread Thread.join answered for is still in the
    ;; kernel's count for a while (164 of 200 trials; macOS removes it after even a
    ;; pthread_join), and nothing can await its removal (docs/processes.md#a-stop-reaps-every-child)
    (let [stopped (thread-diff (declared-threads) (threads))]
      {:cycle i :ok ok? :boot-ms (lib/r2 boot-ms) :stop st :left (vec (filter alive? pids))
       :health (set health)
       :running running :stopped (dissoc stopped :os) :stopped-os (:os stopped)})))

(defn lifecycle-cycle
  "One cycle (cycle-row) in this process, its row the last line, as EDN."
  [i]
  (out! (sedn/encode (cycle-row i)))
  (lib/c-exit 0))

(defn- cycles
  "n cycles, each in a process of its own (target/seon lifecycle-cycle i): a process boots root
  once, on its main carrier, and forks its fork server from it while it is the one thread
  (seon.root/boot!); a second boot in the process that ran the first is refused (boot-twice).
  Each cycle's row (cycle-row), read from its last line."
  [n]
  (vec (for [i (range n)]
         (let [{:keys [exit out err]} (clojure.java.shell/sh "./seon" "lifecycle-cycle" (str i) "--profile" (name (seon.config/profile)))]
           (if (zero? exit)
             (sedn/decode (last (clojure.string/split-lines out)))
             {:cycle i :ok false :exit exit :err err :health #{}})))))

(defn- in-child
  "f's answer in a child lib/fork! makes, run on the child's own fiber, as its exit status: 0
  when (f) answers true, 1 when it answers anything else."
  [f]
  (lib/fork! {:child #(lib/c-exit (if (true? (f)) 0 1))
              :refused (fn [n] {:refused n}) :parent lib/wait-status}))

(defn- in-child-bounded
  "in-child's answer with f on a future with a 5 s deadline: 2 when it has not answered, so a
  child whose runtime is broken ends instead of hanging."
  [f]
  (in-child #(let [r (deref (future (f)) 5000 ::hung)] (if (= ::hung r) (lib/c-exit 2) r))))

(def ^:private fork-cases
  "The forking cases seon.lib-test runs, each in a fresh process (target/seon forks CASE) whose
  main carrier makes the fork, as every process that forks does (seon.root/boot!, the fork
  server, an agent): a fork beside live runtime threads is refused whenever one is active at
  that instant. Each answers the data its test asserts."
  {"line-parks"
   ;; on the child's own fiber: the reader's wait must be that fiber's park, not a thread's. The line
   ;; comes from a thread of the parent, 200 ms on (no fiber of the child could write it while its
   ;; carrier is held): it was a future in the child, and a thread started in a forked child made
   ;; collections that copied ever larger continuations (35 GB, minutes at 99% CPU, the reader's main
   ;; thread waiting on the collector; Jolt's, filed). The verdict is which happened first, never how long.
   #(let [[r w] (sock/socketpair)]
      (lib/fork! {:child (fn []
                           (let [ran (volatile! false)
                                 _ (a/go (vreset! ran true))
                                 rd (sock/line-reader r)
                                 lines (loop [] (let [ls (rd)] (if (seq ls) ls (recur))))]
                             (lib/c-exit (if (and (= ["done"] lines) @ran) 0 1))))
                  :refused (fn [n] {:refused n})
                  :parent (fn [pid]
                            (doto (Thread. #(do (Thread/sleep 200) (sock/write-line! w "done"))) (.setDaemon true) (.start))
                            (lib/wait-status pid))}))
   "memory-gauge"
   ;; the largest block a test allocates (one process, no fan-out: AGENTS section 8); Chez keeps a
   ;; bytevector this large in place (a large object is never copied by a collection)
   #(let [before (lib/real-mb)
          block (byte-array (* 300 1024 1024))]
      (java.util.Arrays/fill block (byte 7))
      (let [held (lib/real-mb)
            child (in-child-bounded (fn [] (let [b0 (lib/real-mb)]
                                          ;; one byte a 16 KB page: 30 MB of pages written, ~1,900 writes
                                          (doseq [i (range 0 (* 30 1024 1024) 16384)] (aset block i (byte 1)))
                                          (< 28 (- (lib/real-mb) b0) 60))))]
        {:before before :held held :child child :after (lib/real-mb) :kept (aget block 0)}))
   "touch-gauge"
   ;; a parent holds 200 MB it wrote; one child only reads every page, another writes every page. Each
   ;; sends its footprint growth and the machine's drop in free plus inactive MB (lib/sysmem) on a
   ;; socket. The ledger charges both 200 MB; only the write costs the machine its copy.
   #(let [mb 200
          block (byte-array (* mb 1024 1024))
          _ (java.util.Arrays/fill block (byte 7))
          [r w] (sock/socketpair)
          touch (fn [write?]
                  (let [pid (lib/fork! {:child (fn []
                                                 (let [f0 (lib/real-mb) m0 (:available-mb (lib/sysmem))
                                                       sum (volatile! 0)]
                                                   (doseq [i (range 0 (* mb 1024 1024) 16384)]
                                                     (if write? (aset block i (byte 1)) (vswap! sum + (aget block i))))
                                                   (sock/write-line! w (pr-str {:charged (- (lib/real-mb) f0)
                                                                                :machine-drop (- m0 (:available-mb (lib/sysmem)))
                                                                                :sum @sum}))
                                                   (lib/c-exit 0)))
                                        :refused (fn [n] {:refused n}) :parent identity})
                        st (lib/wait-status pid)
                        rd (sock/line-reader r)
                        line (loop [] (let [ls (rd)] (if (seq ls) (first ls) (recur))))]
                    (assoc (sedn/decode line) :exit st)))]
      {:read (touch false) :write (touch true)})
   "go-blocks"
   #(let [c (a/chan) r (a/go (a/<! c))]
      (a/>!! c 41)
      {:parent (a/<!! r)
       :go (in-child-bounded (fn [] (let [c (a/chan) r (a/go (a/<! c))] (a/>!! c 41) (= 41 (a/<!! r)))))
       :timeout (in-child-bounded (fn [] (= :t (a/alt!! (a/timeout 10) :t))))
       :store (in-child-bounded (fn [] (let [cfg {:store {:backend :memory :id (random-uuid)}}]
                                      (d/create-database cfg)
                                      (let [c (d/connect cfg)] (d/transact c [{:db/ident :x}]) (d/release c) true))))})
   "ends-with-its-starter"
   ;; a process ends with the pid it watches (seon.lib/end-with!), and so does what it forks: mid
   ;; watches itself, so its child inherits the watch of mid; the child parks in a read that never
   ;; comes. mid is killed: its child ends with it, though nothing in its own code would
   #(let [[r w] (sock/socketpair)
          [hold _] (sock/socketpair)
          park (fn [_] (sock/recv! (sock/conn hold)))
          mid (lib/fork! {:child (fn []
                                   (lib/end-with! (lib/c-getpid) false)
                                   (lib/fork! {:child (fn [] (sock/write-line! w (str (lib/c-getpid))) (park nil))
                                               :refused park :parent park}))
                          :refused (fn [n] {:refused n}) :parent identity})
          rd (sock/line-reader r)
          child (parse-long (first (loop [] (let [ls (rd)] (if (seq ls) ls (recur))))))
          {:keys [wait close]} (lib/wait-fd-or-exit hold child)]
      (lib/c-kill mid 9)
      (let [st (lib/wait-status mid) ended (wait)]
        (close)
        {:mid st :child ended}))
   "ends-with-a-gone-pid"
   ;; a pid already gone when it is watched ends the process at once (its watch's ESRCH)
   #(let [gone (lib/fork! {:child (fn [] :done) :refused (fn [n] {:refused n}) :parent identity})]
      (lib/wait-status gone)
      (lib/fork! {:child (fn [] (lib/end-with! gone false) (sock/recv! (sock/conn (first (sock/socketpair)))))
                  :refused (fn [n] {:refused n}) :parent lib/wait-status}))
   "ends-its-group"
   ;; a process that leads its group ends the group with it: its sibling in the group, parked in a
   ;; read and watching nothing that ends, ends too
   #(let [[r w] (sock/socketpair)
          [hold _] (sock/socketpair)
          park (fn [_] (sock/recv! (sock/conn hold)))
          anchor (lib/fork! {:child (fn [] (park nil)) :refused (fn [n] {:refused n}) :parent identity})
          leader (lib/fork! {:child (fn []
                                      (lib/c-setpgid 0 0)
                                      (lib/fork! {:child (fn [] (sock/write-line! w (str (lib/c-getpid))) (park nil))
                                                  :refused park
                                                  :parent (fn [_] (lib/end-with! anchor false) (park nil))}))
                             :refused (fn [n] {:refused n}) :parent identity})
          rd (sock/line-reader r)
          member (parse-long (first (loop [] (let [ls (rd)] (if (seq ls) ls (recur))))))
          {:keys [wait close]} (lib/wait-fd-or-exit hold member)]
      (lib/c-kill anchor 9)
      (let [st [(lib/wait-status anchor) (lib/wait-status leader)] ended (wait)]
        (close)
        {:anchor (first st) :leader (second st) :member ended}))
   "forks-beside-the-watch"
   ;; 200 forks from a process whose watcher thread runs: each child runs and exits 0
   #(do (lib/end-with! (lib/c-getpid) false)
        (frequencies (for [_ (range 200)]
                       (lib/fork! {:child (fn [] :ran) :refused (fn [n] {:refused n}) :parent lib/wait-status}))))
   "child-throws"
   ;; a child whose code throws: unwound out of lib/fork! it would run this case's catch in the
   ;; child (exit 7); it ends at fork!, exit 1. One whose code returns exits 0
   #(vector (try (lib/fork! {:child (fn [] (throw (ex-info "out of the child" {})))
                             :refused (fn [n] {:refused n}) :parent lib/wait-status})
                 (catch clojure.lang.ExceptionInfo _ (lib/c-exit 7)))
            (lib/fork! {:child (fn [] :returned) :refused (fn [n] {:refused n}) :parent lib/wait-status}))
   "child-hooks"
   ;; a shutdown hook belongs to the process that registered it: root's (seon.main/root-serve,
   ;; its whole stop) ran in every fork of root that left by Jolt's exit (an agent, a test child),
   ;; on root's copied state and the shared store. The parent's hook writes a file; the child
   ;; leaves by System/exit, which runs hooks: none of its parent's may run there
   #(let [f (java.io.File/createTempFile "seon-child-hook" ".txt")
          _ (.delete f)
          hook (Thread. ^Runnable (fn [] (spit f (str (lib/c-getpid)) :append true)))]
      (.addShutdownHook (Runtime/getRuntime) hook)
      (try (let [st (lib/fork! {:child (fn [] (System/exit 3)) :refused (fn [n] {:refused n}) :parent lib/wait-status})]
             {:status st :parents-hook-ran-in (when (.exists f) (slurp f))})
           (finally (.removeShutdownHook (Runtime/getRuntime) hook) (.delete f))))})

(defn forks
  "Fork case k (fork-cases) on this process's main carrier: its answer, the last line as EDN."
  [k]
  (out! (sedn/encode (jolt.host/main-carrier-run! (get fork-cases k))))
  (lib/c-exit 0))

(defn boot-twice
  "start! -> stop! -> start! in this one process: the second boot's answer, the last line as EDN,
  {:second message}. A process boots root once (seon.root/boot!: on the main carrier, before any
  thread), and its threads outlive stop!, so the second start! is refused by Jolt by name
  (main-carrier-run!: fibers already run on carrier threads) and forks nothing beside them."
  []
  (c/stop! (c/start! {:datoms 2000}))
  (out! (sedn/encode {:second (try (c/stop! (c/start! {:datoms 2000})) :booted
                                   (catch Throwable e (ex-message e)))}))
  (lib/c-exit 0))

(defn- exact? [row] (and (:ok row) (empty? (:running row)) (empty? (:stopped row))))

(defn- pause-row
  "Pause/resume in this process, which boots root once: a paused agent's two requests, held
  while it is paused and answered after resume; with the threads root declares and the GC's."
  []
  (let [sys (c/start! {:datoms 2000})
        [a] (spawn-ready! sys "paused")
        _ (flow/pause-proc (:graph sys) a)
        ;; the proc's control channel is FIFO: this ping is taken after the pause
        paused-health (ping sys a)
        ;; one form (a do): several top-level forms answer each one's value (DECISIONS 2026-10-29)
        p1 (c/submit! sys a "(do (def x 1) x)")
        p2 (c/submit! sys a "(inc x)")
        ;; the measurement: no answer within the window while paused
        held (deref p1 (seon.config/value :seon.config.check/hold-ms) :held)
        held-state (= :paused (:status (ping sys a)))
        _ (flow/resume-proc (:graph sys) a)
        r1 @p1 r2 @p2
        declared {:declared-running (declared-threads sys)}]
    (c/stop! sys)
    {:threads (assoc declared :declared-stopped (declared-threads))
     :gc (lib/gc-stats) :gc-sweepers (lib/gc-sweepers! -1)
     ;; what the two held requests answered once resumed, printed, and as they came
     :resumed {:printed [(printed r1) (printed r2)] :responses [r1 r2]}
     :pause-holds (and (= :held held) held-state (= :paused (:status paused-health)))}))

(defn lifecycle-pause
  "pause-row in this process, the last line as EDN."
  []
  (out! (sedn/encode (pause-row)))
  (lib/c-exit 0))

(defn lifecycle
  "n cycles (cycles): each stop! must answer with every proc exited (:graph true) and no
  agent or fork-server process left, and root's threads must be exactly what it declares
  (declared-threads): while running by name and by the OS's count, after every
  stop! by name (the OS's count after a stop recorded: cycles). Then pause/resume (pause-row):
  a paused agent's request is held (no answer), and answered after resume, in order. Each in a
  process of its own (target/seon lifecycle-cycle i, lifecycle-pause): a process boots root
  once, and this one boots none."
  [n]
  (let [rows (cycles n)
        {:keys [exit out err]} (clojure.java.shell/sh "./seon" "lifecycle-pause" "--profile" (name (seon.config/profile)))
        pr (if (zero? exit) (sedn/decode (last (clojure.string/split-lines out))) {:exit exit :err err})
        [r1 r2] (get-in pr [:resumed :printed])]
    (run-check "LIFECYCLE" nil
     (fn [_]
       {:report {:threads (:threads pr)
                 :cycles (mapv #(select-keys % [:cycle :boot-ms :running :stopped :stopped-os :left :health :exit :err]) rows)
                 :stop-ms (lib/summ (map #(get-in % [:stop :ms]) rows))
                 :gc (:gc pr) :gc-sweepers (:gc-sweepers pr)
                 :resumed (:resumed pr)
                 :pause (select-keys pr [:exit :err])}
        :checks {:every-stop-exited (every? #(and (true? (get-in % [:stop :graph])) (zero? (get-in % [:stop :agents-left])) (empty? (:left %))) rows)
                 :every-cycle-worked (every? #(and (:ok %) (= #{:running} (:health %))) rows)
                 :threads-declared (every? exact? rows)
                 :pause-holds (true? (:pause-holds pr))
                 :resume-releases-in-order (and (= "1" r1) (= "2" r2))}}))))

(defn threads-declared
  "lifecycle's cycles alone; the last line is the EDN {:running [diff ..] :stopped [diff ..]}
  that seon.root-test/root-threads-are-what-root-declares reads."
  [n]
  (let [rows (cycles n)]
    (out! (sedn/encode {:running (mapv :running rows) :stopped (mapv :stopped rows)}))
    (lib/c-exit (if (every? exact? rows) 0 1))))

;; ---- the GC's sweepers ----------------------------------------------------------------

(defn sweepers-held
  "Six threads allocate while this one waits for 30 more collections; the last line is the EDN
  {:sweepers n :collections n :gc-real-ms ms}: the sweepers this process has after collecting beside threads
  that wait. Unfixed, Chez starts one per waiting thread and never stops it (our Chez,
  S_collect_sweepers); 0 only when the pool was fixed before the first of them, which
  seon.main/-main does first (seon.root-test/the-sweeper-pool-is-fixed-before-anything-collects)."
  []
  (let [stop (atom false)
        c0 (:count (lib/gc-stats)) r0 (:real-ms (lib/gc-stats))
        workers (doall (for [_ (range 6)] (future (loop [n 0] (if @stop n (do (vec (range 2000)) (recur (inc n))))))))]
    (loop [] (when (< (- (:count (lib/gc-stats)) c0) 30) (recur)))
    (reset! stop true)
    (run! deref workers)
    (out! (sedn/encode {:sweepers (lib/gc-sweepers! -1) :collections (- (:count (lib/gc-stats)) c0) :gc-real-ms (- (:real-ms (lib/gc-stats)) r0)}))
    (lib/c-exit 0)))

;; ---- delivery (session F2): main's commits to the reconciler, messages to agents ------

(defn drain
  "n agents each transact in a loop, and root's own writes stream in, when stop! is
  called. Every request the server accepted is answered; every :ok write is in the
  store afterwards (durable: reopened in a fresh connection), and nothing is left running."
  [n]
  (run-check "DRAIN" {:datoms 2000}
   (fn [sys]
    (let [ids (ready! sys #(vec (pmap (fn [i] (c/spawn! sys {:task (str "drain " i)})) (range n))))
        pids (vec (keep #(get-in (c/state sys) [:agents % :pid]) ids))
        fs-pid (get-in (c/state sys) [:fork-server :pid])
        ;; each agent: one eval writing 50 notes, one transact! at a time
        evals (mapv #(c/submit! sys % (str "(dotimes [i 50] (transact! [{:note/text (str \"d\" i) :note/agent " % "}])) :done")) ids)
        write #(c/transact-main! sys [{:note/text (str "m" %) :note/agent :root}])
        ;; the first half answered, then the second streams in as stop! is called
        half (mapv write (range 150))
        _ @(peek half)
        mains (into half (map write) (range 150 300))
        st (c/stop! sys)
        ;; answered by the time stop! returns, or never: :hang
        answer #(if (realized? %) @% :hang)
        evr (mapv answer evals)
        mr (mapv answer mains)
        ok-main (count (filter :ok mr))
        refused-main (count (filter #(= "root stopped" (:seon.error/message %)) mr))
        conn (d/connect (:cfg sys))
        main-notes (d/q '[:find (count ?e) . :where [?e :note/agent :root]] @conn)
        left (filter alive? (cons fs-pid pids))]
      (d/release conn)
      {:report {:agents n :stop st :main {:ok ok-main :refused-after-close refused-main :stored main-notes}
                :evals {:done (count (filter #(= ":done" (printed %)) evr))
                        :other (frequencies (map #(if (and (map? %) (contains? % :text)) (printed %) %) evr))} :left (vec left)}
       :checks {:every-request-answered (and (not-any? #{:hang} evr) (not-any? #{:hang} mr))
                :main-ok-all-stored (= ok-main (or main-notes 0))
                :main-answers-account (= 300 (+ ok-main refused-main))
                :nothing-left-running (empty? left)
                :graph-stopped (true? (:graph st))}}))))

;; ---- writers (session F2): group commit at 1/8/32/256 concurrent writers --------------

(defn backpressure
  "1. Across the ring: an agent floods root with 20,000 messages while its proc is paused.
     Its channel fills, the poller leaves the frames in the ring, the ring fills, and the
     agent's own send waits: its proc takes nothing while paused (its count when the pause
     arrived, read by the ping after it, is its count after the window), the flood is not
     through, the agent is still busy,
     and after resume all 20,000 arrive, in order, then its reply.
  2. A paused writer: the writer proc is paused while n agents transact (their own
     branches: all answered) and root writes main n times (held, none answered while
     paused); resume completes every transaction. n = 8 by default: what is proved needs a few
     agents, not 200 (pure-tests 2026-10-23: 200 spawns were most of the step's 31 s). Root's memory before/after is reported (a parked put is a parked
     fiber)."
  [n]
  (run-check "BACKPRESSURE" {:datoms 2000}
   (fn [sys]
    (let [[a] (spawn-ready! sys "flood")
        flood "(do (dotimes [i 20000] (#'seon.agent/send! {:op :noise :i i})) :flooded)"
        ev (c/events sys)
        p (c/submit! sys a flood)
        ;; the flood began (the agent said so); then what arrives while paused is the measurement
        _ (await-event ev (started a flood))
        _ (flow/pause-proc (:graph sys) a)
        ;; what its proc had taken when the pause arrived (the ping after it is FIFO), and after the window
        p0 (ping sys a)
        _ (Thread/sleep (seon.config/value :seon.config.check/hold-ms))
        p1 (ping sys a)
        [n0 n1] (map #(get-in % [:state :noise]) [p0 p1])
        busy (get-in (c/state sys) [:agents a :status])
        t0 (profile/now-ns)
        _ (flow/resume-proc (:graph sys) a)
        r @p
        drain-ms (profile/ms-since t0)
        st (:stats (c/state sys))
        ids (ready! sys #(vec (pmap (fn [i] (c/spawn! sys {:task (str "bp " i)})) (range n))))
        mb0 (lib/r2 (lib/real-mb))
        _ (flow/pause-proc (:graph sys) :writer)
        ;; agents own their branches' writes: a paused writer does not hold them
        agents-ok (count (filter #(= ":ok" (printed %))
                                 (mapv #(deref (c/submit! sys % "(do (transact! [{:note/text \"bp\" :note/agent :bp}]) :ok)")) ids)))
        ;; root's own writes to main wait for it
        mains (mapv #(c/transact-main! sys [{:note/text (str "bp" %) :note/agent :root}]) (range n))
        _ (Thread/sleep (seon.config/value :seon.config.check/hold-ms))
        answered-while-paused (count (filter realized? mains))
        mb1 (lib/r2 (lib/real-mb))
        t1 (profile/now-ns)
        _ (flow/resume-proc (:graph sys) :writer)
        rs (mapv deref mains)
        resume-ms (profile/ms-since t1)
        ok (count (filter :ok rs))]
      {:report {:flood {:arrived (:noise st) :ordered (:noise-ordered st) :reply (printed r) :taken-before-the-pause n0 :taken-while-paused (when (and n0 n1) (- n1 n0)) :status-while-paused busy
                        :blocked (filterv :blocked [p0 p1]) :drain-ms (lib/r2 drain-ms)}
                :writer {:agents n :writer-buf (seon.config/value :seon.config.root/writer-buf) :answered-while-paused answered-while-paused
                         :root-real-mb {:before mb0 :writer-saturated mb1} :resume->all-done-ms (lib/r2 resume-ms) :ok ok
                         :agents-ok agents-ok :not-ok (frequencies (remove :ok rs))}}
       :checks {:its-proc-answered (not-any? :blocked [p0 p1])
                :ring-pushes-back (and n0 n1 (= n0 n1) (< n0 20000) (= :busy busy))
                :flood-all-arrive-in-order (and (= 20000 (:noise st)) (true? (:noise-ordered st)) (= ":flooded" (printed r)))
                :agents-write-past-a-paused-writer (= n agents-ok)
                :writer-paused-holds-all (zero? answered-while-paused)
                :writer-resumed-completes-all (= n ok)}}))))

;; ---- hot reload (session F2): a proc's step-fn var redefined in the built binary -----------

(defn hotreload
  "Redefine the :reconciler proc's step-fn Var while root runs (alter-var-root, as a defn
  at the REPL would): its next step runs the new code, with no update! and no restart.
  And a platform function redefined as a merge loads it (its defn evaluated in its
  namespace) reaches a compiled platform caller on its next call, not through a proc's Var:
  a direct-linked call reads the var's linked binding, which every redefinition writes
  through (BUILD plan P3)."
  []
  (run-check "HOTRELOAD" {:datoms 2000}
   (fn [sys]
    (let [hit (promise)
        old @#'seon.reconcile/reconciler-proc
        _ @(c/transact-main! sys [{:note/text "before"}])
        _ (alter-var-root #'seon.reconcile/reconciler-proc
                          (fn [f] (fn ([] (f)) ([a] (f a)) ([st tr] (f st tr)) ([st in m] (deliver hit true) (f st in m)))))
        _ (dotimes [i 3] @(c/transact-main! sys [{:note/text (str "after" i)}]))
        ;; the redefined step delivers when it runs: no poll
        _ @hit
        health (ping sys :reconciler)
        _ (alter-var-root #'seon.reconcile/reconciler-proc (constantly old))
        ;; seon.edn/encode: a one-arity plain defn, the shape a direct-linked build may
        ;; splice, called by the compiled seon.program/method-key
        old-encode @#'seon.edn/encode
        before (seon.program/method-key 'a/b "x")
        _ (binding [*ns* (the-ns 'seon.edn)]
            (eval (read-string "(defn encode \"redefined\" {:malli/schema [:=> [:cat :any] :string]} [v] \"redefined\")")))
        after (seon.program/method-key 'a/b "x")
        _ (alter-var-root #'seon.edn/encode (constantly old-encode))]
      {:report {:health health :compiled-caller {:before before :after-redefining-its-callee after}}
       :checks {:redefined-step-ran (realized? hit) :compiled-caller-reaches-the-redefinition (and (= "[a/b \"x\"]" before) (= "redefined" after))}}))))

(defn fx
  "Session FX1: the effect hop end to end. One agent; per-call cost of a synchronous effect
  (my.fx/call :echo) inside one eval, against a plain call and a branch write; file
  write-back through :file against spit in the agent; async sends; an effect in a test
  fork; the outcomes recorded on the agent's branch."
  [n]
  (run-check "FX" {:datoms 2000}
   (fn [sys]
    (let [[a] (spawn-ready! sys {:task "fx" :branch :fx1})
        dir (str (:agents-dir (:store (c/state sys))) "/fx1") ; this root's own run dir, kept with the store
        _ (.mkdirs (java.io.File. dir))
        ev (fn [code] @(c/submit! sys a code))
        ;; per call, timed inside the agent: (loop n) of the form, µs each
        per (fn [form k] (let [code (str "(let [t0 (seon.profile/now-ns)] (dotimes [i " k "] " form ") (/ (- (seon.profile/now-ns) t0) " k " 1000.0))")]
                           (ev code)
                           (lib/summ (vec (for [_ (range 3)] (parse-double (printed (ev code))))))))
        ok1 (ev "(my.fx/call :echo {:x 1})")
        r {:echo-once (printed ok1)
           :plain-us (per "(identity {:x i})" n)
           :echo-us (per "(my.fx/call :echo {:x i})" n)
           :floor-us (per "(my.fx/call :nope {})" n)
           :transact-us (per "(transact! [{:note/text \"r\"}])" (quot n 4))
           :spit-us (per (str "(spit \"" dir "/a.txt\" (str \"text \" i))") n)
           :file-us (per (str "(my.fx/call :file {:path \"" dir "/b.txt\" :text (str \"text \" i)})") n)
           :send-us (per "(my.fx/send :echo {:x i})" n)
           :inbox (printed (ev "(count (filter #(= :fx (:from %)) (messages)))"))
           :unknown (printed (ev "(:seon.error/type (my.fx/call :nope {}))"))
           :test-fork (do (ev "(ns app.fx (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest fx-in-test (is (= :seon.fx/in-test (:seon.error/type (my.fx/call :echo {:x 1}))))
                                (is (= {:value 7} (binding [seon.agent/*fx-stub* (fn [_ _] {:value 7})] (my.fx/call :echo {:x 1})))))")
                          (select-keys @(c/run-tests! sys a) [:pass :fail :error]))
           :file-text (slurp (str dir "/b.txt"))}
        bdb (:db @(c/branch-db sys :fx1))
        recs (d/q '[:find ?k (count ?e) :where [?e :seon.fx/kind ?k]] bdb)
        q (d/q '[:find [?q ...] :where [?e :seon.fx/kind :echo] [?e :seon.fx/queue-us ?q]] bdb)
        us (d/q '[:find [?q ...] :where [?e :seon.fx/kind :echo] [?e :seon.fx/us ?q]] bdb)
        echo-ping (ping sys :fx/echo)]
      (seon.fx/remove-tree! dir)
      {:report (assoc r :recorded (into {} recs) :echo-queue-us (lib/summ (vec q)) :echo-perform-us (lib/summ (vec us))
                      :owner-ping echo-ping :load (clojure.string/trim (:out (clojure.java.shell/sh "uptime"))))
       ;; the effect answered, its unknown kind is a typed error, and inside the test fork a
       ;; call is refused unless stubbed (both assertions ran and passed)
       :checks {:unknown-kind-is-typed (= ":seon.fx/unknown-kind" (:unknown r)) :test-fork-refuses-unless-stubbed (= {:pass 2 :fail 0 :error 0} (:test-fork r))}}))))

(defn probe-proc
  "A declared test proc (the system check): counts what arrives on :in, and says each count
  on root's events ({:type :probe :tag :got}), which the check awaits."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:tag "a label" :events "root's events channel"} :ins {:in "anything"} :outs {}
       :workload :io :ping-map-fn (fn [st] {:got (:got st) :tag (:tag st)})})
  ([args] (assoc (select-keys args [:tag]) :got 0 ::flow/out-ports {:events (:events args)}))
  ([st _] st)
  ([st _ m] (if (some? m)
              (let [st (update st :got inc)] [st {:events [(seon.step/event {:type :probe :tag (:tag st) :got (:got st)})]}])
              [st nil])))

(defn config-check
  "The config as data on main (session CONFIG): root's boot writes the config in force on
  main's root entity; an agent, forked holding the same config and reading no environment,
  reads the same facts from its own branch's db."
  []
  (run-check "CONFIG" {:datoms 2000}
   (fn [sys]
    (let [main (seon.config/from-db @(:conn sys))
          expected (seon.config/with-provider seon.config/config (seon.config/provider seon.config/config))
          [a] (spawn-ready! sys "reads the config")
          r @(c/submit! sys a "[(count (seon.config/from-db (db))) (hash (seon.config/from-db (db))) (hash seon.config/config)]")
          [n h own] (ts/value-of r)]
      {:report {:keys (count main) :agent-reply (:text r)}
       :checks {:main-holds-the-config-in-force (= expected main)
                :every-declared-key-the-config-holds (= (count seon.config/config) (count main) n)
                :the-agent-reads-it-from-its-branch (= (hash main) h)
                :the-agent-was-forked-holding-it (= (hash seon.config/config) own)}}))))

(defn system-check
  "Root's procs from resources/seon/system.edn: boot reaches the hand map's running set;
  apply! after adding or removing one proc in the file touches only that proc; an unchanged
  proc keeps its queued messages across an apply!."
  []
  (run-check "SYSTEM" {:datoms 2000}
   (fn [sys]
    (let [;; the procs that answered a graph ping within :seon.config.check/ping-ms (an expected one that did not is blocked or absent)
          pids (fn [] (set (keys (flow/ping (:graph sys) :timeout-ms (seon.config/value :seon.config.check/ping-ms)))))
        booted (pids)
        decl (clojure.edn/read-string (slurp (seon.config/value :seon.config.root/system-path)))
        ;; the procs system.edn declares, and each effect kind's owner and workers: the declaration is the one list
        expect (into (set (keys (:procs decl)))
                     (mapcat (fn [[k spec]] (cons (keyword "fx" (name k))
                                                  (for [i (range (if (number? (:workers spec)) (:workers spec) (seon.config/value :seon.config.ai/max-in-flight)))]
                                                    (keyword (str "fx." (name k)) (str i))))))
                     (:fx decl))
        dir (str (:agents-dir (:store (c/state sys))) "/system") ; this root's own run dir
        _ (.mkdirs (java.io.File. dir))
        write! (fn [nm d] (let [f (str dir "/" nm ".edn")] (spit f (sedn/encode d)) f))
        probe (fn [tag] {:proc 'seon.check/probe-proc :args {:tag tag :events :bind/events}})
        r1 @(c/apply! sys (write! "add" (assoc-in decl [:procs :probe] (probe "one"))))
        ;; the probe paused, three messages queued on its :in, then another proc added
        _ (flow/pause-proc (:graph sys) :probe)
        _ @(flow/inject (:graph sys) [:probe :in] [1 2 3])
        r2 @(c/apply! sys (write! "add2" (-> decl (assoc-in [:procs :probe] (probe "one")) (assoc-in [:procs :probe2] (probe "two")))))
        ev (c/events sys)
        _ (flow/resume-proc (:graph sys) :probe)
        ;; the resumed probe took its three: it said so on root's events
        got (:got (await-event ev #(and (= :probe (:type %)) (= "one" (:tag %)) (= 3 (:got %)))))
        r3 @(c/apply! sys (write! "remove" (assoc-in decl [:procs :probe] (probe "one"))))
        after (pids)]
      (seon.fx/remove-tree! dir)
      {:report {:no-answer (vec (remove booted expect)) :extra (vec (remove expect booted))
                :add (dissoc r1 :kept) :add2 (dissoc r2 :kept) :queued-kept got :remove (dissoc r3 :kept)}
       :checks {:boot-set (= expect booted)
                :add-starts-only-it (and (= #{:probe} (set (:started r1))) (empty? (:stopped r1)))
                :add2-starts-only-it (and (= #{:probe2} (set (:started r2))) (empty? (:stopped r2)))
                :queued-kept (= 3 got)
                :remove-stops-only-it (and (= #{:probe2} (set (:stopped r3))) (empty? (:started r3)))
                :running-set-after (= (conj expect :probe) after)}}))))

(defn- eval-records
  "The eval records on db of whom the evals are on, by its identity (seon.repl/record-tx): an
  agent's [:seon.agent/id id], or root's [:seon.root/id :root], each its record with who
  evaluated it (:by, :seon.eval/by): [{:code :text :by ...}]."
  [db [attr id]]
  (vec (for [[r by] (d/q '[:find ?r ?by :in $ ?attr ?id :where [?a ?attr ?id] [?e :seon.eval/agent ?a] [?e :seon.eval/record ?r] [?e :seon.eval/by ?by]] db attr id)]
         (assoc r :by by))))

(defn await-records
  "What (read) answers once (enough? it) holds, read again at each :commit event of agent a
  on ev (a tap taken before what writes them): an agent's records are applied ahead of its
  commit (seon.agent/transact!), so its branch shows them at its commit, not before."
  [ev a read enough?]
  (loop [] (let [r (read)] (if (enough? r) r (do (await-event ev #(and (= :commit (:type %)) (= a (:agent %)))) (recur))))))

(defn mcp-proof
  "The MCP phase's proof: an outside client (a separate process: bin/mcp PORT, the stdio
  relay, fed JSON-RPC on stdin) lists agents, evaluates in a running agent and as root, and
  both evaluations are recorded by \"outside\" (the agent's on its branch, root's on
  main). The relay's peak footprint stays within :seon.config.machine/relay-mb, so a relay
  that boots Seon again is red; one against a port nobody listens on answers each request
  the JSON-RPC error, and exits."
  []
  (run-check "MCP" {:datoms 2000}
   (fn [sys]
    (let [;; port 0: the kernel picks a free one, so two worktrees' proofs never collide
        port (.getLocalPort ^java.net.ServerSocket (seon.server/serve! sys 0))
        [a] (spawn-ready! sys {:task "mcp" :branch :mcp1})
        msgs [{"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" {"protocolVersion" "2024-11-05"}}
              {"jsonrpc" "2.0" "method" "notifications/initialized"}
              {"jsonrpc" "2.0" "id" 2 "method" "tools/list"}
              {"jsonrpc" "2.0" "id" 3 "method" "tools/call" "params" {"name" "seon_agents" "arguments" {}}}
              {"jsonrpc" "2.0" "id" 4 "method" "tools/call" "params" {"name" "seon_eval" "arguments" {"agent" (name a) "code" "(def outside-x 41) (inc outside-x)"}}}
              {"jsonrpc" "2.0" "id" 5 "method" "tools/call" "params" {"name" "seon_eval" "arguments" {"agent" "root" "code" "(count (:agents (c/state sys)))"}}}]
        ;; the client's stdin is a file, so its EOF is a fact of the file: a pipe's write end
        ;; held open anywhere (bin/check mcp hung two hours on one) leaves the client waiting
        dir (:agents-dir (:store (c/state sys))) ; this root's own run dir
        in (str dir "/mcp-in.jsonl")
        _ (spit in (apply str (map #(str (clojure.data.json/write-str %) "\n") msgs)))
        relay (fn [p] (clojure.java.shell/sh "sh" "-c" (str "exec /usr/bin/time -l '" (seon.config/checkout) "/bin/mcp' " p " < '" in "'")))
        ev (c/events sys)
        t0 (profile/now-ns)
        r (relay port)
        ms (profile/ms-since t0)
        ;; a port nobody listens on: the one we listened on, closed
        down (relay (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s)))))
        _ (clojure.java.shell/sh "rm" "-f" in)
        peak-mb (fn [r] (some-> (re-find #"(\d+)\s+peak memory footprint" (:err r)) second parse-long (/ 1048576.0)))
        lines (fn [r] (for [l (clojure.string/split-lines (:out r)) :when (clojure.string/starts-with? l "{")] (clojure.data.json/read-str l)))
        answers (into {} (for [j (lines r)] [(get j "id") j]))
        text (fn [id] (get-in answers [id "result" "content" 0 "text"]))
        ;; the agent's record is on its branch at its commit (await-records); answers its one
        ;; eval when recorded, else waits on the next commit (the eval failed: its text check says so)
        mine (if (clojure.string/includes? (str (text 4)) "42")
               (await-records ev a #(filter (fn [r] (= "outside" (:by r))) (eval-records (:db @(c/branch-db sys :mcp1)) [:seon.agent/id a])) #(= 1 (count %)))
               [])
        _ (a/untap (:events-mult sys) ev)
        roots (filter #(= "outside" (:by %)) (eval-records @(:conn sys) [:seon.root/id :root]))
        bound (seon.config/value :seon.config.machine/relay-mb)]
      {:report {:tools (count (get-in answers [2 "result" "tools"]))
                :agents-text (text 3) :agent-eval (text 4) :root-eval (text 5)
                :agent-records (mapv #(select-keys % [:code :text :by]) mine)
                :root-records (mapv #(select-keys % [:code :text :by]) roots)
                :client-ms (lib/r2 ms) :exit (:exit r)
                :relay-peak-mb (some-> (peak-mb r) lib/r2) :relay-mb-bound bound
                :root-down (mapv #(get-in % ["error" "message"]) (lines down)) :root-down-exit (:exit down)}
       :checks {:tools-are-the-ops (= (set (map #(str "seon_" (name %)) (keys seon.server/ops))) (set (map #(get % "name") (get-in answers [2 "result" "tools"]))))
                :notification-unanswered (= #{1 2 3 4 5} (set (keys answers)))
                :both-recorded-outside (and (= 1 (count mine)) (= 1 (count roots)))
                :agent-eval (clojure.string/includes? (str (text 4)) "42") :root-eval (clojure.string/includes? (str (text 5)) "1")
                :relay-within-bound (boolean (some-> (peak-mb r) (<= bound)))
                :root-down-answers-every-request (and (zero? (:exit down)) (= [1 2 3 4 5] (mapv #(get % "id") (lines down)))
                                                      (every? #(get-in % ["error" "message"]) (lines down)))}}))))

(defn- os-threads
  "The OS threads of process pid (ps -M lists one line per thread after its header)."
  [pid]
  (dec (count (clojure.string/split-lines (:out (clojure.java.shell/sh "ps" "-M" "-p" (str pid)))))))

(defn threads-check
  "Session RC1's physics rule: the processes that fork (the fork server; an agent, for its
  tests and clones) must stay single-threaded, so no sweeper thread ever starts in them.
  An agent evaluates, collects, runs its tests in a fork and is cloned; then the fork
  server, the agent and the clone each have one thread, beside the C thread that ends it with
  root (seon.lib/end-with!)."
  []
  (run-check "THREADS" {:datoms 2000}
   (fn [sys]
    (let [[a] (spawn-ready! sys {:task "threads" :branch :threads1})
          _ @(c/submit! sys a "(ns app.one (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest t-one (is (= 2 (inc 1)))) (dotimes [i 200] (vec (range 10000))) (System/gc) :ok")
          tests (select-keys @(c/run-tests! sys a) [:pass :fail :error])
          cl (:id (ready! sys #(deref (c/clone! sys a {}))))
          cl-eval (printed @(c/submit! sys cl "(inc 41)"))
          st (c/state sys)
          res {:fork-server (os-threads (get-in st [:fork-server :pid])) :agent (os-threads (get-in st [:agents a :pid]))
               :clone (os-threads (get-in st [:agents cl :pid]))}]
      {:report (assoc res :tests tests :clone-eval cl-eval)
       ;; one Chez thread each, beside the C thread that ends it with root (seon.lib/end-with!): the
       ;; collector never sees a thread Chez did not make, so it starts no sweeper for it
       :checks {:each-forking-process-one-thread (= (+ 1 (if (pos? (lib/end-watched)) 1 0)) (:fork-server res) (:agent res) (:clone res))
                :its-test-passed (= 1 (:pass tests)) :the-clone-evaluates (= "42" cl-eval)}}))))

(defn ids
  "Two agents, each a process of its own on a branch of its own forked at one main commit,
  write 10 notes each: every entity id and every transaction id is issued once across them
  (one store-wide page of ids, seon.db/ids!), so a datom names the same entity and the same
  transaction wherever a merge or a union carries it. Per-branch tx ids gave both their
  first transaction one id."
  []
  (run-check "IDS" {}
   (fn [sys]
    (let [[a b] (spawn-ready! sys "ids a" "ids b")
        notes (fn [tag n] (str "(transact! (vec (for [i (range " n ")] {:note/text (str \"" tag "\" i)})))"))
        _ (doall (pmap #(deref (c/submit! sys %1 (notes %2 10))) [a b] ["a" "b"]))
        ids-of (fn [ag] (ts/value-of @(c/submit! sys ag "(vec (q '[:find ?n ?tx :where [?n :note/text _ ?tx]]))")))
        ia (ids-of a) ib (ids-of b)
        es (fn [xs] (set (map first xs))) txs (fn [xs] (set (map second xs)))]
      {:report {:a (count ia) :b (count ib) :a-txs (sort (txs ia)) :b-txs (sort (txs ib))}
       :checks {:each-wrote-10 (= 10 (count ia) (count ib))
                :entity-ids-issued-once (not-any? (es ia) (es ib))
                :tx-ids-issued-once (not-any? (txs ia) (txs ib))}}))))

(defn vectors
  "Semantic search on Seon's own text (docs/vector.md): a root booted on this checkout's source,
  task files and wiki embeds every form, section and plan through its :vectors proc and :embed
  effect (the model in process), awaited on main (seon.root/await-db: nothing due), no clock;
  then 200 of those vectors, each a query, have their top 10 by the pyramid's default levels
  compared with an exact search of every vector (seon.vector/near with a shortlist of all).
  Red below a recall of 0.99, or when the model is missing."
  []
  (run-check "VECTORS" {:datoms 0 :source {:root (seon.config/checkout) :dirs ["src" "resources/seon/schemas"]}}
   (fn [sys]
     (let [path (seon.config/value :seon.config.vector/model)
           model (seon.vector/model-id path)]
       (if (or (not (.exists (java.io.File. path))) (zero? (seon.config/value :seon.config.vector/texts)))
         {:report {:model path :texts (seon.config/value :seon.config.vector/texts)}
          :checks {:model-present (.exists (java.io.File. path)) :embedding-on (pos? (seon.config/value :seon.config.vector/texts))}}
         (let [t0 (System/currentTimeMillis)
               _ (my.root/load-tasks! sys)
               _ (my.root/load-docs! sys)
               db (seon.root/await-db sys #(empty? (seon.vector/due % model)))
               embed-ms (- (System/currentTimeMillis) t0)
               es (sort (map :v (d/datoms db :aevt :seon.vector/of)))
               qs (take-nth (max 1 (quot (count es) 200)) es)
               found (fn [q opts] (set (map first (seon.vector/near db q 10 opts))))
               exact (into {} (for [q qs] [q (found q {:shortlist (count es)})]))
               recall-of (fn [opts] (/ (reduce + (for [q qs] (count (filter (exact q) (found q opts))))) (* 10.0 (count qs))))
               recall (recall-of {})]
           {:report {:vectors (count es) :queries (count qs) :recall10 recall :embed-ms embed-ms
                     ;; what each shortlist would give: the default's evidence, measured here every run
                     :by-shortlist (into (sorted-map) (for [k [50 100 200 400]] [k (recall-of {:shortlist k})]))
                     :forms (count (d/datoms db :aevt :form/id)) :sections (count (d/datoms db :aevt :seon.section/address))}
            :checks {:model-present true :every-text-embedded (empty? (seon.vector/due db model))
                     :pyramid-recall-at-least-0.99 (>= recall 0.99)}}))))))

(defn- fork-server-caught-up
  "A FIFO barrier through the fork server: {:op :stats} goes to the server process on the
  socket that carries main's commits (seon.fork-server/fork-server-proc), the process takes
  that socket in order and answers in order (seon.fork-server/main), and its answers come back
  through the :fork-server proc and the api in order. So this answers after every earlier
  commit's :applied has passed through both. Checked, not assumed: :caught-up only when the
  server's :t has reached main's t read before the request; an ordering that changed fails here."
  [sys]
  (let [main-t (:max-tx @(:conn sys))
        fs @(c/call! sys {:op :fs :req {:op :stats}})]
    {:t (:t fs) :main-t main-t :mismatch (:mismatch fs) :caught-up (boolean (and (:t fs) (>= (:t fs) main-t)))}))

(defn- writer-flushed
  "The writer's answer once its store is clean (seon.root/store-clean!): the flush covering
  main's last commit has synced and its notice reached the api before the answer. Checked,
  not assumed: :covered only when the answer's commit count has reached the count the writer
  held before the request."
  [sys]
  (let [before (get-in (ping sys :writer) [:state :commits])
        r @(c/store-clean! sys)]
    (assoc (select-keys r [:commits :flushes :durable?]) :before before
           :covered (boolean (and (:ok r) before (>= (:commits r) before))))))

(defn idle
  "An idle root steps nothing (owner, 2026-10-21: no beat; health is what flows): root
  boots and settles (the reconciler's quiescence), a tab opens on its dashboard (an SSE
  feed on SEON_WEB_PORT) and receives its page; then every root proc's Flow step count is
  read at the two ends of a window of window-ms with nothing happening, and must not move:
  from the feed's first settled mark after its keyframe (seon.web: the render proc has
  nothing in flight, so the tab's own opening has run its course), the writer's answer once
  its store is clean (writer-flushed: a late sync stepped the flusher and the api), and the fork server's
  answer to a request behind main's commits (fork-server-caught-up: their late :applied
  answers stepped the fork server and the api inside the window under load), and the render
  proc's next settled mark when an ask of its own is still in flight (render-settled: its
  answer stepped the render proc, which sent a settled mark, inside the window under load).
  Then a change reaches the tab: an agent spawned and a commit to main each grow what the
  tab received. The window is the measurement (that nothing happens), not a dependency."
  [window-ms]
  (run-check "IDLE" {:datoms 300}
   (fn [sys]
    (let [web (:state (ping sys :web))]
     (if-not (:listening web)
      ;; its root serves no dashboard (:seon.config.web/port 0): a verdict, not a connect to port 0
      {:report {:web web :needs "a web port: bin/check gives idle one (IDLE_WEB_PORT)"} :checks {:its-root-serves-a-dashboard false}}
    (let [;; the port its web proc bound
        port (:port web)
        _ (seon.await/await-quiet sys 0)
        received (atom 0)
        ;; what the wait needs of the feed's text: its keyframe seen, then a settled mark after it
        ;; (a mark split across two reads is found in the carry of the last read's end)
        feed (atom {:carry "" :keyframe false :settled false})
        see (fn [{:keys [carry keyframe settled]} s]
              (let [t (str carry s)
                    k (when-not keyframe (clojure.string/index-of t "event: datastar-patch-elements"))
                    after (cond k (subs t k) keyframe t)]
                {:keyframe (boolean (or keyframe k))
                 :settled (boolean (or settled (and after (clojure.string/index-of after ": settled"))))
                 :carry (let [r (or after t)] (subs r (max 0 (- (count r) 40))))}))
        sock (java.net.Socket. "127.0.0.1" (int port))
        _ (doto (.getOutputStream sock)
            (.write (.getBytes (str "GET /feed?view=root HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n") "UTF-8"))
            (.flush))
        ;; the tab: an edge reader counting the bytes its feed sends, and watching its text
        in (.getInputStream sock)
        _ (doto (Thread. (fn [] (let [buf (byte-array 8192)]
                                  (loop [] (let [n (try (.read in buf) (catch Exception _ -1))]
                                             (when (pos? n) (swap! feed see (String. buf 0 (int n) "UTF-8")) (swap! received + n) (recur)))))))
            (.setDaemon true) (.start))
        opened (await-ref received pos?)
        ;; the tab's opening ran its course: its keyframe (the feed's first patch: a new tab is
        ;; stale until it), then a settled mark (the render proc has nothing in flight)
        settled (await-ref feed :settled)
        ;; main's last commit synced (the flusher's answer), then main's commits applied in the
        ;; fork server, each one's answers through its proc and the api
        flushed (writer-flushed sys)
        fs (fork-server-caught-up sys)
        ;; the render proc's own asks answered: one in flight (main read for a commit the
        ;; tab's page reads, its state) steps it once more, and the step that answers the last
        ;; starts busy, so it sends a settled mark (seon.render.proc), the mark this waits for. A
        ;; mark sent before the reset and still in the socket when it is read ends the wait early;
        ;; the window then counts the late answer's step (red, never a hang)
        _ (swap! feed assoc :settled false)
        rs (:state (ping sys :render))
        render-pending (:pending rs)
        ;; main as the render proc holds it against main now: behind is a commit notice in flight
        render-main {:t (:main-t rs) :main-t (:max-tx @(:conn sys))}
        render-settled (or (zero? render-pending) (await-ref feed :settled))
        ;; root's declared procs and its effect owners (their workers step only when given work)
        decl (seon.system/read-decl (seon.config/value :seon.config.root/system-path))
        pids (sort-by str (concat (keys (:procs decl)) (for [k (keys (:fx decl))] (keyword "fx" (name k)))))
        counts (fn [] (into (sorted-map) (for [pid pids :let [p (ping sys pid)]] [pid (if (:blocked p) :blocked (:count p))])))
        b0 @received c0 (counts)
        _ (Thread/sleep window-ms)
        c1 (counts) b1 @received
        moved (into (sorted-map) (for [[pid n] c1 :when (not= n (get c0 pid))] [pid [(get c0 pid) n]]))
        n-spawn @received
        a (c/spawn! sys {:task "idle check"})
        spawned (await-ref received #(> % n-spawn))
        n-commit @received
        _ @(c/transact-main! sys [{:db/id "n" :ns/name 'idle.check}])
        committed (await-ref received #(> % n-commit))]
      (.close sock)
      {:report {:window-ms window-ms :procs (count pids) :steps-at-idle moved :bytes-at-idle (- b1 b0) :agent a :flushed flushed :fork-server fs :render-pending render-pending :render-main render-main}
       :checks {:tab-opened opened :tab-settled settled :flush-covered-main (:covered flushed) :fork-server-caught-up (:caught-up fs) :render-settled render-settled :no-step-at-idle (empty? moved) :no-byte-at-idle (= b0 b1)
                :no-proc-blocked (not-any? #{:blocked} (concat (vals c0) (vals c1)))
                :spawn-reached-tab spawned :commit-reached-tab committed}}))))))

;; ---- the store bin/check is given ----------------------------------------------------------

(defn seed
  "The one store bin/check is given (:seon.config.root/store-path; SEON_STORE names another),
  opened before any step boots: created and seeded with about n datoms on :db when missing,
  else as it is. Each step's root then works on a branch of it, its own main (seon.db/open!)."
  [n]
  (let [path (seon.config/value :seon.config.root/store-path)
        t0 (profile/now-ns)
        {:keys [seeded? conn]} (seon.db/open! :lmdb path n :db)
        ms (lib/r2 (profile/ms-since t0))
        datoms (count (d/datoms @conn :eavt))
        branches (count (d/branches conn))]
    (d/release conn)
    (out! (if seeded? "seeded " "opened ") path " (" datoms " datoms on :db, " branches " branches) in " ms " ms; " (dir-bytes path) " bytes")
    (out! "SEED OK")
    (lib/c-exit 0)))

(defn drop-mains
  "Delete each of mains (check steps' own, check-<step>) and every branch under it from the
  given store, with its agents' directory (seon.db/delete-main!): what a step made, deleted
  by the run that made it, before the step runs (a failed earlier run's) and once it is
  green. A store not yet made holds nothing to delete."
  [mains]
  (let [path (seon.config/value :seon.config.root/store-path)]
    (if (seon.db/store? path)
      (let [cfg (seon.db/store-cfg :lmdb path)
            conn (d/connect cfg)
            gone (vec (mapcat #(seon.db/delete-main! conn (keyword %)) mains))]
        (seon.db/sync! cfg)
        (d/release conn)
        (doseq [m mains] (seon.fx/remove-tree! (seon.db/agents-dir (assoc cfg :branch (keyword m)))))
        ;; the store's agents directory goes when the last main's has (rmdir leaves a non-empty one)
        (clojure.java.shell/sh "rmdir" (str path "-agents"))
        (out! "deleted " (count gone) " branches of " (count mains) " mains"))
      (out! "no store at " path ": nothing to delete"))
    (out! "DROP OK")
    (lib/c-exit 0)))

(def ^:private modes
  "Every mode -main runs, by name: (f n more), n the first argument after it. The one list:
  an unknown mode prints these names (bin/check passes its mode here)."
  (let [n-or (fn [n d] (Long/parseLong (or n d)))]
    (sorted-map
     "seed" (fn [n more] (seed (n-or n "2000")))
     "drop" (fn [n more] (drop-mains (remove nil? (cons n more))))
     ;; the store bin/check is given, its size and branch count: "PATH KB BRANCHES"
     "store" (fn [n more] (let [path (seon.config/value :seon.config.root/store-path)
                  c (d/connect (seon.db/store-cfg :lmdb path))]
              (println path (quot (dir-bytes path) 1024) (count (d/branches c))) (d/release c) (lib/c-exit 0)))
     "durable-write" (fn [n more] (durable-write n))
     "durable-read" (fn [n more] (durable-read n))
     "window-write" (fn [n more] (window-write n))
     "window-read" (fn [n more] (window-read n))
     "scale" (fn [n more] (scale (n-or n "100")))
     "spawn-phases" (fn [n more] (spawn-phases (parse-long (or n "1"))))
     "scale-work" (fn [n more] (scale-work (n-or n "10") (not= "no-search" (first more))))
     "robust" (fn [n more] (robust))
     "lifecycle" (fn [n more] (lifecycle (n-or n "10")))
     "threads-declared" (fn [n more] (threads-declared (n-or n "3")))
     "lifecycle-cycle" (fn [n more] (lifecycle-cycle (n-or n "0")))
     "lifecycle-pause" (fn [n more] (lifecycle-pause))
     "boot-twice" (fn [n more] (boot-twice))
     "forks" (fn [n more] (forks n))
     "sweepers-held" (fn [n more] (sweepers-held))
     "drain" (fn [n more] (drain (n-or n "8")))
     "backpressure" (fn [n more] (backpressure (n-or n "8")))
     "contracts" (fn [n more] (contracts))
     "test-inputs" (fn [n more] (test-inputs))
     "mg-check" (fn [n more] (mg-check-mode n more))
     "e2e" (fn [n more] (lib/c-exit (if (seon.e2e/run n more) 0 1)))
     ;; swarm N M: seon.e2e's swarm row (N tasks over 5 shared vars, max-running M), SWARM OK/FAILED
     "swarm" (fn [n more] (lib/c-exit (if (seon.e2e/run "swarm" [(or n "10") (or (first more) "8")]) 0 1)))
     "instrument" (fn [n more] (instrument))
     "record" (fn [n more] (record))
     "orphan" (fn [n more] (orphan))
     "ids" (fn [n more] (ids))
     "vectors" (fn [n more] (vectors))
     "fx" (fn [n more] (fx (n-or n "20")))
     "system" (fn [n more] (system-check))
     "config" (fn [n more] (config-check))
     "problems" (fn [n more] (problems-check))
     "threads" (fn [n more] (threads-check))
     ;; this checkout's ports as the config resolves them, "SERVER WEB" (bin/ports)
     ;; bin/check's step names, the one list (seon.proofs/check-steps), space separated
     ;; jobs: how many steps or shards a proof run runs at once (:seon.config.proofs/jobs), for bin/check
     "jobs" (fn [n more] (do (println (seon.config/value :seon.config.proofs/jobs)) (lib/c-exit 0)))
     "steps" (fn [n more] (do (println (clojure.string/join " " (sort (keys seon.proofs/check-steps)))) (lib/c-exit 0)))
     "mcp-proof" (fn [n more] (mcp-proof))
     "hotreload" (fn [n more] (hotreload))
     "idle" (fn [n more] (idle (n-or n "2000")))
     "smoke" (fn [_ _] (smoke)))))

(defn -main [& args]
  ;; --config FILE and --set :k=v are the config's (seon.config reads them from the command
  ;; line); the mode reads the rest
  (let [[mode n & more] (seon.config/without-overrides args)]
    (if-let [f (get modes mode)]
      (f n more)
      ;; a mode that is not one: its verdict, never another mode's
      (do (out! "no mode " (or mode "given") "; the modes: " (clojure.string/join " " (keys modes)))
          (out! (if mode (clojure.string/upper-case mode) "MODE") " FAILED")
          (lib/c-exit 1)))))
