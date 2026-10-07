(ns seon.pass-probe
  "One pass of each entrant, measured as data (agent-loop spec §3, slice S0).

  A window brackets one pass at its real entrance (`seon.mcp/call` for MCP
  SCI and JVM, an outside message for a model pass answered by the fixture
  provider in the agent's settings, MCP SCI `my.test/check` for a test) with
  the owners' own readings: wall time, the JVM's allocated bytes split by
  platform thread (a virtual thread's bytes land on its carrier), the armed
  calls `seon.profile/explain` counts, attempts through
  `seon.db/transact-call`, durable commits as distinct commit ids per branch
  and the touched files' final sizes from the store's konserve write hooks,
  and the tokens shown. Results are returned as data; nothing is stored."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [konserve.core :as konserve]
            [konserve.impl.defaults :as konserve.defaults]
            [konserve.protocols :as konserve.protocols]
            [seon.ai.tokens :as tokens]
            [seon.cluster.host :as host]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.profile :as profile]
            [seon.test-support :as support])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent ConcurrentLinkedQueue FutureTask TimeUnit TimeoutException]))

(set! *warn-on-reflection* true)

(def ^:private pass-timeout-ms
  "The bound one probed pass is awaited for before its record is incomplete."
  120000)

;;; ---------------------------------------------------------------------------
;;; Readings
;;; ---------------------------------------------------------------------------

(defn- thread-bytes
  "Each live platform thread's allocated bytes so far, by thread id, with its name."
  {:malli/schema [:=> [:cat] [:map-of :int [:tuple :string :int]]]}
  []
  (let [mx ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        ids (.getAllThreadIds mx)
        allocated (.getThreadAllocatedBytes mx ids)
        infos (.getThreadInfo mx ids)]
    (into {} (keep (fn [i] (let [^java.lang.management.ThreadInfo info (aget infos i) b (aget allocated i)]
                             (when (and info (not (neg? b))) [(aget ids i) [(.getThreadName info) b]]))))
          (range (alength ids)))))

(defn- total-bytes
  "The JVM's allocated bytes so far, every thread, exited ones included."
  {:malli/schema [:=> [:cat] :int]}
  []
  (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)))

(defn- thread-group
  "A thread's role: its name without trailing digits."
  {:malli/schema [:=> [:cat :string] :string]}
  [thread-name]
  (str/replace thread-name #"[-\d]+$" ""))

(defn- by-thread
  "Bytes allocated between two `thread-bytes` readings, by thread role; a
  thread born in the window starts from zero."
  {:malli/schema [:=> [:cat [:map-of :int [:tuple :string :int]] [:map-of :int [:tuple :string :int]]]
                  [:map-of :string :int]]}
  [before after]
  (reduce-kv (fn [groups thread-id [thread-name b]]
               (let [moved (- b (get-in before [thread-id 1] 0))]
                 (if (pos? moved) (update groups (thread-group thread-name) (fnil + 0) moved) groups)))
             {} after))

(defn- written
  "The `[op key commit-id branch]` of each key one konserve write event names;
  commit id and branch are read only from a written commit record."
  {:malli/schema [:=> [:cat :map] [:vector :tuple]]}
  [{op :api-op k :key v :value kvs :kvs}]
  (let [one (fn [k v] (let [record (when (map? v) v)]
                        [op k (get-in record [:meta :datahike/commit-id]) (get-in record [:config :branch])]))]
    (if (= :multi-assoc op)
      (mapv (fn [[k v]] (one k v)) kvs)
      [(one k v)])))

(defn- hook!
  "Register a write hook on `store` collecting `written` tuples; nil when the
  store keeps no hooks (an in-memory overlay)."
  {:malli/schema [:=> [:cat [:fn some?]] [:maybe [:tuple :keyword [:fn some?]]]]}
  [store]
  (when (konserve.protocols/-get-write-hooks store)
    (let [hook-id (keyword "seon.pass-probe" (id/id))
          events (ConcurrentLinkedQueue.)]
      (konserve/add-write-hook! store hook-id (fn [event] (run! #(.add events %) (written event))))
      [hook-id events])))

(defn- storage
  "Durable commits by branch (every branch the window committed on; on a shared
  store other writers' too), those on `own-branches`, and the final sizes of
  the files the window touched, from the hook's `events` on the store at `base`."
  {:malli/schema [:=> [:cat [:sequential :tuple] [:maybe :string] [:set :keyword]] :map]}
  [events base own-branches]
  (let [commits (reduce (fn [m [_ _ cid branch]] (if cid (update m branch (fnil conj #{}) cid) m)) {} events)
        size (fn [k] (let [f (io/file base (konserve.defaults/key->store-key k))] (when (.exists f) (.length f))))
        sized (fn [ops] (let [ks (into #{} (keep (fn [[op k]] (when (ops op) k))) events)
                              sizes (map size ks)]
                          {:files (count ks) :bytes (reduce + 0 (keep identity sizes))
                           :missing (count (remove some? sizes))}))
        store (sized (complement #{:bassoc}))
        blobs (sized #{:bassoc})]
    {::durable-commits (into {} (map (fn [[b ids]] [b (count ids)])) commits)
     ::own-commits (reduce + 0 (map count (vals (select-keys commits own-branches))))
     ::store-files (:files store) ::store-bytes (:bytes store) ::store-missing (:missing store)
     ::blob-bytes (:bytes blobs)}))

(defn- grown
  "Calls and inclusive milliseconds of `syms` in `explanation`'s growth."
  {:malli/schema [:=> [:cat :seon.profile/explanation [:set :qualified-symbol]] [:map-of :qualified-symbol :map]]}
  [explanation syms]
  (reduce (fn [m {sym :seon.profile/sym :as g}]
            (if (syms sym)
              (update m sym #(merge-with + % (select-keys g [:seon.profile/calls :seon.profile/total-ms])))
              m))
          {} (:seon.profile/by-calls explanation)))

(defn window
  "Run `pass` (a thunk answering `{::complete? … ::shown …}`) and measure it.
  The profile mark, thread and JVM byte readings and the write hook are taken
  outside the pass; the hook is removed only once the pass completed."
  {:malli/schema [:=> [:cat [:map [::store [:fn some?]] [::base [:maybe :string]] [::branches [:set :keyword]]
                             [::stages {:optional true} [:set :qualified-symbol]]]
                       [:=> [:cat] :map]]
                  :map]}
  [{::keys [store base branches stages]} pass]
  (let [mark (profile/begin)
        threads0 (thread-bytes)
        total0 (total-bytes)
        [hook-id events] (hook! store)
        t0 (System/nanoTime)
        result (pass)
        t1 (System/nanoTime)
        total1 (total-bytes)
        threads1 (thread-bytes)
        explanation (profile/explain mark 100000)
        groups (by-thread threads0 threads1)
        allocated (- total1 total0)]
    (when (and hook-id (::complete? result)) (konserve/remove-write-hook! store hook-id))
    (merge (dissoc result ::shown)
           {::wall-ms (/ (- t1 t0) 1e6)
            ::allocated-bytes allocated
            ::allocated-by-thread groups
            ::unattributed-bytes (- allocated (reduce + 0 (vals groups)))
            ::checked-calls (:seon.profile/calls explanation)
            ::top-calls (mapv #(select-keys % [:seon.profile/sym :seon.profile/calls :seon.profile/total-ms])
                              (take 10 (:seon.profile/by-calls explanation)))
            ::attempts (get-in (grown explanation '#{seon.db/transact-call}) ['seon.db/transact-call :seon.profile/calls] 0)
            ::stages (grown explanation (or stages #{}))
            ::tokens (tokens/estimate (str/join "\n" (::shown result)))
            ::calibration tokens/shipped-chars-per-token}
           (if hook-id
             (storage (vec events) base branches)
             {::storage :unavailable}))))

;;; ---------------------------------------------------------------------------
;;; Entrances
;;; ---------------------------------------------------------------------------

(defn- outside
  "`f`'s value, called on a fresh platform thread (no conveyed custody) and
  awaited under `pass-timeout-ms`; a thrown failure is rethrown here, and a
  call still running at the bound throws `TimeoutException` and keeps running."
  {:malli/schema [:=> [:cat [:=> [:cat] :seon.schema/value]] :seon.schema/value]}
  [f]
  (let [task (FutureTask. ^java.util.concurrent.Callable f)]
    (.start (Thread. task))
    (try (.get task pass-timeout-ms TimeUnit/MILLISECONDS)
         (catch java.util.concurrent.ExecutionException failure (throw (.getCause failure))))))

(defn- mcp-pass
  "A pass thunk: `seon.mcp/call` eval_clj on a fresh platform thread, awaited
  under `pass-timeout-ms`; answers the reply's texts."
  {:malli/schema [:=> [:cat [:map-of :string :string]] [:=> [:cat] :map]]}
  [arguments]
  (fn []
    (try
      (let [reply (json/read-str (outside #(mcp/call (json/write-str {"name" "eval_clj" "arguments" arguments}))))]
        {::complete? true ::error? (boolean (get reply "isError"))
         ::shown (mapv #(get % "text") (get reply "content"))})
      ;; the call keeps running and owns its agent until it exits
      (catch TimeoutException _ {::complete? false ::shown []}))))

(defn- drain!
  "Take every report already queued on `channel`."
  {:malli/schema [:=> [:cat [:fn some?]] :nil]}
  [channel]
  (when (async/poll! channel) (recur channel)))

(defn- model-pass
  "A pass thunk: an outside message to the agent, answered by one capped model
  turn; ends at the proc's idle report after the turn's terminal report. The
  report channel is drained before the wake, so no earlier report is read."
  {:malli/schema [:=> [:cat :map] [:=> [:cat] :map]]}
  [{connection :seon.db/connection process :seon.db.process/id cluster-name :seon.boot/cluster-name
    agent-id :seon.agent/id reports ::reports ledger ::ledger}]
  (fn []
    (drain! reports)
    (let [requests (count @ledger)]
      (try
        ;; an outside wake comes from no evaluation: written off this thread's custody
        (outside #(drive/inbound! connection cluster-name process agent-id (str "Probe wake " (id/id) ".")))
        (let [turn (support/await-event! reports ::turn #(= :call (:seon.turn.work/situation %)) pass-timeout-ms)]
          (support/await-event! reports ::idle :seon.turn.loop/idle? pass-timeout-ms)
          (let [sent (subvec @ledger requests)]
            {::complete? true ::turn (select-keys turn [:seon.turn/id :seon.turn.loop/outcome :seon.turn.loop/forms-run])
             ::provider-requests (count sent)
             ::shown (into [] (comp (mapcat #(get % "messages")) (map #(str (get % "content")))) sent)}))
        (catch clojure.lang.ExceptionInfo failure
          {::complete? false ::shown [] ::failure (ex-message failure) ::failure-data (ex-data failure)})
        (catch TimeoutException _ {::complete? false ::shown []})))))

;;; ---------------------------------------------------------------------------
;;; The agent's world
;;; ---------------------------------------------------------------------------

(defn- agent-world
  "Where `agent-id` runs in the instance named `running`: its home connection,
  process, routing, branches and store."
  {:malli/schema [:=> [:cat :string :seon.agent/id] :map]}
  [running agent-id]
  (let [instance (get @runtime/running-instances running)
        handle (:seon.turn.loop/cluster instance)
        cluster-db (db/db (:seon.db/connection handle))
        live (get-in cluster-db [:config :branch])
        home (:seon.agent/branch (db/pull cluster-db [:seon.agent/branch] [:seon.agent/id agent-id]))
        hosted (when (and home (not= home live))
                 (host/hosted (assoc handle :seon.store/store (:seon.store/store instance)) home))
        cluster (if hosted (:seon.turn.loop/cluster hosted) handle)
        routing (if hosted (:seon.agent/routing hosted) (:seon.agent/routing handle))
        connection (:seon.db/connection cluster)]
    {:seon.db/connection connection
     :seon.db.process/id (:seon.db.process/id cluster)
     :seon.boot/cluster-name (:seon.cluster/name handle)
     :seon.agent/id agent-id
     ::host hosted
     ::reports (get-in @routing [:seon.agent/armed agent-id :seon.flow/started :report-chan])
     ::store (:store @connection)
     ::base (get-in @connection [:config :store :path])
     ::branches (into #{live} (keep identity) [(get-in @connection [:config :branch])])}))

;;; ---------------------------------------------------------------------------
;;; Pass texts
;;; ---------------------------------------------------------------------------

(defn- filler
  "A form defining nothing whose shown value is about 2 KB."
  {:malli/schema [:=> [:cat :int] :string]}
  [i]
  (str "(apply str \"h" i "-\" (repeat 1990 \\x))"))

(defn- defn-text
  "A contracted one-line definition of `fn-name`."
  {:malli/schema [:=> [:cat :string] :string]}
  [fn-name]
  (str "(defn " fn-name " \"Returns x plus one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"))

(defn- fenced
  "`code` as a model reply: one Clojure fence."
  {:malli/schema [:=> [:cat :string] :string]}
  [code]
  (str "```clojure\n" code "\n```"))

;;; ---------------------------------------------------------------------------
;;; Runs
;;; ---------------------------------------------------------------------------

(defn- mcp!
  "One measured MCP pass of `code` for the agent of `world`."
  {:malli/schema [:=> [:cat :string :map :string :string :map] :map]}
  [running world mode code fields]
  (merge fields
         (window world (mcp-pass {"code" code "agent" (:seon.agent/id world) "cluster" running "mode" mode
                                  "namespace" (str "scratch.s0probe-" (:seon.agent/id world))}))))

(defn- sequence-plan
  "The passes of one entrant to `size` prior passes: ordinary fillers, and a
  definition and the pass after it once the history reaches each of `at`."
  {:malli/schema [:=> [:cat :int [:set :int] :boolean] [:vector [:tuple :keyword :int]]]}
  [size at defn?]
  (into [] (mapcat (fn [i] (cond-> [[:ordinary i]]
                             (and defn? (at (inc i))) (into [[:defn i] [:after-defn i]]))))
        (range size)))

(defn- texts
  "The code a pass of `kind` at step `i` sends."
  {:malli/schema [:=> [:cat :keyword :int] :string]}
  [kind i]
  (case kind
    :ordinary (filler i)
    :defn (defn-text (str "probe-f" i))
    :after-defn (str "(probe-f" i " 41)")))

(defn run-mcp
  "One record per measured MCP pass of a new agent on the instance `running`.

  `size` passes in `mode` (\"sci\" or \"jvm\"), with a definition and the pass
  after it as the history reaches each of `at`."
  {:malli/schema [:=> [:cat :string :string :int [:set :int] :boolean] [:vector :map]]}
  [running mode size at defn?]
  (let [agent-id (str "s0-" mode "-" (id/id))
        entrant (if (= "jvm" mode) :mcp-jvm :mcp-sci)
        first-pass (mcp-pass {"code" "(+ 1 2)" "agent" agent-id "cluster" running})
        _ (first-pass)
        world (agent-world running agent-id)]
    (try
      (first
       (reduce (fn [[records prior] [kind i]]
                 [(conj records (mcp! running world mode (texts kind i)
                                      {::entrant entrant ::kind kind ::prior-passes prior ::sequence (inc (count records))}))
                  (inc prior)])
               [[] 1] (sequence-plan size at defn?)))
      (finally (some-> (::host (agent-world running agent-id)) host/retire!)))))

(defn run-model
  "One record per measured model pass of a new agent on the instance `running`.

  `size` capped model passes, each answered by the fixture provider set in
  the agent's settings; definitions as `run-mcp`."
  {:malli/schema [:=> [:cat :string :int [:set :int]] [:vector :map]]}
  [running size at]
  (let [agent-id (str "s0-model-" (id/id))
        plan (sequence-plan size at true)
        ledger (atom [])
        timings (atom [])]
    (support/with-provider
     ledger (support/reply-queue (mapv (fn [[kind i]] (fenced (texts kind i))) plan)) timings
     (fn [server]
       ((mcp-pass {"agent" agent-id "cluster" running
                   "code" (str "(my.agent/settings! " (id/printed (merge (support/provider-config server)
                                                                     {:seon.config.ai/no-provider false
                                                                      :seon.config.run/max-episode-runs 1}))
                               ")")}))
       (let [world (assoc (agent-world running agent-id) ::ledger ledger)]
         (try
           (first
            (reduce (fn [[records prior] [kind _]]
                      (let [served (count @timings)
                            record (merge {::entrant :model ::kind kind ::prior-passes prior
                                           ::sequence (inc (count records))}
                                          (window world (model-pass world)))
                            provider-ns (reduce + 0 (map (fn [[a b]] (- b a)) (subvec @timings served)))]
                        [(conj records (assoc record ::provider-ms (/ provider-ns 1e6))) (inc prior)]))
                    [[] 1] plan))
           (finally (some-> (::host (agent-world running agent-id)) host/retire!))))))))

(defn run-check
  "One record per MCP SCI pass of `(my.test/check …)` naming `test-sym`.

  `repetitions` passes of a new agent on `running`, the first cold. Stages
  report the runner's owners' growth; a member ran when `seon.test/bounded-result` grew."
  {:malli/schema [:=> [:cat :string :qualified-symbol pos-int?] [:vector :map]]}
  [running test-sym repetitions]
  (let [agent-id (str "s0-check-" (id/id))
        _ ((mcp-pass {"code" "(+ 1 2)" "agent" agent-id "cluster" running}))
        world (assoc (agent-world running agent-id)
                     ::stages '#{seon.test/run seon.test/select seon.test/clean-request-handle seon.test/isolated-members
                                 seon.test/bounded-result seon.test.runner/record-tx})]
    (try
      (mapv (fn [n]
              (let [record (window world (mcp-pass {"code" (str "(my.test/check {:seon.test/changed ['" test-sym "]})")
                                                    "agent" agent-id "cluster" running}))]
                (assoc record ::entrant :test ::kind :check ::sequence (inc n) ::prior-passes (inc n)
                       ::member-executed? (pos? (get-in record [::stages 'seon.test/bounded-result :seon.profile/calls] 0)))))
            (range repetitions))
      (finally (some-> (::host (agent-world running agent-id)) host/retire!)))))
