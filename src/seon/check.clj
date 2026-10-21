(ns seon.check
  "End-to-end checks of seon.root through the same API the dashboard uses (proto/flow
  f2-final proto.smoke). bin/check runs them; target/seon <check> [args] runs one.
  usage: target/seon smoke        3 agents: define, test, transact; merge one; kill one mid-eval
         target/seon scale N      N agents spawned at once, each eval + transact + test"
  (:require [seon.root :as c] [seon.lib :as lib] [datahike.api :as d] [clojure.pprint :refer [pprint]] [clojure.java.shell] [clojure.string] [clojure.core.async.flow :as flow] [seon.db] [seon.reconcile] [seon.contract :as contract] [malli.core :as m] [malli.generator :as mg] [seon.e2e] [seon.system] [seon.web] [seon.render.proc] [seon.fixtures] [my.root] [seon.task] [seon.config] [clojure.edn] [seon.entrance] [seon.mcp] [clojure.data.json] [seon.cost :as cost]))

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
        ;; each agent works in a program namespace of its own: scratch (agent.*) never
        ;; merges, so its tests never judge
        programs {(ids 0) "(ns app.sq (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))
                           (deftest sq-test (is (= 144 (sq 12))))
                           (transact! [{:note/text \"sq is defined\" :note/agent :a1}])
                           (sq 12)"
                  (ids 1) "(ns app.census (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn n-vars {:malli/schema [:=> [:cat] :int]} [] (count (q '[:find ?v :where [?v :var/name]])))
                           (deftest n-vars-test (is (< 360 (n-vars))) (is (some #{'app.census/n-vars} (q '[:find [?v ...] :where [_ :var/name ?v]]))))
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
        t-clone (lib/now-ns)
        cl @(c/clone! sys (ids 2) {:task "a3's clone"})
        _ (out! "clone " (ids 2) " -> " cl " in " (lib/r2 (lib/ms-since t-clone)) " ms; the clone says: "
                (short-items @(c/submit! sys (:id cl) "(my.agent/whoami) (swap! memo update :seen inc) (doc-of 'app.m0/f1)"))
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
    ;; each agent's tests ran and passed; a1 and a3 merged (their tests judging); a2 is refused (its conflict, or the merged doc-test it now fails)
    (let [ok (and (every? (fn [id] (let [t (tests id)] (and (pos? (:pass t)) (zero? (+ (:fail t) (:error t)))))) ids)
                  (:merged? m1) (:merged? m3) (:merged? m3b) (pos? (get-in m1 [:tests :pass] 0))
                  (not (:merged? m2)))]
      (out! (if ok "SMOKE OK" "SMOKE FAILED"))
      (lib/c-exit (if ok 0 1)))))

;; ---- contracts (lane G1, proto/core 9f5100a proto.smoke/contracts) --------------------

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
  plain caller compiled against a contracted function reaches its check. Prints the counts
  and exits 0 only when all hold."
  []
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
                    (catch Throwable e (if (= 'seon.check/probe-callee (:seon.error/operation (ex-data e))) :refused :other)))
        ok (and (= direct :refused) (empty? uncontracted) (every? contract/armed? late)
                (empty? (results :not-refused)) (empty? (results :other)))]
    (pprint {:namespaces (count nss) :contracted (count vs) :checked-at-definition (count at-def)
             :armed-at-boot (vec (sort (map symbol late))) :uncontracted uncontracted
             :wrong-arity {:refused (count (results :refused)) :not-refused (mapv symbol (results :not-refused))
                           :other (mapv symbol (results :other)) :no-bad-arity (mapv symbol (results :no-bad-arity))}
             :direct-linked-caller direct :boot (dissoc boot :uncontracted)
             :guarantees (if contract/on? :on :off)
             :result (if ok :CONTRACTS-OK :CONTRACTS-FAILED)})
    (lib/c-exit (if ok 0 1))))

(defn admit
  "An agent's defn with :malli/schema is armed (a bad call refuses as the error map); one
  without is admitted with a warning that grows with the agent's uncontracted definitions."
  []
  (let [sys (c/start! {:datoms 500})
        a (c/spawn! sys {:task "admit"})
        _ (await-status sys [a] #{:idle} 5000)
        [d1 bad] @(c/submit! sys a "(defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)) (sq \"x\")")
        [u1] @(c/submit! sys a "(defn plain [x] x)")
        [u2] @(c/submit! sys a "(defn plain2 [x] x)")
        ok (and (= :armed (:contract d1)) (= :err (:tag bad)) (re-find #"invalid-input" (str (:printed bad)))
                (= 1 (:count (first (:warnings u1)))) (= 2 (:count (first (:warnings u2)))))]
    (pprint {:armed (select-keys d1 [:printed :contract]) :refused (:printed bad)
             :warnings [(first (:warnings u1)) (dissoc (first (:warnings u2)) :seon.repl/fix)]})
    (out! (if ok "ADMIT OK" "ADMIT FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

(defn record
  "Each eval request is recorded in one transaction on the agent's branch (its evaluations
  on its agent entity, its defs in program namespaces), built at root from the reply; the
  agent's next request sees its own recording."
  []
  (let [sys (c/start! {:datoms 500})
        a (c/spawn! sys {:task "record"})
        _ (await-status sys [a] #{:idle} 5000)
        r1 @(c/submit! sys a "(+ 1 2) (in-ns 'app.m0) (defn f3 [x] (* 3 x)) (/ 1 0)")
        r2 @(c/submit! sys a "(count (my.db/q '[:find ?e :where [?e :seon.cluster.eval/agent ?a] [?a :seon.agent/id :a1]]))
                               (my.db/q '[:find ?s . :where [?e :var/name app.m0/f3] [?e :var/form ?f] [?f :form/src ?s]])")
        ok (and (= 4 (count r1)) (= "4" (:printed (first r2))) (re-find #"f3" (str (:printed (second r2)))))]
    (pprint {:first (mapv #(select-keys % [:tag :printed :record :recorded]) r1) :second (mapv :printed r2)})
    (out! (if ok "RECORD OK" "RECORD FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

;; ---- problems (lane D2): each class provoked, then read back from main ----------------

(defn- until [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true (> (System/currentTimeMillis) end) false :else (do (Thread/sleep 100) (recur))))))

(def ^:private problem-specs
  "Two tasks the stand-in model cannot make green in one turn: p1's proof wants 99 (the
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
  status and stderr, a red proof's assertions and a cost proof's verdict."
  []
  (let [t0 (System/currentTimeMillis)
        sys (c/start! {:datoms 300 :max-running 2 :stall-ms 1000})
        a (c/spawn! sys {:task "problems"})
        b (c/spawn! sys {:task "dies"})
        _ (await-status sys [a b] #{:idle} 10000)
        r1 @(c/submit! sys a (str "(/ 1 0) (defn sq {:malli/schema [:=> [:cat :int] :int]} [x] (* x x)) (sq \"x\")"
                                 " (my.fx/call :file {:path \"/nonexistent-d2/x\" :text \"x\"})"))
        r2 @(c/submit! sys a "(Thread/sleep 2500) :awake")
        _ (clojure.core.async/>!! (:back sys) {:op :writer :tx-count 1 :batch-ms 1 :n-tx "not a number" :commits 1})
        r3 @(c/submit! sys b "(spit \"/dev/stderr\" \"D2 boom: the agent writes its last words\\n\" :append true) (seon.lib/c-exit 3)")
        _ (doseq [tx (seon.fixtures/program-txs @(:conn sys) problem-specs)] @(c/transact-main! sys tx))
        ds (mapv #(assoc % :seon.task/config {:seon.config.agent/max-turns 1})
                 (remove #(= :merge (:seon.task/kind %)) (seon.fixtures/decls problem-specs 'problems nil)))
        _ (my.root/create! sys ds)
        _ (my.root/run! sys ["problems/p1" "problems/p2"])
        settled (until #(let [p (my.root/problems sys)]
                          (and (some (fn [r] (= 'app.proofs/p1-test (:test r))) (get-in p [:classes :red :rows])) (pos? (get-in p [:classes :cost :n]))))
                       120000)
        _ (until #(let [p (my.root/problems sys)] (and (pos? (get-in p [:classes :platform :n])) (some (fn [r] (= :seon.agent/died (:type r))) (get-in p [:classes :agent :rows])))) 10000)
        p (my.root/problems sys)
        rows (fn [k] (get-in p [:classes k :rows]))
        types (fn [k] (set (map :type (rows k))))
        died (first (filter #(= :seon.agent/died (:type %)) (rows :agent)))
        red (first (filter #(= 'app.proofs/p1-test (:test %)) (rows :red)))
        cost-row (first (rows :cost))
        checks {:eval (every? (types :eval) [:seon.agent/eval-error :seon.instrument/contract-error])
                :effect (contains? (types :effect) :seon.fx/io-error)
                :platform (contains? (types :platform) :seon.root/proc-defect)
                :stalled (contains? (types :agent) :seon.agent/stalled)
                :died (and died (= {:exit 3} (get-in died [:example :exit])) (boolean (re-find #"D2 boom" (str (get-in died [:example :stderr])))))
                :red (and red (= 'app.proofs/p1-test (:test red)) (seq (:assertions red)) (= ['app.proofs/p1-test] (:proofs red)))
                :cost (and cost-row (false? (get-in cost-row [:verdict :ok?])) (seq (:rows cost-row)))
                :settled settled}
        ok (every? identity (vals checks))]
    (pprint {:replies (mapv short-items [r1 r2 r3]) :checks checks
             :counts (into (sorted-map) (for [[k v] (:classes p)] [k (:n v)]))
             :died (select-keys (:example died) [:exit :stderr :last-op])
             :red (select-keys red [:attempt :test :assertions :evidence])
             :cost (select-keys cost-row [:attempt :test :verdict])
             :ms (- (System/currentTimeMillis) t0)})
    (out! (if ok "PROBLEMS OK" "PROBLEMS FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

;; ---- the census gate (SEON - Main's census of ~/src/seon, 2026-10-20) ------------------

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

(defn census
  "The census gate: no [:fn pred] schema without :gen anywhere in the registry or a
  contract, and every contract whose arguments generate and whose namespace is pure passes
  malli.generator/check (100 runs each). Prints the counts and the contracts it checked;
  exits 0 only when both hold. With shard \"I/K\" (0 <= I < K) it censuses only the
  contracted vars at positions I, I+K, ... of the platform's contracted vars sorted by
  symbol (the registry's own forms in shard 0), so K shards in K processes check exactly
  what one run does. By var, not namespace: seon.reconcile alone holds 36 of the 82
  checked contracts, so a namespace split cannot go below its share."
  [& [shard]]
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
        ;; its contract admits any map as state
        pure (filter #(and (or (pure-nss (ns-name (:ns (meta %)))) (pure-fns (symbol %)))
                           (not= :seon.step/step-fn (contract/contract %)) (generates? %)) vs)
        checked (for [v pure
                      :let [t0 (System/nanoTime)
                            r (try (mg/check (m/deref (contract/schema (contract/contract v))) @v {:registry contract/registry :num-tests 100 :seed 42})
                               (catch Throwable e {:smallest :threw :seed (ex-message e)}))]]
                  [(symbol v) r (lib/r2 (/ (- (System/nanoTime) t0) 1e6))])
        ;; malli could not generate an [:and ...] argument (such-that): not generated, not failed
        such-that (set (for [[sym r] checked :when (= ":malli.generator/such-that-failure" (:seed r))] sym))
        checked (remove (comp such-that first) checked)
        failed (vec (for [[sym r] checked :when (some? r)]
                      [sym (if (= :threw (:smallest r)) r
                             (let [c (:check (first (:errors r)))]
                               {:smallest (:smallest c) :problem (some-> (:malli.core/result c) ex-message)}))]))
        ok (and (empty? no-gen) (empty? failed))]
    (pprint {:shard [i k] :contracts (count vs) :fn-without-gen no-gen
             :generated-and-checked (count checked) :failed failed
             :checked (vec (sort (map first checked))) :ms (into (sorted-map) (map (juxt first #(nth % 2))) checked) :such-that (vec (sort such-that))
             :not-generated (count (remove generates? vs))
             :result (if ok :CENSUS-OK :CENSUS-FAILED)})
    (lib/c-exit (if ok 0 1))))

;; ---- the program is entities -------------------------------------------------------

(defn program []
  (let [sys (c/start! {:datoms 2000})
        _ (out! "root loaded main's program: " (get-in (c/state sys) [:boot :program]))
        [a b] (mapv #(c/spawn! sys {:task %}) ["make f1 multiply" "bystander"])
        _ (await-status sys [a b] #{:idle} 5000)
        p1 (fn [id forms] (mapv (juxt :printed :recorded) @(c/submit! sys id forms)))
        _ (out! a " changes app.m0/f1 in REPL terms: "
                (p1 a "(app.m0/f1 2) (in-ns 'app.m0) (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (* x 10)) (deftest f1-test (is (= 20 (f1 2)))) (f1 2)"))
        _ (out! b " (its own branch) still runs the old f1: " (p1 b "(app.m0/f1 2)"))
        m @(c/merge! sys a)
        _ (out! "merge " a " (gated by its tests): " m)
        n (c/spawn! sys {:task "born after the merge"})
        _ (await-status sys [n] #{:idle} 5000)
        _ (out! n ", forked after the merge, inherits the new f1 compiled: " (p1 n "(app.m0/f1 2)")
                "; its tests include the merged proof: " (select-keys @(c/run-tests! sys n) [:pass :fail :error]))
        mm @(c/merge-main! sys b)
        _ (out! b " merges main forward " (select-keys mm [:ok :applied]) " and now runs: " (p1 b "(app.m0/f1 2)"))
        x (c/spawn! sys {:task "breaks f1"})
        _ (await-status sys [x] #{:idle} 5000)
        _ (out! x " breaks f1: " (p1 x "(in-ns 'app.m0) (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (- x)) (f1 2)"))
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
        c1 (check "agent calling System/exit mid-request dies alone" (and (= :seon.agent/died (:seon.error/type (:err (last r1))))
                                                                          (= :dead (get-in (c/state sys) [:agents a :status])))
                  [(short-items r1) (select-keys (get-in (c/state sys) [:agents a]) [:status :exit])])
        r2 @(c/submit! sys b "(transact! [{:no/such-attr 1}])")
        c2 (check "a refused transaction is an :err item" (and (= :err (:tag (first r2))) (re-find #"refused" (:printed (first r2)))) (short-items r2))
        r3 @(c/submit! sys b "(apply str (repeat 100000 \"x\")) (range)")
        c3 (check "huge and infinite values print within the printer's budget" (and (< (count (:printed (first r3))) 25000) (< (count (:printed (second r3))) 25000))
                  (mapv #(count (:printed %)) r3))
        r4 @(c/submit! sys b "(+ 1")
        c4 (check "a read error is one :err item" (= :read (get-in (first r4) [:err :phase])) (short-items r4))
        _ @(c/submit! sys x "(ns app.spin (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest spins (loop [] (recur)))")
        t5 @(c/run-tests! sys x 1000000000)
        c5 (check "a test that never ends stops at its tick budget, naming the test; the agent lives"
                  (and (= 1 (:error t5)) (= "2" (:printed (first @(c/submit! sys x "(+ 1 1)"))))
                       (= [:seon.test/ticks-spent "#'app.spin/spins"] ((juxt :seon.error/type :var) (first (:reports t5)))))
                  (select-keys t5 [:error :reports :ms]))
        _ @(c/submit! sys x "(in-ns 'app.spin) (ns-unmap 'app.spin 'spins) (deftest exits (System/exit 7))")
        t6 @(c/run-tests! sys x)
        c6 (check "a test that kills its process is an error; the agent lives"
                  (and (= 1 (:error t6)) (= "3" (:printed (first @(c/submit! sys x "(+ 1 2)")))))
                  (select-keys t6 [:error :reports :exit]))
        r9 @(c/submit! sys b "(count (vec (range 300000000))) (+ 5 5)")
        c8 (check "an agent past its heap bound gets OutOfMemoryError as an :err item and lives"
                  (and (= :err (:tag (first r9))) (= "10" (:printed (second r9)))) (short-items r9))
        r11 @(c/submit! sys b "(def kept 7) (loop [] (recur)) (+ kept 1)" {:ticks 1000000000})
        p12 (c/submit! sys b "(reduce + (range)) :next")
        _ (Thread/sleep 200)
        _ (c/interrupt! sys b)
        r12 (deref p12 3000 :hang)
        r12b @(c/submit! sys b ":next")
        ;; nREPL's model (owner's choice): an interrupt stops the request; the agent goes on
        c11 (check "root's interrupt! stops the running request; the agent's next request runs"
                   (and (vector? r12) (= "interrupted by root" (:printed (first r12))) (= 1 (count r12)) (= ":next" (:printed (first r12b))))
                   (if (vector? r12) (short-items r12) r12))
        c10 (check "a form past its tick budget is stopped, a typed error naming its ticks; the agent keeps its state and goes on"
                   (and (= :seon.repl/ticks-spent (get-in (second r11) [:err :seon.error/type]))
                        (<= 1000000000 (get-in (second r11) [:err :ticks])) (= "8" (:printed (nth r11 2)))) (short-items r11))
        r10 @(c/submit! sys x "(println \"hello from\" (:id (my.agent/whoami))) (def busy (future (loop [] (recur))))")
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
        r8 @(c/submit! sys y "(count (q '[:find ?v :where [?v :var/name]])) (loop [] (recur)) :after" {:ticks 1000000000})
        c7 (check "the fork server dies: agents keep working, a new server forks the next agent (whose tick budget works)"
                  (and (= "7" (:printed (first r7))) ready? (= "360" (:printed (first r8)))
                       (= :seon.repl/ticks-spent (get-in (second r8) [:err :seon.error/type])) (= ":after" (:printed (nth r8 2))))
                  [(:server (c/state sys)) (short-items r7) (short-items r8)])
        all [c1 c2 c3 c4 c5 c6 c8 c9 c10 c11 c7]]
    (out! (count (filter true? all)) "/" (count all) " robustness checks pass")
    (c/stop! sys)
    (lib/c-exit (if (every? true? all) 0 1))))

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
  (let [sys (c/start! {:datoms 2000})
        mem0 (lib/sysmem)
        th-boot (thread-diff (c/declared-threads sys) (c/threads))
        t0 (lib/now-ns)
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "scale " i)})) (range n)))
        ready? (await-status sys ids #{:idle} 20000)
        t-ready (lib/ms-since t0)
        th-ready (thread-diff (c/declared-threads sys) (c/threads))
        _ (out! "ready " ready? " in " (lib/r2 t-ready) " ms: " (frequencies (map #(get-in (c/state sys) [:agents % :status]) ids)))
        work (fn [id]
               (let [k (subs (name id) 1)
                     t1 (lib/now-ns)
                     r @(c/submit! sys id (str "(ns app.s" k " (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (* x " k "))
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
        th-work (thread-diff (c/declared-threads sys) (c/threads))
        st (c/state sys)
        mem1 (lib/sysmem)
        agent-mb (keep #(get-in st [:agents % :mem-mb]) ids)
        merged (mapv (fn [id] @(c/merge-main! sys id) @(c/merge! sys id)) (take 10 ids)) ; as a merge task does: main first
        notes (count (main-q sys '[:find ?n :where [?n :note/text]]))
        fs (c/fs-call! sys {:op :stats})
        _ (doseq [id ids] (c/kill! sys id))
        killed? (await-status sys ids #{:killed} 10000)
        ;; kill! marks :killed at once; the process is gone when root has retired it
        _ (loop [k 0] (when (and (not-every? #(get-in (c/state sys) [:agents % :ended-ms]) ids) (< k 1000)) (Thread/sleep 10) (recur (inc k))))
        mem2 (do (Thread/sleep 500) (lib/sysmem))
        th-end (thread-diff (c/declared-threads sys) (c/threads))]
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
             :gc (lib/gc-stats) :gc-sweepers (lib/gc-sweepers! -1)
             :root-threads {:declared (c/declared-threads sys) :os (lib/nthreads)
                            :diff {:boot th-boot :all-ready th-ready :after-work th-work :after-kill th-end}}})
    (c/stop! sys)
    ;; every agent's eval and its test passed; the 10 merges went in
    ;; and root's threads exactly as declared once agents run (at boot Jolt's io poller has not
    ;; started yet: it starts at the first socket wait, an agent's)
    (let [ok (and ready? (every? :ok results) (every? :merged? merged) killed?
                  (every? empty? [th-ready th-work th-end]))]
      (out! (if ok "SCALE OK" "SCALE FAILED"))
      (lib/c-exit (if ok 0 1)))))

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
                                                    " (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (+ " (rand-int 1000) " (- x " (rand-int 1000) ") (- 1 " (rand-int 1000) ") (- 0 (- " (rand-int 1000) "))))"
                                                    " (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                                                    " (deftest f1-proof-" (name id) " (is (= 3 (f1 2))) (is (= 1 (f1 0))))"
                                                    " (my.db/transact! [{:task/name :" m "/tidy :task/status :done :task/agent " id "}])"))
                          ok-eval (every? #(= :ret (:tag %)) r)
                          ;; git: merge main into the branch first (a conflict stays unmerged
                          ;; there), and again while root finds the branch behind
                          [mm mr] (loop [k 0]
                                    (let [mm @(c/merge-main! sys id) mr @(c/merge! sys id)]
                                      (if (and (= :seon.merge/behind (:seon.error/type mr)) (< k 20)) (recur (inc k)) [mm mr])))]
                      (swap! log conj {:k k :agent id :generation generation :eval ok-eval
                                       :merged (:merged? mr) :conflicts (count (:conflicts mm))
                                       :red (when (:tests mr) (pos? (+ (:fail (:tests mr) 0) (:error (:tests mr) 0))))})
                      (c/kill! sys id)
                      (when (and (not (:merged? mr)) (< generation 4)) (attempt k (inc generation))))))
        _ (doall (pmap (fn [i] (attempt (mod i modules) 0)) (range n)))
        wall (lib/ms-since t0)
        done (main-q sys '[:find (count ?t) . :where [?t :task/status :done]])
        fresh (c/spawn! sys {:task "check main"})
        _ (await-status sys [fresh] #{:idle} 5000)
        proofs @(c/run-tests! sys fresh)
        l @log]
    (pprint {:agents-started (count l) :modules modules :wall-ms (lib/r2 wall)
             :merged (count (filter :merged l)) :conflicted (count (filter #(pos? (:conflicts %)) l))
             :refused-red (count (filter :red l))
             :by-generation (frequencies (map (juxt :generation :merged) l))
             :tasks-done-on-main done
             :fresh-agent-runs-all-merged-proofs (select-keys proofs [:test :pass :fail :error])
             :writer (:writer (c/state sys)) :main (:main (c/state sys))})
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
        _ @(c/submit! sys b "(ns app.forever (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest forever (loop [] (recur)))")
        _ (c/run-tests! sys b 600000)
        _ (Thread/sleep 300)
        pids (keep #(get-in (c/state sys) [:agents % :pid]) [a b (:id cl)])
        test-child (:out (clojure.java.shell/sh "pgrep" "-P" (str (get-in (c/state sys) [:agents b :pid]))))]
    (out! "PIDS " (clojure.string/join " " (concat pids [(get-in (c/state sys) [:server :pid])] (clojure.string/split-lines test-child))))
    ;; without a running test child there is nothing to prove about it
    (when (clojure.string/blank? test-child) (out! "NO TEST CHILD"))
    (lib/c-exit (if (clojure.string/blank? test-child) 1 0))))

;; ---- durability: the whole system restarts and recovers ------------------------------

(defn- dir-bytes [path] (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (java.io.File. path))))))

(defn durable-write
  "Phase 1 (a process of its own): boot on main branch nm of the one store (made from its
  seed), then
  a merges a new f1 (gated), b commits unmerged facts and a recorded defn on its branch,
  c's clone commits on a branch of a branch. Root then exits WITHOUT stop! (SIGKILL of
  itself): nothing but the store survives."
  [nm]
  (let [t0 (lib/now-ns)
        sys (c/start! {:main (keyword nm)})
        path (str (:store-path sys) "." nm)
        _ (out! "fresh branch booted in " (lib/r2 (lib/ms-since t0)) " ms: " (:store (c/state sys)))
        [a b k] (mapv #(c/spawn! sys {:task % :branch %2}) ["make f1 multiply" "unmerged work" "parent"] [:dur-a :dur-b :dur-c])
        _ (await-status sys [a b k] #{:idle} 5000)
        p1 (fn [id forms] (mapv (juxt :printed :recorded) @(c/submit! sys id forms)))
        _ (out! a ": " (p1 a "(in-ns 'app.m0) (defn f1 {:malli/schema [:=> [:cat :int] :int]} [x] (* x 10)) (deftest f1-test (is (= 20 (f1 2)))) (f1 2)"))
        m @(c/merge! sys a)
        _ (out! "merge " a ": " (dissoc m :ops))
        _ (out! b ": " (p1 b "(in-ns 'app.m1) (defn f2 {:malli/schema [:=> [:cat :int] :string]} [x] (str \"b:\" x)) (deftest f2-test (is (= \"b:1\" (f2 1))))
                             (in-ns 'agent.a2) (transact! [{:note/text \"b's unmerged note\" :note/agent :b}]) (app.m1/f2 1)"))
        cl @(c/clone! sys k {:branch :dur-clone})
        _ (await-status sys [(:id cl)] #{:idle} 5000)
        ev (c/events sys)
        _ (out! "clone " (:id cl) ": " (p1 (:id cl) "(transact! [{:note/text \"the clone's note\" :note/agent :clone}]) (count (q '[:find ?n :where [?n :note/text]]))"))
        ;; an agent's write is answered when applied, committed when the agent is idle (its
        ;; run-ahead writer) and durable at root's next flush: the kill below waits for the
        ;; clone's commit event, then for a flush after it (a :flush? write's answer comes after
        ;; its flush, which covers the commit the writer was told of first), rather than
        ;; racing them (a kill in between loses the write by design)
        _ (loop [] (let [e (clojure.core.async/<!! ev)] (when-not (and (= :commit (:type e)) (= (:id cl) (:agent e))) (recur))))
        _ @(c/transact-main! sys [] {:flush? true})
        st (c/state sys {:datoms true})
        expect {:main-datoms (get-in st [:main :datoms]) :main-t (get-in st [:main :t])}]
    (spit (str path ".expect.edn") (pr-str expect))
    (out! "phase 1 done: " expect "; store " (dir-bytes (:store-path sys)) " bytes; killing root (no stop!)")
    (lib/c-kill (lib/c-getpid) 9)))

(defn durable-read
  "Phase 2 (a new process): reopen the same store on the same main and check main, the
  program, the branches and agents' committed work came back; resume b and merge it now."
  [nm]
  (let [t0 (lib/now-ns)
        sys (c/start! {:main (keyword nm)})
        expect (read-string (slurp (str (:store-path sys) "." nm ".expect.edn")))
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
        mmb @(c/merge-main! sys rb)
        mb @(c/merge! sys rb)
        _ (out! "merge main into the resumed :dur-b " (select-keys mmb [:ok :applied]) ", then merge it: " (dissoc mb :ops))
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

(defn window-write
  "Phase 1 of the flush-window check (root's :flush-ms 1000): main branch nm of the one
  store. A is
  written and the window passes: flushed. B is answered committed and unflushed (the
  writer's flush count does not move before its answer). C is written with :flush? (an
  outside effect follows): its answer comes after its flush (the count moved). D is answered
  unflushed, and root SIGKILLs itself at once."
  [nm]
  (let [sys (c/start! {:main (keyword nm) :flush-ms 1000})
        flushes #(:flushes (::flow/state (flow/ping-proc (:graph sys) :writer :timeout-ms 1000)))
        t (fn [p] (let [t0 (lib/now-ns) r @p] [(:ok r) (lib/r2 (lib/ms-since t0))]))
        a (t (c/transact-main! sys [{:note/text "A" :note/agent :window}]))
        _ (Thread/sleep 1500)
        f0 (flushes)
        b (t (c/transact-main! sys [{:note/text "B" :note/agent :window}]))
        f1 (flushes)
        cc (t (c/transact-main! sys [{:note/text "C" :note/agent :window}] {:flush? true}))
        f2 (flushes)
        d (t (c/transact-main! sys [{:note/text "D" :note/agent :window}]))
        ok (and (= f0 f1) (= (inc f1) f2) (every? first [a b cc d]))]
    (out! "answers [ok ms]: A " a " B " b " C(flush?) " cc " D " d "; flushes " [f0 f1 f2])
    (out! (if ok "WINDOW-WRITE OK" "WINDOW-WRITE FAILED"))
    (lib/c-kill (lib/c-getpid) 9)))

(defn window-read
  "Phase 2: the store reopened on main nm after root's crash holds A, B and C. (D is there too: a process crash
  keeps what LMDB handed the kernel; only a machine crash loses the open window.)"
  [nm]
  (let [sys (c/start! {:main (keyword nm) :flush-ms 1000})
        notes (set (main-q sys '[:find [?t ...] :where [?n :note/agent :window] [?n :note/text ?t]]))
        ok (every? notes ["A" "B" "C"])]
    (out! "after the crash: " (sort notes))
    (out! (if ok "WINDOW OK" "WINDOW FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

;; ---- durability numbers ----------------------------------------------------------------

(defn- alive? [pid] (zero? (lib/c-kill pid 0)))

(defn lifecycle
  "n cycles of start! -> 3 agents work -> stop! in ONE process. Each stop! must answer
  with every proc exited (:graph true) and no agent or fork-server process left, and root's
  threads must be exactly what it declares (seon.root/declared-threads) while running and
  after every stop!, by name and by the OS's count. Then pause/resume:
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
                          running (thread-diff (c/declared-threads sys) (c/threads))
                          pids (vec (cons (get-in (c/state sys) [:server :pid]) (keep #(get-in (c/state sys) [:agents % :pid]) ids)))
                          st (c/stop! sys)
                          left (filter alive? pids)]
                      {:cycle i :ready ready? :ok ok? :boot-ms (lib/r2 boot-ms) :stop st :left (vec left)
                       :health (set (map #(if (map? %) (:status %) %) (vals health)))
                       :running running :stopped (thread-diff (c/declared-threads) (c/threads))})))
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
        declared-running (c/declared-threads sys)
        st (c/stop! sys)
        checks {:every-stop-exited (every? #(and (true? (get-in % [:stop :graph])) (zero? (get-in % [:stop :agents-left])) (empty? (:left %))) rows)
                :every-cycle-worked (every? #(and (:ready %) (:ok %) (= #{:running} (:health %))) rows)
                :threads-declared (every? #(and (empty? (:running %)) (empty? (:stopped %))) rows)
                :pause-holds (and (= :held held) (true? held-state) (= :paused (:status paused-health)))
                :resume-releases-in-order (and (= "1" (:printed (second r1))) (= "2" (:printed (first r2))))
                :final-stop (true? (:graph st))}]
    (pprint {:threads {:declared-running declared-running :declared-stopped (c/declared-threads)}
             :cycles (mapv #(select-keys % [:cycle :boot-ms :running :stopped :left :health]) rows)
             :stop-ms (lib/summ (map #(get-in % [:stop :ms]) rows))
             :gc (lib/gc-stats) :gc-sweepers (lib/gc-sweepers! -1)
             :checks checks})
    (out! (if (every? true? (vals checks)) "LIFECYCLE OK" "LIFECYCLE FAIL"))
    (lib/c-exit (if (every? true? (vals checks)) 0 1))))

(defn threads-declared
  "n cycles of start! -> 3 agents evaluate -> stop! in one process; after the evals and
  after each stop!, root's threads against what it declares (thread-diff, {} when exact).
  The last line is the EDN {:running [diff ..] :stopped [diff ..]} that
  seon.root-test/root-threads-are-what-root-declares reads."
  [n]
  (let [rows (vec (for [_ (range n)]
                    (let [sys (c/start! {:datoms 2000})
                          ids (mapv #(c/spawn! sys {:task (str "td " %)}) (range 3))
                          _ (await-status sys ids #{:idle} 5000)
                          _ (mapv #(deref (c/submit! sys % "(+ 2 2)")) ids)
                          running (thread-diff (c/declared-threads sys) (c/threads))]
                      (c/stop! sys)
                      {:running running :stopped (thread-diff (c/declared-threads) (c/threads))})))]
    (pprint {:declared (c/declared-threads) :measured (c/threads)})
    (out! (pr-str {:running (mapv :running rows) :stopped (mapv :stopped rows)}))
    (lib/c-exit (if (every? #(and (empty? (:running %)) (empty? (:stopped %))) rows) 0 1))))

;; ---- delivery (lane F2): main's commits to the reconciler, messages to agents ------

(defn drain
  "n agents each transact in a loop, and root's own writes stream in, when stop! is
  called. Every request the entrance accepted is answered; every :ok write is in the
  store afterwards (durable: reopened in a fresh connection), and nothing is left running."
  [n]
  (let [sys (c/start! {:datoms 2000})
        ids (vec (pmap (fn [i] (c/spawn! sys {:task (str "drain " i)})) (range n)))
        _ (await-status sys ids #{:idle} 20000)
        pids (vec (keep #(get-in (c/state sys) [:agents % :pid]) ids))
        fs-pid (get-in (c/state sys) [:server :pid])
        ;; each agent: one eval writing 50 notes, one transact! at a time
        evals (mapv #(c/submit! sys % (str "(dotimes [i 50] (transact! [{:note/text (str \"d\" i) :note/agent " % "}])) :done")) ids)
        mains (vec (for [i (range 300)] (do (when (= i 150) (Thread/sleep 20)) (c/transact-main! sys [{:note/text (str "m" i) :note/agent :root}]))))
        st (c/stop! sys)
        evr (mapv #(deref % 1000 :hang) evals)
        mr (mapv #(deref % 1000 :hang) mains)
        ok-main (count (filter :ok mr))
        refused-main (count (filter #(= "root stopped" (:seon.error/message %)) mr))
        eval-done (count (filter #(= ":done" (:printed (second %))) evr))
        eval-other (frequencies (map #(if (vector? %) (:printed (last %)) %) evr))
        conn (d/connect (:cfg sys))
        main-notes (d/q '[:find (count ?e) . :where [?e :note/agent :root]] @conn)
        left (filter #(zero? (lib/c-kill % 0)) (cons fs-pid pids))
        checks {:every-request-answered (and (not-any? #{:hang} evr) (not-any? #{:hang} mr))
                :main-ok-all-stored (= ok-main (or main-notes 0))
                :main-answers-account (= 300 (+ ok-main refused-main))
                :nothing-left-running (empty? left)
                :graph-stopped (true? (:graph st))}]
    (d/release conn)
    (pprint {:agents n :stop st :main {:ok ok-main :refused-after-close refused-main :stored main-notes}
             :evals {:done eval-done :other eval-other} :left (vec left) :checks checks})
    (out! (if (every? true? (vals checks)) "DRAIN OK" "DRAIN FAIL"))
    (lib/c-exit (if (every? true? (vals checks)) 0 1))))

;; ---- writers (lane F2): group commit at 1/8/32/256 concurrent writers --------------

(defn backpressure
  "1. Across the ring: an agent floods root with 20,000 messages while its proc is paused.
     Its channel fills, the poller leaves the frames in the ring, the ring fills, and the
     agent's own send waits: few messages arrive while paused, the agent is still busy,
     and after resume all 20,000 arrive, in order, then its reply.
  2. A saturated writer: the writer proc is paused while n agents transact (their own
     branches: all answered) and root writes main n times (held). The writer's
     in-port (:seon.config.root/writer-buf) fills, then the agent procs' puts park; resume completes
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
        ;; agents own their branches' writes: a paused writer does not hold them
        ps (mapv #(c/submit! sys % "(transact! [{:note/text \"bp\" :note/agent :bp}]) :ok") ids)
        agents-ok (count (filter #(= ":ok" (:printed (second %))) (mapv #(deref % 20000 :hang) ps)))
        ;; root's own writes to main wait for it
        mains (mapv #(c/transact-main! sys [{:note/text (str "bp" %) :note/agent :root}]) (range n))
        _ (Thread/sleep 500)
        answered-while-paused (count (filter realized? mains))
        mb1 (lib/r2 (lib/real-mb))
        t1 (lib/now-ns)
        _ (flow/resume-proc (:graph sys) :writer)
        rs (mapv #(deref % 20000 :hang) mains)
        resume-ms (lib/ms-since t1)
        ok (count (filter :ok rs))
        checks {:ring-pushes-back (and (< while-paused 2000) (= :busy busy))
                :flood-all-arrive-in-order (and (= 20000 (:noise st)) (true? (:noise-ordered st)) (= ":flooded" (:printed (second r))))
                :agents-write-past-a-paused-writer (= n agents-ok)
                :writer-paused-holds-all (zero? answered-while-paused)
                :writer-resumed-completes-all (= n ok)}]
    (pprint {:flood {:arrived (:noise st) :ordered (:noise-ordered st) :reply (:printed (second r)) :arrived-while-paused while-paused :status-while-paused busy :drain-ms (lib/r2 drain-ms)}
             :writer {:agents n :writer-buf (seon.config/value :seon.config.root/writer-buf) :answered-while-paused answered-while-paused
                      :root-real-mb {:before mb0 :writer-saturated mb1} :resume->all-done-ms (lib/r2 resume-ms) :ok ok
                      :agents-ok agents-ok :not-ok (frequencies (remove :ok rs))}
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

(defn fx
  "Lane FX1: the effect hop end to end. One agent; per-call cost of a synchronous effect
  (my.fx/call :echo) inside one eval, against a plain call and a branch write; file
  write-back through :file against spit in the agent; async sends; an effect in a test
  fork; the outcomes recorded on the agent's branch."
  [n]
  (let [sys (c/start! {:datoms 2000})
        a (c/spawn! sys {:task "fx" :branch :fx1})
        _ (await-status sys [a] #{:idle} 5000)
        dir (str (:agents-dir (:store (c/state sys))) "/fx1") ; this root's own run dir, kept with the store
        _ (.mkdirs (java.io.File. dir))
        ev (fn [code] (let [r @(c/submit! sys a code)] (last r)))
        ;; per call, timed inside the agent: (loop n) of the form, µs each
        per (fn [form k] (let [code (str "(let [t0 (System/nanoTime)] (dotimes [i " k "] " form ") (/ (- (System/nanoTime) t0) " k " 1000.0))")]
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
        ping (flow/ping-proc (:graph sys) :fx/echo :timeout-ms 1000)]
    (pprint (assoc r :recorded (into {} recs) :echo-queue-us (lib/summ (vec q)) :echo-perform-us (lib/summ (vec us))
                   :owner-ping ping :load (clojure.string/trim (:out (clojure.java.shell/sh "uptime")))))
    (clojure.java.shell/sh "rm" "-rf" dir)
    (c/stop! sys)
    ;; the effect answered, its unknown kind is a typed error, and inside the test fork a
    ;; call is refused unless stubbed (both assertions ran and passed)
    (let [ok (and (= ":seon.fx/unknown-kind" (:unknown r)) (= {:pass 2 :fail 0 :error 0} (:test-fork r)))]
      (out! (if ok "FX OK" "FX FAILED"))
      (lib/c-exit (if ok 0 1)))))

(defn probe-proc
  "A declared test proc (the system check): counts what arrives on :in, reports it in ping."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:tag "a label"} :ins {:in "anything"} :outs {}
       :workload :io :ping-map-fn (fn [st] {:got (:got st) :tag (:tag st)})})
  ([args] (assoc args :got 0))
  ([st _] st)
  ([st _ m] [(cond-> st (some? m) (update :got inc)) nil]))

(defn config-check
  "The config as data on main (lane CONFIG): root's boot writes the config in force on
  main's root entity; an agent, forked holding the same config and reading no environment,
  reads the same facts from its own branch's db. Verdict CONFIG OK / CONFIG FAILED."
  []
  (let [sys (c/start! {:datoms 2000})
        main (seon.config/from-db @(:conn sys))
        expected (seon.config/with-provider seon.config/config (seon.config/provider seon.config/config))
        a (c/spawn! sys {:task "reads the config"})
        _ (await-status sys [a] #{:idle} 5000)
        r @(c/submit! sys a "[(count (seon.config/from-db (db))) (hash (seon.config/from-db (db))) (hash seon.config/config)]")
        [n h own] (some-> (first r) :printed clojure.edn/read-string)
        checks {:main-holds-the-config-in-force (= expected main)
                :every-declared-key-the-config-holds (= (count seon.config/config) (count main) n)
                :the-agent-reads-it-from-its-branch (= (hash main) h)
                :the-agent-was-forked-holding-it (= (hash seon.config/config) own)}
        ok (every? true? (vals checks))]
    (pprint {:keys (count main) :agent-reply (:printed (first r)) :checks checks})
    (out! (if ok "CONFIG OK" "CONFIG FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

(defn system-check
  "Root's procs from resources/seon/system.edn: boot reaches the hand map's running set;
  apply! after adding or removing one proc in the file touches only that proc; an unchanged
  proc keeps its queued messages across an apply!."
  []
  (let [sys (c/start! {:datoms 2000})
        pids (fn [] (set (keys (flow/ping (:graph sys) :timeout-ms 2000))))
        booted (pids)
        expect (into #{:api :writer :fork-server :flusher :reconciler :render :web :fx/echo :fx.echo/0 :fx/file :fx.file/0 :fx/source :fx.source/0 :fx/git :fx.git/0 :fx/deps :fx.deps/0 :fx/build :fx.build/0 :fx/model}
                     (for [i (range (seon.config/value :seon.config.ai/max-in-flight))] (keyword "fx.model" (str i))))
        dir (str (:agents-dir (:store (c/state sys))) "/system") ; this root's own run dir
        _ (.mkdirs (java.io.File. dir))
        decl (clojure.edn/read-string (slurp (seon.config/value :seon.config.root/system-path)))
        write! (fn [nm d] (let [f (str dir "/" nm ".edn")] (spit f (pr-str d)) f))
        probe (fn [tag] {:proc 'seon.check/probe-proc :args {:tag tag}})
        f1 (write! "add" (assoc-in decl [:procs :probe] (probe "one")))
        r1 @(c/apply! sys f1)
        ;; the probe paused, three messages queued on its :in, then another proc added
        _ (flow/pause-proc (:graph sys) :probe)
        _ @(flow/inject (:graph sys) [:probe :in] [1 2 3])
        f2 (write! "add2" (-> decl (assoc-in [:procs :probe] (probe "one")) (assoc-in [:procs :probe2] (probe "two"))))
        r2 @(c/apply! sys f2)
        _ (flow/resume-proc (:graph sys) :probe)
        _ (Thread/sleep 200)
        got (get-in (flow/ping-proc (:graph sys) :probe :timeout-ms 1000) [::flow/state :got])
        f3 (write! "remove" (assoc-in decl [:procs :probe] (probe "one")))
        r3 @(c/apply! sys f3)
        after (pids)
        res {:boot-set (= expect booted) :missing (vec (remove booted expect)) :extra (vec (remove expect booted))
             :add (dissoc r1 :kept) :add2 (dissoc r2 :kept) :queued-kept got :remove (dissoc r3 :kept)
             :ok (and (= expect booted)
                      (= #{:probe} (set (:started r1))) (empty? (:stopped r1))
                      (= #{:probe2} (set (:started r2))) (empty? (:stopped r2)) (= 3 got)
                      (= #{:probe2} (set (:stopped r3))) (empty? (:started r3))
                      (= (conj expect :probe) after))}]
    (pprint res)
    (clojure.java.shell/sh "rm" "-rf" dir)
    (c/stop! sys)
    (out! (if (:ok res) "SYSTEM OK" "SYSTEM FAIL"))
    (lib/c-exit (if (:ok res) 0 1))))

(defn root-serve
  "A root with its entrance on 127.0.0.1:(:seon.config.root/entrance-port) (seon.entrance),
  until stdin closes: what an outside agent's MCP server (target/seon mcp) talks to. A
  checkout whose config names no entrance port (0) serves none, and says so."
  []
  (let [port (seon.config/value :seon.config.root/entrance-port)
        _ (when (zero? port)
            (out! "ROOT REFUSED: :seon.config.root/entrance-port is 0 (no entrance); bin/worktree --ports writes config/local.edn with this checkout's ports")
            (lib/c-exit 1))
        sys (c/start! {})]
    (seon.entrance/serve! sys port)
    (out! "ROOT LISTENING " port)
    (loop [] (when (read-line) (recur)))
    (c/stop! sys)
    (lib/c-exit 0)))

(defn- eval-records
  "The evaluation records on db of agent id: [{:form :origin ...}]."
  [db id]
  (mapv clojure.edn/read-string
        (d/q '[:find [?r ...] :in $ ?id :where [?a :seon.agent/id ?id] [?e :seon.cluster.eval/agent ?a] [?e :seon.cluster.eval/record ?r]] db id)))

(defn mcp-proof
  "The MCP phase's proof: an outside client (a separate process: target/seon mcp PORT, fed
  JSON-RPC on stdin) lists agents, evaluates in a running agent and as root, and both
  evaluations are recorded with origin :outside (the agent's on its branch, root's on main)."
  []
  (let [sys (c/start! {:datoms 2000})
        ;; port 0: the kernel picks a free one, so two lanes' proofs never collide
        port (.getLocalPort ^java.net.ServerSocket (seon.entrance/serve! sys 0))
        a (c/spawn! sys {:task "mcp" :branch :mcp1})
        _ (await-status sys [a] #{:idle} 5000)
        msgs [{"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" {"protocolVersion" "2024-11-05"}}
              {"jsonrpc" "2.0" "method" "notifications/initialized"}
              {"jsonrpc" "2.0" "id" 2 "method" "tools/list"}
              {"jsonrpc" "2.0" "id" 3 "method" "tools/call" "params" {"name" "seon_agents" "arguments" {}}}
              {"jsonrpc" "2.0" "id" 4 "method" "tools/call" "params" {"name" "seon_eval" "arguments" {"agent" (name a) "code" "(def outside-x 41) (inc outside-x)"}}}
              {"jsonrpc" "2.0" "id" 5 "method" "tools/call" "params" {"name" "seon_eval" "arguments" {"agent" "root" "code" "(count (:agents (c/state sys)))"}}}]
        t0 (lib/now-ns)
        r (clojure.java.shell/sh "./seon" "mcp" "--set" (str ":seon.config.root/entrance-port=" port) :in (clojure.string/join "\n" (map clojure.data.json/write-str msgs)))
        ms (lib/ms-since t0)
        answers (into {} (for [l (clojure.string/split-lines (:out r)) :when (clojure.string/starts-with? l "{")
                               :let [j (clojure.data.json/read-str l)]] [(get j "id") j]))
        text (fn [id] (get-in answers [id "result" "content" 0 "text"]))
        bdb (:db @(c/branch-db sys :mcp1))
        mine (filter #(= :outside (:origin %)) (eval-records bdb a))
        roots (filter #(= :outside (:origin %)) (eval-records @(:conn sys) :root))
        res {:tools (count (get-in answers [2 "result" "tools"]))
             :agents-text (some-> (text 3) (subs 0 (min 120 (count (text 3)))))
             :agent-eval (text 4) :root-eval (text 5)
             :agent-records (mapv #(select-keys % [:form :shown :origin]) mine)
             :root-records (mapv #(select-keys % [:form :shown :origin]) roots)
             :client-ms (lib/r2 ms) :exit (:exit r) :err (some-> (:err r) clojure.string/trim not-empty)}
        ok (and (= 5 (:tools res)) (= 2 (count mine)) (= 1 (count roots))
                (clojure.string/includes? (str (text 4)) "42") (clojure.string/includes? (str (text 5)) "1"))]
    (pprint res)
    (c/stop! sys)
    (out! (if ok "MCP OK" "MCP FAIL"))
    (lib/c-exit (if ok 0 1))))

(defn- os-threads
  "The OS threads of process pid (ps -M lists one line per thread after its header)."
  [pid]
  (dec (count (clojure.string/split-lines (:out (clojure.java.shell/sh "ps" "-M" "-p" (str pid)))))))

(defn threads-check
  "Lane RC1's physics rule: the processes that fork (the fork server; an agent, for its
  tests and clones) must stay single-threaded, so no sweeper thread ever starts in them.
  An agent evaluates, collects, runs its tests in a fork and is cloned; then the fork
  server, the agent and the clone each have one thread."
  []
  (let [sys (c/start! {:datoms 2000})
        a (c/spawn! sys {:task "threads" :branch :threads1})
        _ (await-status sys [a] #{:idle} 5000)
        _ @(c/submit! sys a "(ns app.one (:require [clojure.test :refer [deftest is]] [my.db :refer [q transact!]])) (deftest t-one (is (= 2 (inc 1)))) (dotimes [i 200] (vec (range 10000))) (System/gc) :ok")
        tests (select-keys @(c/run-tests! sys a) [:pass :fail :error])
        cl (:id @(c/clone! sys a {}))
        _ (await-status sys [cl] #{:idle} 5000)
        cl-eval (:printed (last @(c/submit! sys cl "(inc 41)")))
        st (c/state sys)
        fs-pid (get-in st [:server :pid])
        a-pid (get-in st [:agents a :pid])
        cl-pid (get-in st [:agents cl :pid])
        res {:fork-server (os-threads fs-pid) :agent (os-threads a-pid) :clone (os-threads cl-pid)
             :tests tests :clone-eval cl-eval}
        ok (and (= 1 (:fork-server res) (:agent res) (:clone res)) (= 1 (:pass tests)) (= "42" cl-eval))]
    (pprint res)
    (c/stop! sys)
    (out! (if ok "THREADS OK" "THREADS FAIL"))
    (lib/c-exit (if ok 0 1))))

(defn units
  "The unit table measured on this binary at 1k, 10k and 100k units, and the 10k row as
  seon.cost/units' literal (ns and bytes per unit)."
  []
  (let [rows (into (sorted-map) (for [k (keys cost/unit-benches)]
                                  [k (mapv #(cost/measure-unit k %) [1000 10000 100000])]))]
    (doseq [[k ms] rows] (out! k " " (mapv (fn [m] [(lib/r2 (:ns m)) (lib/r2 (:bytes m))]) ms)))
    (out! "{" (clojure.string/join "\n "
                (for [[k ms] rows :let [{:keys [ns bytes]} (second ms)]]
                  (str k " {:ns " (lib/r2 ns) " :bytes " (lib/r2 bytes) " :what " (pr-str (:what (cost/unit-benches k))) "}"))) "}")
    (lib/c-exit 0)))

(defn lease
  "Run with --set :seon.config.store/eid-lease=50: agent a writes 60 new entities in one transaction, past its
  lease: refused with :seon.db/lease-spent, nothing applied. A refused transaction keeps the
  ids it reserved (Datahike's store counter), so a's lease is spent: its next write is
  refused too, and the record of its evals, which cannot be written on its branch, is
  recorded on main as its problem. Agent b, the next lease, writes 10: its ids lie past a's
  lease, never shared."
  []
  (let [sys (c/start! {})
        [a b] (mapv #(c/spawn! sys {:task %}) ["past its lease" "the next lease"])
        _ (await-status sys [a b] #{:idle} 5000)
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
        spent? #(and (= :err (:tag %)) (re-find #"entity ids are spent" (str (:printed %))))
        ok (and (spent? r-past) (= "0" (:printed r-count)) (spent? r-next)
                (= 50 (get-in stats [:ids :of])) (pos-int? recorded)
                (< (get-in stats [:ids :used] 0) 50)
                (>= b-min (+ (get-in stats [:ids :from]) 50)))]
    (out! "past its lease: " (select-keys r-past [:tag :printed]) "\n  error: " (:err r-past))
    (out! "after the refusal a's notes: " (:printed r-count) "; its next write: " (select-keys r-next [:tag :printed]) "; its ids " (:ids stats))
    (out! "a's unrecorded evals, recorded on main: " recorded "; b's lowest id " b-min)
    (out! (if ok "LEASE OK" "LEASE FAILED"))
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

(defn idle
  "An idle root steps nothing (owner, 2026-10-21: no beat; health is what flows): root
  boots and settles (the reconciler's quiescence), a tab opens on its dashboard (an SSE
  feed on SEON_WEB_PORT) and receives its page; then every root proc's Flow step count is
  read at the two ends of a window of window-ms with nothing happening, and must not move.
  Then a change reaches the tab: an agent spawned and a commit to main each grow what the
  tab received. The wait is the measurement (that nothing happens), not a dependency."
  [window-ms]
  (let [port (seon.config/value :seon.config.web/port)
        sys (c/start! {:datoms 300})
        _ (seon.e2e/await-quiet sys 0)
        received (atom 0)
        sock (java.net.Socket. "127.0.0.1" (int port))
        _ (doto (.getOutputStream sock)
            (.write (.getBytes (str "GET /feed?view=root HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n") "UTF-8"))
            (.flush))
        ;; the tab: an edge reader counting the bytes its feed sends
        in (.getInputStream sock)
        _ (doto (Thread. (fn [] (let [buf (byte-array 8192)]
                                  (loop [] (let [n (try (.read in buf) (catch Exception _ -1))]
                                             (when (pos? n) (swap! received + n) (recur)))))))
            (.setDaemon true) (.start))
        await-more (fn [n0 ms] (let [deadline (+ (lib/now-ns) (* ms 1000000))]
                                 (loop [] (cond (> @received n0) true
                                                (> (lib/now-ns) deadline) false
                                                :else (do (Thread/sleep 5) (recur))))))
        opened (await-more 0 10000)
        ;; the page's own first passes (the tab's views, its keyframe) settle first
        _ (Thread/sleep 1000)
        ;; root's declared procs and its effect owners (their lanes step only when given work)
        decl (seon.system/read-decl (seon.config/value :seon.config.root/system-path))
        pids (sort-by str (concat (keys (:procs decl)) (for [k (keys (:fx decl))] (keyword "fx" (name k)))))
        counts (fn [] (into (sorted-map) (for [pid pids] [pid (some-> (flow/ping-proc (:graph sys) pid :timeout-ms 1000) ::flow/count)])))
        b0 @received c0 (counts)
        _ (Thread/sleep window-ms)
        c1 (counts) b1 @received
        moved (into (sorted-map) (for [[pid n] c1 :when (not= n (get c0 pid))] [pid [(get c0 pid) n]]))
        n-spawn @received
        a (c/spawn! sys {:task "idle check"})
        spawned (await-more n-spawn 10000)
        n-commit @received
        _ @(c/transact-main! sys [{:db/id "n" :ns/name 'idle.check}])
        committed (await-more n-commit 10000)
        ok (and opened (empty? moved) (= b0 b1) spawned committed)]
    (pprint {:window-ms window-ms :procs (count pids) :steps-at-idle moved :bytes-at-idle (- b1 b0)
             :tab-opened opened :spawn-reached-tab spawned :commit-reached-tab committed :agent a})
    (out! (if ok "IDLE OK" "IDLE FAILED"))
    (.close sock)
    (c/stop! sys)
    (lib/c-exit (if ok 0 1))))

(defn seed
  "bin/check's one store: created at :seon.config.root/store-path (bin/check sets SEON_STORE)
  and seeded with about n datoms on :db, before any step boots; each step's root then works on
  a branch of it (seon.db/open!)."
  [n]
  (let [path (seon.config/value :seon.config.root/store-path)
        t0 (lib/now-ns)
        {:keys [seeded? conn]} (seon.db/open! :lmdb path n :db)
        ms (lib/r2 (lib/ms-since t0))
        datoms (count (d/datoms @conn :eavt))]
    (d/release conn)
    (out! "seeded " path " (" datoms " datoms) in " ms " ms; " (dir-bytes path) " bytes")
    (out! (if seeded? "SEED OK" "SEED FAILED (the store existed)"))
    (lib/c-exit (if seeded? 0 1))))

(defn -main [& args]
  ;; --config FILE and --set :k=v are the config's (seon.config reads them from the command
  ;; line); the mode reads the rest
  (let [[mode n & more] (seon.config/without-overrides args)]
  (case mode
    "seed" (seed (Long/parseLong (or n "2000")))
    "durable-write" (durable-write n)
    "durable-read" (durable-read n)
    "window-write" (window-write n)
    "window-read" (window-read n)
    "scale" (scale (Long/parseLong (or n "100")))
    "robust" (robust)
    "lifecycle" (lifecycle (Long/parseLong (or n "10")))
    "drain" (drain (Long/parseLong (or n "32")))
    "backpressure" (backpressure (Long/parseLong (or n "200")))
    "program" (program)
    "contracts" (contracts)
    "census" (census n)
    "units" (units)
    "e2e" (lib/c-exit (if (seon.e2e/run n more) 0 1))
    "admit" (admit)
    "record" (record)
    "orphan" (orphan)
    "lease" (lease)
    "fx" (fx (Long/parseLong (or n "1000")))
    "system" (system-check)
    "config" (config-check)
    "problems" (problems-check)
    "threads" (threads-check)
    "threads-declared" (threads-declared (Long/parseLong (or n "3")))
    "root" (root-serve)
    ;; this checkout's ports as the config resolves them, "ENTRANCE WEB" (bin/ports)
    "ports" (do (println (seon.config/value :seon.config.root/entrance-port) (seon.config/value :seon.config.web/port)) (lib/c-exit 0))
    "mcp" (do (seon.mcp/serve! (seon.config/value :seon.config.root/entrance-port)) (lib/c-exit 0))
    "mcp-proof" (mcp-proof)
    "hotreload" (hotreload)
    "swarm" (swarm (Long/parseLong (or n "100")))
    "idle" (idle (Long/parseLong (or n "5000")))
    (smoke))))
