(ns seon.check
  "End-to-end checks of seon.root through the same API the dashboard uses (proto/flow
  f2-final proto.smoke). bin/check runs them; target/seon <check> [args] runs one.
  usage: target/seon smoke        3 agents: define, test, transact; merge one; kill one mid-eval
         target/seon scale N      N agents spawned at once, each eval + transact + test
  A mode is its body: run-check boots root, runs it, prints its report and checks, stops
  root and ends on the one verdict line, NAME OK or NAME FAILED, and its exit status. Waits
  are root's events (await-agents, on a tap taken before what causes them) or a watch on a
  reference (await-ref, seon.e2e/await-db), never a poll or a deadline."
  (:require [seon.profile :as profile] [seon.root :as c] [seon.lib :as lib] [datahike.api :as d] [clojure.pprint :refer [pprint]] [clojure.java.shell] [clojure.string] [clojure.core.async :as a] [clojure.core.async.flow :as flow] [seon.db] [seon.reconcile] [seon.contract :as contract] [malli.core :as m] [malli.generator :as mg] [seon.e2e] [seon.system] [seon.web] [seon.render.proc] [seon.fixtures] [my.root] [seon.task] [seon.config] [clojure.edn] [seon.server] [clojure.data.json] [seon.cost] [seon.problems]))

(defn- out! [& xs] (println (apply str xs)) (flush))

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
    (pprint (assoc report :checks checks))
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
  missed."
  [sys f]
  (let [ev (c/events sys)
        r (f)]
    (await-agents ev (cond (keyword? r) [r] (map? r) [(:id r)] :else r) #{:ready})
    (a/untap (:events-mult sys) ev)
    r))

(defn- spawn-ready!
  "Agents spawned for tasks (a task string, or spawn! opts), once each is ready (ready!)."
  [sys & tasks]
  (ready! sys #(mapv (fn [t] (c/spawn! sys (if (map? t) t {:task t}))) tasks)))

(defn- await-ref
  "true once (pred @r) holds: a watch on reference r runs pred at each change (seon.e2e/await-db's
  mechanism, on any reference). No deadline, no polling."
  [r pred]
  (let [p (promise) k (gensym "await-")]
    (add-watch r k (fn [_ _ _ v] (when (pred v) (deliver p true))))
    (when (pred @r) (deliver p true))
    @p
    (remove-watch r k)
    true))

(defn- main-q [sys query & inputs] (apply d/q query @(:conn sys) inputs))

(defn- short-items [items] (mapv #(select-keys % [:tag :printed :ms]) items))

(defn- p1 "Each reply item of forms evaluated in agent id, as [printed recorded]." [sys id forms]
  (mapv (juxt :printed :recorded) @(c/submit! sys id forms)))

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
        _ (out! (ids 2) " after tests, its branch still says: " (:printed (first @(c/submit! sys (ids 2) "(doc-of 'app.m0/f1)"))))
        _ (out! (ids 2) " branch diff before its merge: " @(c/branch-diff sys (ids 2)) "; stats now: " @(c/agent-stats! sys (ids 2)))
        m1 @(c/merge! sys (ids 0))
        ;; git: a branch merges main first (main took a1's work), then root merges it
        mm3 @(c/merge-main! sys (ids 2))
        m3 @(c/merge! sys (ids 2))
        _ (out! "merge main into " (ids 2) " first: " (select-keys mm3 [:ok :applied :conflicts]))
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
        mm2 @(c/merge-main! sys (ids 1))
        _ (out! "merge main into " (ids 1) " (both changed f1's doc): " (select-keys mm2 [:ok :applied :conflicts]))
        m2 @(c/merge! sys (ids 1))
        _ (out! "merge " (ids 1) " with its conflict unmerged: " (dissoc m2 :tx))
        ;; clone: a3 forks itself, live values and all, onto a branch of its branch
        _ @(c/submit! sys (ids 2) "(def memo (atom {:seen 1}))")
        t-clone (profile/now-ns)
        cl (ready! sys #(deref (c/clone! sys (ids 2) {:task "a3's clone"})))
        _ (out! "clone " (ids 2) " -> " cl " in " (lib/r2 (profile/ms-since t-clone)) " ms; the clone says: "
                (short-items @(c/submit! sys (:id cl) "(my.agent/whoami) (swap! memo update :seen inc) (doc-of 'app.m0/f1)"))
                "; the parent still has " (:printed (first @(c/submit! sys (ids 2) "@memo"))))
        ;; merge main forward into a1's branch (git merge main): it now sees a3's notes too
        mm @(c/merge-main! sys (ids 0))
        _ (out! "merge main into " (ids 0) ": " mm " -> it sees "
                (:printed (first @(c/submit! sys (ids 0) "(sort (q '[:find [?t ...] :where [?n :note/text ?t]]))"))))
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
       ;; each agent's tests ran and passed; a1 and a3 merged (their tests judging); a2 is refused (its conflict, or the merged doc-test it now fails)
       :checks {:every-agent's-tests-pass (every? (fn [id] (let [t (tests id)] (and (pos? (:pass t)) (zero? (+ (:fail t) (:error t)))))) ids)
                :a1-merged-gated-by-its-tests (and (:merged? m1) (pos? (get-in m1 [:tests :pass] 0)))
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

(defn contracts
  "In this binary (a release build direct-links and inlines app defs): every function var
  of the platform carries a contract; every contracted var holds its checked function
  from its definition on (or is armed at boot), and that function object (what a
  direct-linked caller holds) refuses a call with a wrong arity as the one error map; a
  plain caller compiled against a contracted function reaches its check."
  []
  (run-check "CONTRACTS" nil
   (fn [_]
    (let [boot (contract/instrument! '[seon.edn seon.config seon.contract seon.profile seon.print seon.print.select seon.print.emit])
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
  "An agent's defn with :malli/schema is armed (a bad call refuses as the error map); one
  without stays uninstrumented, joins the agent's uncontracted set, and the warning naming them grows."
  []
  (run-check "INSTRUMENT" {:datoms 500}
   (fn [sys]
    (let [[a] (spawn-ready! sys "instrument")
          [d1 bad] @(c/submit! sys a "(defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)) (sq \"x\")")
          [u1] @(c/submit! sys a "(defn plain [x] x)")
          [u2] @(c/submit! sys a "(defn plain2 [x] x)")]
      {:report {:armed (select-keys d1 [:printed :contract]) :refused (:printed bad)
                :warnings [(first (:warnings u1)) (dissoc (first (:warnings u2)) :seon.repl/fix)]}
       :checks {:armed (= :armed (:contract d1)) :refused (and (= :err (:tag bad)) (re-find #"invalid-input" (str (:printed bad))))
                :warning-grows (and (= 1 (:count (first (:warnings u1)))) (= 2 (:count (first (:warnings u2)))))}}))))

(defn record
  "Each eval request is recorded in one transaction on the agent's branch (its evaluations
  on its agent entity, its defs in program namespaces), built at root from the reply; the
  agent's next request sees its own recording."
  []
  (run-check "RECORD" {:datoms 500}
   (fn [sys]
    (let [[a] (spawn-ready! sys "record")
          r1 @(c/submit! sys a "(+ 1 2) (in-ns 'app.m0) (defn f3 [x] (* 3 x)) (/ 1 0)")
          r2 @(c/submit! sys a "(count (my.db/q '[:find ?e :where [?e :seon.cluster.eval/agent ?a] [?a :seon.agent/id :a1]]))
                               (my.db/q '[:find ?s . :where [?e :var/name app.m0/f3] [?e :var/form ?f] [?f :form/src ?s]])")]
      {:report {:first (mapv #(select-keys % [:tag :printed :record :recorded]) r1) :second (mapv :printed r2)}
       :checks {:four-items (= 4 (count r1)) :its-evals-recorded (= "4" (:printed (first r2)))
                :its-defn-recorded (re-find #"f3" (str (:printed (second r2))))}}))))

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
  for the stall), then read my.root/problems: an eval error and a contract refusal, an
  effect's io error, a platform defect in the api, a stall, an agent's death with its exit
  status and stderr, a red proof's assertions and a cost proof's verdict. It reads them
  once main holds each (seon.e2e/await-db over seon.problems/report)."
  []
  (run-check "PROBLEMS" {:datoms 300 :max-running 2 :stall-ms 1000}
   (fn [sys]
    (let [t0 (System/currentTimeMillis)
        [a b] (spawn-ready! sys "problems" "dies")
        r1 @(c/submit! sys a (str "(/ 1 0) (defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)) (sq \"x\")"
                                 " (my.fx/call :file {:path \"/nonexistent-d2/x\" :text \"x\"})"))
        r2 @(c/submit! sys a "(Thread/sleep 2500) :awake")
        _ (a/>!! (:back sys) {:op :writer :tx-count 1 :batch-ms 1 :n-tx "not a number" :commits 1})
        r3 @(c/submit! sys b "(spit \"/dev/stderr\" \"D2 boom: the agent writes its last words\\n\" :append true) (seon.lib/c-exit 3)")
        _ (doseq [tx (seon.fixtures/program-txs @(:conn sys) problem-specs)] @(c/transact-main! sys tx))
        ds (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 1})
                 (remove #(= :merge (:seon.task/kind %)) (seon.fixtures/decls problem-specs 'problems nil)))
        _ (my.root/create! sys ds)
        _ (my.root/run! sys ["problems/p1" "problems/p2"])
        _ (seon.e2e/await-db sys #(let [p (seon.problems/report %)]
                                    (and (some (fn [r] (= 'app.proofs/p1-test (:test r))) (get-in p [:classes :red :rows]))
                                         (pos? (get-in p [:classes :cost :n])) (pos? (get-in p [:classes :platform :n]))
                                         (some (fn [r] (= :seon.agent/died (:type r))) (get-in p [:classes :agent :rows])))))
        p (my.root/problems sys)
        rows (fn [k] (get-in p [:classes k :rows]))
        types (fn [k] (set (map :type (rows k))))
        died (first (filter #(= :seon.agent/died (:type %)) (rows :agent)))
        red (first (filter #(= 'app.proofs/p1-test (:test %)) (rows :red)))
        cost-row (first (rows :cost))]
      {:report {:replies (mapv short-items [r1 r2 r3])
                :counts (into (sorted-map) (for [[k v] (:classes p)] [k (:n v)]))
                :died (select-keys (:example died) [:exit :stderr :last-op])
                :red (select-keys red [:attempt :test :assertions :evidence])
                :cost (select-keys cost-row [:attempt :test :verdict])
                :ms (- (System/currentTimeMillis) t0)}
       :checks {:eval (every? (types :eval) [:seon.agent/eval-error :seon.instrument/contract-error])
                :effect (contains? (types :effect) :seon.fx/io-error)
                :platform (contains? (types :platform) :seon.root/proc-defect)
                :stalled (contains? (types :agent) :seon.agent/stalled)
                :died (and died (= {:exit 3} (get-in died [:example :exit])) (re-find #"D2 boom" (str (get-in died [:example :stderr]))))
                :red (and red (seq (:assertions red)) (= ['app.proofs/p1-test] (:proofs red)))
                :cost (and cost-row (false? (get-in cost-row [:verdict :ok?])) (seq (:rows cost-row)))}}))))

;; ---- the mg/check gate (v1's survey of ~/src/seon, 2026-10-20) ------------------

(def ^:private pure-nss
  "The namespaces whose contracted functions are pure functions of plain data: mg/check
  calls them with generated arguments. Every other function touches a host object (a
  socket, a process, the store) and is never called with random arguments: a generated pid
  handed to kill(2) is a real signal."
  '#{seon.merge seon.reconcile seon.template seon.proofs seon.task seon.step seon.ref})

(def ^:private pure-fns
  "Pure functions in namespaces that are otherwise host code (seon.lib binds libc: never
  generate its arguments)."
  '#{seon.lib/percentile seon.lib/r2 seon.lib/summ seon.lib/push-window seon.lib/p50
     seon.cost/work seon.cost/exponent seon.cost/per-unit seon.cost/expected seon.cost/judge seon.profile/top})

(defn- fn-without-gen
  "The [:fn ...] schemas without a :gen property in form f, anywhere in it."
  [f] (filter #(and (vector? %) (= :fn (first %)) (not (:gen (when (map? (second %)) (second %))))) (tree-seq coll? seq f)))

(defn mg-check
  "The mg/check gate: no [:fn pred] schema without :gen anywhere in the registry or a
  contract, and every contract whose arguments generate and whose namespace is pure passes
  malli.generator/check (100 runs each). Prints the counts and the contracts it checked.
  With shard \"I/K\" (0 <= I < K) it checks only the
  contracted vars at positions I, I+K, ... of the platform's contracted vars sorted by
  symbol (the registry's own forms in shard 0), so K shards in K processes check exactly
  what one run does. By var, not namespace: seon.reconcile alone holds 36 of the 82
  checked contracts, so a namespace split cannot go below its share."
  [& [shard]]
  (run-check "MG-CHECK" nil
   (fn [_]
    (let [[i k] (if shard (mapv #(Long/parseLong %) (clojure.string/split shard #"/")) [0 1])
        vs (keep-indexed #(when (= i (mod %1 k)) %2)
                         (sort-by symbol (for [n (platform-nss) v (vals (ns-interns n)) :when (contract/contract v)] v)))
        no-gen (vec (concat (when (zero? i) (for [[k f] contract/forms :when (seq (fn-without-gen f))] k))
                            (for [v vs :when (seq (fn-without-gen (contract/contract v)))] (symbol v))))
        ;; its arguments generate (every arity's input)
        generates? (fn [v] (try (let [s (m/deref (contract/schema (contract/contract v)))]
                                  (every? #(some? (mg/generator (:input (m/-function-info %)) {:registry contract/registry}))
                                          (m/-function-schema-arities s)))
                                (catch Throwable _ false)))
        ;; a proc's step-fn is tested as a plain function on its own states (seon.procs-test):
        ;; its contract accepts any map as state
        pure (filter #(and (or (pure-nss (ns-name (:ns (meta %)))) (pure-fns (symbol %)))
                           (not= :seon.step/step-fn (contract/contract %)) (generates? %)) vs)
        checked (for [v pure
                      :let [t0 (profile/now-ns)
                            r (try (mg/check (m/deref (contract/schema (contract/contract v))) @v {:registry contract/registry :num-tests 100 :seed 42})
                               (catch Throwable e {:smallest :threw :seed (ex-message e)}))]]
                  [(symbol v) r (lib/r2 (profile/ms-since t0))])
        ;; malli could not generate an [:and ...] argument (such-that): not generated, not failed
        such-that (set (for [[sym r] checked :when (= ":malli.generator/such-that-failure" (:seed r))] sym))
        checked (remove (comp such-that first) checked)
        failed (vec (for [[sym r] checked :when (some? r)]
                      [sym (if (= :threw (:smallest r)) r
                             (let [c (:check (first (:errors r)))]
                               {:smallest (:smallest c) :problem (some-> (:malli.core/result c) ex-message)}))]))]
      {:report {:shard [i k] :contracts (count vs) :fn-without-gen no-gen
                :generated-and-checked (count checked) :failed failed
                :checked (vec (sort (map first checked))) :ms (into (sorted-map) (map (juxt first #(nth % 2))) checked) :such-that (vec (sort such-that))
                :not-generated (count (remove generates? vs))}
       :checks {:every-fn-schema-generates (empty? no-gen) :every-pure-contract-holds (empty? failed)}}))))

;; ---- robustness ---------------------------------------------------------------------

(defn robust []
  (run-check "ROBUST" {:datoms 2000}
   (fn [sys]
    (let [[a b x] (spawn-ready! sys "exits" "misbehaves" "tests misbehave")
        ev (c/events sys)
        r1 @(c/submit! sys a "(+ 1 1) (System/exit 3) (+ 2 2)")
        _ (await-agents ev [a] #{:dead})
        r2 @(c/submit! sys b "(transact! [{:no/such-attr 1}])")
        r3 @(c/submit! sys b "(apply str (repeat 100000 \"x\")) (range)")
        r4 @(c/submit! sys b "(+ 1")
        _ @(c/submit! sys x "(ns app.spin (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest spins (loop [] (recur)))")
        t5 @(c/run-tests! sys x 1000000000)
        r5 @(c/submit! sys x "(+ 1 1)")
        _ @(c/submit! sys x "(in-ns 'app.spin) (ns-unmap 'app.spin 'spins) (deftest exits (System/exit 7))")
        t6 @(c/run-tests! sys x)
        r6 @(c/submit! sys x "(+ 1 2)")
        r9 @(c/submit! sys b "(count (vec (range 300000000))) (+ 5 5)")
        r11 @(c/submit! sys b "(def kept 7) (loop [] (recur)) (+ kept 1)" {:ticks 1000000000})
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
        r8 @(c/submit! sys y "(count (q '[:find ?v :where [?v :var/name]])) (loop [] (recur)) :after" {:ticks 1000000000})]
      {:report {:exit [(short-items r1) (select-keys (get-in (c/state sys) [:agents a]) [:status :exit])]
                :refused (short-items r2) :printed-chars (mapv #(count (:printed %)) r3) :read-error (short-items r4)
                :spin (select-keys t5 [:error :reports :ms]) :test-exits (select-keys t6 [:error :reports :exit])
                :oom (short-items r9) :interrupt (short-items r12) :ticks (short-items r11)
                :out [(:out (first r10)) (:reports t10)] :fork-server [(:fork-server (c/state sys)) (short-items r7) (short-items r8)]}
       :checks {"agent calling System/exit mid-request dies alone, named by its exit status"
                (and (= :seon.agent/died (:seon.error/type (:err (last r1)))) (= {:exit 3} (:exit (:err (last r1))))
                     (= :dead (get-in (c/state sys) [:agents a :status])))
                "a refused transaction is an :err item" (and (= :err (:tag (first r2))) (re-find #"refused" (:printed (first r2))))
                "huge and infinite values print within the printer's budget" (and (< (count (:printed (first r3))) 25000) (< (count (:printed (second r3))) 25000))
                "a read error is one :err item" (= :read (get-in (first r4) [:err :phase]))
                "a test that never ends stops at its tick budget, naming the test; the agent lives"
                (and (= 1 (:error t5)) (= "2" (:printed (first r5)))
                     (= [:seon.test/ticks-spent "#'app.spin/spins"] ((juxt :seon.error/type :var) (first (:reports t5)))))
                "a test that kills its process is an error; the agent lives" (and (= 1 (:error t6)) (= "3" (:printed (first r6))))
                "an agent past its heap bound gets OutOfMemoryError as an :err item and lives" (and (= :err (:tag (first r9))) (= "10" (:printed (second r9))))
                ;; nREPL's model (owner's choice): an interrupt stops the request; the agent goes on
                "root's interrupt! stops the running request; the agent's next request runs"
                (and (= "interrupted by root" (:printed (first r12))) (= 1 (count r12)) (= ":next" (:printed (first r12b))))
                "a form past its tick budget is stopped, a typed error naming its ticks; the agent keeps its state and goes on"
                (and (= :seon.repl/ticks-spent (get-in (second r11) [:err :seon.error/type]))
                     (<= 1000000000 (get-in (second r11) [:err :ticks])) (= "8" (:printed (nth r11 2))))
                "printed output comes back as :out; a busy thread blocks the test fork instead of breaking it"
                (and (= "hello from :a3\n" (:out (first r10))) (= 1 (:error t10)) (re-find #"Chez threads active" (str (:message (first (:reports t10))))))
                "the fork server dies: agents keep working, a new server forks the next agent (whose tick budget works)"
                (and (= "7" (:printed (first r7))) (= "360" (:printed (first r8)))
                     (= :seon.repl/ticks-spent (get-in (second r8) [:err :seon.error/type])) (= ":after" (:printed (nth r8 2))))}}))))

(defn thread-diff
  "Where measured threads (seon.root/threads) differ from declared ones
  (seon.root/declared-threads): {} when they are exactly what is declared, else each
  differing name with [declared measured], :gc-sweepers likewise, and :os when the OS
  counts threads beyond the roster and the sweepers."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [decl got]
  (let [names (into (set (keys (:roster decl))) (keys (:roster got)))
        roster (into {} (for [n names :let [d (get-in decl [:roster n] 0) g (get-in got [:roster n] 0)] :when (not= d g)] [n [d g]]))
        expect-os (+ (reduce + 0 (vals (:roster got))) (:gc-sweepers got))]
    (cond-> roster
      (not= (:gc-sweepers decl) (:gc-sweepers got)) (assoc :gc-sweepers [(:gc-sweepers decl) (:gc-sweepers got)])
      (not= expect-os (:os got)) (assoc :os [expect-os (:os got)]))))

;; ---- scale ------------------------------------------------------------------------

(defn scale [n]
  (run-check "SCALE" {:datoms 2000}
   (fn [sys]
    (let [mem0 (lib/sysmem)
        th-boot (thread-diff (c/declared-threads sys) (c/threads))
        t0 (profile/now-ns)
        ids (ready! sys #(vec (pmap (fn [i] (c/spawn! sys {:task (str "scale " i)})) (range n))))
        t-ready (profile/ms-since t0)
        th-ready (thread-diff (c/declared-threads sys) (c/threads))
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
                 {:id id :ok (and (every? #(= :ret (:tag %)) r) (= 1 (:pass tr)) (zero? (+ (:fail tr) (:error tr))))
                  :eval-ms (/ (- t2 t1) 1e6) :tests-ms (/ (- t3 t2) 1e6)}))
        results (vec (pmap work ids))
        t-done (profile/ms-since t0)
        th-work (thread-diff (c/declared-threads sys) (c/threads))
        st (c/state sys)
        mem1 (lib/sysmem)
        agent-mb (keep #(get-in st [:agents % :mem-mb]) ids)
        merged (mapv (fn [id] @(c/merge-main! sys id) @(c/merge! sys id)) (take 10 ids)) ; as a merge task does: main first
        notes (count (main-q sys '[:find ?n :where [?n :note/text]]))
        fs (c/fs-call! sys {:op :stats})
        ;; kill! marks :killed at once; the :killed event says root has retired the process
        ev (c/events sys)
        _ (doseq [id ids] (c/kill! sys id))
        _ (await-agents ev ids #{:killed})
        mem2 (do (Thread/sleep (seon.config/value :seon.config.check/pages-ms)) (lib/sysmem))
        th-end (thread-diff (c/declared-threads sys) (c/threads))
        ;; the target machine (a 16 GB M1, owner 2026-10-22): root, the fork server and the default
        ;; number of agents at once fit :seon.config.machine/memory-mb, each agent charged what the
        ;; system's free memory dropped per agent (its own pages, its page tables, the kernel's share)
        per-agent (/ (- (:free-mb mem0) (:free-mb mem1)) n)
        need (+ (lib/real-mb) (:mem-mb fs) (* (seon.config/value :seon.config.agent/max-running) per-agent))
        budget (seon.config/value :seon.config.machine/memory-mb)]
      {:report {:agents n :all-ok (count (filter :ok results))
                :spawn->all-ready-ms (lib/r2 t-ready) :end-to-end-ms (lib/r2 t-done)
                :start-ms (lib/summ (keep #(get-in st [:agents % :forked-ms]) ids))
                :server-fork-ms-p50 (get-in st [:fork-server :fork-ms-p50])
                :eval+transact-ms (lib/summ (map :eval-ms results)) :tests-ms (lib/summ (map :tests-ms results))
                :agent-real-mb (lib/summ agent-mb) :agent-real-mb-sum (lib/r2 (reduce + agent-mb))
                :system-free-drop-mb-per-agent (lib/r2 per-agent)
                :system-free-returned-mb-per-agent (lib/r2 (/ (- (:free-mb mem2) (:free-mb mem1)) n))
                :control-real-mb (lib/r2 (lib/real-mb)) :fork-server (select-keys fs [:mem-mb :os-threads :active-threads :mismatch :datoms])
                :writer (:writer st) :merged (count (filter :merged? merged)) :main-notes notes
                :all-ended (every? #(get-in (c/state sys) [:agents % :ended-ms]) ids)
                :gc (lib/gc-stats) :gc-sweepers (lib/gc-sweepers! -1)
                :memory-at-max-running {:agents (seon.config/value :seon.config.agent/max-running) :need-mb (lib/r2 need) :budget-mb budget}
                :root-threads {:declared (c/declared-threads sys) :os (lib/nthreads)
                               :diff {:boot th-boot :all-ready th-ready :after-work th-work :after-kill th-end}}}
       ;; root's threads exactly as declared once agents run (at boot Jolt's io poller has not
       ;; started yet: it starts at the first socket wait, an agent's)
       :checks {:every-eval-and-test-passed (every? :ok results) :ten-merges-went-in (every? :merged? merged)
                :root-threads-as-declared (every? empty? [th-ready th-work th-end]) :fits-the-machine (<= need budget)}}))))

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
    (when-not test-child (out! "NO TEST CHILD " (pr-str forked)))
    (lib/c-exit (if test-child 0 1))))

;; ---- durability: the whole system restarts and recovers ------------------------------

(defn- dir-bytes [path] (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (java.io.File. path))))))

(defn- main-data-datoms
  "Main's datoms but root's own facts (:seon.root/*: its boot, its quiescence report, its
  collection record) and every transaction entity's own datoms (:db/txInstant, e = tx): a
  restarted root writes its facts anew in transactions of its own (boot, quiescent, collected:
  three :db/txInstant datoms more), so what a restart must give back exactly is the rest."
  [sys]
  (count (remove #(or (= "seon.root" (namespace (:a %))) (= (:e %) (:tx %))) (d/datoms @(:conn sys) :eavt))))

(defn durable-write
  "Phase 1 (a process of its own): boot on main branch nm of the one store (made from its
  seed), then
  a merges a new f1 (gated), b commits unmerged facts and a recorded defn on its branch,
  c's clone commits on a branch of a branch. Root then exits WITHOUT stop! (SIGKILL of
  itself): nothing but the store survives."
  [nm]
  (run-check "DURABLE-WRITE" {:main (keyword nm)}
   (fn [sys]
    (let [path (str (seon.db/agents-dir (:cfg sys)) "/durable")
        [a b k] (spawn-ready! sys {:task "make f1 multiply" :branch :dur-a} {:task "unmerged work" :branch :dur-b} {:task "parent" :branch :dur-c})
        ra (p1 sys a "(in-ns 'app.m0) (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (* x 10)) (deftest f1-test (is (= 20 (f1 2)))) (f1 2)")
        m @(c/merge! sys a)
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
        expect {:main-datoms (main-data-datoms sys) :main-t (get-in st [:main :t])}]
      (spit (str path ".expect.edn") (pr-str expect))
      {:report {:store (:store st) :a ra :merge (dissoc m :ops) :b rb :clone [(:id cl) rc] :expect expect :store-bytes (dir-bytes (:store-path sys))}
       :checks {:merged (:merged? m)} :then :kill}))))

(defn durable-read
  "Phase 2 (a new process): reopen the same store on the same main and check main, the
  program, the branches and agents' committed work came back; resume b and merge it now."
  [nm]
  (run-check "DURABLE" {:main (keyword nm)}
   (fn [sys]
    (let [expect (read-string (slurp (str (seon.db/agents-dir (:cfg sys)) "/durable.expect.edn")))
        st (c/state sys {:datoms true})
        md (main-data-datoms sys)
        [n rb rc] (spawn-ready! sys "born after the restart" {:task "resume b" :branch :dur-b} {:task "resume the clone" :branch :dur-clone})
        r-n (p1 sys n "(app.m0/f1 2) (count (q '[:find ?n :where [?n :note/text]]))")
        r-b (p1 sys rb "(app.m1/f2 1) (q '[:find [?t ...] :where [?n :note/text ?t]]) (app.m0/f1 2)")
        ;; its recorded f2-test; main's later f1-test is not on its branch
        t-b @(c/run-tests! sys rb)
        r-c (p1 sys rc "(sort (q '[:find [?t ...] :where [?n :note/text ?t]]))")
        mmb @(c/merge-main! sys rb)
        mb @(c/merge! sys rb)
        [n2] (spawn-ready! sys "after merging b")
        r-n2 (p1 sys n2 "(app.m1/f2 1) (q '[:find [?t ...] :where [?n :note/agent :b] [?n :note/text ?t]]) (app.m0/f1 2)")]
      {:report {:store (:store st) :main (:main st) :main-data-datoms {:phase-1 (:main-datoms expect) :recovered md}
                :born-after r-n :resumed-b r-b :b-tests (select-keys t-b [:pass :fail :error]) :resumed-clone r-c
                :merge-b [(select-keys mmb [:ok :applied]) (dissoc mb :ops)] :after-merging-b r-n2}
       :checks {:main-recovered (and (= (:main-datoms expect) md) (:recovered? (:store st)))
                ;; phase 1's root was SIGKILLed: it left no stop fact, so this boot found its end :unclean
                :the-killed-root-ended-unclean (= :unclean (:cause (:seon.root/previous-end (d/pull @(:conn sys) [:seon.root/previous-end] [:seon.root/id :root]))))
                :branches-recovered (= #{:dur-a :dur-b :dur-c :dur-clone} (set (get-in st [:store :recovered-branches])))
                :main-runs-the-merged-f1 (= "20" (ffirst r-n))
                :b-resumes-its-work (and (= "\"b:1\"" (ffirst r-b)) (re-find #"unmerged" (str (second r-b))))
                :b's-tests-pass (and (zero? (+ (:fail t-b) (:error t-b))) (pos? (:pass t-b)))
                :the-clone-resumes (re-find #"clone's note" (str r-c))
                :b-merges-now (and (:merged? mb) (= "\"b:1\"" (ffirst r-n2)) (= "20" (first (nth r-n2 2))))}}))))

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

(defn- cycles
  "n cycles of start! -> 3 agents evaluate -> stop! in this one process: per cycle, root's
  threads against what it declares (thread-diff, {} when exact) while running and after its
  stop!, the stop's answer and any process it left."
  [n]
  (vec (for [i (range n)]
         (let [t0 (profile/now-ns)
               sys (c/start! {:datoms 2000})
               boot-ms (profile/ms-since t0)
               ids (apply spawn-ready! sys (map #(str "lc " %) (range 3)))
               ok? (every? #(= "4" (:printed (first @(c/submit! sys % "(+ 2 2)")))) ids)
               health (mapv #(let [p (ping sys %)] (:status p p)) [:api :writer :fork-server :flusher :reconciler])
               running (thread-diff (c/declared-threads sys) (c/threads))
               pids (vec (cons (get-in (c/state sys) [:fork-server :pid]) (keep #(get-in (c/state sys) [:agents % :pid]) ids)))
               st (c/stop! sys)]
           {:cycle i :ok ok? :boot-ms (lib/r2 boot-ms) :stop st :left (vec (filter alive? pids))
            :health (set health)
            :running running :stopped (thread-diff (c/declared-threads) (c/threads))}))))

(defn- exact? [row] (and (empty? (:running row)) (empty? (:stopped row))))

(defn lifecycle
  "n cycles (cycles): each stop! must answer with every proc exited (:graph true) and no
  agent or fork-server process left, and root's threads must be exactly what it declares
  (seon.root/declared-threads) while running and after every stop!, by name and by the OS's
  count. Then pause/resume: a paused agent's request is held (no answer), and answered after
  resume, in order."
  [n]
  (let [rows (cycles n)]
    (run-check "LIFECYCLE" {:datoms 2000}
     (fn [sys]
      (let [[a] (spawn-ready! sys "paused")
            _ (c/pause-agent! sys a)
            ;; the proc's control channel is FIFO: this ping is taken after the pause
            paused-health (ping sys a)
            p1 (c/submit! sys a "(def x 1) x")
            p2 (c/submit! sys a "(inc x)")
            ;; the measurement: no answer within the window while paused
            held (deref p1 (seon.config/value :seon.config.check/hold-ms) :held)
            held-state (= :paused (:status (ping sys a)))
            _ (c/resume-agent! sys a)
            r1 @p1 r2 @p2]
        {:report {:threads {:declared-running (c/declared-threads sys) :declared-stopped (c/declared-threads)}
                  :cycles (mapv #(select-keys % [:cycle :boot-ms :running :stopped :left :health]) rows)
                  :stop-ms (lib/summ (map #(get-in % [:stop :ms]) rows))
                  :gc (lib/gc-stats) :gc-sweepers (lib/gc-sweepers! -1)}
         :checks {:every-stop-exited (every? #(and (true? (get-in % [:stop :graph])) (zero? (get-in % [:stop :agents-left])) (empty? (:left %))) rows)
                  :every-cycle-worked (every? #(and (:ok %) (= #{:running} (:health %))) rows)
                  :threads-declared (every? exact? rows)
                  :pause-holds (and (= :held held) held-state (= :paused (:status paused-health)))
                  :resume-releases-in-order (and (= "1" (:printed (second r1))) (= "2" (:printed (first r2))))}})))))

(defn threads-declared
  "lifecycle's cycles alone; the last line is the EDN {:running [diff ..] :stopped [diff ..]}
  that seon.root-test/root-threads-are-what-root-declares reads."
  [n]
  (let [rows (cycles n)]
    (out! (pr-str {:running (mapv :running rows) :stopped (mapv :stopped rows)}))
    (lib/c-exit (if (every? exact? rows) 0 1))))

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
                :evals {:done (count (filter #(= ":done" (:printed (second %))) evr))
                        :other (frequencies (map #(if (vector? %) (:printed (last %)) %) evr))} :left (vec left)}
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
  2. A saturated writer: the writer proc is paused while n agents transact (their own
     branches: all answered) and root writes main n times (held). The writer's
     in-port (:seon.config.root/writer-buf) fills, then the agent procs' puts park; resume completes
     every transaction. Root's memory before/after is reported (a parked put is a parked
     fiber)."
  [n]
  (run-check "BACKPRESSURE" {:datoms 2000}
   (fn [sys]
    (let [[a] (spawn-ready! sys "flood")
        flood "(dotimes [i 20000] (#'seon.agent/send! {:op :noise :i i})) :flooded"
        ev (c/events sys)
        p (c/submit! sys a flood)
        ;; the flood began (the agent said so); then what arrives while paused is the measurement
        _ (await-event ev (started a flood))
        _ (c/pause-agent! sys a)
        ;; what its proc had taken when the pause arrived (the ping after it is FIFO), and after the window
        p0 (ping sys a)
        _ (Thread/sleep (seon.config/value :seon.config.check/hold-ms))
        p1 (ping sys a)
        [n0 n1] (map #(get-in % [:state :noise]) [p0 p1])
        busy (get-in (c/state sys) [:agents a :status])
        t0 (profile/now-ns)
        _ (c/resume-agent! sys a)
        r @p
        drain-ms (profile/ms-since t0)
        st (c/stats sys)
        ids (ready! sys #(vec (pmap (fn [i] (c/spawn! sys {:task (str "bp " i)})) (range n))))
        mb0 (lib/r2 (lib/real-mb))
        _ (flow/pause-proc (:graph sys) :writer)
        ;; agents own their branches' writes: a paused writer does not hold them
        agents-ok (count (filter #(= ":ok" (:printed (second %)))
                                 (mapv #(deref (c/submit! sys % "(transact! [{:note/text \"bp\" :note/agent :bp}]) :ok")) ids)))
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
      {:report {:flood {:arrived (:noise st) :ordered (:noise-ordered st) :reply (:printed (second r)) :taken-before-the-pause n0 :taken-while-paused (when (and n0 n1) (- n1 n0)) :status-while-paused busy
                        :blocked (filterv :blocked [p0 p1]) :drain-ms (lib/r2 drain-ms)}
                :writer {:agents n :writer-buf (seon.config/value :seon.config.root/writer-buf) :answered-while-paused answered-while-paused
                         :root-real-mb {:before mb0 :writer-saturated mb1} :resume->all-done-ms (lib/r2 resume-ms) :ok ok
                         :agents-ok agents-ok :not-ok (frequencies (remove :ok rs))}}
       :checks {:its-proc-answered (not-any? :blocked [p0 p1])
                :ring-pushes-back (and n0 n1 (= n0 n1) (< n0 20000) (= :busy busy))
                :flood-all-arrive-in-order (and (= 20000 (:noise st)) (true? (:noise-ordered st)) (= ":flooded" (:printed (second r))))
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
        ev (fn [code] (let [r @(c/submit! sys a code)] (last r)))
        ;; per call, timed inside the agent: (loop n) of the form, µs each
        per (fn [form k] (let [code (str "(let [t0 (seon.profile/now-ns)] (dotimes [i " k "] " form ") (/ (- (seon.profile/now-ns) t0) " k " 1000.0))")]
                           (dotimes [_ 3] (ev code))
                           (lib/summ (vec (for [_ (range 7)] (parse-double (:printed (ev code))))))))
        ok1 (ev "(my.fx/call :echo {:x 1})")
        r {:echo-once (:printed ok1)
           :plain-us (per "(identity {:x i})" n)
           :echo-us (per "(my.fx/call :echo {:x i})" n)
           :floor-us (per "(my.fx/call :nope {})" n)
           :transact-us (per "(transact! [{:note/text \"r\"}])" (quot n 4))
           :spit-us (per (str "(spit \"" dir "/a.txt\" (str \"text \" i))") n)
           :file-us (per (str "(my.fx/call :file {:path \"" dir "/b.txt\" :text (str \"text \" i)})") n)
           :send-us (per "(my.fx/send :echo {:x i})" n)
           :inbox (:printed (ev "(count (filter #(= :fx (:from %)) (messages)))"))
           :unknown (:printed (ev "(:seon.error/type (my.fx/call :nope {}))"))
           :test-fork (do (ev "(ns app.fx (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest fx-in-test (is (= :seon.fx/in-test (:seon.error/type (my.fx/call :echo {:x 1}))))
                                (is (= {:value 7} (binding [seon.agent/*fx-stub* (fn [_ _] {:value 7})] (my.fx/call :echo {:x 1})))))")
                          (select-keys @(c/run-tests! sys a) [:pass :fail :error]))
           :file-text (slurp (str dir "/b.txt"))}
        bdb (:db @(c/branch-db sys :fx1))
        recs (d/q '[:find ?k (count ?e) :where [?e :seon.fx/kind ?k]] bdb)
        q (d/q '[:find [?q ...] :where [?e :seon.fx/kind :echo] [?e :seon.fx/queue-us ?q]] bdb)
        us (d/q '[:find [?q ...] :where [?e :seon.fx/kind :echo] [?e :seon.fx/us ?q]] bdb)
        echo-ping (ping sys :fx/echo)]
      (clojure.java.shell/sh "rm" "-rf" dir)
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
          [n h own] (some-> (first r) :printed clojure.edn/read-string)]
      {:report {:keys (count main) :agent-reply (:printed (first r))}
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
        expect (into #{:api :writer :fork-server :flusher :reconciler :render :web :fx/echo :fx.echo/0 :fx/file :fx.file/0 :fx/source :fx.source/0 :fx/git :fx.git/0 :fx/deps :fx.deps/0 :fx/build :fx.build/0 :fx/model}
                     (for [i (range (seon.config/value :seon.config.ai/max-in-flight))] (keyword "fx.model" (str i))))
        dir (str (:agents-dir (:store (c/state sys))) "/system") ; this root's own run dir
        _ (.mkdirs (java.io.File. dir))
        decl (clojure.edn/read-string (slurp (seon.config/value :seon.config.root/system-path)))
        write! (fn [nm d] (let [f (str dir "/" nm ".edn")] (spit f (pr-str d)) f))
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
      (clojure.java.shell/sh "rm" "-rf" dir)
      {:report {:no-answer (vec (remove booted expect)) :extra (vec (remove expect booted))
                :add (dissoc r1 :kept) :add2 (dissoc r2 :kept) :queued-kept got :remove (dissoc r3 :kept)}
       :checks {:boot-set (= expect booted)
                :add-starts-only-it (and (= #{:probe} (set (:started r1))) (empty? (:stopped r1)))
                :add2-starts-only-it (and (= #{:probe2} (set (:started r2))) (empty? (:stopped r2)))
                :queued-kept (= 3 got)
                :remove-stops-only-it (and (= #{:probe2} (set (:stopped r3))) (empty? (:started r3)))
                :running-set-after (= (conj expect :probe) after)}}))))

(defn root-serve
  "A root with its server on 127.0.0.1:(:seon.config.root/entrance-port) (seon.server),
  until stdin closes (bin/root stop) or SIGTERM, SIGINT or SIGHUP (Jolt's shutdown hooks):
  what an outside agent's MCP relay (bin/mcp) talks to. Its end is a fact on main
  naming that cause (seon.root/stop!). A checkout whose config names no server port (0)
  serves none, and says so."
  []
  (let [port (seon.config/value :seon.config.root/entrance-port)
        _ (when (zero? port)
            (out! "ROOT REFUSED: :seon.config.root/entrance-port is 0 (no server); bin/worktree --ports writes config/local.edn with this checkout's ports")
            (lib/c-exit 1))
        sys (c/start! {})]
    ;; the first cause stops root, once; a signal's hook waits for that stop, then Jolt exits
    (let [cause (promise) stopped (promise)
          end! (fn [why] (deliver cause why)
                 (when (= why @cause) (out! "ROOT STOPPED " (pr-str (select-keys (c/stop! sys why) [:cause :ms :stop-fact-refused]))) (deliver stopped true))
                 @stopped)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (end! :signal))))
      (seon.server/serve! sys port)
      (out! "ROOT LISTENING " port)
      (loop [] (when (read-line) (recur)))
      (end! :stdin-eof))
    (lib/c-exit 0)))

(defn- eval-records
  "The evaluation records on db of agent id: [{:form :origin ...}]."
  [db id]
  (vec (d/q '[:find [?r ...] :in $ ?id :where [?a :seon.agent/id ?id] [?e :seon.cluster.eval/agent ?a] [?e :seon.cluster.eval/record ?r]] db id)))

(defn await-records
  "What (read) answers once (enough? it) holds, read again at each :commit event of agent a
  on ev (a tap taken before what writes them): an agent's records are applied ahead of its
  commit (seon.agent/transact!), so its branch shows them at its commit, not before."
  [ev a read enough?]
  (loop [] (let [r (read)] (if (enough? r) r (do (await-event ev #(and (= :commit (:type %)) (= a (:agent %)))) (recur))))))

(defn mcp-proof
  "The MCP phase's proof: an outside client (a separate process: bin/mcp PORT, the stdio
  relay, fed JSON-RPC on stdin) lists agents, evaluates in a running agent and as root, and
  both evaluations are recorded with origin :outside (the agent's on its branch, root's on
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
        ;; the agent's record is on its branch at its commit (await-records); answers 2 when
        ;; recorded, else waits on the next commit (the eval failed: its text check says so)
        mine (if (clojure.string/includes? (str (text 4)) "42")
               (await-records ev a #(filter (fn [r] (= :outside (:origin r))) (eval-records (:db @(c/branch-db sys :mcp1)) a)) #(= 2 (count %)))
               [])
        _ (a/untap (:events-mult sys) ev)
        roots (filter #(= :outside (:origin %)) (eval-records @(:conn sys) :root))
        bound (seon.config/value :seon.config.machine/relay-mb)]
      {:report {:tools (count (get-in answers [2 "result" "tools"]))
                :agents-text (text 3) :agent-eval (text 4) :root-eval (text 5)
                :agent-records (mapv #(select-keys % [:form :shown :origin]) mine)
                :root-records (mapv #(select-keys % [:form :shown :origin]) roots)
                :client-ms (lib/r2 ms) :exit (:exit r)
                :relay-peak-mb (some-> (peak-mb r) lib/r2) :relay-mb-bound bound
                :root-down (mapv #(get-in % ["error" "message"]) (lines down)) :root-down-exit (:exit down)}
       :checks {:five-tools (= 5 (count (get-in answers [2 "result" "tools"])))
                :notification-unanswered (= #{1 2 3 4 5} (set (keys answers)))
                :both-recorded-outside (and (= 2 (count mine)) (= 1 (count roots)))
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
  server, the agent and the clone each have one thread."
  []
  (run-check "THREADS" {:datoms 2000}
   (fn [sys]
    (let [[a] (spawn-ready! sys {:task "threads" :branch :threads1})
          _ @(c/submit! sys a "(ns app.one (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest t-one (is (= 2 (inc 1)))) (dotimes [i 200] (vec (range 10000))) (System/gc) :ok")
          tests (select-keys @(c/run-tests! sys a) [:pass :fail :error])
          cl (:id (ready! sys #(deref (c/clone! sys a {}))))
          cl-eval (:printed (last @(c/submit! sys cl "(inc 41)")))
          st (c/state sys)
          res {:fork-server (os-threads (get-in st [:fork-server :pid])) :agent (os-threads (get-in st [:agents a :pid]))
               :clone (os-threads (get-in st [:agents cl :pid]))}]
      {:report (assoc res :tests tests :clone-eval cl-eval)
       :checks {:each-forking-process-one-thread (= 1 (:fork-server res) (:agent res) (:clone res))
                :its-test-passed (= 1 (:pass tests)) :the-clone-evaluates (= "42" cl-eval)}}))))

(defn lease
  "Run with --set :seon.config.store/eid-lease=50: agent a writes 60 new entities in one transaction, past its
  lease: refused with :seon.db/lease-spent, nothing applied. A refused transaction keeps the
  ids it reserved (Datahike's store counter), so a's lease is spent: its next write is
  refused too, and the record of its evals, which cannot be written on its branch, is
  recorded on main as its problem. Agent b, the next lease, writes 10: its ids lie past a's
  lease, never shared."
  []
  (run-check "LEASE" {}
   (fn [sys]
    (let [[a b] (spawn-ready! sys "past its lease" "the next lease")
        notes (fn [tag n] (str "(transact! (vec (for [i (range " n ")] {:note/text (str \"" tag "\" i)})))"))
        r-past (first @(c/submit! sys a (notes "a" 60)))
        r-count (first @(c/submit! sys a "(count (q '[:find ?n :where [?n :note/text]]))"))
        r-next (first @(c/submit! sys a (notes "a" 1)))
        stats @(c/agent-stats! sys a)
        _ @(c/submit! sys b (notes "b" 10))
        b-min (read-string (:printed (first @(c/submit! sys b "(q '[:find (min ?n) . :where [?n :note/text]])"))))
        ;; parks on main's commits until root has recorded a's unrecorded evals (no deadline)
        lease-q '[:find ?c . :where [?e :seon.error/type :seon.db/lease-spent] [?e :seon.error/count ?c]]
        recorded (d/q lease-q (seon.e2e/await-db sys #(d/q lease-q %)))
        spent? #(and (= :err (:tag %)) (re-find #"entity ids are spent" (str (:printed %))))]
      {:report {:past-its-lease (select-keys r-past [:tag :printed :err]) :notes-after (:printed r-count)
                :next-write (select-keys r-next [:tag :printed]) :ids (:ids stats) :recorded-on-main recorded :b-lowest-id b-min}
       :checks {:past-its-lease-refused (spent? r-past) :nothing-applied (= "0" (:printed r-count)) :next-write-refused (spent? r-next)
                :its-lease-was-50 (= 50 (get-in stats [:ids :of])) :recorded-on-main (pos-int? recorded)
                :used-under-the-lease (< (get-in stats [:ids :used] 0) 50)
                :b's-ids-past-a's-lease (>= b-min (+ (get-in stats [:ids :from]) 50))}}))))

(defn- fork-server-caught-up
  "A FIFO barrier through the fork server: {:op :stats} goes to the server process on the
  socket that carries main's commits (seon.fork-server/fork-server-proc), the process takes
  that socket in order and answers in order (seon.fork-server/main), and its answers come back
  through the :fork-server proc and the api in order. So this answers after every earlier
  commit's :applied has passed through both. Checked, not assumed: :caught-up only when the
  server's :t has reached main's t read before the request; an ordering that changed fails here."
  [sys]
  (let [main-t (:max-tx @(:conn sys))
        fs (c/fs-call! sys {:op :stats})]
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
  answers stepped the fork server and the api inside the window under load).
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
        _ (seon.e2e/await-quiet sys 0)
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
      {:report {:window-ms window-ms :procs (count pids) :steps-at-idle moved :bytes-at-idle (- b1 b0) :agent a :flushed flushed :fork-server fs}
       :checks {:tab-opened opened :tab-settled settled :flush-covered-main (:covered flushed) :fork-server-caught-up (:caught-up fs) :no-step-at-idle (empty? moved) :no-byte-at-idle (= b0 b1)
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
        (doseq [m mains] (clojure.java.shell/sh "rm" "-rf" (seon.db/agents-dir (assoc cfg :branch (keyword m)))))
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
     "robust" (fn [n more] (robust))
     "lifecycle" (fn [n more] (lifecycle (n-or n "10")))
     "threads-declared" (fn [n more] (threads-declared (n-or n "3")))
     "drain" (fn [n more] (drain (n-or n "32")))
     "backpressure" (fn [n more] (backpressure (n-or n "200")))
     "contracts" (fn [n more] (contracts))
     "mg-check" (fn [n more] (mg-check n))
     "e2e" (fn [n more] (lib/c-exit (if (seon.e2e/run n more) 0 1)))
     ;; swarm N M: seon.e2e's swarm row (N tasks over 5 shared vars, max-running M), SWARM OK/FAILED
     "swarm" (fn [n more] (lib/c-exit (if (seon.e2e/run "swarm" [(or n "20") (or (first more) "8")]) 0 1)))
     "instrument" (fn [n more] (instrument))
     "record" (fn [n more] (record))
     "orphan" (fn [n more] (orphan))
     "lease" (fn [n more] (lease))
     "fx" (fn [n more] (fx (n-or n "1000")))
     "system" (fn [n more] (system-check))
     "config" (fn [n more] (config-check))
     "problems" (fn [n more] (problems-check))
     "threads" (fn [n more] (threads-check))
     "root" (fn [n more] (root-serve))
     ;; this checkout's ports as the config resolves them, "SERVER WEB" (bin/ports)
     "ports" (fn [n more] (do (println (seon.config/value :seon.config.root/entrance-port) (seon.config/value :seon.config.web/port)) (lib/c-exit 0)))
     "mcp-proof" (fn [n more] (mcp-proof))
     "hotreload" (fn [n more] (hotreload))
     "idle" (fn [n more] (idle (n-or n "5000")))
     "smoke" (fn [_ _] (smoke)))))

(defn -main [& args]
  ;; --config FILE and --set :k=v are the config's (seon.config reads them from the command
  ;; line); the mode reads the rest
  (let [[mode n & more] (seon.config/without-overrides args)]
    (if-let [f (get modes mode)]
      (f n more)
      ;; a mode that is not one: its verdict, never another mode's
      (do (out! "no mode " (pr-str mode) "; the modes: " (clojure.string/join " " (keys modes)))
          (out! (if mode (clojure.string/upper-case mode) "MODE") " FAILED")
          (lib/c-exit 1)))))
