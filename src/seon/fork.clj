(ns seon.fork
  "Single-threaded System Fork Parent + COW Agent & clojure.test Worker processes:
   - Zero atoms in parent loop (pure recur state over `jolt.async.remote` + `jolt.io-poller/exit-chan`).
   - COW GC ceiling (`gc-generations! 6` + `(System/gc)` promotion before fork & after `:advance-image`).
   - Persistent Agent Worker loop: keeps the forked OS process alive across multi-turn work AND REPL
     `eval`s, supporting multi-tool calls per turn and dynamic runtime Malli->Datahike schema registration!"
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [jolt.io-poller :as poller]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.fork.calls :as fcalls :refer [*agent-ctx*]]
            [seon.fork.helpers :as helpers]
            [seon.fork.worker-support :as wsup]
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

(defn promote-heap! []
  (System/gc)
  (System/gc))

(defn apply-form-overlay! [& args] (apply wsup/apply-form-overlay! args))

(defn- run-test-worker! [end {:keys [batch-id cfg branch overlay test-items gen-items samples seed]}]
  (let [conn (when (and cfg branch)
               (store/after-fork! cfg)
               (store/connect (store/child-cfg cfg 4) branch))]
    (try
      (let [db (when conn @conn)]
        (when overlay
          (apply-form-overlay! db overlay))
        (let [test-results (mapv (fn [{:keys [var digest]}]
                                   (wsup/run-single-test-var var digest))
                                 test-items)
              gen-results (vec
                           (keep (fn [{:keys [var digest]}]
                                   (when-let [r (schema/stress-var db var {:samples (or samples 100)
                                                                           :seed (or seed 42)
                                                                           :measure-fn code/measure-call})]
                                     {:seon.test/digest digest
                                      :seon.test/var var
                                      :seon.test/kind :generative
                                      :seon.test/pass? (boolean (:ok? r))
                                      :seon.test/ms (double (:ms r 0.0))
                                      :seon.test/ticks (long (:max-ticks r 0))
                                      :seon.test/bytes (long (:max-bytes r 0))
                                      :seon.test/report (if (:ok? r)
                                                          (str "PASS (" (:ran r) " samples, seed=" (or seed 42) ")")
                                                          (str (:reason r)))
                                      :detail r}))
                                 gen-items))
              gen-failures (vec (keep #(when-not (:seon.test/pass? %) (:detail %)) gen-results))
              all-results (into test-results (map #(dissoc % :detail) gen-results))]
          (a/>!! (:out end) {:ev :test-batch-done
                             :batch-id batch-id
                             :worker-pid (c-getpid)
                             :mem-mb (mem-mb)
                             :results all-results
                             :generative-failures gen-failures})))
      (finally
        (when conn (d/release conn))))))

(defn install-agent-helpers! [& args] (apply helpers/install-agent-helpers! args))

(defn- sync-missing-main-schema! [& args] (apply wsup/sync-missing-main-schema! args))

(defn- sync-conn-to-latest-branch-head! [& args] (apply wsup/sync-conn-to-latest-branch-head! args))

(defn- maybe-emit-branch-render!
  "When an agent on branch `B` defines a custom `:seon.agent/render-fn` (or edits its own
   configured `:seon.agent/render-fn` ahead of `:main`), evaluates it safely inside the warm COW
   worker and pushes pure Hiccup EDN straight to `:web` so UI refinements take effect in realtime!"
  [conn out agent-id branch]
  (when (and conn out agent-id (not= branch :main))
    (try
      (let [bdb @conn
            mdb (store/head conn :main)
            base-db (or (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)) mdb)
            ag (d/pull bdb '[*] [:seon.agent/id agent-id])
            default-syms #{'seon.render.ui/agent-canvas 'seon.render.ui/root-canvas 'seon.render.ui/default-canvas}
            rfn-sym (or (:seon.agent/render-fn ag) 'seon.render.ui/agent-canvas)
            fkey (when (qualified-symbol? rfn-sym) (str rfn-sym))
            rfn-edited? (when fkey
                          (let [bh (:seon.code.form/hash (d/pull bdb '[:seon.code.form/hash] [:seon.code.form/key fkey]))
                                base-h (:seon.code.form/hash (d/pull base-db '[:seon.code.form/hash] [:seon.code.form/key fkey]))
                                mh (:seon.code.form/hash (d/pull mdb '[:seon.code.form/hash] [:seon.code.form/key fkey]))]
                            (and bh (not= bh base-h) (not= bh mh))))
            custom? (and (qualified-symbol? rfn-sym) (not (contains? default-syms rfn-sym)))
            target-sym (when (or custom? rfn-edited?) rfn-sym)]
        (when target-sym
          (when-let [rfn (or (try (resolve target-sym) (catch Throwable _ nil))
                             (try (requiring-resolve target-sym) (catch Throwable _ nil)))]
            (let [ctx-map (assoc (or (when ctx/ctx @ctx/ctx) ag)
                                 :seon.ctx/db bdb
                                 :seon.ctx/main-db mdb
                                 :seon.ctx/branch branch
                                 :seon.ctx/tx (:max-tx bdb))
                  res (try
                        {:ok? true
                         :hiccup (wsup/sanitize-edn-hiccup
                                  (jolt.host/run-interruptible
                                   (jolt.host/make-interrupt)
                                   #(try
                                      (rfn ctx-map)
                                      (catch clojure.lang.ArityException _
                                        (rfn bdb {:conn conn :branch-db bdb :viewing-id agent-id :agent ag})))
                                   100000000
                                   268435456))}
                        (catch Throwable ex
                          {:ok? false :error (or (.getMessage ex) (str ex))}))]
              (a/>!! out (cond-> {:ev :branch-render
                                  :agent-id agent-id
                                  :branch branch
                                  :branch-tx (:max-tx bdb)
                                  :render-fn target-sym}
                           (:ok? res) (assoc :hiccup (:hiccup res))
                           (not (:ok? res)) (assoc :error (:error res))))))))
      (catch Throwable _ nil))))

(defn- run-fast-auto-proofs! [& args] (apply wsup/run-fast-auto-proofs! args))

(defn- execute-turn-step! [conn in out agent-id branch cur-ns]
  (let [main-db (store/head conn :main)
        branch-db @conn
        me (d/pull main-db '[*] [:seon.agent/id agent-id])
        unread (wsup/unread-messages-for main-db branch-db agent-id)
        history (wsup/agent-history-turns branch-db agent-id)
        turn-n (inc (:max-turn (store/agent-eval-history-stats branch-db agent-id)))
        req-id (str (name agent-id) "-t" turn-n "-" (c-getpid))]
    (a/>!! out {:ev :model-request
                :req-id req-id
                :agent-id agent-id
                :branch branch
                :turn turn-n
                :scope (:seon.agent/scope me)
                :role (:seon.agent/role me :worker)
                :goal (:seon.agent/goal me "")
                :parent (:seon.agent/parent me)
                :history history
                :unread unread})
    (loop []
      (if-let [m (a/<!! in)]
        (cond
          (= (:op m) :stop)
          {:stopped? true :ns cur-ns}

          (and (= (:op m) :model-response) (= (:req-id m) req-id))
          (let [{:keys [thought reasoning usage code tool-calls]} (:response m)
                prefix-hash (:prefix-hash m)
                seen-ids (mapv :seon.agent.message/id unread)
                has-calls? (or (seq tool-calls) (not (str/blank? (str code))))]
            (if-not has-calls?
              (let [reply-ent (cond-> {:seon.agent.eval/id (random-uuid)
                                       :seon.agent.eval/agent agent-id
                                       :seon.agent.eval/turn (long turn-n)
                                       :seon.agent.eval/code ""
                                       :seon.agent.eval/thought (or thought "")
                                       :seon.agent.eval/out ""
                                       :seon.agent.eval/ms 0.0
                                       :seon.agent.eval/ticks 0
                                       :seon.agent.eval/bytes 0}
                                (seq seen-ids) (assoc :seon.agent.eval/seen-messages (set seen-ids))
                                reasoning (assoc :seon.agent.eval/reasoning reasoning)
                                prefix-hash (assoc :seon.agent.eval/prefix-hash (str prefix-hash))
                                (:prompt-tokens usage) (assoc :seon.agent.eval/prompt-tokens (long (:prompt-tokens usage)))
                                (:completion-tokens usage) (assoc :seon.agent.eval/completion-tokens (long (:completion-tokens usage)))
                                (:cache-hit-tokens usage) (assoc :seon.agent.eval/cache-hit-tokens (long (:cache-hit-tokens usage)))
                                (:cache-miss-tokens usage) (assoc :seon.agent.eval/cache-miss-tokens (long (:cache-miss-tokens usage))))]
                (d/transact conn {:tx-data [reply-ent
                                            (cond-> {:seon.agent/id agent-id :seon.agent/waiting? true}
                                              (seq seen-ids) (assoc :seon.agent/ready? false))]})
                (ctx/refresh-active! @conn nil)
                (maybe-emit-branch-render! conn out agent-id branch)
                (a/>!! out {:ev :turn-committed
                            :agent-id agent-id
                            :branch branch
                            :turn turn-n
                            :branch-tx (:max-tx @conn)
                            :ok? true
                            :tool-results []
                            :final-ns cur-ns})
                {:stopped? false :ns cur-ns})
              (let [before-cid (d/commit-id @conn)
                    _ (d/transact conn {:tx-data [(cond-> {:seon.agent/id agent-id :seon.agent/waiting? false}
                                                    (seq seen-ids) (assoc :seon.agent/ready? false))]})
                    calls (if (seq tool-calls)
                            tool-calls
                            [{:id "tc-0" :code code}])
                    multi-res (code/eval-tool-calls
                               conn
                               {:agent-id agent-id
                                :turn turn-n
                                :seen-messages seen-ids
                                :thought thought
                                :reasoning reasoning
                                :usage usage
                                :prefix-hash prefix-hash
                                :start-ns cur-ns}
                               calls)]
                (run-fast-auto-proofs! conn agent-id branch before-cid)
                (ctx/refresh-active! @conn nil)
                (maybe-emit-branch-render! conn out agent-id branch)
                (a/>!! out {:ev :turn-committed
                            :agent-id agent-id
                            :branch branch
                            :turn turn-n
                            :branch-tx (:max-tx @conn)
                            :ok? (:ok? multi-res)
                            :tool-results (:results multi-res)
                            :final-ns (:final-ns multi-res)})
                {:stopped? false :ns (:final-ns multi-res cur-ns)})))

          :else (recur))
        {:stopped? true :ns cur-ns}))))

(defn- run-eval-cmd! [conn out agent-id branch cur-ns {:keys [req-id code tool-calls ns]}]
  (let [turn-n (inc (:max-turn (store/agent-eval-history-stats @conn agent-id)))]
    (if (seq tool-calls)
      (let [multi (code/eval-tool-calls conn
                                        {:agent-id agent-id
                                         :turn turn-n
                                         :start-ns (or ns cur-ns)}
                                        tool-calls)]
        (ctx/refresh-active! @conn nil)
        (maybe-emit-branch-render! conn out agent-id branch)
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
      (let [res (code/eval-forms @conn {:agent-id agent-id :turn turn-n :default-ns (or ns cur-ns)} code)
            r (d/transact conn {:tx-data (:tx-data res)})]
        (ctx/refresh-active! (:db-after r) (:tx-data r))
        (maybe-emit-branch-render! conn out agent-id branch)
        (a/>!! out {:ev :eval-done
                    :req-id req-id
                    :agent-id agent-id
                    :branch branch
                    :branch-tx (:max-tx @conn)
                    :ok? (:ok? res)
                    :handle (:handle res)
                    :result-schema (:result-schema res)
                    :value (:value-str res)
                    :out (:out res)
                    :error (:error res)
                    :ticks (:ticks res)
                    :bytes (:bytes res)
                    :ms (:ms res)
                    :final-ns (:final-ns res)})
        (:final-ns res cur-ns)))))

(defn- ensure-agent-entity! [conn agent-id branch]
  (when-not (first (d/datoms @conn :avet :seon.agent/id agent-id))
    (d/transact conn {:tx-data [{:seon.agent/id agent-id
                                 :seon.agent/branch branch
                                 :seon.agent/role :repl}]})))

(defn- run-agent-worker! [end {:keys [agent-id branch cfg mode initial-eval base-commit]}]
  (let [lean-cfg (store/child-cfg cfg 4)]
    (store/after-fork! lean-cfg)
    (let [in (:in end)
          out (:out end)
          conn (store/connect lean-cfg branch)
          main-cid (d/commit-id (store/head conn :main))
          _ (when (and base-commit main-cid (not= base-commit main-cid))
              (try (code/load-delta! conn base-commit main-cid true) (catch Throwable _ nil)))
          branch-cid (d/commit-id @conn)
          base-cid (when (not= branch :main)
                     (d/merge-base conn :main branch))
          loaded (if (and base-cid branch-cid (not= base-cid branch-cid))
                   (code/load-delta! conn base-cid branch-cid true)
                   {:loaded [] :unmapped [] :errors []})]
      (try
        (sync-missing-main-schema! conn branch)
        (ensure-agent-entity! conn agent-id branch)
        (let [my-ctx (ctx/ctx-atom conn [:seon.agent/id agent-id])
              rns (create-ns 'repl)]
          (install-agent-helpers!)
          (when-let [install-ref! (try (requiring-resolve 'seon.code.refactor/install-user-helpers!) (catch Throwable _ nil))]
            (install-ref! (fn [] fcalls/*agent-ctx*) ctx/refresh-active!))
          (intern (create-ns 'user) 'ctx my-ctx)
          (binding [*ns* rns]
            (refer-clojure)
            (require '[datahike.api :as d]
                     '[clojure.string :as str])
            (intern rns 'ctx my-ctx))
          (a/>!! out {:ev :agent-started
                      :agent-id agent-id
                      :branch branch
                      :pid (c-getpid)
                      :ceiling (jolt.host/collect-ceiling)
                      :mem-mb (mem-mb)
                      :loaded-delta (:loaded loaded)})
          (binding [fcalls/*agent-ctx* {:conn conn :in in :out out :agent-id agent-id :branch branch}
                    ctx/ctx my-ctx]
            (let [ns0 (cond
                        initial-eval
                        (run-eval-cmd! conn out agent-id branch 'repl initial-eval)

                        (= mode :turn)
                        (let [r (execute-turn-step! conn in out agent-id branch 'repl)]
                          (if (:stopped? r) ::stop (:ns r)))

                        :else 'repl)]
              (when-not (= ::stop ns0)
                (loop [cur-ns ns0
                       known-cid (d/commit-id @conn)
                       known-main-cid main-cid]
                  (when-let [msg (a/<!! in)]
                    (case (:op msg)
                      :stop nil

                      :eval
                      (let [{:keys [branch-cid main-cid]}
                            (sync-conn-to-latest-branch-head! conn branch known-cid known-main-cid)
                            nxt-ns (run-eval-cmd! conn out agent-id branch cur-ns msg)]
                        (recur nxt-ns (d/commit-id @conn) main-cid))

                      :run-turn
                      (let [{:keys [branch-cid main-cid]}
                            (sync-conn-to-latest-branch-head! conn branch known-cid known-main-cid)
                            r (execute-turn-step! conn in out agent-id branch cur-ns)]
                        (when-not (:stopped? r)
                          (recur (:ns r cur-ns) (d/commit-id @conn) main-cid)))

                      (recur cur-ns known-cid known-main-cid))))))))
        (catch Throwable t
          (try (a/>!! out {:ev :agent-error :agent-id agent-id :branch branch :error (or (.getMessage t) (str t))})
               (catch Throwable _)))
        (finally
          (try (d/release conn) (catch Throwable _)))))))

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

          (nil? v)
          (do (doseq [[pid {:keys [end]}] children]
                (c-kill pid 9)
                (remote/close! end))
              (System/exit 0))

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

(defn start-fork-parent! []
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

(defn- run-single-test-var [test-sym digest]
  (wsup/run-single-test-var test-sym digest))