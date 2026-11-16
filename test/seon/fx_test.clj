(ns seon.fx-test
  "The effect owner and worker step-fns as plain functions (no graph): admission by workers,
  priority order, budget refused, the queue bound, and a worker answering a defect."
  (:require [seon.lib] [clojure.test :refer [deftest is testing]] [jolt.deps] [seon.fx :as fx] [seon.config :as cfg] [seon.contract :as contract] [seon.proofs :as proofs]
            [clojure.core.async.flow :as flow] [clojure.core.async :as a] [seon.model :as model] [clojure.java.shell] [seon.program]))

(defn- scratch-dir []
  (let [d (str (System/getProperty "java.io.tmpdir") "/seon-fx-test-" (random-uuid))]
    (.mkdirs (java.io.File. d))
    d))

(defn- owner [workers] (fx/owner-proc {:kind :t :workers workers :work nil :back nil}))
(defn- req [k & {:as more}] (merge {:req {:k k} :dest {:agent "boot/1" :op :fx-reply :k k}} more))

;; docs/source.md#indexing-a-tree. Estimate: a directory that is no git checkout asks git nothing: one file read, one absent path, one
;; skipped (not source) = 2 stats and 1 slurp, ~20k ticks. (The :tree id of a checkout is proved by the :since test below.)
(deftest ^{:seon.test/estimate {:ticks 100000}} a-read-of-named-paths-reads-only-those-files
  ;; task seon.source/an-import-reads-only-changed-files: (my.root/import! sys paths) names the files
  ;; and the :source read gives just those
  (let [d (scratch-dir)]
    (try
      (.mkdirs (java.io.File. (str d "/src")))
      (spit (str d "/src/a.clj") "(ns a)") (spit (str d "/src/other.clj") "(ns other)") (spit (str d "/README.md") "x")
      (let [r (seon.fx/read-source {:req {:root d :dirs ["src"] :paths #{"src/a.clj" "src/gone.clj" "README.md"}}})]
        (is (= {"src/a.clj" "(ns a)" "src/gone.clj" nil} (:value r)) "the named source files only; a removed file is nil; src/other.clj unread")
        (is (= #{"src/a.clj" "src/gone.clj" "README.md"} (set (:paths r)))))
      (finally (seon.lib/remove-tree! d)))))

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

;; docs/config.md#the-test-services-three-limits: the dials, any combination: a count, a share of the cores, the least set holds;
;; the processes in flight bounded by the cores and two, derived, never a key
(deftest ^{:seon.test/estimate {:ticks 500000}} the-dials-make-one-count-and-a-derived-process-bound
  (is (= {:max-workers 18 :budget 20 :max-load nil} (fx/limits {:cores 18})) "nothing set: the cores, and the cores and two")
  (is (= 2 (:max-workers (fx/limits {:max-workers 2 :cores 18}))))
  (is (= 2 (:max-workers (fx/limits {:max-cores 0.5 :cores 4}))) "half of four cores: two")
  (is (= 3 (:max-workers (fx/limits {:max-cores 0.2 :max-workers 4 :cores 18}))) "a fifth of eighteen, under max-workers")
  (is (= 1 (:max-workers (fx/limits {:max-cores 0.1 :cores 4}))) "at least one")
  (is (= 12.0 (:max-load (fx/limits {:max-load 12.0 :cores 4})))))

(defn- flight [& fs] (into {} (map-indexed (fn [i f] [i (merge {:tag (str "t" i)} f)])) fs))

;; a pure fn of the dials and the owner's state: why a request waits, or nil
(deftest ^{:seon.test/estimate {:ticks 500000}} a-request-is-held-by-the-dials-and-the-processes-in-flight
  (let [lim (fx/limits {:cores 18 :max-workers 4 :max-load 10.0})
        st {:flight (flight {:class :root} {:class :test :declared 1}) :peaks {:root 6}}]
    (is (nil? (fx/hold-reason lim st {:seon.fx/class :test :seon.fx/processes 1})) "7 held and 1 more, under 20")
    (is (= :processes (fx/hold-reason lim (assoc-in st [:peaks :root] 14) {:seon.fx/class :root})) "6 + 1 + a root's 14 is past 20")
    (is (= :processes (fx/hold-reason lim st {:seon.fx/class :unread})) "a class never measured weighs the whole bound")
    (is (nil? (fx/hold-reason lim {:flight {}} {:seon.fx/class :unread})) "and runs when nothing else is in flight")
    (is (= :workers (fx/hold-reason (assoc lim :max-workers 2) st {:seon.fx/class :test})) "max-workers of a class in flight")
    (is (= :load (fx/hold-reason lim (assoc st :load 30.0) {:seon.fx/class :test})) "the load over max-load")
    (is (nil? (fx/hold-reason lim (assoc st :load 30.0 :flight {}) {:seon.fx/class :test})) "nothing in flight: never stalls")
    (is (nil? (fx/hold-reason (assoc lim :max-workers 1) (assoc st :load 30.0) {})) "a request of no class (a model call) is never held")
    (is (= :processes (fx/hold-reason lim (assoc st :reading {"t0" 19}) {:seon.fx/class :test :seon.fx/processes 1}))
        "the root measured holding 19 now: what it holds, not its weight, counts")))

(defn- powner [& {:as args}] (fx/owner-proc (merge {:kind :process :workers 18 :work nil :back nil :held-fn (constantly {}) :load-fn (constantly 1.0)} args)))
(defn- preq [k & {:as more}] (merge {:req {:k k :kind :step :seon.prove/batch (str "b" k)} :dest {:rid k} :seon.fx/class :step} more))
(defn- sent [out] (mapv #(get-in % [:req :k]) (:to-workers out)))

(deftest ^{:seon.test/estimate {:ticks 2000000}} owner-holds-a-requests-weight-until-its-outcome
  (let [[st out] (fx/owner-proc (powner :limits (fx/limits {:cores 1})) :in (preq 1 :seon.fx/class :root))
        _ (is (= [1] (sent out)) "nothing in flight: an unread class runs alone")
        [st out] (fx/owner-proc st :in (preq 2 :seon.fx/class :root))
        _ (is (empty? (sent out)) "the unread class holds the whole bound")
        [st out] (fx/owner-proc st :done {:dest {:rid 1} :request (first (:to-workers (second (fx/owner-proc (powner :limits (fx/limits {:cores 1})) :in (preq 1 :seon.fx/class :root)))))
                                          :outcome {:value 1 :seon.fx/peak-processes 2}})]
    (is (= {:root 2} (:peaks st)) "the outcome's peak is the class's weight from now on")
    (is (= [2] (sent out)) "the next runs once the weight is given back")
    (is (= #{"b2"} (set (keys (:flight st)))) "in flight at its class's measured peak, no longer the whole bound")))

;; the owner's processes in flight are read from the machine by each request's tag (held-processes, stubbed): a reading is its
;; class's peak, then the class is admitted by it. Measured red (2026-11-13): 127 processes, load 45.4 on 18 cores at a weight of 3
(deftest ^{:seon.test/estimate {:ticks 2000000}} admission-counts-the-processes-its-requests-hold
  (let [run (fn [reading n] (let [[st _] (fx/owner-proc (powner :limits (fx/limits {:cores 18}) :held-fn (constantly reading)) :in (preq 1))]
                              (reduce (fn [[st c] k] (let [[st o] (fx/owner-proc st :in (preq k))] [st (+ c (count (sent o)))])) [st 0] (range 2 (+ 2 n)))))]
    (let [[st more] (run {"b1" 18} 3)]
      (is (= 0 more) "it holds 18: a step measured at 18 does not fit under 20")
      (is (= 18 (get-in st [:peaks :step])) "the reading is its class's peak"))
    (let [[st more] (run {"b1" 3} 8)]
      (is (= 5 more) "it holds 3, so a step counts 3: five more make 18, a sixth 21")
      (is (= 3 (get-in st [:peaks :step]))))))

;; docs/clocks.md#the-machines-load-admits-work: over max-load nothing more is admitted while one runs, its askers told once and
;; again when it resumes; nothing in flight admits one anyway
(deftest ^{:seon.test/estimate {:ticks 2000000}} max-load-admits-only-under-it-tells-its-askers-and-never-stalls
  (let [load (atom 30.0)
        o (powner :limits (fx/limits {:cores 18 :max-load 10.0}) :load-fn #(deref load))
        held (fn [out] (filterv #(= :prove-held (:op %)) (:out out)))
        [st o1] (fx/owner-proc o :in (preq 1 :seon.fx/processes 1 :seon.prove/askers #{"k1"}))
        _ (is (= [1] (sent o1)) "nothing in flight: one admitted though the load is over")
        [st o2] (fx/owner-proc st :in (preq 2 :seon.fx/processes 1 :seon.prove/askers #{"k2"}))
        _ (is (empty? (sent o2)) "one in flight and the load over: held")
        _ (is (= [["k2" true 30.0 10.0]] (mapv (juxt :key :held :load :max-load) (held o2))) "its asker told once")
        [st o3] (fx/owner-proc st :in (preq 3 :seon.fx/processes 1 :seon.prove/askers #{"k3"}))
        _ (is (= [] (held o3)) "a hold is told once, not at each ask")
        _ (reset! load 2.0)
        [_ o4] (fx/owner-proc st :done {:dest {:rid 1} :request (first (:to-workers o1)) :outcome {:value 1}})]
    (is (= [2 3] (sent o4)) "the load under it: both run")
    (is (= [["k2" false]] (mapv (juxt :key :held) (held o4))) "and the asker told of the hold told it resumed")))

;; the owner's dials are the config in force at each admission: the owner turns max-workers down while he games, and the next
;; admission holds by it, no restart
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-dial-turned-holds-at-the-next-admission
  ;; the dial is turned as the owner turns it: the config in force becomes another value (seon.config/held, replaced when a
  ;; transaction touches an entry), never a redefinition of its reader
  (let [base (:config (seon.config/in-force))
        cf (atom {:config (assoc base :seon.config.proofs/max-workers 18 :seon.config.proofs/max-cores nil :seon.config.proofs/max-load nil)})]
    (with-redefs [seon.config/in-force (fn [] @cf)]
      (let [[st _] (fx/owner-proc (powner) :in (preq 1 :seon.fx/processes 1))
            [st o2] (fx/owner-proc st :in (preq 2 :seon.fx/processes 1))
            _ (is (= [2] (sent o2)) "eighteen workers: a second admitted")
            _ (swap! cf assoc-in [:config :seon.config.proofs/max-workers] 2)
            [_ o3] (fx/owner-proc st :in (preq 3 :seon.fx/processes 1))]
        (is (empty? (sent o3)) "max-workers turned to two: the third waits, no restart")))))

;; Estimate: two derivations of the limits (config-limits: one resolver read and a map, ~5k each) and one compare, ~15k measured
;; (the task's ~200k a read was not borne out here: 14k for the whole test) = ~20000.
(deftest ^{:seon.test/estimate {:ticks 20000}} a-grant-reads-no-config
  ;; AGENTS.md 3a.3, root's review of b8eea50e59: the dials are derived once a config change, held by the reader, never read
  ;; through the resolver at each admission or grant (~200k ticks a call)
  ;; proved by identity, this call's own value, never by the process-wide call record (seon.profile/touched): a config read
  ;; anywhere else in the process (the shared root's procs, another test) counted as the grant's, and the test was flaky (task
  ;; seon.fx/a-grant-reads-no-config-is-its-own-reads-only, deps' run). A recompute builds a new limits map; the held read
  ;; answers the one it holds
  (let [[st l1] (fx/limits-held {})
        [st2 l2] (fx/limits-held st)]
    (is (= l1 l2) "the config unchanged: the same limits")
    (is (identical? l1 l2) "and no config read: the held limits themselves, never derived again")
    (let [c (:config (seon.config/in-force))]
      (with-redefs [seon.config/in-force (fn [] {:config (assoc c :seon.config.proofs/max-workers 3)})]
        (is (= 3 (:max-workers (second (fx/limits-held st2)))) "a turned dial reaches the next read")))))

;; docs/flow.md, the five rules (rule 1, reviewed by flow): a test batch's run is the agent's, never a wait inside a worker's step. The
;; owner asks the api for it as an output (step/ask) and takes its one answer on its own :answers port: no worker, no timer, and its
;; end comes from its sources (the agent's answer, or the api's :seon.system/ended at its death). Exactly one answer frees the batch's
;; weight; a late one finds nothing. Red before: run-item derefed the api's answer inside the worker's step.
;; Estimate: four owner steps, ~0.2M ticks.
(deftest ^{:seon.test/estimate {:ticks 500000}} a-test-batch-is-asked-of-the-api-and-answered-once
  (let [batch (fn [k] (preq k :req {:kind :test :seon.fx/ask {:op :tests :id :a1 :names #{(symbol "app.t-test" (str "t" k))}} :seon.prove/batch (str "b" k)}
                          :seon.fx/class [:test false false] :seon.fx/processes 1))
        asks (fn [out] (filterv #(= :request (:op %)) (:out out)))
        o (powner :kind :t :workers 1 :limits (fx/limits {:cores 4}))
        ;; the one worker busy with a build: a batch takes no worker, so it is asked all the same
        [st _] (fx/owner-proc o :in (preq 0 :req {:kind :build :seon.prove/batch "b0"} :seon.fx/class [:build false true] :seon.fx/processes 1))
        [st out] (fx/owner-proc st :in (batch 1))
        [ask] (asks out)
        _ (is (= {:op :tests :id :a1 :names '#{app.t-test/t1}} (:req ask)) (str "asked of the api as an output: " (pr-str out)))
        _ (is (= [:fx/t :answers] (:route ask)) "answered on the owner's own port")
        _ (is (empty? (:to-workers out)) "no worker takes it")
        _ (is (contains? (:flight st) "b1") "in flight at its weight until its answer")
        [st out] (fx/owner-proc st :answers {:tag (:tag ask) :r {:pass 1 :fail 0 :error 0}})
        _ (is (= [{:dest {:rid 1} :r {:pass 1 :fail 0 :error 0}}] (mapv #(update % :r select-keys [:pass :fail :error]) (filterv :dest (:out out))))
                "its one answer goes to its asker")
        _ (is (not (contains? (:flight st) "b1")) "and its weight comes back")
        [st2 out2] (fx/owner-proc st :answers {:tag (:tag ask) :r {:seon.error/type :seon.system/ended}})]
    (is (empty? (:out out2)) "a late answer (the api's :ended after the agent's own) finds nothing")
    (is (= (:flight st) (:flight st2)) "and gives no weight back twice")))

(deftest owner-refuses-a-spent-budget
  (let [[st _] (fx/owner-proc (owner 1) :in (req 1 :budget 10 :budget-key :task-a))
        [st _] (fx/owner-proc st :done {:dest {:agent "boot/1" :op :fx-reply :k 1} :outcome {:value 1} :budget-key :task-a :cost 10})
        [_ o] (fx/owner-proc st :in (req 2 :budget 10 :budget-key :task-a))]
    (is (= :seon.fx/budget-spent (get-in (first (:out o)) [:r :seon.error/type])))))

;; docs/flow.md#effects-one-owner-and-its-workers-per-kind: at its bound an owner stops reading the agents' :in, but root's own asks
;; (:ask, the api's {:op :fx}) are read and queued still, so the api that put one never waits on a full owner and every other
;; asker's op is answered. Red before: no :ask port (root put on :in, behind the agents' back-pressure, and waited with every op)
(deftest the-api-answers-while-an-effects-queue-is-full
  (let [b (cfg/value :seon.config.fx/buf)
        st (reduce (fn [st k] (first (fx/owner-proc st :in (req k)))) (owner 0) (range b))
        [st2 _] (fx/owner-proc st :ask {:req {:k :root} :prio 0 :dest {:rid 1}})]
    (is (false? ((::flow/input-filter st) :in)) "the agents' requests wait at the bound")
    (is (true? ((::flow/input-filter st) :ask)) "root's are read")
    (is (= (inc b) (count (:queue st2))) "and queued past the bound")
    (is (contains? (:ins (fx/owner-proc)) :ask) "a port of its own, so root's put never waits behind the agents'")))

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

;; Estimate: three worker steps as plain calls, one reader thread and two channel puts: ~200k ticks; 2,000,000
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-batch-call-in-flight-ends-at-the-stop
  ;; the switch-over's L2 (seon.system/an-update-loses-nothing; docs/flow.md#effects-one-owner-and-its-workers-per-kind): a batch kind's
  ;; call waits on its host (the :embed helper's model load, 1.5-1.7 s [M result/c0a59861]) as lines on the worker's :lines, never in the
  ;; step, so a stop's transition shuts it at once; whole frames (:frames) are folded as lines are. Red before: a batch perform could not
  ;; answer :stream, so the wait was the step's and a root's stop waited out the model's load (:fx.embed/0, 1,674 ms [M result/da72e7d0])
  (let [shut (promise) frames (atom [{:ready true} {:answer [7 8]}])
        pull (fn [] (if-let [f (first @frames)] (do (swap! frames rest) f) (do @shut nil)))
        perform (fn [{:keys [batch]}] {:stream {:frames true :pull pull :init [] :step (fn [s f] [(conj s f) []])
                                                :final (fn [s] (mapv (fn [m] {:value [(get-in m [:req :k]) s]}) batch))
                                                :close (fn [_] []) :shut #(deliver shut :closed)}})
        st (fx/worker-proc {:kind :t :owner :fx/t :perform perform})
        item {:batch [(assoc (req 1) :in-ns 0 :taken-ns 0) (assoc (req 2) :in-ns 0 :taken-ns 0)]}
        [st o] (fx/worker-proc st :work item)]
    (is (and (:call st) (nil? o)) "the step returns with the call in flight")
    (let [f1 (a/<!! (:lines st)) f2 (a/<!! (:lines st))]
      (is (= [{:line {:ready true}} {:line {:answer [7 8]}}] [f1 f2]) "each frame is one line, whole")
      (testing "the frames fold into one outcome per request of the batch"
        (let [[st2 _] (fx/worker-proc st :lines f1) [st3 _] (fx/worker-proc st2 :lines f2)
              [_ o] (fx/worker-proc st3 :lines {:end true})]
          (is (= [[1 [{:ready true} {:answer [7 8]}]] [2 [{:ready true} {:answer [7 8]}]]]
                 (mapv #(get-in % [:outcome :value]) (:batch (first (get o [:fx/t :done]))))))))
      (fx/worker-proc st ::flow/stop)
      (is (= :closed (deref shut 0 :open)) "stopped with the call in flight, its :shut is called at once"))))

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

;; docs/source.md#the-next-generation: a test child of the service is a fork of root's agent, and held root's environment: its
;; SEON_SOURCE_ROOT named the main tree, so a test's root it started seeded main's tree under the branch's binary and refused
;; every spawn (runtime's tree 5a37b7bd28 on 04592fd982). KEPT: the subject is what a child process inherits, so it needs real
;; processes. Estimate: 2 setenv + 2 spawns (env, pwd) at ~100k ticks each = ~0.3M ticks.
(deftest ^{:seon.test/estimate {:ticks 1000000} :seon.test/execs ["env" "pwd"]} a-run-of-another-checkout-holds-none-of-roots-environment
  (let [co "/tmp" ; a directory that exists: a process starts in JOLT_PWD
        saved (into {} (for [v (conj fx/child-env-variables "SEON_CHECKOUT")] [v (System/getenv v)]))]
    (try
      (seon.lib/c-setenv "SEON_SOURCE_ROOT" "/the/roots/checkout" 1)
      (seon.lib/c-setenv "SEON_RUNS" "/the/roots/runs" 1)
      (fx/checkout-env! co)
      (let [e (into {} (for [l (clojure.string/split-lines (:out (clojure.java.shell/sh "env"))) :let [[k v] (clojure.string/split l #"=" 2)]] [k v]))]
        (is (not-any? e ["SEON_SOURCE_ROOT" "SEON_RUNS"])
            "a process it starts (bin/system start) takes its source root and runs from checkout, not root's")
        (is (= [co (str co "/target/home")] (mapv e ["SEON_CHECKOUT" "HOME"]))
            "and its checkout and home are checkout's, as bin/test starts a shard"))
      (is (= (.getCanonicalPath (java.io.File. co)) (clojure.string/trim (:out (clojure.java.shell/sh "pwd" "-P"))))
          "and it starts in checkout (JOLT_PWD, which Jolt keeps to itself)")
      (finally (doseq [[v x] saved] (if x (seon.lib/c-setenv v x 1) (seon.lib/c-unsetenv v)))))))

(deftest a-file-the-program-loads-reads
  ;; docs/source.md#a-program-file: seen 2026-11-07, edamame refused three files Jolt loads (a #() inside a #(), a symbol with two slashes in EDN), so a write-back naming them was refused; the check reads as the loader does
  (is (nil? (fx/unread-text "src/x.clj" "(ns x)\n(def f #(do (g #(h %)) %))\n")) "a fn literal inside one reads, as Jolt's reader loads it")
  (is (nil? (fx/unread-text "src/x.clj" "(ns x (:require [clojure.string :as s]))\n(def k ::s/k)\n")) "an alias the ns form declares resolves")
  (is (nil? (fx/unread-text "x.edn" "{a/b/c.jolt {:test 1} :t #env X}\n")) "EDN reads as the EDN reader reads it")
  (is (some? (fx/unread-text "src/x.clj" "(ns x)\n(def f #(do (g %)")) "a form left open is still refused")
  (is (some? (fx/unread-text "x.edn" "{:a 1} ]")) "so is EDN with a stray close"))

;; docs/source.md#a-program-file. KEPT with its two interpreters: zsh -n and perl -c are the subject. Estimate: 2 spawns (~100k ticks
;; each) + 4 EDN/clj reads + 2 config validations of the shipped file (~4M ticks each) = ~10M ticks.
(deftest ^{:seon.test/estimate {:ticks 15000000} :seon.test/execs ["zsh" "perl"]} a-file-is-read-as-its-reader-reads-it
  ;; docs/source.md#a-program-file: measured 2026-11-07, one agent's spit of a schemaless key stopped a new process loading its config; what doesn't read is named where it is read in (an import), never written back again by a commit
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
    (is (re-find #"seon.config.root/view-window" (str (fx/unreadable seon.config/shipped-file (clojure.string/replace-first shipped "{" "{:seon.config.root/view-window 0\n")))) "an invalid value is refused, naming its key")))

;; docs/source.md#indexing-a-tree: an import brings what the tree changed, so a merge the tree never held stays on main.
;; Estimate: git is the subject (tree-id and diff). One init, 2 commits, 3 tree-ids and 3 reads of ~5 git calls each (tree-id 4, diff 1)
;; = ~25 spawns at ~100k ticks = ~3M ticks; the reads of three tiny files are noise.
(deftest ^{:seon.test/estimate {:ticks 6000000} :seon.test/execs ["git"]} a-source-read-since-its-last-tree-reads-only-what-moved
  (let [d (scratch-dir)
        git (fn [& args] (:out (apply clojure.java.shell/sh "git" "-C" d "-c" "user.email=t@t" "-c" "user.name=t" args)))
        read (fn [m] (seon.fx/read-source {:req (merge {:root d :dirs ["src"]} m)}))]
    (try
      (.mkdirs (java.io.File. (str d "/src")))
      (git "init" "-q")
      (spit (str d "/src/a.clj") "(ns a)") (spit (str d "/src/b.clj") "(ns b)") (spit (str d "/notes.clj") "(ns notes)")
      (git "add" ".") (git "commit" "-q" "-m" "one")
      (let [t (fx/tree-id d)]
        (spit (str d "/src/b.clj") "(ns b) (def x 1)") (spit (str d "/notes.clj") "(ns notes) (def y 1)")
        (.delete (java.io.File. (str d "/src/a.clj")))
        (let [r (read {:since t})]
          (is (= {"src/a.clj" nil "src/b.clj" "(ns b) (def x 1)"} (:value r)) "the file that moved, under dirs; a removed file reads nil; notes.clj is outside dirs")
          (is (= #{"src/a.clj" "src/b.clj"} (set (:paths r))) "named by path"))
        (is (contains? (:value (read {:since (apply str (repeat 40 "0"))})) "src/b.clj") "a tree git can't find reads whole")
        (testing "a dir the last import didn't read is read whole, though nothing in it moved"
          (.mkdirs (java.io.File. (str d "/bin"))) (spit (str d "/bin/x.clj") "(ns x)") (git "add" ".") (git "commit" "-q" "-m" "two")
          (let [t3 (fx/tree-id d)
                r (read {:dirs ["src" "bin"] :since t3 :since-dirs ["src"]})]
            (is (not= t t3))
            (is (= {"bin/x.clj" "(ns x)"} (:value r))))))
      (finally (seon.lib/remove-tree! d)))))

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

;; docs/source.md#commit-the-merges-branch. KEPT: git status is the subject. Estimate: 1 init + 3 status spawns at ~100k ticks = ~0.4M ticks.
(deftest ^{:seon.test/estimate {:ticks 1000000} :seon.test/execs ["git"]} a-checkout-says-what-no-merge-carries
  ;; repl-audit B F26: an edit of bin/ports was invisible: (my.branch/changes) read {} and nothing warned
  (let [dir (scratch-dir)]
    (clojure.java.shell/sh "git" "init" "-q" dir)
    (is (= [] (fx/uncommitted dir)))
    (spit (str dir "/ports") "x")
    (is (= ["?? ports"] (fx/uncommitted dir)))
    (is (contract/error? (fx/uncommitted (str dir "/none"))) "git can't read it: said, never no files")
    (seon.lib/remove-tree! dir)))

(deftest a-failed-build-says-what-failed-and-which-form
  ;; deepseek-run3: a REPL form recorded as program stopped the gate's build 6 times, with only a log path to the agent, which called it unrelated
  (let [out "build: seon: jolt build\nUnhandled exception (FileNotFoundException): Could not locate seon/repl_test.jolt\n  at ./src/seon/repl.clj:3:1\n  trace:\n"
        files {"src/seon/repl.clj" "(ns seon.repl)\n\n(intern 'seon.repl-test\n  'let 1)\n\n(def after 1)\n"}]
    (is (= "the gate's build failed: FileNotFoundException: Could not locate seon/repl_test.jolt, at src/seon/repl.clj:3, where your change wrote: (intern 'seon.repl-test\n  'let 1)"
           (fx/build-failure out files)))
    (is (clojure.string/ends-with? (fx/build-failure out {}) "a place your change's files don't hold"))
    (is (= "the gate's build failed and named no exception; it ended: nothing" (fx/build-failure "nothing" files)))
    ;; Context's loop D3: the gate's build failed eight times naming no exception, and its log was in the checkout the run
    ;; removed, so the agent read a path that was gone: how it ended is in the error itself
    (is (= "the gate's build failed and named no exception; it ended: b | c | d | e | f | killed: out of memory"
           (fx/build-failure "a\nb\n\nc\nd\ne\nf\nkilled: out of memory\n" files)))))

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
      (mk "a/one.pass.edn" "1") (mk "b/c/two.red.edn" "22") (mk "three.txt" "333")
      (link "a/loop" d)              ;; a link back to the root
      (link "b/up" (str d "/b"))     ;; a link to its own parent
      (link "b/c/out" "/tmp")        ;; a link out of the tree
      (link "a/file-link.edn" (str d "/three.txt"))
      (let [names (mapv #(.getName ^java.io.File %) (fx/files-under d))]
        (is (= ["file-link.edn" "one.pass.edn" "three.txt" "two.red.edn"] (sort names)) "each file once, a link to a file as a file"))
      (testing "the cache count sees each verdict once"
        (let [r (fx/write-verdicts! d {:id "run" :tree "t"} {'a/t {:test 'a/t :digest "d1" :pass 1 :fail 0 :error 0}} {} 1)]
          (is (= {:recorded 1} r))
          ;; a bound of 1 byte evicts every verdict not just written (verdict-listing counts .pass.edn and .red.edn): the walk ended and removed the old ones
          (is (not (.exists (java.io.File. d "a/one.pass.edn"))))
          (is (not (.exists (java.io.File. d "b/c/two.red.edn"))))))
      (finally (seon.lib/remove-tree! d)))))

;; a gate's refusal keeps what stopped it: its last 3 lines were where and two verdicts, and the error was cut
(deftest a-runners-end-keeps-its-error
  (let [end (@#'seon.fx/runner-end "prove: run\nerror[analyze/unknown-namespace]: No such namespace: print\n  --> 2566:28\nTEST FAILED (the plan)\nPROVE FAILED\n\n")]
    (is (clojure.string/includes? end "No such namespace: print"))
    (is (clojure.string/includes? end "2566:28")))
  ;; a base's runner that threw (deps' land, 2026-11-14): the exception's head came before 14 trace frames, so its last lines
  ;; were frames alone and the check said "made no plan" with no why
  (let [end (@#'seon.fx/runner-end (str "prove: plan\nUnhandled exception (ExceptionInfo): the kept store refused its open\n  ex-data: {:path \"/s\"}\n  trace:\n"
                                        (clojure.string/join "\n" (map #(str "    frame-" %) (range 14))) "\n"))]
    (is (clojure.string/includes? end "the kept store refused its open") end)
    (is (clojure.string/includes? end "frame-13") "and where it ended")))

(deftest a-build-the-analyzer-stopped-names-its-cause
  ;; docs/merging.md#a-merge-runs-what-the-change-reaches: a gate on a tree that doesn't compile says what stopped it and the agent's form, not "named no exception"
  (let [out "error[analyze/unresolved-symbol]: Unable to resolve symbol: h in this context\n  --> /x/runs/merge-tests/abc/src/app/a.clj:2:3\n"
        said (seon.fx/build-failure out {"src/app/a.clj" "(ns app.a)\n(defn f [] (h))\n"})]
    (is (clojure.string/includes? said "Unable to resolve symbol: h") said)
    (is (clojure.string/includes? said "src/app/a.clj:2") said)
    (is (clojure.string/includes? said "(defn f [] (h))") said))
  (is (= "the gate's build failed and named no exception; it ended: nothing" (seon.fx/build-failure "nothing" {}))))


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

;; Estimate: git is the subject. init + empty commit + 3 git-commits (~8 spawns each on a checked-out branch, ~4 refused or plumbed) + 6 reads
;; = ~30 spawns at ~100k ticks = ~3M ticks.
(deftest ^{:seon.test/estimate {:ticks 5000000} :seon.test/execs ["git"]} a-write-back-onto-a-checked-out-branch-leaves-that-tree-clean
  ;; docs/source.md#commit-the-merges-branch, 2026-11-11: the standing root's write-back commits onto
  ;; jolt/merges, the branch .worktrees/merges has checked out. git-commit moved the ref by plumbing, so that tree's HEAD
  ;; moved and its index and files stayed at the parent: each merged file a staged deletion, and bin/update's merge of main
  ;; then refused (or, unchecked, did nothing). A commit onto a branch a worktree has checked out goes through that tree:
  ;; its files written, its index, its HEAD, one commit. Red before: git status showed "D  src/demo/half.clj"
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-fx-checked-out-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (clojure.string/trim (str (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@localhost" args)))))
        commit #(fx/git-commit {:req (merge {:dir dir :branch "jolt/merges" :message "Seon merge"} %)})]
    (try
      (git "init" "-q" "-b" "jolt/merges")
      (git "commit" "-q" "--allow-empty" "-m" "one")
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
        (spit (str dir "/src/demo/half.clj") "(ns demo.half)\n")
        (let [r (fx/git-commit {:req {:dir dir :branch "jolt/sync-x" :message "sync" :files {"b.txt" "b\n"}}})]
          (is (some? (get-in r [:value :sha])) (pr-str r))
          (is (= "" (git "status" "--short")) "the checked-out tree untouched")
          (is (not (.exists (java.io.File. (str dir "/b.txt")))) "and no file written into it")))
      (finally (seon.lib/remove-tree! dir)))))

;; Estimate: git is the subject. init + empty commit + 4 git-commits (~8 spawns, the refused ones ~3) + 5 reads = ~25 spawns at ~100k ticks
;; = 2.5M ticks.
(deftest ^{:seon.test/estimate {:ticks 4000000} :seon.test/execs ["git"]} main-moves-only-from-the-commit-the-store-was-imported-from
  ;; docs/source.md#commit-the-merges-branch: git's main follows the store's main (task seon.source/a-merge-moves-gits-main-to-the-
  ;; stores-files): a commit carries :expect, the commit the store's main was imported from, and the ref moves only from it, as
  ;; one compare-and-swap (git update-ref NEW EXPECT). A landing that moved main since is never undone by the store's older text:
  ;; the answer is :seon.fx/ref-moved, nothing written
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-fx-expect-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (clojure.string/trim (str (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@localhost" args)))))
        commit #(fx/git-commit {:req (merge {:dir dir :branch "main" :message "Seon merge"} %)})]
    (try
      (git "init" "-q" "-b" "main")
      ;; a top-level path the tree id leaves out (:seon.config.source/tree-excludes), committed, as main holds claude_scratch/
      (.mkdirs (java.io.File. (str dir "/claude_scratch"))) (spit (str dir "/claude_scratch/n") "n\n")
      (git "add" "-A") (git "commit" "-q" "-m" "one")
      (let [imported (git "rev-parse" "HEAD")
            r (commit {:expect imported :files {"src/demo/x.clj" "(ns demo.x)\n"}})]
        (is (= (get-in r [:value :sha]) (git "rev-parse" "HEAD")) (pr-str r))
        (is (= (fx/tree-id dir) (:tree (:value r))) "its tree, read from the commit, is the clean checkout's tree id (commit-tree-id: never a walk of the disk)")
        (is (= imported (git "rev-parse" "HEAD^")) "one commit on the commit the store was imported from")
        (is (= "(ns demo.x)\n" (slurp (str dir "/src/demo/x.clj"))) "the checked-out tree moved with it (its clean state is proved by the checked-out-branch test)")
        (is (= imported (:expected (:value r))) "the answer names the commit it was made from"))
      (testing "a landing moved main after the store's import: refused, the landing kept"
        (let [stale (git "rev-parse" "HEAD")]
          (spit (str dir "/c.txt") "landed\n") (git "add" "-A") (git "commit" "-q" "-m" "a landing")
          (let [landed (git "rev-parse" "HEAD")
                r (commit {:expect stale :files {"c.txt" "the store's older text\n"}})]
            (is (= :seon.fx/ref-moved (:seon.error/type r)) (pr-str r))
            (is (= [stale landed] ((juxt :expected :found) r)) "naming the commit it expected and the one main holds")
            (is (= landed (git "rev-parse" "HEAD")) "main unmoved")
            (is (= "landed\n" (slurp (str dir "/c.txt"))) "and the landing's file as it landed"))))
      (testing "a branch no tree has checked out: the same compare-and-swap"
        (git "branch" "-q" "side" "HEAD^")
        (let [r (commit {:branch "side" :expect (git "rev-parse" "HEAD") :files {"d.txt" "d\n"}})]
          (is (= :seon.fx/ref-moved (:seon.error/type r)) (pr-str r))))
      (finally (seon.lib/remove-tree! dir)))))

;; docs/source.md#commit-the-merges-branch: a seon_done's git commit carries the landing's own message, so a branch commit's Deletes: reaches
;; main: its own commits since the last landing it read, its merges of main left out. Estimate: git is the subject, ~15 spawns at ~100k
;; ticks = 1.5M ticks
(deftest ^{:seon.test/estimate {:ticks 3000000} :seon.test/execs ["git"]} a-landing-message-is-the-branchs-own-commits-since-its-last-landing
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-fx-landing-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (clojure.string/trim (str (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@localhost" args)))))
        commit (fn [f m] (spit (str dir "/" f) m) (git "add" "-A") (git "commit" "-q" "-m" m))]
    (try
      (git "init" "-q" "-b" "main") (commit "a" "base")
      (git "checkout" "-q" "-b" "jolt/s") (commit "b" "A fix\n\nDeletes: x, onto y")
      (git "checkout" "-q" "main") (commit "c" "main moved") (git "checkout" "-q" "jolt/s") (git "merge" "-q" "--no-edit" "main")
      (let [l (fx/landing-message dir nil)]
        (is (clojure.string/includes? (:message l) "Deletes: x, onto y") (pr-str l))
        (is (not (clojure.string/includes? (:message l) "Merge")) "a merge of main is no part of it")
        (is (not (clojure.string/includes? (:message l) "main moved")) "nor main's own commits")
        (is (= (git "rev-parse" "HEAD") (:head l)))
        (is (nil? (fx/landing-message dir (:head l))) "landed: nothing new since")
        (commit "d" "Next fix")
        (is (= "Next fix" (:message (fx/landing-message dir (:head l)))) "the next landing carries only its own"))
      (finally (seon.lib/remove-tree! dir)))))

;; task seon.writer/a-branchs-files-are-written-out-to-a-tree: a branch's files are written out as a commit on the base main was imported
;; from, its pins as gitlinks, the ref moved from whatever it held, so seon.fx/build of it makes the tree a candidate builds from. Estimate:
;; git is the subject, ~12 spawns at ~100k ticks = 1.2M
(deftest ^{:seon.test/estimate {:ticks 3000000} :seon.test/execs ["git"]} a-branchs-files-are-written-out-as-a-commit
  (let [dir (.getCanonicalPath (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/seon-fx-writeout-" (random-uuid))) (.mkdirs)))
        git (fn [& args] (clojure.string/trim (str (:out (apply clojure.java.shell/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@localhost" args)))))
        pin "1111111111111111111111111111111111111111"]
    (try
      (git "init" "-q" "-b" "main") (spit (str dir "/a.edn") "{:a 1}\n") (spit (str dir "/b.txt") "b\n") (git "add" "-A") (git "commit" "-q" "-m" "base")
      (let [base (git "rev-parse" "HEAD")
            out (fn [files] (fx/write-out {:req {:dir dir :ref "refs/seon/candidate/x" :base base :files files :modes {} :links {"reference-code/lib" pin} :message "m"}}))
            r (out {"a.edn" "{:a 2}\n"})
            sha (get-in r [:value :sha])]
        (is (= "{:a 2}" (git "show" (str sha ":a.edn"))) (pr-str r))
        (is (= "b" (git "show" (str sha ":b.txt"))) "every other file as base holds it")
        (is (re-find (re-pattern (str "^160000 commit " pin "\t")) (git "ls-tree" sha "reference-code/lib")) "a pin as its gitlink")
        (is (= base (git "rev-parse" (str sha "^"))) "on the base main was imported from")
        (is (= base (git "rev-parse" "HEAD")) "no tree moved")
        (let [r2 (out {"b.txt" "c\n"}) sha2 (get-in r2 [:value :sha])]
          (is (= sha2 (git "rev-parse" "refs/seon/candidate/x")) "written out again, the ref moves to it")
          (is (= base (git "rev-parse" (str sha2 "^"))) "from the base again, never on the last write-out")
          (is (= "{:a 1}" (git "show" (str sha2 ":a.edn"))) "so it holds the branch's files now alone")
          (is (= "" (git "branch" "--list" "*candidate*")) "never a branch")
          (is (= {:dropped "refs/seon/candidate/x"} (:value (fx/drop-write-out {:req {:dir dir :ref "refs/seon/candidate/x"}}))))
          (is (= "" (git "for-each-ref" "refs/seon/")) "dropped with its candidate")))
      (finally (seon.lib/remove-tree! dir)))))

;; docs/merging.md#verdicts-are-a-cache: a config value naming a file enters a test's digest as the file's name, never where the
;; machine keeps it, so one test has one digest from any checkout (the embed model in the main tree's cache read "<checkout>/..."
;; from the main tree and an absolute path from a worktree: 103 of 181 process digests apart on the standing system, 2026-11-12)
(deftest a-config-file-is-its-name-from-every-checkout
  (let [model "/home/m/main/target/cache/models/2188ac.gguf"
        cfg {:seon.config.vector/model model :seon.config.root/wiki-dirs ["docs" "."]}]
    (is (= (fx/config-input cfg "/home/m/main") (fx/config-input cfg "/home/m/main/.worktrees/w"))
        "from the main tree and from a worktree, one rendering")
    (is (= "<file>/2188ac.gguf" (get (fx/config-input cfg "/home/m/main") :seon.config.vector/model)))
    (is (= "[\"docs\" \".\"]" (get (fx/config-input cfg "/x") :seon.config.root/wiki-dirs)) "a value that names no file is its text")))


;; docs/source.md#tests-run-by-reach: a green whose run recorded what it read keeps that record beside its verdicts, the
;; newest only, and a red keeps none: what a plan's reuse check reads, one small file a test
(deftest ^{:seon.test/estimate {:ticks 5000000}} a-green-keeps-what-its-run-read-beside-its-verdicts
  (let [dir (str (System/getProperty "java.io.tmpdir") "/seon-reads-" (random-uuid)) t 'a.r-test/t]
    (try
      (fx/write-verdicts! dir {:id "r"} {t {:digest "d1" :pass 1 :fail 0 :error 0 :reads {'a/f "f1"}}} {})
      (fx/write-verdicts! dir {:id "r"} {t {:digest "d2" :pass 1 :fail 0 :error 0 :reads {'a/f "f2"}}} {})
      (fx/write-verdicts! dir {:id "r"} {t {:digest "d3" :pass 0 :fail 1 :error 0 :reads {'a/f "f3"}}} {})
      (is (= {t [{:digest "d2" :reads {'a/f "f2"}}]} (fx/held-reads dir [t 'a.r-test/none])) "the newest green's, and none for a test with none")
      (finally (seon.lib/sh "rm" "-rf" dir)))))

(deftest ^{:seon.test/estimate {:ticks 100000}} a-stream-that-goes-quiet-past-its-kinds-bound-is-ended-reached
  ;; docs/flow.md#effects-one-owner-and-its-workers-per-kind: a streaming call is bounded by its kind's :seon.bound/ms of
  ;; quiet, re-armed by each line (a long reply that keeps streaming is never cut); past it the worker shuts the connection
  ;; and the call's outcome is :seon.bound/reached, so its worker is free again and its asker answered
  (let [value cfg/value
        gate (promise)
        shut? (atom false)
        lines-in (atom ["data: a"])
        pull (fn [] (if-let [l (first @lines-in)] (do (swap! lines-in rest) (.getBytes (str l "\n") "UTF-8")) @gate))]
    (with-redefs [cfg/value (fn [k] (if (= k :seon.config.fx/bounds) {:t {:seon.bound/ms 10}} (value k)))]
      (let [st (fx/worker-proc {:kind :t :owner :fx/t
                                :perform (fn [_] {:stream {:pull pull :init [] :step (fn [s l] [(conj s l) []]) :final (fn [s] {:value s})
                                                           :close (fn [_] []) :shut (fn [] (reset! shut? true) (deliver gate nil))}})})
            [st o] (fx/worker-proc st :work (assoc (req 1) :in-ns 0 :taken-ns 0))
            _ (is (nil? o) "the call streams: no outcome yet")
            ;; feed the worker what reaches its :lines until its call ends: the one line, the bound's due, the reader's end
            [st done] (loop [st st n 0]
                        (let [m (a/<!! (:lines st)) [st o] (fx/worker-proc st :lines m)]
                          (if-let [d (get o [:fx/t :done])] [st (first d)] (if (< n 20) (recur st (inc n)) [st nil]))))]
        (is @shut? "the connection was shut")
        (is (= :seon.bound/reached (get-in done [:outcome :seon.error/type])) (pr-str done))
        (is (= {:seon.bound/ms 10} (get-in done [:outcome :seon.bound/bound])))
        (is (nil? (:call st)) "the worker is free for its next item")))))
;; docs/source.md#the-binary-records-its-program: the binary's identity, one implementation (seon.fx/binary-digest), checked on trees
;; of a tiny repository of its own holding only the files the identity reads (git's own add and write-tree).
(defn- tiny-tree
  "A git repository at dir holding files ({path text}), created on first use: the tree id git writes for exactly those files (git add -A,
  write-tree: two spawns), in an index of the repository's own. No commit, no other repository read."
  [dir files]
  (let [git (fn [& args] (let [r (apply clojure.java.shell/sh "git" "-C" dir args)]
                           (assert (zero? (:exit r)) (str args (:err r))) (clojure.string/trim (:out r))))]
    (when-not (.exists (java.io.File. (str dir "/.git"))) (git "init" "-q"))
    (doseq [[p text] files] (.mkdirs (.getParentFile (java.io.File. (str dir "/" p)))) (spit (str dir "/" p) text))
    (git "add" "-A")
    (git "write-tree")))

(def ^:private identity-files
  "The files the binary's identity reads, as a tiny tree holds them: a build input, the shipped config stating one at-load key and one
  build key, and two schema files marking those keys and two more by :default (api-writes at-load, view-window not marked)."
  {"bin/build-input" "x\n"
   "resources/seon/config/default.edn" "{:seon.config.root/max-agents 3 :seon.config.build/zz-first \"a\" :seon.config.build/inputs [\"bin/build-input\"]}\n"
   "resources/seon/schemas/seon.config.build.edn" "{:seon.config.build/zz-first [:string {:seon.config/build true}]}\n"
   "resources/seon/schemas/seon.config.root.edn" (str "{:seon.config.root/max-agents [:int {:seon.config/at-load true}]\n"
                                                      " :seon.config.root/api-writes [:int {:seon.config/at-load true :default 1}]\n"
                                                      " :seon.config.root/view-window [:int {:default 1}]}\n")})

;; Estimate: git is the subject, four to five spawns a digest (ls-tree x3, show x2) or per identity-lines. 3 digests + 2 differs (2 identity-lines
;; each) + 5 trees (2 spawns) + init = ~36 spawns at ~100k ticks = ~3.6M ticks (before: the real tree's 40M).
(deftest ^{:seon.test/estimate {:ticks 6000000} :seon.test/execs ["git"]} only-what-a-new-process-reads-moves-the-binary-digest
  ;; 2026-11-15: bin/binary-digest found :seon.config/at-load and :seon.config/place keys by a line regex, which missed a schema
  ;; file's first entry (9 of 57 keys: a web port change moved no identity) and never held jolt-compiler-options, which the
  ;; compiler reads. The identity now holds the keys a schema marks :seon.config/build, at-load or place, read as data
  ;; wherever their entry sits.
  (let [root (scratch-dir)
        cfg-p "resources/seon/config/default.edn" build-p "resources/seon/schemas/seon.config.build.edn"
        root-p "resources/seon/schemas/seon.config.root.edn"
        tree (fn [m] (tiny-tree root (merge identity-files m)))
        differs (fn [a b] (#'fx/build-differs root root a b))
        base (tree {})
        d0 (fx/binary-digest root base)
        swap (fn [p from to] (clojure.string/replace (identity-files p) from to))]
    (try
      (is (string? d0) (str "answered " d0))
      ;; a build key whose entry is its file's first moves the digest with its value (the regex's miss)
      (is (not= d0 (fx/binary-digest root (tree {cfg-p (swap cfg-p "zz-first \"a\"" "zz-first \"b\"")}))) "a change of what the compiler reads is a build")
      ;; a key read at load moves it, and a mismatch names its cause from the same lines (root, 2026-11-15: a schema-moved key answered
      ;; :seon.process/differs []); the differs being non-empty is the digest's moving, which digests the same lines
      (is (= [":seon.config.root/max-agents"] (differs base (tree {cfg-p (swap cfg-p "max-agents 3" "max-agents 7")})))
          "a change of a key read at load is a new process's, named")
      (is (= [":seon.config.root/api-writes"] (differs base (tree {root-p (swap root-p "api-writes [:int {:seon.config/at-load true :default 1" "api-writes [:int {:seon.config/at-load true :default 7")})))
          "a change of an at-load key's schema default is a new process's, named")
      (is (= d0 (fx/binary-digest root (tree {root-p (swap root-p "view-window [:int {:default 1" "view-window [:int {:default 7")}))) "a change of a key the branch holds is no build")
      (finally (seon.lib/remove-tree! root)))))

;; 2026-11-15 15:53: an untracked clone in the main checkout (reference-code/core.async-flow) entered bin/tree's tree as a gitlink,
;; and every reader of a gitlink took it for a pin: the build's identity moved (25fd5003 against c7311e6b) and spawns were refused.
;; A pin is a gitlink .gitmodules names. docs/source.md#the-binary-records-its-program. Estimate: 2 tree ids at 4 spawns + 2 inits + add + commit
;; = ~12 spawns at ~100k ticks = ~1.2M ticks (before: also an outer commit, 3 more spawns).
(deftest ^{:seon.test/estimate {:ticks 3000000} :seon.test/execs ["git"]} an-untracked-nested-repo-moves-no-tree
  ;; the outer repository needs no commit: a tree id is HEAD's tree (none before a first commit) with every unignored file added
  (let [dir (scratch-dir)
        sh (fn [d & args] (let [r (apply clojure.java.shell/sh (concat args [:dir d :env (assoc (into {} (System/getenv)) "GIT_CONFIG_GLOBAL" "/dev/null")]))]
                            (assert (zero? (:exit r)) (str args (:err r))) (clojure.string/trim (:out r))))]
    (try
      (sh dir "git" "init" "-q") (spit (str dir "/a") "a")
      (let [before (fx/tree-id dir)]
        (.mkdirs (java.io.File. (str dir "/clone")))
        (sh (str dir "/clone") "git" "init" "-q") (spit (str dir "/clone/a") "a") (sh (str dir "/clone") "git" "add" "a")
        (sh (str dir "/clone") "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "a")
        (is (= before (fx/tree-id dir)) "a nested repository .gitmodules does not name is no part of the tree"))
      (finally (seon.lib/remove-tree! dir)))))

;; Estimate: one tree digest of the tiny repository (five spawns) kept by tree id; a second code's own digest (five more); the unreadable
;; tree is one failing spawn; the init and tree two = ~15 spawns at ~100k ticks = ~1.5M ticks (before: the real tree, 5M).
(deftest ^{:seon.test/estimate {:ticks 3000000} :seon.test/execs ["git"]} runtime-for-answers-for-the-running-binary
  ;; 2026-11-15: root (pid 58108) ran digest c7311e6b while target/seon.digest said 76dc758d, a later build's, and runtime-for
  ;; answered current off the file [M root result/aff6b6de, cf6571ca]. It compares the running binary's identity, its build
  ;; record's :digest held since boot (seon.program/binary-program). The checkout is a tiny scratch repository in the temp dir, so the
  ;; digest it keeps (target/runtime-for) lands there, never in the checkout
  (let [root (scratch-dir)
        tree (tiny-tree root identity-files)]
    (try
      (with-redefs [cfg/checkout (fn [] root) cfg/process (let [p cfg/process] (fn [] (assoc (p) :source-root nil)))]
        (let [r (fx/runtime-for tree {:record {:digest "another-build" :inputs (:inputs (seon.program/binary-program))}})
              d (:seon.process/branch-digest r)]
          (is (= :seon.process/env-mismatch (:seon.error/type r)) (str "a running binary of another identity is told: " r))
          (is (= "another-build" (:seon.process/binary-digest r)) "naming the running binary's identity")
          (is (string? d) "and the digest of the tree it was asked for")
          (is (= d (:digest (fx/runtime-for tree {:record {:digest d}}))) "and its own tree's is current")
          (is (= (str root "/target/seon") (:binary (fx/runtime-for tree {:record {:digest d}}))) "answering the binary of the source root")
          ;; a tree this repository cannot read is the identity's typed error, never a digest of what could be read
          (is (= :seon.fx/io-error (:seon.error/type (fx/runtime-for "0123456789abcdef0123456789abcdef01234567"))) "an unreadable tree is told")
          ;; the kept digest is keyed by the code that computed it: a value a changed binary-digest computed is never read back
          (let [kept (fn [code] (java.io.File. (str root "/target/runtime-for/" tree "." (seon.lib/digest code) ".digest")))]
            (is (.isFile ^java.io.File (kept (seon.program/running-form 'seon.fx/binary-digest))) "this code keeps its value in the scratch tree")
            (with-redefs [seon.program/running-form (fn [_] "another-code")]
              (fx/runtime-for tree)
              (is (.isFile ^java.io.File (kept "another-code")) "another code keeps its own value")))))
      (finally (seon.lib/remove-tree! root)))))
