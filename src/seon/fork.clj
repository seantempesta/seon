(ns seon.fork
  "Single-threaded System Fork Parent + COW Agent & clojure.test Worker processes:
   - Zero atoms in parent loop (pure recur state over `jolt.async.remote` + `jolt.io-poller/exit-chan`).
   - COW GC ceiling (`gc-generations! 6` + `(System/gc)` promotion before fork & after `:advance-image`)
     so children never collect or copy inherited `:main` pages (`untouched? = true`).
   - Persistent Agent Worker loop: keeps the forked OS process alive across multi-turn work AND REPL
     `eval`s (preserving live runtime vars, `*ns*`, and `ctx`), supporting multi-tool calls per turn!
   - Isolated `clojure.test` + 100-sample `seed = 42` Malli generative + `:cost/*` budget worker."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [jolt.io-poller :as poller]
            [seon.code :as code]
            [seon.schema :as schema]
            [seon.store :as store]))

(ffi/load-library)
(ffi/defcfn c-getpid "getpid" [] :int)
(ffi/defcfn c-waitpid "waitpid" [:int :pointer :int] :int)
(ffi/defcfn c-kill "kill" [:int :int] :int)

(defn mem-mb []
  (if-let [f (or (resolve 'jolt.host/process-private-bytes)
                 (resolve 'jolt.host/process-footprint-bytes))]
    (/ (Math/round (/ (double (@f)) 104857.6)) 10.0)
    0.0))

(defn promote-heap!
  "Promotes live objects into this process's top GC generation before forking children,
   so child `collect-ceiling` (automatically `top - 1` on `fork-alone`) never scans inherited pages."
  []
  (System/gc)
  (System/gc))

(defn apply-form-overlay!
  "Applies `{:upserts [...] :retracted-keys [...]}` directly into the current process's Clojure namespaces."
  ([overlay] (code/apply-overlay! nil overlay))
  ([db overlay] (code/apply-overlay! db overlay)))

;; --- Ephemeral Forked Child 1: Isolated clojure.test + Seed-42 Malli Generative + Cost Budget Runner ---

(defn- run-single-test-var [test-sym digest]
  (let [ns-sym (symbol (namespace test-sym))
        sw (java.io.StringWriter.)
        counts (volatile! {:pass 0 :fail 0 :error 0 :messages []})
        measured
        (binding [*out* sw
                  *err* sw
                  t/*test-out* sw
                  t/report (fn [m]
                             (case (:type m)
                               :pass (vswap! counts update :pass inc)
                               :fail (vswap! counts #(-> % (update :fail inc)
                                                         (update :messages conj (str "FAIL: " (:message m "") " expected=" (pr-str (:expected m)) " actual=" (pr-str (:actual m))))))
                               :error (vswap! counts #(-> % (update :error inc)
                                                          (update :messages conj (str "ERROR: " (:message m "") " actual=" (pr-str (:actual m))))))
                               nil))]
          (code/measure-call
           (fn []
             (try
               (when-not (find-ns ns-sym) (require ns-sym))
               (if-let [v (resolve test-sym)]
                 (do (t/test-var v) v)
                 (do (vswap! counts #(-> % (update :error inc)
                                         (update :messages conj (str "Test var not found: " test-sym))))
                     nil))
               (catch Throwable ex
                 (vswap! counts #(-> % (update :error inc)
                                     (update :messages conj (str "UNCAUGHT: " (.getMessage ex)))))
                 nil)))))
        v (:val measured)
        vmeta (when (var? v) (meta v))
        ticks (:ticks measured)
        bytes (:bytes measured)
        ms (:ms measured)
        ticks-budget (or (:cost/ticks vmeta) (get-in vmeta [:seon.test/estimate :ticks]))
        bytes-budget (or (:cost/bytes vmeta) (get-in vmeta [:seon.test/estimate :bytes]))
        _ (when (and ticks-budget (> ticks ticks-budget))
            (vswap! counts #(-> % (update :fail inc)
                                (update :messages conj
                                        (str "COST OVERRUN on " test-sym ": actual " ticks " ticks > estimated "
                                             ticks-budget " ticks (:cost/ticks). Optimize or update estimate.")))))
        _ (when (and bytes-budget (> bytes bytes-budget))
            (vswap! counts #(-> % (update :fail inc)
                                (update :messages conj
                                        (str "BYTES OVERRUN on " test-sym ": actual " bytes " bytes > estimated "
                                             bytes-budget " bytes (:cost/bytes). Optimize or update estimate.")))))
        {:keys [fail error messages]} @counts
        pass? (zero? (+ fail error))]
    {:test/digest digest
     :test/var test-sym
     :test/kind :deftest
     :test/pass? pass?
     :test/ms (double ms)
     :test/ticks (long ticks)
     :test/bytes (long bytes)
     :test/report (if pass?
                    (let [s (str/trim (str sw))] (if (str/blank? s) "PASS" s))
                    (str/join "\n" (cond-> messages (not (str/blank? (str sw))) (conj (str/trim (str sw))))))}))

(defn- run-test-worker! [end {:keys [batch-id cfg branch overlay test-items gen-items samples seed]}]
  (let [conn (when (and cfg branch)
               (store/after-fork! cfg)
               (store/connect (store/child-cfg cfg 4) branch))]
    (try
      (let [db (when conn @conn)]
        (when overlay
          (apply-form-overlay! db overlay))
        (let [test-results (mapv (fn [{:keys [var digest]}]
                                   (run-single-test-var var digest))
                                 test-items)
              gen-results (vec
                           (keep (fn [{:keys [var digest]}]
                                   (when-let [r (schema/stress-var db var {:samples (or samples 100)
                                                                           :seed (or seed 42)
                                                                           :measure-fn code/measure-call})]
                                     {:test/digest digest
                                      :test/var var
                                      :test/kind :generative
                                      :test/pass? (boolean (:ok? r))
                                      :test/ms (double (:ms r 0.0))
                                      :test/ticks (long (:max-ticks r 0))
                                      :test/bytes (long (:max-bytes r 0))
                                      :test/report (if (:ok? r)
                                                     (str "PASS (" (:ran r) " samples, seed=" (or seed 42) ")")
                                                     (str (:reason r)))
                                      :detail r}))
                                 gen-items))
              gen-failures (vec (keep #(when-not (:test/pass? %) (:detail %)) gen-results))
              all-results (into test-results (map #(dissoc % :detail) gen-results))]
          (a/>!! (:out end) {:ev :test-batch-done
                             :batch-id batch-id
                             :worker-pid (c-getpid)
                             :mem-mb (mem-mb)
                             :results all-results
                             :generative-failures gen-failures})))
      (finally
        (when conn (d/release conn))))))

;; --- Persistent Forked Child 2: Unified Agent Runtime (Multi-Turn + Multi-Tool + Interactive REPL!) ---

(def ^:dynamic *agent-ctx* nil)

(defn install-agent-helpers! []
  (let [u (create-ns 'user)]
    (binding [*ns* u]
      (refer-clojure)
      (intern u 'task
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (let [ag (d/pull @conn '[:agent/task :agent/goal] [:agent/id agent-id])]
                    (if-let [tid (:agent/task ag)]
                      (d/pull @conn '[*] [:task/id tid])
                      ag)))))
      (intern u 'config
              (fn
                ([] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                      (:effective (store/config-map (store/head conn :main) agent-id))))
                ([k] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                       (store/config-val (store/head conn :main) agent-id k)))))
      (intern u 'register-schema!
              (fn [key form & [{:keys [doc examples render]}]]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [{:keys [sample entity]}
                        (schema/validate-schema-registration!
                         @conn {:key key :form form :doc doc :examples examples :render render})]
                    (d/transact conn {:tx-data [entity]})
                    {:registered key :sample sample}))))
      (intern u 'search
              (fn [substr]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn
                        q (str/lower-case (str substr))]
                    {:vars (->> (d/datoms db :aevt :var/name)
                                (keep #(when (str/includes? (str/lower-case (str (:v %))) q) (:v %)))
                                sort (take 25) vec)
                     :schemas (->> (d/datoms db :aevt :schema/key)
                                   (keep #(when (str/includes? (str/lower-case (str (:v %))) q) (:v %)))
                                   sort (take 20) vec)
                     :docs (->> (d/datoms db :aevt :doc/id)
                                (keep #(when (str/includes? (str/lower-case (str (:v %))) q) (:v %)))
                                sort (take 20) vec)}))))
      (intern u 'send-message!
              (fn [to msg-type body]
                (when-let [{:keys [out agent-id]} *agent-ctx*]
                  (a/>!! out {:ev :tx-main
                              :op :send-message
                              :from agent-id
                              :to to
                              :type msg-type
                              :body (str body)})
                  {:sent-to to :type msg-type})))
      (intern u 'spawn-agent!
              (fn [{:keys [id branch role goal task]}]
                (when-let [{:keys [out agent-id]} *agent-ctx*]
                  (let [b (or branch (keyword "branch" (str (namespace id) "." (name id))))]
                    (a/>!! out {:ev :tx-main
                                :op :spawn-agent
                                :parent agent-id
                                :id id
                                :branch b
                                :role (or role :worker)
                                :goal (or goal "")
                                :task task})
                    {:spawned id :branch b}))))
      (intern u 'wait-for-mail!
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (d/transact conn {:tx-data [{:agent/id agent-id :agent/waiting? true :agent/ready? false}]})
                  :waiting)))
      (intern u 'ready!
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (d/transact conn {:tx-data [{:agent/id agent-id :agent/waiting? false :agent/ready? true}]})
                  :ready))))))

(defn- unread-messages-for [main-db branch-db agent-id]
  (let [seen (into #{}
                   (mapcat (fn [dt]
                             (:eval/seen-messages (d/pull branch-db '[:eval/seen-messages] (:e dt)))))
                   (d/datoms branch-db :avet :eval/agent agent-id))]
    (->> (d/datoms main-db :avet :message/to agent-id)
         (map #(d/pull main-db '[:message/id :message/seq :message/from :message/to :message/type :message/body] (:e %)))
         (remove #(contains? seen (:message/id %)))
         (sort-by :message/seq)
         vec)))

(defn- sync-conn-to-latest-branch-head!
  "If another writer (e.g. `:import-file`) committed onto `branch` since our last step,
   hot-loads the `d/changes` delta into this warm runtime in O(|Delta|)!"
  [conn branch last-cid]
  (let [latest-db (store/head conn branch)
        latest-cid (d/commit-id latest-db)]
    (when (and last-cid latest-cid (not= last-cid latest-cid))
      (reset! (:wrapped-atom conn) latest-db)
      (code/load-delta! conn last-cid latest-cid))
    latest-cid))

(defn- execute-turn-step! [conn in out agent-id branch cur-ns]
  (let [main-db (store/head conn :main)
        branch-db @conn
        me (d/pull main-db '[*] [:agent/id agent-id])
        unread (unread-messages-for main-db branch-db agent-id)
        past-turns (map #(:eval/turn (d/pull branch-db '[:eval/turn] (:e %)))
                        (d/datoms branch-db :avet :eval/agent agent-id))
        turn-n (inc (reduce max 0 past-turns))
        req-id (str (name agent-id) "-t" turn-n "-" (c-getpid))]
    (a/>!! out {:ev :model-request
                :req-id req-id
                :agent-id agent-id
                :branch branch
                :turn turn-n
                :role (:agent/role me :worker)
                :goal (:agent/goal me "")
                :parent (:agent/parent me)
                :unread unread})
    (loop []
      (if-let [m (a/<!! in)]
        (cond
          (= (:op m) :stop)
          {:stopped? true :ns cur-ns}

          ;; Allow interactive `:eval` even if waiting, or handle the matching `:model-response`!
          (and (= (:op m) :model-response) (= (:req-id m) req-id))
          (let [{:keys [thought code tool-calls]} (:response m)
                seen-ids (mapv :message/id unread)
                calls (if (seq tool-calls)
                        tool-calls
                        [{:id "tc-0" :code (or code ":noop")}])
                multi-res (code/eval-tool-calls
                           conn
                           {:agent-id agent-id
                            :turn turn-n
                            :seen-messages seen-ids
                            :thought thought
                            :start-ns cur-ns}
                           calls)]
            (a/>!! out {:ev :turn-committed
                        :agent-id agent-id
                        :branch branch
                        :turn turn-n
                        :branch-tx (:max-tx @conn)
                        :ok? (:ok? multi-res)
                        :tool-results (:results multi-res)
                        :final-ns (:final-ns multi-res)})
            {:stopped? false :ns (:final-ns multi-res cur-ns)})

          :else (recur))
        {:stopped? true :ns cur-ns}))))

(defn- run-eval-cmd! [conn out agent-id branch cur-ns {:keys [req-id code tool-calls ns]}]
  (let [past-turns (map #(:eval/turn (d/pull @conn '[:eval/turn] (:e %)))
                        (d/datoms @conn :avet :eval/agent agent-id))
        turn-n (inc (reduce max 0 past-turns))]
    (if (seq tool-calls)
      (let [multi (code/eval-tool-calls conn
                                        {:agent-id agent-id
                                         :turn turn-n
                                         :start-ns (or ns cur-ns)}
                                        tool-calls)]
        (a/>!! out {:ev :eval-done
                    :req-id req-id
                    :agent-id agent-id
                    :branch branch
                    :branch-tx (:max-tx @conn)
                    :ok? (:ok? multi)
                    :tool-results (:results multi)
                    :value (pr-str (mapv :value (:results multi)))
                    :error (some :error (:results multi))
                    :final-ns (:final-ns multi)})
        (:final-ns multi cur-ns))
      (let [res (code/eval-forms @conn {:agent-id agent-id :turn turn-n :default-ns (or ns cur-ns)} code)]
        (d/transact conn {:tx-data (:tx-data res)})
        (a/>!! out {:ev :eval-done
                    :req-id req-id
                    :agent-id agent-id
                    :branch branch
                    :branch-tx (:max-tx @conn)
                    :ok? (:ok? res)
                    :value (:value-str res)
                    :out (:out res)
                    :error (:error res)
                    :ticks (:ticks res)
                    :bytes (:bytes res)
                    :ms (:ms res)
                    :final-ns (:final-ns res)})
        (:final-ns res cur-ns)))))

(defn- run-agent-worker! [end {:keys [agent-id branch cfg mode initial-eval]}]
  (let [lean-cfg (store/child-cfg cfg 4)]
    (store/after-fork! lean-cfg)
    (let [in (:in end)
          out (:out end)
          conn (store/connect lean-cfg branch)
          branch-cid (d/commit-id @conn)
          base-cid (when (not= branch :main)
                     (d/merge-base conn :main branch))
          loaded (if (and base-cid branch-cid (not= base-cid branch-cid))
                   (code/load-delta! conn base-cid branch-cid)
                   {:loaded [] :unmapped []})]
      (try
        (install-agent-helpers!)
        (a/>!! out {:ev :agent-started
                    :agent-id agent-id
                    :branch branch
                    :pid (c-getpid)
                    :ceiling (jolt.host/collect-ceiling)
                    :mem-mb (mem-mb)
                    :loaded-delta (:loaded loaded)})
        (binding [*agent-ctx* {:conn conn :out out :agent-id agent-id :branch branch}]
          (let [ns0 (cond
                      initial-eval
                      (run-eval-cmd! conn out agent-id branch 'user initial-eval)

                      (= mode :turn)
                      (let [r (execute-turn-step! conn in out agent-id branch 'user)]
                        (if (:stopped? r) ::stop (:ns r)))

                      :else 'user)]
            (when-not (= ::stop ns0)
              ;; Persistent agent event loop: stays warm across multi-turn work & REPL tool calls!
              (loop [cur-ns ns0
                     known-cid (d/commit-id @conn)]
                (when-let [msg (a/<!! in)]
                  (case (:op msg)
                    :stop nil

                    :eval
                    (let [_ (sync-conn-to-latest-branch-head! conn branch known-cid)
                          ns' (run-eval-cmd! conn out agent-id branch cur-ns msg)]
                      (recur ns' (d/commit-id @conn)))

                    :run-turn
                    (let [_ (sync-conn-to-latest-branch-head! conn branch known-cid)
                          r (execute-turn-step! conn in out agent-id branch cur-ns)]
                      (when-not (:stopped? r)
                        (recur (:ns r) (d/commit-id @conn))))

                    (recur cur-ns known-cid)))))))
        (finally
          (d/release conn))))))

;; --- Single-Threaded System Fork Parent Loop (0 atoms, 0 LMDB handles) ---

(defn- run-fork-parent-loop! [fd]
  (remote/close-streams-but! #{fd})
  (promote-heap!)
  (let [parent (remote/open fd {:pump :fiber :buf 128})
        cmd-in (:in parent)
        ev-out (:out parent)
        exits-ch (a/chan 128)]
    (a/>!! ev-out {:ev :fork-parent-ready
                   :pid (c-getpid)
                   :ceiling (jolt.host/collect-ceiling)
                   :mem-mb (mem-mb)})
    (loop [children {}
           warm-commit nil]
      (let [[v ch] (a/alts!! [cmd-in exits-ch])]
        (cond
          ;; 1. Kernel kqueue EVFILT_PROC exit event: reap OS zombie immediately, then deliver
          ;;    `raw-status` to the child's stream reader fiber so ALL buffered socket frames drain
          ;;    in causal order BEFORE `:child-exited` is emitted!
          (= ch exits-ch)
          (let [pid v
                st-buf (ffi/alloc 4)
                _ (c-waitpid pid st-buf 0)
                raw-status (ffi/read st-buf :int)
                _ (ffi/free st-buf)
                info (get children pid)]
            (when info
              (deliver (:reaped-status info) raw-status))
            (recur (dissoc children pid) warm-commit))

          ;; 2. Parent closed pipe -> reap & exit cleanly
          (nil? v)
          (do (doseq [[pid {:keys [end]}] children]
                (c-kill pid 9)
                (remote/close! end))
              (System/exit 0))

          ;; 3. Commands from Flow procs
          :else
          (case (:op v)
            :advance-image
            (do (apply-form-overlay! v)
                (promote-heap!)
                (a/>!! ev-out {:ev :image-advanced :commit (:commit v)})
                (recur children (:commit v)))

            (:fork-agent :fork-test)
            (let [t0 (System/nanoTime)
                  child-type (if (= (:op v) :fork-agent) :agent :test)
                  [cend cfd] (remote/remote-pair {:pump :fiber :buf 32})
                  pid (jolt.host/fork-alone 1000)]
              (cond
                (zero? pid)
                (do (remote/close-streams-but! #{cfd})
                    (let [mine (remote/open cfd {:pump :fiber :buf 32})]
                      (try
                        (if (= child-type :agent)
                          (run-agent-worker! mine (assoc v :base-commit (or (:base-commit v) warm-commit)))
                          (run-test-worker! mine v))
                        (catch Throwable ex
                          (a/>!! (:out mine) {:ev :child-exception
                                              :type child-type
                                              :agent-id (:agent-id v)
                                              :batch-id (:batch-id v)
                                              :error (.getMessage ex)})))
                      (a/close! (:out mine))
                      (a/<!! (:sent mine))
                      (System/exit 0)))

                (neg? pid)
                (do (remote/close! cend)
                    (remote/close-fd! cfd)
                    (a/>!! ev-out {:ev :fork-refused :type child-type :code pid})
                    (recur children warm-commit))

                :else
                (let [fork-ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e5)) 10.0)
                      reaped-status (promise)]
                  (remote/close-fd! cfd)
                  (poller/exit-chan pid exits-ch)
                  (a/>!! ev-out {:ev :forked
                                 :type child-type
                                 :agent-id (:agent-id v)
                                 :batch-id (:batch-id v)
                                 :pid pid
                                 :fork-ms fork-ms})
                  (a/go
                    (loop []
                      (when-let [frame (a/<! (:in cend))]
                        (a/>! ev-out {:ev :child-frame
                                      :type child-type
                                      :agent-id (:agent-id v)
                                      :batch-id (:batch-id v)
                                      :pid pid
                                      :frame frame})
                        (recur)))
                    (let [raw-status (deref reaped-status 2000 -1)]
                      (remote/close! cend)
                      (a/>! ev-out {:ev :child-exited
                                    :type child-type
                                    :agent-id (:agent-id v)
                                    :batch-id (:batch-id v)
                                    :pid pid
                                    :raw-status raw-status})))
                  (recur (assoc children pid {:type child-type
                                              :agent-id (:agent-id v)
                                              :batch-id (:batch-id v)
                                              :reaped-status reaped-status
                                              :end cend})
                         warm-commit))))

            :to-child
            (do (when-let [end (get-in children [(:pid v) :end])]
                  (a/put! (:out end) (:msg v)))
                (recur children warm-commit))

            :kill-all-9!
            (let [pids (vec (keys children))]
              (doseq [[pid {:keys [end reaped-status]}] children]
                (c-kill pid 9)
                (let [st-buf (ffi/alloc 4)]
                  (c-waitpid pid st-buf 0)
                  (deliver reaped-status (ffi/read st-buf :int))
                  (ffi/free st-buf))
                (remote/close! end))
              (a/>!! ev-out {:ev :killed-all-9 :pids pids})
              (recur {} warm-commit))

            (recur children warm-commit)))))))

(defn start-fork-parent!
  "Raises Chez GC generations to 6, promotes inherited static/runtime heap into top generation,
   and spawns the single-threaded fork helper process BEFORE opening LMDB or starting Flow threads."
  []
  (jolt.host/gc-generations! 6)
  (promote-heap!)
  (let [[end fd] (remote/remote-pair {:pump :fiber :buf 128})
        pid (jolt.host/fork-alone 2000)]
    (when (zero? pid)
      (run-fork-parent-loop! fd))
    (when (neg? pid)
      (throw (ex-info "Failed to fork single-threaded fork parent" {:code pid})))
    (remote/close-fd! fd)
    (let [ready (a/<!! (:in end))]
      {:pid (:pid ready)
       :ceiling (:ceiling ready)
       :mem-mb (:mem-mb ready)
       :end end})))
