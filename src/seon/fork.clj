(ns seon.fork
  "Single-threaded System Fork Parent + COW Agent & clojure.test Worker processes.
   - Zero atoms in parent loop (pure recur state over `jolt.async.remote` + `jolt.io-poller/exit-chan`).
   - Zero `SCM_RIGHTS` or custom sockets.
   - Holds `:main` compiled warm in COW RAM (`:advance-image` applies O(|Delta|) diffs as `:main` moves),
     so forked test workers and agent REPLs start in ~5 ms with 0 commits of `:main` drift."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.io-poller :as poller]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.code :as code]
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

(defn apply-form-overlay!
  "Applies `{:upserts [...] :retracted-keys [...]}` directly into the current process's Clojure namespaces."
  [overlay]
  (code/apply-overlay! overlay))

;; --- Ephemeral Forked Child 1: Isolated clojure.test + Malli Generative Runner ---

(defn- run-single-test-var [test-sym digest]
  (let [t0 (System/nanoTime)
        ns-sym (symbol (namespace test-sym))
        sw (java.io.StringWriter.)
        counts (volatile! {:pass 0 :fail 0 :error 0 :messages []})]
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
      (try
        (when-not (find-ns ns-sym) (require ns-sym))
        (if-let [v (resolve test-sym)]
          (t/test-var v)
          (vswap! counts #(-> % (update :error inc)
                              (update :messages conj (str "Test var not found: " test-sym)))))
        (catch Throwable ex
          (vswap! counts #(-> % (update :error inc)
                              (update :messages conj (str "UNCAUGHT: " (.getMessage ex))))))))
    (let [{:keys [fail error messages]} @counts
          ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)
          pass? (zero? (+ fail error))]
      {:test/digest digest
       :test/var test-sym
       :test/pass? pass?
       :test/ms (double ms)
       :test/report (if pass?
                      (let [s (str/trim (str sw))] (if (str/blank? s) "PASS" s))
                      (str/join "\n" (cond-> messages (not (str/blank? (str sw))) (conj (str/trim (str sw))))))})))

(defn- check-malli-var [fn-sym]
  (when-let [v (resolve fn-sym)]
    (when-let [sch (:malli/schema (meta v))]
      (when (and (vector? sch) (= :=> (first sch)))
        (let [[_ cat-schema ret-schema] sch
              args-samples (mg/sample cat-schema {:size 8 :seed 42})
              valid-ret? (m/validator ret-schema)]
          (reduce (fn [_ sample-args]
                    (let [ans (apply @v sample-args)]
                      (if (valid-ret? ans)
                        {:ok? true}
                        (reduced {:ok? false :args sample-args :return ans}))))
                  {:ok? true}
                  args-samples))))))

(defn- run-test-worker! [end {:keys [batch-id overlay test-items malli-vars]}]
  (when overlay
    (apply-form-overlay! overlay))
  (let [results (mapv (fn [{:keys [var digest]}]
                        (run-single-test-var var digest))
                      test-items)
        gen-failures (vec
                      (keep (fn [sym]
                              (let [r (check-malli-var sym)]
                                (when (and r (not (:ok? r)))
                                  (assoc r :var sym))))
                            malli-vars))]
    (a/>!! (:out end) {:ev :test-batch-done
                       :batch-id batch-id
                       :worker-pid (c-getpid)
                       :mem-mb (mem-mb)
                       :results results
                       :generative-failures gen-failures})))

;; --- Ephemeral / Interactive Forked Child 2: Agent REPL & Turn Runner ---

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
      (intern u 'search
              (fn [substr]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [db @conn]
                    (->> (d/datoms db :aevt :form/key)
                         (keep (fn [dt]
                                 (when (str/includes? (:v dt) substr)
                                   (:v dt))))
                         sort
                         (take 40)
                         vec)))))
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

(defn- run-agent-worker! [end {:keys [agent-id branch cfg mode initial-eval]}]
  (store/after-fork! cfg)
  (let [in (:in end)
        out (:out end)
        conn (store/connect cfg branch)
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
                  :mem-mb (mem-mb)
                  :loaded-delta (:loaded loaded)})
      (binding [*agent-ctx* {:conn conn :out out :agent-id agent-id :branch branch}]
        (case mode
          ;; Interactive / CLI / MCP REPL eval on `branch` (supports rapid session evals until :stop)
          :repl
          (let [run-one! (fn [{:keys [req-id code ns]}]
                           (let [turn-n (inc (count (d/datoms @conn :avet :eval/agent agent-id)))
                                 res (code/eval-forms @conn {:agent-id agent-id :turn turn-n :default-ns ns} code)]
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
                                         :final-ns (:final-ns res)})))]
            (when initial-eval (run-one! initial-eval))
            (loop [cur-ns (or (:ns initial-eval) 'user)]
              (when-let [msg (a/<!! in)]
                (case (:op msg)
                  :eval (do (run-one! (update msg :ns #(or % cur-ns)))
                            (recur (:ns msg cur-ns)))
                  :stop nil
                  (recur cur-ns)))))

          ;; Autonomous Agent Turn: Pull context + unread mail -> request :model -> eval Clojure -> commit -> exit 0!
          :turn
          (let [main-db (store/head conn :main)
                branch-db @conn
                me (d/pull main-db '[*] [:agent/id agent-id])
                unread (unread-messages-for main-db branch-db agent-id)
                turn-n (inc (count (d/datoms branch-db :avet :eval/agent agent-id)))
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
              (when-let [m (a/<!! in)]
                (cond
                  (= (:op m) :stop) nil
                  (and (= (:op m) :model-response) (= (:req-id m) req-id))
                  (let [{:keys [thought code]} (:response m)
                        seen-ids (mapv :message/id unread)
                        res (code/eval-forms @conn
                                             {:agent-id agent-id
                                              :turn turn-n
                                              :seen-messages seen-ids
                                              :thought thought}
                                             (or code ":noop"))]
                    (d/transact conn {:tx-data (:tx-data res)})
                    (a/>!! out {:ev :turn-committed
                                :agent-id agent-id
                                :branch branch
                                :turn turn-n
                                :branch-tx (:max-tx @conn)
                                :ok? (:ok? res)
                                :value (:value-str res)
                                :error (:error res)}))
                  :else (recur)))))))
      (finally
        (d/release conn)))))

;; --- Single-Threaded System Fork Parent Loop (0 atoms, 0 LMDB handles) ---

(defn- run-fork-parent-loop! [fd]
  (remote/close-streams-but! #{fd})
  (let [parent (remote/open fd {:pump :fiber :buf 128})
        cmd-in (:in parent)
        ev-out (:out parent)
        exits-ch (a/chan 128)]
    (a/>!! ev-out {:ev :fork-parent-ready :pid (c-getpid) :mem-mb (mem-mb)})
    (loop [children {} ; {os-pid {:type :agent|:test :agent-id kw :batch-id any :end remote-end}}
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
  "Spawns the single-threaded fork helper process BEFORE opening LMDB or starting multi-core Flow pools."
  []
  (let [[end fd] (remote/remote-pair {:pump :fiber :buf 128})
        pid (jolt.host/fork-alone 2000)]
    (when (zero? pid)
      (run-fork-parent-loop! fd))
    (when (neg? pid)
      (throw (ex-info "Failed to fork single-threaded fork parent" {:code pid})))
    (remote/close-fd! fd)
    (let [ready (a/<!! (:in end))]
      {:pid (:pid ready)
       :mem-mb (:mem-mb ready)
       :end end})))
