(ns seon.agent
  "An agent: a process forked from the fork server, holding its branch's db value
  (inherited from main at the branch point, then advanced by its own tx-data with
  d/db-with) and running its REPL over its socket to root (seon.repl). The live value of
  each form stays here under its handle. Agent code calls the verbs of my.db and my.agent,
  referred into its namespace. From proto/flow f2-final proto.agent (lane F2)."
  (:require [datahike.api :as d] [seon.lib :as lib] [seon.sock :as sock] [jolt.scheme :as scm] [clojure.test :as t]
            [clojure.string :as str] [jolt.ffi :as ffi] [seon.program :as program] [seon.intr :as intr] [seon.wire :as wire]))

;; One state map per agent process (this process is the agent). Fields:
;; :id :branch :fd :conn :db :results :n :pending :tx-seq :mode :ns :home :nss :mismatch
(defonce state (atom nil))

;; collect-trip-bytes in an agent: Chez's default scales with the parent heap (E10); a
;; small trip keeps an agent's garbage, and so its private pages, small
(def trip-bytes (* 1048576 (Long/parseLong (or (System/getenv "SEON_TRIP_MB") "4"))))

;; An agent's heap may grow this far beyond what it inherited; past it an allocation
;; throws OutOfMemoryError (an :err item), so a runaway agent cannot take the machine.
(def heap-extra-bytes (* 1048576 (Long/parseLong (or (System/getenv "SEON_AGENT_HEAP_MB") "1024"))))

(def ^:dynamic *print-limits* {:length 20 :level 4 :chars 20000})

(defn builtin-print
  "Collections cut by *print-length*/*print-level*; text beyond the char budget is cut
  and ends with the elision mark ` …`."
  [v]
  (let [s (binding [*print-length* (:length *print-limits*) *print-level* (:level *print-limits*)] (pr-str v))
        n (:chars *print-limits*)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

;; The value printer in use: nil is the builtin one. Root installs another (Seon's, lane
;; L3: proto.seon-printer) before it forks anything, so every agent inherits it. It is a
;; fn of [value handle-symbol] answering {:text ...} plus what it reports (:tokens ...).
(def ^:dynamic *value-printer* nil)

(defn print-value
  "The one place that turns a value into reply text. Answers {:printed text ...}; with
  Seon's printer also :tokens, :truncated? and :elisions, whose cuts are runnable forms on
  the handle (result/h3)."
  ([v] (print-value v nil))
  ([v handle]
   (if-let [p *value-printer*]
     (let [r (p v handle)]
       (if (:text r)
         (cond-> {:printed (:text r)} (:tokens r) (merge (select-keys r [:tokens :truncated? :elisions :html])))
         ;; the printer refused the value: the builtin print, with the refusal beside it
         {:printed (builtin-print v) :printer-error (or (:seon.error/message r) (str r))}))
     {:printed (builtin-print v)})))

(defn printer
  "Text of v through the value printer in use."
  [v] (:printed (print-value v nil)))

;; ---- the socket to root (nREPL's wire, seon.wire) -----------------------------

(defn send-wire!
  "Send an nREPL message (string keys) to root; :dead when root is gone."
  [m] (if (= :epipe (wire/send! (:fd @state) m)) :dead true))

(defn send!
  "Send a Seon platform message {:op k ...} to root (as seon/<op>); :dead when root is gone."
  [m] (send-wire! (wire/seon->wire m)))

(defn- recv-raw!
  "The next message from root: Seon's as {:op k ...}, an nREPL request as
  {:op :nrepl :msg m}, or :dead at EOF."
  []
  (let [m (wire/next-msg! (:rd @state))]
    (if (= m :eof) :dead (or (wire/wire->seon m) {:op :nrepl :msg m}))))

(defn next-msg! []
  (if-let [m (peek (:pending @state))]
    (do (swap! state update :pending pop) m)
    (recv-raw!)))

;; ---- the agent's own API ------------------------------------------------------

(defn db [] (:db @state))

(defn messages
  "The messages delivered to this agent and not yet taken ({:from :body}), taken: root's
  requests (e.g. :catch-up, \"main moved, merge main\") and other agents' sends."
  [] (let [[old _] (swap-vals! state assoc :inbox [])] (:inbox old)))
(defn send-message
  "Send body to the agents tos (ids); root delivers it by id. nil."
  [tos body] (when (= :dead (send! {:op :send :to (vec tos) :body body})) (lib/c-exit 0)) nil)

(defn- apply-sync!
  "Apply the writer's resolved datoms for this branch. A :sync (root merged main forward)
  may change the program: its :var/src is loaded, so this process runs its branch's code."
  [m]
  (let [db' (d/db-with (db) (:tx-data m))]
    (swap! state (fn [s] (cond-> (assoc s :db db')
                           (and (not (:resume m)) (not= (:max-tx db') (:t m))) (update :mismatch (fnil inc 0)))))
    (when (= :sync (:op m))
      (when-let [errs (seq (program/load-changes! db' (:tx-data m)))]
        (swap! state update :load-errors (fnil into []) errs)))))

(defn- await-op!
  "Wait for {:op op :k k} on the ring. A :sync is applied in arrival order (the writer's
  order); anything else waits in :pending for the loop."
  [op k]
  (loop []
    (let [m (recv-raw!)]
      (cond
        (= m :dead) (lib/c-exit 0)
        (and (= op (:op m)) (= k (:k m))) m
        (= :sync (:op m)) (do (apply-sync! m) (recur))
        :else (do (swap! state update :pending conj m) (recur))))))

(def ^:private tx-lock (Object.))

(defn transact!
  "Writes tx-data to this agent's branch through the writer; answers {:t :datoms}."
  [tx-data]
  (if (= :test (:mode @state))
    (let [r (d/with (db) tx-data)]
      (swap! state assoc :db (:db-after r))
      {:t (:max-tx (:db-after r)) :datoms (count (:tx-data r)) :local true})
    (let [k (:tx-seq (swap! state update :tx-seq inc))
          ;; not interruptible from the send until the writer's answer lands
          ;; (run-interruptible never escapes while a counted lock is held)
          m (locking tx-lock
              (when (= :dead (send! {:op :tx :k k :tx-data tx-data})) (lib/c-exit 0))
              (await-op! :tx-reply k))]
      (do
        (if (:ok m)
          ;; the writer's resolved datoms: ids come from the store-wide counter
          (do (apply-sync! m)
              (cond-> {:t (:t m) :datoms (:datoms m)} (seq (:tempids m)) (assoc :tempids (:tempids m))))
          (throw (ex-info (str "transact refused: " (:error m)) {:writer-error (:error m)})))))))

;; ---- evaluation -----------------------------------------------------------------

(defn error-map [^Throwable e]
  (cond-> {:message (or (ex-message e) (str (type e))) :class (str (type e))}
    (ex-data e) (assoc :data (printer (ex-data e)))))

(defn- interrupt-self-test
  "T1's boot check: SIGUSR1 reaches a Chez handler only in a process whose ancestors were
  all forked from a main thread. Answers whether this agent's handler ran."
  []
  (let [got (volatile! false)]
    (lib/on-signal! lib/SIGUSR1 (fn [] (vreset! got true)))
    (lib/c-kill (lib/c-getpid) lib/SIGUSR1)
    (loop [k 0] (when (and (not @got) (< k 100000)) (eval 1) (recur (inc k))))
    @got))

;; ---- tests, in a fork of this agent ------------------------------------------------

(defn- run-tests-here
  "clojure.test over every namespace this agent evaluated in; answers report data."
  []
  (let [reports (atom [])
        orig t/report
        ;; this agent's namespaces, and every program namespace holding tests: merged
        ;; proofs are regression tests for every agent
        prog (d/q '[:find [?n ...] :where [_ :ns/name ?n]] (db))
        nss (keep find-ns (into (set (:nss @state))
                                (filter (fn [n] (some->> (find-ns n) ns-interns vals (some (comp :test meta))))) prog))
        counts (binding [t/report (fn [m]
                                    (when (#{:pass :fail :error} (:type m))
                                      (swap! reports conj {:type (:type m) :var (some-> (first t/*testing-vars*) str)
                                                           :message (:message m)
                                                           :expected (printer (:expected m)) :actual (printer (:actual m))}))
                                    (orig m))
                         t/*test-out* (java.io.StringWriter.)]
                 ;; test-ns tests the vars interned now (Jolt's run-tests also runs its
                 ;; registry, which keeps a test after ns-unmap)
                 (apply merge-with + {:test 0 :pass 0 :fail 0 :error 0}
                        (map #(select-keys (t/test-ns %) [:test :pass :fail :error]) nss)))]
    {:pass (:pass counts 0) :fail (:fail counts 0) :error (:error counts 0) :test (:test counts 0)
     :reports @reports}))

(defn- ffi-status
  "Reap the test child (killing it first when kill?); answers its decoded exit status."
  [pid kill?]
  (when kill? (lib/c-kill pid 9))
  (let [b (ffi/alloc 4)]
    (lib/c-waitpid pid b 0)
    (lib/decode-status (ffi/read b :int))))

(defn run-tests-forked
  "Fork this agent; the child runs the tests and writes one EDN line on a pipe. A child
  that does not answer in timeout-ms is killed."
  [timeout-ms]
  (let [[r w] (lib/pipe) t0 (lib/now-ns) pid (lib/safe-fork 1000)]
    (cond
      (= pid -2)
      (do (lib/c-close r) (lib/c-close w)
          {:pass 0 :fail 0 :error 1 :ms (lib/ms-since t0)
           :reports [{:type :error :message "cannot fork the tests: another thread of this agent stayed busy for 1 s (a running future?)"}]})
      (zero? pid)
      (do (lib/c-close r)
          ;; die with the agent: it kills this child at the bound; if the agent dies itself,
          ;; the engine timer sees this process re-parented and exits (no watcher thread).
          ;; The agent's socket is FD_CLOFORK: this child does not hold it.
          (lib/set-trip! (* 16 1048576))
          (swap! state assoc :mode :test :evaluating false)
          (let [agent-pid (lib/c-getppid)
                res (try (lib/run-bounded (fn [] (not= agent-pid (lib/c-getppid))) (fn [] (lib/c-exit 0)) run-tests-here)
                         (catch Throwable e {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (ex-message e)}]}))]
            (lib/write-line! w (pr-str res)))
          (lib/c-exit 0))
      :else
      (do (lib/c-close w)
          (let [rd (lib/line-reader r)
                deadline (+ t0 (* timeout-ms 1000000))
                line (loop []
                       (let [left (quot (- deadline (lib/now-ns)) 1000000)]
                         (if (<= left 0)
                           :timeout
                           (let [ls (rd (min left 1000))]
                             (cond (= ls :eof) :eof (seq ls) (first ls) :else (recur))))))
                st (ffi-status pid (keyword? line))]
            (lib/c-close r)
            (assoc (cond
                     (string? line) (read-string line)
                     (= line :timeout) {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (str "tests timed out after " timeout-ms " ms; killed")}]}
                     :else {:pass 0 :fail 0 :error 1 :reports [{:type :error :message (str "test process died: " st)}]})
                   :ms (lib/ms-since t0) :pid pid :exit st))))))

;; ---- the loop ----------------------------------------------------------------------

(defn- make-ns! [id]
  (let [sym (symbol (str "agent." (name id)))
        nsx (create-ns sym)]
    (binding [*ns* nsx]
      (refer-clojure)
      (refer 'clojure.test :only '[deftest is are testing use-fixtures])
      (refer 'my.db)
      (refer 'my.agent)
      (alias 'd 'datahike.api)
      (alias 'str 'clojure.string))
    nsx))

(defn mem-mb
  "Real MB of this process (lib/real-mb walks the VM regions, ~1.5 ms), refreshed at most
  once a second, or now when fresh?."
  ([] (mem-mb false))
  ([fresh?]
   (let [{:keys [mem-at mem]} @state now (lib/now-ns)]
     (if (and (not fresh?) mem-at (< (- now mem-at) 1000000000))
       mem
       (let [m (lib/r2 (lib/real-mb))] (swap! state assoc :mem m :mem-at now) m)))))

(declare clone!)

(defn handle!
  "One Seon platform request; answers the messages to send back (a vector). (REPL
  requests are nREPL's: seon.repl.)"
  [m]
  (case (:op m)
    :tests [{:op :tests :id (:id m) :result (run-tests-forked (or (:timeout-ms m) 30000)) :mem-mb (mem-mb)}]
    :sync (do (apply-sync! m) [{:op :synced :t (:max-tx (db)) :mismatch (:mismatch @state 0) :load-errors (:load-errors @state)}])
    :clone (do (clone! m) nil)
    :stats [{:op :stats :id (:id m) :result {:mem-mb (mem-mb true) :t (:max-tx (db)) :pid (lib/c-getpid)
                                              :results (count (:results @state)) :evals (:n @state)
                                              :heap-mb (lib/r2 (/ (lib/bytes-in-heap) 1048576.0))}}]
    ;; a message (lane F2): it waits in :inbox until the agent's code takes it ((messages));
    ;; root hears that it arrived
    :message (do (swap! state update :inbox (fnil conj []) (select-keys m [:from :body]))
                 [{:op :received :sent-ns (:ns m) :recv-ns (sock/now-ns)}])
    nil))

(defn hello! [t-req-ns extra]
  (send! (merge {:op :hello :pid (lib/c-getpid) :start-ms (lib/ms-since t-req-ns) :mem-mb (mem-mb) :t (:max-tx (db))} extra)))

(defn- clone!
  "Fork this agent, live values and all, into a new agent on a branch of this branch.
  Root branches this branch first (no transaction is in flight here, so the new branch's
  head is this db); the clone is double-forked so it is not this agent's child."
  [{:keys [k] :as m}]
  (let [t0 (lib/now-ns)
        fd (sock/take-fd! (:conn @state))]
    (send! {:op :branch-me :k k})
    (let [r (await-op! :branched k)]
      (if-not (:ok r)
        (do (sock/close! fd) (send! {:op :clone-failed :k k :error (:error r)}))
        (let [pid1 (lib/safe-fork 1000)]
          (cond
            (= pid1 -2) (do (sock/close! fd) (send! {:op :clone-failed :k k :error "cannot fork: another thread of this agent stayed busy for 1 s"}))
            (zero? pid1)
            (if (zero? (lib/c-fork))
              (let [parent (:id @state)]
                ;; the parent's socket closed on fork (FD_CLOFORK); this clone's came with the request
                (sock/clofork! fd)
                (let [c (sock/conn fd)]
                  (swap! state assoc :id (:agent m) :branch (:branch m) :fd fd :conn c :rd (wire/reader c) :mode :agent :tx-seq 0
                         :pending clojure.lang.PersistentQueue/EMPTY :mem-at nil))
                (hello! t0 {:cloned-from parent})
                ((:serve @state)))
              (lib/c-exit 0))
            :else
            (do (ffi-status pid1 false)
                (sock/close! fd)
                (send! {:op :cloned :k k :clone (:agent m)}))))))))

(defn init!
  "Make this process the agent: its socket (fd), its namespace, its state map, its
  interrupt handlers. serve is the loop a clone runs (seon.repl/serve!)."
  [fd {id :agent branch :branch} db0 serve]
  ;; a test child or clone forked from here does not hold root's socket, so EOF on root's
  ;; end means this process is gone
  (sock/clofork! fd)
  (lib/set-trip! trip-bytes)
  (lib/heap-cap! (+ (lib/bytes-in-heap) heap-extra-bytes))
  (let [nsx (make-ns! id)
        intr-ok (interrupt-self-test)
        c (sock/conn fd)]
    (intr/install!)
    (reset! state {:id id :branch branch :fd fd :conn c :rd (wire/reader c) :db db0 :results {} :n 0
                   :pending clojure.lang.PersistentQueue/EMPTY :tx-seq 0 :mode :agent
                   :ns nsx :nss #{(ns-name nsx)} :interrupt-ok intr-ok :serve serve})))
