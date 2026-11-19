(ns seon.fork
  "Single-threaded System Fork Parent + COW Agent & clojure.test Worker processes:
   - Zero atoms in parent loop (pure recur state over `jolt.async.remote` + `jolt.io-poller/exit-chan`).
   - COW GC ceiling (`gc-generations! 6` + `(System/gc)` promotion before fork & after `:advance-image`).
   - Persistent Agent Worker loop: keeps the forked OS process alive across multi-turn work AND REPL
     `eval`s, supporting multi-tool calls per turn and dynamic runtime Malli->Datahike schema registration!"
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [jolt.io-poller :as poller]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
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

(defn apply-form-overlay!
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
    {:seon.test/digest digest
     :seon.test/var test-sym
     :seon.test/kind :deftest
     :seon.test/pass? pass?
     :seon.test/ms (double ms)
     :seon.test/ticks (long ticks)
     :seon.test/bytes (long bytes)
     :seon.test/report (if pass?
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

;; --- Persistent Forked Child 2: Unified Agent Runtime ---

(def ^:dynamic *agent-ctx* nil)

(defn- tx-main-sync!
  "Sends a request to `:transactor` on `:main` via `:agents` without blocking any Flow proc,
   returning the exact transaction reply map (e.g. `:call-id`, cycle rejection, or schema error)."
  [req]
  (when-let [{:keys [in out]} *agent-ctx*]
    (let [rid (str "tx-" (random-uuid))]
      (a/>!! out (assoc req :ev :tx-main-sync :req-id rid))
      (loop []
        (if-let [m (a/<!! in)]
          (cond
            (and (= (:op m) :tx-reply) (= (:req-id m) rid)) (:reply m)
            (= (:op m) :stop) {:ok? false :error :stopped}
            :else (recur))
          {:ok? false :error :eof})))))

(defn- coerce-call-id [x]
  (cond
    (uuid? x) x
    (and (map? x) (uuid? (:call-id x))) (:call-id x)
    (and (map? x) (uuid? (:seon.agent.call/id x))) (:seon.agent.call/id x)
    (string? x) (try (java.util.UUID/fromString (str/trim x)) (catch Throwable _ nil))
    :else nil))

(defn- active-incoming-call-id [conn agent-id]
  (try
    (let [mdb (store/head conn :main)]
      (when (contains? (:schema mdb) :seon.agent.call/callee)
        (->> (d/datoms mdb :avet :seon.agent.call/callee agent-id)
             (map #(d/pull mdb '[:seon.agent.call/id :seon.agent.call/status :seon.agent.call/created-tx] (:e %)))
             (filter #(= :pending (:seon.agent.call/status %)))
             (sort-by :seon.agent.call/created-tx)
             last
             :seon.agent.call/id)))
    (catch Throwable _ nil)))

(defn install-agent-helpers! []
  (let [u (create-ns 'user)]
    (binding [*ns* u]
      (refer-clojure)
      (require '[datahike.api :as d]
               '[clojure.string :as str])
      (intern u 'conn-of
              (fn [] (:conn *agent-ctx*)))
      (intern u 'db
              (fn [] (when-let [c (:conn *agent-ctx*)] @c)))
      (intern u 'source
              (fn [sym]
                (when-let [{:keys [conn branch]} *agent-ctx*]
                  (let [bdb @conn
                        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                        base-db (when (not= branch :main)
                                  (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)))
                        s (if (string? sym) (symbol sym) sym)
                        src-in (fn [db]
                                 (when-let [fkey (:seon.code.var/form (d/pull db '[:seon.code.var/form] [:seon.code.var/name s]))]
                                   (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key fkey]))))]
                    (if (namespace s)
                      (let [b-src (src-in bdb)
                            base-src (when base-db (src-in base-db))
                            m-src (src-in mdb)]
                        ;; Prefer branch edit when the branch actually modified this var; else show latest `:main`!
                        (if (and b-src (not= b-src base-src)) b-src (or m-src b-src)))
                      (let [ns-forms (fn [db]
                                       (->> (d/datoms db :avet :seon.code.form/ns s)
                                            (map #(d/pull db '[:seon.code.form/key :seon.code.form/order :seon.code.form/src] (:e %)))
                                            (sort-by #(or (:seon.code.form/order %) 0))))
                            m-fs (ns-forms mdb)
                            b-by-k (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms bdb))
                            base-by-k (if base-db
                                        (into {} (map (juxt :seon.code.form/key :seon.code.form/src)) (ns-forms base-db))
                                        {})
                            merged (mapv (fn [{:keys [:seon.code.form/key :seon.code.form/src]}]
                                           (let [bs (get b-by-k key)]
                                             (if (and bs (not= bs (get base-by-k key))) bs src)))
                                         (if (seq m-fs) m-fs (ns-forms bdb)))]
                        (str/join "\n\n" merged)))))))
      (intern u 'transact!
              (fn [tx-data]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [r (d/transact conn {:tx-data tx-data})]
                    (ctx/refresh-active! (:db-after r) (:tx-data r))
                    {:tx (:max-tx (:db-after r))}))))
      (intern u 'task
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (let [ag (d/pull @conn '[:seon.agent/task :seon.agent/goal :seon.agent/scope]
                                   [:seon.agent/id agent-id])]
                    (if-let [tid (:seon.agent/task ag)]
                      (d/pull @conn '[*] [:seon.task/id tid])
                      ag)))))
      (intern u 'health
              (fn []
                (when-let [{:keys [conn]} *agent-ctx*]
                  (health/report @conn))))
      (intern u 'config
              (fn
                ([] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                      (:effective (store/config-map (store/head conn :main) agent-id))))
                ([k] (when-let [{:keys [conn agent-id]} *agent-ctx*]
                       (store/config-val (store/head conn :main) agent-id k)))))
      (intern u 'register-schema!
              (fn [key form & [{:keys [doc examples render]}]]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [{:keys [sample entity dh-decls]}
                        (schema/validate-schema-registration!
                         @conn {:key key :form form :doc doc :examples examples :render render})]
                    (when (seq dh-decls)
                      (d/transact conn {:tx-data dh-decls}))
                    (let [r (d/transact conn {:tx-data [entity]})]
                      (ctx/refresh-active! (:db-after r) (:tx-data r)))
                    {:registered key :sample sample :derived-attributes (mapv :db/ident dh-decls)}))))
      (intern u 'search
              (fn [substr]
                (when-let [{:keys [conn branch]} *agent-ctx*]
                  (let [bdb @conn
                        mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                        q (str/lower-case (str substr))
                        find-vals (fn [attr lim]
                                    (->> (concat (d/datoms mdb :aevt attr)
                                                 (when (not= bdb mdb) (d/datoms bdb :aevt attr)))
                                         (keep #(when (str/includes? (str/lower-case (str (:v %))) q) (:v %)))
                                         distinct sort (take lim) vec))]
                    {:vars (find-vals :seon.code.var/name 25)
                     :schemas (find-vals :seon.schema/key 20)
                     :docs (find-vals :seon.code.doc/id 20)}))))
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
              (fn [{:keys [id branch scope role goal task]}]
                (when-let [{:keys [out agent-id]} *agent-ctx*]
                  (let [b (or branch (keyword "branch" (str (namespace id) "." (name id))))]
                    (a/>!! out {:ev :tx-main
                                :op :spawn-agent
                                :parent agent-id
                                :id id
                                :branch b
                                :scope scope
                                :role (or role :worker)
                                :goal (or goal "")
                                :task task})
                    {:spawned id :branch b :scope scope}))))
      (intern u 'pin!
              (fn [item]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [cur (vec (or (:seon.agent/pinned @ctx/ctx) []))
                        ref-val (cond
                                  (and (map? item) (:seon.code.var/name item)) [:seon.code.var/name (:seon.code.var/name item)]
                                  (and (map? item) (:seon.code.ns/name item))  [:seon.code.ns/name (:seon.code.ns/name item)]
                                  (and (map? item) (:seon.code.doc/id item))   [:seon.code.doc/id (:seon.code.doc/id item)]
                                  (and (map? item) (:seon.schema/key item))    [:seon.schema/key (:seon.schema/key item)]
                                  (and (map? item) (:seon.task/id item))       [:seon.task/id (:seon.task/id item)]
                                  (and (map? item) (:seon.agent/id item))      [:seon.agent/id (:seon.agent/id item)]
                                  :else item)
                        nxt (if (some #(= % ref-val) cur) cur (conj cur ref-val))]
                    (swap! ctx/ctx assoc :seon.agent/pinned nxt)
                    {:pinned ref-val :count (count nxt)}))))
      (intern u 'unpin!
              (fn [item]
                (when-let [{:keys [conn]} *agent-ctx*]
                  (let [cur (vec (or (:seon.agent/pinned @ctx/ctx) []))
                        nxt (vec (remove #(= % item) cur))]
                    (swap! ctx/ctx assoc :seon.agent/pinned nxt)
                    {:unpinned item :count (count nxt)}))))
      (intern u 'canvas!
              (fn
                ([m-or-hiccup]
                 (when *agent-ctx*
                   (let [m (cond
                             (map? m-or-hiccup) (select-keys m-or-hiccup [:title :summary :body])
                             (vector? m-or-hiccup) {:body m-or-hiccup}
                             (string? m-or-hiccup) {:summary m-or-hiccup}
                             :else {:body m-or-hiccup})]
                     (swap! ctx/ctx assoc :seon.agent/canvas m)
                     {:canvas-updated true :keys (vec (keys m))})))
                ([title body]
                 ((resolve 'user/canvas!) {:title (str title) :body body}))
                ([title summary body]
                 ((resolve 'user/canvas!) {:title (str title) :summary (str summary) :body body}))))
      (intern u 'present!
              (fn
                ([k hiccup]
                 (let [title (if (keyword? k) (name k) (str k))]
                   ((resolve 'user/canvas!) {:title title :body hiccup})))
                ([_k title hiccup]
                 ((resolve 'user/canvas!) {:title (str title) :body hiccup}))))
      (intern u 'unpresent!
              (fn [_k]
                (when *agent-ctx*
                  (swap! ctx/ctx assoc :seon.agent/canvas {})
                  {:cleared true})))
      (intern u 'clear-stage!
              (fn []
                (when *agent-ctx*
                  (swap! ctx/ctx assoc :seon.agent/canvas {} :seon.agent/stage [])
                  {:cleared true})))
      (intern u 'set-render!
              (fn [sym]
                (when *agent-ctx*
                  (swap! ctx/ctx assoc :seon.agent/render-fn sym)
                  {:render-fn sym})))
      (intern u 'merge-self!
              (fn []
                ((resolve 'user/ready!))))
      (intern u 'ask!
              (fn
                ([callee prompt]
                 ((resolve 'user/ask!) callee prompt {}))
                ([callee prompt opts]
                 (when-let [{:keys [conn agent-id]} *agent-ctx*]
                   (let [parent-cid (or (:parent-call opts) (active-incoming-call-id conn agent-id))]
                     (tx-main-sync! (cond-> (merge {:op :create-call
                                                    :caller agent-id
                                                    :callee callee
                                                    :prompt (str prompt)}
                                                   opts)
                                      parent-cid (assoc :parent-call parent-cid))))))))
      (intern u 'await!
              (fn [call-or-calls]
                (when-let [{:keys [agent-id]} *agent-ctx*]
                  (let [raw (if (or (vector? call-or-calls) (set? call-or-calls) (seq? call-or-calls))
                              call-or-calls
                              [call-or-calls])
                        ids (vec (keep coerce-call-id raw))]
                    (tx-main-sync! {:op :await-calls :caller agent-id :call-ids ids})))))
      (intern u 'call!
              (fn
                ([callee prompt]
                 ((resolve 'user/call!) callee prompt {}))
                ([callee prompt opts]
                 ((resolve 'user/ask!) callee prompt (assoc opts :await? true)))))
      (intern u 'answer!
              (fn [call-id value]
                (when-let [{:keys [agent-id]} *agent-ctx*]
                  (if-let [cid (coerce-call-id call-id)]
                    (tx-main-sync! {:op :answer-call :call-id cid :callee agent-id :result value})
                    {:ok? false :error {:class :invalid-call-id :value call-id}}))))
      (intern u 'fail!
              (fn [call-id error]
                (when-let [{:keys [agent-id]} *agent-ctx*]
                  (if-let [cid (coerce-call-id call-id)]
                    (tx-main-sync! {:op :fail-call :call-id cid :callee agent-id :error error})
                    {:ok? false :error {:class :invalid-call-id :value call-id}}))))
      (intern u 'cancel!
              (fn
                ([call-id] ((resolve 'user/cancel!) call-id "cancelled"))
                ([call-id reason]
                 (if-let [cid (coerce-call-id call-id)]
                   (tx-main-sync! {:op :cancel-call :call-id cid :reason (str reason)})
                   {:ok? false :error {:class :invalid-call-id :value call-id}}))))
      (intern u 'calls
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (let [mdb (store/head conn :main)
                        pull-all (fn [attr]
                                   (if (contains? (:schema mdb) attr)
                                     (->> (d/datoms mdb :avet attr agent-id)
                                          (map #(dissoc (d/pull mdb '[*] (:e %)) :db/id))
                                          (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                          vec)
                                     []))]
                    {:incoming (pull-all :seon.agent.call/callee)
                     :outgoing (pull-all :seon.agent.call/caller)}))))
      (intern u 'show-user!
              (fn [{:keys [user-id viewing mode sidebar? expand selected-branch as-of-tx] :as arg}]
                (when-let [{:keys [out]} *agent-ctx*]
                  (a/>!! out (cond-> {:ev :tx-main
                                      :op :show-user
                                      :user-id (or user-id :user/default)}
                               viewing (assoc :viewing viewing)
                               mode (assoc :mode mode)
                               (some? sidebar?) (assoc :sidebar? (boolean sidebar?))
                               (seq expand) (assoc :expand (set expand))
                               selected-branch (assoc :selected-branch selected-branch)
                               (contains? arg :as-of-tx) (assoc :as-of-tx as-of-tx)))
                  {:ok? true :viewing viewing :mode mode :sidebar? sidebar? :as-of-tx as-of-tx})))
      (intern u 'remember!
              (fn [note]
                (when *agent-ctx*
                  (let [cur (vec (or (:seon.agent/memory @ctx/ctx) []))
                        nxt (if (some #(= % note) cur) cur (conj cur note))]
                    (swap! ctx/ctx assoc :seon.agent/memory nxt)
                    {:remembered note :count (count nxt)}))))
      (intern u 'forget!
              (fn [idx-or-note]
                (when *agent-ctx*
                  (let [cur (vec (or (:seon.agent/memory @ctx/ctx) []))
                        nxt (if (and (integer? idx-or-note) (<= 0 idx-or-note (dec (count cur))))
                              (vec (concat (subvec cur 0 idx-or-note) (subvec cur (inc idx-or-note))))
                              (vec (remove #(= % idx-or-note) cur)))]
                    (swap! ctx/ctx assoc :seon.agent/memory nxt)
                    {:count (count nxt)}))))
      (intern u 'tune-context!
              (fn [{:keys [pin unpin pattern recent-data-limit max-vars-shown max-schemas-shown
                           extra-namespaces extra-schemas] :as opts}]
                (when *agent-ctx*
                  (let [cur-prefs (or (:seon.agent/context-prefs @ctx/ctx) {})
                        pref-patch (cond-> {}
                                     (integer? recent-data-limit) (assoc :recent-data-limit (long recent-data-limit))
                                     (integer? max-vars-shown)    (assoc :max-vars-shown (long max-vars-shown))
                                     (integer? max-schemas-shown) (assoc :max-schemas-shown (long max-schemas-shown))
                                     (contains? opts :extra-namespaces)
                                     (assoc :extra-namespaces (mapv #(if (string? %) (symbol %) %) extra-namespaces))
                                     (contains? opts :extra-schemas)
                                     (assoc :extra-schemas (vec extra-schemas)))
                        next-prefs (merge cur-prefs pref-patch)]
                    (when (seq pref-patch)
                      (swap! ctx/ctx assoc :seon.agent/context-prefs next-prefs))
                    (when (and (vector? pattern) (seq pattern))
                      (swap! ctx/ctx assoc :seon.ctx/pattern pattern))
                    (doseq [p pin] ((resolve 'user/pin!) p))
                    (doseq [u-item unpin] ((resolve 'user/unpin!) u-item))
                    {:ok? true :context-prefs next-prefs}))))
      (intern u 'compact!
              (fn
                ([] ((resolve 'user/compact!) {}))
                ([summary-or-opts]
                 (when-let [{:keys [conn agent-id]} *agent-ctx*]
                   (let [opts (if (map? summary-or-opts) summary-or-opts {:summary (str summary-or-opts)})
                         {:keys [summary memory tune through-turn include-current?]} opts
                         _ (when (not (str/blank? (str summary)))
                             ((resolve 'user/remember!) (str summary)))
                         _ (doseq [m memory] ((resolve 'user/remember!) m))
                         _ (when (map? tune) ((resolve 'user/tune-context!) tune))
                         db @conn
                         turns (map #(:seon.agent.eval/turn (d/pull db '[:seon.agent.eval/turn] (:e %)))
                                    (d/datoms db :avet :seon.agent.eval/agent agent-id))
                         max-t (reduce max 0 (remove nil? turns))
                         cut (long (cond
                                     (integer? through-turn) through-turn
                                     include-current? (inc max-t)
                                     :else max-t))]
                     (swap! ctx/ctx assoc :seon.agent/compacted-through-turn cut)
                     {:ok? true
                      :compacted-through-turn cut
                      :preserved-evals-in-db (count turns)
                      :memory-count (count (:seon.agent/memory @ctx/ctx))})))))
      (intern u 'wait-for-mail!
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? true :seon.agent/ready? false}]})
                  :waiting)))
      (intern u 'ready!
              (fn []
                (when-let [{:keys [conn agent-id]} *agent-ctx*]
                  (d/transact conn {:tx-data [{:seon.agent/id agent-id :seon.agent/waiting? false :seon.agent/ready? true}]})
                  :ready))))))

(defn- unread-messages-for [main-db branch-db agent-id]
  (let [seen (into #{}
                   (mapcat (fn [dt]
                             (:seon.agent.eval/seen-messages
                              (d/pull branch-db '[:seon.agent.eval/seen-messages] (:e dt)))))
                   (d/datoms branch-db :avet :seon.agent.eval/agent agent-id))]
    (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
         (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                 :seon.agent.message/from :seon.agent.message/to
                                 :seon.agent.message/type :seon.agent.message/body] (:e %)))
         (remove #(contains? seen (:seon.agent.message/id %)))
         (sort-by :seon.agent.message/seq)
         vec)))

(defn- agent-history-turns [branch-db agent-id]
  (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
       (map #(d/pull branch-db '[:seon.agent.eval/turn :seon.agent.eval/call-index
                                 :seon.agent.eval/call-id :seon.agent.eval/handle
                                 :seon.agent.eval/result-schema :seon.agent.eval/thought
                                 :seon.agent.eval/reasoning :seon.agent.eval/intent
                                 :seon.agent.eval/code :seon.agent.eval/value
                                 :seon.agent.eval/out :seon.agent.eval/error
                                 :seon.agent.eval/ms :seon.agent.eval/ticks] (:e %)))
       (group-by :seon.agent.eval/turn)
       (sort-by key)
       (mapv (fn [[turn es]]
               (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                     first-e (first ordered)]
                 {:turn turn
                  :thought (:seon.agent.eval/thought first-e)
                  :reasoning (:seon.agent.eval/reasoning first-e)
                  :calls (mapv (fn [e]
                                 {:id (or (:seon.agent.eval/call-id e) (str "t" turn "-c" (or (:seon.agent.eval/call-index e) 0)))
                                  :handle (:seon.agent.eval/handle e)
                                  :result-schema (:seon.agent.eval/result-schema e)
                                  :intent (:seon.agent.eval/intent e)
                                  :code (:seon.agent.eval/code e)
                                  :value (:seon.agent.eval/value e)
                                  :out (:seon.agent.eval/out e)
                                  :error (:seon.agent.eval/error e)
                                  :ms (:seon.agent.eval/ms e)
                                  :ticks (:seon.agent.eval/ticks e)})
                               ordered)})))))

(defn- sync-missing-main-schema!
  "Ensures a long-lived branch (e.g. `:branch/root`) always has every accretive Datahike `:db/ident`
   attribute declaration AND matching `:seon.schema/key` Malli entity installed on `:main`."
  [conn branch]
  (when (not= branch :main)
    (let [mdb (store/head conn :main)
          bdb @conn
          main-schema (:schema mdb)
          br-schema (:schema bdb)
          missing-dh (->> main-schema
                          (keep (fn [[k decl]]
                                  (when (and (qualified-keyword? k)
                                             (not= "db" (namespace k))
                                             (map? decl)
                                             (:db/valueType decl)
                                             (not (contains? br-schema k)))
                                    (dissoc decl :db/id))))
                          vec)
          _ (when (seq missing-dh)
              (d/transact conn {:tx-data missing-dh}))
          bdb2 @conn
          br-keys (into #{} (map :v) (d/datoms bdb2 :aevt :seon.schema/key))
          missing-ents (->> (d/datoms mdb :aevt :seon.schema/key)
                            (keep (fn [dt]
                                    (when-not (contains? br-keys (:v dt))
                                      (let [e (d/pull mdb '[:seon.schema/key :seon.schema/form :seon.schema/doc :seon.schema/render] (:e dt))]
                                        (when (:seon.schema/form e) e)))))
                            vec)]
      (when (seq missing-ents)
        (d/transact conn {:tx-data missing-ents})))))

(defn- sync-conn-to-latest-branch-head!
  "Syncs warm agent worker to both its latest branch head AND any newly merged `:main` code/schema deltas
   so running agents always execute up-to-date `:main` code overlaid with their own branch edits."
  [conn branch last-cid last-main-cid]
  (sync-missing-main-schema! conn branch)
  (let [latest-db (store/head conn branch)
        latest-cid (d/commit-id latest-db)
        latest-main-cid (d/commit-id (store/head conn :main))
        main-moved? (and (not= branch :main)
                         last-main-cid latest-main-cid
                         (not= last-main-cid latest-main-cid))
        branch-moved? (and last-cid latest-cid (not= last-cid latest-cid))]
    (when main-moved?
      (try
        (code/load-delta! conn last-main-cid latest-main-cid)
        (when-let [base-cid (d/merge-base conn :main branch)]
          (when (not= base-cid latest-cid)
            (code/load-delta! conn base-cid latest-cid true)))
        (catch Throwable _ nil)))
    (when branch-moved?
      (let [ds (try (d/changes conn last-cid latest-cid) (catch Throwable _ nil))]
        (reset! (:wrapped-atom conn) latest-db)
        (code/load-delta! conn last-cid latest-cid true)
        (ctx/refresh-active! latest-db ds)))
    {:branch-cid latest-cid :main-cid latest-main-cid}))

(defn- sanitize-edn-hiccup
  "Walks Hiccup returned by a branch-local render fn so it is guaranteed pure EDN for IPC transport."
  [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)) x
    (vector? x) (mapv sanitize-edn-hiccup x)
    (map? x) (into {} (map (fn [[k v]] [(sanitize-edn-hiccup k) (sanitize-edn-hiccup v)])) x)
    (list? x) (apply list (map sanitize-edn-hiccup x))
    (seq? x) (mapv sanitize-edn-hiccup x)
    (set? x) (into #{} (map sanitize-edn-hiccup) x)
    :else (pr-str x)))

(defn- maybe-emit-branch-render!
  "When an agent on branch `B` defines a custom `:seon.agent/render-fn` (or edits `seon.render.ui/root-canvas`
   ahead of `:main`), evaluates it safely inside the warm COW worker (50M ticks, fault-isolated) and
   pushes pure Hiccup EDN straight to `:web` so UI refinements take effect in realtime!"
  [conn out agent-id branch]
  (when (and conn out agent-id (not= branch :main))
    (try
      (let [bdb @conn
            mdb (store/head conn :main)
            base-db (or (try (store/merge-base-db conn :main branch) (catch Throwable _ nil)) mdb)
            ag (d/pull bdb '[*] [:seon.agent/id agent-id])
            custom-sym (:seon.agent/render-fn ag)
            root-edited? (let [bh (:seon.code.form/hash (d/pull bdb '[:seon.code.form/hash] [:seon.code.form/key "seon.render.ui/root-canvas"]))
                               base-h (:seon.code.form/hash (d/pull base-db '[:seon.code.form/hash] [:seon.code.form/key "seon.render.ui/root-canvas"]))
                               mh (:seon.code.form/hash (d/pull mdb '[:seon.code.form/hash] [:seon.code.form/key "seon.render.ui/root-canvas"]))]
                           (and bh (not= bh base-h) (not= bh mh)))
            target-sym (cond
                         (and (qualified-symbol? custom-sym) (not= custom-sym 'seon.render.ui/root-canvas))
                         custom-sym
                         root-edited?
                         'seon.render.ui/root-canvas
                         :else nil)]
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
                         :hiccup (sanitize-edn-hiccup
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

(defn- execute-turn-step! [conn in out agent-id branch cur-ns]
  (let [main-db (store/head conn :main)
        branch-db @conn
        me (d/pull main-db '[*] [:seon.agent/id agent-id])
        unread (unread-messages-for main-db branch-db agent-id)
        history (agent-history-turns branch-db agent-id)
        turn-n (inc (reduce max 0 (map :turn history)))
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
                :health (health/report branch-db)
                :unread unread})
    (loop []
      (if-let [m (a/<!! in)]
        (cond
          (= (:op m) :stop)
          {:stopped? true :ns cur-ns}

          (and (= (:op m) :model-response) (= (:req-id m) req-id))
          (let [{:keys [thought reasoning usage code tool-calls]} (:response m)
                seen-ids (mapv :seon.agent.message/id unread)
                has-calls? (or (seq tool-calls) (not (str/blank? (str code))))]
            (if-not has-calls?
              ;; Clean conversational reply (0 tool calls): store prose + reasoning & wait for user!
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
              ;; Tool-call turn: clear `:waiting?` first so explicit `(user/wait-for-mail!)` or `(user/ready!)`
              ;; takes effect, otherwise agent automatically continues to read `result/e<n>` on the next turn!
              (let [_ (d/transact conn {:tx-data [(cond-> {:seon.agent/id agent-id :seon.agent/waiting? false}
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
                                :start-ns cur-ns}
                               calls)]
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
  (let [past-turns (map #(:seon.agent.eval/turn (d/pull @conn '[:seon.agent.eval/turn] (:e %)))
                        (d/datoms @conn :avet :seon.agent.eval/agent agent-id))
        turn-n (inc (reduce max 0 past-turns))]
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
                   (code/load-delta! conn base-cid branch-cid true)
                   {:loaded [] :unmapped [] :errors []})]
      (try
        (sync-missing-main-schema! conn branch)
        (ensure-agent-entity! conn agent-id branch)
        (let [my-ctx (ctx/ctx-atom conn [:seon.agent/id agent-id])]
          (install-agent-helpers!)
          (intern (create-ns 'user) 'ctx my-ctx)
          (a/>!! out {:ev :agent-started
                      :agent-id agent-id
                      :branch branch
                      :pid (c-getpid)
                      :ceiling (jolt.host/collect-ceiling)
                      :mem-mb (mem-mb)
                      :loaded-delta (:loaded loaded)})
          (binding [*agent-ctx* {:conn conn :in in :out out :agent-id agent-id :branch branch}
                    ctx/ctx my-ctx]
          (let [ns0 (cond
                      initial-eval
                      (run-eval-cmd! conn out agent-id branch 'user initial-eval)

                      (= mode :turn)
                      (let [r (execute-turn-step! conn in out agent-id branch 'user)]
                        (if (:stopped? r) ::stop (:ns r)))

                      :else 'user)]
            (when-not (= ::stop ns0)
              (loop [cur-ns ns0
                     known-cid (d/commit-id @conn)
                     known-main-cid (d/commit-id (store/head conn :main))]
                (when-let [msg (a/<!! in)]
                  (case (:op msg)
                    :stop nil

                    :eval
                    (let [{:keys [main-cid]} (sync-conn-to-latest-branch-head! conn branch known-cid known-main-cid)
                          ns' (run-eval-cmd! conn out agent-id branch cur-ns msg)]
                      (recur ns' (d/commit-id @conn) main-cid))

                    :run-turn
                    (let [{:keys [main-cid]} (sync-conn-to-latest-branch-head! conn branch known-cid known-main-cid)
                          r (execute-turn-step! conn in out agent-id branch cur-ns)]
                      (when-not (:stopped? r)
                        (recur (:ns r) (d/commit-id @conn) main-cid)))

                    (recur cur-ns known-cid known-main-cid))))))))
        (finally
          (d/release conn))))))

;; --- Single-Threaded System Fork Parent Loop ---

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
