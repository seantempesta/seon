(ns seon.test.agent
  "Test agents: one test request is a run agent, and each member is a one-body
  agent on its own branch, running through the one REPL path.

  Owners, as the audit's option B revised by review 57814415b (all six
  blockers accepted by root):

  world         the member's ONE world value: the one-path spec's per-entrant
                world (a :seon.db/custody-request extension: cluster, branch,
                connection, db value, :seon.db/writer, SCI fork, member).
                It crosses every thread hop as a value; `enter` re-establishes
                the bindings from it on the thread that runs the body (B2).
  member-request the one-path test entrant (seon.turn.batch/test-request,
                spec rev 3): one entry whose evaluate stage is the body, the
                world entered by the :seon.batch/around stage, and the
                recording SUPPLIED as the :seon.batch/settle stage (B6).
  start-body!   the body on its own virtual thread; its `finally` is the exit
                fact, sent to the member graph. Nothing else is exit (B1).
  roster        the process roster of running bodies: at most `limit`
                top-level bodies run; a body's nested request never takes a
                slot (its parent holds one), so nesting cannot starve (B3).
  resume-listener  kernel pressure's resume notice, from the collectors'
                completion notifications, waking every waiting run (B5).
  host-global   the tests whose invocation graph (body, local helpers,
                fixtures, namespace load effects) reaches a JVM-global
                mutation; they run on the isolated host (B4).
  ready!        first-load arming as a positive ready value per key, taken
                once by concurrent callers, before any body (B4)."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [seon.await :as await]
            [seon.batch :as batch]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as sflow]
            [seon.schema :as schema]
            [seon.sci.kernel :as kernel]
            [seon.test :as test])
  (:import [java.lang.management ManagementFactory]
           [javax.management NotificationEmitter NotificationListener]))

;;; ---------------------------------------------------------------------------
;;; The world, as a value, and its entrance on any thread (B2)
;;; ---------------------------------------------------------------------------

(defn member-world
  "The member's one world value, read from its execution handle once."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle] :map]}
  [member]
  {:seon.cluster/name (:seon.cluster/name member)
   :seon.agent/branch (:seon.agent/branch member)
   :seon.db/connection (:seon.db/connection member)
   :seon.db/db (db/db (:seon.db/connection member))
   :seon.db/writer db/transact!
   :seon.sci.eval/ctx (:seon.sci.eval/ctx member)
   :seon.schema/projection (:seon.schema/projection member)
   :seon.test/member member})

(defn enter
  "Run `thunk` inside `world` on THIS thread: its custody (the elided seon.db
  arities reach its branch), its projection, its member and its SCI world.
  Nothing is inherited from the submitting thread."
  {:malli/schema [:=> [:cat :map [:=> [:cat] :any]] :any]}
  [world thunk]
  (db/call-with-custody
   (select-keys world [:seon.db/connection])
   (fn []
     (schema/call-with-projection
      (:seon.schema/projection world)
      (fn []
        (binding [test/*member* (:seon.test/member world)
                  kernel/*host-world* (:seon.sci.eval/ctx world)]
          (thunk)))))))

;;; ---------------------------------------------------------------------------
;;; The member's batch: the one path's test entrant (B6)
;;; ---------------------------------------------------------------------------

(defn member-request
  "One-path request for one body: read one synthetic entry, evaluate the body
  inside the world, settle through the supplied recording `record`."
  {:malli/schema [:=> [:cat :map [:=> [:cat] :any] [:=> [:cat :map :map] :map]] :map]}
  [world body record]
  {:seon.batch/text "(body)"
   :seon.batch/database (:seon.db/db world)
   :seon.batch/read-state (fn [text] {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}
                                      :seon.batch/repairs []})
   :seon.batch/read-next (fn [_ {v :seon.batch/version}]
                           (if (zero? (:seon.batch/pos v))
                             {:seon.batch/form ::body :seon.batch/start 0 :seon.batch/end 6
                              :seon.batch/version (assoc v :seon.batch/pos 6) :seon.batch/repairs []}
                             {:seon.batch/eof true :seon.batch/version v :seon.batch/repairs []}))
   :seon.batch/evaluate (fn [_ _ _] (body))
   :seon.batch/ns-name (fn [_] 'user)
   :seon.batch/around (fn [_ run] (enter world run))
   :seon.batch/settle (fn [_ b] (record world b))})

(defn start-body!
  "Run the member's batch on its own virtual thread. The thread's `finally`
  sends the exit fact, carrying the batch; that message is the only exit."
  {:malli/schema [:=> [:cat :map [:=> [:cat] :any] [:=> [:cat :map :map] :map] :any] :any]}
  [world body record exit-chan]
  (Thread/startVirtualThread
   (fn []
     (let [started (System/nanoTime) outcome (volatile! nil)]
       (try (vreset! outcome {::batch (batch/evaluate-batch (member-request world body record))})
            (catch Throwable t (vreset! outcome {::error t}))
            (finally
              (async/put! exit-chan (assoc @outcome ::started-at started ::exited-at (System/nanoTime)))))))))

;;; ---------------------------------------------------------------------------
;;; The process roster: admission bound and wakes (B3, B5)
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Process roster: running bodies {member-key {::run id ::nested? b}} and
  waiting runs {run-id wake-chan}. One per process: bodies are host objects."}
  body-roster (atom {::running {} ::waiting {}}))

(defn- admitted
  [held limit member-key run-id nested?]
  (let [top (count (remove ::nested? (vals (::running held))))]
    (if (or nested? (< top limit))
      (assoc-in held [::running member-key] {::run run-id ::nested? nested?})
      held)))

(defn admit!
  "Admit one member when no pressure is held and a top-level slot is free; a
  nested member never takes a slot. True when admitted."
  {:malli/schema [:=> [:cat :any :int :any :any :any :boolean] :boolean]}
  [roster limit pressure-state member-key run-id nested?]
  (and (nil? (kernel/memory-pressure pressure-state))
       (contains? (::running (swap! roster admitted limit member-key run-id nested?)) member-key)))

(defn wake-all!
  "Offer a wake to every waiting run (sliding: a wake says only 'look')."
  {:malli/schema [:=> [:cat :any] :nil]}
  [roster]
  (doseq [c (vals (::waiting @roster))] (async/offer! c :wake))
  nil)

(defn release!
  "A body exited: free its slot and wake every waiting run."
  {:malli/schema [:=> [:cat :any :any] :nil]}
  [roster member-key]
  (swap! roster update ::running dissoc member-key)
  (wake-all! roster))

(defn resume-listener
  "The kernel's resume notice: after each collection, a held pressure that
  clears wakes every waiting run. Kernel-owned state, no second monitor."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [roster pressure-state]
  (reify NotificationListener
    (handleNotification [_ _ _]
      (when (and @pressure-state (nil? (kernel/memory-pressure pressure-state)))
        (wake-all! roster)))))

(defn watch-resume!
  "Subscribe `listener` to every collector's completion notices; answers the
  function that unsubscribes it."
  {:malli/schema [:=> [:cat :any] [:=> [:cat] :nil]]}
  [listener]
  (let [emitters (filter #(instance? NotificationEmitter %) (ManagementFactory/getGarbageCollectorMXBeans))]
    (doseq [e emitters] (.addNotificationListener ^NotificationEmitter e listener nil nil))
    (fn [] (doseq [e emitters] (.removeNotificationListener ^NotificationEmitter e listener)) nil)))

;;; ---------------------------------------------------------------------------
;;; First-load arming as a positive ready value (B4)
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Readiness per [loaded program identity, namespaces]: one delay each."}
  ready-values (atom {}))

(defn ready!
  "The ready value for `k`: `load!` runs once however many callers arrive;
  every caller waits for it before starting a body."
  {:malli/schema [:=> [:cat :any :any [:=> [:cat] :any]] :any]}
  [readiness k load!]
  @(get (swap! readiness update k #(or % (delay (load!)))) k))

;;; ---------------------------------------------------------------------------
;;; Graphs: the member agent and the run agent (B1)
;;; ---------------------------------------------------------------------------

(defn member-step
  "A member agent's one proc: on its body's exit fact, release the member's
  branch, then answer the run. It never waits on the body."
  ([] {:ins {:exit "The body thread's exit fact"} :outs {}})
  ([args] (assoc args ::flow/in-ports {:exit (::exit-chan args)}))
  ([state _transition] state)
  ([state _port exit]
   (let [failure (try (agent/release-context! (::member state)) nil (catch Throwable t t))]
     (async/put! (::done state) (assoc exit ::member-key (::member-key state)
                                       ::release-failure failure ::released-at (System/nanoTime))))
   [state nil]))

(defn- join!
  [{graph :seon.flow/graph executor :seon.flow/executor}]
  (flow/stop graph)
  (sflow/join-graph! {:seon.flow/executor executor
                      :seon.await/bound {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
                                         :seon.await/config-value 2000
                                         :seon.await/deadline-nanos (+ (System/nanoTime) 2000000000)}
                      :seon.await/diagnostic {:seon.error/layer ::member :seon.error/operation `join!}}))

(defn- start-member
  [state {k ::key body ::body}]
  (try
    (let [member (agent/acquire-context! (::requester state) nil {:seon.agent/isolate? true})
          w (member-world member)
          exit-chan (async/chan 1)
          g (sflow/start-graph!
             {:seon.flow/projection (:seon.schema/projection member)
              :seon.flow/graph-definition
              {:procs {:member {:proc (sflow/var-process #'member-step :io
                                                         {:seon.env/environment (env/of member)
                                                          ::exit-chan exit-chan ::member member
                                                          ::member-key k ::done (::done state)})}}
               :conns []}})]
      (start-body! w body (::record state) exit-chan)
      (update state ::running assoc k (assoc g ::branch (:seon.agent/branch member) ::started-at (System/nanoTime))))
    (catch Throwable t
      (release! (::roster state) k)
      (update state ::results assoc k {::error (ex-message t)}))))

(defn- finish
  [state]
  (if (and (empty? (::running state)) (or (::answered? state) (empty? (::pending state))))
    (do (when-not (::answered? state)
          (async/put! (::answer state) {::results (::results state) ::unfinished [] ::pending []}))
        (swap! (::roster state) update ::waiting dissoc (::run-id state))
        (async/put! (::finished state) {::results (::results state) ::finished-at (System/nanoTime)})
        (assoc state ::answered? true))
    state))

(defn run-step
  "A test request's run agent: admits pending members on each wake, joins a
  member's graph after its body exited, answers at completion or deadline,
  and keeps ownership of unfinished members until each exits."
  ([] {:ins {:wake "Look again" :done "A member exited" :deadline "The request bound"} :outs {}})
  ([args] (-> args
              (assoc ::flow/in-ports {:wake (::wake args) :done (::done args) :deadline (::deadline args)})
              (assoc ::running {} ::results {} ::answered? false)))
  ([state _transition] state)
  ([state port message]
   (case port
     :wake
     [(finish (loop [st state]
                (let [m (first (::pending st))]
                  (if (and m (not (::answered? st))
                           (admit! (::roster st) (::limit st) (::pressure st) (::key m) (::run-id st) (::nested? st)))
                    (recur (start-member (update st ::pending (comp vec rest)) m))
                    st))))
      nil]
     :done
     (let [k (::member-key message)
           joined (join! (get-in state [::running k]))]
       (release! (::roster state) k)
       [(finish (-> state
                    (update ::running dissoc k)
                    (update ::results assoc k (assoc (select-keys message [::batch ::error ::started-at ::exited-at
                                                                           ::released-at ::release-failure])
                                                     ::joined-at (System/nanoTime) ::join joined))))
        nil])
     :deadline
     (if (::answered? state)
       [state nil]
       (let [unfinished (vec (keys (::running state))) pending (mapv ::key (::pending state))
             stop ((::record-stop state) (::run-id state) unfinished pending)]
         (async/put! (::answer state) {::results (::results state) ::unfinished unfinished ::pending pending
                                    ::stop stop ::answered-at (System/nanoTime)})
         [(finish (assoc state ::answered? true ::pending [])) nil])))))

(defn request!
  "Start one test request's run agent. `members` are {::key ::body}; `record`
  is the member settle stage, `record-stop` the run's own stop event. A
  request made inside a member body is nested: its members take no slot.
  Answers {::answer promise ::finished promise ::graph ...}."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [requester members record record-stop limit pressure deadline-ms roster]
    :or {roster body-roster}}]
  (let [run-id (str "run-" (random-uuid))
        wake (async/chan (async/sliding-buffer 1)) done (async/chan 64) deadline (async/chan 1)
        answer (async/promise-chan) finished (async/promise-chan)
        _ (swap! roster assoc-in [::waiting run-id] wake)
        g (sflow/start-graph!
           {:seon.flow/projection (:seon.schema/projection requester)
            :seon.flow/graph-definition
            {:procs {:run {:proc (sflow/var-process
                                  #'run-step :io
                                  {:seon.env/environment (env/of requester)
                                   ::requester requester ::pending (vec members) ::record record
                                   ::record-stop record-stop ::limit limit ::pressure pressure
                                   ::roster roster ::run-id run-id ::nested? (some? test/*member*)
                                   ::wake wake ::done done ::deadline deadline
                                   ::answer answer ::finished finished})}}
             :conns []}})]
    (async/go (async/<! (async/timeout deadline-ms)) (async/>! deadline :deadline))
    (async/offer! wake :wake)
    (assoc g ::run-id run-id ::answer answer ::finished finished)))

(defn- take-within
  "One take from `ch`, awaited through seon.await under a carried bound."
  [ch ms]
  (let [v (await/await! {:seon.await/port-operations [ch]
                         :seon.await/bound {:seon.await/config-attribute :seon.test/check-time-limit-ms
                                            :seon.await/config-value ms}
                         :seon.await/diagnostic {:seon.error/layer ::run :seon.error/operation `take-within}})]
    (if (and (map? v) (:seon.error/at v)) ::timeout v)))

(defn close!
  "Join a run agent once it finished (every member exited); bounded."
  {:malli/schema [:=> [:cat :map :int] :any]}
  [run ms]
  (when (not= ::timeout (take-within (::finished run) ms))
    (join! run)))

(defn run-request!
  "Request and wait for the answer (bounded by the deadline plus `margin-ms`);
  the run is joined once every member has exited, after the answer when
  members outlived the deadline."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [deadline-ms margin-ms] :or {margin-ms 5000} :as request}]
  (let [run (request! request)
        answer (take-within (::answer run) (+ deadline-ms margin-ms))]
    (if (seq (::unfinished answer))
      (Thread/startVirtualThread #(close! run 600000))
      (close! run 5000))
    answer))

;;; ---------------------------------------------------------------------------
;;; Host-global classification over the invocation graph (B4)
;;; ---------------------------------------------------------------------------

(def mutators
  "JVM-global mutation a test may reach: redefinition, interning, loading,
  instrumentation and cluster lifecycle."
  '#{clojure.core/with-redefs clojure.core/with-redefs-fn clojure.core/alter-var-root
     clojure.core/intern clojure.core/load-file clojure.core/remove-ns clojure.core/ns-unmap
     seon.instrument/apply! seon.instrument/restore! seon.instrument/remove!
     seon.test-support/preserving-instrumentation-state seon.test-support/preserving-schema-registry
     seon.cluster.boot/start! seon.cluster.boot/stop!})

(defn host-global
  "Each test's reached mutations, over clj-kondo analysis of the test roots:
  its body, the local helpers and fixtures it reaches (test-root Vars only;
  production Vars are a boundary, counted only when they are mutators), and
  every namespace-level usage of the namespaces it reaches (fixtures, load
  effects). Answers {test-symbol #{mutator ...}} for tests reaching any."
  {:malli/schema [:=> [:cat :map] [:map-of :symbol [:set :symbol]]]}
  [{:keys [var-definitions var-usages]}]
  (let [sym (fn [n v] (symbol (str n) (str v)))
        local (into #{} (map #(sym (:ns %) (:name %))) var-definitions)
        tests (into [] (comp (filter :test) (map #(sym (:ns %) (:name %)))) var-definitions)
        edges (reduce (fn [m u]
                        (let [from (if (:from-var u) (sym (:from u) (:from-var u)) (symbol (str (:from u))))
                              to (sym (:to u) (:name u))]
                          (update m from (fnil conj #{}) to)))
                      {} var-usages)
        reach (fn [start]
                (loop [todo [start (symbol (namespace start))] seen #{}]
                  (if-let [v (peek todo)]
                    (if (seen v)
                      (recur (pop todo) seen)
                      (let [outs (get edges v #{})
                            nexts (concat (filter local outs)
                                          (keep #(when (local %) (symbol (namespace %))) outs))]
                        (recur (into (pop todo) nexts) (conj seen v))))
                    seen)))
        hits (fn [t] (into #{} (comp (mapcat #(get edges % #{})) (filter mutators)) (reach t)))]
    (into {} (keep (fn [t] (let [h (hits t)] (when (seq h) [t h])))) tests)))

(defn direct-host-global
  "The audit's first classifier, kept as the counterexample: the test body's
  own usages only."
  {:malli/schema [:=> [:cat :map] [:map-of :symbol [:set :symbol]]]}
  [{:keys [var-definitions var-usages]}]
  (let [tests (into #{} (comp (filter :test) (map #(symbol (str (:ns %)) (str (:name %))))) var-definitions)]
    (reduce (fn [m u]
              (let [from (when (:from-var u) (symbol (str (:from u)) (str (:from-var u))))
                    to (symbol (str (:to u)) (str (:name u)))]
                (if (and (tests from) (mutators to)) (update m from (fnil conj #{}) to) m)))
            {} var-usages)))
