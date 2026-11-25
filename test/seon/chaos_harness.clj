(ns seon.chaos-harness
  "Deterministic `seed = 42` lifecycle chaos harness with Zeller `ddmin` shrinking.
   Exercises real Flow procs (`:transactor`, `:reconciler`, `:agents`, `:test`) against
   an isolated LMDB store with `flow-edges` derived directly from `seon.main/flow-connections`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.agent :as agent-proc]
            [seon.code :as code]
            [seon.code.edit :as code-edit]
            [seon.code.io :as code-io]
            [seon.code.overlay :as overlay]
            [seon.fork.calls :as fork-calls]
            [seon.fork.worker-support :as worker-support]
            [seon.main :as main]
            [seon.reconciler :as reconciler]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test :as test-proc]
            [seon.transactor :as transactor]))

(def event-alphabet
  [:spawn-worker :user-chat :edit-fn :set-meta :fork-review
   :main-refactor-delete-fn :add-require-or-prune :eval-interrupted
   :main-register-schema :external-branch-move :review-compact-merge-back
   :main-merge :model-tcp-drop :model-429 :model-400-ctx
   :model-auth-err :gc-store :cold-reboot])

(def ^:private fault-responses
  {:model-tcp-drop {:error :model/network :retryable? true :thought "TCP"}
   :model-429      {:error :model/rate-limit :retryable? true :retry-after-ms 10 :thought "429"}
   :model-400-ctx  {:error :model/context-length :retryable? false :thought "400"}
   :model-auth-err {:error :model/auth :retryable? false :thought "[model/auth]"}})

(def ^:private simulated-procs #{:transactor :reconciler :agents :test})
(def ^:private simulated-targets (conj simulated-procs :model))

(def flow-edges
  "Pure Flow routing table derived directly from `seon.main/flow-connections`."
  (reduce (fn [acc [[src out-port] [dst in-port]]]
            (if (and (contains? simulated-procs src) (contains? simulated-targets dst))
              (update acc src (fnil conj []) [out-port dst in-port])
              acc))
          {}
          main/flow-connections))

(defn synthetic-sources
  "Generates isolated `seon.chaos.s<seed>.*` namespace sources for chaos trials."
  {:malli/schema [:=> [:cat :int] :map] :cost/ticks 500000}
  [seed]
  (let [abs-seed (Math/abs (long seed))
        prefix (str "seon.chaos.s" abs-seed)
        src-dir (str "src/seon/chaos/s" abs-seed)
        make-sym #(symbol (str prefix "." %))
        helper-ns (make-sym "helper")
        ui-ns (make-sym "ui")
        extra-ns (make-sym "extra")
        test-ns (make-sym "ui-test")
        schema-meta (fn [ret] (str "{:malli/schema [:=> [:cat :int] " ret "] :cost/ticks 10000}"))
        str-meta (schema-meta ":string")
        ui-header (str "(ns " ui-ns " (:require [" helper-ns " :as h]))\n")]
    {:prefix prefix :helper-ns helper-ns :ui-ns ui-ns :extra-ns extra-ns :test-ns test-ns
     :ui-path (str src-dir "/ui.clj") :extra-path (str src-dir "/extra.clj")
     :files [[(str src-dir "/helper.clj")
              (str "(ns " helper-ns ")\n(defn compute " (schema-meta ":int") " [x] (+ x 1))\n")]
             [(str src-dir "/ui.clj")
              (str ui-header
                   "(defn- render-nav-bar [x] (str \"n\" x))\n"
                   "(defn page " str-meta " [x] (str (render-nav-bar x) (h/compute x)))\n")]
             [(str "test/seon/chaos/s" abs-seed "/ui_test.clj")
              (str "(ns " test-ns " (:require [clojure.test :refer [deftest is]] [" ui-ns " :as ui]))\n"
                   "(deftest page-test (is (string? (ui/page 1))))\n")]]
     :extra-src (str "(ns " extra-ns ")\n(defn badge " str-meta " [x] (str \"b:\" x))\n")
     :ui-refactored-src (str ui-header "(defn page " str-meta " [x] (str \"i\" x (h/compute x)))\n")}))

(defn route-outs
  "Pure Flow message router derived from `seon.main/flow-connections`."
  {:malli/schema [:=> [:cat :keyword [:maybe :map]] [:map [:msgs [:vector :any]] [:errors [:vector :any]]]]
   :cost/ticks 500000}
  [src out-map]
  {:errors (vec (get out-map ::flow/error))
   :msgs (vec (for [[out-port dst in-port] (flow-edges src)
                    msg (get out-map out-port)]
                [dst in-port msg]))})

(defn- branch-for-agent [agent-id] (keyword "branch" (name agent-id)))
(defn- has-datom? [db attr val] (some? (first (d/datoms db :avet attr val))))
(defn- pull-attr [db attr lookup-ref] (get (d/pull db [attr] lookup-ref) attr))
(defn- enqueue-msg [world [dst in-port msg]] (update-in world [:queue [dst in-port]] (fnil conj []) msg))
(defn- enqueue-tx [world tx-msg] (enqueue-msg world [:transactor :tx tx-msg]))
(defn- ignore-errors! [thunk] (try (thunk) (catch Throwable _ nil)))

(defn- with-branch-conn [cfg branch f]
  (let [branch-conn (store/connect cfg branch)]
    (try (f branch-conn) (finally (d/release branch-conn)))))

(defn- transact-branch! [cfg branch tx-data]
  (with-branch-conn cfg branch #(d/transact % {:tx-data tx-data})))

(defn- with-agent-ctx [conn branch agent-id thunk]
  (binding [fork-calls/*agent-ctx* {:conn conn :branch branch :agent-id agent-id}]
    (thunk)))

(defn- worker-child [world worker-id]
  (some #(when (= worker-id (:agent-id %)) %) (vals (:children world))))

(defn- restore-image! [conn {:keys [helper-ns ui-ns test-ns]} deleted-keys reload?]
  (doseq [form-key deleted-keys :when (re-find #"^[^/]+/[^#]+$" (str form-key))]
    (let [var-sym (symbol form-key)
          ns-sym (symbol (namespace var-sym))]
      (when (find-ns ns-sym)
        (ns-unmap ns-sym (symbol (name var-sym))))))
  (when reload?
    (let [db @conn
          forms (vec (mapcat #(code/ns-forms db %) [helper-ns ui-ns test-ns]))]
      (when (seq forms)
        (code/apply-overlay! db {:upserts (code/sort-forms-topologically forms) :retracted-keys #{}} true)))))

(defn- boot-chaos-procs! [world dir]
  (let [cfg (assoc (store/store-cfg dir {:cache-bytes 4194304}) :branch :main)
        _ (when-not (:conn world)
            (let [init-cfg (dissoc cfg :branch)
                  init-conn (do (d/create-database init-cfg) (d/connect init-cfg))]
              (try
                (d/transact init-conn {:tx-data (:declarations @schema/base-registry)})
                (d/branch! init-conn :db :main)
                (finally (d/release init-conn)))))
        conn (store/connect cfg :main)
        fork-out (a/chan 256)
        base-args {:conn conn :cfg cfg :fork-out fork-out}]
    (when-not (:conn world)
      (d/transact conn
                  {:tx-data (into [(assoc store/default-config-values :seon.config/key :seon.config/root)]
                                  (mapcat (fn [[path src]] (code/file-import-tx @conn path src false))
                                          (get-in world [:synthetic-ns :files])))}))
    (assoc world :conn conn :cfg cfg :fork-out fork-out :children {} :prefix-hashes {}
           :queue {[:transactor :boot] [{:op :boot-init}]}
           :proc-states {:transactor (transactor/transactor-proc (assoc base-args :commit-git? false))
                         :reconciler (reconciler/reconciler-proc {:conn conn})
                         :agents (agent-proc/agents-proc (assoc base-args :fork-agent-in (a/chan 64) :max-os-procs 4))
                         :test (test-proc/test-proc (assoc base-args :fork-test-in (a/chan 64) :max-workers 2))})))

(defn- handle-fork-test! [world {:keys [batch-id overlay test-items gen-items cfg branch]}]
  (let [pid (:next-pid world)
        branch-conn (when (and cfg branch) (store/connect cfg branch))
        test-event #(vector :test :fork-ev (assoc % :type :test :batch-id batch-id :pid pid))
        enqueue-events #(reduce enqueue-msg (assoc world :next-pid (inc pid)) %)]
    (try
      (let [db (when branch-conn @branch-conn)
            _ (when overlay (worker-support/apply-form-overlay! db overlay))
            [results gen-failures]
            (worker-support/call-with-candidate-overrides
             db
             (fn [_]
               (let [test-res (mapv #(worker-support/run-single-test-var db (:var %) (:digest %)) test-items)
                     gen-res (vec (keep (fn [{:keys [var digest]}]
                                          (when-let [res (schema/stress-var db var {:samples 5 :seed 42})]
                                            #:seon.test{:digest digest :var var :kind :generative
                                                        :pass? (true? (:ok? res)) :ms 0.0 :ticks 0 :bytes 0
                                                        :report (str (or (:reason res) "OK"))}))
                                        gen-items))]
                 [(into test-res gen-res) (vec (remove :seon.test/pass? gen-res))])))]
        (enqueue-events
         [(test-event {:ev :child-frame
                       :frame {:ev :test-batch-done :batch-id batch-id :worker-pid pid
                               :results results :generative-failures gen-failures}})
          (test-event {:ev :child-exited :exit-code 0})]))
      (catch Throwable ex
        (enqueue-events
         [(test-event {:ev :child-frame :frame {:ev :child-exception :batch-id batch-id :error (str ex)}})]))
      (finally
        (when branch-conn (d/release branch-conn))
        (restore-image! (:conn world) (:synthetic-ns world) (:deleted-keys world) true)))))

(defn- agent-fork-ev [agent-id pid payload]
  [:agents :fork-ev (assoc payload :type :agent :agent-id agent-id :pid pid)])

(defn- emit-model-request [world pid {:keys [agent-id branch conn]}]
  (let [unread (worker-support/unread-messages-for @(:conn world) @conn agent-id)
        turn (inc (:max-turn (store/agent-eval-history-stats @conn agent-id)))]
    (enqueue-msg
     (assoc-in world [:children pid :pending] [turn unread])
     (agent-fork-ev agent-id pid
                    {:ev :child-frame
                     :frame {:ev :model-request :req-id (str (name agent-id) "-" turn "-" pid)
                             :agent-id agent-id :branch branch :turn turn :unread unread}}))))

(defn- handle-child-msg! [world pid msg]
  (if-let [{:keys [conn agent-id branch pending branch-cid main-cid] :as child}
           (get-in world [:children pid])]
    (case (:op msg)
      :stop
      (do (ignore-errors! #(d/release conn))
          (enqueue-msg (update world :children dissoc pid)
                       (agent-fork-ev agent-id pid {:ev :child-exited :exit-code 0})))

      :run-turn
      (let [synced (worker-support/sync-conn-to-latest-branch-head! conn branch branch-cid main-cid)
            child' (assoc child :branch-cid (:branch-cid synced) :main-cid (:main-cid synced))]
        (emit-model-request (assoc-in world [:children pid] child') pid child'))

      :model-response
      (let [[turn unread] pending
            resp (:response msg)
            err-str (when-let [ek (or (:seon.error/kind resp) (:error resp))]
                      (or (not-empty (:thought resp)) (name ek)))
            seen-ids (when-not err-str (not-empty (set (map :seon.agent.message/id unread))))]
        (d/transact conn
                    {:tx-data [(cond-> #:seon.agent.eval{:id (random-uuid) :agent agent-id :turn turn
                                                         :code "" :thought (if err-str "" (:thought resp ""))}
                                 err-str (assoc :seon.agent.eval/error err-str)
                                 seen-ids (assoc :seon.agent.eval/seen-messages seen-ids)
                                 (:prefix-hash msg) (assoc :seon.agent.eval/prefix-hash (:prefix-hash msg)))
                               (cond-> #:seon.agent{:id agent-id
                                                    :waiting? (or (nil? err-str) (false? (:retryable? resp)))}
                                 seen-ids (assoc :seon.agent/ready? false))]})
        (enqueue-msg (assoc-in world [:children pid :branch-cid] (d/commit-id @conn))
                     (agent-fork-ev agent-id pid
                                    {:ev :child-frame
                                     :frame {:ev :turn-committed :agent-id agent-id :branch branch
                                             :turn turn :branch-tx (:max-tx @conn) :ok? (nil? err-str)}})))
      world)
    world))

(defn- handle-fork-cmd! [world {:keys [op pid msg agent-id branch cfg retracted-keys] :as cmd}]
  (case op
    :advance-image
    (do (restore-image! (:conn world) (:synthetic-ns world) retracted-keys false) world)

    :fork-agent
    (let [pid (:next-pid world)
          branch-conn (store/connect cfg branch)
          _ (when-not (has-datom? @branch-conn :seon.agent/id agent-id)
              (d/transact branch-conn {:tx-data [#:seon.agent{:id agent-id :branch branch}]}))
          child {:agent-id agent-id :branch branch :conn branch-conn
                 :branch-cid (d/commit-id @branch-conn) :main-cid (d/commit-id @(:conn world))}]
      (emit-model-request
       (enqueue-msg (assoc-in (assoc world :next-pid (inc pid)) [:children pid] child)
                    (agent-fork-ev agent-id pid {:ev :forked}))
       pid child))

    :to-child (handle-child-msg! world pid msg)
    :fork-test (handle-fork-test! world cmd)
    world))

(defn- drain-flow! [world max-ticks]
  (let [proc-fns {:transactor transactor/transactor-proc :reconciler reconciler/reconciler-proc
                  :agents agent-proc/agents-proc :test test-proc/test-proc}]
    (loop [current (assoc world :quiesced? true) tick 0]
      (if (> tick max-ticks)
        (assoc current :quiesced? false)
        (if-let [fork-cmd (a/poll! (:fork-out current))]
          (recur (handle-fork-cmd! current fork-cmd) (inc tick))
          (if (seq (:queue current))
            (let [queue-keys (vec (sort (keys (:queue current))))
                  chosen-key (nth queue-keys (.nextInt ^java.util.Random (:rng current) (count queue-keys)))
                  [dst in-port] chosen-key
                  pending (get-in current [:queue chosen-key])
                  msg (first pending)
                  dequeued (if (= 1 (count pending))
                             (update current :queue dissoc chosen-key)
                             (assoc-in current [:queue chosen-key] (subvec pending 1)))]
              (recur
               (if (= dst :model)
                 (let [fault-key (first (:fault-queue dequeued))
                       next-w (if fault-key (update dequeued :fault-queue subvec 1) dequeued)
                       response (get fault-responses fault-key {:thought (str "ok" (:turn msg))})]
                   (enqueue-msg next-w [:agents :response {:agent-id (:agent-id msg) :req-id (:req-id msg) :response response}]))
                 (let [[next-state outs] ((proc-fns dst) (get-in dequeued [:proc-states dst]) in-port msg)
                       {:keys [msgs errors]} (route-outs dst outs)
                       updated (reduce enqueue-msg (-> dequeued (assoc-in [:proc-states dst] next-state) (update :flow-errors into errors)) msgs)]
                   (if-let [prefix-hash (and (= dst :agents) (= in-port :fork-ev)
                                             (= :model-request (get-in msg [:frame :ev]))
                                             (get-in next-state [:model-reqs (get-in msg [:frame :req-id]) :prefix-hash]))]
                     (update-in updated [:prefix-hashes (:agent-id msg)] (fnil conj []) prefix-hash)
                     updated)))
               (inc tick)))
            current))))))

(defn- check-worker-branch-invariants! [world event record-violation!]
  (when-let [worker-id (and (seq (:deleted-keys world))
                            (#{:main-refactor-delete-fn :add-require-or-prune :edit-fn
                               :set-meta :external-branch-move :review-compact-merge-back} event)
                            (last (:workers world)))]
    (let [{:keys [conn cfg synthetic-ns]} world
          worker-branch (branch-for-agent worker-id)
          verify-branch
          (fn [branch-conn]
            (record-violation!
             (not (str/includes? (str (code-io/merged-ns-src branch-conn worker-branch (:ui-ns synthetic-ns)))
                                 "render-nav-bar"))
             :inv-4 worker-branch)
            (let [head-cid (d/commit-id @branch-conn)
                  base-cid (d/merge-base branch-conn :main worker-branch)]
              (when (and base-cid (not= base-cid head-cid))
                (record-violation! (<= (count (d/changes branch-conn base-cid head-cid)) 500) :inv-4 worker-branch))
              (when (= event :add-require-or-prune)
                (try
                  (record-violation! (empty? (:errors (overlay/load-delta! branch-conn base-cid head-cid false)))
                                     :inv-5 worker-branch)
                  (finally (restore-image! conn synthetic-ns (:deleted-keys world) true))))))]
      (if-let [active-conn (:conn (worker-child world worker-id))]
        (verify-branch active-conn)
        (with-branch-conn cfg worker-branch verify-branch)))))

(defn- check-invariants! [world event]
  (let [{:keys [conn synthetic-ns]} world
        step-idx (:step world 0)
        violations (transient [])
        record-violation! (fn [ok? inv-id detail]
                            (when-not ok? (conj! violations {:inv inv-id :step step-idx :event event :detail detail})))]
    (record-violation! (:quiesced? world) :inv-1 :quiesce-timeout)
    (doseq [[conn-key active-conn] (assoc (update-vals (:children world) :conn) :main conn)]
      (record-violation!
       (and (some? (:writer @(:wrapped-atom active-conn)))
            (>= (:max-tx @active-conn) (get-in world [:last-tx-by-conn conn-key] 0)))
       :inv-2 conn-key))
    (when (#{:eval-interrupted :external-branch-move :gc-store :cold-reboot} event)
      (try (d/transact conn {:tx-data [#:seon.config{:key :seon.config/root :eval-ticks 100000000}]})
           (catch Throwable ex (record-violation! false :inv-2 (.getMessage ex)))))
    (doseq [[aid hashes] (:prefix-hashes world)]
      (record-violation! (<= (count (distinct hashes)) 1) :inv-3 [aid hashes]))
    (check-worker-branch-invariants! world event record-violation!)
    (let [test-state (get-in world [:proc-states :test])]
      (record-violation!
       (and (empty? (:active-subs test-state)) (empty? (:parents test-state))
            (empty? (:flow-errors world)) (zero? (store/active-lmdb-write-txns)))
       :inv-6 (:flow-errors world)))
    (when (#{:gc-store :cold-reboot} event)
      (let [branches (d/branches conn) main-db @conn]
        (record-violation!
         (and (every? #(contains? branches (branch-for-agent %)) (:workers world))
              (has-datom? main-db :seon.code.form/key (str (:ui-ns synthetic-ns) "/page"))
              (every? #(has-datom? main-db :seon.schema/key %) (:registered-schemas world)))
         :inv-8 event)))
    (when-let [[reviewer-id target-id target-branch zero-diff-result edited-test-result]
               (when (= event :review-compact-merge-back) (:last-review world))]
      (let [main-db @conn
            target-db (store/head conn target-branch)
            extra-key (str (:extra-ns synthetic-ns) "/badge")]
        (record-violation! (:ok? edited-test-result) :inv-5 edited-test-result)
        (record-violation!
         (and (:ok? zero-diff-result)
              (zero? (long (or (:ran zero-diff-result) 0)))
              (not (pull-attr main-db :seon.agent/paused? [:seon.agent/id target-id]))
              (str/includes? (str (pull-attr target-db :seon.code.form/src [:seon.code.form/key extra-key])) "\"rev:\"")
              (not (has-datom? main-db :seon.code.form/key extra-key))
              (not (has-datom? target-db :seon.agent/id reviewer-id)))
         :inv-9 reviewer-id)))
    (persistent! violations)))

(defn- exec-worker-event! [world event]
  (if-let [worker-id (last (:workers world))]
    (let [{:keys [conn cfg synthetic-ns]} world
          worker-branch (branch-for-agent worker-id)
          helper-fn (symbol (name (:helper-ns synthetic-ns)) "compute")
          step-idx (:step world 0)
          child (worker-child world worker-id)
          branch-conn (or (:conn child) (store/connect cfg worker-branch))
          no-eval {:eval? false}]
      (try
        (case event
          :edit-fn
          (let [next-edit (inc (:edit-count world 0))
                src (str (pull-attr @branch-conn :seon.code.form/src [:seon.code.form/key (str helper-fn)]))
                expr-match (re-find #"\(\+ x \d+\)" src)]
            (when expr-match
              (with-agent-ctx branch-conn worker-branch worker-id
                #(code-edit/edit-fn! helper-fn expr-match (str "(+ x " next-edit ")") no-eval)))
            (assoc world :edit-count next-edit))

          :set-meta
          (do (with-agent-ctx branch-conn worker-branch worker-id
                #(code-edit/set-meta! helper-fn {:cost/ticks (+ 10000 step-idx)} no-eval))
              world)

          :add-require-or-prune
          (do (with-agent-ctx branch-conn worker-branch worker-id
                #(do (code-edit/add-require! (:ui-ns synthetic-ns) (quote [clojure.set :as cset]) no-eval)
                     (code-edit/prune-requires! (:ui-ns synthetic-ns))))
              world)

          :eval-interrupted
          (do (transact-branch! cfg worker-branch [#:seon.agent{:id worker-id :summary (str "e" step-idx)}])
              (code/eval-tool-calls branch-conn
                                    {:agent-id worker-id :turn (+ 50 step-idx) :start-ns (:helper-ns synthetic-ns)}
                                    [{:id "i1" :code "(throw (ex-info \"int\" {:jolt/interrupted :ticks}))"}])
              (update world :prefix-hashes dissoc worker-id))

          :external-branch-move
          (let [prev-branch-cid (d/commit-id @branch-conn) prev-main-cid (d/commit-id @conn)]
            (transact-branch! cfg worker-branch [#:seon.agent{:id worker-id :summary (str "m" step-idx)}])
            (worker-support/sync-conn-to-latest-branch-head! branch-conn worker-branch prev-branch-cid prev-main-cid)
            (update world :prefix-hashes dissoc worker-id))

          world)
        (finally (when-not (:conn child) (d/release branch-conn)))))
    world))

(defn- close-world-store! [world]
  (doseq [[_ child] (:children world) :when (:conn child)]
    (ignore-errors! #(d/release (:conn child))))
  (when (:conn world)
    (ignore-errors! #(store/close! {:cfg (:cfg world) :conn (:conn world)}))))

(defn- run-test-batch! [world branch agent-id]
  (let [reply-ch (a/chan 1)
        branch-tx (:max-tx (store/head (:conn world) branch))
        next-world (drain-flow!
                    (enqueue-msg world [:test :batch {:branch branch :agent-id agent-id
                                                      :branch-tx branch-tx :reply-ch reply-ch}])
                    60)]
    [next-world (a/poll! reply-ch)]))

(defn- exec-review-event! [world event]
  (let [{:keys [conn cfg synthetic-ns]} world
        worker-id (last (:workers world))]
    (case event
      :fork-review
      (if worker-id
        (let [worker-branch (branch-for-agent worker-id)
              rev-num (inc (:review-count world 0))
              reviewer-id (keyword "agent" (str "cr" rev-num))
              review-branch (branch-for-agent reviewer-id)
              target-ref [:seon.agent/id worker-id]]
          (with-branch-conn cfg worker-branch
            #(when-let [tx (seq (code/file-import-tx @% (:extra-path synthetic-ns) (:extra-src synthetic-ns) false))]
               (d/transact % {:tx-data tx})))
          (store/branch! conn worker-branch review-branch)
          (transact-branch! cfg review-branch [#:seon.agent{:id reviewer-id :branch review-branch :summary (str "r" rev-num)}])
          (enqueue-tx (assoc world :review-count rev-num
                             :active-review {:id reviewer-id :branch review-branch :target worker-id :target-branch worker-branch})
                      {:op :transact
                       :tx-data [#:seon.agent{:id worker-id :paused? true}
                                 #:seon.agent{:id reviewer-id :branch review-branch :forked-from target-ref :target target-ref :goal "r"}]}))
        world)

      :review-compact-merge-back
      (if-let [{:keys [id branch target target-branch]} (:active-review world)]
        (let [[w0 zero-diff-result] (run-test-batch! world branch id)
              _ (with-branch-conn cfg branch
                  #(with-agent-ctx % branch id
                     (fn []
                       (code-edit/edit-fn! (symbol (name (:extra-ns synthetic-ns)) "badge")
                                           "\"b:\"" "\"rev:\"" {:eval? false}))))
              [w1 edited-test-result] (run-test-batch! w0 branch id)
              branch-tx (:max-tx (store/head conn branch))
              w2 (drain-flow! (enqueue-tx w1 {:op :merge :branch branch :agent-id id :branch-tx branch-tx}) 60)]
          (-> w2
              (assoc :last-review [id target target-branch zero-diff-result edited-test-result] :active-review nil)
              (update :prefix-hashes dissoc target)))
        world))))

(defn- exec-chaos-event! [world event]
  (let [{:keys [conn cfg synthetic-ns]} world
        worker-id (last (:workers world))
        step-idx (:step world 0)
        send-chat #(enqueue-tx %1 {:op :send-message :from :user/default :to worker-id :type :chat :body %2})
        after-event
        (if (fault-responses event)
          (if worker-id (send-chat (update world :fault-queue conj event) (name event)) world)
          (case event
            :spawn-worker
            (let [agent-id (keyword "agent" (str "cw" (inc (count (:workers world)))))]
              (enqueue-tx (update world :workers conj agent-id)
                          {:op :spawn-agent :id agent-id :branch (branch-for-agent agent-id) :goal "g"}))

            :user-chat (if worker-id (send-chat world (str "p" step-idx)) world)

            :main-merge
            (let [merge-branch (keyword "branch" (str "cm-" step-idx))]
              (store/branch! conn :main merge-branch)
              (transact-branch! cfg merge-branch [#:seon.task{:id (keyword "task" (str "c" step-idx)) :title "c" :done? true}])
              (enqueue-tx world {:op :merge :branch merge-branch}))

            :main-refactor-delete-fn
            (enqueue-tx (update world :deleted-keys conj (str (:ui-ns synthetic-ns) "/render-nav-bar"))
                        {:op :transact :tx-data (code/file-import-tx @conn (:ui-path synthetic-ns) (:ui-refactored-src synthetic-ns) false)})

            :main-register-schema
            (let [schema-key (keyword "seon.chaos" (str "t" (inc (:schema-count world 0))))]
              (enqueue-tx (-> world (update :schema-count (fnil inc 0)) (update :registered-schemas conj schema-key))
                          {:op :register-schema :key schema-key :form :string :doc "t"
                           :db {:valueType :db.type/string :cardinality :db.cardinality/one}}))

            (:fork-review :review-compact-merge-back) (exec-review-event! world event)

            :gc-store
            (let [keep-branches (cond-> (into #{:main} (map branch-for-agent (:workers world)))
                                  (:active-review world) (conj (:branch (:active-review world))))]
              (enqueue-tx world {:op :gc-store :keep-branches keep-branches}))

            :cold-reboot (do (close-world-store! world) (boot-chaos-procs! world (:dir world)))

            (exec-worker-event! world event)))
        drained (drain-flow! after-event 120)
        after-hook (if-let [hook (:fault-hook drained)] (or (hook drained event) drained) drained)]
    (-> after-hook (update :step (fnil inc 0)) (update :events-seen conj event))))

(defn ^:seon.flow/edge cleanup-chaos!
  "Closes store/connections, deletes temporary dir, and removes synthetic chaos namespaces."
  {:malli/schema [:=> [:cat :any] :nil]}
  [world]
  (close-world-store! world)
  (when-let [dir (:dir world)] (ignore-errors! #(store/rm-rf! (io/file dir))))
  (when-let [prefix (get-in world [:synthetic-ns :prefix])]
    (doseq [ns-obj (all-ns) :when (str/starts-with? (name (ns-name ns-obj)) prefix)]
      (ignore-errors! #(remove-ns (ns-name ns-obj)))))
  nil)

(defn ^:seon.flow/edge replay!
  "Replays an exact chaos event sequence on an isolated temporary LMDB store."
  {:malli/schema [:=> [:cat :map] :map]
   :seon.eval/budget-override {:ticks 300000000 :bytes 671088640}}
  [{:keys [seed steps fault-hook] :or {seed 42}}]
  (let [started-ns (System/nanoTime)
        started-bytes (code/alloc-bytes)
        step-vec (vec steps)
        dir (str "/tmp/seon-chaos-" seed "-" started-ns)
        initial-world {:seed seed :dir dir :synthetic-ns (synthetic-sources seed)
                       :rng (java.util.Random. (long seed)) :next-pid 100
                       :workers [] :deleted-keys #{} :registered-schemas #{}
                       :flow-errors [] :fault-queue [] :events-seen #{}
                       :last-tx-by-conn {} :fault-hook fault-hook}
        current-world (volatile! (drain-flow! (boot-chaos-procs! initial-world dir) 80))
        make-report (fn [ok? world ran violations]
                      (array-map :ok? ok? :seed seed :dir dir :steps step-vec :ran ran
                                 :events-covered (:events-seen world) :violations violations
                                 :ms (/ (- (System/nanoTime) started-ns) 1e6)
                                 :bytes (max 0 (- (code/alloc-bytes) started-bytes))))]
    (try
      (loop [remaining step-vec ran 0]
        (let [world @current-world]
          (if (empty? remaining)
            (make-report true world ran [])
            (let [event (first remaining)
                  next-world (exec-chaos-event! world event)
                  _ (vreset! current-world next-world)
                  violations (check-invariants! next-world event)
                  tx-snapshot (assoc (update-vals (:children next-world) #(:max-tx @(:conn %)))
                                     :main (:max-tx @(:conn next-world)))]
              (if (seq violations)
                (make-report false next-world (inc ran) violations)
                (do (vreset! current-world (assoc next-world :last-tx-by-conn tx-snapshot))
                    (recur (subvec remaining 1) (inc ran))))))))
      (finally (cleanup-chaos! @current-world)))))
