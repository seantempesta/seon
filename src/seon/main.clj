(ns seon.main
  "System assembly, AOT boot preloads (`datahike.query.execute`), `--self-test` cold-boot verifier, graceful drain, and `--take-over` atomic binary swap-over."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.query.execute]
            [seon.agent :as agents]
            [seon.browser :as browser]
            [seon.code :as code]
            [seon.fork :as fork]
            [seon.git :as git]
            [seon.git-proc :as git-proc]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.render.ui :as ui]
            [seon.server :as server]
            [seon.store :as store :refer [rm-rf!]]
            [seon.switchover :as switchover]
            [seon.test :as test-runner]
            [seon.transactor :as transactor]
            [seon.web :as web]
            [clojure.tools.cli :as cli]))

(defmacro ^{:private true} compile-epoch-ms []
  (System/currentTimeMillis))

(def ^{:private true} aot-build-ms (compile-epoch-ms))

(defn- sync-cold-boot-forms!
  "Overlays non-test `:seon.code.form/*` entities on `db0` written after `aot-build-ms`\n              into the coordinator runtime and ForkParent so cold-booted binaries (reboot or\n              `--seed-store` benchmark instances) never run stale AOT code against newer `:main`."
  [db0 router]
  (when (and db0 (contains? (:schema db0) :seon.code.form/src))
    (let [cutoff (- (long aot-build-ms) 60000)
          fresh-txs (into
                      #{}
                      (keep
                        (fn [dt]
                          (let [v (:v dt)]
                            (when (and
                                    (instance? java.util.Date v)
                                    (>=
                                      (.getTime
                                        ^{:tag java.util.Date} v)
                                      cutoff))
                              (:e dt)))))
                      (d/datoms db0 :aevt :db/txInstant))]
      (when (seq fresh-txs)
        (let [upserts (->>
                        (d/datoms db0 :aevt :seon.code.form/src)
                        (keep
                          (fn [dt]
                            (when (contains? fresh-txs (:tx dt))
                              (let [f
                                    (d/pull
                                      db0
                                      '[:seon.code.form/key
                                      :seon.code.form/ns
                                      :seon.code.form/src
                                      :seon.code.form/order
                                      :seon.code.form/deps]
                                      (:e dt))
                                    nsym (:seon.code.form/ns f)]
                                (when
                                  (and nsym (not= nsym 'seon.main))
                                  f)))))
                        vec)]
          (when (seq upserts)
            (code/apply-overlay! db0 {:upserts upserts} true)
            (when-let [fo (:fork-out router)]
              (a/put!
                fo
                {:op :advance-image,
                 :upserts upserts,
                 :commit (d/commit-id db0)}))))))))

(defn make-fork-router [fork-parent]
  (let [targets (volatile! {:agent-ch nil :test-ch nil :kill-ack nil})]
    (a/go-loop []
      (when-let [ev (a/<! (:in (:end fork-parent)))]
        (let [{:keys [agent-ch test-ch kill-ack]} @targets]
          (cond
            (= (:ev ev) :killed-all-9) (when kill-ack (deliver kill-ack (:pids ev)))
            (= (:type ev) :agent)      (when agent-ch (a/>! agent-ch ev))
            (= (:type ev) :test)       (when test-ch (a/>! test-ch ev))))
        (recur)))
    {:fork-parent fork-parent
     :fork-out (:out (:end fork-parent))
     :set-targets! (fn [m] (vreset! targets m))}))

(defn start-flow-instance!
  {:malli/schema [:=> [:cat [:map [:fork-out :seon.host/chan]] :map] :map]}
  [router {:keys [store-path seed-store repo-dir commit-git? auto-switch-over? port listener model-provider max-os-procs max-test-workers boot-seq on-power-loss on-hand-over on-switch-over enable-browser?]
          :or {port 0 boot-seq 1}}]
  (let [{:keys [cfg conn created?]} (store/open! store-path (cond-> {} repo-dir (assoc :repo-dir repo-dir) seed-store (assoc :seed-store seed-store)))
        _ (when repo-dir
            (let [catchup (git/catch-up-tx @conn repo-dir)]
              (when (seq catchup)
                (let [sch-forms (into {} (keep #(when (and (map? %) (:seon.schema/key %) (some? (:seon.schema/form %)))
                                                  [(:seon.schema/key %) (:seon.schema/form %)]))
                                      catchup)
                      reg (when (seq sch-forms) (seon.schema/registry-for-db @conn sch-forms))
                      new-dh (when reg (vec (remove #(contains? (:schema @conn) (:db/ident %)) (:declarations reg))))]
                  (when (seq new-dh)
                    (datahike.api/transact conn {:tx-data new-dh})))
                (datahike.api/transact conn {:tx-data catchup})
                (store/sync! cfg))))
        db0 @conn
        auto-git? (do
                  (sync-cold-boot-forms! db0 router)
                  (if (some? commit-git?)
                    commit-git?
                    (and
                      (some? repo-dir)
                      (boolean (store/config-val db0 :seon.config/auto-sync-git?)))))
        os-procs (or max-os-procs (store/config-val db0 :seon.config/max-os-procs) 6)
        test-workers (or max-test-workers (store/config-val db0 :seon.config/max-test-workers) 4)
        model-slots (or (store/config-val db0 :seon.config/max-model-slots) 4)
        eff-port (long (or (when listener (:port listener)) (when (and port (pos? (long port))) port) 0))
        browser-bin (when (and enable-browser? (pos? eff-port)) (browser/default-binary-path))
        browser-on? (boolean browser-bin)
        vision-env (System/getenv "SEON_AI_VISION")
        vision-on? (cond
                     (= "1" vision-env) true
                     (= "0" vision-env) false
                     :else (model/vision-capable-model? (or (:model model-provider)
                                                            (store/config-val db0 :seon.config/ai-model))))
        server-in-ch (a/chan 64)
        model-ctrl-ch (a/chan 8)
        fork-agent-in (a/chan 128)
        fork-test-in (a/chan 128)
        report-chan (a/chan (a/sliding-buffer 128))
        error-chan (a/chan (a/sliding-buffer 64))
        flow-holder (volatile! nil)
        _ ((:set-targets! router) {:agent-ch fork-agent-in :test-ch fork-test-in :kill-ack nil})
        g (flow/create-flow
           {:procs
            {:server     {:proc (flow/process #'server/server-proc)
                          :args {:in-ch server-in-ch
                                 :conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :on-hand-over on-hand-over
                                 :on-switch-over on-switch-over}}
             :transactor {:proc (flow/process #'transactor/transactor-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :commit-git? auto-git?
                                 :fork-out (:fork-out router)}
                          :chan-opts {:tx {:buf-or-n 64}}}
             :reconciler {:proc (flow/process #'reconciler/reconciler-proc)
                          :args {:conn conn}
                          :chan-opts {:db {:buf-or-n 32} :exits {:buf-or-n 64}}}
             :agents     {:proc (flow/process #'agents/agents-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :fork-out (:fork-out router)
                                 :fork-agent-in fork-agent-in
                                 :max-os-procs os-procs
                                 :browser-enabled? browser-on?
                                 :vision? vision-on?}
                          :chan-opts {:run {:buf-or-n 32}
                                      :eval {:buf-or-n 32}
                                      :response {:buf-or-n 32}
                                      :browser-res {:buf-or-n 16}}}
             :browser    {:proc (flow/process #'browser/browser-proc)
                          :args {:port (if browser-on? eff-port 0)
                                 :binary-path browser-bin}
                          :chan-opts {:req {:buf-or-n 16}}}
             :model      {:proc (flow/process #'model/model-proc)
                          :args {:provider model-provider
                                 :ctrl-ch model-ctrl-ch
                                 :max-slots model-slots
                                 :latency-ms 80}
                          :chan-opts {:request {:buf-or-n 16}}}
             :test       {:proc (flow/process #'test-runner/test-proc)
                          :args {:conn conn
                                 :cfg cfg
                                 :fork-out (:fork-out router)
                                 :fork-test-in fork-test-in
                                 :max-workers test-workers}
                          :chan-opts {:db {:buf-or-n 32} :batch {:buf-or-n 32}}}
             :git        {:proc (flow/process #'git-proc/git-proc)
             :args {:conn conn
                    :cfg cfg
                    :repo-dir repo-dir
                    :store-path store-path
                    :port eff-port
                    :auto-switch-over? auto-switch-over?}
             :chan-opts {:in {:buf-or-n 32}}}
             :web        {:proc (flow/process #'web/web-proc)
                          :args {:port port
                                 :listener listener
                                 :conn conn
                                 :cfg cfg
                                 :repo-dir repo-dir
                                 :store-path store-path
                                 :fork-parent-pid (get-in router [:fork-parent :pid])
                                 :fork-parent-ceiling (get-in router [:fork-parent :ceiling])
                                 :server-in-ch server-in-ch
                                 :report-chan report-chan
                                 :error-chan error-chan
                                 :on-power-loss on-power-loss
                                 :boot-seq boot-seq}
                          :chan-opts {:db {:buf-or-n (a/sliding-buffer 16)}
                                      :stream {:buf-or-n (a/sliding-buffer 64)}}}}

            :conns
            [[[:server :tx]          [:transactor :tx]]
             [[:server :eval]        [:agents :eval]]
             [[:server :batch]       [:test :batch]]
             [[:server :git]         [:git :in]]
             [[:transactor :db]      [:reconciler :db]]
             [[:transactor :db]      [:test :db]]
             [[:transactor :db]      [:web :db]]
             [[:transactor :git]     [:git :in]]
             [[:git :tx]             [:transactor :tx]]
             [[:reconciler :run]     [:agents :run]]
             [[:reconciler :batch]   [:test :batch]]
             [[:reconciler :tx]      [:transactor :tx]]
             [[:agents :request]     [:model :request]]
             [[:agents :browser-req] [:browser :req]]
             [[:browser :agents-res] [:agents :browser-res]]
             [[:browser :web-snap]   [:web :snap]]
             [[:model :response]     [:agents :response]]
             [[:model :stream]       [:web :stream]]
             [[:agents :tx]          [:transactor :tx]]
             [[:agents :git]         [:git :in]]
             [[:agents :exits]       [:reconciler :exits]]
             [[:agents :exits]       [:web :exits]]
             [[:test :tx]            [:transactor :tx]]]

            :report-chan report-chan
            :error-chan error-chan})]
    (vreset! flow-holder g)
    (flow/start g)
    (flow/resume g)
    {:flow g
     :conn conn
     :cfg cfg
     :created? created?
     :server-in-ch server-in-ch
     :report-chan report-chan
     :error-chan error-chan
     :router router}))

(defn call!
  ([sys req] (call! sys req 30000))
  ([sys req timeout-ms]
   (let [reply-ch (a/chan 1)]
     (a/>!! (:server-in-ch sys) {:req req :reply-ch reply-ch})
     (let [[ans _] (a/alts!! [reply-ch (a/timeout timeout-ms)])]
       (or ans {:ok? false :error :timeout :req req})))))

(defn stop!
  ([sys] (stop! sys {:close-fork-parent? false}))
  ([{:keys [flow conn cfg router]} {:keys [close-fork-parent?]}]
   (let [ack (promise)]
     ((:set-targets! router) {:agent-ch nil :test-ch nil :kill-ack ack})
     (a/>!! (:fork-out router) {:op :kill-all-9!})
     (deref ack 2000 [])
     (when flow
       (try (flow/stop flow) (catch Throwable _)))
     (store/close! {:conn conn :cfg cfg})
     (when close-fork-parent?
       (try (a/close! (:fork-out router)) (catch Throwable _))))))

(defn- request-hand-over! [port]
  (let [resp (model/http-post! (str "http://127.0.0.1:" port "/op")
                               [["Content-Type" "application/edn"]]
                               "{:op :hand-over}")]
    (if (= 200 (:status resp))
      (edn/read-string (:body resp))
      {:ok? false :status (:status resp) :body (:body resp)})))

(defn- resolve-standing-model-provider
  "Selects live `:openai-compat` DeepSeek multimodal streaming (`deepseek-flash` supports native
   text + image inputs on `api.deepseek.com`) when its API key env var is set (unless
   `SEON_MODEL_STUB=1`), falling back cleanly to `:built-in` offline synthesis."
  ([] (resolve-standing-model-provider {}))
  ([{:keys [model base-url stub?]}]
   (let [api-key-env (or (System/getenv "SEON_AI_API_KEY_ENV") "DEEPSEEK_API_KEY")
         api-key (System/getenv api-key-env)
         eff-stub? (or (boolean stub?) (= "1" (System/getenv "SEON_MODEL_STUB")))]
     (if (and (not eff-stub?) (not (str/blank? api-key)))
       {:type :openai-compat
        :model (or model (System/getenv "SEON_AI_MODEL") "deepseek-flash")
        :base-url (or base-url (System/getenv "SEON_AI_BASE_URL") "https://api.deepseek.com")
        :api-key-env api-key-env
        :thinking :enabled
        :reasoning-effort :low
        :stream? true}
       {:type :built-in}))))

(defn drain-for-handover!
  "Gracefully pauses new agent dispatches (`:reconciler`) and waits up to `timeout-ms` for in-flight
   agent turns and test workers to finish committing into LMDB before handing over the port."
  ([sys] (drain-for-handover! sys 4000))
  ([sys timeout-ms]
   (when-let [g (:flow sys)]
     (try (flow/pause-proc g :reconciler) (catch Throwable _))
     (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
       (loop []
         (let [pings (try (flow/ping g) (catch Throwable _ nil))
               busy-agents (get-in pings [:agents :busy-os-procs] 0)
               busy-tests  (get-in pings [:test :active-workers] 0)
               idle? (and (zero? (long busy-agents)) (zero? (long busy-tests)))]
           (cond
             idle? true
             (> (System/currentTimeMillis) deadline) false
             :else (do (Thread/sleep 50) (recur)))))))))

(defn ensure-boot-state!
  "100% data-driven post-boot initializer (ZERO hardcoded `:agent/root` maps!):
   - If the store holds no agents yet (e.g. booted without `:repo-dir`), imports declarative
     `resources/seon/agents/*.edn` seed files from `seed-dir` when present.
   - Ensures every declared non-`:main` `:seon.agent/branch` exists in Datahike.
   - Seeds `:user/default` pointing at `(ui/entry-agent-id db)` only when absent (never
     overwriting restored LMDB user viewport state!)."
  ([sys] (ensure-boot-state! sys store/checkout-root))
  ([sys seed-dir]
   (let [conn (:conn sys)
         db0 @conn
         has-agents? (boolean (first (d/datoms db0 :aevt :seon.agent/id)))]
     (when (and (not has-agents?) seed-dir)
       (let [adir (io/file seed-dir "resources/seon/agents")]
         (when (.isDirectory adir)
           (doseq [^java.io.File f (sort-by #(.getName ^java.io.File %) (.listFiles adir))
                   :when (str/ends-with? (.getName f) ".edn")]
             (let [rel (str "resources/seon/agents/" (.getName f))
                   tx (code/file-import-tx @conn rel (slurp f) false)]
               (when (seq tx)
                 (call! sys {:op :transact :tx-data tx :reason [:seed-agents rel]})))))))
     (let [db @conn]
       (doseq [dt (d/datoms db :aevt :seon.agent/branch)
               :let [b (:v dt)]
               :when (and (keyword? b) (not= b :main))]
         (store/branch! conn :main b))
       (when-not (first (d/datoms db :avet :seon.user/id :user/default))
         (when-let [entry-id (ui/entry-agent-id db)]
           (call! sys {:op :transact
                       :tx-data [{:seon.user/id :user/default
                                  :seon.user/viewing [:seon.agent/id entry-id]
                                  :seon.user/view-mode :canvas
                                  :seon.user/sidebar-open? false
                                  :seon.user/expanded-agents #{entry-id}}]})))))))

(def cli-options
  [["-p" "--port PORT" "HTTP port (0 = ephemeral OS-assigned port)"
    :parse-fn parse-long
    :validate [#(and (integer? %) (<= 0 % 65535)) "Must be 0..65535"]]
   [nil "--store PATH" "LMDB store directory"]
   [nil "--seed-store PATH" "Copy pre-seeded LMDB store into --store if absent"]
   [nil "--repo PATH" "Git repository working tree"]
   [nil "--take-over PORT" "Gracefully take over a running daemon on PORT"
    :parse-fn parse-long
    :validate [#(and (integer? %) (< 0 % 65536)) "Must be 1..65535"]]
   [nil "--headless" "Disable WKWebView browser proc (for parallel/CI instances)"]
   [nil "--commit-git BOOL" "Write green merges back to local Git (true/false)"
    :parse-fn #(cond (= "true" %) true (= "false" %) false :else :invalid)
    :validate [boolean? "Must be true or false"]]
   [nil "--auto-switch-over BOOL" "Automatically rebuild & swap binary on boot-affected merge (true/false)"
    :parse-fn #(cond (= "true" %) true (= "false" %) false :else :invalid)
    :validate [boolean? "Must be true or false"]]
   [nil "--model-base-url URL" "Override OpenAI-compat API base URL (e.g. local bench proxy)"]
   [nil "--model NAME" "Override AI model name"]
   [nil "--model-stub" "Force offline built-in synthesizer"]
   [nil "--max-os-procs N" "Max concurrent COW agent OS processes"
    :parse-fn parse-long
    :validate [#(and (integer? %) (pos? %)) "Must be > 0"]]
   [nil "--max-test-workers N" "Max concurrent COW test workers"
    :parse-fn parse-long
    :validate [#(and (integer? %) (pos? %)) "Must be > 0"]]
   [nil "--self-test" "Boot an isolated smoke instance, evaluate (+ 20 22), and exit"]
   ["-h" "--help" "Show CLI usage summary"]])

(defn parse-cli-options
  "Parses CLI arguments using `clojure.tools.cli/parse-opts`."
  {:malli/schema [:=> {:examples [[[["--port" "0" "--headless" "--auto-switch-over" "false"]]
                                   {:options {:port 0 :headless true :auto-switch-over false}
                                    :errors nil}]
                                  [[["--self-test"]]
                                   {:options {:self-test true}
                                    :errors nil}]]}
                  [:cat [:sequential :string]] :map]}
  [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond-> {:options options :errors errors}
      (or (:help options) (seq errors)) (assoc :summary summary))))

(defn -main
  "CLI entrypoint for Seon (`--self-test`, `--port 0`, `--headless`, `--seed-store`, `--model-base-url`, `--auto-switch-over`, `--take-over`)."
  {:malli/schema [:function [:=> [:cat [:* :string]] :any]]}
  [& args]
  (let [{:keys [options errors summary]} (parse-cli-options (vec args))]
    (when (:help options)
      (println "Usage: target/seon [options]\n" summary)
      (flush)
      (System/exit 0))
    (when (seq errors)
      (doseq [e errors] (println e))
      (when summary (println summary))
      (flush)
      (System/exit 2))
    (jolt.host/main-carrier-run!
     (fn []
       (let [self-test? (boolean (:self-test options))
             take-over-port (:take-over options)
             fp (fork/start-fork-parent!)
             router (make-fork-router fp)]
         (if self-test?
           (let [tmp-dir (str "/tmp/seon-selftest-" (System/nanoTime))
                 sys (start-flow-instance! router {:store-path tmp-dir :commit-git? false :port 0})]
             (try
               (let [st (call! sys {:op :status})
                     ev (call! sys {:op :eval :branch :branch/smoke :code "(+ 20 22)"})]
                 (if (and (:ok? st) (:ok? ev) (= "42" (:value ev)))
                   (do (println "SEON SELF-TEST PASS" (pr-str {:fork-parent-pid (:pid fp) :eval (:value ev)}))
                       (flush)
                       (stop! sys {:close-fork-parent? true})
                       (rm-rf! (io/file tmp-dir))
                       (System/exit 0))
                   (do (println "SEON SELF-TEST FAIL" (pr-str {:status st :eval ev}))
                       (flush)
                       (stop! sys {:close-fork-parent? true})
                       (rm-rf! (io/file tmp-dir))
                       (System/exit 1))))
               (catch Throwable ex
                 (println "SEON SELF-TEST ERROR" (.getMessage ex))
                 (flush)
                 (System/exit 1))))

           (let [repo-dir (or (:repo options) (System/getenv "SEON_REPO") (System/getProperty "user.dir"))
                 store-path (or (:store options) (System/getenv "SEON_STORE") (str repo-dir "/runs/store"))
                 seed-store (:seed-store options)
                 raw-port (cond
                            take-over-port take-over-port
                            (contains? options :port) (:port options)
                            (System/getenv "SEON_PORT") (parse-long (System/getenv "SEON_PORT"))
                            :else 9998)
                 ephemeral-port? (zero? (long raw-port))
                 commit-git-env (System/getenv "SEON_COMMIT_GIT")
                 commit-git? (cond
                               (contains? options :commit-git) (:commit-git options)
                               commit-git-env (= "1" commit-git-env)
                               :else nil)
                 auto-sw? (when (contains? options :auto-switch-over) (:auto-switch-over options))
                 headless? (boolean (:headless options))
                 prov-opts {:model (:model options)
                            :base-url (:model-base-url options)
                            :stub? (:model-stub options)}
                 _ (when take-over-port
                     (let [ans (request-hand-over! take-over-port)]
                       (when-not (:released ans)
                         (println "SEON TAKE-OVER REFUSED:" (pr-str ans))
                         (flush)
                         (System/exit 1))))
                 listener (if take-over-port
                            (loop [attempts 40]
                              (let [res (try {:ok (web/start-http-listener! take-over-port)}
                                             (catch Throwable ex {:err ex}))]
                                (if (:ok res)
                                  (:ok res)
                                  (if (pos? attempts)
                                    (do (Thread/sleep 60) (recur (dec attempts)))
                                    (throw (:err res))))))
                            (web/start-http-listener! (if ephemeral-port? -1 (long raw-port))))
                 bound-port (:port listener raw-port)
                 active-sys (volatile! nil)
                 rebooting? (volatile! false)
                 exit-ch (a/chan 1)
                 hand-over! (fn [reply-ch]
                              (when-let [cur @active-sys]
                                (vreset! active-sys nil)
                                (let [drained? (drain-for-handover! cur 4000)
                                      sync-res (try (store/sync! (:cfg cur)) {:ok? true}
                                                    (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))}))]
                                  (when-not (:ok? sync-res)
                                    (println (str "⚠ [HAND-OVER] store/sync! warning: " (:error sync-res))))
                                  (stop! cur {:close-fork-parent? true})
                                  (a/>!! reply-ch {:ok? true :released true :drained? drained? :sync-ok? (:ok? sync-res) :port bound-port})
                                  (a/close! reply-ch)
                                  (Thread/sleep 40)
                                  (when listener ((:stop! listener)))
                                  (println (str "✓ [HAND-OVER] Drained=" drained? " SyncOk=" (:ok? sync-res) " — released store & port " bound-port " to successor binary."))
                                  (flush)
                                  (a/>!! exit-ch :handed-over))))
                 switch-over! (fn []
                                (switchover/run-switch-over!
                                 {:repo-dir repo-dir
                                  :store-path store-path
                                  :port bound-port}))
                 reboot! (fn reboot! []
                           (when (and (not @rebooting?) @active-sys)
                             (vreset! rebooting? true)
                             (try
                               (let [old @active-sys
                                     next-seq (inc (:boot-seq old 1))]
                                 (println (str "⚡ [POWER LOSS] SIGKILLing children & cold-rebooting Boot #" next-seq " on " store-path "..."))
                                 (flush)
                                 (stop! old {:close-fork-parent? false})
                                 (let [fresh (start-flow-instance! router
                                                                   {:store-path store-path
                                                                    :repo-dir repo-dir
                                                                    :commit-git? commit-git?
                                                                    :auto-switch-over? auto-sw?
                                                                    :listener listener
                                                                    :model-provider (resolve-standing-model-provider prov-opts)
                                                                    :max-os-procs (:max-os-procs options)
                                                                    :max-test-workers (:max-test-workers options)
                                                                    :enable-browser? (not headless?)
                                                                    :boot-seq next-seq
                                                                    :on-power-loss reboot!
                                                                    :on-hand-over hand-over!
                                                                    :on-switch-over switch-over!})]
                                   (vreset! active-sys (assoc fresh :boot-seq next-seq))
                                   (println (str "✓ [BOOT #" next-seq "] Cold reboot complete & serving on port " bound-port))
                                   (flush)))
                               (finally
                                 (vreset! rebooting? false)))))
                 live-provider (resolve-standing-model-provider prov-opts)
                 sys (start-flow-instance! router
                                           {:store-path store-path
                                            :seed-store seed-store
                                            :repo-dir repo-dir
                                            :commit-git? commit-git?
                                            :auto-switch-over? auto-sw?
                                            :listener listener
                                            :model-provider live-provider
                                            :max-os-procs (:max-os-procs options)
                                            :max-test-workers (:max-test-workers options)
                                            :enable-browser? (not headless?)
                                            :boot-seq 1
                                            :on-power-loss reboot!
                                            :on-hand-over hand-over!
                                            :on-switch-over switch-over!})]
             (ensure-boot-state! sys repo-dir)
             (vreset! active-sys (assoc sys :boot-seq 1))
             (.addShutdownHook (Runtime/getRuntime)
                               (Thread. ^Runnable
                                        (fn []
                                          (when-let [cur @active-sys]
                                            (stop! cur {:close-fork-parent? true}))
                                          (when listener
                                            ((:stop! listener))))))
             (println (str "SEON LISTENING http://127.0.0.1:" bound-port
                           " | Model " (if (= :openai-compat (:type live-provider))
                                         (str (:model live-provider)
                                              (if (model/vision-capable-model? (:model live-provider))
                                                " (LIVE streaming + vision)"
                                                " (LIVE streaming)"))
                                         ":built-in (stub)")
                           (if headless?
                             " | Headless"
                             (when (browser/default-binary-path) " | Browser WKWebView"))
                           " | ForkParent PID " (:pid fp)
                           " (GC ceiling " (:ceiling fp) ")"
                           " | Store " store-path))
             (flush)
             (a/<!! exit-ch)
             (System/exit 0))))))))