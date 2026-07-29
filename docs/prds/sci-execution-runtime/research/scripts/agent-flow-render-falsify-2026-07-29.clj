(ns falsify
  "Falsification prototype for plan ruling 21 — rendering moves into the
  agent's flow (a THIRD proc beside mailbox and turn).

  NOT PRODUCTION CODE. Nothing here may be copied into src/ as-is; it
  exists to break or confirm the design before a contract is sealed.
  Run: clojure -M:dev:test -e '(load-file \"tmp/agent-render-falsify/falsify.clj\")(falsify/-main)'"
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.cluster.agent :as cluster.agent]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.render.agent :as render.agent]
            [seon.render.block :as block]
            [seon.test-support :as support]
            [interest])
  (:import [java.security MessageDigest]
           [java.lang.management ManagementFactory]))

;;; ---------------------------------------------------------------------------
;;; Shared helpers
;;; ---------------------------------------------------------------------------

(defn digest
  "SHA-256 of a value's printed bytes — the byte-identical churn test."
  [value]
  (let [text (if (string? value) value (pr-str value))
        md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %)
                    (take 8 (.digest md (.getBytes ^String text "UTF-8")))))))

(defn caps [] (config/result-caps (config/defaults)))

(defn agent-eid [db agent-id]
  (d/q '[:find ?a . :in $ ?id :where [?a :seon.cluster.agent/id ?id]]
       db agent-id))

(defn create-agents!
  "Create n agents through the production creation-tx owner."
  [connection ids]
  (doseq [id ids]
    (d/transact connection
                {:tx-data (cluster.agent/creation-tx
                           {:seon.cluster.agent/id id
                            :seon.ns/name (symbol (str "my.agents." id))})}))
  (mapv (fn [id] [id (agent-eid @connection id)]) ids))

;;; ---------------------------------------------------------------------------
;;; THE REGISTRATION — one shape for every render kind
;;; ---------------------------------------------------------------------------
;;;
;;; A registration is a BLOCK (the production `:seon.render.block/*` shape,
;;; already carrying `:seon.render/ai` and `:seon.render/html` projection
;;; symbols side by side) plus the kind this registration asks for. That is
;;; the whole "one mechanism for all render kinds" claim: html blocks, the
;;; canvas, the transcript, AND the agent's own ai context pieces are the
;;; same row rendered through the same `seon.render.block/surface` door.

(defn registration
  [block kind]
  {:seon.render.registration/name [(:seon.render.block/name block) kind]
   :seon.render.block/block block
   :seon.render/kind kind
   ;; RULING 19: reactivity is DERIVED from the input contract. A block
   ;; whose projection declares :seon.db/db among its inputs is DYNAMIC
   ;; (the current database value is injected, a wake registers); a block
   ;; with a literal or db-free projection is STATIC (memoized on input
   ;; equality, no listener, never re-derived on a wake).
   :seon.render.registration/dynamic?
   (boolean (or (symbol? (get block kind))
                (not (contains? #{:seon.render/ai :seon.render/html} kind))))})

(defn registrations-for
  "Every registration for one agent, derived from its block membership.
  ONE derivation covers both kinds — nothing enumerates a kind list."
  [db agent-id]
  (let [membership (block/membership db agent-id)]
    (into []
          (mapcat (fn [block]
                    (keep (fn [kind]
                            (when (contains? block kind)
                              (registration block kind)))
                          [:seon.render/ai :seon.render/html])))
          membership)))

(defn literal-registration
  "A STATIC registration: an :seon.render/ai literal string. Ruling 19
  says it must never re-derive on a wake."
  []
  (assoc (registration {:seon.render.block/name :static-note
                        :seon.render.block/band :anchor
                        :seon.render.block/priority 5
                        :seon.render/ai "A literal scaffold sentence."}
                       :seon.render/ai)
         :seon.render.registration/dynamic? false))

;;; ---------------------------------------------------------------------------
;;; THE THIRD PROC — registered renders
;;; ---------------------------------------------------------------------------

(def ^:dynamic *derivations* (atom 0))

(defn derive-one
  "Render one registration through the production block door."
  [db agent-id registration]
  (block/surface {:seon.db/db db
                  :seon.cluster.agent/id agent-id
                  :seon.sci.admit/caps (caps)}
                 (:seon.render.block/block registration)
                 (:seon.render/kind registration)))

(defn pass
  "ONE render pass over proc state. Pure except for the derivation calls.
  Returns [next-memory changed] where changed is the registration names
  whose bytes actually moved."
  [{:keys [db agent-id registrations memory]}]
  (reduce
   (fn [[memo changed] registration]
     (let [name (:seon.render.registration/name registration)
           held (get memo name)]
       (if (and held (not (:seon.render.registration/dynamic? registration)))
         ;; STATIC: memoized on input equality, never re-derived
         [memo changed]
         (let [surface (do (swap! *derivations* inc)
                           (derive-one db agent-id registration))
               output (:seon.render/output surface)
               d (digest output)]
           (if (= d (:digest held))
             ;; byte-identical re-derivation is SUPPRESSED
             [(assoc memo name (update held :passes inc)) changed]
             [(assoc memo name {:digest d
                                :output output
                                :kind (:seon.render/kind registration)
                                :churn (inc (:churn held 0))
                                :passes (inc (:passes held 0))})
              (conj changed name)])))))
   [memory #{}]
   registrations))

(defn render-step
  "The third proc's transform, in Flow's four arities.
  In-port ::interest (sliding-1, payload-free \"look\"); out ::package."
  ([]
   {:ins {}
    :outs {::package "One revisioned package of changed fragments."}
    :workload :io
    :ping-map-fn (fn [state] (select-keys state [::passes ::revision
                                                 ::last-package]))})
  ([args]
   (cond-> (assoc args
                  ::flow/in-ports {::interest (::interest-channel args)}
                  ::passes 0 ::revision 0 ::memory {})
     (::package-channel args)
     (assoc ::flow/out-ports {::package (::package-channel args)})))
  ([state _transition] state)
  ([state _input _message]
   (let [db @(::connection state)
         agent-id (:seon.cluster.agent/id state)
         registrations ((::registrations-fn state) db agent-id)
         [memory changed] (pass {:db db :agent-id agent-id
                                 :registrations registrations
                                 :memory (::memory state)})
         state' (-> state
                    (update ::passes inc)
                    (assoc ::memory memory))]
     (if (seq changed)
       (let [package {:revision (inc (::revision state))
                      :changed changed
                      :thread (.getName (Thread/currentThread))
                      :virtual-thread? (.isVirtual (Thread/currentThread))
                      :agent agent-id
                      :outputs (into {} (map (fn [name]
                                               [name (:output (get memory name))]))
                                     changed)}]
         [(-> state'
              (update ::revision inc)
              (assoc ::last-package (dissoc package :outputs)))
          {::package [package]}])
       [state' nil]))))

;;; ---------------------------------------------------------------------------
;;; Context assembly from the SAME proc state
;;; ---------------------------------------------------------------------------

(defn context-assembly
  "The agent's ai context, churn-ordered stable-first, served from the
  render proc's own memory. No second derivation, no prompt-time render."
  [memory]
  (->> memory
       (filter (fn [[_ v]] (= :seon.render/ai (:kind v))))
       (filter (fn [[_ v]] (some? (:output v))))
       (sort-by (fn [[name v]] [(:churn v) (str name)]))
       (mapv (fn [[name v]] {:piece name :churn (:churn v)
                             :bytes (count (str (:output v)))}))))

;;; ---------------------------------------------------------------------------
;;; E1 — wake routing
;;; ---------------------------------------------------------------------------

(defn render-route!
  "The wake router's render-interest delivery: ONE more block inside the
  EXISTING `seon.cluster.wake/route!` handler, never a second listener.
  Both absolute prohibitions hold — one try/catch, `offer!` only.

  :indexed — the old writer's two-stage delivery: reverse candidate
             index narrows, per-interest matching confirms;
  :all     — today's unconditional per-report render wake, widened to
             every DISPLAYED agent (the fail-open case the old JVM feed
             also used, src-old/seon/web/feed.clj:145-153)."
  [{:keys [connection key mode registry channels]}]
  (d/listen
   connection key
   (fn [report]
     (try
       (when (map? report)
         (case mode
           :all (doseq [ch (vals channels)] (async/offer! ch ::wake))
           :indexed
           (let [datoms (vec (:tx-data report))]
             (doseq [reference (interest/interested registry datoms)]
               (when-let [ch (get (:interest/channels @registry) reference)]
                 (async/offer! ch ::wake))))))
       (catch Throwable _ nil))))
  key)

(defn drain! [ch]
  (loop [n 0] (if (async/poll! ch) (recur (inc n)) n)))

(defn capture-interest
  "Derive one agent's render interest by RUNNING its registrations once
  under the read-evidence seam and reducing the captured Datahike
  dependency plans to attributes. Absence-safe: the plan comes from the
  parsed read form, not from returned rows."
  [db agent-id registrations]
  (let [[_ evidence]
        (interest/with-read-evidence
          (mapv #(derive-one db agent-id %) registrations))]
    (interest/evidence-dependencies evidence)))

(defn e1b-plan-widens-on-data
  "THE SOUNDNESS QUESTION for execution-captured plans: a branch that did
  not run contributes no attributes. Is a narrow interest captured on
  empty data a MISS, or does the guard read always wake it?

  `transcript-html` queries for this agent's messages (guard) and then
  wildcard-pulls each one (`d/pull db '[*] eid`). On a fresh agent the
  pull never runs, so the captured plan is narrow. Commit a message: the
  guard attribute IS in the interest, so the wake fires, the render
  re-runs, the wildcard pull executes, and the plan re-registers as
  `:all`. One render of lag, never a missed wake."
  []
  (support/with-database
    (fn [connection]
      (let [_ (create-agents! connection ["t1"])
            db0 @connection
            eid (agent-eid db0 "t1")
            transcript (first (filter #(= :transcript (:seon.render.block/name
                                                       (:seon.render.block/block %)))
                                      (registrations-for db0 "t1")))
            before (capture-interest db0 "t1" [transcript])
            _ (d/transact connection
                          {:tx-data [{:seon.cluster.message/to eid
                                      :seon.cluster.message/content "hello"
                                      :seon.cluster.message/id "t-1"
                                      :seon.cluster.message/at (java.util.Date.)}]})
            db1 @connection
            after (capture-interest db1 "t1" [transcript])]
        {:interest-on-empty-transcript before
         :guard-attribute-present?
         (and (set? before) (contains? before :seon.cluster.message/to))
         :interest-after-one-message after}))))

(defn e1-wake-routing []
  (support/with-database
    (fn [connection]
      (let [ids (mapv #(str "a" %) (range 1 6))
            pairs (create-agents! connection ids)
            chans (into {} (map (fn [[id _]] [id (async/chan (async/sliding-buffer 1))])) pairs)
            eids (into {} pairs)
            db @connection
            ;; ONE registration set per agent. a3 also displays the ai
            ;; namespace block; a1/a2/a4/a5 display only the header.
            header (first (filter #(= :agent-header (:seon.render.block/name
                                                     (:seon.render.block/block %)))
                                  (registrations-for db "a1")))
            namespace-ai (first (filter #(= :namespace (:seon.render.block/name
                                                        (:seon.render.block/block %)))
                                        (registrations-for db "a3")))
            registry (interest/registry)
            interests
            (into {}
                  (map (fn [id]
                         (let [regs (if (= id "a3") [header namespace-ai] [header])
                               deps (capture-interest db id regs)]
                           (interest/install! registry [id ::render] deps (get chans id))
                           [id deps])))
                  ids)
            _ (render-route! {:connection connection :key ::indexed
                              :mode :indexed :registry registry})
            ;; a commit touching ONLY :seon.cluster.message/to + /text
            _ (d/transact connection
                          {:tx-data [{:seon.cluster.message/to (get eids "a3")
                                      :seon.cluster.message/content "hi"
                                      :seon.cluster.message/id "m-1"
                                      :seon.cluster.message/at (java.util.Date.)}]})
            indexed-woken (into #{} (keep (fn [[id ch]] (when (async/poll! ch) id))) chans)
            ;; a commit touching an attribute NOBODY reads
            _ (run! drain! (vals chans))
            _ (d/transact connection
                          {:tx-data [{:seon.ancestor/digest (apply str (repeat 64 "1"))}]})
            noise-woken (into #{} (keep (fn [[id ch]] (when (async/poll! ch) id))) chans)
            ;; the falsified case, proven SAFE here: an attribute the
            ;; renderer reads but which is currently ABSENT everywhere
            _ (run! drain! (vals chans))
            absent-attribute-in-index?
            (contains? (:interest/by-attribute (:interest/index @registry))
                       :seon.cluster.agent/run)
            _ (d/unlisten connection ::indexed)
            _ (run! drain! (vals chans))
            _ (render-route! {:connection connection :key ::all
                              :mode :all :channels chans})
            _ (d/transact connection
                          {:tx-data [{:seon.cluster.message/to (get eids "a3")
                                      :seon.cluster.message/content "hi again"
                                      :seon.cluster.message/id "m-2"
                                      :seon.cluster.message/at (java.util.Date.)}]})
            all-woken (into #{} (keep (fn [[id ch]] (when (async/poll! ch) id))) chans)
            ;; PARKED/undrained render proc: three commits, one wake retained
            _ (run! drain! (vals chans))
            _ (dotimes [i 3]
                (d/transact connection
                            {:tx-data [{:seon.cluster.message/to (get eids "a3")
                                        :seon.cluster.message/content (str "burst " i)
                                        :seon.cluster.message/id (str "m-b" i)
                                        :seon.cluster.message/at (java.util.Date.)}]}))
            coalesced (drain! (get chans "a3"))
            ;; a CLOSED channel: offer! returns false, nothing throws,
            ;; the committing transaction still completes
            closed (async/chan (async/sliding-buffer 1))
            _ (async/close! closed)
            _ (d/unlisten connection ::all)
            _ (render-route! {:connection connection :key ::closed
                              :mode :all :channels {"dead" closed}})
            commit-after-close
            (try (:db-after (d/transact connection
                                        {:tx-data [{:seon.cluster.message/to (get eids "a3")
                                                    :seon.cluster.message/content "after close"
                                                    :seon.cluster.message/id "m-3"
                                                    :seon.cluster.message/at (java.util.Date.)}]}))
                 (catch Throwable t t))
            ;; delivery cost of the two-stage index at 1,000 interests
            big (interest/registry)
            _ (dotimes [i 1000]
                (interest/install! big [(str "big" i) ::render]
                                   #{(keyword "noise" (str i))}
                                   (async/chan (async/sliding-buffer 1))))
            _ (interest/install! big ["target" ::render]
                                 #{:seon.cluster.message/to}
                                 (async/chan (async/sliding-buffer 1)))
            probe-datoms [[1 :seon.cluster.message/to 2 100 true]]
            _ (dotimes [_ 2000] (interest/interested big probe-datoms))
            t0 (System/nanoTime)
            _ (dotimes [_ 10000] (interest/interested big probe-datoms))
            lookup-us (/ (- (System/nanoTime) t0) 10000.0 1000.0)]
        (d/unlisten connection ::closed)
        {:wildcard-pull-plan (d/pull-dependency-plan '[*] [1])
         :per-registration-interest
         (into (sorted-map)
               (map (fn [registration]
                      [(:seon.render.registration/name registration)
                       (capture-interest db "a1" [registration])]))
               (registrations-for db "a1"))
         :captured-interest interests
         :indexed-woken indexed-woken
         :noise-commit-woken noise-woken
         :absent-attribute-registered? absent-attribute-in-index?
         :all-woken all-woken
         :coalesced-wakes-after-3-commits coalesced
         :commit-survives-closed-channel (not (instance? Throwable commit-after-close))
         :addressed-of-1001-interests (count (interest/interested big probe-datoms))
         :index-lookup-us lookup-us}))))

;;; ---------------------------------------------------------------------------
;;; E2 — proc cost at 100 parked agents
;;; ---------------------------------------------------------------------------

(defn three-proc-definition
  "The agent blueprint plus the third proc. Same handle, one more conn."
  [{:keys [handle agent-id connection interest-channel registrations-fn]}]
  (let [base (cluster.agent/graph-definition
              {:seon.cluster.loop/cluster handle
               :seon.cluster.agent/id agent-id})]
    (-> base
        (assoc-in [:procs ::render]
                  {:proc (seon.flow/var-process
                          #'render-step :io
                          {::connection connection
                           ::interest-channel interest-channel
                           ::registrations-fn registrations-fn
                           :seon.cluster.agent/id agent-id})}))))

(defn used-heap []
  (dotimes [_ 3] (System/gc))
  (Thread/sleep 120)
  (let [r (Runtime/getRuntime)] (- (.totalMemory r) (.freeMemory r))))

(defn platform-threads []
  (.getThreadCount (ManagementFactory/getThreadMXBean)))

(defn e2-proc-cost [n]
  (support/with-database
    (fn [connection]
      (let [ids (mapv #(str "c" %) (range n))
            _ (create-agents! connection ids)
            handle {:seon.store/branch-connection connection
                    :seon.cluster.run/process "falsify"
                    :seon.sci.admit/caps (caps)}
            build (fn [three?]
                    (mapv (fn [id]
                            (let [wake (async/chan (async/sliding-buffer 1))
                                  interest (async/chan (async/sliding-buffer 1))
                                  h (assoc handle
                                           :seon.cluster.wake/channel wake
                                           :seon.cluster.loop/completion (async/promise-chan))
                                  definition (if three?
                                               (three-proc-definition
                                                {:handle h :agent-id id
                                                 :connection connection
                                                 :interest-channel interest
                                                 :registrations-fn registrations-for})
                                               (cluster.agent/graph-definition
                                                {:seon.cluster.loop/cluster h
                                                 :seon.cluster.agent/id id}))]
                              {:graph (flow/create-flow definition)
                               :wake wake :interest interest}))
                          ids))
            measure
            (fn [three?]
              (let [heap0 (used-heap)
                    t0 (System/nanoTime)
                    entries (build three?)
                    create-ms (/ (- (System/nanoTime) t0) 1e6)
                    th0 (platform-threads)
                    t1 (System/nanoTime)
                    _ (run! (fn [{:keys [graph]}]
                              (flow/start graph) (flow/resume graph))
                            entries)
                    start-ms (/ (- (System/nanoTime) t1) 1e6)
                    _ (Thread/sleep 400)
                    heap1 (used-heap)
                    th1 (platform-threads)]
                (run! (fn [{:keys [graph]}] (flow/stop graph)) entries)
                (Thread/sleep 300)
                {:create-ms create-ms :start-ms start-ms
                 :heap-delta-bytes (- heap1 heap0)
                 :platform-threads-before th0
                 :platform-threads-after th1}))
            ;; WARM the JIT and the graph machinery first; the first
            ;; measurement in a JVM pays for class loading and would be
            ;; read as the render proc's cost if it went second.
            _ (measure false)
            _ (measure true)
            three (measure true)
            two (measure false)]
        {:agents n
         :two-proc two
         :three-proc three
         :per-agent-render-proc-heap-bytes
         (double (/ (- (:heap-delta-bytes three) (:heap-delta-bytes two)) n))
         :per-agent-render-proc-create-ms
         (double (/ (- (:create-ms three) (:create-ms two)) n))
         :per-agent-render-proc-start-ms
         (double (/ (- (:start-ms three) (:start-ms two)) n))}))))

;;; ---------------------------------------------------------------------------
;;; E3 — one mechanism for ai AND html
;;; ---------------------------------------------------------------------------

(defn e3-one-mechanism
  "ONE registration mechanism, ONE memory, ONE pass — for html blocks AND
  the agent's own ai context pieces. Pure over `pass`, so the numbers are
  deterministic; E4 proves the same pass runs inside the agent's proc."
  []
  (support/with-database
    (fn [connection]
      (let [_ (create-agents! connection ["m1"])
            db0 @connection
            eid (agent-eid db0 "m1")
            registrations (conj (registrations-for db0 "m1")
                                (literal-registration))
            count-pass (fn [db memory]
                         (reset! *derivations* 0)
                         (let [[memory' changed]
                               (pass {:db db :agent-id "m1"
                                      :registrations registrations
                                      :memory memory})]
                           {:memory memory' :changed changed
                            :derivations @*derivations*}))
            first-pass (count-pass db0 {})
            ;; a SECOND wake with no fact change at all
            idle-pass (count-pass db0 (:memory first-pass))
            ;; a fact change this agent's world sees
            _ (d/transact connection
                          {:tx-data [{:seon.cluster.message/to eid
                                      :seon.cluster.message/content "what is the plan?"
                                      :seon.cluster.message/id "m-x"
                                      :seon.cluster.message/at (java.util.Date.)}]})
            db1 @connection
            change-pass (count-pass db1 (:memory idle-pass))]
        {:registration-count (count registrations)
         :kinds (frequencies (map :seon.render/kind registrations))
         :static-count (count (remove :seon.render.registration/dynamic? registrations))
         :first-pass {:derivations (:derivations first-pass)
                      :emitted (count (:changed first-pass))}
         :idle-pass {:derivations (:derivations idle-pass)
                     :emitted (count (:changed idle-pass))
                     :suppressed (- (count registrations)
                                    (count (:changed idle-pass)))}
         :change-pass {:derivations (:derivations change-pass)
                       :emitted (count (:changed change-pass))
                       :changed (vec (sort-by str (:changed change-pass)))}
         :static-never-rederived?
         (= (:derivations idle-pass) (dec (count registrations)))
         :context-assembly-after-change
         (context-assembly (:memory change-pass))}))))

;;; ---------------------------------------------------------------------------
;;; E4 — cross-namespace production, and production INSIDE the agent's proc
;;; ---------------------------------------------------------------------------

(defn peer-summary-ai
  "A plain defn standing in for a corpus renderer row OWNED BY AGENT B.
  Agent A's registration names it; nothing about B is a process."
  [unit]
  (let [db (get unit :seon.db/db)
        other (get unit ::peer)]
    (when (and db other)
      (str "peer " other " namespace: "
           (d/q '[:find ?name .
                  :in $ ?id
                  :where
                  [?a :seon.cluster.agent/id ?id]
                  [?a :seon.cluster.agent/namespace ?ns]
                  [?ns :seon.ns/name ?name]]
                db other)))))

(defn e4-cross-namespace []
  (support/with-database
    (fn [connection]
      (let [_ (create-agents! connection ["A" "B"])
            cross {:seon.render.block/name :peer-b
                   :seon.render.block/band :dynamic
                   :seon.render.block/priority 95
                   ::peer "B"
                   :seon.render/ai `peer-summary-ai}
            registrations [(registration cross :seon.render/ai)]
            packages (async/chan (async/sliding-buffer 8))
            wake (async/chan (async/sliding-buffer 1))
            definition
            {:procs {::render
                     {:proc (seon.flow/var-process
                             #'render-step :io
                             {::connection connection
                              ::interest-channel wake
                              ::package-channel packages
                              ::registrations-fn (constantly registrations)
                              :seon.cluster.agent/id "A"})}}
             :conns []}
            graph (flow/create-flow definition)
            _ (flow/start graph)
            _ (flow/resume graph)
            _ (async/offer! wake ::wake)
            package (async/alt!! packages ([v] v)
                                 (async/timeout 5000) ::none)
            state (flow/ping-proc graph ::render)]
        (flow/stop graph)
        {:package (dissoc package :outputs)
         :b-rendered-by-a (vals (:outputs package))
         :ping-state (::flow/state state)
         :caller-thread (.getName (Thread/currentThread))}))))

(defn -main [& _]
  (println "\n=== E1 wake routing ===")
  (clojure.pprint/pprint (e1-wake-routing))
  (println "\n=== E1b plan widening ===")
  (clojure.pprint/pprint (e1b-plan-widens-on-data))
  (println "\n=== E3 one mechanism ===")
  (clojure.pprint/pprint (e3-one-mechanism))
  (println "\n=== E4 cross-namespace ===")
  (clojure.pprint/pprint (e4-cross-namespace))
  (println "\n=== E2 proc cost ===")
  (clojure.pprint/pprint (e2-proc-cost 100)))
